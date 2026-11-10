package ua.madeisy.atlantic.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import ua.madeisy.atlantic.core.ApConnector
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Підключає телефон до точки доступу обігрівача (GA-xxxxxxxxxxxx) лише для цього застосунку.
 * Android 10+: WifiNetworkSpecifier — система покаже діалог «Підключитися до GA-…?».
 * Інтернет телефону через цю мережу не йде; запити до 192.168.4.1 відкриваються саме через неї.
 */
class WifiApConnector(context: Context) : ApConnector {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetId: Int = -1

    override fun connect(ssid: String, password: String, timeoutMs: Long): (URL) -> HttpURLConnection {
        disconnect()
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) connectQ(ssid, password, timeoutMs)
        else connectLegacy(ssid, password, timeoutMs)
        return { url -> network.openConnection(url) as HttpURLConnection }
    }

    private fun connectQ(ssid: String, password: String, timeoutMs: Long): Network {
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val latch = CountDownLatch(1)
        var result: Network? = null
        var unavailable = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { result = network; latch.countDown() }
            override fun onUnavailable() { unavailable = true; latch.countDown() }
        }
        callback = cb
        cm.requestNetwork(request, cb, timeoutMs.toInt().coerceAtLeast(10_000))
        latch.await(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
        val net = result
        if (net == null) {
            disconnect()
            throw IOException(if (unavailable) "мережу не знайдено або підключення скасовано" else "тайм-аут підключення")
        }
        return net
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectLegacy(ssid: String, password: String, timeoutMs: Long): Network {
        val wm = app.getSystemService(WifiManager::class.java)
        val conf = WifiConfiguration().apply {
            SSID = "\"" + ssid + "\""
            preSharedKey = "\"" + password + "\""
        }
        legacyNetId = wm.addNetwork(conf)
        if (legacyNetId == -1) throw IOException("не вдалося додати мережу $ssid")
        wm.disconnect()
        wm.enableNetwork(legacyNetId, true)
        wm.reconnect()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = wm.connectionInfo?.ssid?.trim('"')
            if (current == ssid) {
                cm.allNetworks.firstOrNull { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }?.let { return it }
            }
            Thread.sleep(1000)
        }
        throw IOException("тайм-аут підключення до $ssid")
    }

    @Suppress("DEPRECATION")
    override fun disconnect() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        if (legacyNetId != -1) {
            runCatching {
                val wm = app.getSystemService(WifiManager::class.java)
                wm.removeNetwork(legacyNetId)
                wm.reconnect()
            }
            legacyNetId = -1
        }
    }
}
