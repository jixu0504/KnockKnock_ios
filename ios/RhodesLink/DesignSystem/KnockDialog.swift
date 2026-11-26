//
//  KnockDialog.swift
//  「敲敲」自绘确认弹窗（黑/白面板 + 黄主按钮）。
//
//  设计依据：docs/敲敲UI改版计划书-v1.1.md
//  - 第 2.1 节：自绘确认弹窗（黑/白面板 + 黄主按钮）；
//  - 第五章 Liquid Glass 风险：系统 alert 的背景由系统接管（iOS 26 为玻璃），
//    因此确认框必须自绘，禁用 `.alert`；
//  - 配色纪律：确认 = 黄主按钮（黄 #F2FF00 + 黑字），取消 = 描边次按钮，
//    危险操作 = `#BF2207` 底 + 白字；背景遮罩为纯黑半透明遮罩（非毛玻璃）。
//
//  可访问性：
//  - 遮罩 `.accessibilityHidden(true)`，弹窗容器带 `.isModal` trait（VoiceOver 会聚焦弹窗）；
//  - 两个按钮均为真实 Button（最小高 46pt），文案由调用方给出中文动词；
//  - 减弱动态效果（reduceMotion）时不做缩放/淡入动画；
//  - 遮罩点击 = 取消（与系统 alert 点外部关闭一致但不强制）。
//

import SwiftUI

// MARK: - 弹窗面板容器

/// 自绘弹窗面板：圆角 20pt + 实色底 + 1.5pt 描边 + 可选棋盘格，最大宽 360pt。
struct KnockDialogPanel<Content: View>: View {
    var title: String?
    var showsChecker: Bool
    private let content: () -> Content

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    init(
        title: String? = nil,
        showsChecker: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.showsChecker = showsChecker
        self.content = content
    }

    private var strokeColor: Color {
        colorSchemeContrast == .increased ? KnockTokens.strokeContrast : KnockTokens.stroke
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
            if let title {
                KnockText(title, role: .pageTitle)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            content()
        }
        .padding(KnockTokens.spacingXL)
        .frame(maxWidth: 360)
        .background {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .fill(KnockTokens.panel)
                .overlay {
                    if showsChecker {
                        KnockCheckerOverlay(cornerRadius: KnockTokens.panelRadius)
                    }
                }
        }
        .overlay {
            RoundedRectangle(cornerRadius: KnockTokens.panelRadius, style: .continuous)
                .strokeBorder(strokeColor, lineWidth: KnockTokens.strokeWidth)
        }
    }
}

// MARK: - 确认弹窗

/// 敲敲确认弹窗：标题 + 说明 + 「取消」描边按钮 + 「确认」黄/红按钮。
struct KnockConfirmDialog: View {
    var title: String
    var message: String
    var confirmTitle: String
    var cancelTitle: String
    var isDestructive: Bool
    var onConfirm: () -> Void
    var onCancel: () -> Void

    init(
        title: String,
        message: String,
        confirmTitle: String = "确认",
        cancelTitle: String = "取消",
        isDestructive: Bool = false,
        onConfirm: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.title = title
        self.message = message
        self.confirmTitle = confirmTitle
        self.cancelTitle = cancelTitle
        self.isDestructive = isDestructive
        self.onConfirm = onConfirm
        self.onCancel = onCancel
    }

    var body: some View {
        KnockDialogPanel(title: title) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingXL) {
                KnockText(
                    message,
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 3
                )
                .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: KnockTokens.spacingM) {
                    Button(cancelTitle, action: onCancel)
                        .buttonStyle(KnockSecondaryButtonStyle())
                    if isDestructive {
                        Button(confirmTitle, action: onConfirm)
                            .buttonStyle(KnockDangerButtonStyle())
                    } else {
                        Button(confirmTitle, action: onConfirm)
                            .buttonStyle(KnockPrimaryButtonStyle())
                    }
                }
            }
        }
    }
}

// MARK: - 弹窗挂载修饰符

private struct KnockConfirmDialogModifier: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let message: String
    let confirmTitle: String
    let cancelTitle: String
    let isDestructive: Bool
    let onConfirm: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .overlay {
                if isPresented {
                    ZStack {
                        // 纯黑遮罩（非毛玻璃）：点击遮罩 = 取消
                        Color.black.opacity(0.62)
                            .ignoresSafeArea()
                            .onTapGesture { dismiss(confirmed: false) }
                            .accessibilityHidden(true)

                        KnockConfirmDialog(
                            title: title,
                            message: message,
                            confirmTitle: confirmTitle,
                            cancelTitle: cancelTitle,
                            isDestructive: isDestructive,
                            onConfirm: { dismiss(confirmed: true) },
                            onCancel: { dismiss(confirmed: false) }
                        )
                        .padding(.horizontal, KnockTokens.spacingXXL)
                        .accessibilityAddTraits(.isModal)
                    }
                    .transition(.opacity)
                }
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isPresented)
    }

    private func dismiss(confirmed: Bool) {
        isPresented = false
        if confirmed {
            onConfirm()
        }
    }
}

extension View {
    /// 挂载自绘确认弹窗（替代系统 `.alert`，避开 iOS 26 玻璃背景）。
    ///
    /// ```swift
    /// .knockConfirmDialog(
    ///     isPresented: $showsDelete,
    ///     title: "删除该角色？",
    ///     message: "删除后聊天记录仍会保留，无法撤销。",
    ///     confirmTitle: "删除",
    ///     isDestructive: true
    /// ) { delete() }
    /// ```
    func knockConfirmDialog(
        isPresented: Binding<Bool>,
        title: String,
        message: String,
        confirmTitle: String = "确认",
        cancelTitle: String = "取消",
        isDestructive: Bool = false,
        onConfirm: @escaping () -> Void
    ) -> some View {
        modifier(
            KnockConfirmDialogModifier(
                isPresented: isPresented,
                title: title,
                message: message,
                confirmTitle: confirmTitle,
                cancelTitle: cancelTitle,
                isDestructive: isDestructive,
                onConfirm: onConfirm
            )
        )
    }
}