package com.lumora.util

/** Providers are frequently pasted/typed without a scheme ("ip.example.net" instead of
 *  "http://ip.example.net"), which OkHttp rejects outright. Defaults to [defaultScheme]
 *  rather than fail when none's given, and the user can always type a scheme explicitly
 *  to override it. Xtream/Stalker panels are overwhelmingly plain HTTP on a bare LAN IP,
 *  so "http" stays the default for those. */
fun normalizeServerUrl(url: String, defaultScheme: String = "http"): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "$defaultScheme://$trimmed"
}
