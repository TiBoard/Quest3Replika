package com.tiboard.quest3replika.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tiboard.quest3replika.common.Protocol
import java.util.ArrayDeque

/**
 * Recherche les émetteurs Quest3Replika sur le réseau local (mDNS / DNS-SD).
 * Les résolutions sont sérialisées (NsdManager n'en accepte qu'une à la fois avant Android 14).
 * Les callbacks sont livrés sur le thread principal.
 */
class NsdScanner(context: Context, private val onChange: (List<Sender>) -> Unit) {

    data class Sender(val name: String, val host: String, val port: Int)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val senders = LinkedHashMap<String, Sender>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    val current: List<Sender> get() = synchronized(senders) { senders.values.toList() }

    fun start() {
        if (discovery != null) return
        acquireMulticast()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Découverte démarrée")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("questmirror")) return
                main.post { enqueueResolve(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    val removed = synchronized(senders) { senders.remove(info.serviceName) }
                    if (removed != null) publish()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Découverte arrêtée")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Échec du démarrage de la découverte : $errorCode")
                discovery = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Échec de l'arrêt de la découverte : $errorCode")
            }
        }
        discovery = l
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices", e)
            discovery = null
        }
    }

    fun stop() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery", e)
            }
        }
        discovery = null
        pending.clear()
        resolving = false
        releaseMulticast()
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        pending.add(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || discovery == null) return
        val info = pending.poll() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Résolution échouée (${s.serviceName}) : $errorCode")
                    main.post {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            main.postDelayed({ enqueueResolve(s) }, 300)
                        }
                        resolveNext()
                    }
                }

                override fun onServiceResolved(s: NsdServiceInfo) {
                    val host = hostOf(s)
                    main.post {
                        resolving = false
                        if (host != null) {
                            synchronized(senders) { senders[s.serviceName] = Sender(s.serviceName, host, s.port) }
                            publish()
                        }
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "resolveService", e)
            resolving = false
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(s: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addr = s.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: s.hostAddresses.firstOrNull()
            if (addr != null) return addr.hostAddress
        }
        return s.host?.hostAddress
    }

    private fun publish() = onChange(current)

    private fun acquireMulticast() {
        if (multicastLock != null) return
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("Quest3Replika-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticast() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdScanner"
    }
}
