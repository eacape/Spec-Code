package com.eacape.speccodingplugin.ui.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Settings State
 * 存储插件配置（不包含敏感信息）
 */
@Service(Service.Level.APP)
@State(
    name = "SpecCodingSettings",
    storages = [Storage("specCodingSettings.xml")]
)
class SpecCodingSettingsState : PersistentStateComponent<SpecCodingSettingsState> {

    // 默认 LLM 提供者
    var defaultProvider: String = "claude-cli"

    // 当前选中的 CLI 模型 ID
    var selectedCliModel: String = ""

    // 代理设置
    var useProxy: Boolean = false
    var proxyHost: String = ""
    var proxyPort: Int = 8080

    // 其他设置
    var autoSaveConversation: Boolean = true
    var maxHistorySize: Int = 100
    var chatOutputFontSize: Int = DEFAULT_CHAT_OUTPUT_FONT_SIZE

    // 引擎路径配置
    var codexCliPath: String = ""
    var claudeCodeCliPath: String = ""

    // CLI 探测缓存：用于插件冷启动时快速恢复 provider/model，避免先回退到 mock。
    var cachedClaudeAvailable: Boolean = false
    var cachedClaudePath: String = ""
    var cachedClaudeVersion: String = ""
    var cachedClaudeModels: String = ""
    var cachedCodexAvailable: Boolean = false
    var cachedCodexPath: String = ""
    var cachedCodexVersion: String = ""
    var cachedCodexModels: String = ""
    var cachedCliDiscoveryEpochMillis: Long = 0L

    // 默认操作模式
    var defaultOperationMode: String = "DEFAULT"

    // 界面语言偏好（AUTO / ENGLISH / ZH_CN）
    var interfaceLanguage: String = "AUTO"

    // 团队 Prompt 同步配置
    var teamPromptRepoUrl: String = ""
    var teamPromptRepoBranch: String = "main"

    // 团队 Skill 同步配置
    var teamSkillRepoUrl: String = ""
    var teamSkillRepoBranch: String = "main"

    // Skill 生成默认作用域（GLOBAL / PROJECT）
    var skillGenerationScope: String = "PROJECT"

    // Skill 保存目标通道（CODEX / CLUADE / ALL）
    var skillGenerationChannel: String = "ALL"

    // Skill AI 草稿生成 provider（为空时跟随 defaultProvider）
    var skillGenerationProvider: String = ""

    // Skill AI 草稿生成 model id（为空时跟随 selectedCliModel）
    var skillGenerationModel: String = ""

    override fun getState(): SpecCodingSettingsState = this

    override fun loadState(state: SpecCodingSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        const val MIN_CHAT_OUTPUT_FONT_SIZE = 11
        const val MAX_CHAT_OUTPUT_FONT_SIZE = 22
        const val DEFAULT_CHAT_OUTPUT_FONT_SIZE = 13

        fun normalizeChatOutputFontSize(value: Int): Int {
            return value.coerceIn(MIN_CHAT_OUTPUT_FONT_SIZE, MAX_CHAT_OUTPUT_FONT_SIZE)
        }

        fun getInstance(): SpecCodingSettingsState {
            return com.intellij.openapi.components.service()
        }
    }
}
