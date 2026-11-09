package ua.home.smstg.telegram

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Result of one sendMessage call. */
sealed interface SendResult {
    object Ok : SendResult

    /** No HTTP response at all: no internet, DNS failure, timeout. */
    data class NetworkError(val reason: String) : SendResult

    /** Telegram answered with an error. */
    data class HttpError(val code: Int, val description: String) : SendResult {
        /** 429 (too many requests) and 5xx are temporary problems on Telegram's side. */
        val isTransient: Boolean get() = code == 429 || code >= 500
    }
}

/** Result of one getUpdates call. */
sealed interface PollResult {
    data class Updates(val updates: List<BotUpdate>) : PollResult

    /** No HTTP response at all: no internet, DNS failure, timeout. */
    data class NetworkError(val reason: String) : PollResult

    /** Telegram answered with an error (wrong token, conflict, ...). */
    data class HttpError(val code: Int, val description: String) : PollResult
}

/** A short, human-readable reason for a network failure (shown in the app and the logs). */
fun IOException.shortReason(): String = when (this) {
    is java.net.UnknownHostException -> "no internet"
    is java.net.SocketTimeoutException -> "timeout"
    is java.net.ConnectException, is java.net.NoRouteToHostException -> "no connection"
    is javax.net.ssl.SSLException -> "secure connection failed"
    else -> "network error"
}

/** One incoming bot message, reduced to what the app needs. */
data class BotUpdate(
    val updateId: Long,
    val chatId: Long?,
    val chatType: String?,
    val text: String,
)

/** Minimal Telegram Bot API client: transport only, no app logic. */
class TelegramApi {

    @Volatile private var pollConn: HttpURLConnection? = null

    /** Sends a message. With [html] = true the text is parsed as Telegram HTML. */
    fun sendMessage(token: String, chatId: Long, text: String, html: Boolean): SendResult {
        var conn: HttpURLConnection? = null
        return try {
            var body = "chat_id=$chatId&disable_web_page_preview=true&text=" + URLEncoder.encode(text, "UTF-8")
            if (html) body += "&parse_mode=HTML"
            conn = open(token, "sendMessage", SEND_READ_TIMEOUT_MS)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 200) SendResult.Ok else SendResult.HttpError(code, errorDescription(conn))
        } catch (e: IOException) {
            SendResult.NetworkError(e.shortReason())
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Long polling: the request waits on the server for up to [timeoutSec] seconds and
     * returns as soon as a new message arrives.
     */
    fun getUpdates(token: String, offset: Long, timeoutSec: Int): PollResult {
        var conn: HttpURLConnection? = null
        return try {
            val allowed = URLEncoder.encode("[\"message\"]", "UTF-8")
            conn = open(
                token,
                "getUpdates?offset=$offset&timeout=$timeoutSec&allowed_updates=$allowed",
                (timeoutSec + 15) * 1000
            )
            pollConn = conn
            val code = conn.responseCode
            if (code != 200) return PollResult.HttpError(code, errorDescription(conn))
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val result = json.optJSONArray("result")
            PollResult.Updates(List(result?.length() ?: 0) { i ->
                val u = result!!.getJSONObject(i)
                val msg = u.optJSONObject("message")
                val chat = msg?.optJSONObject("chat")
                BotUpdate(
                    updateId = u.getLong("update_id"),
                    chatId = chat?.optLong("id"),
                    chatType = chat?.optString("type"),
                    text = msg?.optString("text", "").orEmpty(),
                )
            })
        } catch (e: IOException) {
            PollResult.NetworkError(e.shortReason())
        } catch (e: Exception) {
            // Telegram answered, but with something we could not parse.
            PollResult.HttpError(200, "bad response: ${e.javaClass.simpleName}")
        } finally {
            pollConn = null
            conn?.disconnect()
        }
    }

    /** Aborts the current long poll (used when the service stops). */
    fun abortPoll() {
        try { pollConn?.disconnect() } catch (e: Exception) { }
    }

    private fun open(token: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL("$BASE_URL/bot$token/$method").openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = readTimeoutMs
        c.useCaches = false
        return c
    }

    private fun errorDescription(conn: HttpURLConnection): String = try {
        val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        JSONObject(text).optString("description")
    } catch (e: Exception) {
        ""
    }

    companion object {
        const val BASE_URL = "https://api.telegram.org"

        // Short timeouts keep a whole retry round close to one minute.
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SEND_READ_TIMEOUT_MS = 8_000
    }
}
