package com.tiboard.quest3replika.receiver

import android.util.Log
import android.view.Surface
import com.tiboard.quest3replika.common.Protocol
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Client TCP du récepteur : se connecte à l'émetteur, lit les paquets et alimente le décodeur.
 * Reconnexion automatique : en cas de coupure, nouvel essai chaque seconde vers l'adresse
 * fournie par [targetProvider] (mise à jour en continu par la découverte NSD).
 */
class StreamReceiver(
    private val surface: Surface,
    private val targetProvider: () -> InetSocketAddress?,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onVideoSize(width: Int, height: Int)
        fun onStats(fps: Int, kbps: Int, resolution: String)
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "QM-receiver", isDaemon = true) { loop() }
    }

    private fun loop() {
        val decoder = VideoDecoder(surface) { w, h -> listener.onVideoSize(w, h) }
        while (running) {
            val target = targetProvider()
            if (target == null) {
                listener.onStatus("Recherche de l'émetteur…")
                sleep(500)
                continue
            }
            try {
                listener.onStatus("Connexion à ${target.hostString}:${target.port}…")
                val s = Socket()
                s.tcpNoDelay = true
                s.receiveBufferSize = 512 * 1024
                s.trafficClass = 0x10
                s.connect(target, 3000)
                s.soTimeout = 5000 // l'émetteur répète l'image au moins toutes les 100 ms
                socket = s
                session(s, decoder)
            } catch (e: Exception) {
                if (running) {
                    Log.i(TAG, "Connexion perdue : ${e.message}")
                    listener.onStatus("Déconnecté (${e.message}). Nouvel essai…")
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                decoder.release()
            }
            if (running) sleep(1000)
        }
        decoder.release()
    }

    private fun session(s: Socket, decoder: VideoDecoder) {
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
        val out = s.getOutputStream()
        Protocol.readHandshake(input)
        listener.onStatus("Connecté à ${s.inetAddress.hostAddress}")
        requestKeyframe(out)

        val header = ByteArray(Protocol.HEADER_SIZE)
        var waitKey = true
        var frames = 0
        var bytes = 0L
        var lastStats = System.currentTimeMillis()

        while (running) {
            val p = Protocol.readPacket(input, header)
            when (p.type) {
                Protocol.TYPE_CONFIG -> {
                    val cfg = Protocol.parseConfig(p.payload)
                    val cur = decoder.config
                    if (cur == null || cur.width != cfg.width || cur.height != cfg.height || !cur.raw.contentEquals(cfg.raw)) {
                        decoder.configure(cfg)
                    }
                    waitKey = true
                }

                Protocol.TYPE_FRAME -> {
                    bytes += p.payload.size
                    if (decoder.config == null) continue
                    if (waitKey && !p.isKeyframe) continue
                    waitKey = false
                    if (decoder.queue(p.payload, p.ptsUs, p.isKeyframe)) {
                        frames++
                    } else {
                        // Image perdue : les suivantes en dépendent, on redemande une image clé.
                        waitKey = true
                        requestKeyframe(out)
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastStats >= 1000) {
                val dt = (now - lastStats) / 1000f
                val cfg = decoder.config
                listener.onStats(
                    (frames / dt).toInt(),
                    (bytes * 8 / 1000 / dt).toInt(),
                    if (cfg != null) "${cfg.width}x${cfg.height}" else "-",
                )
                frames = 0; bytes = 0; lastStats = now
            }
        }
    }

    private fun requestKeyframe(out: OutputStream) {
        try {
            synchronized(out) {
                Protocol.writePacket(
                    out, Protocol.newHeaderBuffer(),
                    Protocol.Packet(Protocol.TYPE_REQUEST_KEYFRAME, 0, 0, ByteArray(0)),
                )
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyframe", e)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        worker?.interrupt()
        worker?.join(1500)
        worker = null
    }

    companion object {
        private const val TAG = "StreamReceiver"
    }
}
