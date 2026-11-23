package com.rhodes.privatechat.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Ktor Darwin 真实流式验证（iOS 模拟器 + 本地 mock SSE 服务器）。
 *
 * 前置：mock SSE 服务器已启动（ios-port/handoffs/mock-sse-server.py，127.0.0.1:18765）。
 * ATS：loopback（127.0.0.1）默认豁免，本测试集实际验证该豁免在 Ktor Darwin 下成立。
 *
 * 场景：a) 首 token 到达；b) 流中途 cancel() 立即终止（计时验证）；
 * c) 首 token 超时中止（服务器 3s 才发首 token，客户端 1.5s 阈值）；
 * d) 静默超时（响应头已回但无 body）；e) 响应阶段超时（连响应头都不回）；
 * f) 半包+断连（服务器发半包后关闭）；g) 错误端点（HTTP 500）；h) 自定义端点。
 */
class KtorDarwinStreamingTest {

    private val base = "http://127.0.0.1:18765"

    private fun client(requestTimeoutMs: Long? = null): HttpClient = HttpClient(Darwin) {
        if (requestTimeoutMs != null) {
            install(HttpTimeout) {
                this.requestTimeoutMillis = requestTimeoutMs
                socketTimeoutMillis = requestTimeoutMs
            }
        }
    }

    private suspend fun <T> withClient(
        requestTimeoutMs: Long? = null,
        block: suspend (HttpClient) -> T,
    ): T {
        val c = client(requestTimeoutMs)
        try {
            return block(c)
        } finally {
            c.close()
        }
    }

    /** 阻塞式消费流：每个到达的片段回调 onChunk，EOF 正常返回。 */
    private suspend fun drain(ch: ByteReadChannel, onChunk: (String) -> Unit) {
        val buf = ByteArray(512)
        while (true) {
            val n = ch.readAvailable(buf)
            if (n == -1) break
            if (n > 0) onChunk(buf.copyOf(n).decodeToString())
            else if (!ch.awaitContent()) break
        }
    }

    @Test
    fun a_firstTokenArrivesAndStreamCompletes() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        var firstChunkMs = -1L
        val received = StringBuilder()
        withClient { c ->
            c.prepareGet("$base/sse").execute { resp ->
                assertEquals(HttpStatusCode.OK, resp.status)
                drain(resp.bodyAsChannel()) { chunk ->
                    if (firstChunkMs < 0) firstChunkMs = start.elapsedNow().inWholeMilliseconds
                    received.append(chunk)
                }
            }
        }
        println("Ktor-Darwin first-token: ${firstChunkMs}ms; received=${received.length} chars")
        // mock 服务器 100ms 后发首 token；放宽到 60..2000 防 CI 抖动，但必须真实到达
        assertTrue(firstChunkMs in 60..2_000, "首 token 应在 ~100ms 到达，实测 ${firstChunkMs}ms")
        assertTrue(received.contains("token1"), "缺 token1")
        assertTrue(received.contains("token10"), "缺 token10（流未完整收完）")
    }

    @Test
    fun b_cancelMidStreamTerminatesImmediately() = runBlocking {
        val tokens = Channel<String>(Channel.UNLIMITED)
        val received = StringBuilder()
        val tokenRe = Regex("token(\\d+)")
        withClient { c ->
            val job = launch {
                c.prepareGet("$base/sse").execute { resp ->
                    val ch = resp.bodyAsChannel()
                    val buf = ByteArray(512)
                    while (true) {
                        val n = ch.readAvailable(buf)
                        if (n == -1) break
                        if (n > 0) {
                            val s = buf.copyOf(n).decodeToString()
                            received.append(s)
                            tokenRe.findAll(s).forEach { tokens.trySend(it.value) }
                        } else if (!ch.awaitContent()) break
                    }
                }
            }
            // 等前 2 个 token（~100ms + ~150ms 间隔）
            val t1 = withTimeoutOrNull(5_000) { tokens.receive() }
            val t2 = withTimeoutOrNull(5_000) { tokens.receive() }
            assertNotNull(t1, "5s 内未收到 token1")
            assertNotNull(t2, "5s 内未收到 token2")
            assertEquals("token1", t1)
            assertEquals("token2", t2)

            val cancelStart = TimeSource.Monotonic.markNow()
            job.cancel()
            job.join()
            val cancelMs = cancelStart.elapsedNow().inWholeMilliseconds
            println("Ktor-Darwin cancel-and-join: ${cancelMs}ms; tokens-before-cancel=${tokenRe.findAll(received.toString()).count()}")
            assertTrue(cancelMs < 1_000, "cancelAndJoin 应立即完成，实测 ${cancelMs}ms")
            val tokenCount = tokenRe.findAll(received.toString()).count()
            assertTrue(tokenCount < 10, "服务器共发 10 个 token，取消后不应收满（实收 $tokenCount）")
        }
    }

    @Test
    fun c_slowFirstTokenIsAbortedByClientTimeout() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        val outcome = withClient { c ->
            withTimeoutOrNull(1_500) {
                c.prepareGet("$base/sse/slow-first").execute { resp ->
                    val ch = resp.bodyAsChannel()
                    val buf = ByteArray(256)
                    while (true) {
                        val n = ch.readAvailable(buf)
                        if (n == -1) break
                        if (n > 0) return@execute "got:${buf.copyOf(n).decodeToString()}"
                        if (!ch.awaitContent()) break
                    }
                    "eof"
                }
            }
        }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        println("Ktor-Darwin slow-first timeout-abort: ${elapsed}ms, outcome=$outcome")
        assertNull(outcome, "服务器 3s 才发数据，客户端 1.5s 应超时中止；实际结果=$outcome")
        assertTrue(elapsed in 1_200..2_500, "超时应在 ~1500ms 触发，实测 ${elapsed}ms")
    }

    @Test
    fun d_silentServerIsAbortedAfterHeadersArrive() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        val outcome = withClient { c ->
            withTimeoutOrNull(2_000) {
                c.prepareGet("$base/sse/only-headers").execute { resp ->
                    assertEquals(HttpStatusCode.OK, resp.status)
                    drain(resp.bodyAsChannel()) { }
                    "eof"
                }
            }
        }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        println("Ktor-Darwin silent-timeout-abort: ${elapsed}ms, outcome=$outcome")
        assertNull(outcome, "服务器 30s 不发 body，客户端 2s 应超时中止；实际=$outcome")
        assertTrue(elapsed in 1_500..3_500, "超时应在 ~2000ms 触发，实测 ${elapsed}ms")
    }

    @Test
    fun e_noResponseAtAllIsAbortedByTimeout() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        val outcome = withClient { c ->
            withTimeoutOrNull(1_500) {
                c.prepareGet("$base/sse/hang").execute { resp ->
                    "status:${resp.status}"
                }
            }
        }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        println("Ktor-Darwin response-timeout-abort: ${elapsed}ms, outcome=$outcome")
        assertNull(outcome, "服务器 5s 连响应头都不回，客户端 1.5s 应中止；实际=$outcome")
        assertTrue(elapsed in 1_200..2_500, "超时应在 ~1500ms 触发，实测 ${elapsed}ms")
    }

    @Test
    fun f_halfPacketThenDisconnectEndsCleanly() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        val received = StringBuilder()
        withClient { c ->
            c.prepareGet("$base/sse/disconnect").execute { resp ->
                assertEquals(HttpStatusCode.OK, resp.status)
                drain(resp.bodyAsChannel()) { received.append(it) }
            }
        }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        println("Ktor-Darwin half-packet-disconnect: '${received}' in ${elapsed}ms")
        assertEquals("data: partial-to", received.toString().trim(), "应完整收到半包内容后正常 EOF")
        assertTrue(elapsed < 5_000, "断连后应立即 EOF，实测 ${elapsed}ms")
    }

    @Test
    fun g_errorEndpointSurfacesHttpStatusAndBody() = runBlocking {
        withClient { c ->
            c.prepareGet("$base/sse/error").execute { resp ->
                assertEquals(HttpStatusCode.InternalServerError, resp.status)
                assertTrue(resp.bodyAsText().contains("mock_server_error"))
            }
        }
        println("Ktor-Darwin error-endpoint: 500 + json body OK")
    }

    @Test
    fun h_customEndpointDeliversItsEvent() = runBlocking {
        val received = StringBuilder()
        withClient { c ->
            c.prepareGet("$base/sse/custom").execute { resp ->
                assertEquals(HttpStatusCode.OK, resp.status)
                drain(resp.bodyAsChannel()) { received.append(it) }
            }
        }
        assertTrue(received.contains("custom-ok"), "自定义端点事件未到达：'$received'")
        println("Ktor-Darwin custom-endpoint: '${received.trim()}'")
    }
}
