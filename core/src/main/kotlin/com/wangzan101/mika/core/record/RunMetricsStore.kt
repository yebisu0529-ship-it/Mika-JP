package com.wangzan101.mika.core.record

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * が要約 ([RunMetrics]) を実行するたびに、行ごとに 1 つのコピーが [file] (JSON 行) に存在し、最新の [keep] コピーのみが保持されます。最後の 30 回の実行のみが記録されます。
 * 概要が明確ではなく、統計が何度も表示される可能性があります。実行の最後にメモを作成し、答えを渡し、追加する前に結末を判断します ([update])。
 * 読み取れない行はスキップされます。ファイル全体が破損している場合は、空としてカウントされます。書き込み時には、ファイル全体が一時ファイルに書き込まれ、その後置き換えられます。書き込みに失敗すると、今度は失われるだけです。
 * 同じファイルに複数のインスタンス (各ページおよび各実行に 1 つ) が存在する場合があり、プロセス内の同じロックが読み取りと書き込みに使用されます。
 */
class RunMetricsStore(private val file: File, private val keep: Int = DEFAULT_KEEP) {
    init { require(keep > 0) { "keep must be positive" } }

    /** 古い順。 */
    fun all(): List<RunMetrics> = synchronized(LOCK) { read() }

    /**
     * 実行番号ごとのこれらの実行の概要。含まれないものは含まれません。これらの数値を含む行のみを解析します (解析後に数値を確認します)。
     * は、ファイル全体 (最大 [keep] コピーまで) を解析しません。
     */
    fun byIds(runIds: Set<String>): Map<String, RunMetrics> = synchronized(LOCK) {
        val ids = runIds.filter { it.isNotEmpty() }
        if (ids.isEmpty() || !file.isFile) return@synchronized emptyMap()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        lines.asSequence()
            .filter { line -> ids.any { line.contains(it) } }
            .mapNotNull { runCatching { JSON.decodeFromString(RunMetrics.serializer(), it) }.getOrNull() }
            .filter { it.runId in runIds }
            .associateBy { it.runId }
    }

    /** 最新の [n] コピー (古いものから順)。 [n] が null の場合はすべて。 */
    fun recent(n: Int?): List<RunMetrics> = all().let { if (n == null) it else it.takeLast(n) }

    /** コピーを 1 部覚えておいてください。同じ実行内で既存のものを置き換えます。 */
    fun append(m: RunMetrics) = synchronized(LOCK) {
        write(read().filter { m.runId.isEmpty() || it.runId != m.runId } + m)
    }

    /** 一度実行されるコピーを変更します。そうでなければ動きません。 */
    fun update(runId: String, change: (RunMetrics) -> RunMetrics) = synchronized(LOCK) {
        val list = read()
        if (list.none { it.runId == runId }) return@synchronized
        write(list.map { if (it.runId == runId) change(it) else it })
    }

    private fun read(): List<RunMetrics> {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.filter { it.isNotBlank() }.mapNotNull { runCatching { JSON.decodeFromString(RunMetrics.serializer(), it) }.getOrNull() }
    }

    private fun write(list: List<RunMetrics>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            try {
                tmp.writeText(list.takeLast(keep).joinToString("") { JSON.encodeToString(RunMetrics.serializer(), it) + "\n" })
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_KEEP = 500
        const val FILE_NAME = "run-metrics.jsonl"
        private val LOCK = Any()
        val JSON = Json { ignoreUnknownKeys = true }

        /** は人間による分析用にエクスポートされます。JSON 配列として、最も古いものから順にエクスポートされます。 */
        fun export(list: List<RunMetrics>): String = EXPORT.encodeToString(ListSerializer(RunMetrics.serializer()), list)

        private val EXPORT = Json { encodeDefaults = true }
    }
}
