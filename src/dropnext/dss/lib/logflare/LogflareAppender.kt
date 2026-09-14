package dropnext.dss.lib.logflare

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.UnsynchronizedAppenderBase
import dropnext.dss.domain.LogflareApiKey
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


/**
 * A Logback appender that ships structured logging events to Logflare, so DSS lines land beside the
 * monolith's instead of only in the container's stdout. The request's `trace_id` travels in the MDC
 * map, which is what makes a webhook readable across the two services.
 *
 * This half is the field-for-field mapping of a logging event to JSON; [LogflareBatchSender] holds
 * the queue, the schedule, and the shipping. Configuration properties:
 * - `sourceName`: the Logflare source, created through the API when it does not exist yet; when that
 *   API hands out no token (the Logflare inside a local `supabase start`), created under one derived from the name.
 * - `apiKey`: the account key both the handshake and every batch authenticate with.
 * - `service`, `version`: shipped as fields of those names on every event; left out when blank.
 * - `maxBatchSize`: how many events one flush may post.
 * - `maxQueuedEvents`: how many may wait before further ones are dropped.
 * - `flushInterval`: how often the batch is flushed.
 *
 * Attached from `main` rather than `logback.xml`, because the configuration it needs is read from
 * the environment after Logback has already initialized.
 *
 * Written in a Java shape (e.g.: mutable properties, `start()`/`stop()`)
 * because that is the shape Logback constructs and drives.
 */
class LogflareAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {

  var sourceName: String = ""
  var apiKey: LogflareApiKey = LogflareApiKey("")
  var endpoint: String = "https://api.logflare.app"
  var service: String = ""
  var version: String = ""
  var maxBatchSize: Int = 50
  var maxQueuedEvents: Int = 10_000
  var flushInterval: Duration = 1.seconds

  private val running = AtomicBoolean(false)
  private var sender: LogflareBatchSender? = null

  override fun start() {
    if (sourceName.isBlank() || apiKey.value.isBlank()) {
      addError("sourceName and apiKey must be set")
      return
    }
    val sender = LogflareBatchSender(
      endpoint = endpoint,
      apiKey = apiKey,
      maxBatchSize = maxBatchSize,
      maxQueuedEvents = maxQueuedEvents,
      flushInterval = flushInterval,
      constantFields = mapOf("service" to service, "version" to version).filterValues { it.isNotBlank() },
      // Not `addError`: Logback's status manager only reaches registered listeners and `logback.xml`
      // registers none, so a dropped batch or a refused flush would be recorded nowhere.
      // Standard-error is what the console appender shares and what the container captures.
      // Never uses SLF4J: the appender would be shipping its own complaints.
      reportError = ::reportToStandardError,
    )
    this.sender = sender
    // The source-token handshake happens on the flush thread: attaching the appender must not put
    // two blocking calls to Logflare in front of the rest of the boot. Events logged in the
    // meantime queue up and ship with the first flush.
    sender.start(sourceName)
    running.set(true)
    super.start()
  }

  override fun append(event: ILoggingEvent) {
    if (!running.get()) return
    val sender = sender ?: return
    sender.enqueue(entryOf(event))
    if (sender.queuedEventCount >= maxBatchSize) sender.requestFlush()
  }

  override fun stop() {
    running.set(false)
    sender?.close()
    sender = null
    super.stop()
  }
}

private fun reportToStandardError(message: String) {
  System.err.println("[logflare] $message")
}

/** One logging event as the Logflare batch API wants it: a message, a timestamp, and a metadata bag. */
private fun entryOf(event: ILoggingEvent): JsonObject {
  val metadata = buildJsonObject {
    put("level", event.level.toString())
    put("logger", event.loggerName)
    put("thread", event.threadName)
    event.mdcPropertyMap?.forEach { (key, value) -> put(key, value) }
    event.throwableProxy?.let { put("error", ThrowableProxyUtil.asString(it)) }
    event.keyValuePairs?.forEach { pair ->
      when (val value = pair.value) {
        is Number -> put(pair.key, value)
        is Boolean -> put(pair.key, value)
        else -> put(pair.key, value.toString())
      }
    }
  }

  return buildJsonObject {
    put("message", event.formattedMessage)
    put("timestamp", Instant.ofEpochMilli(event.timeStamp).toString())
    put("metadata", metadata)
  }
}
