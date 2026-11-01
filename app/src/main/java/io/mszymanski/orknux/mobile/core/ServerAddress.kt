package io.mszymanski.orknux.mobile.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddress {

    /**
     * What was typed, as a base URL, or null when it cannot be one.
     *
     * With no scheme it is https, unless it names this device or a private
     * network, where a self-hosted server usually has no certificate.
     */
    fun normalise(typed: String): String? {
        val text = typed.trim().trimEnd('/')
        if (text.isEmpty()) return null
        val withScheme = when {
            "://" in text -> text
            isLocal(text.substringBefore('/').substringBefore(':')) -> "http://$text"
            else -> "https://$text"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return url.toString().trimEnd('/')
    }

    /** Whether a password sent to this address would cross a network unencrypted. */
    fun isExposed(base: String): Boolean {
        val url = base.toHttpUrlOrNull() ?: return false
        return !url.isHttps && url.host !in LOOPBACK
    }

    private fun isLocal(host: String): Boolean =
        host in LOOPBACK || host.endsWith(".local") || host.startsWith("10.") ||
            host.startsWith("192.168.") || PRIVATE_172.matches(host)

    private val LOOPBACK = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val PRIVATE_172 = Regex("""172\.(1[6-9]|2\d|3[01])\..*""")
}
