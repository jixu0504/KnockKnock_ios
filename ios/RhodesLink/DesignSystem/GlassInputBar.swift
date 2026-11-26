//
//  GlassInputBar.swift
//  「敲敲」聊天输入栏（P1 视觉重写；文件名与对外 API 保留）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 1.2 节条形栏规格 + 2.2 节：**黑底 + 1.5pt 描边胶囊输入框 + 黄色圆形发送/停止键**；
//    输入框底 = 行色（深 `#171717` / 浅 `#F7F7F5`），1.5pt 次描边，圆角=胶囊；
//    黄 = 主 CTA（发送 / 停止是聊天页唯一黄按钮）；蓝只用于消息气泡，不做按钮；
//  - 第 7.1 节验收 10：输入栏描边胶囊 + 键盘吸附；
//  - 禁止毛玻璃 / `.bar` 材质 / 半透明底 —— 输入栏一律实色 `KnockTokens.canvas` + 1pt 顶部分隔线。
//
//  保留的既有能力（业务零改动）：
//  - **键盘避让采用显式监听**（keyboardWillShow/Hide 抬升输入栏），不依赖父视图 safeArea 推导
//    （TabView + NavigationStack + safeAreaInset 组合下更可靠）；`SelfTestLog` 打点原样保留；
//  - 回调签名不变：`onSend` / `onCancel` / `onAddAttachment`；`placeholder` 可覆盖（群聊用）；
//  - 多行输入（1...5 行）自适应，Dynamic Type 大字号下不裁切（minHeight + 自适应高度）。
//
//  可访问性：
//  - 发送 / 停止 / 附件按钮均有 `accessibilityLabel`，最小命中 44pt 由按钮 frame 保证；
//  - 输入框文字经 `KnockTypography`（Dynamic Type）；光标品牌黄；占位符次文字灰；
//  - 发送按钮禁用态（空草稿）用中性灰阶，不只靠颜色区分（图标同时变淡 + disabled 语义）。
//

import SwiftUI
import UIKit

struct GlassInputBar: View {
    @Binding var text: String
    var isStreaming: Bool
    var placeholder: String = "输入消息…"
    var onSend: () -> Void
    var onCancel: () -> Void
    /// T42：附件（+）→ PhotosPicker → 发送图片
    var onAddAttachment: () -> Void = {}

    @FocusState private var isFocused: Bool
    /// 键盘抬升高度（已扣除底部安全区，避免重复留白）
    @State private var keyboardInset: CGFloat = 0

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var fieldStrokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.strokeSecondary
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: KnockTokens.spacingS) {
            attachmentButton
            inputField
            trailingButton
        }
        .padding(.horizontal, KnockTokens.spacingM)
        .padding(.vertical, KnockTokens.spacingS)
        // 实色页底（禁止 .bar / 毛玻璃）
        .background(KnockTokens.canvas)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(KnockTokens.stroke)
                .frame(height: KnockTokens.hairline)
                .accessibilityHidden(true)
        }
        // 键盘抬升：输入栏整体上移键盘高度（父视图需 ignoresSafeArea(.keyboard)，见 ChatView）
        .padding(.bottom, keyboardInset)
        .animation(.easeOut(duration: 0.22), value: keyboardInset)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            guard
                let end = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            let safeBottom = Self.bottomSafeAreaInset
            keyboardInset = max(0, end.height - safeBottom)
            SelfTestLog.add("input keyboardWillShow height=\(end.height) safeBottom=\(safeBottom) inset=\(keyboardInset)")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardInset = 0
            SelfTestLog.add("input keyboardWillHide")
        }
    }

    // MARK: 附件（+）— 圆形描边按钮

    private var attachmentButton: some View {
        Button(action: onAddAttachment) {
            Image(systemName: "plus")
                .font(.system(size: 17, weight: .heavy))
                .foregroundStyle(KnockTokens.textPrimary)
                .frame(width: 36, height: 36)
                .background(Circle().fill(KnockTokens.row))
                .overlay {
                    Circle().strokeBorder(fieldStrokeColor, lineWidth: KnockTokens.strokeWidth)
                }
                // 视觉 36pt、命中区补齐到 44pt
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("添加附件")
    }

    // MARK: 输入框（胶囊 + 1.5pt 描边 + 行底 + 多行自适应）

    private var inputField: some View {
        TextField("", text: $text, prompt: promptText, axis: .vertical)
            .lineLimit(1...5)
            .font(KnockTypography.font(.body, dynamicTypeSize: dynamicTypeSize))
            .foregroundStyle(KnockTokens.textPrimary)
            .tint(KnockTokens.electric)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(KnockTokens.row)
            }
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(fieldStrokeColor, lineWidth: KnockTokens.strokeWidth)
            }
            .focused($isFocused)
            .accessibilityLabel(placeholder)
    }

    /// 占位符：次文字灰（计划书 1.2：占位符 `#9A9A9A`）。
    private var promptText: Text {
        Text(placeholder)
            .font(KnockTypography.font(.body, dynamicTypeSize: dynamicTypeSize))
            .foregroundStyle(KnockTokens.textSecondary)
    }

    // MARK: 发送 / 停止（黄色圆形主 CTA）

    @ViewBuilder
    private var trailingButton: some View {
        if isStreaming {
            Button(action: onCancel) {
                Image(systemName: "stop.fill")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(KnockTokens.onElectric)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(KnockTokens.electric))
                    .overlay {
                        Circle().strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                    }
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("停止生成")
        } else {
            Button(action: onSend) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundStyle(canSend ? KnockTokens.onElectric : KnockTokens.textTertiary)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(canSend ? KnockTokens.electric : KnockTokens.rowPressed))
                    .overlay {
                        Circle().strokeBorder(
                            canSend ? KnockTokens.electricRing : fieldStrokeColor,
                            lineWidth: KnockTokens.strokeWidth
                        )
                    }
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
            .accessibilityLabel("发送消息")
        }
    }

    /// 当前窗口底部安全区（键盘 frame 已包含该区域，需扣除避免双倍留白）
    private static var bottomSafeAreaInset: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            if let window = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first {
                return window.safeAreaInsets.bottom
            }
        }
        return 0
    }
}