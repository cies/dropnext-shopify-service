package dropnext.dss.lib.shopify.oauth

import dev.forkhandles.result4k.Result
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import java.time.Instant


/**
 * The three pre-token OAuth operations the installation flow needs, per app: the authorize redirect, the
 * signed `state` that defends the callback against CSRF and replay, and the code exchange.
 * [HttpShopifyOAuthService] talks to Shopify; `FakeShopifyOAuthService` (in `test/`) answers what a test
 * configured, so a callback test needs no fake Shopify server just to sign a state.
 */
interface ShopifyOAuthService {
  /** The URL a merchant is redirected to so they grant the app access, carrying the signed [state]. */
  fun authorizeUrl(shop: ShopDomain, state: String): String

  /** A short-lived, signed `state` for [shop]; [now] is a parameter so a test can sign in the past. */
  fun signedState(shop: ShopDomain, now: Instant = Instant.now()): String

  /** Whether [state] was produced by [signedState] for [expectedShop] and has not expired at [now]. */
  fun isSignedStateValid(state: String, expectedShop: ShopDomain, now: Instant = Instant.now()): Boolean

  /** Trades the authorization [code] Shopify sent to the callback for the shop's long-lived Admin token and the scopes granted with it. */
  suspend fun exchangeCode(shop: ShopDomain, code: String): OAuthResult<ShopifyAccessGrant>
}

typealias OAuthResult<T> = Result<T, OAuthError>

/** What a code exchange yields. A merchant can grant less than the install asked for, so the scopes travel with the token. */
data class ShopifyAccessGrant(val token: ShopifyAdminToken, val scopeHandles: List<String>)

/**
 * Why a code exchange produced no token. Two shapes because the callback answers them differently: a
 * refused code is the merchant's to redo (start the install again, a `4xx`), no answer is upstream (a `502`).
 */
sealed interface OAuthError {
  val message: String

  /** Shopify answered a `4xx`: the code was used, expired, or issued to another app. Only a new install fixes it. */
  data class CodeRejected(val httpStatus: Int) : OAuthError {
    override val message: String get() = "Shopify refused the authorization code (HTTP $httpStatus)"
  }

  /** No usable answer: a transport failure, a `5xx`, or a `2xx` body that is not a token. */
  data class Transport(override val message: String) : OAuthError
}
