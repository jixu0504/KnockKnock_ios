<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2432-2460
  Decl  : in_classifyGroupError
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
, RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()) -> "无法连接 AI 服务，请检查网络、接口地址或服务状态"
        else -> "发送失败：${e.message?.take(50) ?: "未知错误"}"
    }

    private fun extractGroupResults(raw: String): List<GroupMsgResult> {
        try {
            val cleaned = sharedUtils.aiService.cleanJson(raw)
            val arr = json.decodeFromString<List<GroupMsgResult>>(cleaned)
            if (arr.isNotEmpty()) return arr
        } catch (_: Exception) {}

        val recovered = JsonBlockExtractor.extract(raw).flatMap { block ->
            runCatching { json.decodeFromString<List<GroupMsgResult>>(block) }.getOrNull()
                ?: runCatching { listOf(json.decodeFromString<GroupMsgResult>(block)) }.getOrNull()
                ?: runCatching {
                    val root = json.parseToJsonElement(block).jsonObject
                    val items = (root["messages"] as? kotlinx.serialization.json.JsonArray)
                        ?: (root["segments"] as? kotlinx.serialization.json.JsonArray)
                        ?: return@runCatching emptyList()
                    items.mapNotNull { item -> runCatching { json.decodeFromString<GroupMsgResult>(item.toString()) }.getOrNull() }
                }.getOrNull()
                ?: emptyList()
        }.filter { it.message.isNotBlank() }
        if (recovered.isNotEmpty()) return recovered

        return emptyList()
    }

    private fun untrustedGroupRepairInput(raw: String): String = 
