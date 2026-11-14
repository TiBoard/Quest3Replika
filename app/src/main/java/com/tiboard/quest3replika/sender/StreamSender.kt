package com.tiboard.quest3replika.sender

import android.util.Log
import com.tiboard.quest3replika.common.Protocol
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Serveur TCP de l'émetteur. Un seul récepteur à la fois : une nouvelle connexion remplace l'ancienne.
 *
 * Contrôle de la latence : si la file d'envoi dépasse quelques images (Wi-Fi saturé), on vide la file
 * et on attend la prochaine image clé, plutôt que d'accumuler du retard.
 */
class StreamSender(private val listener: Listener) {

    interface Listener {
        fun onClientConnected(address: String)
        fun onClientDisconnected(address: String)
        fun onKeyframeRequested()
        fun onStopRequested()
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    @Volatile private var client: Client? = null
    @Volatile private var configPacket: Protocol.Packet? = null

    /** Démarre l'écoute. Renvoie le port réellement utilisé. */
    fun start(preferredPort: Int): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(preferredPort))
        } catch (e: IOException) {
            Log.w(TAG, "Port $preferredPort occupé, port aléatoire utilisé")
            ss.bind(InetSocketAddress(0))
        }
        server = ss
        running = true
        thread(name = "QM-accept", isDaemon = true) { acceptLoop(ss) }
        return ss.localPort
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept", e)
                break
            }
            try {
                s.tcpNoDelay = true
                s.sendBufferSize = 256 * 1024
                s.trafficClass = 0x10 // IPTOS_LOWDELAY
                s.keepAlive = true
            } catch (e: Exception) {
                Log.w(TAG, "Options socket", e)
            }
            client?.close()
            val c = Client(s)
            client = c
            c.start()
        }
    }

    fun setConfig(payload: ByteArray) {
        val p = Protocol.Packet(Protocol.TYPE_CONFIG, 0, 0, payload)
        configPacket = p
        client?.sendConfig(p)
    }

    fun sendFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        client?.offerFrame(
            Protocol.Packet(Protocol.TYPE_FRAME, if (keyframe) Protocol.FLAG_KEYFRAME else 0, ptsUs, data)
        )
    }

    val hasClient: Boolean get() = client != null

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        client?.close()
        client = null
    }

    private inner class Client(private val socket: Socket) {
        private val address = socket.inetAddress?.hostAddress ?: "?"
        private val queue = LinkedBlockingDeque<Protocol.Packet>()
        @Volatile private var open = true
        // On commence toujours par une image clé (IDR à la connexion).
        @Volatile private var waitKeyframe = true

        fun start() {
            thread(name = "QM-writer", isDaemon = true) { writeLoop() }
            thread(name = "QM-reader", isDaemon = true) { readLoop() }
            listener.onClientConnected(address)
            listener.onKeyframeRequested()
        }

        fun sendConfig(p: Protocol.Packet) {
            if (!open) return
            queue.clear()
            queue.offer(p)
            waitKeyframe = true
            listener.onKeyframeRequested()
        }

        fun offerFrame(p: Protocol.Packet) {
            if (!open) return
            if (waitKeyframe) {
                if (!p.isKeyframe) return
                waitKeyframe = false
            } else if (queue.size >= MAX_QUEUED_FRAMES) {
                // Réseau trop lent : on jette le retard accumulé et on repart sur une image clé.
                Log.w(TAG, "File saturée (${queue.size}), vidage et demande d'image clé")
                queue.removeIf { it.type == Protocol.TYPE_FRAME }
                if (!p.isKeyframe) {
                    waitKeyframe = true
                    listener.onKeyframeRequested()
                    return
                }
            }
            queue.offer(p)
        }

        private fun writeLoop() {
            val header = Protocol.newHeaderBuffer()
            try {
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                Protocol.writeHandshake(out)
                configPacket?.let { queue.offerFirst(it) }
                while (open) {
                    val p = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    Protocol.writePacket(out, header, p)
                    if (queue.isEmpty()) out.flush()
                }
            } catch (e: Exception) {
                if (open) Log.i(TAG, "Fin d'écriture : ${e.message}")
            } finally {
                close()
            }
        }

        private fun readLoop() {
            val header = ByteArray(Protocol.HEADER_SIZE)
            try {
                val input = DataInputStream(socket.getInputStream())
                while (open) {
                    val p = Protocol.readPacket(input, header)
                    when (p.type) {
                        Protocol.TYPE_REQUEST_KEYFRAME -> {
                            waitKeyframe = true
                            listener.onKeyframeRequested()
                        }

                        Protocol.TYPE_STOP -> listener.onStopRequested()
                    }
                }
            } catch (e: Exception) {
                if (open) Log.i(TAG, "Fin de lecture : ${e.message}")
            } finally {
                close()
            }
        }

        fun close() {
            synchronized(this) {
                if (!open) return
                open = false
            }
            queue.clear()
            try {
                socket.close()
            } catch (_: IOException) {
            }
            if (client === this) client = null
            listener.onClientDisconnected(address)
        }
    }

    companion object {
        private const val TAG = "StreamSender"
        private const val MAX_QUEUED_FRAMES = 6 // ~200 ms à 30 i/s
    }
}
