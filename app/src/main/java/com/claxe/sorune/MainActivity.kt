package com.claxe.sorune

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        val NEW_TAB = "${Themes.ASSET_ORIGIN}/pages/newtab.html"
        val SETTINGS = "${Themes.ASSET_ORIGIN}/pages/settings.html"
        val LIBRARY = "${Themes.ASSET_ORIGIN}/pages/library.html"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val UPDATE_INTERVAL_MS = 3 * 60 * 60 * 1000L
        /** Private tabs share this profile; it is deleted (cookies, storage, cache) when the last one closes. */
        private const val PRIVATE_PROFILE = "sorune-private"
    }

    /** One tab: its page and what the chrome shows for it. A private tab lives in its own browser profile. */
    inner class BrowserTab(val isPrivate: Boolean = false) {
        val id: String = UUID.randomUUID().toString()
        val webView = WebView(this@MainActivity).also { if (isPrivate) WebViewCompat.setProfile(it, PRIVATE_PROFILE) }
        /** Ads and pop-ups held back on the current page (counted off the main thread). */
        val blocked = java.util.concurrent.atomic.AtomicInteger()
        var title = ""
        var url = ""
        var progress = 100
        var desktopMode = false
        /** Answers Sorune's own pages (new tab, settings) on the page's message channel. */
        var reply: JavaScriptReplyProxy? = null
        /** Page translation: on while set; stays on for the tab's next pages, like Chrome. */
        var translateTarget: String? = null
        var translatedFrom: String? = null
        var translating = false
        /** The last screen automatic translation handled (so the same screen is never sent twice). */
        var autoView: String? = null
    }

    /** Read from the network thread for every request, so kept outside the preferences. */
    @Volatile private var adBlockOn = true
    private val privateSupported get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private lateinit var translationChip: TextView
    private var screenTranslating = false
    /** Automatic screen translation waits until then after Google didn't answer or asked to slow down. */
    private var autoPausedUntil = 0L

    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var theme: SoruneTheme
    private lateinit var assetLoader: WebViewAssetLoader

    private lateinit var root: FrameLayout
    private lateinit var frame: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var wallpaper: ImageView
    private lateinit var address: EditText
    private lateinit var tabsButton: TextView
    private lateinit var menuButton: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var suggestionScroll: ScrollView
    private lateinit var suggestions: LinearLayout
    private lateinit var tabSwitcher: LinearLayout
    private lateinit var tabList: LinearLayout
    private lateinit var fullscreen: FrameLayout

    private val tabs = mutableListOf<BrowserTab>()
    private var active: BrowserTab? = null
    private var suggestJob: Job? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingInstall: File? = null

    /** The settings page that asked for a picture (gets the palette back). */
    private var pickingFor: BrowserTab? = null

    private val picturePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val tab = pickingFor ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val staged = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { CustomThemes.stage(this@MainActivity, uri) }
            }
            val palette = staged.getOrElse { error ->
                // The reason helps when a picture still can't be read.
                toast("Bu resim açılamadı (${error.message ?: error.javaClass.simpleName})")
                return@launch
            }
            post(tab, JSONObject()
                .put("type", "custom:picked")
                .put("preview", "${Themes.ASSET_ORIGIN}${CustomThemes.URL_PATH}pending.jpg?t=${System.currentTimeMillis()}")
                .put("palette", JSONArray(palette.map { String.format("#%06X", 0xFFFFFF and it) })))
        }
    }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    // ---------------------------------------------------------------- start

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)
        library = Library(File(filesDir, "library"))
        // Sorune's own pages and the theme pictures are served from the app's assets.
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(CustomThemes.URL_PATH, WebViewAssetLoader.InternalStoragePathHandler(this, CustomThemes.folder(this)))
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        root = findViewById(R.id.root)
        frame = findViewById(R.id.frame)
        toolbar = findViewById(R.id.toolbar)
        wallpaper = findViewById(R.id.wallpaper)
        address = findViewById(R.id.address)
        tabsButton = findViewById(R.id.tabsButton)
        menuButton = findViewById(R.id.menuButton)
        progress = findViewById(R.id.progress)
        content = findViewById(R.id.content)
        suggestionScroll = findViewById(R.id.suggestionScroll)
        suggestions = findViewById(R.id.suggestions)
        tabSwitcher = findViewById(R.id.tabSwitcher)
        tabList = findViewById(R.id.tabList)
        fullscreen = findViewById(R.id.fullscreen)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            frame.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            tabSwitcher.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        wallpaper.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitWallpaper() }
        adBlockOn = prefs.adBlock
        // A private session left over from last time (Sorune closed with private tabs open) is wiped.
        deletePrivateProfile()
        createTranslationChip()
        createFindBar()
        applyToolbarPosition()
        setupAddressBar()
        tabsButton.setOnClickListener { showTabSwitcher() }
        menuButton.setOnClickListener { showMenu() }
        findViewById<TextView>(R.id.newTabButton).setOnClickListener {
            hideTabSwitcher()
            openTab(NEW_TAB)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        applyTheme()
        restoreTabs(intent)
        scheduleUpdateCheck()
        startTranslationLoops()
        // History is written every half minute while browsing (and when Sorune goes to the background).
        lifecycleScope.launch {
            while (true) {
                delay(30_000)
                saveLibrary()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { openTab(it) }
    }

    private fun linkFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf { it.startsWith("http") }

    private fun restoreTabs(intent: Intent?) {
        val (urls, activeIndex) = prefs.loadSession()
        urls.forEach { openTab(it, activate = false) }
        if (tabs.isNotEmpty())
            activate(tabs[activeIndex.coerceIn(0, tabs.lastIndex)])
        val link = linkFrom(intent)
        if (link != null) openTab(link) else if (tabs.isEmpty()) openTab(NEW_TAB)
    }

    override fun onPause() {
        super.onPause()
        // Private tabs are never saved.
        val saved = tabs.filter { !it.isPrivate && it.url.isNotEmpty() }
        prefs.saveSession(saved.map { it.url }, saved.indexOf(active).coerceAtLeast(0))
        saveLibrary()
        CookieManager.getInstance().flush()
        active?.webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        active?.webView?.onResume()
        // Back from "allow installing apps": carry on with the update.
        pendingInstall?.let { apk ->
            if (packageManager.canRequestPackageInstalls() && apk.exists()) {
                pendingInstall = null
                Updater.install(this, apk)
            }
        }
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(isPrivate: Boolean = false): BrowserTab {
        if (isPrivate) ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
        val tab = BrowserTab(isPrivate)
        val view = tab.webView
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            // Without "wv" sites treat Sorune as the full Chrome-based browser it is (Google sign-in included).
            userAgentString = userAgentString.replace("; wv", "")
            textZoom = prefs.textZoom
        }
        applySiteDark(view)
        view.setOnLongClickListener { onLongPress(tab) }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.setBackgroundColor(theme.base)
        view.webViewClient = PageClient(tab)
        view.webChromeClient = ChromeClient(tab)
        view.setDownloadListener { url, userAgent, disposition, mimeType, _ -> download(url, userAgent, disposition, mimeType) }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Only Sorune's own pages (served from the app) can talk to the app.
            WebViewCompat.addWebMessageListener(view, "soruneHost", setOf(Themes.ASSET_ORIGIN)) { _, message, _, isMainFrame, reply ->
                if (!isMainFrame) return@addWebMessageListener
                tab.reply = reply
                runCatching { JSONObject(message.data ?: "{}") }.getOrNull()?.let { onPageMessage(tab, it) }
            }
        }
        return tab
    }

    private fun openTab(url: String?, activate: Boolean = true, index: Int = tabs.size, isPrivate: Boolean = false): BrowserTab {
        val tab = createTab(isPrivate)
        tabs.add(index.coerceIn(0, tabs.size), tab)
        tab.webView.visibility = View.GONE
        content.addView(tab.webView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (url != null) {
            tab.url = url
            tab.webView.loadUrl(url)
        }
        if (activate || active == null) activate(tab)
        updateTabsButton()
        return tab
    }

    private fun activate(tab: BrowserTab) {
        if (active !== tab) {
            active?.webView?.apply { visibility = View.GONE; onPause() }
        }
        active = tab
        tab.webView.visibility = View.VISIBLE
        tab.webView.onResume()
        showAddress(tab)
        showProgress(tab)
        updateTabsButton()
        updateTranslationChip()
        styleChromeFor(tab)
        if (findBar.visibility == View.VISIBLE) closeFind()
    }

    private fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        content.removeView(tab.webView)
        tab.webView.destroy()
        if (tab.isPrivate && tabs.none { it.isPrivate }) deletePrivateProfile()
        if (tabs.isEmpty()) openTab(NEW_TAB)
        else if (active === tab) activate(tabs[(index - 1).coerceAtLeast(0)])
        updateTabsButton()
        if (tabSwitcher.visibility == View.VISIBLE) renderTabSwitcher()
    }

    private fun updateTabsButton() {
        tabsButton.text = if (tabs.size > 99) ":)" else tabs.size.toString()
    }

    private fun goBack() {
        when {
            customView != null -> active?.webView?.webChromeClient?.onHideCustomView()
            findBar.visibility == View.VISIBLE -> closeFind()
            tabSwitcher.visibility == View.VISIBLE -> hideTabSwitcher()
            suggestionScroll.visibility == View.VISIBLE || address.hasFocus() -> clearAddressFocus()
            active?.webView?.canGoBack() == true -> active?.webView?.goBack()
            else -> moveTaskToBack(true)
        }
    }

    // ---------------------------------------------------------------- page events

    private inner class PageClient(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            assetLoader.shouldInterceptRequest(request.url)?.let { return it }
            // Ads and trackers: answered with nothing (the page itself is never blocked).
            if (adBlockOn && !request.isForMainFrame && AdBlocker.isBlocked(request.url.host)) {
                tab.blocked.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return false
            if (scheme in setOf("http", "https", "about", "data", "blob", "javascript")) return false
            openExternal(view, request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            tab.url = url
            tab.reply = null
            tab.blocked.set(0)
            if (tab === active) showAddress(tab)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            if (tab === active) showAddress(tab)
            if (!isReload && isWebPage(url) && !tab.isPrivate) library.visit(url, tab.title)
        }

        override fun onPageFinished(view: WebView, url: String) {
            // The next page of a translated tab is translated too.
            if (tab.translateTarget != null && isWebPage(url)) lifecycleScope.launch {
                delay(500)
                translatePage(tab, quiet = true)
            }
        }
    }

    private inner class ChromeClient(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (tab === active) showProgress(tab)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title.orEmpty()
            if (isWebPage(tab.url) && !tab.isPrivate) library.titleFor(tab.url, tab.title)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Windows a page opens by itself (not from a tap) are pop-up ads.
            if (adBlockOn && !isUserGesture) {
                tab.blocked.incrementAndGet()
                return false
            }
            val child = openTab(null, index = tabs.indexOf(tab) + 1, isPrivate = tab.isPrivate)
            (resultMsg.obj as WebView.WebViewTransport).webView = child.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView?.let { onHideCustomView(); return }
            customView = view
            customViewCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.visibility = View.VISIBLE
            WindowInsetsControllerCompat(window, root).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        override fun onHideCustomView() {
            fullscreen.removeAllViews()
            fullscreen.visibility = View.GONE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                fileChooser.launch(params.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    /** tel:, mailto:, intent://, app links: handed to the app that handles them. */
    private fun openExternal(view: WebView, url: String) {
        try {
            val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("http") }?.let { view.loadUrl(it) }
                    ?: toast("Bu bağlantıyı açacak uygulama yok")
            }
        } catch (e: Exception) {
            toast("Bu bağlantı açılamadı")
        }
    }

    private fun download(url: String, userAgent: String, disposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) {
            toast("Bu dosya indirilemiyor")
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        library.addDownload(DownloadRecord(id, name, url, System.currentTimeMillis()))
        toast("İndiriliyor: $name")
    }

    // ---------------------------------------------------------------- address bar

    private fun setupAddressBar() {
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(address.text.toString())
                true
            } else false
        }
        address.setOnFocusChangeListener { _, focused ->
            styleAddress(focused)
            if (focused) {
                active?.let { tab -> if (isNewTab(tab.url)) address.setText("") }
                address.selectAll()
            } else {
                hideSuggestions()
                active?.let { showAddress(it) }
            }
        }
        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (address.hasFocus()) updateSuggestions(s?.toString().orEmpty())
            }
        })
    }

    private fun isNewTab(url: String) = url.isEmpty() || url.startsWith(NEW_TAB)

    private fun displayAddress(url: String): String = when {
        isNewTab(url) -> ""
        url.startsWith(SETTINGS) -> "sorune://ayarlar"
        url.startsWith(LIBRARY) -> "sorune://kitaplik"
        else -> url
    }

    private fun showAddress(tab: BrowserTab) {
        if (!address.hasFocus()) address.setText(displayAddress(tab.url))
    }

    private fun showProgress(tab: BrowserTab) {
        progress.progress = tab.progress
        progress.visibility = if (tab.progress in 1..99) View.VISIBLE else View.INVISIBLE
    }

    private fun navigate(text: String) {
        val url = AddressResolver.resolve(text, SearchEngines.find(prefs.searchEngine)) ?: return
        clearAddressFocus()
        val tab = active ?: openTab(null)
        tab.url = url
        tab.webView.loadUrl(url)
        showAddress(tab)
    }

    private fun clearAddressFocus() {
        address.clearFocus()
        hideSuggestions()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun updateSuggestions(text: String) {
        suggestJob?.cancel()
        val query = text.trim()
        if (query.isEmpty()) {
            hideSuggestions()
            return
        }
        val engine = SearchEngines.find(prefs.searchEngine)
        val first = if (AddressResolver.isAddress(query)) Suggestion(query, "Siteye git", query, "🌐")
            else Suggestion(query, "${engine.name} ile ara", query, "🔍")
        // Bookmarks (★) and history (🕘) that match, right under the first row.
        val local = library.suggest(query).map { (url, title) ->
            Suggestion(title.ifBlank { url }, url.removePrefix("https://").removePrefix("www."), url, if (library.isBookmarked(url)) "★" else "🕘")
        }
        renderSuggestions(listOf(first) + local)
        if (!prefs.searchSuggestions) return
        suggestJob = lifecycleScope.launch {
            delay(150)
            val remote = Net.suggestions(engine, query)
            if (address.hasFocus() && address.text.toString().trim() == query)
                renderSuggestions(listOf(first) + local + remote.map { Suggestion(it, "", it, "🔍") })
        }
    }

    /** A row of the address bar's list: what it shows, a hint on the right, what opening it does. */
    private data class Suggestion(val label: String, val hint: String, val target: String, val icon: String)

    private fun renderSuggestions(items: List<Suggestion>) {
        suggestions.removeAllViews()
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = rippleBackground()
                setOnClickListener { navigate(item.target) }
            }
            row.addView(TextView(this).apply {
                this.text = item.icon
                textSize = 14f
                setTextColor(theme.accent)
                setPadding(0, 0, dp(14), 0)
            })
            val text = item.label
            val hint = item.hint.take(40)
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(theme.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (hint.isNotEmpty()) row.addView(TextView(this).apply {
                this.text = hint
                textSize = 12f
                setTextColor(theme.textSecondary)
            })
            suggestions.addView(row)
        }
        suggestionScroll.visibility = View.VISIBLE
        suggestionScroll.bringToFront()
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        suggestionScroll.visibility = View.GONE
    }

    // ---------------------------------------------------------------- tab overview

    private fun showTabSwitcher() {
        clearAddressFocus()
        renderTabSwitcher()
        tabSwitcher.visibility = View.VISIBLE
    }

    private fun hideTabSwitcher() {
        tabSwitcher.visibility = View.GONE
    }

    private fun renderTabSwitcher() {
        tabList.removeAllViews()
        for (tab in tabs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
                background = roundRect(if (tab === active) ColorUtils.setAlphaComponent(theme.accent, 46) else theme.surface, dp(14).toFloat(),
                    if (tab === active) theme.accent else theme.border)
                setOnClickListener {
                    activate(tab)
                    hideTabSwitcher()
                }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = (if (tab.isPrivate) "🕶 " else "") + tab.title.ifEmpty { if (isNewTab(tab.url)) "Yeni sekme" else tab.url }
                textSize = 15f
                setTextColor(theme.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this).apply {
                text = displayAddress(tab.url).ifEmpty { "Sorune" }
                textSize = 12f
                setTextColor(theme.textSecondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(TextView(this).apply {
                text = "✕"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(theme.textSecondary)
                contentDescription = getString(R.string.close_tab)
                setOnClickListener { closeTab(tab) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            tabList.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        clearAddressFocus()
        val tab = active
        val popup = PopupMenu(this, menuButton)
        val menu = popup.menu
        menu.add(0, 1, 0, "Yeni sekme")
        if (privateSupported) menu.add(0, 15, 0, "Yeni gizli sekme")
        menu.add(0, 16, 4, "Sayfada bul").isEnabled = tab != null && isWebPage(tab.url)
        menu.add(0, 17, 9, if (adBlockOn) "Reklam engelleyici: ${tab?.blocked?.get() ?: 0} engellendi" else "Reklam engelleyici kapalı").apply {
            isCheckable = true
            isChecked = adBlockOn
        }
        menu.add(0, 2, 1, "Yenile")
        menu.add(0, 3, 2, "İleri").isEnabled = tab?.webView?.canGoForward() == true
        menu.add(0, 4, 3, "Paylaş").isEnabled = tab != null && tab.url.startsWith("http")
        val bookmarked = tab != null && library.isBookmarked(tab.url)
        menu.add(0, 11, 3, if (bookmarked) "Yer iminden kaldır" else "Yer imlerine ekle").isEnabled = tab != null && isWebPage(tab.url)
        menu.add(0, 12, 10, "Geçmiş")
        menu.add(0, 13, 11, "Yer imleri")
        menu.add(0, 14, 12, "İndirmeler")
        menu.add(0, 5, 4, "Masaüstü sitesi").apply { isCheckable = true; isChecked = tab?.desktopMode == true }
        val web = tab != null && isWebPage(tab.url)
        menu.add(0, 8, 5, if (tab?.translateTarget != null) "Orijinali göster" else "Sayfayı Türkçeye çevir").isEnabled = web
        menu.add(0, 9, 6, "Ekranı çevir (resimler dahil)").isEnabled = web
        menu.add(0, 10, 7, "Otomatik ekran çevirisi").apply { isCheckable = true; isChecked = prefs.autoScreenTranslate }
        menu.add(0, 6, 8, "Ayarlar ve temalar")
        menu.add(0, 7, 9, "Güncellemeleri denetle")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openTab(NEW_TAB)
                2 -> tab?.webView?.reload()
                3 -> tab?.webView?.goForward()
                4 -> tab?.let { share(it) }
                11 -> tab?.let {
                    val added = library.toggleBookmark(it.url, it.title)
                    toast(if (added) "Yer imlerine eklendi" else "Yer iminden kaldırıldı")
                    saveLibrary()
                }
                15 -> openTab(NEW_TAB, isPrivate = true)
                16 -> openFind()
                17 -> setAdBlock(!adBlockOn)
                12 -> openLibrary("gecmis")
                13 -> openLibrary("yer-imleri")
                14 -> openLibrary("indirmeler")
                5 -> tab?.let { toggleDesktop(it) }
                6 -> openSettings()
                7 -> checkForUpdates(manual = true)
                8 -> tab?.let { if (it.translateTarget != null) showOriginal(it) else lifecycleScope.launch { translatePage(it, quiet = false) } }
                9 -> tab?.let { lifecycleScope.launch { translateScreen(it, quiet = false) } }
                10 -> {
                    prefs.autoScreenTranslate = !prefs.autoScreenTranslate
                    toast(if (prefs.autoScreenTranslate) "Otomatik ekran çevirisi açık: her ${prefs.autoScreenSeconds} saniyede bakar" else "Otomatik ekran çevirisi kapalı")
                    broadcast(settingsState())
                }
            }
            true
        }
        popup.show()
    }

    private fun setAdBlock(on: Boolean) {
        prefs.adBlock = on
        adBlockOn = on
        toast(if (on) "Reklam engelleyici açık" else "Reklam engelleyici kapalı")
        active?.webView?.reload()
        broadcast(settingsState())
    }

    private fun share(tab: BrowserTab) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, tab.url)
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
        }, "Paylaş"))
    }

    private fun toggleDesktop(tab: BrowserTab) {
        tab.desktopMode = !tab.desktopMode
        val defaultUa = WebViewDefaults.userAgent(this)
        tab.webView.settings.userAgentString = if (tab.desktopMode) DESKTOP_UA else defaultUa
        tab.webView.reload()
    }

    /** One library tab: reused (and switched to the asked list) when it is already open. */
    private fun openLibrary(section: String) {
        val url = "$LIBRARY#$section"
        tabs.firstOrNull { it.url.startsWith(LIBRARY) }?.let {
            activate(it)
            it.webView.loadUrl(url)
            return
        }
        openTab(url)
    }

    private val libraryWriter = Any()

    private fun saveLibrary() {
        if (!library.dirty) return
        val write = library.snapshot()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { synchronized(libraryWriter) { write() } } }
    }

    /** The asked list for the library page (downloads with their live state from Android). */
    private fun libraryState(view: String, query: String): JSONObject {
        val items = JSONArray()
        when (view) {
            "gecmis" -> library.searchHistory(query).forEach {
                items.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time))
            }
            "yer-imleri" -> library.bookmarks.forEach {
                items.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time))
            }
            else -> {
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val states = mutableMapOf<Long, JSONObject>()
                if (library.downloads.isNotEmpty()) {
                    manager.query(DownloadManager.Query().setFilterById(*library.downloads.map { it.id }.toLongArray()))?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            states[id] = JSONObject()
                                .put("state", when (status) {
                                    DownloadManager.STATUS_SUCCESSFUL -> "done"
                                    DownloadManager.STATUS_FAILED -> "failed"
                                    else -> "running"
                                })
                                .put("done", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                                .put("total", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        }
                    }
                }
                library.downloads.forEach { record ->
                    val state = states[record.id] ?: JSONObject().put("state", "missing")
                    items.put(state.put("id", record.id).put("name", record.name).put("url", record.url).put("time", record.time))
                }
            }
        }
        return JSONObject().put("type", "library").put("view", view).put("items", items)
    }

    private fun openDownload(id: Long) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id) ?: run { toast("Dosya bulunamadı"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, manager.getMimeTypeForDownloadedFile(id))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast("Bu dosyayı açacak uygulama yok")
        }
    }

    private fun openSettings() {
        tabs.firstOrNull { it.url.startsWith(SETTINGS) }?.let { activate(it); return }
        openTab(SETTINGS)
    }

    // ---------------------------------------------------------------- Sorune's own pages

    private fun onPageMessage(tab: BrowserTab, message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> post(tab, config())
            "navigate" -> {
                val url = AddressResolver.resolve(message.optString("text"), SearchEngines.find(prefs.searchEngine)) ?: return
                tab.webView.loadUrl(url)
            }
            "settings:get" -> post(tab, settingsState())
            "setTheme" -> {
                prefs.themeId = message.optString("id")
                applyTheme()
            }
            "setEngine" -> {
                prefs.searchEngine = SearchEngines.find(message.optString("id")).id
                broadcast(settingsState())
            }
            "setSuggest" -> {
                prefs.searchSuggestions = message.optBoolean("value", true)
                broadcast(settingsState())
            }
            "checkUpdate" -> checkForUpdates(manual = true)
            "library:get" -> post(tab, libraryState(message.optString("view"), message.optString("query")))
            "open" -> message.optString("url").takeIf { it.startsWith("http") }?.let { tab.webView.loadUrl(it) }
            "history:remove" -> {
                library.removeVisit(message.optString("url"), message.optLong("time"))
                saveLibrary()
                post(tab, JSONObject().put("type", "library:changed"))
            }
            "history:clear" -> {
                library.clearHistory()
                saveLibrary()
                post(tab, JSONObject().put("type", "library:changed"))
            }
            "bookmark:remove" -> {
                library.removeBookmark(message.optString("url"))
                saveLibrary()
                post(tab, JSONObject().put("type", "library:changed"))
            }
            "download:open" -> openDownload(message.optLong("id"))
            "download:remove" -> {
                library.removeDownload(message.optLong("id"))
                saveLibrary()
                post(tab, JSONObject().put("type", "library:changed"))
            }
            "download:folder" -> try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                toast("İndirilenler açılamadı")
            }
            "setAutoScreen" -> {
                prefs.autoScreenTranslate = message.optBoolean("value", false)
                broadcast(settingsState())
            }
            "setAdBlock" -> setAdBlock(message.optBoolean("value", true))
            "custom:pick" -> {
                pickingFor = tab
                runCatching { picturePicker.launch("image/*") }.onFailure { toast("Galeri açılamadı") }
            }
            "custom:create" -> lifecycleScope.launch {
                val accent = runCatching { Color.parseColor(message.optString("accent")) }.getOrDefault(theme.accent)
                val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        CustomThemes.create(this@MainActivity, message.optString("name"), accent, message.optDouble("focusX", 0.5).toFloat())
                    }.getOrNull()
                }
                if (id == null) {
                    toast("Tema kaydedilemedi")
                    return@launch
                }
                prefs.themeId = id
                applyTheme()
                toast("Temana geçildi")
            }
            "custom:delete" -> {
                val id = message.optString("id")
                if (prefs.themeId == id) prefs.themeId = Themes.DEFAULT_ID
                CustomThemes.delete(this, id)
                applyTheme()
            }
            "setToolbarBottom" -> {
                prefs.toolbarBottom = message.optBoolean("value", false)
                applyToolbarPosition()
                broadcast(settingsState())
            }
            "setSiteDark" -> {
                prefs.siteDark = message.optBoolean("value", false)
                tabs.forEach { applySiteDark(it.webView) }
                broadcast(settingsState())
            }
            "setTextZoom" -> {
                prefs.textZoom = message.optInt("value", 100)
                tabs.forEach { it.webView.settings.textZoom = prefs.textZoom }
                broadcast(settingsState())
            }
            "setPip" -> {
                prefs.pictureInPicture = message.optBoolean("value", true)
                broadcast(settingsState())
            }
            "setAutoSeconds" -> {
                prefs.autoScreenSeconds = message.optInt("value", 10)
                broadcast(settingsState())
            }
        }
    }

    private fun post(tab: BrowserTab, message: JSONObject) {
        runCatching { tab.reply?.postMessage(message.toString()) }
    }

    private fun broadcast(message: JSONObject) = tabs.forEach { post(it, message) }

    private fun config(): JSONObject = JSONObject()
        .put("type", "config")
        .put("theme", JSONObject(theme.hex).put("id", theme.id).put("name", theme.name).put("wallpaper", Themes.wallpaperUrl(theme))
            .put("focusX", theme.focusX.toDouble()))
        .put("engine", SearchEngines.find(prefs.searchEngine).name)
        .put("bookmarks", JSONArray(library.bookmarks.take(8).map { JSONObject().put("name", it.title.ifBlank { it.url }).put("url", it.url) }))
        .put("version", BuildConfig.VERSION_NAME)

    private fun settingsState(): JSONObject = JSONObject()
        .put("type", "settings")
        .put("theme", theme.id)
        .put("themes", JSONArray(Themes.list(this).map {
            JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
                .put("accent", it.hex["accent"]).put("thumb", Themes.thumbUrl(it)).put("custom", it.isCustom)
        }))
        .put("engine", prefs.searchEngine)
        .put("engines", JSONArray(SearchEngines.all.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("suggest", prefs.searchSuggestions)
        .put("autoScreen", prefs.autoScreenTranslate)
        .put("adBlock", prefs.adBlock)
        .put("toolbarBottom", prefs.toolbarBottom)
        .put("siteDark", prefs.siteDark)
        .put("siteDarkSupported", WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
        .put("textZoom", prefs.textZoom)
        .put("pip", prefs.pictureInPicture)
        .put("autoSeconds", prefs.autoScreenSeconds)
        .put("version", BuildConfig.VERSION_NAME)

    // ---------------------------------------------------------------- private tabs, dark sites

    private fun deletePrivateProfile() {
        if (!privateSupported) return
        // Only possible once no tab uses it; a leftover is retried at the next start.
        runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
    }

    private fun applySiteDark(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, prefs.siteDark)
    }

    /** A private tab gets a black bar and says so in the address field. */
    private fun styleChromeFor(tab: BrowserTab) {
        val tint = if (tab.isPrivate) Color.argb(240, 6, 6, 8)
            else ColorUtils.setAlphaComponent(theme.chromeTint, (theme.chromeTintOpacity * 255).toInt())
        toolbar.setBackgroundColor(tint)
        address.hint = if (tab.isPrivate) "🕶 Gizli sekme: ara veya adres yaz" else getString(R.string.address_hint)
    }

    // ---------------------------------------------------------------- long press

    /** Links and pictures: open, copy, share, download. Anything else keeps the usual text selection. */
    private fun onLongPress(tab: BrowserTab): Boolean {
        val hit = tab.webView.hitTestResult
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                hit.extra?.let { showPressMenu(tab, link = it, image = null) }
                hit.extra != null
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val image = hit.extra
                // A picture inside a link: the link's address comes back asynchronously.
                val handler = android.os.Handler(android.os.Looper.getMainLooper()) { message ->
                    showPressMenu(tab, link = message.data.getString("url"), image = image)
                    true
                }
                tab.webView.requestFocusNodeHref(handler.obtainMessage())
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                hit.extra?.let { showPressMenu(tab, link = null, image = it) }
                hit.extra != null
            }
            else -> false
        }
    }

    private fun showPressMenu(tab: BrowserTab, link: String?, image: String?) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val web = { url: String? -> url != null && url.startsWith("http") }
        if (web(link)) {
            actions += "Yeni sekmede aç" to { openTab(link, isPrivate = tab.isPrivate); Unit }
            actions += "Arka planda aç" to { openTab(link, activate = false, index = tabs.indexOf(tab) + 1, isPrivate = tab.isPrivate); toast("Arka planda açıldı") }
            if (privateSupported && !tab.isPrivate) actions += "Gizli sekmede aç" to { openTab(link, isPrivate = true); Unit }
            actions += "Bağlantıyı kopyala" to { copy(link!!) }
            actions += "Bağlantıyı paylaş" to { shareText(link!!, null) }
        }
        if (web(image)) {
            actions += "Resmi yeni sekmede aç" to { openTab(image, isPrivate = tab.isPrivate); Unit }
            actions += "Resmi indir" to { download(image!!, tab.webView.settings.userAgentString, null, null) }
            actions += "Resim adresini kopyala" to { copy(image!!) }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this, R.style.Theme_Sorune_Dialog)
            .setTitle((link ?: image)!!.removePrefix("https://").take(80))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Sorune", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Kopyalandı")
    }

    private fun shareText(text: String, subject: String?) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }, "Paylaş"))
    }

    // ---------------------------------------------------------------- find in page

    private fun createFindBar() {
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        findInput = EditText(this).apply {
            hint = "Sayfada bul"
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    active?.webView?.findAllAsync(s?.toString().orEmpty())
                }
            })
            setOnEditorActionListener { _, _, _ -> active?.webView?.findNext(true); true }
        }
        findCount = TextView(this).apply { textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        findBar.addView(findCount)
        findBar.addView(button("˄") { active?.webView?.findNext(false) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        findBar.addView(button("˅") { active?.webView?.findNext(true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        findBar.addView(button("✕") { closeFind() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(findBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun openFind() {
        val tab = active ?: return
        tab.webView.setFindListener { ordinal, matches, _ ->
            findCount.text = if (findInput.text.isEmpty()) "" else if (matches == 0) "0/0" else "${ordinal + 1}/$matches"
        }
        findBar.background = roundRect(ColorUtils.setAlphaComponent(theme.surface, 245), 0f, theme.border)
        findInput.setTextColor(theme.textPrimary)
        findInput.setHintTextColor(theme.textSecondary)
        findCount.setTextColor(theme.textSecondary)
        for (i in 2 until findBar.childCount) (findBar.getChildAt(i) as TextView).setTextColor(theme.textPrimary)
        findBar.visibility = View.VISIBLE
        findBar.bringToFront()
        findInput.setText("")
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, 0)
    }

    private fun closeFind() {
        active?.webView?.clearMatches()
        findBar.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    // ---------------------------------------------------------------- layout

    /** The address bar at the top, or at the bottom within the thumb's reach. */
    private fun applyToolbarPosition() {
        frame.removeView(toolbar)
        frame.removeView(progress)
        if (prefs.toolbarBottom) {
            frame.addView(progress)
            frame.addView(toolbar)
        } else {
            frame.addView(toolbar, 0)
            frame.addView(progress, 1)
        }
        (suggestionScroll.layoutParams as FrameLayout.LayoutParams).gravity = if (prefs.toolbarBottom) Gravity.BOTTOM else Gravity.TOP
        suggestionScroll.requestLayout()
    }

    /** Leaving Sorune during a fullscreen video: it keeps playing in a small window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (customView != null && prefs.pictureInPicture && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            runCatching {
                enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
            }
        }
    }

    // ---------------------------------------------------------------- translation

    private fun isWebPage(url: String?) = url != null && url.startsWith("http") && !url.startsWith(Themes.ASSET_ORIGIN)

    /** Translates the page in place (Google Translate); quiet runs only report real problems. */
    private suspend fun translatePage(tab: BrowserTab, quiet: Boolean) {
        if (!isWebPage(tab.url) || tab.translating) return
        val first = tab.translateTarget == null
        tab.translateTarget = "tr"
        tab.translating = true
        updateTranslationChip()
        try {
            val result = PageTranslator.translateNewText(tab.webView, "tr")
            result.sourceLanguage?.let { tab.translatedFrom = it }
            if (first && PageTranslator.sameLanguage(result.sourceLanguage, "tr")) {
                showOriginal(tab)
                toast("Bu sayfa zaten Türkçe")
            } else if (first && result.translated == 0) {
                showOriginal(tab)
                toast("Bu sayfada çevrilecek yazı bulamadım")
            }
        } catch (e: Exception) {
            if (first) showOriginal(tab)
            if (!quiet || first) toast("Google Çeviri'ye ulaşılamadı, sonra tekrar dene")
        } finally {
            tab.translating = false
            updateTranslationChip()
        }
    }

    private fun showOriginal(tab: BrowserTab) {
        tab.translateTarget = null
        tab.translatedFrom = null
        tab.webView.evaluateJavascript(PageTranslator.RESTORE, null)
        updateTranslationChip()
    }

    /** Screen translation, text in pictures too (Google Translate's image mode). */
    private suspend fun translateScreen(tab: BrowserTab, quiet: Boolean) {
        if (!isWebPage(tab.url) || screenTranslating) return
        screenTranslating = true
        if (!quiet) toast("Ekranı çeviriyorum…")
        try {
            val outcome = ScreenTranslator.translateVisible(this, tab.webView, "tr")
            val note = outcome.note
            if (outcome.googleBusy) {
                // Said once even while reading automatically; then automatic translation rests a minute.
                if (!quiet || System.currentTimeMillis() >= autoPausedUntil) toast(note ?: "")
                autoPausedUntil = System.currentTimeMillis() + 60_000
            } else when {
                note == null -> if (!quiet) toast("Çevirdim! Aşağı kaydırınca yine çevir.")
                // "No text here" is normal while reading automatically (a picture without words).
                !quiet || note != ScreenTranslator.NO_TEXT -> toast(note)
            }
        } catch (e: Exception) {
            if (!quiet) toast("Ekranı çeviremedim")
        } finally {
            screenTranslating = false
        }
    }

    /**
     * Two loops while Sorune is open: text a translated page adds later is translated too, and
     * automatic screen translation looks every few seconds; when the page stands still on a screen
     * it hasn't translated yet (scrolled, new chapter, pictures loaded), that screen is translated.
     */
    private fun startTranslationLoops() {
        lifecycleScope.launch {
            while (true) {
                delay(2500)
                val tab = active ?: continue
                if (tab.translateTarget != null && !tab.translating && tab.progress >= 100 && !isFinishing)
                    runCatching { translatePage(tab, quiet = true) }
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(prefs.autoScreenSeconds * 1000L)
                val tab = active ?: continue
                // (Not waiting for the page to finish loading: sites with ads may never get there.)
                if (!prefs.autoScreenTranslate || screenTranslating || !isWebPage(tab.url)
                    || System.currentTimeMillis() < autoPausedUntil
                    || customView != null || tabSwitcher.visibility == View.VISIBLE || !hasWindowFocus()) continue
                val before = tab.webView.eval(ScreenTranslator.VIEW)
                delay(600)
                if (active !== tab) continue
                val after = tab.webView.eval(ScreenTranslator.VIEW)
                if (before != after || tab.autoView == after) continue
                tab.autoView = after
                translateScreen(tab, quiet = true)
            }
        }
    }

    private fun createTranslationChip() {
        translationChip = TextView(this).apply {
            textSize = 13f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            elevation = dp(6).toFloat()
            visibility = View.GONE
            setOnClickListener { active?.let { showOriginal(it) } }
        }
        content.addView(translationChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
    }

    /** "İngilizce → Türkçe  ✕" over a translated page; a tap shows the original. */
    private fun updateTranslationChip() {
        if (!::translationChip.isInitialized) return
        val tab = active
        if (tab?.translateTarget == null) {
            translationChip.visibility = View.GONE
            return
        }
        translationChip.text = "🌐 ${PageTranslator.languageName(tab.translatedFrom)} → Türkçe" + (if (tab.translating) " …" else "") + "   ✕"
        translationChip.setTextColor(theme.textPrimary)
        translationChip.background = roundRect(ColorUtils.setAlphaComponent(theme.surface, 235), dp(18).toFloat(), theme.accent, dp(1))
        translationChip.visibility = View.VISIBLE
        translationChip.bringToFront()
    }

    // ---------------------------------------------------------------- theme

    private fun applyTheme() {
        theme = Themes.find(this, prefs.themeId)
        window.navigationBarColor = theme.base
        root.setBackgroundColor(theme.base)

        theme.wallpaper?.let { file ->
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                if (theme.isCustom) wallpaper.setImageBitmap(BitmapFactory.decodeFile(File(CustomThemes.folder(this), file).path, options))
                else assets.open("themes/$file").use { wallpaper.setImageBitmap(BitmapFactory.decodeStream(it, null, options)) }
            }
        }
        fitWallpaper()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            wallpaper.setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP))

        toolbar.setBackgroundColor(ColorUtils.setAlphaComponent(theme.chromeTint, (theme.chromeTintOpacity * 255).toInt()))
        styleAddress(address.hasFocus())
        address.setTextColor(theme.textPrimary)
        address.setHintTextColor(theme.textSecondary)
        tabsButton.setTextColor(theme.textPrimary)
        tabsButton.background = roundRect(Color.TRANSPARENT, dp(7).toFloat(), theme.textPrimary, dp(2))
        menuButton.setColorFilter(theme.textPrimary)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        suggestionScroll.background = roundRect(ColorUtils.setAlphaComponent(theme.surface, 250), 0f, theme.border)
        tabSwitcher.setBackgroundColor(ColorUtils.setAlphaComponent(theme.base, 245))
        findViewById<TextView>(R.id.tabSwitcherTitle).setTextColor(theme.textPrimary)
        findViewById<TextView>(R.id.newTabButton).apply {
            setTextColor(theme.base)
            background = roundRect(theme.accent, dp(20).toFloat())
        }
        tabs.forEach { it.webView.setBackgroundColor(theme.base) }
        active?.let { styleChromeFor(it) }
        broadcast(config())
        broadcast(settingsState())
    }

    /** The character in the wallpaper stays on screen, whatever the phone's shape or rotation. */
    private fun fitWallpaper() {
        val image = wallpaper.drawable ?: return
        if (wallpaper.width == 0 || image.intrinsicWidth <= 0) return
        wallpaper.scaleType = ImageView.ScaleType.MATRIX
        wallpaper.imageMatrix = Themes.focusMatrix(image.intrinsicWidth, image.intrinsicHeight, wallpaper.width, wallpaper.height, theme.focusX)
    }

    private fun styleAddress(focused: Boolean) {
        address.background = roundRect(ColorUtils.setAlphaComponent(theme.surface, 225), dp(21).toFloat(),
            if (focused) theme.accent else theme.border, dp(1))
    }

    // ---------------------------------------------------------------- updates

    private fun scheduleUpdateCheck() {
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < UPDATE_INTERVAL_MS) return
        lifecycleScope.launch {
            delay(4000)
            checkForUpdates(manual = false)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) toast("Güncellemeler denetleniyor…")
        lifecycleScope.launch {
            val release = Updater.latest()
            prefs.lastUpdateCheck = System.currentTimeMillis()
            when {
                release == null -> if (manual) toast("Güncelleme sunucusuna ulaşılamadı")
                !Updater.isNewer(release) -> if (manual) toast("Sorune güncel (${BuildConfig.VERSION_NAME})")
                else -> offerUpdate(release)
            }
        }
    }

    private fun offerUpdate(release: Updater.Release) {
        val notes = release.notes.lines().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("---") }
            .joinToString("\n").take(700)
        AlertDialog.Builder(this, R.style.Theme_Sorune_Dialog)
            .setTitle("Sorune ${release.version} hazır")
            .setMessage(notes.ifEmpty { "Yeni sürüm indirilebilir." })
            .setPositiveButton("Güncelle") { _, _ -> downloadUpdate(release) }
            .setNegativeButton("Sonra", null)
            .show()
    }

    private fun downloadUpdate(release: Updater.Release) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(12), dp(24), dp(4)); addView(bar) }
        val dialog = AlertDialog.Builder(this, R.style.Theme_Sorune_Dialog)
            .setTitle("Sorune ${release.version} indiriliyor")
            .setView(box)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val file = Updater.updateFile(this@MainActivity)
            val ok = Net.download(release.apkUrl, file) { fraction ->
                runOnUiThread {
                    bar.isIndeterminate = fraction < 0
                    if (fraction >= 0) bar.progress = (fraction * 100).toInt()
                }
            }
            dialog.dismiss()
            if (!ok) {
                toast("Güncelleme indirilemedi, sonra tekrar dene")
                return@launch
            }
            if (!Updater.install(this@MainActivity, file)) {
                pendingInstall = file
                toast("Sorune'nin uygulama yüklemesine izin ver, sonra geri dön")
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun roundRect(fill: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        stroke?.let { setStroke(strokeWidth.coerceAtLeast(1), it) }
    }

    private fun rippleBackground() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, 60)), null, null)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** The WebView's own user agent (for leaving desktop mode), with "wv" removed like every tab. */
object WebViewDefaults {
    fun userAgent(activity: AppCompatActivity): String =
        android.webkit.WebSettings.getDefaultUserAgent(activity).replace("; wv", "")
}
