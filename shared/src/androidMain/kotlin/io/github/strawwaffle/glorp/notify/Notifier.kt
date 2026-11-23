package io.github.strawwaffle.glorp.notify

import io.github.strawwaffle.glorp.diag.AppLog
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.Notification
import io.github.strawwaffle.glorp.auth.KeystoreSessionStore
import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatSettings
import io.github.strawwaffle.glorp.chat.SharedChatPrefs
import io.github.strawwaffle.glorp.net.createHttpClient
import io.github.strawwaffle.glorp.site.Site
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notifications without a chat connection: Android's job scheduler runs
 * [AlertJob] every 15, 30 or 60 minutes (when there's a network, batched
 * with other apps' work), which asks the site once per kind wanted. Each
 * kind has its own channel, so Android's settings can set its sound,
 * vibration or silence.
 */
object Notifier {
    /** The extra on the activity's intent saying what a tapped notification opens. */
    const val EXTRA_OPEN = "io.github.strawwaffle.glorp.OPEN"

    /** True while chat is connected: it notifies as things arrive, so the background check stays quiet. */
    @Volatile
    var liveChat = false

    private const val JOB_ID = 0x6c6f72

    /** Schedules the check as the options say, or cancels it when nothing is wanted. */
    fun schedule(context: Context) {
        val prefs = SharedChatPrefs(context)
        val options = NotifyOptions(prefs, ChatSettings(prefs))
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!options.any) {
            jobs.cancel(JOB_ID)
            return
        }
        val interval = options.intervalMinutes * 60_000L
        val current = jobs.getPendingJob(JOB_ID)
        if (current != null && current.intervalMillis == interval && current.isRequireBatteryNotLow) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AlertJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(interval)
                .setPersisted(true)
                .build(),
        )
    }

    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal suspend fun check(context: Context) {
        val prefs = SharedChatPrefs(context)
        val options = NotifyOptions(prefs, ChatSettings(prefs))
        if (!options.any) return
        // The running app leaves the site and who is signed in (see ChatViewModel).
        val origin = prefs.get(NotifyOptions.ORIGIN) ?: return
        val cookies = KeystoreSessionStore(context).load() as? Session.Cookies
        val client = createHttpClient()
        try {
            val alerts = AlertCheck(client, Site.guess(origin), prefs, options, cookies, prefs.get(NotifyOptions.NICK), prefs.get(NotifyOptions.SERVER)).run()
            AppLog.note("notify", "background check: ${alerts.size} new")
            // While chat is connected it has already told of whispers and mentions; going live it never does.
            if (permitted(context)) alerts.filter { !liveChat || it.kind == Alert.Kind.Live }.forEach { post(context, it) }
        } finally {
            client.close()
        }
    }

    fun post(context: Context, alert: Alert) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        channels(context, manager)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_OPEN, alert.open)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return
        val tap = PendingIntent.getActivity(
            context, alert.open.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat
        val notification = Notification.Builder(context, channelOf(alert.kind))
            .setSmallIcon(icon)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (alert.kind == Alert.Kind.Live) Notification.CATEGORY_EVENT else Notification.CATEGORY_MESSAGE)
            .build()
        manager.notify(alert.open.hashCode(), notification)
    }

    private fun channelOf(kind: Alert.Kind) = when (kind) {
        Alert.Kind.Live -> "live"
        Alert.Kind.Mention -> "mentions"
        Alert.Kind.Whisper -> "whispers"
    }

    private fun channels(context: Context, manager: NotificationManager) {
        listOf(
            NotificationChannel("live", "Destiny goes live", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("mentions", "Mentions", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("whispers", "Whispers", NotificationManager.IMPORTANCE_HIGH),
        ).forEach(manager::createNotificationChannel)
    }

    /** Android's notification settings for Glorp, where each kind's sound and vibration are set. */
    fun openSystemSettings(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.let { channels(context, it) }
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** One background look at the site (see [Notifier]). */
class AlertJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: kotlinx.coroutines.Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { Notifier.check(applicationContext) }.onFailure { AppLog.note("notify", "background check failed", it) }
            jobFinished(params, false)
        }
        return true
    }

    // Android took the job back (no network, Doze): stop at once. The next
    // periodic run comes as usual, so no retry is asked for.
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
