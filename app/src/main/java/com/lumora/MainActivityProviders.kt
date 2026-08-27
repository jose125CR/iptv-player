package com.lumora

import java.io.File
import android.view.View
import android.widget.*
import com.lumora.cache.ChannelCache
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.model.AccountConfig
import com.lumora.data.AccountStore
import com.lumora.parser.M3uParser
import com.lumora.parser.XtreamClient
import com.lumora.util.normalizeServerUrl
import com.lumora.data.remote.stalker.StalkerProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException

// ── Provider loading, per-provider content gates & backend fetches ──
//
// Extracted from MainActivity.kt; see that file's header.
/** Fixed, no-picker-required fallback location for devices (Fire TV, most Android TV
 *  boxes) with no Storage Access Framework document picker installed at all. */
internal fun MainActivity.localBackupFile(): File {
    val dir = File(getExternalFilesDir(null), "backups").apply { mkdirs() }
    return File(dir, "lumora_backup.json")
}

internal fun MainActivity.hasIptvConfigured(): Boolean = AccountStore.load(prefs).isNotEmpty()

internal fun MainActivity.hasProviderConfigured(): Boolean = hasIptvConfigured()

internal fun MainActivity.hasProviderEnabled(): Boolean =
    AccountStore.activeAccount(prefs) != null

/** Search and the tab bar are useful once there's an enabled provider to browse - with none,
 *  they point at nothing, so hide them and leave just the Settings button as the way in.
 *  The empty state carries its own Settings/Demo actions. Settings and refresh stay visible
 *  so the user can always get back to configuring one. */
internal fun MainActivity.updateTopChromeVisibility() {
    val enabled = hasProviderEnabled()
    if (!enabled) binding.homeSearchBar.visibility = View.GONE
    // Every tab/search visibility rule now lives in applySimpleModeUi() - it recomputes the
    // same "is there anything to browse" flag, and it must have the last word anyway.
    applySimpleModeUi()
}

/** Blanket show/hide of the scroller's browsing items - the tabs plus the Search pill.
 *  Settings and Refresh sit in the same scroller now (so the chrome reads as one bar), so
 *  hiding the scroller would take them with it, and "no provider" is exactly the state
 *  where Settings must stay reachable. Showing is only lifting the blanket hide: the
 *  per-item rules (Downloads is phone-only, Catch Up needs an archive, simple mode shows
 *  Live + Catch Up alone) are re-applied by the caller straight after. */
internal fun MainActivity.setBrowseTabsVisible(visible: Boolean) {
    val vis = if (visible) View.VISIBLE else View.GONE
    for (tab in listOf(
        binding.tabHome, binding.tabLive, binding.tabSeries,
        binding.tabFilms, binding.tabDownloads, binding.btnSearch,
    )) {
        tab.visibility = vis
    }
}

/** Settings' LEFT target, which depends on what is actually on screen to its left:
 *  Downloads (phone), else Search, else Home (simple mode's rightmost tab), else nothing
 *  left of it at all. An explicit nextFocusLeftId pointing at a GONE view resolves to
 *  nothing and eats the press, so it can't be left to the XML default. */
internal fun MainActivity.updateChromeFocusChain() {
    binding.btnSettings.nextFocusLeftId = when {
        binding.tabDownloads.visibility == View.VISIBLE -> R.id.tabDownloads
        binding.btnSearch.visibility == View.VISIBLE -> R.id.btnSearch
        binding.tabHome.visibility == View.VISIBLE -> R.id.tabHome
        else -> R.id.btnSettings
    }
}

/** Simple mode is "TV only": Live TV and Home stay reachable (Catch Up through its own
 *  small chip, not a tab), Series/Films/Discover/Downloads/Search go - there is nothing to
 *  browse there once VOD is off. The toolbar above the bar stays, so Settings/Refresh
 *  stay reachable too. */
internal fun MainActivity.isSimpleMode(): Boolean = prefs.getBoolean(PREF_SIMPLE_MODE, false)

/** VOD is dropped at fetch time; the manual toggle and simple mode both turn it off,
 *  and simple mode never writes the manual pref so turning it off re-enables VOD. */
internal fun MainActivity.isVodDisabled(): Boolean = isSimpleMode() || prefs.getBoolean(PREF_DISABLE_VOD, false)

// ── Per-provider content-type gates ──────────────
/** Effective per-content-type gate for one IPTV provider: the provider's own flag,
 *  with Movies/Series additionally subject to the global VOD gate. */
internal fun MainActivity.providerAllowsLive(cfg: AccountConfig): Boolean = cfg.liveEnabled

internal fun MainActivity.providerAllowsMovies(cfg: AccountConfig): Boolean = !isVodDisabled() && cfg.moviesEnabled

internal fun MainActivity.providerAllowsSeries(cfg: AccountConfig): Boolean = !isVodDisabled() && cfg.seriesEnabled

internal fun MainActivity.isTypeAllowed(
    ch: Channel,
    configs: List<AccountConfig>,
): Boolean {
    val owner = ch.sourceProviderId?.let { id -> configs.firstOrNull { it.id == id } }
    return when (ch.mediaType) {
        MediaType.LIVE -> owner?.let { providerAllowsLive(it) } ?: true
        MediaType.MOVIE -> owner?.let { providerAllowsMovies(it) } ?: true
        MediaType.SERIES -> owner?.let { providerAllowsSeries(it) } ?: true
    }
}

internal fun MainActivity.applySimpleModeUi() {
    val simple = isSimpleMode()
    // Chrome up = something to browse, so the tab bar would be showing in normal mode.
    // Simple mode hides it regardless; the flag still gates the forced tab switch below
    // (with no providers the empty state owns the screen and selectTab would fight it).
    val chromeUp = hasProviderEnabled()
    if (simple) {
        // Series/Films/Downloads/Search go; Live TV and Home stay. Catch Up
        // stays reachable through its own chip, gated below same as normal mode.
        setBrowseTabsVisible(false)
        binding.tabLive.visibility = if (chromeUp) View.VISIBLE else View.GONE
        binding.tabHome.visibility = if (chromeUp) View.VISIBLE else View.GONE
        if (chromeUp) updateCatchupTabVisibility()
        // Series/Films/Downloads are unreachable now - land back on Live instead
        // of leaving a hidden pane on screen. Home is a legitimate destination, left alone.
        if (chromeUp && !showingHome && (showingDownloads || activeTab == 1 || activeTab == 2)) {
            if (!showingCatchup) selectTab(0)
        }
        // Live <-> Home <-> Settings: the tabs between them (Series/Films/Search/
        // Downloads) are all GONE, and an unrouted nextFocusId pointing at a GONE view eats
        // the D-pad press rather than skipping it.
        binding.tabLive.nextFocusLeftId = R.id.tabLive
        binding.tabLive.nextFocusRightId = R.id.tabHome
        binding.tabHome.nextFocusLeftId = R.id.tabLive
        binding.tabHome.nextFocusRightId = R.id.btnSettings
    } else {
        setBrowseTabsVisible(chromeUp)
        if (chromeUp) {
            // Downloads is phone-only and Catch Up needs an archive - the blanket show
            // above put both back, so re-apply their own rules.
            if (isTv) binding.tabDownloads.visibility = View.GONE
            updateCatchupTabVisibility()
            updateProviderOnlyTabsVisibility()
        }
        // Back to the full-row chain: Live -> Series -> Films -> Home.
        binding.tabLive.nextFocusLeftId = R.id.tabDownloads
        binding.tabLive.nextFocusRightId = R.id.tabSeries
    }
    updateChromeFocusChain()
}

/** Home/Live are provider catalog views with nothing to show once there's no IPTV
 *  provider - Live has no channels and Home's shelves are built from the same catalog.
 *  Series/Films go with them: without Discover or the plugins there is nothing else to
 *  browse, so with no provider enabled the empty state owns the screen. */
internal fun MainActivity.updateProviderOnlyTabsVisibility() {
    val providerAvailable = hasProviderEnabled()
    val catalogVis = if (providerAvailable) View.VISIBLE else View.GONE
    binding.tabHome.visibility = catalogVis
    binding.tabLive.visibility = catalogVis
    val seriesFilmsVis = catalogVis
    binding.tabSeries.visibility = seriesFilmsVis
    binding.tabFilms.visibility = seriesFilmsVis
    if (providerAvailable) {
        // Back to the XML chain, in case a previous no-provider pass below rewired these.
        binding.tabSeries.nextFocusLeftId = R.id.tabLive
        binding.tabFilms.nextFocusRightId = R.id.tabHome
    } else {
        // The tabs vanishing under the user (every provider disabled while on one of
        // them) must not leave the pane on screen with no way out. Downloads (phone)
        // is a legitimate destination, left alone; anything else has nothing left to
        // browse, so the empty state owns the screen.
        if (!showingDownloads) showEmptyState()
    }
}

/** Re-runs the provider load so the VOD gate takes effect - VOD is skipped at fetch
 *  time (and filtered out of a cached cold start), so either toggle needs a reload. */
internal fun MainActivity.vodStateChanged() {
    if (hasProviderConfigured()) scope.launch { loadAllConfiguredProviders(forceRefresh = true) }
}

/** Shows the "no provider" empty state and, crucially, moves focus onto one of its
 *  buttons. With the tab bar and search hidden and the content panes gone, nothing else
 *  on screen is focusable - so without this the D-pad had nothing to land on and stopped
 *  responding entirely (the "can't navigate when nothing's returned" trap). */
internal fun MainActivity.showEmptyState() {
    binding.contentRow.visibility = View.GONE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    // The status row shares this weight=1 slot; leaving it up splits the screen and
    // buries the empty-state buttons under it.
    binding.statusRow.visibility = View.GONE
    binding.emptyState.visibility = View.VISIBLE
    updateTopChromeVisibility()
    // Focus a button so the D-pad has somewhere to land - without this nothing is
    // focused and centre-press does nothing. Retried once on the next frame because the
    // very first post can land before the row is laid out (requestFocus then no-ops).
    fun focusFirstAction(): Boolean {
        val target = binding.emptyChooseM3u.takeIf { it.isShown }
            ?: return false
        return target.requestFocus()
    }
    binding.emptyState.post { if (!focusFirstAction()) binding.emptyState.post { focusFirstAction() } }
}

internal fun MainActivity.loadSavedProvider() {
    loadAllConfiguredProviders()
}

/** Whether a finished catalog load should refresh in place rather than run the full
 *  first-paint render.
 *
 *  [MainActivity.uiPainted] alone is not the question. A load kicked off from Settings sets it
 *  on the way past (the live-partial paint marks it even though the settings overlay
 *  suppressed the render), so adding the very first provider then closing Settings before the
 *  fetch landed left the app on the "no provider" empty state: the surgical refresh has no
 *  path that takes that screen down. If the empty state is what the user is looking at, the
 *  catalog that just arrived is first content, whatever the flag says. */
internal fun MainActivity.shouldPreserveUiOnLoad(): Boolean =
    uiPainted && binding.emptyState.visibility != View.VISIBLE

/** Reacts to a provider being switched on or off in Settings.
 *
 *  Switching one *off* needs no network at all: its items are already in memory and
 *  carry their own provenance, so they're just dropped. Re-fetching every other provider
 *  to achieve that meant a full "Connecting to ..." reload - visible behind the settings
 *  dialog - and left the catalog at the mercy of a provider that happened to be down.
 *  Switching one *on* genuinely needs its catalog, so that still refreshes. */
internal fun MainActivity.applyProviderToggle(enabled: Boolean, belongsToProvider: (Channel) -> Boolean) {
    if (enabled) { loadAllConfiguredProviders(forceRefresh = true); return }
    scope.launch {
        allChannels = allChannels.filterNot(belongsToProvider)
        classifyAndShow()
        persistCatalog(allChannels)
    }
}

/** Saves [channels] to the disk catalog cache, first merging back the previously-cached
 *  movies/series of any provider whose VOD gate is currently active. Fetch-time gates skip
 *  the slow VOD/series crawl, which would otherwise strip that provider's VOD from the
 *  cache and leave it unrecoverable until a network fetch runs with the gate lifted. The
 *  cold-start filter (loadAllConfiguredProviders) is what hides gated VOD at display time,
 *  so the cache itself stays display-unfiltered. */
internal suspend fun MainActivity.persistCatalog(channels: List<Channel>) = withContext(Dispatchers.IO) {
    val configs = AccountStore.load(prefs)
    val configuredIds = configs.map { it.id }.toSet()
    // Fast path: no content-type gate is active (per-provider flags fold the global VOD
    // gate in via isVodDisabled), so the cache can be saved unfiltered.
    val anyGateOff = configs.any { !providerAllowsLive(it) || !providerAllowsMovies(it) || !providerAllowsSeries(it) }
    if (!anyGateOff) {
        ChannelCache.save(this@persistCatalog, channels)
        return@withContext
    }
    val old = ChannelCache.load(this@persistCatalog)
    if (old.isNullOrEmpty()) {
        ChannelCache.save(this@persistCatalog, channels)
        return@withContext
    }
    val known = channels.map { it.id }.toSet()
    // Keep cached items whose type is currently gated OFF for their still-configured
    // owner, so re-enabling the type restores them without a network re-fetch. Items of
    // dropped/removed providers and of types that are ON do not resurrect.
    val resurrect = old.filter { ch ->
        if (ch.id in known) return@filter false
        val ownerConfigured = ch.sourceProviderId != null && ch.sourceProviderId in configuredIds
        if (!ownerConfigured) return@filter false
        !isTypeAllowed(ch, configs)
    }
    ChannelCache.save(this@persistCatalog, channels + resurrect)
}

/** Caches written by older builds can still carry rows from the removed anime catalog
 *  (ids prefixed "anime:" - metadata-only titles that had no stream of their own). Drop
 *  them rather than leaving dead tiles on screen; a fresh fetch never produces them. */
internal fun MainActivity.stripLegacyAnimeRows(channels: List<Channel>): List<Channel> =
    channels.filterNot { it.id.startsWith("anime:") }

/** True when the cached catalog is old enough to be worth re-fetching. A missing stamp
 *  counts as stale so a cache written by an older build refreshes once, then follows
 *  the TTL like everything else. */
internal fun MainActivity.isCatalogStale(): Boolean {
    val last = prefs.getLong(PREF_CATALOG_REFRESHED_AT, 0L)
    return last <= 0L || System.currentTimeMillis() - last >= CATALOG_TTL_MS
}

/** The one place that loads whatever's configured+enabled across every IPTV provider
 *  (any number of them now, not just one), and merges the result - every settings
 *  Save/reload call site routes through here instead of assuming a single active provider.
 *  [forceRefresh] skips the on-disk cache (used after a settings change, where showing
 *  stale content would be actively wrong) and re-fetches from the network(s) directly. */
internal fun MainActivity.loadAllConfiguredProviders(forceRefresh: Boolean = false) {
    // With nothing configured, classifyAndShow's own hasContent check lands on
    // showEmptyState() - the first-run chooser - rather than auto-opening Settings
    // unasked on a fresh install.
    if (!hasProviderConfigured()) { scope.launch { classifyAndShow() }; return }
    // Raised for the cached path too: reading and re-deriving a big catalog still takes
    // a few seconds, and with no status up the app just looks frozen.
    setStatus(getString(R.string.loading), visible = true)
    val activeConfig = AccountStore.activeAccount(prefs)
    xtreamProviderConfigs = if (activeConfig?.type == "xtream") mapOf(activeConfig.id to activeConfig) else emptyMap()
    // Every type, not just Xtream, and regardless of enabled state - a cached catalog can
    // still contain items from a provider that's since been switched off, and their chips
    // should still say where they came from.
    providerNamesById = AccountStore.load(prefs).associate { it.id to it.name }
    // Toggling/adding/removing providers in quick succession each calls this with no
    // ordering guarantee between the launched coroutines - without cancelling the
    // previous one, whichever network fetch happens to finish last wins and gets written
    // to allChannels/ChannelCache, which can silently persist a stale provider list.
    //
    // Waited on, not just cancelled: cancel() only sets a flag and returns, so the outgoing
    // load carried on fetching while the new one started. Against one Stalker portal that
    // meant several handshakes and two 70MB catalogue streams in flight at once - enough on
    // its own to trip the portal's rate limit (every call after the handshake came back
    // "Connection reset") and to double the peak memory of the load.
    val previousLoad = providerLoadJob
    providerLoadJob = scope.launch {
        previousLoad?.cancelAndJoin()
        filmsSeriesDeriveJob?.cancel()
        // The cached catalog is authoritative until it goes stale: re-fetching every
        // launch means several seconds of "Loading..." and, on a large catalog, real
        // work for a result that is almost always identical. Providers change rarely,
        // so the network is only worth hitting once every CATALOG_TTL_MS - or right
        // away when the user changes a provider, which force-refreshes.
        var cached: List<Channel>? = null
        if (!forceRefresh) {
            cached = withContext(Dispatchers.IO) { ChannelCache.load(this@loadAllConfiguredProviders) }
            if (!cached.isNullOrEmpty()) {
                // The content-type gates apply to a cached cold start too - the cache
                // is saved unfiltered, so types switched off since it was written would
                // otherwise resurrect here (a cache saved with VOD on, for example).
                // Both lists are read once, not per channel: each is a JSON parse, and this
                // filter runs across a catalogue of tens of thousands of items.
                val typeGates = listOfNotNull(AccountStore.activeAccount(prefs))
                cached = if (isVodDisabled()) {
                    cached.filter { it.mediaType == MediaType.LIVE && isTypeAllowed(it, typeGates) }
                } else {
                    cached.filter { isTypeAllowed(it, typeGates) }
                }
                cached = stripLegacyAnimeRows(cached)
                // Paint the cached catalog immediately (Live first, films/series in background),
                // then only hit the network when the cache is stale - a non-stale cache returns
                // here; a stale one falls through and refreshes silently under the content.
                val hadContentOnScreen = uiPainted // content already rendered before this cached paint?
                allChannels = cached
                classifyAndShowLiveFirst()
                uiPainted = true
                deriveFilmsSeries()
                // On a cold start the initial tab render runs through selectTab (reached from
                // classifyAndShowLiveFirst above), which clears the status itself once content
                // lands. Clearing here first - before that render - blanked the screen into a
                // "Loading..." -> blank -> "Loading..." sandwich, because selectTab re-raises
                // the same message on entry. Only drop the status when content was already on
                // screen before this cached paint (a warm re-load).
                if (hadContentOnScreen) setStatus("", visible = false)
                // A cache written before a Channel field existed reads back with that
                // field at its default for every item - which is why Catch Up stayed
                // hidden on an upgraded install: tvArchive was false catalogue-wide until
                // something refetched. Treat that file as stale so the refresh below runs
                // once, silently, under the content already on screen.
                if (!isCatalogStale() && !ChannelCache.lastLoadWasLegacyFormat) return@launch
            }
        }

        val combined = mutableListOf<Channel>()
        val errors = mutableListOf<String>()
        var expiryText: String? = null

        val enabledConfigs = AccountStore.activeAccount(prefs)?.let { listOf(it) } ?: emptyList()
        if (!uiPainted && enabledConfigs.isNotEmpty()) {
            setStatus(
                if (enabledConfigs.size == 1) getString(R.string.plug_connecting_to, enabledConfigs.first().name)
                else getString(R.string.plug_connecting_to_n, enabledConfigs.size),
                visible = true
            )
        }
        // Fetched concurrently, not one after another - they used to run sequentially, so
        // a single dead/slow provider (up to PROVIDER_FETCH_TIMEOUT_MS - a Stalker portal
        // alone walks up to 200 live pages plus 50 each of VOD and series, each with its own
        // retries and backoff) held up every provider after it in the list. A routine
        // remove/toggle that left one stale provider behind therefore read as the whole app
        // freezing for minutes. Each is still individually bounded by the same timeout and
        // reported as failed on its own if it can't answer in time.
        val fetchResults = enabledConfigs.map { config ->
            async {
                val result = withTimeoutOrNull(PROVIDER_FETCH_TIMEOUT_MS) {
                    when (config.type) {
                        "xtream" -> fetchXtreamChannels(config) { expiryText = it }
                        "stalker" -> fetchStalkerChannels(config) { live ->
                            mergeProviderPartial(config.id, live)
                            renderLivePartial()
                        }
                        else -> fetchM3uChannels(config)
                    }
                } ?: FetchResult.Failure("timed out")
                if (result is FetchResult.Success) {
                    mergeProviderPartial(config.id, result.channels)
                    renderLivePartial()
                }
                config to result
            }
        }.awaitAll()
        // Enabled ids are re-read here rather than reusing the pre-loop snapshot: toggling
        // a provider off mid-refresh drops its id from this set, so the channels it just
        // fetched can't slip back into the catalog.
        val enabledProviderIds = AccountStore.activeAccountId(prefs)?.let { setOf(it) } ?: emptySet()
        for ((config, result) in fetchResults) {
            when (result) {
                is FetchResult.Success ->
                    combined += result.channels.filter { it.sourceProviderId == null || it.sourceProviderId in enabledProviderIds }
                is FetchResult.Failure -> errors += "${config.name}: ${result.message}"
            }
        }

        // A refresh that produced nothing must not wipe the cached catalog off disk or off the
        // screen: keep the previously-cached allChannels, surface the errors, and don't stamp
        // the TTL (a stamp here would leave the app on stale data for the whole TTL window).
        // Covers both the stale-cache refresh and a forceRefresh (e.g. a VOD-toggle reload)
        // whose fetches all failed - forceRefresh never loads `cached` up front, so fall back
        // to reading the disk cache here.
        if (combined.isEmpty()) {
            val fallback = (cached ?: withContext(Dispatchers.IO) { ChannelCache.load(this@loadAllConfiguredProviders) })
                ?.let { stripLegacyAnimeRows(it) }
            if (!fallback.isNullOrEmpty()) {
                allChannels = fallback
                filmsSeriesDeriveJob?.cancel()
            }
            // classifyAndShow() has to run even when nothing above changed allChannels -
            // skipping this call left a provider-less setup (no IPTV channels,
            // empty/no disk cache) with nothing ever painted past the toolbar: uiPainted
            // stays false and no later event re-triggers a render.
            classifyAndShow(preserveUi = shouldPreserveUiOnLoad())
            setStatus("", visible = false)
            if (errors.isNotEmpty()) {
                Toast.makeText(this@loadAllConfiguredProviders, errors.joinToString(" · "), Toast.LENGTH_LONG).show()
            }
            return@launch
        }

        allChannels = combined
        filmsSeriesDeriveJob?.cancel()
        classifyAndShow(preserveUi = shouldPreserveUiOnLoad())
        persistCatalog(allChannels)
        // Only a load that actually produced a catalog resets the TTL - stamping it on a
        // total failure would leave the app sitting on an empty catalog for 12 hours.
        if (combined.isNotEmpty()) {
            prefs.edit().putLong(PREF_CATALOG_REFRESHED_AT, System.currentTimeMillis()).apply()
        }

        if (combined.isEmpty()) {
            // Don't raise the status row here - it lives in the same weight=1 slot as the
            // empty state, so showing both splits the screen in half and the relayout
            // steals focus off the empty-state buttons. The empty state is the message;
            // surface any real fetch errors as a toast instead of a persistent bar.
            setStatus("", visible = false)
            if (errors.isNotEmpty()) {
                Toast.makeText(this@loadAllConfiguredProviders, errors.joinToString(" · "), Toast.LENGTH_LONG).show()
            }
        } else {
            val summary = getString(R.string.plug_item_count, combined.size) +
                (expiryText?.let { "  ·  $it" } ?: "") +
                (errors.takeIf { it.isNotEmpty() }?.let { "  ·  ⚠ " + it.joinToString(", ") } ?: "")
            setStatus(summary, visible = true)
            // A refresh over existing content can't use the status row (suppressed while a
            // pane owns the slot), so the outcome would otherwise be silent - and a failed
            // provider is exactly what the user needs told.
            if (binding.statusRow.visibility != View.VISIBLE) {
                Toast.makeText(this@loadAllConfiguredProviders, summary, Toast.LENGTH_LONG).show()
            }
            if (errors.isEmpty()) mainHandler.postDelayed({ setStatus("", visible = false) }, 4000)
        }
    }
}

/** [onLive] fires with the live channels alone as soon as they land, before VOD/series are
 *  even requested - a portal with tens of thousands of live channels plus a large VOD/series
 *  library used to hold all three in memory at once before anything was shown, which is
 *  what ran a low-RAM box out of heap (lowmemorykiller killing the process) and read as the
 *  whole app freezing. Splitting the fetch also gets Live TV on screen while VOD/series -
 *  the slower, bulkier part - are still loading. */
internal suspend fun MainActivity.fetchStalkerChannels(config: AccountConfig, onLive: suspend (List<Channel>) -> Unit): FetchResult {
    return try {
        val mac = config.userAgent ?: return FetchResult.Failure("no MAC address")
        val stalkerProvider = Provider(
            name = config.name, type = ProviderType.M3U,
            serverUrl = config.url?.let { normalizeServerUrl(it) }, userAgent = mac
        )
        val stalker = StalkerProvider(BaseApplication.instance.okHttpClient)
        // sourceProviderId ties each item back to this portal config, so the play step
        // can re-auth against the right one to resolve a Stalker VOD create_link.
        fun tag(channels: List<Channel>) = channels.map { it.copy(streamUserAgent = mac, sourceProviderId = config.id) }

        val liveResult = withContext(Dispatchers.IO) { stalker.loadLiveChannels(stalkerProvider) }
        if (liveResult.isFailure) return FetchResult.Failure(liveResult.exceptionOrNull()?.message?.take(60) ?: "error")
        val live = tag(liveResult.getOrThrow()).filter { providerAllowsLive(config) }
        onLive(live)

        // Content-type gates: a live-only setup never touches the (slow) VOD/series fetch.
        if (!providerAllowsMovies(config) && !providerAllowsSeries(config)) return FetchResult.Success(live)

        val vodSeriesResult = withContext(Dispatchers.IO) { stalker.loadVodAndSeries(stalkerProvider) }
        if (vodSeriesResult.isFailure) return FetchResult.Failure(vodSeriesResult.exceptionOrNull()?.message?.take(60) ?: "error")
        val (films, series) = vodSeriesResult.getOrThrow()
        FetchResult.Success(
            live +
                tag(films).filter { providerAllowsMovies(config) } +
                tag(series).filter { providerAllowsSeries(config) }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(60) ?: "error")
    }
}

// ── M3U / Xtream channel fetch ─────────────────

internal suspend fun MainActivity.fetchM3uChannels(config: AccountConfig): FetchResult {
    val url = config.url ?: return FetchResult.Failure("no URL")
    return try {
        val result = withContext(Dispatchers.IO) { M3uParser.parseFromUrl(url, BaseApplication.instance.okHttpClient) }
        // Content-type gates: an M3U file lists live and VOD in one parse - drop the
        // types this provider has switched off, per mediaType.
        val channels = result.channels.filter { ch ->
            when (ch.mediaType) {
                MediaType.LIVE -> providerAllowsLive(config)
                MediaType.MOVIE -> providerAllowsMovies(config)
                MediaType.SERIES -> providerAllowsSeries(config)
            }
        }
        // sourceProviderId isn't needed for playback here (an M3U item's url is already
        // final), but it's what names the provider a duplicate came from on the detail
        // screen's version chips - without it every M3U copy is an anonymous "Version N".
        FetchResult.Success(channels.map { it.copy(streamUserAgent = config.userAgent, sourceProviderId = config.id) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(60) ?: "error")
    }
}

internal suspend fun MainActivity.fetchXtreamChannels(config: AccountConfig, onExpiry: (String?) -> Unit): FetchResult {
    return try {
        val xtreamProvider = Provider(
            name = config.name, type = ProviderType.XTREAM,
            serverUrl = config.url?.let { normalizeServerUrl(it) },
            username = config.username, password = config.password
        )
        val client = XtreamClient(BaseApplication.instance.okHttpClient)
        val authResult = withContext(Dispatchers.IO) { client.authenticate(xtreamProvider) }
        val auth = authResult.getOrElse { return FetchResult.Failure(it.message?.take(60) ?: "auth error") }
        if (!auth.valid) return FetchResult.Failure("auth failed - check server URL and credentials")
        // Remembered for EPG lookups and the subscription-expiry line, both inherently
        // "the" Xtream account concepts - first enabled Xtream provider wins if there's
        // more than one configured.
        provider = xtreamProvider

        val live: List<Channel>
        val films: List<Channel>
        val series: List<Channel>
        withContext(Dispatchers.IO) {
            // Content-type gates: the (slow) VOD/series category+stream fetches are
            // skipped entirely for types this provider has switched off.
            val liveOff = !providerAllowsLive(config)
            val moviesOff = !providerAllowsMovies(config)
            val seriesOff = !providerAllowsSeries(config)
            val liveCatsDeferred: Deferred<List<Pair<String, String>>>? = if (liveOff) null else async { runCatching { client.getLiveCategories(xtreamProvider) }.getOrDefault(emptyList()) }
            val vodCatsDeferred: Deferred<List<Pair<String, String>>>? = if (moviesOff) null else async { runCatching { client.getVodCategories(xtreamProvider) }.getOrDefault(emptyList()) }
            val seriesCatsDeferred: Deferred<List<Pair<String, String>>>? = if (seriesOff) null else async { runCatching { client.getSeriesCategories(xtreamProvider) }.getOrDefault(emptyList()) }
            val liveDeferred: Deferred<List<Channel>>? = if (liveOff) null else async { client.getLiveStreams(xtreamProvider) }
            val filmsDeferred: Deferred<List<Channel>>? = if (moviesOff) null else async { client.getVodStreams(xtreamProvider) }
            val seriesDeferred: Deferred<List<Channel>>? = if (seriesOff) null else async { client.getSeries(xtreamProvider) }

            val liveCatNames = liveCatsDeferred?.await()?.toMap() ?: emptyMap()
            val vodCatNames = vodCatsDeferred?.await()?.toMap() ?: emptyMap()
            val seriesCatNames = seriesCatsDeferred?.await()?.toMap() ?: emptyMap()

            // Resolve each stream's category name from the authoritative category list.
            // get_vod_streams (and often get_live_streams) carries only a numeric category_id
            // and no category_name, and some panels tag streams with category_ids that never
            // appear in get_*_categories at all - 860 VOD items on one live provider. Left
            // as-is those rendered as a sidebar row literally titled "1411"/"1071". When the id
            // can't be resolved and the stream has no name of its own, fold it into a single
            // "Uncategorised" row (shared id) rather than one bare-number row per orphan id.
            fun withCategory(ch: Channel, names: Map<String, String>, uncatId: String): Channel {
                val resolved = names[ch.categoryId]
                val mapped = when {
                    resolved != null -> ch.copy(categoryName = resolved)
                    !ch.categoryName.isNullOrBlank() -> ch
                    else -> ch.copy(categoryId = uncatId, categoryName = "Uncategorised")
                }
                return mapped.copy(sourceProviderId = config.id)
            }
            live = liveDeferred?.await()?.map { withCategory(it, liveCatNames, "uncat_live") } ?: emptyList()
            films = filmsDeferred?.await()?.map { withCategory(it, vodCatNames, "uncat_vod") } ?: emptyList()
            series = seriesDeferred?.await()?.map { withCategory(it, seriesCatNames, "uncat_series") } ?: emptyList()
        }

        // Persist the subscription expiry so Settings' "Active until / Expired" line can
        // display - xtream_exp_date / xtream_is_trial were never written anywhere before,
        // so the Settings read always came back empty. putString(key, null) removes the
        // key, which drops a stale expiry when the account stops reporting one.
        prefs.edit()
            .putString("xtream_exp_date", auth.expDateSeconds?.toString())
            .putBoolean("xtream_is_trial", auth.isTrial)
            .apply()
        onExpiry(formatSubscriptionStatus(auth.expDateSeconds, auth.isTrial))
        FetchResult.Success(live + films + series)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message?.take(60) ?: "error")
    }
}

// ── First-run startup chooser (empty state) ──

/** Wires the empty state's two entry points. An IPTV provider is required before the app
 *  has anything to show, and only its M3U/Xtream flavors are offered - each button opens
 *  the provider form already on that type. Only reachable while there is neither a provider
 *  nor a plugin enabled - showEmptyState() is the sole caller of the screen that hosts them. */
internal fun MainActivity.wireStartupChooser() {
    binding.emptyChooseM3u.setOnClickListener { showProviderSettings("m3u") }
    binding.emptyChooseXtream.setOnClickListener { showProviderSettings("xtream") }
}
