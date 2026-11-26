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
        // Android repeats a job no more often than every 15 minutes. Shorter
        // than that, each check is a one-off that books the next when it's
        // done (see AlertJob); Android may still hold them back while the phone sleeps.
        val repeating = interval >= MIN_PERIODIC
        if (current != null && current.isRequireBatteryNotLow &&
            (if (repeating) current.isPeriodic && current.intervalMillis == interval else !current.isPeriodic && current.minLatencyMillis == interval)
        ) return
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, AlertJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
        jobs.schedule(if (repeating) builder.setPeriodic(interval).build() else builder.setMinimumLatency(interval).build())
    }

    /** After a one-off check: the next one (see [schedule]). */
    fun next(context: Context) {
        val prefs = SharedChatPrefs(context)
        val options = NotifyOptions(prefs, ChatSettings(prefs))
        if (options.any && options.intervalMinutes * 60_000L < MIN_PERIODIC) schedule(context)
    }

    private const val MIN_PERIODIC = 15 * 60_000L

    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    suspend fun check(context: Context) {
        val prefs = SharedChatPrefs(context)
        val options = NotifyOptions(prefs, ChatSettings(prefs))
        if (!options.any) return
        // Notifications turned off in Android: nothing could be shown, so
        // don't ask the site (and leave what's unseen for when they're back).
        if (!permitted(context) || context.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == false) return
        // The running app leaves the site and who is signed in (see ChatViewModel).
        val origin = prefs.get(NotifyOptions.ORIGIN) ?: return
        val cookies = KeystoreSessionStore(context).load() as? Session.Cookies
        val client = createHttpClient()
        try {
            val site = Site.guess(origin)
            val alerts = AlertCheck(
                client, site, prefs, options, cookies, prefs.get(NotifyOptions.NICK), prefs.get(NotifyOptions.SERVER),
                // Bigscreen: a few seconds on the site's live socket, signed out.
                liveSnapshot = { io.github.strawwaffle.glorp.site.LiveSocket(client, site).snapshot() },
            ).run { found ->
                // While chat is connected it has already told of whispers and mentions; going live
                // and bigscreen it never does (bigscreen's memory is shared, so nothing repeats).
                if (permitted(context)) found.filter { !liveChat || it.kind == Alert.Kind.Live || it.kind == Alert.Kind.Bigscreen }.forEach { post(context, it) }
            }
            AppLog.note("notify", "background check: ${alerts.size} new")
            runCatching { movieNightCheck(context, client) }.onFailure { AppLog.note("movienight", "check failed", it) }
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
        // A vote or a schedule opens on dinkdonk.mov itself, where the voting is.
        val tap = if (alert.kind == Alert.Kind.Vote || alert.kind == Alert.Kind.MovieNight) {
            PendingIntent.getActivity(
                context, alert.open.hashCode(),
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(alert.open)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getActivity(
                context, alert.open.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val icon = smallIcon(context)
        val id = alert.open.hashCode()
        val builder = Notification.Builder(context, channelOf(alert.kind))
            .setSmallIcon(icon)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(if (alert.kind in setOf(Alert.Kind.Live, Alert.Kind.Bigscreen, Alert.Kind.Vote, Alert.Kind.MovieNight)) Notification.CATEGORY_EVENT else Notification.CATEGORY_MESSAGE)
        if (alert.kind == Alert.Kind.Bigscreen) builder.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Watch", tap).build())
        if (alert.kind == Alert.Kind.Vote) {
            builder.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, if (alert.open.contains("/rate/")) "Rate" else "Vote", tap).build())
            // Gone once the vote has closed.
            builder.setTimeoutAfter(6 * 60_000L)
        }
        // The other buttons act without opening the app (AlertActionReceiver).
        alert.actions.forEachIndexed { index, action ->
            if (action.command == LISTEN_COMMAND) {
                builder.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, action.label, VoteListener.startIntent(context)).build())
                return@forEachIndexed
            }
            val intent = Intent(context, AlertActionReceiver::class.java)
                .putExtra(AlertActionReceiver.EXTRA_COMMAND, action.command)
                .putExtra(AlertActionReceiver.EXTRA_ID, id)
            val pending = PendingIntent.getBroadcast(
                context, id * 31 + index, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, action.label, pending).build())
        }
        manager.notify(id, builder.build())
    }

    private fun channelOf(kind: Alert.Kind) = when (kind) {
        Alert.Kind.Live -> "live"
        Alert.Kind.Mention -> "mentions"
        Alert.Kind.Whisper -> "whispers"
        Alert.Kind.Bigscreen -> "bigscreen"
        Alert.Kind.Vote -> "votes"
        Alert.Kind.MovieNight -> "movienight"
    }

    fun smallIcon(context: Context): Int =
        context.resources.getIdentifier("ic_notification", "drawable", context.packageName).takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat

    private fun channels(context: Context, manager: NotificationManager) {
        listOf(
            NotificationChannel("live", "Destiny goes live", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("mentions", "Mentions", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("whispers", "Whispers", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("bigscreen", "Bigscreen: hosts and embeds", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("votes", "Movie night: votes open", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("movienight", "Movie night: starting", NotificationManager.IMPORTANCE_DEFAULT),
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
            try {
                Notifier.check(applicationContext)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.note("notify", "background check failed", e)
            }
            jobFinished(params, false)
            // A one-off check (under 15 minutes apart) books the next itself.
            Notifier.next(applicationContext)
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

/**
 * A bigscreen notification's button (Mute, Stop following, Follow): changes
 * the lists without opening the app, then clears the notification.
 */
class AlertActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val prefs = SharedChatPrefs(context)
        NotifyOptions(prefs, ChatSettings(prefs)).bigscreen.apply(command)
        context.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra(EXTRA_ID, 0))
        // Following or unfollowing can turn the background check on or off.
        Notifier.schedule(context)
        AppLog.note("notify", "notification button: ${command.substringBefore('	')}")
    }

    companion object {
        const val EXTRA_COMMAND = "io.github.strawwaffle.glorp.COMMAND"
        const val EXTRA_ID = "io.github.strawwaffle.glorp.NOTIFICATION"
    }
}
