# CBB 安全性文档索引

2.2.x 引入的**二层加密（应用层加密）**完整开发依据。六份文档，按"先看哪份"排序：

| # | 文档 | 什么时候看 |
|---|---|---|
| 01 | [架构分层与信任边界](01-架构分层与信任边界.md) | 想知道"蓝牙配对到底保护了什么"、威胁模型覆盖到哪 |
| 02 | [帧协议规范（冻结）](02-帧协议规范.md) | 要改协议、要写第三方实现、要抓包对齐字节 |
| 03 | [握手状态机](03-握手状态机.md) | 排查"为什么连上了却不同步"、看失败原因 |
| 04 | [算法插拔与替换规范](04-算法插拔与替换规范.md) | 要加国密/后量子/新 KDF |
| 05 | [传输层迁移适配指南](05-传输层迁移适配指南.md) | 要把 RFCOMM 换成 BLE / 局域网 / 高速蓝牙 |
| 06 | [性能与高吞吐预留](06-性能与高吞吐预留.md) | 要传大文件、要迁移到低带宽链路 |

---

## 一句话版本

> 蓝牙配对只保证"这一跳空口被加密"，不保证"对面这个 App 是我配的那台设备"。
> CBB 在 RFCOMM 之上加了一层自己的加密：**离线预共享口令 → 挑战应答验身份 → AES-256-GCM 加密每一条消息 → 帧号防重放**。
> 口令永不上线；断开即销毁密钥；重连必须重新握手。

---

## 快速事实表

| 项 | 值 |
|---|---|
| 传输 | 经典蓝牙 RFCOMM/SPP，UUID `8ce255c0-200a-11e0-ac64-0800200c9a66` |
| 密钥模型 | 离线 PSK，两端手抄一致，**永不经蓝牙传输** |
| 握手 | HELLO → CHALLENGE → CONFIRM → ACCEPT，限时 12 秒 |
| 密钥派生 | PBKDF2-HMAC-SHA256（盐 = nC‖nS，默认 60 000 次）→ 双向独立会话密钥 |
| 数据加密 | AES-256-GCM，每帧随机 96-bit nonce，128-bit tag |
| 附加认证数据 | 16 字节帧头（改一位即 tag 失败） |
| 防重放 | seq 单调 + 64 帧滑动窗口 + 64 条 nonce 去重 |
| 帧头 | 16 字节，magic `'C','B'`，version 1，小端 |
| 单帧上限 | 12 MiB |
| 安全模式 | `off` / `auto`（默认）/ `require` |
| 旧版兼容 | 探针行协商，2.1.x 对端走明文行协议（`require` 模式拒绝） |
| 自检 | Windows `CBB.exe --selftest`；Android 设置 → 安全 → 运行安全自检；均 17 项 |

---

## 验证命令

```bash
# Windows：17 项安全自检（可输出报告）
CBB.exe --selftest
CBB.exe --selftest D:\selftest-win.txt

# Android：JVM 单元测试（两个用例覆盖全部 17 项 + 重放窗口）
JAVA_HOME=... tools/jdk21/... tools/gradle-8.14.3/bin/gradle :app:testDebugUnitTest
```

真机验收三条：

1. 两端配同一口令 → 设备卡显示「已连接 · 已加密」；
2. 一端改口令 → 1 秒内断开并报「密钥不匹配」；
3. 对端是 2.1.x + 本端 `auto` → 仍能明文互通（不报错）。

---

## 排查入口

| 端 | 安全日志 |
|---|---|
| Windows | `%APPDATA%\ClipSync\security.log`（2 MB 轮转） |
| Android | `Android/data/com.aubynsamuel.clipsync/files/security.log`（512 KB 轮转） |
| 传输日志（Windows） | `%APPDATA%\ClipSync\transfer.log` |

日志关键字：`negotiate:` / `handshake:` / `session READY` / `drop:` / `SECURITY-FAILED:`。

**Release 版本不输出明文内容**，只有 `#if DEBUG` / `BuildConfig.DEBUG` 构建才会打印 `tx`/`rx` 明文。

---

## 其余文档（阶段二～四与测试）

| # | 文档 | 内容 |
|---|---|---|
| 07 | [传输层扩展接口规范](07-传输层扩展接口规范.md) | `TransportLink` / `IPeerLink` 的契约，接新链路要满足什么 |
| 09 | [HyperOS 4 / Android 17 后台剪贴板方案](09-HyperOS4-Android17-后台剪贴板方案.md) | 系统把 `READ_CLIPBOARD` 收成前台 op 之后，什么能做什么不能做（含 2026-11-03 修正：Shizuku v13.6.0+ 可恢复特权通道） |
| 10 | [文案规范](10-文案规范.md) | 双端双语文案的写法与命名（避免"文件已不可用"这类误报） |
| 11 | [压力测试报告 · 第一轮](11-任务4测试报告-第一轮.md) | 大文件连发，挖出的缺陷与修复 |
| 12 | [压力测试报告 · 第二轮](12-任务4测试报告-压力测试.md) | 剪贴板高频、断链注入、息屏后台、内存基线 |
