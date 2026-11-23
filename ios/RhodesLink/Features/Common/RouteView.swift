//
//  RouteView.swift
//  统一路由目标视图（四个 Tab 的 navigationDestination 共用）。
//  已实现：聊天页 / 新建聊天 / 角色详情 / 群聊页（T43）/ 记忆详情；其余为占位页。
//

import SwiftUI

struct RouteView: View {
    let route: Route

    var body: some View {
        switch route {
        case .chat(let sessionId):
            ChatView(sessionId: sessionId)

        case .newChat:
            NewChatView()

        case .roleDetail(let roleId):
            RoleDetailView(roleId: roleId)

        case .groupChat(let sessionId):
            GroupChatView(sessionId: sessionId)

        case .memoryDetail(let memoryID):
            MemoryDetailView(memoryID: memoryID)

        case .memoryList:
            MemoryListView()
                .navigationTitle("记忆管理")

        case .moments:
            // T50：动态广场真实页面（时间线 / 发布 / 点赞 / 评论 / 删除；真实 SQLite）
            MomentsView()

        case .diary:
            // T51：干员日记真实页面（列表/未读筛选/详情阅读/标记已读；真实 SQLite + settings）
            DiaryView()

        case .impressions:
            // T54：大家的印象真实页面（三段分组/来源标签；真实 memory_items + 旧版 memories 兜底）
            ImpressionsView()

        case .ranking:
            // T54：聊天排行榜 / 消费统计真实页面（三维度 + Token 今日/本周/全部；真实 SQLite + settings）
            RankingView()

        case .settingsAppearance:
            // T53：外观设置真实页面（主题 / 聊天背景 / 字体大小；键落盘 + 聊天页/全局生效）
            AppearanceSettingsView()

        case .settingsNotifications:
            // T53：通知设置（真实 UNUserNotificationCenter 授权状态/请求 + 4 类提醒开关落盘）
            NotificationSettingsView()

        case .settingsDebugLogs:
            // T53：调试日志（shared IosDebugLogger 文件日志列表 / 筛选 / 导出 / 清空）
            DebugLogsView()

        case .settingsProviders:
            ProviderSettingsView()

        case .settingsBackup:
            BackupSettingsView()

        case .settingsChatPerformance:
            ChatPerformanceSettingsView()

        case .settingsPromptEditor:
            PromptEditorView()

        case .settingsAccessibility:
            PlaceholderView(
                title: "可访问性",
                detail: "降低透明度、增强对比度、减弱动态效果与动态字体已在全局生效；更多可访问性选项即将上线。",
                systemImage: "figure.arms.open"
            )

        case .placeholder(let title, let detail):
            PlaceholderView(title: title, detail: detail, systemImage: "hammer")
        }
    }
}

/// 占位页：路由已注册、页面待实现（Phase 3 路由表覆盖 A1/A2 主要路由，其余占位）
struct PlaceholderView: View {
    var title: String
    var detail: String
    var systemImage: String = "hammer"

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 16) {
                Image(systemName: systemImage)
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(GlassTokens.brandOrange)
                Text(title)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text(detail)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Text("更多内容即将上线")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(GlassTokens.bubbleSystem, in: Capsule())
            }
            .padding(24)
            .cardSurface(cornerRadius: 24)
            .padding(.horizontal, 24)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
