# baseline-agent 工作记录与交接（T2）

- 执行代理：baseline-agent（lead 委派）
- 任务：team task-2（Phase 0 基线冻结，源码级金样归档）
- 完成日期：2026-11-22
- 基线：`v1.14.27-android-baseline` @ `3642d2cbcec704c6b2b6d5d34298f171a9f64965`
  （分支 ios-port，versionName 1.14.27 / versionCode 74，applicationId com.buzidongshouhuoji.privatechat）

## 1. 产出清单（全部在 ios-port/ 下，写域合规）

| 产物 | 位置 | 来源 |
|---|---|---|
| 基线描述 | `baseline/BASELINE.md` | git 元数据 + `git show tag:app/build.gradle.kts` |
| 版本目录快照 | `baseline/libs.versions.toml`（84 行） | `git show v1.14.27-android-baseline:gradle/libs.versions.toml` |
| 测试盘点 | `baseline/tests.md` | grep 统计（324 @Test / 39 文件；10 仪器 / 4 文件） |
| DB schema 金样 | `golden/db/schema/`（24 个 .sq，与 tag 逐字节 diff 一致） | `git show tag:.../*.sq` |
| 迁移清单 | `golden/db/migrations.md` | 23 个 .sqm 逐一核对 |
| 提示词盘点 | `golden/prompts/inventory.md` | 全仓库 grep + 精读（file:line 全表） |
| 提示词原文 | `golden/prompts/raw/`（114 件，约 588 KB） | `handoffs/extract_prompts.py` 从 tag 抽取三引号字面量 |
| 验证件 | `golden/sessions/`（6 个 *-verification-*.json） | docs/ 直接复制（1.14.16/18/19/20/21/22） |
| 头像清单 | `golden/media/avatars.md` | drawable 目录逐号核验 + 代码引用定位 |
| 本交接 | `handoffs/baseline-agent.md` + `handoffs/extract_prompts.py`（可复跑） | — |

## 2. 与预期数字的差异及原因

| 预期 | 实测 | 差异说明 |
|---|---|---|
| 28 表 | 28（.sq 中 CREATE TABLE 计数） | **一致**。但迁移 18 另建 `data_repair_runs` 表未进 .sq，运行时实际 29 张表；迁移 11 删除历史表 `world_events`。报告口径按 .sq 计数成立，已在 migrations.md/BASELINE.md 注明 |
| 23 个 .sqm | 23 | **一致**。其中 4/6/9.sqm 为 no-op（`SELECT 1;` / `SELECT 1 WHERE 0;`），疑似已撤销迁移占位 |
| 324 单测 | 324（app/src/test，39 文件） | **一致**。shared 模块 0 测试 |
| 10 仪器测试 | 10（app/src/androidTest，4 文件：3+5+1+1） | **一致** |
| 147 头像 | 147（avatar_1..147.png 连续无缺号，全在 res/drawable，nodpi 无 avatar） | **一致**。映射为运行时动态编号（AvatarImage.kt:31-34 `getIdentifier("avatar_$num")`），无静态映射表 |
| docs 验证件 | 6 个（1.14.16–1.14.22 各一个） | 范围 1.14.16–1.14.22 内**缺 1.14.17**：docs/ 中无 1.14.17 的 verify json（1.14.17 可能无验证件产出）。另有 1.14.23 newspaper-live-test 不在任务范围，未收 |

其他备注：
- `app/data/PromptTemplates.kt` 是 12 行空对象（历史遗留），真正模板库是
  `viewmodel/shared/PromptTemplates.kt`（VERSION=39）。inventory.md 已标注防踩坑。
- 提示词 raw 抽取为三引号字面量**近似**归档：缩进按 trimIndent 近似还原（首行接在
  `"""` 后时不缩进），`${...}` 占位符逐字保留；抽取件不构成可直接编译的 Kotlin，属
  「原文金样」用途，金样 diff 时建议比对 `git show tag:<file>` 原文。

## 3. 金样仓库使用说明（对应计划书）

计划书（`RhodesLink-iOS开发计划书-v1.1-DSH执行版.md`）要求 Phase 0 冻结入库的金样
（:152 提示词/备份包/会话；:216 完整会话、三层记忆+知识库召回会话、关系数据集、
内容生态数据集、供应商设置集、真实备份+媒体）。本目录映射：

| 计划书金样 | 目录 | 状态 |
|---|---|---|
| 提示词金样 | `golden/prompts/`（inventory.md + raw/） | ✅ 源码级完成 |
| 会话金样 | `golden/sessions/`（1.14.16–1.14.22 verify jsons） | ✅ 现有验证件归档 |
| 数据库结构金样 | `golden/db/`（schema/ + migrations.md） | ✅ 完成 |
| 媒体金样（内置头像部分） | `golden/media/avatars.md` | ✅ 映射完成；图片本体在 Android 仓库 res/drawable |
| 完整会话/关系数据集/内容生态/供应商设置集 | 待定（建议 `golden/sessions/` 与 `golden/db/` 扩展或新建 `golden/datasets/`） | ⏳ 需 Android 模拟器 |
| 真实 Android 完整备份 + 媒体 | 建议 `golden/device/`（新建） | ⏳ 设备级，见下节 |
| Android APK 基线 | `baseline/`（构建状态待 T1） | ⏳ T1 产出后归档 |

## 4. 设备级金样采集步骤建议（待模拟器就绪后）

前置：T1 打通 Android 构建产出 APK（或直接用 1.14.27 现网包），模拟器
（Android SDK 35 / API 26+）就绪。建议步骤：

1. **数据库文件**：安装 APK → 完成首次启动 + 初始化干员 → 通过备份/导出功能或
   `adb root` / `run-as com.buzidongshouhuoji.privatechat` 拉取
   `databases/*.db`，归档到 `golden/device/db/`；记录 `PRAGMA user_version`
   （应=23）并与 `golden/db/migrations.md` 交叉核对实际表数（预期 29 含 data_repair_runs）。
2. **完整备份包**：在 App 内走完整备份导出（含媒体），归档 zip 到 `golden/device/backup/`；
   同时记录 settings 中的供应商配置集（去密钥）与 11 供应商设置集，形成
   `golden/device/settings-set.json`。
3. **会话金样**：构造计划书 :216 指定会话（私聊+群聊+引用+撤回+重试+图片、
   三层记忆+知识库召回会话、20+ 关系/等级/印象数据集、内容生态数据集），导出会话
   JSON 或 DB 快照至 `golden/device/sessions/`；每会话附截图基线。
4. **媒体样本**：内置头像图片本体从 `RhodesLink/app/src/main/res/drawable/avatar_*.png`
   复制到 `golden/media/assets/`（147 个，约数十 MB，评估后决定是否入库或仅清单+哈希）；
   用户媒体样本（file:// URI → media/<uuid>）随备份包归档。
5. **提示词运行时快照**：对每种 (type, mode) 执行 `getPromptTemplate` 渲染并落盘
   `golden/device/rendered-prompts/`，作为 iOS 金样 diff 的「渲染后」基准
   （raw/ 是「源码级」基准，两者配合使用）。
6. 每步产出附 `sha256` 清单文件。

## 5. 并发与口径注意

- 归档期间 p0-toolchain 正在改 gradle/ 与 shared/build.gradle.kts；本代理所有归档
  一律用 `git show v1.14.27-android-baseline:<path>` 取基线版本（.sq、libs.versions.toml、
  提示词 raw 均如此），只有 docs/*.json 从工作树复制（不在并行写域）。
- 提示词 raw 抽取脚本 `handoffs/extract_prompts.py` 依赖工作区内 RhodesLink git，
  可随时复跑验证；114 件清单见 inventory.md §3。
