package com.tiboard.quest3replika.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import java.net.NetworkInterface

/**
 * Liaison directe tablette <-> casque par Wi-Fi Direct, sans box ni point d'accès mobile.
 *  - Tablette (récepteur) : crée le groupe Wi-Fi Direct (elle en est le « propriétaire », adresse 192.168.49.1).
 *  - Casque (émetteur) : cherche ce groupe et s'y connecte. Au premier lien, la tablette demande d'accepter.
 * Le lien reste actif au niveau du système, même quand l'appli du casque se ferme (mode discret).
 */
object WifiDirect {
    /** Adresse habituelle du propriétaire du groupe Wi-Fi Direct (la tablette). */
    const val GROUP_OWNER_IP = "192.168.49.1"

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private var host = false
    private var connecting = false

    @Volatile var status: String? = null
        private set
    var listener: ((String) -> Unit)? = null
    /** Appelé avec (nom, adresse) de la tablette choisie, pour s'en souvenir. */
    var onPeerChosen: ((String, String) -> Unit)? = null
    /** Appelé quand plusieurs tablettes sont visibles et qu'aucune n'est mémorisée : l'utilisateur choisit. */
    var onPeers: ((List<WifiP2pDevice>) -> Unit)? = null
    private var preferredAddress: String? = null

    /** Fréquences (MHz) des canaux 5 GHz sans radar : 36, 40, 44, 48. */
    val CHANNELS = intArrayOf(5180, 5200, 5220, 5240)
    val CHANNEL_NUMBERS = intArrayOf(36, 40, 44, 48)

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun neededPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(neededPermission()) == PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: Activity, code: Int) {
        activity.requestPermissions(arrayOf(neededPermission()), code)
    }

    private fun say(text: String) {
        status = text
        listener?.invoke(text)
    }

    private fun init(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx
        if (manager == null) manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val m = manager ?: return false
        if (channel == null) channel = m.initialize(ctx, Looper.getMainLooper(), null)
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = onEvent(i.action)
            }
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, f)
        }
        return channel != null
    }

    // ---------------- Tablette : crée le groupe ----------------

    @SuppressLint("MissingPermission")
    fun startHost(context: Context, channelIndex: Int = 0) {
        if (!isSupported(context)) return say("Wi-Fi Direct non disponible sur cet appareil.")
        if (!init(context)) return say("Wi-Fi Direct indisponible.")
        host = true
        val m = manager!!
        val ch = channel!!
        say("Création du lien direct…")
        val plain = {
            m.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = refreshGroup()
                override fun onFailure(reason: Int) {
                    // « Occupé » : un groupe existe déjà, on s'y raccroche.
                    if (reason == WifiP2pManager.BUSY) refreshGroup() else say("Échec du lien direct (code $reason). Activez le Wi-Fi et réessayez.")
                }
            })
        }
        // Canal 5 GHz propre à cette tablette : deux liens dans la même pièce ne se gênent plus.
        val create = {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val chars = "abcdefghjkmnpqrstuvwxyz23456789"
                    val pass = (1..12).map { chars.random() }.joinToString("")
                    val cfg = WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-QR-" + (1..4).map { chars.random() }.joinToString(""))
                        .setPassphrase(pass)
                        .setGroupOperatingFrequency(CHANNELS[channelIndex.coerceIn(0, 3)])
                        .build()
                    m.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = refreshGroup()
                        override fun onFailure(reason: Int) { plain() }
                    })
                } catch (e: Exception) {
                    plain()
                }
            } else {
                plain()
            }
        }
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create()
            override fun onFailure(reason: Int) = create()
        })
    }

    @SuppressLint("MissingPermission")
    private fun refreshGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestGroupInfo(ch) { g ->
            if (g == null) {
                say("En attente du lien direct…")
            } else {
                val n = g.clientList.size
                say(
                    if (n == 0) "Lien direct prêt (« ${g.networkName} »). Lancez la connexion depuis le casque, puis acceptez l'invitation ici."
                    else "Casque connecté en lien direct ($n appareil). Lancement de la réception…"
                )
            }
        }
    }

    // ---------------- Casque : rejoint le groupe ----------------

    @SuppressLint("MissingPermission")
    fun startClient(context: Context, preferredAddress: String?) {
        if (!isSupported(context)) return say("Wi-Fi Direct non disponible sur cet appareil.")
        if (!init(context)) return say("Wi-Fi Direct indisponible.")
        host = false
        connecting = false
        this.preferredAddress = preferredAddress
        say("Recherche de la tablette…")
        manager!!.discoverPeers(channel!!, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) = say("Recherche impossible (code $reason). Wi-Fi activé ?")
        })
    }

    @SuppressLint("MissingPermission")
    private fun onEvent(action: String?) {
        val m = manager ?: return
        val ch = channel ?: return
        when (action) {
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (host) refreshGroup() else if (!connecting) m.requestPeers(ch) { list -> choosePeer(list.deviceList.toList()) }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                m.requestConnectionInfo(ch) { info ->
                    if (host) {
                        refreshGroup()
                    } else if (info != null && info.groupFormed && !info.isGroupOwner) {
                        val owner = info.groupOwnerAddress?.hostAddress ?: GROUP_OWNER_IP
                        connecting = false
                        say("Connecté à la tablette en lien direct ($owner). Vous pouvez lancer la diffusion.")
                    } else if (!host && connecting) {
                        // connexion en cours ou refusée : on laisse le statut courant
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun choosePeer(devices: List<WifiP2pDevice>) {
        val owners = devices.filter { it.isGroupOwner }
        if (owners.isEmpty()) return
        val dev = owners.firstOrNull { it.deviceAddress == preferredAddress }
            ?: owners.singleOrNull()
        if (dev == null) {
            say("Plusieurs tablettes trouvées : touchez celle à utiliser.")
            onPeers?.invoke(owners)
            return
        }
        connectTo(dev)
    }

    /** Se connecte à la tablette choisie. */
    @SuppressLint("MissingPermission")
    fun connectTo(dev: WifiP2pDevice) {
        connecting = true
        say("Connexion à « ${dev.deviceName} »… (acceptez l'invitation sur la tablette)")
        val cfg = WifiP2pConfig().apply {
            deviceAddress = dev.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        manager!!.connect(channel!!, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                connecting = false
                say("Connexion refusée (code $reason). Réessayez.")
            }
        })
        onPeerChosen?.invoke(dev.deviceName, dev.deviceAddress)
    }

    /** Arrête la recherche ; le lien déjà établi reste actif. */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!host) m.stopPeerDiscovery(ch, null)
    }

    /** Coupe le lien direct. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        val m = manager ?: return
        val ch = channel ?: return
        m.cancelConnect(ch, null)
        m.stopPeerDiscovery(ch, null)
        m.removeGroup(ch, null)
        connecting = false
        say("Lien direct coupé.")
    }

    /** Adresses de diffusion des interfaces Wi-Fi Direct (p2p-*), pour la balise du casque. */
    fun broadcastTargets(): List<java.net.InetAddress> {
        val out = ArrayList<java.net.InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.name.startsWith("p2p")) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            }
            out += java.net.InetAddress.getByName(GROUP_OWNER_IP)
        } catch (_: Exception) {
        }
        return out
    }
}
