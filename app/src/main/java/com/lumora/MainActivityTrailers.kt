package com.lumora

import android.app.Dialog
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.util.cleanVodTitle
import com.lumora.util.extractYearFromName
import kotlinx.coroutines.launch

/** Parses a Discover [Channel.id] of the form "tmdb:movie:123" / "tmdb:tv:123". */
internal fun MainActivity.tmdbTypeAndId(id: String): Pair<String, Int>? {
    val parts = id.split(":")
    if (parts.size != 3 || parts[0] != "tmdb") return null
    return parts[1] to (parts[2].toIntOrNull() ?: return null)
}

/** The panel-supplied TMDB id as a `(mediaType, id)` pair, or null when it sent none. Live
 *  items are excluded - a channel is not a TMDB title, whatever id happens to be attached. */
internal fun Channel.tmdbTypeAndId(): Pair<String, Int>? {
    if (mediaType == MediaType.LIVE) return null
    val id = tmdbId?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return (if (mediaType == MediaType.SERIES) "tv" else "movie") to id
}

/** Release year for TMDB matching. Panels routinely omit the `year` field on the bulk lists
 *  while still putting "(2026)" in the name, and a year is what separates the right "Run" or
 *  "Fearless" from the popular one - so fall back to the release date, then to the name. */
internal fun Channel.tmdbYear(): String? =
    year?.takeIf { it.isNotBlank() }
        ?: releaseDate?.take(4)?.takeIf { it.length == 4 }
        ?: extractYearFromName(name)

/** Looks up and plays a TMDB trailer for a Discover item (id already carries the TMDB id). */
internal fun MainActivity.showTrailerForDiscoverItem(item: Channel) {
    val (type, id) = tmdbTypeAndId(item.id) ?: run {
        android.util.Log.d("TrailerPlayer", "showTrailerForDiscoverItem: '${item.id}' not a tmdb id")
        return
    }
    scope.launch {
        val key = try {
            tmdbClient.trailerKey(type, id)
        } catch (e: Exception) {
            android.util.Log.e("TrailerPlayer", "trailerKey($type,$id) threw", e)
            null
        }
        android.util.Log.d("TrailerPlayer", "trailerKey($type,$id) = $key")
        if (key == null) {
            Toast.makeText(this@showTrailerForDiscoverItem, getString(R.string.plug_no_trailer_found), Toast.LENGTH_SHORT).show()
        } else {
            showTrailerPlayer(key)
        }
    }
}

/** Shows/hides the detail screen's Trailer button, resolving a catalog item to a TMDB id
 *  by title/year search since provider/Jellyfin content carries no TMDB id of its own. */
internal fun MainActivity.wireTrailerButton(item: Channel) {
    val button = binding.detailTrailerButton
    button.visibility = View.GONE
    button.setOnClickListener(null)
    if (!tmdbClient.hasKey()) {
        android.util.Log.d("TrailerPlayer", "wireTrailerButton: no TMDB key configured")
        return
    }
    // The panel's own trailer key, when it sent one: no network at all, and it covers plenty
    // of titles TMDB itself has no video for.
    item.trailerKey?.takeIf { it.isNotBlank() }?.let { key ->
        button.visibility = View.VISIBLE
        button.setOnClickListener { showTrailerPlayer(key) }
        return
    }
    scope.launch {
        try {
            // The panel's TMDB id beats any title search - see Channel.tmdbId.
            val direct = tmdbTypeAndId(item.id) ?: item.tmdbTypeAndId()
            // cleanVodTitle first, same as the detail screen's TMDB metadata fill - this
            // path used the raw catalog name, so a title the plot/backdrop lookup resolved
            // fine could still fail here and silently drop the button.
            val resolved = direct
                ?: tmdbClient.resolveId(cleanVodTitle(item.name), item.tmdbYear(), item.mediaType == MediaType.SERIES)
            android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}', year=${item.year}): resolved=$resolved (direct=${direct != null})")
            val (type, id) = resolved ?: return@launch
            val key = tmdbClient.trailerKey(type, id)
            android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}'): trailerKey=$key")
            if (key == null) return@launch
            button.visibility = View.VISIBLE
            button.setOnClickListener { showTrailerPlayer(key) }
        } catch (e: Exception) {
            android.util.Log.e("TrailerPlayer", "wireTrailerButton('${item.name}') threw", e)
        }
    }
}

/** Plays a YouTube trailer in-app, fullscreen, via the standard /embed player - loaded
 *  directly (no hand-built HTML wrapper: that rendered blank with no logged error). */
internal fun MainActivity.showTrailerPlayer(youtubeKey: String) {
    val density = resources.displayMetrics.density
    val webView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // YouTube's iframe player needs this or it stays blank with no error
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            // YouTube's watch/embed page top-navigates to plain youtube.com/ as a fallback
            // when an internal resource (e.g. the doubleclick ad request) fails to load -
            // seen on networks that block ad domains. Refuse every main-frame navigation
            // outright: this player never legitimately needs to leave the embed URL.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && !request.url.toString().contains("/embed/")) {
                    android.util.Log.d("TrailerPlayer", "blocked main-frame navigation to ${request.url}")
                    return true
                }
                return false
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                android.util.Log.e(
                    "TrailerPlayer",
                    "onReceivedError url=${request.url} code=${error.errorCode} desc=${error.description}"
                )
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                android.util.Log.e(
                    "TrailerPlayer",
                    "onReceivedHttpError url=${request.url} status=${response.statusCode}"
                )
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                android.util.Log.d("TrailerPlayer", "onPageStarted url=$url")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                android.util.Log.d("TrailerPlayer", "onPageFinished url=$url")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                android.util.Log.d("TrailerPlayer", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
    }
    val closeButton = Button(this).apply {
        text = getString(R.string.close)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = (16 * density).toInt()
            rightMargin = (16 * density).toInt()
        }
    }
    val root = FrameLayout(this).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(webView)
        addView(closeButton)
    }
    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.setContentView(root)
    closeButton.setOnClickListener { dialog.dismiss() }
    dialog.setOnDismissListener { webView.destroy() }
    // A raw loadUrl only sends the Referer header on the very first request, not on the
    // player's own follow-up calls - got as far as fixing error 153 but still hit 152.
    // Giving the WebView's document itself a youtube-nocookie.com origin (via
    // loadDataWithBaseURL) plus an explicit iframe referrerpolicy covers those too.
    val html = """
        <html><body style="margin:0;padding:0;background:#000;">
        <iframe width="100%" height="100%"
            src="https://www.youtube-nocookie.com/embed/$youtubeKey?autoplay=1&playsinline=1"
            frameborder="0" referrerpolicy="strict-origin-when-cross-origin"
            allow="autoplay; encrypted-media" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    webView.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
    dialog.show()
    closeButton.requestFocus()
}

/** Shows/hides the detail screen's Find Stream button - one entry point that searches every
 *  source (the built-in site scrapers) and plays the best result without asking. */
internal fun MainActivity.wireFindStreamButton(item: Channel) {
    val button = binding.detailFindStreamButton
    val available = canFindStream(item)
    button.visibility = if (available) View.VISIBLE else View.GONE
    button.setOnClickListener(
        if (!available) null
        else View.OnClickListener {
            // A series has to say which episode before anything can be searched for - without
            // this it silently searched S01E01, which is right roughly never. The picker is
            // TMDB-backed, so it works for a title the library does not have at all (the
            // "No episodes found" case on a Discover series).
            if (item.mediaType == MediaType.SERIES) {
                showSeriesEpisodePicker(item) { season, episode ->
                    showFindStreamDialog(item, season, episode)
                }
            } else {
                showFindStreamDialog(item)
            }
        }
    )
}
