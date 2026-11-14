package com.tiboard.quest3replika

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.tiboard.quest3replika.common.Permissions
import com.tiboard.quest3replika.common.Prefs
import com.tiboard.quest3replika.common.Protocol
import com.tiboard.quest3replika.discovery.NsdScanner
import com.tiboard.quest3replika.receiver.ReceiverActivity
import com.tiboard.quest3replika.sender.CaptureService

/**
 * Écran d'accueil : choix du mode (Émetteur sur le Quest 3 / Récepteur sur la tablette),
 * réglages du recadrage et lancement de la diffusion ou de la réception.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var senderPanel: View
    private lateinit var receiverPanel: View
    private lateinit var senderButton: Button
    private lateinit var senderStatus: TextView
    private lateinit var receiverStatus: TextView
    private lateinit var senderList: LinearLayout
    private var scanner: NsdScanner? = null
    private var autoLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        senderPanel = findViewById(R.id.senderPanel)
        receiverPanel = findViewById(R.id.receiverPanel)
        senderButton = findViewById(R.id.senderButton)
        senderStatus = findViewById(R.id.senderStatus)
        receiverStatus = findViewById(R.id.receiverStatus)
        senderList = findViewById(R.id.senderList)

        val group = findViewById<RadioGroup>(R.id.modeGroup)
        group.check(if (prefs.mode == Prefs.MODE_RECEIVER) R.id.modeReceiver else R.id.modeSender)
        group.setOnCheckedChangeListener { _, id ->
            prefs.mode = if (id == R.id.modeReceiver) Prefs.MODE_RECEIVER else Prefs.MODE_SENDER
            applyMode()
        }

        setupSenderControls()
        setupReceiverControls()
        Permissions.requestMissing(this)
    }

    override fun onResume() {
        super.onResume()
        CaptureService.statusListener = { text -> runOnUiThread { renderSenderState(text) } }
        renderSenderState(CaptureService.status)
        applyMode()
    }

    override fun onPause() {
        CaptureService.statusListener = null
        stopScanning()
        super.onPause()
    }

    private fun applyMode() {
        val sender = prefs.mode == Prefs.MODE_SENDER
        senderPanel.visibility = if (sender) View.VISIBLE else View.GONE
        receiverPanel.visibility = if (sender) View.GONE else View.VISIBLE
        if (sender) stopScanning() else startScanning()
    }

    // ---------------- Émetteur ----------------

    private fun setupSenderControls() {
        val crop = findViewById<Switch>(R.id.cropSwitch)
        crop.isChecked = prefs.cropEnabled
        crop.setOnCheckedChangeListener { _, checked ->
            prefs.cropEnabled = checked
            updateCropLabels()
        }

        bindSeek(R.id.zoomSeek, prefs.cropZoom - 100) { prefs.cropZoom = it + 100 }
        bindSeek(R.id.offsetXSeek, prefs.cropOffsetX + 100) { prefs.cropOffsetX = it - 100 }
        bindSeek(R.id.offsetYSeek, prefs.cropOffsetY + 100) { prefs.cropOffsetY = it - 100 }
        bindSeek(R.id.bitrateSeek, prefs.bitrateMbps - 2) { prefs.bitrateMbps = it + 2 }
        updateCropLabels()

        senderButton.setOnClickListener {
            if (CaptureService.isRunning) {
                CaptureService.stop(this)
            } else {
                requestProjection()
            }
        }
    }

    private fun bindSeek(id: Int, initial: Int, onChange: (Int) -> Unit) {
        val seek = findViewById<SeekBar>(id)
        seek.progress = initial
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onChange(progress)
                    updateCropLabels()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun updateCropLabels() {
        val enabled = prefs.cropEnabled
        findViewById<TextView>(R.id.zoomLabel).text = "${getString(R.string.sender_zoom)} : ${prefs.cropZoom} %"
        findViewById<TextView>(R.id.offsetXLabel).text = "${getString(R.string.sender_offset_x)} : ${prefs.cropOffsetX}"
        findViewById<TextView>(R.id.offsetYLabel).text = "${getString(R.string.sender_offset_y)} : ${prefs.cropOffsetY}"
        findViewById<TextView>(R.id.bitrateLabel).text = "${getString(R.string.sender_bitrate)} : ${prefs.bitrateMbps} Mbit/s"
        for (id in intArrayOf(R.id.zoomSeek, R.id.offsetXSeek, R.id.offsetYSeek, R.id.zoomLabel, R.id.offsetXLabel, R.id.offsetYLabel)) {
            findViewById<View>(id).isEnabled = enabled
            findViewById<View>(id).alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("API Activity historique, suffisante ici (aucune dépendance AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            senderStatus.text = "Démarrage…"
        } else {
            Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderSenderState(text: String?) {
        val running = CaptureService.isRunning
        senderButton.setText(if (running) R.string.sender_stop else R.string.sender_start)
        senderStatus.text = text ?: getString(R.string.sender_idle)
    }

    // ---------------- Récepteur ----------------

    private fun setupReceiverControls() {
        val auto = findViewById<CheckBox>(R.id.autoConnect)
        auto.isChecked = prefs.autoConnect
        auto.setOnCheckedChangeListener { _, c -> prefs.autoConnect = c }

        val ip = findViewById<EditText>(R.id.manualIp)
        ip.setText(prefs.lastManualIp)
        findViewById<Button>(R.id.manualConnect).setOnClickListener {
            val raw = ip.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener
            val host = raw.substringBefore(':')
            val port = raw.substringAfter(':', "").toIntOrNull() ?: Protocol.DEFAULT_PORT
            prefs.lastManualIp = raw
            openReceiver(null, host, port)
        }
    }

    private fun startScanning() {
        if (scanner != null) return
        receiverStatus.setText(R.string.receiver_searching)
        scanner = NsdScanner(this) { list -> renderSenders(list) }.also { it.start() }
    }

    private fun stopScanning() {
        scanner?.stop()
        scanner = null
    }

    private fun renderSenders(list: List<NsdScanner.Sender>) {
        senderList.removeAllViews()
        receiverStatus.setText(if (list.isEmpty()) R.string.receiver_searching else R.string.receiver_found)
        for (s in list) {
            val b = Button(this)
            b.text = "${s.name}\n${s.host}:${s.port}"
            b.isAllCaps = false
            b.setOnClickListener { openReceiver(s.name, s.host, s.port) }
            senderList.addView(b)
        }
        if (prefs.autoConnect && !autoLaunched && list.isNotEmpty()) {
            val preferred = list.firstOrNull { it.name == prefs.lastSenderName } ?: list.first()
            autoLaunched = true
            openReceiver(preferred.name, preferred.host, preferred.port)
        }
    }

    private fun openReceiver(name: String?, host: String, port: Int) {
        if (name != null) prefs.lastSenderName = name
        startActivity(
            Intent(this, ReceiverActivity::class.java)
                .putExtra(ReceiverActivity.EXTRA_NAME, name)
                .putExtra(ReceiverActivity.EXTRA_HOST, host)
                .putExtra(ReceiverActivity.EXTRA_PORT, port)
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_CODE && grantResults.any { it != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_PROJECTION = 2001
    }
}
