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
    modifier: Modifier,
)

/**
 * The screen around [SiteLoginView]: a bar with Close and the load progress,
 * the note about Google, and what went wrong. Found cookies go to [onCookies],
 * which checks them with the site the way every cookie login is checked, and
 * reports back an error or null.
 */
@Composable
internal fun SiteSignIn(
    origin: String,
    host: String,
    busy: Boolean,
    onCookies: (Session.Cookies, (String?) -> Unit) -> Unit,
    onComputer: () -> Unit,
    onClose: () -> Unit,
) {
    // A new attempt gets a new web view; the old one has forgotten everything.
    var attempt by remember { mutableIntStateOf(0) }
    var progress by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var landed by remember { mutableStateOf(false) }
    // Back steps back through the login's pages first, then closes.
    var webBack by remember { mutableStateOf<() -> Boolean>({ false }) }
    OnBack(enabled = true) { if (landed || !webBack()) onClose() }
    Column(Modifier.fillMaxSize().background(Palette.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Close", color = Palette.Blue) }
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
                "Remember me is ticked for you. Google sign-in may not work inside apps; " +
                    "if it fails, use Sign in from a computer.",
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
                            problem = "You reached $host, but it didn't hand over a login (no sid cookie). " +
                                "Try again, or sign in from a computer."
                        } else {
                            problem = null
                            onCookies(session) { error -> problem = error }
                        }
                    },
                    goBack = { webBack = it },
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
                        TextButton(onClick = onComputer) { Text("Sign in from a computer", color = Palette.Blue) }
                    }
                }
            }
        }
    }
}
