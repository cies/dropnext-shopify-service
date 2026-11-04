package dropnext.dss.workflow

import dropnext.dss.mapper.MonolithProductMapping
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean


private val log = KotlinLogging.logger {}

/**
 * Warns once per process that Shopify sent a product status this build does not know.
 * Shopify gives a new status to many products at once, but what needs a human is the schema moving, and that happens once.
 * [apiVersion] is the version this build speaks, where the schema bump starts from.
 * `dssDependencies` builds the one instance; a test builds its own.
 */
class UnknownProductStatusWarning(private val apiVersion: String) {
  private val warned = AtomicBoolean(false)

  fun warnIfUnknown(productId: String, mapping: MonolithProductMapping) {
    if (!mapping.hasUnknownStatus || !warned.compareAndSet(false, true)) return
    log.warn {
      "Shopify product status unknown to this build, mapped to draft; later products are not logged " +
        "product_id=$productId api_version=$apiVersion"
    }
  }
}
