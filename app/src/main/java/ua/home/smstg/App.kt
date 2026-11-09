package ua.home.smstg

import android.app.Application
import android.content.Context
import android.content.res.Resources
import ua.home.smstg.core.BotCommands
import ua.home.smstg.core.ConnectionMonitor
import ua.home.smstg.core.MessageDispatcher
import ua.home.smstg.core.ServiceStatus
import ua.home.smstg.core.SmsInbox
import ua.home.smstg.core.WakeSignal
import ua.home.smstg.data.AppDatabase
import ua.home.smstg.data.LegacyPrefsMigration
import ua.home.smstg.data.LogRepository
import ua.home.smstg.data.MessageRepository
import ua.home.smstg.data.SettingsRepository
import ua.home.smstg.model.PerfMode
import ua.home.smstg.network.ConnectivityChecker
import ua.home.smstg.service.ForwarderService
import ua.home.smstg.sms.SmsHandler
import ua.home.smstg.sms.SmsHandlerProvider
import ua.home.smstg.telegram.TelegramApi

class App : Application(), SmsHandlerProvider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        LegacyPrefsMigration.run(this, graph.settings)
    }

    override val smsHandler: SmsHandler get() = graph.smsInbox
}

/** Shortcut for Android components (service, activity, receivers). */
val Context.graph: AppGraph get() = (applicationContext as App).graph

/**
 * Composition root: creates every component once and wires them together.
 * The only place that knows all layers.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext

    private val db = AppDatabase(app)
    val settings = SettingsRepository(db)
    val messages = MessageRepository(db)
    val logs = LogRepository(db)

    val api = TelegramApi()
    val connectivity = ConnectivityChecker()
    val status = ServiceStatus()
    val connection = ConnectionMonitor(logs)

    /** Wakes the sender thread: new SMS, network is back, chat linked, test message. */
    val senderSignal = WakeSignal()

    /** Resources in the app language, for texts produced outside the activity (bot replies, notification). */
    fun strings(): Resources = Lang.wrap(app, settings.get().language).resources

    val dispatcher = MessageDispatcher(settings, messages, logs, api, senderSignal, connection)
    val smsInbox = SmsInbox(settings, messages, wakeSender = { wakeSender() })
    val commands = BotCommands(
        settings, api, logs, strings = { strings() },
        onChatLinked = { senderSignal.raise() },
        changeMode = { setPerfMode(it, source = "bot") },
    )

    /** Used by both the app slider and the bot; the sender picks up the new timings at once. */
    fun setPerfMode(mode: PerfMode, source: String) {
        if (settings.get().perfMode == mode) return
        settings.setPerfMode(mode)
        logs.info("Mode: ${mode.label} ($source)")
        senderSignal.raise()
    }

    /** Makes sure the service runs and delivers the queue now. */
    fun wakeSender() {
        try {
            ForwarderService.start(app) // already running: just wakes the sender thread
        } catch (e: Exception) {
            logs.error("Service start failed: ${e.javaClass.simpleName}. SMS saved")
        }
    }
}
