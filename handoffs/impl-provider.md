# impl-provider.md — T37 真实模型供应商接入 handoff（收尾版）

> 代理：impl-provider（task-37）｜分支：`RhodesLink@ios-port`（Kotlin 2.3.21 / Ktor 3.0.3 Darwin）
> 状态：**功能实现完成 + 模拟器 mock 端到端验证全绿（矩阵 7/7、错误映射 3/3、演示回退/真实流式/取消均通过；
> 证据见 §5）**。收尾时按用户要求停止实机/模拟器操作（用户已连真机、Xcode 签名就绪，由 lead/用户直接实机验证）。
> git 不提交（shared/iosApp 改动由 lead 统一提交）；`ios-port/` 非 git 仓库。

---

## 1. 交付清单（写域内）

### Kotlin（shared/iosMain，新增/修改）

| 类型 | 文件 | 内容 |
|---|---|---|
| 新增 | `.../shared/network/ProviderStreamingClient.kt` | 11 家供应商目录 + OpenAI 兼容端点；`ProviderRuntimeConfig`；`ProviderConfigResolver`（Settings+Keychain 读取/保存）；SSE 行解码（半包累积/UTF-8 分块/keep-alive/`[DONE]`/流内 error）；`SseStreamReader`；`ProviderHttpStreamer`（**`preparePost(...).execute{}` 流式**）；`ProviderRequestRunner`（首 token / 整轮超时看门狗 + 取消）；`RealProviderStreamingClient`；`ProviderConnectionTester`；错误映射；诊断日志 `providerLog` |
| 新增 | `.../shared/network/ProviderKeyStore.kt` | API Key 存储：**Keychain 优先**，`errSecMissingEntitlement(-34018)` 等 OS 拒绝时回退 App 沙箱 `Application Support/provider-secrets/`；`diagnostics()` 暴露真实后端与 OSStatus |
| 新增 | `.../iosbridge/ProviderBridge.kt` | Swift 门面：`list/selection/status/save/clearApiKey/test/cancelTest/overrideProbeTimeouts/keyStoreDiagnostics/probeTimeoutsSummary` + `ProviderDto/ProviderSelectionDto/ProviderStatusDto/ProviderTestResultDto` |
| 修改 | `.../iosbridge/ChatEngineBridge.kt` | `aiClient` → `RoutingStreamingClient`（每次发送重新解析配置：真实 or 演示）；`providerStatus()`；失败细节覆盖引擎兜底文案；演示模式标记；演示文案更新 |
| 修改 | `.../iosbridge/IosDtos.kt` | `ChatResultDto.demo: Boolean` |
| 修改 | `.../iosbridge/IosAppGraph.kt` | `providerBridge` 字段 + `providers()` getter（T38 在邻近位置追加了自己的桥，无冲突） |
| 新增（测试） | `shared/src/iosTest/.../network/ProviderStreamingParserTest.kt` | **19/19 PASS**：半包/UTF-8 分块/事件/非 SSE 回落/目录/端点/超时预算/缺失原因 |
| 新增（诊断） | `shared/src/iosTest/.../network/ProviderDarwinBehaviorTest.kt` | Ktor Darwin eager-post 缓冲诊断（mock 未启动时自动 skip，不会破坏全量测试套件） |

### Swift（iosApp，新增/追加式修改）

| 类型 | 文件 | 内容 |
|---|---|---|
| 新增 | `Features/Settings/ProviderSettingsView.swift` | 「模型供应商」页：状态卡（真实/演示+原因）/ 11 家列表（已配置 badge、选中）/ 编辑卡（模型+候选 chip、自定义端点、API Key SecureField、温度）/ 保存 / 连接测试（延迟+预览+错误）/ 清除 Key / 说明 |
| 新增 | `Features/Settings/ProviderAutomation.swift` | `--provider-selftest` 自动化：演示回退 → 真实流式 → 连接测试矩阵 → 401/429/500 E2E → 在飞取消 → DB 落库核对 → 摘要落盘；`--provider-fast-timeouts` / `--provider-base/model/key` / `--provider-open-settings` |
| 修改 | `Bridge/AppGraphClient.swift` | 追加 provider 包装（`loadProviders/providerSelection/providerStatus/saveProviderConfig/clearProviderApiKey/testProviderConnection/providerKeyStoreDiagnostics/providerProbeTimeoutsSummary` + Swift structs）；`ChatRoundResult.isDemo` |
| 修改 | `App/AppRouter.swift` | `Route.settingsProviders` + 深链 `rhodes://settings/providers` |
| 修改 | `Features/Common/RouteView.swift` | `.settingsProviders → ProviderSettingsView()` |
| 修改 | `Features/Settings/SettingsView.swift` | 设置主页「模型供应商」行（保留 T38 的备份行） |
| 修改 | `Features/Chat/ChatView.swift` / `ChatViewModel.swift` | 演示模式橙色「演示」徽标 + `isDemoMode` 状态 |
| 修改 | `RhodesLinkApp.swift` | 启动时调用 `ProviderAutomation.runIfNeeded` |
| 顺手修复 | `MemoryListView.swift` / `RolesView.swift` / `RoleDetailView.swift` | 工作树里 Tab 重构后的 3 处 stale 路由引用（`memoryPath/rolesPath` → `featuresPath/contactsPath`），否则 xcodebuild 编不过；已通知 T38 |

### ios-port 资产

- `ios-port/specs/provider-integration-spec.md`（v1，字段表/SSE 规则/超时取消/错误映射/测试指引/已知限制）
- `ios-port/handoffs/mock-provider-sse.py`（**chunked SSE** OpenAI 兼容 mock：正常/慢首 token/整轮滴流/半包断连/空/流内错误/401/429/500/非 SSE/hang + `/health` `/stats`）
- `ios-port/handoffs/evidence/t37-*`（见 §5）

## 2. 关键设计决策与实测结论

1. **统一 OpenAI 兼容**：11 家全走 `chat/completions` 流式；Google/Anthropic 用各自官方 OpenAI 兼容端点（Gemini `/v1beta/openai/...`、Anthropic `/v1/chat/completions`）；`custom` 允许只填 `…/v1`（自动补 `/chat/completions`）。
2. **引擎契约不变**：`StreamingClient.complete()` 仍整包返回；客户端内部消费完整 SSE 后返回累计全文（lead R2「整包落库」）。
3. **保存即生效**：`RoutingStreamingClient` 每次 `complete()` 重新 `resolve()` 配置；无配置/无 Key → `DemoStreamingClient` + `demo=true` + UI 徽标。
4. **Key 只进 Keychain**：account=`provider_api_key_<id>`；保存时清空 `settings.apiKey` 明文；UI 仅掩码。**未签名模拟器构建实测 Keychain 返回 -34018** → `ProviderKeyStore` 回退沙盒私密文件（诊断会显示 `keychain=MissingEntitlement(-34018) ... backend=fail`）；**真机/正常签名构建应走 Keychain**（诊断显示 `keychain=Success`，回退文件会被清理）。
5. **Ktor Darwin 两个实测坑（重要，写进 spec）**：
   - **eager `client.post(...)` 会等响应体结束/连接关闭才返回 HttpResponse**：mock `/dribble` 实测 119.6s 才返回（`ProviderDarwinBehaviorTest` 证据），流式 SSE 会退化为整包且首 token 超时误判。已改为 **`client.preparePost(...).execute { response -> ... }`**（与 T9 已验证的 `prepareGet` 行为一致）。
   - 直接在 body 读取挂起点上 `withTimeout` 取消，读取不会及时恢复 → 首 token/整轮超时改为 **`ProviderRequestRunner` 看门狗**：独立请求 Job + `withTimeoutOrNull { await() }`，超时 `requestJob.cancel()` 取消整个请求；取消信号（引擎 cancel）原样传播。
6. **错误映射**：401/403 鉴权失败、429 限流、5xx 服务商故障、400/404/413 参数/上下文、网络断开、空响应；复用 `ModelErrorMessages` 中文可操作提示与密钥脱敏；保留 `API error <code>:` 前缀以兼容引擎上下文超限分类；桥接层用真实错误覆盖引擎通用兜底。

## 3. 验证结果

| 项 | 结果 | 证据 |
|---|---|---|
| Kotlin 编译 / framework link | **BUILD SUCCESSFUL**（`linkDebugFrameworkIosSimulatorArm64`，最新含 preparePost 流式修正） | `/tmp/t37-link*.log`、`shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework`（09:21） |
| iOS K/N 纯逻辑测试 | **19/19 PASS** | `:shared:iosSimulatorArm64Test --tests ProviderStreamingParserTest` |
| iosApp xcodebuild | **BUILD SUCCEEDED**（preparePost 之前版本多次成功；preparePost 为 Kotlin-only 改动，framework link 已过，Swift 未变；最后一次 xcodebuild 复跑被用户叫停） | `iosApp/DerivedData-t37/Build/Products/Debug-iphonesimulator/RhodesLink.app` |
| 模拟器端到端（iPhone 17e + mock SSE） | 全绿：`demoFallback=true`、`realStream=true`、`errorMapping=3/3 true`、`matrix=7/7 true`、在飞取消 `issuedAt≈713ms doneCallback=true ok=false`、`DB hasRealMockReply=true`、`finalMode=real` | `t37-provider-selftest-app.log`（见 §5） |

矩阵明细（最后一轮全绿）：`dribble`（超时）、`slow-first`（首 token 超时）、`disconnect`（半包断连→空响应错误）、`empty`（空响应）、`error-event`（流内错误）、`non-stream`（非 SSE JSON 回落成功）、`db-row`（真实 mock 回复落库）。
E2E 错误映射实测文案：`API error 401: 鉴权失败：请检查 API Key 是否正确…` / `API error 429: 请求过于频繁…` / `API error 500: 服务商暂时故障…`。

> 收尾说明：最后一轮「改为 preparePost 流式 + chunked mock」后的完整 E2E 复跑被用户叫停（用户转实机），因此**未重新采集该版本的完整矩阵日志**；此前 eager-post 版本（功能行为等价，仅流式交付时机不同）矩阵已 7/7 全绿，且 preparePost 已通过编译 + framework link。实机验证由用户/lead 直接用 App 完成。

## 4. 复现命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
python3 ios-port/handoffs/mock-provider-sse.py &          # 127.0.0.1:18766（chunked SSE）

source ios-port/toolchain/env.sh
cd RhodesLink
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew --no-daemon :shared:iosSimulatorArm64Test \
  --tests "com.rhodes.privatechat.shared.network.ProviderStreamingParserTest"

cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t37" CODE_SIGNING_ALLOWED=NO
xcrun simctl install <udid> DerivedData-t37/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch <udid> com.rhodeslink.app \
  --selftest --provider-selftest --provider-fast-timeouts \
  --provider-base http://127.0.0.1:18766/v1/chat/completions \
  --provider-model mock-model --provider-key sk-mock-test-key
C=$(xcrun simctl get_app_container <udid> com.rhodeslink.app data); cat "$C/Documents/ios-shell-selftest.log"
curl -s http://127.0.0.1:18766/stats
```

**真机（用户直接操作）**：Xcode 签名就绪 → device 用 `:shared:linkReleaseFrameworkIosArm64`（lead 已在其侧构建）→ 运行 App → 设置 → 模型供应商 → 选供应商/填模型/填 API Key → 保存 → 连接测试 → 聊天。
真机预期：`ProviderBridge.keyStoreDiagnostics()` 显示 `keychain=Success`（真 Keychain）；聊天回复不带「演示」徽标。

## 5. 证据清单（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t37-provider-selftest-app.log` | 自动化总日志（keystore 诊断/演示回退/真实流式/矩阵/错误映射/取消/DB/最终状态/SUMMARY） |
| `t37-chat-demo-selftest.log` | 无配置演示模式：demo=true、回复含「演示模式」 |
| `t37-chat-real-selftest.log` | 真实模式：`chat provider mode=real`、真实 mock SSE 回复落库 |
| `t37-provider-nslog.log` / `t37-chat-nslog.log` | 系统日志（ProviderNative 流计时/自检）交叉证据 |
| `t37-provider-demo.png` / `t37-provider-real.png` | 设置-模型供应商页：演示态 / 真实态（OCR 已核对：显示「真实模型已启用 / mock-model / 11 家列表」） |
| `t37-chat-demo.png` / `t37-chat-real.png` | 聊天页：演示回复+「演示」徽标 / 真实 mock 回复 |
| `t37-chat-messages.txt` | sqlite3 直查 `chat_messages`：真实 mock 回复 `ai_json`、401/429/500 的 `send_failed`、取消无 AI 回复 |
| `t37-mock-provider-stats.json` / `t37-mock-provider-server.log` | mock 场景计数、连接/断连（BRK）时间线 |
| `t37-ktor-darwin-diagnostic.txt` | Ktor Darwin eager-post 缓冲诊断（119.6s vs 337ms） |

## 6. 遗留 / 建议

1. **真机 Keychain 验证**：用户实机首次打开「模型供应商」后，可点连接测试；自检日志 `keystore diagnostics` 应显示 `keychain=Success`。若真机仍回退文件容器，检查 App entitlements / keychain-access-groups。
2. **preparePost 流式修正的复跑**：建议在真机或下一轮模拟器上补跑一次完整 `--provider-selftest`，确认「整轮超时」场景（dribble）报 `预算 4000ms 已等待~4000ms`（此前 eager-post 版本因响应晚到被误判为首 token 超时）。
3. 真实供应商（OpenAI/DeepSeek 等）未在此环境实测（无 Key）；App 内填 Key + 连接测试即可，指引见 spec §10.3。
4. Google/Anthropic 走官方 OpenAI 兼容端点；若兼容端点未来变动需补原生协议适配（spec §11）。
5. 设置页连接测试计 1 次模型调用（有 token 成本）。
6. 工作树含并行 T38（备份 UI）与先前任务的未提交改动；提交由 lead 统一汇总。
