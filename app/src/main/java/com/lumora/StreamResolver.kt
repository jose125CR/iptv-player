package com.lumora

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.SidecarSubtitle
import com.lumora.scraper.bridge.ScraperCatalog
import com.lumora.scraper.bridge.ScraperSiteStore
import com.lumora.util.cleanVodTitle
import com.lumora.util.extractYearFromName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val FIND_STREAM_PREFS = "lumora_scraper_prefs"
private const val KEY_LAST_GOOD = "last_good_source"

/**
 * How long a started source has to produce something before it is abandoned for the next one.
 *
 * Generous on purpose: a slow host can take this long just handing over a playlist, so a tighter
 * bound would drop working sources.
 */
private const val STALL_TIMEOUT_MS = 20_000L

/**
 * Find Stream: one search across every source Lumora has, played best-first without asking.
 *
 * The sources are the built-in site scrapers (see [ScraperCatalog]).
 *
 * **A source that played last time wins** - a setup that works should keep working rather than
 * re-deriving the answer - then quality tier, then seeders as the tie-break inside a tier.
 *
 * Nothing is presented for approval and there is no picker. The queue is walked from the top, a
 * source that fails to resolve is skipped silently - a dead host is the normal case, not an error
 * - and the dialog only reports what it is doing. Which embed host or release a stream came from
 * is not a choice anyone can make usefully: the names are interchangeable and nothing on screen
 * predicts which will work, so the app tries them instead of asking.
 */
internal class StreamResolver(
    private val activity: MainActivity,
    private val item: Channel,
    private val season: Int?,
    private val episode: Int?,
    /**
     * When set, the resolved stream is handed to the caller instead of being played - the Download
     * button uses it to find a source for a title that has none yet. The [Channel] carries the
     * resolved url and headers; nothing is started, so no watchdog is armed.
     */
    private val onResolved: ((Channel) -> Unit)?,
) {
    /**
     * Searches every enabled source for [item] and starts playing the best one that resolves.
     *
     * [season]/[episode] pick the episode of a series; without them sources are asked for S01E01,
     * the only sane default when the caller does not know.
     */
    fun start() {
        val siteProviders = ScraperSiteStore.activeProviders(activity)
        if (siteProviders.isEmpty()) {
            activity.scraperToast(activity.getString(R.string.scraper_no_sources))
            return
        }

        val isSeries = item.mediaType == MediaType.SERIES
        val epTag = if (season != null && episode != null) " S%02dE%02d".format(season, episode) else ""
        // What every source is searched for. Kept out of the display strings below on purpose: the
        // dialog title and the resolved item keep the catalog's own name, so the user still sees the
        // title they picked rather than the stripped-down query.
        val searchTitle = streamSearchTitle(item.name)
        val searchYear = streamSearchYear(item)
        val prefs = activity.getSharedPreferences(FIND_STREAM_PREFS, Context.MODE_PRIVATE)
        val lastGood = prefs.getString(KEY_LAST_GOOD, null)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val status = TextView(activity).apply {
            text = activity.getString(R.string.scraper_finding_sources)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }
        val detail = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 12f
            visibility = View.GONE
        }
        container.addView(status)
        container.addView(detail)

        val found = mutableListOf<FoundSource>()
        val tried = mutableSetOf<String>()
        // Cleared once a source actually renders. Until then a started source is still on trial.
        var watchdog: Runnable? = null
        // tryNext() is declared below startPlayback (it calls it), so the watchdog reaches it through
        // this rather than by forward reference, which a local fun cannot do.
        var advance: (() -> Unit)? = null
        var watching: androidx.media3.common.Player.Listener? = null
        var trying = false
        var searchesDone = 0
        var attempts = 0
        var playbackStarted = false
        lateinit var searchJob: Job

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.scraper_dialog_title, item.name + epTag))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!playbackStarted) searchJob.cancel() }
            .create()
        // No "pick a source" button. An embed host or release name is not a decision anyone can make
        // usefully - the names are interchangeable and nothing on screen predicts which will work -
        // so the dialog only ever reports progress while the ranked queue is walked. Cancel is the
        // one control, and it stops the search.

        /** Stops watching the current attempt, whether it succeeded or is being abandoned. */
        fun clearWatch() {
            watchdog?.let { activity.mainHandler.removeCallbacks(it) }
            watchdog = null
            watching?.let { activity.playerManager.getExoPlayer().removeListener(it) }
            watching = null
        }

        /**
         * This attempt is producing something. The search can stop and the queue can be let go.
         *
         * Deliberately not done at the moment playback is *started* - that was the old behaviour and
         * it is what let a source that never delivered a frame halt everything, since the queue had
         * already been thrown away by then.
         */
        fun confirmPlayback() {
            if (watchdog == null && watching == null) return
            clearWatch()
            searchJob.cancel()
        }

        fun startPlayback(source: FoundSource, url: String, headers: Map<String, String>,
                          subtitles: List<SidecarSubtitle>, mimeType: String?,
                          maintainTokenQuery: String? = null) {
            playbackStarted = true
            prefs.edit().putString(KEY_LAST_GOOD, source.key()).apply()
            dialog.dismiss()

            val resolved = item.copy(
                id = item.id + epTag,
                name = item.name + epTag,
                url = url,
                streamHeaders = headers.ifEmpty { null },
                episodeNum = episode,
            )
            if (onResolved != null) {
                // Resolve-only: nothing is playing, so there is no first frame to wait for and no
                // watchdog to arm. The search is stopped here instead.
                searchJob.cancel()
                onResolved?.invoke(resolved)
                return
            }

            activity.hideContentDetail()
            activity.showPlayerFor(
                item.copy(
                    id = item.id + epTag,
                    name = item.name + epTag,
                    url = url,
                    streamHeaders = headers.ifEmpty { null },
                    episodeNum = episode,
                ),
                externalSubtitles = subtitles.map { activity.externalSubtitleFor(it) },
                mimeType = mimeType,
                maintainTokenQuery = maintainTokenQuery,
            )
            activity.detailReturnItem = item

            // A source that resolves to a URL can still never deliver a frame - a signed segment
            // that 403s makes ExoPlayer retry forever without raising an error, which shows up as a
            // spinner that never resolves. Nothing in the resolve path can see that, so the queue is
            // held open until this attempt proves itself and abandoned if it does not.
            clearWatch()
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onRenderedFirstFrame() = confirmPlayback()
                override fun onPlaybackStateChanged(state: Int) {
                    // STATE_READY as well as a rendered frame: an audio-only or still-image stream
                    // never renders one, and is not a stall.
                    if (state == androidx.media3.common.Player.STATE_READY) confirmPlayback()
                }

                /**
                 * A source that fails outright is known bad immediately - a 500 from the CDN, a dead
                 * host - and there is no reason to sit out the stall timeout before moving on.
                 * Measured on device: a 500 arrived 3.4s in, and the queue waited the full 20s.
                 *
                 * Only errors during the trial window reach here; [confirmPlayback] removes this
                 * listener once the source has proved itself, so a mid-film failure much later is
                 * left to the player's own error handling rather than silently switching source.
                 */
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (!activity.isPlayerVisible) return
                    clearWatch()
                    playbackStarted = false
                    trying = false
                    advance?.invoke()
                }
            }
            watching = listener
            activity.playerManager.getExoPlayer().addListener(listener)
            val timeout = Runnable {
                // Left the player, or it recovered on its own - either way this is not ours to act on.
                if (!activity.isPlayerVisible || activity.playerManager.playbackState == androidx.media3.common.Player.STATE_READY) {
                    confirmPlayback()
                    return@Runnable
                }
                clearWatch()
                playbackStarted = false
                trying = false
                advance?.invoke()
            }
            watchdog = timeout
            activity.mainHandler.postDelayed(timeout, STALL_TIMEOUT_MS)
        }

        /** Resolves the best untried source, and on failure the next best. */
        fun tryNext() {
            if (trying || playbackStarted) return
            val next = found.sortedWith(ranking(lastGood)).firstOrNull { it.key() !in tried }
            if (next == null) {
                if (searchesDone >= 1) status.text = activity.getString(R.string.scraper_no_sources)
                return
            }
            trying = true
            tried += next.key()
            attempts++
            status.text = activity.getString(R.string.scraper_opening_source, next.title)
            detail.visibility = View.VISIBLE
            detail.text = activity.getString(R.string.scraper_attempt_detail, next.detail, attempts)

            activity.scope.launch {
                val video = ScraperCatalog.resolve(next.source)
                trying = false
                if (playbackStarted) return@launch
                if (video == null || video.source.isBlank()) {
                    tryNext()
                } else {
                    startPlayback(
                        next, video.source, video.headers.orEmpty(),
                        video.subtitles.map {
                            SidecarSubtitle(it.file, it.label, null, it.default)
                        },
                        video.type?.takeIf { it.isNotBlank() }
                            ?: hlsMimeIfLooksLikeHls(video.source),
                        // Hosts that sign the playlist and expect the same token on every
                        // segment. Without this the playlist loads, every segment 403s, and
                        // ExoPlayer retries silently - which shows up as a spinner that never
                        // resolves rather than as an error.
                        maintainTokenQuery = video.source
                            .takeIf { video.maintainToken }
                            ?.substringAfter('?', "")
                            ?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }

        advance = ::tryNext

        fun onFound(source: FoundSource) {
            activity.runOnUiThread {
                if (playbackStarted) return@runOnUiThread
                found += source
                status.text = activity.resources.getQuantityString(
                    R.plurals.scraper_sources_found, found.size, found.size
                )
                tryNext()
            }
        }

        dialog.show()

        searchJob = activity.scope.launch {
            // A cancelled search (dialog dismissed) must stay cancelled - rethrowing
            // CancellationException keeps the trailing searchesDone++/tryNext() from
            // running after searchJob.cancel(), which could otherwise start playback
            // for a dialog the user already closed.
            try {
                ScraperCatalog.findSources(
                    providers = siteProviders,
                    title = searchTitle,
                    year = searchYear,
                    isSeries = isSeries,
                    season = season,
                    episode = episode,
                    isAnime = item.id.startsWith("anime:"),
                    onProgress = { searched, total ->
                        activity.runOnUiThread {
                            if (found.isEmpty()) {
                                status.text =
                                    activity.getString(R.string.scraper_searching_sites, searched, total)
                            }
                        }
                    },
                    onSources = { list -> list.forEach { onFound(FoundSource(it)) } },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Swallowed exactly as the old runCatching did; the queue is advanced by
                // tryNext() below regardless of a single search's failure.
            }
            activity.runOnUiThread { searchesDone++; tryNext() }
        }
    }
}

/** One playable candidate. */
internal data class FoundSource(val source: ScraperCatalog.Source) {
    val title: String get() = source.server.name
    // A site link carries no release metadata, so it gets the neutral baseline rather than
    // being guessed at from a host name that says nothing about the encode.
    val score: Int get() = StreamQuality.UNKNOWN
    val seeders: Int get() = 0
    /** Short provenance line, shown under the status while this source is being tried. */
    val detail: String
        get() = listOfNotNull(
            source.providerName,
            runCatching { android.net.Uri.parse(source.server.src).host }.getOrNull(),
            source.matchedTitle,
        ).joinToString("  ·  ")
}

/** Stable key for the last-good-source memory. */
internal fun FoundSource.key(): String = "site:${source.providerName} · ${source.server.name}"

/**
 * Quality/codec/source words a panel bolts onto a VOD title with no delimiter in front of them
 * ("Dune 4K", "Oppenheimer 1080p MULTI"). [cleanVodTitle] only peels tags that are *delimited*
 * ("4K - Dune") or bracketed ("[4K]"), because for display that is the safe rule - an undelimited
 * word may be part of the name. For a search query the trade runs the other way: a site's match
 * is exact-normalised-equality, so one leftover token is the difference between every site
 * carrying the film and none of them.
 */
internal val SEARCH_NOISE_TOKENS = setOf(
    "4k", "uhd", "fhd", "hd", "sd", "hq", "hdr", "hdr10", "dv", "dolby", "atmos",
    "hevc", "h264", "h265", "x264", "x265", "av1", "10bit", "8bit",
    "web", "webdl", "webrip", "bluray", "brrip", "bdrip", "hdrip", "dvdrip", "cam", "ts",
    "multi", "raw", "dub", "dubbed", "sub", "subbed", "subs", "vostfr", "vf", "vo",
    "imax", "remux", "extended", "repack", "proper",
)

/** "1080p", "2160i", "720P" - a resolution tag, which is noise in a search query too. */
internal val SEARCH_NOISE_RES_REGEX = Regex("""^\d{3,4}[pi]$""", RegexOption.IGNORE_CASE)

internal fun isSearchNoise(token: String): Boolean {
    val t = token.trim('-', '.', '_', '·', '|').lowercase()
    return t.isNotEmpty() && (t in SEARCH_NOISE_TOKENS || SEARCH_NOISE_RES_REGEX.matches(t))
}

/**
 * The title a source is actually asked for: the film/show's own name, nothing else.
 *
 * [cleanVodTitle] does the delimited/bracketed prefixes; this then eats undelimited quality
 * noise off both ends and drops a "(YYYY)" suffix, since the year is passed to both search APIs
 * as its own argument and leaving it in the string only breaks the exact match. Stripping stops
 * while one token is left, so a film genuinely named "Raw" or "Cam" survives being all-noise.
 */
internal fun streamSearchTitle(name: String): String {
    var tokens = cleanVodTitle(name)
        // The year is carried separately - see streamSearchYear.
        .replace(Regex("""\(\s*(19|20)\d{2}\s*\)"""), " ")
        .split(' ')
        .filter { it.isNotBlank() }
    while (tokens.size > 1 && isSearchNoise(tokens.last())) tokens = tokens.dropLast(1)
    while (tokens.size > 1 && isSearchNoise(tokens.first())) tokens = tokens.drop(1)
    return tokens.joinToString(" ").ifBlank { cleanVodTitle(name) }
}

/** The year to search under: what the catalog states, else the one baked into the title that
 *  [streamSearchTitle] just removed - dropping it there must not lose it here. */
internal fun streamSearchYear(item: Channel): String? =
    item.year?.takeIf { it.isNotBlank() } ?: extractYearFromName(item.name)

/** True when anything at all can source a stream for [item]. */
internal fun MainActivity.canFindStream(item: Channel): Boolean =
    (item.mediaType == MediaType.MOVIE || item.mediaType == MediaType.SERIES) &&
        scrapersUsable()

/**
 * Best-first ordering.
 *
 * **A source that played last time wins** - a setup that works should keep working rather than
 * re-deriving the answer - then quality tier, then seeders as the tie-break inside a tier.
 */
internal fun ranking(lastGood: String?): Comparator<FoundSource> =
    compareByDescending<FoundSource> { it.key() == lastGood }
        .thenByDescending { it.score }
        .thenByDescending { it.seeders }
