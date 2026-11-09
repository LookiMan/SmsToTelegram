package ua.home.smstg.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import ua.home.smstg.Lang
import ua.home.smstg.R
import ua.home.smstg.core.RetryPolicy
import ua.home.smstg.graph
import ua.home.smstg.model.AppLanguage
import ua.home.smstg.model.IncomingSms
import ua.home.smstg.model.PerfMode
import ua.home.smstg.model.Target
import ua.home.smstg.service.ForwarderService
import ua.home.smstg.ui.Ui.colorOf
import ua.home.smstg.ui.Ui.show

/**
 * Two tabs: "Status" and "Logs". Rendering lives in [StatusTab] and [LogsTab];
 * this class only switches tabs and handles user actions (permissions, dialogs, service).
 */
class MainActivity : Activity() {

    private lateinit var statusTab: StatusTab
    private lateinit var logsTab: LogsTab
    private lateinit var statusView: View
    private lateinit var logsView: View
    private lateinit var tabStatus: TextView
    private lateinit var tabLogs: TextView
    private var showingLogs = false

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    /** Every text of this screen comes from resources in the language chosen in the app. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase, newBase.graph.settings.get().language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusTab)
        logsView = findViewById(R.id.logsTab)
        statusTab = StatusTab(statusView, graph)
        logsTab = LogsTab(logsView, graph.logs)

        tabStatus = findViewById(R.id.tabStatus)
        tabLogs = findViewById(R.id.tabLogs)
        tabStatus.setOnClickListener { selectTab(logs = false) }
        tabLogs.setOnClickListener { selectTab(logs = true) }

        statusTab.toggleButton.setOnClickListener { toggle() }
        statusTab.testButton.setOnClickListener { sendTest() }
        statusTab.resetButton.setOnClickListener { confirmReset() }
        logsTab.clearButton.setOnClickListener { confirmClearLogs() }
        statusTab.modeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    graph.setPerfMode(PerfMode.entries[progress], source = "app")
                    refresh()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        setupLanguageSwitch()
        selectTab(logs = false)
    }

    private fun setupLanguageSwitch() {
        val current = Lang.effective(graph.settings.get().language)
        mapOf(AppLanguage.EN to R.id.langEn, AppLanguage.UK to R.id.langUk).forEach { (lang, id) ->
            val item = findViewById<TextView>(id)
            val selected = lang == current
            item.setBackgroundResource(if (selected) R.drawable.bg_segment_on else 0)
            item.setTextColor(colorOf(if (selected) R.color.text_primary else R.color.text_secondary))
            item.paint.isFakeBoldText = selected
            item.setOnClickListener {
                if (lang == current) return@setOnClickListener
                graph.settings.setLanguage(lang)
                ForwarderService.refreshNotification(this) // the notification text follows the language
                recreate() // rebuilds the screen with the new resources
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun selectTab(logs: Boolean) {
        showingLogs = logs
        statusView.show(!logs)
        logsView.show(logs)
        listOf(tabStatus to !logs, tabLogs to logs).forEach { (tab, selected) ->
            tab.setBackgroundResource(if (selected) R.drawable.bg_segment_on else 0)
            tab.setTextColor(colorOf(if (selected) R.color.text_primary else R.color.text_secondary))
            tab.paint.isFakeBoldText = selected
            tab.invalidate()
        }
        refresh()
    }

    private fun refresh() {
        if (showingLogs) logsTab.render() else statusTab.render()
    }

    private fun toggle() {
        val settings = graph.settings
        if (settings.get().isEnabled) {
            settings.setEnabled(false)
            stopService(Intent(this, ForwarderService::class.java))
            refresh()
            return
        }

        if (settings.get().botToken == null) {
            val token = statusTab.tokenInput()
            if (!Regex("""^\d+:[A-Za-z0-9_-]{30,}$""").matches(token)) {
                toast(getString(R.string.toast_bad_token))
                return
            }
            settings.saveToken(token)
            statusTab.clearTokenInput() // the full token is never shown again
        }

        val needed = mutableListOf<String>()
        if (!hasSmsPermission()) needed += Manifest.permission.RECEIVE_SMS
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1) else startForwarding()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasSmsPermission()) startForwarding() else toast(getString(R.string.toast_need_sms))
    }

    private fun startForwarding() {
        graph.settings.setEnabled(true)
        ForwarderService.start(this)
        askBatteryExemption()
        refresh()
    }

    /** Goes through the same path as a real SMS: database → sender → Telegram. */
    private fun sendTest() {
        graph.messages.insert(
            IncomingSms(
                sender = getString(R.string.test_sender),
                text = getString(R.string.test_text),
                target = Target.TEST,
                smsTime = System.currentTimeMillis(),
            )
        )
        graph.wakeSender()
        toast(getString(R.string.toast_test_queued))
        refresh()
    }

    /** Removing the token and chat needs an explicit confirmation; Cancel is the safe default. */
    private fun confirmReset() {
        val s = graph.settings.get()
        val chatText = s.chatId?.let { getString(R.string.reset_chat, Ui.mask(it.toString())) }
            ?: getString(R.string.reset_no_chat)
        val queued = graph.messages.countPending(RetryPolicy.MAX_TRIES)
        val queuedText = resources.getQuantityString(R.plurals.unsent_messages, queued, queued)
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(getString(R.string.reset_body, Ui.mask(s.botToken.orEmpty()), chatText, queuedText))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_remove) { _, _ -> reset() }
            .show()
    }

    /** Clearing logs can't be undone, so it is confirmed too; Cancel is the safe default. */
    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_clear_title)
            .setMessage(getString(R.string.logs_clear_body, graph.logs.count()))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.logs_clear) { _, _ ->
                graph.logs.clearAll()
                refresh()
            }
            .show()
    }

    private fun reset() {
        graph.settings.setEnabled(false)
        stopService(Intent(this, ForwarderService::class.java))
        graph.settings.clearBot()
        graph.messages.deleteUnsent() // they must not go to a different chat later
        graph.logs.info("Token and chat removed")
        toast(getString(R.string.toast_reset_done))
        refresh()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) { }
    }

    private fun hasSmsPermission() =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
