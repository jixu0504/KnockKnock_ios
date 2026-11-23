# settings-pages-spec.md — iOS 设置页完善（T46：聊天表现 / 提示词编辑器 / 识图模型）

> 规格版本：v1（2026-11-23）｜实现：T46 / task-46｜分支：`RhodesLink@ios-port`（Kotlin 2.3.21 / SwiftUI iOS 18+）
> 目标阶段：Phase 5 P1 —— 让 iOS 设置页与 Android 1.14.27 的「聊天参数 / 提示词编辑器 / 识图模型」对齐到可用。

---

## 1. 范围

| 项 | 内容 |
|---|---|
| 聊天表现页 | `iosApp/RhodesLink/Features/Settings/ChatPerformanceSettingsView.swift`（新） |
| 提示词编辑器页 | `iosApp/RhodesLink/Features/Settings/PromptEditorView.swift`（新，列表 + 详情/编辑） |
| 识图模型配置 | `iosApp/RhodesLink/Features/Settings/ProviderSettingsView.swift` 新增「识图模型（图片聊天）」section |
| 设置桥扩展 | `shared/src/iosMain/.../iosbridge/SettingsBridge.kt`（**仅新增方法/DTO，不改已有签名**） |
| Swift 包装 | `iosApp/RhodesLink/Bridge/AppGraphClient.swift`（追加 extension，Kotlin 类型不外露） |
| 路由 | `AppRouter.swift` / `RouteView.swift` / `SettingsView.swift`：新增 `.settingsChatPerformance` / `.settingsPromptEditor` |
| 自动化 | `iosApp/RhodesLink/Features/Settings/SettingsAutomation.swift`（`--settings-selftest` / `-verify` / `-restore`） |
| 证据 | `ios-port/handoffs/evidence/t46-*`、`ios-port/handoffs/t46-mock-vision.py` |

不在本卡范围：
- 聊天参数页的「推荐预设（省钱/标准/完整）」「自动上下文/上下文长度上限」（Android `context_mode` 一键批量调整）——本卡只做明确要求的 4 项 + 回复长度上限（含旁白），其余下一批；
- 提示词编辑器的「多标签页一次性保存全部脏改动 / 剪贴板 / 结构说明弹窗」——本卡为单条详情保存（契约存储键完全一致）；
- 动态/日记/派遣 surface 的生成接线（模板可编辑，管线已在 T18）；
- 语音/向量/文生图供应商配置（各卡独立）。

### 1.1 与 Android oracle 的页面映射

| iOS | Android oracle | 说明 |
|---|---|---|
| 聊天表现页 | `app/src/main/java/com/rhodes/privatechat/ui/chatsettings/ChatSettingsScreen.kt`（通用 Tab + 私聊/群聊 Tab） | 只取通用 Tab 中用户要求的四项 + `dia_max`/`nar_max` |
| 提示词编辑器 | `app/src/main/java/com/rhodes/privatechat/ui/prompt/PromptEditorScreen.kt` + `PromptTemplates.kt` / `PromptModuleDefaults.kt` / `PromptPlaceholderRegistry.kt` | 目录/键/保存-恢复默认语义一致；UI 为分类列表 + 单条详情 |
| 识图模型 | `app/src/main/java/com/rhodes/privatechat/ui/model/ModelSettingsScreen.kt:88-250`（vision 字段）+ `shared/.../modelgateway/VisionConfiguration.kt` | 字段与存储键一致；API Key 在 iOS 走 Keychain |

---

## 2. 设置键（全部为 SettingsRepository 现有键，无新增 schema）

### 2.1 聊天表现

| 设置键 | SettingsRepository 属性 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `ai_temperature` | `aiTemperature` | 0.70 | 0.00–2.00（Int ×100 存储） | Android「创意程度」同一键 |
| `deepseek_thinking_enabled` | `deepseekThinkingEnabled` | false | bool | DeepSeek 深度思考；整轮预算 +60s（T37） |
| `history_messages` | `historyMessages` | 15 | 0–200 | 私聊/群聊共用历史回看轮数 |
| `dia_max` | `diaMax` | 300 | dia_min–2000 | **台词单段最多字数**（回复长度上限） |
| `nar_max` | `narMax` | 300 | nar_min–2000 | 旁白单段最多字数（线下/导演模式） |

> 说明：`SettingsRepository` 没有单一「整轮回复长度上限」键；Android 聊天参数页用 `dia_max`/`nar_max` 控制单段回复长度，本卡沿用该 oracle 键（见 §6 开放问题）。

### 2.2 提示词（T18 契约，键名固定）

| 内容 | 键 | 保存语义 | 恢复默认 |
|---|---|---|---|
| 主模板 | `prompt_<type>`（mode 为空）或 `prompt_<type>_<mode>` | 写值 + `<key>_custom=true` + `<key>_version=39` | 删三键 |
| 模块 | `prompt_<module>_<type>` 或 `prompt_<module>_<type>_<mode>`（module=behavior/protocol/runtime） | 同上 | 删三键 |

- `type`：`private` / `group` / `moment` / `moment_comment` / `diary` / `dispatch`；
- `mode`：私聊 `online/offline/director/proactive`；群聊 `online/offline/director/auto`；派遣 `start/progress/ending`；
- 解析语义（T18 `PromptEditor.getPromptTemplate`）：`_custom=true` → 用已存值（**空串 = 有意禁用，不回落默认**）；否则 `_version` 低于出厂 `VERSION=39` → 用出厂默认；否则用已存值；
- 语法校验：孤立 `}}` / 未闭合 `{{` / 空名 / body 含 `{ } \r \n` → **阻断保存**（`PromptSyntaxValidator`）；未知占位符只警告、原样发送；
- 备份：`prompt_*` 自定义覆盖不进备份（Android 现状，见 audit）。

### 2.3 识图模型（`effectiveVisionConfiguration` 对应键）

| 设置键 | 属性 | 默认 | 说明 |
|---|---|---|---|
| `vision_mode` | `visionMode` | auto（有历史 vision_* 键时为 independent） | `auto` = 复用聊天供应商；`independent` = 独立识图 |
| `vision_provider` | `visionProvider` | `ali` | 识图供应商 id |
| `vision_base_url` | `visionBaseUrl` | dashscope 多模态端点 | OpenAI 兼容 chat/completions 或 Qwen-VL 原生端点 |
| `vision_model_name` | `visionModelName` | `qwen3-vl-plus` | 识图模型名 |
| `vision_api_key` | `visionApiKey` | 空 | 空则独立模式下复用聊天 Key（`visionKey.ifBlank { chatKey }`） |

- 保存后 `SettingsRepository.effectiveVisionConfiguration().configured = endpoint/apiKey/model 均非空`；
- 键与 Android `ModelSettingsScreen` 完全一致，`VisionGatewayFactory` 按 provider 选择 OpenAI 兼容 / Qwen-VL / Anthropic 网关（T42 已接线 `ChatEngineBridge.analyzeVision`）。

---

## 3. 桥 API（SettingsBridge 新增，Swift 侧经 AppGraphClient + DTO 包装）

| 方法 | 返回 | 说明 |
|---|---|---|
| `chatPerformance()` | `ChatPerformanceDto` | 读 5 个聊天表现键 + contextMode/diaMin/narMin 诊断 |
| `saveChatPerformance(temperature, deepseekThinking, historyMessages, dialogueMaxChars, narrationMaxChars)` | `String`（空=成功） | 保存即写；范围 coerce 由 SettingsRepository 属性 setter 兜底 |
| `promptCatalog()` | `List<PromptEntryDto>` | 38 项（14 主模板 + 24 模块），轻量（不含正文） |
| `promptItem(type,mode,module)` | `PromptItemDto` | 当前生效文本 / 默认文本 / 自定义 / 禁用 / 版本 / 可用占位符（推荐/可用/旧版兼容） |
| `savePromptItem(type,mode,module,text)` | `PromptSaveResultDto` | 先语法校验；通过后写三键；返回 warnings |
| `resetPromptItem(type,mode,module)` | Unit | 删三键（恢复默认） |
| `visionStatus()` | `VisionStatusDto` | `configured` = commonMain `effectiveVisionConfiguration().configured`；`engineConfigured` 含 Keychain 兜底；backend 诊断 |
| `saveVisionConfiguration(provider,endpoint,model,apiKey)` | `String`（空=成功） | 写 5 个 vision_* 键；Key 写 Keychain（account=`vision_api_key_<provider>`） |
| `clearVisionConfiguration()` | `Boolean` | vision_mode=auto + 删 Keychain 识图密钥 |
| `flushDefaults()` | `Boolean` | NSUserDefaults 强制落盘（自检/取证用） |

---

## 4. 页面行为

### 4.1 聊天表现页

- 进入即从 shared 读取当前值（`chatPerformance()`），未设置时显示默认值；
- 温度 Slider（0–2，step 0.05，显示 2 位小数）、深度思考 Toggle、历史轮数 Slider（0–200）、台词/旁白单段字数 Slider（10–2000，step 5）；
- 「保存聊天表现」→ `saveChatPerformance` + 立即读回校验（一致才显示成功）；
- 保存即写 NSUserDefaults；下一轮聊天请求由 ChatEngine/ProviderConfigResolver/PrivatePromptAssembler 重新读取 settings（无需重启）。

### 4.2 提示词编辑器页

- 列表按 `私聊 / 群聊 / 内容生成` 分组，行显示名称 + 键名 + 状态徽标（默认/自定义/已禁用）；
- 详情：当前生效文本（默认文本来源 + 自定义覆盖）、`TextEditor` 编辑、字数、键名、真实 version；
- 操作：保存（语法错误阻断并显示错误；成功显示警告）、恢复默认（确认弹窗）、清空并保存（禁用，确认弹窗）；
- 占位符说明：推荐（出厂模板实际使用，橙色）/ 本场景可用（灰色）/ 旧版兼容（可能给默认值），均为 `{{KEY}}` chip 网格；未知占位符警告语义与 Android 一致。

### 4.3 识图模型 section（模型供应商页）

- 状态行：`configured` / `engineConfigured` / key 状态 / Keychain backend + 中文摘要；
- 字段：供应商 Picker（11 家）+ 识图端点 + 识图模型名 + API Key（SecureField，留空保持）；
- 保存：写 vision_* 键 + Keychain；成功后 `configured=true`，图片聊天走真实识图网关（T42 已接线）；
- 供应商切换：非自定义供应商自动带出默认端点/模型建议；`custom`（自定义端点）**不覆盖已填模型名**（`ProviderConfigs.kt:72` 的候选模型占位 `自填` 仅用于聊天页展示，不能当识图模型名）；
- 清空（回到自动）：`vision_mode=auto` + 删识图 Keychain key。

---

## 5. 验收（可自动判定）

| # | 验收项 | 判定方式 | 命令 / 证据 |
|---|---|---|---|
| 1 | 聊天参数默认值 → 改值 → 读回一致 | `--settings-selftest` 日志 `settings-check PASS chat-performance.save-readback` | `evidence/t46-settings-selftest-app.log` |
| 2 | 落盘（冷启动后仍存在） | 另起进程 `--settings-selftest-verify`（只读断言）；并 `plutil -p` 核对 `ai_temperature=123`/`history_messages=42`/… | `evidence/t46-nsuserdefaults-*.txt` |
| 3 | 提示词保存/恢复默认往返 | `--settings-selftest` 的 `prompt.save-readback` / `prompt.invalid-syntax-blocked` / `prompt.reset-default` | 同上 |
| 4 | 模块空串禁用语义 | `prompt.module-empty-disable`（isCustom=true && isDisabled=true && text=""）+ 恢复默认后为默认 | 同上 |
| 5 | 识图配置保存后 `effectiveVisionConfiguration.configured=true` | `vision.save-effective-configured`；`plutil` 核对 vision_* 键 | 同上 |
| 6 | 图片聊天真实识图（非演示识图桩） | mock vision 服务（`t46-mock-vision.py`）+ `--image-send-demo`；DB 图片消息内容含 `T46-MOCK-VISION-OK`；mock `/stats` 有请求 | `evidence/t46-vision-db.txt` / `t46-vision-e2e-image-send.log` |
| 7 | 页面可进入 + 截图 | `--open-url rhodes://settings/chat-performance`、`rhodes://settings/prompts`（+`--settings-prompt-demo`）、`rhodes://settings/providers --settings-vision` 后 `simctl io screenshot` | `evidence/t46-*.png` |
| 8 | 恢复默认（清理测试态） | `--settings-selftest-restore`：temp=0.70/history=15/vision_mode=auto | `evidence/t46-settings-restore.log` |

---

## 6. 已知差异 / 风险 / 开放问题

1. **`vision_api_key` 明文镜像（iOS 差异白名单）**：`effectiveVisionConfiguration()` 只读 settings；实现该解析器与真实识图分支的 `ChatEngineBridge` 在本批次禁改（T45 并行）。为使保存后 `configured=true` 且图片聊天立即走真实网关，`SettingsBridge.saveVisionConfiguration` 把 Keychain 密钥**同时镜像**到 `vision_api_key`。Keychain 是权威副本（account `vision_api_key_<provider>`），镜像仅为兼容 commonMain 解析器；后续 T45 完成后可给 `VisionConfiguration` 加 Keychain provider 并移除镜像。
2. **「回复长度上限」键选择**：SettingsRepository 无单一整轮输出 token 上限键；按 Android 聊天参数页 oracle 使用 `dia_max`（台词单段）+ `nar_max`（旁白单段），语义是「单段字数上限」而非 `max_tokens`。若 lead 要求硬 token 上限，需要新增设置键并改引擎预算（跨卡）。
3. **提示词模块的枚举完整性**：本卡覆盖 Android 编辑器的 role/behavior/protocol/runtime 全部模块与 14 个主模板；多标签页「保存全部脏改动」/剪贴板/结构说明弹窗未移植（不影响存储契约与生效）。
4. **模拟器 Keychain**：`CODE_SIGNING_ALLOWED=NO` 构建无 entitlements，Keychain 写返回 -34018 时 `ProviderKeyStore` 回退沙盒私密文件（T37 结论）；证据里 `keyStoreBackend` 会显示实际后端。
5. **T45 并行**：本卡不改 `ChatEngineBridge.kt` / `IosAppGraph.kt`；framework 构建需等 T45 的 gradle 结束（排队）。

## 7. 评审结论

- reviewer：待评审（本卡 lead 直接派发，P1 设置页）
- lead：待批准
