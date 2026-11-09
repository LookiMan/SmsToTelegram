package ua.home.smstg.data

import android.content.ContentValues
import ua.home.smstg.model.AppLanguage
import ua.home.smstg.model.PerfMode
import ua.home.smstg.model.Settings

/** Reads and writes the single row of the `settings` table. */
class SettingsRepository(private val db: AppDatabase) {

    fun get(): Settings =
        db.readableDatabase.rawQuery(
            "SELECT bot_token, chat_id, is_enabled, update_offset, perf_mode, language FROM settings WHERE id = 1", null
        ).use { c ->
            c.moveToFirst()
            Settings(
                botToken = if (c.isNull(0)) null else c.getString(0),
                chatId = if (c.isNull(1)) null else c.getLong(1),
                isEnabled = c.getInt(2) == 1,
                updateOffset = c.getLong(3),
                perfMode = runCatching { PerfMode.valueOf(c.getString(4)) }.getOrDefault(PerfMode.DEFAULT),
                language = AppLanguage.fromTag(if (c.isNull(5)) null else c.getString(5)),
            )
        }

    fun setPerfMode(mode: PerfMode) = update { put("perf_mode", mode.name) }

    fun setLanguage(language: AppLanguage) = update { put("language", language.tag) }

    /** A new token means a new bot: the linked chat and the update offset are reset. */
    fun saveToken(token: String) = update {
        put("bot_token", token)
        putNull("chat_id")
        put("update_offset", 0L)
    }

    fun saveChatId(chatId: Long) = update { put("chat_id", chatId) }

    fun setEnabled(enabled: Boolean) = update { put("is_enabled", if (enabled) 1 else 0) }

    fun saveOffset(offset: Long) = update { put("update_offset", offset) }

    /** Forgets the bot completely and stops forwarding. */
    fun clearBot() = update {
        putNull("bot_token")
        putNull("chat_id")
        put("update_offset", 0L)
        put("is_enabled", 0)
    }

    private inline fun update(block: ContentValues.() -> Unit) {
        db.writableDatabase.update("settings", ContentValues().apply(block), "id = 1", null)
    }
}
