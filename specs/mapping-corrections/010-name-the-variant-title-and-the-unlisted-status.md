# Spec: name the variant title, and stop calling unlisted products active

Status: draft
Author: cies (with Claude)
Date: 2026-09-16
Depends on: nothing. Independent of everything else in `specs/`.
Repos: DSS only as written. Open question 2 has a variant that needs the monolith first.


## Problem

Two small mappings send Shopify data to the monolith under the wrong name. Neither breaks anything today, and
both put a wrong value in front of a human.

**The variant title is the product title plus the variant.** `mapOrderForMonolith` fills
`snapshot_of_variant_title` from `LineItem.name` and `snapshot_of_product_title` from `LineItem.title`. In
Shopify's schema `title` is the product's title, `name` is "the title of the product combined with the title of
the variant", and `variantTitle` is the variant's own. So for a blue small tee the monolith stores the product
title twice over: `"Classic T-Shirt"` as the product and `"Classic T-Shirt - Blue / S"` as the variant. The
variant's real title, `"Blue / S"`, is never sent, and the column that exists to hold it holds something else.
The two columns are read side by side in the retailer portal, where the repetition is what a reader sees.

**An unlisted product is recorded as active.** Shopify's `ProductStatus` has `ACTIVE`, `ARCHIVED`, `DRAFT` and
`UNLISTED`; the monolith's `product_status_enum` has `Active`, `Draft` and `Archived`. `toProductVariantItems`
maps `UNLISTED` to `ACTIVE`, in the same branch as `__UNKNOWN_VALUE`, under a comment that says only "Better than
an else branch...". `UNLISTED` means the product is published to no sales channel while remaining sellable by
direct link, so calling it active overstates it, and folding an unknown future status into the same branch means
whatever Shopify adds next is recorded as active too.

The field is display-only on our side. `product_status` is read in exactly one place,
`src/dropnext/db/sql/portal/retailer/productsRead.kt`, and rendered as a text detail row on the retailer's
product variant page. Nothing filters, branches or plans on it.


## What changes

- **`src/resources/GetOrderForDss.graphql`** — the line item selection gains `variantTitle` beside `name` and
  `title`. It is a scalar, so it costs nothing.
- **`mapper/mapOrderForMonolith.kt`** — `snapshotOfVariantTitle` is `variantTitle`, falling back to `name` when
  it is absent. `snapshotOfProductTitle` stays `title`. The fallback matters: `variantTitle` is nullable, and a
  line whose variant Shopify has since deleted can carry a null.
- **`mapper/toProductVariantItems.kt`** — `UNLISTED` and `__UNKNOWN_VALUE` stop sharing a branch. `UNLISTED` maps
  as open question 2 decides; `__UNKNOWN_VALUE` maps to `DRAFT`, which claims the least of the three available
  values, and logs one `warn` naming the raw status, because a status this build does not know is a signal that
  the schema has moved.

Neither change touches the contract. `ProductStatus` in the contract keeps its three values unless open question
2 is answered the other way.


## Not changing: the OAuth callback HMAC

An earlier review suggested the OAuth callback HMAC message needed percent-encoding of `%`, `&` and `=` in keys
and values. **Checked against Shopify's current documentation on 2026-09-16: it does not.** The documented
algorithm removes `hmac`, sorts the remaining parameters by key, joins them as `key=value` with `&`, and compares
in constant time. `ShopifyHmacVerifierService.verifyOAuthCallback` already does exactly that, and also drops
`signature`, which is harmless. Recorded here so the next reader does not raise it again.

One real difference remains, and it is not worth changing on its own: for a parameter that appears more than
once, this service takes the first value while Shopify's sample renders the whole array. Shopify does not send a
repeated parameter on the OAuth callback, so the two cannot disagree in practice.


## Behavioral contract

- **Precondition**: an order snapshot that loaded, and a product snapshot that loaded.
- **Postcondition**: `snapshot_of_variant_title` holds the variant's own title whenever Shopify has one, and the
  combined name otherwise. `snapshot_of_product_title` is unchanged.
- **Postcondition**: a product Shopify reports as unlisted is no longer recorded as active, and a status this
  build does not know is recorded as a draft and logged.
- **Invariant**: both are mapping changes with no effect on which orders or products are mirrored, on any status
  code, or on any retry decision.
- **Not retroactive**: the snapshot columns are written once at ingest and never updated, so orders already
  stored keep the titles they were stored with. A product's status is overwritten by the next upsert, so that one
  corrects itself on the next `products/update`.


## Edge cases

- **A product with no options.** Shopify gives its single variant the title `"Default Title"`, so the column
  would read that instead of the product name. Whether that is an improvement is open question 1.
- **A line item with no variant.** Already omitted by the mapper as `NO_VARIANT`, so it never reaches either
  field.
- **A line whose variant was deleted after the order.** `variantTitle` can be null; the fallback to `name` keeps
  the column non-empty, which the contract requires.
- **An archived product that is also unlisted.** Shopify reports one status, so there is nothing to resolve.
- **A status Shopify adds later.** Recorded as a draft with one warn line, rather than silently as active.


## Reuse inventory

- `mapOrderForMonolith` and its `MonolithOrderMapping`, unchanged in shape.
- `toProductVariantItems` and the generated `ProductStatus` enums on both sides.
- `MapOrderForMonolithTest` and `ToProductVariantItemsTest`, which already cover both mappers.
- `OrderFixtures` and `ProductFixtures`, which build the generated types and gain the new field with a default.
- `ShopifyHmacVerifierService`, examined and left alone.


## Test plan

- **Pure** (`MapOrderForMonolithTest`): a line with a variant title maps it; a line whose `variantTitle` is null
  falls back to the combined name; the product title is unchanged in both.
- **Pure** (`ToProductVariantItemsTest`): an unlisted product maps as decided; an unknown status maps to draft
  and logs; active, draft and archived are unchanged.
- **Wire** (`HttpShopifyGraphqlServiceTest`): the order fixture carries `variantTitle` and deserializes.
- No request-to-response test. Neither change alters a status code or a decision, and the two pure tests pin the
  behavior at the only place it exists.


## Open questions for the human developer

1. **Should `"Default Title"` fall back to the combined name?** For a product with no options, the variant title
   Shopify supplies is the literal string `"Default Title"`, which is worse in a portal than the product name.
   Treating it as absent means matching a magic string, which is exactly the kind of thing that rots when Shopify
   changes its wording. The proposal is to accept `"Default Title"` and not match on it, on the grounds that a
   truthful mapping is easier to defend than a special case, and that the product title sits in the next column.
2. **Map `UNLISTED` to `Draft`, or add `Unlisted` to the monolith's enum?** Mapping to `Draft` is DSS-only and
   understates the product mildly. Adding the value is more truthful and is cheaper than it first looks, because
   the evidence above shows the field is display-only and nothing branches on it: a migration extending
   `product_status_enum`, the Kotlin `ProductStatus`, and `DbEnumValidator` agreeing again. It needs a monolith
   spec first and a note that `alter type ... add value` cannot be used in the same transaction that adds it.
   Adding the value is proposed.
