package com.vadymsidorov.walky.telemetry

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vadymsidorov.walky.core.telemetry.Logger
import com.vadymsidorov.walky.core.telemetry.WifiAccess
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * Robot Relay over Wi-Fi: the TLS listener, the key Relay handed over USB, and the mDNS
 * beacon Relay browses for. Relay pins [fingerprint], learned from a USB hello, so the
 * self-signed certificate is the phone's identity: it lives in AndroidKeyStore and is kept
 * for the life of the install.
 */
class RelayWifi(context: Context, private val logger: () -> Logger) : WifiAccess {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    private val keyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    private val identity: Pair<PrivateKey, X509Certificate> by lazy {
        if (!keyStore.containsAlias(ALIAS)) generate()
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        entry.privateKey to entry.certificate as X509Certificate
    }

    override val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(identity.second.encoded).joinToString("") { "%02x".format(it) }
    }

    override fun listen(): ServerSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(KeyManager()), null, null)
        val socket = context.serverSocketFactory.createServerSocket(PORT) as SSLServerSocket
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        return socket
    }

    override fun key(): ByteArray? =
        preferences.getString(KEY, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    override fun storeKey(key: ByteArray) {
        if (key.contentEquals(key())) return
        preferences.edit().putString(KEY, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        logger().info("Robot Relay paired for Wi-Fi")
    }

    /** Advertises `_walky._tcp` on [port] until [stop]. Main thread only. */
    fun start(port: Int?) {
        if (registration != null || port == null) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = logger().info("Advertising ${info.serviceName} on port $port")
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = logger().warn("mDNS registration failed: $error")
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                registration = null
                logger().error("mDNS registration failed", it)
            }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private fun generate() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS 1.2 signs a digest it computed itself (NONE); TLS 1.3 asks for SHA-256.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=Walky"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0))
                .setCertificateNotAfter(Date(4_102_444_800_000)) // 2100; Relay pins the certificate, not its dates.
                .build()
        )
        generator.generateKeyPair()
    }

    /** Serves the one keystore identity to every handshake. */
    private inner class KeyManager : X509ExtendedKeyManager() {
        // Asked once per key type the cipher suites allow; the identity is EC only.
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias(keyType)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias(keyType)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = alias(keyType)?.let { arrayOf(it) }
        override fun getCertificateChain(alias: String?) = arrayOf(identity.second)
        override fun getPrivateKey(alias: String?) = identity.first
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        private fun alias(keyType: String?) = ALIAS.takeIf { keyType == null || keyType.startsWith("EC") }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "relay-wifi"
        // Also named in backup_rules.xml and data_extraction_rules.xml: the key stays on this phone.
        const val PREFERENCES = "relay_link"
        const val KEY = "key"
        const val PORT = 7778
        const val SERVICE_NAME = "Walky"
        const val SERVICE_TYPE = "_walky._tcp"
    }
}
