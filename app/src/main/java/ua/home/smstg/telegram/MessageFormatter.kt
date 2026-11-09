package ua.home.smstg.telegram

import ua.home.smstg.model.StoredMessage
import ua.home.smstg.model.Target
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a stored message for Telegram:
 *
 *   <b>sender</b>
 *
 *   <i>SIM1 · 08.11 19:04</i>
 *
 *   text
 */
object MessageFormatter {

    /** Telegram's limit is 4096 characters; leave room for the header and escaping. */
    private const val MAX_TEXT = 3500

    /** [html] for parse_mode=HTML, [plain] as a fallback if Telegram rejects the HTML. */
    data class Formatted(val html: String, val plain: String)

    fun format(m: StoredMessage): Formatted {
        val text = if (m.text.length > MAX_TEXT) m.text.take(MAX_TEXT) + "…" else m.text
        val meta = when (m.target) {
            Target.TEST -> "SMS to Telegram · " + time(m.smsTime, "dd.MM HH:mm:ss")
            Target.SIM1 -> "SIM1 · " + time(m.smsTime, "dd.MM HH:mm")
            Target.SIM2 -> "SIM2 · " + time(m.smsTime, "dd.MM HH:mm")
            Target.UNKNOWN -> "SIM · " + time(m.smsTime, "dd.MM HH:mm")
        }
        val body = if (m.target == Target.TEST) "<i>${escape(text)}</i>" else escape(text)

        return Formatted(
            html = "<b>${escape(m.sender)}</b>\n\n<i>${escape(meta)}</i>\n\n$body",
            plain = "${m.sender}\n\n$meta\n\n$text",
        )
    }

    /** Telegram HTML needs only &, < and > escaped. */
    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun time(millis: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
