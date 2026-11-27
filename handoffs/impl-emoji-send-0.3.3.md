# 实现报告：表情发送（v0.3.3）

| 项 | 内容 |
|---|---|
| 需求 | 线上模式可发送绝区零官方表情；表情**不需要识图**，对方只按文字语义理解 |
| 素材 | `zzz_emoji/绳网聊天表情`（19 个，46×46，带情绪命名）复制为 `ios/KnockKnock/Resources/Emoji/emoji_SMS-xx.png` |
| 结论 | 已实现并在模拟器用真实模型验证：发「生气」→ 角色回「深红底、皱眉，判定为不满」；发「开心」→ 角色回「上一个不满，这一个满意」 |

## 1. 核心设计：表情 = 文字语义，不是图片

表情从不进入识图管线，三点保证：

1. 目录里每个表情自带 `label`（情绪名，如「生气」）与 `description`（画面，如「深红底 + 皱眉」）；
2. 送模型的是纯文本 `用户发送了一个表情：生气（深红底 + 皱眉）`，无 base64、无图片 URL；
3. 落库保存 JSON 载荷 `{"emojiId","label","description"}`，仅用于回放渲染，**没有 visionSummary 字段**。

单测 `EmojiMessageContractTest` 断言：提示词不含 `base64`/`data:image`/`http`，载荷不含 `visionSummary`，历史格式化输出等价文本。

## 2. 代码落点

- `shared/.../domain/chat/EmojiCatalog.kt`（新）：19 个表情目录 + `promptText()`
- `shared/.../domain/chat/ReplyCodec.kt`：`EmojiMessageContract`（编码/解析/提示词）+ 历史格式化分支
- `shared/.../domain/chat/ChatEngine.kt`：`sendEmojiMessage()`（仅 online，无 `visionConfigured` 门槛）、`resumeEmojiReply()`；落库走 JSON 载荷、送模型走文字
- `shared/src/iosMain/.../ChatEngineBridge.kt`：`sendEmoji()`、`emojiCatalog()`、`imagePath()` 对 `emoji:` 前缀返回空
- `shared/src/iosMain/.../MessageHistoryBridge.kt`：`emoji` 类型解析（`text=[表情：心情]`、`imageId=emoji:SMS-xx`）
- `ios/KnockKnock/DesignSystem/EmojiImage.swift`（新）：按 bundle 路径加载 PNG（loose 资源最稳）
- `ios/KnockKnock/DesignSystem/GlassInputBar.swift`：线上模式才出现的表情按钮
- `ios/KnockKnock/Features/Chat/EmojiPickerSheet.swift`（新）：紧凑图标网格（48pt 一列、不显示文字），点选即发
- `ios/KnockKnock/Features/Chat/{ChatView,ChatViewModel,ChatModels}.swift`：`.emoji` 显示类型、裸图气泡（不套蓝色底）、`sendEmoji()`
- `ios/KnockKnock/DesignSystem/MessageBubble.swift`：表情分支只画图

## 3. 验证

| 项 | 结果 |
|---|---|
| 全量单测 | **743 tests / 8 failed**（8 条均为基线 `KtorDarwinStreamingTest`） |
| 模拟器真实模型 | 表情被正确理解（生气→不满、开心→满意，并能对比前后两个表情） |
| 渲染 | 表情以裸图标显示，不带气泡底色；选择面板为紧凑图标网格 |

## 4. 限制

- 仅线上模式；线下/导演模式按钮不出现，引擎侧也会 Toast 拒绝。
- 表情只覆盖「敲敲」19 个（角色立绘表情包 60 个尚未接入，见下一步建议）。
- 旧测试数据里有一条把提示词文本存成正文的历史行，会显示为纯文本（新数据不受影响）。
