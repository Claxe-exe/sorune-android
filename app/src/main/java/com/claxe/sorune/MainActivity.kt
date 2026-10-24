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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
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
        val READER = "${Themes.ASSET_ORIGIN}/pages/reader.html"
        /** From the home screen widget and the app icon's shortcuts. */
        const val ACTION_SEARCH = "com.claxe.sorune.SEARCH"
        const val ACTION_PRIVATE = "com.claxe.sorune.PRIVATE"
        const val ACTION_LIBRARY = "com.claxe.sorune.LIBRARY"
        /** Languages pages and screens can be translated into. */
        val TRANSLATE_LANGUAGES = listOf("tr", "en", "ja", "ko", "zh-CN", "de", "fr", "es", "ru", "ar", "it", "pt")
        private val NEW_TAB_FONTS = setOf("system", "orbitron", "bebas", "pacifico", "pixel", "righteous", "script")
        private val NEW_TAB_EFFECTS = setOf("theme", "none", "petals", "snow", "sparks", "bubbles")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val UPDATE_INTERVAL_MS = 3 * 60 * 60 * 1000L
        /** Private tabs share this profile; it is deleted (cookies, storage, cache) when the last one closes. */
        private const val PRIVATE_PROFILE = "sorune-private"
        private const val PRIVATE_KEEP_MS = 5 * 60 * 1000L
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
        var favicon: android.graphics.Bitmap? = null
        /** A small picture of the page for the tab overview, taken when the tab is left. */
        var preview: android.graphics.Bitmap? = null
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
    private lateinit var refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    /** Articles waiting for their reader tab (by tab id). */
    private val readerArticles = mutableMapOf<String, JSONObject>()
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
        refresh = findViewById(R.id.refresh)
        refresh.setOnRefreshListener {
            active?.webView?.reload()
            refresh.postDelayed({ refresh.isRefreshing = false }, 1200)
        }
        // Only when the setting is on and the page is at its very top.
        refresh.setOnChildScrollUpCallback { _, _ ->
            !prefs.pullRefresh || customView != null || active?.webView?.let { it.scrollY > 0 || it.canScrollVertically(-1) } ?: true
        }
        setupTabSwipe()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            lastInsets = insets
            applyFramePadding()
            insetTop = bars.top
            insetBottom = bars.bottom
            tabSwitcher.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Pages get no system bar insets: newer WebViews would otherwise leave their own empty
        // strip above the navigation bar. Sorune's frame already handles the bars.
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, _ -> WindowInsetsCompat.CONSUMED }

        wallpaper.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitWallpaper() }
        adBlockOn = prefs.adBlock
        SecureDns.provider = SecureDns.Provider.find(prefs.dnsProvider)
        if (prefs.secureDns) applySecureDns()
        // A private session left over from last time (Sorune closed with private tabs open) is wiped.
        deletePrivateProfile()
        createTranslationChip()
        createFindBar()
        createDrawer()
        applyToolbarPosition()
        setupAddressBar()
        tabsButton.setOnClickListener { if (prefs.tabsPlace == "swap") showQuickMenu() else showTabSwitcher() }
        menuButton.setOnClickListener { if (drawerOpen) closeDrawer() else openDrawer() }
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
        if (!handleAction(intent)) linkFrom(intent)?.let { openTab(it) }
    }

    /** Widget and app-icon shortcuts: search, private tab, library. */
    private fun handleAction(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_SEARCH -> {
                if (active?.url?.let { isNewTab(it) } != true) openTab(NEW_TAB)
                address.postDelayed({
                    address.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, 0)
                }, 250)
            }
            ACTION_PRIVATE -> openTab(NEW_TAB, isPrivate = privateSupported)
            ACTION_LIBRARY -> openLibrary("gecmis")
            else -> return false
        }
        return true
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
        handleAction(intent)
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

    /** When Sorune last left the screen; private tabs close if it stays away too long. */
    private var leftAt = 0L

    override fun onStop() {
        super.onStop()
        // Not while Sorune itself sent the person away (the lock's PIN screen, picking a file) or the screen only turned.
        if (authRunning || fileCallback != null || isChangingConfigurations) return
        privateUnlocked = false
        leftAt = android.os.SystemClock.elapsedRealtime()
    }


    override fun onResume() {
        super.onResume()
        // Private tabs are one-time, but a quick look elsewhere (a code in messages) keeps them.
        if (leftAt > 0 && android.os.SystemClock.elapsedRealtime() - leftAt > PRIVATE_KEEP_MS) closePrivateTabs()
        leftAt = 0
        active?.webView?.onResume()
        updatePrivateLock()
        broadcast(settingsState())
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
        // Closing Sorune for good ends the private session too.
        if (isFinishing) deletePrivateProfile()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(isPrivate: Boolean = false): BrowserTab {
        if (isPrivate) {
            // The first private tab starts clean: anything an earlier session left is wiped first.
            if (tabs.none { it.isPrivate }) deletePrivateProfile()
            ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
        }
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
        view.setOnScrollChangeListener { _, _, y, _, oldY -> if (tab === active) onPageScroll(y, oldY) }
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
            applyUaFor(tab, url)
            tab.webView.loadUrl(url)
        }
        if (activate || active == null) activate(tab)
        updateTabsButton()
        return tab
    }

    private fun activate(tab: BrowserTab) {
        val changed = active !== tab
        if (changed) {
            active?.takeIf { it in tabs }?.let { capturePreview(it) }
            active?.webView?.apply { visibility = View.GONE; onPause() }
        }
        active = tab
        tab.webView.visibility = View.VISIBLE
        if (changed) fadeIn(tab.webView, 0, 200)
        setToolbarShown(true)
        updatePrivateLock()
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
        if (!tab.isPrivate && isWebPage(tab.url)) {
            closedTabs += ClosedTab(tab.url, index)
            if (closedTabs.size > 15) closedTabs.removeAt(0)
            showUndo("Sekme kapatıldı") { reopenClosedTab() }
        }
        tabs.removeAt(index)
        readerArticles.remove(tab.id)
        content.removeView(tab.webView)
        tab.webView.destroy()
        if (tab.isPrivate && tabs.none { it.isPrivate }) {
            deletePrivateProfile()
            privateUnlocked = false
        }
        if (tabs.isEmpty()) openTab(NEW_TAB)
        else if (active === tab) activate(tabs[(index - 1).coerceAtLeast(0)])
        updateTabsButton()
        if (tabSwitcher.visibility == View.VISIBLE) renderTabSwitcher()
    }

    private fun updateTabsButton() {
        tabsButton.text = if (tabs.size > 99) ":)" else tabs.size.toString()
        tabsButton.visibility = if (prefs.tabsPlace == "menu") View.GONE else View.VISIBLE
        if (prefs.tabsPlace == "swap") tabsButton.text = "⊞"
    }

    private fun goBack() {
        when {
            customView != null -> active?.webView?.webChromeClient?.onHideCustomView()
            drawerOpen -> closeDrawer()
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
            // A site remembered as "desktop" (or no longer) opens with the matching browser identity.
            if ((scheme == "http" || scheme == "https") && request.isForMainFrame && !request.isRedirect
                && applyUaFor(tab, request.url.toString())) {
                view.loadUrl(request.url.toString())
                return true
            }
            if (scheme in setOf("http", "https", "about", "data", "blob", "javascript")) return false
            openExternal(view, request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            tab.url = url
            tab.reply = null
            tab.blocked.set(0)
            applyDataSaver(tab)
            if (tab === active) {
                showAddress(tab)
                setToolbarShown(true)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            if (tab === active) showAddress(tab)
            if (!isReload && isWebPage(url) && !tab.isPrivate) library.visit(url, tab.title)
        }

        /**
         * The phone closed the pages' process (to free memory while Sorune was in the background,
         * or it crashed). Without this Sorune would close too; instead the tab gets a fresh page.
         */
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            recoverTab(tab)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (tab === active) refresh.isRefreshing = false
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

        override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap?) {
            tab.favicon = icon
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
                setToolbarShown(true)
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
        url.startsWith(READER) -> "sorune://okuma"
        else -> url
    }

    private fun showAddress(tab: BrowserTab) {
        if (!address.hasFocus()) address.setText(displayAddress(tab.url))
        applyFramePadding()
    }

    private var lastInsets: WindowInsetsCompat? = null

    /**
     * No empty strips at the screen edges. The toolbar runs under the system bar on its own edge
     * (status bar on top, navigation bar below), like the side panel. On the other edge Sorune's own
     * pages always run under the bar (they leave room themselves), web pages do with gesture navigation.
     * The keyboard still pushes everything up.
     */
    private fun applyFramePadding() {
        val insets = lastInsets ?: return
        val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val withKeyboard = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        // Some phones report the gesture bar as tappable; its small height gives it away.
        gestureNav = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom == 0 || nav < dp(44)
        // Some phones report a leftover keyboard height while it is closed: ask whether it is shown.
        val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
        val ownPage = active?.let { !isWebPage(it.url) } ?: true
        toolbarInsetTop = if (prefs.toolbarBottom) 0 else sys.top
        toolbarInsetBottom = if (prefs.toolbarBottom && !keyboard) sys.bottom else 0
        val top = if (prefs.toolbarBottom) sys.top else 0
        val bottom = when {
            keyboard -> withKeyboard.bottom
            prefs.toolbarBottom || gestureNav || ownPage -> 0
            else -> sys.bottom
        }
        if (frame.paddingBottom != bottom || frame.paddingTop != top) frame.setPadding(sys.left, top, sys.right, bottom)
        toolbar.setPadding(toolbar.paddingLeft, toolbarInsetTop, toolbar.paddingRight, toolbarInsetBottom)
        if (toolbarAnimator?.isRunning != true) setToolbarHeight(toolbarTargetHeight())
        if (::theme.isInitialized) window.navigationBarColor = if (gestureNav) Color.TRANSPARENT else theme.base
    }

    // ---------------------------------------------------------------- toolbar hides while scrolling

    private var toolbarInsetTop = 0
    private var toolbarInsetBottom = 0
    private var gestureNav = true
    private var toolbarShown = true
    private var toolbarAnimator: android.animation.ValueAnimator? = null
    private var scrollSum = 0
    private var ignoreScrollUntil = 0L

    /** Hidden, the toolbar keeps only the status bar strip on top (the clock stays readable). */
    private fun toolbarTargetHeight() =
        if (toolbarShown) dp(60) + toolbarInsetTop + toolbarInsetBottom
        else toolbarInsetTop + (if (gestureNav) 0 else toolbarInsetBottom)

    private fun setToolbarHeight(height: Int) {
        if (toolbar.layoutParams.height == height) return
        toolbar.layoutParams.height = height
        toolbar.requestLayout()
    }

    private fun setToolbarShown(show: Boolean) {
        if (toolbarShown == show) return
        toolbarShown = show
        toolbarAnimator?.cancel()
        // The page changes size while the bar moves: its scroll events then are not the reader's.
        ignoreScrollUntil = android.os.SystemClock.uptimeMillis() + 400
        toolbarAnimator = android.animation.ValueAnimator.ofInt(toolbar.layoutParams.height.coerceAtLeast(0), toolbarTargetHeight()).apply {
            duration = 180
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                setToolbarHeight(it.animatedValue as Int)
                val alpha = if (show) it.animatedFraction else 1f - it.animatedFraction
                for (i in 0 until toolbar.childCount) toolbar.getChildAt(i).alpha = alpha
            }
            start()
        }
    }

    private fun onPageScroll(y: Int, oldY: Int) {
        if (!prefs.hideToolbar || address.hasFocus() || findBar.visibility == View.VISIBLE) return
        if (android.os.SystemClock.uptimeMillis() < ignoreScrollUntil) return
        if (y < dp(56)) {
            scrollSum = 0
            setToolbarShown(true)
            return
        }
        val dy = y - oldY
        scrollSum = if ((dy > 0) == (scrollSum > 0)) scrollSum + dy else dy
        if (scrollSum > dp(40)) setToolbarShown(false)
        else if (scrollSum < -dp(40)) setToolbarShown(true)
    }

    // ---------------------------------------------------------------- tab previews and closed tabs

    /** A small picture of what the tab shows, for the tab overview. */
    private fun capturePreview(tab: BrowserTab) {
        val view = tab.webView
        if (view.width == 0 || view.height == 0 || view.visibility != View.VISIBLE) return
        runCatching {
            val scale = 0.3f
            val height = minOf(view.height, (view.width * 1.3f).toInt())
            val bitmap = android.graphics.Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(canvas)
            tab.preview = bitmap
        }
    }

    private class ClosedTab(val url: String, val index: Int)
    private val closedTabs = mutableListOf<ClosedTab>()

    private fun reopenClosedTab() {
        val closed = closedTabs.removeLastOrNull() ?: return
        openTab(closed.url, index = closed.index.coerceAtMost(tabs.size))
        if (tabSwitcher.visibility == View.VISIBLE) renderTabSwitcher()
    }

    private lateinit var undoBar: LinearLayout
    private val hideUndoLater = Runnable { hideUndo() }

    /** A short note at the bottom with an undo button, gone by itself after a few seconds. */
    private fun showUndo(text: String, action: () -> Unit) {
        if (!::undoBar.isInitialized) {
            undoBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                elevation = dp(12).toFloat()
                setPadding(dp(18), dp(2), dp(4), dp(2))
            }
            root.addView(undoBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        undoBar.removeAllViews()
        undoBar.background = roundRect(ColorUtils.setAlphaComponent(theme.surface, 250), dp(16).toFloat(), theme.border)
        undoBar.addView(TextView(this).apply { this.text = text; textSize = 14f; setTextColor(theme.textPrimary) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoBar.addView(TextView(this).apply {
            this.text = "GERİ AL"
            textSize = 14f
            setTextColor(theme.accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rippleBackground()
            setOnClickListener { hideUndo(); action() }
        })
        undoBar.layoutParams = (undoBar.layoutParams as FrameLayout.LayoutParams).apply {
            setMargins(dp(14), 0, dp(14), insetBottom + dp(if (prefs.toolbarBottom && tabSwitcher.visibility != View.VISIBLE) 76 else 16))
        }
        undoBar.visibility = View.VISIBLE
        undoBar.bringToFront()
        fadeIn(undoBar, dp(16), 200)
        undoBar.removeCallbacks(hideUndoLater)
        undoBar.postDelayed(hideUndoLater, 4000)
    }

    private fun hideUndo() {
        if (!::undoBar.isInitialized || undoBar.visibility != View.VISIBLE) return
        undoBar.removeCallbacks(hideUndoLater)
        undoBar.animate().alpha(0f).setDuration(160).withEndAction { undoBar.visibility = View.GONE; undoBar.alpha = 1f }.start()
    }

    // ---------------------------------------------------------------- private tab lock

    /** Unlocked until Sorune leaves the screen or the last private tab closes. */
    private var privateUnlocked = false
    private var authRunning = false
    private var lockView: LinearLayout? = null

    private fun privateLocked() = prefs.privateLock && !privateUnlocked

    /** Fingerprint, face or the screen lock (PIN, pattern), whichever the phone has. */
    private fun authenticate(title: String, onSuccess: () -> Unit) {
        if (authRunning) return
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("Telefonda ekran kilidi yok: önce parmak izi ya da PIN ayarla")
            return
        }
        authRunning = true
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authRunning = false
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authRunning = false
            }
        }).authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Parmak izi, yüz ya da ekran kilidi")
            .setAllowedAuthenticators(allowed)
            .build())
    }

    private fun unlockPrivate() = authenticate("Gizli sekmeleri aç") {
        privateUnlocked = true
        updatePrivateLock()
    }

    /** A locked private tab shows a lock instead of its page; private tabs are never in screenshots while the lock is on. */
    private fun updatePrivateLock() {
        val tab = active
        val secure = tab?.isPrivate == true && prefs.privateLock
        if (secure) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val locked = secure && privateLocked()
        if (!locked) {
            lockView?.visibility = View.GONE
            return
        }
        val view = lockView ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            content.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }.also { lockView = it }
        view.removeAllViews()
        view.setBackgroundColor(theme.base)
        view.addView(TextView(this).apply { text = "🔒"; textSize = 46f; gravity = Gravity.CENTER })
        view.addView(TextView(this).apply {
            text = "Gizli sekmeler kilitli"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(theme.textPrimary)
            setPadding(0, dp(10), 0, dp(18))
        })
        view.addView(TextView(this).apply {
            text = "Kilidi aç"
            textSize = 15f
            setTextColor(theme.base)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = roundRect(theme.accent, dp(24).toFloat())
            setOnClickListener { unlockPrivate() }
        })
        view.visibility = View.VISIBLE
        view.bringToFront()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) unlockPrivate()
    }

    private fun setPrivateLock(on: Boolean) {
        authenticate(if (on) "Gizli sekme kilidini aç" else "Gizli sekme kilidini kapat") {
            prefs.privateLock = on
            privateUnlocked = true
            updatePrivateLock()
            broadcast(settingsState())
        }
    }

    // ---------------------------------------------------------------- blocked sites (private DNS)

    /** The phone's private DNS: on and its server, or off. Blocks done by DNS fall away with it on. */
    private fun privateDnsState(): JSONObject {
        val state = JSONObject().put("active", false).put("server", "")
        runCatching {
            val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
            val link = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return state
            state.put("active", link.isPrivateDnsActive).put("server", link.privateDnsServerName ?: "")
        }
        return state
    }

    /** Secure DNS for Sorune alone: every HTTPS connection of every tab goes through [SecureDns]. */
    private fun applySecureDns() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            toast("Bu telefondaki WebView sürümü bunu desteklemiyor")
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        if (prefs.secureDns) {
            val port = runCatching { SecureDns.start() }.getOrNull() ?: return
            androidx.webkit.ProxyController.getInstance().setProxyOverride(
                androidx.webkit.ProxyConfig.Builder().addProxyRule("127.0.0.1:$port", androidx.webkit.ProxyConfig.MATCH_HTTPS).build(),
                executor) {}
        } else {
            androidx.webkit.ProxyController.getInstance().clearProxyOverride(executor) { SecureDns.stop() }
        }
    }

    /** One line for the settings page: working, or the last time a service did not answer. */
    private fun secureDnsStatus(): String {
        if (!prefs.secureDns) return ""
        val minutes = (System.currentTimeMillis() - SecureDns.lastProblemAt) / 60_000
        val problem = SecureDns.lastProblem
        return if (problem != null && minutes < 30) "Son sorun ($minutes dk önce): $problem. Diğer hizmetler denendi."
            else "Çalışıyor: ${SecureDns.answered} adres güvenli DNS ile bulundu."
    }

    /** No app may change private DNS itself: the address goes to the clipboard and the system settings open. */
    private fun openPrivateDnsSettings() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val server = SecureDns.provider.privateDns
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DNS", server))
        toast("$server kopyalandı: Özel DNS'e yapıştır")
        val opened = runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }.isSuccess
        if (!opened) runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
    }

    // ---------------------------------------------------------------- print, data saver

    /** The system print dialog, which also saves the page as a PDF. */
    private fun printPage(tab: BrowserTab) {
        val name = tab.title.ifBlank { "Sorune" }
        runCatching {
            (getSystemService(PRINT_SERVICE) as android.print.PrintManager)
                .print(name, tab.webView.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
        }.onFailure { toast("Yazdırma açılamadı") }
    }

    /** Data saver: web pages load no pictures. Sorune's own pages keep theirs. */
    private fun applyDataSaver(tab: BrowserTab) {
        tab.webView.settings.blockNetworkImage = prefs.dataSaver && isWebPage(tab.url)
    }

    private fun setDataSaver(on: Boolean) {
        prefs.dataSaver = on
        tabs.forEach { applyDataSaver(it) }
        broadcast(settingsState())
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
        applyUaFor(tab, url)
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
        if (suggestionScroll.visibility != View.VISIBLE) fadeIn(suggestionScroll, -dp(6), 150)
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
        active?.let { capturePreview(it) }
        renderTabSwitcher()
        tabSwitcher.visibility = View.VISIBLE
        fadeIn(tabSwitcher, dp(28), 230)
    }

    private fun hideTabSwitcher() {
        if (tabSwitcher.visibility != View.VISIBLE) return
        tabSwitcher.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(170)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { tabSwitcher.visibility = View.GONE; tabSwitcher.alpha = 1f; tabSwitcher.translationY = 0f }.start()
    }

    /** Small entrance: fade from transparent while sliding [dy] pixels into place. */
    private fun fadeIn(view: View, dy: Int, duration: Long) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dy.toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(duration)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
    }

    /** Tabs two by two, each with a picture of its page; private tabs in their own group below. */
    private fun renderTabSwitcher() {
        tabList.removeAllViews()
        addTabGrid(tabs.filter { !it.isPrivate })
        val privateTabs = tabs.filter { it.isPrivate }
        if (privateTabs.isEmpty()) return
        tabList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "🕶 Gizli sekmeler"
                textSize = 16f
                setTextColor(theme.textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "Hepsini kapat"
                textSize = 13f
                setTextColor(theme.accent)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rippleBackground()
                setOnClickListener { closePrivateTabs() }
            })
        })
        tabList.addView(TextView(this).apply {
            text = "Sorune'den çıkıp 5 dakika dönmezsen kapanır, hiçbir şey kaydedilmez."
            textSize = 12f
            setTextColor(theme.textSecondary)
            setPadding(dp(4), 0, 0, dp(10))
        })
        addTabGrid(privateTabs)
    }

    private fun addTabGrid(list: List<BrowserTab>) {
        var row: LinearLayout? = null
        list.forEachIndexed { i, tab ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                tabList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                })
            }
            row?.addView(tabCard(tab), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % 2 == 1) marginStart = dp(12)
            })
        }
        if (list.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(12) })
    }

    private fun tabCard(tab: BrowserTab): View {
        val current = tab === active
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(if (current) ColorUtils.setAlphaComponent(theme.accent, 46) else theme.surface, dp(16).toFloat(),
                if (current) theme.accent else theme.border, if (current) dp(2) else 1)
            clipToOutline = true
            setOnClickListener {
                activate(tab)
                hideTabSwitcher()
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val icon = tab.favicon.takeUnless { tab.isPrivate && privateLocked() }
        if (icon != null) head.addView(ImageView(this).apply { setImageBitmap(icon) }, LinearLayout.LayoutParams(dp(16), dp(16)))
        else if (tab.isPrivate) head.addView(TextView(this).apply { text = "🕶"; textSize = 12f })
        head.addView(TextView(this).apply {
            text = if (tab.isPrivate && privateLocked()) "Gizli sekme" else tab.title.ifEmpty { if (isNewTab(tab.url)) "Yeni sekme" else displayAddress(tab.url).ifEmpty { "Sorune" } }
            textSize = 13f
            setTextColor(theme.textPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(theme.textSecondary)
            contentDescription = getString(R.string.close_tab)
            setOnClickListener { closeTab(tab) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(head)
        val preview = tab.preview.takeUnless { tab.isPrivate && privateLocked() }
        if (preview != null) {
            card.addView(ImageView(this).apply {
                setImageBitmap(preview)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
        } else {
            card.addView(TextView(this).apply {
                text = if (tab.isPrivate) "🕶" else "🌐"
                textSize = 34f
                gravity = Gravity.CENTER
                setBackgroundColor(ColorUtils.setAlphaComponent(theme.base, 200))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
        }
        return card
    }
    // ---------------------------------------------------------------- menu

    // ---------------------------------------------------------------- side panel (☰)

    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: ScrollView
    private lateinit var drawerContent: LinearLayout
    private var drawerOpen = false
    private var insetTop = 0
    private var insetBottom = 0

    private fun createDrawer() {
        drawerScrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        drawerContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerPanel = ScrollView(this).apply {
            visibility = View.GONE
            elevation = dp(16).toFloat()
            isVerticalScrollBarEnabled = false
            addView(drawerContent)
        }
        root.addView(drawerScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val width = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.84f).toInt())
        root.addView(drawerPanel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    }

    /** The panel slides in from the left, the page dims behind it. */
    private fun openDrawer() {
        clearAddressFocus()
        if (findBar.visibility == View.VISIBLE) closeFind()
        buildDrawer()
        drawerOpen = true
        drawerPanel.background = roundRectCorners(ColorUtils.setAlphaComponent(theme.surface, 250), dp(22).toFloat())
        drawerPanel.scrollTo(0, 0)
        drawerScrim.visibility = View.VISIBLE
        drawerPanel.visibility = View.VISIBLE
        drawerScrim.bringToFront()
        drawerPanel.bringToFront()
        drawerPanel.translationX = -drawerPanel.layoutParams.width.toFloat()
        drawerScrim.animate().alpha(1f).setDuration(220).start()
        drawerPanel.animate().translationX(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    private fun closeDrawer(then: (() -> Unit)? = null) {
        if (!drawerOpen) { then?.invoke(); return }
        drawerOpen = false
        drawerScrim.animate().alpha(0f).setDuration(180).withEndAction { drawerScrim.visibility = View.GONE }.start()
        drawerPanel.animate().translationX(-drawerPanel.layoutParams.width.toFloat()).setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction { drawerPanel.visibility = View.GONE; then?.invoke() }.start()
    }

    private fun buildDrawer() {
        val tab = active
        val web = tab != null && isWebPage(tab.url)
        val box = drawerContent
        box.removeAllViews()
        box.setPadding(dp(14), insetTop + dp(14), dp(14), insetBottom + dp(16))

        // Header: logo, name, version
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, dp(12))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.sorune_logo) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply { text = "Sorune"; textSize = 20f; setTextColor(theme.textPrimary); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                addView(TextView(this@MainActivity).apply { text = "Sürüm ${BuildConfig.VERSION_NAME}"; textSize = 12f; setTextColor(theme.textSecondary) })
            })
        })

        // Quick buttons
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun quickButton(icon: String, label: String, action: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = roundRect(ColorUtils.setAlphaComponent(theme.base, 200), dp(14).toFloat(), theme.border)
            foreground = rippleBackground()
            addView(TextView(this@MainActivity).apply { text = icon; textSize = 18f; gravity = Gravity.CENTER })
            addView(TextView(this@MainActivity).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(theme.textSecondary); maxLines = 1 })
            setOnClickListener { closeDrawer(action) }
        }
        if (prefs.tabsPlace == "swap") {
            box.addView(tabStrip())
        } else {
            val quickItems = mutableListOf<Triple<String, String, () -> Unit>>()
            if (prefs.tabsPlace == "menu") quickItems += Triple("▣ ${tabs.size}", "Sekmeler") { showTabSwitcher() }
            quickItems += quickActions()
            quickItems.forEachIndexed { i, (icon, label, action) ->
                quick.addView(quickButton(icon, label, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
            box.addView(quick)
        }

        fun section(title: String) = box.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(theme.accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(18), 0, dp(6))
        })
        fun item(icon: String, label: String, enabled: Boolean = true, action: () -> Unit) = box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = rippleBackground()
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            addView(TextView(this@MainActivity).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER; setTextColor(theme.accent) }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply { text = label; textSize = 15f; setTextColor(theme.textPrimary); setPadding(dp(10), 0, 0, 0) })
            if (enabled) setOnClickListener { closeDrawer(action) }
        })
        fun toggle(icon: String, label: String, hint: String?, on: Boolean, enabled: Boolean = true, action: () -> Unit) = box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
            background = rippleBackground()
            alpha = if (enabled) 1f else 0.4f
            addView(TextView(this@MainActivity).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER; setTextColor(theme.accent) }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(TextView(this@MainActivity).apply { text = label; textSize = 15f; setTextColor(theme.textPrimary) })
                if (hint != null) addView(TextView(this@MainActivity).apply { text = hint; textSize = 12f; setTextColor(theme.textSecondary) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val switch = androidx.appcompat.widget.SwitchCompat(this@MainActivity).apply {
                isChecked = on
                isEnabled = enabled
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(theme.accent, theme.textSecondary))
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ColorUtils.setAlphaComponent(theme.accent, 110), ColorUtils.setAlphaComponent(theme.textSecondary, 70)))
                isClickable = false
            }
            addView(switch)
            if (enabled) setOnClickListener {
                switch.isChecked = !switch.isChecked
                action()
            }
        })

        if (closedTabs.isNotEmpty()) item("↺", "Kapatılan sekmeyi geri aç") { reopenClosedTab() }

        section("Bu sayfa")
        item("⟳", "Yenile", tab != null) { tab?.webView?.reload() }
        item("→", "İleri", tab?.webView?.canGoForward() == true) { tab?.webView?.goForward() }
        val bookmarked = tab != null && library.isBookmarked(tab.url)
        item(if (bookmarked) "★" else "☆", if (bookmarked) "Yer iminden kaldır" else "Yer imlerine ekle", web) {
            tab?.let {
                val added = library.toggleBookmark(it.url, it.title)
                toast(if (added) "Yer imlerine eklendi" else "Yer iminden kaldırıldı")
                saveLibrary()
            }
        }
        item("⌕", "Sayfada bul", web) { openFind() }
        item("📖", "Okuma modu", web) { tab?.let { lifecycleScope.launch { openReader(it) } } }
        item("⇪", "Paylaş", tab != null && tab.url.startsWith("http")) { tab?.let { share(it) } }
        item("🖨", "PDF olarak kaydet / yazdır", web) { tab?.let { printPage(it) } }
        item("⌂", "Ana ekrana ekle", web) { tab?.let { addToHomeScreen(it) } }
        toggle("🖥", "Masaüstü sitesi", "Bu site için hatırlanır", tab?.desktopMode == true, web) { tab?.let { toggleDesktop(it) } }

        section("Çeviri")
        item("🌐", if (tab?.translateTarget != null) "Orijinali göster" else "Sayfayı çevir (${PageTranslator.languageName(prefs.translateLanguage)})", web) {
            tab?.let { if (it.translateTarget != null) showOriginal(it) else lifecycleScope.launch { translatePage(it, quiet = false) } }
        }
        item("🖼", "Ekranı çevir (resimler dahil)", web) { tab?.let { lifecycleScope.launch { translateScreen(it, quiet = false) } } }
        toggle("🔁", "Otomatik ekran çevirisi", "Her ${prefs.autoScreenSeconds} saniyede, ekran değişince", prefs.autoScreenTranslate) {
            prefs.autoScreenTranslate = !prefs.autoScreenTranslate
            broadcast(settingsState())
        }

        section("Koruma")
        toggle("🛡", "Reklam engelleyici", if (adBlockOn) "Bu sayfada ${tab?.blocked?.get() ?: 0} engellendi" else "Kapalı", adBlockOn) { setAdBlock(!adBlockOn) }
        toggle("🍃", "Veri tasarrufu", "Sayfalardaki resimler yüklenmez", prefs.dataSaver) { setDataSaver(!prefs.dataSaver) }

        section("Sorune")
        item("⚙", "Ayarlar ve temalar") { openSettings() }
        item("⭳", "Güncellemeleri denetle") { checkForUpdates(manual = true) }
    }

    private fun quickActions(): List<Triple<String, String, () -> Unit>> {
        val items = mutableListOf<Triple<String, String, () -> Unit>>(Triple("＋", "Yeni") { openTab(NEW_TAB); Unit })
        if (privateSupported) items += Triple("🕶", "Gizli") { openTab(NEW_TAB, isPrivate = true); Unit }
        items += Triple("🕘", "Geçmiş") { openLibrary("gecmis") }
        items += Triple("★", "Yer imleri") { openLibrary("yer-imleri") }
        items += Triple("⬇", "İndirilen") { openLibrary("indirmeler") }
        return items
    }

    /** "Swap" layout: the ⊞ button in the address bar opens the quick actions as a small menu. */
    private fun showQuickMenu() {
        clearAddressFocus()
        val items = quickActions()
        val menu = PopupMenu(this, tabsButton)
        items.forEachIndexed { i, (icon, label, _) -> menu.menu.add(0, i, i, "$icon  ${if (label == "Yeni") "Yeni sekme" else if (label == "Gizli") "Yeni gizli sekme" else label}") }
        menu.setOnMenuItemClickListener { items[it.itemId].third(); true }
        menu.show()
    }

    /** "Swap" layout: open tabs side by side at the top of the panel, the current one framed. */
    private fun tabStrip(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ordered = tabs.filter { !it.isPrivate } + tabs.filter { it.isPrivate }
        for (tab in ordered) {
            val current = tab === active
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = roundRect(if (tab.isPrivate) Color.argb(235, 8, 8, 10) else ColorUtils.setAlphaComponent(theme.base, 200), dp(14).toFloat(),
                    if (current) theme.accent else theme.border, if (current) dp(2) else 1)
                foreground = rippleBackground()
                val icon = tab.favicon.takeUnless { tab.isPrivate && privateLocked() }
                if (icon != null) addView(ImageView(this@MainActivity).apply { setImageBitmap(icon) }, LinearLayout.LayoutParams(dp(18), dp(18)))
                else addView(TextView(this@MainActivity).apply { text = if (tab.isPrivate) "🕶" else "🌐"; textSize = 14f })
                addView(TextView(this@MainActivity).apply {
                    text = if (tab.isPrivate && privateLocked()) "Gizli sekme" else tab.title.ifBlank { displayAddress(tab.url).ifBlank { "Yeni sekme" } }
                    textSize = 13f
                    setTextColor(if (current) theme.textPrimary else theme.textSecondary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener { closeDrawer { activate(tab) } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        row.addView(TextView(this).apply {
            text = "▣ ${tabs.size}\nTümü"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(theme.textSecondary)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundRect(ColorUtils.setAlphaComponent(theme.base, 200), dp(14).toFloat(), theme.border)
            foreground = rippleBackground()
            setOnClickListener { closeDrawer { showTabSwitcher() } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            // Keep the current tab in view.
            post { ordered.indexOf(active).takeIf { it >= 0 }?.let { scrollTo(row.getChildAt(it).left - dp(20), 0) } }
        }
    }

    private fun roundRectCorners(fill: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
    }

    /** One new tab setting, checked before it is kept. */
    private fun setNewTabOption(key: String, value: Any?) {
        val options = runCatching { JSONObject(prefs.newTab) }.getOrDefault(JSONObject())
        when (key) {
            "clockFont", "labelFont" -> (value as? String)?.takeIf { it in NEW_TAB_FONTS }?.let { options.put(key, it) } ?: return
            "effect" -> (value as? String)?.takeIf { it in NEW_TAB_EFFECTS }?.let { options.put(key, it) } ?: return
            "hour12", "greeting", "bookmarks", "weather", "note", "todo" -> (value as? Boolean)?.let { options.put(key, it) } ?: return
            "dim" -> (value as? Number)?.let { options.put(key, it.toInt().coerceIn(0, 80)) } ?: return
            "blur" -> (value as? Number)?.let { options.put(key, it.toInt().coerceIn(0, 20)) } ?: return
            "glass" -> (value as? Number)?.let { options.put(key, it.toInt().coerceIn(0, 90)) } ?: return
            else -> return
        }
        prefs.newTab = options.toString()
        broadcast(config())
        broadcast(settingsState())
    }

    // ---------------------------------------------------------------- swipe between tabs

    /** A quick sideways swipe on the address bar goes to the next or previous tab. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTabSwipe() {
        var flung = false
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!prefs.swipeTabs || e1 == null || address.hasFocus() || tabs.size < 2) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) < dp(50) || Math.abs(dx) < Math.abs(dy) * 1.5f || Math.abs(velocityX) < 500) return false
                flung = true
                switchTab(if (dx < 0) 1 else -1)
                return true
            }
        })
        address.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) flung = false
            detector.onTouchEvent(event)
            // After a swipe the field must not take the tap as "start typing".
            event.actionMasked == android.view.MotionEvent.ACTION_UP && flung
        }
    }

    private fun switchTab(step: Int) {
        // Normal and private tabs never mix: a swipe stays on its own side.
        val group = tabs.filter { it.isPrivate == active?.isPrivate }
        val index = group.indexOf(active)
        if (index < 0 || group.size < 2) return
        val target = group[(index + step).mod(group.size)]
        activate(target)
        content.translationX = step * dp(40).toFloat()
        content.alpha = 0.4f
        content.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    // ---------------------------------------------------------------- reader mode

    /** Finds the article (the block with the most paragraph text) and returns its parts as plain data. */
    private val readerScript = """
        (() => {
            let best = null, bestScore = 0;
            for (const el of document.querySelectorAll("article, main, [role=main], div, section")) {
                let score = 0;
                for (const p of el.querySelectorAll(":scope > p, :scope > div > p")) score += (p.innerText || "").trim().length;
                if (el.tagName === "ARTICLE") score *= 1.3;
                if (score > bestScore) { bestScore = score; best = el; }
            }
            if (!best || bestScore < 400) return null;
            const blocks = [];
            for (const el of best.querySelectorAll("h1, h2, h3, p, img, blockquote, li")) {
                if (blocks.length > 500) break;
                if (el.closest("nav, footer, aside, form, button, figure figcaption")) continue;
                if (el.tagName === "IMG") {
                    if ((el.naturalWidth || el.width) >= 200 && /^https?:/.test(el.currentSrc || el.src)) blocks.push({ t: "img", src: el.currentSrc || el.src });
                    continue;
                }
                const text = (el.innerText || "").replace(/\s+/g, " ").trim();
                if (!text || (el.tagName === "LI" && text.length < 20)) continue;
                blocks.push({ t: /^H/.test(el.tagName) ? "h" : el.tagName === "BLOCKQUOTE" ? "quote" : "p", text });
            }
            return { title: document.title, site: location.hostname.replace(/^www\./, ""), url: location.href, blocks };
        })()
    """.trimIndent()

    private suspend fun openReader(tab: BrowserTab) {
        val article = runCatching { org.json.JSONTokener(tab.webView.eval(readerScript)).nextValue() as? JSONObject }.getOrNull()
        if (article == null) {
            toast("Bu sayfada okunacak bir yazı bulamadım")
            return
        }
        val reader = openTab(READER, index = tabs.indexOf(tab) + 1, isPrivate = tab.isPrivate)
        readerArticles[reader.id] = article.put("type", "reader:article")
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

    private fun hostOf(url: String?): String? = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()

    /** Desktop or phone browser identity for this address (remembered per site); true when it changed. */
    private fun applyUaFor(tab: BrowserTab, url: String): Boolean {
        val desktop = hostOf(url)?.let { it in prefs.desktopSites } == true
        if (desktop == tab.desktopMode) return false
        tab.desktopMode = desktop
        tab.webView.settings.userAgentString = if (desktop) DESKTOP_UA else WebViewDefaults.userAgent(this)
        return true
    }

    /** Desktop site on/off for this site, remembered for next time. */
    private fun toggleDesktop(tab: BrowserTab) {
        val host = hostOf(tab.url) ?: return
        prefs.desktopSites = if (host in prefs.desktopSites) prefs.desktopSites - host else prefs.desktopSites + host
        applyUaFor(tab, tab.url)
        toast(if (tab.desktopMode) "$host masaüstü sitesi olarak açılacak" else "$host telefon sitesi olarak açılacak")
        tab.webView.reload()
    }

    /** The site as an icon on the home screen (opens in Sorune). */
    private fun addToHomeScreen(tab: BrowserTab) {
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            toast("Ana ekranın kısayol eklemeyi desteklemiyor")
            return
        }
        val icon = tab.favicon.takeUnless { tab.isPrivate && privateLocked() }?.let { androidx.core.graphics.drawable.IconCompat.createWithBitmap(it) }
            ?: androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "site-" + tab.url.hashCode())
            .setShortLabel(tab.title.ifBlank { hostOf(tab.url) ?: "Sorune" }.take(24))
            .setIcon(icon)
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url), this, MainActivity::class.java))
            .build()
        androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
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
            "newtab:set" -> setNewTabOption(message.optString("key"), message.opt("value"))
            "newtab:shortcuts" -> {
                val items = JSONArray()
                val list = message.optJSONArray("items") ?: JSONArray()
                for (i in 0 until minOf(list.length(), 16)) {
                    val item = list.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                    items.put(JSONObject().put("name", item.optString("name").take(24)).put("url", url.take(2000)))
                }
                prefs.newTab = JSONObject(prefs.newTab).put("shortcuts", items).toString()
                broadcast(config())
            }
            "newtab:widgets" -> {
                // Only the known fields, size-limited: the new tab page writes these.
                val data = message.optJSONObject("data") ?: JSONObject()
                val todos = JSONArray()
                val list = data.optJSONArray("todos") ?: JSONArray()
                for (i in 0 until minOf(list.length(), 30)) {
                    val item = list.optJSONObject(i) ?: continue
                    todos.put(JSONObject().put("text", item.optString("text").take(120)).put("done", item.optBoolean("done")))
                }
                val clean = JSONObject()
                    .put("city", data.optString("city").take(60))
                    .put("note", data.optString("note").take(2000))
                    .put("todos", todos)
                prefs.newTab = JSONObject(prefs.newTab).put("widgets", clean).toString()
            }
            "newtab:resetShortcuts" -> {
                prefs.newTab = JSONObject(prefs.newTab).apply { remove("shortcuts") }.toString()
                broadcast(config())
                broadcast(settingsState())
            }
            "setTranslateLanguage" -> {
                message.optString("value").takeIf { it in TRANSLATE_LANGUAGES }?.let { prefs.translateLanguage = it }
                broadcast(settingsState())
            }
            "setSwipeTabs" -> {
                prefs.swipeTabs = message.optBoolean("value", true)
                broadcast(settingsState())
            }
            "setHideToolbar" -> {
                prefs.hideToolbar = message.optBoolean("value", true)
                if (!prefs.hideToolbar) setToolbarShown(true)
                broadcast(settingsState())
            }
            "setDataSaver" -> setDataSaver(message.optBoolean("value", false))
            "setPrivateLock" -> setPrivateLock(message.optBoolean("value", false))
            "dns:open" -> openPrivateDnsSettings()
            "setDnsProvider" -> {
                val provider = SecureDns.Provider.find(message.optString("value"))
                prefs.dnsProvider = provider.id
                SecureDns.provider = provider
                broadcast(settingsState())
            }
            "setSecureDns" -> {
                prefs.secureDns = message.optBoolean("value", false)
                applySecureDns()
                broadcast(settingsState())
            }
            "setPullRefresh" -> {
                prefs.pullRefresh = message.optBoolean("value", false)
                broadcast(settingsState())
            }
            "reader:get" -> readerArticles[tab.id]?.let { post(tab, it) }
            "reader:close" -> closeTab(tab)
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
            "setTabsPlace" -> {
                prefs.tabsPlace = message.optString("value").takeIf { it in setOf("bar", "menu", "swap") } ?: "bar"
                updateTabsButton()
                broadcast(settingsState())
            }
            "setToolbarBottom" -> {
                prefs.toolbarBottom = message.optBoolean("value", false)
                applyToolbarPosition()
                ViewCompat.requestApplyInsets(root)
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
            .put("focusX", theme.focusX.toDouble()).put("particles", theme.particles))
        .put("newtab", runCatching { JSONObject(prefs.newTab) }.getOrDefault(JSONObject()))
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
        .put("newtab", runCatching { JSONObject(prefs.newTab) }.getOrDefault(JSONObject()))
        .put("swipeTabs", prefs.swipeTabs)
        .put("tabsPlace", prefs.tabsPlace)
        .put("translateLanguage", prefs.translateLanguage)
        .put("languages", JSONArray(TRANSLATE_LANGUAGES.map { JSONObject().put("id", it).put("name", PageTranslator.languageName(it)) }))
        .put("pullRefresh", prefs.pullRefresh)
        .put("hideToolbar", prefs.hideToolbar)
        .put("dataSaver", prefs.dataSaver)
        .put("privateLock", prefs.privateLock)
        .put("secureDns", prefs.secureDns)
        .put("dnsProvider", SecureDns.provider.id)
        .put("dnsStatus", secureDnsStatus())
        .put("dnsProviders", JSONArray(SecureDns.Provider.entries.map {
            JSONObject().put("id", it.id).put("name", it.title).put("note", it.note).put("server", it.privateDns)
        }))
        .put("privateDns", privateDnsState())
        .put("autoSeconds", prefs.autoScreenSeconds)
        .put("version", BuildConfig.VERSION_NAME)

    // ---------------------------------------------------------------- private tabs, dark sites

    /** Puts a fresh page in place of one whose process is gone, at the same address. */
    private fun recoverTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasActive = tab === active
        val url = tab.url.ifEmpty { NEW_TAB }
        tabs.removeAt(index)
        content.removeView(tab.webView)
        runCatching { tab.webView.destroy() }
        if (wasActive) active = null
        val fresh = openTab(url, activate = wasActive, index = index, isPrivate = tab.isPrivate)
        fresh.translateTarget = tab.translateTarget
        fresh.preview = tab.preview
        if (tabSwitcher.visibility == View.VISIBLE) renderTabSwitcher()
    }

    /** Closes every private tab and wipes what they left behind (cookies, storage, cache). */
    private fun closePrivateTabs() {
        val privateTabs = tabs.filter { it.isPrivate }
        if (privateTabs.isEmpty()) return
        val wasActive = active?.isPrivate == true
        for (tab in privateTabs) {
            tabs.remove(tab)
            readerArticles.remove(tab.id)
            content.removeView(tab.webView)
            tab.webView.destroy()
        }
        deletePrivateProfile()
        // The pages close a moment after destroy(); try again once they surely have.
        content.postDelayed({ if (tabs.none { it.isPrivate }) deletePrivateProfile() }, 1500)
        if (tabs.isEmpty()) openTab(NEW_TAB) else if (wasActive) activate(tabs.last())
        updateTabsButton()
        if (tabSwitcher.visibility == View.VISIBLE) renderTabSwitcher()
    }

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
        setToolbarShown(true)
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
        fadeIn(findBar, -dp(12), 180)
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
        tab.translateTarget = prefs.translateLanguage
        tab.translating = true
        updateTranslationChip()
        try {
            val result = PageTranslator.translateNewText(tab.webView, tab.translateTarget ?: prefs.translateLanguage)
            result.sourceLanguage?.let { tab.translatedFrom = it }
            if (first && PageTranslator.sameLanguage(result.sourceLanguage, tab.translateTarget)) {
                showOriginal(tab)
                toast("Bu sayfa zaten ${PageTranslator.languageName(prefs.translateLanguage)}")
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
            val outcome = ScreenTranslator.translateVisible(this, tab.webView, prefs.translateLanguage)
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
        translationChip.text = "🌐 ${PageTranslator.languageName(tab.translatedFrom)} → ${PageTranslator.languageName(tab.translateTarget)}" + (if (tab.translating) " …" else "") + "   ✕"
        translationChip.setTextColor(theme.textPrimary)
        translationChip.background = roundRect(ColorUtils.setAlphaComponent(theme.surface, 235), dp(18).toFloat(), theme.accent, dp(1))
        if (translationChip.visibility != View.VISIBLE) fadeIn(translationChip, dp(10), 200)
        translationChip.visibility = View.VISIBLE
        translationChip.bringToFront()
    }

    // ---------------------------------------------------------------- theme

    private fun applyTheme() {
        theme = Themes.find(this, prefs.themeId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        root.setBackgroundColor(theme.base)
        ViewCompat.requestApplyInsets(root)

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
        refresh.setColorSchemeColors(theme.accent)
        refresh.setProgressBackgroundColorSchemeColor(theme.surface)
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
