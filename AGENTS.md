# 守护侠（ShouHuXia）— 开发约定

守护侠是独立 Android 应用「守护侠」（`com.jiarenxia.guardian`，debug 加 `.debug`），
2026-10-23 自家人侠仓库拆出，仓库 xiaoHaoZ1/ShouHuXia。
设计文档：`docs/GUARDIAN_MODULE.md`（真机回归清单见第 11 节）。
**换电脑 / 新会话先读 `docs/HANDOFF.md`**（本机交接记录，不入库；没有该文件就跳过）；收尾时更新它。

## 开发环境

- 需要 JDK 17（Temurin 17 已验证）与 Android SDK。`local.properties` 写 `sdk.dir`
  指向本机 SDK（不入库）：`.properties` 里单反斜杠是转义符会被吞掉，用正斜杠
  （`sdk.dir=D:/AndroidDev/sdk`）或写双反斜杠。
- 在类 Unix 会话（WSL2 等）里调 Windows 工具链用 `cmd.exe /c`；在 Git Bash 里须写
  `cmd.exe //c`（`/c` 会被 MSYS 转成 `C:\`，命令只见 cmd 横幅、静默不执行）。
- JDK 不在 PATH 时构建命令加前缀：`set JAVA_HOME=<JDK17 路径>&& gradlew.bat <任务>`
  （cmd 里 `set X=Y&&` 的 `&&` 前不能留空格，否则值带尾部空格）。
- Gradle wrapper 缓存损坏报 `Cannot find JAR 'kotlin-compiler-embeddable-...'`：删掉
  `%USERPROFILE%\.gradle\wrapper\dists\<对应版本>\` 下的损坏目录，重新放入发行包并
  `touch <版本>.ok`。
- GitHub 直连不通时在命令上加 `-c http.proxy=http://127.0.0.1:<本机代理端口>`
  （HTTP 代理端口，别写成 SOCKS 口；不写进 git config）。WSL 里的 git 没有
  GitHub 凭据时，改用 Windows 侧 git 经 `cmd.exe /c` 执行推拉。

## 模块结构

- `:guardianapp` — 应用模块，只依赖 `:feature:guardian` 与 `:core:designsystem`。
- `:feature:guardian` — 守护模块（无活动检测 + 紧急联系）。不依赖 Hilt/Room。
- `:core:designsystem` — 主题与通用组件，拆分时自家人侠复制的快照，两边各自演进。

## 构建 / 测试

Gradle 根目录就是仓库根目录。完整验证任务：

```
:feature:guardian:testDebugUnitTest :guardianapp:testDebugUnitTest :guardianapp:lintDebug :guardianapp:assembleDebug :guardianapp:assembleRelease
```

Windows 下经 cmd 调用：`cmd.exe /c "cd /d <仓库根> && gradlew.bat <任务>"`。

- `local.properties` 的 `sdk.dir` 指向本机 Android SDK（不入库）。
- `--tests` 模式经 `cmd.exe` 时不要加引号。
- APK 输出：`guardianapp/build/outputs/apk/`。
- 截图测试（Roborazzi，record 模式，产物 `guardianapp/build/outputs/roborazzi/g_*.png`）随 `:guardianapp:testDebugUnitTest` 一起运行，没有跳过开关；改界面后要打开相关 PNG 看一眼。
- 安装到设备：`adb.exe -s <真机序列号> install -r guardianapp/build/outputs/apk/debug/guardianapp-debug.apk`；小米/HyperOS 需开「USB 安装」。

## 测试分工（项目规则）

- **代码测试由模型自己完成**：单元测试、Robolectric/截图测试、lint、debug/release 构建，改完代码必须亲自跑完整验证任务并全部通过，失败要自己修，不得把"请用户跑一下构建/单测"当作交付。
- **真机上一步一步的操作测试交给用户**：安装 APK、锁屏静置、观察响铃与全屏页、接听电话、去系统设置里开关权限、重启手机等，模型不要自己用 adb 或其他方式代为操作手机。模型的职责是把这些整理成**编号的逐步操作清单**交给用户：每步写清楚操作、预期现象、不符合时记录什么（截图 / 守护记录页内容）。
- 真机步骤清单随交付一起给出；用户反馈结果后，模型再根据反馈定位和修复。
- 尚未完成的真机验证统一记在 `docs/DEVICE_VERIFY_TODO.md`：新改动需要真机验证时追加到该文件，用户验证后勾选；开始新的真机相关工作前先看一眼其中未勾选的项。

## 正式版签名

与家人侠共用同一把密钥：工程根目录放 `keystore.properties` 与 `jiarenxia-release.jks`
（均已 .gitignore，不入库）。
文件缺失时 release 保持未签名，绝不回落到 debug 签名。
证书 SHA-256：`00:15:22:AE:2C:13:CB:3B:BD:2F:38:CB:FA:49:FB:32:2D:5B:59:91:85:93:F5:67:A2:C0:65:E2:61:F8:75:D0`。

## 守护模块硬性约定

- 不依赖 Hilt/Room；引擎唯一入口 `GuardEngine.handle(Event)`，未来时序全部走 AlarmManager。
- Direct Boot 安全：Application 只初始化守护模块（device-protected 存储）。
- 日志与 EventLog 中的电话号码一律脱敏（`maskNumber`）。
- 使用会话（inUse）由引擎统一判定：解锁开始、锁屏结束；使用中不计倒计时，使用中上限 = 正常阈值、不受开发快速阈值影响。
- 状态通知只能有一条：前台服务通知（无 tag，渠道 `guardian_status_quiet`）；演练通知走 `guardian_confirm` 渠道带 tag，演练结束必须清除。
- 紧急联系人 1–3 位有序；开启守护必须由引擎层门槛判定：列表非空 + 当前设备的有效演练记录（通过 + 等待 ≥30 分钟 + 版本/指纹/整个联系人列表（顺序+号码）与 schema 一致）。凭据为 `qualifiedPass`——通过且等待 ≥30 分钟才写入，任何时长的失败都清空它，快速检查与中止不影响。正式包忽略存储中的快速阈值残留。
- 真实报警：紧急短信必须先于第一通拨号群发；接通判定只看通话记录（本次拨出、号码匹配、DURATION>0），读不到一律按未接通继续；接听策略默认接通即停；最多 2 轮、间隔 3 分钟；解除时若已发紧急短信必须群发"已解除"；报警流程中不发平安短信。
- 序列每一步持久化（`alertSeq`/`alertSmsFlags`）：先持久化再拨号；看门狗/SystemReset/SyncSession 恢复时续上——先补查未判定的那通，不重拨、不重复群发。
- 自拨号过滤覆盖整个拨号序列及最后一通拨号后 3 分钟。
- 实机演练是独立于守护开关的持久化状态机（`Drill`/`DrillResult`，见 `core/Drill.kt`）：给每位联系人发测试短信并各拨一次（不论策略），逐位四项（短信已发出/电话已接通/确认收到短信/确认接到电话）全 ✓ 才通过；不发真实紧急/平安短信；演练拨号不算真实使用、真实报警优先、记录失效不自动关闭已开启守护。
- 通话记录只查守护侠自己拨出、号码在紧急联系人列表中的记录，只在内存判定，不留存内容。
- 序列闹钟 requestCode 7–9（通话记录检查 / 拨号兜底 / 下一轮），取消守护与序列收敛时一并取消。
- 真机测试只走实机演练 + 用户同意的安全号码；**不得**用真实家人号码做拨号回归。开发快速阈值（`devFastThresholds`）仅调试包可见，只供开发者回归正常守护链路。

## Git / 提交

- 提交作者必须是用户本人：`git -c user.name=yangyonghao -c user.email=2107290326@qq.com commit ...`；不要把身份信息写进 git config。
- 提交信息用中文；代码注释用中文。
