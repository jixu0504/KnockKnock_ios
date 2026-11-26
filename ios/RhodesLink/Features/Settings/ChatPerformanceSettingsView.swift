//
//  ChatPerformanceSettingsView.swift
//  T46「聊天表现」设置页（对齐 Android oracle `app/ui/chatsettings/ChatSettingsScreen.kt`），
//  P4 敲敲视觉改造：滑杆 / 双滑杆 / 自绘胶囊开关 / 分组面板全部换 Knock 控件。
//
//  - 温度（ai_temperature，0.00–2.00，默认 0.70，Android 页「创意程度」整数 ×100 存储）；
//  - 深度思考开关（deepseek_thinking_enabled，默认 false，仅 DeepSeek 类供应商使用）；
//  - 历史回看轮数（history_messages，0–200，默认 15，私聊/群聊共用）；
//  - 回复长度上限：台词/旁白的段数范围与单段字数；群聊每位成员发言段数 / 每条字数 / 旁白。
//
//  全部经 Shared.SettingsBridge 读写真实 SettingsRepository（NSUserDefaults）；
//  保存即写，下一轮聊天请求重新读取 settings 后生效（无需重启）。
//
//  验证钩子：`--settings-selftest`（见 SettingsAutomation.swift，逻辑与文案零改动）。
//

import SwiftUI

struct ChatPerformanceSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var temperature: Double = 0.70
    @State private var deepseekThinking = false
    @State private var historyMessages: Int = 15
    @State private var dialogueMinChars: Int = 10
    @State private var dialogueMaxChars: Int = 300
    @State private var narrationMinChars: Int = 50
    @State private var narrationMaxChars: Int = 300
    @State private var dialogueSegMin: Int = 1
    @State private var dialogueSegMax: Int = 3
    @State private var narrationSegMin: Int = 1
    @State private var narrationSegMax: Int = 3
    @State private var groupMsgMin: Int = 10
    @State private var groupMsgMax: Int = 100
    @State private var groupSpeechMin: Int = 1
    @State private var groupSpeechMax: Int = 2
    @State private var groupNarSegMin: Int = 1
    @State private var groupNarSegMax: Int = 1
    @State private var groupNarMin: Int = 20
    @State private var groupNarMax: Int = 100

    @State private var loaded = false
    @State private var saveMessage = ""
    @State private var saveOK = false
    @State private var scrollOffset: CGFloat = 0

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "聊天表现", showsBack: true, onBack: { dismiss() })

                ScrollView {
                    VStack(spacing: KnockTokens.spacingXL) {
                        statusGroup
                        generationGroup
                        contextGroup
                        replyLengthGroups
                        groupBudgetGroup
                        saveGroup
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingM)
                    .padding(.bottom, KnockTokens.spacingXXL)
                }
                .scrollIndicators(.hidden)
                .settingsKnockScrollEdgeHidden()
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    geometry.contentOffset.y
                } action: { _, newValue in
                    scrollOffset = newValue
                }
            }
        }
        .navigationTitle("聊天表现")
        .settingsKnockNavigationChrome()
        .task { reload() }
    }

    // MARK: - 数据（逻辑零改动）

    private func reload() {
        let dto = environment.bridge.chatPerformance()
        temperature = dto.temperature
        deepseekThinking = dto.deepseekThinking
        historyMessages = dto.historyMessages
        dialogueMinChars = dto.dialogueMinChars
        dialogueMaxChars = dto.dialogueMaxChars
        narrationMinChars = dto.narrationMinChars
        narrationMaxChars = dto.narrationMaxChars
        dialogueSegMin = dto.dialogueSegMin
        dialogueSegMax = dto.dialogueSegMax
        narrationSegMin = dto.narrationSegMin
        narrationSegMax = dto.narrationSegMax
        groupMsgMin = dto.groupMsgMin
        groupMsgMax = dto.groupMsgMax
        groupSpeechMin = dto.groupSpeechMin
        groupSpeechMax = dto.groupSpeechMax
        groupNarSegMin = dto.groupNarSegMin
        groupNarSegMax = dto.groupNarSegMax
        groupNarMin = dto.groupNarMin
        groupNarMax = dto.groupNarMax
        loaded = true
        SelfTestLog.add("chat-performance load temp=\(dto.temperature) thinking=\(dto.deepseekThinking) history=\(dto.historyMessages) dia=\(dto.dialogueMinChars)-\(dto.dialogueMaxChars) nar=\(dto.narrationMinChars)-\(dto.narrationMaxChars) diaSeg=\(dto.dialogueSegMin)-\(dto.dialogueSegMax) narSeg=\(dto.narrationSegMin)-\(dto.narrationSegMax)")
    }

    private func save() {
        let result = environment.bridge.saveChatPerformance(
            temperature: temperature,
            deepseekThinking: deepseekThinking,
            historyMessages: historyMessages,
            dialogueMaxChars: dialogueMaxChars,
            narrationMaxChars: narrationMaxChars,
            dialogueMinChars: dialogueMinChars,
            narrationMinChars: narrationMinChars,
            dialogueSegMin: dialogueSegMin,
            dialogueSegMax: dialogueSegMax,
            narrationSegMin: narrationSegMin,
            narrationSegMax: narrationSegMax,
            groupMsgMin: groupMsgMin,
            groupMsgMax: groupMsgMax,
            groupSpeechMin: groupSpeechMin,
            groupSpeechMax: groupSpeechMax,
            groupNarSegMin: groupNarSegMin,
            groupNarSegMax: groupNarSegMax,
            groupNarMin: groupNarMin,
            groupNarMax: groupNarMax
        )
        let readBack = environment.bridge.chatPerformance()
        let consistent = abs(readBack.temperature - temperature) < 0.001
            && readBack.deepseekThinking == deepseekThinking
            && readBack.historyMessages == historyMessages
            && readBack.dialogueMaxChars == dialogueMaxChars
            && readBack.narrationMaxChars == narrationMaxChars
            && readBack.dialogueMinChars == dialogueMinChars
            && readBack.narrationMinChars == narrationMinChars
            && readBack.dialogueSegMin == dialogueSegMin
            && readBack.dialogueSegMax == dialogueSegMax
            && readBack.narrationSegMin == narrationSegMin
            && readBack.narrationSegMax == narrationSegMax
            && readBack.groupMsgMin == groupMsgMin
            && readBack.groupMsgMax == groupMsgMax
            && readBack.groupSpeechMin == groupSpeechMin
            && readBack.groupSpeechMax == groupSpeechMax
            && readBack.groupNarSegMin == groupNarSegMin
            && readBack.groupNarSegMax == groupNarSegMax
            && readBack.groupNarMin == groupNarMin
            && readBack.groupNarMax == groupNarMax
        saveOK = result.isEmpty && consistent
        saveMessage = result.isEmpty
            ? (consistent ? "已保存（下一轮聊天生效）" : "保存后读回不一致")
            : result
        SelfTestLog.add("chat-performance save ok=\(saveOK) result='\(result)' readBack temp=\(readBack.temperature) thinking=\(readBack.deepseekThinking) history=\(readBack.historyMessages) diaMax=\(readBack.dialogueMaxChars) narMax=\(readBack.narrationMaxChars)")
    }

    // MARK: - 状态

    private var statusGroup: some View {
        SettingsKnockGroup("聊天表现参数", systemImage: "slider.horizontal.3") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockRowGlyph(systemImage: "slider.horizontal.3", tint: KnockTokens.electric, size: 36)
                        VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                            KnockText("保存即写入 NSUserDefaults", role: .rowTitle, lineLimit: 2)
                            KnockText(
                                "下一轮发送消息时由提示词 / 供应商配置重新读取，无需重启。",
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineSpacing: 2
                            )
                        }
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockBadge(text: loaded ? "已读取" : "读取中", variant: loaded ? .neutral : .accent)
                    }

                    if !saveMessage.isEmpty {
                        SettingsKnockStatusBanner(
                            kind: saveOK ? .success : .failure,
                            title: saveMessage
                        )
                    }
                }
            }
        }
    }

    // MARK: - 生成风格

    private var generationGroup: some View {
        SettingsKnockGroup("生成风格", systemImage: "sparkles") {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                    SettingsKnockSliderRow(
                        title: "温度（创意程度）",
                        valueText: String(format: "%.2f", temperature),
                        value: $temperature,
                        range: 0...2,
                        step: 0.05,
                        hint: "键 ai_temperature（默认 0.70）。建议 0.60–0.90：太低说话死板重复，太高容易跑偏。"
                    )

                    KnockDivider()

                    SettingsKnockToggleRow(
                        title: "深度思考",
                        detail: "DeepSeek 思考模式更慢更贵；推理内容不会展示或保存。",
                        footnote: "键 deepseek_thinking_enabled（默认关闭）",
                        isOn: $deepseekThinking
                    )
                }
            }
        }
    }

    // MARK: - 上下文

    private var contextGroup: some View {
        SettingsKnockGroup("上下文", systemImage: "clock.arrow.circlepath") {
            KnockPanel {
                SettingsKnockSliderRow(
                    title: "历史回看轮数",
                    valueText: "\(historyMessages)",
                    value: Binding(
                        get: { Double(historyMessages) },
                        set: { historyMessages = Int($0.rounded()) }
                    ),
                    range: 0...200,
                    step: 1,
                    hint: "键 history_messages（默认 15）。私聊与群聊共用；手动调整只影响本轮请求带入的近期互动，不改变滚动摘要 / 记忆提取。"
                )
            }
        }
    }

    // MARK: - 回复长度上限

    private var replyLengthGroups: some View {
        VStack(spacing: KnockTokens.spacingXL) {
            SettingsKnockGroup("台词输出", systemImage: "bubble.left.and.bubble.right") {
                KnockPanel {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                        SettingsKnockRangeRow(
                            title: "台词段数范围",
                            minValue: $dialogueSegMin,
                            maxValue: $dialogueSegMax,
                            range: 0...10,
                            step: 1,
                            hint: "键 dia_seg_min / dia_seg_max。每轮输出多少段台词（默认 1–3 段），线上/线下/导演模式都生效。"
                        )

                        KnockDivider()

                        SettingsKnockRangeRow(
                            title: "台词单段字数",
                            minValue: $dialogueMinChars,
                            maxValue: $dialogueMaxChars,
                            range: 1...1000,
                            step: 5,
                            hint: "键 dia_min / dia_max。每段台词的字符上下限（默认 10–300 字），太低会一问一答，太高回复变啰嗦。"
                        )
                    }
                }
            }

            SettingsKnockGroup("旁白输出", systemImage: "text.bubble") {
                KnockPanel {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                        SettingsKnockRangeRow(
                            title: "旁白段数范围",
                            minValue: $narrationSegMin,
                            maxValue: $narrationSegMax,
                            range: 1...10,
                            step: 1,
                            hint: "键 nar_seg_min / nar_seg_max。每轮输出多少段【旁白】（默认 1–3 段）。仅线下/导演模式生效，线上模式禁止旁白。"
                        )

                        KnockDivider()

                        SettingsKnockRangeRow(
                            title: "旁白单段字数",
                            minValue: $narrationMinChars,
                            maxValue: $narrationMaxChars,
                            range: 0...1000,
                            step: 5,
                            hint: "键 nar_min / nar_max。每段旁白的字符上下限（默认 50–300 字）。仅线下/导演模式生效。"
                        )
                    }
                }
            }
        }
    }

    // MARK: - 群聊输出

    private var groupBudgetGroup: some View {
        SettingsKnockGroup(
            "群聊输出",
            systemImage: "person.3",
            footer: "每位成员的发言段数与每条消息字数，以及群聊旁白的段数与字数（旁白仅线下/导演模式）。"
        ) {
            KnockPanel {
                VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                    SettingsKnockRangeRow(
                        title: "每位成员发言段数",
                        minValue: $groupSpeechMin,
                        maxValue: $groupSpeechMax,
                        range: 1...20,
                        step: 1,
                        hint: "键 group_speech_min / group_speech_max。每位当前成员本轮必须发言的段数（默认 1–2 段）。"
                    )

                    KnockDivider()

                    SettingsKnockRangeRow(
                        title: "每条消息字数",
                        minValue: $groupMsgMin,
                        maxValue: $groupMsgMax,
                        range: 5...200,
                        step: 5,
                        hint: "键 group_msg_min / group_msg_max。每位成员每条消息的字符范围（默认 10–100 字）。"
                    )

                    KnockDivider()

                    SettingsKnockRangeRow(
                        title: "群聊旁白段数",
                        minValue: $groupNarSegMin,
                        maxValue: $groupNarSegMax,
                        range: 1...20,
                        step: 1,
                        hint: "键 group_nar_seg_min / group_nar_seg_max。每轮群聊旁白段数（默认 1–1 段），仅线下/导演模式。"
                    )

                    KnockDivider()

                    SettingsKnockRangeRow(
                        title: "群聊旁白字数",
                        minValue: $groupNarMin,
                        maxValue: $groupNarMax,
                        range: 0...300,
                        step: 5,
                        hint: "键 group_nar_min / group_nar_max。每段群聊旁白的字符范围（默认 20–100 字）。"
                    )
                }
            }
        }
    }

    // MARK: - 保存

    private var saveGroup: some View {
        VStack(spacing: KnockTokens.spacingM) {
            Button(action: save) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 15, weight: .heavy))
                    KnockText("保存聊天表现", role: .rowTitle, color: KnockTokens.onElectric, lineLimit: 1)
                }
            }
            .buttonStyle(KnockPrimaryButtonStyle())
            .disabled(!loaded)

            SettingsKnockBullet("数值范围与 Android 原版一致（段数与字数分别限定）；shared SettingsRepository 内部会再做一次范围收敛（min 不超过 max）。")
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}