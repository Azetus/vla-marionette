# VLA-Marionette

一个面向 Minecraft 26.1.2（NeoForge 26.1.2.126）的 NeoForge 客户端 mod。

VLA-Marionette 是**设备层**。它通过画面采集、状态报告、逐 tick 原始输入注入和动作
回执，把游戏客户端暴露给外部控制器；它不包含模型 prompt、动作文本解析、模型词表或
模型侧换算常数。

[English README](README_EN.md)

## 功能

- **画面与状态**：默认以 5 Hz 目标输出 854×480 JPEG；以 10 Hz 目标报告位置、朝向、生命、
  着地、hotbar、当前界面、输入锁和实际按住的键鼠。
- **原始输入**：按 client tick 注入 GLFW 键码、鼠标按钮 0–7、客户区鼠标位移和滚轮；
  支持组合键、按住状态和跨 chunk 连续动作。
- **动作组合**：动作由原始输入组合完成。
- **查询**：方块、背包和客户端设置；设置查询返回完整当前 keybind 表和 `hide_gui`。
- **聊天/命令**：`Chat` 统一发送一行文本，`/` 开头走原版命令路径。
- **会话控制**：连接后锁定物理输入，ESC 可手动接管；支持断线清理、重连新 session 和
  关闭客户端。
- **HUD 控制**：host 可用 `SetHud` 直接显示/隐藏 HUD；Minecraft 原版的 F1 键
  （显示/隐藏 HUD）也可切换。
- **FOV**：`[fov]` 启动配置固定世界投影的垂直 FOV（默认 `fixed` 70）；`QuerySettings`
  报告 `fov`、`fov_effect_scale` 和 `fov_mode`。
- **最小化运行**：最小化窗口保持 30 FPS 渲染下限和原始输入注入，采集目标仍为 5 Hz。

## 文档

中文文档：

- [中文文档索引](docs/zh/README.md)
- [总体架构](docs/zh/architecture.md)
- [协议](docs/zh/protocol.md)
- [动作参考](docs/zh/actions.md)

英文文档：

- [English documentation](docs/en/README.md)
- [英文 README](README_EN.md)

## 环境要求

- 通过 [mise](https://mise.jdx.dev/) 固定项目 JDK。
- 仓库内的 Gradle wrapper。
- 通过 `mise install` 安装 JDK 25；`mise.toml` 固定 Java 25.0.5。
- 用于实机测试的 Minecraft/NeoForge 客户端安装。
- 实机测试需要超平坦测试世界；客户端窗口由配置决定，默认 854×480。

## 构建与测试

```powershell
mise install
mise exec -- .\gradlew.bat test --console=plain
mise exec -- .\gradlew.bat build --console=plain
```

类 Unix shell：

```sh
mise install
mise exec -- ./gradlew test --console=plain
mise exec -- ./gradlew build --console=plain
```

构建产物是 `build/libs/vla-marionette-<minecraft版本>-neoforge-<mod版本>.jar`
（当前为 `build/libs/vla-marionette-26.1.2-neoforge-0.1.0.jar`）。如果 Gradle
缓存不在默认的 `~/.gradle`，构建前设置 `GRADLE_USER_HOME`。

## 配置

首次启动会在 `config/vla_marionette-client.toml` 生成默认配置：

```toml
[bridge]
host = "127.0.0.1"
port = 25580
retry_ms = 2000

[capture]
width = 854
height = 480
mode = "wall_clock"   # wall_clock | tick
hz = 5.0
tick_interval = 4

[fov]
mode = "fixed"        # fixed | vanilla
vertical = 70         # 30-110, vertical degrees
```

- `width/height` 同时决定窗口、渲染目标和 JPEG 输出尺寸，三者一致，不做缩放。
- `wall_clock` 按真实时间采样；`tick` 在每 N 个 client tick 后的第一个渲染帧采样。
- 端口、尺寸、采集模式和 FOV 的修改在重启客户端后生效；运行中的桥接不会重读配置。
- `[fov]` 的 `vertical` 是垂直 FOV（与 Minecraft 自身的 FOV 设置一致，MC 的水平 FOV
  由宽高比派生）。`fixed` 固定世界投影，`vanilla` 保留客户端原版行为。
- HUD 不在 TOML 中配置；host 用 `SetHud` 控制，Minecraft 原版的 F1 键
  （显示/隐藏 HUD）仍是独立开关。

## 实机测试

launcher 脚本从被 git 忽略的本机配置文件读取路径。复制
`tools/mc.local.example.ps1` 为 `tools/mc.local.ps1`，填入：

- 安装版本目录；
- 测试世界名；
- 桥接端口；
- 可选的隔离副本世界源目录。

示例文件只含占位符。

常用命令：

```powershell
# 把构建产物部署到安装目录；旧的 vla-marionette* 会被删除。
.\tools\deploy.ps1 -GameDir '<version-dir>'

# 用测试世界启动安装客户端。
.\tools\launch-mc.ps1 -Installed -World vla-m1

# 运行一次测试，或三次并自动关闭。
.\tools\m1-run.ps1 -Installed -Runs 3 -World vla-m1

# 启动时覆盖 FOV（测试便利；不修改 TOML）。
.\tools\launch-mc.ps1 -Installed -World vla-m1 -Fov 30
```

`launch-mc.ps1` 也可以创建隔离的 `runs/mc-*/game` 副本，不触碰安装目录的存档。
`deploy.ps1` 读取带版本号的构建产物，并按原文件名安装；部署前会删除 `mods/` 下所有
旧的 `vla-marionette*`（包括 `.jar.disabled` 备份），确保 NeoForge 只加载一个同 mod id
的 jar。隔离运行同样按原文件名复制。
`-Fov`/`-FovMode` 通过 `-Dvla.fov.*` 覆盖启动配置，只对这一次启动生效；
`stop-mc.ps1` 先校验进程 id 并请求窗口关闭；按 PID 强杀只是超时兜底。

## 限制与边界

- **设备层范围**：不实现冻结、暂停或 tick rate 覆盖；HUD 默认不主动隐藏，host 可用
  `SetHud` 控制；线上只有原始输入帧，没有 `forward`/`attack`/`camera` 等语义动作字段。
- **输入范围**：支持 GLFW 键码 32–348 和鼠标按钮 0–7；不提供 GUI 文本输入、Fn/媒体键、
  8 号以上鼠标键。
- **单 tick 表达**：一个 client tick 内每个键/按钮至多一个按下/松开边沿；不提供 sub-tick
  事件流。
- **动作调度**：同一时间一个 active + 至多一个 scheduled；没有多 chunk 并发。
- **查询范围**：返回客户端快照，不返回 NBT/components；响应按 `request_id` 关联，
  可能乱序；不提供实体或通用世界查询。
- **会话行为**：连接期间物理输入被锁；连接前按住的键会在锁 arm 时释放；ESC 释放注入
  输入并允许手动接管（暂停界面期间物理输入可用）。`ChatAck.ok` 只表示已派发，不表示
  服务器已执行。
- **运行环境**：最小化窗口以 30 FPS 渲染以维持 5 Hz 采集；多人服务器和长时间运行不在
  当前范围；工具挖掘、掉落物和实体交互由 Minecraft 原版处理，本层只注入原始输入。
- **FOV**：FOV 是启动配置，运行中不能修改；`fixed` 模式固定世界投影，疾跑、飞行、水下、
  死亡和望远镜不再改变画面 FOV，HUD/手部渲染和准星/交互距离不受影响；host 只能查询
  `fov`/`fov_mode`，不能设置。

## 目录结构

```text
src/main/java/com/vlamarionette/marionette/
  protocol/       分帧和 SessionInfo
  net/            传输、连接生命周期、入站路由、关闭
  capture/        帧抓取、JPEG 编码、latest-frame mailbox、调度
  state/          client tick 时钟和状态报告
  action/         原始帧、调度、回执、Minecraft 原版输入注入
  query/          方块/背包读取、聊天/命令派发
  config/         TOML 配置加载
  mc/             窗口尺寸、HUD 控制和 FOV 配置
  mixin/          渲染目标、FOV 覆盖、焦点绕过、Minecraft 原版输入访问器
src/main/resources/META-INF/neoforge.mods.toml
src/main/resources/vla_marionette.mixins.json
src/test/java/    纯单元测试（不启动游戏进程）
tools/            构建、launcher、测试和诊断脚本
docs/en/          英文技术文档
docs/zh/          中文技术文档
```

## 许可证

MIT。见 [LICENSE](LICENSE)。
