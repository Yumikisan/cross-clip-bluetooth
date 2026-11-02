# Cross Clip Bluetooth (CCB)

> Clipboard, files and folders between **Windows** and **Android**, over plain **Bluetooth Classic** (RFCOMM/SPP).
> No account, no cloud, no LAN, no pairing server — the two devices talk to each other and nothing else.

[![Release](https://img.shields.io/github/v/release/Yumikisan239/cross-clip-bluetooth?label=release&color=2f81f7)](https://github.com/Yumikisan239/cross-clip-bluetooth/releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(minSdk%2024)-3ddc84)](android)
[![Windows](https://img.shields.io/badge/Windows-10%20%2F%2011-0078d4)](windows)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**English** · [简体中文](#中文说明)

---

## This is a fork

This repository is the **enhanced fork** of [ClipSync / Cross Clip](https://github.com/aubynsamuel) by **aubynsamuel**, maintained by **Yumikisan**. Upstream ships two separate apps; this fork ships **the Android and the Windows side together, as one release**, because the features below only make sense when both ends run the same protocol version.

The original project, its architecture and its Bluetooth foundation are entirely aubynsamuel's work, released under the MIT license. What this fork adds is listed in the table below. Both in-app "About" pages credit the upstream author and link the two sponsor pages side by side, so it is never ambiguous who is being supported.

Nothing here changes the premise: **Bluetooth only**. Transport is RFCOMM/SPP on service UUID `8ce255c0-200a-11e0-ac64-0800200c9a66`; a full-repo search for `BluetoothGatt / BluetoothLeScanner / AdvertisementWatcher` returns zero hits.

## What this fork adds

| Area | Upstream | This fork |
| --- | --- | --- |
| Direction | Windows listens, Android dials; phone→PC was unreliable | Both ends maintain a link and arbitrate duplicate dials; full duplex |
| History | None | Persistent history on both ends, search, two-axis filters, card/list views |
| Files | None | Framed file transfer, queue, size limit, progress / speed / ETA, cancel from either side |
| Folders | None | Recursive folder transfer with atomic `.cbbpart` writes and `folder_begin/end` bookkeeping |
| Security | OS pairing only | Optional **PSK + AES-256-GCM** layer-2 channel, SAS confirmation code, replay window |
| Images | None | A picture copied on Windows is sent to the phone as PNG |
| Android intake | App must be open | Share sheet, notification action, screenshot watcher, Shizuku background clipboard |
| Windows UX | Plain window | Tray icon, autostart, single instance, hotkey, drag-and-drop send |
| Stability | Reconnect only | Stall watchdog, half-dead link reaping, crash forensics, echo suppression |
| Performance | Full rebuild of the history list | 500-row refresh: ~856 ms → ~3 ms; file throughput +69–75 % |

Full, version-by-version detail: **[CHANGELOG.md](CHANGELOG.md)**. Protocol, trust boundaries and test reports: **[docs/](docs/)**.

## Install

Both artifacts are attached to the latest [Release](https://github.com/Yumikisan239/cross-clip-bluetooth/releases).

- **Windows** — `CCB-Windows-<ver>.exe`, a self-contained single file. Double click, no .NET runtime needed. Unpack it anywhere; settings live in `%APPDATA%\ClipSync`.
- **Android** — `CCB-Android-<ver>.apk`. The `applicationId` is the same as upstream (`com.aubynsamuel.clipsync`) but the signing key is not, so **uninstall the official build first**. Later fork versions install over each other normally.

Pair the two devices in the OS first, then open both apps and select the peer in the device list.

## Build

Requirements: **JDK 21**, **Android SDK 36** (platform + build-tools), **Gradle 8.14.3**, **.NET 9 SDK**.

```bash
# Android  →  android/app/build/outputs/apk/release/
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease          # or ./gradlew assembleRelease

# Windows  →  windows/bin/Release/net9.0-windows/win-x64/publish/CBB.exe
cd windows
dotnet publish -c Release -r win-x64 --self-contained \
  -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true \
  -p:IncludeNativeLibrariesForSelfExtract=true
```

Release signing is **never** in the repository. Put `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` in `android/local.properties` (or export `CBB_STORE_FILE`, `CBB_STORE_PASSWORD`, `CBB_KEY_ALIAS`, `CBB_KEY_PASSWORD`); without them `assembleRelease` still builds, just unsigned.

Verify the crypto stack without any hardware:

```bash
windows/bin/Release/net9.0-windows/win-x64/publish/CBB.exe --selftest
# → %APPDATA%\ClipSync\selftest.log   (19/19 expected)
cd android && gradle test        # wire / handshake / folder / cancel unit tests
```

## Roadmap

Planned, not promised — the order is roughly the order of usefulness:

1. **LAN transport backend.** The transport layer is already an interface (`transport/`, `Services/IPeerLink`), so the same frame protocol can run over TCP on the local network for a large speed jump, while Bluetooth stays the zero-config default.
2. **Resumable transfers.** Keep partial `.cbbpart` state across a dropped link instead of restarting a 1 GB file.
3. **Incremental folder sync.** Today a folder is copied file by file; only the changed files should travel.
4. **Richer clipboard payloads.** Images and rich text in both directions, not just Windows→Android for PNG.
5. **Packaging.** A signed Windows installer next to the portable exe, and a build badge that does not lie.
6. **Android 17 background clipboard.** Currently impossible without a privileged channel; the app says so honestly instead of pretending (see `docs/09-…`).

Explicitly **not** planned: a BLE/GATT rewrite (a research branch exists and will never be merged — RFCOMM is the reason this works on every phone), and iOS/macOS ports (no Bluetooth Classic SPP).

## Compatibility

- Fork ↔ fork, same protocol version: full feature set.
- Fork ↔ upstream 1.x: text only, and only where the frame protocol still matches; not supported.
- Every protocol change ships to both platforms in the same release. Version pairs are listed per release.

## AI assistance

Part of this fork — the refactors, the defect hunts (handshake MAC layering, file echo loops, history-render performance), the test scaffolding and the documentation — was produced with **DeepSeek V4.1 Flash** as an assistant. Every change was reviewed, built, and verified on real hardware (Xiaomi/PowerOS phone + Windows 11) before release; the model never shipped anything unreviewed.

---

## 中文说明

### 这是什么

**Cross Clip Bluetooth（CCB）** 是基于 **aubynsamuel** 的开源项目 **ClipSync / Cross Clip** 做的增强分支，由 **Yumikisan** 维护，**安卓端与 Windows 端一起发行**。

上游是"电脑能发、手机收"的剪贴板小工具；这个分支把它做成了**双向、可传文件与文件夹、带历史与二层加密**的完整工具。所有传输**只走蓝牙经典（RFCOMM/SPP）**：不联网、不上云、不需要同一局域网，配对完就能用。

原项目的架构与蓝牙底座完全属于原作者，按 MIT 许可开源；本分支只在其上增补。两端「关于」页并列写明来源与两个赞助入口——原作者的是原作者，本分支的是本分支，不会让人误会。

### 相对原项目改了什么

| 方面 | 原项目 | 本分支 |
| --- | --- | --- |
| **双向同步** | 电脑监听、手机拨号，手机→电脑经常发不出去 | 两端都能拨、按节点 id 仲裁重复链路，**全双工**，任一端复制都能到对端 |
| **链路稳定性** | 断了重连 | 心跳 + 拨号超时回收 + 双拨号去重 + **40 秒无入站即断开**（半死链路不再冒充在线）+ 写超时看门狗 |
| **二层加密** | 仅系统配对 | **口令（PSK）+ AES-256-GCM**，每帧随机 nonce、头作 AAD、64 帧滑窗防重放；可选"自动 / 强制 / 关闭" |
| **身份确认** | 无 | 双端 **6 位确认码（SAS）**，可关；两端显示同一串才算对上 |
| **历史记录** | 无 | 双端落盘历史，关键字搜索、格式/长度/时间/后缀/大小多维筛选、条目视图与两列卡片视图、复制/删除/清空 |
| **文件传输** | 无 | 分帧传输（8 KiB 原始字节/帧，socket 4 KiB 切片）、发送队列、大小上限（默认 8 MB 可调）、**进度 + 速率 + 剩余时间**、**任一端都能取消** |
| **文件夹传输** | 无 | 递归目录、`folder_begin/mkdir/file_*/folder_end` 记账、`.cbbpart` 原子落盘、断点不留半成品、深度上限 6 层、空目录用占位文件保住 |
| **图片** | 无 | 电脑复制截图/图片 → 手机按 PNG 收 |
| **手机端入口** | 要打开 App | 系统分享面板、通知按钮直达、截屏后自动同步、Shizuku 后台读剪贴板 |
| **电脑端体验** | 普通窗口 | 托盘常驻、关闭进托盘、开机自启、单实例、全局快捷键、**拖文件进窗口即发送** |
| **性能** | 历史列表每次全量重建 | 500 条历史刷新 **856 ms → 3 ms**；文件吞吐 **+69–75%** |
| **可诊断性** | 出错只有一句提示 | 两端 `security.log` / `transfer.log` 可按 `kp`、`nI/nR`、`nC/nS` 对账；崩溃取证（`CrashGuard`，含 minidump）|
| **防回声** | — | 文件/截图/自己写入的剪贴板内容都不会被当成"用户刚复制的"回传（按内容身份判定，跨服务重启生效） |

逐版本明细见 **[CHANGELOG.md](CHANGELOG.md)**；协议、信任边界、握手状态机与三轮压力测试报告在 **[docs/](docs/)**。

### 安装

两个平台的安装包都在最新的 [Release](https://github.com/Yumikisan239/cross-clip-bluetooth/releases) 里。

- **Windows**：`CCB-Windows-<版本>.exe`，自包含单文件，双击即用，不需要装 .NET 运行时。配置在 `%APPDATA%\ClipSync`。
- **安卓**：`CCB-Android-<版本>.apk`。`applicationId` 与原版相同（`com.aubynsamuel.clipsync`），但**签名不同**，装过官方版的机器必须先卸载；本分支后续版本之间可以直接覆盖安装。

先在系统设置里把两台设备配好对，再打开两端应用，在设备列表里勾选对端。

### 这个 fork 是怎么搭的（构建）

目录结构：

```
cross-clip-bluetooth/
├─ android/       安卓端（Kotlin + Compose，Gradle 工程，app/ 为唯一 module）
├─ windows/       Windows 端（.NET 9 + WPF，CBB.exe）
├─ docs/          协议、信任边界、算法插拔、传输层扩展、测试报告（两端共用一份）
├─ CHANGELOG.md   逐版本改动说明（这个 fork 的全部历史）
└─ .github/       CI：两端各一个构建任务
```

环境要求：**JDK 21**、**Android SDK 36**（platform + build-tools）、**Gradle 8.14.3**、**.NET 9 SDK**。

```bash
# 安卓
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease        # 产物：android/app/build/outputs/apk/release/

# Windows
cd windows
dotnet publish -c Release -r win-x64 --self-contained \
  -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true \
  -p:IncludeNativeLibrariesForSelfExtract=true
```

**签名密钥不进仓库。** 在 `android/local.properties` 里写 `STORE_FILE` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，或在 CI 里导出 `CBB_STORE_FILE` / `CBB_STORE_PASSWORD` / `CBB_KEY_ALIAS` / `CBB_KEY_PASSWORD`。两者都没有时 `assembleRelease` 仍能编译，只是产出未签名的包——这是刻意的：**带着密钥的源码等于把"可覆盖安装"的能力发给所有人**。

不接硬件也能验证加密栈：

```bash
CBB.exe --selftest        # 19 项，结果写 %APPDATA%\ClipSync\selftest.log
cd android && gradle test # 帧协议 / 握手 / 文件夹 / 取消 的单元测试
```

**跟上上游**：本仓库保留了两个上游作为远端别名，需要取上游修复时用 `git fetch upstream` + `git cherry-pick`，不要把上游整棵合并进来——两端的协议层已经分叉，合并只会制造冲突。

### 路线图

按"有用程度"排序，不构成承诺：

1. **局域网传输后端**：传输层已抽成接口（`android/.../transport/`、`windows/Services/IPeerLink`），同一套帧协议可以跑在 TCP 上，速度上一个数量级；蓝牙仍是零配置默认项。
2. **断点续传**：链路断了保留 `.cbbpart` 现场，1 GB 的文件不用从头再来。
3. **文件夹增量同步**：现在逐文件全量复制，只传变化的部分才是正解。
4. **更丰富的剪贴板**：双向图片与富文本（现在只有电脑→手机的 PNG）。
5. **打包**：除便携单文件外，补一个签名的 Windows 安装包。
6. **安卓 17 后台剪贴板**：系统把 `READ_CLIPBOARD` 收成了前台 op，后台直读在 HyperOS 4 上已彻底不可用；等系统放开或出现合规通道再跟进，这期间应用如实告知，不假装能读。

**明确不做**：BLE/GATT 重写（留了一个只做预研、永不合并的分支——蓝牙经典是这工具能在各种机型上跑通的全部原因）；iOS/macOS（没有 Bluetooth Classic SPP）。

### 兼容性

- 本分支 ↔ 本分支，同协议版本：全功能。
- 本分支 ↔ 上游 1.x：仅文本，且不保证；不做支持。
- 任何协议改动都双端同版本发行，版本对应关系写在每个 Release 里。

### AI 辅助说明

本分支的重构、缺陷定位（握手 MAC 多算一层、文件回声成环、历史列表全量重建的性能问题）、测试脚手架与文档，有相当一部分在 **DeepSeek V4.1 Flash** 辅助下完成。所有改动都经过人工审阅、本机构建与真机回归（小米 / 澎湃 OS + Windows 11）后才发布——模型产出的东西不会不经检查直接进包。

### 致谢与赞助

| | 主页 | 赞助 |
| --- | --- | --- |
| 原项目 / 原作者 **aubynsamuel** | https://github.com/aubynsamuel | https://www.buymeacoffee.com/aubynsamuel |
| 本分支 / **Yumikisan** | https://github.com/Yumikisan239 | https://afdian.com/a/Yumikisan239AFD |

上游是两个独立仓库：[clipsync-android](https://github.com/aubynsamuel/clipsync-android)、[clipsync-windows](https://github.com/aubynsamuel/clipsync-windows)。没有他的工作，就没有这个分支。
