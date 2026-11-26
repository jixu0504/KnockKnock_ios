<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2PromptTemplates.kt:136-164
  Decl  : SCHEMA
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

统一字段结构：
[
  {
    "type": "emotion_state | behavior_state | physiological_state | event | agreement_commitment | intent_wish | preference_expression | evaluation_opinion | self_cognition_statement | external_knowledge | care_reminder",
    "content": "一句完整、具体、可复用的记忆，12到80字，不含系统词",
    "nickname": "用户昵称；不确定则填系统提供的当前昵称",
    "importance": 0,
    "privacy": "public | private | shared",
    "unmet_need": false,
    "location": "地点或空字符串",
    "emotion_valence": "positive | neutral | negative | mixed",
    "event_time": "原始事件时间或空字符串",
    "scheduled_time": "未来约定/提醒时间或空字符串",
    "action": "需要后续执行的动作或空字符串",
    "care_type": "comfort | remind | celebrate | accompany | none",
    "topic": "2到6字主题标签；不确定填空字符串",
    "subject": "user | character | relationship | world | other",
    "predicate": "prefer | dislike | reside | work | allergy | promise | plan | own | study | birthday | other",
    "object": "事实对象，尽量短，去掉“吃/喝”等动词",
    "polarity": "positive | negative | unknown",
    "qualifiers": ["程度/条件/范围词，如 周末、最近、暂时；没有则空数组"]
  }
]

字段规则：
- type 必须从上面枚举选择，不得输出 relationship_statement。
- importance 必须是 0 到 100 的整数：重大禁忌/承诺/安全需求 80-100，明确偏好/计划 50-79，普通事件 20-49，弱信息 1-19。
- privacy：公开群聊/动态事实用 public；私聊、负面情绪、个人隐私、亲密内容用 private；可被关系网自然听说但不敏感的信息用 shared。
- unmet_need 只在用户明确需要安慰、陪伴、提醒、帮助且尚未满足时为 true。
- content 禁止出现“系统记录”“记忆”“锚点”“摘要”“好感度”“affection”等机制词。
- content 必须具体到谁、什么事、什么偏好或什么约定；不要写“聊得很开心”这种空泛句。
- JSON 字段类型必须正确：importance 是数字，unmet_need 是布尔值，qualifiers 是字符串数组，其他字段是字符串。
- 偏好、禁忌、事实、计划、承诺类记忆必须尽量填写 subject / predicate / object / polarity；无法结构化时四者填空字符串/unknown。
- 否定（不/没/未/别/无）必须 polarity=negative；肯定 positive；无法判断 unknown。
- predicate 优先使用枚举值；object 只写对象本身，不要带主语或“吃/喝”等动词。
- topic 只用于展示与弱分组，不要求逐字稳定。
- L2/L3 合并时额外输出 evidence_ids：整数数组，必须只引用本次输入 JSON 中的 id。L1 提取时不要输出该字段。
