package io.github.strawwaffle.glorp.video

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LivePlaybackSpeedControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * The one native player, owned here rather than by a screen, so it can keep
 * playing sound when the screen goes off and be picked up again, without
 * buffering, when the app comes back.
 *
 * A screen [attach]es to play a stream and [detach]es when it leaves. Kept
 * playing in the background, the player lives in [PlaybackService], which
 * shows the media notification; otherwise it is released at once, so no
 * decoder or network is held for nothing.
 */
@OptIn(UnstableApi::class)
object Playback {
    private class Remembered(val key: String, val positionMs: Long, val at: Long)

    private var lastRecording: Remembered? = null

    class Active(
        val url: String,
        val key: String?,
        val player: ExoPlayer,
        val controller: ExoController,
        val session: MediaSession,
        val edges: EdgeChoice,
    )

    var active: Active? = null
        private set

    /**
     * Dev builds: once a second, how far behind live the player is, how much
     * is buffered and how fast it plays, to logcat under `GlorpLive`, for
     * watching catch-up on real streams.
     */
    var logLive = false

    /**
     * Dev builds: the next player's downloads fail with HTTP 403 after this
     * long, as an expired address does, to try looking a recording up again
     * (`--es expire-test <seconds>`). Used once.
     */
    @Volatile
    var expireTestMs: Long? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val logTick = object : Runnable {
        override fun run() {
            val current = active ?: return
            val player = current.player
            if (player.isCurrentMediaItemLive) {
                android.util.Log.d(
                    "GlorpLive",
                    "offset=${player.currentLiveOffset}ms buffered=${player.totalBufferedDuration}ms " +
                        "state=${player.playbackState} playing=${player.isPlaying}" +
                        (current.edges.current?.let { " edge=$it" } ?: ""),
                )
            }
            ticker.postDelayed(this, 1_000)
        }
    }

    /** The player for [playable]: the one already playing it, or a new one. */
    /**
     * The fastest a live stream may play to catch up, from the menu's
     * setting; 1 turns catching up off. Read by the player as it plays, so a
     * change applies at once.
     */
    @Volatile
    var catchUpMax = 1.5f

    fun attach(context: Context, playable: Playable, background: Boolean): Active {
        active?.let {
            if (it.url == playable.hlsUrl) {
                // Back on screen: pictures again, and the player stays.
                soundOnly(it.player, false)
                watchNetwork(context, false)
                handler.removeCallbacks(idleRelease)
                stoppedAt = null
                it.player.removeListener(pausedOffScreen)
                // Back on screen, or looked up again, after it failed: start again.
                if (it.controller.state.value.failed) it.controller.restart()
                return it.also { start(context, background) }
            }
        }
        // The same recording at a new address (the old one expired): carry on from where it was.
        val resumeAt = if (playable.live || playable.key == null) null
        else active?.takeIf { it.key == playable.key }?.player?.currentPosition?.takeIf { it > 0 }
            ?: lastRecording?.takeIf { it.key == playable.key && android.os.SystemClock.elapsedRealtime() - it.at < 30 * 60_000 }?.positionMs
        resumeAt?.let { io.github.strawwaffle.glorp.diag.AppLog.note("player", "looked up again; carrying on at ${it / 1000} s") }
        release(context)
        val app = context.applicationContext
        // AngelThump streams come from one of several edges; requests go to
        // the chosen one and fall over to its neighbours (see EdgeFailover).
        val edges = EdgeChoice(AngelThumpEdges.hostOf(playable.hlsUrl)?.takeIf(AngelThumpEdges::isEdge))
        val expiresAt = expireTestMs?.let { android.os.SystemClock.elapsedRealtime() + it }
        expireTestMs = null
        val dataSources = EdgeFailoverDataSource.Factory(DefaultDataSource.Factory(app), edges).let {
            // YouTube's recordings (a manifest of separate files) are read by byte range.
            if (playable.format == Playable.Format.Dash) YouTubeRangesDataSource.Factory(it, playable.headers) else it
        }.let { if (expiresAt != null) ExpiringDataSource.Factory(it, expiresAt) else it }
        // Live HLS is read at the edge, where downloads wait on the stream (see LiveBandwidthMeter).
        val meter = if (playable.live && playable.format == Playable.Format.Hls) LiveBandwidthMeter(app) else null
        val player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .apply { meter?.let(::setBandwidthMeter) }
            // A few seconds from live there's never the 10 s of buffer Auto
            // wants before stepping up, and Media3 counts one segment less than
            // there is: 1 s is enough to try a better quality.
            .setTrackSelector(
                androidx.media3.exoplayer.trackselection.DefaultTrackSelector(
                    app,
                    QualitySelection(if (meter != null) 1_000 else androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS),
                ),
            )
            // Pauses for calls and other apps' sound, as a video app should.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setLivePlaybackSpeedControl(catchUpControl())
            // Keeps the network up while the screen is off, but only while playing.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Auto picks nothing above what the screen can show: a 1080p60 stream
        // on a small view costs decode work and data for nothing. A quality
        // picked by hand overrides this.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setViewportSizeToPhysicalDisplaySize(app, true)
            .build()
        meter?.let(player::addAnalyticsListener)
        if (logLive) player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                val f = mediaLoadData.trackFormat
                android.util.Log.d("GlorpLoad", "type=${mediaLoadData.trackType} ${f?.bitrate} ${f?.height}p ${loadEventInfo.bytesLoaded} bytes in ${loadEventInfo.loadDurationMs} ms")
            }
            override fun onTracksChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
                tracks.groups.forEach { g ->
                    android.util.Log.d("GlorpLoad", "group type=${g.type} " + (0 until g.length).joinToString { "${g.getTrackFormat(it).height}p/${g.getTrackFormat(it).bitrate}/${g.isTrackSupported(it)}" })
                }
            }
            override fun onBandwidthEstimate(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                android.util.Log.d("GlorpLoad", "estimate ${bitrateEstimate / 1000} kbit/s")
            }
        })
        player.addListener(object : Player.Listener {
            private val stalls = ArrayDeque<Long>()
            private var wasReady = false

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) wasReady = true
                val edge = edges.current ?: return
                if (state != Player.STATE_BUFFERING || !wasReady || !player.playWhenReady) return
                // Three stalls within a minute on one edge: try the next.
                val now = android.os.SystemClock.elapsedRealtime()
                stalls.addLast(now)
                while (stalls.isNotEmpty() && now - stalls.first() > 60_000) stalls.removeFirst()
                if (stalls.size >= 3) {
                    stalls.clear()
                    edges.leave(edge)
                }
            }
        })
        // What the media notification and the lock screen show.
        val title = playable.key?.split('/')?.takeIf { it.size == 2 }?.let { (platform, id) ->
            "$id on ${io.github.strawwaffle.glorp.ui.PlatformIcons.name(platform)}"
        } ?: "Glorp"
        val item = MediaItem.Builder().setUri(playable.hlsUrl)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist("Glorp").build())
            .setMimeType(
                when (playable.format) {
                    Playable.Format.Hls -> MimeTypes.APPLICATION_M3U8
                    Playable.Format.Mp4 -> MimeTypes.VIDEO_MP4
                    Playable.Format.Dash -> MimeTypes.APPLICATION_MPD
                },
            )
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .apply { if (playable.lowLatency) setTargetOffsetMs(LOW_LATENCY_TARGET_MS) }
                    .setMaxPlaybackSpeed(FASTEST_CATCH_UP)
                    .setMinPlaybackSpeed(0.97f)
                    .build(),
            )
            .build()
        val start = playable.startSeconds
        when {
            // Live HLS reads its playlists rewritten to stay closer to live (see lowLatencyPlaylist).
            playable.live && playable.format == Playable.Format.Hls -> player.setMediaSource(
                HlsMediaSource.Factory(dataSources).setPlaylistParserFactory(LowLatencyPlaylists()).createMediaSource(item),
            )
            playable.live -> player.setMediaItem(item)
            resumeAt != null -> player.setMediaItem(item, resumeAt)
            start != null -> player.setMediaItem(item, start * 1000L)
            else -> player.setMediaItem(item)
        }
        player.playWhenReady = true
        player.prepare()
        // The session gives the picture-in-picture window, headset buttons and
        // the notification play and pause.
        val session = MediaSession.Builder(app, player).setId("glorp").build()
        if (logLive) { ticker.removeCallbacks(logTick); ticker.postDelayed(logTick, 1_000) }
        return Active(playable.hlsUrl, playable.key, player, ExoController(player), session, edges).also {
            active = it
            start(context, background)
        }
    }

    /**
     * The screen showing the player has gone. With [keepPlaying] (the app was
     * left while playing and background sound is on) the service carries on;
     * otherwise everything is released.
     */
    fun detach(context: Context, keepPlaying: Boolean) {
        val current = active ?: return
        // Between a dropped connection and its retry it isn't playing, but it means to.
        if (keepPlaying && (meantToPlay(current.player) || current.controller.retrying)) {
            // Only the sound goes on: no video to decode with nothing to show it.
            soundOnly(current.player, true)
            watchNetwork(context, true)
            appContext = context.applicationContext
            current.player.addListener(pausedOffScreen)
            // Already stalled as the app was left: no change will come to arm the timer.
            if (!current.player.isPlaying) stoppedOffScreen()
            return
        }
        release(context)
    }

    // The track settings from before sound only, put back on return (a quality picked by hand included).
    private var beforeSoundOnly: androidx.media3.common.TrackSelectionParameters? = null
    private var pictureOff: Pair<Player, Player.Listener>? = null
    private val pictureOffLater = Runnable { turnPictureOff() }

    /**
     * No picture, and the smallest stream (see QualitySelection.lowestOnly).
     *
     * Media3 only changes which HLS stream it reads while video is selected.
     * Auto quality carries on choosing by itself with the picture off, so
     * usually the picture just goes. A quality picked by hand doesn't: Auto
     * has to take over first, with the picture still on, and the picture goes
     * once it has. (That swap drops what was buffered: a second of silence.)
     */
    private fun soundOnly(player: ExoPlayer, on: Boolean) {
        if (!on) {
            val before = beforeSoundOnly ?: return
            cancelPictureOff()
            player.removeListener(reprepared)
            QualitySelection.lowestOnly = false
            player.trackSelectionParameters = before
            beforeSoundOnly = null
            return
        }
        if (beforeSoundOnly != null) return
        val before = player.trackSelectionParameters
        beforeSoundOnly = before
        QualitySelection.lowestOnly = true
        reprepared.player = player
        reprepared.lastState = player.playbackState
        player.addListener(reprepared)
        if (before.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }) {
            player.trackSelectionParameters = before.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        } else {
            pictureOnThenOff(player)
        }
    }

    /**
     * Auto on the smallest stream with the picture on, then the picture off:
     * for a quality picked by hand, and for a stream prepared again (after an
     * error) while sound only, which would otherwise read the first stream
     * its playlist lists, usually the largest.
     */
    private fun pictureOnThenOff(player: ExoPlayer) {
        cancelPictureOff()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = turnPictureOff()
        }
        pictureOff = player to listener
        player.addListener(listener)
        // In case nothing changes to say so.
        handler.postDelayed(pictureOffLater, 2_000)
    }

    private fun turnPictureOff() {
        val (player, _) = pictureOff ?: return
        cancelPictureOff()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
    }

    private fun cancelPictureOff() {
        handler.removeCallbacks(pictureOffLater)
        pictureOff?.let { (player, listener) -> player.removeListener(listener) }
        pictureOff = null
    }

    /**
     * Off screen there's no screen to look a failed stream up again, so after
     * a longer drop (a lift, a train, Wi-Fi to mobile data) the sound would
     * stay stopped. Android says when a network is there; then the stream
     * carries on. Listened for only while the sound is meant to play off screen.
     */
    private var networkWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private var connectivity: android.net.ConnectivityManager? = null

    // Tries without a network change in between, so a stream that ended
    // isn't tried forever; a network change allows more.
    private var triesLeft = 0

    // Wi-Fi and mobile networks known to reach the internet. A VPN isn't
    // counted: it says it works while the network under it is gone.
    private val usableNetworks = HashSet<android.net.Network>()

    private val networkCheck = Runnable {
        val current = active ?: return@Runnable
        if (!current.player.playWhenReady) return@Runnable
        // Only once it has given up: until then its own retries are at work.
        if (!current.controller.state.value.failed) return@Runnable
        if (usableNetworks.isEmpty() || triesLeft <= 0) return@Runnable
        // Stopped too long ago (timers stop counting while the phone sleeps,
        // so this asks the clock): don't start playing again, let it go.
        if (stoppedFor() >= IDLE_LIMIT_MS) {
            appContext?.let(::release)
            return@Runnable
        }
        triesLeft--
        io.github.strawwaffle.glorp.diag.AppLog.note("player", "network there: starting again")
        // As the Retry button does: back a few seconds from live, where
        // catching up takes over; what was missed in a long drop is gone.
        current.controller.restart()
    }

    private fun checkNetworkSoon(networkChanged: Boolean) {
        if (networkChanged) triesLeft = 3
        handler.removeCallbacks(networkCheck)
        handler.postDelayed(networkCheck, 1_000)
    }

    // Stopped (given up after its retries) while off screen: see if a network is there.
    private val stoppedOffScreen = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE) checkNetworkSoon(networkChanged = false)
        }
    }

    private fun watchNetwork(context: Context, on: Boolean) {
        val connectivity = connectivity
            ?: context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)?.also { connectivity = it }
            ?: return
        networkWatch?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkWatch = null
        handler.removeCallbacks(networkCheck)
        active?.player?.removeListener(stoppedOffScreen)
        usableNetworks.clear()
        if (!on) return
        triesLeft = 3
        active?.player?.addListener(stoppedOffScreen)
        // Wi-Fi and mobile data themselves: behind a VPN the default network
        // stays the VPN, which says nothing when Wi-Fi comes and goes.
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            // Wi-Fi reports changes every few seconds (signal strength); only
            // becoming usable counts as a change.
            override fun onLost(network: android.net.Network) {
                usableNetworks.remove(network)
                checkNetworkSoon(networkChanged = true)
            }

            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                if (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    usableNetworks.remove(network)
                } else if (usableNetworks.add(network)) {
                    checkNetworkSoon(networkChanged = true)
                }
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        // Called on the main thread, where the player lives.
        runCatching { connectivity.registerNetworkCallback(request, callback, handler) }
            .onSuccess { networkWatch = callback }
    }

    // Sound only, and the stream prepared again (a retry after an error).
    private val reprepared = object : Player.Listener {
        var player: ExoPlayer? = null
        var lastState = Player.STATE_IDLE

        override fun onPlaybackStateChanged(playbackState: Int) {
            val was = lastState
            lastState = playbackState
            if (was == Player.STATE_IDLE && playbackState != Player.STATE_IDLE && beforeSoundOnly != null) {
                player?.let(::pictureOnThenOff)
            }
        }
    }

    /** Playing, or about to: a moment of buffering or a passing interruption doesn't count as stopped. */
    fun meantToPlay(player: Player): Boolean =
        player.playWhenReady && (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var appContext: Context? = null

    // Paused or stopped while off screen (from the notification, a call, a
    // stall, a dropped network) for five minutes: let the player, its decoder
    // and the service go. Still trying to load by then gets five more.
    private var stoppedAt: Long? = null

    private fun stoppedFor(): Long = stoppedAt?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0

    private fun stoppedOffScreen() {
        if (stoppedAt == null) stoppedAt = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, (IDLE_LIMIT_MS - stoppedFor()).coerceAtLeast(1_000))
    }

    private val idleRelease: Runnable = Runnable {
        val current = active ?: return@Runnable
        if (current.player.isPlaying) return@Runnable
        val trying = meantToPlay(current.player) || current.controller.retrying
        val limit = if (trying) 2 * IDLE_LIMIT_MS else IDLE_LIMIT_MS
        if (stoppedFor() >= limit) appContext?.let(::release)
        else handler.postDelayed(idleRelease, 60_000)
    }
    private val pausedOffScreen = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                stoppedAt = null
                handler.removeCallbacks(idleRelease)
            } else {
                stoppedOffScreen()
            }
        }
    }

    private const val IDLE_LIMIT_MS = 5 * 60_000L

    /** Background sound turned on while a stream plays on screen: start the service that will carry it. */
    fun backgroundTurnedOn(context: Context) {
        if (active != null) start(context, true)
    }

    /**
     * Catching up with a live stream that's fallen behind (after a pause or a
     * stall): Media3's own control, tuned. Speed follows how far behind the
     * target it is (the setting's speed from 5 s, less as it closes; gentler
     * than that went unnoticed on a phone: 7 s took over half a minute), so it eases off as the gap
     * closes instead of hitting the edge at full speed. Within 0.75 s it plays
     * at normal speed, so small wobbles don't switch it on and off. The
     * target never comes closer to live than what's buffered safely allows,
     * so it doesn't run the buffer dry chasing the newest segment, and each
     * stall moves the target 2 s further back rather than racing to the
     * same spot to stall again. A seek back on purpose sets a new target,
     * so a rewind isn't undone.
     */
    private fun catchUpControl(): LivePlaybackSpeedControl = CappedSpeedControl(
        DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMaxPlaybackSpeed(FASTEST_CATCH_UP)
        .setProportionalControlFactor(0.1f)
        .setMaxLiveOffsetErrorMsForUnitSpeed(750)
            .setTargetLiveOffsetIncrementOnRebufferMs(2_000)
            .build(),
    )

    /**
     * Media3's control picks the target (how close to live is safe, given
     * the buffer and past stalls); the speed towards it follows the setting:
     * its full speed from [FULL_SPEED_BEHIND_US] behind the target, easing
     * down in proportion as the gap closes, normal speed within 0.75 s. Off
     * ([catchUpMax] 1), it plays at normal speed.
     */
    private class CappedSpeedControl(private val inner: DefaultLivePlaybackSpeedControl) : LivePlaybackSpeedControl by inner {
        override fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long): Float {
            val suggested = inner.getAdjustedPlaybackSpeed(liveOffsetUs, bufferedDurationUs)
            val cap = catchUpMax
            if (cap <= 1f) return 1f
            val target = inner.targetLiveOffsetUs
            if (target == C.TIME_UNSET) return minOf(suggested, cap)
            val behindUs = liveOffsetUs - target
            if (kotlin.math.abs(behindUs) < UNIT_SPEED_WITHIN_US) return 1f
            val speed = 1f + (cap - 1f) * behindUs / FULL_SPEED_BEHIND_US
            return speed.coerceIn(0.97f, cap)
        }
    }

    private const val FULL_SPEED_BEHIND_US = 5_000_000f
    private const val UNIT_SPEED_WITHIN_US = 750_000L

    /** The highest the setting offers; the media item allows up to this, the setting picks below it. */
    private const val FASTEST_CATCH_UP = 2f

    /** Media3's own playlist parsing, given [lowLatencyPlaylist]'s rewrite of media playlists first. */
    private class LowLatencyPlaylists : HlsPlaylistParserFactory {
        private val inner = DefaultHlsPlaylistParserFactory()

        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = rewriting(inner.createPlaylistParser())

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> = rewriting(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

        private fun rewriting(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            val text = input.readBytes().decodeToString()
            if (logLive) {
                val tags = text.lineSequence().filter { it.startsWith("#EXT") && !it.startsWith("#EXT-X-PROGRAM-DATE-TIME") }
                    .map { it.take(120) }.distinct().take(14).joinToString(" | ")
                android.util.Log.d("GlorpPlaylist", "${uri.host}: $tags")
            }
            parser.parse(uri, lowLatencyPlaylist(text).encodeToByteArray().inputStream())
        }
    }

    fun release(context: Context) {
        val current = active ?: return
        active = null
        handler.removeCallbacks(idleRelease)
        stoppedAt = null
        cancelPictureOff()
        current.player.removeListener(reprepared)
        reprepared.player = null
        watchNetwork(context, false)
        beforeSoundOnly = null
        QualitySelection.lowestOnly = false
        current.player.removeListener(pausedOffScreen)
        // Where a recording was, for the same one played again soon: looked up
        // again at a new address (the old one expired), or picked again.
        if (current.key != null && !current.player.isCurrentMediaItemLive && current.player.currentPosition > 0) {
            lastRecording = Remembered(current.key, current.player.currentPosition, android.os.SystemClock.elapsedRealtime())
        }
        context.applicationContext.stopService(Intent(context.applicationContext, PlaybackService::class.java))
        current.session.release()
        current.controller.release()
    }

    // Started while the app is on screen: Android doesn't let an app start a
    // service once it's in the background. Media3 then keeps it in the
    // foreground, with the media notification, only while it plays.
    private fun start(context: Context, background: Boolean) {
        if (!background) return
        val app = context.applicationContext
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }
}

/** Holds the player's session while sound plays with the app off screen. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        // Added straight away so the media notification can show it.
        Playback.active?.session?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = Playback.active?.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away: stop rather than play on with no app.
        Playback.release(this)
        stopSelf()
    }

    override fun onDestroy() {
        // The sessions belong to Playback, which releases them; only let go
        // of the ones held here. (Playback may already have moved on to a new
        // stream, whose session this service never held.)
        sessions.toList().forEach(::removeSession)
        super.onDestroy()
    }
}

/** Dev builds: refuses every request after [expiresAt] with HTTP 403, as an expired address is refused (see [Playback.expireTestMs]). */
@OptIn(UnstableApi::class)
private class ExpiringDataSource(private val upstream: androidx.media3.datasource.DataSource, private val expiresAt: Long) :
    androidx.media3.datasource.DataSource by upstream {
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        if (android.os.SystemClock.elapsedRealtime() > expiresAt) {
            throw androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
                403, "Forbidden (expire test)", null, emptyMap(), dataSpec, ByteArray(0),
            )
        }
        return upstream.open(dataSpec)
    }

    class Factory(private val upstream: androidx.media3.datasource.DataSource.Factory, private val expiresAt: Long) :
        androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource = ExpiringDataSource(upstream.createDataSource(), expiresAt)
    }
}
