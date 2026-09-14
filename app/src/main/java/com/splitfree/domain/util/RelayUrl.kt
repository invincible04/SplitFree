package com.splitfree.domain.util

import java.net.URI
import java.net.URISyntaxException

/** Canonical form of relay URLs typed by the user. */
object RelayUrl {
    private const val SCHEME = "wss"

    /**
     * Canonical form of a user-typed relay URL, or null when it is not a usable `wss://` URL.
     *
     * Scheme and host are case-insensitive and are lowercased; userinfo, port, path, query and fragment are
     * kept byte-for-byte, percent-encoding included. A bare authority with a root path (`wss://host/`) drops
     * the slash so it matches the bare form; any other path is preserved exactly, including a trailing slash,
     * because `/room/` and `/room` may be different endpoints.
     *
     * The host must parse as a DNS name or IP literal; an internationalised host must be typed as punycode.
     * Surrounding whitespace is ignored.
     */
    fun normalize(input: String): String? {
        val uri = try {
            URI(input.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme?.lowercase() != SCHEME) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery
        val fragment = uri.rawFragment
        return buildString {
            append(SCHEME).append("://")
            uri.rawUserInfo?.let { append(it).append('@') }
            append(host)
            if (uri.port != -1) append(':').append(uri.port)
            if (path != "/" || query != null || fragment != null) append(path)
            query?.let { append('?').append(it) }
            fragment?.let { append('#').append(it) }
        }
    }
}
