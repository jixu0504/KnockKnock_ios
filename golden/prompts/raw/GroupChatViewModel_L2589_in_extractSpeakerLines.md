<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2589-2594
  Decl  : in_extractSpeakerLines
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
).matchEntire(line)
            if (narrationMatch != null) {
                results += GroupMsgResult("旁白", narrationMatch.groupValues[1].trim(), "narration")
                return@forEach
            }
            val dialogueLine = line.replaceFirst(Regex(
