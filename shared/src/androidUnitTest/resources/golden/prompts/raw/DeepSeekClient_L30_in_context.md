<!--
  Source: app/src/main/java/com/rhodes/privatechat/galgame_full/DeepSeekClient.kt:30-72
  Decl  : in_context
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

请求类型：StoryTurn
模板版本：story-turn-v2
【项目资料】
作品名称：${state.project.title}
作品简介：${state.project.description}
创作风格：${state.project.style.ifBlank { "未特别设定" }}
创作禁区：${state.project.restrictions.ifBlank { "无" }}
【当前章节】
章节编号：${state.chapter}
章节标题：${chapter?.title.orEmpty()}
章节目标：${state.goal.ifBlank { chapter?.goal.orEmpty() }}
章节内容：${chapter?.contentDescription.orEmpty()}
章节完成提示：${chapter?.completionHint.orEmpty()}
章节氛围：${chapter?.atmosphere?.ifBlank { "未特别设定" } ?: "未特别设定"}
本章注意事项：${chapter?.notes?.ifBlank { "无" } ?: "无"}
不可提前揭露内容：${chapter?.spoilers?.ifBlank { "无" } ?: "无"}
最终章：${chapter?.isFinal == true}
允许角色：${chapter?.allowedCharacterIds?.joinToString(",").orEmpty()}
允许背景：${chapter?.allowedBackgroundIds?.joinToString(",").orEmpty()}
【玩家角色】
${player?.let { "${it.id}|${it.name}|性格=${it.personality}|目标=${it.goal}|说话方式=${it.speechStyle}|与玩家关系=${it.relationshipToPlayer.ifBlank { "未特别设定" }}|秘密=${it.secret.ifBlank { "无" }}|禁区=${it.taboos.ifBlank { "无" }}" } ?: "暂无玩家角色"}
【NPC角色】
$characters
【立绘资料】
$sprites
【背景资料】
${backgrounds.joinToString("\n") { "${it.id}|${it.name}|条件=${it.usageCondition.ifBlank { "默认" }}" }.ifBlank { "本章未限制背景，可保持当前背景。" }}
【当前状态】
当前变量：${state.project.variables.joinToString("；") { "${it.id}=${state.variables[it.id] ?: it.initial}" }}
已拥有道具：${state.items.sorted().joinToString(",").ifBlank { "暂无" }}
已发生事件：${state.events.sorted().joinToString(",").ifBlank { "暂无" }}
本章需要完成事件：${chapter?.requiredFlag?.ifBlank { "无" } ?: "无"}
当前背景：${state.scene.backgroundId}
当前角色：${state.scene.visibleCharacterId.orEmpty()}
当前立绘：${state.scene.visibleSpriteId.orEmpty()}
【长期剧情记忆】
${state.storyMemory.ifBlank { "暂无已整理的前情。" }}
【最近对话】
${state.messages.takeLast(12).joinToString("\n") { "${it.speaker}：${it.text}" }.ifBlank { "暂无最近对话。" }}
【本轮玩家输入】
$input
