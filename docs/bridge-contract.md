# MobileSpeak bridge 契约

本文记录 `native/src/lib.rs` 的实际行为。Rust/native 是契约事实来源；Swift/Kotlin 属性名可以不同，但跨 bridge 的 JSON 键不得自行改名。

当前契约未显式版本化：没有 `version` 字段。兼容规则见文末。

## 架构与调用面

- iOS 只通过 `Core.h` 的 C ABI 调用 Rust。
- Android 只通过 `NativeCore.kt`/`android_jni.rs` 的 JNI 调用同一个 Rust 核心。
- `shutdown` 是核心生命周期命令，仅由 `Bridge::drop` 使用，不是平台功能命令。
- 平台公开命令为 `configure`、`connect`、`disconnect`、`join`、`mute`、`set_noise_suppression`、`send_channel_message`、`send_private_message`、`set_chat_visible`、`set_app_active`。
- 未知 JSON 字段会被 serde 忽略；未知命令、缺少必填字段或类型错误会被拒绝。

## 命令

所有命令都是 UTF-8 JSON object，`type` 是必填的 snake_case 字符串。

| `type` | 字段 | 必填/默认 | 校验与语义 |
| --- | --- | --- | --- |
| `configure` | `storage: string` | 必填 | UTF-8 长度最多 4096 bytes；必须是有父目录的绝对路径。只在未连接时生效。 |
| `connect` | `address: string`, `name: string`, `password: string`, `identity: object\|null` | `address`、`name` 必填；`password` 默认 `""`；`identity` 可缺省或为 `null` | `address` trim 后非空、最多 1024 bytes；`name` trim 后非空、最多 128 bytes；`password` 最多 1024 bytes。`identity` 为 `null` 时创建新 identity。连接中再次调用产生 error event。 |
| `disconnect` | 无 | — | 连接中断开；未连接时无操作。 |
| `join` | `channel: u64`, `password: string` | `channel` 必填；`password` 默认 `""` | `channel > 0`；密码最多 1024 bytes。仅在已完成订阅的会话中执行。 |
| `mute` | `input: bool`, `output: bool` | 均必填 | 更新本地音频门控并通过 `tsclientlib.client_update()` 更新服务器状态。 |
| `set_noise_suppression` | `mode: string` | 必填 | 枚举仅为 `rnnoise`、`none`；未连接时也会保存为下一会话设置。 |
| `send_channel_message` | `request_id: string`, `channel: u64`, `message: string` | 均必填 | `request_id` 为非空 ASCII、最多 128 bytes；`channel > 0`；消息 trim 后非空、最多 8192 bytes，并且发送时必须仍在该频道。 |
| `send_private_message` | `request_id: string`, `client: u16`, `uid: string`, `message: string` | 均必填 | `client > 0`；`uid` 非空、最多 256 bytes；request/message 同上；发送时 client 的 UID 必须仍匹配。 |
| `set_chat_visible` | `server: string`, `conversation: string`, `token: string`, `visible: bool` | 均必填 | 分别非空且最多 256/512/64 bytes。token 用于避免旧页面的关闭事件清除新页面状态。 |
| `set_app_active` | `active: bool` | 必填 | 控制可见会话的未读清除；不等同于网络连接状态。 |
| `shutdown` | 无 | 内部 | 停止 worker；平台不得作为业务命令调用。 |

示例：

```json
{
  "type": "send_channel_message",
  "request_id": "uuid",
  "channel": 123,
  "message": "你好"
}
```

长度校验使用 Rust UTF-8 字节长度，不是 Unicode 字符数。

## 数值范围

- Rust snapshot 中频道、父频道、排序、组 ID 使用 `u64`；频道命令也接受 `u64`。
- iOS 使用 `UInt64`，可表示完整范围 `0...18446744073709551615`。
- Android 模型使用 Kotlin `Long`，只可靠表示 `0...9223372036854775807` 的非负 ID。当前没有额外编码层；超过 `Long.MAX_VALUE` 的服务端 ID 是已记录但未通过真实服务器复现的兼容风险。
- 私聊命令的 `client` 是 `u16`（`1...65535`）。
- `icon`/`iconId` 是 `u32`；未读计数是饱和递增的 `u32`。
- `timestamp` 是 Unix epoch 毫秒 `u64`。当前值可安全放入 Kotlin `Long`；协议未承诺超出 `Long.MAX_VALUE` 的时间戳。
- JSON number 没有字符串包装。调用方不得经只能精确表示 53-bit integer 的中间层重编码 ID。

## `poll` envelope

`ts_poll`/JNI `poll` 每次返回：

```json
{
  "snapshot": { "status": "disconnected", "channels": [], "clients": [] },
  "events": [],
  "chats": null,
  "unread": {
    "serverId": null,
    "channel": null,
    "channelCount": 0,
    "privateCounts": {}
  }
}
```

| 字段 | 更新方式 |
| --- | --- |
| `snapshot` | 每次 poll 都返回当前完整值，不是增量；只在核心状态变化时被重新生成。 |
| `events` | 自上次 poll 后的一次性 FIFO 事件；poll 后消费。最多保留 64 条，溢出时丢弃最旧事件。 |
| `chats` | 发生聊天变化时返回当前服务器的完整聊天数组；没有更新时为 `null`。`[]` 表示明确更新为“空聊天列表”，与 `null` 不同。多次变化发生在一次 poll 前时只保留最新完整数组。 |
| `unread` | 每次 poll 都返回当前完整值，不是增量，也不为 `null`。 |

### Snapshot

`status` 枚举：`disconnected`、`connecting`、`reconnecting`、`connected`。

- 所有状态必有：`status: string`、`channels: array`、`clients: array`。
- `connected` 另有：`server: string`、`serverId: string`、`ownClient: u16`、`canSend: bool`。
- 非 connected 状态不提供上述连接字段；iOS 保存为 optional，Android 的 `canSend` 默认 `false`。

Channel（`channels[]`）全部字段：

| 字段 | 类型 | 可空 | 含义 |
| --- | --- | --- | --- |
| `id` | u64 | 否 | 当前连接内频道 ID。 |
| `parent` | u64 | 否 | 父频道；根为 0。 |
| `order` | u64 | 否 | TeamSpeak predecessor ID。 |
| `name` | string | 否 | 频道名。 |
| `password` | bool | 否 | 是否需要密码。 |
| `permanent` | bool | 否 | 是否为 permanent channel。 |
| `key` | string | 否 | GUID；无 GUID 时为 `id:<id>`。 |
| `icon` | u32 | 是 | 非零频道图标 ID，否则 `null`。 |
| `iconPath` | string | 是 | 已缓存本地文件绝对路径，否则 `null`。 |

Member（`clients[]`）全部字段：

| 字段 | 类型 | 可空 | 含义 |
| --- | --- | --- | --- |
| `id` | u16 | 否 | 当前 client ID。 |
| `channel` | u64 | 否 | 当前频道 ID。 |
| `uid` | string | 是 | TeamSpeak UID。 |
| `name` | string | 否 | 昵称。 |
| `avatarHash` | string | 否 | 服务端头像 revision/hash，可为空字符串。 |
| `avatarPath` | string | 是 | 已缓存头像路径。 |
| `badges` | Badge[] | 否 | 已识别徽章；未知徽章不会输出。 |
| `serverGroupIcons` | GroupIcon[] | 否 | 有图标的服务器组，按 `(sort_id, id)` 排序。 |
| `channelGroupIcon` | GroupIcon | 是 | 当前频道组有图标时存在。 |
| `muted`, `deafened`, `speaking` | bool | 否 | 输入静音、输出静音、当前有解码队列。 |

Badge：`id`、`name`、`description`、`filename` 均为必填 string，`iconPath` 为 nullable string。

GroupIcon：`id: u64`、`name: string`、`iconId: u32` 必填，`iconPath: string|null`。内置 100/300 图标可以没有文件路径。

### ChatMessage

聊天数组中的 object 使用 camelCase：

- 必填 string：`id`、`conversation`、`targetId`、`targetName`、`senderUid`、`senderName`、`senderAvatarHash`、`text`。
- 必填 enum：`kind` 为 `channel|private`；`status` 为 `received|pending|sent|failed`。
- 必填：`own: bool`、`timestamp: u64`。
- 可空：`error: string|null`、`avatarPath: string|null`。
- conversation ID 固定为 `channel:<channel key>` 或 `client:<uid>`；平台只派生同样的短字符串，没有另设协议。
- 发送消息先以 `pending` 进入完整聊天快照，服务器结果更新为 `sent`/`failed`；断线时未决消息变为 `failed`。

### Unread

- `serverId: string|null`
- `channel: string|null`（当前频道 conversation ID）
- `channelCount: u32`
- `privateCounts: object<string,u32>`，key 是对方 UID，不含 `client:` 前缀。

收到非己方 `received` 消息时计数；应用 active 且对应 conversation 可见时不计数/清零。切换服务器清空全部，切换频道只清频道计数。

### Events

| `type` | 字段 | 语义 |
| --- | --- | --- |
| `error` | `message: string` | 命令、连接、持久化或核心错误。当前没有 error code/分类。 |
| `identity` | `value: object` | `tsclientlib::Identity` 的 `key: string`、`counter: u64`、`max_counter: u64`；属于敏感数据，平台必须安全存储。 |
| `audio_muted` | `message: string` | 当前频道不是 Opus Voice/Music 等导致核心强制停止发送。平台应同步麦克风 UI。 |

## 平台模型映射

- iOS 保存 Channel 的所有字段；Android 有意不保存 `icon`，只保存实际显示所需的 `iconPath`。
- iOS 保存 Member 的 `avatarHash`；Android有意忽略它。两端都保存头像路径、徽章和组图标。
- iOS 保存 Badge 的 `filename`；Android 有意忽略。缓存下载和 revision 决策仍由 Rust 完成。
- iOS ChatMessage 保存 target/sender UID/hash 字段；Android 只保存当前 Compose 使用的 `id`、`conversation`、`senderName`、`avatarPath`、`own`、`text`、`timestamp`、`status`、`error`。两端均忽略 `kind`，因为 UI 按 conversation target 区分。
- Android 对当前使用的必填 snapshot/chat/unread 字段使用严格 `get*`；nullable 或状态相关字段才使用缺省/nullable 读取。

## C ABI、JNI 与所有权

### C ABI

- `ts_create() -> Bridge*`：返回拥有的 handle；由 `ts_destroy` 释放。
- `ts_destroy(handle)`：null 无操作；释放后不得再调用。
- `ts_command(handle, utf8_c_string) -> i32`：0 表示命令已进入队列；-1 表示 null、UTF-8/JSON/校验错误或队列不可用，同时尽可能生成 error event。0 不代表服务器已完成操作。
- `ts_poll(handle) -> char*`：null handle 返回 null；成功返回 Rust 分配的 UTF-8 CString，调用方必须且只能用 `ts_free` 释放一次。
- `ts_capture(..., count) -> i32`：0 已入队；1 队列满/关闭、该帧被丢弃；-1 表示指针无效或 count 不是 960。
- `ts_playback(..., capacity) -> usize`：有帧时固定返回 1920；无帧、无效指针/handle 或容量不足均返回 0。
- `ts_set_notifier(handle, callback, context)`：无返回值；callback 可为 null 以清除。

### JNI

- `create/destroy/command/poll/capture/playback` 映射同一 C ABI；JSON 用 UTF-8 `ByteArray`，避免 modified UTF-8 差异。
- null/0 handle、capture 数组不是 960、playback 数组少于 1920 等 JNI 参数错误抛 Java runtime exception；C ABI 正常返回码保持不变。
- Android 没有 notifier；`VoiceService` 的现有工作循环调用 `poll`。

## Notifier

notifier 只表示“核心状态可能有变化”，不携带数据，也不保证一次回调对应一个字段变化。实际值必须随后通过 `poll` 获取。

回调在触发变更的 Rust worker/持久化线程上、且在 output mutex 持有期间执行，因此回调不得同步重入 `poll`、`command` 或其他可能获取同一锁的函数。iOS callback 只向 main queue 派发 `receive()`，符合此要求。Android 工作循环 poll 是允许的等价机制，不要求新增 JNI notifier。

## 音频

- Capture：48 kHz、单声道、signed i16、每次准确 960 samples（20 ms）。RNNoise 模式内部拆成两个 480-sample frame；失败时降级为未处理音频。
- 核心只向 Opus Voice/Opus Music 频道发送；其他 codec 产生 `audio_muted`。
- Playback：48 kHz、双声道、interleaved f32，每个 native frame 为 1920 samples（960 stereo frames，20 ms）；输出 buffer capacity 至少 1920。
- Opus 编解码、RNNoise、音频队列在 Rust；AVAudioSession/AVAudioEngine 与 AudioRecord/AudioTrack 留在平台层。

## 兼容规则

- 新字段只能先作为可选字段增加；旧平台会忽略未知字段。
- enum 扩展前必须更新两端：Swift 的 `ChatStatus` 是闭合 enum，未知值会解码失败。
- 删除字段、改名、改变类型/必填性、改变 `null` 与空数组语义、改变 conversation ID 均属于破坏性变更；当前没有版本协商，必须同步发布 Rust/iOS/Android，并更新本文和契约测试。
- 如需独立升级核心与 UI，再引入最小版本字段；当前同仓库同步构建，不新增推测性的版本系统。
