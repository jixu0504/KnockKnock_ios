# provider-integration-spec.md — iOS 真实模型供应商接入（T37）

> 规格版本：v1（2026-11-23）｜实现：T37 / task-37｜分支：`RhodesLink@ios-port`（Kotlin 2.3.21 / Ktor 3.0.3 Darwin）
> 目标里程碑：「可用软件」M1 —— 用真实模型供应商替换 fake AI 通道，同时保留无配置时的演示回退。

---

## 1. 范围

| 项 | 内容 |
|---|---|
| 真实流式客户端 | `shared/src/iosMain/.../network/ProviderStreamingClient.kt`（Ktor Darwin + OpenAI 兼容 SSE） |
| 供应商配置桥 | `shared/src/iosMain/.../iosbridge/ProviderBridge.kt`（11 家列表 / 保存 / Keychain / 连接测试） |
| 聊天接线 | `ChatEngineBridge.aiClient` 改为 `RoutingStreamingClient`（真实 / 演示按配置路由，保存后下一轮生效） |
| 设置 UI | `iosApp/.../Features/Settings/ProviderSettingsView.swift`（列表 + 编辑 + 连接测试） |
| 演示回退标记 | `ChatResultDto.demo` → `ChatResultDto` / `ChatRoundResult.isDemo` → 聊天页「演示」徽标 |
| 验证资产 | `ios-port/handoffs/mock-provider-sse.py`（本地 OpenAI 兼容 mock，含 401/429/5xx/超时/断连/空响应/流内错误/非 SSE） |

不在本卡范围：群聊/动态/日记等其它 surface 的模型调用（仍走各自既有路径）；视觉/向量/语音四组供应商（已各有独立桥）。

---

## 2. 供应商配置字段表

### 2.1 「当前聊天供应商」持久化字段（SettingsRepository，NSUserDefaults）

| 设置键 | 属性 | 说明 | 默认 |
|---|---|---|---|
| `provider` | `provider` | 供应商 id（11 家之一） | `deepseek` |
| `model_name` | `modelName` | 模型名（DeepSeek 旧名 `deepseek-chat/reasoner` 归一为 `deepseek-v4-flash`） | `deepseek-v4-flash` |
| `custom_url` | `customUrl` | 自定义端点 base URL / 完整 endpoint | 空 |
| `ai_temperature` | `aiTemperature` | 温度（0.00–2.00，内部按 ×100 存 Int） | 0.70 |
| `deepseek_thinking_enabled` | `deepseekThinkingEnabled` | DeepSeek 深度思考（开启时整轮预算 +60s） | false |

### 2.2 API Key（Keychain 优先，不进 NSUserDefaults / 不进备份）

| 项 | 值 |
|---|---|
| Keychain service | `com.rhodeslink.shared.keychain`（T9 `KeychainStore.DEFAULT_SERVICE`） |
| Keychain account | `provider_api_key_<providerId>`（每家独立，切换供应商不串 Key） |
| 读取优先级 | Keychain(`provider_api_key_<id>`) → `SettingsRepository.apiKey`（旧明文迁移兜底） |
| 写入 | `ProviderBridge.save()` 非空 key → Keychain upsert，并**清空** `settings.apiKey` 明文 |
| 清除 | `ProviderBridge.clearApiKey(providerId)` |
| 展示 | 仅掩码（`••••` + 后 4 位），明文永不回传 UI/日志 |

> T9 已知：K/N standalone 测试运行器无 Keychain（`errSecNotAvailable -25291`）。
>
> **T37 实测补充（2026-11-23，iPhone 17e 模拟器）**：用 `CODE_SIGNING_ALLOWED=NO` 构建的 App 是
> `adhoc, linker-signed` 且**无 entitlements**（`codesign -d --entitlements :-` 为空），`SecItemAdd`
> 返回 `errSecMissingEntitlement(-34018)`。为使该构建方式下功能仍可用，`ProviderKeyStore`（新文件
> `network/ProviderKeyStore.kt`）实现：**Keychain 写入成功 → 用 Keychain；OS 拒绝 → 回退 App 沙盒
> `Library/Application Support/provider-secrets/<account>`**（文件受 App 容器数据保护，未进 NSUserDefaults、
> 未进备份白名单）。`ProviderBridge.keyStoreDiagnostics()` 返回实际后端与 OSStatus，自动化日志会记录；
> 正常 Xcode 签名（真机 / 常规模拟器构建）应显示 `keychain=Success`，此时回退文件会被清理。
> 读取优先级：Keychain → 回退文件 → `settings.apiKey` 明文（仅旧数据迁移兜底）。

### 2.3 11 家供应商（顺序 = Android 设置页顺序）

| id | 名称 | 实际 POST 端点 | 鉴权头 |
|---|---|---|---|
| `deepseek` | DeepSeek | `https://api.deepseek.com/chat/completions` | `Authorization: Bearer` |
| `minimax` | MiniMax | `https://api.minimax.cn/v1/chat/completions`（abab* 老模型切 `api.minimax.chat`，`resolveProviderEndpoint`） | Bearer |
| `byte` | 豆包(字节) | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | Bearer |
| `google` | Google | `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`（官方 OpenAI 兼容端点） | Bearer |
| `anthropic` | Anthropic Claude | `https://api.anthropic.com/v1/chat/completions`（官方 OpenAI 兼容端点） | Bearer |
| `ali` | 阿里千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | Bearer |
| `zhipu` | 智谱AI | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | Bearer |
| `siliconflow` | 硅基流动 | `https://api.siliconflow.cn/v1/chat/completions` | Bearer |
| `xiaomi` | 小米 MiMo | `https://api.xiaomimimo.com/v1/chat/completions` | **`api-key`** |
| `openai_compat` | OpenAI | `https://api.openai.com/v1/chat/completions` | Bearer |
| `custom` | 其他厂商（OpenAI 兼容） | 用户填写；`normalizeCustomUrl`：不含 `chat/completions` 时自动补（如 `…/v1` → `…/v1/chat/completions`） | Bearer |

模型候选列表与名称复用 commonMain `shared/network/ProviderConfigs.kt` 的 `providers` map（单一来源），iOS 侧只追加端点覆盖。

### 2.4 「已配置」判定（真实模式闸门）

```
configured = providerId ∈ 11家 && model 非空 && baseUrl 非空 && apiKey 非空
```
任一不满足 → 演示模式，`ProviderStatusDto.reason` 给出中文原因（未选择供应商 / 未填写模型名 / 端点地址为空 / 未填写 API Key）。

---

## 3. 请求构造

- `POST <endpoint>`，`Content-Type: application/json`，`Accept: text/event-stream`。
- Body 复用 Android oracle 模型 `ChatCompletionRequest`：`model`、`messages[{role,content}]`、`stream=true`、
  `temperature`、`thinking`（`ModelCapabilities.thinkingParameter`，DeepSeek 默认 `disabled`，避免小输出预算被思维链吃光）、
  `enable_thinking`（千问）、`reasoning_effort`（MiniMax M3）。
- 不发送 `max_tokens`：由服务商默认值控制（私聊输出协议由提示词约束）。

---

## 4. SSE 解析规则

逐行处理（按字节缓冲到 `\n` 后整行 UTF-8 解码）：

| 输入行 | 处理 |
|---|---|
| 空行 / `: 注释` | 忽略（keep-alive） |
| `event:` / `id:` / `retry:` | 忽略 |
| `data: [DONE]` | 流结束（`sawDoneMarker`） |
| `data: {json}` | 解析 JSON 对象 |
| 其他 `data:` 非 JSON | 忽略（部分厂商心跳） |

`data: {json}` 提取顺序（`SseEventParser.extractContent`）：
1. `choices[0].delta.content`（OpenAI 流式）
2. `choices[0].message.content`（忽略 stream 的非流式 JSON）
3. `choices[0].text`（legacy completions）
4. 空串 / 仅 role 的 delta（首块 `{"delta":{"role":"assistant"}}`）→ 忽略，**不计入首 token**

错误事件（HTTP 200 内的 `data: {"error":{...}}` 或 `{"error":"..."}`）→ `SseEvent.Failure`，以「模型服务返回错误：…」抛出。

**键解析要求**：
- **半包累积**：TCP 分片任意位置切断（含恰好在 `\n` 前）都必须正确拼行；`\r\n` 去掉 `\r`；
- **UTF-8 分块**：多字节字符可能跨分片，必须按字节缓冲后整行解码（不得逐片 `decodeToString`）；
- EOF 时残留未换行字节作最后一行处理（`SseLineDecoder.flush`）。

**非 SSE 回落**：响应 `Content-Type` 非 `text/event-stream` → 读整包 → 按 `choices[0].message.content` 解析；
解析不出且体含 `error` → 「模型服务返回错误」；否则空响应错误。

> **Ktor Darwin 关键坑（T37 实测 + `ProviderDarwinBehaviorTest` 证据）**：必须用
> `client.preparePost(url){...}.execute { response -> ... }`（流式语句），**不能**用 eager
> `client.post(url)`。后者在 Darwin 引擎下要等响应体结束/连接关闭才返回 `HttpResponse`
> （mock `/dribble` 场景实测 **119.6s** 才返回，流式 SSE 退化为整包、首 token 超时误判）；
> `execute` 形式与 T9 已验证的 `prepareGet` 行为一致（首字节即时到达）。

---

## 5. 超时与取消

| 项 | 值/行为 | 来源 |
|---|---|---|
| 首 token 超时 | 30s（`ProviderTimeouts.DEFAULT_FIRST_TOKEN_MS`） | 独立于引擎的阶段预算；首块 role-only 不算 token |
| 整轮预算 | `ChatStageBudget.MODEL_BUDGET_MS` = 120s；DeepSeek 深度思考开启 + `THINKING_BUDGET_BONUS_MS` 60s = 180s | `ProviderTimeouts.forConfig`，与引擎 `ChatEngineConfig.effectiveModelBudgetMs` 同源 |
| 引擎外层兜底 | `withChatStageTimeout("private", stage, remainingBudget(modelBudgetMs))` 仍为最终权威 | `ChatEngine.kt` request() |
| 超时异常 | 统一抛 `ChatStageTimeoutException`（非 CancellationException），引擎按「回复超时了，再试一次吧」分类 | `ProviderErrorMapper.timeout` |
| 取消 | 协程 cancel → Ktor Darwin 取消底层 NSURLSession 任务，任何 `CancellationException` **原样传播**，不吞 | T9 + T37 实测 |
| 超时实现 | **看门狗 + 独立请求 Job**：流式读取跑在独立 Job；调用方用 `withTimeoutOrNull { firstToken.await() }` / `{ work.await() }` 计时，超时则 `requestJob.cancel()` 取消整个请求 | `ProviderRequestRunner`（T37 关键实现） |
| 传输层上限 | `createHttpClient()` 240s（保证业务预算先触发） | `shared/network/HttpClient.kt` |

> **实现关键（T37 模拟器实测，2026-11-23）**：直接在 body 读取挂起点上 `withTimeout` 取消，
> 在 K/N + Ktor Darwin 3.0.3 下不会让读取及时恢复 —— 实测 `/dribble` 场景首 token 后整轮 51s 不返回。
> 因此超时改由 `ProviderRequestRunner` 用「独立请求 Job + 可取消的 `await` 看门狗」实现：
> 计时在 `await` 上（必达），超时后取消整个请求 Job（关闭 NSURLSession 任务）。
> 另一个实测行为：**上一个请求被取消后的短时间内，下一个请求可能等待前一个真正释放**，
> 因此自动化里把「整轮超时」场景放在连接测试矩阵最前，避免测量被前序取消拖慢。

---

## 6. 错误映射

HTTP 状态 → `ProviderErrorKind` → 用户文案（复用 `ModelErrorMessages.forHttpStatus` 中文提示 + 密钥脱敏）：

| 状态 | kind | 用户可见要点 |
|---|---|---|
| 401 / 403 | `Unauthorized` | 鉴权失败：检查 API Key 是否正确、是否与服务商匹配 |
| 429 | `RateLimited` | 请求过于频繁：稍后重试 |
| 400/404/413/422 | `BadRequest` | 接口/模型不存在、上下文超限、参数被拒（含 `isContextLimitError` 可识别文案） |
| 5xx | `ServerError` | 服务商暂时故障 |
| 网络异常/断连 | `Network` | 网络连接失败：<原因> |
| 首 token / 整轮超时 | `Timeout`（抛 `ChatStageTimeoutException`） | 回复超时了，再试一次吧 |
| 流结束但无任何 content / 仅 `[DONE]` | `EmptyResponse` | 模型返回空内容，请检查 Key/模型名 |
| 配置缺失 | `Config` | 未填写 API Key / 端点为空 … |

消息统一前缀 `API error <status>: `（与 Android oracle 一致，引擎/上下文超限分类依赖此前缀与关键字）。
桥接层把真实失败细节写入 `ChatResultDto.error`，覆盖引擎的通用兜底文案（更可操作）；引擎落库的失败系统消息仍用引擎 `FailureMessages`。

---

## 7. 演示模式回退（无配置 / 无 Key）

- 每次 `complete()` 前由 `RoutingStreamingClient` 重新 `resolve()` 配置 → **保存后下一轮立即生效**；
- 未配置 → `DemoStreamingClient`（确定性 fake、不联网、120ms 首响），回复文案明确写「（演示模式）…」；
- 事件标记：`ChatResultDto.demo = true`；Swift `ChatRoundResult.isDemo`；聊天页标题栏显示「演示」橙色徽标；
- `ProviderBridge.status()` / `ChatEngineBridge.providerStatus()` 返回 `ProviderStatusDto`（mode=`demo|real`、原因、摘要）；
- 配置成功后 `demo=false`，无需重启 App。

---

## 8. 桥接 API（Swift 可见）

### 8.1 `ProviderBridge`（`RhodesAppGraph.providers()`）

| 方法 | 返回 | 线程 |
|---|---|---|
| `list()` | `[ProviderDto]`（id/name/modelsCsv/configured/selected/requiresCustomUrl/endpointHint） | 同步 |
| `selection()` | `ProviderSelectionDto`（含掩码 keyPreview、temperature、customUrl） | 同步 |
| `status()` | `ProviderStatusDto`（mode/reason/summary/…） | 同步 |
| `save(providerId, model, customUrl, apiKey, temperature)` | `String`（""=成功；非空=失败原因） | 同步 |
| `clearApiKey(providerId)` | `Boolean` | 同步 |
| `test(providerId, model, customUrl, apiKey, temperature, onDone)` | `IosCancellable`；结果 `ProviderTestResultDto`（ok/latencyMs/message/replyPreview） | 后台 → Main 回调 |
| `overrideProbeTimeouts(firstTokenMs, totalMs)` | —（自动化验证用，UI 不暴露） | 同步 |

### 8.2 `ChatEngineBridge`

| 方法 | 说明 |
|---|---|
| `providerStatus()` | 当前真实/演示模式 + 原因，供聊天页/设置页显示 |
| `send(...)` 的 `ChatResultDto` | 新增 `demo: Boolean` |

### 8.3 Swift 包装

`AppGraphClient` extension：`loadProviders / providerSelection / providerStatus / saveProviderConfig / clearProviderApiKey / testProviderConnection / overrideProviderProbeTimeouts`，另定义 `ProviderItem / ProviderSelectionItem / ProviderStatusItem / ProviderTestResultItem / ProviderTestHandle`（SwiftUI 不接触 Kotlin 类型）。

---

## 9. iOS 设置页「模型供应商」

路由：`Route.settingsProviders`（`rhodes://settings/providers` 深链）；入口在设置主页「模型供应商」行。

交互：
1. 顶部状态卡：真实模式 / 演示模式、供应商、模型、端点、演示原因；
2. 11 家列表：已配置 badge、当前选中圆点、端点提示；点击进入编辑；
3. 编辑卡：模型输入 + 候选 chip、自定义端点（仅 custom）、API Key（SecureField，已保存时占位提示「留空不变」）、温度滑杆；
4. 「保存」：写 settings + Keychain，提示「下一轮发送生效」；
5. 「连接测试」：最小请求（system+user ping，温度 0），显示成功/失败、首 token 延迟、回复预览；
6. 「清除已保存的 API Key」；
7. 说明卡（保存即生效 / Key 只进 Keychain / 演示模式语义 / Google·Anthropic 兼容端点）。

---

## 10. 测试与验证

### 10.1 纯逻辑单测（无需网络）

`shared/src/iosTest/.../network/ProviderStreamingParserTest.kt`（19 例）：
- 半包累积 / `\r\n` / EOF 残留行；逐字节喂入 UTF-8 中文不截断；
- `data:` content / `[DONE]` / keep-alive / role-only / 空 choices / 流内 error（object & string）；
- 非 SSE JSON 回落与 error 体提权；
- 11 家目录完整性、Google/Anthropic 兼容端点、自定义 URL 归一、超时预算（120s/180s）、缺失原因中文。

命令（mock 服务器非必需）：
```bash
source ios-port/toolchain/env.sh
cd RhodesLink && ./gradlew --no-daemon :shared:iosSimulatorArm64Test \
  --tests "com.rhodes.privatechat.shared.network.ProviderStreamingParserTest"
```

### 10.2 模拟器端到端（mock 供应商，无需真实 Key）

```bash
# 1) 启动 mock（127.0.0.1:18766，ATS loopback 豁免已在 T9 实测）
python3 ios-port/handoffs/mock-provider-sse.py
# 2) 构建 framework + App
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project RhodesLink/iosApp/RhodesLink.xcodeproj -scheme RhodesLink \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath ios-port/iosApp-run/DerivedData CODE_SIGNING_ALLOWED=NO build
# 3) 启动自动化（演示回退 / 真实流式 / 401·429·500 错误映射 / 取消 / 连接测试矩阵）
xcrun simctl launch booted com.rhodeslink.app \
  --selftest --provider-selftest --provider-fast-timeouts \
  --provider-base http://127.0.0.1:18766/v1/chat/completions \
  --provider-model mock-model --provider-key sk-mock-test-key
# 4) 取证据
xcrun simctl spawn booted log show --last 3m --predicate 'eventMessage CONTAINS "provider"' --style compact
APP=$(xcrun simctl get_app_container booted com.rhodeslink.app data)
cat "$APP/Documents/ios-shell-selftest.log"
curl -s http://127.0.0.1:18766/stats     # 服务器侧场景计数 + BRK（取消）证据
```

mock 场景矩阵（`mock-provider-sse.py`）：

| 场景 | 路径后缀 | 期望 |
|---|---|---|
| 正常流式（中文、跨分片、keep-alive、role-only、usage、[DONE]） | `/v1/chat/completions` | ok，中文全文完整 |
| 首 token 超时 | `/slow-first`（3s 后首 token；自动化用 1.5s 预算） | 超时错误 |
| 整轮超时 | `/dribble`（首 token 后每 3s 一个；4s 整体预算） | 超时错误 |
| 半包断连 | `/disconnect`（半包后断开） | 空响应 / 网络错误，不崩溃 |
| 空响应 | `/empty`（仅 `[DONE]`） | 「返回空内容」 |
| 流内错误 | `/error-event`（200 + error 事件） | 「模型服务返回错误」 |
| 401 / 429 / 500 | `/unauthorized` `/ratelimited` `/server-error` | 对应中文错误映射 |
| 非 SSE JSON | `/non-stream` | 回落成功（预览含回复） |
| 无响应头 | `/hang`（8s 不发头） | 首 token 超时 |

自动化启动参数：`--provider-selftest`（开关）、`--provider-base/--provider-model/--provider-key`、`--provider-fast-timeouts`（1.5s/4s 预算，加快矩阵）、`--provider-open-settings`（自动导航到设置页便于截图）。

### 10.3 真实供应商（用户持有 Key 时）

App 内路径：设置 → 模型供应商 → 选供应商 → 填模型（可用 chip）→ 填 API Key → 保存 → 连接测试 → 聊天。
- OpenAI：`openai_compat`，模型 `gpt-4o-mini`；
- DeepSeek：`deepseek`，模型 `deepseek-v4-flash`（Key 需与供应商匹配）；
- 自定义：`custom`，填 `https://your-host/v1` + 模型名 + Key（任何 OpenAI 兼容网关/本地 vLLM/Ollama 均可）。
判定：连接测试显示「连接成功 + 首 token 延迟 + 回复预览」；聊天回复不再是「（演示模式）」且无「演示」徽标。
DeepSeek 若需思考模式，打开 `deepseek_thinking_enabled`（整轮预算自动 +60s）。

---

## 11. 已知限制

1. 11 家统一走 OpenAI 兼容协议；Google/Anthropic 使用其官方 OpenAI 兼容端点（非原生 Gemini/Messages 协议）。若某服务商未来下线兼容端点，需要补原生协议适配。
2. Anthropic 官方 OpenAI 兼容行为受其 beta 语义约束（如 system 只能一条字符串）；私聊 prompt 单 system 消息，实际可用。
3. 不做自动模型回退/多 Key 轮询（超出 M1 范围）。
4. 真机 Keychain 首次解锁前读取可能返回 `InteractionNotAllowed`（T9 已知）；无 entitlements 的
   `CODE_SIGNING_ALLOWED=NO` 模拟器构建返回 `MissingEntitlement(-34018)`。两种情况下 `ProviderKeyStore`
   自动回退 Application Support 私密文件（`provider-secrets/`），功能可用；`keyStoreDiagnostics()` 可确认真实后端。
5. 聊天 UI 的 delta 仍是落库后打字机（lead R2「整包落库」），未做逐 token 实时渲染。
6. 测试连接计入 1 次模型调用（有 token 成本）。
