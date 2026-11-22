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
 * Signing in with a login key made on the site's developer page, in the
 * phone's own browser.
 *
 * The site's login page can't run inside the app: Twitch's and Kick's bot
 * protection blank it in a web view, and Google refuses in-app sign-in. The
 * browser has none of those problems, and a login key lasts until it's
 * removed on that page. Pasting the browser's cookies stays as the way to
 * sign in for the parts of the site a key doesn't open.
 */
@Composable
fun SignInScreen(
    origin: String,
    host: String,
    busy: Boolean,
    onSession: (Session, (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
    /** Opens on the cookie form, for what a login key can't open (whisper history). */
    startWithCookies: Boolean = false,
) {
    var cookies by remember { mutableStateOf(startWithCookies) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit: (Session) -> Unit = { session -> error = null; onSession(session) { error = it } }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back", color = Palette.Blue) }
            Text("Sign in to $host", color = Palette.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { Text(it, color = Palette.Orange, fontSize = 14.sp) }
                if (cookies) CookieForm(host, busy, submit) else LoginKeyForm(origin, host, busy, submit)
                TextButton(onClick = { cookies = !cookies; error = null }) {
                    Text(if (cookies) "Use a login key instead" else "Paste browser cookies instead", color = Palette.Blue)
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
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.White),
    ) { Text("Sign in") }
    Text(
        "The key stays on this phone, encrypted, and works for chat until you remove it on the developer page. " +
            "Pages of the site that need your account (profile, subscriptions) will ask for cookies later.",
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
        "Signed in to $host in a desktop browser, copy the values of its sid and rememberme cookies " +
            "(the developer tools list them under the site's cookies). They stay on this phone, encrypted.",
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
        rememberMe, { rememberMe = it }, label = { Text("rememberme (optional)") }, singleLine = true, colors = fieldColors(),
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
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.White),
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
