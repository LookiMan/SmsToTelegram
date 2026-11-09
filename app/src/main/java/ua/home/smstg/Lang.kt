package ua.home.smstg

import android.content.Context
import android.content.res.Configuration
import ua.home.smstg.model.AppLanguage
import java.util.Locale

/**
 * App language without AndroidX: wraps a context so that its resources use the chosen language.
 * The activity wraps its base context; the service and the bot ask [AppGraph.strings].
 */
object Lang {

    /** The language actually in use: the saved choice, otherwise the system language. */
    fun effective(saved: AppLanguage?): AppLanguage =
        saved ?: if (Locale.getDefault().language == "uk") AppLanguage.UK else AppLanguage.EN

    fun wrap(base: Context, saved: AppLanguage?): Context {
        if (saved == null) return base // follow the system
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale(saved.tag))
        return base.createConfigurationContext(config)
    }
}
