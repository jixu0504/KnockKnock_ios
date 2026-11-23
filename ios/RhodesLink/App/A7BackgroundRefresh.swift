//
//  A7BackgroundRefresh.swift
//  T56：A7 后台兜底通道（BGAppRefreshTask，可选路径）。
//
//  口径（T13 实测 + A7 spec §A-12）：
//  - BGTaskScheduler 在 iOS 模拟器上 submit 一律失败（BGTaskSchedulerErrorDomain Code=1
//    "not available on this platform"）→ 自动降级为「前台 tick + 启动/回前台 catch-up」；
//  - 真机上系统调用 launchHandler 时，再跑一次与前台完全相同的 catch-up（同执行体、同唯一键、
//    同租约，保证语义一致），完成后 setTaskCompleted；
//  - expirationHandler 取消当前结算协程（层不中断已 running 执行体：与 Android cancel 语义一致）。
//

import BackgroundTasks
import Foundation

enum A7BackgroundRefresh {
    /// Info.plist `BGTaskSchedulerPermittedIdentifiers` 必须包含该标识符。
    static let taskIdentifier = "com.rhodeslink.app.a7.refresh"

    /// App 启动时调用（必须在 `applicationDidFinishLaunching` 前/早期注册）。
    static func register() {
        let registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier,
            using: nil
        ) { task in
            handle(task: task)
        }
        NSLog("[RhodesLink][A7][BG] register id=%@ ok=%d", taskIdentifier, registered ? 1 : 0)
    }

    /// 请求下一次后台刷新（机会型；失败只记日志，不影响前台补算）。
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
            NSLog("[RhodesLink][A7][BG] submit ok earliest=+900s")
        } catch {
            NSLog("[RhodesLink][A7][BG] submit failed（降级前台补算）err=%@", error.localizedDescription)
        }
    }

    private static func handle(task: BGTask) {
        guard let refresh = task as? BGAppRefreshTask else {
            task.setTaskCompleted(success: false)
            return
        }
        NSLog("[RhodesLink][A7][BG] launchHandler fired id=%@", taskIdentifier)
        // 先排下一次，避免本次失败后计划断档。
        schedule()
        let work = Task {
            let executed = await AppGraphClient.shared.a7CatchUp()
            NSLog("[RhodesLink][A7][BG] catch-up finished executed=%d", executed)
            refresh.setTaskCompleted(success: true)
        }
        refresh.expirationHandler = {
            NSLog("[RhodesLink][A7][BG] expired（任务被系统回收，前台补算兜底）")
            work.cancel()
        }
    }
}
