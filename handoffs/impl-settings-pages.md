# impl-settings-pages.md — T46 P1 设置页（聊天表现 / 提示词编辑器 / 识图模型）handoff

> 代理：`impl-settings-pages`（task-46）｜分支：`RhodesLink@ios-port`（Kotlin 2.3.21 / SwiftUI iOS 18+）
> 状态：**实现完成 + 模拟器端到端全绿**（聊天参数读写落盘、提示词保存/恢复默认/空串禁用往返、
> 识图配置 `effectiveVisionConfiguration.configured=true`、图片聊天走真实识图网关 mock E2E、5 张截图）。
> git 不提交（lead 统一提交）；`ios-port/` 非 git 仓库。
> 构建纪律：T45/T48 并行 gradle 排队；**发现并绕过 iCloud「dataless」文件导致的 gradle 随机失败**（见 §6）。

---

## 1. 交付清单（写域内）

### Kotlin（shared/iosMain，仅新增方法，不改已有签名）

| 文件 | 内容 |
|---|---|
| `.../iosbridge/SettingsBridge.kt`（修改，追加） | T46 扩展：`chatPerformance()` / `saveChatPerformance(...)`；`promptCatalog()` / `promptItem(...)` / `savePromptItem(...)` / `resetPromptItem(...)` / `promptResolvedText(...)` / `isPromptCustom(...)`；`visionStatus()` / `saveVisionConfiguration(...)` / `clearVisionConfiguration()` / `flushDefaults()`；DTO：`ChatPerformanceDto` / `PromptEntryDto` / `PromptItemDto` / `PromptSaveResultDto` / `VisionStatusDto`；内部 `PromptResolver` 适配器（把 `SettingsRepository` 接到 T18 `PromptEditor`）；提示词目录常量 38 项（14 主模板 + 24 模块）。 |

说明：
- 不改 `ChatEngineBridge.kt` / `IosAppGraph.kt`（T45 并行写域），全部能力经既有 `graph.settings()` 暴露；
- 提示词保存/恢复默认严格走 T18 `PromptEditor`（值 + `_custom=true` + `_version=39` 三键；恢复默认删三键；空串=有意禁用）；
- 识图状态同时给出 `configured`（= commonMain `effectiveVisionConfiguration().configured`，聊天引擎判据）
  与 `engineConfigured`（额外考虑 T37 聊天 Key 在 Keychain 的兜底路径）。

### Swift（iosApp）

| 类型 | 文件 | 内容 |
|---|---|---|
| 新增 | `Features/Settings/ChatPerformanceSettingsView.swift` | 聊天表现页：温度 / 深度思考 / 历史回看轮数 / 台词单段字数上限 / 旁白单段字数上限 + 保存（保存后立即读回校验） |
| 新增 | `Features/Settings/PromptEditorView.swift` | 提示词编辑器：分组列表（私聊/群聊/内容生成）+ 详情（TextEditor、保存、恢复默认、清空禁用、默认/自定义/已禁用徽标、键名、version、变量占位符推荐/可用/旧版兼容 chip 网格）；截图钩子 `--settings-prompt-demo`、`--settings-prompt-detail-scroll` |
| 新增 | `Features/Settings/SettingsAutomation.swift` | `--settings-selftest`（写+断言）/ `--settings-selftest-verify`（冷启动只读断言持久化）/ `--settings-selftest-restore`（恢复默认并清识图） |
| 修改 | `Features/Settings/ProviderSettingsView.swift` | 新增「识图模型（图片聊天）」section：状态行（configured/engineConfigured/key/backend）、供应商 Picker、端点、模型名、API Key（Keychain）、保存/清空；截图钩子 `--settings-vision` 自动滚动到该卡 |
| 修改 | `Features/Settings/SettingsView.swift` | 二级路由新增「聊天表现」「提示词编辑器」（替换原提示词占位行） |
| 修改 | `App/AppRouter.swift` | 新路由 `.settingsChatPerformance` / `.settingsPromptEditor`；深链 `rhodes://settings/{chat-performance|prompts}`（含别名） |
| 修改 | `Features/Common/RouteView.swift` | 两个新路由 → 新页面 |
| 修改 | `Bridge/AppGraphClient.swift` | 追加 T46 extension（Kotlin 类型只在文件内部出现）：`chatPerformance/saveChatPerformance/promptCatalog/promptItem/savePromptItem/resetPromptItem/visionStatus/saveVisionConfiguration/clearVisionConfiguration/flushSettingsDefaults` + Swift struct |
| 修改 | `RhodesLinkApp.swift` | 启动接 `SettingsAutomation.runIfNeeded`（`--settings-selftest*`，对用户路径零影响） |

### ios-port 资产

- `ios-port/specs/settings-pages-spec.md`（设置键表 / T18 键语义 / 桥 API / 验收 / 已知差异）
- `ios-port/handoffs/t46-mock-vision.py`（OpenAI 兼容识图 mock：`visibleSummary` 带唯一标记 `T46-MOCK-VISION-OK`）
- `ios-port/handoffs/evidence/t46-*`（见 §5）

---

## 2. 关键设计决策

1. **聊天表现键沿用 Android oracle**（`app/ui/chatsettings/ChatSettingsScreen.kt`）：
   `ai_temperature`（0.70）、`deepseek_thinking_enabled`（false）、`history_messages`（15）、
   **回复长度上限 = `dia_max`（台词单段 300）+ `nar_max`（旁白单段 300）**。
   `SettingsRepository` 没有单一的「整轮回复 token 上限」键；Android 原版聊天参数页同样用单段字数控制回复长度，
   本卡按 oracle 键实现并在 spec §6 记录开放问题（若 lead 要硬 `max_tokens` 上限需新增键 + 改引擎预算，跨卡）。
   保存即写 NSUserDefaults；下一轮请求由 `PrivatePromptAssembler`/`ProviderConfigResolver` 重新读取 settings（无需重启）。
2. **提示词编辑器完整复用 T18 契约**：目录来自 `PromptTemplates` / `PromptModuleDefaults`；
   保存 `prompt_<type>_<mode>`（模块 `prompt_<module>_<type>_<mode>`）+ `_custom` + `_version`；
   恢复默认删三键；空串保存=有意禁用、不回落默认；保存前过 `PromptSyntaxValidator`（畸形语法阻断）；
   未知占位符只警告（与 Android 行为一致）；占位符说明来自 `PromptPlaceholderRegistry`（推荐/可用/旧版兼容三层）。
   覆盖私聊 4 模式 + 群聊 4 模式 + 动态/评论/日记 + 派遣 3 阶段 = 14 主模板，及私聊/群聊 × 4 模式 × 3 模块 = 24 模块。
3. **识图模型与 Android 字段完全一致**：写 `vision_mode=independent / vision_provider / vision_base_url / vision_model_name / vision_api_key`；
   Key 另写 Keychain（account=`vision_api_key_<provider>`，与 T37 聊天 Key 的 `provider_api_key_<id>` 命名空间分开，互不覆盖）。
   保存后 `effectiveVisionConfiguration().configured=true`，`ChatEngineBridge.analyzeVision` 走 `createVisionGateway(...)` 真实网关（T42 已接线）。
4. **识图供应商切换的模型占位防覆盖**：`ProviderConfigs.kt:72` 给 `custom` 供应商的候选模型是占位文案 `自填`，原实现切到自定义端点时会把「识图模型名」覆盖成 `自填`；已改为自定义端点保留用户已填模型（仅空时给提示），截图证据 `t46-settings-vision.png` 现显示 `mock-vision`。
5. **`vision_api_key` 明文镜像（已知 iOS 差异）**：commonMain `effectiveVisionConfiguration()` 只读 settings，且实现它的
   `ChatEngineBridge` 本批次禁改（T45 并行）。为保证 `configured=true` 且图片聊天立即走真实识图，把 Keychain 密钥同时镜像到
   `vision_api_key`。Keychain 仍是权威副本；后续 T45 完成后可给 `VisionConfiguration` 增加 Keychain provider 并移除镜像。
6. **自动化三阶段**（`--settings-selftest` / `-verify` / `-restore`）：写入阶段断言 + `flushDefaults()`
   强制 NSUserDefaults 落盘；**另起进程**做冷启动只读断言（真正证明持久化）；最后恢复阶段清理测试态。
   `flushDefaults` 只用于自检取证，生产路径不依赖。

---

## 3. 验证结果（模拟器 iPhone 17，UDID A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA）

| 项 | 结果 | 证据 |
|---|---|---|
| `:shared:linkDebugFrameworkIosSimulatorArm64` | **BUILD SUCCESSFUL** | `evidence/t46-framework-link.log` |
| iosApp `xcodebuild`（iOS 18+ 模拟器） | **BUILD SUCCEEDED** | `evidence/t46-xcodebuild.log` |
| 写入阶段 7 项断言 | **7 PASS / 0 FAIL** | `evidence/t46-settings-selftest-app.log` |
| 冷启动持久化 3 项断言 | **3 PASS / 0 FAIL** | `evidence/t46-settings-verify-app.log` |
| NSUserDefaults 落盘核对 | `ai_temperature=123`, `deepseek_thinking_enabled=true`, `history_messages=42`, `dia_max=555`, `nar_max=444`, `vision_mode=independent`, `vision_provider=custom`, `vision_base_url=http://127.0.0.1:18764/v1/chat/completions`, `vision_model_name=mock-vision`, `vision_api_key=sk-mock-vision-t46`；`prompt_*` 无残留（恢复默认已删键） | `evidence/t46-nsuserdefaults-write.txt` |
| 图片聊天真实识图 E2E | mock 识图服务收到 `POST /v1/chat/completions model=mock-vision has_image=True`；`image-send.log` `engine done ok=true`；`chat_messages` 图片消息 `visionSummary` 含 **T46-MOCK-VISION-OK**（证明非本地演示识图桩） | `evidence/t46-vision-e2e-image-send.log`、`t46-mock-vision-received.log`、`t46-mock-vision-stats.json`、`t46-vision-db.txt` |
| 页面截图 | 设置主页二级路由（聊天表现/提示词编辑器两行）、聊天表现页、提示词列表、提示词详情、提示词占位符说明（推荐/可用/旧版兼容三层 chip）、模型供应商-识图模型（`configured=true engine=true`、独立配置 badge） | `evidence/t46-settings-home-rows.png`、`t46-settings-chat-performance.png`、`t46-settings-prompt-list.png`、`t46-settings-prompt-detail.png`、`t46-settings-prompt-placeholders.png`、`t46-settings-vision.png` |
| 恢复默认（清理测试态） | `--settings-selftest-restore`：temp=0.70/false/history=15/dia=300/nar=300、vision_mode=auto | `evidence/t46-settings-restore.log` |

### 3.1 写入阶段断言逐条

```
settings-check PASS chat-performance.save-readback result='' temp=1.23 thinking=true history=42 diaMax=555 narMax=444
settings-check PASS prompt.save-readback saveOk=true warnings=0 isCustom=true version=39 len=73 defaultLen=890
settings-check PASS prompt.invalid-syntax-blocked ok=false error={{ 格式不正确，请使用 {{名称}}。
settings-check PASS prompt.reset-default isCustom=false version=0 len=890 placeholders=40 recommended=27 legacy=10
settings-check PASS prompt.module-empty-disable saveOk=true isCustom=true isDisabled=true textLen=0
settings-check PASS prompt.module-reset isCustom=false
settings-check PASS vision.save-effective-configured result='' mode=independent provider=custom configured=true engineConfigured=true keyConfigured=true backend=file effectiveEndpoint=http://127.0.0.1:18764/v1/chat/completions
```

### 3.2 冷启动持久化断言（另起进程，只读）

```
settings-check PASS persisted.chat-performance temp=1.23 thinking=true history=42 diaMax=555 narMax=444
settings-check PASS persisted.prompt-reset isCustom=false len=890
settings-check PASS persisted.vision-configured mode=independent provider=custom configured=true engineConfigured=true endpoint=... model=mock-vision
```

---

## 4. 复现命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
cd RhodesLink

# 1) shared framework（注意：先 pgrep 排队，T45/T48 并行）
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64

# 2) iosApp
cd iosApp
TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t46" CODE_SIGNING_ALLOWED=NO

# 3) 安装 + 写入阶段自检
UDID=A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA
xcrun simctl install $UDID DerivedData-t46/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch $UDID com.rhodeslink.app --selftest --settings-selftest
C=$(xcrun simctl get_app_container $UDID com.rhodeslink.app data)
cat "$C/Documents/ios-shell-selftest.log"
plutil -p "$C/Library/Preferences/com.rhodeslink.app.plist"

# 4) 冷启动持久化校验（另起进程）
xcrun simctl terminate $UDID com.rhodeslink.app
xcrun simctl launch $UDID com.rhodeslink.app --selftest --settings-selftest-verify

# 5) 真实识图 E2E（mock 服务 + 图片消息）
python3 ios-port/handoffs/t46-mock-vision.py 18764 &
xcrun simctl terminate $UDID com.rhodeslink.app
xcrun simctl launch $UDID com.rhodeslink.app --open-url "rhodes://chat/session_amiya" --image-send-demo
# 等 20s：Documents/image-send.log 应出现 engine done ok=true；
sqlite3 "$C/Library/Application Support/rhodes.db" \
  "select id,type,substr(content,1,300) from chat_messages where type='image' order by id desc limit 1;"
# 期望包含 T46-MOCK-VISION-OK

# 6) 截图
xcrun simctl launch $UDID com.rhodeslink.app --tab settings --open-url "rhodes://settings/chat-performance"
xcrun simctl launch $UDID com.rhodeslink.app --tab settings --open-url "rhodes://settings/prompts" --settings-prompt-demo
xcrun simctl launch $UDID com.rhodeslink.app --tab settings --open-url "rhodes://settings/providers" --settings-vision
xcrun simctl io $UDID screenshot out.png

# 7) 恢复默认（清测试态）
xcrun simctl terminate $UDID com.rhodeslink.app
xcrun simctl launch $UDID com.rhodeslink.app --selftest --settings-selftest-restore
```

---

## 5. 证据清单（`ios-port/handoffs/evidence/`）

| 文件 | 内容 |
|---|---|
| `t46-framework-link.log` | shared framework 链接日志 |
| `t46-xcodebuild.log` | iosApp 编译日志（BUILD SUCCEEDED） |
| `t46-settings-selftest-app.log` | 写入阶段 7 项断言 + flush |
| `t46-settings-verify-app.log` | 冷启动持久化 3 项断言 |
| `t46-nsuserdefaults-write.txt` | 写入后 NSUserDefaults plist 全量（含 10 个 T46 键） |
| `t46-vision-e2e-image-send.log` | 图片发送自检日志（`engine done ok=true`） |
| `t46-mock-vision-received.log` / `t46-mock-vision-stats.json` | mock 识图服务收到真实请求（含 image data） |
| `t46-vision-db.txt` | `chat_messages` 图片消息 `visionSummary` 含 mock 标记 |
| `t46-settings-home-rows.png` | 设置主页二级路由（聊天表现 / 提示词编辑器） |
| `t46-settings-chat-performance.png` | 聊天表现页（temp=1.23 / thinking=true / history=42 / 555 / 444） |
| `t46-settings-prompt-list.png` | 提示词编辑器列表（分组 + 默认/自定义/已禁用徽标 + 键名） |
| `t46-settings-prompt-detail.png` | 提示词详情（键 `prompt_private_offline`、TextEditor、保存/恢复默认/禁用） |
| `t46-settings-prompt-placeholders.png` | 提示词占位符说明（推荐 27 / 本场景可用 / 旧版兼容三层 `{{KEY}}` chip 网格） |
| `t46-settings-vision.png` | 模型供应商页识图 section（configured=true / engine=true / 独立配置） |
| `t46-settings-restore.log` | 恢复默认阶段日志（temp=0.70 / thinking=false / history=15 / dia=300 / nar=300 / vision_mode=auto） |
| `t46-nsuserdefaults-restored.txt` | 恢复后 NSUserDefaults plist（默认值 + vision_* 复位，测试态已清理） |

---

## 6. 构建/环境说明（重要，供后续代理参考）

- **最终二进制与证据对应关系（已对齐）**：其它并行代理修复 Swift 树后，本卡于 12:31 重新链接 `shared` framework（含 T45/T49 新桥，BUILD SUCCESSFUL）；修复「识图模型名」被 custom 供应商占位模型 `自填` 覆盖的 UI bug 后，12:43 `xcodebuild` **BUILD SUCCEEDED**，重装模拟器并**在最终二进制上重新采集了 §3 全部运行证据与 6 张截图**（7 PASS + 3 PASS + 真实识图 E2E 再次通过 + 恢复默认）。此后如其他代理继续改 Swift，需再跑一次 xcodebuild 确认。



1. **iCloud「dataless」文件坑（本次实测，2026-11-23，差点阻断构建）**：工作区位于 `~/Documents` 且文件带 `compressed,dataless` 标志
   （`ls -laO` / `xattr`），构建期间出现 gradle 随机失败：
   `Could not load properties for module 'gradle-daemon-server' ... EOFException`、
   JVM 卡在 `ZipFile.findEND / RandomAccessFile.readFullyAt` 数分钟。
   原因：云端同步把工具链/缓存文件驱逐为 dataless placeholder，JVM 读到「正在物化」的截断文件。
   处置：读取（`cat`）相关文件强制物化后恢复；`df` 从 17Gi→50Gi free 正是同步在腾空间。
   建议后续代理：构建前先 `find ios-port/toolchain RhodesLink -flags dataless` 检查，必要时先读一遍再构建。
2. **识图 Key 明文镜像**：见 §2.5，属于待 T45 后收口的已知差异（Keychain 为权威副本；镜像仅为兼容 commonMain 解析器）。
3. **回复长度上限语义**：`dia_max`/`nar_max` 是「单段字数上限」，不是整轮 `max_tokens`；见 spec §6。
4. **模拟器 Keychain**：`CODE_SIGNING_ALLOWED=NO` 构建无 entitlements，Keychain 写失败时回退沙盒私密文件
   （T37 结论；证据里写入阶段显示 `backend=file`，只读状态显示 `stored`）。真机/正常签名构建走 Keychain。
5. **gradle 并发**：T45/T48 同批构建时，`--no-daemon` 之间会排队并加剧内存压力（16G 机器 swap 2.3G）；
   `pgrep` 检查 + 30s 排队仍是正确纪律；本次因 dataless 文件叠加导致多次误判为「构建卡死」。
6. **未跑**：`shared` 全量单测本卡未新增 shared 测试（T46 为 UI/桥接线；识图/提示词语义由 T18/T37/T42 已覆盖）。
   如需回归，可跑 `:shared:testDebugUnitTest`（T45/T48 并行时注意排队）。

## 7. 未支持 / 下一批

- 聊天参数页的「推荐预设（省钱/标准/完整）」一键批量调整（`context_mode`）、自动上下文/上下文 token 上限 UI；
- 提示词编辑器多标签「保存全部脏改动」、剪贴板、结构说明弹窗（Android 有、iOS 本卡未移植；存储契约不受影响）；
- 识图「连接测试」按钮（本卡用 mock E2E 证明真实路径；正式 UX 可在下一批复用 T37 ProviderConnectionTester 思路，发一条 1x1 图测试）；
- 提示词修改的「未保存离开确认」弹窗（当前保存按钮显式触发；避免误触）。
