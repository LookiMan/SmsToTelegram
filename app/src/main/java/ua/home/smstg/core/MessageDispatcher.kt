package ua.home.smstg.core

import ua.home.smstg.data.LogRepository
import ua.home.smstg.data.MessageRepository
import ua.home.smstg.data.SettingsRepository
import ua.home.smstg.model.StoredMessage
import ua.home.smstg.model.Target
import ua.home.smstg.telegram.MessageFormatter
import ua.home.smstg.telegram.SendResult
import ua.home.smstg.telegram.TelegramApi

/** Delivery rules in one place. */
object RetryPolicy {
    /** Retries after the first attempt within one delivery round. */
    const val MAX_RETRIES = 5

    /** After this many rejected rounds a message is given up (network errors never count). */
    const val MAX_TRIES = 5

    /** Pause before retry n (1-based): 2^(n-1) seconds = 1, 2, 4, 8, 16. */
    fun delayMs(retry: Int): Long = 1000L shl (retry - 1)
}

/**
 * Sends unsent messages from the database to Telegram, oldest first.
 * Runs on one thread only (the service's sender thread), so a message is never sent twice in parallel.
 */
class MessageDispatcher(
    private val settings: SettingsRepository,
    private val messages: MessageRepository,
    private val logs: LogRepository,
    private val api: TelegramApi,
    private val signal: WakeSignal,
    private val connection: ConnectionMonitor,
) {
    /** Set to false by the service when it stops, to abort retry pauses. */
    @Volatile var active = false

    private enum class Outcome { SENT, REJECTED, NO_CONNECTION, STOPPED }

    /**
     * Sends everything that is pending. Stops early if the connection is down:
     * the remaining messages wait for the network to come back.
     */
    fun sendPending() {
        val s = settings.get()
        val token = s.botToken ?: return
        val chatId = s.chatId ?: return // nothing is sent until the owner sends /start
        val rejectedThisPass = mutableSetOf<Long>()

        while (active) {
            // Re-read after every message: SMS that arrive meanwhile are picked up in the same pass.
            val msg = messages.nextPending(RetryPolicy.MAX_TRIES, rejectedThisPass) ?: return
            when (deliver(token, chatId, msg)) {
                Outcome.SENT -> Unit
                Outcome.REJECTED -> rejectedThisPass += msg.id
                Outcome.NO_CONNECTION, Outcome.STOPPED -> return
            }
        }
    }

    private fun deliver(token: String, chatId: Long, msg: StoredMessage): Outcome {
        val formatted = MessageFormatter.format(msg)
        var last: SendResult = SendResult.NetworkError("not attempted")

        for (attempt in 0..RetryPolicy.MAX_RETRIES) {
            if (attempt > 0) {
                // Retries are not logged one by one; only the final result is.
                signal.await(RetryPolicy.delayMs(attempt)) // a new SMS or "network is back" shortens the pause
                if (!active) return Outcome.STOPPED
            }
            last = sendOnce(token, chatId, formatted)
            when (val r = last) {
                is SendResult.NetworkError -> connection.report(false, r.reason)
                else -> connection.report(true) // any answer from Telegram means it is reachable
            }
            if (last == SendResult.Ok) {
                messages.markSent(msg.id)
                logs.info("${name(msg)} sent" + if (attempt > 0) " (retry $attempt)" else "")
                return Outcome.SENT
            }
        }

        val rejected = last is SendResult.HttpError && !(last as SendResult.HttpError).isTransient
        if (!rejected) {
            logs.error("${name(msg)} not sent: ${describe(last)}. Waiting for connection")
            return Outcome.NO_CONNECTION
        }
        val tries = messages.incrementTries(msg.id)
        val givenUp = if (tries >= RetryPolicy.MAX_TRIES) ". Gave up" else ""
        logs.error("${name(msg)} rejected: ${describe(last)} (try $tries/${RetryPolicy.MAX_TRIES})$givenUp")
        return Outcome.REJECTED
    }

    /** Sends as HTML; if Telegram rejects the markup (HTTP 400), sends the same text as plain text. */
    private fun sendOnce(token: String, chatId: Long, f: MessageFormatter.Formatted): SendResult {
        val result = api.sendMessage(token, chatId, f.html, html = true)
        return if (result is SendResult.HttpError && result.code == 400) {
            api.sendMessage(token, chatId, f.plain, html = false)
        } else {
            result
        }
    }

    private fun name(msg: StoredMessage): String =
        if (msg.target == Target.TEST) "Test message" else "SMS #${msg.id}"

    private fun describe(r: SendResult): String = when (r) {
        SendResult.Ok -> "ok"
        is SendResult.NetworkError -> r.reason
        is SendResult.HttpError -> "HTTP ${r.code} ${r.description}".trim()
    }
}
