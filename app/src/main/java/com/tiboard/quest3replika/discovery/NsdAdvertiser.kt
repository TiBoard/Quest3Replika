package com.tiboard.quest3replika.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.tiboard.quest3replika.common.Protocol

/** Annonce l'émetteur sur le réseau local via mDNS / DNS-SD (_questmirror._tcp). */
class NsdAdvertiser(context: Context, private val port: Int) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    var registeredName: String? = null
        private set

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "${Protocol.SERVICE_NAME_PREFIX} ${Build.MODEL}"
            serviceType = Protocol.SERVICE_TYPE
            port = this@NsdAdvertiser.port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                registeredName = s.serviceName
                Log.i(TAG, "Service annoncé : ${s.serviceName} port $port")
            }

            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Échec de l'annonce NSD : $errorCode")
            }

            override fun onServiceUnregistered(s: NsdServiceInfo) {
                Log.i(TAG, "Service retiré")
            }

            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Échec du retrait NSD : $errorCode")
            }
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.e(TAG, "registerService", e)
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd.unregisterService(l)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterService", e)
        }
    }

    companion object {
        private const val TAG = "NsdAdvertiser"
    }
}
