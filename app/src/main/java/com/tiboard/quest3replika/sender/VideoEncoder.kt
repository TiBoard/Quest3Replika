package com.tiboard.quest3replika.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.tiboard.quest3replika.common.Protocol
import kotlin.concurrent.thread

/**
 * Encodeur H.264 matériel alimenté par une Surface (aucune copie CPU des pixels).
 * Réglages orientés faible latence : CBR, pas d'images B, image clé toutes les secondes,
 * priorité temps réel, répétition de la dernière image si l'écran ne change pas.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    private var bitrate: Int,
    private val fps: Int,
    private val callback: Callback,
) {
    interface Callback {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean)
        fun onError(e: Exception)
    }

    private lateinit var codec: MediaCodec
    lateinit var inputSurface: Surface
        private set
    @Volatile private var running = false
    private var drainThread: Thread? = null

    fun start() {
        codec = MediaCodec.createEncoderByType(Protocol.MIME)
        try {
            codec.configure(buildFormat(withProfile = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Configuration avec profil refusée, nouvel essai sans profil", e)
            codec.reset()
            codec.configure(buildFormat(withProfile = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        drainThread = thread(name = "QM-encoder", isDaemon = true) { drainLoop() }
        Log.i(TAG, "Encodeur ${codec.name} démarré : ${width}x$height @ $fps i/s, ${bitrate / 1000} kbit/s")
    }

    private fun buildFormat(withProfile: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(Protocol.MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps * 3)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0) // temps réel
        f.setInteger(MediaFormat.KEY_LATENCY, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        if (supportsCbr()) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        if (withProfile) {
            // Baseline : pas d'images B, décodage le plus simple et le plus rapide côté tablette.
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        return f
    }

    private fun supportsCbr(): Boolean = try {
        codec.codecInfo.getCapabilitiesForType(Protocol.MIME).encoderCapabilities
            .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    } catch (e: Exception) {
        false
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(data)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                callback.onConfig(data)
                            } else {
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                callback.onFrame(data, info.presentationTimeUs, key)
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }

                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Certains encodeurs fournissent SPS/PPS via le format plutôt que via un buffer CODEC_CONFIG.
                        val f = codec.outputFormat
                        val sps = f.getByteBuffer("csd-0")
                        val pps = f.getByteBuffer("csd-1")
                        if (sps != null && pps != null) {
                            val a = ByteArray(sps.remaining()).also { sps.duplicate().get(it) }
                            val b = ByteArray(pps.remaining()).also { pps.duplicate().get(it) }
                            callback.onConfig(a + b)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) callback.onError(e)
        }
    }

    fun requestKeyframe() {
        if (!running) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyframe", e)
        }
    }

    fun setBitrate(bps: Int) {
        bitrate = bps
        if (!running) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        } catch (e: Exception) {
            Log.w(TAG, "setBitrate", e)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        drainThread?.join(500)
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop", e)
        }
        codec.release()
        inputSurface.release()
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
