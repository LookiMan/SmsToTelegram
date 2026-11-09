package ua.home.smstg.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import ua.home.smstg.R
import ua.home.smstg.data.LogRepository
import ua.home.smstg.model.LogEntry
import ua.home.smstg.model.LogLevel
import ua.home.smstg.ui.Ui.colorOf
import ua.home.smstg.ui.Ui.tintOf

/** The "Logs" tab: newest entries first, filterable by level. */
class LogsTab(root: View, private val logs: LogRepository) {

    private val context = root.context
    private val adapter = LogAdapter(context)
    private val filters: Map<LogLevel?, TextView> = mapOf(
        null to root.findViewById(R.id.filterAll),
        LogLevel.WARNING to root.findViewById(R.id.filterWarning),
        LogLevel.ERROR to root.findViewById(R.id.filterError),
    )
    /** The activity attaches the confirmation dialog. */
    val clearButton: TextView = root.findViewById(R.id.clearLogs)
    private var filter: LogLevel? = null
    private var shownKey = ""

    init {
        root.findViewById<ListView>(R.id.logsList).apply {
            adapter = this@LogsTab.adapter
            emptyView = root.findViewById(R.id.logsEmpty)
        }
        filters.forEach { (level, pill) -> pill.setOnClickListener { setFilter(level) } }
        renderFilters()
    }

    fun render() {
        val entries = logs.recent(LIMIT, filter)
        // Rebuild only when something changed, so the scroll position is kept.
        val key = "$filter|${entries.firstOrNull()?.id}|${entries.size}"
        if (key == shownKey) return
        shownKey = key
        adapter.submit(entries)
    }

    private fun setFilter(level: LogLevel?) {
        filter = level
        renderFilters()
        render()
    }

    private fun renderFilters() {
        filters.forEach { (level, pill) ->
            val selected = level == filter
            val (fg, bg) = if (selected) colorsFor(level) else R.color.text_secondary to R.color.card
            pill.setTextColor(context.colorOf(fg))
            pill.backgroundTintList = context.tintOf(bg)
        }
    }

    private class LogAdapter(private val context: Context) : BaseAdapter() {
        private var items: List<LogEntry> = emptyList()

        fun submit(list: List<LogEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_log, parent, false)
            val e = items[position]
            val (fg, bg) = colorsFor(e.level)
            view.findViewById<TextView>(R.id.logLevel).apply {
                text = e.level.name
                setTextColor(context.colorOf(fg))
                backgroundTintList = context.tintOf(bg)
            }
            view.findViewById<TextView>(R.id.logTime).text = Ui.fullTime(e.createdAt)
            view.findViewById<TextView>(R.id.logText).text = e.text
            return view
        }
    }

    private companion object {
        const val LIMIT = 300

        /** Text color and pill background for a level; null = "All". */
        fun colorsFor(level: LogLevel?): Pair<Int, Int> = when (level) {
            null -> R.color.accent to R.color.accent_soft
            LogLevel.INFO -> R.color.success to R.color.success_soft
            LogLevel.WARNING -> R.color.warning to R.color.warning_soft
            LogLevel.ERROR -> R.color.danger to R.color.danger_soft
        }
    }
}
