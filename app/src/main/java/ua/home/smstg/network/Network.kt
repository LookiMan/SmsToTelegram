package ua.home.smstg.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import ua.home.smstg.telegram.TelegramApi
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Checks whether Telegram is reachable right now. */
class ConnectivityChecker {

    /**
     * A HEAD request to api.telegram.org: a few hundred bytes, no token needed.
     * Any HTTP answer (even a redirect) means Telegram is reachable.
     */
    fun isTelegramReachable(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(TelegramApi.BASE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.responseCode > 0
        } catch (e: IOException) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}

/**
 * Android tells us when a network becomes available (e.g. Wi-Fi is back),
 * so the queue is sent at once instead of waiting for the next timer tick.
 * Costs no traffic and no battery.
 */
class NetworkWatcher(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit,
) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkAvailable()
        override fun onLost(network: Network) = onNetworkLost()
    }

    fun start() {
        try { cm.registerDefaultNetworkCallback(callback) } catch (e: Exception) { }
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { }
    }
}
