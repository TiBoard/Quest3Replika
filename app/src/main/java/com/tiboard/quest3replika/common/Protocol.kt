package com.tiboard.quest3replika.common

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocole binaire minimaliste, sur une connexion TCP (TCP_NODELAY).
 *
 * À la connexion, l'émetteur envoie une poignée de main : MAGIC (int32) + VERSION (int32).
 * Ensuite, chaque paquet = en-tête de 14 octets (big-endian) suivi de `length` octets :
 *
 *   type   : 1 octet  (TYPE_*)
 *   flags  : 1 octet  (FLAG_*)
 *   length : 4 octets (taille de la charge utile)
 *   ptsUs  : 8 octets (horodatage de présentation, microsecondes)
 *
 * Émetteur -> récepteur :
 *   TYPE_CONFIG : largeur (int32) + hauteur (int32) + SPS/PPS H.264 au format Annex-B
 *   TYPE_FRAME  : une unité d'accès H.264 Annex-B (FLAG_KEYFRAME si IDR)
 * Récepteur -> émetteur :
 *   TYPE_REQUEST_KEYFRAME : demande d'image clé (aucune charge utile)
 */
object Protocol {
    const val SERVICE_TYPE = "_questmirror._tcp."
    const val SERVICE_NAME_PREFIX = "Quest3Replika"
    const val DEFAULT_PORT = 47800

    const val MAGIC = 0x514D3031 // "QM01"
    const val VERSION = 1

    const val TYPE_CONFIG: Byte = 1
    const val TYPE_FRAME: Byte = 2
    const val TYPE_REQUEST_KEYFRAME: Byte = 10

    const val FLAG_KEYFRAME: Int = 1

    const val HEADER_SIZE = 14
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    const val MIME = "video/avc"
    const val FPS = 30

    data class Packet(val type: Byte, val flags: Int, val ptsUs: Long, val payload: ByteArray) {
        val isKeyframe get() = (flags and FLAG_KEYFRAME) != 0
    }

    fun writeHandshake(out: OutputStream) {
        val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        b.putInt(MAGIC).putInt(VERSION)
        out.write(b.array())
        out.flush()
    }

    fun readHandshake(input: DataInputStream) {
        val magic = input.readInt()
        val version = input.readInt()
        if (magic != MAGIC) throw IOException("Flux inconnu (magic=%08x)".format(magic))
        if (version != VERSION) throw IOException("Version de protocole incompatible : $version")
    }

    fun writePacket(out: OutputStream, header: ByteBuffer, p: Packet) {
        header.clear()
        header.put(p.type).put(p.flags.toByte()).putInt(p.payload.size).putLong(p.ptsUs)
        out.write(header.array(), 0, HEADER_SIZE)
        if (p.payload.isNotEmpty()) out.write(p.payload)
    }

    fun readPacket(input: DataInputStream, header: ByteArray): Packet {
        input.readFully(header, 0, HEADER_SIZE)
        val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val type = b.get()
        val flags = b.get().toInt() and 0xFF
        val length = b.getInt()
        val pts = b.getLong()
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("Taille de paquet invalide : $length")
        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return Packet(type, flags, pts, payload)
    }

    fun newHeaderBuffer(): ByteBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)

    fun configPayload(width: Int, height: Int, csd: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + csd.size).order(ByteOrder.BIG_ENDIAN)
        b.putInt(width).putInt(height).put(csd)
        return b.array()
    }

    data class VideoConfig(val width: Int, val height: Int, val sps: ByteArray?, val pps: ByteArray?, val raw: ByteArray)

    fun parseConfig(payload: ByteArray): VideoConfig {
        if (payload.size < 8) throw EOFException("CONFIG tronqué")
        val b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val w = b.getInt()
        val h = b.getInt()
        val csd = payload.copyOfRange(8, payload.size)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexB(csd)) {
            if (nal.size <= 4) continue
            // L'en-tête NAL suit le code de démarrage (00 00 01 ou 00 00 00 01).
            val start = if (nal[2].toInt() == 1) 3 else 4
            when (nal[start].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        return VideoConfig(w, h, sps, pps, csd)
    }

    /** Découpe un flux Annex-B en NAL units, en conservant leur code de démarrage. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts.add(i); i += 3; continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts.add(i); i += 4; continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(data)
        return starts.mapIndexed { idx, s ->
            val e = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            data.copyOfRange(s, e)
        }
    }
}
