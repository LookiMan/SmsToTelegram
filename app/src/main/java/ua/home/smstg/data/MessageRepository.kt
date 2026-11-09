package ua.home.smstg.data

import android.content.ContentValues
import android.database.Cursor
import ua.home.smstg.model.IncomingSms
import ua.home.smstg.model.StoredMessage
import ua.home.smstg.model.Target

/** The `messages` table: every SMS to forward and its delivery state. */
class MessageRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Stores a newly received SMS (or a test message). Returns its id. */
    fun insert(sms: IncomingSms): Long {
        val now = clock()
        val values = ContentValues().apply {
            put("sender", sms.sender)
            put("text", sms.text)
            put("target", sms.target.name)
            put("sms_time", sms.smsTime)
            put("is_sent", 0)
            put("tries", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        return db.writableDatabase.insertOrThrow("messages", null, values)
    }

    /** Oldest unsent message that has not been given up on, or null. */
    fun nextPending(maxTries: Int, excludeIds: Set<Long> = emptySet()): StoredMessage? {
        val exclude = if (excludeIds.isEmpty()) "" else " AND id NOT IN (${excludeIds.joinToString(",")})"
        return db.readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM messages WHERE is_sent = 0 AND tries < ?$exclude ORDER BY id LIMIT 1",
            arrayOf(maxTries.toString())
        ).use { c -> if (c.moveToFirst()) c.toMessage() else null }
    }

    fun countPending(maxTries: Int): Int =
        count("is_sent = 0 AND tries < ?", maxTries)

    /** Messages that Telegram kept rejecting and that are no longer retried. */
    fun countGivenUp(maxTries: Int): Int =
        count("is_sent = 0 AND tries >= ?", maxTries)

    fun markSent(id: Long) {
        db.writableDatabase.execSQL(
            "UPDATE messages SET is_sent = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any>(clock(), id)
        )
    }

    /** Adds one failed round and returns the new number of tries. */
    fun incrementTries(id: Long): Int {
        db.writableDatabase.execSQL(
            "UPDATE messages SET tries = tries + 1, updated_at = ? WHERE id = ?",
            arrayOf<Any>(clock(), id)
        )
        return db.readableDatabase.rawQuery("SELECT tries FROM messages WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun lastReceivedAt(): Long? = maxValue("created_at", "1 = 1")

    fun lastSentAt(): Long? = maxValue("updated_at", "is_sent = 1")

    /** Used when the bot is removed: unsent messages must not go to a different chat later. */
    fun deleteUnsent() {
        db.writableDatabase.delete("messages", "is_sent = 0", null)
    }

    private fun count(where: String, maxTries: Int): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE $where", arrayOf(maxTries.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun maxValue(column: String, where: String): Long? =
        db.readableDatabase.rawQuery("SELECT MAX($column) FROM messages WHERE $where", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private fun Cursor.toMessage() = StoredMessage(
        id = getLong(0),
        sender = getString(1),
        text = getString(2),
        target = runCatching { Target.valueOf(getString(3)) }.getOrDefault(Target.UNKNOWN),
        smsTime = getLong(4),
        isSent = getInt(5) == 1,
        tries = getInt(6),
        createdAt = getLong(7),
        updatedAt = getLong(8),
    )

    private companion object {
        const val COLUMNS = "id, sender, text, target, sms_time, is_sent, tries, created_at, updated_at"
    }
}
