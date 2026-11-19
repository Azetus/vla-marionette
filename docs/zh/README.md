# VLA-Marionette 中文文档

> 本目录描述当前代码库的中文技术文档。协议字段、消息名、类名、命令和代码标识符
> 保持英文。英文文档位于 `docs/en/`，中文 README 为仓库根目录的
> [`README.md`](../../README.md)。

VLA-Marionette 是 Minecraft 26.1.2 / NeoForge 26.1.2.126 的客户端设备层。它负责
采集画面、报告状态、注入原始输入、按 tick 调度动作并返回回执；它不包含模型调用、
prompt、动作文本解析、模型词表或模型侧换算常数。

## 目录

- [总体架构](architecture.md)
- [协议](protocol.md)
- [动作参考](actions.md)

## 当前实现

- 采集：窗口、渲染目标和 JPEG 输出使用统一尺寸，默认 854×480；JPEG 质量 0.9。
  默认墙钟 5 Hz，也可按每 N 个 client tick 采样。
- 状态：目标 10 Hz，包含位置、朝向、生命、着地、hotbar、screen、
  `input_locked`、`held` 和采集状态。
- 输入：逐 client tick 的 GLFW 键码、鼠标按钮 0–7、客户区像素位移和滚轮。
- 查询：`QueryBlock`、`QueryInventory`、`QuerySettings`；设置查询包含完整当前
  keybind 表和当前 `hide_gui`。
- 聊天/命令：统一 `Chat`，以 `/` 开头走命令路径。
- HUD：host 用 `SetHud` 直接设置显示/隐藏；Minecraft 原版的 F1 键仍可改变，`QuerySettings`
  可读当前值。
- FOV：`[fov]` 启动配置固定世界投影的垂直 FOV（默认 `fixed` 70）；`QuerySettings`
  报告 `fov`、`fov_effect_scale` 和 `fov_mode`。
- 会话：物理输入锁、ESC 接管、断线清理、重连新会话、优雅关闭。

## 非目标

- GUI 文本输入（`charTyped`、IME、死键）；聊天和命令用 `Chat`。
- Fn、媒体键、8 号以上鼠标键。
- 实体查询、完整 NBT/components、通用世界信息。
- `State.sneaking` / `State.sprinting`；潜行/疾跑从速度推断。
- 多人服务器和长时间稳定性试验。
- 语义动作字段（`forward`、`attack`、`camera` 等）；线上只传原始帧。
