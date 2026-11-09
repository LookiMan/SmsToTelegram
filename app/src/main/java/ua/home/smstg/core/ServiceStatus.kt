package ua.home.smstg.core

/**
 * Live, in-memory state of the running service, shown on the main screen.
 * Not stored: it only makes sense while the process is alive.
 * Connection state lives in [ConnectionMonitor].
 */
class ServiceStatus {
    @Volatile var isRunning = false

    /** Time of the last successful getUpdates and the last poll error, if any. */
    @Volatile var lastPollAt = 0L
    @Volatile var pollError: String? = null
}
