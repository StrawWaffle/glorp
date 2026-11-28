package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.auth.Session

/**
 * Signing in, easiest first:
 *
 * - "Sign in with <site>": the site's own login page in the app's sign-in
 *   web view ([SiteSignIn]). Its `sid` and `rememberme` cookies open
 *   everything (settings that follow the account, whisper history,
 *   notifications, /stalk, what you're watching). Every login the site
 *   offers loads there (passkeys can't, see SiteSignIn).
 * - Behind [computerSignIn] (off on Android, where the web view does it all;
 *   on where there's no sign-in web view yet, iOS): "From a computer", the
 *   same cookies, which the site keeps out of reach of pages, so Glorp's
 *   sign-in page (pages/signin/legacy.html) has you copy them from the
 *   browser on a computer and shows them as a QR code to scan here; and the
 *   cookies typed in by hand.
 * - Behind [loginKeys]: a login key from the developer page.
 *
 * The site's OAuth for apps doesn't help: chat takes only login keys made by
 * hand, and turns away any token an app holds (ApiController::userByAuthToken).
 */
@Composable
fun SignInScreen(
    origin: String,
    host: String,
    busy: Boolean,
    onSession: (Session, (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
    /** Scans a QR code; null where there's no scanner. */
    scanCode: (((String?) -> Unit) -> Unit)? = null,
    /** Reads a sign-in code from the computer page. */
    readCode: (String) -> Session? = { null },
    /**
     * Offer signing in with a login key. Off for now: the site's login page
     * opens more. A key already in use keeps working either way.
     */
    loginKeys: Boolean = false,
    /** Offer the QR code from a computer and typing in the cookies (a build flag; see GlorpApp). */
    computerSignIn: Boolean = false,
    /** Opens the site's login page in the app (full screen, over everything); null where there's none. */
    onSiteSignIn: (() -> Unit)? = null,
) {
    var way by remember { mutableStateOf(Way.Choose) }
    var problem by remember { mutableStateOf<String?>(null) }
    val submit: (Session) -> Unit = { session -> problem = null; onSession(session) { problem = it } }
    val log: (String) -> Unit = { io.github.strawwaffle.glorp.diag.AppLog.note("signin", it) }
    OnBack(enabled = way != Way.Choose) { way = Way.Choose }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { if (way == Way.Choose) onClose() else way = Way.Choose }) { Text("Back", color = Palette.Blue) }
            Text("Sign in to $host", color = Palette.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                problem?.let { Text(it, color = Palette.Orange, fontSize = 14.sp) }
                when (way) {
                    Way.Choose -> {
                        if (onSiteSignIn != null) {
                            SiteOption(host, busy) { problem = null; onSiteSignIn() }
                            if (computerSignIn) Text("Having trouble?", color = Palette.TextDim, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        if (computerSignIn) ComputerSignIn(host, busy, scanCode, readCode, submit, log) { problem = it }
                        if (computerSignIn || loginKeys) Text("Other ways", color = Palette.TextDim, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (loginKeys) {
                            TextButton(onClick = { way = Way.Key; problem = null }) { Text("Paste a login key", color = Palette.Blue) }
                        }
                        if (computerSignIn) {
                            TextButton(onClick = { way = Way.Cookies; problem = null }) { Text("Type in the browser's cookies", color = Palette.Blue) }
                        }
                    }
                    Way.Key -> LoginKeyForm(origin, host, busy) { log("method login key"); submit(it) }
                    Way.Cookies -> CookieForm(host, busy) { log("method cookies typed in"); submit(it) }
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize().background(Palette.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Glorp)
                }
            }
        }
    }
}

private enum class Way { Choose, Key, Cookies }

/** Where the computer half of the QR sign-in lives. */
const val SIGN_IN_PAGE = "https://strawwaffle.dev/glorp/signin/legacy.html"

@Composable
private fun Option(title: String, detail: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Navy, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(detail, color = Palette.TextDim, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun SiteOption(host: String, busy: Boolean, open: () -> Unit) = Option(
    "Sign in with $host",
    "Use $host's own login page, here in the app, with any of its logins. For everything: chat and whispers, " +
        "settings that follow your account, whisper history, notifications, /stalk and /mentions.",
) {
    Button(
        onClick = open,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
    ) { Text("Sign in with $host") }
}

@Composable
private fun ComputerSignIn(
    host: String,
    busy: Boolean,
    scanCode: (((String?) -> Unit) -> Unit)?,
    readCode: (String) -> Session?,
    submit: (Session) -> Unit,
    log: (String) -> Unit,
    onProblem: (String) -> Unit,
) = Option(
    "Sign in from a computer",
    "For everything. Chat and whispers, settings that follow your account, whisper history, " +
        "notifications, /stalk and /mentions, and what you're watching.",
) {
    @Suppress("DEPRECATION") // The newer clipboard API is suspend-only and platform-typed.
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // The page signs in to destiny.gg unless told the test site.
    val page = if (host == "destiny.gg") SIGN_IN_PAGE else "$SIGN_IN_PAGE?site=$host"
    Step("1", "On a computer signed in to $host, open:")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(page.removePrefix("https://"), color = Palette.Blue, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { clipboard.setText(AnnotatedString(page)); copied = true }) {
            Text(if (copied) "Copied" else "Copy", color = Palette.Blue)
        }
    }
    Step("2", "It shows how to copy two values from the browser, and turns them into a code.")
    Step("3", "Scan the code here.")
    val use: (String?, String) -> Unit = { text, how ->
        if (text == null) log("method computer code ($how): nothing read")
        else {
            val session = readCode(text)
            if (session == null) {
                log("method computer code ($how): not a code for $host")
                onProblem("That isn't a Glorp sign-in code for $host.")
            } else {
                log("method computer code ($how)")
                submit(session)
            }
        }
    }
    if (scanCode != null) {
        Button(
            onClick = { scanCode { use(it, "scanned") } },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
        ) { Text("Scan the code") }
    }
    var typed by remember { mutableStateOf("") }
    OutlinedTextField(
        typed, { typed = it.trim() },
        label = { Text(if (scanCode != null) "Or paste the code as text" else "Paste the code") },
        singleLine = true, colors = fieldColors(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (typed.isNotEmpty()) {
        OutlinedButton(onClick = { use(typed, "pasted") }, enabled = !busy) { Text("Sign in with the code", color = Palette.White) }
    }
}

@Composable
private fun LoginKeyForm(origin: String, host: String, busy: Boolean, submit: (Session) -> Unit) {
    val browser = LocalUriHandler.current
    @Suppress("DEPRECATION") // The newer clipboard API is suspend-only and platform-typed.
    val clipboard = LocalClipboardManager.current
    var key by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    Step("1", "Open the developer page in your browser and sign in there, with any of the site's logins.")
    OutlinedButton(onClick = { browser.openUri("$origin/profile/developer") }) {
        Text("Open $host/profile/developer", color = Palette.White)
    }
    Step("2", "Under Dgg App Connections, tap Create Login Key, then tap the key's button to copy it.")
    Step("3", "Come back here and paste it.")
    OutlinedButton(onClick = {
        val copied = clipboard.getText()?.text.orEmpty()
        val session = Session.loginKey(copied)
        if (session == null) {
            problem = "The clipboard doesn't hold a login key. Copy it from the developer page first."
        } else {
            key = session.key
            problem = null
            // Don't leave the key lying around for other apps' paste suggestions.
            clipboard.setText(AnnotatedString(""))
        }
    }) { Text("Paste key", color = Palette.White) }
    OutlinedTextField(
        key, { key = it.trim() }, label = { Text("Login key") }, singleLine = true, colors = fieldColors(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    problem?.let { Text(it, color = Palette.Orange, fontSize = 13.sp) }
    Button(
        onClick = {
            val session = Session.loginKey(key)
            if (session == null) problem = "That doesn't look like a login key." else { problem = null; submit(session) }
        },
        enabled = !busy && key.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
    ) { Text("Sign in") }
    Text(
        "The key stays on this phone, encrypted, and works for chat until you remove it on the developer page. " +
            "Settings that follow your account, whisper history and notifications need Sign in with $host instead.",
        color = Palette.TextDim,
        fontSize = 12.sp,
    )
}

@Composable
private fun Step(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, color = Palette.Glorp, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text, color = Palette.White, fontSize = 15.sp)
    }
}

@Composable
private fun CookieForm(host: String, busy: Boolean, submit: (Session) -> Unit) {
    var sid by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Text(
        "In a desktop browser signed in to $host, copy the values of its sid and rememberme cookies. " +
            "The developer tools list them under the site's cookies. They stay on this phone, encrypted.",
        color = Palette.TextDim,
        fontSize = 14.sp,
    )
    OutlinedTextField(
        sid, { sid = it }, label = { Text("sid") }, singleLine = true, colors = fieldColors(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        rememberMe, { rememberMe = it }, label = { Text("rememberme, if you have it") }, singleLine = true, colors = fieldColors(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Without rememberme you'll need to sign in again when the session ends.", color = Palette.TextDim, fontSize = 12.sp)
    problem?.let { Text(it, color = Palette.Orange, fontSize = 13.sp) }
    Button(
        onClick = {
            val session = Session.cookies(sid, rememberMe)
            if (session == null) problem = "That doesn't look like a cookie value." else { problem = null; submit(session) }
        },
        enabled = !busy && sid.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
    ) { Text("Sign in") }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.White,
    unfocusedTextColor = Palette.White,
    focusedBorderColor = Palette.Blue,
    unfocusedBorderColor = Palette.NavyLight,
    focusedLabelColor = Palette.Blue,
    unfocusedLabelColor = Palette.TextDim,
    cursorColor = Palette.Blue,
)
