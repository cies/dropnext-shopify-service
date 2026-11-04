# Verification findings: what is still open

Every issue of this round (70–96, as kept by the human) is fixed and was removed, as the workflow asks.
Nothing is left open.
Three issues asked for a decision or research; this is what was done, so it can be overruled.

- **70, research.** Shopify's documentation of the `fulfillment` query lists `read_orders` among the scopes that grant it
  (others: `read_marketplace_orders`, `read_assigned_fulfillment_orders`, `read_merchant_managed_fulfillment_orders`,
  `read_third_party_fulfillment_orders`, …), and lists no further scope for its `events` field.
  So the events read needs nothing the install does not already ask for.
  The `403` path no longer drops the update for good: the monolith replays the newest tracking checkpoint per shipment
  once the store is re-authorized, which the `toDssError` KDoc already says.
- **73, comment style.** The workspace rule (semantic line breaks) was followed over the surrounding files' fill-to-width
  style, in the three KDocs named.
- **96, `isSellable`.** Made an exhaustive `when`, so whoever adds a fifth status has to decide on it.
