# syntax=docker/dockerfile:1.7
#
# Multi-stage build for the dropnext-shopify-service (DSS). Multi-arch: amd64 (what we deploy today) and arm64 both work.
# `dnc -e <env> deploy` builds and pushes it (default `--arch amd64`); to build by hand:
#
#   tag=$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short=12 HEAD)-amd64
#   docker buildx build --platform linux/amd64 --build-arg VERSION_TAG=$tag -t dropnext-shopify-service:$tag --load .
#
# VERSION_TAG is the only way the running app learns its version (`/health`, the startup log): `.git/` is not in
# the build context, and `dnc` passes the image tag it pushes, so the app reports the tag ECR knows it by.
#
# The final image runs a jlinked custom JRE built from Amazon Corretto 25
# on top of amazonlinux:2023-minimal (~170MB uncompressed; see the runtime stage for where that goes).


# ---------- Stage 1: Gradle build ----------
# Corretto 25 on Amazon Linux 2023 — multi-arch (amd64 + arm64).
FROM amazoncorretto:25-al2023 AS build

# Tools Gradle needs at runtime: tar/gzip for plugin downloads, findutils for the script wiring.
RUN dnf install -y --setopt=install_weak_deps=False tar gzip findutils \
  && dnf clean all

WORKDIR /src

# The build configuration, then the sources. The dependencies are downloaded by the build step below into the
# BuildKit cache mount, which outlives every layer, so no separate step has to prime it.
COPY gradle ./gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./

# Tests are intentionally not copied — they are excluded via `.dockerignore` and the build runs
# `installDist -x test`. CI runs the suite separately.
# `src/graphql-schema/schema.graphql` is committed, so no introspection is needed at build time.
COPY src ./src

# Produce the runnable distribution at build/install/dropnext-shopify-service/{bin,lib}.
# Tests are skipped here — run them in CI before building the image.
# `--no-daemon` because containers.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
  chmod +x gradlew && ./gradlew --no-daemon --no-configuration-cache installDist -x test


# ---------- Stage 2: jlink a custom JRE ----------
# Same Corretto base so the produced JRE matches the runtime libc (glibc on AL2023).
# Only depends on the JDK's bundled jmods — no need to read the app classpath.
FROM amazoncorretto:25-al2023 AS jlink

# binutils provides `objcopy`, required by `jlink --strip-debug`.
RUN dnf install -y --setopt=install_weak_deps=False binutils && dnf clean all

# Explicit JDK module list rather than `jdeps --print-module-deps`. jdeps fails in mixed
# modular/non-modular classpaths (Ktor + OkHttp + graphql-kotlin pull in plain JARs that
# `--ignore-missing-deps` does not fully cover).
#
# The list was checked against `jdeps -s` over the runtime classpath plus every jar's
# module-info: each module below is statically referenced by our code or a dependency,
# except the last two, which are service providers loaded by name and cannot show up in
# such a scan. Modules that neither check turned up (jdk.crypto.cryptoki, jdk.naming.dns)
# were dropped; jdk.crypto.ec is an empty module since JDK 22, elliptic-curve TLS lives in
# java.base.
#   java.base            — implicit, always present
#   java.instrument      — kotlinx-coroutines (debug agent probe)
#   java.logging         — j.u.l → SLF4J bridge
#   java.management      — JMX, MaxRAMPercentage relies on it for container memory detection
#   java.naming          — logback requires it
#   java.net.http        — JDK HttpClient (our own code)
#   java.xml             — Logback parses `logback.xml` via SAX at JVM startup
#   jdk.unsupported      — sun.misc.Unsafe (Ktor, kotlinx-coroutines, OkHttp)
#   jdk.charsets         — non-default charsets, loaded by name
#   jdk.zipfs            — zip:// NIO filesystem provider, loaded by name
RUN jlink \
  --add-modules java.base,java.instrument,java.logging,java.management,java.naming,java.net.http,java.xml,jdk.unsupported,jdk.charsets,jdk.zipfs \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=zip-9 \
  --output /javaruntime


# ---------- Stage 3: Runtime ----------
# AL2023 minimal is ~100MB uncompressed — over half of the final image — and ships microdnf
# for installing the few extras we need. `2023-minimal` is a rolling tag within the AL2023
# major, so every build picks up the latest patch level; Amazon publishes no newer major yet.
# The image could be slimmed down considerably with Google's `java-base-debian12` distroless
# base (~30MB, made for jlinked runtimes), at the cost of no shell (breaks ECS Exec) and no
# curl for the health check `docker-compose.yml` runs.
# This tag only exists on Amazon ECR Public — Docker Hub has `amazonlinux:2023` (full,
# ~150MB) and `amazonlinux:minimal` (rolling), but not the pinned `2023-minimal` combo.
FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal AS runtime

# curl-minimal: the health check `docker-compose.yml` runs inside the container, and a first tool under ECS Exec.
#   The image declares no HEALTHCHECK of its own: ECS ignores one and probes `/health` through the load balancer
#   (the infra's `app-dss-stack`), and a check baked into the image cannot follow the port `PORT` makes the app bind.
# findutils: provides `xargs`, required by the SSM agent that ECS Exec injects
#   when `enableExecuteCommand` is set on the task. Without it, exec sessions fail
#   with "xargs is not available (exit 1)".
# shadow-utils: useradd. Removed after creating the user to keep the image lean.
RUN microdnf install -y --setopt=install_weak_deps=0 curl-minimal findutils shadow-utils \
  && useradd -r -u 1000 -d /app -s /sbin/nologin dropnext \
  && microdnf remove -y shadow-utils \
  && microdnf clean all \
  && rm -rf /var/cache/yum /var/cache/dnf

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jlink /javaruntime ${JAVA_HOME}
COPY --from=build --chown=dropnext:dropnext /src/build/install/dropnext-shopify-service /app

# JAVA_OPTS is read by the start script the Gradle `application` plugin generates. Not JAVA_TOOL_OPTIONS:
# every JVM reads that one and prints "Picked up JAVA_TOOL_OPTIONS" to stderr for it.
# MaxRAMPercentage lets the JVM size its heap from the container memory limit. ExitOnOutOfMemoryError
# ends the process on the first OutOfMemoryError, so ECS replaces the task instead of keeping one alive
# whose threads died mid-request. Headless avoids accidental AWT init.
# The image tag, handed to the app as its version. `dnc` always passes it; the default marks a hand build that did not.
ARG VERSION_TAG=unspecified
ENV VERSION_TAG=${VERSION_TAG} \
  PORT=9999 \
  JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"

USER dropnext
WORKDIR /app
EXPOSE 9999

ENTRYPOINT ["/app/bin/dropnext-shopify-service"]
