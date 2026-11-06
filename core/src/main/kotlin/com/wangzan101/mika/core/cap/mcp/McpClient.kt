package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** トランスポート層は、これら 2 つの操作を実装するだけで済みます。ツールは実行デプロイメントごとに 1 回呼び出され、その後ディレクトリは変更されません。 */
interface McpEndpoint {
    val serverId: String
    fun tools(): List<McpToolDescriptor>
    suspend fun call(name: String, arguments: JsonObject): McpResult

    /** 接続中にサーバーから読み取られたリソース ディレクトリ。サポートされていない場合は空になります。 */
    fun resources(): List<McpResourceDescriptor> = emptyList()
    suspend fun readResource(uri: String): McpResult =
        McpResult("MCP server does not support resources", isError = true)

    /** 接続時にサーバーから読み取られるプロンプト単語テンプレート。サポートされていない場合は空になります。 */
    fun prompts(): List<McpPromptDescriptor> = emptyList()
    suspend fun getPrompt(name: String, arguments: JsonObject): McpResult =
        McpResult("MCP server does not support prompts", isError = true)
}

data class McpAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpAnnotations = McpAnnotations(),
    val estimatedCostUnits: Long = 0,
)

data class McpResult(val text: String, val isError: Boolean = false, val costUnits: Long = 0)

/** resource/list;v1 の項目は、モデルを表示するために uri/name/description のみを使用します。 */
data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String = "",
    val mimeType: String? = null,
)

/** プロンプト/リストの項目。 */
data class McpPromptDescriptor(
    val name: String,
    val description: String = "",
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptArgument(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

/** 各サービスは個別に構成されます。 Restricted は、サービスが要求する読み取り専用アノテーションを受け入れません。 */
enum class McpTrust { Restricted, TrustAnnotations }

/** 長い結果の完全なテキストはホストによって保存されます。返される参照には、ファイル パスまたはリソース ID のみが含まれます。 */
fun interface McpResultStore { fun save(serverId: String, toolName: String, content: String): String }

data class McpConnection(
    val endpoint: McpEndpoint,
    val resultStore: McpResultStore? = null,
    val maxCostUnits: Long? = null,
    val previewChars: Int = 4000,
    val trust: McpTrust = McpTrust.Restricted,
    /** はこれらのツールのみを公開します。 null はすべてを意味し、空のセットは何も意味しません。 */
    val allowedTools: Set<String>? = null,
) {
    init { require(previewChars > 0 && (maxCostUnits == null || maxCostUnits >= 0)) }
}

/** 読み取り専用の注釈がない場合は、書き込み可能として扱われます。破壊的でオープンワールドの操作は、不可逆的で外部的な操作として記録されます (承認記録には、タスクが外部のことを行ったことがわかります)。 */
fun mcpApprovalRisk(a: McpAnnotations, trust: McpTrust = McpTrust.TrustAnnotations): ApprovalRisk = when {
    a.destructiveHint == true -> ApprovalRisk(ActionRisk.Irreversible)
    a.openWorldHint == true -> ApprovalRisk(ActionRisk.External)
    a.readOnlyHint == true && trust == McpTrust.TrustAnnotations -> ApprovalRisk(ActionRisk.Read)
    else -> ApprovalRisk(ActionRisk.Change)
}

internal class McpCapability(connection: McpConnection, override val id: String) : Capability {
    private val endpoint = connection.endpoint
    private val budget = McpBudget(connection.maxCostUnits)
    private val listed = endpoint.tools()
        .filter { connection.allowedTools == null || it.name in connection.allowedTools }
    private val descriptors = listed.distinctBy { it.name }
    private val toolNames = mcpToolNames(endpoint.serverId, descriptors.map { it.name })
    private val serverToolNames = descriptors.map { mcpSafe(it.name) }.toSet()
    private val assemblyFailures = mutableListOf<String>()
    private val tools = buildList {
        listed.groupingBy { it.name }.eachCount().filterValues { it > 1 }.forEach { (name, count) ->
            assemblyFailures += "tool '$name': listed $count times, only the first is used"
        }
        descriptors.forEachIndexed { index, descriptor ->
            val name = toolNames[index]
            val assembled = assembleMcpTool(id, endpoint, descriptor, name) {
                McpClientTool(id, endpoint, descriptor, name, budget, connection.resultStore, connection.previewChars, connection.trust)
            }
            add(assembled.tool)
            assembled.failure?.let(assemblyFailures::add)
        }
        try {
            resourceTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "resource_read: ${mcpAssemblyReason(e)}"
        }
        try {
            promptTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "prompt_get: ${mcpAssemblyReason(e)}"
        }
    }
    override fun status(): CapabilityStatus = if (assemblyFailures.isEmpty()) CapabilityStatus.Available
        else CapabilityStatus.Degraded(assemblyFailures.joinToString("; "))
    override fun tools(): List<AgentTool> = tools
    override fun sections(env: PromptEnv): List<String> = if (assemblyFailures.isEmpty()) emptyList() else listOf(
        "MCP サーバー '${endpoint.serverId}' の一部を構成できませんでした。他のツールは引き続き利用できます。\n" +
            assemblyFailures.joinToString("\n") { "- $it" },
    )

    /** リソース/読み取りは読み取り専用ツールに合成されます。サーバーに同じ名前のツールがある場合、それがサーバーに与えられます。 */
    private fun resourceTool(connection: McpConnection): AgentTool? {
        val resources = endpoint.resources()
        if (resources.isEmpty() || "resource_read" in serverToolNames) return null
        val available = resources.take(30).joinToString(", ") { it.uri }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "resource_read"),
                description = "Read a resource from the MCP server. Available: $available".take(4000),
                props = mapOf("uri" to buildJsonObject {
                    put("type", "string")
                    put("description", "Resource URI from the list: $available".take(4000))
                }),
                required = listOf("uri"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "resource_read",
            call = { args -> endpoint.readResource(args["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()) },
        )
    }

    /** プロンプト/ゲットは読み取り専用ツールに合成され、パラメータ名と説明はプロンプト/リストから取得されます。 */
    private fun promptTool(connection: McpConnection): AgentTool? {
        val prompts = endpoint.prompts()
        if (prompts.isEmpty() || "prompt_get" in serverToolNames) return null
        val names = prompts.take(30).joinToString(", ") { it.name }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "prompt_get"),
                description = "Get a prompt template from the MCP server. Available: $names".take(4000),
                props = mapOf(
                    "name" to buildJsonObject {
                        put("type", "string")
                        put("description", "Prompt name: $names".take(4000))
                    },
                    "arguments" to buildJsonObject {
                        put("type", "object")
                        put("description", "Prompt arguments (see the prompt description on the server)")
                    },
                ),
                required = listOf("name"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "prompt_get",
            call = { args ->
                val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                endpoint.getPrompt(name, args["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
            },
        )
    }
}

internal data class McpToolAssembly(val tool: AgentTool, val failure: String?)

/** 不正な宣言を持つツールだけを無効にし、名前と構成エラーを呼出し側へ返します。 */
internal fun assembleMcpTool(
    capability: String,
    endpoint: McpEndpoint,
    descriptor: McpToolDescriptor,
    name: String,
    assemble: () -> AgentTool,
): McpToolAssembly = try {
    McpToolAssembly(assemble(), null)
} catch (e: Exception) {
    val reason = mcpAssemblyReason(e)
    McpToolAssembly(
        McpFailedTool(capability, endpoint.serverId, descriptor, name, reason),
        "tool '${descriptor.name}': $reason",
    )
}

internal fun mcpAssemblyReason(e: Exception): String {
    val type = e::class.simpleName ?: "Exception"
    val detail = e.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return "$type$detail".replace(Regex("\\s+"), " ").take(500)
}

internal const val MCP_TOOL_NAME_MAX = 64
private const val MCP_INITIAL_HASH_CHARS = 8
private const val MCP_MAX_HASH_CHARS = 56

/**
 * 生成稳定且全局唯一的工具名。未超限、清洗后也不撞名的声明保持原样；其余声明按原工具名排序分配短名，
 * 因此服务端返回顺序不影响结果。短名与已有名字再次相撞时增加摘要长度，而不是把冲突留到注册阶段。
 */
internal fun mcpToolNames(serverId: String, toolNames: List<String>): List<String> {
    require(toolNames.size == toolNames.toSet().size) { "duplicate MCP tool names: $toolNames" }
    val plain = toolNames.map { mcpPlainToolName(serverId, it) }
    val counts = plain.groupingBy { it }.eachCount()
    val shortened = toolNames.indices.filter { plain[it].length > MCP_TOOL_NAME_MAX || counts.getValue(plain[it]) > 1 }
    val assigned = toolNames.indices.filterNot { it in shortened }.associate { toolNames[it] to plain[it] }.toMutableMap()
    val used = assigned.values.toMutableSet()
    shortened.map { toolNames[it] }.sorted().forEach { name ->
        var hashChars = MCP_INITIAL_HASH_CHARS
        var round = 0
        while (true) {
            val candidate = mcpShortToolName(serverId, name, hashChars, round)
            if (used.add(candidate)) {
                assigned[name] = candidate
                break
            }
            if (hashChars < MCP_MAX_HASH_CHARS) hashChars += 8 else round++
        }
    }
    return toolNames.map { assigned.getValue(it) }
}

/** 普通格式保留完整的服务编号和工具名；超长或清洗撞名时改成带摘要的短格式。 */
internal fun mcpToolName(serverId: String, toolName: String, short: Boolean = false): String {
    val plain = mcpPlainToolName(serverId, toolName)
    return if (!short && plain.length <= MCP_TOOL_NAME_MAX) plain
        else mcpShortToolName(serverId, toolName, MCP_INITIAL_HASH_CHARS, 0)
}

private fun mcpPlainToolName(serverId: String, toolName: String): String =
    "mcp_${mcpSafe(serverId)}__${mcpSafe(toolName)}"

private fun mcpShortToolName(serverId: String, toolName: String, hashChars: Int, round: Int): String {
    val material = if (round == 0) "$serverId\u0000$toolName" else "$round\u0000$serverId\u0000$toolName"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    val hash = digest.take(hashChars / 2).joinToString("") { "%02x".format(it) }
    val head = "mcp_${mcpSafe(serverId).take(8)}__${mcpSafe(toolName)}"
    return head.take(MCP_TOOL_NAME_MAX - hashChars - 1) + "_" + hash
}

private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_]")
internal fun mcpSafe(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_")

private class McpFailedTool(
    override val capability: String,
    private val serverId: String,
    private val descriptor: McpToolDescriptor,
    name: String,
    reason: String,
) : AgentTool {
    private val message = "MCP サーバー '$serverId' のツール '${descriptor.name}' を構成できませんでした: $reason"
    override val def = ToolDef(
        name,
        "${descriptor.description}\n\n利用できません: $message".trim(),
    )
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", "mcp_tool_assembly_failed")
        put("server", serverId)
        put("tool", descriptor.name)
        put("message", message)
        put("recoverable", false)
    })
}

private class McpBudget(private val limit: Long?) {
    private var used = 0L
    @Synchronized fun charge(units: Long): Boolean {
        if (units < 0 || limit != null && units > limit - used) return false
        used += units
        return true
    }
}

private class McpClientTool(
    override val capability: String,
    private val endpoint: McpEndpoint,
    private val descriptor: McpToolDescriptor,
    name: String,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    trust: McpTrust,
) : AgentTool {
    private val schema = descriptor.inputSchema
    private val approval = mcpApprovalRisk(descriptor.annotations, trust)
    override val def: ToolDef = ToolDef(
        name,
        descriptor.description,
        (schema["properties"] as? JsonObject).orEmpty()
            .mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) },
        (schema["required"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
    )
    override val verb = Verb.Interact
    override val risk = when (approval.level) {
        ActionRisk.Read -> Risk.Read
        ActionRisk.Change -> Risk.Write
        else -> Risk.External
    }
    override val approvalRisk: ApprovalRisk = approval
    override val offScreen = true
    override val logged = risk != Risk.Read

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        serverId = endpoint.serverId,
        storeName = descriptor.name,
        budget = budget,
        costUnits = descriptor.estimatedCostUnits,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { endpoint.call(descriptor.name, a.o) },
    )
}

/** リソース/プロンプト 合成読み取り専用ツール。結果クリッピング、予算設定、信頼できないマーキング、および一般的な MCP ツールは同じセットを共有します。 */
private class McpSyntheticTool(
    override val capability: String,
    private val serverId: String,
    override val def: ToolDef,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    private val storeName: String,
    private val call: suspend (JsonObject) -> McpResult,
) : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true
    override val logged = false

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        serverId = serverId,
        storeName = storeName,
        budget = budget,
        costUnits = 0,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { call(a.o) },
    )
}

private suspend fun mcpExecution(
    capability: String,
    serverId: String,
    storeName: String,
    budget: McpBudget,
    costUnits: Long,
    resultStore: McpResultStore?,
    previewChars: Int,
    call: suspend () -> McpResult,
): ToolExecution {
    if (!budget.charge(costUnits)) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_cost_limit")
    })
    val result = call()
    val long = result.text.length > previewChars
    val ref = if (long) resultStore?.save(serverId, storeName, result.text) else null
    if (long && ref == null) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_result_too_large")
    })
    val preview = if (long) result.text.take(previewChars) else result.text
    return ToolExecution(buildJsonObject {
        put("ok", !result.isError)
        put("text", Untrusted.wrap(preview))
        ref?.let { put("fullResult", it) }
        if (long) put("truncated", true)
    }, observations = listOf(Observation(ObsKind.Output, preview, source = capability, trust = Trust.Untrusted)))
}
