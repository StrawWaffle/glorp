package io.github.strawwaffle.glorp.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun EmbedPlayer(url: String, origin: String, modifier: Modifier) {
    val context = LocalContext.current
    // One web view for as long as the player is on screen; embeds swap pages in it.
    val view = remember {
        WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        onRelease = { it.destroy() },
    )
    // The page is there only while the app is on screen. Left playing in the
    // background, a web view keeps decoding video, holding the network and
    // draining the battery; picture in picture will be the way to keep watching.
    LifecycleStartEffect(url, origin) {
        view.onResume()
        // Loaded once the view has its size: a page loaded into a view that
        // hasn't been laid out yet keeps a zero-height viewport for `vh`.
        val load = { view.loadDataWithBaseURL("$origin/bigscreen", frame(url), "text/html", "utf-8", null) }
        val waitForSize = object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(v: android.view.View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.height > 0) {
                    v.removeOnLayoutChangeListener(this)
                    load()
                }
            }
        }
        if (view.height > 0) load() else view.addOnLayoutChangeListener(waitForSize)
        onStopOrDispose {
            view.removeOnLayoutChangeListener(waitForSize)
            view.loadUrl("about:blank")
            view.onPause()
        }
    }
}

private fun frame(url: String): String {
    val src = url.replace("&", "&amp;").replace("\"", "&quot;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;background:#000;overflow:hidden}iframe{position:fixed;left:0;top:0;border:0}</style>
</head><body><iframe src="$src" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen></iframe>
<script>(function(){var f=document.querySelector('iframe');function fit(){f.style.width=innerWidth+'px';f.style.height=innerHeight+'px'}fit();addEventListener('resize',fit)})()</script>
</body></html>"""
}
