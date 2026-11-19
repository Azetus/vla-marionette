# 协议

本文描述 mod 实际实现的线上协议。它是设备层协议：mod 采集帧、报告状态、注入原始
输入并返回回执，不解释模型输出。

协议**没有全局版本字段**。兼容性由 `SessionInfo` 的字段和 `capabilities` 数组表达。
宿主只能使用自己理解的 capability；未知的可选字段忽略。

## 1. 传输

### 1.1 分帧

大端：

```text
u32 frameLen      # 本字段之后的字节数：4 + headerLen + bodyLen
u32 headerLen
header            # UTF-8 JSON 对象，必须含 "type"
body              # frameLen - 4 - headerLen 字节；JPEG 或空
```

限制：

- `headerLen <= 64 KiB`
- `bodyLen <= 16 MiB`
- 长度非法或 JSON 非法 → 关闭连接。

mod 主动连接宿主，使用 TCP；每个成功连接是新 session；开启 `TCP_NODELAY`。

### 1.2 顺序

- 握手 `SessionInfo` 是第一条出站消息。
- 控制消息（state、receipt、Pong、ShutdownAck）优先于帧。
- 帧只有一个 latest-wins 槽；宿主消费慢时丢旧帧，不丢最新帧。
- 控制队列耗尽且无法履行回执契约时，主动关闭 session。

## 2. 消息

| 方向 | 类型 | 用途 |
|---|---|---|
| mod → host | `SessionInfo` | 握手和 capabilities |
| mod → host | `Observation` | 一帧 JPEG |
| mod → host | `State` | 周期状态快照 |
| mod → host | `ActionReceipt` | 动作生命周期 |
| mod → host | `Pong` | 回应 Ping |
| mod → host | `ShutdownAck` | 关闭请求已接受 |
| mod → host | `QueryBlockResult` | `QueryBlock` 的异步响应 |
| mod → host | `QueryInventoryResult` | `QueryInventory` 的异步响应 |
| mod → host | `QuerySettingsResult` | `QuerySettings` 的异步响应 |
| mod → host | `ChatAck` | `Chat` 的异步响应 |
| host → mod | `ActionChunk` | 有序原始输入帧 |
| host → mod | `Release` | 中断当前输入 |
| host → mod | `Ping` | 可选存活探测 |
| host → mod | `Shutdown` | 保存并关闭客户端 |
| host → mod | `QueryBlock` | 读取方块状态/方块实体 |
| host → mod | `QueryInventory` | 读取选中槽位和背包 |
| host → mod | `QuerySettings` | 读取客户端设置、游戏模式和键位 |
| host → mod | `SetHud` | 直接显示/隐藏 HUD |
| host → mod | `Chat` | 发送一行聊天；以 `/` 开头为命令 |

## 3. `SessionInfo`

```json
{
  "type": "SessionInfo",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "mc_version": "26.1.2",
  "mod_version": "0.1.0",
  "tick_ms": 50,
  "image": {"width": 854, "height": 480, "pixel_format": "jpeg",
            "origin": "top-left"},
  "render": {"width": 854, "height": 480},
  "window": {"width": 854, "height": 480},
  "capabilities": ["ping", "observation", "state", "raw_input", "shutdown",
                   "query_block", "query_inventory", "chat", "query_settings",
                   "set_hud"],
  "limits": {"max_action_frames": 20}
}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `session_id` | string | 每个 TCP 连接一个新 UUID |
| `mc_version` | string | Minecraft 版本 |
| `mod_version` | string | mod 版本 |
| `tick_ms` | int | 名义逻辑 tick，50 ms |
| `image` | object | JPEG 输出规格；与 render/window 同尺寸 |
| `render` | object | 渲染目标尺寸；与 image/window 同尺寸 |
| `window` | object | 客户端窗口尺寸；与 image/render 同尺寸 |
| `capabilities` | string[] | 仅列已实现能力 |
| `limits.max_action_frames` | int | 单个 `ActionChunk` 最大帧数 |

当前 capabilities：

- `ping`
- `observation`
- `state`
- `raw_input`
- `shutdown`
- `query_block`
- `query_inventory`
- `chat`
- `query_settings`
- `set_hud`

`tick_ms` 是名义值；逻辑 tick 会追赶或暂停，不是墙钟保证。

## 4. `Observation`

```json
{
  "type": "Observation",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "obs_id": 17,
  "state_tick": 123,
  "submit_ns": 456789012345,
  "img_w": 854,
  "img_h": 480,
  "pixel_format": "jpeg"
}
```

body 是 JPEG。当前实现：

- 渲染目标、窗口和 JPEG 输出使用同一尺寸（默认 854×480）；
- 质量 0.9；
- 墙钟 5 Hz 目标，或每 N 个 client tick 采样；
- 最小化窗口渲染 30 FPS，而不是 Minecraft 原版的 10 FPS
  （`FramerateLimitTrackerMixin`），以保证 5 Hz 采集有足够渲染帧；
- 保留 HUD；
- `state_tick` 是采样时的逻辑 tick；
- `obs_id` 每个 session 递增；
- `submit_ns` 是本地单调时间戳，仅用于诊断。

## 5. `State`

目标 10 Hz（墙钟），由 client tick 和渲染帧驱动；没有世界/玩家时也发送。

```json
{
  "type": "State",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "tick": 123,
  "game_time": 456,
  "in_world": true,
  "dimension": "minecraft:overworld",
  "x": 1.5, "y": 64.0, "z": -3.25,
  "yaw": 90.0, "pitch": 12.5,
  "health": 20.0, "alive": true, "on_ground": true,
  "hotbar": 0,
  "screen": null,
  "screen_open": false,
  "input_locked": true,
  "held": {"keys": [], "buttons": []},
  "capture": {"valid": true, "reason": null, "last_obs_id": 17,
              "last_frame_tick": 122, "encode_failures": 0}
}
```

| 字段 | 含义 |
|---|---|
| `tick` | 逻辑 client tick |
| `game_time` | 关卡游戏时间；无世界时 null |
| `in_world` | 是否有 level |
| `dimension` | 维度 id；无世界时 null |
| `x/y/z` | 玩家位置，保留 0.001；无玩家时 null |
| `yaw/pitch` | 玩家朝向，保留 0.001 |
| `health` | 生命值 |
| `alive` | 是否存活 |
| `on_ground` | 是否着地 |
| `hotbar` | 选中槽位 0–8 |
| `screen` | 当前 `Screen` 的简单类名，或 null |
| `screen_open` | 是否有 `Screen` |
| `input_locked` | 物理输入当前是否被抑制 |
| `held` | 实际按住状态：`keys`/`buttons` |
| `held.keys` | 实际按住的 GLFW 键码 |
| `held.buttons` | 实际按住的 GLFW 鼠标按钮 |
| `capture` | 采集状态 |

`capture.reason` 在 `valid=true` 时为 null。无效示例：`no_level`、`bad_size`、
`no_target`、`not_ready`。

## 6. `ActionChunk`

一个 chunk 是有序的原始输入帧列表，一帧对应一个逻辑 client tick。
`frames.length` 就是持续时间，没有单独的 `ticks` 字段。

```json
{
  "type": "ActionChunk",
  "seq": 42,
  "start_tick": 320,
  "frames": [
    {"keys": [69]},
    null,
    {"keys": [340], "buttons": [0],
     "mouse": {"dx": 125, "dy": -50, "scroll": 0}}
  ]
}
```

| 字段 | 约束 |
|---|---|
| `seq` | 整数 ≥ 0；在本 session 已受理 chunk 中唯一 |
| `start_tick` | 整数 ≥ 0；必须大于受理时的当前 tick |
| `frames` | 1–20 个对象或 `null` |
| `frames[i].keys` | GLFW 键盘码；可选；缺省为空 |
| `frames[i].buttons` | GLFW 鼠标按钮码；可选；缺省为空 |
| `frames[i].mouse` | 可选对象 |
| `frames[i].mouse.dx/dy` | 客户区逻辑像素；可选，默认 0 |
| `frames[i].mouse.scroll` | 滚轮格数；可选，默认 0 |
| `session_id` | 可选；存在时必须匹配当前连接 |

输入码范围：

- 键盘：GLFW key code 32–348；
- 鼠标按钮：0–7。

`null` 帧等价于 `{}`：不按键、不按鼠标、不移动。未知可选字段忽略；未知或越界输入码
以 `bad_input` 拒绝。

### 6.1 tick 定义

一个 tick 是一次 Minecraft **客户端逻辑 tick**：一次 `Minecraft.tick()` 执行，
从 `ClientTickEvent.Pre` 到 `Post`。名义 20 TPS，但不是墙钟 50 ms，也不是渲染帧。
`start_tick` 使用这个计数器。

### 6.2 单 tick 限制

一帧只保存一个按住状态。因此，同一 client tick 内同一键/按钮的多次按下/松开边沿
无法表达。例如一个 tick 内“按 E → 松 E → 再按 E”不能编码。这是刻意限制；要支持
需要未来的 sub-tick 事件流。

## 7. `ActionReceipt`

```json
{
  "type": "ActionReceipt",
  "session_id": "0b0e7f5e-2f0e-4f7a-9d6e-2f5b1c8a9d31",
  "seq": 42,
  "status": "applied",
  "tick": 320
}
```

| 状态 | 含义 | `tick` |
|---|---|---|
| `received` | 调度器已受理 | 无 |
| `applied` | 第一帧正在应用 | `start_tick` |
| `ended` | 最后一帧和鼠标 flush 已交付 | `start_tick + frames.length` |
| `rejected` | 未受理 | 无 |
| `interrupted` | 已受理后被中断 | 当前 tick |

`received` 在调度器受理锁内入队，不会被 `applied` 越过。

### 7.1 拒绝原因

| 原因 | 含义 |
|---|---|
| `bad_shape` | 必填字段缺失/类型错误 |
| `bad_frames` | frames 为空或超过 20 |
| `bad_input` | 未知/越界 GLFW 码，或非有限鼠标值 |
| `late` | `start_tick <= 当前 tick` |
| `busy` | 已有 active 或 scheduled chunk |
| `duplicate` | 本 session 重复使用已受理的 `seq` |
| `manual_takeover` | 物理 ESC 打开暂停界面并停止自动化 |

### 7.2 中断原因

| 原因 | 触发 |
|---|---|
| `release` | 宿主发送 `Release` |
| `disconnected` | TCP 连接结束 |
| `no_level` | 客户端离开世界 |
| `manual_takeover` | 物理 ESC 进入接管 |

打开 GUI 不会中断原始输入。

## 8. `Release`

```json
{"type":"Release"}
```

- 清空 pending/active 输入，并在主线程释放所有键/按钮。
- 对 active 或 scheduled chunk 发送 `interrupted/reason=release`。
- 没有活动动作时不发回执。
- 连接保持；新 chunk 使用新 `seq`。

## 9. `Shutdown` / `ShutdownAck`

```json
{"type":"Shutdown","save":true,"reason":"host"}
{"type":"ShutdownAck","save":true,"reason":"host"}
```

- `save` 默认 true。
- mod 确认后通过 `halt(true)` 保存集成服务器，然后请求关闭客户端窗口。
- 窗口关闭走 Minecraft 正常 destroy/save 路径。
- 没有集成服务器时只请求关闭窗口。

## 10. `Ping` / `Pong`

```json
{"type":"Ping","nonce":"abc123"}
{"type":"Pong","nonce":"abc123"}
```

`nonce` 存在时原样回显。Ping 不依赖 Minecraft 状态。

## 11. 调度

- 一个 active chunk + 至多一个 scheduled chunk。
- `start_tick <= 当前 tick` 以 `late` 拒绝，不做平移。
- 只有受理时才记录 `seq`；被拒绝的 chunk 不消耗 `seq`。
- `applied` 在第一帧的 `ClientTickEvent.Pre` 发送。
- `ended` 在最后一帧的 `ClientTickEvent.Post`、鼠标 flush 之后发送。
- 后面没有 chunk 时，下一 tick 视为空帧并释放按住输入。
- 相邻 chunk 对相同键/按钮保持按住状态；chunk 边界不强制释放。

## 12. 输入注入

- 键盘：Minecraft 原版 `KeyboardHandler.keyPress`/`keyRelease`；有 `Screen` 时交给
  screen，否则交给 `KeyMapping`。
- 鼠标按钮：Minecraft 原版 `MouseHandler.onButton`；GUI 收到
  `mouseClicked`/`mouseReleased`，世界收到 `KeyMapping` + click。
- 鼠标移动：虚拟光标按原始像素推进，在 tick post 通过
  `MouseHandler.handleAccumulatedMovement()` flush。
- 滚轮：Minecraft 原版 `MouseHandler.onScroll`。
- 注入期间绕过焦点检查；不按 GUI 语义分类。
- 当前帧按住的修饰键也会被
  `Minecraft.hasShiftDown()/hasControlDown()/hasAltDown()` 看到。Ctrl+Q 因此丢整组，
  Q 单独丢一个。直接调用 `InputConstants.isKeyDown` 的调试路径不桥接。
- 应用鼠标 delta 前，注入器先在当前虚拟位置发一次 no-op move，消费 Minecraft 原版
  `ignoreFirstMove`，避免屏幕/抓取切换后的第一个真实位移被丢弃。
- 持续挖掘只是按住 button-0 的帧。Minecraft 原版只在 `screen == null`、`keyAttack` 按下、
  `mouseHandler.isMouseGrabbed()` 都成立时继续攻击；本层不绕过该门控。
- GUI/世界语义完全由 Minecraft 原版 `Screen`/`KeyMapping` 决定。

### 12.1 物理输入锁和 ESC 接管

- 连接后的 session arm 物理输入锁。
- arm 锁、以及 ESC 暂停界面关闭后的 re-arm，会在客户端线程释放全部 Minecraft 原版 key
  mapping。连接前按住的键/按钮因此不会在自动化接管后继续生效；否则它们的 release
  边沿也会被拦截。
- armed 期间物理键鼠事件被丢弃。
- 注入事件带 `InputInjection` 标记，始终放行。
- 物理 ESC 始终允许。从世界（无 `Screen`）按 ESC：
  1. 释放注入键/按钮；
  2. 以 `manual_takeover` 中断 active/scheduled 输入；
  3. 打开暂停界面；
  4. 暂停界面打开期间允许物理输入。
- 暂停界面关闭后重新加锁；宿主必须发新 `ActionChunk` 才能继续。
- 接管期间新 `ActionChunk` 以 `manual_takeover` 拒绝。
- `State.input_locked` 在物理输入被抑制时为 true；暂停接管和断线后为 false。
- 锁在 session setup 中 arm。紧跟 `SessionInfo` 采样到的 `State` 仍可能报告
  `input_locked=false`；宿主必须等到 `input_locked=true` 的 `State` 再发
  `ActionChunk`。mod 不把这段窗口视为错误。
- `State.held` 报告实际按住的键/按钮，包括注入输入和当前允许的物理输入。

## 13. 失败与清理

- framing/JSON 非法：关闭连接。
- 动作形状非法：`rejected`，连接保留。
- 查询/聊天形状非法：`ok:false` + reason，连接保留。
- 控制队列无法交付回执：关闭 session 并释放输入。
- 断线/Release/离开世界：释放键、按钮和 pending 鼠标。
- 正常客户端关闭：尽力释放输入。

## 14. 最小动作序列

```text
mod                          host
 |---- SessionInfo ---------->|
 |<--- Ping ------------------|
 |---- Pong ----------------->|
 |---- State (tick=T) ------->|
 |---- Observation ---------->|
 |<--- ActionChunk -----------|  frames=N, keys=W
 |---- ActionReceipt(received)|
 |---- ActionReceipt(applied) |  tick=T0
 |---- ActionReceipt(ended) --|  tick=T0+N
 |---- State (tick>=ended) -->|
 |---- Observation ---------->|  动作后帧
 |<--- Release ---------------|
```

动作后的状态和 JPEG 用于确认本 tick 的动作已经落地；重连后应使用新的 session。

## 15. 查询和聊天：公共规则

`QueryBlock`、`QueryInventory`、`QuerySettings`、`Chat` 都是按 `request_id` 关联的
请求/响应消息。

- `request_id` 必填整数；不透明、原样回显、不去重：两个请求可以共享 id。
- 形状校验在网络线程；方块/背包读取和聊天派发通过 `Minecraft.execute` 在客户端
  线程执行。
- 响应是控制消息，不是帧；可与 `State`、回执或其他响应交错，**可能乱序**，宿主必须
  按 `request_id` 关联。
- 只有请求所在 session 仍是当前连接时才投递；旧 session 的延迟响应丢弃。
- 最多 32 个在途请求；超出的请求直接回 `busy`，不排客户端任务。
- 查询/聊天不触碰输入锁、不中断 active 动作、不要求 `screen == null`。
- 连接关闭或客户端关闭时响应可能丢失。
- 每个响应都有 `type`、`request_id`（无法关联时可为 null）、`ok`、`reason`
  （成功为 null）、`tick`（生成响应时的客户端 tick）。

### 15.1 失败原因

| 原因 | 含义 |
|---|---|
| `bad_shape` | 必填字段缺失或类型错误 |
| `no_level` | 无世界或无本地玩家 |
| `no_target` | 准星未指向方块 |
| `chunk_not_loaded` | 请求的 chunk 未在客户端加载 |
| `out_of_bounds` | 位置超出世界边界 |
| `not_connected` | 没有 `ClientPacketListener` |
| `bad_text` | 文本为空/过长、含控制字符、命令体为空 |
| `busy` | 在途请求超过 32 |
| `not_ready` | `Minecraft` 尚未构造 |
| `internal` | 未预期失败，已记录日志 |

## 16. `QueryBlock`

位置查询：

```json
{"type":"QueryBlock","request_id":1,"pos":[10,64,-3]}
```

准星查询（省略 `pos` 或传 null）：

```json
{"type":"QueryBlock","request_id":2}
```

成功（位置）：

```json
{
  "type":"QueryBlockResult","request_id":1,"ok":true,"reason":null,
  "tick":1288,"query":"pos","pos":[10,64,-3],
  "block_state":"minecraft:oak_log","properties":{"axis":"y"},
  "block_entity":null
}
```

成功（准星）：

```json
{
  "type":"QueryBlockResult","request_id":2,"ok":true,"reason":null,
  "tick":1290,"query":"crosshair","pos":[1,-59,0],
  "block_state":"minecraft:stone","properties":{},
  "block_entity":null,"distance":2.515,"face":"east"
}
```

| 字段 | 含义 |
|---|---|
| `pos`（请求） | 可选 `[x,y,z]` 整数；缺省/null 表示准星 |
| `query` | 成功时为 `pos` 或 `crosshair`；失败时无 |
| `pos`（响应） | 读取/命中的方块 |
| `block_state` | 方块 id，例如 `minecraft:stone` |
| `properties` | 方块状态属性名到字符串值；无属性为 `{}` |
| `block_entity` | 方块实体类型 id，或 null；始终存在 |
| `distance` | 仅准星：相机眼到命中点的距离，3 位小数 |
| `face` | 仅准星：命中面名称 |

实现说明：

- 位置查询先检查 `isInValidBounds`（`out_of_bounds`），再以
  `loadOrGenerate=false` 查客户端 chunk 缓存（`chunk_not_loaded`）。
  `ClientLevel.hasChunk` 总是 true，不能用。
- 准星查询读取 Minecraft 原版的 `hitResult`，每渲染帧更新一次；不是即时 raycast，
  可能最多落后一个渲染帧。未命中或命中实体为 `no_target`。
- 只返回方块 id、属性和方块实体类型；不返回 NBT。

## 17. `QueryInventory`

```json
{"type":"QueryInventory","request_id":4}
```

```json
{
  "type":"QueryInventoryResult","request_id":4,"ok":true,"reason":null,
  "tick":1300,"selected":0,
  "slots":[
    {"slot":0,"item":"minecraft:stone","count":5},
    {"slot":40,"item":"minecraft:shield","count":1}
  ]
}
```

- `selected` 是选中 hotbar 槽位，0–8。
- `slots` 只含非空槽位，按 slot 排序；缺失即空。
- 槽位索引按客户端背包容器：0–8 hotbar，9–35 背包，36–39 盔甲（脚/腿/胸/头），
  40 副手，41 body，42 saddle。
- 响应是客户端同步背包，不是服务器权威快照；改变背包的命令要等客户端收到更新后才
  可见。
- 不返回 NBT 或 item components。

## 18. `Chat`

一行聊天。以 `/` 开头走 Minecraft 原版命令路径，否则作为聊天发送。

```json
{"type":"Chat","request_id":6,"text":"/setblock 10 64 -3 minecraft:stone"}
```

```json
{"type":"ChatAck","request_id":6,"ok":true,"reason":null,
 "kind":"command","tick":1305}
```

```json
{"type":"ChatAck","request_id":6,"ok":false,"reason":"bad_text",
 "kind":null,"tick":1305}
```

| 字段 | 含义 |
|---|---|
| `text` | 必填字符串；trim 前最多 256 字符，trim 后非空 |
| `kind` | trim 后以 `/` 开头为 `command`，否则 `chat`；无法解析时为 null |

规则：

- 只 trim 两端；内部空白保留（不执行 Minecraft 原版 `ChatScreen` 的空白归一化，引号 JSON
  等命令参数保留空格）。
- 控制字符（`< 0x20`、`0x7F`）拒绝；单独 `/` 或空命令体为 `bad_text`。
- 调 `ClientPacketListener.sendCommand` 前只去掉一个前导 `/`；`//foo` 视为命令
  `/foo`，由服务器报告解析错误。
- mod 不解析命令名，没有 allowlist/denylist；`stop`、`kick`、`ban` 等直接到服务器。
  正常关闭用 `Shutdown(save=true)`。
- 不打开聊天界面；不中断进行中的动作，不改变输入状态。
- `ok=true` 只表示交给了 `sendCommand`/`sendChat`，**不表示服务器执行成功**；缺权限、
  缺 cheats、语法错误只出现在游戏聊天。用 `QueryBlock`/`QueryInventory`/`State`
  验证效果。
- 多人游戏中普通聊天是公开的，受聊天签名、服务器过滤和限速影响；安全性取决于宿主。

## 19. `QuerySettings`

读取影响原始输入映射的客户端设置。它是按需快照，不是 10 Hz 状态字段。

```json
{"type":"QuerySettings","request_id":8}
```

```json
{
  "type":"QuerySettingsResult","request_id":8,"ok":true,"reason":null,
  "tick":1302,
  "gamemode":"survival",
  "sensitivity":0.5,
  "invert_mouse_x":false,
  "invert_mouse_y":false,
  "smooth_camera":false,
  "camera_type":"first_person",
  "scoping":false,
  "hide_gui":false,
  "gui_scale":2,
  "gui_width":427,
  "gui_height":240,
  "keybinds":{
    "key.forward":{"type":"keysym","code":87},
    "key.attack":{"type":"mouse","code":0},
    "key.hotbar.1":{"type":"keysym","code":49},
    "key.smoothCamera":null
  }
}
```

| 字段 | 含义 |
|---|---|
| `gamemode` | `survival`、`creative`、`adventure`、`spectator` |
| `sensitivity` | 鼠标灵敏度，0–1 |
| `invert_mouse_x/y` | 鼠标反转选项 |
| `smooth_camera` | 平滑相机选项 |
| `camera_type` | `first_person`、`third_person_back`、`third_person_front` |
| `scoping` | 玩家当前是否在用望远镜 |
| `hide_gui` | 当前 HUD 是否隐藏（`options.hideGui`） |
| `gui_scale` | 当前 GUI scale |
| `gui_width/gui_height` | 当前 GUI 缩放后尺寸 |
| `keybinds` | 全部当前 `KeyMapping`，按 mapping 技术名索引 |

`keybinds` 是完整表（`Options.keyMappings`，包括 hotbar 和 debug mapping），只读：

- 值为 `null` 表示未绑定；
- 否则 `type` 为 `keysym`/`scancode`/`mouse`，`code` 为 GLFW 键码或鼠标按钮；
- 按 mapping 名排序；未知名字由 host 忽略；
- 是当前 options 快照，不是默认值；mod 不校验、不归一化、不修改绑定；
- `scancode` 绑定会返回，但 `ActionChunk` 只能表达 keysym 和鼠标按钮，不能注入。

- 与其他查询一样在客户端线程读取，使用相同 `request_id`、在途上限和 session 守卫。
- 值反映读取时的设置；options 改变后下一次查询可见。
- 相机像素→角度换算仍在 host。参考：Minecraft 原版先算
  `sensMod = (sensitivity * 0.6 + 0.2)^3`，普通视角乘 `sensMod * 1.2`，望远镜视角乘
  `sensMod * 0.15`，再应用反转选项，pitch 钳制在 ±90；`smooth_camera` 会改变响应曲线。

## 20. `SetHud`

直接设置 HUD 是否可见。它不依赖键码，也不使用 F1 键的显示/隐藏切换语义。

```json
{"type":"SetHud","visible":false}
```

- `visible` 必填布尔值；`false` 隐藏 HUD（`options.hideGui=true`），`true` 显示。
- 在客户端线程直接设置；不记录旧值，不在断线、离开世界或退出时恢复。
- 不写入 `options.txt`；客户端重启后回到默认显示。
- 设置后 Minecraft 原版的 F1 键（显示/隐藏 HUD）仍可再次切换；mod 不强制保持。host 可用
  `QuerySettings.hide_gui` 读取当前实际值。
- 该消息是可选的：host 可以完全不发，HUD 保持客户端当前状态。为了让观察画面
  可复现，建议 host 在需要时显式发送并查询确认。
- 没有 `HudAck`；形状非法的消息被忽略并记录日志，不改变 HUD。
