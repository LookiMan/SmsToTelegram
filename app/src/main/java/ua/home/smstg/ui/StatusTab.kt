package ua.home.smstg.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import ua.home.smstg.AppGraph
import ua.home.smstg.R
import ua.home.smstg.core.RetryPolicy
import ua.home.smstg.ui.Ui.colorOf
import ua.home.smstg.ui.Ui.show
import ua.home.smstg.ui.Ui.tintOf

/**
 * The "Status" tab: renders the current state into the views of tab_status.xml.
 * Texts come from strings.xml (values and values-uk) (the activity's context already has the app language).
 * Buttons are exposed so the activity can attach the actions (they need permissions and dialogs).
 */
class StatusTab(private val root: View, private val graph: AppGraph) {

    private val context = root.context

    private val setupCard: View = root.findViewById(R.id.setupCard)
    private val tokenField: EditText = root.findViewById(R.id.tokenField)

    private val stateIconBg: FrameLayout = root.findViewById(R.id.stateIconBg)
    private val stateIcon: ImageView = root.findViewById(R.id.stateIcon)
    private val stateTitle: TextView = root.findViewById(R.id.stateTitle)
    private val stateSubtitle: TextView = root.findViewById(R.id.stateSubtitle)
    val toggleButton: TextView = root.findViewById(R.id.toggleButton)
    private val waitingValue: TextView = root.findViewById(R.id.waitingValue)
    private val waitingLabel: TextView = root.findViewById(R.id.waitingLabel)
    private val lastSmsValue: TextView = root.findViewById(R.id.lastSmsValue)
    private val lastSentValue: TextView = root.findViewById(R.id.lastSentValue)

    private val warningsCard: TextView = root.findViewById(R.id.warningsCard)

    val modeSlider: SeekBar = root.findViewById(R.id.modeSlider)
    private val modeLabels: List<TextView> = listOf(
        // Same order as PerfMode.entries: energy (left) → performance (right).
        root.findViewById(R.id.modeEnergy),
        root.findViewById(R.id.modeBalanced),
        root.findViewById(R.id.modePerformance),
    )
    private val modeDescription: TextView = root.findViewById(R.id.modeDescription)

    private val botCard: View = root.findViewById(R.id.botCard)
    private val botBody: View = root.findViewById(R.id.botBody)
    private val botSummary: TextView = root.findViewById(R.id.botSummary)
    private val botChevron: ImageView = root.findViewById(R.id.botChevron)
    private val tokenValue: TextView = root.findViewById(R.id.tokenValue)
    private val chatValue: TextView = root.findViewById(R.id.chatValue)
    private val pollingValue: TextView = root.findViewById(R.id.pollingValue)
    val testButton: TextView = root.findViewById(R.id.testButton)

    private val helpBody: TextView = root.findViewById(R.id.helpBody)
    private val helpChevron: ImageView = root.findViewById(R.id.helpChevron)

    val resetButton: TextView = root.findViewById(R.id.resetButton)
    private val versionText: TextView = root.findViewById(R.id.versionText)

    /** Whether the Bot card is expanded is a UI preference only, so it lives in SharedPreferences. */
    private val uiPrefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    init {
        root.findViewById<View>(R.id.helpHeader).setOnClickListener {
            setExpanded(helpBody, helpChevron, helpBody.visibility != View.VISIBLE)
        }
        root.findViewById<View>(R.id.botHeader).setOnClickListener {
            val open = botBody.visibility != View.VISIBLE
            uiPrefs.edit().putBoolean(KEY_BOT_EXPANDED, open).apply()
            setExpanded(botBody, botChevron, open)
            render()
        }
        setExpanded(botBody, botChevron, uiPrefs.getBoolean(KEY_BOT_EXPANDED, false))
        versionText.text = context.getString(R.string.version, appVersion())
    }

    fun tokenInput(): String = tokenField.text.toString().trim()

    fun clearTokenInput() = tokenField.setText("")

    fun render() {
        val s = graph.settings.get()
        val conn = graph.connection
        val hasToken = s.botToken != null

        setupCard.show(!hasToken)
        botCard.show(hasToken)
        resetButton.show(hasToken)

        // Green "Start" when stopped, red "Stop" when running.
        toggleButton.text = context.getString(if (s.isEnabled) R.string.btn_stop else R.string.btn_start)
        val toggleBg = if (s.isEnabled) R.drawable.bg_button_stop else R.drawable.bg_button_start
        if (toggleButton.tag != toggleBg) {
            toggleButton.setBackgroundResource(toggleBg)
            toggleButton.setTextColor(0xFFFFFFFF.toInt())
            toggleButton.tag = toggleBg
        }

        // State card
        val state = when {
            !s.isEnabled -> State(
                R.color.text_secondary, R.color.neutral_soft, str(R.string.state_stopped),
                str(if (hasToken) R.string.state_stopped_hint else R.string.state_stopped_no_token)
            )
            s.chatId == null -> State(
                R.color.warning, R.color.warning_soft, str(R.string.state_wait_start),
                str(R.string.state_wait_start_hint)
            )
            conn.isReachable == false -> State(
                R.color.danger, R.color.danger_soft, str(R.string.state_offline),
                context.getString(R.string.state_offline_hint, Ui.shortTime(conn.since))
            )
            conn.isReachable == true -> State(
                R.color.success, R.color.success_soft, str(R.string.state_running),
                context.getString(R.string.state_online_hint, Ui.shortTime(conn.since))
            )
            else -> State(R.color.accent, R.color.accent_soft, str(R.string.state_running), str(R.string.state_checking))
        }
        stateIcon.imageTintList = context.tintOf(state.fg)
        stateIconBg.backgroundTintList = context.tintOf(state.bg)
        stateTitle.text = state.title
        stateSubtitle.text = state.subtitle

        val givenUp = graph.messages.countGivenUp(RetryPolicy.MAX_TRIES)
        waitingValue.text = graph.messages.countPending(RetryPolicy.MAX_TRIES).toString()
        waitingLabel.text =
            if (givenUp > 0) context.getString(R.string.tile_waiting_failed, givenUp) else str(R.string.tile_waiting)
        waitingLabel.setTextColor(context.colorOf(if (givenUp > 0) R.color.danger else R.color.text_secondary))
        lastSmsValue.text = graph.messages.lastReceivedAt()?.let { Ui.shortTime(it) } ?: "—"
        lastSentValue.text = graph.messages.lastSentAt()?.let { Ui.shortTime(it) } ?: "—"

        // Warnings the user has to fix
        val warnings = buildList {
            if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                add(str(R.string.warn_sms_permission))
            }
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                add(str(R.string.warn_battery))
            }
        }
        warningsCard.show(warnings.isNotEmpty())
        warningsCard.text = warnings.joinToString("\n") { "⚠ $it" }

        // Mode card
        val mode = s.perfMode
        if (modeSlider.progress != mode.ordinal) modeSlider.progress = mode.ordinal
        modeLabels.forEachIndexed { i, label ->
            val selected = i == mode.ordinal
            label.setTextColor(context.colorOf(if (selected) R.color.accent else R.color.text_secondary))
            label.paint.isFakeBoldText = selected
            label.invalidate()
        }
        modeDescription.text = context.getString(
            R.string.mode_description, (mode.networkCheckMs / 1000).toInt(), (mode.resendMs / 1000).toInt()
        )

        // Bot card: the polling state is shown in the header while the card is collapsed
        tokenValue.text = Ui.mask(s.botToken.orEmpty())
        chatValue.text = s.chatId?.let { Ui.mask(it.toString()) } ?: str(R.string.bot_not_linked)
        val st = graph.status
        val (pollText, pollColor) = when {
            // Telegram is reachable again; the poll resumes within a moment.
            st.pollError != null && conn.isReachable == true -> str(R.string.poll_reconnecting) to R.color.text_secondary
            st.pollError != null -> localizeReason(st.pollError.orEmpty()) to R.color.danger
            st.lastPollAt > 0 -> context.getString(R.string.poll_ok, Ui.shortTime(st.lastPollAt)) to R.color.success
            else -> "—" to R.color.text_secondary
        }
        pollingValue.text = pollText
        pollingValue.setTextColor(context.colorOf(pollColor))
        botSummary.text = pollText
        botSummary.setTextColor(context.colorOf(pollColor))
        botSummary.show(botBody.visibility != View.VISIBLE)

        val canTest = s.isEnabled && s.chatId != null
        testButton.isEnabled = canTest
        testButton.alpha = if (canTest) 1f else 0.4f
    }

    private fun str(id: Int): String = context.getString(id)

    /** Network reasons come from the transport layer in English (they also go to the logs). */
    private fun localizeReason(reason: String): String = when (reason) {
        "no internet" -> str(R.string.net_no_internet)
        "timeout" -> str(R.string.net_timeout)
        "no connection" -> str(R.string.net_no_connection)
        "secure connection failed" -> str(R.string.net_tls)
        "network error" -> str(R.string.net_error)
        else -> reason // e.g. "HTTP 401 Unauthorized"
    }

    private fun setExpanded(body: View, chevron: ImageView, open: Boolean) {
        body.show(open)
        chevron.setImageResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
    }

    private data class State(val fg: Int, val bg: Int, val title: String, val subtitle: String)

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private companion object {
        const val KEY_BOT_EXPANDED = "bot_expanded"
    }
}
