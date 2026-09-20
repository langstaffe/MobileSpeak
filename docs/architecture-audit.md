# 跨平台架构审计报告

范围为当前提交及本次 diff；`native/src/lib.rs` 是 bridge 事实来源。没有把被 `.gitignore` 排除的历史 `ios/NATIVE_VALIDATION.md` 当作本次验证结果。

## 1. 审计发现

### P0

1. **iOS 书签密码被写入 UserDefaults 明文。** `Bookmark.password` 会被 JSONEncoder 持久化，初始化还把旧 Keychain 密码迁出并删除 Keychain 副本；Android 对同类数据使用 Android Keystore + AES-GCM。已修复：新保存密码只进 Keychain；已有明文先全部写入 Keychain，再从 UserDefaults 元数据移除。迁移任一步失败会设置 `bookmarkLoadFailed` 并停止书签写入，避免覆盖原数据。

### P1

1. **Rust 静音状态手写 `clientupdate` 原始命令。** 固定版本 `tsclientlib` 已提供 `Connection::client_update().set_input_muted().set_output_muted()`。已改用 typed API，并删除手写 command builder 和相关 tsproto command imports。
2. **Android 对 bridge 必填字段使用 `opt*` 静默补默认值。** Channel 的 `password/permanent`、Member 的数组和布尔状态、Chat 的 `own`、Unread 计数等与 iOS 的严格解码不一致。已对当前模型实际使用的必填字段改为 `get*`；nullable/状态相关字段保留 optional 读取。

### P2 / 已记录但未修改

1. Rust/iOS 的 ID 是 `u64/UInt64`，Android 是 signed `Long`。超过 `Long.MAX_VALUE` 的服务端 ID 可能不兼容，但未从真实服务器复现；改成 string 或新增编码层会破坏契约，因此只在 bridge 文档明确范围。
2. Swift `ChatStatus` 是闭合 enum，Android 保存 string。当前 Rust 只输出四个已知值，没有现有行为差异；枚举扩展规则已写入契约，不新增 unknown 兼容层。
3. 频道排序/层级、conversation ID、书签规范化/自动命名和 spacer 展示在两端各有短小实现。迁入 Rust 会增加字段或让平台安全存储绕路，总代码不会下降；保留现状和两端测试。
4. 当前 error event 只有字符串，没有 code。未发现导致两端行为不同的证据，不新建错误体系。
5. 没有 CI。按范围要求只记录，不搭建流水线。
6. 没有双端截图、Android emulator、iOS 真机或真实服务器证据；Android 真机仅完成 instrumentation 与启动冒烟验证，UI 对比、音频和官方兼容继续标记未验证。

## 2. 实际修改文件

- `native/src/lib.rs`：typed client update；新增 poll 消费语义测试。
- `ios/NativeApp/Client.swift`：书签密码 Keychain 保存、删除和明文迁移保护。
- `ios/NativeTests/ClientTests.swift`：确认密码仍可读取且 UserDefaults 不含密码。
- `android/app/src/main/kotlin/dev/mobilespeak/mobilespeak/Models.kt`：当前使用的必填 snapshot 字段严格解析。
- `android/app/src/main/kotlin/dev/mobilespeak/mobilespeak/ClientSession.kt`：Chat/Unread 必填字段严格解析。
- `android/app/src/androidTest/kotlin/dev/mobilespeak/mobilespeak/NativeCoreTest.kt`：补充初始 envelope 的 null/空数组断言。
- `docs/bridge-contract.md`：现有 bridge 契约。
- `docs/ui-spec.md`：现有原生 UI 规范和允许差异。
- `docs/cross-platform-validation.md`：本次真实验收矩阵。
- `docs/architecture-audit.md`：本报告。

没有修改 `THIRD_PARTY_NOTICES.md`；`openspeak_flutter` 保持为合法设计来源说明。

## 3. 未修改发现及原因

- P2 的数值上限、future enum、字符串错误和 CI 没有当前故障证据，直接改会扩大契约或基础设施范围。
- 没有把频道排序或 conversation ID 移到 Rust：当前两端实现短、测试已存在，新增 snapshot 字段反而增加总复杂度。
- 没有移动书签逻辑到 Rust：密码与数据恢复依赖 Keychain/Keystore。
- 没有统一 notifier：Android poll 已与音频 service loop 共用节奏，没有耗电/延迟测量证明需要 JNI callback。
- 没有 UI 代码改动：静态代码对比显示关键 tokens/结构已一致，没有截图或实际运行证据支持逐像素修改。

## 4. 协议实现边界

Rust/native 仍是唯一 TeamSpeak 协议实现。iOS 不构造 wire packet；Android 不构造 wire packet。

`tsclientlib` 当前直接负责：连接/临时断线重连、bookkeeping state/events、频道订阅和移动、消息发送及结果、file transfer、identity level、音频收发入口。Rust 核心在其上负责 bridge 状态、聊天持久化、未读、缓存、头像/频道图标/组图标/徽章、Opus encode/decode queue 和 RNNoise。

审计后不再有手写 TeamSpeak command。仍直接使用 `tsproto_packets::AudioData`、`OutAudio`、`CodecType`，原因是固定版本 `tsclientlib::send_audio` 的公开输入就是这些 packet types；使用位置只在 Rust 核心，没有下沉到平台。

## 5. Bridge 契约内容

`bridge-contract.md` 记录：公开/内部命令、JSON 键、必填/可选/默认、枚举、UTF-8 字节限制、u64/Long 风险、snapshot/events/chats/unread 完整值与一次性语义、Chat/Unread 模型、error/identity/audio_muted、C ABI/JNI 返回码和所有权、notifier 线程/禁止重入、音频格式、未版本化现状和兼容规则。

## 6. Notifier 与 Android poll

iOS notifier 在 Rust output lock 持有期间触发，只把工作异步派发到 main queue，随后 `ts_poll` 取数据；callback 本身不取数据且不重入。Android `VoiceService` 现有 worker 在音频 tick 前调用 `pollCore()`。二者都消费同一 poll envelope，差异属于平台生命周期实现，予以保留。

## 7. 平台模型字段

- iOS 保存 Channel `icon`、Member `avatarHash`、Badge `filename` 和较完整 Chat target/sender 字段。
- Android 有意忽略 Channel `icon`、Member `avatarHash`、Badge `filename`、Chat `kind/target*/senderUid/senderAvatarHash`，因为当前 Compose 不使用；实际缓存和资源 revision 在 Rust。
- Android 本次只收紧已使用必填字段，没有为未使用字段添加无意义属性。
- 两端 conversation 均为 `channel:<key>` / `client:<uid>`；Rust 是聊天记录事实来源。

## 8. 重复逻辑处置

- 删除：Rust 手写 `clientupdate` command builder。
- 保留：两端频道排序（predecessor + 层级 + orphan fallback）、conversation 短派生、书签规范化/自动命名、spacer 展示。
- 原因：这些平台实现短小，移入 Rust 不能减少总复杂度；排序/spacer 已有两端测试，书签仍受平台安全存储约束。

## 9. 安全存储边界

- iOS identity：Keychain generic password，`AfterFirstUnlockThisDeviceOnly`。
- iOS 书签：非敏感 metadata 在 UserDefaults；密码在独立 Keychain item，同样为 `AfterFirstUnlockThisDeviceOnly`。旧明文迁移先写 Keychain，再 scrub metadata；失败会阻止后续写入。删除先更新 metadata，再删 Keychain，清理失败向 UI 报错。
- Android identity、书签和密码：一个 AES-GCM 加密 JSON blob 存在 private SharedPreferences，AES-256 key 在 Android Keystore；解密失败后禁止覆盖，写入使用同步 `commit()` 并检查结果。
- 没有密码、identity、账号或真实服务器信息进入测试、日志或文档。

## 10. UI 一致性

代码对比确认两端共享相同颜色值、64 Header、书签卡片/空状态、频道卡片/层级缩进、成员头像/徽章、频道 sheet、48 VoiceBar、底部三导航、14 圆角聊天气泡、设置结构、错误/加载状态。详见 `ui-spec.md`。

本次没有 UI 代码修改；iOS 原生导航/sheet/Toggle 与 Android 返回键/ModalBottomSheet/权限/前台服务差异保留。

## 11. 实际测试和构建

- Rust：8 tests passed；format/check 通过。
- iOS simulator：10 tests passed、1 physical-audio test skipped；Debug build 通过。测试覆盖 bridge fixture、频道排序、spacer、音频意图、RNNoise 设置、sheet 逻辑，以及修复后的 Keychain 书签密码不落 UserDefaults。
- Android JVM：测试与 Debug APK build 通过；覆盖书签规范化、频道排序、spacer。
- Android Rust arm64-v8a/x86_64 native library 在 Gradle 构建中成功重新编译。
- Android 真机：Android 10（API 29）设备上的 instrumentation 1/1 通过；Debug APK 安装和冷启动通过，进程存活、`MainActivity` 位于前台、logcat 无 fatal crash，首页可访问性树可读取。

## 12. 无法执行的验证

- iOS 真机：没有可用设备，无法安装或运行。
- 完整语音质量、Bluetooth、音频中断、权限拒绝：需要真机交互。
- 连接、频道、聊天、媒体下载、断线重连：没有授权/配置真实测试服务器和凭据。
- 双端 UI 截图对比：未执行；Android 只有启动页语义树证据，不能替代 iOS/Android 逐屏视觉对比。

## 13. 真机状态

iOS 真机：未验证。Android 真机：API 29 设备上的 instrumentation 与启动冒烟通过；连接、聊天、音频、权限和持久化场景仍未验证。历史忽略文件提到的旧设备记录仅作为线索，没有计入本次结果。

## 14. 官方兼容性

本次没有使用官方服务器或官方客户端进行互操作，兼容性未验证。固定 `tsclientlib` revision 和单元测试不能替代该结论。

## 15. 剩余风险

- Android signed Long 与 Rust u64 的高位范围。
- Chat status 将来扩展时 Swift closed enum 需要同步更新。
- 网络、消息结果、重连、媒体下载和真实音频路径缺少本次服务器/设备验证。
- iOS UserDefaults 对 metadata 普通写入没有同步错误回执；密码先写 Keychain，因此该限制不暴露 secret，也不导致旧 secret 被先删除。
- event queue 上限 64、chat update 为 latest full snapshot；平台长时间不 poll 可能丢最旧事件，但当前工作循环/notifier 会持续唤醒，未观察到问题。

## 16. 最终 review 发现与修复

- 新增测试首次使用 `UserDefaults.synchronize()` 返回值判断迁移成功，模拟器证明它会产生假失败；已删除该不可靠判据。迁移仍保持“全部 Keychain 写入成功后才 scrub 明文”的数据保护顺序。
- Android local JVM 的 `org.json` 是不可运行桩，不能作为解析测试；没有为此增加依赖，改在现有 instrumentation test 中验证 native 初始 envelope。连接 API 29 真机后该测试 1/1 通过。
- iOS 首次复测使用 `CODE_SIGNING_ALLOWED=NO`，导致 Keychain entitlement 被移除并按预期拒绝保存；最终改用 simulator 默认 ad-hoc 签名后全部可执行测试通过，没有为测试绕过 Keychain。
- 最终 diff 未引入依赖、代码生成、Flutter、协议扩展或无关文件；Rust/native 仍是唯一协议实现。
