package ua.home.smstg.core

import ua.home.smstg.data.MessageRepository
import ua.home.smstg.data.SettingsRepository
import ua.home.smstg.model.IncomingSms
import ua.home.smstg.sms.SmsHandler

/**
 * Entry point for received SMS: saves them first (so nothing is lost),
 * then asks the sender to deliver them right away.
 */
class SmsInbox(
    private val settings: SettingsRepository,
    private val messages: MessageRepository,
    private val wakeSender: () -> Unit,
) : SmsHandler {

    override fun onSms(messages: List<IncomingSms>) {
        // "Stop" means stop: nothing is stored or forwarded.
        if (!settings.get().isEnabled) return
        messages.forEach(this.messages::insert)
        wakeSender()
    }
}
