package ua.home.smstg.core

import ua.home.smstg.model.EventLog

/**
 * Single source of truth for "can we reach Telegram right now".
 * Every component that talks to Telegram reports what it saw; only changes are logged:
 * WARNING when the connection is lost, INFO when it is back (with the outage duration).
 */
class ConnectionMonitor(
    private val logs: EventLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** null until the first observation. */
    @Volatile var isReachable: Boolean? = null
        private set

    /** When the current state began, epoch millis. */
    @Volatile var since: Long = 0L
        private set

    /** Called (outside the lock) when Telegram becomes reachable again; set by the service. */
    @Volatile var onRestored: (() -> Unit)? = null

    fun report(reachable: Boolean, detail: String = "") {
        if (update(reachable, detail)) onRestored?.invoke()
    }

    /** Returns true when the state changed from unreachable to reachable. */
    @Synchronized
    private fun update(reachable: Boolean, detail: String): Boolean {
        if (isReachable == reachable) return false
        val now = clock()
        val previous = isReachable
        when {
            !reachable -> logs.warning("Offline" + if (detail.isNotEmpty()) ": $detail" else "")
            previous == false -> logs.info("Online again after ${duration(now - since)}")
            // previous == null && reachable: normal start, nothing to log
        }
        isReachable = reachable
        since = now
        return reachable && previous == false
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${s % 3600 / 60}m"
        }
    }
}
