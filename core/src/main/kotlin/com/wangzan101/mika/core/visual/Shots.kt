package com.wangzan101.mika.core.visual

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.RECALL_KEEP
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.refExpired
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 最後の [RECALL_KEEP] 画面読み取り値は、view_past_screens が確認するためのものです。保護されたページや支払いページには写真は残りません。 */
internal class Recall(val id: Int, val step: Int, val app: String?, val png: ByteArray?, val elementsText: String)

/**
 * 複数の画面で共有される画面番号とレビュー: タスク内では、screenshotId がカウントアップされ、どの画面であっても view_past_screens が見つかります。
 * [step] はこのコールのラウンド (サイクルの「N 番目のステップ」) であり、レコードのステップを振り返ってください。
 */
internal class Shots {
    var step = 0
    var lastId = 0
        private set
    val recalls = ArrayDeque<Recall>()

    fun next(): Int = ++lastId

    fun remember(r: Recall) {
        recalls.addLast(r)
        while (recalls.size > RECALL_KEEP) recalls.removeFirst()
    }

    /**
     * は、画面の ScreenshotId を snapshotId とみなします (これは、写真を見るときによく行われます)。画面番号は確認のためにのみ使用され、クリックには使用できません。
     * 画面読み取り数値は決して純粋な数値ではありません。送信された画面番号を超えずに純粋な番号を使用するのは間違いです。どちらを使用すべきか正直に教えてください。
     */
    fun idAsSnapshot(o: JsonObject): ToolExecution? {
        val p = o["snapshotId"] as? JsonPrimitive ?: return null
        val n = p.content.trim().toIntOrNull() ?: return null
        if (n < 1 || n > lastId) return null
        return refExpired(Feedback.format("screenshot_id_as_snapshot", "id" to n), "screenshot_id")
    }
}
