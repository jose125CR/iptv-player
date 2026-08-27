package com.lumora

import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.WatchedStore
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.util.cleanVodTitle
import com.lumora.util.normalizeTitleForGrouping
import kotlinx.coroutines.launch

// ── Watched state ──
//
// A watched mark is stored under a key derived from the title and season/episode
// numbers. Reads consult the per-copy position entry first and the shared mark second.

/**
 * The provider-independent key for [item], or null when one can't be built.
 *
 * Uses [normalizeTitleForGrouping] - the same normalisation that decides two catalogue entries
 * are the same title when duplicate versions are grouped - so "4K-AMZ - SAS Rogue Heroes" and
 * a "SAS Rogue Heroes" land on one key.
 *
 * Null for anything that isn't a single watchable title: a top-level series entry (the series
 * as a whole is not a thing you finish), a live channel, or an episode whose series name or
 * numbering can't be determined. Callers fall back to per-copy state, which is exactly the old
 * behaviour.
 */
internal fun MainActivity.watchedKeyFor(item: Channel): String? = when (item.mediaType) {
    MediaType.LIVE -> null
    MediaType.MOVIE -> movieWatchedKey(item.name)
    // Season-less numbering still keys consistently across providers as long as every copy
    // agrees there is no season, which is the case for the flat-numbered anime/documentary
    // strands that omit it.
    MediaType.SERIES -> episodeWatchedKey(
        seriesTitleForEpisode(item), tileSeasonNumber(item), item.episodeNum
    )
}

/** [watchedKeyFor] for a film, from its raw catalogue title. */
internal fun movieWatchedKey(title: String): String? =
    normalizeForWatchedKey(title)?.let { "m|$it" }

/**
 * [watchedKeyFor] for an episode, from the parts rather than a [Channel].
 */
internal fun episodeWatchedKey(seriesName: String?, season: Int?, episode: Int?): String? {
    if (episode == null) return null
    val normalized = normalizeForWatchedKey(seriesName ?: return null) ?: return null
    return "e|$normalized|s${season ?: 0}|e$episode"
}

private fun normalizeForWatchedKey(title: String): String? {
    val normalized = normalizeTitleForGrouping(cleanVodTitle(title))
    if (normalized.isBlank()) return null
    // A newline would corrupt the store's line-per-key file; '|' is this key's own separator.
    if (normalized.any { it == '\n' || it == '\r' || it == '|' }) return null
    return normalized
}

/**
 * The show an episode belongs to, by name.
 *
 * Preferred source is the catalogue entry the episode's parent id points at, because that is
 * the show's real title. Falling back to the episode's own name only works for the providers
 * that bake the series into it, and the marker/title decoration has to come off first or two
 * copies of one show would normalise differently.
 */
private fun MainActivity.seriesTitleForEpisode(item: Channel): String? {
    if (item.episodeNum == null) return null
    resolveHomeTileSeries(item)?.let { return cleanVodTitle(it.name) }
    // "SAS Rogue Heroes - S03E03 - Title" / "S03E03 · Title" / "Series · Title": drop the
    // marker and anything after it, leaving the leading show name if there is one.
    val name = cleanVodTitle(item.name)
    val marker = ANY_SEASON_MARKER_REGEX.find(name) ?: return null
    val prefix = name.substring(0, marker.range.first).trim().trim('-', '·', ':').trim()
    return prefix.ifBlank { null }
}

/**
 * Whether [item] has been watched, by any copy of it on any provider.
 *
 * The per-copy position entry is checked first: it is the authoritative answer for the exact
 * file that played, and it is what a provider with no resolvable title key still relies on.
 */
internal fun MainActivity.isItemWatched(item: Channel): Boolean {
    val key = item.id.ifBlank { item.url }
    if (key.isNotBlank() && PlaybackPositionStore.get(this, key)?.isNearComplete == true) return true
    val shared = watchedKeyFor(item) ?: return false
    return WatchedStore.isWatched(this, shared)
}

/**
 * Records [item] as watched (or not).
 *
 * The per-copy position entry is written or cleared, and the shared title key is set so every
 * other copy in the catalogue reads the new state immediately.
 */
internal fun MainActivity.setItemWatched(
    item: Channel,
    watched: Boolean,
    alsoPushToServers: Boolean = true
) {
    val copyKey = item.id.ifBlank { item.url }
    if (copyKey.isNotBlank()) {
        val existing = PlaybackPositionStore.get(this, copyKey)
        when {
            // Already finished from real playback - leave the entry alone. Overwriting it
            // with the stub below would throw away the true duration for no gain, and this
            // path runs on every teardown of an already-watched title.
            watched && existing?.isNearComplete == true -> Unit
            // Duration is unknown for something never played; 1ms of 1ms clears the
            // completion threshold, which is what the episode row already did by hand.
            watched -> PlaybackPositionStore.save(this, copyKey, 1L, 1L, item)
            else -> PlaybackPositionStore.clear(this, copyKey)
        }
    }
    val shared = watchedKeyFor(item)
    if (shared != null) {
        WatchedStore.setWatched(this, shared, watched)
        clearSiblingCopyPositions(item, shared)
    }
    if (alsoPushToServers) {
        // Trakt gets the same mark, under its own toggle. A play that went through the player
        // was already scrobbled; Trakt de-duplicates a history add against that scrobble, so
        // the overlap costs a request rather than a duplicate entry.
        pushWatchedToTrakt(item, watched)
    }
}

/**
 * Drops every *other* catalogue copy's position entry for the same title.
 *
 * Needed in both directions, for opposite reasons. Marking watched: a sibling's half-finished
 * resume point is moot now the title has been seen, and leaving it would keep that copy sitting
 * in Continue Watching. Marking unwatched: a sibling's near-complete entry would out-vote the
 * cleared shared mark, because [isItemWatched] consults the per-copy entry first.
 */
private fun MainActivity.clearSiblingCopyPositions(item: Channel, sharedKey: String) {
    val ownKey = item.id.ifBlank { item.url }
    for (candidate in allChannels) {
        if (candidate.mediaType != item.mediaType) continue
        val key = candidate.id.ifBlank { candidate.url }
        if (key.isBlank() || key == ownKey) continue
        if (PlaybackPositionStore.get(this, key) == null) continue
        if (watchedKeyFor(candidate) == sharedKey) PlaybackPositionStore.clear(this, key)
    }
}

