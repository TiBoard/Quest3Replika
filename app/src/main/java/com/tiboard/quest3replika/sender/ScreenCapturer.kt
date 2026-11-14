package com.tiboard.quest3replika.sender

import android.content.Context
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.tiboard.quest3replika.common.Prefs
import com.tiboard.quest3replika.common.Protocol
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Chaîne de capture : MediaProjection -> VirtualDisplay -> GlCropRenderer (recadrage) -> VideoEncoder -> StreamSender.
 *
 * Le VirtualDisplay est créé une seule fois (Android 14 interdit d'en recréer un avec le même jeton).
 * Quand le format de sortie change (activation/désactivation du recadrage), seul l'étage
 * GL + encodeur est reconstruit et rebranché via VirtualDisplay.setSurface().
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val sender: StreamSender,
    private val prefs: Prefs,
    private val onError: (Exception) -> Unit,
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    private var renderer: GlCropRenderer? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var dpi = 320
    private var currentCropEnabled = false

    var outputWidth = 0
        private set
    var outputHeight = 0
        private set

    fun start() {
        computeCaptureSize()
        startPipeline()
        virtualDisplay = projection.createVirtualDisplay(
            "Quest3Replika",
            captureWidth, captureHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            renderer!!.inputSurface, null, null,
        )
        Log.i(TAG, "Capture ${captureWidth}x$captureHeight -> sortie ${outputWidth}x$outputHeight")
    }

    @Suppress("DEPRECATION")
    private fun computeCaptureSize() {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        var w = metrics.widthPixels.coerceAtLeast(640)
        var h = metrics.heightPixels.coerceAtLeast(360)
        dpi = metrics.densityDpi
        // Limite la taille de capture : suffisante pour un œil en 720p, raisonnable pour le GPU.
        val maxSide = MAX_CAPTURE_SIDE
        val scale = maxSide.toFloat() / max(w, h)
        if (scale < 1f) {
            w = (w * scale).roundToInt()
            h = (h * scale).roundToInt()
        }
        captureWidth = w and 1.inv()
        captureHeight = h and 1.inv()
    }

    private fun startPipeline() {
        val crop = prefs.cropRect()
        currentCropEnabled = prefs.cropEnabled
        val (ow, oh) = outputSizeFor(crop)
        outputWidth = ow
        outputHeight = oh
        val enc = VideoEncoder(ow, oh, prefs.bitrateMbps * 1_000_000, Protocol.FPS, object : VideoEncoder.Callback {
            override fun onConfig(csd: ByteArray) = sender.setConfig(Protocol.configPayload(ow, oh, csd))
            override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) = sender.sendFrame(data, ptsUs, keyframe)
            override fun onError(e: Exception) = onError.invoke(e)
        })
        enc.start()
        val r = GlCropRenderer(enc.inputSurface, ow, oh, captureWidth, captureHeight, Protocol.FPS)
        r.start()
        r.setCrop(crop)
        encoder = enc
        renderer = r
    }

    private fun stopPipeline() {
        renderer?.release()
        renderer = null
        encoder?.stop()
        encoder = null
    }

    /** Taille de sortie qui respecte le ratio de la zone recadrée, dans une boîte 1280x720. */
    private fun outputSizeFor(crop: RectF): Pair<Int, Int> {
        val aspect = (crop.width() * captureWidth) / (crop.height() * captureHeight)
        var w: Float
        var h: Float
        if (aspect >= MAX_W.toFloat() / MAX_H) {
            w = MAX_W.toFloat(); h = w / aspect
        } else {
            h = MAX_H.toFloat(); w = h * aspect
        }
        val ow = (w.toInt() / 16 * 16).coerceAtLeast(160)
        val oh = (h.toInt() / 16 * 16).coerceAtLeast(160)
        return ow to oh
    }

    /** Applique les réglages de recadrage. Zoom/décalage : en direct. Activation : reconstruction. */
    fun applyCropSettings() {
        val r = renderer ?: return
        if (prefs.cropEnabled != currentCropEnabled) {
            Log.i(TAG, "Changement de recadrage : reconstruction de l'encodeur")
            virtualDisplay?.surface = null
            stopPipeline()
            try {
                startPipeline()
                virtualDisplay?.surface = renderer!!.inputSurface
            } catch (e: Exception) {
                onError(e)
            }
        } else {
            r.setCrop(prefs.cropRect())
        }
    }

    fun setBitrate(mbps: Int) = encoder?.setBitrate(mbps * 1_000_000)

    fun requestKeyframe() = encoder?.requestKeyframe()

    fun stop() {
        virtualDisplay?.surface = null
        virtualDisplay?.release()
        virtualDisplay = null
        stopPipeline()
    }

    companion object {
        private const val TAG = "ScreenCapturer"
        private const val MAX_W = 1280
        private const val MAX_H = 720
        private const val MAX_CAPTURE_SIDE = 2560
    }
}
