# 动作参考

这是对现有原始输入原语的组合参考，不是第二套动作 API。它说明一个语义动作如何由
GLFW 键码、鼠标按钮、鼠标位移和滚轮组合而成。宿主仍然发送 `ActionChunk`；mod 不解释
下表中的语义名称。

## 原语

| 原语 | 线上字段 | 范围 |
|---|---|---|
| 键盘按下/按住 | `frames[i].keys` | GLFW 键码 32–348 |
| 鼠标按钮 | `frames[i].buttons` | 0–7 |
| 相机/GUI 光标 | `frames[i].mouse.dx/dy` | 客户区逻辑像素 |
| 滚轮 | `frames[i].mouse.scroll` | 滚轮格数 |

一帧是一个 client tick 的按住状态。多个键/按钮可同时按住；相邻 chunk 共享边界时
保持同一按住状态。

`QuerySettings.keybinds` 返回当前完整 `KeyMapping` 表。宿主应通过该表取动作对应的
键，而不是假设固定键码。下表中的 `key.forward`、`key.attack` 等是 Minecraft 原版 mapping
名，不是 mod 的动作词表。

## 动作

| 动作 | 原始输入 | 可观测结果 |
|---|---|---|
| 前进 | 按住 `key.forward` | 沿朝向的水平位移 |
| 后退 | 按住 `key.back` | 与朝向相反的位移 |
| 左移 | 按住 `key.left` | 沿左向量的位移 |
| 右移 | 按住 `key.right` | 沿右向量的位移 |
| 跳跃 | 按住 `key.jump` 1–2 tick | `y` 上升，`on_ground` false→true |
| 潜行 | 按住 `key.sneak` + `key.forward` | 速度低于行走 |
| 疾跑 | 按住 `key.sprint` + `key.forward` | 速度高于行走 |
| 攻击 | 按住 `key.attack` | 目标方块状态变化，`QueryBlock` |
| 使用 | 对准可交互方块按 `key.use` | 方块状态变化，`QueryBlock` |
| 热栏 | 按 `key.hotbar.N` | `State.hotbar` 和 `QueryInventory.selected` |
| 滚轮 | `mouse.scroll` | 选中 hotbar 槽位变化 |
| 背包开关 | 按 `key.inventory` | `State.screen_open` 和 `screen` |
| 背包 Shift 点击 | 光标移到槽位，按住 `key.sneak` + 左键 | `QueryInventory` 槽位变化 |
| 丢弃一个 | 按 `key.drop` | 选中堆数量减一 |
| 相机 | `mouse.dx/dy` | `State.yaw/pitch` 变化，位置不变 |
| GUI 光标 | `mouse.dx/dy` + 按钮 | Minecraft 原版 `Screen` 处理槽位/动作 |

## 相机换算

相机像素换算在 host 侧。参考客户端（`smooth_camera=false`、非望远镜、第一人称）：

```text
sensMod   = (sensitivity * 0.6 + 0.2)^3
yaw/pitch = pixels * sensMod * 1.2      # 普通视角
          = pixels * sensMod * 0.15     # 望远镜
```

`invert_mouse_x/y` 翻转符号；pitch 钳制在 ±90。`smooth_camera` 会改变响应曲线。
宿主应使用 `QuerySettings` 的 sensitivity，而不是硬编码。

## GUI 坐标

`QuerySettings.gui_scale`、`gui_width`、`gui_height` 描述当前 GUI。GUI 坐标到客户区
像素：

```text
screen_pixel = gui_coordinate * gui_scale
```

Minecraft 原版背包布局参考：

```text
left = (gui_width  - 176) / 2
top  = (gui_height - 166) / 2
hotbar slot 0 = (left + 8,       top + 142)
main slot 9   = (left + 8,       top + 84)
```

如果宿主需要确定性的 GUI 光标定位，可以在屏幕打开时先发一个很大的负 delta，把虚拟
光标夹到 `(0, 0)`，再移动到目标槽位。这是 host 侧技巧；mod 只负责传递 delta。

## 会话条件

- 宿主在发送动作前等待 `State.input_locked == true`。session setup 期间采样到的
  `State` 仍可能是 false。
- 注入输入绕过窗口焦点检查，因此窗口失焦或最小化不阻止输入；GUI/世界语义仍由
  Minecraft 原版 `Screen`/`KeyMapping` 决定。
- HUD 可见性不是原始输入动作；host 用 `SetHud` 设置，`QuerySettings.hide_gui` 读取
  当前值；Minecraft 原版的 F1 键（显示/隐藏 HUD）仍可改变。
- 最小化窗口的渲染下限是 30 FPS，保证 5 Hz 采集有足够渲染帧。

## 非目标

- GUI 文本输入（`charTyped`、IME、死键）；聊天和命令用 `Chat`。
- Fn、媒体键、8 号以上鼠标键。
- 实体查询、完整 NBT/components、通用世界信息。
- 多人和长时间稳定性试验。
- 线上语义动作字段（`forward`、`attack`、`camera` 等）。
