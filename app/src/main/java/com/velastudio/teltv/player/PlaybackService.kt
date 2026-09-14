package com.velastudio.teltv.player
import androidx.media3.common.MimeTypes
import android.os.Build
import android.media.MediaCodecList
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.velastudio.teltv.MainActivity
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory
import timber.log.Timber

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val app = application as TelTvApp
        val dataSourceFactory = TdLibAwareDataSourceFactory(app.telegramClient, this)
        val constrained = app.deviceProfile.isConstrained

        // Keep the decoder buffer small on low-memory TVs while retaining a larger cushion on
        // devices that can afford it.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (constrained) 10_000 else 15_000,
                /* maxBufferMs = */ if (constrained) 25_000 else 35_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ if (constrained) 2_000 else 2_500
            )
            .setTargetBufferBytes(if (constrained) 24 * 1024 * 1024 else 35 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Audio Passthrough (Bitstream) for Soundbars & AV Receivers
        val audioCapabilities = AudioCapabilities.getCapabilities(this)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(audioCapabilities)
                    .setEnableFloatOutput(false) // Safe PCM output for legacy audio streams
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
            forceDisableMediaCodecAsynchronousQueueing()
                        setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val defaultDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType.equals(MimeTypes.AUDIO_MPEG, ignoreCase = true)) {
                    val custom = mutableListOf<MediaCodecInfo>()
                    try {
                        val mcl = MediaCodecList(MediaCodecList.ALL_CODECS)
                        for (info in mcl.codecInfos) {
                            if (info.isEncoder) continue
                            for (t in info.supportedTypes) {
                                if (t.equals(MimeTypes.AUDIO_MPEG, ignoreCase = true) && info.name.equals("c2.android.mp3.decoder", ignoreCase = true) == false) {
                                    val caps = try { info.getCapabilitiesForType(mimeType) } catch (e: Exception) { null }
                                    val isHw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else false
                                    val isSw = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else true
                                    val isVendor = if (Build.VERSION.SDK_INT >= 29) info.isVendor else false
                                    custom.add(MediaCodecInfo.newInstance(info.name, mimeType, mimeType, caps, isHw, isSw, isVendor, false, false))
                                }
                            }
                        }
                    } catch (e: Exception) { Timber.w(e, "MediaCodecList query failed") }
                    
                    if (custom.none { it.name.equals("OMX.google.mp3.decoder", ignoreCase = true) }) {
                        custom.add(0, MediaCodecInfo.newInstance("OMX.google.mp3.decoder", mimeType, mimeType, null, false, true, false, false, false))
                    }
                    val c2 = defaultDecoders.filter { it.name.equals("c2.android.mp3.decoder", ignoreCase = true) }
                    val others = defaultDecoders.filter { it.name.equals("c2.android.mp3.decoder", ignoreCase = true) == false }
                    (custom + others + c2).distinctBy { it.name }
                } else {
                    defaultDecoders
                }
            } else {
                    decoders
                }
            }
        }

        // Track selector: Never downmix 5.1/7.1 audio to stereo on soundbars
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setConstrainAudioChannelCountToDeviceCapabilities(false)
                .build()
        }

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
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
