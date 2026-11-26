<!--
  Source: app/src/main/java/com/rhodes/privatechat/galgame_full/ProgressJudgeClient.kt:17-33
  Decl  : user
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

【章节】
标题：${chapter.title}
内容：${chapter.contentDescription}
目标：${chapter.goal}
完成提示：${chapter.completionHint}
注意事项：${chapter.notes}
必需事件：${chapter.requiredFlag.ifBlank { "无" }}
最低好感度：${chapter.minAffection}
【已确认状态】
变量：${state.variables}
道具：${state.items}
事件：${state.events}
当前好感度：${state.affection}
长期记忆：${state.storyMemory.ifBlank { "无" }}
最近对话：${state.messages.takeLast(12).joinToString("\n") { "${it.speaker}：${it.text}" }}
