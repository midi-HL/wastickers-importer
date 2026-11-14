# .wastickers Importer / .wastickers 导入器

一个极简、免费、开源、无广告、**不联网**的 Android 小工具：打开现成的 `.wastickers` 文件，预览整包贴纸，并通过 WhatsApp 官方第三方贴纸 API 添加到 WhatsApp / WhatsApp Business。

> English summary: A tiny offline Kotlin app that opens `.wastickers` ZIP packs, previews stickers, materializes them into internal storage, and adds them to WhatsApp or WhatsApp Business through the official sticker ContentProvider API. No ads, no analytics, no account, no INTERNET permission, no sticker editor. CI builds signed-or-unsigned release APKs plus installable debug APKs for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, and a universal APK.

## 1. 项目动机

Telegram 机器人（如 `@tgtowabot`）和 sticker-convert 等工具可以把贴纸包导出为 `.wastickers`，但把这些文件送进 WhatsApp 的最后一步往往依赖广告多、闭源的商业 app。WhatsApp 官方 `WhatsApp/stickers` 示例（BSD）早已有可移植的 ContentProvider，却把贴纸包写死在 assets 里。本项目只改变「贴纸包来源」：运行时解析任意 `.wastickers` 并落地到内部存储，其余导入契约严格对齐官方示例。

## 2. 功能与非目标

**功能（v1）**

- F1 三条文件入口：`ACTION_VIEW` 文件关联（多种 `content`/`file`/mime/`pathPattern` 组合）、`ACTION_SEND` 系统分享、app 内 SAF 文件选择。不申请 `READ_EXTERNAL_STORAGE`。
- F2 先复制到 app 内部缓存，再解压/转换到 `files/packs/<identifier>/` 并写 `manifest.json`；源文件之后删除也不影响再次添加。
- F3 预览页：贴纸网格（动态 WebP 显示首帧）、包名、作者、张数；名称/作者可编辑并同步 manifest。
- F4 一键「添加到 WhatsApp」；优先 `com.whatsapp`，其次 `com.whatsapp.w4b`；两个都装时弹出选择；返回 `validation_error` 时翻译成人话并保留原始信息。
- F5 主页卡片列表、再次添加、删除本地副本，删除说明明确告知 WhatsApp 内已添加的包需要在 WhatsApp 内移除。
- F6 identifier 由清洗后的包名 + 内容 SHA-256 短哈希派生，仅含 `[A-Za-z0-9._-]`，不以 `.` 开头；重复导入得到同一 identifier，超过 30 张的子包带 `-partNofM` 后缀。
- F7 从文件名提取最多 3 个 emoji，缺失时补 `😀`。
- 兜底修复：静态 PNG/非 512×512 图等比居中补透明边到 512×512，转 WebP 并逐步降质到 ≤100 KB；托盘图统一生成 96×96 PNG；动态 WebP 原样透传。

**明确不做**：贴纸制作/编辑、从 Telegram 下载、TGS/Lottie 转换、账号云同步、商店推荐、统计、广告 SDK、INTERNET 权限、任何数据库或大型第三方 SDK。

## 3. 使用步骤

1. 用 `@tgtowabot` 或 `sticker-convert` 得到 `.wastickers` 文件。
2. 在文件管理器点击该文件并选择本 app；或从文件管理器/下载 app「分享」到本 app；或打开本 app 点右下角「打开 .wastickers」用系统文件选择器挑文件。
3. 在预览页检查整包贴纸，可修改名称和作者。
4. 点「添加到 WhatsApp」，选择目标 app（若两者都装），在 WhatsApp 确认页确认。
5. 打开 WhatsApp 任意聊天 → 表情/贴纸 → 贴纸列表，即可发送。动态包在 WhatsApp 内仍是动态。

> 删除本 app 内某包后，由于文件已落地，只要该目录还在就能再次添加；删除动作本身会移除目录。若源 `.wastickers` 已不存在，未删除的包仍可再次添加。

## 4. `.wastickers` 格式说明

本质是 ZIP，常见内容：

| 文件 | 说明 |
| --- | --- |
| `title.txt` | 名称，第一行有效；缺失用文件名兜底 |
| `author.txt` | 作者；缺失用 `Unknown` |
| 一张 PNG | 托盘图标；按包内 PNG 像素面积最小者探测，其余 PNG 当普通贴纸 |
| 多张 `.webp`（也可能 `.png`） | 贴纸；按 ZIP 条目顺序 |

字段缺失容错；非 ZIP/损坏文件给出明确中文/英文错误而不崩溃。托盘图标缺失时用首张贴纸生成。包内没有 emoji 元数据，按 F7 补默认值。

## 5. WhatsApp 硬性限制与处理

| 规则 | 处理 |
| --- | --- |
| 每包 3–30 张 | <3 阻止导入；>30 自动拆为多个 ≤30 张子包，命名「原名 (1/2)」等 |
| 512×512 WebP | 合规 WebP 原样使用；静态 PNG/尺寸不符则等比居中补透明边并转 WebP，不裁切不拉伸 |
| 静态 ≤100 KB | WEBP_LOSSY 逐步降质（90→30，迭代上限 7）；仍超限则跳过该张，剩余不足 3 张则整包阻止 |
| 动态 ≤500 KB | 动态 WebP 原样透传；超限直接报错并指出第几张（Android 原生无法重编码动态 WebP） |
| 托盘 96×96 PNG ≤50 KB | 包内 PNG 或首张贴纸缩放/居中填充；超限时降到 64×64 |
| 名称/作者非空且 ≤128 | 空则兜底，超长截断 |

## 6. UI 设计

设计语言参考开源项目 Kazumi 的 Material You 信息架构与交互规范，但**没有复制其任何代码、图标、字体或美术素材**（Kazumi 为 GPL-3.0）。本项目仍是原生 Kotlin + View + Material Components for Android 1.12.0：

- Android 12+ 通过 `DynamicColors.applyToActivitiesIfAvailable` 跟随系统壁纸动态取色；三档深色模式在动态取色下同样生效；关闭动态取色或低版本回退到内置 MD3 种子色（设置页 6 档可选）。设置页底部的「关于」卡片列出作者 midi-HL、仓库地址、版本与许可摘要。
- 主页可折叠大标题、surface/surfaceContainer 色阶卡片列表、Extended FAB、MD3 空状态；预览页大托盘信息卡 + 圆角贴纸网格 + 底部固定主按钮。
- 入场 stagger、涟漪/状态层、CircularProgressIndicator、Snackbar、成功按钮切「已添加」；系统「移除动画」时自动降级为直接切换；可点元素 ≥48dp，图标按钮有 contentDescription。
- 原创自适应图标 + Android 13+ 单色主题图标；中英双语字符串。

## 7. 构建方法（GitHub Actions 为唯一编译途径）

按仓库主要求，本地不假设有 Android SDK。`.github/workflows/build.yml`：

- 触发：`push main`、tag `v*`、`workflow_dispatch`；`ubuntu-latest`、JDK 17 temurin、`android-actions/setup-android@v3` 接受 licenses 并安装 SDK 35 / build-tools；仓库内 Gradle Wrapper 8.11.1；Gradle 缓存。
- 步骤：`./gradlew lint test assembleDebug assembleRelease --no-daemon`。
- `versionName` 取 tag（去掉 `v`）/ `gradle.properties` 的 `versionName`；`versionCode` 用 `GITHUB_RUN_NUMBER` 单调递增，通过 `-PVERSION_CODE` / env 传入。
- 有 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` 时，workflow 解码 keystore 到 runner 临时目录并用 Gradle `signingConfigs.ciRelease` 签名 release；没配置时 release 为未签名 APK，同时一定产出可安装的 debug APK，并在 Job Summary 醒目提示「未配置签名，本次以 debug 包为准」，不因此失败。
- `splits.abi` 同时产出 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四个 debug/release APK 和 universal APK。App 无 native 库，四个 ABI 包内容相同但 versionCode 由 AGP 自动区分，满足 Play / 多架构分发要求；单包 <5 MB。
- 每个 APK 用 `aapt2 dump permissions` 输出权限到 Job Summary，并断言不含 `android.permission.INTERNET`；另用 `aapt2 dump xmltree` 断言最终 manifest 仍声明 `StickerContentProvider` 与 `${applicationId}.stickercontentprovider`。
- `actions/upload-artifact@v4` 上传全部 debug/release APK，保留 30 天；`v*` tag 用内置 `GITHUB_TOKEN` 建 GitHub Release 并附上全部 APK（`permissions: contents: write`）。
- Job Summary 写清版本号、产物文件名、是否签名、权限清单。

### 仓库主操作手册

**A. 生成签名 keystore**（把 `release.jks` 留在本地，绝不要提交）

Windows PowerShell：

```powershell
keytool -genkeypair -v -keystore "$env:USERPROFILE\release.jks" -alias wastickers -keyalg RSA -keysize 2048 -validity 10000
```

macOS / Linux：

```bash
keytool -genkeypair -v -keystore "$HOME/release.jks" -alias wastickers -keyalg RSA -keysize 2048 -validity 10000
```

按提示输入两次 keystore 口令、两次 key 口令（可与 keystore 相同）和证书信息。记住 alias 与两个口令。

**B. 转 base64 并填 Secrets**

Windows PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\release.jks")) | Set-Clipboard
```

macOS：

```bash
base64 -i "$HOME/release.jks" | pbcopy
```

Linux：

```bash
base64 -w 0 "$HOME/release.jks" | xclip -selection clipboard
```

在仓库 `Settings → Secrets and variables → Actions → New repository secret` 添加四个值：

| Secret | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | 剪贴板中的整段 base64 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `wastickers` |
| `KEY_PASSWORD` | key 口令 |

本项目构建/发 Release 只用 GitHub 内置 `GITHUB_TOKEN`，**不需要个人 PAT**。若曾为交接代码临时开过 fine-grained PAT（仅本仓库、Contents: Read and write、需要时 Actions: Read and write、短有效期），任务结束后请立即在 GitHub 设置里吊销。任何 token、keystore、口令都不得写入仓库、日志、Issue/PR。

**C. 下载 APK**

- `Actions` 页点最新一次成功运行 → 底部 `Artifacts` 下载 `apk-debug`、`apk-release` 或 `apk-all`；解压后按手机 CPU 选对应 ABI 包，普通手机通常选 `arm64-v8a`，不确定就用 `universal`。
- 打 `v1.0.0` 这类 tag 后，在 `Releases` 页直接下载附上的 APK。

**D. 手机安装注意事项**

1. 系统设置里允许「安装未知来源应用」（或允许当前浏览器/文件管理器安装 APK）。
2. 未配置签名时下载 **debug** 包；配置签名后优先下载 **release** 包。debug 与正式签名包签名不同，**不能互相覆盖升级**，切换前需先卸载旧包。
3. Android 11+ 若系统提示「应用未安装」，核对 ABI、Android 版本 ≥7.0、以及是否已有同 applicationId 但不同签名的旧包。
4. 真机导入步骤见第 9 节。

## 8. 已知限制

- Android 对未知扩展名的文件关联并不可靠，部分文件管理器不会把 `.wastickers` 交给 `ACTION_VIEW`；此时请用「分享」或 app 内 SAF 入口（三条入口均已实现）。
- WhatsApp 校验逐项比对且常常只返回笼统 `validation_error`；本 app 做常见关键字翻译，极端情况仍需对照官方 `StickerPackValidator` 逐列核对。
- 动态 WebP 在 Android 原生栈只能解出首帧做预览，不能重编码；超限动态包只能由 sticker-convert 等上游工具重新导出。
- 静态/动态混装包会以首张动态检测为准，跳过类型不一致的图片；跳过导致不足 3 张时整包阻止。
- 内置种子色选择器提供 6 档预设，关闭动态取色后需重新打开页面才能完全应用；动态取色开关同样在重新创建 Activity 后生效。
- `image_data_version` 初值为 `"1"`；本 app 重新导入同一 identifier 会覆盖文件但当前版本未自动递增该值。若 WhatsApp 已缓存同 identifier 的旧图，先在 WhatsApp 内移除该包再重新添加。
- 没有 `fixtures/` 真实 `.wastickers` 样本时，JVM 单元测试用程序化生成的 ZIP 夹具覆盖标题/作者/托盘探测/排序/损坏文件/identifier/emoji/动态标记。
- 真机导入 WhatsApp 属于闭环验收，CI 不安装 WhatsApp；步骤列在第 9 节，结果标注「待仓库主真机验收」。

## 9. 自测与验收说明

| 验收项 | 自动化 / 状态 |
| --- | --- |
| 全新仓库 Actions 一次通过且产物有可安装 APK | CI `lint test assembleDebug assembleRelease` + artifact 上传；以 Actions 运行记录为准 |
| APK 不含 INTERNET 及无关权限 | CI 对每个 APK 执行 `aapt2 dump permissions` 断言；manifest 不声明任何 `<uses-permission>` |
| 真机打开/分享/SAF → 预览 → 添加 → WhatsApp 贴纸列表可发送 | **待仓库主真机验收**：Android 11+ 手机安装 debug 或签名 release → 用 @tgtowabot 生成 3–30 张静态包 → 三条入口各试一次 → 确认页确认 → 聊天里发送 |
| 动态包导入后仍动态 | **待仓库主真机验收**：用 sticker-convert 导出动态 WebP 包，重复上一步并观察贴纸播放动画 |
| 2 张包阻止 / 31 张拆两包 / 无托盘生成 / 损坏文件不崩 / 未装 WhatsApp 提示 / 双目标可选 | 前 4 项由解析器与导入规则代码路径保证；后 2 项由目标检测与选择弹窗保证；**边界包真机项待仓库主验收** |
| 源 `.wastickers` 删除后仍可再次添加 | F2 落地到 `files/packs/`，未删除本地副本时与源文件无关；**真机项待仓库主验收**：导入后删源文件，回主页点「再次添加」 |
| U0–U6 动态取色/深浅色/动效/高刷 | **待仓库主真机验收**：换壁纸观察配色、切深浅三档、关闭动态取色、系统开发者选项开启「移除动画」对比、90/120Hz 设备观察列表与转场 |

## 10. 许可证与上游署名

- 本项目原创代码：MIT 基础上附加「必须署名作者 midi-HL 与本仓库、禁止付费分发」条款，见 `LICENSE`；可自由使用、复制、修改与免费再分发。修改版 app 的「关于」页必须保留作者与仓库地址。
- 上游 WhatsApp Stickers Android 示例：BSD，ContentProvider 与导入契约按其当前实现（commit `06144a1f`，2025-11-07）移植；全文见 `UPSTREAM-LICENSE-BSD.txt`，署名见 `NOTICE`。
- Kazumi 仅作为 Material You 视觉/交互设计语言参考，未包含其代码或素材；WhatsApp / Telegram 名称仅用于说明兼容性，图标与图形均为原创几何贴纸/纸张意象。
