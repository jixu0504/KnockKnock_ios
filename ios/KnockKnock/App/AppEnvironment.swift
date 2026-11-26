//
//  AppEnvironment.swift
//  执行环境（AppGraph 单例 + 设置状态）：
//  - `AppGraphClient.shared`：Shared.framework 的 RhodesAppGraph（DB / 桥接 / 聊天引擎）；
//  - `SettingsStore`：外观（跟随系统/浅色/深色）与设置页真实读写；
//  - 启动时若无会话则调用一次 `bootstrap()` 播种演示数据（Phase 3 壳的临时行为，
//    正式产品由 onboarding / 导入流程取代，见 ios-port/specs/ios-shell-spec.md）。
//

import SwiftUI

@MainActor
final class AppEnvironment: ObservableObject {
    static let shared = AppEnvironment()

    let bridge: AppGraphClient
    let settings: SettingsStore

    @Published private(set) var ready = false
    @Published private(set) var seedMessage = ""
    @Published private(set) var sessionCount = 0
    @Published private(set) var memoryCount = 0

    private var didStart = false

    private init() {
        bridge = AppGraphClient.shared
        settings = SettingsStore(bridge: AppGraphClient.shared)
    }

    /// 幂等启动：加载会话/记忆计数，必要时播种演示数据（仅首次为空时）
    func start() async {
        guard !didStart else { return }
        didStart = true

        let sessions = await bridge.loadSessions()
        switch sessions {
        case .success(let items):
            sessionCount = items.count
            if items.isEmpty {
                let result = await bridge.bootstrap()
                seedMessage = result.error == nil
                    ? "已播种演示数据（会话 \(result.count) 个）"
                    : "播种失败：\(result.error ?? "未知错误")"
                sessionCount = result.count
                SelfTestLog.add("bootstrap sessions=\(result.count) error=\(result.error ?? "-")")
            } else {
                seedMessage = "已就绪：\(items.count) 个会话"
            }
        case .failure(let error):
            seedMessage = "会话读取失败：\(error.message)"
            SelfTestLog.add("sessions load failed error=\(error.message)")
        }

        let memories = await bridge.loadMemories()
        if case .success(let items) = memories {
            memoryCount = items.count
        }

        // T56：启动 A7 自动化接线（前台 tick + 启动 catch-up；通知事件源按设置开关过滤）。
        bridge.startA7Glue()
        SelfTestLog.add("a7 glue: \(bridge.a7StatusLine())")

        ready = true
        SelfTestLog.add("environment ready db=\(bridge.databasePath) sessions=\(sessionCount) memories=\(memoryCount)")
    }

    /// 刷新计数（聊天/播种后调用）
    func refreshCounts() async {
        if case .success(let items) = await bridge.loadSessions() { sessionCount = items.count }
        if case .success(let items) = await bridge.loadMemories() { memoryCount = items.count }
    }
}

// MARK: - 设置状态（外观 + 设置页真实读写）

@MainActor
final class SettingsStore: ObservableObject {
    enum Appearance: String, CaseIterable, Identifiable {
        case system
        case light
        case dark

        var id: String { rawValue }

        var label: String {
            switch self {
            case .system: return "跟随系统"
            case .light: return "浅色"
            case .dark: return "深色"
            }
        }

        /// nil = 跟随系统（SwiftUI preferredColorScheme(nil)）
        var colorScheme: ColorScheme? {
            switch self {
            case .system: return nil
            case .light: return .light
            case .dark: return .dark
            }
        }
    }

    /// T53 字体大小档位：小 / 标准 / 大（标准 = 跟随系统 Dynamic Type）。
    /// 通过 `RootTabView` 的 `.dynamicTypeSize(...)` 全局缩放（聊天与列表页无需改动即可生效）。
    enum FontSize: String, CaseIterable, Identifiable {
        case small
        case standard
        case large

        var id: String { rawValue }

        var label: String {
            switch self {
            case .small: return "小"
            case .standard: return "标准"
            case .large: return "大"
            }
        }

        /// nil = 跟随系统（不强制 Dynamic Type 档位）。
        var dynamicTypeSize: DynamicTypeSize? {
            switch self {
            case .small: return .small
            case .standard: return nil
            case .large: return .xxLarge
            }
        }

        var scaleDescription: String {
            switch self {
            case .small: return "强制 Dynamic Type .small（约 15pt 正文）"
            case .standard: return "跟随系统 Dynamic Type（默认 .large）"
            case .large: return "强制 Dynamic Type .xxLarge（约 23pt 正文）"
            }
        }
    }

    static let appearanceKey = "ios_shell_appearance"
    static let noteKey = "ios_shell_note"
    static let fontSizeKey = "ios_shell_font_size"

    @Published private(set) var appearance: Appearance
    /// T53：聊天背景（读 `ChatBackgroundStyle.settingsKey`；聊天页渲染时直接读 UserDefaults）。
    @Published private(set) var chatBackground: ChatBackgroundStyle
    /// T53：字体大小档位（`ios_shell_font_size`）。
    @Published private(set) var fontSize: FontSize
    @Published private(set) var lastSnapshot = ""
    @Published private(set) var lastNoteReadBack = ""
    @Published private(set) var lastWriteTime: String = ""

    private let bridge: AppGraphClient

    init(bridge: AppGraphClient) {
        self.bridge = bridge
        let raw = bridge.settingsString(key: Self.appearanceKey)
        appearance = Appearance(rawValue: raw) ?? .system
        let backgroundRaw = bridge.settingsString(key: ChatBackgroundStyle.settingsKey)
        chatBackground = ChatBackgroundStyle(rawValue: backgroundRaw) ?? .classic
        let fontRaw = bridge.settingsString(key: Self.fontSizeKey)
        fontSize = FontSize(rawValue: fontRaw) ?? .standard
        lastSnapshot = bridge.settingsSnapshot()
        lastNoteReadBack = bridge.settingsString(key: Self.noteKey)
        // 把 Swift 侧外观同步到 shared 设置（dark_mode），保证两端一致
        bridge.setDarkMode(appearance == .dark)
    }

    func setAppearance(_ mode: Appearance) {
        appearance = mode
        bridge.setSettingsString(key: Self.appearanceKey, value: mode.rawValue)
        bridge.setDarkMode(mode == .dark)
        refreshSnapshot()
        SelfTestLog.add("settings appearance=\(mode.rawValue) snapshot=\(lastSnapshot)")
    }

    /// T53：聊天背景（写 `ios_chat_background`；聊天页渲染时读 UserDefaults，重进聊天页生效）。
    func setChatBackground(_ style: ChatBackgroundStyle) {
        chatBackground = style
        bridge.setSettingsString(key: ChatBackgroundStyle.settingsKey, value: style.rawValue)
        refreshSnapshot()
        SelfTestLog.add("settings chat-background=\(style.rawValue) snapshot=\(lastSnapshot)")
    }

    /// T53：字体大小档位（写 `ios_shell_font_size`；`RootTabView` 立即应用 Dynamic Type 缩放）。
    func setFontSize(_ size: FontSize) {
        fontSize = size
        bridge.setSettingsString(key: Self.fontSizeKey, value: size.rawValue)
        refreshSnapshot()
        SelfTestLog.add("settings font-size=\(size.rawValue) dynamicType=\(size.scaleDescription)")
    }

    /// 真实写入 + 立即读回（设置页「写入测试值」按钮；证明 SettingsBridge 生效）
    @discardableResult
    func writeTestNote() -> String {
        let stamp = Self.timestamp()
        let value = "KnockKnock iOS 真壳写入 @\(stamp)"
        bridge.setSettingsString(key: Self.noteKey, value: value)
        let readBack = bridge.settingsString(key: Self.noteKey)
        lastNoteReadBack = readBack
        lastWriteTime = stamp
        refreshSnapshot()
        SelfTestLog.add("settings write note=\(value) readBack=\(readBack)")
        return readBack
    }

    func refreshSnapshot() {
        lastSnapshot = bridge.settingsSnapshot()
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: Date())
    }
}
