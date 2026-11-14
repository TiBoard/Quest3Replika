package com.tiboard.quest3replika.common

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF

/** Réglages persistants de l'application (mode, recadrage, débit…). */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("quest3replika", Context.MODE_PRIVATE)

    var mode: String
        get() = sp.getString(KEY_MODE, MODE_SENDER) ?: MODE_SENDER
        set(v) = sp.edit().putString(KEY_MODE, v).apply()

    var cropEnabled: Boolean
        get() = sp.getBoolean(KEY_CROP, true)
        set(v) = sp.edit().putBoolean(KEY_CROP, v).apply()

    /** Zoom dans l'œil gauche, en pourcentage (100 = œil entier, 200 = moitié). */
    var cropZoom: Int
        get() = sp.getInt(KEY_ZOOM, 100)
        set(v) = sp.edit().putInt(KEY_ZOOM, v.coerceIn(100, 200)).apply()

    /** Décalage du cadrage, de -100 à +100 (0 = centré). */
    var cropOffsetX: Int
        get() = sp.getInt(KEY_OFF_X, 0)
        set(v) = sp.edit().putInt(KEY_OFF_X, v.coerceIn(-100, 100)).apply()

    var cropOffsetY: Int
        get() = sp.getInt(KEY_OFF_Y, 0)
        set(v) = sp.edit().putInt(KEY_OFF_Y, v.coerceIn(-100, 100)).apply()

    var bitrateMbps: Int
        get() = sp.getInt(KEY_BITRATE, 5)
        set(v) = sp.edit().putInt(KEY_BITRATE, v.coerceIn(2, 10)).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    var lastSenderName: String?
        get() = sp.getString(KEY_LAST_SENDER, null)
        set(v) = sp.edit().putString(KEY_LAST_SENDER, v).apply()

    var lastManualIp: String
        get() = sp.getString(KEY_LAST_IP, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_IP, v).apply()

    /**
     * Zone source à capturer, en fractions de l'image (origine en haut à gauche).
     * Sans recadrage : image entière. Avec recadrage : moitié gauche (œil gauche du rendu
     * stéréoscopique côte à côte), éventuellement zoomée et décalée.
     */
    fun cropRect(): RectF {
        if (!cropEnabled) return RectF(0f, 0f, 1f, 1f)
        val z = cropZoom / 100f
        val w = 0.5f / z
        val h = 1f / z
        val cx = 0.25f + (cropOffsetX / 100f) * (0.25f - w / 2f)
        val cy = 0.5f + (cropOffsetY / 100f) * (0.5f - h / 2f)
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    companion object {
        const val MODE_SENDER = "sender"
        const val MODE_RECEIVER = "receiver"

        const val KEY_MODE = "mode"
        const val KEY_CROP = "crop_enabled"
        const val KEY_ZOOM = "crop_zoom"
        const val KEY_OFF_X = "crop_off_x"
        const val KEY_OFF_Y = "crop_off_y"
        const val KEY_BITRATE = "bitrate_mbps"
        const val KEY_AUTO = "auto_connect"
        const val KEY_LAST_SENDER = "last_sender"
        const val KEY_LAST_IP = "last_ip"
    }
}
