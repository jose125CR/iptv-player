package com.lumora.model

/** One configured IPTV source (Xtream/M3U/Stalker). Only one account is active at a
 *  time — switching accounts replaces the entire catalog. Each account carries its own
 *  per-content-type gates (live/movies/series). */
data class AccountConfig(
    val id: String,
    val type: String, // "m3u" | "xtream" | "stalker"
    val name: String,
    // Per-content-type gates: TV (live) / Movies / Series can each be switched off for a
    // single account without touching the others.
    val liveEnabled: Boolean = true,
    val moviesEnabled: Boolean = true,
    val seriesEnabled: Boolean = true,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    // Stalker's MAC address or M3U's custom User-Agent - same slot, different meaning per
    // type, baked onto every Channel this source produces (see Channel.streamUserAgent) so
    // playback sends the right one without needing to know which account a channel came
    // from at that point.
    val userAgent: String? = null
)
