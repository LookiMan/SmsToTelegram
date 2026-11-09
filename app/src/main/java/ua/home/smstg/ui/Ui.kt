package ua.home.smstg.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Small helpers shared by the UI classes. */
internal object Ui {

    /** Shows only the last 3 characters of a secret, e.g. "•••F7k". */
    fun mask(s: String): String = if (s.length <= 3) "•••" else "•••" + s.takeLast(3)

    fun fullTime(millis: Long): String =
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(millis))

    /** "19:38" for today, "08.11" for earlier days. */
    fun shortTime(millis: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val now = Calendar.getInstance()
        val today = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (today) "HH:mm" else "dd.MM", Locale.getDefault()).format(Date(millis))
    }

    fun Context.colorOf(resId: Int): Int = getColor(resId)

    fun Context.tintOf(resId: Int): ColorStateList = ColorStateList.valueOf(getColor(resId))

    fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
