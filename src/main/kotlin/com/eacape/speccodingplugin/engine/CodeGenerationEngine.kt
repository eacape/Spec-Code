package com.eacape.speccodingplugin.engine

import kotlinx.coroutines.flow.Flow

/**
 * 代码生成引擎接口
 * 抽象 Codex CLI / Claude Code CLI 等后端引擎
 */
interface CodeGenerationEngine {

    /** 引擎唯一标识 */
    val id: String

    /** 引擎显示名称 */
    val displayName: String

    /** 引擎支持的能力 */
    val capabilities: Set<EngineCapability>

    /** 当前状态 */
    val status: EngineStatus

    /** 启动引擎 */
    suspend fun start()

    /** 停止引擎 */
    suspend fun stop()

    /** 健康检查 */
    suspend fun healthCheck(): EngineHealthResult

    /** 生成代码（一次性返回） */
    suspend fun generate(request: EngineRequest): EngineResponse

    /** 流式生成代码 */
    fun stream(request: EngineRequest): Flow<EngineChunk>

    /** 估算 Token 数 */
    fun estimateTokens(content: String): Int
}
