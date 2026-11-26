# RhodesLink iOS 移植 — Phase 1 执行索引（领域层抽取）

- **前置**：Phase 0 全部 Gate 通过（见 `PHASE0.md`），基线 tag `v1.14.27-android-baseline` @ 3642d2c，分支 `ios-port`
- **依据**：`RhodesLink-iOS开发计划书-v1.1-DSH执行版.md` §四 Phase 1；reviewer 终审 §6 抽取顺序建议
- **目标**：共享领域层抽取（60–75 代理日基准），Gate 1 = Android 原功能不回归 + 核心提示词/记忆测试全绿 + 共享层不依赖 Android Context/WorkManager/UI + A7 决策点完成

## 抽取波次（reviewer 建议顺序，lead 已采用）

| 波次 | 模块 | 规格卡 | 实现 | 状态 |
|---|---|---|---|---|
| 1a | 提示词管线 | `specs/prompt-template-spec.md` ✅批准 | T18 `06f7fde` 115 测试绿 | ✅ |
| 1b | 消息 ID + 媒体库 | `specs/message-id-media-spec.md` ✅批准 | T17 `df71a3b` 17 测试绿 | ✅ |
| 1c | 私聊轮次引擎 | `specs/private-chat-engine-spec.md` ✅批准 | T19 `368e29b` JVM217/iOS245 全绿 | ✅ |
| 2a | 群聊引擎 + DelayedTaskLayer | `specs/group-chat-engine-spec.md` ✅批准 | T23 `5ed811f` 151 测试绿 | ✅ |
| 2b | 记忆 V2 + 知识库 | `specs/memory-v2-spec.md` ✅批准 | T24 `7847bc5` JVM414/iOS442 全绿 | ✅ |
| 2c | A7 胶水（MomentReply/ContextMaintenance） | `specs/a7-delay-task-spec.md` ✅批准 | T25 `a9a3afd` 56 测试绿 | ✅ |
| 3 | **接线批 A**：app 叶子逻辑委托 shared | — | T26 `66dbeb9` 净删 2993 行 | ✅ |
| 4 | **接线批 B**：收口（单公式/诊断/死代码） | — | T27 `0fd2fb9` 全门禁绿 | ✅ |
| 5 | **接线批 C**：编排层委托（延后至 Phase 2/4） | — | 待批 C | ⏳ |

## 关键决策记录（lead）

| # | 决策 | 日期 |
|---|---|---|
| 1 | Kotlin 升级 2.3.21（授权最小 app 修复：ProblemChecker `<Any>` + GroupChatViewModel lambda 拆分） | 11-23 |
| 2 | 测试口径以实测 376 为准（39 文件；「324」为旧口径作废） | 11-23 |
| 3 | prompt_* 自定义不进备份=照搬 Android 现状 | 11-23 |
| 4 | 内置头像=Bundle 资源按数字解析，不落 media 目录；落点=Application Support/media | 11-23 |
| 5 | 金样必须由 v1.14.27-android-baseline tag 的 Android oracle 代码生成（禁止自证）；基线金样只读，行号漂移从 tag 重抽校准 | 11-23 |
| 6 | DelayedTaskLayer 由群聊波次（T23）先落地，A7 波次接胶水；契约以 T22 spec §A-12 冻结 | 11-23 |
| 7 | 后台恢复=启动 reconcile + 前台补算；领域层不依赖 BGTask/WorkManager | 11-23 |
| 8 | 打字机效果属 UI 层，领域层不承诺 | 11-23 |
| 9 | 群聊状态机单独立卡（T20），不复用私聊卡正文 | 11-23 |

## 写域约定

- 每个实现代理只写任务板声明的目录；跨域只经 shared/domain 接口
- 共享构建：gradle 前 `pgrep -fl gradle` 排队、`--no-daemon`、工具链 `source ios-port/toolchain/env.sh`
- git 提交由 lead 统一执行（代理不提交）；每波实现后 lead 提交并跑合并回归
- 金样/验证件入 `ios-port/golden/`；handoff 入 `ios-port/handoffs/`

## Gate 1 验收清单（关闭时逐项勾选）

- [ ] Android 原功能不回归（`:app:testDebugUnitTest` 376 例 + `:app:assembleDebug`）
- [ ] 核心提示词/记忆测试全绿（commonTest 双路径 + 金样 diff 门禁）
- [ ] 共享层不依赖 Android Context/WorkManager/UI（commonMain 平台符号扫描）
- [ ] A7 决策点完成（拆分移植已裁决 + DelayedTaskLayer + 胶水落地）
- [ ] 接线批完成：app 侧 ChatViewModel/GroupChatViewModel/MainViewModel 域逻辑下沉，行为一致

---

# Phase 2 执行索引（iOS 基础设施与数据底座）

| 波次 | 交付物 | 实现 | 状态 |
|---|---|---|---|
| 2-1 | 批 C 专项：VM 测试替身 + 接线缝 + 诊断事件 | T28 `f14de16`（app 345/0；发现 D1/D2/D3 语义漂移，逐入口替换按设计暂停，需先建 ChatEngineFactory 适配层） | ✅ 部分 |
| 2-2 | IosAppGraph 窄门面 + framework + Swift 集成 Demo | T29 `d8767b4`（真机级模拟器实跑：真实 DB 落库/设置/备份/会话列表） | ✅ |
| 2-3 | A5 完整备份恢复 + 角色卡 + 聊天导入导出 | T30 `736fe86`（4 类金样往返、坏包无残留、2000 条 272ms/754ms；shared 504/532） | ✅ |
| 2-4 | A5 全类目字段级重放（T31） | — | ⏳ |
| 2-5 | iOS 平台服务：文件/剪贴板/通知/深链/日志导出（T32） | — | ⏳ |
| 2-6 | Phase 3 真壳：iosApp 工程 + 导航/路由/玻璃 token/聊天时间线（T33） | — | ⏳ |

## T28 遗留关键问题（批 C 续作）

- D1 取消语义：基线协程取消跳过 DB 写入 vs 引擎 send_failed+租约回 pending
- D2 撤回语义：基线 deleteMessage 连带删 reply_turns vs 引擎保留 cancelled 终止态
- D3 群聊全员禁言：基线回调 true vs 引擎需逐条对齐
- 续作顺序：ChatEngineFactory 适配层 → 事件映射冻结 → 逐入口替换（每步更新对应 VM 测试）
