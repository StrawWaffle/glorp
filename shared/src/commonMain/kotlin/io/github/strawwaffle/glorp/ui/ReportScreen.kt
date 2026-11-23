package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.diag.AppLog
import io.github.strawwaffle.glorp.diag.Diagnostics
import io.github.strawwaffle.glorp.diag.ProblemReport

/**
 * Reporting a problem: the user says what happened, sees everything that
 * would be sent, and sends it as a GitHub issue they submit themselves (or
 * copies it to send another way). Nothing leaves the phone from here.
 */
@Composable
fun ReportScreen(diagnostics: Diagnostics) {
    var what by rememberSaveable { mutableStateOf("") }
    var withLog by rememberSaveable { mutableStateOf(true) }
    val crash = remember { diagnostics.lastCrash() }
    var withCrash by rememberSaveable { mutableStateOf(crash != null) }
    val log = remember { AppLog.lines() }
    val report = ProblemReport(
        what = what.trim(),
        version = diagnostics.version,
        device = diagnostics.device,
        crash = crash?.takeIf { withCrash },
        log = if (withLog) log else emptyList(),
    )
    val links = LocalUriHandler.current
    @Suppress("DEPRECATION") // The newer clipboard API is suspend-only and platform-typed.
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    fun sent() {
        if (withCrash) diagnostics.clearCrash()
    }

    LazyColumn(Modifier.fillMaxSize().background(Palette.Black)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Report a problem", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "This opens a new issue on GitHub with what's below filled in. You can read and change all of it there, " +
                        "and nothing is sent until you submit it. No GitHub account? Copy it and whisper it to StrawWaffle in destiny.gg chat.",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("What happened?", color = Palette.White, fontSize = 15.sp)
                BasicTextField(
                    what,
                    onValueChange = { what = it },
                    textStyle = TextStyle(color = Palette.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.White),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 96.dp)
                        .background(Palette.Navy, RoundedCornerShape(6.dp)).padding(10.dp),
                    decorationBox = { field ->
                        if (what.isEmpty()) Text("What you did, what you expected, what you saw", color = Palette.TextDim, fontSize = 15.sp)
                        field()
                    },
                )
            }
        }
        if (crash != null) item {
            SwitchRow("Include the crash", "Glorp closed because of an error last time; this is where in the code it happened", withCrash) { withCrash = it }
        }
        item {
            SwitchRow(
                "Include the app's recent log",
                "${log.size} lines about connections, failures and the player, with times. No chat messages, names or logins",
                withLog,
            ) { withLog = it }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { links.openUri(report.issueLink()); sent() },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
                ) { Text("Open on GitHub") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report.text())); copied = true; sent() }) {
                    Text(if (copied) "Copied" else "Copy", color = Palette.White)
                }
            }
        }
        item {
            Text("What would be sent", color = Palette.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        }
        item {
            Text(
                report.text(),
                color = Palette.TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Asked once, on the start after a crash. */
@Composable
fun CrashPrompt(onReport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Navy,
        title = { Text("Glorp closed because of an error", color = Palette.White) },
        text = {
            Text(
                "Reporting it helps get it fixed. You'll see everything the report holds before anything is sent.",
                color = Palette.TextDim,
            )
        },
        confirmButton = { TextButton(onClick = onReport) { Text("Report it", color = Palette.Glorp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = Palette.TextDim) } },
    )
}
