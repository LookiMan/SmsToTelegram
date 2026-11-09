package ua.home.smstg.model

/**
 * Shared data types. Every layer speaks in these types; no layer exposes
 * its internals (Intent, Cursor, HTTP connection) to the others.
 */

/** Where a message came from: a SIM slot, or the in-app test button. */
enum class Target { SIM1, SIM2, UNKNOWN, TEST }

/**
 * Standard DTO produced by the SMS module and stored as is in the database.
 */
data class IncomingSms(
    val sender: String,
    val text: String,
    val target: Target,
    /** When the operator's SMS centre stamped the message, epoch millis. */
    val smsTime: Long,
)

/** A row of the `messages` table. */
data class StoredMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val target: Target,
    val smsTime: Long,
    val isSent: Boolean,
    /** Failed delivery rounds in which Telegram rejected the message. */
    val tries: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class LogLevel { INFO, WARNING, ERROR }

/** A row of the `logs` table. */
data class LogEntry(
    val id: Long,
    val createdAt: Long,
    val level: LogLevel,
    val text: String,
)

/** Where components write log entries. Implemented by the `logs` table; tests use a fake. */
interface EventLog {
    fun info(text: String)
    fun warning(text: String)
    fun error(text: String)
}

/** The single row of the `settings` table. */
data class Settings(
    val botToken: String?,
    val chatId: Long?,
    val isEnabled: Boolean,
    /** Telegram getUpdates offset: id of the next update to read. */
    val updateOffset: Long,
    val perfMode: PerfMode,
    /** UI and bot language chosen in the app; null = follow the system language. */
    val language: AppLanguage?,
)

/** Languages of the UI and bot replies. Logs are always in English. */
enum class AppLanguage(val tag: String) {
    EN("en"),
    UK("uk");

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * How often the background timers run. Saves battery and traffic in exchange for slower
 * recovery after an outage. New SMS are always sent immediately, in every mode.
 */
enum class PerfMode(
    /** How often to check that Telegram is reachable. */
    val networkCheckMs: Long,
    /** How often to retry unsent messages. */
    val resendMs: Long,
) {
    ENERGY(networkCheckMs = 240_000, resendMs = 240_000),
    BALANCED(networkCheckMs = 120_000, resendMs = 60_000),
    PERFORMANCE(networkCheckMs = 30_000, resendMs = 15_000);

    /** "performance", "balanced", "energy": used in the UI and as bot commands. */
    val label: String get() = name.lowercase()

    companion object {
        val DEFAULT = BALANCED

        fun fromLabel(label: String): PerfMode? = entries.firstOrNull { it.label == label }
    }
}
