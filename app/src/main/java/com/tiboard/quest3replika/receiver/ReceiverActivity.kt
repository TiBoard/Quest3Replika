package com.tiboard.quest3replika.receiver

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.tiboard.quest3replika.R
import com.tiboard.quest3replika.common.Prefs
import com.tiboard.quest3replika.common.Protocol
import com.tiboard.quest3replika.discovery.NsdScanner
import java.net.InetSocketAddress

/**
 * Affichage plein écran du flux. L'émetteur est choisi par son nom NSD (suivi même si son IP change)
 * ou par une adresse IP manuelle. Touchez l'écran pour afficher/masquer les statistiques.
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback, StreamReceiver.Listener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TextView
    private lateinit var prefs: Prefs
    private var scanner: NsdScanner? = null
    private var receiver: StreamReceiver? = null

    @Volatile private var target: InetSocketAddress? = null
    private var wantedName: String? = null
    private var videoW = 0
    private var videoH = 0
    private var statusText = ""
    private var statsText = ""
    private var showOverlay = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        surfaceView = findViewById(R.id.videoSurface)
        overlay = findViewById(R.id.overlay)
        surfaceView.holder.addCallback(this)
        findViewById<View>(R.id.root).setOnClickListener {
            showOverlay = !showOverlay
            refreshOverlay()
        }
        findViewById<View>(R.id.root).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }

        wantedName = intent.getStringExtra(EXTRA_NAME)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        if (host != null) target = InetSocketAddress(host, port)

        if (wantedName != null) {
            // Suit l'émetteur par son nom : reconnexion transparente si son adresse change.
            scanner = NsdScanner(this) { list ->
                list.firstOrNull { it.name == wantedName }?.let { target = InetSocketAddress(it.host, it.port) }
            }
        }
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        scanner?.start()
        hideSystemBars()
    }

    override fun onPause() {
        scanner?.stop()
        super.onPause()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        receiver = StreamReceiver(holder.surface, { target }, this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        receiver?.stop()
        receiver = null
    }

    // --- StreamReceiver.Listener (threads réseau -> UI) ---

    override fun onStatus(text: String) = runOnUiThread {
        statusText = text
        refreshOverlay()
    }

    override fun onVideoSize(width: Int, height: Int) = runOnUiThread {
        if (width > 0 && height > 0 && (width != videoW || height != videoH)) {
            videoW = width
            videoH = height
            applyAspect()
        }
    }

    override fun onStats(fps: Int, kbps: Int, resolution: String) = runOnUiThread {
        statsText = "$resolution · $fps i/s · ${"%.1f".format(kbps / 1000f)} Mbit/s"
        refreshOverlay()
    }

    private fun refreshOverlay() {
        overlay.visibility = if (showOverlay) View.VISIBLE else View.GONE
        val name = wantedName ?: target?.hostString ?: ""
        overlay.text = listOf(name, statusText, statsText).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Ajuste la SurfaceView au ratio de la vidéo (bandes noires si nécessaire). */
    private fun applyAspect() {
        val root = findViewById<View>(R.id.root)
        val rw = root.width
        val rh = root.height
        if (videoW == 0 || videoH == 0 || rw == 0 || rh == 0) return
        val va = videoW.toFloat() / videoH
        val (w, h) = if (rw.toFloat() / rh > va) (rh * va).toInt() to rh else rw to (rw / va).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            surfaceView.layoutParams = lp
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }
}
