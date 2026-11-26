# 内置头像清单（基线 v1.14.27-android-baseline @ 3642d2c）

## 映射规则

- 编号 `1..147` ↔ Android drawable 资源 `avatar_N.png`（全为 PNG，位于 `app/src/main/res/drawable/`）。
- 连续性核验：`avatar_1.png` … `avatar_147.png` **147 个齐全无缺号**（已逐号脚本核验）。
- 解析方式为**运行时动态编号**（非编译期资源表）：
  `app/src/main/java/com/rhodes/privatechat/ui/common/AvatarImage.kt:31-34`
  ```kotlin
  if (num != null && num in 1..147) {
      context.resources.getIdentifier("avatar_$num", "drawable", context.packageName)
  ```
  即 `avatarUri` 为纯数字字符串 `"N"`（1..147）时映射 `avatar_N`；
  非数字字符串走 `AsyncImage` 网络/文件加载（`AvatarImage.kt:46-52`）；
  空/越界回退首字符色块（`:53-64`，emoji 或名字首字）。
- 体积：avatar_1.png ≈ 36 KB，avatar_147.png ≈ 445 KB（PNG，随编号大小差异大；未做无损压缩盘点）。

## 引用位置（file:line）

| 位置 | 用途 |
|---|---|
| `app/src/main/java/com/rhodes/privatechat/ui/common/AvatarImage.kt:24-45` | `OperatorAvatarImage`：编号→drawable 解析与渲染（核心） |
| `app/src/main/java/com/rhodes/privatechat/ui/profile/ProfileSettingsScreen.kt:95` | 用户头像编号偏好 `user_avatar_index`（默认 0） |
| 同 :114 | 保存 `user_avatar_index` |
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/settings/SettingsRepository.kt:853-854` | `user_avatar_uri` 设置项存取（字符串，可存编号） |
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/model/Operator.kt:12` | 干员 `avatarUri` 字段（默认空串） |
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/model/ChatSession.kt:17` | 会话 `avatarUri` 字段 |
| `app/src/main/java/com/rhodes/privatechat/settings/SettingsMigration.kt:217` | 旧键 `avatar_uri` → `user_avatar_uri` 迁移 |
| `app/src/main/java/com/rhodes/privatechat/data/backup/PortableSettings.kt:9` | 备份白名单含 `user_avatar_uri` |
| `app/src/main/java/com/rhodes/privatechat/data/backup/BackupContentFilter.kt:52-55` | 导出时头像 URI 可移植化（operators/sessions/user） |
| `app/src/main/java/com/rhodes/privatechat/data/backup/BackupMediaCollector.kt:44-46` | 备份媒体收集（user_avatar_uri / operator.avatarUri / session.avatarUri） |
| `app/src/main/java/com/rhodes/privatechat/data/backup/BackupRestorePayloadRewriter.kt:23-27` | 恢复时 URI 重写 |

## 编号↔资源名映射（1..147）

```
1..147 → drawable/avatar_1.png … drawable/avatar_147.png（连续、无别名资源、无 density 变体）
```

- 无 `drawable-nodpi` 或 density 分桶中的头像副本（`drawable-nodpi/` 下无 avatar_*）。
- 无「资源名→编号」的反向映射表（代码只用编号字符串），iOS 移植需打包同名
  1..147 图片并按 `avatar_<N>` 解析（可行性报告 §1.3 已确认该结论）。

## 设备级补充（待模拟器就绪）

用户自选头像的实际 `user_avatar_uri` / 干员 `avatarUri` 分布需从真机 DB
（operators 表 + settings）统计，见 `handoffs/baseline-agent.md` §设备级金样采集建议。
