package io.github.strawwaffle.glorp.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.strawwaffle.glorp.chat.ChatSettings
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.SharedChatPrefs
import io.github.strawwaffle.glorp.chat.retryDelay
import io.github.strawwaffle.glorp.community.DinkDonkClient
import io.github.strawwaffle.glorp.diag.AppLog
import io.github.strawwaffle.glorp.net.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Holds the quiet socket to Glorp's server for the length of a movie night
 * (server/votes.go): it carries nothing but votes opening, so it costs a
 * ping every half minute, not chat's traffic. A foreground service, so
 * Android shows that it's listening and doesn't stop it; it ends with the
 * night (three hours after the last vote) or when its Stop is tapped.
 */
class VoteListener : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        val notification = ongoing(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
        running = true
        // The offer to listen has been taken up.
        getSystemService(NotificationManager::class.java)?.cancel(movieNightOn().open.hashCode())
        if (job?.isActive != true) job = scope.launch { listen() }
        return START_NOT_STICKY
    }

    private suspend fun listen() {
        val prefs = SharedChatPrefs(applicationContext)
        val options = NotifyOptions(prefs, ChatSettings(prefs)).movieNight
        val server = prefs.get(MovieNightOptions.SERVER)
        if (server == null || !options.votes) return stopListening()
        // A night just found to be on may not have its end yet: give it the usual three hours.
        if (options.nightUntil < now()) options.nightUntil = now() + NIGHT_MS
        AppLog.note("movienight", "listening until ${options.nightUntil}")
        val client = createHttpClient()
        try {
            var attempts = 0
            while (scope.isActive && now() < options.nightUntil && options.votes) {
                attempts++
                var opened = 0L
                try {
                    client.webSocket(urlString = votesSocketUrl(server), request = { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }) {
                        opened = now()
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            when (val message = parseVoteMessage(frame.readText())) {
                                is VoteMessage.Opened -> heard(options, message.vote)
                                is VoteMessage.State -> {
                                    options.nightUntil = message.state.nightUntil.takeIf { it > 0 } ?: options.nightUntil
                                    message.state.open.forEach { heard(options, it) }
                                }
                                null -> Unit
                            }
                            if (now() >= options.nightUntil) break
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.note("movienight", "socket dropped", e)
                }
                if (opened > 0 && now() - opened > 5_000) attempts = 0
                delay(retryDelay(attempts, Random::nextDouble))
            }
        } finally {
            client.close()
        }
        AppLog.note("movienight", "the night is over")
        stopListening()
    }

    private fun heard(options: MovieNightOptions, vote: MovieVote) {
        if (!options.votes || !options.firstTime(vote)) return
        if (Notifier.permitted(applicationContext)) Notifier.post(applicationContext, voteAlert(vote))
    }

    private fun stopListening() {
        running = false
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION = 0x766f7465
        private const val ACTION_STOP = "io.github.strawwaffle.glorp.STOP_LISTENING"
        private const val NIGHT_MS = 3 * 60 * 60_000L

        @Volatile
        var running = false
            private set

        private fun now() = System.currentTimeMillis()

        /**
         * Starts listening if Android allows it here (the app on screen, or a
         * tapped notification); false where it doesn't, from the background.
         */
        fun start(context: Context): Boolean {
            if (running) return true
            return try {
                context.startForegroundService(Intent(context, VoteListener::class.java))
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: from the background, since Android 12.
                AppLog.note("movienight", "Android won't start the listener from here")
                false
            }
        }

        /** What a notification's "Listen for votes" does: a tap may start a foreground service. */
        fun startIntent(context: Context): PendingIntent {
            val intent = Intent(context, VoteListener::class.java)
            return PendingIntent.getForegroundService(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun ongoing(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(LISTENING_CHANNEL, "Movie night: listening", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(
                context, 2, Intent(context, VoteListener::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, LISTENING_CHANNEL)
                .setSmallIcon(Notifier.smallIcon(context))
                .setContentTitle("Listening for movie-night votes")
                .setContentText("Polls and ratings notify as they open. Stops when the night is over.")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
                .build()
        }

        private const val LISTENING_CHANNEL = "movienight.listening"
    }
}

/**
 * The movie-night part of the background check: asks the server whether a
 * night is on (telling any vote that's open right now), and starts the
 * listener, or offers it where Android won't let it start on its own.
 * Once or twice a day, it also reads dinkdonk.mov's schedule and books a
 * reminder before each session in the next day and a half.
 */
internal suspend fun movieNightCheck(context: Context, client: HttpClient) {
    val prefs = SharedChatPrefs(context)
    val options = NotifyOptions(prefs, ChatSettings(prefs)).movieNight
    val now = System.currentTimeMillis()
    val server = prefs.get(MovieNightOptions.SERVER)
    if (options.votes && server != null) {
        askMovieNight(client, server)?.let { state ->
            options.nightUntil = state.nightUntil
            state.open.filter { options.firstTime(it) }.forEach { Notifier.post(context, voteAlert(it)) }
            if (state.nightUntil > now && !VoteListener.running && !VoteListener.start(context)) {
                // Offered once per night.
                if (prefs.get(OFFERED) != state.nightUntil.toString()) {
                    prefs.put(OFFERED, state.nightUntil.toString())
                    Notifier.post(context, movieNightOn())
                }
            }
        }
    }
    if (options.reminders && now - (prefs.get(SCHEDULE_READ)?.toLongOrNull() ?: 0) > 12 * 60 * 60_000L) {
        val schedule = runCatching { DinkDonkClient(client).schedule() }.getOrNull() ?: return
        prefs.put(SCHEDULE_READ, now.toString())
        val starts = (listOfNotNull(schedule.banner) + schedule.sessions).map { it.start * 1000 }
            .filter { it > now + 60_000 && it < now + 36 * 60 * 60_000L }.distinct()
        starts.forEach { MovieNightReminder.book(context, it) }
        AppLog.note("movienight", "schedule read: ${starts.size} sessions coming")
    }
}

private const val OFFERED = "movienight.offered"
private const val SCHEDULE_READ = "movienight.scheduleRead"

/** A reminder a quarter of an hour before a booked session, by an inexact alarm (no special permission). */
class MovieNightReminder : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val start = intent.getLongExtra(EXTRA_START, 0)
        val prefs = SharedChatPrefs(context)
        val options = NotifyOptions(prefs, ChatSettings(prefs)).movieNight
        if (!options.reminders || start == 0L || !Notifier.permitted(context)) return
        val now = System.currentTimeMillis()
        if (now > start + 30 * 60_000L) return // too late to be useful
        Notifier.post(context, movieNightReminder(start, now))
    }

    companion object {
        private const val EXTRA_START = "io.github.strawwaffle.glorp.START"
        private const val BEFORE = 15 * 60_000L

        fun book(context: Context, start: Long) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, MovieNightReminder::class.java).putExtra(EXTRA_START, start)
            val pending = PendingIntent.getBroadcast(
                context, (start / 60_000).toInt(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Within ten minutes of a quarter of an hour before: Android batches it with other wake-ups.
            alarms.setWindow(AlarmManager.RTC_WAKEUP, start - BEFORE - 5 * 60_000L, 10 * 60_000L, pending)
        }
    }
}
