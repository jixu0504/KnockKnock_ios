package com.rhodes.privatechat.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * T37 诊断：Ktor Darwin 对「长时间不结束的 SSE 响应」何时交付 HttpResponse / 首字节。
 *
 * 背景：T37 模拟器实测 /dribble（首 token 立即、之后每 3s 一个）在 `client.post` 后
 * 1.5s 内拿不到响应对象；而短流（normal、disconnect）立即返回。此测试用与 App 完全相同的
 * `createHttpClient()` 与「纯 Darwin 引擎」两种客户端做对照，定位是引擎行为还是客户端插件配置问题。
 *
 * 需要 mock 服务器：`python3 ios-port/handoffs/mock-provider-sse.py`（127.0.0.1:18766）。
 */
class ProviderDarwinBehaviorTest {

    /** mock 服务器未启动时跳过（避免整包测试套件在无网络依赖时失败）。 */
    private suspend fun mockAvailable(): Boolean = runCatching {
        val client = createHttpClient()
        try {
            client.get("http://127.0.0.1:18766/health").status.value == 200
        } finally {
            client.close()
        }
    }.getOrDefault(false)

    private val dribbleUrl = "http://127.0.0.1:18766/v1/chat/completions/dribble"
    private val normalUrl = "http://127.0.0.1:18766/v1/chat/completions"
    private val body = """{"model":"mock","messages":[{"role":"user","content":"ping"}],"stream":true}"""

    private suspend fun measure(client: HttpClient, url: String): Pair<Long, Long> {
        val start = TimeSource.Monotonic.markNow()
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "text/event-stream")
            setBody(body)
        }
        val responseMs = start.elapsedNow().inWholeMilliseconds
        val channel: ByteReadChannel = response.bodyAsChannel()
        val buffer = ByteArray(4096)
        val firstByte = withTimeoutOrNull(3_000) {
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read > 0) return@withTimeoutOrNull start.elapsedNow().inWholeMilliseconds
                if (read == -1) return@withTimeoutOrNull -1L
                if (!channel.awaitContent()) return@withTimeoutOrNull -1L
            }
            @Suppress("UNREACHABLE_CODE")
            -1L
        }
        return responseMs to (firstByte ?: -2L)
    }

    @Test
    fun appClientDribbleResponseTiming() = runBlocking {
        if (!mockAvailable()) { println("T37-DIAG skip (mock server not running)"); return@runBlocking }
        val client = createHttpClient()
        try {
            val (responseMs, firstByteMs) = measure(client, dribbleUrl)
            println("T37-DIAG app-client(dribble) responseMs=$responseMs firstByteMs=$firstByteMs")
        } finally {
            client.close()
        }
    }

    @Test
    fun plainDarwinClientDribbleResponseTiming() = runBlocking {
        if (!mockAvailable()) { println("T37-DIAG skip (mock server not running)"); return@runBlocking }
        val client = HttpClient(Darwin) {
            install(HttpTimeout) {
                requestTimeoutMillis = 240_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 240_000
            }
        }
        try {
            val (responseMs, firstByteMs) = measure(client, dribbleUrl)
            println("T37-DIAG plain-darwin(dribble) responseMs=$responseMs firstByteMs=$firstByteMs")
        } finally {
            client.close()
        }
    }

    @Test
    fun appClientNormalStreamIsFast() = runBlocking {
        if (!mockAvailable()) { println("T37-DIAG skip (mock server not running)"); return@runBlocking }
        val client = createHttpClient()
        try {
            val (responseMs, firstByteMs) = measure(client, normalUrl)
            println("T37-DIAG app-client(normal) responseMs=$responseMs firstByteMs=$firstByteMs")
            assertTrue(responseMs < 3_000)
            assertTrue(firstByteMs in 0..3_000)
        } finally {
            client.close()
        }
    }
}
