package com.wangzan101.mika.core.usage

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 1回のモデル呼び出しで、HTTPクライアントへ実際に渡した推論リクエストの数。
 * アダプタ内の互換性再試行と外側の再試行を同じ箇所で数える。ほかの呼び出しとは共有しない。
 */
internal class ModelHttpAttempts : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelHttpAttempts>

    private val count = AtomicInteger()
    val started: Int get() = count.get()

    fun start() {
        count.incrementAndGet()
    }
}
