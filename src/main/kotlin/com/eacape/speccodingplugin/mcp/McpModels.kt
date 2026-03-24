package com.eacape.speccodingplugin.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * MCP Server 配置
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val transport: TransportType = TransportType.STDIO,
    val autoStart: Boolean = false,
    val trusted: Boolean = false
)

/**
 * 传输类型
 */
enum class TransportType {
    STDIO,  // 标准输入输出
    SSE     // Server-Sent Events
}

fun TransportType.isBuiltInRuntimeSupported(): Boolean = this == TransportType.STDIO

/**
 * MCP Server 状态
 */
enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * MCP 运行日志级别
 */
enum class McpRuntimeLogLevel {
    INFO,
    WARN,
    ERROR,
    STDERR,
}

/**
 * MCP 运行日志事件（由 Client 发出）
 */
data class McpRuntimeLogEvent(
    val level: McpRuntimeLogLevel,
    val message: String,
)

/**
 * MCP 运行日志条目（由 Hub 持久化到内存）
 */
data class McpRuntimeLogEntry(
    val timestampMillis: Long,
    val level: McpRuntimeLogLevel,
    val message: String,
)

data class McpClientUnexpectedTermination(
    val message: String,
    val exitCode: Int? = null,
)

/**
 * MCP Server 实例
 */
data class McpServer(
    var config: McpServerConfig,
    var status: ServerStatus = ServerStatus.STOPPED,
    var process: Process? = null,
    var capabilities: ServerCapabilities? = null,
    var error: String? = null
)

/**
 * Server 能力
 */
@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val resources: ResourcesCapability? = null,
    val prompts: PromptsCapability? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean? = null
)

/**
 * MCP 工具定义
 */
@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement
)

/**
 * MCP 工具调用请求
 */
data class ToolCallRequest(
    val serverId: String,
    val toolName: String,
    val arguments: Map<String, Any>
)

/**
 * MCP 工具调用结果
 */
sealed class ToolCallResult {
    data class Success(
        val content: List<ToolContent>,
        val isError: Boolean = false
    ) : ToolCallResult()

    data class Error(
        val code: Int,
        val message: String,
        val data: Any? = null
    ) : ToolCallResult()
}

/**
 * 工具内容
 */
@Serializable
data class ToolContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null
)

/**
 * JSON-RPC 请求
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null
)

/**
 * JSON-RPC 响应
 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

/**
 * JSON-RPC 错误
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * JSON-RPC 通知
 */
@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

/**
 * Initialize 请求参数
 */
@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities,
    val clientInfo: ClientInfo
)

/**
 * 客户端能力
 */
@Serializable
data class ClientCapabilities(
    val experimental: Map<String, JsonElement> = emptyMap(),
    val sampling: Map<String, JsonElement> = emptyMap()
)

/**
 * 客户端信息
 */
@Serializable
data class ClientInfo(
    val name: String,
    val version: String
)

/**
 * Initialize 响应结果
 */
@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo
)

/**
 * Server 信息
 */
@Serializable
data class ServerInfo(
    val name: String,
    val version: String
)

/**
 * Tools/List 响应
 */
@Serializable
data class ToolsListResult(
    val tools: List<McpTool>
)

/**
 * Tools/Call 请求参数
 */
@Serializable
data class ToolsCallParams(
    val name: String,
    val arguments: JsonElement? = null
)

/**
 * Tools/Call 响应结果
 */
@Serializable
data class ToolsCallResult(
    val content: List<ToolContent>,
    val isError: Boolean? = null
)

/**
 * MCP 协议版本
 */
object McpProtocol {
    const val VERSION = "2024-11-05"
    const val CLIENT_NAME = "Spec Code"
    const val CLIENT_VERSION = "0.2.0"
}

/**
 * MCP 方法名
 */
object McpMethods {
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val PROMPTS_LIST = "prompts/list"
    const val PROMPTS_GET = "prompts/get"
}

/**
 * JSON-RPC 错误码
 */
object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}
