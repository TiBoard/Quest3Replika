package com.tiboard.quest3replika.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.tiboard.quest3replika.MainActivity
import com.tiboard.quest3replika.R
import com.tiboard.quest3replika.common.Permissions
import com.tiboard.quest3replika.common.Prefs
import com.tiboard.quest3replika.common.Protocol
import com.tiboard.quest3replika.discovery.NsdAdvertiser

/**
 * Service au premier plan (type mediaProjection) : la capture continue quand l'utilisateur
 * quitte l'application et lance un jeu ou une application VR sur le Quest 3.
 *
 * Verrous maintenus pendant la diffusion :
 *  - WakeLock partiel (le CPU ne s'endort pas),
 *  - WifiLock basse latence (pas d'économie d'énergie Wi-Fi qui ajoute des dizaines de ms),
 *  - MulticastLock (annonce mDNS fiable).
 */
class CaptureService : Service(), StreamSender.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    private var sender: StreamSender? = null
    private var capturer: ScreenCapturer? = null
    private var advertiser: NsdAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var port = Protocol.DEFAULT_PORT
    private var stopping = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_CROP, Prefs.KEY_ZOOM, Prefs.KEY_OFF_X, Prefs.KEY_OFF_Y -> capturer?.applyCropSettings()
            Prefs.KEY_BITRATE -> capturer?.setBitrate(prefs.bitrateMbps)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (projection != null) return START_NOT_STICKY
                // Android 14 : startForeground(type mediaProjection) AVANT getMediaProjection().
                startInForeground(getString(R.string.sender_idle))
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startStreaming(resultCode, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Démarrage impossible", e)
                    publishStatus("Erreur : ${e.message}")
                    stopSelf()
                }
            }
        }
        // Pas de redémarrage automatique : le jeton MediaProjection est à usage unique.
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("MediaProjection nulle")
        projection = mp
        // Obligatoire avant createVirtualDisplay() sur Android 14.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection arrêtée par le système")
                main.post { stopSelf() }
            }
        }, main)

        acquireLocks()

        val s = StreamSender(this)
        port = s.start(Protocol.DEFAULT_PORT)
        sender = s

        advertiser = NsdAdvertiser(this, port).also { it.start() }

        capturer = ScreenCapturer(this, mp, s, prefs) { e ->
            Log.e(TAG, "Erreur d'encodage", e)
            main.post {
                publishStatus("Erreur d'encodage : ${e.message}")
                stopSelf()
            }
        }.also { it.start() }

        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        isRunning = true
        publishStatus(waitingText())
    }

    private fun waitingText(): String {
        val ips = Permissions.localIpv4Addresses().joinToString(", ")
        val c = capturer
        val res = if (c != null) " — ${c.outputWidth}x${c.outputHeight}" else ""
        return "En attente d'un récepteur sur $ips:$port$res"
    }

    // --- StreamSender.Listener (appelés depuis les threads réseau) ---

    override fun onClientConnected(address: String) {
        main.post { publishStatus("Diffusion vers $address") }
    }

    override fun onClientDisconnected(address: String) {
        main.post { if (!stopping) publishStatus("Récepteur $address déconnecté. " + waitingText()) }
    }

    override fun onKeyframeRequested() {
        capturer?.requestKeyframe()
    }

    // --- Notification & état ---

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun publishStatus(text: String) {
        status = text
        if (!stopping) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
        statusListener?.invoke(text)
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Quest3Replika:capture").apply {
            setReferenceCounted(false); acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "Quest3Replika:wifi").apply {
            setReferenceCounted(false); acquire()
        }
        multicastLock = wifi.createMulticastLock("Quest3Replika:mdns").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }
        wakeLock = null; wifiLock = null; multicastLock = null
    }

    override fun onDestroy() {
        stopping = true
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        advertiser?.stop(); advertiser = null
        try { capturer?.stop() } catch (e: Exception) { Log.w(TAG, "capturer.stop", e) }
        capturer = null
        sender?.stop(); sender = null
        try { projection?.stop() } catch (e: Exception) { Log.w(TAG, "projection.stop", e) }
        projection = null
        releaseLocks()
        isRunning = false
        status = null
        statusListener?.invoke(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 42

        const val ACTION_START = "com.tiboard.quest3replika.START"
        const val ACTION_STOP = "com.tiboard.quest3replika.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile var isRunning = false
            private set
        @Volatile var status: String? = null
            private set

        /** Écouteur d'état pour l'interface (appelé sur le thread principal ; null = arrêté). */
        var statusListener: ((String?) -> Unit)? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
