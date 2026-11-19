package ai.jarvis.core

/**
 * The gateway URL the user types in Settings is the single most common source of
 * "Server reachable nahi" errors. Three things go wrong again and again:
 *
 *  1. `/api` is typed twice (`…/api/api` → 404) or the trailing slash is missing.
 *  2. The emulator loopback `10.0.2.2` is used on a *physical* phone, where it points at
 *     nothing (it only exists inside the Android emulator).
 *  3. The value carries whitespace / a trailing `/` from copy-paste.
 *
 * This object is pure (no Android APIs) so it is unit-testable and can be used from both the
 * settings screen and the HTTP layer.
 */
object HostResolver {

    /** Hosts that only resolve inside an emulator / on the same device. */
    private val LOOPBACK_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1", "0.0.0.0", "::1")

    data class Normalized(
        val url: String,
        /** True when the URL itself is fine but only works inside an emulator. */
        val emulatorOnly: Boolean,
        /** Human hint for the UI, null when there is nothing to warn about. */
        val warning: String?,
        val error: String? = null,
    )

    /**
     * Clean a user-typed gateway base URL.
     *
     * @param raw what is in the text field
     * @param isEmulator true when running inside the Android emulator (Build.FINGERPRINT)
     */
    fun normalize(raw: String, isEmulator: Boolean = false): Normalized {
        var s = raw.trim()
        if (s.isEmpty()) return Normalized("", false, null, "Server URL khali hai")

        // A bare host like "192.168.1.5:2785/api" is a very common entry — default to http.
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        // Drop trailing slashes but keep path (the /api prefix is part of OpenWA's routing).
        s = s.trimEnd('/')
        // Collapse a duplicated /api/api and /api/api/api that copy-paste produces.
        while (s.endsWith("/api/api", true)) s = s.dropLast(4)

        val host = runCatching { java.net.URI(s).host?.lowercase() }.getOrNull()
            ?: return Normalized(s, false, null, "URL parse nahi hui — format: http://host:port/api")

        val emulatorOnly = host in LOOPBACK_HOSTS
        val warning = when {
            emulatorOnly && !isEmulator ->
                "$host sirf emulator ke andar kaam karta hai. Phone par apne PC ka LAN IP " +
                    "daalo (jaise http://192.168.1.5:2785/api) aur PC + phone same Wi-Fi par rakho."
            emulatorOnly && isEmulator ->
                "$host emulator loopback hai — emulator me theek hai, asli phone par nahi chalega."
            !s.endsWith("/api") ->
                "URL /api par khatam hona chahiye (jaise ${s.removeSuffix("/")}/api)."
            else -> null
        }
        return Normalized(s, emulatorOnly, warning, null)
    }

    /** Best-effort guess of this device's own LAN address, used to prefill the hint text. */
    fun suggestLanHint(): String =
        "PC ka LAN IP phone ke Wi-Fi se same subnet me hoga, e.g. http://192.168.1.5:2785/api"
}
