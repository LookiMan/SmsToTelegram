package ua.home.smstg.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import ua.home.smstg.model.IncomingSms

/**
 * Public API of the SMS module.
 *
 * The module only receives SMS and hands them out as [IncomingSms] DTOs.
 * What happens to them next is decided by whoever implements [SmsHandler].
 */
fun interface SmsHandler {
    fun onSms(messages: List<IncomingSms>)
}

/** Implemented by the Application, so the manifest receiver can find the handler. */
interface SmsHandlerProvider {
    val smsHandler: SmsHandler
}

private fun dispatch(intent: Intent, handler: SmsHandler) {
    val fresh = SmsDeduplicator.filterNew(SmsParser.parse(intent))
    if (fresh.isNotEmpty()) handler.onSms(fresh)
}

/**
 * Declared in the manifest: Android calls it for every incoming SMS,
 * even when the screen is locked and the app is not running.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val provider = context.applicationContext as? SmsHandlerProvider ?: return
        dispatch(intent, provider.smsHandler)
    }
}

/**
 * Second receiver, registered at runtime while the service runs.
 * Some firmwares (MIUI) block manifest receivers; this one still works then.
 */
class RuntimeSmsReceiver(private val handler: SmsHandler) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = dispatch(intent, handler)
    }

    fun register(context: Context) {
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = 999 }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, BROADCAST_SMS, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, BROADCAST_SMS, null)
        }
    }

    fun unregister(context: Context) {
        try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { }
    }

    private companion object {
        const val BROADCAST_SMS = "android.permission.BROADCAST_SMS"
    }
}
