package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.auth.Session

/**
 * Signing in: the site's own login page where the platform has a web view,
 * and pasting the two cookies as the way round for logins that refuse to run
 * inside an app (Google's does).
 */
@Composable
fun SignInScreen(
    origin: String,
    host: String,
    busy: Boolean,
    onSession: (Session, (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var pasting by remember { mutableStateOf(!hasSiteLoginPage) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit: (Session) -> Unit = { session -> error = null; onSession(session) { error = it } }

    Column(Modifier.fillMaxSize().background(Palette.Black).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back", color = Palette.Blue) }
            Text("Sign in to $host", color = Palette.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (hasSiteLoginPage) {
                TextButton(onClick = { pasting = !pasting; error = null }) {
                    Text(if (pasting) "Login page" else "Paste cookies", color = Palette.Blue)
                }
            }
        }
        error?.let {
            Text(it, color = Palette.Orange, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (pasting) CookieForm(host, busy, submit)
            else SiteLoginPage(origin, onCookies = { header ->
                val session = Session.fromCookieHeader(header)
                if (session == null) error = "The login page didn't leave a session behind. Try again."
                else submit(session)
            }, modifier = Modifier.fillMaxSize())
            if (busy) {
                Box(Modifier.fillMaxSize().background(Palette.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Glorp)
                }
            }
        }
    }
}

@Composable
private fun CookieForm(host: String, busy: Boolean, submit: (Session) -> Unit) {
    var sid by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.White,
        unfocusedTextColor = Palette.White,
        focusedBorderColor = Palette.Blue,
        unfocusedBorderColor = Palette.NavyLight,
        focusedLabelColor = Palette.Blue,
        unfocusedLabelColor = Palette.TextDim,
        cursorColor = Palette.Blue,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Sign in to $host in a browser, then copy the values of its sid and rememberme cookies " +
                "(the browser's developer tools list them under the site's cookies). They stay on this phone, encrypted.",
            color = Palette.TextDim,
            fontSize = 14.sp,
        )
        OutlinedTextField(
            sid, { sid = it }, label = { Text("sid") }, singleLine = true, colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            rememberMe, { rememberMe = it }, label = { Text("rememberme (optional)") }, singleLine = true, colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Without rememberme you'll need to sign in again when the session ends.",
            color = Palette.TextDim,
            fontSize = 12.sp,
        )
        problem?.let { Text(it, color = Palette.Orange, fontSize = 13.sp) }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val session = Session.fromPasted(sid, rememberMe)
                if (session == null) problem = "That doesn't look like a cookie value." else { problem = null; submit(session) }
            },
            enabled = !busy && sid.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.White),
        ) { Text("Sign in") }
    }
}

/** Whether this platform can show the site's login page in the app. */
internal expect val hasSiteLoginPage: Boolean

/**
 * The site's login page. Calls [onCookies] with the site's cookies once the
 * login is done, then forgets them, so the app's encrypted store is the only
 * copy.
 */
@Composable
internal expect fun SiteLoginPage(origin: String, onCookies: (String) -> Unit, modifier: Modifier)
