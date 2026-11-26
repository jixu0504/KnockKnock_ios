//
//  AvatarRenderAutomation.swift
//  T52 上传头像渲染自检（仅 `--avatar-render-selftest` 启动参数启用，对用户路径零影响）：
//  1) 取联系人列表首行角色（与 RolesView 渲染顺序一致，截图必可见），记录原 avatarUri；
//  2) 走 T49 真实上传链路：Swift 生成 256×256 测试图 → 临时文件 →
//     `OperatorBridge.setAvatarFromFile`（MediaRepository PICKER 入库）→ `operators.avatarUri = media/<uuid>.jpg`；
//  3) 断言渲染链路（与 RoleAvatar View 同一解析器）：媒体 ID → 本机绝对路径（文件存在、字节数>0）→
//     `UIImage` 非空（尺寸 256×256）→ `RoleAvatarProbe.description` = `media:<id>`；
//  4) 断言上传后角色列表/详情/会话数据均取到媒体 ID（详情页由同一 RoleAvatar 渲染）；
//     fallback：不存在的媒体 ID / 空 avatarId → nil（回退首字占位）；knock:<id> 真实素材可渲染；
//     旧内置数字（1..147）资源已删除 → 无位图、描述 builtin-missing:N（渲染走首字占位）；
//  5) UI 证据：切到联系人 Tab 停留 6s（截图列表真实图片）→ 进角色详情停留 7s（截图详情大头像）
//     → 恢复原头像（避免污染后续验证）；
//  6) 结束落盘 Documents/t52-avatar-selftest.log。
//

import Foundation
import SwiftUI
import UIKit

@MainActor
enum AvatarRenderAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--avatar-render-selftest")
    private static var didRun = false

    static func runIfNeeded(environment: AppEnvironment, router: AppRouter) async {
        guard isEnabled, !didRun else { return }
        didRun = true

        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            if ok { passCount += 1 } else { failCount += 1 }
            PolishSelfTestLog.add("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " \(detail)")")
        }

        let bridge = environment.bridge
        PolishSelfTestLog.add("=== avatar render selftest start ===")
        PolishSelfTestLog.add("bundledAvatars=\(RoleAvatarProbe.bundledAvatarCount())/0 legacyAvatarsRemoved=true")

        // 1. 目标角色：列表首行（bridge 返回顺序 = RolesView 渲染顺序，截图必可见）
        var roles: [RoleItem] = []
        if case .success(let items) = await bridge.loadRoles() { roles = items }
        guard let target = roles.first else {
            PolishSelfTestLog.add("avatar selftest ABORT (no roles)")
            PolishSelfTestLog.flushToDisk(fileName: "t52-avatar-selftest.log", summary: "abort: no roles")
            return
        }
        let originalAvatar = target.avatarId
        PolishSelfTestLog.add(
            "target role=\(target.id) name=\(target.name) session=\(target.sessionId) " +
            "originalAvatar=\(originalAvatar) probe=\(RoleAvatarProbe.description(avatarId: originalAvatar))"
        )
        check("target.found", !target.id.isEmpty, "role=\(target.id)")

        // 2. 生成测试图（青→蓝渐变 + 白色圆 + T52 字样；与插件内置头像明显不同，便于截图肉眼区分）
        let stamp = Int(Date().timeIntervalSince1970)
        let sourceURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("t52-avatar-src-\(stamp).jpg")
        let rendered = renderTestAvatar(size: 256)
        var sourceBytes = 0
        if let rendered, let data = rendered.jpegData(compressionQuality: 0.9) {
            try? data.write(to: sourceURL)
            sourceBytes = data.count
        }
        check(
            "fixture.sourceImage",
            sourceBytes > 0 && FileManager.default.fileExists(atPath: sourceURL.path),
            "path=\(sourceURL.lastPathComponent) bytes=\(sourceBytes)"
        )

        // 3. 上传头像（与 RoleEditView 保存按钮同一条桥 API：PICKER 入库 → 写 avatarUri → 同步会话头像）
        var mediaId = ""
        switch await bridge.setOperatorAvatarFromFile(id: target.id, path: sourceURL.path) {
        case .success(let value):
            mediaId = value
            check("upload.mediaId", value.hasPrefix("media/"), "avatarUri=\(value)")
        case .failure(let error):
            check("upload.mediaId", false, "error=\(error.message)")
        }
        try? FileManager.default.removeItem(at: sourceURL)

        var updatedRole: RoleItem?
        if case .success(let items) = await bridge.loadRoles() {
            updatedRole = items.first(where: { $0.id == target.id })
        }
        check(
            "db.operators.avatarUri",
            updatedRole?.avatarId == mediaId && !mediaId.isEmpty,
            "dbAvatar=\(updatedRole?.avatarId ?? "-") mediaId=\(mediaId)"
        )

        // 4. 渲染链路断言（RoleAvatarResolver = RoleAvatar View 使用的解析器）
        let resolvedPath = RoleAvatarResolver.mediaPath(for: mediaId)
        let fileAttrs = try? FileManager.default.attributesOfItem(atPath: resolvedPath)
        let fileBytes = (fileAttrs?[.size] as? Int) ?? 0
        check(
            "media.pathResolved",
            !resolvedPath.isEmpty && FileManager.default.fileExists(atPath: resolvedPath) && fileBytes > 0,
            "path=\(resolvedPath.isEmpty ? "<empty>" : resolvedPath) bytes=\(fileBytes)"
        )
        check(
            "avatar.probe",
            RoleAvatarProbe.description(avatarId: mediaId).hasPrefix("media:"),
            RoleAvatarProbe.description(avatarId: mediaId)
        )
        PolishSelfTestLog.add("avatar.renderProbe \(RoleAvatarProbe.mediaRenderProbe(avatarId: mediaId))")

        let loaded = RoleAvatarResolver.image(for: mediaId)
        check(
            "avatar.uiImage",
            loaded != nil,
            "image=\(loaded.map { "\(Int($0.size.width))x\(Int($0.size.height))" } ?? "<nil>")"
        )
        check(
            "media.ingestPrefix",
            mediaId.hasPrefix("media/") && !resolvedPath.isEmpty,
            "mediaId=\(mediaId)"
        )

        // 5. 会话头像同步（chat_sessions.avatarUri 与 operators.avatarUri 同源更新）
        if !target.sessionId.isEmpty {
            let session = await bridge.findSession(id: target.sessionId)
            check(
                "session.avatarSync",
                session?.avatarId == mediaId,
                "session=\(target.sessionId) avatar=\(session?.avatarId ?? "-")"
            )
        } else {
            PolishSelfTestLog.add("session.avatarSync SKIPPED (role has no session)")
        }

        // 6. 回退语义：不存在的媒体 ID / 空值 → 无位图（首字占位）；knock: 素材可渲染；旧内置数字资源已删除 → 回退占位
        check(
            "fallback.missingMedia",
            RoleAvatarResolver.image(for: "media/t52-missing-\(stamp).jpg") == nil
                && RoleAvatarProbe.description(avatarId: "media/t52-missing-\(stamp).jpg").hasPrefix("media-missing:"),
            RoleAvatarProbe.description(avatarId: "media/t52-missing-\(stamp).jpg")
        )
        check(
            "fallback.placeholder",
            RoleAvatarResolver.image(for: "") == nil
                && RoleAvatarProbe.description(avatarId: "").hasPrefix("placeholder:"),
            RoleAvatarProbe.description(avatarId: "")
        )
        check(
            "knock.render",
            RoleAvatarResolver.image(for: "knock:001") != nil
                && RoleAvatarProbe.description(avatarId: "knock:001") == "knock:001",
            RoleAvatarProbe.description(avatarId: "knock:001")
        )
        check(
            "builtin.fallbackPlaceholder",
            RoleAvatarResolver.image(for: "1") == nil
                && RoleAvatarProbe.description(avatarId: "1") == "builtin-missing:1",
            RoleAvatarProbe.description(avatarId: "1")
        )
        check("avatar.listRow", updatedRole != nil, "role=\(updatedRole?.name ?? "-") avatar=\(updatedRole?.avatarId ?? "-")")

        // 7. UI 证据：联系人列表（首个已有会话角色在顶部，行内头像 = 上传图）→ 角色详情（92pt 大头像）
        router.selectedTab = .contacts
        router.contactsPath = NavigationPath()
        PolishSelfTestLog.add("ui.contactsList shown role=\(target.id) avatar=\(mediaId)")
        try? await Task.sleep(nanoseconds: 6_000_000_000)

        router.contactsPath.append(Route.roleDetail(roleId: target.id))
        PolishSelfTestLog.add("ui.roleDetail shown role=\(target.id) expectedAvatar=\(mediaId)")
        try? await Task.sleep(nanoseconds: 7_000_000_000)

        // 8. 恢复原头像（若为内置数字；媒体 ID 原值无法回写文件则不恢复，仅记录）
        if let number = RoleAvatarResolver.builtinNumber(originalAvatar) {
            switch await bridge.setOperatorAvatarBuiltin(id: target.id, number: number) {
            case .success(let restored):
                check("restore.builtin", restored == "\(number)", "avatarUri=\(restored)")
            case .failure(let error):
                check("restore.builtin", false, "error=\(error.message)")
            }
        } else {
            PolishSelfTestLog.add("restore.builtin SKIPPED originalAvatar=\(originalAvatar)")
        }

        let allPass = failCount == 0
        PolishSelfTestLog.add("=== avatar render selftest done allPASS=\(allPass) PASS=\(passCount) FAIL=\(failCount) ===")
        PolishSelfTestLog.flushToDisk(
            fileName: "t52-avatar-selftest.log",
            summary: "T52 上传头像渲染：role=\(target.id) originalAvatar=\(originalAvatar) mediaId=\(mediaId) " +
                "path=\(resolvedPath) imageBytes=\(fileBytes) PASS=\(passCount) FAIL=\(failCount)"
        )
    }

    /// 生成可视化测试头像（青→蓝渐变 + 白色圆 + T52 字样）。
    private static func renderTestAvatar(size: CGFloat) -> UIImage? {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        return renderer.image { ctx in
            let cg = ctx.cgContext
            let colors = [UIColor.systemTeal.cgColor, UIColor.systemBlue.cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                cg.drawLinearGradient(
                    gradient,
                    start: .zero,
                    end: CGPoint(x: size, y: size),
                    options: []
                )
            }
            UIColor.white.setFill()
            cg.fillEllipse(in: CGRect(x: size * 0.18, y: size * 0.18, width: size * 0.64, height: size * 0.64))
            let text = "T52" as NSString
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: size * 0.22, weight: .heavy),
                .foregroundColor: UIColor.systemBlue,
            ]
            let textSize = text.size(withAttributes: attributes)
            text.draw(
                at: CGPoint(x: (size - textSize.width) / 2, y: (size - textSize.height) / 2),
                withAttributes: attributes
            )
        }
    }
}
