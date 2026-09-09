package com.velastudio.teltv.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.velastudio.teltv.MainActivity
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory
import timber.log.Timber

/**
 * Owns the single ExoPlayer instance for the whole app, instead of `PlayerScreen` building and
 * releasing a throwaway one on every composition (the old approach, which had no lifecycle of
 * its own: backgrounding the app or getting a phone call left it playing, with no audio focus
 * handling and no way to show a system media notification or respond to the TV remote's
 * dedicated play/pause/ffwd hardware keys).
 *
 * `PlayerScreen` no longer touches ExoPlayer directly -- it binds a `MediaController` to this
 * service's session and drives playback through that instead. See [TdLibAwareDataSourceFactory]
 * for how Telegram (`tdlib://file/<id>`) and direct/NAS/WebDAV URIs both play through one player
 * without the UI needing to special-case either.
 *
 * This class was previously declared in AndroidManifest.xml with nothing behind it -- a dead
 * reference that would only have surfaced as a ClassNotFoundException if something had ever
 * actually tried to start it.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val app = application as TelTvApp
        val dataSourceFactory = TdLibAwareDataSourceFactory(app.telegramClient, this)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .build()
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e(error, "Player error (errorCode=%s)", error.errorCodeName)
            }
        })
        player = exoPlayer

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Media3's default behaviour keeps a MediaSessionService (and its foreground notification)
     * alive after the app is swiped away as long as the player is still playing, which is
     * correct for music but not really the intent here -- TelTV doesn't have an "audio-only
     * background playback" mode for video content. Stop the player (and let the service tear
     * down) once every client has disconnected and nothing is actively playing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = player
        if (currentPlayer == null || !currentPlayer.playWhenReady || currentPlayer.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
