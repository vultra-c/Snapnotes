package com.whyy.snapnotes.logic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.whyy.snapnotes.ui.viewmodel.AmadeusConfig

/**
 * 手机端 Amadeus AI 聊天全套实现。
 *
 * 协议对齐手环端 `src/app.ux` 的 `_processChatMessage`：
 * - 手环→手机入站（**不带 tag**）：`chat_request` / `chat_new` / `reply_ack`
 * - 手机→手环回包（**带 tag:'chat'**）：`reply_start` / `reply_chunk` / `reply_end` / `reply_error` / `chat_new_ack`
 * - 流控与推书同款：手环每收一片 `reply_chunk` 回 `reply_ack`，手机端等 ack 再发下一片。
 *
 * 上下文按 `sessionId` 在手机端维护 `MutableList<ChatMsg>`，系统提示词固定置顶。
 * 回复采用 **SSE 流式**：边收 LLM delta 边攒够 80 字符 flush 一片 `reply_chunk`，首字快。
 * `reply_start` 的 `totalChunks` 传保守上限 999（手环端按 `app.ux_chat_protocol_fix.md`
 * 微调后不再 hard 判 `received === totalChunks`，只判 `received > 0` 即视为完整）。
 */
class AmadeusChat(
    private val appContext: Context,
    private val conn: InterHandshake,
    private val pusher: JsonFilePusher,
    private val configFlow: StateFlow<AmadeusConfig>,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ChatPusher"
        private const val CHAT_TAG = "__chat__"
        private const val REPLY_CHUNK_CHARS = 80
        /** 每片等 ack 上限。 */
        private const val PER_CHUNK_ACK_TIMEOUT_MS = 15_000L
        /** reply_start totalChunks 保守上限（手环端按 fix.md 不再 hard 判等值）。 */
        private const val TOTAL_CHUNKS_UPPER_BOUND = 999
        private const val SYSTEM_PROMPT =
            "你是 Amadeus。请用纯文本回答，禁用 markdown（不要用 **加粗**、# 标题、- 列表符等）。" +
                "回答尽量精炼，长内容请拆成若干段小短句，便于在手环小屏阅读。"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** sessionId -> 历史消息（含系统提示词置顶）。 */
    private val contexts = mutableMapOf<String, MutableList<ChatMsg>>()

    /** 最近一次 LLM 调用状态，供「上下文管理菜单」观测。 */
    private val _lastCall = MutableStateFlow<CallStatus>(CallStatus.Idle)
    val lastCall: StateFlow<CallStatus> = _lastCall.asStateFlow()

    /**
     * DNS 缓存：息屏 Doze 下系统 resolver 常被冻，`InetAddress.getAllByName` 直接返「No address associated
     * with hostname」。WakeLock/WifiLock 保的是物理链路（socket 不断），但保不了 DNS 系统服务。
     * 这里在亮屏/连手环成功时主动预解析把 host→IP 列存进表；OkHttp 的 [cachedDns] 优先返缓存命中，
     * Doze 即便 resolver 冻了也有 IP 可用，绕过系统解析。亮屏期填充，黑屏期命中。
     */
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    /** OkHttp 用的带缓存 DNS：缓存命中直接返，不命中走系统解析（Doze 下大概率失败，但有兜底重试）。 */
    private val cachedDns = Dns { hostname ->
        // activeNetwork 在手时先走它的 DNS（系统维护、不被能力降级），其次缓存，最后系统默认。
        activeNetwork?.let { net -> net.getAllByName(hostname).toList().takeIf { it.isNotEmpty() } }?.let { return@Dns it }
        dnsCache[hostname]?.let { if (it.isNotEmpty()) return@Dns it }
        systemDns.lookup(hostname).also { if (it.isNotEmpty()) dnsCache[hostname] = it }
    }

    private val systemDns = Dns.SYSTEM

    /**
     * 息屏 Doze 会下调后台应用的 Network 能力，主动 `socket.connect()` 直接 fail to connect。
     * 申请一条带 NET_CAPABILITY_INTERNET 的 [NetworkRequest] 拿到 active Network 句柄，OkHttp 走它的
     * socketFactory / DNS 发包，绕过 Doze 的能力降级（这条网络的出站不被限）。启用/连接时请求，关时释放。
     */
    @Volatile
    private var activeNetwork: Network? = null
    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 申请一条 active internet 网络句柄。幂等：已申请则跳过。供 VM 在启用/连手环成功时调。 */
    fun requestActiveNetwork() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetwork = network
                // 绑进程到这条网络，确保默认 socket 走它（双保险）。
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.bindProcessToNetwork(network)
                }
                Log.d(TAG, "active network available")
            }
            override fun onLost(network: Network) {
                if (activeNetwork == network) activeNetwork = null
                runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.bindProcessToNetwork(null) }
                Log.d(TAG, "active network lost")
            }
        }
        runCatching { cm.requestNetwork(req, cb) }.onFailure { Log.w(TAG, "requestActiveNetwork fail: ${it.message}") }
        networkCallback = cb
    }

    /** 释放网络申请。停 Amadeus / 服务销毁时调，防泄漏。 */
    fun releaseActiveNetwork() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        networkCallback = null
        activeNetwork = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm?.bindProcessToNetwork(null)
        }
    }

    /** 本轮是否已向手环吐过 reply_start（已部分回复即不可安全重试）。每次 runLlmWithRetry 入口复位。 */
    @Volatile
    private var replyStartedThisRequest: Boolean = false

    /** 当前等待 ack 的 chunk 完成 promise。同会话串行，单 promise 够用。 */
    @Volatile
    private var chunkAck: CompletableDeferred<Int> = CompletableDeferred()

    private data class ChatMsg(val role: String, val content: String)

    /* ──────────── 上下文管理 API（供 UI「上下文管理菜单」观测/操作） ──────────── */

    /** 单会话快照（列表用）。note 前缀摘要便于辨认手环真实 sid vs test_ 测试会话。 */
    data class SessionSnapshot(val sessionId: String, val messageCount: Int, val isTest: Boolean)

    /** 单会话详情（下钻看完整往来）。messages: (role, content) 序列，字符截断交 UI 处理。 */
    data class SessionDetail(val sessionId: String, val messages: List<Pair<String, String>>)

    /** 最近一次 LLM 调用状态。Running 进行中；Success/Failed 终态含 http/字符数/耗时。 */
    sealed class CallStatus {
        object Idle : CallStatus()
        data class Running(val sid: String) : CallStatus()
        data class Success(val sid: String, val http: Int, val chars: Int, val ms: Long) : CallStatus()
        data class Failed(val sid: String, val msg: String, val http: Int? = null, val ms: Long = 0L) : CallStatus()
    }

    /** 全部会话快照（主页/上下文页列表用）。 */
    fun snapshots(): List<SessionSnapshot> = contexts.map { (k, v) ->
        SessionSnapshot(k, v.size, isTest = k.startsWith("test_"))
    }

    /** 某 sid 的完整往来；不存在返回 null。 */
    fun detail(id: String): SessionDetail? = contexts[id]?.let { SessionDetail(id, it.map { it.role to it.content }) }

    /** 清空指定会话历史。 */
    fun clearSession(id: String) { contexts.remove(id) }

    /** 清空全部会话历史。 */
    fun clearAll() { contexts.clear() }

    /** 主动预解析 LLM 服务域名并刷缓存。亮屏/连手环成功后由 ViewModel 调一次，给息屏 Doze 备好 IP。
     *  失败静默：亮屏期若解析不到也不报错，发请求时仍走系统解析 + 重试兜底。 */
    fun prefetchDns(cfg: AmadeusConfig) {
        val host = resolveLlmHost(cfg)
        scope.launch {
            runCatching {
                val addrs = systemDns.lookup(host)
                if (addrs.isNotEmpty()) {
                    dnsCache[host] = addrs
                    Log.d(TAG, "prefetchDns $host -> ${addrs.size} addr(s)")
                }
            }.onFailure { Log.w(TAG, "prefetchDns $host fail: ${it.message}") }
        }
    }

    /** 从配置推出 LLM 服务 host：自定义 baseUrl 取其 host，否则默认 deepseek。 */
    private fun resolveLlmHost(cfg: AmadeusConfig): String =
        cfg.baseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }?.let {
            runCatching { URI(it).host }.getOrNull()
        } ?: "api.deepseek.com"

    /** abort 内部信号：表示本会话已发过 reply_error，外层不要再重复报错。 */
    private object ReplyAborted : RuntimeException("reply aborted")

    init {
        conn.addListener(CHAT_TAG, ::handleInbound)
        // 断开后旧上下文无意义（手环端会重开 session），清掉避免占内存与误用。
        conn.addOnDisconnectedListener { contexts.clear() }
    }

    /** 入站分发入口：解析 {type,...}，按 type 分流。 */
    private fun handleInbound(payload: String) {
        try {
            val obj = json.parseToJsonElement(payload).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "chat_new" -> onChatNew(obj)
                "chat_request" -> scope.launch { onRequest(obj) }
                "reply_ack" -> onReplyAck(obj)
                else -> Log.d(TAG, "ignore chat type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse chat inbound fail", e)
        }
    }

    /** chat_new：采手环发来的 sessionId，清该会话历史，回 chat_new_ack。 */
    private fun onChatNew(obj: JsonObject) {
        val sid = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: run {
            Log.w(TAG, "chat_new without sessionId")
            return
        }
        contexts.remove(sid)
        contexts.getOrPut(sid) { mutableListOf() }
        Log.d(TAG, "chat_new sessionId=$sid")
        scope.launch { sendWithLock { conn.sendMessage(encode("chat_new_ack", sessionId = sid)) } }
    }

    /** chat_request：取历史 + append user + 调 LLM + append assistant + 流式回包。 */
    private suspend fun onRequest(obj: JsonObject) {
        val sid = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: run {
            Log.w(TAG, "chat_request without sessionId")
            return
        }
        val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: run {
            Log.w(TAG, "chat_request without text")
            return
        }
        val cfg = configFlow.value
        Log.d(TAG, "chat_request recv sessionId=$sid enabled=${cfg.isReady}")
        if (!cfg.isReady) {
            // 未启用或未配齐 key/model：发 reply_error 让手环清态，不再走 watchdog 超时。
            replyError(sid, if (!cfg.enabled) "Amadeus 未启用" else "Amadeus 未配置 API Key / Model")
            return
        }
        runRequest(sid, text, cfg, replyViaBle = true)
    }

    /**
     * 统一的请求执行流：append user 历史 → 调 LLM → append assistant 历史。
     *
     * [replyViaBle] = true（手环真实 chat_request）：按 80 字符切片，带 tag:'chat' 流式回包，
     *   每片等手环 reply_ack 再发下一片（单 BLE 串行，靠 [pusher.sendMutex] 与推书互斥）。
     * [replyViaBle] = false（手机端「测试发送」）：只本地跑 SSE 累积完整回复，落历史 + 刷 lastCall，
     *   不发任何 BLE reply_*（无手环无法 ack），用于在「上下文管理菜单」里调试网络/cos 配置。
     *
     * 失败：发 reply_error（仅真实路径）+ 更新 lastCall=Failed + 维持历史干净（移除本轮未答的 user 消息）。
     */
    private suspend fun runRequest(sid: String, text: String, cfg: AmadeusConfig, replyViaBle: Boolean) {
        val hist = contexts.getOrPut(sid) { mutableListOf() }
        hist += ChatMsg("user", text)
        val started = SystemClock.elapsedRealtime()
        _lastCall.value = CallStatus.Running(sid)
        try {
            // 后台时 OkHttp 的底层 socket 常被系统掐（IOException: Software caused connection abort 等），
            // retryOnConnectionFailure 兜 HTTP 重发；再叠一层应用级重试，对这类网络抖动重发一次而非直接报错，
            // 避免手环端误显示「调用失败」。
            val assistantText = runLlmWithRetry(cfg, sid, hist, replyViaBle)
            hist += ChatMsg("assistant", assistantText)
            _lastCall.value = CallStatus.Success(sid, 200, assistantText.length, SystemClock.elapsedRealtime() - started)
        } catch (_: ReplyAborted) {
            // 已发 reply_error，仅落防线：移除本轮未答的 user 消息，保持历史干净。
            hist.removeAt(hist.lastIndex)
            _lastCall.value = CallStatus.Failed(sid, "已中止：手环未确认分片", null, SystemClock.elapsedRealtime() - started)
            Log.d(TAG, "chat request aborted sessionId=$sid (reply_error sent)")
        } catch (e: TimeoutCancellationException) {
            if (replyViaBle) replyError(sid, "调用超时，请重试或调大超时时间")
            hist.removeAt(hist.lastIndex)
            _lastCall.value = CallStatus.Failed(sid, "调用超时", null, SystemClock.elapsedRealtime() - started)
        } catch (e: Exception) {
            Log.e(TAG, "chat request fail: ${e.message}", e)
            if (replyViaBle) replyError(sid, "调用失败: ${e.message ?: e.javaClass.simpleName}")
            hist.removeAt(hist.lastIndex)
            _lastCall.value = CallStatus.Failed(sid, e.message ?: e.javaClass.simpleName, httpFor(e), SystemClock.elapsedRealtime() - started)
        }
    }

    /**
     * 后台/锁屏时 OkHttp 连接易被掐或拒连，分两类，都需要应用层容忍而非直接报错：
     * - socket 中途 abort（IOException：Software caused connection abort / broken pipe / connection reset）
     * - 系统拒发新连接 / DNS 查不动（IOException：Connection refused / failed to connect / Unknown host）
     * 夜间 Doze 很可能拦下前若干秒，靠延迟重试能在 maintenance 窗口（或用户亮屏）逮到机会。
     * 已开始切片回包（reply_start 已吐）后绝不重试 —— 重发会乱序；仅「首片未吐」安全。
     */
    private suspend fun runLlmWithRetry(cfg: AmadeusConfig, sid: String, hist: MutableList<ChatMsg>, replyViaBle: Boolean): String {
        replyStartedThisRequest = false
        val maxRetries = if (replyViaBle) 2 else 1   // 真实路径更值得多忍一次，测试路径少等等
        var lastError: IOException? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return sprintRequestStream(cfg, sid, hist, replyViaBle)
            } catch (e: IOException) {
                lastError = e
                if (!isTransientNetworkError(e) || replyStartedForSid(sid)) throw e
                if (attempt >= maxRetries) throw e
                // Doze 下网络限制可能持续几秒，延迟重试逮下一个窗口。
                val backoffMs = if (replyViaBle) 1500L * (attempt + 1) else 800L
                Log.w(TAG, "LLM connect/socket fail (attempt ${attempt + 1}), retry in ${backoffMs}ms: ${e.message}")
                delay(backoffMs)
                replyStartedThisRequest = false
            }
        }
        throw lastError ?: IOException("LLM retry exhausted")
    }

    /** 判断是否属于「后台被掐 / 拒连 / DNS 失败」类的瞬时网络错误，值得重试一次以上。 */
    private fun isTransientNetworkError(e: IOException): Boolean {
        val msg = (e.message ?: e.javaClass.simpleName).lowercase()
        return msg.contains("connection abort") ||
            msg.contains("software caused") ||
            msg.contains("broken pipe") ||
            msg.contains("connection reset") ||
            msg.contains("socket closed") ||
            msg.contains("connection shutdown") ||
            msg.contains("failed to connect") ||
            msg.contains("connection refused") ||
            msg.contains("unable to resolve host") ||
            msg.contains("unknown host") ||
            msg.contains("econnrefused")
    }

    /** 本 sid 是否已经向手环吐过第一片 reply（看是否有 reply_start 的 in-flight 标记）。 */
    private fun replyStartedForSid(@Suppress("UNUSED_PARAMETER") sid: String): Boolean {
        // 真实路径里 sprintRequestStream 在流进入「开始回包」前若抛 IOException，则 reply_start 尚未发出，
        // 重试安全；这里用轻量开关，由 sprintRequestStream 在发出 reply_start 时置位。
        return replyStartedThisRequest
    }

    /** 从我们抛出的 "HTTP {code} ..." 异常里抠出 HTTP 码；抠不到返回 null。 */
    private fun httpFor(e: Throwable): Int? {
        val m = Regex("HTTP\\s+(\\d{3})").find(e.message.orEmpty())
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 本地测试入口（上下文管理菜单「测试发送」调用）：
     * mock sessionId，跑完整 SSE 把回复收完落历史 + 刷 lastCall，**不发 BLE reply_***。
     * 未启用或未配齐时直接置 lastCall=Failed 提示，用于在配置页就能看出「能否发起调用」。
     * 协程内运行，调用方 fire-and-forget 即可。
     */
    fun testSend(text: String) {
        scope.launch {
            val cfg = configFlow.value
            val sid = "test_" + SystemClock.elapsedRealtime()
            if (!cfg.isReady) {
                contexts.remove(sid)
                _lastCall.value = CallStatus.Failed(sid, if (!cfg.enabled) "Amadeus 未启用" else "Amadeus 未配置 API Key / Model")
                return@launch
            }
            runRequest(sid, text, cfg, replyViaBle = false)
        }
    }

    /** reply_ack：完成当前 chunk 的 ack promise，触发发下一片。 */
    private fun onReplyAck(obj: JsonObject) {
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: true
        val idx = obj["chunkIndex"]?.jsonPrimitive?.intOrNull ?: 0
        if (!ok) {
            chunkAck.completeExceptionally(Exception("reply_ack(ok=false)"))
            return
        }
        if (!chunkAck.isCompleted) chunkAck.complete(idx)
    }

    private fun replyError(sid: String, message: String) {
        Log.e(TAG, "replyError $sid: $message")
        scope.launch { sendWithLock { conn.sendMessage(encode("reply_error", sessionId = sid, message = message)) } }
    }

    /**
     * 真·流式回包：SSE 边收 LLM delta 边攒够 80 字符 flush 一片，
     * 并等手环 `reply_ack` 再发下一片。首字到达即发 `reply_start`（满足手环 15s watchdog），
     * 全部读完后发 `reply_end`。返回完整 assistant 文本用于落历史。
     *
     * [replyViaBle]=false（测试路径）：不发任何 BLE reply_*、不等 ack，只把 SSE 收完返回完整文本。
     *
     * 与推书共用 [pusher.sendMutex]：保证 chat 下发与推书/storage 查询串行，单 BLE 不被吞。
     */
    private suspend fun sprintRequestStream(
        cfg: AmadeusConfig,
        sid: String,
        hist: List<ChatMsg>,
        replyViaBle: Boolean
    ): String = withContext(Dispatchers.IO) {
        val baseUrl = cfg.baseUrl.trimEnd('/')
        val url = if (baseUrl.isBlank()) {
            "https://api.deepseek.com/v1/chat/completions"
        } else {
            "$baseUrl/v1/chat/completions"
        }
        val messages = buildList {
            add(mapOf("role" to "system", "content" to SYSTEM_PROMPT))
            hist.forEach { add(mapOf("role" to it.role, "content" to it.content)) }
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(cfg.model))
            put("stream", JsonPrimitive(true))
            put("messages", encodeMessages(messages))
        }.toString()

        val client = buildClient(cfg)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp: Response = client.newCall(request).execute()
        resp.use {
            if (!it.isSuccessful) {
                val errBody = it.body?.string()?.take(500) ?: ""
                throw Exception("HTTP ${it.code} ${it.message} $errBody")
            }
            val sse = it.body ?: throw Exception("响应体为空")
            streamSseChunks(cfg, sid, sse, replyViaBle)
        }
    }

    /**
     * 读 SSE 流，增量累积 delta，攒满 80 字符就 flush 一片；
     * 首字到达（即拿到第一个 delta）时发 `reply_start`。返回完整 assistant 文本。
     *
     * [replyViaBle]=false 时所有 `sendWithLock{...}` 与 `chunkAck.await()` 全部跳过，
     * 仅累积 full/piece 直接进下一轮——测试路径纯本地收流，不依赖手环 ack。
     */
    private suspend fun streamSseChunks(cfg: AmadeusConfig, sid: String, body: ResponseBody, replyViaBle: Boolean): String {
        val source = body.source()
        val full = StringBuilder()
        val piece = StringBuilder()
        var chunkIndex = 0
        var started = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            val data = line.trimStart()
            if (data.isEmpty() || data.startsWith(":")) continue
            if (!data.startsWith("data:")) continue
            val payload = data.substring(5).trim()
            if (payload == "[DONE]") break
            val delta = try {
                val obj = json.parseToJsonElement(payload).jsonObject
                (obj["choices"] as? JsonArray)?.firstOrNull()
                    ?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                Log.w(TAG, "skip unparseable sse line")
                null
            }
            if (delta.isNullOrEmpty()) continue

            if (!started) {
                started = true
                if (replyViaBle) {
                    sendWithLock { conn.sendMessage(encode("reply_start", sessionId = sid, totalChunks = TOTAL_CHUNKS_UPPER_BOUND)) }
                    replyStartedThisRequest = true   // 已开始向手环回包，此后 socket 失败不可安全重试
                    Log.d(TAG, "reply send start sessionId=$sid (firstByte)")
                }
            }

            full.append(delta)
            piece.append(delta)
            while (piece.length >= REPLY_CHUNK_CHARS) {
                // 切出前 REPLY_CHUNK_CHARS 字符，并回退到不拆散代理对的安全边界；余下留给下一片继续累积。
                var safeLen = dropTrailingCodePointBoundary(piece, REPLY_CHUNK_CHARS)
                if (safeLen <= 0) safeLen = REPLY_CHUNK_CHARS.coerceAtMost(piece.length) // 退化：强行切，避免死循环
                val flush = piece.substring(0, safeLen)
                val rest = piece.substring(safeLen)
                val idx = chunkIndex++
                if (replyViaBle) {
                    chunkAck = CompletableDeferred()
                    sendWithLock { conn.sendMessage(encode("reply_chunk", sessionId = sid, chunkIndex = idx, data = flush)) }
                    Log.d(TAG, "reply chunk ${idx + 1} sent ${flush.length}chars")
                    try {
                        withTimeout(PER_CHUNK_ACK_TIMEOUT_MS) { chunkAck.await() }
                    } catch (e: TimeoutCancellationException) {
                        Log.e(TAG, "reply ack timeout chunk ${idx + 1}; abort")
                        replyError(sid, "手环未确认第 ${idx + 1} 片，已中止")
                        throw ReplyAborted
                    }
                } else {
                    Log.d(TAG, "test chunk ${idx + 1} ${flush.length}chars (no BLE)")
                }
                piece.setLength(0)
                piece.append(rest)
                // 余下若仍 ≥ 80，循环继续 flush；rest 通常 ≤ REPLY_CHUNK_CHARS。
            }
        }
        // 残片
        if (piece.isNotEmpty()) {
            val idx = chunkIndex++
            if (replyViaBle) {
                chunkAck = CompletableDeferred()
                sendWithLock { conn.sendMessage(encode("reply_chunk", sessionId = sid, chunkIndex = idx, data = piece.toString())) }
                Log.d(TAG, "reply chunk ${idx + 1}(tail) sent ${piece.length}chars")
                withTimeout(PER_CHUNK_ACK_TIMEOUT_MS) { chunkAck.await() }
            } else {
                Log.d(TAG, "test chunk ${idx + 1}(tail) ${piece.length}chars (no BLE)")
            }
        }
        if (!started) throw Exception("LLM 返回空内容")
        if (replyViaBle) {
            sendWithLock { conn.sendMessage(encode("reply_end", sessionId = sid)) }
            Log.d(TAG, "reply send end sessionId=$sid totalChunks=$chunkIndex")
        }
        return full.toString().also { if (it.isEmpty()) throw Exception("LLM 返回空内容") }
    }

    /** 在 [0..piece.length] 中找到不超过 target 的最大 codePoint 边界（不切断代理对）。 */
    private fun dropTrailingCodePointBoundary(piece: StringBuilder, target: Int): Int {
        if (target <= 0) return 0
        if (target >= piece.length) return piece.length
        var cut = target
        // 若 cut 正好落在一个代理对的中间（cut-1 是 high surrogate，cut 是 low），
        // 则在 cut 处切断会拆散字符，回退一位。
        while (cut > 0) {
            if (cut < piece.length &&
                Character.isHighSurrogate(piece[cut - 1]) &&
                Character.isLowSurrogate(piece[cut])
            ) {
                cut--
                break
            }
            break
        }
        return cut
    }

    private fun buildClient(cfg: AmadeusConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)   // 后台被系统掐 socket 时自动重连重发
            .dns(cachedDns)                    // 优先返亮屏预解析缓存，绕过息屏 Doze 冻住的系统 resolver
        // 有 active network 句柄时，强制 OkHttp 用它的 socketFactory 发包，绕过 Doze 的出站能力降级。
        activeNetwork?.let { builder.socketFactory(it.socketFactory) }
        val proxyStr = cfg.proxy.trim()
        if (proxyStr.isNotBlank() && !proxyStr.equals("无", ignoreCase = true)) {
            runCatching {
                val idx = proxyStr.lastIndexOf(':')
                if (idx > 0) {
                    val host = proxyStr.substring(0, idx).removePrefix("http://").removePrefix("https://").trim()
                    val port = proxyStr.substring(idx + 1).trim().toIntOrNull()
                    if (host.isNotBlank() && port != null) {
                        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
                    }
                }
            }.onFailure { Log.w(TAG, "proxy parse fail: $proxyStr") }
        }
        return builder.build()
    }

    /** 复用 pusher 的共享锁串行下发，避免与推书/storage 查询在同一 BLE 通道并发被吞。
     *  挂起至「锁内发送并 await 完成」结束，保证调用方串行语义。 */
    private suspend fun sendWithLock(block: () -> CompletableDeferred<Unit>) {
        pusher.sendMutex.withLock { block().await() }
    }

    /** 构造 {tag:"chat", stat:"...", ...} 出站回包。 */
    private fun encode(
        stat: String,
        sessionId: String? = null,
        chunkIndex: Int? = null,
        data: String? = null,
        totalChunks: Int? = null,
        message: String? = null
    ): String = buildJsonObject {
        put("tag", JsonPrimitive("chat"))
        put("stat", JsonPrimitive(stat))
        if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        if (chunkIndex != null) put("chunkIndex", JsonPrimitive(chunkIndex))
        if (data != null) put("data", JsonPrimitive(data))
        if (totalChunks != null) put("totalChunks", JsonPrimitive(totalChunks))
        if (message != null) put("message", JsonPrimitive(message))
    }.toString()

    private fun encodeMessages(items: List<Map<String, String>>): JsonArray = JsonArray(
        items.map { m ->
            buildJsonObject {
                put("role", JsonPrimitive(m.getValue("role")))
                put("content", JsonPrimitive(m.getValue("content")))
            }
        }
    )
}
