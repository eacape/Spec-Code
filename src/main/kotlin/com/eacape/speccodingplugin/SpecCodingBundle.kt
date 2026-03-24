package com.eacape.speccodingplugin

import com.eacape.speccodingplugin.i18n.InterfaceLanguage
import com.eacape.speccodingplugin.i18n.LocaleManager
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

private const val BUNDLE_NAME = "messages.SpecCodingBundle"
private val NO_FALLBACK_CONTROL: ResourceBundle.Control =
    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT)

object SpecCodingBundle : DynamicBundle(BUNDLE_NAME) {
    fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any): String {
        val locale = runCatching { LocaleManager.getInstance().currentLocale() }
            .getOrElse { fallbackLocale() }

        return try {
            val bundle = ResourceBundle.getBundle(
                BUNDLE_NAME,
                locale,
                javaClass.classLoader,
                NO_FALLBACK_CONTROL,
            )
            val pattern = bundle.getString(key)
            if (params.isEmpty()) {
                // Keep literal braces untouched (e.g. "{{variable_name}}").
                pattern
            } else {
                runCatching {
                    MessageFormat(pattern, locale).format(params)
                }.getOrDefault(pattern)
            }
        } catch (_: MissingResourceException) {
            getMessage(key, *params)
        }
    }

    fun messageOrDefault(key: String, defaultValue: String): String {
        return runCatching { message(key) }.getOrDefault(defaultValue)
    }

    private fun fallbackLocale(): Locale {
        val systemLocale = Locale.getDefault()
        val settingsLanguage = runCatching {
            InterfaceLanguage.fromCode(SpecCodingSettingsState.getInstance().interfaceLanguage)
        }.getOrDefault(InterfaceLanguage.AUTO)
        if (settingsLanguage != InterfaceLanguage.AUTO) {
            return settingsLanguage.resolve(systemLocale)
        }

        val runtimeLanguage = InterfaceLanguage.fromCode(
            System.getProperty(LocaleManager.LANGUAGE_OVERRIDE_PROPERTY)?.trim(),
        )
        if (runtimeLanguage != InterfaceLanguage.AUTO) {
            return runtimeLanguage.resolve(systemLocale)
        }
        return systemLocale
    }
}
