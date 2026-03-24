package com.eacape.speccodingplugin.i18n

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

enum class InterfaceLanguage(
    val code: String,
    val labelKey: String,
    private val locale: Locale?,
) {
    AUTO("AUTO", "settings.language.auto", null),
    ENGLISH("ENGLISH", "settings.language.english", Locale.ENGLISH),
    ZH_CN("ZH_CN", "settings.language.zhCn", Locale.SIMPLIFIED_CHINESE),
    ;

    fun resolve(systemLocale: Locale): Locale = locale ?: systemLocale

    companion object {
        fun fromCode(value: String?): InterfaceLanguage {
            return entries.firstOrNull { it.code.equals(value, ignoreCase = true) } ?: AUTO
        }
    }
}

data class LocaleChangedEvent(
    val previousLanguage: InterfaceLanguage,
    val currentLanguage: InterfaceLanguage,
    val reason: String,
    val changedAt: Long,
    val resolvedLocale: Locale,
)

interface LocaleChangedListener {
    fun onLocaleChanged(event: LocaleChangedEvent) {}

    companion object {
        val TOPIC: Topic<LocaleChangedListener> = Topic.create(
            "SpecCoding.LocaleChanged",
            LocaleChangedListener::class.java,
        )
    }
}

@Service(Service.Level.APP)
class LocaleManager internal constructor(
    private val messageBus: MessageBus,
    private val settingsProvider: () -> SpecCodingSettingsState,
    private val systemLocaleProvider: () -> Locale,
    private val clock: () -> Long,
) {
    private val logger = thisLogger()

    @Volatile
    private var persistedLanguageHint: InterfaceLanguage? = null

    @Volatile
    private var lastSelectionLogKey: String? = null

    init {
        val bootSelection = resolveLanguageSelection()
        syncRuntimeOverride(bootSelection.language)
        logger.info(
            "[i18n] LocaleManager init: selected=${bootSelection.language.code}, " +
                "source=${bootSelection.source}, " +
                "settings=${bootSelection.settingsLanguage.code}, " +
                "runtime=${bootSelection.runtimeLanguage.code}, " +
                "xml=${bootSelection.persistedLanguage.code}, " +
                "runtimeOverride=${System.getProperty(LANGUAGE_OVERRIDE_PROPERTY)}",
        )
    }

    constructor() : this(
        messageBus = ApplicationManager.getApplication().messageBus,
        settingsProvider = { SpecCodingSettingsState.getInstance() },
        systemLocaleProvider = { Locale.getDefault() },
        clock = { System.currentTimeMillis() },
    )

    fun selectedLanguage(): InterfaceLanguage {
        val selection = resolveLanguageSelection()
        syncRuntimeOverride(selection.language)
        logSelectionIfNeeded(selection)
        return selection.language
    }

    fun currentLocale(): Locale {
        return selectedLanguage().resolve(systemLocaleProvider())
    }

    fun setLanguage(language: InterfaceLanguage, reason: String = "unspecified"): LocaleChangedEvent? {
        val settings = settingsProvider()
        val previous = InterfaceLanguage.fromCode(settings.interfaceLanguage)
        logger.info(
            "[i18n] setLanguage requested: previous=${previous.code}, next=${language.code}, " +
                "settingsBefore=${settings.interfaceLanguage}, reason=$reason",
        )
        if (previous == language) {
            persistedLanguageHint = language
            syncRuntimeOverride(language)
            logger.info(
                "[i18n] setLanguage skipped (unchanged): settingsAfter=${settings.interfaceLanguage}, " +
                    "runtimeOverride=${System.getProperty(LANGUAGE_OVERRIDE_PROPERTY)}",
            )
            return null
        }

        settings.interfaceLanguage = language.code
        persistedLanguageHint = language
        syncRuntimeOverride(language)
        logger.info(
            "[i18n] setLanguage persisted: settingsAfter=${settings.interfaceLanguage}, " +
                "runtimeOverride=${System.getProperty(LANGUAGE_OVERRIDE_PROPERTY)}",
        )
        val event = LocaleChangedEvent(
            previousLanguage = previous,
            currentLanguage = language,
            reason = reason,
            changedAt = clock(),
            resolvedLocale = currentLocale(),
        )
        logger.info(
            "[i18n] setLanguage event: previous=${event.previousLanguage.code}, current=${event.currentLanguage.code}, " +
                "resolvedLocale=${event.resolvedLocale}, reason=${event.reason}",
        )
        messageBus.syncPublisher(LocaleChangedListener.TOPIC)
            .onLocaleChanged(event)
        return event
    }

    private fun resolvePersistedLanguageHint(): InterfaceLanguage {
        persistedLanguageHint?.let { return it }
        val loaded = loadLanguageFromOptionsXml()
        persistedLanguageHint = loaded
        return loaded
    }

    private fun loadLanguageFromOptionsXml(): InterfaceLanguage {
        return runCatching {
            val optionsPath = PathManager.getOptionsPath()
            val settingsFile = Paths.get(optionsPath, SETTINGS_FILE_NAME)
            if (!Files.isRegularFile(settingsFile)) {
                return@runCatching InterfaceLanguage.AUTO
            }
            Files.newInputStream(settingsFile).use { input ->
                val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    isExpandEntityReferences = false
                    runCatching { setFeature(FEATURE_DISALLOW_DOCTYPE_DECL, true) }
                    runCatching { setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false) }
                    runCatching { setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false) }
                }
                val document = documentBuilderFactory.newDocumentBuilder().parse(input)
                val options = document.getElementsByTagName(OPTION_TAG_NAME)
                for (index in 0 until options.length) {
                    val optionElement = options.item(index) as? Element ?: continue
                    if (!OPTION_NAME_INTERFACE_LANGUAGE.equals(optionElement.getAttribute(OPTION_ATTR_NAME), ignoreCase = true)) {
                        continue
                    }
                    val value = optionElement.getAttribute(OPTION_ATTR_VALUE)
                    return@runCatching InterfaceLanguage.fromCode(value)
                }
            }
            InterfaceLanguage.AUTO
        }.onFailure {
            logger.warn("[i18n] Failed to read interface language from options xml", it)
        }.getOrDefault(InterfaceLanguage.AUTO)
    }

    private fun resolveLanguageSelection(): LanguageSelection {
        val settingsLanguage = InterfaceLanguage.fromCode(settingsProvider().interfaceLanguage)
        if (settingsLanguage != InterfaceLanguage.AUTO) {
            return LanguageSelection(
                language = settingsLanguage,
                source = SELECTION_SOURCE_SETTINGS,
                settingsLanguage = settingsLanguage,
                runtimeLanguage = InterfaceLanguage.AUTO,
                persistedLanguage = persistedLanguageHint ?: InterfaceLanguage.AUTO,
            )
        }

        val runtimeLanguage = InterfaceLanguage.fromCode(System.getProperty(LANGUAGE_OVERRIDE_PROPERTY)?.trim())
        if (runtimeLanguage != InterfaceLanguage.AUTO) {
            return LanguageSelection(
                language = runtimeLanguage,
                source = SELECTION_SOURCE_RUNTIME,
                settingsLanguage = settingsLanguage,
                runtimeLanguage = runtimeLanguage,
                persistedLanguage = persistedLanguageHint ?: InterfaceLanguage.AUTO,
            )
        }

        val persistedLanguage = resolvePersistedLanguageHint()
        if (persistedLanguage != InterfaceLanguage.AUTO) {
            return LanguageSelection(
                language = persistedLanguage,
                source = SELECTION_SOURCE_OPTIONS_XML,
                settingsLanguage = settingsLanguage,
                runtimeLanguage = runtimeLanguage,
                persistedLanguage = persistedLanguage,
            )
        }

        return LanguageSelection(
            language = InterfaceLanguage.AUTO,
            source = SELECTION_SOURCE_SYSTEM,
            settingsLanguage = settingsLanguage,
            runtimeLanguage = runtimeLanguage,
            persistedLanguage = persistedLanguage,
        )
    }

    private fun syncRuntimeOverride(language: InterfaceLanguage) {
        runCatching {
            val current = System.getProperty(LANGUAGE_OVERRIDE_PROPERTY)?.trim()
            if (!language.code.equals(current, ignoreCase = true)) {
                System.setProperty(LANGUAGE_OVERRIDE_PROPERTY, language.code)
            }
        }
    }

    private fun logSelectionIfNeeded(selection: LanguageSelection) {
        val key = "${selection.language.code}|${selection.source}|${selection.settingsLanguage.code}|" +
            "${selection.runtimeLanguage.code}|${selection.persistedLanguage.code}"
        if (key == lastSelectionLogKey) {
            return
        }
        lastSelectionLogKey = key
        logger.info(
            "[i18n] selectedLanguage resolved: selected=${selection.language.code}, " +
                "source=${selection.source}, " +
                "settings=${selection.settingsLanguage.code}, " +
                "runtime=${selection.runtimeLanguage.code}, " +
                "xml=${selection.persistedLanguage.code}",
        )
    }

    private data class LanguageSelection(
        val language: InterfaceLanguage,
        val source: String,
        val settingsLanguage: InterfaceLanguage,
        val runtimeLanguage: InterfaceLanguage,
        val persistedLanguage: InterfaceLanguage,
    )

    companion object {
        const val LANGUAGE_OVERRIDE_PROPERTY: String = "spec.coding.interfaceLanguage"
        private const val SETTINGS_FILE_NAME: String = "specCodingSettings.xml"
        private const val OPTION_TAG_NAME: String = "option"
        private const val OPTION_ATTR_NAME: String = "name"
        private const val OPTION_ATTR_VALUE: String = "value"
        private const val OPTION_NAME_INTERFACE_LANGUAGE: String = "interfaceLanguage"
        private const val SELECTION_SOURCE_SETTINGS: String = "settings"
        private const val SELECTION_SOURCE_RUNTIME: String = "runtime"
        private const val SELECTION_SOURCE_OPTIONS_XML: String = "optionsXml"
        private const val SELECTION_SOURCE_SYSTEM: String = "system"
        private const val FEATURE_DISALLOW_DOCTYPE_DECL: String = "http://apache.org/xml/features/disallow-doctype-decl"
        private const val FEATURE_EXTERNAL_GENERAL_ENTITIES: String = "http://xml.org/sax/features/external-general-entities"
        private const val FEATURE_EXTERNAL_PARAMETER_ENTITIES: String =
            "http://xml.org/sax/features/external-parameter-entities"

        fun getInstance(): LocaleManager = service()
    }
}
