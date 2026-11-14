package com.tiboard.quest3replika.receiver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.tiboard.quest3replika.common.Protocol
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Décodeur H.264 matériel en mode faible latence, qui rend directement dans la Surface d'affichage.
 * Chaque image décodée est affichée immédiatement (pas de tampon de lecture).
 */
class VideoDecoder(
    private val surface: Surface,
    private val onVideoSize: (Int, Int) -> Unit,
) {
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var outputThread: Thread? = null
    @Volatile var renderedFrames = 0L
        private set

    var config: Protocol.VideoConfig? = null
        private set

    fun configure(cfg: Protocol.VideoConfig) {
        release()
        val c = MediaCodec.createDecoderByType(Protocol.MIME)
        try {
            c.configure(buildFormat(cfg, c, vendorKeys = true), surface, null, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Configuration étendue refusée, nouvel essai standard", e)
            c.reset()
            c.configure(buildFormat(cfg, c, vendorKeys = false), surface, null, 0)
        }
        c.start()
        codec = c
        config = cfg
        running = true
        outputThread = thread(name = "QM-decoder-out", isDaemon = true) { outputLoop(c) }
        onVideoSize(cfg.width, cfg.height)
        Log.i(TAG, "Décodeur ${c.name} : ${cfg.width}x${cfg.height}")
    }

    private fun buildFormat(cfg: Protocol.VideoConfig, c: MediaCodec, vendorKeys: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(Protocol.MIME, cfg.width, cfg.height)
        if (cfg.sps != null && cfg.pps != null) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(cfg.sps))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(cfg.pps))
        } else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(cfg.raw))
        }
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lowLatency = try {
                c.codecInfo.getCapabilitiesForType(Protocol.MIME)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            } catch (e: Exception) {
                false
            }
            if (lowLatency) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        if (vendorKeys) {
            // Clés propriétaires courantes (Qualcomm / Exynos / MediaTek) : ignorées si non reconnues.
            f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            f.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
        }
        return f
    }

    /** Envoie une unité d'accès au décodeur. Renvoie false si elle a dû être abandonnée. */
    fun queue(data: ByteArray, ptsUs: Long, keyframe: Boolean): Boolean {
        val c = codec ?: return false
        return try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return false
            val buf = c.getInputBuffer(idx) ?: return false
            buf.clear()
            if (buf.capacity() < data.size) {
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                return false
            }
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "queue", e)
            false
        }
    }

    private fun outputLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    idx >= 0 -> {
                        c.releaseOutputBuffer(idx, info.size > 0)
                        renderedFrames++
                    }

                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        var w = f.getInteger(MediaFormat.KEY_WIDTH)
                        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                        }
                        onVideoSize(w, h)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "outputLoop", e)
        }
    }

    fun release() {
        running = false
        outputThread?.join(300)
        outputThread = null
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        codec = null
        config = null
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}
