package ua.home.smstg.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite schema. All times are epoch milliseconds, booleans are 0/1.
 * Only the repositories in this package touch SQL.
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        // Lets the SMS receiver write while the service threads read.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE settings (
                id            INTEGER PRIMARY KEY CHECK (id = 1),
                bot_token     TEXT,
                chat_id       INTEGER,
                is_enabled    INTEGER NOT NULL DEFAULT 0,
                update_offset INTEGER NOT NULL DEFAULT 0,
                perf_mode     TEXT    NOT NULL DEFAULT 'BALANCED',
                language      TEXT
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO settings (id) VALUES (1)")

        db.execSQL(
            """
            CREATE TABLE messages (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                sender     TEXT    NOT NULL,
                text       TEXT    NOT NULL,
                target     TEXT    NOT NULL,
                sms_time   INTEGER NOT NULL,
                is_sent    INTEGER NOT NULL DEFAULT 0,
                tries      INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_pending ON messages (is_sent, tries)")

        db.execSQL(
            """
            CREATE TABLE logs (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                level      TEXT    NOT NULL,
                text       TEXT    NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_logs_created ON logs (created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Numbered steps, applied in order.
        if (oldVersion < 2) db.execSQL("ALTER TABLE settings ADD COLUMN language TEXT") // 2.4: app language
    }

    private companion object {
        const val NAME = "smstg.db"
        const val VERSION = 2
    }
}
