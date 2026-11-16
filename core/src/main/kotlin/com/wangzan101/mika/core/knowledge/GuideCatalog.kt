package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus

/** 組み込みアプリのナレッジ (種類 `app`)、サイトのナレッジ (`site`)、スキル (`skill`)、またはユーザーの個人スキル (`personal`)。 [files] は読み取り可能なファイルです (最初はメイン ファイル、残りは添付ファイルです)。 */
data class GuideInfo(
    val kind: String,
    val name: String,
    val display: String,
    val description: String,
    val aliases: List<String>,
    val terms: List<String>,
    val files: List<String>,
    /** に関連付けられたアプリ/サイト名。 */
    val apps: List<String> = emptyList(),
    val sites: List<String> = emptyList(),
)

sealed interface GuideView {
    data class Found(
        val info: GuideInfo, val file: String, val content: String, val truncated: Boolean, val totalChars: Int,
    ) : GuideView

    data class Failed(val error: String, val message: String) : GuideView
}

private const val SKILL_FILE = "SKILL.md"
/** のドキュメントには、ネイティブでは利用できないツールが記載されている場合があります。インジェクションとknowledge_read / skill_readの境界におけるリマインダー。 */
const val UNAVAILABLE_TOOLS_NOTE = "(Note: scripts mentioned in the text and tools this phone does not have are not available; use only the tools in your tool list.)"
/** デバイスがサンドボックスをサポートしている場合、リストに従ってスキル スクリプトを実行できることに注意してください。 */
const val SANDBOX_TOOLS_NOTE = "(Note: scripts marked runnable can be run with run_bundled_script; other scripts and tools this phone does not have are not available.)"

/** サンドボックス ツールが提供するかどうかに応じて、参照資料とKnowledge_read/skill_readを挿入するときの注意事項。 */
fun toolsNote(sandboxAvailable: Boolean): String = if (sandboxAvailable) SANDBOX_TOOLS_NOTE else UNAVAILABLE_TOOLS_NOTE
const val VIEW_LIMIT = 12_000
const val FIND_LIMIT = 10
/** タスクの開始時に、アプリ/サイト ナレッジ インデックスのスコアしきい値がタスク テキストに従って指定されます。名前/表示名/エイリアスが少なくとも部分的に一致します。 */
const val RECOMMEND_MIN_SCORE = 60
const val PERSONAL_KIND = "personal"
/** アプリとウェブサイトのナレッジ (knowledge_search /Knowledge_read によって制御);スキルは別のグループ (組み込みスキルと個人スキル) です。 */
val KNOWLEDGE_KINDS = setOf(KnowledgeIndex.APP, KnowledgeIndex.SITE)
val SKILL_KINDS = setOf(KnowledgeIndex.SKILL, PERSONAL_KIND)

/**
 * 読み取り専用ディレクトリ: 名前/エイリアス/説明でテキストを検索して読み取ります。組み込みパーツは、ビルド時にそれぞれのディレクトリで生成された [KnowledgeIndex.RESOURCE] から取得されます。
 * はユーザーの個人スキルをマージします (種類 = `personal`、使用中のスキルのみがカウントされます。同じ名前が組み込みを完全にカバーします)。
 */
class GuideCatalog private constructor(
    private val loader: ClassLoader,
    internal val rows: List<IndexEntry>,
    private val personal: PersonalSkills? = null,
    /** は個人スキルテキストロード時に書き換えられました(ツール名変更前から現在のツール名に)。デフォルトはそのままです。 */
    private val rewrite: (String) -> String = { it },
) {
    private val bundledItems: List<GuideInfo> = rows.map(::info)

    /** 個人スキルは毎回読み取られ、いつでも受け入れおよび無効化されます。 */
    val items: List<GuideInfo>
        get() {
            val mine = personalEntries().map { it.info }
            val names = mine.map { it.name }.toSet()
            return mine + bundledItems.filter { it.name !in names }
        }

    /**
     * [route] は `android`: [name] という名前のアプリ ナレッジがあります。 `browser` の場合: [name] という名前のサイト ナレッジがあるか、[name] という名前のアプリがサイトに関連付けられています。
     */
    fun hasRoute(name: String, route: String): Boolean = when (route) {
        "android" -> bundledItems.any { it.kind == KnowledgeIndex.APP && it.name == name }
        "browser" -> bundledItems.any { it.name == name && (it.kind == KnowledgeIndex.SITE || (it.kind == KnowledgeIndex.APP && it.sites.isNotEmpty())) }
        else -> false
    }

    /**
     * スコアリング: 名前/表示名/エイリアスは、単語全体 > を含む (またはクエリ単語全体に出現) > 検索用語 > 説明と同じです。空白で区切られた各単語は個別にスコア付けされます。
     * は、少なくとも 1 つの単語がヒットし、合計スコアが [minScore] 以上である場合にのみ返されます。 [among] が指定されている場合は、これらのタイプのみをチェックしてください ([KNOWLEDGE_KINDS]、[SKILL_KINDS] を参照)。
     */
    fun find(query: String, kind: String? = null, limit: Int = FIND_LIMIT, minScore: Int = 0, among: Set<String>? = null): List<GuideInfo> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return items.filter { (kind == null || it.kind == kind) && (among == null || it.kind in among) }
            .mapNotNull { s -> score(s, tokens).takeIf { it > 0 && it >= minScore }?.let { s to it } }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    private fun score(s: GuideInfo, tokens: List<String>): Int {
        val names = (listOf(s.name, s.display) + s.aliases).map { it.lowercase() }
        val terms = s.terms.map { it.lowercase() }
        val desc = s.description.lowercase()
        return tokens.map { t ->
            when {
                names.any { it == t } -> 100
                // 单个 ASCII 字符做子串会命中几乎所有英文名与描述,只有完全相等才算数
                t.length == 1 && t[0].code < 128 -> 0
                // 中文目标通常不带空格,整句是一个词:名字出现在句子里也算部分对上
                names.any { it.contains(t) || (it.length >= 2 && t.contains(it)) } -> 60
                terms.any { it == t } -> 50
                terms.any { it.contains(t) } -> 40
                desc.contains(t) -> 20
                else -> 0
            }
        }.sum()
    }

    /** [name] は、`app:meituan` / `site:jd.com` / `skill:documents` / `personal:xxx` と書くこともできますし、名前のみを書くこともできます（名前が重複すると曖昧になります）。 */
    /** [note] is a note placed before the text (see [toolsNote]). [among] が与えられた場合、これらのタイプのみを読み取ることができます。名前が別のグループにある場合、どのツールを使用して読むかを指定してください。 */
    fun view(name: String, file: String? = null, note: String = UNAVAILABLE_TOOLS_NOTE, among: Set<String>? = null): GuideView {
        val n = name.trim()
        val prefixed = n.substringBefore(':', "").takeIf { it in KnowledgeIndex.KINDS || it == PERSONAL_KIND }
        val id = if (prefixed != null) n.substringAfter(':') else n
        val named = items.filter { it.name == id && (prefixed == null || it.kind == prefixed) }
        val hits = named.filter { among == null || it.kind in among }
        val knowledge = among == KNOWLEDGE_KINDS
        val info = when (hits.size) {
            0 -> return GuideView.Failed(if (knowledge) "knowledge_not_found" else "skill_not_found", when {
                named.isNotEmpty() && knowledge -> "'$n' is a skill, not App or website knowledge; read it with skill_read."
                named.isNotEmpty() -> "'$n' is App or website knowledge, not a skill; read it with knowledge_read."
                knowledge -> "No App or website knowledge named '$n'. Use knowledge_search to find it."
                else -> "No skill named '$n'. Use skill_search to find it."
            })
            1 -> hits.single()
            else -> return GuideView.Failed(if (knowledge) "ambiguous_name" else "ambiguous_skill",
                "'$n' exists as ${hits.joinToString { it.kind }}; use ${hits.joinToString(" or ") { "${it.kind}:${it.name}" }}.")
        }
        val main = info.files.first()
        val path = (file?.trim()?.takeIf { it.isNotEmpty() } ?: main).removePrefix("./")
        if (info.kind == PERSONAL_KIND) return viewPersonal(info, path)
        if (path !in info.files)
            return GuideView.Failed("file_not_found", "No file '$path' in ${info.kind}:${info.name}. Available: ${info.files.joinToString()}.")
        val text = (if (path == main) fullText(info) else read(info, path))
            ?: return GuideView.Failed("file_not_found", "File '$path' could not be read.")
        val cut = text.length > VIEW_LIMIT
        return GuideView.Found(info, path, note + "\n" + (if (cut) text.take(VIEW_LIMIT) + "\n…(truncated; use file_path to view the other files)" else text), cut, text.length)
    }

    // The broken personal skill clearly reports an error and does not fall back to the built-in skill with the same name.
    private fun viewPersonal(info: GuideInfo, path: String): GuideView {
        if (path != SKILL_FILE) return GuideView.Failed("file_not_found", "Personal skill ${info.name} has only $SKILL_FILE.")
        val body = personalEntries().find { it.info.name == info.name }?.body
            ?: return GuideView.Failed("personal_skill_invalid",
                "The user's personal skill '${info.name}' is damaged and cannot be read; do not rely on it. The user can fix or delete it in Memory & skills.")
        val cut = body.length > VIEW_LIMIT
        return GuideView.Found(info, SKILL_FILE, if (cut) body.take(VIEW_LIMIT) + "\n…(truncated)" else body, cut, body.length)
    }

    /** The main text of the main file (APP.md / SITE.md / SKILL.md), remove the header.壊れた個人スキルは無効です。 サンドボックス内の */
    fun fullText(info: GuideInfo): String? {
        if (info.kind == PERSONAL_KIND) return personalEntries().find { it.info.name == info.name }?.body
        return read(info, KnowledgeIndex.fileOf(info.kind))
    }

    private fun read(info: GuideInfo, file: String): String? =
        resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/$file")?.let { FrontMatter.parse(it.toString(Charsets.UTF_8)).second }

    /** のディレクトリ (`/opt/mika/skills` の下) は、`skills/documents` や `skills/research` などの run_bundled_script のソース パラメーターでもあります。 */
    fun scriptSource(info: GuideInfo): String = "${KnowledgeIndex.dirOf(info.kind).substringAfterLast('/')}/${info.name}"

    /**
     * サンドボックスに同期するスクリプト: `scripts/manifest.json` の各エントリ、マニフェスト自体、およびマニフェストにリストされているファイル。
     * キーは、`/opt/mika/skills` の下の相対パスです。
     */
    fun scriptFiles(): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        for (info in bundledItems) {
            val src = "${KnowledgeIndex.dirOf(info.kind)}/${info.name}"
            val manifest = resource("$src/scripts/manifest.json") ?: continue
            val dir = scriptSource(info)
            out["$dir/scripts/manifest.json"] = manifest
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) resource("$src/${e.file}")?.let { out["$dir/${e.file}"] = it }
        }
        return out
    }

    /** は、`skills/documents/scripts/extract_pdf.py` など、`ディレクトリ/ファイル` リストで実行可能としてマークされているスクリプト (および実行可能なスクリプト タイプ) です。 */
    fun runnableScripts(): Set<String> {
        val out = sortedSetOf<String>()
        for (info in bundledItems) {
            val manifest = resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/scripts/manifest.json") ?: continue
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) {
                if (e.runnable && ScriptManifest.interpreterFor(e.file) != null) out += "${scriptSource(info)}/${e.file}"
            }
        }
        return out
    }

    /** 組み込みサイト ナレッジで宣言されたサイト コマンドはありますか (宣言されていない場合、サイト コマンドを実行するツールは提供されません)。 */
    fun hasSiteCommands(): Boolean = rows.any { it.kind == KnowledgeIndex.SITE && it.commands.isNotEmpty() }

    /**
     * ホスト [host] スクリプト テキストを含む、所属するサイトによって宣言されたサイト コマンド。そうでない場合は空です。サイトは、サイト ナレッジを挿入するときと同じルールに従って識別されます ([SiteMatch]、最も具体的なドメイン名が優先されます)。
     * 認識したサイトにコマンドがない場合は存在しないことを意味し、他のサイトには返されません。
     */
    fun siteCommands(host: String): List<SiteCommand> {
        val site = SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()
            ?: return emptyList()
        val dir = "${KnowledgeIndex.dirOf(KnowledgeIndex.SITE)}/${site.name}"
        return site.commands.mapNotNull { d ->
            resource("$dir/${d.file}")?.toString(Charsets.UTF_8)?.let { SiteCommand(site.name, d, SiteCommandFile.parse(d.file, it).body) }
        }
    }

    /** は [host] をホストし、それが属するサイトの知識によって書かれたペース (サイトを識別するためのルールは [siteCommands] と同じです)。そうでない場合は null です。 */
    fun sitePace(host: String): String? =
        SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()?.pace

    /** パッケージ名 [packageName] は、それが属するアプリの知識に基づいて書かれています (パッケージ名はパッケージ内にあります)。そうでない場合は null です。 */
    fun appPace(packageName: String): String? =
        rows.firstOrNull { it.kind == KnowledgeIndex.APP && packageName in it.packages }?.pace

    private fun resource(rel: String): ByteArray? = loader.getResourceAsStream(rel)?.use { it.readBytes() }

    /** [body] は null:SKILL.md ヘッダーがないか、名前が一致しないか、テキストが空です。 */
    private class PersonalEntry(val info: GuideInfo, val body: String?)

    private fun personalEntries(): List<PersonalEntry> =
        personal?.list().orEmpty().filter { it.status == SkillStatus.active || it.status == SkillStatus.trial }.map { s ->
            // 現在の名前に変更される前に書かれていたツール名であり、モデル内で読み込まれた名前はすべて使用可能な名前です。
            val (meta, body) = FrontMatter.parse(s.markdown).let { (m, b) -> m to rewrite(b) }
            fun one(k: String) = meta[k] as? String
            fun many(k: String) = (meta[k] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(one(k)?.takeIf { it.isNotEmpty() })
            val valid = one("name") == s.name && body.isNotBlank()
            PersonalEntry(
                GuideInfo(
                    kind = PERSONAL_KIND, name = s.name, display = one("display")?.takeIf { it.isNotEmpty() } ?: s.name,
                    description = (if (s.status == SkillStatus.trial) "[unverified: check the result when trying it] " else "") + (one("description") ?: ""), aliases = many("aliases"),
                    terms = many("apps") + many("domains"), files = listOf(SKILL_FILE),
                    apps = many("apps"), sites = many("sites") + many("domains"),
                ),
                body.takeIf { valid }?.let { if (s.status == SkillStatus.trial) "[Unverified personal skill draft: use it only as a trial; the result must be checked.]\n$it" else it },
            )
        }

    companion object {
        fun loadBundled(loader: ClassLoader = GuideCatalog::class.java.classLoader): GuideCatalog {
            val rows = loader.getResourceAsStream(KnowledgeIndex.RESOURCE)?.use { KnowledgeIndex.decode(it.readBytes().toString(Charsets.UTF_8)) }
            return GuideCatalog(loader, rows.orEmpty())
        }

        /** 内蔵ディレクトリ + 個人スキル; [rewrite]は個人スキルテキストロード時の書き換え（リネーム前のツール名を現在のツール名に変更）です。 */
        fun withPersonal(bundled: GuideCatalog, personal: PersonalSkills, rewrite: (String) -> String = { it }): GuideCatalog =
            GuideCatalog(bundled.loader, bundled.rows, personal, rewrite)

        private fun info(e: IndexEntry) = GuideInfo(
            kind = e.kind, name = e.name, display = e.display, description = e.description,
            aliases = e.aliases, terms = e.terms, files = listOf(KnowledgeIndex.fileOf(e.kind)) + e.files,
            apps = e.apps, sites = e.sites,
        )
    }
}
