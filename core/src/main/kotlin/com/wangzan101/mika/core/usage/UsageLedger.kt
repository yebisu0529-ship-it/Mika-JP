package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** は、どのサービス プロバイダー、どのモデル、どの目的の 1 つのグリッドに属します。 */
data class UsageKey(val providerId: String, val model: String, val purpose: UsagePurpose)

/** 1 つのグリッド内の累積数: 入力 (キャッシュ ヒットを含む)、出力、キャッシュ ヒット トークン、呼び出し数 (使用量は報告されません)。 */
data class UsageCounts(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    operator fun plus(o: UsageCounts) =
        UsageCounts(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    companion object {
        fun of(usage: Usage?, httpAttempts: Int = 1): UsageCounts {
            val calls = httpAttempts.coerceAtLeast(0).toLong()
            val withoutUsage = (calls - if (usage == null) 0 else 1).coerceAtLeast(0)
            return UsageCounts(
                usage?.inputTokens?.toLong() ?: 0,
                usage?.outputTokens?.toLong() ?: 0,
                usage?.cachedInputTokens?.toLong() ?: 0,
                calls = calls,
                callsWithoutUsage = withoutUsage,
            )
        }
    }
}

/** [hour] は UTC 時間バケット、つまり 1970-01-01T00:00Z からの時間です。 */
data class UsageRow(val hour: Long, val key: UsageKey, val counts: UsageCounts)

/** は、現時点での台帳の内容全体です。 [providerNames] は、最後にアカウンティングされたときの各サービス プロバイダーの名前です (サービス プロバイダーが削除しても保持されます)。 */
data class UsageSnapshot(val rows: List<UsageRow>, val providerNames: Map<String, String>) {
    companion object {
        val EMPTY = UsageSnapshot(emptyList(), emptyMap())
    }
}

/**
 * ローカル トークン使用量台帳: UTC 時間 × サービス プロバイダー × モデル × 目的に従って蓄積され、この携帯電話 ([file]、JSON) にのみ存在します。
 *
 * アカウンティング ([record]) は、メモリに 1 つのエントリを追加するだけであり、複数のスレッドが同時に記録しても安全です。変更後、[flushDelayMs] はバックグラウンド スレッドによって完全に書き出されます。
 * (一時ファイルを書き込んでから名前を変更します。書き込み途中で電源を切っても元のファイルは壊れません)。プロセスが強制終了されると、最大でも最後の数秒が失われます。
 * は、最新の [keepDays] 日のみを保持し、それ以前の日は読み取りおよび書き込み時に破棄されます。ファイルが読み取れない（破損している）場合、台帳は空のアカウントとして再起動されます。
 */
class UsageLedger(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushDelayMs: Long = 2_000,
    private val keepDays: Int = KEEP_DAYS,
) : UsageSink {
    private data class Cell(val hour: Long, val key: UsageKey)

    private val lock = Any()
    private val writeLock = Any()
    private val cells = HashMap<Cell, UsageCounts>()
    private val names = HashMap<String, String>()
    private var dirty = false
    private var flushQueued = false

    private val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "usage-ledger").apply { isDaemon = true } }
    }

    private val _version = MutableStateFlow(0L)
    /** エントリごとに 1 つ追加します。インターフェースはそれに応じて再計算します。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        load()
    }

    override fun record(source: UsageSource, usage: Usage?) = record(source, usage, httpAttempts = 1)

    override fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) {
        // 本地组包就失败、从未发请求时,运行记录仍算逻辑调用,但用量账本没有可计的次数或 token。
        if (httpAttempts <= 0 && usage == null) return
        val cell = Cell(Math.floorDiv(now(), HOUR_MS), UsageKey(source.providerId, source.model, source.purpose))
        val counts = UsageCounts.of(usage, httpAttempts)
        val queue = synchronized(lock) {
            cells[cell] = (cells[cell] ?: UsageCounts()) + counts
            if (source.providerName.isNotEmpty()) names[source.providerId] = source.providerName
            dirty = true
            !flushQueued.also { flushQueued = true }
        }
        _version.update { it + 1 }
        if (queue) writer.schedule({ runCatching { flush() } }, flushDelayMs, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): UsageSnapshot = synchronized(lock) {
        UsageSnapshot(cells.map { (c, n) -> UsageRow(c.hour, c.key, n) }, HashMap(names))
    }

    /** 書き込まれていない変更がある場合は、その場で書き込みます (バックグラウンド スレッドが定期的に調整します。テストして終了する前に直接調整することもできます)。書き込みに失敗した場合は、変更を保存して次回再度書き込みます。 */
    fun flush() {
        synchronized(writeLock) {
            val text = synchronized(lock) {
                flushQueued = false
                if (!dirty) return
                dirty = false
                prune()
                serialize()
            }
            try {
                writeAtomically(text)
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                throw e
            }
        }
    }

    private fun cutoffHour(): Long = Math.floorDiv(now(), HOUR_MS) - keepDays * 24L

    private fun prune() {
        val cutoff = cutoffHour()
        cells.keys.removeAll { it.hour < cutoff }
    }

    private fun serialize(): String {
        val keys = cells.keys.map { it.key }.distinct()
        val index = keys.withIndex().associate { (i, k) -> k to i }
        return buildJsonObject {
            put("version", FORMAT)
            putJsonObject("names") { names.forEach { (id, n) -> put(id, n) } }
            putJsonArray("keys") {
                keys.forEach { k -> addJsonObject { put("provider", k.providerId); put("model", k.model); put("purpose", k.purpose.key) } }
            }
            putJsonArray("rows") {
                cells.entries.sortedBy { it.key.hour }.forEach { (c, n) ->
                    addJsonArray {
                        add(JsonPrimitive(c.hour)); add(JsonPrimitive(index.getValue(c.key)))
                        add(JsonPrimitive(n.input)); add(JsonPrimitive(n.output)); add(JsonPrimitive(n.cached))
                        add(JsonPrimitive(n.calls)); add(JsonPrimitive(n.callsWithoutUsage))
                    }
                }
            }
        }.toString()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["names"]?.jsonObject?.forEach { (id, n) -> names[id] = n.jsonPrimitive.content }
            val keys = root["keys"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                UsageKey(
                    o.getValue("provider").jsonPrimitive.content, o.getValue("model").jsonPrimitive.content,
                    UsagePurpose.of(o.getValue("purpose").jsonPrimitive.content),
                )
            }
            val cutoff = cutoffHour()
            root["rows"]?.jsonArray.orEmpty().forEach { e ->
                val r = (e as JsonArray).map { it.jsonPrimitive }
                val hour = r[0].long
                if (hour < cutoff) return@forEach
                val cell = Cell(hour, keys[r[1].int])
                val n = UsageCounts(r[2].long, r[3].long, r[4].long, r[5].long, r[6].long)
                cells[cell] = (cells[cell] ?: UsageCounts()) + n
            }
        }.onFailure {
            cells.clear()
            names.clear()
        }
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.name}" }
        }
    }

    companion object {
        const val KEEP_DAYS = 400
        const val HOUR_MS = 3_600_000L
        private const val FORMAT = 1
    }
}
