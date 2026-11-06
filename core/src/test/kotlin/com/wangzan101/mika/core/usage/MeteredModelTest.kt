package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MeteredModelTest {
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
    private val source = UsageSource("p1", "服务商甲", "m1", UsagePurpose.of(ModelRole.Light))
    private val recorded = mutableListOf<Pair<UsageSource, Usage?>>()
    private val attempts = mutableListOf<Int>()
    private val sink = object : UsageSink {
        override fun record(s: UsageSource, u: Usage?) = record(s, u, 1)
        override fun record(s: UsageSource, u: Usage?, httpAttempts: Int) {
            recorded += s to u
            attempts += httpAttempts
        }
    }

    private class Plain(val reply: () -> ModelReply) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64000, reasoning = false)
        override val name = "plain-model"
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = reply()
    }

    private class Streaming(val reply: ModelReply) : StreamingChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 8000, reasoning = true)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) = reply
        override suspend fun completeStreaming(
            system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
        ): ModelReply {
            onText("一"); onText("一二")
            return reply
        }
    }

    private fun reply(usage: Usage?) = ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, usage)

    @Test fun recordsReportedUsageAndPassesReplyThrough() = runTest {
        val r = reply(Usage(100, 20, 60))
        val m = Plain { r }.metered(source, sink)
        assertSame(r, m.complete("s", user, emptyList()))
        assertEquals(listOf(source to Usage(100, 20, 60)), recorded)
    }

    @Test fun keepsNameAndCapabilities() {
        val inner = Plain { reply(null) }
        val m = inner.metered(source, sink)
        assertEquals("plain-model", m.name)
        assertEquals(inner.capabilities, m.capabilities)
        assertFalse("逐次テキスト出力に未対応なら、ラップ後も未対応です", m is StreamingChatModel)
        assertTrue("呼び出しがなければ記録しません", recorded.isEmpty())
    }

    @Test fun nonHttpReplyDoesNotInventARequest() = runTest {
        Plain { reply(null) }.metered(source, sink).complete("s", user, emptyList())
        assertEquals(listOf<Pair<UsageSource, Usage?>>(source to null), recorded)
        assertEquals(listOf(0), attempts)
    }

    @Test fun localFailureIsRethrownWithoutAnHttpAttempt() = runTest {
        val err = ModelError.RateLimited(1000)
        val m = Plain { throw err }.metered(source, sink)
        try {
            m.complete("s", user, emptyList()); fail()
        } catch (e: ModelError.RateLimited) {
            assertSame(err, e)
        }
        assertEquals(listOf<Pair<UsageSource, Usage?>>(source to null), recorded)
        assertEquals(listOf(0), attempts)
    }

    @Test fun cancellationBeforeHttpIsRethrownWithoutAnAttempt() = runTest {
        val m = Plain { throw CancellationException("stop") }.metered(source, sink)
        try {
            m.complete("s", user, emptyList()); fail()
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals(listOf<Pair<UsageSource, Usage?>>(source to null), recorded)
        assertEquals(listOf(0), attempts)
    }

    @Test fun streamingStaysStreamingAndForwardsText() = runTest {
        val m = Streaming(reply(Usage(5, 6))).metered(source, sink)
        assertTrue(m is StreamingChatModel)
        assertTrue(m.capabilities.vision)
        val texts = mutableListOf<String>()
        val r = (m as StreamingChatModel).completeStreaming("s", user, emptyList(), texts::add)
        assertEquals(listOf("一", "一二"), texts)
        assertEquals(Usage(5, 6), r.usage)
        assertEquals(listOf(source to Usage(5, 6)), recorded)
    }

    @Test fun brokenSinkDoesNotBreakTheCall() = runTest {
        val r = reply(Usage(1, 1))
        val m = Plain { r }.metered(source) { _, _ -> error("disk full") }
        assertSame(r, m.complete("s", user, emptyList()))
    }

    // は実行コンテキストで呼び出され、この実行の名前で記録されます (成功、使用報告なし、失敗はすべて 1 回としてカウントされます)。コンテキストにないものはカウントされません。
    @Test fun callsInsideARunAreTalliedForThatRun() = runTest {
        val tally = RunUsageTally()
        val ok = Plain { reply(Usage(100, 20, 60)) }.metered(source, sink)
        val silent = Plain { reply(null) }.metered(source, sink)
        val failing = Plain { throw ModelError.RateLimited(1000) }.metered(source, sink)
        kotlinx.coroutines.withContext(tally) {
            ok.complete("s", user, emptyList())
            silent.complete("s", user, emptyList())
            runCatching { failing.complete("s", user, emptyList()) }
        }
        ok.complete("s", user, emptyList())
        val e = tally.event(7)
        assertEquals(3, e.calls)
        assertEquals(0, e.httpCalls)
        assertEquals(2, e.unreported)
        assertEquals(0, e.httpUnreported)
        assertEquals(100L, e.inputTokens)
        assertEquals(20L, e.outputTokens)
        assertEquals(60L, e.cachedTokens)
        // 毎回総勘定元帳記録
        assertEquals(4, recorded.size)
    }
}
