package io.github.strawwaffle.glorp.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

internal actual val hasSiteLoginPage: Boolean = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun SiteLoginPage(origin: String, onCookies: (String) -> Unit, modifier: Modifier) {
    val callback = rememberUpdatedState(onCookies)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val cookies = CookieManager.getInstance()
            // Start clean, so a cookie left from an earlier try can't count.
            cookies.removeAllCookies(null)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    private var done = false

                    override fun onPageFinished(view: WebView, url: String) {
                        if (done) return
                        val header = cookies.getCookie(origin) ?: return
                        // Back on the site, away from the login and provider
                        // pages, with a session: the login went through.
                        val onSite = url.startsWith(origin) && "/login" !in url && "/oauth" !in url
                        if (onSite && Regex("""(^|;\s*)sid=""").containsMatchIn(header)) {
                            done = true
                            // The app keeps its own encrypted copy; the web
                            // view's cookie jar shouldn't hold a second one.
                            cookies.removeAllCookies(null)
                            cookies.flush()
                            view.stopLoading()
                            callback.value(header)
                        }
                    }
                }
                loadUrl("$origin/login")
            }
        },
        onRelease = { it.destroy() },
    )
}
