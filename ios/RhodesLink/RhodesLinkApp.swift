//
//  RhodesLinkApp.swift
//  RhodesLink iOS 真壳入口（T33，Phase 3；敲敲改版 P0 根容器改造）。
//
//  - 4 Tab（聊天 / 联系人 / 功能 / 设置），每个 Tab 独立 NavigationStack 路径（AppRouter）；
//  - **隐藏系统 TabBar**（`toolbar(.hidden, for: .tabBar)`）→ 自绘 `KnockTabBar`
//    （图 B 胶囊分段栏形态：整条胶囊容器 + 选中黄胶囊 + 浅蓝外圈 + 黑字）；
//    TabBar 挂在**每个 Tab 的 NavigationStack 根视图**的 `safeAreaInset` 上（P5 收口修复，
//    见 `TabRootStack` 注释）：任何形式的二级页 push 都会让根页离屏 → TabBar 自动收起；
//  - 深链：rhodes://… → AppRouter.handle(url:)（见 AppRouter.swift 路由表）；
//  - 外观：跟随系统 / 浅色 / 深色（设置页写入 shared SettingsBridge，实时生效）；
//    Info.plist 不锁深色；字号三档经 `.dynamicTypeSize` 全局生效；
//  - `--knock-gallery` 启动参数直接打开设计系统画廊（P0 截图验收，不启动桥与自动化）。
//
//  可访问性：TabBar 每项为真实 Button（`.isSelected` trait）；减弱动态效果时切 Tab 无动画；
//  纹理 / 字体描边补偿两个全局开关经环境值下发给全站组件。
//

import SwiftUI

@main
struct RhodesLinkApp: App {
    init() {
        // T56：BGAppRefresh 兜底通道注册（模拟器 submit 被平台拒绝 → A7 glue 自动降级前台补算）。
        A7BackgroundRefresh.register()
    }

    var body: some Scene {
        WindowGroup {
            if KnockLaunchOptions.showsGallery {
                // P0 设计系统画廊：`--knock-gallery`（可加 `--knock-appearance dark|light`）。
                // 刻意**不实例化** AppEnvironment / AppRouter（避免冷启动初始化 KMP 桥），
                // 画廊首帧更快、截图更稳定（专项页不再出现启动黑屏）。
                KnockGalleryView()
            } else {
                RootShell()
            }
        }
    }
}

// MARK: - 正式壳根容器（持有 AppEnvironment / AppRouter / 生命周期与自动化钩子）

/// 只在非画廊启动路径创建：`AppEnvironment.shared` 会初始化 KMP 桥（较重），
/// 画廊截图/设计验收不需要它，因此放到这里按需创建。
private struct RootShell: View {
    @StateObject private var environment = AppEnvironment.shared
    @StateObject private var router = AppRouter()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
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

// MARK: - 根 Tab 容器（隐藏系统 TabBar + 自绘 KnockTabBar）

struct RootTabView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var router: AppRouter

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // 两个全局视觉开关：设置页写入 UserDefaults 后这里自动刷新并下发环境值
    @AppStorage(KnockPreferences.textureKey) private var textureEnabled = true
    @AppStorage(KnockPreferences.strokeCompensationKey) private var strokeCompensation = true

    var body: some View {
        TabView(selection: $router.selectedTab) {
            TabRootStack(path: $router.chatPath) { ChatListView() }
                .tabItem { Label(AppTab.chat.title, systemImage: AppTab.chat.systemImage) }
                .tag(AppTab.chat)
            TabRootStack(path: $router.contactsPath) { RolesView() }
                .tabItem { Label(AppTab.contacts.title, systemImage: AppTab.contacts.systemImage) }
                .tag(AppTab.contacts)
            TabRootStack(path: $router.featuresPath) { FeaturesView() }
                .tabItem { Label(AppTab.features.title, systemImage: AppTab.features.systemImage) }
                .tag(AppTab.features)
            TabRootStack(path: $router.settingsPath) { SettingsView() }
                .tabItem { Label(AppTab.settings.title, systemImage: AppTab.settings.systemImage) }
                .tag(AppTab.settings)
        }
        // 计划书 7.1-9：系统 TabBar 必须隐藏且无玻璃边缘
        .toolbar(.hidden, for: .tabBar)
        .tint(KnockTokens.electric)
        .preferredColorScheme(settings.appearance.colorScheme)
        // T53：字体大小（小/标准/大）→ 全局 Dynamic Type 缩放（聊天与列表字体一并生效）
        .modifier(AppFontScaleModifier(fontSize: settings.fontSize))
        // 敲敲全局视觉开关（画廊 / 设置页可改；默认读取 UserDefaults）
        .environment(\.knockTextureEnabled, textureEnabled)
        .environment(\.knockStrokeCompensation, strokeCompensation)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: router.selectedTab)
    }
}

// MARK: - 四个 Tab 根（各自 NavigationStack 路径 + 统一 Route 目标 + 根页专属 KnockTabBar）

/// Tab 根容器：`NavigationStack` + 统一 `Route` 目标 + **只挂在根页上的** `KnockTabBar`。
///
/// P5 收口 Bug 修复（content 域报告「KnockTabBar 遮挡二级页」）：
/// 旧实现把 `KnockTabBar` 作为 `RootTabView` 的 `TabView.safeAreaInset`，再用
/// `router.<tab>Path.isEmpty` 判断是否显示。但下面三类二级页 push **不经过 AppRouter.path**，
/// 判断失效 → TabBar 仍然渲染、盖住页面底部（设置→我的身份、角色详情→关系网、
/// 日记→自动管理、提示词→示例详情）：
///   1) `navigationDestination(isPresented:)`（SettingsView / RoleDetailView / DiaryView / PromptEditorView）；
///   2) `NavigationLink { destination }` 目的视图写法（PromptEditorView / FeaturesView）；
///   3) 未来任何新增的非 path 路由。
///
/// 修法（App 层一处解决，无需改 Features 导航代码）：把 `KnockTabBar` 下移到**各 Tab 根视图**
/// 的 `safeAreaInset(edge: .bottom)`。safeAreaInset 属于根视图自身的布局，push 后根页离屏，
/// TabBar 随之消失 —— 与 push 方式无关，也不再依赖「path 是否为空」这种脆弱的自证。
/// 行为与 iOS 聊天类 App 一致：仅 Tab 根页显示底部栏，二级页全高显示（输入栏/列表贴合安全区）；
/// sheet 覆盖在整棵 TabView 之上，天然不会出现「TabBar 盖 sheet」。
private struct TabRootStack<Root: View>: View {
    @EnvironmentObject private var router: AppRouter
    @Binding var path: NavigationPath
    @ViewBuilder let root: () -> Root

    private var tabItems: [KnockSegmentItem<AppTab>] {
        AppTab.allCases.map { tab in
            KnockSegmentItem(value: tab, title: tab.title, systemImage: tab.systemImage)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            root()
                .navigationDestination(for: Route.self) { route in
                    RouteView(route: route)
                }
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    KnockTabBar(items: tabItems, selection: $router.selectedTab)
                }
        }
        .toolbar(.hidden, for: .tabBar)
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