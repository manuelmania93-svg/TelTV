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

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (constrained) 10_000 else 15_000,
                if (constrained) 25_000 else 35_000,
                1_500,
                if (constrained) 2_000 else 2_500
            )
            .setTargetBufferBytes(if (constrained) 24 * 1024 * 1024 else 35 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioCapabilities = AudioCapabilities.getCapabilities(this)
        val renderersFactory = object : DefaultRenderersFactory(this) {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py} 
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py} 
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(audioCapabilities)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
            forceDisableMediaCodecAsynchronousQueueing()
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val defaultDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType.equals(MimeTypes.AUDIO_MPEG, ignoreCase = true)) {
                    val custom = mutableListOf<MediaCodecInfo>()
                    try {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py} 
                        val mcl = MediaCodecList(MediaCodecList.ALL_CODECS)
                        for (info in mcl.codecInfos) {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py} 
                            if (info.isEncoder) continue
                            for (t in info.supportedTypes) {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py} 
                                if (t.equals(MimeTypes.AUDIO_MPEG, ignoreCase = true) && !info.name.equals("c2.android.mp3.decoder", ignoreCase = true)) {
                                    val caps = try {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py}  info.getCapabilitiesForType(mimeType)  catch (e: Exception) {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py}  null 
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
                    val others = defaultDecoders.filter { !it.name.equals("c2.android.mp3.decoder", ignoreCase = true) }
                    (custom + others + c2).distinctBy {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py}  it.name 
                 else {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py} 
                    defaultDecoders
                }
            
        }

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
                true
            )
            .build()

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {.git{,hub,ignore},RE{ADME.md,LEASE.md},app{,ly_{advanced_upgrades.py,logo_and_manual_login.py,s{oundbar_and_updater.py,treaming_upgrades.py,uite.py}}},build.gradle.kts,gradle{,.properties,w{,.bat}},logo.png,process_app_logo.py,settings.gradle.kts,update_icons.py} 
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e(error, "Player error (errorCode=%s)", error.errorCodeName)
            }
        )
        player = exoPlayer

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, sessionActivityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
