# 真机 WebDriverAgent 启动与面板显示修复（T41）

> ## ⚠️ 任务状态：中止（2026-11-23 09:39，用户决定自行实机测试）
>
> **用户决定在 iPhone 上自行实机测试，不需要电脑端手机镜像 / WDA。** 按 lead 指令停止 WDA 继续调试：
> 不再调用 `ios_real_start_wda`，未再跑任何 xcodebuild。
>
> 中止时的实际进度：
> - 插件侧 3 层根因 **已全部修复**（tooling 补齐 + 热重载、签名 Team、runner bundleId），`resolveWdaTooling` 已 available；
> - WDA runner **已成功编译、签名并安装到真机**（`com.rhodeslink.wda.a41f2.xctrunner`）；
> - 唯一剩余阻塞是 iOS 强制的「信任开发者证书」，用户选择不再继续，故**未做**截图/OCR/元素树/断言点击的闭环验证；
> - 按 lead 要求 **保留**插件 `lib/index.js:74` 的 teamId/bundleId 覆盖 patch 与热重载后的状态（插件现在对真机 tooling 是 available 的），如需恢复原文件见 `/tmp/dsh-ios-index.js.bak`。
> - 若日后要启用真机镜像：手机端「信任开发者证书」一次 → 重跑 `ios_real_start_wda` 即可，其余都已就绪。

- 日期：2026-11-23
- 设备：`Chen 的 iphone 17`（iPhone18,3，iOS 27.0.1 build 24A446）
  - hardware UDID：`00008150-001055D23EF3401C`（xcodebuild `-destination id=` / usbmux 用）
  - CoreDevice UDID：`78D58853-14E6-5980-8748-BE0E0EEAA2FF`（devicectl / `ios_sim_devices.realDevices` 用）
- 签名：Apple Development: `xsbcjx@gmail.com (GL3H4V49VR)`，真实 Team **`7PPJKDAPJZ`**
- 工具链：Xcode 27.2 (27B52)，iPhoneOS27.2 SDK
- 目标：在 dsh-ios 插件的真机面板显示画面并可交互（此前提示「需要运行 WebDriverAgent」）

---

## 0. 结论（TL;DR）

真机侧没有任何硬件/配对问题（`connected / paired / wired / developerMode=enabled / ddiServicesAvailable=true`）。
失败**全部来自宿主侧 dsh-ios 插件**，共 3 个叠加的根因，均已修复：

| # | 根因 | 修复 |
|---|------|------|
| 1 | 插件运行期缓存的 WDA tooling 过期：`WdaController` 在插件 mount 时**一次性**解析 tooling，当时 `~/Library/Caches/dsh-ios/wda/src`（WDA checkout）不存在、PATH 无 `iproxy` → `ios_real_start_wda` 永远报 `WDA tooling is unavailable` | ① 用 npm 包补 WDA 源码树；② `brew install libimobiledevice`；③ 热重载插件让 `WdaController` 重新解析 |
| 2 | 插件签名 Team 解析错误：从 keychain identity 字符串 `"... (GL3H4V49VR)"` 里把**证书 ID** 当成 Team ID，得到 `DEVELOPMENT_TEAM=GL3H4V49VR` → 自动签名必然失败 | 在插件 mount 处显式 `teamId: '7PPJKDAPJZ'` |
| 3 | WDA runner 默认 bundle id `com.finiyang.WebDriverAgentRunner` 的 App ID 已被其他开发者占用（App ID 全局唯一）→ `Failed Registering Bundle Identifier: ... ".xctrunner" cannot be registered` | 在插件 mount 处显式 `bundleId: 'com.rhodeslink.wda.a41f2'` |

修复后 `xcodebuild … test` 实测：**编译 ✅ / 自动签名 ✅ / 安装到真机 ✅**，唯一剩余阻塞是 iOS 强制的**手动信任开发者证书**：

```
The application could not be launched because the Developer App Certificate is not trusted.
```

该步骤无法自动化（见 §5），完成后 `ios_real_start_wda` 即可冷启动 WDA 并完成面板/交互验证。

---

## 1. 诊断过程（按时间顺序）

### 1.1 第一次调用：`WDA tooling is unavailable`

```
$ 工具 ios_real_start_wda(udid=00008150-001055D23EF3401C)
Error: ios_real_start_wda: WebDriverAgent is not available for "Chen 的 iphone 17"
  — WDA tooling is unavailable on this host
```

`lib/wda-host.js:240` 的 `resolveWdaTooling()` 要求三件事同时成立：

1. `<wdaProjectDir>/WebDriverAgent.xcodeproj` 存在（默认 `~/Library/Caches/dsh-ios/wda/src`）
2. `xcodebuild` 在 PATH
3. `iproxy` 在 PATH

实测本机：

- `~/Library/Caches/dsh-ios/` 只有 `bin/`、`runtime/`，**没有 `wda/`** → 缺 checkout
- `command -v xcodebuild` → `/usr/bin/xcodebuild` ✅
- `command -v iproxy` → 空 ❌

**注意（插件文档明确）**：README「Real iPhone」段写明 WDA checkout 需要用户自备，插件 **「never downloads or clones anything」**。任务简报里「首次运行会下载 WebDriverAgent」与实现不符。

### 1.2 补齐 WDA checkout（插件钉住的 commit）

插件安全补丁锚点是对着 **appium/WebDriverAgent commit `6e2b5c026f728aed9098481e756b458db87d1272`**（= npm 包 `appium-webdriveragent@16.12.1`）验证的（`~/.dsh/profiles/desktop/node_modules/@zseven-w/dsh-ios/lib/wda-physical-stage.js` 里 `PHYSICAL_WDA_DEFAULT_PATCHES`）。

网络现状：`api.github.com`/`registry.npmjs.org`/`registry.npmmirror.com` 可达，但 `github.com`、`raw.githubusercontent.com` 被墙（git clone 卡在 80 KB）。因此改用 **npm registry 拉官方源码包**：

```bash
curl -sL https://registry.npmmirror.com/appium-webdriveragent/-/appium-webdriveragent-16.12.1.tgz -o /tmp/wda-npm/wda.tgz
mkdir -p ~/Library/Caches/dsh-ios/wda/src
tar xzf /tmp/wda-npm/wda.tgz -C /tmp/wda-npm
cp -R /tmp/wda-npm/package/. ~/Library/Caches/dsh-ios/wda/src/
```

npm 包 `files` 白名单包含 `WebDriverAgent.xcodeproj` / `WebDriverAgentLib` / `WebDriverAgentRunner` / `Configurations` / `PrivateHeaders` / `Scripts`，**足以构建**（WDA 16.x 已自包含，不再依赖 Carthage/SPM；`FBHTTPServer.m` 自带 HTTP server）。

校验 staging（插件会把源码 stage 成 loopback-only 安全副本）：

```js
// node --input-type=module -e
import { resolvePhysicalWdaProjectDir } from '<plugin>/lib/wda-physical-stage.js';
await resolvePhysicalWdaProjectDir();
// → stageDir: ~/Library/Caches/dsh-ios/wda-physical-safe/sources/physical-wda-ddcb892eebde8fd6-9054c39120275579/
//   patches: bindingIPAddress-unconditional-loopback, mjpeg-broadcaster-loopback-only
```

两个安全补丁锚点（`FBConfiguration.m` 的 `bindingIPAddress`、`FBWebServer.m` 的 `screenshotsBroadcaster`）与 16.12.1 源码**逐字匹配**，staging 成功。

### 1.3 补 `iproxy`

```bash
HOMEBREW_NO_AUTO_UPDATE=1 brew install libimobiledevice    # 提供 /opt/homebrew/bin/iproxy 1.4.0_2
```

> 坑：直接 `brew install` 会先跑 Homebrew auto-update，而 auto-update 走 github.com（被墙）会长时间挂住；必须加 `HOMEBREW_NO_AUTO_UPDATE=1`。
> DSH 主进程的 PATH 已包含 `/opt/homebrew/bin`（`ps eww` 验证），无需额外配置。

**已知小出入（不影响主路径）**：本机 iproxy 1.4.0 的 CLI 是 `iproxy LOCAL:DEVICE [-u UDID]`，而插件 `assembleIproxyArgs()` 用的是**位置参数** `iproxy <local> <device> <udid>`（作者机器上是另一份 `usbmuxd-flutter` 版）。好在插件**优先用内置的原生 usbmux 转发器**（`lib/usbmux.js`，直接说 `/var/run/usbmuxd` 协议、按 USB 记录钉设备），`iproxy` 只是 fallback，且 tooling 检测只要求它在 PATH 上。所以本机不受影响。

### 1.4 根因 1 的关键：tooling 是插件 mount 时一次性解析的

```
~/.dsh/profiles/desktop/node_modules/@zseven-w/dsh-ios/lib/index.js:74
    const wda = new WdaController({ idleTimeoutMs: 0 });
        → lib/wda-host.js:1019  this.tooling = resolveWdaTooling(...)
```

源码/iproxy 补齐后，**运行中的进程仍是旧值**（`available=false`）。验证：补完文件后再调 `ios_real_start_wda` 仍报 `unavailable`。

**进程内热重载**（不重启 DSH、不丢会话）：

```
plugin_manager set_plugin(target="include:dsh-ios", enabled=false)
plugin_manager set_plugin(target="include:dsh-ios", enabled=true)
```

实测该操作会**重新 import 插件入口**（`lib/index.js` 的改动会生效，见 §2 的探针实验），并重建 `WdaController`/`SimHostController`。副作用：模拟器面板流中断数秒后自动重建；热重载瞬间 skill 目录会短暂移除 `ios-ui-automation`。

### 1.5 重载后新的失败：`build-failed`（两个签名根因）

重载后 tooling 变为 available，`ios_real_start_wda` 开始真正跑 `xcodebuild`，但 ~30–60 s 内失败：

```
Error: ios_real_start_wda: ... the WebDriverAgentRunner build failed — fix the error and re-run
```

插件把 xcodebuild 原始输出吞进 stderr ring，工具错误只给归类后的 reason。**复现命令**（在 stage 目录内，与插件完全同参）：

```bash
cd ~/Library/Caches/dsh-ios/wda-physical-safe/sources/physical-wda-ddcb892eebde8fd6-9054c39120275579
xcodebuild -project WebDriverAgent.xcodeproj -scheme WebDriverAgentRunner \
  -destination 'id=00008150-001055D23EF3401C' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=7PPJKDAPJZ CODE_SIGN_STYLE=Automatic \
  PRODUCT_BUNDLE_IDENTIFIER=com.rhodeslink.wda.a41f2 test
```

第一次复现（用插件默认值 `DEVELOPMENT_TEAM` 由插件自己解析 / `com.finiyang.WebDriverAgentRunner`）得到：

```
error: Failed Registering Bundle Identifier: The app identifier
  "com.finiyang.WebDriverAgentRunner.xctrunner" cannot be registered to your
  development team because it is not available. Change your bundle identifier ...
error: No profiles for 'com.finiyang.WebDriverAgentRunner.xctrunner' were found
```

→ 根因 3：App ID **全局唯一**，`com.finiyang.WebDriverAgentRunner.xctrunner` 已被别的开发者占用（这是插件作者留下的默认值，插件为此提供了 `DSHPLUGIN_IOS_WDA_BUNDLE_ID` 覆盖口）。

同时用 Node 直接调用插件的签名解析函数，抓到根因 2：

```js
import { resolveSigningTeam, detectAppleDevelopmentIdentities } from '<plugin>/lib/devicectl.js';
await detectAppleDevelopmentIdentities();
// → [{ name: "Apple Development: xsbcjx@gmail.com (GL3H4V49VR)", teamId: "GL3H4V49VR" }]   ← 错！
await resolveSigningTeam({ fallback: '5CHT5RB9C3' });
// → { teamId: "GL3H4V49VR", source: "identity" }                                             ← 错！
```

`GL3H4V49VR` 是**证书 ID**（identity 名字括号里的值），真实 Team ID 是 **7PPJKDAPJZ**（可用 `security cms -D -i ~/Library/Developer/Xcode/UserData/Provisioning Profiles/*.mobileprovision` 的 `TeamIdentifier` 佐证，RhodesLink 的 device profile 就是 `7PPJKDAPJZ.com.rhodeslink.app`）。
插件只在「显式 option / 显式 env」时才跳过这个启发式，否则会拿 GL3H4V49VR 去签名 → 自动签名必然失败。
（`~/.dsh/.env` / 进程 env 改不动运行中进程，因此直接在插件 mount 处传 option。）

### 1.6 修复落点（最小侵入，可回滚）

改 `~/.dsh/profiles/desktop/node_modules/@zseven-w/dsh-ios/lib/index.js` 第 74 行（**只此一处**）：

```diff
-    const wda = new WdaController({ idleTimeoutMs: 0 });
+    const wda = new WdaController({ idleTimeoutMs: 0, teamId: '7PPJKDAPJZ', bundleId: 'com.rhodeslink.wda.a41f2' });
```

原文件已备份：`/tmp/dsh-ios-index.js.bak`（`wda-host.js` 未改动，备份在 `/tmp/dsh-ios-wda-host.js.bak`）。
`WdaController` 的 `options.teamId/bundleId` 是插件原生支持的参数（对应 `-allowProvisioningUpdates DEVELOPMENT_TEAM=… PRODUCT_BUNDLE_IDENTIFIER=…`），因此这等价于 README 里推荐的 `DSHPLUGIN_IOS_TEAM_ID` + `DSHPLUGIN_IOS_WDA_BUNDLE_ID` 环境变量（只是运行中的进程 env 改不了，先用代码级等价覆盖）。

> 探针实验佐证「热重载会重新执行 index.js」：先把 `wdaProjectDir` 改成不存在的 `/tmp/wda-probe-path` 并热重载，`ios_real_start_wda` 立刻从 `build-failed` 变回 `unavailable`；改回后再次热重载恢复正常。所以上面的 patch 只要重载一次即生效。

### 1.7 修复后实测：编译/签名/安装全过

同参 xcodebuild（`/tmp/wda-build2.log`）关键输出：

```
Signing Identity:     "Apple Development: xsbcjx@gmail.com (GL3H4V49VR)"
Provisioning Profile: "iOS Team Provisioning Profile: com.rhodeslink.wda.a41f2.xctrunner"
...
** TEST FAILED **
Testing failed:
  WebDriverAgentRunner-Runner encountered an error (Failed to install or launch the test runner.
  (Underlying Error: The application could not be launched because the Developer App Certificate is not trusted.)
```

- 自动签名成功：Xcode 自动注册了新的 App ID `com.rhodeslink.wda.xctrunner`（team 7PPJKDAPJZ），生成并使用了 `iOS Team Provisioning Profile: com.rhodeslink.wda.a41f2.xctrunner`
- 安装成功：`ios_sim_list_apps(真机)` 已能看到
  `com.rhodeslink.wda.a41f2.xctrunner` / `WebDriverAgentRunner-Runner` v1.0
- 仅剩**启动**被 iOS 安全策略拒绝 —— 开发者证书未在设备上信任（`cert-untrusted`，插件同样有该归类与修复提示）

构建耗时：冷构建约 **4–5 分钟**（WDA scheme 带 `Analyze`/静态分析阶段，比预想慢；增量构建 1 分钟内）。

---

## 2. 复盘：为什么最初面板提示「需要运行 WebDriverAgent」

插件在 `ios_sim_screenshot` / `ios_sim_interact` / `ios_sim_ui_tree` 等真机路径上，若 WDA 未 running 会返回
`run ios_real_start_wda first (a cold build can take minutes)` 之类的 hint；但此前**连 tooling 本身都 unavailable**，
所以任何真机工具都失败、面板也拿不到 MJPEG 流。三层根因修掉两层半之后，面板缺的只是「WDA 真正跑起来」。

---

## 3. 复现/操作手册（以后重装或插件升级后照此做）

```bash
# 1) WDA 源码树（插件不会自动下载）。github 被墙时用 npm registry 镜像
mkdir -p ~/Library/Caches/dsh-ios/wda/src
curl -sL https://registry.npmmirror.com/appium-webdriveragent/-/appium-webdriveragent-16.12.1.tgz -o /tmp/wda.tgz
mkdir -p /tmp/wda && tar xzf /tmp/wda.tgz -C /tmp/wda && cp -R /tmp/wda/package/. ~/Library/Caches/dsh-ios/wda/src/

# 2) iproxy（tooling 检测要求它在 PATH 上；实际转发优先走插件内置原生 usbmux）
HOMEBREW_NO_AUTO_UPDATE=1 brew install libimobiledevice

# 3) 检查三件套
test -d ~/Library/Caches/dsh-ios/wda/src/WebDriverAgent.xcodeproj && echo checkout OK
command -v xcodebuild iproxy

# 4) 签名/bundle id 覆盖（二选一）
#    A. 长期正解：把下面两行放进 DSH 启动环境（~/.dsh/.env 或 shell 环境），重启 DSH
#       DSHPLUGIN_IOS_TEAM_ID=7PPJKDAPJZ
#       DSHPLUGIN_IOS_WDA_BUNDLE_ID=com.rhodeslink.wda.a41f2
#    B. 不重启的等价做法：改插件 mount 行并在 plugin_manager 里 disable→enable 热重载（本任务采用）

# 5) 真机：解锁 + 信任开发者证书（见 §5），然后
#    ios_real_start_wda(udid=00008150-001055D23EF3401C)
```

验签失败的通用复现命令（打印原始 xcodebuild 输出，定位签名/注册问题）：

```bash
cd ~/Library/Caches/dsh-ios/wda-physical-safe/sources/physical-wda-*/ 
xcodebuild -project WebDriverAgent.xcodeproj -scheme WebDriverAgentRunner \
  -destination 'id=00008150-001055D23EF3401C' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=7PPJKDAPJZ CODE_SIGN_STYLE=Automatic \
  PRODUCT_BUNDLE_IDENTIFIER=com.rhodeslink.wda.a41f2 test 2>&1 | tee /tmp/wda-build.log
```

---

## 4. 真机闭环验证

### 4.1 前置状态（已确认）

```
$ xcrun devicectl list devices
Chen 的 iphone 17   00008150-001055D23EF3401C (UDID)   connected   iPhone 17 (iPhone18,3)   physical
$ ios_sim_devices → realDevices
{ "name":"Chen 的 iphone 17", "hardwareUdid":"00008150-001055D23EF3401C",
  "state":"available (paired)", "connection":"wired", "tunnelState":"connected",
  "developerMode":"enabled", "ddiServicesAvailable":true, "osVersion":"27.0.1" }
```

### 4.2 待验证项（因任务中止，未执行）

> **中止说明：用户决定不在电脑端做手机镜像，未再启动 WDA，故本节全部验证未执行。** 保留原验证清单供日后启用时执行。

| 验证 | 工具 | 期望 |
|------|------|------|
| 画面可见 | `ios_sim_screenshot(udid=…)` | 返回 PNG path + 设备尺寸（真机像素） |
| OCR + 设备点坐标 | `ios_sim_find_text(udid=…)` | items[].rect 为 point 坐标 |
| 元素树 | `ios_sim_ui_tree(udid=…)` | 可读层级 + screenPoints |
| 可交互断言 | `ios_sim_interact` 或 `ios_sim_tap_element(expect_text/expect_gone)` | `expected.matched=true` |
| 面板流 | 真机面板 | MJPEG 9100 经 usbmux 隧道出画面 |

> 当前状态：**签名/安装已通，启动被 iOS 的证书信任策略拦住**（见 §5）。完成 §5 后重跑 `ios_real_start_wda` 即进入本节验证。

（本节在用户完成信任步骤后回填实测证据。**当前任务已中止，未回填。**）

---

## 5. 需要用户手动完成的步骤（唯一剩余阻塞，用户选择不做）

> 用户已选择自行在 iPhone 上测试、不需要电脑端镜像，因此本节步骤**未执行**，属于「日后如需启用再操作」。

iPhone 上（iOS 强制，无法用命令/自动化完成）：

1. 解锁 `Chen 的 iphone 17`，保持解锁（WDA 不能在锁屏下启动）；
2. **设置 → 通用 → VPN与设备管理 → 开发者 App（Developer App）** → 选择
   `Apple Development: xsbcjx@gmail.com` → **信任 / 信任此开发者**；
   （WDA runner 已安装，证书条目现在已出现；英文路径：Settings → General → VPN & Device Management → Developer App → Trust）
3. 建议：**设置 → 显示与亮度 → 自动锁定 → 永不**，避免长会话锁屏导致 WDA 中断。

完成后回一句「已信任」，重跑 `ios_real_start_wda` 即可（增量构建，1–2 分钟）。

> 备注：免费/个人开发者证书签名的 app 每次到期（7 天）后需要重新签名（`profile-expired` 归类），重跑 `ios_real_start_wda` 即可；证书信任本身只需做一次。

---

## 6. 后续自动化建议

1. **长期正解**：把
   `DSHPLUGIN_IOS_TEAM_ID=7PPJKDAPJZ`、`DSHPLUGIN_IOS_WDA_BUNDLE_ID=com.rhodeslink.wda.a41f2`
   写进 DSH 启动环境（`~/.dsh/.env` 或 launchd 环境），并**撤销对 `node_modules/@zseven-w/dsh-ios/lib/index.js` 的本地 patch**（插件升级会覆盖它；两个 patch 文件备份在 `/tmp/dsh-ios-*.bak`）。
   注意 DSH 保留 `DSH_` 前缀，必须用 `DSHPLUGIN_`。
2. 把 `~/Library/Caches/dsh-ios/wda/src` 作为项目 bootstrap 的一步（`ios-port/scripts/` 里加个脚本，用 npmmirror 兜底 github），避免换机/清缓存后再次 unavailable。
3. WDA tooling 是插件 mount 时一次性解析的：以后凡补齐了 WDA checkout / iproxy，都需要 `plugin_manager` 对 `include:dsh-ios` disable→enable 热重载一次（或重启 DSH），否则 `ios_real_start_wda` 一直报 `WDA tooling is unavailable` —— 这个坑建议反馈给插件作者（tooling 应惰性解析/可刷新）。
4. 向上游反馈两个插件 bug：① `detectAppleDevelopmentIdentities` 把证书 ID 当 Team ID（identity 名字括号里的值是 cert ID；team 应从 cert 的 OU 或 Xcode 账号团队解析）；② 默认 WDA bundle id `com.finiyang.WebDriverAgentRunner` 的 App ID 已被人占用，应改用可配置的、默认带随机后缀的 bundle id。
5. 真机 UI 自动化纪律：每次 tap 都带 `expect_text`/`expect_gone` 或 `ios_sim_ui_rows` + `expect_count`，不靠截图 diff；真机上不要点无法识别的控件。

---

## 7. 证据文件 / 路径清单

| 内容 | 路径 |
|------|------|
| WDA 源码树（npm 16.12.1 = commit 6e2b5c0） | `~/Library/Caches/dsh-ios/wda/src` |
| 插件安全 staging（loopback-only 补丁） | `~/Library/Caches/dsh-ios/wda-physical-safe/sources/physical-wda-ddcb892eebde8fd6-9054c39120275579` |
| 插件 patch 后的 mount 行 | `~/.dsh/profiles/desktop/node_modules/@zseven-w/dsh-ios/lib/index.js:74` |
| 原文件备份 | `/tmp/dsh-ios-index.js.bak`、`/tmp/dsh-ios-wda-host.js.bak` |
| 复现构建日志（旧 bundle id 失败） | `/tmp/wda-build.log` |
| 复现构建日志（新 bundle id，编译/签名/安装通过，启动被证书信任拦） | `/tmp/wda-build2.log` |
| 已安装到真机的 runner | `com.rhodeslink.wda.a41f2.xctrunner`（WebDriverAgentRunner-Runner 1.0） |
| Xcode DerivedData | `~/Library/Developer/Xcode/DerivedData/WebDriverAgent-etdkxpamultslkdficxoymczoxix`、`…-ajvrnypvebqmugcsjjwwgsemquyb` |
