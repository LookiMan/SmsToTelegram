package ua.home.smstg.data

import android.content.ContentValues
import android.util.Log
import ua.home.smstg.model.LogEntry
import ua.home.smstg.model.LogLevel

/** The `logs` table. Every entry is also mirrored to logcat (tag "SmsTg"). */
class LogRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun info(text: String) = add(LogLevel.INFO, text)
    fun warning(text: String) = add(LogLevel.WARNING, text)
    fun error(text: String) = add(LogLevel.ERROR, text)

    private fun add(level: LogLevel, text: String) {
        val priority = when (level) {
            LogLevel.INFO -> Log.INFO
            LogLevel.WARNING -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, "SmsTg", text)
        try {
            val values = ContentValues().apply {
                put("created_at", clock())
                put("level", level.name)
                put("text", text)
            }
            db.writableDatabase.insert("logs", null, values)
        } catch (e: Exception) {
            // Logging must never break message delivery.
            Log.e("SmsTg", "Cannot write log entry", e)
        }
    }

    /** Newest first; [level] = null means all levels. */
    fun recent(limit: Int, level: LogLevel? = null): List<LogEntry> {
        val where = if (level == null) "" else "WHERE level = ?"
        val args = listOfNotNull(level?.name, limit.toString()).toTypedArray()
        return db.readableDatabase.rawQuery(
            "SELECT id, created_at, level, text FROM logs $where ORDER BY id DESC LIMIT ?",
            args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        LogEntry(
                            id = c.getLong(0),
                            createdAt = c.getLong(1),
                            level = runCatching { LogLevel.valueOf(c.getString(2)) }.getOrDefault(LogLevel.INFO),
                            text = c.getString(3),
                        )
                    )
                }
            }
        }
    }

    fun count(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM logs", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Deletes every entry and leaves one INFO entry saying when and how many were cleared. */
    fun clearAll() {
        val deleted = db.writableDatabase.delete("logs", null, null)
        info("Logs cleared ($deleted entries deleted)") // its own time shows when
    }

    /** Deletes entries older than [cutoff] (epoch millis). Returns how many were deleted. */
    fun deleteOlderThan(cutoff: Long): Int =
        db.writableDatabase.delete("logs", "created_at < ?", arrayOf(cutoff.toString()))
}
