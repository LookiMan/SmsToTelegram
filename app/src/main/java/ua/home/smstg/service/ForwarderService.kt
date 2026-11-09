package ua.home.smstg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import ua.home.smstg.R
import ua.home.smstg.core.WakeSignal
import ua.home.smstg.graph
import ua.home.smstg.network.NetworkWatcher
import ua.home.smstg.sms.RuntimeSmsReceiver
import ua.home.smstg.telegram.PollResult
import ua.home.smstg.ui.MainActivity
import kotlin.concurrent.thread

/**
 * Foreground service. Only orchestrates; the work is done by the graph's components.
 *
 * Two threads:
 *  - sender: delivers the queue immediately on events (new SMS, network back, chat linked,
 *    mode changed) and runs two timers whose intervals depend on the performance mode:
 *    a Telegram reachability check and a resend of leftovers;
 *  - bot poll: long-polls Telegram for commands.
 */
class ForwarderService : Service() {

    companion object {
        private const val POLL_TIMEOUT_SEC = 30
        private const val POLL_RETRY_MS = 30_000L
        private const val LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val PRUNE_INTERVAL_MS = 24L * 60 * 60 * 1000

        private const val CHANNEL = "forwarder"
        private const val NOTIF_ID = 1

        /** Re-posts the ongoing notification, e.g. after the app language changed. */
        fun refreshNotification(c: Context) {
            if (!c.graph.status.isRunning) return
            c.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(c))
        }

        /** The ongoing notification, in the app language. Also (re)creates the channel. */
        private fun buildNotification(c: Context): Notification {
            val res = c.graph.strings()
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, res.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Builder(c, CHANNEL)
                .setContentTitle(res.getString(R.string.app_name))
                .setContentText(res.getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }

        /** Starts the service, or wakes the sender if it is already running. */
        fun start(c: Context) {
            c.startForegroundService(Intent(c, ForwarderService::class.java))
        }
    }

    @Volatile private var running = false
    /** Belongs to this service instance only; the dispatcher checks it to stop a pass. */
    private val isActive: () -> Boolean = { running }
    private val pollPause = WakeSignal()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var smsReceiver: RuntimeSmsReceiver
    private lateinit var networkWatcher: NetworkWatcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        acquireLocks()

        running = true
        graph.status.isRunning = true

        smsReceiver = RuntimeSmsReceiver(graph.smsInbox).also { it.register(this) }
        networkWatcher = NetworkWatcher(
            this,
            onNetworkAvailable = {
                // A network appeared: send what waits and resume bot polling right away.
                // Reachability itself is confirmed by the next request that succeeds.
                graph.senderSignal.raise()
                pollPause.raise()
            },
            onNetworkLost = { graph.connection.report(false, "network lost") },
        ).also { it.start() }

        // Telegram is back (e.g. a message just went through): resume bot polling at once.
        graph.connection.onRestored = { pollPause.raise() }

        graph.logs.info("Service started")
        thread(name = "sender", isDaemon = true) { senderLoop() }
        thread(name = "bot-poll", isDaemon = true) { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!graph.settings.get().isEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        graph.senderSignal.raise()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        graph.connection.onRestored = null
        graph.status.isRunning = false
        graph.senderSignal.raise()
        pollPause.raise()
        graph.api.abortPoll()
        smsReceiver.unregister(this)
        networkWatcher.stop()
        releaseLocks()
        graph.logs.info("Service stopped")
        super.onDestroy()
    }

    private fun senderLoop() {
        val start = System.currentTimeMillis()
        var mode = graph.settings.get().perfMode
        var nextCheck = start + mode.networkCheckMs
        var nextResend = start + mode.resendMs
        var lastPrune = 0L

        checkConnection()
        graph.dispatcher.sendPending(isActive) // anything left from before

        while (running) {
            val wait = minOf(nextCheck, nextResend) - System.currentTimeMillis()
            val woken = graph.senderSignal.await(wait)
            if (!running) break

            // An event: send right away, no extra check (speed matters most).
            if (woken) graph.dispatcher.sendPending(isActive)

            // The mode may have changed: bring the timers forward if the new intervals are shorter.
            mode = graph.settings.get().perfMode
            var now = System.currentTimeMillis()
            nextCheck = minOf(nextCheck, now + mode.networkCheckMs)
            nextResend = minOf(nextResend, now + mode.resendMs)

            if (now >= nextCheck) {
                val wasDown = graph.connection.isReachable == false
                // Telegram just became reachable again: send what waited.
                if (checkConnection() && wasDown) graph.dispatcher.sendPending(isActive)
                now = System.currentTimeMillis()
                nextCheck = now + mode.networkCheckMs
            }

            if (now >= nextResend) {
                // Resend leftovers unless we already know Telegram is unreachable;
                // the check timer or the network watcher takes over then.
                if (graph.connection.isReachable != false) graph.dispatcher.sendPending(isActive)
                now = System.currentTimeMillis()
                nextResend = now + mode.resendMs
            }

            if (now - lastPrune > PRUNE_INTERVAL_MS) {
                graph.logs.deleteOlderThan(now - LOG_RETENTION_MS)
                lastPrune = now
            }
        }
    }

    private fun checkConnection(): Boolean {
        val ok = graph.connectivity.isTelegramReachable()
        graph.connection.report(ok, "Telegram unreachable")
        return ok
    }

    private fun pollLoop() {
        while (running) {
            val s = graph.settings.get()
            val token = s.botToken
            if (token == null) {
                pollPause.await(POLL_RETRY_MS)
                continue
            }
            val result = graph.api.getUpdates(token, s.updateOffset, POLL_TIMEOUT_SEC)
            if (!running) break // the poll was aborted because the service stops
            when (result) {
                is PollResult.NetworkError -> {
                    graph.status.pollError = result.reason
                    graph.connection.report(false, result.reason)
                    pollPause.await(POLL_RETRY_MS)
                }
                is PollResult.HttpError -> {
                    graph.status.pollError = "HTTP ${result.code} ${result.description}".trim()
                    graph.connection.report(true) // Telegram answered
                    pollPause.await(POLL_RETRY_MS)
                }
                is PollResult.Updates -> {
                    graph.status.pollError = null
                    graph.status.lastPollAt = System.currentTimeMillis()
                    graph.connection.report(true)
                    for (u in result.updates) {
                        graph.settings.saveOffset(u.updateId + 1)
                        graph.commands.handle(token, u)
                    }
                }
            }
        }
    }

    /** The phone is always on the charger, so CPU and Wi-Fi are kept awake while the screen is locked. */
    private fun acquireLocks() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smstg:service")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "smstg:wifi")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (e: Exception) { }
        try { wifiLock?.release() } catch (e: Exception) { }
    }

    private fun startAsForeground() {
        val n = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}

/** Starts the service after a reboot (unless it was stopped with the Stop button). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = context.graph.settings.get()
        if (s.isEnabled && s.botToken != null) {
            try {
                ForwarderService.start(context)
            } catch (e: Exception) {
                context.graph.logs.error("Start after boot failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
