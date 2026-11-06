package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.uistate.AppWait
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.session

/** 電話機でアプリを一覧表示して開きます: 電話機では常に機能します。 */
internal object ListApps : PhoneTool {
    override val verb = Verb.ListApps
    override val label = "インストール済みアプリを表示"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val def = ToolDef("phone_list_apps", Prompts.tool("phone_list_apps"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val apps = ctx.phone.screen.listApps()
        // は、クローンや作業マテリアルなどの別のユーザー コピーにもインストールされます。各コピーには個別のアイテムと、それを開くために使用するユーザーがあります。
        val copies = ctx.phone.screen.appCopies().groupBy { it.packageName }
        val access = ctx.phone.screen.appAccess.current()
        return ok {
            put("apps", JsonArray(apps.flatMap { (p, l) ->
                listOf(buildJsonObject {
                    put("package", p); put("label", l)
                    // false 是默认态,不为每个 App 重复写;这份结果会留在后续每一步的历史里。
                    if (p in access.blocked) put("blocked", true)
                    if (p in access.shareAllowed) put("screenShareAllowed", true)
                }) +
                    copies[p].orEmpty().map { c -> buildJsonObject { put("package", p); put("label", c.label(l)); put("user", c.userId) } }
            }))
        }
    }

    /** にリストされているアプリ名は、個別の証拠としては使用されません。これらは、後でアプリを開くために書き留められます (元のコピーが上書きされないように、別のコピーの名前は記録されません)。 */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        (r.content["apps"] as? JsonArray)?.forEach { a ->
            val o2 = a as? JsonObject ?: return@forEach
            if ("user" in o2) return@forEach
            val p = (o2["package"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            (o2["label"] as? JsonPrimitive)?.contentOrNull?.let { log.appLabels[p] = it }
        }
        return emptyList()
    }
}

internal object LaunchApp : PhoneTool {
    override val verb = Verb.LaunchApp
    override val batchable = true
    override val routable = true
    override val label = "アプリを開く"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_launch_app", Prompts.tool("phone_launch_app"),
        mapOf(
            "package" to str("Exact package name, e.g. com.example.app."),
            "cold" to bool("Restart the app first (default false)."),
            "user" to int("Only to open another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
            "wait" to int("Only when another task is using this app: seconds to wait for it to be free, then open it (at most $MAX_WAIT_S). Leave out to get the in-use result at once.", min = 1, max = MAX_WAIT_S),
        ),
        listOf("package"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val user = a.int("user", 0, MAX_USER)
        val cold = a.bool("cold", false)
        val wait = a.int("wait", 1, MAX_WAIT_S)
        // はリストされているもののみを開きます。利用可能なユーザーに含まれていないもの (プライベート スペース、他人のユーザー、インストールされていないもの) には触れません。
        if (user != null && ctx.phone.screen.appCopies().none { it.packageName == p && it.userId == user }) {
            return ctx.action(ActionResult.Failed(COPY_NOT_FOUND))
        }
        // アプリを変更したら、以前拒否された支払い申告が今回は拒否されました。
        ctx.refusedPay = null
        suspend fun open() = if (user != null) ctx.phone.screen.openAppCopy(p, user, cold) else ctx.phone.screen.openApp(p, cold)
        var opened = open()
        // 他のタスクが使用されており、モデルが要求されている場合は、待機します。モデルが解放されるのを待って、再度開きます。お互いを待ちます（誰も待てません）、制限時間がまだ占有されているまで待って、真実を話してください
        if (wait != null && opened is ActionResult.Failed && opened.reason == APP_IN_USE_BY_OTHER_TASK) {
            when (ctx.phone.screen.awaitAppFree(p, user, wait * 1_000L)) {
                AppWait.Free -> opened = open()
                AppWait.WouldDeadlock -> return failCode(WAIT_WOULD_DEADLOCK)
                AppWait.TimedOut -> return failCode(WAIT_TIMED_OUT)
                AppWait.Unsupported -> Unit
            }
        }
        val r = ctx.action(opened)
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return unreadable(ctx, p, r)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        o.argStr("package")?.let { p -> (o["user"] as? JsonPrimitive)?.contentOrNull?.let { "$p (user $it)" } ?: p }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            val copy = (o["user"] as? JsonPrimitive)?.contentOrNull?.let { "（別のコピー、ユーザー $it）" }.orEmpty()
            Observation(ObsKind.Fact, "$name が $p を開きました$copy" + (log.appLabels[p]?.let { "（アプリ名「$it」）" } ?: ""), source = PHONE)
        })
    }

    /** Android ユーザー数の上限 (UserHandle.PER_USER_RANGE 内)。 */
    private const val MAX_USER = 99_999
    /** 他のタスクがアプリをリリースするまで待ちます。長くても数秒待ってください。最初に他のことをするか、ユーザーに伝えたほうがよいでしょう。 */
    const val MAX_WAIT_S = 180
    const val COPY_NOT_FOUND = "copy_not_found"
    const val WAIT_WOULD_DEADLOCK = "app_wait_would_deadlock"
    const val WAIT_TIMED_OUT = "app_wait_timed_out"
}

/**
 * タスクが終了したら、アプリはオンのままにしておくべきですか、それとも閉じるべきですか?デフォルトのままにし（バックグラウンドに戻し、完了したページに留まり、次回使用する）、終了するとサウンドをオフにします。
 * ユーザーがプレイを継続したいとき、または終了後に閉じるときにマークするために使用します (一時的なログイン タイプ)。ただ書き留めてください。現時点では何も動いていません。同じアプリを再度マークすると、前回の時間が優先されます。
 */
internal object AppAfterTask : PhoneTool {
    override val verb = Verb.AppAfterTask
    override val label = "アプリの終了後の扱いを決める"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("phone_app_after_task", Prompts.tool("phone_app_after_task"),
        mapOf(
            "package" to str("Exact package name of the app, e.g. com.example.app."),
            "action" to str("keep: leave it running in the background after the task (even if it is playing sound). close: close it when the task ends.", listOf(KEEP, CLOSE)),
            "reason" to str("Why, in a few words (e.g. the user wants the music to keep playing; a one-time sign-in)."),
            "user" to int("Only for another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
        ),
        listOf("package", "action", "reason"))

    /** 操作中に画面を切り替えないでください。 */
    override fun aimAt(o: JsonObject): String? = null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val action = a.str("action")!!
        if (action != KEEP && action != CLOSE) throw ArgError("action", "must be keep or close")
        val reason = a.str("reason", max = 200)!!.trim()
        val user = a.int("user", 0, MAX_USER)
        val marks = ctx.phone.screen.afterTask ?: return failCode(AFTER_TASK_UNAVAILABLE)
        val previous = marks.mark(p, user, action == KEEP, reason)
        return ok {
            put("package", p)
            user?.let { put("user", it) }
            put("action", action)
            previous?.takeIf { it.keep != (action == KEEP) }?.let { put("replaces", if (it.keep) KEEP else CLOSE) }
            put("hint", Feedback[if (action == KEEP) "after_task.keep" else "after_task.close"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("package"), o.argStr("action")).joinToString(" ").ifEmpty { null }

    const val KEEP = "keep"
    const val CLOSE = "close"
    const val AFTER_TASK_UNAVAILABLE = "after_task_unavailable"
    private const val MAX_USER = 99_999
}

/**
 * テキストの一部をアプリに共有し (ACTION_SEND テキスト/プレーン)、ミカの画面でその共有ページを開きます。そのページで受信者と送信者をクリックし、
 * ここでは何も出力されません。読めないインターフェースに単語を入力できない場合は、単語を共有して取り込みましょう。
 */
internal object ShareText : PhoneTool {
    override val verb = Verb.ShareText
    override val label = "アプリへテキストを共有"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_text", Prompts.tool("phone_share_text"),
        mapOf(
            "package" to str("Exact package name of the app to share the text to, e.g. com.example.app."),
            "text" to buildJsonObject { put("type", "string"); put("description", "The text to share, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "reason" to str("Optional: why you share it, e.g. the message the user asked to send to a contact."),
        ),
        listOf("package", "text"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val text = a.str("text", max = 10000)!!
        // アプリを変更したら、以前拒否された支払い申告が今回は拒否されました。
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareText(p, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        // システムが最初にどちらを使用するかを尋ねるとき (2 つのコピーがインストールされており、複数の共有ページがある場合)、プロンプトにはすでに選択方法が示されています。共有ページにアクセスしたら、次のステップについて話し合うことができます。
        val opened = if (r.content["chooser"] != null) r else withHint(r, Feedback["share.opened"])
        return unreadable(ctx, p, opened)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("package")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            Observation(ObsKind.Fact, "$name が $p の共有ページを開きました（まだ送信していません）" + (log.appLabels[p]?.let { "（アプリ名「$it」）" } ?: ""), source = PHONE)
        })
    }
}

private fun withHint(r: ToolExecution, hint: String): ToolExecution {
    val all = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content, hint).joinToString(" ")
    return ToolExecution(JsonObject(r.content + ("hint" to JsonPrimitive(all))), changed = r.changed)
}

/** フロントデスクに関しては、ウィンドウ (セキュリティウィンドウ、起動画面など) を読み取ることができません。モデルが開いていないと判断して再び起動しないように、正直に話してください。 */
private suspend fun unreadable(ctx: ToolContext, p: String, r: ToolExecution): ToolExecution {
    val s = ctx.session.peek()
    if (s?.unreadableApp != p || s.elements.any { it.meaningful }) return r
    return withHint(ToolExecution(JsonObject(r.content + ("readable" to JsonPrimitive(false))), changed = r.changed), Feedback.format("unreadable_window", "app" to p))
}
