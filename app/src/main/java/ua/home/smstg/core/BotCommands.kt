package ua.home.smstg.core

import android.content.res.Resources
import ua.home.smstg.R
import ua.home.smstg.data.LogRepository
import ua.home.smstg.data.SettingsRepository
import ua.home.smstg.model.PerfMode
import ua.home.smstg.telegram.BotUpdate
import ua.home.smstg.telegram.TelegramApi
import java.util.Locale

/**
 * Bot commands. The bot binds to the first private chat that sends /start
 * and ignores every other chat completely.
 */
class BotCommands(
    private val settings: SettingsRepository,
    private val api: TelegramApi,
    private val logs: LogRepository,
    /** Resources in the current app language (read on every reply, so a language switch applies at once). */
    private val strings: () -> Resources,
    private val onChatLinked: () -> Unit,
    private val changeMode: (PerfMode) -> Unit,
) {

    fun handle(token: String, update: BotUpdate) {
        val chatId = update.chatId ?: return
        val command = update.text.trim().substringBefore(' ').substringBefore('@').lowercase(Locale.ROOT)
        val linked = settings.get().chatId

        if (linked == null) {
            if (command == "/start" && update.chatType == "private") {
                settings.saveChatId(chatId)
                api.sendMessage(token, chatId, strings().getString(R.string.bot_linked), html = false)
                logs.info("Chat linked")
                onChatLinked() // send anything that waited for a chat
            }
            return
        }
        if (chatId != linked) return

        val res = strings()
        val reply = when (command) {
            "/ping" -> "pong"
            "/start" -> res.getString(R.string.bot_already_linked)
            "/perf" -> perfMenu(res, settings.get().perfMode)
            else -> {
                val mode = PerfMode.fromLabel(command.removePrefix("/")) ?: return
                changeMode(mode)
                res.getString(R.string.bot_mode_changed, mode.label, describe(res, mode))
            }
        }
        api.sendMessage(token, chatId, reply, html = false)
    }

    // Mode names stay English here: they are the commands themselves.
    private fun perfMenu(res: Resources, current: PerfMode): String = buildString {
        append(res.getString(R.string.bot_mode_current, current.label)).append("\n\n")
        PerfMode.entries.forEach { append("/${it.label} — ${describe(res, it)}\n") }
        append("\n").append(res.getString(R.string.bot_mode_footer))
    }

    private fun describe(res: Resources, mode: PerfMode): String =
        res.getString(R.string.bot_mode_describe, (mode.networkCheckMs / 1000).toInt(), (mode.resendMs / 1000).toInt())
}
