package dropnext.dss.domain


/** What an install came to, in the merchant's terms: the install page tells them this before it shows the report. */
sealed interface ShopInstallOutcome {
  data object Connected : ShopInstallOutcome

  /** The token is saved, but the shop lacks something DropNext needs to act on its orders or products. */
  data class ConnectedWithGaps(
    val missingScopes: List<ShopifyAccessScope>,
    val failedTopics: List<WebhookTopicRegistration>,
  ) : ShopInstallOutcome

  /** The monolith has no store with this Shopify address, so the merchant has to add it before installing again. */
  data object UnknownStore : ShopInstallOutcome

  /** Any other failure to save the token, an unreachable monolith included. */
  data object NotSaved : ShopInstallOutcome
}

/**
 * The saving decides first, as a gap cannot be fixed before the store is connected.
 * The product count and the shop id lookup do not count: the install did not depend on them.
 * Stale and obsolete subscriptions do not either: the registration reports and handles them.
 */
fun ShopInstallReport.outcome(): ShopInstallOutcome {
  val persist = monolithPersist
  if (persist is MonolithPersistOutcome.Failed) {
    return if (persist.storeNotFound) ShopInstallOutcome.UnknownStore else ShopInstallOutcome.NotSaved
  }
  val missingScopes = accessScopes.missing
  val failedTopics = webhooks.failures
  if (missingScopes.isEmpty() && failedTopics.isEmpty()) return ShopInstallOutcome.Connected
  return ShopInstallOutcome.ConnectedWithGaps(missingScopes, failedTopics)
}
