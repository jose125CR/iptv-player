package com.lumora

import android.animation.AnimatorInflater
import android.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import android.net.Uri
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.data.DeviceIdentity
import com.lumora.download.DownloadStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.model.Provider
import com.lumora.model.AccountConfig
import com.lumora.data.AccountStore
import com.lumora.pairing.QrPairingManager
import com.lumora.player.PlayerManager
import com.lumora.util.normalizeServerUrl
import com.lumora.data.local.entity.EpgSourceEntity
import com.lumora.data.backup.BackupManager

import com.lumora.data.update.AppUpdateChecker
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.Locale

// ── Provider settings, EPG sources & backup ──
//
// Extracted from MainActivity.kt; see that file's header.
internal fun MainActivity.showAddEpgSourceDialog() {
    val input = EditText(this).apply {
        hint = getString(R.string.sett_epg_source_url_hint)
        inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val nameInput = EditText(this).apply {
        hint = getString(R.string.sett_epg_source_name_hint)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
    }
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 20, 40, 20)
        addView(nameInput)
        addView(input)
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.sett_add_epg_source_title))
        .setView(layout)
        .setPositiveButton(getString(R.string.add)) { _, _ ->
            val name = nameInput.text.toString().trim().ifBlank { "EPG ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}" }
            val url = input.text.toString().trim()
            if (url.isBlank()) { Toast.makeText(this, getString(R.string.sett_enter_url), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            scope.launch {
                database.epgSourceDao().insert(
                    EpgSourceEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        url = url
                    )
                )
                // Fetch it now rather than leaving the new source dark until the next 6h
                // periodic run — adding a source and seeing no guide for hours reads as broken.
                com.lumora.data.sync.EpgSyncWorker.enqueue(this@showAddEpgSourceDialog)
                Toast.makeText(this@showAddEpgSourceDialog, getString(R.string.sett_epg_source_added), Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}



/** A Filters-pane checkbox with a dimmed caption line under its title - the other filter
 *  toggles carry a single line, but these need the caption to say what the toggle changes
 *  about playback. Wired straight to [key] in the shared "iptv_prefs" file, so PlayerManager
 *  sees the same value (subtitles_with_dub, subtitles_enabled) without any extra plumbing. Styled to match the
 *  static pane rows (hide-adult row's card surface, focus scale, and text hierarchy) so
 *  runtime-added rows don't read as cheaper than their XML siblings. */
/** A settings row that picks a language into [key]. Sits in General with the other
 *  whole-app choices; the Subtitles on/off switch stays under Filters with the rest of
 *  the playback toggles. */
internal fun MainActivity.languageChoiceRow(
    title: String,
    key: String,
    caption: String,
    choices: List<Pair<String, String>> = PLAYBACK_LANGUAGES,
    onSelect: ((String) -> Unit)? = null
): TextView {
    val row = TextView(this)
    row.setTextColor(getColor(R.color.text_primary))
    row.setBackgroundResource(R.drawable.card_surface_background)
    val hPad = resources.getDimensionPixelSize(R.dimen.settings_gap_l)
    val vPad = resources.getDimensionPixelSize(R.dimen.settings_row_padding_vertical)
    row.setPadding(hPad, vPad, hPad, vPad)
    row.stateListAnimator = AnimatorInflater.loadStateListAnimator(this, R.animator.focus_scale_flat)
    row.isClickable = true
    row.isFocusable = true
    row.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.settings_gap_m) }

    fun render() {
        val current = prefs.getString(key, "es") ?: "es"
        val display = choices.firstOrNull { it.first == current }?.second ?: current.uppercase()
        row.text = twoLineSettingsText(title, getString(R.string.sett_language_row, display, caption))
    }
    render()
    row.setOnClickListener {
        val codes = choices.map { it.first }
        val current = prefs.getString(key, "es") ?: "es"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(
                choices.map { it.second }.toTypedArray(),
                codes.indexOf(current).coerceAtLeast(0)
            ) { dialog, which ->
                prefs.edit().putString(key, codes[which]).apply()
                onSelect?.invoke(codes[which])
                render()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    return row
}

internal fun MainActivity.languageName(code: String): String =
    PLAYBACK_LANGUAGES.firstOrNull { it.first == code }?.second ?: code.uppercase()

/** The medium-title-over-grey-caption text every settings row uses. Shared so a plain
 *  row and a CheckBox row can't drift apart. */
internal fun MainActivity.twoLineSettingsText(title: String, subtitle: String): SpannableStringBuilder {
    val titleEnd = title.length
    val captionStart = titleEnd + 1 // skip the "\n"
    val text = SpannableStringBuilder(title).append("\n").append(subtitle)
    val bodySize = resources.getDimensionPixelSize(R.dimen.settings_text_body)
    val captionSize = resources.getDimensionPixelSize(R.dimen.settings_text_caption)
    val titleFont = ResourcesCompat.getFont(this, R.font.inter_medium) ?: Typeface.DEFAULT
    val captionFont = ResourcesCompat.getFont(this, R.font.inter_regular) ?: Typeface.DEFAULT
    text.setSpan(MainActivity.FontSpan(titleFont), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(AbsoluteSizeSpan(bodySize), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(MainActivity.FontSpan(captionFont), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(AbsoluteSizeSpan(captionSize), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), captionStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return text
}

internal fun MainActivity.dubCheckBoxRow(title: String, subtitle: String, key: String, onToggle: ((Boolean) -> Unit)? = null): CheckBox {
    val checkBox = CheckBox(this)
    checkBox.text = twoLineSettingsText(title, subtitle)
    checkBox.setTextColor(getColor(R.color.text_primary))
    checkBox.setBackgroundResource(R.drawable.card_surface_background)
    val hPad = resources.getDimensionPixelSize(R.dimen.settings_gap_l)
    val vPad = resources.getDimensionPixelSize(R.dimen.settings_row_padding_vertical)
    checkBox.setPadding(hPad, vPad, hPad, vPad)
    checkBox.stateListAnimator = AnimatorInflater.loadStateListAnimator(this, R.animator.focus_scale_flat)
    checkBox.isClickable = true
    checkBox.isFocusable = true
    checkBox.isChecked = prefs.getBoolean(key, false)
    checkBox.setOnCheckedChangeListener { _, checked ->
        prefs.edit().putBoolean(key, checked).apply()
        onToggle?.invoke(checked)
    }
    checkBox.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = resources.getDimensionPixelSize(R.dimen.settings_gap_m)
    }
    return checkBox
}

/** Whether the Settings rail is collapsed on this device: a persisted pref, except on a
 *  portrait phone, where the rail auto-hides and only the transient (unpersisted)
 *  [MainActivity.portraitSettingsRailExpanded] can bring it back - mirrors the category
 *  rail's isSidebarCollapsed() exactly. */
internal fun MainActivity.isSettingsRailCollapsed(): Boolean =
    if (isPortraitPhone()) !portraitSettingsRailExpanded
    else prefs.getBoolean(PREF_SETTINGS_RAIL_COLLAPSED, false)

/** Single place that decides the Settings rail's visibility: collapses the rail + divider
 *  and shows the floating re-expand pill in their place (the content ScrollView already
 *  fills the freed width via its layout_weight, so nothing else moves). The settings tree
 *  is inflated fresh on every open and survives rotation in place, so this is applied at
 *  inflation time in showProviderSettings() and re-applied from onConfigurationChanged.
 *  Like the category rail, the pill's footprint is reserved as extra top padding on the
 *  content scroll, so the pill floats over empty space rather than the pane's first row. */
internal fun MainActivity.applySettingsRailVisibility(root: View? = null) {
    // showProviderSettings() applies this at inflation time, which is before the tree is
    // reachable as activeSettingsOverlay (that field is only assigned just before show()) -
    // so it passes its dialogView in explicitly. Without the parameter the open-time call
    // hit the null overlay and returned, and the rail only ever collapsed on rotation.
    val view = root ?: activeSettingsOverlay?.view ?: return
    val collapsed = isSettingsRailCollapsed()
    view.findViewById<View>(R.id.settingsNavRail).visibility = if (collapsed) View.GONE else View.VISIBLE
    view.findViewById<View>(R.id.settingsNavDivider).visibility = if (collapsed) View.GONE else View.VISIBLE
    view.findViewById<View>(R.id.settingsExpandRailButton).visibility = if (collapsed) View.VISIBLE else View.GONE
    val contentScroll = view.findViewById<View>(R.id.settingsContentScroll)
    val reservePx = (56 * resources.displayMetrics.density).toInt()
    contentScroll.setPadding(
        contentScroll.paddingLeft,
        if (collapsed) reservePx else 0,
        contentScroll.paddingRight,
        contentScroll.paddingBottom
    )
}

/** Collapses the Settings rail from its Collapse row: the state persists (or flips the
 *  portrait transient), the rail hides, and focus moves to the re-expand pill since the
 *  focused row is about to disappear - mirrors collapseCategorySidebar(). */
internal fun MainActivity.collapseSettingsRail() {
    if (isPortraitPhone()) portraitSettingsRailExpanded = false
    else prefs.edit().putBoolean(PREF_SETTINGS_RAIL_COLLAPSED, true).apply()
    applySettingsRailVisibility()
    activeSettingsOverlay?.view?.findViewById<View>(R.id.settingsExpandRailButton)?.requestFocus()
    // Names the way back at the one moment the user is guaranteed to be looking at this
    // corner of the screen - an accidental collapse otherwise reads as a dead end.
    Toast.makeText(this, R.string.settings_hidden_hint, Toast.LENGTH_SHORT).show()
}

/** Applies a Typeface to a span range independent of the TextView's own typeface - lets a
 *  single two-line TextView carry a medium title over a regular caption. (TypefaceSpan's
 *  Typeface constructor is API 28+, so this hand-rolled span keeps minSdk 25 happy.) */

@Suppress("DEPRECATION")
internal fun MainActivity.showProviderSettings(presetProviderType: String? = null) {
    // Already open: unticking the last provider or plugin from inside Settings reloads, and
    // that load's "nothing configured" branch calls straight back in here - which would
    // inflate a second settings tree on top of the live one, leaving the first orphaned
    // behind it and only the second reachable by Back.
    if (activeSettingsOverlay != null) return
    // Close Search if it's open - the two share the weighted content slot and would otherwise
    // render stacked on top of each other (see showSearchDialog).
    activeSearchOverlay?.dismiss()
    // Every open of Settings on a portrait phone starts with the rail hidden, the same way
    // rotating into portrait re-hides the category rail: the panes need the full width to
    // be readable, and the tree is inflated fresh here anyway, so an expand from a previous
    // visit is not carried into this one.
    if (isPortraitPhone()) portraitSettingsRailExpanded = false
    val dialogView = layoutInflater.inflate(R.layout.activity_settings, null)

    val clearHistory = dialogView.findViewById<View>(R.id.settingsClearHistory)

    clearHistory.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sett_clear_history_confirm))
            .setMessage(getString(R.string.sett_clear_history_message))
            .setPositiveButton(getString(R.string.search_recents_clear)) { _, _ ->
                PlaybackPositionStore.clearAllAccounts(this)
                clearUpNextMemo()
                RecentlyPlayedStore.clearAll(this)
                Toast.makeText(this, getString(R.string.sett_history_cleared), Toast.LENGTH_SHORT).show()
                if (showingHome) selectHome()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    val dialog = MainActivity.FullScreenOverlay(
        binding.settingsContainer,
        dialogView,
        closeButton = dialogView.findViewById(R.id.settingsCancelButton),
        initialFocus = { null }
    )

    // EPG Source
    dialogView.findViewById<View>(R.id.settingsAddEpgSource).setOnClickListener {
        showEpgSourceListDialog()
    }

    // Recording storage
    dialogView.findViewById<View>(R.id.settingsRecordingStorage).setOnClickListener {
        Toast.makeText(this, getString(R.string.sett_recording_storage_path, "${filesDir}/recordings"), Toast.LENGTH_SHORT).show()
    }

    // Playback settings. Only entries that change something belong here — the Decoder /
    // Buffer / Surface / FFmpeg rows this dialog used to carry wrote prefs no part of the
    // playback stack ever read (Surface didn't even write one, it toasted "Surface mode
    // changed" and returned), so every one of them was a control that reported success and
    // did nothing.
    dialogView.findViewById<View>(R.id.settingsDecoderMode).setOnClickListener {
        val items = arrayOf(
            getString(R.string.sett_external_player_summary, externalPlayerSummary(this)),
            getString(R.string.sett_suggest_external_player_on_problems, if (prefs.getBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, true)) getString(R.string.sett_on) else getString(R.string.sett_off)),
            getString(R.string.legal_notice_title)
        )
        AlertDialog.Builder(this@showProviderSettings)
            .setTitle(getString(R.string.sett_playback_settings_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseDefaultExternalPlayer()
                    1 -> {
                        val on = !prefs.getBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, true)
                        prefs.edit().putBoolean(PREF_SUGGEST_EXTERNAL_PLAYER, on).apply()
                        Toast.makeText(this@showProviderSettings, getString(R.string.sett_suggest_external_player, if (on) getString(R.string.sett_on) else getString(R.string.sett_off)), Toast.LENGTH_SHORT).show()
                    }
                    2 -> showLegalNotice()
                }
            }
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    // A/V sync offset settings
    dialogView.findViewById<View>(R.id.settingsAvOffset).setOnClickListener {
        val current = avOffsetManager.getOffset()
        val presets = listOf("-500 ms", "-250 ms", "-100 ms", "-50 ms", "0 ms", "+50 ms", "+100 ms", "+250 ms", "+500 ms")
        val values = listOf(-500, -250, -100, -50, 0, 50, 100, 250, 500)
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this@showProviderSettings)
            .setTitle(getString(R.string.sett_av_sync_offset_title))
            .setSingleChoiceItems(presets.toTypedArray(), checked) { dialog, which ->
                avOffsetManager.save(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    val parentalPinRow = dialogView.findViewById<View>(R.id.settingsParentalPin)
    val parentalPinLabel = dialogView.findViewById<TextView>(R.id.settingsParentalPinLabel)

    // General pane: Simple mode + Disable VOD live here, not under Filters - they shape
    // the whole app (which tabs exist, what gets fetched), not the catalogue filters.
    val generalPane = dialogView.findViewById<LinearLayout>(R.id.paneGeneral)
    lateinit var vodCheckBox: CheckBox
    generalPane.addView(dubCheckBoxRow(
        getString(R.string.sett_simple_mode),
        getString(R.string.sett_simple_mode_caption),
        PREF_SIMPLE_MODE
    ) { checked ->
        // Simple mode drives the VOD toggle so the two checkboxes never disagree:
        // on -> VOD disabled (box checked), off -> VOD re-enabled (box unchecked).
        prefs.edit().putBoolean(PREF_DISABLE_VOD, checked).apply()
        vodCheckBox.isChecked = checked
        applySimpleModeUi()
        // Simple mode forces VOD off, so its effective state changed with the toggle.
        vodStateChanged()
    })
    vodCheckBox = dubCheckBoxRow(
        getString(R.string.sett_disable_vod),
        getString(R.string.sett_disable_vod_caption),
        PREF_DISABLE_VOD
    ) { vodStateChanged() }
    generalPane.addView(vodCheckBox)
    generalPane.addView(
        languageChoiceRow(
            getString(R.string.settings_ui_language),
            PREF_UI_LANGUAGE,
            getString(R.string.settings_ui_language_caption),
            choices = UI_LANGUAGES,
            onSelect = { code -> onUiLanguageSelected(code) }
        )
    )
    generalPane.addView(
        languageChoiceRow(
            getString(R.string.sett_audio_language),
            PREF_AUDIO_LANGUAGE,
            getString(R.string.sett_audio_language_caption)
        )
    )
    generalPane.addView(
        languageChoiceRow(
            getString(R.string.sett_subtitle_language),
            PREF_SUBTITLE_LANGUAGE,
            getString(R.string.sett_subtitle_language_caption)
        )
    )

    // StreamVault-style nav rail: one section visible at a time.
    val navRows = listOf(
        R.id.navPlayback to R.id.panePlayback,
        R.id.navPrivacy to R.id.panePrivacy,
        R.id.navEpg to R.id.paneEpg,
        R.id.navDownloads to R.id.paneDownloads,
        R.id.navGeneral to R.id.paneGeneral,
        R.id.navAbout to R.id.paneAbout
    ).map { (navId, paneId) -> dialogView.findViewById<View>(navId) to dialogView.findViewById<View>(paneId) }
    var activeSection = 0
    fun selectSection(index: Int) {
        activeSection = index
        navRows.forEachIndexed { i, (row, pane) ->
            row.isSelected = i == index
            pane.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        // Reachable from code, not just a rail click (e.g. onProviderAdded() jumping here)
        // - without this the D-pad's focus is left on
        // whatever view triggered the jump, which has often just been removed from the
        // tree by the same re-render, leaving nothing focused and the remote stuck.
        // With the rail collapsed the row is gone - leave focus where it is (the expand
        // pill) rather than requesting focus on a GONE view, which silently does nothing.
        if (!isSettingsRailCollapsed()) navRows[index].first.requestFocus()
    }
    navRows.forEachIndexed { i, (row, _) -> row.setOnClickListener { selectSection(i) } }

    // Collapse/expand of the rail itself, mirroring the category sidebar: a "Collapse" row
    // at the top of the rail hides it, and the floating pill that replaces it brings it
    // back and refocuses the section that was selected.
    dialogView.findViewById<View>(R.id.navCollapseRail).setOnClickListener { collapseSettingsRail() }
    val settingsExpandRailButton = dialogView.findViewById<View>(R.id.settingsExpandRailButton)
    settingsExpandRailButton.setOnClickListener {
        // Portrait's auto-hide is transient state, not the pref - see isSettingsRailCollapsed().
        if (isPortraitPhone()) portraitSettingsRailExpanded = true
        else prefs.edit().putBoolean(PREF_SETTINGS_RAIL_COLLAPSED, false).apply()
        applySettingsRailVisibility()
        // Rows aren't laid out the instant the rail becomes visible again - retry once on
        // the next frame (same double-post pattern as the category rail's re-expand).
        val row = navRows[activeSection].first
        settingsExpandRailButton.post {
            if (!row.isShown || !row.requestFocus()) {
                settingsExpandRailButton.post { row.requestFocus() }
            }
        }
    }
    // Single canonical apply point: the tree is inflated fresh on every open, so the
    // collapsed state (persisted pref, or a portrait phone's transient auto-hide) is
    // applied here, before the first selectSection so nothing requests focus on a row
    // that is about to disappear. Re-applied on rotation from onConfigurationChanged.
    // dialogView is passed because activeSettingsOverlay is not assigned until show().
    applySettingsRailVisibility(dialogView)
    selectSection(0)

    // A TV box has nowhere meaningful to browse a downloaded file (same reasoning
    // as the Downloads tab being mobile-only).
    dialogView.findViewById<View>(R.id.navDownloads).visibility = if (isTv) View.GONE else View.VISIBLE

    // Downloads pane reuses the exact same adapter/data as the Downloads tab -
    // RecyclerView supports multiple views sharing one adapter instance fine.
    dialogView.findViewById<RecyclerView>(R.id.settingsDownloadsList).apply {
        layoutManager = LinearLayoutManager(this@showProviderSettings)
        adapter = downloadAdapter
    }
    val settingsDownloadsEmptyText = dialogView.findViewById<TextView>(R.id.settingsDownloadsEmptyText)
    if (!isTv) {
        scope.launch {
            val records = withContext(Dispatchers.IO) { DownloadStore.getAll(this@showProviderSettings) }
            settingsDownloadsEmptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    refreshDownloadsList()

    // About pane
    dialogView.findViewById<TextView>(R.id.settingsAppVersion).text = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.versionCode})"
    } catch (e: Exception) { getString(R.string.sett_unknown) }
    dialogView.findViewById<TextView>(R.id.settingsDeviceMac).text = DeviceIdentity.getDeviceId(this) ?: "---"
    dialogView.findViewById<TextView>(R.id.settingsDeviceKey).text = DeviceIdentity.getKey(this) ?: "---"
    val checkUpdateLabel = dialogView.findViewById<TextView>(R.id.settingsCheckUpdateLabel)
    dialogView.findViewById<View>(R.id.settingsCheckUpdate).setOnClickListener {
        checkUpdateLabel.text = getString(R.string.sett_checking)
        scope.launch {
            val updater = AppUpdateChecker(this@showProviderSettings)
            val info = withContext(Dispatchers.IO) { updater.checkForUpdate() }
            checkUpdateLabel.text = getString(R.string.check_for_updates)
            when {
                info == null -> Toast.makeText(this@showProviderSettings, getString(R.string.sett_couldnt_check_updates), Toast.LENGTH_SHORT).show()
                info.isUpdateAvailable && info.downloadUrl.isNotBlank() -> {
                    AlertDialog.Builder(this@showProviderSettings)
                        .setTitle(getString(R.string.sett_update_available))
                        .setMessage(getString(R.string.sett_update_available_message, info.latestVersion, info.currentVersion, info.releaseNotes.take(200).replace("%", "%%")))
                        .setPositiveButton(getString(R.string.update)) { _, _ -> downloadAndInstallUpdate(info.downloadUrl, info.latestVersion) }
                        .setNegativeButton(getString(R.string.sett_later), null)
                        .show()
                }
                else -> Toast.makeText(this@showProviderSettings, getString(R.string.sett_latest_version), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Init UI
    // No selectType() call here - the form starts closed with no type chosen; see
    // openIptvForm()/closeIptvForm() above for how that gets set per add/edit.
    // Hide whatever the active tab is showing so it doesn't render doubled-up behind
    // Settings in the same weight=1 slot - restored on dismiss below. That includes
    // Home's search bar, which sits outside homeContent/contentRow and so used to stay
    // on screen above Settings as if it belonged to it.
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.contentRow.visibility = View.GONE
    binding.emptyState.visibility = View.GONE
    dialog.setOnDismissListener {
        qrManager.stop()
        // Same: a device-code poll is only meaningful while its code is on screen, and its
        // callbacks write into views that are about to be detached.
        traktSignInJob?.cancel()
        traktSignInJob = null
        activeSettingsOverlay = null
        applyStatus()
        // The tab bar and search are gated on there being something to browse, and
        // classifyAndShow() deliberately skips that check while this overlay is up (it
        // would flip the chrome underneath the dialog). Adding a provider is exactly what
        // changes the answer, so re-derive it here - on the non-empty path nothing else
        // did, and the tab bar stayed hidden until the app was restarted. showEmptyState()
        // runs it itself on the other branch.
        //
        // "Nothing to show" is the same question classifyAndShow() asks. Testing
        // allChannels alone sent a provider-less setup back to the "no provider" empty
        // state the moment Settings closed.
        // Unticking the last provider has to take the tab bar and search back down, and
        // land on the empty state - which is the only screen left with a way back into
        // Settings. Asked of the enabled providers rather than of allChannels: disabling
        // one drops its items, but a provider whose channels are still in memory from a
        // cache load would otherwise keep the chrome up with nothing enabled behind it.
        // Saving a provider starts its fetch and leaves Settings open; closing before that
        // fetch lands used to read as "returned nothing" and put the empty state up, which
        // is the screen the user then sat on. An in-flight load has not returned anything
        // yet - let the tab render empty under the "Loading..." status and let the load's
        // own classifyAndShow fill it in.
        val loadInFlight = providerLoadJob?.isActive == true
        if (!hasProviderEnabled()) {
            showEmptyState()
        } else if (allChannels.isEmpty() && !loadInFlight) {
            // Enabled, but it returned nothing (fetch failed, or an empty catalogue).
            showEmptyState()
        } else {
            binding.emptyState.visibility = View.GONE
            updateTopChromeVisibility()
            if (showingHome) selectHome() else if (showingDownloads) selectDownloads() else selectTab(activeTab)
        }
    }
    activeSettingsOverlay = dialog
    // The overlay takes the slot now; a load still narrating into it must come down.
    applyStatus()
    dialog.show()

    // The Save button's listener validates and keeps the form open on error instead of
    // dismissing unconditionally. Only acts when the add/edit form is actually open -
    // the same footer button is shared by every nav pane, most of which have nothing
    // for it to save.
    dialogView.findViewById<View>(R.id.settingsSaveButton).setOnClickListener {
        activeSettingsOverlay?.dismiss()
    }
}

// ── Parental PIN ───────────────────────────────

/** Shows the detail screen's "Find Stream" button when a stream-search plugin is enabled,
 *  and only for movies - a series detail screen isn't a single episode, so there's nothing
 *  specific to resolve from here (per-episode search would hang off the episode list). */

internal fun MainActivity.hasParentalPin(): Boolean = !prefs.getString(PREF_PARENTAL_PIN, null).isNullOrBlank()

/** 4-digit PIN entry. Calls onCorrect only if it matches the saved PIN. */
internal fun MainActivity.promptForPin(title: String, onCorrect: () -> Unit) {
    val input = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton(getString(R.string.sett_ok)) { _, _ ->
            if (input.text.toString() == prefs.getString(PREF_PARENTAL_PIN, null)) {
                onCorrect()
            } else {
                Toast.makeText(this, getString(R.string.sett_incorrect_pin), Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/** Sets (or changes) the 4-digit PIN - entered twice so a typo doesn't lock the user out. */
internal fun MainActivity.showSetPinDialog(label: TextView) {
    val input = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        hint = getString(R.string.sett_pin_hint)
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.set_parental_pin))
        .setView(input)
        .setPositiveButton(getString(R.string.sett_next)) { _, _ ->
            val pin = input.text.toString()
            if (pin.length != 4) {
                Toast.makeText(this, getString(R.string.sett_pin_length_error), Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val confirm = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                hint = getString(R.string.sett_confirm_pin_hint)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sett_confirm_pin_title))
                .setView(confirm)
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    if (confirm.text.toString() == pin) {
                        prefs.edit().putString(PREF_PARENTAL_PIN, pin).apply()
                        label.text = getString(R.string.sett_change_parental_pin)
                        Toast.makeText(this, getString(R.string.sett_parental_pin_set), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.sett_pins_didnt_match), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

/** The full disclaimer. The car screen shows a condensed version at the start of every
 *  session (see auto/CarDisclaimerScreen.kt); this is the readable-at-leisure copy. */
internal fun MainActivity.showLegalNotice() {
    AlertDialog.Builder(this)
        .setTitle(R.string.legal_notice_title)
        .setMessage(R.string.legal_notice)
        .setPositiveButton(getString(R.string.close), null)
        .show()
}
