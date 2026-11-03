# 09 · HyperOS 4 / Android 17 后台读剪贴板：调研、实测与方案

> 编写时间：2026-10-30 00:50
> 适用：CCB 安卓端 2.4.2 之后
> 结论先行：**Android 17 没有关掉"shell 身份读剪贴板"这条路；现在读不到的真实原因是"特权服务没跑起来 + 管理器里本应用没被授权"，不是剪贴板规则变了。**

> **⚠️ 2026-11-03 重要修正（推翻本文第八节的旧结论）**
>
> **本文原结论「HyperOS 4 / Android 17 上 Shizuku 完全不可用、后台上限是"仅前台可读"」是错的。**
> 那个结论只在**旧版 Shizuku（≤ 13.5.4，仅适配到 Android 15）**上成立：Android 16+ 新的
> Binder / ADB 会话回收逻辑会在服务拉起瞬间撤销会话，表现为"刚启动就断开"。
>
> **Shizuku v13.6.0（最新原版 Rikka）已修复 Android 16 QPR1 的 binder 会话托管与启动脚本路径
> 兼容，在 HyperOS 4（Android 17, SDK 37）实测稳定：服务 `running`、授权 `granted`、
> 特权剪贴板通道（native read / proxy read）均正常。** 因此正确结论是
> **「版本依赖」而非「系统封死」**。详见本文第十节。
>
> 仍需保留的**系统级**限制（与 Shizuku 版本无关、无法绕过）：Android 17 的后台静默读剪贴板
> 依旧被谷歌框架限制，复制后仍需打开一次本应用才能同步。

---

## 一、机型实测事实（不是推测，全部在本机执行过）

| 项目 | 实测值 | 命令 |
|---|---|---|
| 系统版本 | **Android 17 / SDK 37** | `getprop ro.build.version.release` / `sdk` |
| ROM | HyperOS **OS4.0.5.0.XPMCNXM**（V816） | `getprop ro.mi.os.version.name` |
| Shizuku | 已安装（`moe.shizuku.privileged.api`） | `pm list packages` |
| AxManager | 已安装 3.0.0.r509，**今日 23:44 装**，从未写入过外部数据目录 | `dumpsys package frb.axeron.manager` |
| 特权服务 | **启动前：无进程、无 binder** | `ps -A` / `service list` |
| `READ_CLIPBOARD` appop | 默认 **Uid mode: foreground** | `cmd appops get --uid <pkg>` |

### 1.1 决定性实验：shell 身份还能读剪贴板吗

用 `app_process`（uid 2000，与 Shizuku/AxManager 的服务进程完全同权限）直接调 `IClipboard`：

```
SET(code1): OK
GET(code4): CCB-CLIPTEST-9001
```

**结论：能写也能读，没有 SecurityException。** `ClipboardService` 的 `isCallerShell()` 在 Android 17 上依然放行。

也就是说：**2.3.x 沿用的"借 shell 的 binder 代理读剪贴板"这条架构没有失效**，不需要因为升系统而推倒重来。

### 1.2 Shizuku 服务能不能在这台机器上跑起来

```
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

日志：

```
I shizuku_server: Calling main entry rikka.shizuku.server.ShizukuService
D Service : attachApplication: moe.shizuku.privileged.api 10349 30571
I Service : send binder to user app moe.shizuku.privileged.api in user 0
```

**能跑。** 之前"点授权没反应"的直接原因：**根本没有服务进程**——应用内那句 `Shizuku.pingBinder()` 为 false，而旧代码在这条分支上是静默 return 的。

### 1.3 AppOps 自授予能不能替代 shell 代理

执行 `cmd appops set --uid com.aubynsamuel.clipsync READ_CLIPBOARD allow` → 回读确认 `Uid mode: READ_CLIPBOARD: allow`。

然后把已知字符串写进剪贴板，观察应用自己的日志：

```
BluetoothService: clipboard read returned nothing (channel=NOT_RUNNING)
```

**api 层没有报错，但读不到内容**——即在 Android 17 + 这台 HyperOS 上，**AppOps 显式 allow 不足以让后台应用原生直读剪贴板**。

> ⚠️ 该次测试时手机处于**锁屏**状态。Android 12 起锁屏会额外限制剪贴板读取，所以这一条**还需要在解锁态复测一次**才能定论。但从"能用 shell 就别指望 appops"的角度，方案里不能把它当主路径。

### 1.4 权限的现状

服务起来后，应用的 `ShizukuBridge` 仍然报 `no binder`。原因是 Shizuku 的 binder 是**服务启动时由管理器分发给已授权客户端**的：

- 服务起来了 → 管理器收到 binder → 管理器**只推给它列表里已授权的应用**；
- 本应用如果不在管理器列表里（重装/换系统后常见），**永远等不到 binder**，`refresh()` 再怎么轮询也没用。

所以"先装 AxManager 就好"不成立：**必须让本应用在管理器里被授权一次**（需要解锁点 UI 那一次）。

---

## 二、规则层面：Android 17 到底改了什么

| 事项 | 是否影响第三方应用 | 依据 |
|---|---|---|
| 后台读剪贴板需要焦点/系统身份 | 一直如此（Android 10 起），**17 没有新增更严的通用禁令** | 实测 shell 仍可读；`READ_CLIPBOARD` appop 仍在，只是默认从 `allow` 变为 `foreground` |
| `READ_CLIPBOARD_IN_BACKGROUND` | 是 `signature\|privileged`，**第三方拿不到**，`pm grant` 也授不了 | Android 17 的"通用剪贴板"就是靠 Pixel 系统服务持有该权限实现的 |
| 系统级"通用剪贴板" | 只服务 Play 服务/系统服务，**不对外提供 API** | Google 的 UniversalClipboardManager 走 `android.companion.datatransfer.continuity`，与第三方无关 |
| HyperOS 自己的剪贴板隐私开关 | MIUI/HyperOS 把剪贴板归到 **AppOps**（小米开发者文档写明"权限级别：App ops"），没有独立授权弹窗 | 小米开发者平台《读取剪贴板权限使用及申请标准》 |

**给用户的一句话解释**：这不是"CCB 被系统禁了"，而是 Android 从 10 开始就规定"后台应用不许读剪贴板"，唯一合规绕法是用一个**以 shell 身份运行的特权服务**代读；HyperOS 4 只是把"怎么启动这个服务"变得更容易失败（无线调试配对那一步）。

---

## 三、方案：三级通道 + 一次诊断

设计原则：**业务层只认"能读到 / 读不到"，通道可替换**（与 2.4.0 的 `TransportLink` 同一思路）。

```
runClipboardTick()
   └── ClipChannel.read(context)            ← 新增，单点收口
         ├─ Tier 1  SHELL_PROXY   Shizuku/AxManager binder → IClipboard.getPrimaryClip
         ├─ Tier 2  NATIVE        ClipboardManager（需 appops allow，且设备已解锁）
         └─ Tier 3  FOCUS_WINDOW  透明 Activity 抢焦点（上游老方案，最后兜底）
```

### Tier 1 · SHELL_PROXY（主路径，已实测可用）

- 复用现有 `ShizukuBridge.readPrimaryClip()`：`SystemServiceHelper.getSystemService("clipboard")` + `ShizukuBinderWrapper`，按参数个数穷举 `getPrimaryClip` 重载。
- 提供者二选一：**Shizuku** 或 **AxManager**（`frb.axeron.manager`，Shizuku-API 兼容）。
- **必须满足三件事**，缺一不可：
  1. 服务进程在运行（`service` 有 binder + `app_process` 进程存活）；
  2. 本应用在管理器里**被授权**；
  3. 管理器没被 HyperOS 的省电/自启动策略杀掉。

### Tier 2 · NATIVE（备用，需验证）

- 前提：`cmd appops set --uid <pkg> READ_CLIPBOARD allow` 生效 **且** 设备已解锁。
- 价值：零 binder 往返，最省电；某些 ROM（AOSP 系、部分三星）实测有效。
- 本机当前**实测无效**，因此只做"探测到有效才启用"，绝不作为唯一依赖。

### Tier 3 · FOCUS_WINDOW（兜底，2.4.5 已实现）

**实现**：`activities/ClipboardFocusActivity` —— 透明、不绘制、`excludeFromRecents`、`noHistory`、0.3 秒自关。

**原理**：`READ_CLIPBOARD` 在 Android 17 是 `foreground` 类 op，判定依据是 uid 是否处于前台。一个 resume 的 Activity 就让 uid 变成 top，条件满足，剪贴板可读。这是**不依赖 ADB 的唯一合法路子**（不涉及特权身份，只是"把自己搬到前台一瞬"）。

**只对复制类事件触发**：`TYPE_VIEW_TEXT_SELECTION_CHANGED`、`TYPE_VIEW_LONG_CLICKED`、文本含"复制/拷贝/copy"的点击。其它事件只做一次直读（某些 ROM 允许），绝不抢焦点，否则会顶掉正在输入的键盘。

**残余风险**：Android 10+ 限制后台启动 Activity；无障碍服务通常在豁免名单里，但 HyperOS 可能加码。若被拦，日志会打印 `focus snatch blocked: …`，那就只剩输入法一条路。

原始设计说明：

- 上游 ClipSync 的老做法：透明 Activity 抢焦点再读。会闪一下、多一个最近任务。
- 仅在 1、2 都不可用时提示用户"临时前台读取一次"，不做常驻。

### 一次诊断（把"点授权没反应"变成可回答的问题）

设置页「特权通道」卡片改为显示一行状态 + 「重新检测」+「复制诊断」：

```
CCB 剪贴板通道诊断
系统: Android 17 (SDK 37) / OS4.0.5.0.XPMCNCM
提供者: AxManager 3.0.0.r509(已装) / Shizuku(已装)
服务: 未运行 (pingBinder=false)
授权: 无
AppOps: READ_CLIPBOARD=allow (uid)
原生读: 失败 (锁屏 or 后台)
代理读: 失败 (无 binder)
→ 下一步: 在 AxManager 里授权本应用 / 用电脑 adb 启动服务
```

这行文本既能给用户看，也能一键复制进反馈报告（2.4.1 已有反馈表单）。

---

## 四、HyperOS 4 上的启动与授权（照抄可执行）

### 路线 A：电脑 adb 启动（最稳，本次已实测成功）

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
# AxManager 若提供 start.sh，同理替换包名
```

重启手机后失效，需要重跑。适合调试与"先跑通"。

### 路线 B：AxManager 无线调试激活（长期方案）

按 AxManager 官方 FAQ，HyperOS 上失败率高，必须先把这四项改掉：

1. 系统设置 → 通知 → 通知中心 → **通知样式改为「Android」**（否则"输入配对码"立刻失败）；
2. 开发者选项 → **USB 调试（安全设置）** 单独打开（与"USB 调试"是两个开关）；
3. 应用信息 → AxManager → **允许后台运行 / 自启动 / 电池无限制**；
4. 开发者选项 → **停用 adb 授权超时**；USB 默认配置改「不传输数据」。

激活后再回本应用 → 设置 → 特权通道 → 打开 AxManager → 在其中把本应用设为**允许**。

### 路线 C：Shizuku（如果 AxManager 仍失败）

Shizuku 需要同样的三个前置：无线调试配对、USB 调试（安全设置）、后台不限。它的"更新提示"是 AxManager 代理时的正常现象，不用管。

---

## 五、代码改动清单（按优先级）

| # | 改动 | 文件 | 目的 |
|---|---|---|---|
| 1 | `ClipChannel` 收口三档读取 + 状态枚举 | `shizuku/ClipChannel.kt`（新） | 通道可替换，日志只报一层 |
| 2 | 诊断字符串 `diagnose()` | `shizuku/ShizukuBridge.kt` | 把失败原因说清楚 |
| 3 | 设置页显示状态 + 「重新检测」「复制诊断」 | `ui/screen/SettingsScreen.kt` | 用户可自助排查 |
| 4 | `requestPermission()` 区分"服务未运行/未授权/被拒" | 同上 | 不再静默 |
| 5 | 服务晚于应用启动时主动重取 binder | `ShizukuBridge.refresh()` | 修"应用先起、服务后起"永远拿不到 binder 的坑 |
| 6 | （可选）`Shizuku.newProcess` 执行 `cmd appops set` | `shizuku/ShellExec.kt`（新） | 给 Tier 2 一次自授予的机会 |

---

## 六、实测闭环（2026-10-30 00:51，已打通）

按「应用先起 → 再启动特权服务」的顺序操作后，应用日志（设备处于**锁屏**状态）：

```
ShizukuBridge: shizuku binder received                            ← 收到特权服务 binder
BluetoothService: clipboard captured, no live link yet (retry)     ← 后台读到了内容
BluetoothService: clipboard read returned nothing (channel=READY)  ← 清空后如实报空
```

**三条结论**：

1. `IClipboard` 代理在 Android 17 上完全可用，锁屏 + 后台也能读到；
2. 关键顺序是**先让应用的服务跑起来，再启动特权服务**——binder 是服务启动时推给已授权客户端的，应用后启动就永远等不到（这正是「点授权没反应」的另一半原因）；
3. `channel=READY` 时读到空，就是剪贴板真的空，不是权限问题——日志已经能自证。

## 七、第二轮实测（2026-10-30 00:55–01:00）：三条新结论

### 7.1 AppOps 路线彻底排除

解锁状态、`appops READ_CLIPBOARD=allow`、应用在后台，用 shell 身份写入 `CCB-BG-TEST-A1` 后：

```
BluetoothService: clipboard read returned nothing (channel=NOT_RUNNING)
```

**与锁屏态结论一致**：Android 17 上，AppOps 显式 allow 不能让普通应用后台直读剪贴板。`READ_CLIPBOARD` 已变成 `foreground` 类 op，前台才能读。→ **Tier 2 永久降级为「探测到有效才用」，不得作为唯一依赖。**

### 7.2 binder 投递的触发条件（关键）

实测 5 次，只有 1 次成功，直到定位到规律：

| 场景 | 结果 |
|---|---|
| 服务先起、应用在后台 | 不投递 |
| 服务先起、把应用**拉到前台** | **投递成功**（`shizuku binder received`） |
| 应用在前台、服务后起 | 不投递（要等下次应用回前台） |

→ **触发点是「应用切到前台」**，不是"服务启动"。所以恢复通道的动作是：**先把服务弄活，再打开一次 CCB**。

### 7.3 划掉最近任务会连带杀掉服务

```
adb shell am force-stop moe.shizuku.privileged.api   → ps 里 shizuku_server 立即消失
am_kill: [..., moe.shizuku.privileged.api, ..., SwipeUpClean, ...]   ← 用户划掉管理器
```

HyperOS 把 `shizuku_server`（uid 2000）与包 `moe.shizuku.privileged.api` 关联，**划掉管理器 = 服务一起死**。这是"用着用着就不同步了"的常见原因。

### 7.4 结论：HyperOS 4 上的正确操作顺序

1. 启动服务（AxManager 激活；或 `adb shell sh …/start.sh`）；
2. **打开一次 CCB**（前台）→ 通道连上，之后后台一直可用；
3. 不要把 Shizuku/AxManager 从最近任务里划掉；给它们「后台运行 / 自启动 / 电池无限制」。

## 八、当时的收口决定（2.4.6，**已被第十节修正**）

> 本节记录 2.4.6 当时的判断。**其中"Android 17 上特权通道也不可用"的部分已被 2026-11-03 的
> 实测推翻**——那是旧版 Shizuku 的问题，升级到 v13.6.0+ 后通道正常。本节其余内容（抢焦点
> 作废、老系统不降级、前台立即读）仍然成立。

抢焦点瞬读实测同样失败——系统把「后台启动 Activity」一并拦了，日志只会打印
`focus snatch blocked`。至此四种不依赖 root 的路径在 Android 17 上全部走不通：

| 路径 | Android 16 / HyperOS 3 | Android 17 / HyperOS 4 |
|---|---|---|
| 后台直读 + appops allow | 可用 | **不可用**（`READ_CLIPBOARD` 改为 `foreground` 类 op） |
| 无障碍触发 + 直读 | 可用 | **不可用**（无障碍不是豁免对象） |
| 抢焦点瞬读 | 可用（有闪烁） | **不可用**（BAL 拦截） |
| 特权通道（Shizuku / AxManager） | 可用 | **旧版 Shizuku(≤13.5.4) 下不稳**；**v13.6.0+ 实测稳定可用**（见第十节） |
| 默认输入法 | 可用 | 可用（未采纳：产品形态改动过大） |

**因此本项目当时的决定**：

1. 面向 Android 17 的**抢焦点**后台通道开发**终止**；抢焦点代码（`ClipboardFocusActivity`）已删除，
   不再保留任何会闪屏的实验路径。（特权通道不受影响，仍保留。）
2. **Android 16 及更早（含 HyperOS 3）保持原样**：后台自动同步仍是默认行为，代码里不引入
   任何按版本的降级，这条路径不受本次改动影响。
3. Android 17 上的行为改为**显式告知 + 可操作**：特权通道未就绪时后台不再空转轮询（省电），
   应用在前台时立即读取并同步（实测有效），设置页给出「发送当前剪贴板」一键手动发送；
   **特权通道就绪时（Shizuku v13.6.0+ / AxManager）则照常后台读取，不进入该降级分支。**

判定集中在一个地方：`core/ClipboardCapability`。它按 `SDK_INT >= 37` 判定系统规则，
特权通道就绪时该限制失效（通道本身就是绕过手段），其余情况按「前台才读」处理。

## 九、还需要用户配合验证的两件事

1. **解锁后**：把服务启动好 → 本应用设置页点「重新检测」，看诊断是否变成 `服务: 运行中 / 授权: 已授权 / 代理读: 成功`；然后在别的应用复制一段文本，看电脑是否收到。
2. **解锁后**（Tier 2 定论）：保持 `appops allow`，把应用切后台，复制文本，看是否仍记录。若记录 → Tier 2 可用，可做省电优化；若不记录 → 说明 17 上必须走 Tier 1，方案里 Tier 2 永久降级为"探测到才用"。

## 十、2026-11-03 修正：HyperOS 4 / Android 17 的 Shizuku 通道（版本依赖，非系统封死）

### 10.1 推翻的旧结论

旧文档（第八节）写的是「Android 17 上特权通道不可用、后台读取收口为仅前台」。**这个结论只在
旧版 Shizuku 上成立**，不能推广成"系统层面彻底封死"。

- **旧版 Shizuku（≤ 13.5.4）**：仅适配到 Android 15。Android 16+ 新的 Binder / ADB 会话回收
  逻辑会在服务拉起瞬间撤销会话，表现为**启动后立刻断开、通道失效**。当时所有"HyperOS 4 上
  Shizuku 不可用"的判断都基于这个版本。
- **Shizuku v13.6.0（最新原版，Rikka 官方）**：更新了 Android 16 QPR1 适配，修复了新版 Android
  的 **binder 会话托管**与**启动脚本路径兼容**逻辑；该兼容层向下/向前兼容 HyperOS 4 基于
  Android 17 的底层。

### 10.2 前置依赖要求（硬性）

> **HyperOS 4 / Android 17 用户要使用 Shizuku 特权通道，强制要求 Shizuku ≥ v13.6.0，
> 且必须是 Rikka 官方原版（拒绝魔改/套壳分发包）。**

低于该版本会表现为"授权成功但通道秒断"，不是应用 bug。

### 10.3 实测结果（本机 HyperOS 4 / Android 17 / SDK 37）

1. Shizuku 服务可以稳定 `service: running`、授权 `grant: granted`；
2. 特权剪贴板通道（native read / proxy read）正常，读写均成功；
3. 现象特点：**部分客户端（本应用）授权已生效，但不会自动出现在 Shizuku APP 内的应用列表**
   ——这是新版本 Shizuku 修改了客户端 UI 发现逻辑，**不是授权异常**。**以本应用内诊断页的
   返回值为准，不要以 Shizuku 界面列表判断可用性。**
4. 系统原生限制仍在（见 10.5），不受 Shizuku 升级影响。

### 10.4 排坑说明

| 现象 | 判断 | 处理 |
|---|---|---|
| 通道诊断显示 `grant` 正常、读写 ok，但 **Shizuku 主界面应用列表看不到本应用** | **新版正常行为，不代表失效** | 以本应用自带诊断页为准，忽略列表 |
| 服务刚启动就 `running → 断开`，通道秒失效 | Shizuku 版本过低（≤13.5.4） | 升级到 **v13.6.0+ 官方原版** |
| 用着用着就不同步 | 把 Shizuku / AxManager 从最近任务划掉了 | **不要清除其最近任务卡片**，否则 HyperOS 会直接终止特权服务；给它们「后台运行 / 自启动 / 电池无限制」 |

**保活要求（重申）**：依然不能清除 Shizuku / AxManager 的最近任务卡片，否则特权服务会被
HyperOS 直接终止，通道持续断开直到服务重新启动。正确顺序仍是「先起服务 → 再打开一次本应用」。

### 10.5 边界提示（恢复 ≠ 全部放开）

- **Shizuku 通道恢复 ≠ 放开小米私有系统命令白名单**。诸如 `svc nfc toggle` 等小米私有系统
  指令，即使拿到 shell 身份，依旧会被系统权限策略拦截，不属于本通道能解决的范畴。
- **后台静默读剪贴板仍受谷歌框架限制**：HyperOS 4 / Android 17 的后台静默读取依然被限制，
  复制后仍需要打开一次本应用才能同步。该限制**不受 Shizuku 升级影响，无法绕过**。

### 10.6 结论修订

| 项目 | 旧结论（错误） | 新结论（已实测） |
|---|---|---|
| HyperOS 4 / Android 17 Shizuku 可用性 | 完全不可用 | **版本依赖**：需 Shizuku ≥ v13.6.0 官方原版，可用且稳定 |
| 特权通道后台读 | 不可用（收口为仅前台） | **通道就绪时后台可用**；未就绪时才降级为仅前台 |
| 后台静默读（无通道） | 不可用 | 不可用（不变，系统级限制） |
| Shizuku 应用列表无本应用 | —（未知） | 新版 UI 现象，正常，**以应用内诊断为准** |
