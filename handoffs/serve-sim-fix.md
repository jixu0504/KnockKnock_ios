# T39 — dsh-ios 模拟器「画面不可用 / 无法交互」修复记录

- 代理: fix-serve-sim
- 时间: 2026-11-23 08:25–08:40 (Asia/Shanghai)
- 设备: iPhone 17 `A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA` (iOS 27.2 runtime, Booted)
- 目标: `ios_sim_boot` 实时画面在 DSH GUI 面板显示 + `ios_sim_interact` / `ios_sim_find_text` / `ios_sim_ui_tree` 全部可用
- 结论: **修复完成，闭环验证通过**（全部为环境类修复，未改 DSH 插件代码、未改 RhodesLink 源码）

## 1. 症状（lead 实测）

1. `ios_sim_boot` 返回 `streaming: true`，但 GUI 面板画面不可用/空白。
2. `ios_sim_interact action=tap` 报错:
   `serve-sim tap failed: ... Failed to connect to serve-sim server at ws://127.0.0.1:3181/helper/A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA/ws`
3. `ios_sim_find_text` 返回 note: `the simulator point size could not be resolved (AXe helper unavailable or describe-ui failed) — size and rects are reported in image pixels instead of points`
4. `ios_sim_screenshot` 正常（走 `simctl io screenshot`）。

## 2. 诊断过程与发现

### 2.1 流进程与端口（看起来都正常，但实际是死的）

```
$ pgrep -fl "serve-sim|Simulator|CoreSimulator"
$ lsof -nP -iTCP:3181 -sTCP:LISTEN
COMMAND   PID     USER   FD   TYPE ... NAME
node    44557 jackchen   12u  IPv4 ... TCP 127.0.0.1:3181 (LISTEN)
$ nc -z 127.0.0.1 3181   # succeeded
```

两个 serve-sim 进程存在（父 CLI + 子 helper）：

```
44554 63645  node serve-sim.js --no-preview --quiet --port 3181 A63CD17A-...
44557 44554  /Users/jackchen/.dsh/dsh-runtimes/.../node serve-sim.js A63CD17A-... --port 3181 --host 127.0.0.1 --exit-on-simulator-shutdown
```

→ 说明「进程活着 + 端口 LISTEN + handshake 打印成功」并不意味着 device session 活着。

### 2.2 关键一击：自己打 serve-sim 的 device helper 路由全是 404

```
$ curl -sS -i "http://127.0.0.1:3181/helper/A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA/health"
HTTP/1.1 404 Not Found
No serve-sim device

$ curl ... /helper/<udid>/stream.mjpeg   → 404 (面板拿不到任何帧)
$ websocket upgrade /helper/<udid>/ws    → HTTP/1.1 404
```

`/helper/<设备>/…` 路由由 `serve-sim` 的 in-process `DeviceSession` 提供；`No serve-sim device` 表示
`serveHelperInProcess()` 里 `getDeviceSession(device)` 同步抛错被 catch 吞掉
（serve-sim `src/middleware.ts` / `src/device-session.ts`；`getDeviceSession` 惰性创建
`NativeCapture`/`NativeHid`）。截图走 simctl，所以 3) 中截图正常，但流/输入全废。

### 2.3 根因 1（画面/tap 全挂）：serve-sim native addon 无法在 DSH runtime node 里加载

用 DSH runtime node 直接 require addon：

```
$ node -e 'require("/Users/jackchen/.dsh/profiles/desktop/node_modules/serve-sim/dist/native/serve-sim-native.node")'
LOAD ERROR: dlopen(...serve-sim-native.node, 0x0001): ...
  (code signature in <9C7BA5A2-...> '...serve-sim-native.node' not valid for use in process:
   mapping process and mapped file (non-platform) have different Team IDs)
```

签名对比：

| 二进制 | 签名 | entitlements |
|---|---|---|
| `~/.dsh/.../dependencies/node/bin/node`（DSH runtime node，被 serve-sim shebang `#!/usr/bin/env node` 解析使用） | `flags=0x10000(runtime)`，Authority=Developer ID … **NAN929V4UM** | 只有 `com.apple.security.cs.allow-jit`，**缺 `com.apple.security.cs.disable-library-validation`** |
| `serve-sim-native.node`（serve-sim 0.1.49 自带） | `flags=0x2(adhoc)`，Signature=adhoc，无 TeamID | — |
| DSH 宿主 app 二进制 | `flags=0x10000(runtime)` | 有 `allow-jit` + `allow-unsigned-executable-memory` + **`disable-library-validation`** |

即：hardened runtime 的 runtime node 开启了 library validation，而 addon 是 ad-hoc 签名（TeamID 不同），
dlopen 被拒。**DSH 打包缺陷**：宿主 app 有 `disable-library-validation`，`dependencies/node` 副本没有。
（lead 之前修的 `env: node: No such file or directory` 只解决了 shebang 找不到 node，没解决 addon 加载。）

后果链：`NativeCapture` 构造抛错 → `/helper/…` 全部 404 →
`stream.mjpeg` 404（面板空白/不刷新）→ `/ws` 404（`ios_sim_interact` 的 tap/gesture 报
`Failed to connect to serve-sim server at ws://…`）。

### 2.4 根因 2（find_text 尺寸退化）：AXe helper 未安装

```
$ ls -la ~/Library/Caches/dsh-ios/bin/     # 只有 ocr/，没有 axe/
$ which axe; ls /opt/homebrew/bin/axe /usr/local/bin/axe   # 都没有
```

插件 `simPointSize()` 用 AXe `describe-ui` 的 app root frame 解析点尺寸，
解析失败就回退成图片像素并加 note（即症状 3）。

## 3. 修复动作（全部环境类，可回滚）

### 3.1 重签 runtime node 副本，允许加载 ad-hoc 原生 addon

不修改 `/Applications/DeepSeek Harness.app` 与 `~/.dsh/dsh-runtimes` 里的原文件，只做副本 + 重签：

```bash
NODE=/Users/jackchen/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin/node
LIB=/Users/jackchen/.local/lib/dsh-ios-node
mkdir -p "$LIB" ~/Library/Caches/dsh-ios/runtime
cp "$NODE" "$LIB/node" && chmod +x "$LIB/node"

cat > ~/Library/Caches/dsh-ios/runtime/entitlements.plist <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.cs.allow-jit</key><true/>
  <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
  <key>com.apple.security.cs.disable-library-validation</key><true/>
</dict></plist>
EOF

codesign --force --sign - --options runtime \
  --entitlements ~/Library/Caches/dsh-ios/runtime/entitlements.plist \
  "$LIB/node"
# 结果: flags=0x10002(adhoc,runtime)，含 disable-library-validation，node --version = v24.21.0

# 让 serve-sim 的 shebang（#!/usr/bin/env node）解析到重签副本
ln -sfn "$LIB/node" /Users/jackchen/.local/bin/node
readlink /Users/jackchen/.local/bin/node   # → /Users/jackchen/.local/lib/dsh-ios-node/node
```

原目标已记录在 `~/.local/lib/dsh-ios-node/original-node-target.txt`：
`/Users/jackchen/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin/node`

验证（重签副本）：
```
$ ~/.local/lib/dsh-ios-node/node -e 'const a=require(".../serve-sim-native.node"); new a.SimCapture("A63C...",null); new a.SimHID("A63C...","")'
ADDON LOADED OK keys: [ 'setDeviceSet', 'SimHID', 'SimCapture', 'axFrontmost', 'axDescribe' ]
SimCapture constructed OK
SimHID constructed OK
```

### 3.2 杀掉僵死的 serve-sim 流，让插件用新 node 重启

```bash
kill -TERM -44554          # 整个进程组（父 CLI 44554 + helper 44557）
kill -TERM 59729           # 诊断时起的临时 3197 实例（如仍在）
# 端口释放 + tmp 状态文件被 serve-sim 自行清理
```

随后调用 `ios_sim_boot`（插件 `ensureRunning` 会重新拉起流），新进程已使用重签副本：

```
62495 node serve-sim.js --no-preview --quiet --port 3181 A63CD17A-...
62498 /Users/jackchen/.local/lib/dsh-ios-node/node serve-sim.js A63CD17A-... --port 3181 --host 127.0.0.1 --exit-on-simulator-shutdown
```

### 3.3 安装插件 pinned 版 AXe 1.8.0（到插件自己的 cache，digest 校验通过）

```bash
BASE="$HOME/Library/Caches/dsh-ios/bin"
curl -fsSL -o /tmp/AXe.tar.gz \
  https://github.com/cameroncooke/AXe/releases/download/v1.8.0/AXe-macOS-v1.8.0-universal.tar.gz
# sha256(archive) = 7b76340b72e90d0f211bc7c4636f15009076eff07acef2f2b632b175debd8834  ✔ 与插件 AXE_RELEASE_SHA256 一致
mkdir -p "$BASE/axe/1.8.0" && tar -xzf /tmp/AXe.tar.gz -C "$BASE/axe/1.8.0"
chmod +x "$BASE/axe/1.8.0/axe"
shasum -a 256 "$BASE/axe/1.8.0/axe" | awk '{print $1}' > "$BASE/axe/1.8.0/.dsh-ios-axe.sha256"
"$BASE/axe/1.8.0/axe" --version   # 1.8.0
```

插件 `resolveAxeBinary()` 的 cache 校验 = 可执行 + `.dsh-ios-axe.sha256` 与二进制 sha256 一致 → 现在命中 cache。

## 4. 闭环验证证据（修复后，全部实测）

1) `ios_sim_boot` → `{ state: "booted", streaming: true, device: iPhone 17 }`
2) serve-sim helper 路由恢复：
```
GET /helper/A63C.../health  → HTTP 200 {"status":"ok"}
GET /helper/A63C.../config  → {"width":1206,"height":2622,"orientation":"portrait"}
GET /helper/A63C.../stream.mjpeg → HTTP 200，2 秒收到 3,106,854 字节真实 JPEG 帧（--frame / image/jpeg）
GET /helper/A63C.../ws (upgrade) → HTTP/1.1 101 Switching Protocols
serve-sim log: [capture] Framebuffer: 1206x2622 (direct IOSurface, zero-copy) / Frame callbacks registered
```
3) `ios_sim_screenshot` → 成功，1206x2622 PNG
4) `ios_sim_find_text` → **`size: {width: 402, height: 874}`（设备点，不再是 1206x2622 图片像素），无降级 note**；
   OCR 正常识别 SpringBoard / 设置页文本
5) `ios_sim_ui_tree` → 正常返回 402x874 点坐标树（Home 76 节点；Settings 过滤树带 BackButton/进一步了解 等 label+identifier）
6) `ios_sim_interact action=tap`（之前必报 ws 连接失败）→ **成功**：`x=0.5,y=0.367` 点击设置「通用」行，
   返回新截图，无 `Failed to connect to serve-sim` 错误；后续 OCR 确认已进入 Settings 子页面
7) `ios_sim_tap_element`（AXe 元素级）：点 `identifier=BackButton`（label 设置），
   **`expected: {text:"通用", mode:"appear", matched:true, waitedMs:1894}`** ← 正式断言通过
8) DSH 面板路由（loopback + 浏览器头）：
```
POST /_dsh/dsh-ios/status  → {"ok":true,"running":true,"device":"A63CD17A-...","deviceName":"iPhone 17"}
```
   说明插件 SimHostController 已把新流接到 GUI 面板（面板若仍显示旧错误帧，刷新一次页面/面板即可重新 /grant 取流）

## 5. 可复现的自检步骤

```bash
# 1. 流与本机 addon 能力
curl -s "http://127.0.0.1:3181/helper/A63CD17A-E1F9-4C5C-9B8C-92DEB43862FA/health"   # 期望 {"status":"ok"}
readlink ~/.local/bin/node                                                            # 期望 .../.local/lib/dsh-ios-node/node

# 2. 若 health 404（Typical 症状复现）：检查 addon 能否加载
~/.local/lib/dsh-ios-node/node -e 'require("/Users/jackchen/.dsh/profiles/desktop/node_modules/serve-sim/dist/native/serve-sim-native.node")'
# Team ID mismatch → 重签副本步骤（3.1）失效/被回滚

# 3. AXe
~/Library/Caches/dsh-ios/bin/axe/1.8.0/axe describe-ui --udid <UDID> | head   # 期望 AXFrame {{0,0},{402,874}}
```

## 6. 回滚方式

- node 软链：`ln -sfn /Users/jackchen/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin/node /Users/jackchen/.local/bin/node`
  （回滚后 serve-sim 会恢复为「HTTP 活着但 device 全 404」的状态 —— 即本 bug 的原状，不建议）
- AXe：`rm -rf ~/Library/Caches/dsh-ios/bin/axe`
- 重签副本：`rm -rf ~/.local/lib/dsh-ios-node`

## 7. 遗留风险 / 建议（需要 lead 决策或上报 DSH）

1. **DSH 打包缺陷（根因 1）**：`dsh-runtimes/.../dependencies/node` 的签名缺少
   `com.apple.security.cs.disable-library-validation`，导致任何 ad-hoc 签名的原生 addon
   （serve-sim 之外的插件同样会踩）无法 dlopen。长期正确修法是 DSH 发布侧给 runtime node
   补该 entitlement；本地的「副本重签 + 软链」是环境级 workaround。
2. 软链指向 `~/.local/lib/dsh-ios-node/node`（重签副本）。若 DSH 升级 runtime node（v24.21.0 → 新版），
   该副本不会自动更新，届时需重跑 3.1（或重新安装/重签）。建议在环境记录里标注。
3. serve-sim/插件不会主动探测「自己子进程活着但 device session 死了」：插件仅对 adopted helper 做
   `/health` 探活，own child 只在进程退出时重启。若再次出现「streaming:true 但 404」，
   先 `curl /helper/<udid>/health` 判定，再 kill 进程组让插件重启流。
4. GUI 面板在流重启后如需手动刷新一次页面才会重新 /grant 取流（面板客户端 poll /status 并重新取 token）。

## 8. 本次改动的文件/状态清单

- `~/.local/bin/node`（软链改指向，原目标已备份）
- `~/.local/lib/dsh-ios-node/node`（新增，DSH runtime node 副本 + ad-hoc 重签含 disable-library-validation）
- `~/.local/lib/dsh-ios-node/original-node-target.txt`（新增，回滚信息）
- `~/Library/Caches/dsh-ios/runtime/entitlements.plist`（新增，重签用）
- `~/Library/Caches/dsh-ios/bin/axe/1.8.0/…`（新增，AXe 1.8.0 + digest）
- 进程：旧 serve-sim 44554/44557 已终止；新流 62495/62498（由插件在修复后 `ios_sim_boot` 拉起）
- 未改动：RhodesLink 源码、DSH 插件代码/配置、DSH 安装目录内文件
