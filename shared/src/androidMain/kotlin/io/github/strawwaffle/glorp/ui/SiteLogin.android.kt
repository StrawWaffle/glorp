package io.github.strawwaffle.glorp.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.strawwaffle.glorp.auth.WebSignIn
import io.github.strawwaffle.glorp.diag.AppLog

internal actual val siteLoginAvailable: Boolean = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun SiteLoginView(
    startUrl: String,
    origin: String,
    onProgress: (Int) -> Unit,
    onLanded: (String?) -> Unit,
    goBack: (() -> Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val progress by rememberUpdatedState(onProgress)
    val landed by rememberUpdatedState(onLanded)
    // Set once the login is in hand: nothing more is read from this view.
    var done by remember { mutableStateOf(false) }

    val view = remember {
        WebView(context).apply {
            // Exactly the space it's given. A web view left to wrap its content
            // gives pages a zero-height viewport, so a page laid out in `vh`
            // (Twitch's login is) comes out blank.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The stock user agent, as the providers expect of an Android web view.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // The app's package name stays out of the requests.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            // Providers keep their own login state in third-party cookies.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    fun finish(page: WebView) {
        if (done) return
        done = true
        val cookies = CookieManager.getInstance().getCookie(origin)
        AppLog.note("signin", "the login landed on the site; ${if (cookies.isNullOrEmpty()) "no cookies" else "cookies read"}")
        page.stopLoading()
        page.loadUrl("about:blank")
        forget()
        landed(cookies)
    }

    remember(view) {
        view.webViewClient = object : WebViewClient() {
            // Every page of the login stays in here; links to other apps (intent:, market:) go nowhere.
            override fun shouldOverrideUrlLoading(page: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "https" && scheme != "http"
            }

            override fun onPageStarted(page: WebView, url: String?, favicon: Bitmap?) {
                if (!done && WebSignIn.landedSignedIn(url, origin)) finish(page)
            }

            override fun onPageFinished(page: WebView, url: String?) {
                if (done) return
                if (WebSignIn.landedSignedIn(url, origin)) finish(page)
                else if (WebSignIn.isLoginPage(url, origin)) page.evaluateJavascript(WebSignIn.TICK_REMEMBER_ME, null)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(page: WebView, newProgress: Int) = progress(newProgress)

            // A provider that opens its page in a new window gets it in this view
            // instead: a throwaway view catches the window's first address.
            override fun onCreateWindow(page: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(context)
                popup.webViewClient = object : WebViewClient() {
                    private fun take(url: String?): Boolean {
                        if (url.isNullOrEmpty() || url == "about:blank") return false
                        page.loadUrl(url)
                        popup.post { popup.destroy() }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = take(request.url.toString())
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        if (take(url)) view.stopLoading()
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        Unit
    }

    remember(view) { goBack { if (!done && view.canGoBack()) { view.goBack(); true } else false } }

    DisposableEffect(view) {
        // Loaded once the view has its size, so the page's viewport is right from the start.
        val load = { view.loadUrl(startUrl) }
        val waitForSize = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.height > 0) {
                    v.removeOnLayoutChangeListener(this)
                    load()
                }
            }
        }
        if (view.height > 0) load() else view.addOnLayoutChangeListener(waitForSize)
        onDispose {
            view.removeOnLayoutChangeListener(waitForSize)
            // Closed before or after the login: the web view keeps nothing.
            if (!done) forget()
        }
    }

    AndroidView(
        factory = { view },
        modifier = modifier,
        onRelease = { it.destroy() },
    )
}

/** Forgets every cookie and all web storage the app's web views hold. */
private fun forget() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}
