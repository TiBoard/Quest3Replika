package com.tiboard.quest3replika.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.tiboard.quest3replika.common.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Découverte « sans box » : quand le casque est connecté directement au point d'accès Wi-Fi de la tablette,
 * la tablette est la passerelle du réseau. Le casque lui envoie donc une petite balise UDP (« QM1|port|nom »)
 * toutes les 2 secondes, en unicast vers la passerelle et en diffusion. Cela ne dépend pas du mDNS,
 * que beaucoup de points d'accès mobiles filtrent.
 */
object Beacon {
    private const val PREFIX = "QM1"
    private const val TAG = "Beacon"

    fun encode(port: Int, name: String): ByteArray = "$PREFIX|$port|$name".toByteArray(Charsets.UTF_8)

    fun parse(data: ByteArray, len: Int): Pair<Int, String>? {
        val parts = String(data, 0, len, Charsets.UTF_8).split('|', limit = 3)
        if (parts.size < 3 || parts[0] != PREFIX) return null
        val port = parts[1].toIntOrNull() ?: return null
        return port to parts[2]
    }

    /** Adresses où envoyer la balise : passerelle Wi-Fi (la tablette), diffusion du sous-réseau et diffusion globale. */
    @Suppress("DEPRECATION")
    fun targets(context: Context): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val d = wifi.dhcpInfo
            if (d != null) {
                if (d.gateway != 0) out += fromLittleEndian(d.gateway)
                if (d.ipAddress != 0 && d.netmask != 0) out += fromLittleEndian((d.ipAddress and d.netmask) or d.netmask.inv())
            }
            out += InetAddress.getByName("255.255.255.255")
            out += WifiDirect.broadcastTargets()
        } catch (e: Exception) {
            Log.w(TAG, "targets", e)
        }
        return out.distinct()
    }

    private fun fromLittleEndian(v: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
    )
}

/** Émet la balise en continu, tant que le service de diffusion tourne. */
class BeaconSender(private val context: Context, private val port: Int, private val name: String) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "QM-beacon", isDaemon = true) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val payload = Beacon.encode(port, name)
                    while (running) {
                        for (target in Beacon.targets(context)) {
                            try {
                                socket.send(DatagramPacket(payload, payload.size, target, Protocol.BEACON_PORT))
                            } catch (e: Exception) {
                                // Réseau pas encore prêt ou adresse injoignable : on réessaie au prochain tour.
                            }
                        }
                        Thread.sleep(2000)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w("BeaconSender", "arrêt", e)
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}

/** Écoute les balises des casques (côté tablette). Le callback est appelé sur un thread secondaire. */
class BeaconListener(context: Context, private val onSender: (NsdScanner.Sender) -> Unit) {
    private val appContext = context.applicationContext
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("Quest3Replika-beacon")?.apply {
            setReferenceCounted(false); acquire()
        }
        worker = thread(name = "QM-beacon-rx", isDaemon = true) {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(Protocol.BEACON_PORT))
                s.soTimeout = 1000
                socket = s
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                while (running) {
                    try {
                        s.receive(packet)
                        val parsed = Beacon.parse(packet.data, packet.length) ?: continue
                        val host = packet.address.hostAddress ?: continue
                        onSender(NsdScanner.Sender(parsed.second, host, parsed.first))
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w("BeaconListener", "arrêt", e)
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        worker = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
