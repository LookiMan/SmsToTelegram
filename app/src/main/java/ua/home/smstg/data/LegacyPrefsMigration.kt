package ua.home.smstg.data

import android.content.Context

/**
 * One-time move of the bot token, chat and state from the SharedPreferences used by
 * versions up to 1.4 into the database, so updating the app needs no new setup.
 */
object LegacyPrefsMigration {

    fun run(context: Context, settings: SettingsRepository) {
        val prefs = context.getSharedPreferences("main", Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return

        val token = prefs.getString("token", null)
        if (!token.isNullOrEmpty() && settings.get().botToken == null) {
            settings.saveToken(token)
            prefs.getLong("chatId", 0L).takeIf { it != 0L }?.let(settings::saveChatId)
            settings.saveOffset(prefs.getLong("offset", 0L))
            settings.setEnabled(prefs.getBoolean("enabled", false))
        }
        prefs.edit().clear().commit()
        context.getSharedPreferences("queue", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
