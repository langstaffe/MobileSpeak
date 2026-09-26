# MobileSpeak 原生 UI 规范

本文记录现有 SwiftUI/Jetpack Compose 实现。iOS 是主要视觉参考；审计确认两端 Palette、主页面结构、频道卡片、VoiceBar、聊天和设置已基本一致，因此本次没有重写 UI。

## 颜色

| 名称 | Hex | 用途 |
| --- | --- | --- |
| text | `#F2F3F5` | 主文字、主要图标 |
| background | `#2B2D31` | 页面背景 |
| bottom | `#292B2F` | VoiceBar、底部导航、输入区 |
| card | `#36393F` | 卡片、收到的消息气泡 |
| selected | `#3A3D42` | 当前频道卡片 |
| border | `#3A3E46` | 卡片/分隔线 |
| muted | `#949BA4` | 次要文字、禁用图标 |
| accent | `#5865F2` | 主按钮、选中项、自己发送的气泡 |
| green | `#23A559` | 连接、发言、麦克风开启 |
| disconnect | `#C83F4A` | 断开、未读、错误 |
| error background | `#542A30` | 顶部错误条 |

## 页面骨架

- 深色原生 UI；系统字体，不嵌入自定义字体。
- Header 高 64 pt/dp，水平边距 14，图标/头像 34，断开按钮点击区 44。
- 主区按“频道 / 成员 / 设置”切换；频道和成员页在已连接时显示，未连接时显示书签或空状态。
- 底部先放最小高度 48 的 VoiceBar，再放三项底部导航；导航 top/bottom padding 为 10/8，每项最小点击高度 44。
- iOS 使用 NavigationView/NavigationLink/sheet；Android 使用 Compose 状态、BackHandler/ModalBottomSheet。保留各自系统返回手势。

## 尺寸与排版

| 项目 | 规格 |
| --- | --- |
| 页面常用边距 | 14（频道/聊天），16（书签/成员），20（设置/sheet），24（空状态） |
| 常用间距 | 3、4、5、8、9、10、11、12、16、20、24 |
| 页面标题 | 20，Bold/Heavy，line height Android 24 |
| 正文/输入 | 17，Regular/Semibold，Android line height 20 |
| 频道名 | 15 Semibold，Android line height 18 |
| 辅助文字 | 11–13，Android line height 13–16 |
| 底栏文字 | 12 Bold/Semibold，Android line height 14 |
| 卡片圆角 | 14 |
| 频道聊天按钮 | 48×48，圆角 10 |
| 默认头像 | 34；频道预览 24；聊天头像 30 |
| Badge/group icon | 18；内置 group glyph 17、角标 9 |
| 频道图标 | 默认 21；详情 28；圆角 4 |
| 未读角标 | 字号 10，最多显示 `99+` |

最小交互目标保持 44×44；纯展示 spacer 不接收点击。文本允许系统动态行为，但当前字号使用显式 system size/sp，没有另建 typography 层。

## 频道与成员

- 频道按 TeamSpeak predecessor `order` 组成兄弟链，先按 ID 提供确定性 fallback；递归显示层级，视觉缩进最多 4 级、每级 10。
- 当前频道使用 selected 背景、accent 2pt/dp 边框；其他频道使用 card 背景、border 1pt/dp。
- 卡片内边距 10 + leading 4；频道标题行最小高 48。成员预览显示前两名的 24 头像与总人数。
- spacer 仅对根级 permanent channel 生效，支持 `[spacer]`、`[lspacer]`、`[cspacer]`、`[rspacer]`、`[*spacer]` 前缀；重复填充目标约 48 个字符。空 spacer 在 iOS 隐藏无障碍内容，Android 不添加额外点击语义。
- 成员行最小高 44、间距 12。头像发言边框为 green 3。最多显示 4 个 badge，并在总展示位约束内显示 server/channel group icon。
- 频道详情 header 44、成员区最小 52、加入按钮最小 56；整体高度按内容增长并上限为 viewport 的约 78%。iOS sheet 与 Android ModalBottomSheet 的动画/圆角可保持平台差异。

## VoiceBar 与设置

- VoiceBar 左侧 18 的 waveform、12 Semibold 状态文字；右侧麦克风点击区 44×48、图标 20，扬声器点击区 44×48、图标 22。
- 未连接显示“未连接”，连接中/重连显示“正在连接…”，连接后显示当前频道。
- 关闭收听时麦克风输入也发送为 muted，但保留用户独立的麦克风意图，以便恢复收听后正确还原。
- 设置页 spacing 24、边距 20；RNNoise 选项卡圆角 14，每项最小高 56、水平边距 16、选择图标 22。
- iOS 原生 Toggle 与 Android 带 Switch semantics 的 Compose 控件允许保留各自原生可访问性和动画差异。

## 聊天

- 页面消息间距 12、内容边距 14；头像 30，头像与气泡间距 8。
- 气泡水平/垂直 padding 为 12/9、圆角 14；己方 accent，收到消息 card。
- 频道消息在对方气泡上方显示 12/Caption sender name。
- pending 显示 12 左右进度；failed 使用 disconnect/red 图标和 11/Caption2 错误文字。
- 输入区背景 bottom、padding 10、间距 10；消息 trim 后非空且 UTF-8 不超过 8192 bytes 才可发送。Android 超长输入显示错误边框；iOS 提交时显示“消息过长”。
- 文本可选择；私聊 header 显示 30 头像。iOS 保留系统导航栏/返回手势，Android保留显式返回按钮与系统返回键。

## 错误、加载与空状态

- 错误：Header 下方 `#542A30` 横条，13/footnote 文本，可关闭；业务错误不改造成新错误分类体系。
- 加载：页面中央原生 ProgressView/CircularProgressIndicator，间距约 18–20，并区分连接与重连文案。
- 空书签：52 headphones、20 Heavy 标题、15/subheadline muted 说明、accent 胶囊按钮。
- 空频道成员：muted “暂时没有成员”，最小高 52。
- 空聊天：保留空消息区域和输入栏，不伪造占位消息。

## 允许的平台差异

- iOS 系统返回手势、NavigationView、SwiftUI sheet/alert、原生 Toggle、AVAudioSession 权限流程。
- Android 系统返回键/手势、BackHandler、ModalBottomSheet/AlertDialog、前台服务通知、运行时权限流程。
- 状态栏/导航栏、安全区、系统键盘、字体栅格化、原生控件细节。
- 权限拒绝和系统设置入口使用平台文案/机制；不得为了像素一致绕过系统可访问性。

## 本次对比结论

- 代码对比确认颜色值、Header、书签/空状态、频道卡片、成员行、频道详情、VoiceBar、底部导航、聊天气泡、错误/loading 状态的关键数值一致。
- spacer、频道排序、消息可用性、音频开关语义均有两端对应实现；频道排序和 spacer 已有平台单元测试。
- 没有本次截图或双真机视觉对比证据，因此视觉结果仍标记“未验证”；没有仅为形式统一而修改 UI 代码。


## 头像 UI（2026-09-26）

- 设置页清除头像：系统正文 17pt/sp、Regular，文字末端与头像卡片外沿对齐，零水平按钮内边距；最小触摸高度 48，卡片到按钮间距 10、头像区域到下一设置项间距 24。启用颜色使用现有 disconnect `#C83F4A`，处理中仍禁用。
- 头像下方仅保留实际错误和 checking/uploading/clearing 反馈；常驻同步说明和成功/待同步状态行不占布局空间。卡片副标题、确认弹窗与麦克风说明保留。
- 裁切区域保持正方形，边长为可用宽度减 32、内容可用高度的 55%、420 三者最小值（原有下限 100）；内容纵向滚动。白色方框 1、圆框 2，圆外遮罩 35%。
- 裁切内容边距 16、纵向间距 20；Android 标题 17 Semibold，根据取消按钮实测宽度对称留位，允许文字换行。取消使用圆角描边，确认使用 accent 宽胶囊按钮、最小高度 60。
- Android 使用现有 Material Slider 的原生轨道样式插槽：6 高圆角轨道、白色 36×24 胶囊滑块、至少 48 高操作区，无末端装饰点。缩放范围与状态保持原实现的 1…4。
- 四个可见移动按钮替换为裁切区域上的本地化原生无障碍自定义动作，继续调用原移动/边界约束逻辑。
