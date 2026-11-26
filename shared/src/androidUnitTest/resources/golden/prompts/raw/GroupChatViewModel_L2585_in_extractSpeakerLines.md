<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2585-2589
  Decl  : in_extractSpeakerLines
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
)
        val results = mutableListOf<GroupMsgResult>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("-").removePrefix("*").trim()
            val narrationMatch = Regex(
