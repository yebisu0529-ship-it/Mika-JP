package com.wangzan101.mika.ui

import com.wangzan101.mika.cap.web.KeptPage

/**
 * 現在のUIからLazyColumnの行数を求める。送信直後は新しい行の測定より先にスクロールが走るため、
 * 前フレームのtotalItemsCountを使うと最新の発言が画面外に残る。
 * ChatScreen.exchangeと同じ条件で数え、内容が空の状態行も1行に含める。
 */
internal fun chatItemCount(
    exchanges: List<Exchange>,
    kept: KeptPage?,
    phoneSetupNeeded: Boolean,
    hasBlocked: Boolean,
): Int {
    var count = if (exchanges.isEmpty() && !hasBlocked) 1 else 0
    exchanges.forEach { ex ->
        if (ex.goal.isNotEmpty()) count++
        val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
        if (if (ex.status.isActive()) !answering else ex.steps.isNotEmpty() || ex.runId != null) count++
        val (joined, waiting) = steersAroundDraft(ex.steers)
        (joined + waiting).forEach { note ->
            if (!note.finished.isNullOrBlank()) count++
            if (!note.interrupted.isNullOrBlank()) count++
            count++
        }
        if (!ex.draft.isNullOrBlank()) count++
        count++ // StatusCard
        if (KeptRow.of(ex, kept) != null) count++
        if (ex.files.isNotEmpty()) count++
        if (ex.runId != null && (ex.memoryNotes.isNotEmpty() || ex.skillNotes.isNotEmpty())) count++
    }
    if (phoneSetupNeeded && exchanges.isNotEmpty()) count++
    if (hasBlocked) count++
    return count
}
