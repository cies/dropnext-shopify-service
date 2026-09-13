package dropnext.dss.boot.config


/**
 * How the service is being run, the same two-valued switch as the monolith's `MONOLITH_MODE`. `PROD` is the
 * default because `DEV` is the chattier, less strict one (secure by default); `DEV` is what a developer's `.env`
 * sets to get one log line per HTTP request.
 */
enum class DssMode {
  DEV, PROD;

  val isDev: Boolean get() = this == DEV
  val isProd: Boolean get() = this == PROD

  companion object {
    /** Case-insensitive, `PROD` when unset; anything else is a typo worth failing the boot over. */
    fun parse(raw: String?): DssMode {
      val value = normalizeQuoted(raw) ?: return PROD
      return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: error("DSS_MODE must be DEV or PROD, was '$value'.")
    }
  }
}
