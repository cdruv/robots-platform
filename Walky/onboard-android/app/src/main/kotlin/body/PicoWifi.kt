package com.vadymsidorov.walky.body

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import com.vadymsidorov.walky.core.body.BodyProtocol
import com.vadymsidorov.walky.core.telemetry.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Joins the Pico's own Wi-Fi network as a second, local-only connection; the phone's normal
 * Wi-Fi keeps carrying the internet. Sockets to the Pico come from [socket] and are bound to
 * that network only. Nothing here redirects the rest of the process.
 *
 * The network is held while the app is in the foreground ([resume] to [pause]) and asked for
 * again, with backoff, whenever the Pico is off or out of range. Android asks the user to
 * approve a pairing once. That dialog takes the foreground from the app, so this class
 * outlives a session and keeps an unapproved request alive across [pause].
 */
class PicoWifi(context: Context, private val logger: () -> Logger) {
    enum class Status { Unpaired, Declined, Searching, Joined }

    private data class Pairing(val ssid: String, val password: String, val approved: Boolean)
    private data class Route(val network: Network, val gateway: InetAddress)

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    // Every field below is touched on the main thread only.
    private val main = Handler(Looper.getMainLooper())
    private var pairing: Pairing? = load()
    private var foreground = false
    private var active: ConnectivityManager.NetworkCallback? = null
    private var backoffMs = MIN_BACKOFF_MS
    private val retry = Runnable { request() }
    private val releaseIfBackground = Runnable { if (!foreground) release() }

    private val route = MutableStateFlow<Route?>(null)
    private val current = MutableStateFlow(if (pairing == null) Status.Unpaired else Status.Searching)
    val status: StateFlow<Status> = current.asStateFlow()
    val ssid: String? get() = pairing?.ssid

    /**
     * Stores the Pico's network name and password. Robot Relay repeats this every time it
     * connects, so a repeat of the stored pairing also retries a request the user declined.
     */
    fun pair(ssid: String, password: String) {
        main.post {
            val known = pairing
            if (known?.ssid == ssid && known.password == password) {
                if (current.value == Status.Declined && foreground) request()
                return@post
            }
            store(Pairing(ssid, password, approved = false))
            logger().info("Paired with Pico network $ssid")
            release()
            if (foreground) request()
        }
    }

    fun resume() {
        foreground = true
        main.removeCallbacks(releaseIfBackground)
        if (active == null && current.value != Status.Declined) request()
    }

    fun pause() {
        foreground = false
        // The approval dialog is what paused the app; releasing the request would dismiss it.
        if (pairing?.approved != false) release()
    }

    /** A socket connected to the Pico. Waits for its network; throws [IOException] if the connect fails. */
    suspend fun socket(): Socket {
        val (network, gateway) = route.filterNotNull().first()
        return withContext(Dispatchers.IO) {
            val socket = network.socketFactory.createSocket()
            try {
                socket.connect(InetSocketAddress(gateway, BodyProtocol.PORT), CONNECT_TIMEOUT_MS)
                socket
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }
    }

    private fun request() {
        release()
        val pairing = pairing ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Without internet the request is local-only, which is what lets the phone keep its normal Wi-Fi.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(
                WifiNetworkSpecifier.Builder().setSsid(pairing.ssid).setWpa2Passphrase(pairing.password).build()
            )
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (active !== this) return
                backoffMs = MIN_BACKOFF_MS
                if (!pairing.approved) {
                    store(pairing.copy(approved = true))
                    // Approved from the dialog. If the app does not come back, let the network go.
                    main.postDelayed(releaseIfBackground, APPROVAL_GRACE_MS)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (active !== this) return
                val gateway = gateway(properties) ?: return
                if (route.value == null) logger().info("Joined Pico network ${pairing.ssid}, gateway ${gateway.hostAddress}")
                route.value = Route(network, gateway)
                current.value = Status.Joined
            }

            override fun onLost(network: Network) = ended(this, "lost")

            // The user declined, or the Pico was not found in the system's own search window.
            override fun onUnavailable() = ended(this, "not found")
        }
        active = callback
        current.value = Status.Searching
        try {
            connectivity.requestNetwork(request, callback, main)
        } catch (e: RuntimeException) {
            logger().error("Pico network request failed", e)
            ended(callback, "request failed")
        }
    }

    private fun ended(callback: ConnectivityManager.NetworkCallback, reason: String) {
        if (active !== callback) return
        val joined = route.value != null
        release()
        val pairing = pairing ?: return
        if (joined) logger().warn("Pico network ${pairing.ssid} $reason")
        if (!pairing.approved) {
            current.value = Status.Declined
            logger().warn("Pico network ${pairing.ssid} was not approved; connect Robot Relay to ask again")
            return
        }
        current.value = Status.Searching
        if (foreground) {
            main.postDelayed(retry, backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun release() {
        main.removeCallbacks(retry)
        main.removeCallbacks(releaseIfBackground)
        active?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        active = null
        route.value = null
        current.value = if (pairing == null) Status.Unpaired else Status.Searching
    }

    private fun gateway(properties: LinkProperties): InetAddress? =
        properties.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && it.gateway?.isAnyLocalAddress == false }?.gateway
            ?: properties.dhcpServerAddress

    private fun load(): Pairing? {
        val ssid = preferences.getString(KEY_SSID, null) ?: return null
        val password = preferences.getString(KEY_PASSWORD, null) ?: return null
        return Pairing(ssid, password, preferences.getBoolean(KEY_APPROVED, false))
    }

    private fun store(value: Pairing) {
        pairing = value
        preferences.edit()
            .putString(KEY_SSID, value.ssid)
            .putString(KEY_PASSWORD, value.password)
            .putBoolean(KEY_APPROVED, value.approved)
            .apply()
    }

    private companion object {
        // Also named in backup_rules.xml and data_extraction_rules.xml: the password stays on this phone.
        const val PREFERENCES = "pico_link"
        const val KEY_SSID = "ssid"
        const val KEY_PASSWORD = "password"
        const val KEY_APPROVED = "approved"
        const val CONNECT_TIMEOUT_MS = 2_000
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 8_000L
        const val APPROVAL_GRACE_MS = 5_000L
    }
}
