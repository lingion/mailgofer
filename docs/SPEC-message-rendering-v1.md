# Spec: MailGofer 安卓端 — 邮件渲染 + 验证码提取

## Objective

让用户看到的邮件是**正常人类能看懂的格式**，并在收件箱列表和详情页把**验证码**抽出来、一键复制。

### 用户故事

- US-1: 打开详情页，看到的应该是干净正文，不是 `--000000000000f924\r\nContent-Type: text/plain; charset="UTF-8"\r\n\r\ntest\r\n\r\n--000000000000f924\r\nContent-Type: text/html; charset="UTF-8"\r\n...` 这种被当成纯文本塞进 content 的原始 MIME。
- US-2: 邮件主题 `=?UTF-8?B?5o6o6YCB5Yiw5omL5py66aqM6K+B?=` 应当显示为「手机端到端验证」，不是字面 encoded-word。
- US-3: HTML 邮件至少显示可读文本 + 链接可点 + 图片可见，不再退回 `replace(<[^>]+>, " ")` 的纯文字残骸。
- US-4: 在收件箱列表最右侧，如果识别出验证码（如 Instagram 的 `G-123456`），显示一个胶囊+复制按钮，无需点进详情也能取走。
- US-5: 详情页顶部也有同样的验证码条，方便复制。

### Success Criteria (可验证)

- [ ] 详情页对脏数据（原始 MIME 进 content 的存量）显示为干净纯文本 + HTML 段不再泄漏成 Content-Type 头。
- [ ] 主题 RFC 2047 编码在列表项、详情 TopAppBar、通知均解码。
- [ ] HTML 邮件用 AndroidView/WebView 渲染，能看到图片和链接。
- [ ] 收件箱列表项最右侧检测到 OTP 时显示验证码胶囊 + 复制图标。
- [ ] 详情页正文顶部展示抽取到的验证码 + 复制按钮。
- [ ] 抽出错误的 OTP 不会错误显示（不该是验证码时直接不渲染胶囊）。
- [ ] JUnit 单测覆盖 RFC 2047 主题解码、原始 MIME 切分、OTP 抽取三段核心逻辑。

## Tech Stack

- Kotlin + Jetpack Compose + Material3（现有）
- kotlinx.serialization + DataStore（现有）
- Android `WebView` via `AndroidView` for HTML-only messages; JavaScript/file access disabled, external links delegated to the system browser

无 OCR。验证码识别 = **关键词正则 + 4-8 位 OTP 模式**（Instagram/Google/Microsoft/Twitter/Discord/Apple/腾讯/阿里 99% 都是纯文本数字字母 OTP，不走图片验证码）。

## Commands

- Build: `cd ~/mailgofer-android && ./gradlew assembleDebug`
- Test: `cd ~/mailgofer-android && ./gradlew test`
- Lint: `cd ~/mailgofer-android && ./gradlew lintDebug`
- Install (真机): `adb install -r app/build/outputs/apk/debug/app-personal-debug.apk`

## Project Structure

新增文件：

```
app/src/main/java/com/lingion/mailgofer/
├── format/
│   ├── MimeSanitizer.kt        # 原始 MIME → 干净 text/html 切分
│   ├── Rfc2047.kt              # =?charset?B/Q?encoded?= 主题解码
│   └── OtpExtractor.kt         # 关键词+正则抽取验证码
└── ui/
    ├── HtmlEmailView.kt        # AndroidView 包 WebView + 链接处理
    ├── OtpChip.kt              # 验证码胶囊+复制按钮
    └── MessageScreen.kt        # 改：用 MimeSanitizer + HtmlEmailView + OtpChip
└── MailboxInboxScreen.kt       # 改：列表项右侧加 OtpChip

app/src/test/java/com/lingion/mailgofer/format/
├── MimeSanitizerTest.kt
├── Rfc2047Test.kt
└── OtpExtractorTest.kt
```

## Code Style

按现有 Kotlin/Compose 风格（data class + 顶级函数 + 文件级文档注释）。不引入新风格。

```kotlin
// 现有 MailboxLogic.kt 的风格: 文件顶部 doc-comment 解释来源,函数简短,数据流单向
data class ParsedBodies(val text: String, val html: String)

/** 服务端修正前的存量邮件: content 字段是被当成纯文本塞进来的整段原始 MIME.
 *  这里按 multipart boundary 切成 text/html 两段,丢弃 MIME 头本身. */
fun sanitizeRawMime(content: String?): ParsedBodies {
    // 1) 检测 multipart 边界
    // 2) 切分 parts,只保留 text/plain 与 text/html 段
    // 3) 找不到 boundary 就回退整段 content 当 text
}
```

## Testing Strategy

- 框架: JUnit 4（项目已用）
- 覆盖三个新增纯函数: `MimeSanitizer.sanitizeRawMime`、`Rfc2047.decode`、`OtpExtractor.extract`
- 测试用例:
  - `MimeSanitizerTest`: rockbase 那封存量为输入，期望 text="test\n"，html 含 `<div`
  - `Rfc2047Test`: `=?UTF-8?B?5o6o6YCB5Yiw5omL5py66aqM6K+B?=` → "手机端到端验证，附注册链接"
  - `OtpExtractorTest`: 关键词+6位数字（Instagram `G-123456`）、关键词+8位数字（Microsoft 8 位）、不匹配时返 null
- UI 层验证: 装到真机后用 `adb shell am start -n com.lingion.mailgofer.personal/.MainActivity` + 手动打开两封存量脏邮件对比

## Boundaries

- **Always**: 先写失败单测；保留所有现有 data/Mailbox* 测试；commit 前跑 `./gradlew test`
- **Ask first**: 不改服务端 worker；不动 D1 数据；不动 personal.properties；不发布 release（用户没要求）
- **Never**: 不引入 OCR/Tesseract（用户问"能不能嵌入"，但 APK 膨胀 + 99% 场景不必要，明确说不并解释原因）；不写用户 token 到 git；不改 build.gradle.kts 之外的 Gradle 文件

## Open Questions

1. 详情页 HTML 渲染已确定使用 AndroidView/WebView：只在 HTML-only 邮件启用，禁用 JavaScript、文件/内容访问；图片按邮件原始 URL 加载，外部链接交给系统浏览器。
2. 验证码「4-8 位数字/字母」+ 关键词组合里，要不要支持 6-7 位纯数字（很多 OTP 是 6 位）？要支持。
3. 是否要把抽取到的验证码也写到 logcat 方便调试？只在 debug build 写。

## Mailbox 生命周期扩展（2026-08-29）

- 创建约束至少满足一项：有效期或最大邮件数；有效期留空表示永不过期，最大邮件数留空表示无限收信。
- 客户端每 10 秒先同步服务端邮箱状态；过期或 inactive 在列表显示红色星号/警告。
- 「刷新邮箱」需要用户确认；服务端会清空该邮箱全部旧邮件、重新激活并重置计数，之后可继续收信。