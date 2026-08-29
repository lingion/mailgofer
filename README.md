<p align="center">
  <a href="https://github.com/lingion/mailgofer/stargazers"><img src="https://img.shields.io/github/stars/lingion/mailgofer?style=for-the-badge&logo=github&color=FFD700" alt="Stars"></a>
  <a href="https://github.com/lingion/mailgofer/issues"><img src="https://img.shields.io/github/issues/lingion/mailgofer?style=for-the-badge&logo=github&color=EF4444" alt="Issues"></a>
  <br>
  <a href="https://github.com/lingion/mailgofer/commits/master"><img src="https://img.shields.io/github/last-commit/lingion/mailgofer?style=flat-square" alt="Last commit"></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/lang-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Compose-4285F4?style=flat-square" alt="Compose">
</p>

<h1 align="center">MailGofer Android</h1>

<p align="center">
  MailGofer 的安卓客户端:多邮箱管理 + 收件箱 + 自动未读。<br>
  管理你自己部署的 MailGofer worker,一次建一批临时邮箱,逐个收信。
</p>

---

## 这是什么

[MailGofer](https://github.com/lingion/mailgofer) 是部署在 Cloudflare Workers + D1 上的邮件 API 后端。这个 App 是它的安卓前端:填入你的 worker 地址和 API Token,就能在手机上创建邮箱、收邮件、看正文。

App 不带任何服务端,也不内置账号体系——你连的是你自己的 worker。

## 功能

**多邮箱列表**
- 主页是邮箱列表,每个邮箱一张卡片:地址、到期日、未读数徽标
- 未读数后台自动算:所有邮箱 10 秒轮询一轮,新邮件进了哪个邮箱,哪个卡片亮数字
- 点进任意邮箱看它的收件箱,返回后未读自动清零
- 卡片上的 × 把邮箱从列表移除(服务端到期后自动清理)

**创建邮箱**
- 单个创建:名字留空自动生成 `mbx_xxx`,或自定义名字
- 批量创建:填一个前缀和数量,一次建最多 30 个(`shop-1` 到 `shop-30`),名字先在本地校验服务端规则再发请求
- 共用设置:域名、有效期(1-72 小时)、最大邮件数(收满自动清空)

**收件箱**
- 邮件列表(发件人、主题、正文预览、时间),10 秒自动轮询可关
- 邮件详情:纯文本 + HTML 降级显示
- 删除单封;复制地址;mailbox token 展示供脚本使用

## 安装

从 [Releases](https://github.com/lingion/mailgofer/releases) 下载 APK 直接安装(Android 8.0+)。

首次使用:进「设置」填服务地址(worker 域名)和 API Token。鉴权走 `x-api-key`,Token 只存在本机 DataStore。

## 从源码构建

```bash
git clone https://github.com/lingion/mailgofer-android.git
cd mailgofer-android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 + Android SDK 36。

### 个人预填版(可选)

debug 构建会读取根目录的 `personal.properties`(git-ignored),把服务地址、Token、域名预填进 BuildConfig,首启自动写入配置,跳过手动填:

```properties
presetHost=api.example.com
presetDomain=mail.example.com
presetToken=your-worker-api-token
```

文件不存在时这些值为空串,公开构建不受影响。**不要把真实 Token 提交进仓库。**

## 技术栈

```
language        = Kotlin
ui              = Jetpack Compose + Material 3
navigation      = Navigation Compose
storage         = DataStore Preferences(邮箱列表 JSON 序列化)
network         = HttpURLConnection(无第三方网络库)
serialization   = kotlinx-serialization-json
build           = AGP + Gradle (Kotlin DSL)
minSdk          = 26 / targetSdk = 36
```

测试:`./gradlew testDebugUnitTest`(创建命名规则、未读增量算法、JSON 往返序列化均有单测)。

## 相关仓库

- [`lingion/mailgofer`](https://github.com/lingion/mailgofer) — 服务端(Cloudflare Workers + D1),`lingion/mailgofer` 是唯一上游

## License

GPL-3.0
