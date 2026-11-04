package dropnext.dss.lib.crypto

import java.security.MessageDigest


/**
 * Names an Admin token (its value) without revealing it: the first 16 lowercase hexadecimal characters of the SHA-256 of its UTF-8
 * bytes. The monolith computes the same of the token it holds, so an access problem report can say which token Shopify
 * refused, and a report about a token a reinstall has replaced since is not taken for news.
 */
fun shopifyTokenFingerprint(token: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
    .take(16)
