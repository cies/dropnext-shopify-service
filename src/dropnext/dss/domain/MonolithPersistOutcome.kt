package dropnext.dss.domain


/**
 * Outcome of attempting to persist the freshly obtained Shopify Admin token to the monolith
 * during OAuth installation. The view renders one of these as a success / error notice.
 */
sealed interface MonolithPersistOutcome {
  data class Persisted(val storeId: StoreId) : MonolithPersistOutcome

  /**
   * [httpStatus] is `null` when the monolith never answered (a transport failure).
   * [detail] is the monolith's own message, and only that: the page shows it to the merchant,
   * while a proxy's page or a transport's host and port stay in the log.
   * [storeNotFound] is the monolith saying it has no such store, which a router's `404` is not.
   */
  data class Failed(val httpStatus: Int?, val detail: String?, val storeNotFound: Boolean) : MonolithPersistOutcome
}
