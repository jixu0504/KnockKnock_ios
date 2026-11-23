<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2522-2530
  Decl  : in_extractGroupPlotSummary
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
)
        val found = tag.find(raw) ?: return ""
        val next = Regex(GroupTaggedReplyParser.NEXT_TAG_PATTERN).find(raw, found.range.last + 1)
        return raw.substring(found.range.last + 1, next?.range?.first ?: raw.length).trim().take(220)
    }

    private fun parseGroupTurnState(raw: String): GroupTurnState? {
        fun field(name: String, maxLength: Int): String {
            val tag = Regex(
