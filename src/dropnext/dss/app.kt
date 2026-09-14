package dropnext.dss

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.LoggerContext
import dropnext.dss.boot.config.Config
import dropnext.dss.boot.config.DssMode
import dropnext.dss.boot.config.readDotEnvFile
import dropnext.dss.lib.logflare.LogflareAppender
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory


private val log = KotlinLogging.logger {}

fun main() {
  // The `.env` file overrides the process environment; it only exists in local development.
  val config = Config.fromEnv(readDotEnvFile(File(".env")))
  val logflareAppender = attachLogflareAppender(config)
  logConfigSummary(config)

  // Ktor registers the JVM shutdown hook itself.
  // On SIGTERM the engine drains in-flight requests for the grace period,
  // then raises `ApplicationStopped`, which is where the dependency graph and the appender close.
  embeddedServer(CIO, configure = {
    connector {
      host = "0.0.0.0"
      port = config.serverPort
    }
    // ECS kills a task thirty seconds after its SIGTERM (Fargate's default stop timeout, which the infra keeps). In-flight
    // requests get fifteen of those to finish: a webhook's budget and its write grace fit, and so does a monolith sync, which the
    // monolith stops waiting for at thirty anyway. Five more to cancel what still runs, then the Logflare appender's drain
    // of at most five seconds, and the process is gone before the kill.
    shutdownGracePeriod = 15_000
    shutdownTimeout = 20_000
  }) {
    val deps = dssDependencies(config)
    dssModule(deps, warmUp = deps.warmUp)
    // Subscribed after the module's own close, so the shutdown lines still ship: handlers run in subscription order.
    monitor.subscribe(ApplicationStopped) {
      log.info { "[shutdown] complete" }
      logflareAppender?.stop()
    }
  }.start(wait = true)
}

/**
 * Attaches the Logflare appender now that the environment has been read — `logback.xml` is parsed
 * long before that, so it cannot carry these values. Returns null when the source name or the key
 * is missing, which is how a local run stays on stdout only.
 *
 * Returns quickly: the source-token handshake runs on the appender's own flush thread, not here.
 */
private fun attachLogflareAppender(config: Config): LogflareAppender? {
  if (!config.logflareEnabled) return null
  val sourceName = config.logflareSourceName ?: return null
  val apiKey = config.logflareApiKey ?: return null

  val appender = LogflareAppender().apply {
    this.sourceName = sourceName
    this.apiKey = apiKey
    config.logflareEndpoint?.let { this.endpoint = it }
    // The runtime name every infra identifier uses (`dropnext-<env>-dss`), which is what an operator types.
    service = "dss"
    version = config.versionTag
    context = LoggerFactory.getILoggerFactory() as LoggerContext
  }
  appender.start()
  (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogbackLogger).addAppender(appender)
  log.info { "[logflare] Appender attached (source: $sourceName)." }
  return appender
}

/**
 * Emits a compact summary of the effective runtime configuration at startup so operators can
 * verify env, container, and reverse-proxy expectations without diving into the code.
 */
private fun logConfigSummary(config: Config) {
  log.info { "Starting dropnext-shopify-service ${config.versionTag}" }
  log.info {
    "[http] Listening on 0.0.0.0:${config.serverPort} with DSS_BASE_URL=${config.dssBaseUrl} - " +
      "reverse-proxy target port must equal ${config.serverPort} (unset PORT locally -> 8080; empty PORT in Docker -> 9999)."
  }
  val prefixNote = config.monolithApiPrefix?.let { " MONOLITH_API_PREFIX=$it" }.orEmpty()
  log.info {
    "[monolith] Outbound enabled: MONOLITH_BASE_URL=${config.monolithBaseUrl}$prefixNote " +
      "(DSS_TO_MONOLITH_API_KEY Bearer configured: ${config.dssToMonolithApiKey != null}, " +
      "shops with a seeded token: ${config.shopAccessTokens.size})."
  }
  log.info {
    "[logging] Mode: ${config.mode}, Logflare shipping: ${if (config.logflareEnabled) "on" else "off (stdout only)"}, " +
      "per-request call logging: ${if (config.mode == DssMode.DEV) "on" else "off"}."
  }
}
