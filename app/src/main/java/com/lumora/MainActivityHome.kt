package com.lumora

import com.lumora.cache.FavoritesStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import com.lumora.parser.XtreamClient
import com.lumora.util.cleanVodTitle
import com.lumora.util.isAdultCategory
import kotlinx.coroutines.*

// ── Home shelves, Continue Watching / Up Next tiles & TMDB episode merge ──
//
// Extracted from MainActivityDiscover.kt before that file's removal: everything here serves
// the Home tab and the Series/Films shelves, none of it the Discover pane itself.

internal fun MainActivity.onHomeItemClick(channel: Channel) {
    // User-initiated play - see playItem for why the suppression flag is cleared here.
    skipResumePrompt = false
    when (channel.mediaType) {
        MediaType.LIVE -> playItem(channel)
        MediaType.MOVIE -> {
            currentIndex = filmList.indexOf(channel)
            showPlayerFor(channel)
            // Back out to the film's own poster, same as playing it from its detail page.
            detailReturnItem = channel
        }
        MediaType.SERIES -> {
            // An episode tile (Continue Watching, Next Up, or a synthesized up-next tile)
            // carries an episode number; clicking it lands on the series' detail page -
            // the season chip lands on the episode's season and the Play button already
            // points at the next-unwatched episode - rather than playing that episode
            // outright. Landing on the poster is the point: the show is there to be picked
            // through, and one wrong click on a row full of episodes used to start
            // playback. A top-level series entry (Favorites, category grids) has no
            // episode number and goes to the detail page as normal. url is NOT a reliable
            // discriminator - catalog series items can carry one. If the episode's series
            // can't be resolved there is no poster to open, so it plays directly.
            if (channel.episodeNum != null) {
                val series = resolveHomeTileSeries(channel)
                if (series != null) {
                    showContentDetail(series)
                    return
                }
                showPlayerFor(channel)
                // A lone episode has no queue behind it - nothing would auto-advance when
                // it ends. An up-next tile already had its cross-season chain built during
                // the fetch that resolved it; anything else back-fills the same chain the
                // detail page plays from.
                val upNextQueue = upNextQueues[channel.id.ifBlank { channel.url }]
                if (upNextQueue != null) {
                    val index = upNextQueue.indexOfFirst {
                        it.id.ifBlank { it.url } == channel.id.ifBlank { channel.url }
                    }
                    currentEpisodeQueue = upNextQueue
                    currentEpisodeQueueIndex = if (index >= 0) index else 0
                } else {
                    populateHomeTileEpisodeQueue(channel)
                }
            } else {
                showContentDetail(channel)
            }
        }
    }
}

/** Resolves the series a Home-tile episode belongs to: exact categoryId (the series id
 *  Xtream parseEpisode and the Jellyfin/Plex toChannel both stamp on episodes) match through the
 *  catalog first, then the "{series} · {episode}" name-prefix fallback for snapshots that
 *  predate categoryId. Null if unresolvable - callers fall back to direct play. */
internal fun MainActivity.resolveHomeTileSeries(channel: Channel): Channel? {
    // Exact series-id match. Ids are provider-scoped (Xtream series id, Jellyfin item id,
    // Plex rating key), so cross-matching is impossible - matching the source flags is the
    // only guard needed, with sourceProviderId compared only when the snapshot carries one
    // (older saves don't).
    channel.categoryId?.takeIf { it.isNotBlank() }?.let { id ->
        allChannels.firstOrNull {
            it.mediaType == MediaType.SERIES && it.id == id &&
                it.isJellyfin == channel.isJellyfin && it.isPlex == channel.isPlex &&
                (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
        }?.let { return it }
    }
    // Name-prefix fallback for old snapshots: "Series Name · S01E02 · Title", longest
    // name wins. Same-provider guard only when the snapshot knows its provider.
    return allChannels
        .filter {
            it.mediaType == MediaType.SERIES &&
                it.isJellyfin == channel.isJellyfin && it.isPlex == channel.isPlex &&
                (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
        }
        .filter { it.name.isNotBlank() && channel.name.startsWith(it.name + " · ") }
        .maxByOrNull { it.name.length }
}

/** A Home tile can be one episode standing alone (Continue Watching, Jellyfin Next Up),
 *  played with no queue - so when it ends nothing auto-advances. Back-fill the series'
 *  full episode chain (all seasons, season-major then episode-major, the order the detail
 *  page plays) and index it from the played episode. Any failure leaves the queue empty,
 *  which is exactly what happened before this existed. */
internal fun MainActivity.populateHomeTileEpisodeQueue(channel: Channel) {
    val playedId = channel.id
    if (playedId.isBlank()) return
    // A media server's chain comes from the server itself (getEpisodes/getSeasons), not
    // Xtream getSeriesFull - and those tiles resolve to the series detail page anyway, so
    // this fallback never needs to build a Jellyfin or Plex queue.
    if (channel.isOwnLibrary) return
    scope.launch {
        val ordered = withContext(Dispatchers.IO) {
            val seriesId = channel.categoryId ?: return@withContext emptyList<Channel>()
            val client = XtreamClient(BaseApplication.instance.okHttpClient)
            // Seasons arrive season-major already; sort each season's episodes by
            // episode number, then flatten into the cross-season chain.
            client.getSeriesFull(xtreamProviderFor(channel) ?: provider, seriesId).seasons
                .flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
        }
        // Don't clobber a queue belonging to whatever is playing now if the user moved on
        // while the fetch was in flight.
        if (nowPlayingChannel?.id != playedId) return@launch
        val index = ordered.indexOfFirst { it.id == playedId }
        if (index >= 0) {
            currentEpisodeQueue = ordered
            currentEpisodeQueueIndex = index
        }
    }
}

internal fun MainActivity.getHiddenHomeShelves(): MutableSet<String> =
    prefs.getStringSet("hidden_home_shelves", emptySet())?.toMutableSet() ?: mutableSetOf()

internal fun MainActivity.toggleHiddenHomeShelf(title: String) {
    val hidden = getHiddenHomeShelves()
    if (!hidden.remove(title)) hidden.add(title)
    prefs.edit().putStringSet("hidden_home_shelves", hidden).apply()
    homeShelfAdapter.submitList(buildHomeShelves())
}

/** X on the "Continue Watching" shelf clears the resume data itself, not just hides the
 *  shelf on the tab it was pressed on. Home, Series and Films all read the same store, so
 *  one clear empties the row everywhere. Media-server resume lives on the server, so those
 *  entries are dropped there too (best effort) and removed from memory immediately. Also
 *  un-hides the CW shelf so future watching isn't stuck behind a stale hide flag. */
internal fun MainActivity.clearContinueWatching() {
    PlaybackPositionStore.clearAll(this)
    clearUpNextMemo()
    // Grouped by the account each entry came from: with several media servers configured, an
    // item id only means anything to its own server, and clearing it against another would at
    // best 404 and at worst clear an unrelated title that happens to share the id.
    val jellyfinIdsByServer = jellyfinResumeItems.groupBy(
        { it.sourceProviderId.orEmpty() },
        { com.lumora.util.rawMediaItemId(it.id) }
    )
    val plexIdsByServer = plexResumeItems.groupBy(
        { it.sourceProviderId.orEmpty() },
        { com.lumora.util.rawMediaItemId(it.id) }
    )
    jellyfinResumeByServer.clear()
    plexResumeByServer.clear()
    for ((serverId, ids) in jellyfinIdsByServer) {
        val client = jellyfinClients[serverId] ?: jellyfinClients.values.singleOrNull() ?: continue
        scope.launch(Dispatchers.IO) { ids.forEach { id -> runCatching { client.clearUserData(id) } } }
    }
    for ((serverId, ids) in plexIdsByServer) {
        val client = plexClients[serverId] ?: plexClients.values.singleOrNull() ?: continue
        scope.launch(Dispatchers.IO) { ids.forEach { id -> runCatching { client.clearUserData(id) } } }
    }
    getHiddenHomeShelves().let { if (it.remove(getString(R.string.category_continue_watching))) prefs.edit().putStringSet("hidden_home_shelves", it).apply() }
    getHiddenCategories(1).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(1), it).apply() }
    getHiddenCategories(2).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(2), it).apply() }
    homeShelfAdapter.submitList(buildHomeShelves())
    if (!showingHome && activeTab != 0) scope.launch { classifyAndShow() }
}

/** Adult content never reaches a Home shelf, regardless of the "Hide adult categories"
 *  setting. That setting governs *browsing* - somebody who unlocks it with the PIN is
 *  choosing to go and look. Continue Watching and Recently Played are different: they
 *  render unprompted on the first screen after launch, in front of whoever happens to
 *  be in the room, so they stay filtered either way.
 *
 *  Three signals, in order of trust: the catalog entry for this id (authoritative, but
 *  only for items still in the catalog - a series episode never is), the category/group
 *  snapshotted at save time, then the title itself as a last resort for entries written
 *  before that snapshot existed. The title check can over-match a legitimate film with
 *  "adult" in its name, which is the right way round to be wrong here. */
internal fun MainActivity.isAdultHomeItem(item: Channel): Boolean {
    val catalog = item.id.takeIf { it.isNotBlank() }?.let { id -> allChannels.firstOrNull { it.id == id } }
    return isAdultCategory(catalog?.categoryName ?: item.categoryName, catalog?.group ?: item.group) ||
        isAdultCategory(item.name)
}

/** First unwatched episode of a series in play order (season-major, then episode
 *  number) - the same ordering the detail page and auto-advance use. Null when the
 *  whole series is watched: a completed series gets no up-next tile. */
internal fun MainActivity.nextEpisodeFor(seasons: List<Pair<String, List<Channel>>>): Channel? {
    val ordered = seasons.flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
    return ordered.firstOrNull { !isItemWatched(it) }
}

/** Builds an up-next tile's display name: "Series · S01E05 · Title". Episode titles often
 *  already carry the series name (Xtream bakes it in), so a leading series-name
 *  occurrence and the "SxxEyy · " marker are peeled from the title before the series
 *  prefix is added - otherwise the series reads twice. */
internal fun MainActivity.upNextTileName(seriesName: String, episodeName: String): String {
    val sMark = Regex("""^S\d+E\d+""").find(episodeName)?.value
    val title = episodeName
        .replaceFirst(Regex("^" + Regex.escape(seriesName) + """\s*[·-]\s*"""), "")
        .replaceFirst(Regex("""^S\d+E\d+\s*·\s*"""), "")
        .replaceFirst(Regex("^" + Regex.escape(seriesName) + """\s*-\s*"""), "")
    return listOfNotNull(seriesName, sMark, title.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** Continue Watching extension: a series whose watched trail ends at a completed
 *  episode has nothing in Continue Watching (it only keeps in-progress entries), so its
 *  next episode would be unreachable from Home. Resolve those lazily - return whatever
 *  next-episode tiles are already memoized, and kick an async bounded fetch for the
 *  rest. Cheap when everything's resolved: just a store read + memo lookups. */
internal fun MainActivity.buildUpNextSeriesTiles(): List<Channel> {
    // Home and the Series tab are the only screens that render these tiles; every other
    // shelf build (clear/watch toggle paths, the player's side menu listing another tab's
    // categories) shouldn't kick six network fetches for a row that isn't visible.
    if (!showingHome && activeTab != 1) return emptyList()
    // Which series the servers' own Next Up lists already answer for, by parent series id
    // (qualified, as toChannel stamps it on an episode). Only those are skipped.
    //
    // Own-library trails used to be dropped wholesale on the grounds that the server-side
    // row covered them. It doesn't, for two compounding reasons: those lists are only
    // re-pulled on a catalog load or at the end of a play, so nothing refreshes them when an
    // episode is *marked* watched rather than played through; and the server answers only
    // for shows it considers in progress, which a show whose episodes were all ticked off
    // isn't. A Jellyfin/Plex series marked watched therefore fell through both halves - no
    // local tile because it was skipped, no server tile because nothing had asked the server
    // since - and never reached Up Next at all. Resolving it locally costs the same one
    // episode-list call as any other series: loadSeriesContent speaks Jellyfin and Plex too.
    val serverCovered = (jellyfinNextUpItems + plexNextUpItems)
        .mapNotNullTo(HashSet()) { it.categoryId?.takeIf { id -> id.isNotBlank() } }
    val trails = PlaybackPositionStore.getCompletedSeriesTrails(this)
        .filterNot { it.categoryId in serverCovered }
    val pending = trails
        .mapNotNull { it.categoryId?.takeIf { id -> id !in upNextTiles && id !in upNextFetching } }
        .take(MAX_UP_NEXT_SERIES)
    if (pending.isNotEmpty()) fetchUpNextSeries(pending)
    // Trail order = most recently completed first; present the memoized tiles in that
    // order (LinkedHashMap insertion order is fetch-completion order, which is arbitrary).
    return trails.mapNotNull { t -> upNextTiles[t.categoryId] }
}

/** Fetches the episode lists for up to [MAX_UP_NEXT_SERIES] series (one network call each,
 *  through loadSeriesContent, so Xtream/Stalker/Jellyfin/Plex all resolve here), computes
 *  each series' next unwatched episode, and rebuilds the Home shelves once. Results commit atomically only
 *  if the memo epoch hasn't moved (see [clearUpNextMemo]) - a fetch that outlives a
 *  watched-state change must not write pre-change tiles.
 *  Only a *resolved* "no next episode" (fully watched / genuinely empty seasons) is
 *  memoized as no-tile; catalog misses and network failures stay unresolved so the next
 *  Home rebuild retries them. */
internal fun MainActivity.fetchUpNextSeries(seriesIds: List<String>) {
    val epoch = upNextEpoch
    upNextFetching.addAll(seriesIds)
    scope.launch {
        val resolved = HashMap<String, Channel?>()
        val queues = HashMap<String, List<Channel>>()
        for (seriesId in seriesIds) {
            if (epoch != upNextEpoch) break
            val series = allChannels.firstOrNull {
                it.mediaType == MediaType.SERIES && it.id == seriesId
            } ?: continue // not in catalog yet - leave unresolved, retry next build
            val seasons = withContext(Dispatchers.IO) {
                runCatching { loadSeriesContent(series).second }.getOrNull()
            } ?: continue // network failure - leave unresolved, retry next build
            val next = nextEpisodeFor(seasons)
            if (next == null) {
                // Resolved: fully watched (or no playable episodes) - no tile, ever.
                resolved[seriesId] = null
                continue
            }
            val chain = seasons.flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
            queues[next.id.ifBlank { next.url }] = chain
            // Prefix the series name so a bare "S02E03 · Title" tile reads as the show
            // it belongs to - but peel any series-name occurrence already baked into the
            // episode title first (Xtream titles often read "Clarkson's Farm (2021) -
            // Tractoring"), or the series shows twice.
            resolved[seriesId] = next.copy(name = upNextTileName(series.name, next.name))
        }
        // Commit only if no watched-state change invalidated the memo mid-fetch. No
        // upNextFetching cleanup here: the clear already wiped the set, and removing
        // ids now could yank a *newer* epoch's in-flight claim for the same series.
        if (epoch != upNextEpoch) return@launch
        val foundAny = resolved.values.any { it != null }
        upNextTiles.putAll(resolved)
        upNextQueues.putAll(queues)
        seriesIds.forEach { upNextFetching.remove(it) }
        if (!foundAny) return@launch
        // Whichever screen asked for the tiles gets the rebuild. The Series tab needs both
        // halves: the poster shelf and the sidebar row, since Up Next carries a count.
        if (showingHome) homeShelfAdapter.submitList(buildHomeShelves())
        else if (activeTab == 1) {
            refreshSeriesShelvesIfShowing()
            rebuildCategoriesForActiveTab()
        }
    }
}

/** Series-tab "Next Up" - the next *unwatched* episode of everything in flight. Two
 *  sources, same as Home: the media servers' own Next Up lists (they track playback from
 *  every other client), and [buildUpNextSeriesTiles] for locally-tracked series whose
 *  watched trail ends on a completed episode. Distinct from Continue Watching, which only
 *  holds episodes stopped part-way through.
 *
 *  Labelled and postered from the parent series by [continueWatchingTiles], so a bare
 *  "S02E03" reads as the show it belongs to. Clicking a tile opens that show's detail page
 *  - see onHomeItemClick. Shared by the Series sidebar row, its content grid, and the
 *  Series poster shelf. */
internal fun MainActivity.seriesUpNextItems(): List<Channel> {
    val server = (jellyfinNextUpItems + plexNextUpItems).filter { it.mediaType == MediaType.SERIES }
    val local = buildUpNextSeriesTiles()
    return continueWatchingTiles(
        (server + local)
            .distinctBy { it.id.ifBlank { it.url } }
            .filterNot(::isAdultHomeItem)
    )
}

/**
 * The Series poster's single lead shelf: everything the user has a personal claim on, in the
 * order they are likely to want it - what is part-watched, then what is next, then what they
 * starred.
 *
 * One row rather than three because three near-identical rows of five tiles each pushed the
 * catalogue itself below the fold, and the same show could head all three of them. The
 * sidebar still lists Continue Watching and Up Next separately: the rail is for picking a
 * filter, where the distinction is the whole point, while the poster is for picking a title.
 *
 * Deduped by *show*, not by tile. A Continue Watching episode, an Up Next episode and a
 * favourite are three different Channels carrying three different ids that can all stand for
 * one series, so what gets claimed is the series each tile belongs to - an episode through
 * its parent id, a top-level entry through its own. First tile in wins, which makes the
 * order above the priority order too.
 */
internal fun MainActivity.seriesPosterLeadShelfItems(favourites: List<Channel>): List<Channel> {
    val claimed = HashSet<String>()
    return (seriesContinueItems() + seriesUpNextItems() + favourites).filter { item ->
        val show = item.categoryId?.takeIf { it.isNotBlank() && item.episodeNum != null }
            ?: item.id.ifBlank { item.url }
        show.isNotBlank() && claimed.add(show)
    }
}

internal fun MainActivity.buildHomeShelves(): List<ContentShelf> {
    val shelves = mutableListOf<ContentShelf>()
    val hidden = getHiddenHomeShelves()

    // The media servers' own resume lists lead Continue Watching: they know about playback
    // from every other client, which a purely local position store never can. Local
    // entries follow, minus anything a server already covered (same item, one card).
    val localContinue = PlaybackPositionStore.getAllInProgress(this)
    val serverContinue = jellyfinResumeItems + plexResumeItems
    // Up-next series tiles: series whose watched trail ends at a completed episode have
    // no in-progress entry, so they'd otherwise drop out of Continue Watching entirely.
    // buildUpNextSeriesTiles returns what's already resolved and kicks the async fetch
    // for the rest - the row fills in as episodes arrive.
    val upNext = buildUpNextSeriesTiles().filterNot(::isAdultHomeItem)
    // Labelled and postered from the parent series - see continueWatchingTiles. Applied after
    // the dedupe so the catalogue pass runs over the final row, not the raw merge.
    val continueItems = continueWatchingTiles(
        (serverContinue + localContinue + upNext)
            .distinctBy { it.id.ifBlank { it.url } }
            .filterNot(::isAdultHomeItem)
    )
    if (continueItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_continue_watching), continueItems))

    // "Next Up" is the row that makes a series library usable - the next unwatched episode
    // of everything in flight, straight from the server's own tracking.
    val nextUpItems = (jellyfinNextUpItems + plexNextUpItems)
        .distinctBy { it.id.ifBlank { it.url } }
        .filterNot(::isAdultHomeItem)
    if (nextUpItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_next_up), nextUpItems))

    val recentItems = RecentlyPlayedStore.getRecentIds(this)
        .mapNotNull { id -> liveChannels.firstOrNull { it.id == id } }
        .filterNot(::isAdultHomeItem)
    if (recentItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_recently_played), recentItems))

    // Favourited live channels get their own Home row. Long-pressing a channel in the
    // guide has always favourited it, but the result was only ever visible as the
    // Favourites category inside Live TV - Home, the screen the app opens on, showed
    // nothing at all, so the favourites looked like they hadn't saved.
    val favChannelIds = FavoritesStore.getFavoriteChannelIds(this)
    val favChannels = liveChannels.filter { it.id in favChannelIds }.filterNot(::isAdultHomeItem)
    if (favChannels.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_favourite_channels), favChannels))

    // One shelf for both, since favourite VOD is stored in a single set (see
    // FavoritesStore.KEY_FAVORITE_SERIES) - a favourited film used to be saved and then
    // never shown anywhere, because only seriesList was searched for the ids.
    val favIds = FavoritesStore.getFavoriteSeriesIds(this)
    val favItems = (seriesList + filmList).filter { it.id in favIds }.filterNot(::isAdultHomeItem)
    if (favItems.isNotEmpty()) shelves.add(ContentShelf(getString(R.string.category_favourites), favItems))

    return shelves.filter { it.title !in hidden }
}

/** Series-only Continue Watching for the Series tab - same merge as the Home shelf
 *  (server resume list first, then local in-progress entries minus anything the server
 *  already covered), filtered down to series and adult-dropped. Shared by the Series
 *  sidebar row, its content grid, and the Series poster shelf. */
internal fun MainActivity.seriesContinueItems(): List<Channel> {
    val local = PlaybackPositionStore.getAllInProgress(this).filter { it.mediaType == MediaType.SERIES }
    val server = (jellyfinResumeItems + plexResumeItems).filter { it.mediaType == MediaType.SERIES }
    val serverIds = server.map { it.id }.toSet()
    return continueWatchingTiles(
        (server + local.filterNot { it.id in serverIds }).filterNot(::isAdultHomeItem)
    )
}

/**
 * Re-labels episode tiles with the show they belong to, and gives them the show's artwork:
 * "SAS Rogue Heroes · S03E03", over the series poster.
 *
 * A Continue Watching entry is a snapshot of the *episode* that was playing, so the tile
 * carried whatever the provider called that episode - frequently a bare "S03E03", or an
 * episode title with no hint of the show - over an episode still, which for most providers is
 * nothing at all. Neither says which series is being offered, which is the one thing the row
 * exists to answer.
 *
 * Only the display fields are rewritten. The id, url, categoryId and episodeNum are the
 * originals, so clicking still resolves to the series page (or resumes the episode) exactly as
 * before, and the playback-position key is untouched.
 *
 * Anything that isn't a resolvable episode is returned as it came: a film, a top-level series
 * entry, or an episode whose series isn't in the catalogue (a provider that never stamped the
 * parent id, or a show since removed). Those keep the name the snapshot was saved with rather
 * than getting a half-built label.
 */
/** Anywhere in a name, not just at the front - see [tileSeasonNumber]. */
internal val ANY_SEASON_MARKER_REGEX = Regex("""(?i)\bS(\d{1,2})E\d{1,3}\b""")

/**
 * The season number to print on a Continue Watching tile, or null if nothing states one.
 *
 * [seasonNumberOf] is the shared helper, but its regex is anchored to the start of the name,
 * so it only reads providers that lead with the marker ("S03E03 · Title"). Plenty bury it
 * instead ("SAS Rogue Heroes - S03E03 - Title"), so fall back to finding it anywhere. That
 * looser match is confined to this label: a false positive here misprints a tile, whereas
 * [seasonNumberOf] feeds episode-queue and find-stream decisions and should stay strict.
 *
 * Jellyfin and Plex episodes state the season as a field that [Channel] has nowhere to keep,
 * and their names carry no marker at all - those tiles get a bare episode number.
 */
internal fun tileSeasonNumber(episode: Channel): Int? =
    seasonNumberOf(episode)
        ?: ANY_SEASON_MARKER_REGEX.find(episode.name)?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun MainActivity.continueWatchingTiles(items: List<Channel>): List<Channel> {
    val wanted = items.mapNotNullTo(HashSet()) { item ->
        item.categoryId?.takeIf { it.isNotBlank() && item.episodeNum != null }
    }
    if (wanted.isEmpty()) return items
    // One pass over the catalogue for the whole row. resolveHomeTileSeries scans allChannels
    // per call, which is fine for the single lookup a click does but not for a shelf rebuild:
    // a six-figure catalogue times a row of tiles is millions of comparisons on the main
    // thread. Candidates are grouped by id because the source guards below still have to run -
    // ids are provider-scoped, so collisions are only possible across providers.
    val candidates = HashMap<String, MutableList<Channel>>()
    for (ch in allChannels) {
        if (ch.mediaType != MediaType.SERIES) continue
        if (ch.id !in wanted) continue
        candidates.getOrPut(ch.id) { mutableListOf() }.add(ch)
    }
    if (candidates.isEmpty()) return items

    return items.map { item ->
        val episode = item.episodeNum ?: return@map item
        val seriesId = item.categoryId?.takeIf { it.isNotBlank() } ?: return@map item
        val series = candidates[seriesId]?.firstOrNull {
            it.isJellyfin == item.isJellyfin && it.isPlex == item.isPlex &&
                (item.sourceProviderId == null || it.sourceProviderId == item.sourceProviderId)
        } ?: return@map item
        val seriesName = cleanVodTitle(series.name).ifBlank { return@map item }
        val marker = tileSeasonNumber(item)
            ?.let { season -> "S%02dE%02d".format(season, episode) }
            ?: "E%02d".format(episode)
        item.copy(
            name = "$seriesName · $marker",
            posterUrl = series.posterUrl ?: series.logoUrl ?: item.posterUrl,
            logoUrl = series.logoUrl ?: series.posterUrl ?: item.logoUrl
        )
    }
}

/**
 * The TMDB show id behind [item], or null when there is no way to be sure of one.
 *
 * The trap here is that "the trailing number in the id" is *not* a TMDB id for anything except
 * a Discover title. A panel's series id is its own primary key - "1234" - and handing that to
 * TMDB does not fail, it happily returns a completely different show (1234 is "Clapperboard",
 * one season, no episodes). That is how a library series ended up listing a stranger's season
 * structure - one episode per season - rather than its own.
 *
 * So the id is trusted only when it is declared as a TMDB one, either as a `tmdb:tv:N` Discover
 * id or as a `tmdb_id` the panel itself sent. Anything else is matched by title and year, the
 * same lookup the episode-metadata enrichment already does.
 */
private suspend fun MainActivity.tmdbTvIdFor(item: Channel): Int? {
    tmdbTypeAndId(item.id)?.let { (type, id) -> if (type == "tv") return id }
    item.tmdbTypeAndId()?.let { (type, id) -> if (type == "tv") return id }
    return runCatching {
        tmdbClient.resolveId(cleanVodTitle(item.name), item.tmdbYear(), isSeries = true)
            ?.takeIf { it.first == "tv" }?.second
    }.getOrNull()
}

/**
 * Seasons and episodes for a title the library does not have, built from TMDB.
 *
 * A Discover series has no provider behind it, so `loadSeriesContent` finds nothing and the
 * detail screen used to say "No episodes found" - accurate about the library, useless to the
 * user, since Find Stream could have played any of those episodes.
 *
 * The Channels produced here are placeholders: they carry the episode's number, title and still,
 * and deliberately no `url`, because there is no stream until a source is found for that specific
 * episode. [onEpisodeClick] in showContentDetail routes a URL-less episode to Find Stream.
 *
 * Season 0 (specials) is dropped - TMDB lists it first, which would otherwise make it the season
 * the screen opens on.
 */
internal suspend fun MainActivity.tmdbSeasonsFor(item: Channel): List<Pair<String, List<Channel>>> {
    val tvId = tmdbTvIdFor(item) ?: return emptyList()
    val seasons = runCatching { tmdbClient.tvSeasons(tvId) }.getOrElse { emptyList() }
        .filter { it.number > 0 && it.episodeCount > 0 }
    if (seasons.isEmpty()) return emptyList()

    return seasons.map { season ->
        // Episode titles are a second request per season, so they are fetched rather than
        // guessed - a list of "Episode 1..10" is not much better than the empty state.
        val details = runCatching { tmdbClient.tvEpisodes(tvId, season.number) }
            .getOrElse { emptyMap() }
        val episodes = (1..season.episodeCount).map { number ->
            val ep = details[number]
            Channel(
                id = "${item.id}:s${season.number}e$number",
                name = ep?.name?.takeIf { it.isNotBlank() }
                    ?.let { "E%02d · %s".format(number, it) }
                    ?: "E%02d".format(number),
                // No URL on purpose - see this function's kdoc.
                url = "",
                posterUrl = ep?.stillUrl ?: item.posterUrl,
                backdropUrl = item.backdropUrl,
                mediaType = MediaType.SERIES,
                episodeNum = number,
                // The air date is what tells a placeholder for an episode nobody carries yet
                // apart from one that simply isn't in the library - see util.isUnreleasedEpisode.
                releaseDate = ep?.airDate,
                description = ep?.overview,
                categoryName = item.name,
                group = item.group,
            )
        }
        // TMDB's own name for the season, but only when it agrees with the number. Most shows
        // name seasons "Season 3"; an anthology names them after the case ("Murder in the Rocky
        // Mountains"), and a few name them for the year. Those went on the chip verbatim, so a
        // series showed "Season 1, Season 2, Season 4, Murder in the Rocky Mountains" - and
        // since mergeMissingEpisodesFromTmdb reads the season number back out of this label,
        // a nameless number meant null: sorted last, and every named season colliding on the
        // same null key.
        val labelNumber = Regex("""\d+""").find(season.name)?.value?.toIntOrNull()
        val label = if (labelNumber == season.number) season.name
            else getString(R.string.list_season_number, season.number)
        label to episodes
    }
}

/**
 * Fills the gaps in a provider's episode list from TMDB.
 *
 * A provider carrying a series is not a promise that it carries all of it - panels routinely
 * list one or two episodes of a season and nothing else. Preferring the library wholesale meant
 * those seasons displayed as complete when they were a fraction, with no hint the rest existed
 * and no way to reach them.
 *
 * So the two are merged rather than one chosen: an episode the provider has keeps its real URL
 * and plays directly, and every number TMDB knows about that the provider is missing is added as
 * a URL-less placeholder that routes to Find Stream. Seasons TMDB has and the provider does not
 * are appended whole.
 *
 * Returns [librarySeasons] unchanged when TMDB knows nothing, so a failed lookup can only cost
 * the extra episodes, never the ones that were already there.
 */
internal suspend fun MainActivity.mergeMissingEpisodesFromTmdb(
    item: Channel,
    librarySeasons: List<Pair<String, List<Channel>>>,
): List<Pair<String, List<Channel>>> {
    val tmdbSeasons = tmdbSeasonsFor(item)
    if (tmdbSeasons.isEmpty()) return librarySeasons

    /** "Season 2" / "S2" / "2" all have to line up with TMDB's own label. */
    fun seasonNumber(label: String): Int? = Regex("""\d+""").find(label)?.value?.toIntOrNull()

    val byNumber = librarySeasons.associateBy { seasonNumber(it.first) }
    val merged = tmdbSeasons.map { (tmdbLabel, tmdbEpisodes) ->
        val number = seasonNumber(tmdbLabel)
        val library = byNumber[number]?.second.orEmpty()
        if (library.isEmpty()) return@map tmdbLabel to tmdbEpisodes
        val have = library.mapNotNull { it.episodeNum }.toSet()
        val filled = (library + tmdbEpisodes.filter { it.episodeNum !in have })
            .sortedBy { it.episodeNum ?: Int.MAX_VALUE }
        (byNumber[number]?.first ?: tmdbLabel) to filled
    }
    // Anything the provider has that TMDB does not know about must not be dropped.
    val covered = merged.mapNotNull { seasonNumber(it.first) }.toSet()
    val extras = librarySeasons.filter { seasonNumber(it.first) !in covered }
    return (merged + extras).sortedBy { seasonNumber(it.first) ?: Int.MAX_VALUE }
}
