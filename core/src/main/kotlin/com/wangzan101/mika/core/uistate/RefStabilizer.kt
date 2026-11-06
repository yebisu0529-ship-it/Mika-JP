package com.wangzan101.mika.core.uistate

import kotlin.math.hypot
import kotlin.math.min

/**
 * はモデルの画面です。[elements] の参照はモデル番号に置き換えられ、[snapshotId] はこの画面のプレフィックスとシリアル番号 (「p1」、「w2」...) です。
 * [port] はポートそのままのスナップショット、[toPort] は型番→ポート番号です。
 */
data class ModelSnapshot(
    val snapshotId: String,
    val port: ScreenSnapshot,
    val elements: List<UiElement>,
    val toPort: Map<String, String>,
) {
    /** はモデル番号のスナップショットに置き換えられます。不可逆判定や座標変換に使用されます。 */
    val screen: ScreenSnapshot by lazy {
        val refs = toPort.entries.associate { (model, original) -> original to model }
        port.copy(
            snapshotId = snapshotId,
            elements = elements,
            windows = port.windows.map { window -> window.copy(nodes = window.nodes.map { it.withRefs(refs) }) },
        )
    }
}

/** 编号解析不出来的原因;[wire] 写进工具结果的 `cause`。 */
enum class StaleCause(val wire: String) {
    /** snapshotId 不是这块屏发过的,或太旧已经不记得。 */
    SnapshotUnknown("snapshot_unknown"),
    /** 编号是给的那一屏之后才有的:新编号配了旧 snapshotId。 */
    RefNotInSnapshot("ref_not_in_snapshot"),
    /** 编号不在最新一屏里:元素没了,或编号写错。 */
    RefRetired("ref_retired"),
    /** 编号还在,但从模型看到的那一屏算起元素挪远了。 */
    ElementMoved("element_moved"),
}

sealed interface Resolved {
    /** [relocated]: モデルは古い画面の snapshotId を示し、その番号は最新の画面の同じ要素を参照しています。 */
    data class Current(val portRef: String, val portSnapshotId: String, val relocated: Boolean) : Resolved
    data class Stale(val cause: StaleCause) : Resolved
}

/** は、古い数値で許可されている最大中心変位、つまり画面の短辺の比率 (ポート取得の上限と一致) に従います。 */
const val STABLE_REF_MAX_SHIFT = RELOCATE_MAX_SHIFT

/** 古い snapshotId は、数画面前まで変換できます。それ以前のものは期限切れとして処理されます。 */
private const val KEEP_CENTERS = 20

/**
 * 読み取り画面全体で安定した要素番号。ポート番号は 1 つの画面読み取り内でのみ有効です。ここでは、2 つの隣接する画面内の変更されていない要素は、同じモデル番号で保持されます。
 * がアクティブになると、ポート番号に変換され、有効期限の判断と取得はポートが引き続き担当します。
 *
 * 古い番号を使用する条件はポート取得 ([relocate]) よりも広くありません。キー (ロール + 名前 + リソース ID) は古い画面と新しい画面の両方で一意です。
 * フロントエンド アプリが変更されていない、トラバーサル シーケンス内の以前の名前付き要素 (コンテキスト) が同じである、またはページ全体が移動せずにまだ所定の位置にある、
 * の中心変位は [STABLE_REF_MAX_SHIFT] の短辺を超えません。リスト内の要素 (スクロール可能なコンテナに含まれる要素) は、スクロールまたは移動しないでください。
 * リスト内で同じ名前の繰り返し要素 (「フォロー」の行) は常に新しい番号を取得します。スクロールすると、同じ位置がすでに別の行にあります。
 * 要素には名前もリソース ID もありません。同じ要素であるかどうかを識別することはできないため、新しい番号が割り当てられます。番号付きタスクは単調に増加し、廃止されたタスクは再利用されなくなります。
 *
 * スナップショット ID は [prefix] です。シリアル番号を追加します: 各画面のプレフィックス。番号を見ればどの画面であるかがわかります。
 */
class RefStabilizer(private val prefix: String = "s") {
    private var nextRef = 1
    private var count = 0
    var latest: ModelSnapshot? = null
        private set
    /** 最新画面の各数字が初めて表示される画面。ある画面から数字が消えると二度と現れないので、【生まれた最新画面】に存在し続けます。 */
    private var born = mapOf<String, Int>()
    /** 最近の [KEEP_CENTERS] 画面内の各型番の中央。 old snapshotId は、変換中の累積変位を制限します。 */
    private val centers = LinkedHashMap<Int, Map<String, Pair<Int, Int>>>()

    fun next(port: ScreenSnapshot): ModelSnapshot {
        val n = ++count
        val prev = latest?.takeIf { it.port.packageName == port.packageName }
        val shortSide = min(port.width, port.height)
        val before = prev?.let { locators(it.screen) }.orEmpty()
        val after = locators(port)
        val still by lazy { stationary(before, after, shortSide * SAME_SPOT_SHIFT) }
        val prevByKey = prev?.elements?.withIndex()?.groupBy { key(it.value) }.orEmpty()
        val newCount = port.elements.groupingBy(::key).eachCount()
        val toPort = LinkedHashMap<String, String>()
        val bornNow = HashMap<String, Int>()
        val elements = port.elements.mapIndexed { i, e ->
            val k = key(e)
            val old = prevByKey[k]?.singleOrNull()?.takeIf { (j, _) ->
                identifiable(e) && newCount[k] == 1 && relocate(before[j], before, after, shortSide) == Relocation.Found(i) &&
                    // リスト項目は再利用されます。ページのみがスクロールされず、同じ場所に残ります。
                    (!(before[j].inList || after[i].inList) || (still && shift(before[j], after[i]) <= shortSide * SAME_SPOT_SHIFT))
            }?.value
            val ref = old?.ref ?: "@e${nextRef++}"
            bornNow[ref] = born[ref] ?: n
            toPort[ref] = e.ref
            e.copy(ref = ref)
        }
        born = bornNow
        centers[n] = elements.associate { it.ref to (it.centerX to it.centerY) }
        while (centers.size > KEEP_CENTERS) centers.remove(centers.keys.first())
        return ModelSnapshot("$prefix$n", port, elements, toPort).also { latest = it }
    }

    fun resolve(ref: String, snapshotId: String): Resolved {
        val cur = latest ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
        val n = snapshotId.removePrefix(prefix).takeIf { snapshotId.startsWith(prefix) }?.toIntOrNull()
            ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
        if (n < 1 || n > count) return Resolved.Stale(StaleCause.SnapshotUnknown)
        // 太旧的一屏连当时有哪些编号都没留着:先报 snapshot_unknown,不能把未知/退役编号误报成 ref_retired。
        if (n != count && n !in centers) return Resolved.Stale(StaleCause.SnapshotUnknown)
        // 编号得真是那一屏里的,模型拼错的组合不放过
        val from = born[ref] ?: return Resolved.Stale(StaleCause.RefRetired)
        if (from > n) return Resolved.Stale(StaleCause.RefNotInSnapshot)
        val portRef = cur.toPort[ref] ?: return Resolved.Stale(StaleCause.RefRetired)
        if (n != count) {
            // 每屏之间都没挪远,累计起来也可能挪远了:从模型看到的那一屏算起
            val then = centers[n]?.get(ref) ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
            val now = centers[count]?.get(ref) ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
            val limit = min(cur.port.width, cur.port.height) * STABLE_REF_MAX_SHIFT
            if (hypot((then.first - now.first).toDouble(), (then.second - now.second).toDouble()) > limit) return Resolved.Stale(StaleCause.ElementMoved)
        }
        return Resolved.Current(portRef, cur.port.snapshotId, relocated = n != count)
    }

    private fun identifiable(e: UiElement) = e.name.isNotBlank() || !e.resourceId.isNullOrBlank()

    private fun key(e: UiElement) = e.role + "\u0000" + e.name + "\u0000" + (e.resourceId ?: "")

    private fun shift(a: ElementLocator, b: ElementLocator) =
        hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble())

    /**
     * はポート読み取りと同じ特性を持っています。コンテキストは、走査順序で前の名前付き要素です。
     * 中心が他のスクロール可能な要素の範囲内にある場合、それらはリストにカウントされます (ポート スナップショットには親子関係がないため、範囲に基づいて推測されます)。
     */
    private fun locators(s: ScreenSnapshot): List<ElementLocator> {
        val panes = s.elements.filter { it.scrollable && it.bounds.size == 4 }
        var last: String? = null
        return s.elements.map { e ->
            val x = normalize(e.centerX, s.width); val y = normalize(e.centerY, s.height)
            val inList = panes.any { p -> p !== e && x in p.bounds[0]..p.bounds[2] && y in p.bounds[1]..p.bounds[3] }
            ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, e.hint, inList)
                .also { if (e.name.isNotBlank()) last = e.name }
        }
    }
}
