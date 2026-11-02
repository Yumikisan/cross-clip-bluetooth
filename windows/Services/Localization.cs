using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Globalization;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Very small two-language resource system. Bindings use the indexer syntax, e.g.
    /// <c>Text="{Binding Source={x:Static services:I18n.Instance}, Path=[btn.start]}"</c>.
    /// Changing <see cref="Language"/> raises PropertyChanged("Item[]") so every binding refreshes.
    /// </summary>
    public sealed class I18n : INotifyPropertyChanged
    {
        private static readonly I18n _instance = new();
        public static I18n Instance => _instance;

        public const string Chinese = "zh-Hans";
        public const string English = "en";

        private static readonly Dictionary<string, (string En, string Zh)> Map = new()
        {
            // Shell
            ["app.title"] = ("Cross Clip", "Cross Clip"),
            ["app.subtitle"] = (
                "Bluetooth clipboard bridge — cross-device sync over Bluetooth only",
                "蓝牙剪贴板桥——纯蓝牙的跨设备剪贴板同步"),
            ["tab.devices"] = ("Devices", "设备"),
            ["tab.history"] = ("History", "历史"),
            ["tab.files"] = ("Files", "文件"),
            ["tab.settings"] = ("Settings", "设置"),
            ["lbl.storage"] = ("Storage", "储存"),
            ["col.type"] = ("Type", "类型"),
            ["col.count"] = ("Count", "数量"),
            ["col.size"] = ("Size", "大小"),
            ["lbl.storageEmpty"] = ("No stored files yet", "暂无储存占用"),
            ["lbl.storageTotal"] = ("Total", "合计"),
            ["storage.clearKind"] = ("Delete all {0}", "删除全部{0}"),
            ["storage.clearKindConfirm"] = (
                "Delete every \"{0}\" entry and its stored file? This cannot be undone.",
                "要删除全部「{0}」记录及其文件吗？此操作无法撤销。"),
            ["storage.kindCleared"] = ("{0} removed", "已删除 {0} 项"),
            ["lbl.filesPicked"] = ("{0} files · {1}", "{0} 个文件 · {1}"),
            ["status.filesQueued"] = ("Queued {0} files, sending one by one", "已排队 {0} 个文件，将逐个发送"),
            ["kind.text"] = ("Text", "文本"),
            ["kind.link"] = ("Links", "链接"),
            ["kind.image"] = ("Images", "图片"),
            ["kind.video"] = ("Videos", "视频"),
            ["kind.audio.one"] = ("Audio", "音频"),
            ["kind.doc"] = ("Documents", "文档"),
            ["kind.archive"] = ("Archives", "压缩包"),
            ["kind.installer"] = ("Installers", "安装包"),
            ["kind.fileother"] = ("Other files", "其他文件"),
            ["btn.cleanNow"] = ("Clean now", "手动清理"),
            ["lbl.cleanConfirm"] = ("Delete all received files on disk? This cannot be undone.", "将删除磁盘上全部已接收的文件，且不可恢复，确认继续？"),
            ["lbl.autoClean"] = ("Auto clean", "自动清理"),
            ["lbl.expireClean"] = ("Clean expired files", "过期自动清理"),
            ["lbl.imageRetention"] = ("Keep images", "图片保留期限"),
            ["lbl.fileRetention"] = ("Keep files", "文件保留期限"),
            ["ret.1d"] = ("1 day", "1 天"),
            ["ret.1w"] = ("1 week", "1 周"),
            ["ret.1m"] = ("1 month", "1 个月"),
            ["ret.3m"] = ("3 months", "3 个月"),
            ["ret.never"] = ("Forever", "永久"),
            ["lbl.thresholdClean"] = ("Clean by storage threshold", "按容量阈值清理"),
            ["lbl.maxStorage"] = ("Max storage (MB)", "最大存储容量（MB）"),
            ["lbl.cleanupPercent"] = ("Cleanup percent", "清理百分比"),
            ["app.lang"] = ("中文", "EN"),

            // Buttons
            ["btn.refresh"] = ("Refresh", "刷新设备"),
            ["btn.start"] = ("Start Service", "启动服务"),
            ["btn.stop"] = ("Stop Service", "停止服务"),
            ["btn.share"] = ("Share Clipboard", "发送剪贴板"),
            ["btn.sendFile"] = ("Send File", "发送文件"),
            ["btn.clearHistory"] = ("Clear", "清空"),
            ["btn.copy"] = ("Copy", "复制"),
            ["btn.delete"] = ("Delete", "删除"),
            ["btn.openFile"] = ("Open", "打开"),
            ["btn.sendFolder"] = ("Send folder", "发送文件夹"),
            ["dlg.folderTitle"] = ("Pick the folder to send", "选择要发送的文件夹"),
            ["status.folderSending"] = ("Sending folder {0}…", "正在发送文件夹 {0}…"),
            ["status.folderSent"] = ("Folder sent to {0} device(s)", "文件夹已发送到 {0} 台设备"),
            ["status.folderSkipped"] = ("Skipped {0} file(s) over the {1} limit", "已跳过 {0} 个文件（超过 {1} 上限）"),
            ["status.folderAllTooLarge"] = ("Nothing to send: all {0} file(s) are over the {1} limit", "没有可发送的文件：{0} 个文件全部超过 {1} 上限"),
            ["btn.openFolder"] = ("Folder", "所在文件夹"),
            ["btn.pickFile"] = ("Choose File…", "选择文件…"),
            ["btn.save"] = ("Save", "保存"),
            ["btn.cancel"] = ("Cancel", "取消"),
            ["btn.ok"] = ("OK", "确定"),
            ["close.title"] = ("Close CBB", "关闭 CBB"),
            ["close.question"] = (
                "Choose what happens when the window is closed. You can change this anytime in Settings.",
                "选择关闭窗口后的行为，之后可随时在设置中修改。"),
            ["close.toTray"] = ("Minimize to the notification area when closed", "关闭时最小化到系统托盘"),
            ["btn.browse"] = ("Browse…", "浏览…"),

            // Labels
            ["lbl.selectDevices"] = ("Select devices to share clipboard with", "选择要同步的设备"),
            ["lbl.noPairedDevices"] = ("No paired devices yet. Pair one in Windows Bluetooth settings first.", "还没有已配对的设备，请先在 Windows 蓝牙设置里配对。"),
            ["btn.done"] = ("Done", "完成"),

            // ---- device manager
            ["devkind.phone"] = ("Phone", "手机"),
            ["devkind.tablet"] = ("Tablet", "平板"),
            ["devkind.computer"] = ("Computer", "电脑"),
            ["devkind.audio"] = ("Audio device", "音频设备"),
            ["devkind.other"] = ("Other device", "其他设备"),
            ["dev.count"] = ("{0} devices · {1} selected", "共 {0} 台 · 已选 {1} 台"),
            ["dev.trusted"] = ("Always trusted", "已始终信任"),
            ["dev.hidden"] = ("Hidden", "已隐藏"),
            ["dev.settings"] = ("Device settings", "设备设置"),
            ["dev.mayNotSupport"] = ("This device probably can not sync clipboards", "此设备可能不支持剪贴板同步"),
            ["dev.settingsTitle"] = ("Device settings", "设备设置"),
            ["dev.alwaysTrusted"] = ("Always trust", "始终信任"),
            ["dev.alwaysTrustedHint"] = ("Skips the code prompt when connecting. Must be turned on on BOTH devices to avoid the code.", "此设备连接时不再弹确认码。需要两端都开启「始终信任」才能跳过核对码。"),
            ["dev.autoReconnect"] = ("Reconnect automatically", "自动重连"),
            ["dev.autoReconnectHint"] = ("Redial whenever the link drops. Off = connect only on refresh or restart.", "断线后自动重连。关掉后只在启动或点「刷新」时连一次。"),
            ["dev.fileSync"] = ("Sync files with this device", "与此设备同步文件"),
            ["dev.fileSyncHint"] = ("Turn off to send clipboard text only to this device.", "关掉后只向这台设备发文字剪贴板，不发文件。"),
            ["dev.hide"] = ("Hide from the list", "在列表中隐藏"),
            ["dev.hideHint"] = ("Keeps working if selected; just collapses into the Hidden group.", "已选中时仍会同步，只是收进「已隐藏」分组。"),
            ["dev.forget"] = ("Forget this device", "移除此设备"),
            ["dev.forgetConfirm"] = ("Remove {0} from this app? The Windows pairing is kept.", "要从本应用移除「{0}」吗？Windows 的配对不会解除。"),
            ["dev.kind"] = ("Device type", "设备类型"),
            ["dev.kindHint"] = ("Overrides the system-detected type; \"Auto\" infers it from the Bluetooth class and the name.", "覆盖系统识别的类型；「自动」按蓝牙类别和设备名推断。"),
            ["dev.kindAuto"] = ("Auto", "自动"),
            ["status.deviceForgotten"] = ("Removed {0}", "已移除 {0}"),
            ["lbl.search"] = ("Search history…", "搜索历史记录…"),
            ["lbl.historyEmpty"] = ("Nothing here yet", "还没有记录"),
            ["lbl.historyNoMatch"] = ("No matching entries", "没有匹配的记录"),
            ["lbl.filterAll"] = ("All", "全部"),
            ["lbl.filterText"] = ("Text", "文本"),
            ["lbl.filterFile"] = ("Files", "文件"),
            ["lbl.settings"] = ("Settings", "设置"),
            ["lbl.general"] = ("General", "通用"),
            ["lbl.security"] = ("Privacy & Security", "隐私与安全"),
            ["lbl.confirmCode"] = ("Require a confirmation code", "连接前需要双端确认码"),
            ["lbl.confirmCodeHint"] = (
                "Both devices show the same 6-digit code and both must confirm it before any clipboard text or file is accepted",
                "建链后两端会显示同一个 6 位确认码，双方都确认后才允许传输剪贴板文本或文件"),
            // ---- layer 2: PSK + AES-256-GCM ----
            ["lbl.secMode"] = ("Encryption", "二层加密"),
            ["lbl.secModeHint"] = (
                "App-level encryption on top of the Bluetooth link. Both ends must hold the same passphrase.",
                "在蓝牙链路加密之上再加一层应用层加密。两端必须配置同一个预共享密钥。"),
            ["lbl.secModeAuto"] = ("Auto (encrypt when keyed)", "自动（有密钥就加密）"),
            ["lbl.secModeRequire"] = ("Require (no key, no link)", "强制（无密钥不连接）"),
            ["lbl.secModeOff"] = ("Off (plaintext)", "关闭（明文）"),
            ["lbl.psk"] = ("Pre-shared key", "预共享密钥"),
            ["lbl.pskMinLen"] = (
                "At least 12 characters; both devices must use exactly the same one",
                "至少 12 个字符；两端必须填写完全相同的密钥"),
            ["psk.placeholder"] = ("Enter a pre-shared key", "输入预共享密钥"),
            ["psk.confirmClearTitle"] = ("Clear the key", "清除密钥"),
            ["psk.confirmClear"] = (
                "This removes the key stored on this device. Encrypted sync will stop working until you enter the same key again on both devices.\n\nClear it now?",
                "将删除本机保存的密钥。在两端重新输入同一密钥之前，加密同步将无法使用。\n\n确定清除？"),
            ["psk.show"] = ("Show key", "显示密钥"),
            ["psk.hide"] = ("Hide key", "隐藏密钥"),
            ["lbl.pskSave"] = ("Save", "保存"),
            ["lbl.pskClear"] = ("Clear", "清除"),
            ["lbl.secLog"] = ("Write security log", "记录安全日志"),
            ["lbl.secLogHint"] = (
                "Frame and handshake diagnostics go to %APPDATA%\\ClipSync\\security.log; payload text is never written in a release build",
                "帧与握手诊断写入 %APPDATA%\\ClipSync\\security.log；正式版不会写入任何载荷内容"),
            ["psk.notSet"] = (
                "Not configured — links stay in plaintext until both ends share a key",
                "尚未设置——两端配置同一个密钥之前，链路保持明文"),
            ["psk.notSetShort"] = ("not set", "未设置"),
            ["psk.setShort"] = ("set", "已设置"),
            ["psk.set"] = ("Configured (fingerprint {0})", "已配置（指纹 {0}）"),
            ["psk.minLen"] = (
                "At least {0} characters; both devices must use exactly the same one",
                "至少 {0} 个字符；两端必须填写完全相同的密钥"),
            ["psk.tooShort"] = (
                "Use at least {0} characters — this passphrase is the only thing protecting the traffic",
                "请至少使用 {0} 个字符——这串密钥是保护通信的唯一凭据"),
            ["psk.storeFailed"] = (
                "The key could not be stored on this device, so it was not saved. Try again.",
                "密钥在本机保存失败，未生效。请重试。"),
            ["psk.saved"] = ("Key saved on this device", "密钥已保存在本机"),
            ["psk.cleared"] = ("Key removed", "密钥已清除"),
            ["psk.clearedModeReset"] = (
                "Encryption is now set to Auto, because Require cannot work without a key",
                "加密模式已自动切回「自动」——「强制」在没有密钥时无法工作"),
            ["secMode.needKey"] = (
                "Set a pre-shared key first, otherwise Require mode refuses every connection",
                "请先设置预共享密钥，否则「强制」模式会拒绝所有连接"),
            ["secMode.state"] = ("Current: {0} · key {1}", "当前：{0} · 密钥{1}"),
            ["status.encrypted"] = ("encrypted", "已加密"),
            ["status.plaintext"] = ("plaintext", "明文"),
            ["lbl.connectedEncrypted"] = ("Connected · encrypted", "已连接 · 已加密"),
            ["status.secFailed"] = ("Connection refused: {0}", "连接被拒绝：{0}"),
            ["sec.fail.noPsk"] = ("no key configured on this device", "本机未配置密钥"),
            ["sec.fail.mismatch"] = ("key mismatch between the two devices", "两端密钥不一致"),
            ["sec.fail.legacyPeer"] = ("the other device is too old for encrypted links", "对端版本过旧，不支持加密链路"),
            ["sec.fail.timeout"] = ("handshake timed out", "握手超时"),
            ["sec.fail.other"] = ("security handshake failed", "安全握手失败"),

            // ---- first-run security wizard ----
            ["wiz.title"] = ("Security setup", "安全设置"),
            ["wiz.headline"] = (
                "Add a second encryption layer to your clipboard link",
                "为剪贴板链路再上一道加密"),
            ["wiz.subhead"] = (
                "Takes one minute. The key never leaves your devices.",
                "一分钟即可完成，密钥不会离开你的设备"),
            ["wiz.intro1"] = (
                "Bluetooth pairing only encrypts the radio between the two adapters. It does not prove which app is on the other end, and it cannot stop a replayed frame.",
                "蓝牙配对只加密两个适配器之间的射频。它既不证明对面跑的是哪个应用，也挡不住被重复发送的旧数据。"),
            ["wiz.intro2"] = (
                "That is why CBB adds its own layer: everything is encrypted again with a key only your two devices know, and every frame carries a sequence number so a replay is dropped.",
                "所以 CBB 自己再加一层：用只有你两台设备知道的密钥再加密一次，每帧带序号，重放的旧帧会被直接丢弃。"),
            ["wiz.intro3"] = (
                "Both devices must use exactly the same key. Set it here and on the phone — the wizard will show a fingerprint so you can check they match.",
                "两端必须填写完全相同的密钥。这里设置一次，手机上再设置一次——向导会给出密钥指纹，方便你核对两边是否一致。"),
            ["wiz.optGenerate"] = ("Generate a random key", "生成一串随机密钥"),
            ["wiz.optGenerateHint"] = (
                "Recommended. 80 bits, no look-alike characters, easy to read off one screen and type into the other.",
                "推荐。80 位随机，去掉了易混淆字符，方便从一台设备的屏幕抄到另一台。"),
            ["wiz.optExisting"] = ("I already have a key", "我已经有密钥了"),
            ["wiz.optExistingHint"] = (
                "Type the one the other device is already using.",
                "把另一端已经在用的那串填进来。"),
            ["wiz.optCreate"] = ("Create my own key", "自己创建一串密钥"),
            ["wiz.optCreateHint"] = (
                "Choose your own passphrase. Longer is better; a sentence beats a word.",
                "自己起一串密钥。越长越好，一句话胜过单个词。"),
            ["wiz.generatedTitle"] = ("Your new key", "你的新密钥"),
            ["wiz.generatedHint"] = (
                "On the phone open Settings → Security setup → \"I already have a key\" and type this in. The dashes are optional.",
                "在手机上打开 设置 → 安全设置 → 「我已经有密钥了」，把这串填进去。中间的横线可以不打。"),
            ["wiz.copy"] = ("Copy", "复制"),
            ["wiz.copied"] = ("Copied to clipboard", "已复制到剪贴板"),
            ["wiz.existingTitle"] = ("Paste the existing key", "填入已有的密钥"),
            ["wiz.existingHint"] = (
                "Exactly as the other device has it. Spaces and dashes are ignored.",
                "与另一端保持一致即可，空格和横线会被忽略。"),
            ["wiz.createTitle"] = ("Choose your own key", "自定义密钥"),
            ["wiz.createHint"] = (
                "At least {0} characters. Both devices must use the same one.",
                "至少 {0} 个字符，两端必须一致。"),
            ["wiz.suggest"] = ("Use a generated one instead", "改用系统随机生成"),
            ["wiz.fingerprint"] = (
                "Key fingerprint: {0} — the other device must show the same six characters",
                "密钥指纹：{0}——另一端显示的六位应当一模一样"),
            ["wiz.finish"] = ("Save key", "保存密钥"),
            ["wiz.later"] = ("Later", "以后再说"),
            ["wiz.storeFailed"] = (
                "The key could not be stored on this device, so it was not saved.",
                "密钥在本机保存失败，未生效。"),
            ["wiz.runFromSettings"] = ("Security setup guide", "安全设置引导"),
            ["wiz.savedHint"] = (
                "Key saved. Make sure the other device uses the same one — if it does not, links are refused and the device card says so.",
                "密钥已保存。请确认另一端填写了同一串——若不一致，连接会被拒绝，设备卡片上会显示原因。"),

            ["lbl.transfer"] = ("Transfer", "传输"),
            ["lbl.sendFileSection"] = ("Send a file", "发送文件"),
            // ---- help & feedback -------------------------------------------------------
            ["history_back_to_top"] = ("Back to top", "回到顶部"),
            ["lbl.support"] = ("Help & feedback", "帮助与反馈"),
            ["lbl.supportHint"] = ("Something not working? Start here — everything below runs locally, nothing is uploaded.", "遇到问题先看这里：以下操作全部在本机完成，不会上传任何内容。"),
            ["lbl.faq"] = ("Before you ask", "提问前先自查"),
            ["lbl.faqHint"] = ("The five that explain most reports.", "这几条能解决大部分反馈。"),
            ["faq.q1"] = ("Paired, but nothing syncs", "已配对，但内容同步不过去"),
            ["faq.a1"] = ("Open the devices page and check the link state. \"Connected, encrypted\" means the link is fine — then confirm both sides use the same passphrase and the same security mode.", "在设备页看链路状态。「已连接 · 已加密」说明链路正常，再确认两端密钥与安全模式一致。"),
            ["faq.q2"] = ("Copy on the phone never arrives", "手机上复制，电脑收不到"),
            ["faq.a2"] = ("Android 10+ forbids background clipboard reads unless Shizuku (or the accessibility helper) is running. Grant one of them, then make sure the app is not battery-restricted.", "Android 10+ 禁止后台读取剪贴板，必须让 Shizuku 或无障碍辅助保持运行，并关闭对该应用的电池限制。"),
            ["faq.q3"] = ("Encrypted mode refuses to connect", "加密模式一直连不上"),
            ["faq.a3"] = ("A passphrase mismatch looks exactly like a link failure. Re-enter the same key on both sides (12 characters or more), or switch to auto and connect once to verify the link itself.", "密钥不一致和连接故障表现一样。请两端重新输入同一串密钥（12 位以上），或先切到自动模式验证连接本身。"),
            ["faq.q4"] = ("A file is refused as too large", "文件提示超出上限"),
            ["faq.a4"] = ("The size cap applies to the sender and is enforced again on the receiver. Raise it in File transfer, or turn on \"ignore size limits\" for one-off big transfers.", "大小上限在发送端与接收端各生效一次。可在「文件传输」里调高，或临时打开「无视大小限制」。"),
            ["faq.q5"] = ("A received folder looks empty", "收到的文件夹是空的"),
            ["faq.a5"] = ("Empty sub-folders are kept only while the transfer completes. If the link dropped mid-way the folder may be partial — resend it; the receiving side never leaves half-written files.", "空子目录只在传输完整结束时保留。若中途断链，文件夹可能不完整，请重发；接收端不会留下写了一半的文件。"),
            ["lbl.feedback"] = ("Send feedback", "发送反馈"),
            ["lbl.feedbackHint"] = ("Pick a category, describe what happened, then copy the report. It already includes version and system details.", "选一个类别、写下发生了什么，然后复制反馈内容——里面已经带好版本与系统信息。"),
            ["lbl.feedbackKind"] = ("Category", "反馈类别"),
            ["fb.kind.bug"] = ("It does not work", "功能异常"),
            ["fb.kind.ui"] = ("Display or layout", "界面显示"),
            ["fb.kind.sync"] = ("Sync / transfer", "同步与传输"),
            ["fb.kind.idea"] = ("Suggestion", "功能建议"),
            ["lbl.feedbackDetail"] = ("What happened", "问题描述"),
            ["fb.detail.placeholder"] = ("What you did, what you expected, what happened instead", "你做了什么、期望怎样、实际怎样"),
            ["btn.copyReport"] = ("Copy report", "复制反馈内容"),
            ["btn.exportLogs"] = ("Export logs…", "导出日志…"),
            ["lbl.logsHint"] = ("Logs stay on this machine. The export writes a zip to your desktop so you can attach it yourself.", "日志只留在本机。导出会在桌面生成一个 zip，由你自行决定是否附上。"),
            ["btn.reportBug"] = ("Report on GitHub", "到 GitHub 提交"),
            ["btn.devHome"] = ("Developer", "开发者主页"),
            ["btn.forkHome"] = ("Fork home", "分支主页"),
            ["btn.sponsorUpstream"] = ("Sponsor the original author", "赞助原作者"),
            ["btn.sponsorFork"] = ("Sponsor this branch", "赞助本分支"),
            ["btn.helpDoc"] = ("Online help", "在线帮助"),
            ["msg.reportCopied"] = ("Report copied — paste it wherever you like", "反馈内容已复制，可直接粘贴"),
            ["msg.logsExported"] = ("Logs exported to {0}", "日志已导出到 {0}"),
            ["msg.logsEmpty"] = ("No logs to export yet", "暂无可导出的日志"),
            ["msg.logsFailed"] = ("Could not write the zip — is the desktop writable?", "无法写入 zip，请检查桌面是否可写"),
            ["lbl.aboutTitle"] = ("About", "关于"),
            ["about.blurb"] = ("An enhanced fork by Yumikisan: the Windows and Android apps are released together from this branch. The original Cross Clip is by aubynsamuel - this build adds to his work rather than replacing it. Bluetooth-only, no LAN, no cloud, no telemetry.", "这是 Yumikisan 的增强分支：Windows 端与安卓端由本分支一同发行。原项目 Cross Clip 由 aubynsamuel 开发，本分支是在他的成果上做增补，而不是另起一个项目。纯蓝牙、不走局域网、不上云、不收集任何数据。"),
            ["lbl.behaviour"] = ("Window & startup", "窗口与启动"),
            ["lbl.language"] = ("Language", "界面语言"),
            ["lbl.theme"] = ("Theme", "主题"),
            ["lbl.themeLight"] = ("Light", "浅色"),
            ["lbl.themeDark"] = ("Dark", "深色"),
            ["lbl.autoSync"] = ("Auto-sync clipboard", "自动同步剪贴板"),
            ["lbl.autoSyncHint"] = ("Send copied text to the selected devices automatically", "复制内容后自动发送到已选设备"),
            ["lbl.autoCopy"] = ("Auto copy received", "自动复制收到的内容"),
            ["lbl.autoCopyHint"] = ("Put content received from a device on the clipboard", "把设备发来的内容直接写入剪贴板"),
            ["lbl.notify"] = ("Show notifications", "显示通知"),
            ["lbl.notifyHint"] = ("Show a toast when content arrives or a transfer finishes", "收到内容或传输完成时弹出提示"),
            ["lbl.notifySeconds"] = ("Notification duration", "通知显示时长"),
            ["lbl.notifySecondsHint"] = ("Seconds a toast stays on screen (1-60)", "提示气泡停留的秒数（1-60）"),
            ["lbl.closeToTray"] = ("Close to tray", "关闭窗口时最小化到托盘"),
            ["lbl.minimizeToTray"] = ("Minimize to tray", "最小化到托盘"),
            ["lbl.startMinimized"] = ("Start minimized", "启动时最小化"),
            ["lbl.autoStart"] = ("Start with Windows", "开机自动启动"),
            ["lbl.rememberPlacement"] = ("Remember window position and size", "记忆窗口位置和大小"),
            ["lbl.openFileLocation"] = ("Open the containing folder on double-click", "双击条目时打开所在位置"),
            ["lbl.openFileLocationHint"] = (
                "A single click still copies the entry; a double click opens Explorer with it selected. Folders open themselves.",
                "单击仍然是复制；双击才打开资源管理器并选中该条目。文件夹条目双击直接打开文件夹"),
            ["lbl.ignoreSizeLimit"] = ("Ignore size limit", "忽略大小上限"),
            ["lbl.ignoreSizeLimitHint"] = ("Stop blocking transfers over the limit. Classic Bluetooth is slow, so use it for one-off cases.", "不再按上限拦截传输。经典蓝牙吞吐有限，建议只在临时需要时开启。"),
            ["lbl.maxFileSize"] = ("Max file size", "文件大小上限"),
            ["lbl.maxFileSizeHint"] = ("Files larger than this are refused before any transfer starts", "超过该大小的文件会在传输开始前被拒绝"),
            ["lbl.adaptive"] = ("Adapt to link quality", "根据链路质量自适应"),
            ["lbl.adaptiveHint"] = ("Lower the limit automatically when Bluetooth is slow or unstable", "蓝牙链路差时自动收紧上限，优先保证响应速度"),
            ["lbl.autoFileSync"] = ("Sync files in clipboard", "同步剪贴板中的文件"),
            ["lbl.autoFileSyncHint"] = (
                "Copy a file or an image and it is transferred as a file, not as a path",
                "复制文件或截图时，直接传输文件内容而不是路径"),
            ["lbl.autoHidePeripherals"] = ("Auto-hide audio & input devices", "自动隐藏音频、键鼠设备"),
            ["lbl.autoHidePeripheralsHint"] = ("Sweep headphones, speakers, keyboards and mice into Hidden, judged by Bluetooth class and name.", "按蓝牙类别和名称，把耳机、音箱、键鼠收进「已隐藏」组。"),
            ["lbl.downloadFolder"] = ("Save folder", "接收目录"),
            ["lbl.historyLimit"] = ("History entries", "历史记录条数"),
            ["lbl.historyLimitRange"] = ("20 – 2000 (type a number)", "20 – 2000（直接输入数字）"),
            ["lbl.historyCardMode"] = ("Card view (two columns)", "卡片视图（两列）"),
            ["lbl.historyLines"] = ("Preview line limit (1 – 8)", "每条显示行数上限（1 – 8）"),
            ["lbl.viewCard"] = ("Switch to card view", "切换到卡片视图"),
            ["lbl.viewList"] = ("Switch to list view", "切换到列表视图"),
            ["lbl.layoutList"] = ("List", "列表"),
            ["lbl.layoutCard"] = ("Cards", "卡片"),
            ["lbl.historyTotal"] = ("{0} items", "共 {0} 项"),

            // Personalisation section — size, colour, spacing, source label
            ["lbl.appearance"] = ("Appearance", "外观"),
            ["lbl.appearanceHint"] = (
                "How history entries look. Changes apply immediately.",
                "历史条目的显示方式，改动立即生效。"),
            ["lbl.darkMode"] = ("Dark mode", "深色模式"),
            ["lbl.entryFont"] = ("Entry text size", "条目字号"),
            ["lbl.fontSmall"] = ("Small", "小"),
            ["lbl.fontNormal"] = ("Standard", "标准"),
            ["lbl.fontLarge"] = ("Large", "大"),
            ["lbl.accentMode"] = ("Type colour bar", "分类标识色"),
            ["lbl.accentHint"] = (
                "Colour of the bar on the left of each entry.",
                "条目左侧色条的取色方式。"),
            ["lbl.accentKind"] = ("By type", "按类型"),
            ["lbl.accentCustom"] = ("Custom", "自定义"),
            ["lbl.editColors"] = ("Set colours by type…", "分类型设置颜色…"),
            ["lbl.accentCustomTitle"] = ("Colours by type", "分类型颜色"),
            ["lbl.accentCustomHint"] = (
                "Pick the bar colour for each text format and file kind. Unset types keep the default.",
                "为每种文本类型、每种文件类型分别取色；没设置的类型沿用默认色。"),
            ["lbl.pickColor"] = ("Pick a colour", "选择颜色"),
            ["lbl.resetDefaults"] = ("Restore defaults", "恢复默认"),
            ["lbl.density"] = ("List spacing", "列表间距"),
            ["lbl.densityCompact"] = ("Compact", "紧凑"),
            ["lbl.densityNormal"] = ("Standard", "标准"),
            ["lbl.densityCozy"] = ("Relaxed", "宽松"),
            ["lbl.showSource"] = ("Show source device name", "显示同步来源设备的名称"),
            ["lbl.showSourceHint"] = (
                "Off hides the device name under each entry; useful with a single phone.",
                "关闭后每条记录下方不再显示设备名，只与一台设备同步时更清爽。"),
            ["lbl.thumbnails"] = ("Image thumbnails", "图片缩略图"),
            ["lbl.thumbnailsHint"] = (
                "Shows a 4:3 preview on image entries. Decoded once and kept in memory.",
                "为图片条目显示 4:3 预览图。只解码一次并缓存在内存中。"),
            ["lbl.closeFilterOnReset"] = ("Close filter after Reset", "重置筛选后关闭窗口"),
            ["lbl.closeFilterOnResetHint"] = (
                "Off keeps the panel open with the conditions cleared.",
                "关闭后点「重置」只清空条件，筛选窗口保持打开。"),

            // History filter panel — mirrors the Android filter sheet
            ["lbl.filterMore"] = ("Filter", "筛选"),
            ["lbl.filterTitle"] = ("Filters", "筛选条件"),
            ["lbl.filterTextGroup"] = ("Text entries", "文本筛选"),
            ["lbl.filterFileGroup"] = ("File entries", "文件筛选"),
            ["lbl.filterLength"] = ("Text length", "文本长度"),
            ["lbl.filterSize"] = ("File size", "文件大小"),
            ["lbl.filterKeyword"] = ("Contains text", "包含内容"),
            ["lbl.filterIgnoreCase"] = ("Ignore case", "忽略大小写"),
            ["lbl.filterTime"] = ("Synced at", "同步时间"),
            ["lbl.filterExt"] = ("Extension", "文件后缀"),
            ["lbl.filterExtHint"] = (
                ".png shows only that type; the other file filters are then ignored",
                "填 .png 只显示该类型，此时忽略其余文件筛选条件"),
            ["lbl.filterReset"] = ("Reset", "重置"),
            ["lbl.filterApply"] = ("Apply", "应用"),
            ["lbl.filterDayHint"] = ("yyyy-MM-dd", "年-月-日"),
            ["filter.dayPlaceholder"] = ("yyyy-MM-dd or MM-dd", "yyyy-mm-dd 或 mm-dd"),
            ["filter.keywordPlaceholder"] = ("Contains this text", "输入要包含的关键字"),
            ["lbl.unlimited"] = ("unlimited", "不限"),
            ["lbl.modeAny"] = ("Any", "不限"),
            ["lbl.modeAtMost"] = ("At most", "不超过"),
            ["lbl.modeAtLeast"] = ("At least", "不少于"),
            ["lbl.modeBetween"] = ("Between", "介于"),
            ["lbl.timeAny"] = ("Any time", "不限时间"),
            ["lbl.time5min"] = ("Last 5 min", "5 分钟内"),
            ["lbl.timeHour"] = ("Last hour", "1 小时内"),
            ["lbl.timeToday"] = ("Today", "今天"),
            ["lbl.timeYesterday"] = ("Yesterday", "昨天"),
            ["lbl.timeCustom"] = ("Pick a day", "指定日期"),
            ["lbl.unitKb"] = ("KB", "KB"),
            ["lbl.openInFolder"] = ("Click to open the folder", "点击打开所在文件夹"),

            ["lbl.alwaysOnTop"] = ("Always on top", "窗口置顶"),
            ["lbl.minimize"] = ("Minimize", "最小化"),
            ["lbl.maximize"] = ("Maximize", "最大化"),
            ["lbl.restore"] = ("Restore", "还原"),
            ["lbl.close"] = ("Close", "关闭"),
            ["lbl.openHotkey"] = ("Show window hotkey", "唤起窗口快捷键"),
            ["lbl.dropHint"] = ("Drop files or folders here to send them to the selected device",
                "拖放文件或整个文件夹到窗口即可发送到已选设备"),
            ["lbl.dropNoDevice"] = ("Select a device first, then drop the file",
                "请先勾选接收设备，再拖入文件"),
            ["lbl.hotkeyUnset"] = ("Not set", "未设置"),
            ["lbl.hotkey"] = ("Hotkey", "快捷键"),
            ["lbl.hotkeyDesc"] = ("Click the box and press the combination (needs Ctrl / Alt / Shift / Win)", "点进输入框后直接按下组合键（必须含 Ctrl / Alt / Shift / Win）"),
            ["lbl.hotkeyClear"] = ("Clear", "清除"),
            ["lbl.openPage"] = ("Page opened on summon", "唤起时打开的页面"),
            ["lbl.openPageAll"] = ("All entries", "全部记录"),
            ["lbl.openPageText"] = ("Text only", "仅文本"),
            ["lbl.openPageFile"] = ("Files only", "仅文件"),

            ["fmt.markdown"] = ("Markdown", "Markdown"),
            ["fmt.code"] = ("Code", "代码"),
            ["fmt.url"] = ("Link", "网址"),
            ["fmt.number"] = ("Number", "数字"),
            ["fmt.letter"] = ("Letters", "字母"),
            ["fmt.plain"] = ("Text", "文本"),
            ["kind.image.one"] = ("Image", "图片"),
            ["kind.video.one"] = ("Video", "视频"),
            ["kind.audio"] = ("Audio", "音频"),
            ["kind.document"] = ("Document", "文档"),
            ["kind.archive.one"] = ("Archive", "压缩包"),
            ["kind.package"] = ("Installer", "安装包"),
            ["kind.other"] = ("File", "文件"),
            ["kind.folder"] = ("Folder", "文件夹"),
            ["lbl.linkQuality"] = ("Link", "链路"),
            ["lbl.effectiveLimit"] = ("Current limit", "当前上限"),
            ["lbl.version"] = ("Version", "版本"),
            ["lbl.about"] = ("Bluetooth only. No LAN, no cloud, no account.", "仅使用蓝牙，不走局域网、不上云、不需要账号"),
            ["lbl.trayHint"] = ("CBB is still running in the notification area", "CBB 仍在托盘中运行"),
            ["lbl.chars"] = ("chars", "字符"),
            ["lbl.dir.local"] = ("This PC", "本机"),
            ["lbl.dir.in"] = ("Received", "接收"),
            ["lbl.dir.out"] = ("Sent", "发送"),
            ["lbl.quality.unknown"] = ("measuring…", "测速中…"),
            ["lbl.quality.good"] = ("good", "良好"),
            ["lbl.quality.fair"] = ("fair", "一般"),
            ["lbl.quality.poor"] = ("poor", "较差"),
            ["lbl.connected"] = ("Connected", "已连接"),
            ["lbl.disconnected"] = ("Not connected", "未连接"),

            // Status messages
            ["status.ready"] = ("Ready", "就绪"),
            ["status.loading"] = ("Loading paired devices…", "正在读取已配对设备…"),
            ["status.loaded"] = ("Paired devices: {0}", "已配对设备：{0} 台"),
            ["status.starting"] = ("Starting service…", "正在启动服务…"),
            ["status.listening"] = ("Service running — listening for devices", "服务运行中 — 正在等待设备连接"),
            ["status.stopped"] = ("Service stopped", "服务已停止"),
            ["status.connected"] = ("Connected: {0}", "已连接：{0}"),
            ["status.disconnected"] = ("Disconnected: {0}", "已断开：{0}"),
            ["status.sending"] = ("Sending clipboard…", "正在发送剪贴板…"),
            ["status.sent"] = ("Clipboard sent to {0} device(s)", "剪贴板已发送到 {0} 台设备"),
            ["status.received"] = ("Received from {0}", "已接收来自 {0} 的内容"),
            ["status.receivedFile"] = ("Received file: {0}", "已接收文件：{0}"),
            ["status.clipboardEmpty"] = ("Clipboard is empty", "剪贴板是空的"),
            ["status.noSelection"] = ("Select at least one device first", "请先选择至少一个设备"),
            ["status.fileTooLarge"] = ("File is over the size limit ({0})", "文件超过大小上限（{0}）"),
            ["status.fileSending"] = ("Sending {0}…", "正在发送 {0}…"),
            ["status.fileSent"] = ("File sent to {0} device(s)", "文件已发送到 {0} 台设备"),
            ["status.fileGone"] = ("That record has no local copy any more", "这条记录在本机已没有副本"),
            ["status.fileSkippedLarge"] = (
                "{0} skipped: larger than {1}",
                "{0} 已跳过：超过 {1} 的上限"),
            ["status.copied"] = ("Copied to clipboard", "已复制到剪贴板"),
            ["status.historyCleared"] = ("History cleared", "历史记录已清空"),
            ["status.error"] = ("Error: {0}", "错误：{0}"),
            ["status.btOff"] = ("Bluetooth is off", "蓝牙未开启"),
            ["status.connFailed"] = ("Could not connect to {0}", "无法连接到 {0}"),

            // Actionable Bluetooth failures — raw Winsock text is useless to the user
            ["status.errBtOff"] = (
                "Computer Bluetooth is off or not ready. Turn Bluetooth on in Windows settings, then retry.",
                "电脑蓝牙未开启或尚未就绪。请在 Windows 设置里打开蓝牙，然后重新点一次「启动服务」。"),
            ["status.errNotInRange"] = (
                "Device unreachable. Make sure its Bluetooth is on, it is nearby and it is paired.",
                "设备连不上。请确认对方蓝牙已打开、就在附近，并且已经和这台电脑配对。"),
            ["status.errTimeout"] = (
                "Connection timed out — the device did not answer.",
                "连接超时 — 对方没有响应。"),
            ["status.noPairedDevices"] = (
                "No paired devices found. Pair your phone in Windows Bluetooth settings first.",
                "未找到已配对设备。请先在 Windows 蓝牙设置里把手机配对。"),
            ["status.btNotReadyRetry"] = (
                "Bluetooth not ready yet — retrying in the background…",
                "蓝牙尚未就绪 — 正在后台自动重试…"),
            ["status.fileNoAck"] = (
                "The other side never confirmed the transfer",
                "对方没有确认收到文件（传输超时）"),
            ["status.transferCancelled"] = ("Transfer cancelled", "传输已取消"),
            ["status.peerStalled"] = (
                "The other side stopped responding, the link was reset",
                "对方长时间没有响应，连接已重置"),
            ["lbl.timeLeft"] = ("left", "剩余"),
            ["status.fileRejected"] = (
                "The other side refused the file",
                "对方拒绝了这次文件传输"),
            ["status.fileCannotSave"] = (
                "Could not save the file: {0}",
                "对方无法保存文件：{0}"),
            ["status.fileMissing"] = (
                "Transfer state was lost before the file finished",
                "传输中断，接收方丢失了这次传输的上下文"),
            ["status.fileReceivedSaved"] = ("Saved to {0}", "已保存到 {0}"),
            ["status.fileCorrupt"] = (
                "The file arrived damaged and was discarded (checksum mismatch)",
                "文件校验不通过，已丢弃（内容在传输中损坏）"),
            ["status.folderStarted"] = ("Receiving folder: {0}", "正在接收文件夹：{0}"),
            ["status.folderReceived"] = ("Received folder: {0}", "已接收文件夹：{0}"),
            ["lbl.folderFiles"] = ("{0} files", "{0} 个文件"),
            ["lbl.dropToSend"] = ("Drop files or folders to send", "松开即可发送文件或文件夹"),

            // Connection confirmation
            ["status.authWaiting"] = (
                "Waiting for the code to be confirmed on {0}…",
                "等待在 {0} 上确认连接码…"),
            ["status.authOk"] = ("Confirmed connection with {0}", "已确认与 {0} 的连接"),
            ["status.authAutoAccepted"] = ("Connected {0} automatically (trusted)", "已按信任设置自动接入 {0}"),
            ["status.fileSyncOff"] = ("File sync is off for the selected devices", "所选设备已关闭文件同步"),
            ["status.fileSendDisabled"] = ("File transfer is disabled in the settings", "已在设置中关闭文件传输"),
            ["status.authRejected"] = ("Connection rejected: {0}", "连接已拒绝：{0}"),
            ["status.authRejectedLocal"] = ("you rejected the code", "你拒绝了这次连接"),
            ["status.authRejectedPeer"] = ("the other device rejected the code", "对方拒绝了这次连接"),
            ["status.authTimeout"] = ("no confirmation in time", "超时未确认"),
            ["status.authMismatch"] = (
                "the codes did not match — possible interference, link closed",
                "两端确认码不一致 — 链路可能被劫持，已断开"),
            ["status.authPeerUnsupported"] = (
                "the other device does not support confirmation codes; update it to 2.0 or turn the code off here",
                "对端版本不支持确认码；请把对端也升级到 2.0，或在设置里关闭确认码"),
            ["status.authPending"] = (
                "Waiting for the connection code to be confirmed",
                "连接尚未确认，暂时不能传输"),

            // Dialogs
            ["dlg.error"] = ("Error", "错误"),
            ["dlg.fileTitle"] = ("Choose a file to send", "选择要发送的文件"),
            ["dlg.allFiles"] = ("All files", "所有文件"),
            ["dlg.clearHistory"] = ("Clear all history entries?", "确定清空全部历史记录？"),
            ["dlg.trayTitle"] = ("CBB", "CBB"),
            ["dlg.trayShow"] = ("Open CBB", "打开 CBB"),
            ["dlg.trayStart"] = ("Start service", "启动服务"),
            ["dlg.trayStop"] = ("Stop service", "停止服务"),
            ["dlg.trayExit"] = ("Quit", "退出"),

            // Confirmation code dialog
            ["dlg.authTitle"] = ("Confirm connection", "连接确认"),
            ["dlg.authPeer"] = ("{0} wants to sync the clipboard with this PC", "「{0}」请求与这台电脑同步剪贴板"),
            ["dlg.authInstruction"] = (
                "Check that the other device shows exactly the same 6-digit code. Only confirm when both match.",
                "请确认另一台设备上显示的 6 位数字与此处完全相同。只有在两端一致时才点「确认」。"),
            ["dlg.authCountdown"] = ("Auto-reject in {0} s", "{0} 秒后自动拒绝"),
            ["dlg.authAccept"] = ("Confirm", "确认"),
            ["dlg.authReject"] = ("Reject", "拒绝"),
        };

        private string _language = Chinese;

        private I18n() { }

        public string Language
        {
            get => _language;
            set
            {
                if (value != Chinese && value != English) value = Chinese;
                if (_language == value) return;
                _language = value;
                CultureInfo.CurrentUICulture = new CultureInfo(value);
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs("Item[]"));
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Language)));
                LanguageChanged?.Invoke(this, EventArgs.Empty);
            }
        }

        public bool IsChinese => _language == Chinese;

        public event EventHandler? LanguageChanged;

        /// <summary>Indexer used by XAML bindings.</summary>
        public string this[string key] => T(key);

        public string T(string key)
        {
            if (!Map.TryGetValue(key, out var pair)) return key;
            return IsChinese ? pair.Zh : pair.En;
        }

        public string T(string key, params object[] args)
        {
            var fmt = T(key);
            try { return string.Format(fmt, args); }
            catch (FormatException) { return fmt; }
        }

        public void Toggle()
        {
            Language = IsChinese ? English : Chinese;
        }

        public event PropertyChangedEventHandler? PropertyChanged;
    }

    public static class Loc
    {
        public static string T(string key) => I18n.Instance.T(key);
        public static string T(string key, params object[] args) => I18n.Instance.T(key, args);
    }
}
