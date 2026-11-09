package ua.home.smstg.core

/**
 * Lets other threads wake a sleeping worker early
 * (new SMS, network is back, chat linked, test message).
 */
class WakeSignal {
    private val lock = Object()
    private var raised = false

    fun raise() = synchronized(lock) {
        raised = true
        lock.notifyAll()
    }

    /** Sleeps up to [ms] or until [raise] is called. Returns true if woken by [raise]. */
    fun await(ms: Long): Boolean = synchronized(lock) {
        if (!raised) lock.wait(ms.coerceAtLeast(1))
        val wasRaised = raised
        raised = false
        wasRaised
    }
}
