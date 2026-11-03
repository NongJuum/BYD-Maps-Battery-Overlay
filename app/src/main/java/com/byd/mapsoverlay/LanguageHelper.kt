package com.byd.mapsoverlay

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LanguageHelper {
    private const val PREFS_NAME = "byd_language_prefs"
    private const val KEY_LANG = "app_language"

    const val LANG_EN = "en"
    const val LANG_TH = "th"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, null) ?: getDefaultLanguage()
    }

    private fun getDefaultLanguage(): String {
        val sysLang = Locale.getDefault().language
        return if (sysLang.equals("th", ignoreCase = true)) LANG_TH else LANG_EN
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, langCode).apply()

        try {
            val localeList = LocaleListCompat.forLanguageTags(langCode)
            AppCompatDelegate.setApplicationLocales(localeList)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isThai(context: Context): Boolean {
        return getSavedLanguage(context) == LANG_TH
    }

    fun wrapContext(context: Context, langCode: String): Context {
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }
}
