package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.RunEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 1回の実行に含まれる、モデルの論理呼び出しと実際のHTTPリクエストの合計。
 * アダプター内部の再送はHTTP回数だけに加算する。実行のコルーチン外で行う終了判定や記憶抽出は含めず、
 * それらも使用量ページには記録する。メモリ内だけで保持し、複数スレッドから安全に加算する。
 */
class RunUsageTally : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunUsageTally>

    private val logicalCalls = AtomicInteger()
    private val calls = AtomicInteger()
    private val unreported = AtomicInteger()
    private val httpUnreported = AtomicInteger()
    private val input = AtomicLong()
    private val output = AtomicLong()
    private val cached = AtomicLong()

    /** 记一次逻辑调用及其 [httpAttempts] 次实际请求;只有最终成功回复的 [usage] 可记 token。 */
    fun add(usage: Usage?, httpAttempts: Int) {
        logicalCalls.incrementAndGet()
        if (usage == null) unreported.incrementAndGet()
        val attempts = httpAttempts.coerceAtLeast(0)
        calls.addAndGet(attempts)
        httpUnreported.addAndGet((attempts - if (usage == null) 0 else 1).coerceAtLeast(0))
        if (usage == null) return
        input.addAndGet(usage.inputTokens.toLong())
        output.addAndGet(usage.outputTokens.toLong())
        cached.addAndGet(usage.cachedInputTokens.toLong())
    }

    /** 此刻的合计,作为运行记录里的一条 [RunEvent.ModelCalls]。 */
    fun event(atMs: Long) = RunEvent.ModelCalls(
        atMs, logicalCalls.get(), unreported.get(), input.get(), output.get(), cached.get(), calls.get(), httpUnreported.get(),
    )
}
