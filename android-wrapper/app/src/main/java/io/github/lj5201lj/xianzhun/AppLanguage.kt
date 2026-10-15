package io.github.lj5201lj.xianzhun

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    private fun preferences(context: Context) = context.getSharedPreferences("xianzhun_language_v1", Context.MODE_PRIVATE)

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = preferences(context)
            // On an OS upgrade, migrate the saved pre-Android-13 selection once. Afterwards the
            // platform is authoritative, including choices made in Android's App Languages screen.
            if (!prefs.getBoolean("platform_migrated", false)) {
                val previous = LanguageChoice.fromTag(prefs.getString("language", "") ?: "")
                val manager = context.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty && previous != LanguageChoice.SYSTEM) {
                    manager.applicationLocales = LocaleList.forLanguageTags(previous.tag)
                }
                prefs.edit().putBoolean("platform_migrated", true).apply()
            }
            return context
        }
        val choice = selected(context)
        if (choice == LanguageChoice.SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(choice.tag))
        return context.createConfigurationContext(configuration)
    }

    fun selected(context: Context): LanguageChoice {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
                .substringBefore(',')
        } else preferences(context).getString("language", "") ?: ""
        return LanguageChoice.fromTag(tag)
    }

    /** Android 13+ recreates the Activity itself; older versions need an explicit recreate(). */
    fun select(context: Context, choice: LanguageChoice): Boolean {
        if (choice == selected(context)) return false
        preferences(context).edit().putString("language", choice.tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(choice.tag)
            return false
        }
        return true
    }
}
