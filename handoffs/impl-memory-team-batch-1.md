# 实现报告：智能体团队并行批次 1

| 项 | 内容 |
|---|---|
| 目标 | 用互不重叠写域并行推进 T71、T86/T87 UI、T68 回放扩编，缩短墙钟 |
| 团队 | Lead（集成/迁移/验证）+ `blob` + `settings-ui` + `eval-set` 三名子代理 |
| 结果 | 三路全部完成并通过集成；全量 **659 tests / 8 failed**（8 条为基线网络测试）；iOS 构建通过 |

## 1. 工作流与改动

### 1.1 T71 向量 BLOB（agent `blob`，task-1）

- `VectorMemories.sq` 表尾新增 `embeddingBlob BLOB` / `embeddingDimension` / `embeddingDtype`；
  新增 16 参 `insertVectorMemoryWithBlob`，**保留**旧 13 参 `insertVectorMemory` 供旧 JSON/播种/恢复路径；
- `26.sqm`：`ALTER TABLE ... ADD COLUMN`（列序与 CREATE 一致）；
- 新 `VectorEmbeddingCodec`：float32 小端编解码、`isUsable`、`encodedSize`；
- `LocalVectorStoreGateway`：写入 BLOB 并清空 `embeddingJson`；读取优先 BLOB，空则回退旧 JSON；
  upsert 校验向量可用性；
- 测试 12 条（编解码 7、真实 SQLite 5）；1536 维 BLOB 6144B，约为 JSON 文本一半以下。

### 1.2 记忆设置与体检 UI（agent `settings-ui`，task-2）

- `SettingsView.swift` 新增「记忆」分区：总开关、跨角色关系回忆开关（含风险说明）、体检入口、宽限期清理说明；
- `AppGraphClient.swift` 补 `private_recall_relationship_memory` 读写包装（未落盘按 shared 默认 true）；
- `MemoryListView.swift` 体检面板完整展示 `a7MemoryHealthLine()`（含整合失败）、新增刷新按钮，保留重建索引入口；
- 只改已有 Swift 文件，未动 pbxproj；模拟器构建通过。

### 1.3 回放集 v1（agent `eval-set`，task-3）

- 数据集 10→14 组、31→41 题（新增状态变更、同名多角色、错误诱导、额外 abstention），tune/holdout 拆分保持；
- tune：hit 17/19 = **89.5%**、recall 0.81、precision 0.83、abstention 3/3、重复/矛盾注入 0；
- holdout：hit 13/16 = **81.3%**、recall 0.78、precision 0.63、abstention 3/3、重复/矛盾注入 0。

## 2. Lead 集成

- `DatabaseMigrationTest`：版本 26→**27**、33 表；升级路径补 `embeddingBlob` 列断言；
- 修复升级模拟：v18 形态需先 DROP 三个新列，否则迁移 26 的 ALTER 与已存在列冲突（与迁移 20 的既有做法一致）；
- 全量回归 + 框架链接 + `xcodebuild` 均通过。

## 3. 风险与遗留

1. **Android 兼容 DDL 未同步**：`DatabaseCompatibility.android.kt` / `android-oracle` 仍按旧列；本项目当前只构建 iOS，跨端前需补。
2. **旧客户端回读**：新行 `embeddingJson` 为空，v0.2.0 回读会得到空向量；不支持降级安装，需写在发布说明。
3. **float32 精度**：相对 JSON double 有精度损失，余弦排序基本不变；已用往返测试覆盖。
4. **T78 只到关系表**：`supports/contradicts` 已记录，但 `conflict_status`、双时间、待确认队列未做。
5. **T69 未做**：事实独立列/实体表仍未迁移；`topicKey` 仍承担事实键。
6. **T77 仍非独立 Worker**：调度仍在提取路径尾部；队列/checkpoint/租约未做。

## 4. 团队并行经验（节 token）

- 每路只给「写域 + 验收 + 构建命令 + 报告上限」，不让代理读整份计划书；
- 迁移文件编号与 `DatabaseMigrationTest` 由 Lead 独占，避免多处冲突；
- 子代理只跑目标测试，全量回归由 Lead 跑一次；
- 冲突当场裁决（如 BLOB insert 参数：保留旧函数 + 新增函数，避免改金样测试）。
