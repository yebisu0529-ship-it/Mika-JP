package com.wangzan101.mika.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunMetricsStore
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunRecords
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.record.RunSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private const val TAG = "RunStore"
private const val MAX_WIDTH = 720
private const val JPEG_QUALITY = 70

/**
 * ランニング レコードのダウンロード: run.json (概要)、events.jsonl (1 行に 1 つのイベント) の下の `files/runs/<yyyyMMdd-HHmmss>-<6位>/`、
 * 画面/NNNN.jpg (画面読み取り画面)。最新の [keep] 時間のみが保持されます。ディスク書き込みエラーが発生した場合、ログのみが記録され、業務には影響しません。
 * 各実行の概要 ([RunMetrics]) は、`files/run-metrics.jsonl` ([metrics]) に個別に保存され、レコードと一緒にクリアされません。
 */
class RunStore internal constructor(
    filesDir: File,
    private val externalDir: () -> File?,
    private val keep: Int,
    private val clock: () -> Long,
    private val encode: (ByteArray) -> ByteArray?,
) {
    constructor(context: Context, keep: Int = 30) : this(
        context.applicationContext.filesDir,
        { context.applicationContext.getExternalFilesDir(null) },
        keep,
        System::currentTimeMillis,
        ::toJpeg,
    )

    init { require(keep > 0) { "keep must be positive" } }

    private val root = File(filesDir, "runs")

    /** 各実行の概要。実行記録がオフになると、書き込みスレッドは 1 つのコピーを記録します ([RunHandle] を参照)。 */
    val metrics = RunMetricsStore(File(filesDir, RunMetricsStore.FILE_NAME))

    fun open(goal: String): RunHandle {
        val start = clock()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(start))
        val suffix = (1..6).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        val id = "$stamp-$suffix"
        val dir = File(root, id)
        dir.mkdirs()
        val handle = RunHandle(id, dir, RunSummary(id = id, goal = Redactor.text(goal), startedAtMs = start), encode, metrics::append)
        prune()
        return handle
    }

    /** 新しい順。 */
    fun list(): List<RunSummary> = runDirs().reversed().mapNotNull { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** 実行の概要。レコードはクリアされているか、読み取ることができず、null です。 */
    fun summary(runId: String): RunSummary? = dirOf(runId)?.let { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** は読み取り専用でコアに渡されます (この会話の前のラウンドの記録を確認してください。history_read を参照してください)。 IO スレッドで読み取ります。 */
    fun reader(): RunRecords = object : RunRecords {
        override suspend fun summary(runId: String) = withContext(Dispatchers.IO) { this@RunStore.summary(runId) }
        override suspend fun events(runId: String) = withContext(Dispatchers.IO) { this@RunStore.events(runId) }
    }

    fun events(runId: String): List<RunEvent> {
        val f = File(dirOf(runId) ?: return emptyList(), EVENTS)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
    }

    /** 既存の JSONL から使用法フィールドを元の順序で取得します。古いレコードの null 値、同じステップに対する複数の応答、ステップ番号のない圧縮イベントはそのまま残されます。 */
    fun usageLog(runId: String): String {
        val dir = dirOf(runId) ?: throw FileNotFoundException("Run is unavailable")
        return File(dir, EVENTS).useLines { lines ->
            buildString {
                for ((index, line) in lines.withIndex()) {
                    if (line.isBlank()) continue
                    val event = try {
                        RunEvent.json.parseToJsonElement(line) as? JsonObject
                    } catch (_: SerializationException) { null }
                    val type = event?.get("type") as? JsonPrimitive
                    if (event == null || type == null) {
                        // プロセスの中断により、未完了のレコード行が残る可能性があります。ギャップをマークし、残りのイベントを保持します。
                        appendLine("""{"type":"unreadable_event","line":${index + 1}}""")
                        continue
                    }
                    val fields = USAGE_FIELDS[type.content] ?: continue
                    val metadata = event.filter { (key, value) ->
                        value is JsonPrimitive && (key == "type" || key == "atMs" || key in fields)
                    }
                    appendLine(JsonObject(metadata).toString())
                }
            }.ifEmpty { throw IOException("No run usage events") }
        }
    }

    /**
     * 実行レコードがオフになった後、追加のイベントが記録されます (結果は回答の提出後に判断されます)。events.jsonl に追加され、run.json の概要に含まれます。
     * レコードがクリアされたか、ディスクへの書き込みに失敗しました。ログのみが記録され、タスクには影響しません。
     */
    @Synchronized
    fun append(runId: String, e: RunEvent) {
        val dir = dirOf(runId) ?: return
        runCatching {
            File(dir, EVENTS).appendText(RunEvent.encode(e) + "\n")
            val summaryFile = File(dir, RUN_JSON)
            val summary = RunEvent.json.decodeFromString(RunSummary.serializer(), summaryFile.readText()).with(e)
            val tmp = File(dir, "$RUN_JSON.tmp")
            tmp.writeText(RunEvent.json.encodeToString(RunSummary.serializer(), summary))
            if (!tmp.renameTo(summaryFile)) error("rename failed")
            if (e is RunEvent.OutcomeJudged) metrics.update(runId) { it.copy(judged = e.outcome) }
        }.onFailure { runCatching { Log.w(TAG, "could not append to run $runId", it) } }
    }

    fun screen(runId: String, id: Int): File? = dirOf(runId)?.let { File(it, screenPath(id)) }?.takeIf { it.isFile }

    /** は、(デバッグと評価の目的で) 開発マシンに簡単にプルできるように、外部ファイル ディレクトリの run/ にコピーされます。最新の [keep] 時間だけがそこに残ります。 */
    fun mirrorToExternal(runId: String) {
        val ext = File(externalDir() ?: return, "runs")
        if (copyTo(runId, File(ext, runId))) runCatching { dirsIn(ext).dropLast(keep).forEach { it.deleteRecursively() } }
    }

    /** 実行のディレクトリ全体を [dest] にコピーします (評価では、各質問のレコードが独自の結果ディレクトリに配置されます)。 */
    fun copyTo(runId: String, dest: File): Boolean {
        val src = dirOf(runId) ?: return false
        return runCatching { src.copyRecursively(dest, overwrite = true) }
            .onFailure { runCatching { Log.w(TAG, "could not copy run $runId", it) } }
            .getOrDefault(false)
    }

    /** は、他のパスが指定されるのを防ぐために、このディレクトリ内で実際の操作のみを受け入れます。 */
    private fun dirOf(runId: String): File? =
        File(root, runId).takeIf { ID_RE.matches(runId) && it.isDirectory }

    private fun runDirs(): List<File> = dirsIn(root)

    private fun dirsIn(parent: File): List<File> =
        parent.listFiles { f -> f.isDirectory && ID_RE.matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun prune() {
        runDirs().dropLast(keep).forEach { it.deleteRecursively() }
    }

    companion object {
        internal const val RUN_JSON = "run.json"
        internal const val EVENTS = "events.jsonl"
        private val USAGE_FIELDS = mapOf(
            "task_started" to setOf("model"),
            "model_reply" to setOf("step", "model", "role", "tool", "inputTokens", "cachedTokens",
                "outputTokens", "repliedAtMs", "latencyMs", "retryWaitMs"),
            "compressed" to setOf("foldedSteps"),
            "model_calls" to setOf("calls", "httpCalls", "unreported", "httpUnreported", "inputTokens", "cachedTokens", "outputTokens"),
            "task_ended" to setOf("outcome", "kind"),
        )
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val ID_RE = Regex("""\d{8}-\d{6}-[a-z0-9]{6}""")

        internal fun screenPath(id: Int) = "screens/%04d.jpg".format(Locale.US, id)
    }
}

/**
 * 実行の記録。タスクの最後には [close]。
 * は、専用スレッドでディスクに書き込み、イメージを圧縮します。 [event]/[screen] はキューに参加するだけで、エージェント ループをブロックすることはありません。概要 ([RunMetrics]) もこのスレッドに書き込まれ、計算されます。
 * は最後のイベントを書き込み、それを [finished] に渡します (以前に実行されている場合のみ)。
 * 画面バックログが [MAX_PENDING_FRAMES] を超えると、最も古いものが失われます。 [close] は、キューの書き込みが完了するまで待機します (最大 [CLOSE_WAIT_MS])。
 */
class RunHandle internal constructor(
    val runId: String,
    private val dir: File,
    private var summary: RunSummary,
    private val encode: (ByteArray) -> ByteArray?,
    private val finished: (RunMetrics) -> Unit = {},
) : RunSink, Closeable {
    private val lock = Object()
    private val events = ArrayDeque<RunEvent>()
    private val frames = ArrayDeque<Pair<Int, ByteArray>>()
    private var closing = false
    private var sinceSummary = 0
    private val metrics = RunMetrics.Builder(runId)
    private val writer = Thread(::drain, "mika-run-$runId").apply { isDaemon = true }

    init {
        writeSummary()
        writer.start()
    }

    override fun event(e: RunEvent) {
        synchronized(lock) {
            if (closing) return
            events.addLast(e)
            lock.notifyAll()
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        synchronized(lock) {
            if (closing) return
            if (frames.size >= MAX_PENDING_FRAMES) frames.removeFirst()
            frames.addLast(mediaId to png)
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            lock.notifyAll()
        }
        writer.join(CLOSE_WAIT_MS)
    }

    private fun drain() {
        while (true) {
            var batch: List<RunEvent> = emptyList()
            var frame: Pair<Int, ByteArray>? = null
            var done = false
            synchronized(lock) {
                while (events.isEmpty() && frames.isEmpty() && !closing) lock.wait()
                if (events.isEmpty() && frames.isEmpty()) {
                    writeSummary()
                    done = true
                } else {
                    batch = events.toList().also { events.clear() }
                    // イベントの優先順位。一度に 1 枚ずつ写真を撮り、途中で戻って新しいイベントがあるかどうかを確認します
                    frame = if (batch.isEmpty()) frames.removeFirstOrNull() else null
                }
            }
            if (done) {
                // 概要はロックの外側に書き込まれます。別のファイルに書き込まれ、他のスレッドをブロックしません。
                metrics.build()?.let { m -> safely("metrics") { finished(m) } }
                return
            }
            batch.forEach(::write)
            frame?.let { (id, png) -> writeFrame(id, png) }
        }
    }

    private fun write(e: RunEvent) {
        // は最初に概要に含まれ、その後追加されます。追記に失敗しても結末はrun.jsonに書き込まれる
        summary = summary.with(e)
        metrics.add(e)
        safely("event") { File(dir, RunStore.EVENTS).appendText(RunEvent.encode(e) + "\n") }
        sinceSummary += 1
        // 定期更新の概要: プロセスが強制終了されると、run.json におおよそのステップ数と使用量も表示されます。
        if (e is RunEvent.TaskEnded || sinceSummary >= SUMMARY_EVERY) writeSummary()
    }

    private fun writeFrame(id: Int, png: ByteArray) {
        val jpg = safely("screen") { encode(png) } ?: return
        safely("screen") {
            val f = File(dir, RunStore.screenPath(id))
            f.parentFile?.mkdirs()
            f.writeBytes(jpg)
        }
    }

    private fun writeSummary() {
        sinceSummary = 0
        safely("summary") {
            val tmp = File(dir, "${RunStore.RUN_JSON}.tmp")
            tmp.writeText(RunEvent.json.encodeToString(RunSummary.serializer(), summary))
            if (!tmp.renameTo(File(dir, RunStore.RUN_JSON))) error("rename failed")
        }
    }

    private inline fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        runCatching { Log.w(TAG, "could not write run $what", e) }
        null
    }

    internal companion object {
        const val MAX_PENDING_FRAMES = 4
        const val SUMMARY_EVERY = 10
        const val CLOSE_WAIT_MS = 10_000L
    }
}

/** は、まずサイズのみを読み取り、2 の累乗でダウンサンプリングしてデコードしてから、幅 ≤ 720、JPEG 品質 70 に縮小します。 */
private fun toJpeg(png: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
    val src = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scaled = if (src.width <= MAX_WIDTH) src
    else Bitmap.createScaledBitmap(src, MAX_WIDTH, (src.height.toLong() * MAX_WIDTH / src.width).toInt().coerceAtLeast(1), true)
    return try {
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    } finally {
        if (scaled !== src) scaled.recycle()
        src.recycle()
    }
}
