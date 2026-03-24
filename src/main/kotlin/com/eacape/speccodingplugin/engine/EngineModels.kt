package com.eacape.speccodingplugin.engine

/**
 * 引擎能力枚举
 */
enum class EngineCapability {
    CODE_GENERATION,
    CODE_REVIEW,
    REFACTOR,
    TEST_GENERATION,
    BUG_FIX,
    EXPLANATION,
}

/**
 * 引擎状态
 */
enum class EngineStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

/**
 * 引擎健康检查结果
 */
data class EngineHealthResult(
    val healthy: Boolean,
    val status: EngineStatus,
    val message: String = "",
    val version: String? = null,
)
