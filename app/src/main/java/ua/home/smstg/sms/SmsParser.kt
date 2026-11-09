package ua.home.smstg.sms

import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import ua.home.smstg.model.IncomingSms
import ua.home.smstg.model.Target

/** Turns an SMS_RECEIVED intent into [IncomingSms] DTOs. Knows nothing about storage or Telegram. */
internal object SmsParser {

    fun parse(intent: Intent): List<IncomingSms> {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return emptyList()
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return emptyList()

        val target = target(intent)
        // A long SMS arrives in several parts: join them per sender.
        return parts.groupBy { it.displayOriginatingAddress ?: "?" }
            .map { (sender, list) ->
                IncomingSms(
                    sender = sender,
                    text = list.joinToString("") { it.displayMessageBody ?: "" },
                    target = target,
                    smsTime = list.first().timestampMillis,
                )
            }
    }

    /** SIM1 / SIM2 by the slot the SMS arrived on. */
    private fun target(intent: Intent): Target {
        val subId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", -1)
        )
        if (subId < 0 || Build.VERSION.SDK_INT < 29) return Target.UNKNOWN
        return when (SubscriptionManager.getSlotIndex(subId)) {
            0 -> Target.SIM1
            1 -> Target.SIM2
            else -> Target.UNKNOWN
        }
    }
}

/**
 * The same SMS can arrive twice: through the manifest receiver and through the
 * runtime receiver. Remembers recent messages and lets each one through only once.
 */
internal object SmsDeduplicator {
    private const val CAPACITY = 50
    private val recent = LinkedHashSet<String>()

    fun filterNew(list: List<IncomingSms>): List<IncomingSms> = synchronized(recent) {
        list.filter { sms ->
            val key = "${sms.sender}|${sms.smsTime}|${sms.text.hashCode()}"
            val isNew = recent.add(key)
            if (recent.size > CAPACITY) recent.remove(recent.first())
            isNew
        }
    }
}
