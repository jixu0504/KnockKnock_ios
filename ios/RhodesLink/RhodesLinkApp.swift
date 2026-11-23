//
//  RhodesLinkApp.swift
//  RhodesLink iOS 真壳入口（T33，Phase 3）。
//
//  - 4 Tab（聊天 / 角色 / 记忆 / 设置），每个 Tab 独立 NavigationStack 路径（AppRouter）；
//  - 深链：rhodes://… → AppRouter.handle(url:)（见 AppRouter.swift 路由表）；
//  - 外观：跟随系统 / 浅色 / 深色（设置页写入 shared SettingsBridge，实时生效）；
//  - iOS 26：Tab Bar 最小化（.tabBarMinimizeBehavior(.automatic)）；iOS 18：系统默认。
//

import SwiftUI

@main
struct RhodesLinkApp: App {
    @StateObject private var environment = AppEnvironment.shared
    @StateObject private var router = AppRouter()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // T56：BGAppRefresh 兜底通道注册（模拟器 submit 被平台拒绝 → A7 glue 自动降级前台补算）。
        A7BackgroundRefresh.register()
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environmentObject(environment)
                .environmentObject(environment.settings)
                .environmentObject(router)
                .onOpenURL { url in
                    _ = router.handle(url: url)
                }
                .onChange(of: scenePhase) { _, phase in
                    // T56：前台 tick 运行 / 后台暂停；回前台立即补算（A7「打开 App 立即补上」口径）。
                    switch phase {
                    case .active:
                        environment.bridge.setA7Foreground(true)
                        A7BackgroundRefresh.schedule()
                    case .background:
                        environment.bridge.setA7Foreground(false)
                        A7BackgroundRefresh.schedule()
                    default:
                        break
                    }
                }
                .task {
                    await environment.start()
                    // 自动化验证钩子：`--open-url rhodes://…` 走与 onOpenURL 完全相同的
                    // AppRouter.handle(url:) 路径（模拟器无法点击系统「在 RhodesLink 中打开？」确认框）。
                    SelfTestDriver.handleLaunchURLIfNeeded(router: router)
                    // T36 角色/会话页验证钩子：`--roles-selftest` / `--autochat <operatorId>`
                    // （放在 SelfTestDriver 之前，角色/关系页打点会进入 --selftest 落盘日志）
                    await RolesAutomation.runIfNeeded(environment: environment, router: router)
                    // T37 真实供应商自动化（--provider-selftest）：演示回退/真实流式/错误映射/取消/连接测试矩阵
                    await ProviderAutomation.runIfNeeded(environment: environment, router: router)
                    // T40 会话列表操作自动化（--sessions-selftest）：置顶排序/已读/移除/新消息恢复/hideAll
                    await SessionsAutomation.runIfNeeded(environment: environment, router: router)
                    // T47 记忆管理自动化（--memory-mgmt-selftest）：预置/列表/筛选/编辑/删除/隐私/锚点
                    await MemoryAutomation.runIfNeeded(environment: environment)
                    // T46 设置页自动化（--settings-selftest / -verify / -restore）：
                    // 聊天表现读写落盘 / 提示词保存-恢复默认往返 / 识图配置 effectiveVisionConfiguration.configured
                    await SettingsAutomation.runIfNeeded(environment: environment)
                    // T53 设置余项自动化（--settings-extra-selftest / -verify / -restore）：
                    // 调试日志读取/导出、通知 4 键落盘读回、外观 3 键（主题/聊天背景/字号）落盘冷启动一致
                    await SettingsExtraAutomation.runIfNeeded(environment: environment)
                    await SelfTestDriver.runIfNeeded(environment: environment, router: router)
                }
        }
    }
}

// MARK: - 根 Tab 容器

struct RootTabView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var router: AppRouter

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TabView(selection: $router.selectedTab) {
            ChatTabRoot()
                .tabItem { Label(AppTab.chat.title, systemImage: AppTab.chat.systemImage) }
                .tag(AppTab.chat)
            ContactsTabRoot()
                .tabItem { Label(AppTab.contacts.title, systemImage: AppTab.contacts.systemImage) }
                .tag(AppTab.contacts)
            FeaturesTabRoot()
                .tabItem { Label(AppTab.features.title, systemImage: AppTab.features.systemImage) }
                .tag(AppTab.features)
            SettingsTabRoot()
                .tabItem { Label(AppTab.settings.title, systemImage: AppTab.settings.systemImage) }
                .tag(AppTab.settings)
        }
        .tint(GlassTokens.brandOrange)
        .modifier(GlassTabBarModifier())
        .preferredColorScheme(settings.appearance.colorScheme)
        // T53：字体大小（小/标准/大）→ 全局 Dynamic Type 缩放（聊天与列表字体一并生效）
        .modifier(AppFontScaleModifier(fontSize: settings.fontSize))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: router.selectedTab)
    }
}

// MARK: - 四个 Tab 根（各自 NavigationStack 路径 + 统一 Route 目标）

struct ChatTabRoot: View {
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        NavigationStack(path: $router.chatPath) {
            ChatListView()
                .navigationDestination(for: Route.self) { route in
                    RouteView(route: route)
                }
        }
    }
}

struct ContactsTabRoot: View {
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        NavigationStack(path: $router.contactsPath) {
            RolesView()
                .navigationDestination(for: Route.self) { route in
                    RouteView(route: route)
                }
        }
    }
}

struct FeaturesTabRoot: View {
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        NavigationStack(path: $router.featuresPath) {
            FeaturesView()
                .navigationDestination(for: Route.self) { route in
                    RouteView(route: route)
                }
        }
    }
}

struct SettingsTabRoot: View {
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        NavigationStack(path: $router.settingsPath) {
            SettingsView()
                .navigationDestination(for: Route.self) { route in
                    RouteView(route: route)
                }
        }
    }
}

/// iOS 26：Tab Bar 最小化（滚动时收窄为浮动玻璃胶囊）；iOS 18：无此能力。
private struct GlassTabBarModifier: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.tabBarMinimizeBehavior(.automatic)
        } else {
            content
        }
    }
}

/// T53：字体大小档位 → 全局 Dynamic Type 缩放。
/// 标准档 = 跟随系统（不加 modifier，保留用户可访问性字号）；小/大档强制对应 Dynamic Type 档位。
private struct AppFontScaleModifier: ViewModifier {
    let fontSize: SettingsStore.FontSize

    @ViewBuilder
    func body(content: Content) -> some View {
        if let size = fontSize.dynamicTypeSize {
            content.dynamicTypeSize(size)
        } else {
            content
        }
    }
}
