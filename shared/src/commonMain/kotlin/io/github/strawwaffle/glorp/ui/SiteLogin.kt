package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.auth.SignInTrace
import io.github.strawwaffle.glorp.auth.WebSignIn

/** Whether this platform has the sign-in web view. */
internal expect val siteLoginAvailable: Boolean

/**
 * The site's login page in a web view of its own (not the player's). It
 * keeps every page of the login inside, ticks "Remember me", and once the
 * login lands on a signed-in page of the site hands over the site's cookies
 * as the platform's cookie store gives them (`a=1; b=2`), or null if it has
 * none. Then, and whenever it leaves the screen, it forgets every cookie and
 * all storage the web view had. [goBack] gets a function that steps back a
 * page in the web view and says whether there was one.
 *
 * Cookie values never reach a log.
 */
@Composable
internal expect fun SiteLoginView(
    startUrl: String,
    origin: String,
    onProgress: (Int) -> Unit,
    onLanded: (String?) -> Unit,
    goBack: (() -> Boolean) -> Unit,
    /** Where the attempt's steps are logged. */
    trace: SignInTrace,
    modifier: Modifier,
)

/**
 * The screen around [SiteLoginView]: a bar with Close and the load progress,
 * the note about passkeys, and what went wrong. Found cookies go to [onCookies],
 * which checks them with the site the way every cookie login is checked, and
 * reports back an error or null.
 */
@Composable
internal fun SiteSignIn(
    origin: String,
    host: String,
    busy: Boolean,
    onCookies: (Session.Cookies, (String?) -> Unit) -> Unit,
    /** Where to go when this fails; null leaves just "Try again" (the computer sign-in is off). */
    onComputer: (() -> Unit)?,
    onClose: () -> Unit,
) {
    // A new attempt gets a new web view; the old one has forgotten everything.
    var attempt by remember { mutableIntStateOf(0) }
    var progress by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var landed by remember { mutableStateOf(false) }
    // Back steps back through the login's pages first, then closes.
    var webBack by remember { mutableStateOf<() -> Boolean>({ false }) }
    val trace = remember(attempt) { SignInTrace() }
    val close = { if (!landed) trace.cancelled(); onClose() }
    OnBack(enabled = true) { if (landed || !webBack()) close() }
    Column(Modifier.fillMaxSize().background(Palette.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = close) { Text("Close", color = Palette.Blue) }
            Text("Sign in to $host", color = Palette.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        if (!landed && progress in 0..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                color = Palette.Glorp,
                trackColor = Palette.Navy,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        if (!landed) {
            Text(
                // Passkeys can't work here: a WebView only passes them on for sites that
                // name the app in their assetlinks.json, or for browsers Google approves.
                "Remember me is ticked for you. Passkeys don't work inside apps; use your password or an authenticator code." +
                    if (onComputer != null) " If it fails, use Sign in from a computer." else "",
                color = Palette.TextDim,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            key(attempt) {
                SiteLoginView(
                    startUrl = WebSignIn.startUrl(origin),
                    origin = origin,
                    onProgress = { progress = it },
                    onLanded = { cookies ->
                        landed = true
                        val session = WebSignIn.session(cookies)
                        if (session == null) {
                            trace.finished("no sid cookie")
                            problem = "You reached $host, but it didn't hand over a login. " +
                                if (onComputer != null) "Try again, or sign in from a computer." else "Try again."
                        } else {
                            problem = null
                            onCookies(session) { error -> trace.finished(error); problem = error }
                        }
                    },
                    goBack = { webBack = it },
                    trace = trace,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy || problem == null) {
                        CircularProgressIndicator(color = Palette.Glorp)
                        Text("Checking the login with $host…", color = Palette.White, fontSize = 15.sp)
                    } else {
                        Text(problem.orEmpty(), color = Palette.Orange, fontSize = 15.sp)
                        Button(
                            onClick = { problem = null; landed = false; progress = 0; attempt++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
                        ) { Text("Try again") }
                        if (onComputer != null) TextButton(onClick = onComputer) { Text("Sign in from a computer", color = Palette.Blue) }
                    }
                }
            }
        }
    }
}
