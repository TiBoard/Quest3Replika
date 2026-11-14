package com.tiboard.quest3replika.common

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/** Gestion des permissions d'exécution Android 13+ et petits utilitaires réseau. */
object Permissions {
    const val REQUEST_CODE = 1001

    /** Permissions demandées à l'exécution, selon la version d'Android. */
    fun runtimePermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list.toTypedArray()
    }

    fun missing(activity: Activity): Array<String> =
        runtimePermissions().filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /** Demande les permissions manquantes ; renvoie true si tout est déjà accordé. */
    fun requestMissing(activity: Activity): Boolean {
        val m = missing(activity)
        if (m.isEmpty()) return true
        activity.requestPermissions(m, REQUEST_CODE)
        return false
    }

    /** Adresses IPv4 locales (Wi-Fi / point d'accès), pour affichage. */
    fun localIpv4Addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }
}
