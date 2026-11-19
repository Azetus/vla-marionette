# 总体架构

VLA-Marionette 是客户端设备层。它把运行中的 Minecraft 客户端变成可观测、可操作的
端点，但不在 mod 内嵌策略或模型知识。

## 运行时结构

```text
TCP 宿主
   │  SessionInfo / Observation / State / ActionReceipt
   │  ActionChunk / Release / Ping / Shutdown
   ▼
Transport（连接/读/写守护线程）
   │
   ├── InboundRouter ──► ActionScheduler ──► InputSink
   │                                      └─► McInputInjector
   │                └──► QueryService ──► 客户端线程读取
   │                                     └─► Chat 派发
   │
   ├── CaptureScheduler ──► FrameMailbox ──► JPEG 编码
   │
   └── StateReporter ──► State

Minecraft 主/客户端线程：
  ClientTickEvent.Pre/Post、RenderFrameEvent.Post、窗口、player、Screen
```

## 模块

### `protocol`

- `Framing`：大端 `u32 frameLen`、`u32 headerLen`、UTF-8 JSON header、可选二进制
  body；header/body 有硬上限。
- `SessionInfo`：每个 TCP 连接一次握手，声明 capabilities、统一的图像/渲染/窗口尺寸
  和单次动作最大帧数；没有全局版本字段。

### `config`

- `VlaConfig`：启动时读取 `config/vla_marionette-client.toml`，提供 bridge、capture、
  state 和 fov 四组设置；缺失文件时写默认值，非法值记录日志并回退。
- `CaptureConfig` / `CaptureMode`：统一尺寸、`wall_clock` 或 `tick` 模式、墙钟频率和
  tick 间隔。
- `StateConfig` / `StateMode`：State 的 `wall_clock`（`hz`）或 `tick`
  （`tick_interval`）模式；独立校验，非法 state 配置不会重置 capture/fov。
- `FovConfig` / `FovMode`：启动 FOV 模式（`fixed`/`vanilla`）和垂直 FOV；独立于
  capture 段校验，非法 FOV 不会重置尺寸。

### `net`

- `Transport`：主动连接宿主，生成每连接 session id，先发握手，管理控制队列和帧队列。
- 控制消息是有界 FIFO；帧是单槽 latest-wins。
- `InboundRouter`：网络线程解析/校验入站消息；`ActionChunk` 的受理和回执与调度器
  协调。
- `ShutdownHandler`：确认关闭请求，保存集成服务器，请求关闭客户端窗口。

### `capture`

- `CaptureSpec`：统一窗口、渲染目标和 JPEG 输出尺寸，默认 854×480；缩放不在采集链路中发生。
- `CaptureScheduler`：默认按真实时间 5 Hz 采样，也可按每 N 个 client tick 采样。
- `FrameGrabber`：渲染线程读取主渲染目标。
- `FrameMailbox`：单槽待编码帧；编码线程直接编码统一尺寸，质量 0.9。
- `CaptureStatus`：valid/reason、最后 observation id/tick、编码失败数。
- 最小化窗口由 `FramerateLimitTrackerMixin` 把渲染下限从 Minecraft 原版的 10 FPS 抬到 30 FPS，
  保证 5 Hz 采样有足够渲染帧。

### `state`

- `ClientClock`：唯一的逻辑 client tick 计数器，在 `ClientTickEvent.Pre` 递增。
- `StateReporter`：两种模式。`wall_clock` 由 client tick 和渲染帧共同驱动，按 `hz`
  节流并保活；`tick` 只在 `ClientTickEvent.Post`、动作调度和鼠标 flush 之后采样，
  每 `tick_interval` 个 tick 一条，`tick` 精确且不补采。没有世界/玩家时也发送状态。

### `action`

- `ActionFrame`：一个 tick 的按住 GLFW 键/按钮和原始鼠标值。
- `ActionChunk`：1–20 帧的有序列表，没有单独的 `ticks` 字段。
- `ActionScheduler`：一个 active 加至多一个 scheduled chunk；回执顺序和
  `applied`/`ended` 时机由它保证。
- `ActionReceipt`：线上回执。
- `InputInjection`：区分桥接注入事件与物理事件的标记。
- `InputLock`：物理输入锁、实际按住键/按钮跟踪、ESC 接管状态。
- `McInputInjector`：通过 Minecraft 原版 `KeyboardHandler`/`MouseHandler` 发送键鼠事件；
  维护虚拟鼠标位置，并在 tick post flush 鼠标位移。

### `query`

- `QueryRequests` / `ChatRequest`：网络线程的形状/文本校验、归一化和命令/聊天分类。
- `QueryService`：在途请求上限、客户端线程跳转、按 session 投递响应。
- `McQueryReader`：方块状态/属性/方块实体、准星目标、背包、客户端设置（包括完整
  当前 keybind 表和 `hide_gui`），以及 `sendCommand`/`sendChat` 派发。
- `QueryResponses`：`QueryBlockResult`、`QueryInventoryResult`、
  `QuerySettingsResult`、`ChatAck` 的响应构造。

### `mc`

- `WindowLayout`：把窗口锁到统一尺寸并禁止手动缩放。
- `HudController`：处理 host 的 `SetHud`，直接设置 `options.hideGui`；不记录基线、
  不写 `options.txt`。
- `FovSpec`：本次客户端运行的生效 FOV 配置；渲染线程每帧读取，`QuerySettings` 读同一值。
  FOV 是启动配置，没有运行时控制通道。

### `mixin`

- `GameRendererResizeMixin`：强制渲染目标为配置的统一尺寸。
- `CameraFovMixin`：`fixed` 模式下替换 `Camera.calculateFov` 的返回值，固定世界投影；
  panorama 模式保持原版。
- `FramerateLimitTrackerMixin`：最小化窗口的 Minecraft 原版的 10 FPS 下限改为 30 FPS，
  保证 5 Hz 采集有足够渲染帧。
- `KeyboardHandlerAccessor` / `MouseHandlerAccessor`：暴露 Minecraft 原版私有输入派发。
- `KeyboardHandlerMixin` / `MouseHandlerMixin`：自动化锁 armed 时丢弃物理输入，
  ESC 例外。
- `MinecraftFocusMixin`：窗口失焦/最小化时仍允许注入鼠标累积和 flush。
- `KeyMappingAccessor`：读取 `KeyMapping` 当前键，供 `QuerySettings` 使用。
- `MinecraftModifierMixin`：把当前注入帧的修饰键并入
  `Minecraft.hasShiftDown()/hasControlDown()/hasAltDown()`。

## 线程

| 线程 | 允许的工作 |
|---|---|
| 客户端/渲染主线程 | tick 时钟、动作调度、输入写入、帧读回、状态快照、窗口变更、查询读取、聊天派发 |
| 网络读/写线程 | 分帧、JSON 解析/校验、入队、received/rejected 回执、查询/聊天形状检查和响应入队；不碰 Minecraft 状态 |
| JPEG 编码线程 | RGB 缩放和 JPEG 编码；不碰 Minecraft 状态或 socket |

渲染/主线程永不等待 socket。网络侧触发的 Minecraft 操作一律通过
`Minecraft.execute(...)`。查询/聊天响应带请求所在 session；连接被替换后，旧响应
直接丢弃。

## 会话生命周期

1. 每个新 TCP 连接生成新 `session_id`。
2. 握手先于 observation/state 入队。
3. 每个被受理的动作属于该连接；`seq` 按 session 去重。
4. 断线清空出站队列，中断 active/scheduled 输入，并在主线程释放按住的键/按钮。
5. 重连是新会话；旧帧/回执/动作被拒绝，延迟的查询/聊天响应按 session id 丢弃。

## 输入模型

- `ActionChunk` 的每一帧是一个逻辑 client tick。
- `keys`/`buttons` 是按住状态；注入器与上一 tick 比较，计算按下/松开边沿。
- 鼠标 `dx/dy` 是客户区逻辑像素；mod 不做模型单位换算、不算角度，换算由 host 完成。
- 键鼠事件走 Minecraft 原版派发，打开的 `Screen` 自然接收；GUI 和世界不是两套协议模式。
- 连接后 arm `InputLock`；armed 期间物理输入被丢弃。arm（以及暂停界面关闭后的
  re-arm）还会释放全部 Minecraft 原版 key mapping，避免连接前按住的键在自动化接管后继续
  生效。世界中按 ESC 会释放注入输入、以 `manual_takeover` 中断动作，并在暂停界面
  打开期间允许物理输入；关闭暂停界面后重新加锁。
- `State.input_locked` 表示物理输入是否被抑制；`State.held` 报告实际按住键/按钮。
- 当前帧的注入修饰键通过 `InjectedInputState` 发布；mixin 把它们并入
  `Minecraft.hasShiftDown()/hasControlDown()/hasAltDown()`。这样 Ctrl+Q 整组丢弃
  等路径与物理键盘一致。
- 鼠标注入器维护虚拟光标；每次 flush 先发一次当前位置的 no-op move，消费 Minecraft 原版
  `ignoreFirstMove`，避免屏幕/抓取切换后的第一个真实位移被吞掉；pending delta 在
  tick Post flush，保证 `ended` 覆盖最后一帧鼠标。
- 按住 button-0 的帧走 Minecraft 原版 `continueAttack`。Minecraft 原版还要求
  `screen == null`、`keyAttack` 按下、`mouseHandler.isMouseGrabbed()`；本层不绕过
  该门控。
- `ended` 在最后一帧输入和鼠标 flush 之后发送。

## 不变量

- 不冻结/暂停/覆盖 tick rate。
- HUD 默认不主动隐藏；host 可用 `SetHud` 控制；mod 不维护基线，也不写 `options.txt`。
- FOV 从启动配置读取；`fixed` 模式固定世界投影，`vanilla` 模式保留原版行为；
  没有运行时 FOV 控制消息，也不写 `options.txt`。
- 渲染目标/窗口/JPEG 尺寸统一，默认 854×480；质量 0.9。
- 采集目标 5 Hz；状态默认 10 Hz 墙钟，可配置为每 N 个 client tick；逻辑 client tick 目标 20 TPS。
- 最小化渲染下限 30 FPS（Minecraft 原版的 10），让 200 ms 采样有 6 帧余量。
- 同一时间一个 active，至多一个 scheduled。
- 查询/聊天响应按 `request_id` 关联，不保证顺序，不泄漏到新 session。
- `ChatAck.ok` 表示已派发，不表示服务器已执行；mod 不过滤聊天/命令。
- HUD 由 host 的 `SetHud` 或 Minecraft 原版的 F1 键控制；mod 不维护基线，也不写 `options.txt`。
- mod 内没有模型词表或模型侧常数。
