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
        get() = sp.getBoolean(KEY_CROP, false)
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

    /** Nom Wi-Fi Direct de la tablette choisie la dernière fois (le casque la retrouve en priorité). */
    var lastDirectName: String?
        get() = sp.getString("last_direct_name", null)
        set(v) = sp.edit().putString("last_direct_name", v).apply()

    var lastManualIp: String
        get() = sp.getString(KEY_LAST_IP, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_IP, v).apply()

    /** true : l'image remplit la tablette en 16:9 (haut/bas coupés) ; false : image entière avec bandes noires. */
    var fillMode: Boolean
        get() = sp.getBoolean(KEY_FILL, true)
        set(v) = sp.edit().putBoolean(KEY_FILL, v).apply()

    /** Zone source (fractions de l'image capturée, origine en haut à gauche) et zone de destination dans la sortie. */
    data class Frame(val src: RectF, val dst: RectF)

    /**
     * Calcule le cadrage de la vidéo de sortie, toujours en paysage ([outAspect], 16:9 par défaut).
     *  - base : image entière, ou moitié gauche (œil gauche du rendu côte à côte) si le recadrage est activé ;
     *  - zoom et décalages s'appliquent dans la base, sur toute sa course ;
     *  - « remplir » : la fenêtre source prend le ratio de la sortie (on coupe en haut/bas ou sur les côtés) ;
     *  - « tout voir » : la fenêtre garde son ratio et s'affiche centrée avec des bandes noires.
     */
    fun frame(capW: Int, capH: Int, outAspect: Float): Frame {
        val base = if (cropEnabled) RectF(0f, 0f, 0.5f, 1f) else RectF(0f, 0f, 1f, 1f)
        val z = cropZoom / 100f
        var w = base.width() / z
        var h = base.height() / z
        var dst = RectF(0f, 0f, 1f, 1f)
        val a = (w * capW) / (h * capH)
        if (fillMode) {
            if (a < outAspect) h = w * capW / (outAspect * capH) else w = h * capH * outAspect / capW
        } else if (a >= outAspect) {
            val dh = outAspect / a
            dst = RectF(0f, (1f - dh) / 2f, 1f, (1f + dh) / 2f)
        } else {
            val dw = a / outAspect
            dst = RectF((1f - dw) / 2f, 0f, (1f + dw) / 2f, 1f)
        }
        val cx = base.centerX() + (cropOffsetX / 100f) * (base.width() / 2f - w / 2f)
        val cy = base.centerY() + (cropOffsetY / 100f) * (base.height() / 2f - h / 2f)
        return Frame(RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), dst)
    }

    companion object {
        const val MODE_SENDER = "sender"
        const val MODE_RECEIVER = "receiver"

        const val KEY_MODE = "mode"
        const val KEY_CROP = "crop_enabled"
        const val KEY_ZOOM = "crop_zoom"
        const val KEY_FILL = "fill_mode"
        const val KEY_OFF_X = "crop_off_x"
        const val KEY_OFF_Y = "crop_off_y"
        const val KEY_BITRATE = "bitrate_mbps"
        const val KEY_AUTO = "auto_connect"
        const val KEY_LAST_SENDER = "last_sender"
        const val KEY_LAST_IP = "last_ip"
    }
}
