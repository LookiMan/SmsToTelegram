package ua.home.smstg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.home.smstg.core.ConnectionMonitor
import ua.home.smstg.core.RetryPolicy
import ua.home.smstg.model.AppLanguage
import ua.home.smstg.model.EventLog
import ua.home.smstg.model.IncomingSms
import ua.home.smstg.model.PerfMode
import ua.home.smstg.model.StoredMessage
import ua.home.smstg.model.Target
import ua.home.smstg.sms.SmsDeduplicator
import ua.home.smstg.telegram.MessageFormatter
import ua.home.smstg.telegram.shortReason
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Pure-JVM tests for the delivery rules, formatting and connection tracking (no device needed). */
class RetryPolicyTest {

    @Test
    fun `delays are 1, 2, 4, 8, 16 seconds`() {
        val delays = (1..RetryPolicy.MAX_RETRIES).map { RetryPolicy.delayMs(it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), delays)
    }

    @Test
    fun `a whole round of pauses fits well within a minute`() {
        val total = (1..RetryPolicy.MAX_RETRIES).sumOf { RetryPolicy.delayMs(it) }
        assertEquals(31_000L, total)
    }
}

class MessageFormatterTest {

    private fun msg(sender: String, text: String, target: Target = Target.SIM1) = StoredMessage(
        id = 1, sender = sender, text = text, target = target, smsTime = 0L,
        isSent = false, tries = 0, createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun `escapes only the characters Telegram HTML needs`() {
        assertEquals("a &amp; b &lt;c&gt; \"d\" 'e'", MessageFormatter.escape("a & b <c> \"d\" 'e'"))
    }

    @Test
    fun `html has bold sender, italic meta and escaped body`() {
        val f = MessageFormatter.format(msg("Bank<1>", "Code: 1234 & <b>"))
        val parts = f.html.split("\n\n")
        assertEquals(3, parts.size)
        assertEquals("<b>Bank&lt;1&gt;</b>", parts[0])
        assertTrue(parts[1].startsWith("<i>SIM1 · ") && parts[1].endsWith("</i>"))
        assertEquals("Code: 1234 &amp; &lt;b&gt;", parts[2])
    }

    @Test
    fun `plain fallback has no markup and no escaping`() {
        val f = MessageFormatter.format(msg("Bank", "a < b"))
        assertTrue(f.plain.startsWith("Bank\n\nSIM1 · "))
        assertTrue(f.plain.endsWith("\n\na < b"))
    }

    @Test
    fun `test message body is italic`() {
        val f = MessageFormatter.format(msg("Test message", "works", Target.TEST))
        assertTrue(f.html.endsWith("<i>works</i>"))
        assertTrue(f.html.contains("SMS to Telegram · "))
    }

    @Test
    fun `very long text stays under the Telegram limit`() {
        val f = MessageFormatter.format(msg("X", "y".repeat(10_000)))
        assertTrue(f.html.length < 4096)
        assertTrue(f.plain.length < 4096)
    }
}

class SmsDeduplicatorTest {

    private fun sms(sender: String, text: String, time: Long) = IncomingSms(sender, text, Target.SIM1, time)

    @Test
    fun `the same SMS delivered by both receivers passes only once`() {
        val a = sms("dedup-1", "hello", 1L)
        assertEquals(listOf(a), SmsDeduplicator.filterNew(listOf(a)))
        assertEquals(emptyList<IncomingSms>(), SmsDeduplicator.filterNew(listOf(a.copy())))
    }

    @Test
    fun `different text or time is a different SMS`() {
        val a = sms("dedup-2", "hello", 1L)
        SmsDeduplicator.filterNew(listOf(a))
        val other = listOf(a.copy(text = "hello!"), a.copy(smsTime = 2L))
        assertEquals(other, SmsDeduplicator.filterNew(other))
    }
}

class ConnectionMonitorTest {

    private class FakeLog : EventLog {
        val entries = mutableListOf<String>()
        override fun info(text: String) { entries += "INFO $text" }
        override fun warning(text: String) { entries += "WARNING $text" }
        override fun error(text: String) { entries += "ERROR $text" }
    }

    private var now = 0L
    private val log = FakeLog()
    private val monitor = ConnectionMonitor(log, clock = { now })

    @Test
    fun `first observation is not logged`() {
        monitor.report(true)
        assertTrue(log.entries.isEmpty())
        assertEquals(true, monitor.isReachable)
    }

    @Test
    fun `only changes are logged, with reason and outage duration`() {
        monitor.report(true)
        now = 10_000
        monitor.report(false, "no internet")
        monitor.report(false, "timeout") // still down: not logged again
        now = 75_000
        monitor.report(true)
        monitor.report(true)
        assertEquals(listOf("WARNING Offline: no internet", "INFO Online again after 1m 5s"), log.entries)
        assertEquals(75_000L, monitor.since)
    }

    @Test
    fun `onRestored fires only when coming back online`() {
        var calls = 0
        monitor.onRestored = { calls++ }
        monitor.report(true)  // first observation
        monitor.report(false)
        monitor.report(true)  // restored
        monitor.report(true)  // no change
        assertEquals(1, calls)
    }
}

class NetworkReasonTest {

    @Test
    fun `exceptions map to short reasons`() {
        assertEquals("no internet", UnknownHostException("api.telegram.org").shortReason())
        assertEquals("timeout", SocketTimeoutException().shortReason())
        assertEquals("no connection", ConnectException().shortReason())
        assertEquals("secure connection failed", SSLException("x").shortReason())
        assertEquals("network error", IOException().shortReason())
    }
}

class ModelTest {

    @Test
    fun `modes go from energy to performance, as on the slider`() {
        assertEquals(listOf(PerfMode.ENERGY, PerfMode.BALANCED, PerfMode.PERFORMANCE), PerfMode.entries)
        assertEquals(PerfMode.BALANCED, PerfMode.DEFAULT)
    }

    @Test
    fun `mode commands and language tags are parsed`() {
        assertEquals(PerfMode.ENERGY, PerfMode.fromLabel("energy"))
        assertNull(PerfMode.fromLabel("turbo"))
        assertEquals(AppLanguage.UK, AppLanguage.fromTag("uk"))
        assertNull(AppLanguage.fromTag(null))
    }

    @Test
    fun `background timers get shorter towards performance`() {
        val modes = PerfMode.entries
        for (i in 1 until modes.size) {
            assertTrue(modes[i].networkCheckMs <= modes[i - 1].networkCheckMs)
            assertTrue(modes[i].resendMs <= modes[i - 1].resendMs)
        }
        assertFalse(PerfMode.PERFORMANCE.resendMs > 60_000)
    }
}
