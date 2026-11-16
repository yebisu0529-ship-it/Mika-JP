package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.prompt.Feedback
import java.io.File

private val GENERIC = setOf("app", "apps", "web", "www", "site", "browser")

// 一般的すぎる中国語の単語: 明示的に名前が付けられている場合は、「open/enter/go」+単語のみがカウントされます (「アラームを設定する」はカウントされません)
private val GENERIC_ZH = setOf(
    "设置", "浏览器", "搜索", "地图", "相机", "相册", "日历", "时钟", "天气", "音乐",
    "视频", "文件", "短信", "电话", "邮件", "商城", "应用", "手机",
)
private val NAMING_PREFIXES = listOf("打开", "进入", "去")

private val GENERIC_JA = setOf("設定", "ブラウザ", "ブラウザー", "検索", "地図", "カメラ", "写真", "カレンダー", "時計", "天気", "音楽", "動画", "ファイル", "電話", "メール")

/** 「アラームを設定して」を設定アプリの起動依頼と取り違えない。 */
private fun namesJapaneseApp(text: String, name: String): Boolean =
    Regex(Regex.escape(name) + "(?:アプリ)?(?:を|で|から)?(?:開|起動|見|確認)").containsMatchIn(text) ||
        text.contains("${name}アプリ")

// 共通システムアプリ名。ブランド単語の直後にある場合（「あるブランドのアプリケーション マーケット」）、それはブランド独自のモールやその他のアイテムではなく、このアプリについて話しています。
private val APP_KINDS = listOf(
    "应用市场", "应用商店", "浏览器", "钱包", "日历", "时钟", "相机", "图库", "文件管理", "主题", "云空间",
    "运动健康", "计算器", "便签", "备忘录", "录音机", "游戏中心", "手机管家", "天气",
    "ブラウザ", "ブラウザー", "ウォレット", "カレンダー", "時計", "カメラ", "ギャラリー", "ファイル", "メモ", "電卓", "天気",
)

/**
 * 組み込みナレッジ + ユーザー補足: [notes] エントリ名 (アプリ名またはサイトのドメイン名) に従って、対応するエントリのテキストに追加し、ユーザー自身が作成したものであることを示します。
 * [learnedNotes] は、Mika の独学によるアプリの知識のサプリメントです。最後に添付されており、試行中か検証済みかを示します。
 * の一致は決定的な文字列一致であり、モデルを呼び出しません。
 */
class KnowledgeStore(private val bundled: List<KnowledgeEntry>, notes: Map<String, String> = emptyMap(),
    learnedNotes: Map<String, LearnedNote> = emptyMap()) {
    private val entries: List<KnowledgeEntry> = bundled.map { e ->
        val personal = notes[e.name]
        val learned = learnedNotes[e.name]
        if (personal == null && learned == null) return@map e
        e.copy(body = listOfNotNull(e.body, personal?.let { "$NOTES_HEADER\n$it" },
            learned?.let { Feedback.format(if (it.trial) "knowledge.learned_trial" else "knowledge.learned", "notes" to it.text) }).joinToString("\n\n"),
            trial = learned?.trial == true)
    }

    /** エントリ [name] 自体の組み込みテキスト (ユーザー補足および自習補足なし)。自習用サプリメントを作成する際の「既存の説明」として使用してください。 */
    fun builtIn(name: String): String? = bundled.firstOrNull { it.name == name && it.body != null }?.body

    private fun pick(c: List<KnowledgeEntry>): KnowledgeEntry? = c.firstOrNull { it.body != null } ?: c.firstOrNull()

    fun byPackage(pkg: String): KnowledgeEntry? = pick(entries.filter { pkg in it.packages })

    /** ホスト名ペアに関するサイトの知識 (最も具体的なドメイン名が最初、[SiteMatch] を参照)。 */
    fun byDomain(host: String): KnowledgeEntry? = pick(SiteMatch.best(host, entries) { it.domains })

    /**
     * タスク テキストのエントリをヒットします。インストールされているアプリを含むエントリが最初にランク付けされ、次にテキストを含むエントリがランク付けされ、次にヒットした単語の長さの順にランク付けされます (長いほど具体的になります)。
     * [installed] はインストールされているアプリ (パッケージ名、表示名): ヒットワードの後にインストールされているアプリの名前が続く場合、
     * はそのアプリ (「特定のブランド」+「App Market」など) について話しているため、ヒットとしてカウントされません。
     */
    fun matchTask(text: String, limit: Int = 2, installed: List<Pair<String, String>> = emptyList()): List<KnowledgeEntry> {
        val t = text.lowercase()
        val packages = installed.map { it.first }.toSet()
        val followers = (APP_KINDS + installed.map { it.second.trim().lowercase() }.filter { it.length >= 2 }).distinct()
        return entries.mapNotNull { e -> score(e, t, followers)?.let { e to it } }
            .sortedWith(compareBy({ it.first.packages.none(packages::contains) }, { it.first.body == null }, { -it.second }))
            .map { it.first }
            .distinctBy { it.service } // 同じサービスのアプリとサイトについては、最高ランクのもののみが与えられます。他のコピーがアプリ/サイトに入ると、パッケージ名/ドメイン名によって指定されます。
            .take(limit)
    }

    /** 「小さな赤い本」 → com.xingin.xhs;名前/表示名/エイリアスによる完全一致 (大文字と小文字は無視)。Android エントリのみを確認します。 */
    fun packageFor(nameOrAlias: String): String? {
        val q = nameOrAlias.trim().lowercase()
        if (q.isEmpty()) return null
        return entries.filter { it.packages.isNotEmpty() }
            .filter { e -> (listOf(e.name, e.display) + e.aliases).any { it.lowercase() == q } }
            .let { c -> c.firstOrNull { it.body != null } ?: c.firstOrNull() }
            ?.let { e -> e.packageAliases.entries.firstOrNull { it.key.lowercase() == q }?.value ?: e.packages.first() }
    }

    private fun score(e: KnowledgeEntry, text: String, followers: List<String>): Int? {
        var best = -1
        val terms = (listOf(e.display, e.name) + e.aliases).map { it.trim().lowercase() }
        // エントリー名（「某ブランドモール」等）は「別アプリ」としてカウントされません
        val others = followers.filter { f -> terms.none { it.endsWith(f) } }
        for (x in terms) {
            if (x.length < 2 || x in GENERIC || (x.all { it.isDigit() } && x.length < 3)) continue
            val hit = when (x) {
                in GENERIC_ZH -> NAMING_PREFIXES.any { text.contains(it + x) }
                in GENERIC_JA -> namesJapaneseApp(text, x)
                else -> containsTerm(text, x, others)
            }
            if (hit) best = maxOf(best, x.length)
        }
        for (d in e.domains) {
            val x = d.trim().lowercase()
            // 域名也是英文词,一样要边界:否则 x.com 会命中 xx.com、zhihu.com 会命中 nzhihu.com
            if (x.length >= 3 && containsTerm(text, x)) best = maxOf(best, x.length)
        }
        return best.takeIf { it >= 0 }
    }

    // 英語/数字の単語には、「mm」が「command」にヒットするのを防ぐために単語の境界が必要です。中国語の直接部分文字列
    // [others] は別のアプリの名前です。ヒットした単語の直後にその単語が続く場合、ヒットした単語はそのアプリの名前の一部にすぎません。
    private fun containsTerm(text: String, term: String, others: List<String> = emptyList()): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(term, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + term.length)
            val leftOk = !term.first().isAsciiAlnum() || before == null || !before.isAsciiAlnum()
            val rightOk = !term.last().isAsciiAlnum() || after == null || !after.isAsciiAlnum()
            val namesOther = others.any { text.startsWith(it, i + term.length) }
            if (leftOk && rightOk && !namesOther) return true
            from = i + 1
        }
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in '0'..'9'

    companion object {
        /** アプリ/サイトのナレッジに追加されるユーザー補足のタイトル。 */
        const val NOTES_HEADER = "## The user's own notes (written by the user; when they differ from the text above or the current screen, the current screen takes precedence)"

        /**
         * は、構築中に生成されたインデックス内のアプリ (APP.md、Android) とサイト (SITE.md、Web ページ) のナレッジを読み取ります。メインテキストはメインファイルから取得され、ヘッダーが削除されます。
         * アプリとサイトは、[KnowledgeEntry.related] を通じて相互に関連付けられています。
         */
        fun loadBundled(loader: ClassLoader = KnowledgeStore::class.java.classLoader): List<KnowledgeEntry> {
            val catalog = GuideCatalog.loadBundled(loader)
            val infos = catalog.items.associateBy { it.kind to it.name }
            return catalog.rows.filter { it.kind == KnowledgeIndex.APP || it.kind == KnowledgeIndex.SITE }.map { e ->
                val app = e.kind == KnowledgeIndex.APP
                KnowledgeEntry(
                    name = e.name, display = e.display, surface = if (app) Surface.Android else Surface.Web,
                    packages = e.packages, domains = e.domains, aliases = e.aliases,
                    open = e.open, description = e.description,
                    body = catalog.fullText(infos.getValue(e.kind to e.name))?.trim()?.ifEmpty { null },
                    packageAliases = e.packageAliases,
                    links = e.links.map { raw -> raw.split('|').map(String::trim).let { Link(it[0], it[1], it.getOrNull(2)) } },
                    related = if (app) e.sites else e.apps,
                    commands = e.commands,
                )
            }
        }

        /** アプリ/サイトへのユーザーの追加: `<dir>/apps/<id>/NOTES.md`、`<dir>/sites/<id>/NOTES.md`、エントリ名別。 */
        fun loadNotes(dir: File): Map<String, String> =
            listOf("apps", "sites").flatMap { sub ->
                File(dir, sub).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { d ->
                    File(d, "NOTES.md").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { d.name to it }
                }
            }.toMap()
    }
}
