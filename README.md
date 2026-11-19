# Sticker Studio

一个免费、开源、无广告的原生 Android 贴纸工作室：导入图片、GIF 和短视频，裁剪、擦除背景、添加文字与描边，管理自己的贴纸包；同时保留 `.wastickers` 文件导入与 Telegram Bot API 拉取转换。静态包可通过官方入口添加到 WhatsApp / WhatsApp Business 或 Telegram，动态 WebP 可添加到 WhatsApp。

> English summary: An offline-first Kotlin app that imports `.wastickers` files and can also fetch Telegram sticker sets through the official Bot API with a user-supplied token. Static, TGS and WebM video stickers are converted locally to WhatsApp-compatible WebP, stored in one in-app pack library, re-fetchable, exportable back to `.wastickers`, and added to WhatsApp / WhatsApp Business through the official sticker ContentProvider API. No ads, analytics, account system, or own server; the only network host is `api.telegram.org`.

## 1. 项目动机

Telegram 机器人（如 `@tgtowabot`）和 sticker-convert 等工具可以把贴纸包导出为 `.wastickers`，但把这些文件送进 WhatsApp 的最后一步往往依赖广告多、闭源的商业 app。WhatsApp 官方 `WhatsApp/stickers` 示例（BSD）已有可移植的 ContentProvider，却把贴纸包写死在 assets 里。v1 把「打开任意 `.wastickers` → 导入 WhatsApp」做成通用工具；v2 再把「贴 Telegram 链接 → 拉取/转换 → 添加」链路开源化，体验对标商业 app 但无账号、无服务器、无广告。

## 2. 功能与非目标

**v1（保持兼容）**

- F1 三条文件入口：`ACTION_VIEW` 文件关联（多种 `content`/`file`/mime/`pathPattern` 组合）、`ACTION_SEND` 系统分享、app 内 SAF 文件选择。
- F2 先复制到 app 内部缓存，再转换落地到 `files/packs/<identifier>/` 并写 `manifest.json`；源文件之后删除也不影响已落地包。
- F3 预览页：贴纸网格、包名、作者、张数；名称/作者可编辑。
- F4 一键「添加到 WhatsApp」；优先 `com.whatsapp`，其次 `com.whatsapp.w4b`；两个都装时弹出选择；返回 `validation_error` 时翻译成人话并保留原始信息。
- F5 主页卡片列表、再次添加、删除本地副本。
- F6 identifier 由清洗后的名称 + 内容短哈希派生；重复导入同 identifier；>30 张自动拆包。
- F7 从文件名提取最多 3 个 emoji，缺失时补 `😀`。

**v2 新增**

- T1 主页 Telegram 链接输入区，支持 `https://t.me/addstickers/<short_name>`、`http`、`telegram.me`、多余路径/查询参数、裸 `short_name`；另注册 `t.me/addstickers/*` 的 `ACTION_VIEW` 入口。
- T2 设置页 Bot Token 区：掩码输入、保存前 `getMe` 验证并回显 `@bot_username`、清除、错误提示；token 存 `EncryptedSharedPreferences` + Android Keystore，不写日志、manifest、导出包或仓库。
- T3 Bot API 拉取：`getStickerSet` → 每张贴纸 `getFile` → `api.telegram.org/file/...` 下载；显示「第 x / 共 y 张」与下载/转换阶段，可取消，取消后清理缓存不留半成品；单文件下载失败自动重试一次。
- T4 全格式本地转换：静态 WebP 走 512×512 / ≤100 KB 策略；TGS 解压 Lottie JSON 后用 Lottie-Android 逐帧渲染，再经 libwebp `WebPAnimEncoder`（JNI）编码为动态 WebP；WebM 用 FFmpeg-Kit `min` LGPL 构建解码带 alpha 的 VP9 帧，再走同一编码管线。起始 30 fps，超 500 KB 时降质量并依次降帧率 30→20→15→10 fps；仍超限则点名跳过，跳过后不足 3 张阻止整包。
- T5 统一存库：Telegram 包与文件包同列表，带「Telegram / 文件」角标；`manifest.json` 兼容旧字段并扩展 `source_type`、`telegram_short_name`、`fetched_at`、`group_id`、`part_index`、`part_count`；同短名重复拉取更新同 identifier 并递增 `image_data_version`；「重新拉取」保留用户改过的包名/作者；大包自动拆成多个 ≤30 张库条目。
- T6 任意库条目可「导出」为 `.wastickers`，经 SAF 写回 `title.txt`、`author.txt`、托盘 PNG 与转换后 WebP，可再被 v1 文件链路打开。

**v3 贴纸工作室**

- 系统相册选择器批量导入图片、GIF 和短视频，静态图片选完直接进入编辑器，批量保存后继续下一张；静态图片采样解码并校正 EXIF 方向。GIF/视频可指定起点和最多 3 秒片段，在本地转换为动态 WebP；静态与动态分别成包。
- 原生图片编辑器：矩形/圆角/圆形/自由裁剪，擦除和恢复笔，连通相似色背景擦除，多行文字与表情定位，平滑涂鸦，彩色描边，一指移动、双指缩放，旋转、镜像，亮度和饱和度，撤销/重做。精修时的双指放大仅改变查看视口；移动缩放工具改变实际保存图像。背景擦除不是 AI 主体分割。
- 制作草稿自动保留；包内复制、删除、排序、关联表情和封面选择。1–30 张可保存，WhatsApp 仍要求至少 3 张。编辑保存保留包 ID 并递增资源版本，清单原子写入。
- 静态包经 Telegram 官方 `CREATE_STICKER_PACK` 协议导入，无需 Bot Token。来自 Telegram 的动态包打开原包链接；自制动态 WebP 暂不能直接导入 Telegram，不会静态化冒充动态导出。
- 保持免费、无广告和 Material You；设置支持跟随系统、简体中文、English。视觉重心平衡的对称笑脸贴纸图标，覆盖传统方形、自适应启动器遮罩与主题单色版本；品牌名称在所有语言环境中统一为 Sticker Studio。

**范围之外**：云端 AI 抠图/生成、社交内容库、账号/关注、云同步、Telegram 账号登录/MTProto/TDLib、广告 SDK、custom emoji 包、频道图片下载。应用网络请求只访问 `api.telegram.org`，跨应用打开 Telegram 链接由目标应用处理。

竞品流程参考：[Sticker.ly 产品说明](https://play.google.com/store/apps/details?id=com.snowcorp.stickerly.android)、[Sticker maker 产品说明](https://play.google.com/store/apps/details?id=com.marsvard.stickermakerforwhatsapp)。Telegram 契约来源：[官方导入文档](https://core.telegram.org/import-stickers)与[官方 Android 示例](https://github.com/DrKLO/TelegramStickersImport)。未复制竞品代码、美术或商业素材。

## 3. 使用步骤

### 链路 C：制作自己的贴纸

1. 贴纸库点「制作贴纸」，填写包名和作者。统一「添加贴纸」入口可选择图片/视频或纯文字；纯文字直接打开文字输入。
2. 图片选完自动进入编辑。画布可直接拖动和双指缩放；工具以三列多行显示，选择后在画布操作并点「应用」，最后「保存贴纸」。未结束的工具先应用或取消。
3. 返回工作室可设置封面、排序、复制、删除和表情。返回自动保留草稿，「丢弃草稿」需要确认。
4. 「保存贴纸包」后，在预览页添加到 WhatsApp、Telegram 或导出 `.wastickers`；「编辑 / 管理贴纸」可继续修改。

### 链路 A：文件包

1. 用 `@tgtowabot` 或 `sticker-convert` 得到 `.wastickers`。
2. 在文件管理器点击并选择本 app；或「分享」到本 app；或打开本 app 点「打开 .wastickers」。
3. 预览、改名，点「添加到 WhatsApp」，在 WhatsApp 确认页确认。

### 链路 B：Telegram 直拉

1. 在 Telegram 找 `@BotFather`，发送 `/newbot`，按提示给 bot 起名与用户名，复制获得的 Bot Token。
2. 本 app → 设置 →「Telegram Bot Token」粘贴 →「保存并测试连接」；成功后显示 `@xxx_bot`。
3. 主页粘贴 `https://t.me/addstickers/myadestes_1_nacho` 或 `myadestes_1_nacho` →「拉取并转换」。
4. 进度完成后包出现在主页，带 Telegram 角标；可预览、导出、重新拉取或再次添加到 WhatsApp。

> 模拟器/真机没装 WhatsApp 时，app 会明确提示「未检测到 WhatsApp」；导入确认页属 WhatsApp 闭环，需仓库主真机验收。

## 4. 隐私说明

- 静态权限只有 `android.permission.INTERNET`，CI 断言最终权限集合**恰好只有**这一项；不申请存储权限，通知权限也不写入 manifest。
- 所有网络请求使用 `HttpURLConnection`，域名固定为 `api.telegram.org`；没有统计、崩溃上报、广告或自有后端。
- Bot Token 只出现在发往 Bot API 的 URL 路径中，这是 Telegram 官方用法；本机通过 Android Keystore 加密存储。token 不进入 `manifest.json`、日志、崩溃报告、导出 `.wastickers` 或源码仓库。
- 贴纸文件、转换中间帧、包清单都只存 app 内部存储/缓存；取消或完成后清理缓存。

## 5. `.wastickers` 格式说明

本质是 ZIP，常见内容：`title.txt`（名称第一行）、`author.txt`（作者）、一张 PNG 托盘图、多张 `.webp`（也可能 `.png`）贴纸。字段缺失容错；托盘图按像素面积最小 PNG 探测，其余 PNG 当普通贴纸；无托盘图时用首张贴纸生成；贴纸顺序按 ZIP 条目顺序；非 ZIP/损坏文件给出明确错误而不崩溃。导出的 `.wastickers` 同样遵循该布局。

## 6. WhatsApp 硬性限制与处理

| 规则 | 处理 |
| --- | --- |
| WhatsApp 每包 3–30 张 | 本地库和归档允许 1–30 张；添加到 WhatsApp 时不足 3 张阻止；>30 拆分并调整尾包，避免出现仅 1–2 张的尾包 |
| 512×512 WebP | 合规 WebP 原样；静态 PNG/尺寸不符则等比居中补透明边并转 WebP，不裁切不拉伸 |
| 静态 ≤100 KB | WEBP_LOSSY 逐步降质（90→30），仍超限跳过并点名 |
| 动态 ≤500 KB | `.wastickers` 动态 WebP 原样；Telegram TGS/WebM 经原生管线生成并降质/降帧 |
| 托盘 96×96 PNG ≤50 KB | 包内 PNG/Telegram thumbnail/首张贴纸缩放居中生成 |
| 名称/作者非空且 ≤128 | 兜底或截断 |

## 7. UI 设计

v3.1 重置首页、预览、设置、工作室和编辑器：原生 Kotlin + View + Material Components 1.12.0，实色大圆角分区、Outlined 输入、Material Slider、底部「贴纸库 / 导入」导航。Android 12+ 动态取色、6 档内置种子色和深浅主题保留；不使用玻璃模糊或持续动画。贴纸库与预览使用独立 RecyclerView，缩略图双线程解码、内存缓存限制为堆的 1/16 且上限 8 MiB；编辑器横屏双栏，描边/调色在后台合并处理，撤销/重做合计最多 12 张位图。视觉参考原生 Material、Telegram 的分区和圆角比例，未复制其代码或素材；性能以实际设备测量为准。

## 8. 构建方法（GitHub Actions 产出正式签名安装包）

`.github/workflows/build.yml`：

- 触发 `push main`、tag `v*`、`workflow_dispatch`；`ubuntu-latest`、JDK 17 temurin、`android-actions/setup-android@v3`、Android SDK 35 / build-tools 35 / NDK `27.0.12077973` / CMake `3.22.1`；仓库内 Gradle Wrapper 8.11；Gradle 缓存。
- 先运行 `scripts/verify-dependencies.py` 检查 vendored libwebp 必需源码与 BSD 许可文本，并输出归一化源码树 SHA256 供追溯（不下载上游归档、不做固定摘要比对），再 `./gradlew lint test assembleDebug assembleRelease assembleDebugAndroidTest`。额外构建独立 `studiotestv3` 旁装包与对应设备测试 APK；签名材料配置时 debug 和测试 APK 使用固定 CI 签名，支持后续无损更新。
- `versionName` 取 tag（去掉 `v`）/ `gradle.properties`；`versionCode` 取 `GITHUB_RUN_NUMBER`。
- 有 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` 时用 PKCS12 keystore 签名 release（v1/v2/v3 signing）；无签名材料时 release 未签名且 Job Summary 醒目提示以 debug 包为准，不因此失败。
- ABI splits 产出 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 与 `universal` 各 debug/release APK；Job Summary 列版本号、全部文件名、每个 APK 体积、签名状态与权限集合。
- 每个 APK 用 `aapt2 dump permissions` 断言权限集合**恰好只有** `android.permission.INTERNET`；另用 `aapt2 dump xmltree` 断言 `StickerContentProvider` 与 authority 在 final manifest 中仍存在。
- `upload-artifact@v4` 上传 debug/release/all APK（30 天）；`v*` tag 用内置 `GITHUB_TOKEN` 创建 GitHub Release 并附 APK（`permissions: contents: write`）。

### 仓库主操作手册

**A. 生成签名 keystore**（不入库）

Windows PowerShell：

```powershell
keytool -genkeypair -v -keystore "$env:USERPROFILE\release.jks" -alias wastickers -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
```

macOS / Linux：

```bash
keytool -genkeypair -v -keystore "$HOME/release.jks" -alias wastickers -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
```

**B. 转 base64 并填 Secrets**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\release.jks")) | Set-Clipboard
```

```bash
base64 -i "$HOME/release.jks" | pbcopy        # macOS
base64 -w 0 "$HOME/release.jks" | xclip -selection clipboard  # Linux
```

在 `Settings → Secrets and variables → Actions` 添加 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。构建/发 Release 只用 GitHub 内置 `GITHUB_TOKEN`，**不需要个人 PAT**；如曾为交接代码临时开过 fine-grained PAT，任务结束后立即吊销。任何 token、keystore、口令都不得写入仓库、日志、Issue/PR。

**C. 下载 APK**：`Actions` 最新成功运行 → Artifacts → `apk-debug` / `apk-release` / `apk-all`；或 `Releases` 页直接下载。普通手机选 `arm64-v8a`，不确定选 `universal`，MuMu x86_64 模拟器选 `x86_64` 或 `universal`。

**D. 手机安装**：允许安装未知来源；未配置签名时安装 debug 包；debug 与签名 release 不能互相覆盖升级，切换前先卸载旧包；Android 版本需 ≥7.0。

## 9. 原生库清单与许可证

本项目原创部分采用 **GPL-3.0-only**。选择理由：按 v2.1 第 8 节决策树核对 sticker-convert，GitHub API 与 `pyproject.toml` 显示 GPLv2，但作者维护的 `sticker-convert.appdata.xml` 明确写 `<project_license>GPL-2.0+</project_license>`（等价 `GPL-2.0-or-later`），因此落到 GPL-3.0；GPL-3.0 与 Apache-2.0 依赖及 sticker-convert 的 or-later 通道兼容。v1 的 MIT 与「禁止付费分发」等附加条款已清除，完整 GPLv3 文本见 `LICENSE`，全部复用/第三方登记见 `NOTICE`。

| 组件 | 版本 | 许可证 | 用途 | 校验值 / 来源 |
| --- | --- | --- | --- | --- |
| libwebp | 1.4.0 | BSD-3-Clause | WebPAnimEncoder / libwebpmux，JNI 动态 WebP | 上游 tar.gz SHA256 `61f873ec69e3be1b99535634340d5bde750b2e4447caa1db9f61be3fd49ab1e5`；归一化 vendored tree `668133ea1462b4b87d9652f85513408c82a6dfc35ba1d91ac7a1b37e8916e219` |
| Lottie-Android | 6.7.1 | Apache-2.0 | TGS / Lottie 逐帧渲染 | AAR SHA256 `4423cb34732018f0ef7262fdf6c4ae0b5aaea0ec05f55c3eef7e90cb100f1da9` |
| FFmpeg-Kit min | 6.0-2.LTS | LGPL-3.0 | WebM VP9 alpha 解码 | AAR SHA256 `a05fe92c90f02f0369d455d212a39117838f25840af4319887fb877307cd490f` |
| APNG4Android awebpencoder | 3.0.5 | Apache-2.0 | Java 动态 WebP mux 回退 | AAR SHA256 `4ea6f58d90483106a98d4eac6b73c36f8fe96d01ddaf40a308fe4d274ae0e725` |
| AndroidX Security Crypto | 1.1.0 | Apache-2.0 | EncryptedSharedPreferences | AAR SHA256 `2831ec1b455e8cc2ff861e8384d7893b42b3307c00f3be843feb02af8ac04b56` |
| rlottie Samsung | v0.2 | MIT / mixed | 仅评估，未链接 | 源码 tar.gz SHA256 `030ccbc270f144b4f3519fb3b86e20dd79fb48d5d55e57f950f12bab9b65216a` |

sticker-convert（GPL-2.0-or-later）转换流程与 TGS 夹具概念被登记在 `NOTICE`；没有把其 Python 源码复制进 Android 工程。WhatsApp 官方示例 BSD 全文见 `UPSTREAM-LICENSE-BSD.txt`。Kazumi 仅作设计语言参考。

## 10. 已知限制

- WhatsApp 校验逐项比对且常只返回笼统 `validation_error`；本 app 做常见关键字翻译，极端情况仍需对照官方 validator。
- Lottie-Android 渲染 TGS 的遮罩/修剪路径与 Telegram rlottie 可能有细微差异，不宣称像素级一致；FFmpeg `min` 构建若遇到特殊 WebM 编码可能解码失败，会点名跳过该张。
- 动态 WebP 目标 ≤500 KB；复杂 TGS/视频在降质与 10 fps 后仍可能超限，此时按文档跳过并给出第几张。
- 原生库使 release APK 体积明显高于 v1 的 5 MB 目标；v2 改为 ABI splits + Job Summary 如实报告每个 APK 体积。
- `image_data_version` 在 Telegram 重新拉取时递增；v1 文件包重复导入仍按原逻辑覆盖，若 WhatsApp 已缓存旧图，先在 WhatsApp 内移除该包再重新添加。
- 后台拉取使用前台协程并依赖进程存活；v2 未引入 WorkManager，因为 WorkManager 会向 manifest 注入 WAKE_LOCK / ACCESS_NETWORK_STATE / RECEIVE_BOOT_COMPLETED / FOREGROUND_SERVICE，与「权限恰好只有 INTERNET」断言冲突。因此 app 被系统彻底杀死时，当前拉取会中断；下次打开重新拉取即可。
- Android 对未知扩展名文件关联不可靠，部分文件管理器不会把 `.wastickers` 交给 `ACTION_VIEW`；请用「分享」或 app 内 SAF 入口。
- 没有 `fixtures/` 真实样本时，JVM 单测用程序化 ZIP 与录制 JSON 夹具覆盖解析、链接、元数据映射；TGS/WebM/WhatsApp 导入确认页标注「待仓库主真机验收」。

## 11. 自测与验收说明

| 验收项 | 状态 |
| --- | --- |
| 现有仓库增量升级、Actions 成功、产物有可安装 APK | 以最新成功 Actions 运行记录为准 |
| 权限集合恰好只有 INTERNET | CI 对每个 APK 用 `aapt2` 精确断言 |
| v1 文件包打开/预览/再次添加、旧 manifest 仍可列出 | 代码兼容 `opt*` 字段；旧包回归单测 |
| v2 静态包直拉：`getMe` 验证、链接拉取、64 张自动拆包、预览顺序/emoji | MuMu 已验证：`@test_agent_cchhbot` 验证通过；`myadestes_1_nacho` 拉取 64 张、自动拆为 30+30+4 三包，标题/作者/emoji 与 API 元数据一致；WhatsApp 确认页待真机 |
| v2 TGS/WebM 转动态、≤500 KB、导入后仍动态 | TGS/WebM 管线、libwebp JNI 与 FFmpeg-LGPL 已接入并随 CI 四 ABI 编译；公开动画包与 WhatsApp 动态确认页待真机 |
| 存库更新、不重复、重新拉取、导出 `.wastickers` 再导入 | MuMu 已验证：重复拉取仍为 3 个 identifier，`image_data_version` 递增到 4；导出等价 ZIP 经 `ACTION_VIEW` 重新导入为 `source_type=file` 的 30 张包 |
| 错误与安全：错 token、错包名、取消清理、token 不出现在日志/manifest/导出 | MuMu/静态检查：错链接明确提示、未装 WhatsApp 明确提示；设备 `files/cache/导出 ZIP` 全量 grep 无 token 明文；取消按钮与清理代码已实现，早取消窗口待真机验收 |
| 大包进度可见可取消、拆分逐组可导入 | 64 张静态包在 MuMu 显示「第 1/64 张」到完成 Snackbar、三包卡片可见；64 张静态 WebP 最大 50.2 KB、无 >100 KB；WhatsApp 导入待真机 |
| U0–U7 动态取色/深浅色/动效/高刷 | 真机项待仓库主验收；截图中的标题重叠与 FAB 遮挡已在布局层修复 |

### v3.0.0 制作工作室验收（2026-11-17）

- 最终应用提交 `497a443`，GitHub Actions [44063928076](https://github.com/midi-HL/wastickers-importer/actions/runs/44063928076) 成功，版本 `3.0.0 / 32`。签名 release 与独立测试包均来自 Actions；发布版已无损覆盖安装到模拟器，旧包保留。
- JVM 单元测试 debug/release 各 9 项通过；lint 无阻断错误，保留 64 条警告。最终 CI 测试包在 API35 模拟器运行 **34 项设备测试全部通过**：12 图像/画布、17 存库/媒体/归档、5 工作室 UI/生命周期。
- 设备用例包含真实 GIF/MP4 转码、1/2/3 张归档回读与 emoji/顺序/封面保留、31/32/61 张拆包、EXIF、大图采样、草稿损坏保留、双会话隔离及重建恢复。手工复查图片/GIF 系统选择、去背景、保存、网格、新图标、中英文/深浅色及系统栏。
- 模拟器未安装 WhatsApp/Telegram，已验证缺少目标应用时明确提示；真实目标应用中的接收确认、实际发送及播放仍需手机验收。不宣称覆盖所有设备或零缺陷。
- 手机包位于本地 `artifacts/studio/phone-test-v3.0.0/`，内有 arm64-v8a、universal 签名 APK 与验收说明。新宣传片等待手机验收后再制作。

### v3.1.0 界面与编辑器验收（2026-11-18）

- 本轮重做首页、工作室、编辑器、预览与设置。编译前完成全部应用变更的代码/UI 主审和独立只读复核；修正深色自绘图标着色、导入成功后返回贴纸库、最近草稿会话身份及文字输入自动聚焦。
- 本地 `3.1.0 / 33` 独立测试包通过 lint（0 错误、63 条警告）、debug/release JVM 单测各 9 项，以及 **46 项设备回归**（触控区域修复后40.030秒）：20 图像/画布/图标、17 媒体/存库/归档、9 工作室/导航/生命周期。新增首页空状态命中区域测试，确保“导入”与“制作贴纸”按钮目标矩形不相交，且导入触控高度不小于48dp。此前图标/导入导航基线45项耗时70.473秒，分别留档。
- 回归覆盖真实多指 ID 与主指抬起交接、双指缩放后的 PNG 像素、笔触回滚、平滑涂鸦、圆角裁剪、批量连续编辑、草稿会话恢复、文字两入口聚焦、图标主题/禁用态，以及 31 张归档拆为 28+3 后贴纸库可见。
- 原 MuMu API35 保持物理 720×1280、density 320，原主应用和数据保留。手工复查深色工具/添加面板图标、系统相册选择静态图片后自动编辑、文字保存返回工作室与横屏双栏；最新云端产物的结果另以对应 Actions 运行及设备日志为准。
- 模拟器仅有的输入法报告窗口高度为 0，可见软键盘遮挡需手机验收；1 核、1.5 GiB、15 FPS 限制下的结果不代表真机高刷性能。WhatsApp/Telegram 的接收确认与发送播放仍需安装目标应用的手机验证。

### v3.1.1 贴纸工坊品牌与取色修复（2026-11-19）

- 应用显示名改为「贴纸工坊 / Sticker Studio」。包名、Provider authority 与升级签名不变。桌面图标重新绘制为居中笑脸贴纸和右下卷角；传统 PNG、adaptive 以及真正透明镂空的 monochrome 版本同源生成，脚本为 `scripts/generate-launcher-icons.py`。
- 修复设置切换取色后返回既有首页仍保留旧配色的问题。在 AppCompat 完成深浅模式设置后、界面膨胀前应用选定颜色；首页恢复时对照配色及系统色快照，保留页签和输入并避开进行中的导入及结果提示。底栏与制作入口显式使用当前主题角色。无壁纸配色的设备使用内置方案。
- 重新绘制贴纸、导入、齿轮、返回等入口图标。空贴纸库使用同一滚动容器中的制作/导入双入口，有贴纸包才显示悬浮制作按钮，避免英文标签变长造成触控重叠。
- 本轮完整差异通过独立代码/UI静态审查；图标脚本的干净检出目录问题已修复并复核关闭。本地 lint 0 错误 / 47 警告、debug/release JVM 各 9 项通过。独立 `3.1.1 / 35` 测试包 **50 项设备回归全部通过**（46.982 秒），包括设置返回后换色、导航/输入保留、系统配色匹配、新名称和单色图标透明孔断言。首轮测试返回值断言错误与重跑结果分别留档。
- 最终签名安装包及对应云端设备结果以本轮 Actions 产物和交付验收说明为准；模拟器仍保持 720×1280 / density320，真实软键盘和目标聊天应用闭环仍需手机验收。

### v3.1.2 国际品牌与启动器图标适配（2026-11-19）

- 应用品牌名在默认资源及所有语言中统一为 **Sticker Studio**，以单一 `translatable="false"` 资源提供；中文和英文的界面语言切换保留。
- 移除不对称卷角，改为左右对称的笑脸贴纸。生成器同时检查透明度重心和相对背景亮度重心，避免只看外接矩形而忽略视觉平衡；所有前景关键像素位于自适应安全区。
- 取消 `android:roundIcon` 和独立圆形资源。Android 8.0+ 使用全出血背景、透明前景及主题单色层，由启动器选择外轮廓。Android 7.x 使用完整方形 PNG 回退，不预裁圆角，也不将整个图标固定成圆形。
- `python scripts/generate-launcher-icons.py --preview-dir artifacts/studio/v3.1.2-validation` 可重建五档传统 PNG，输出方形、矩形、圆角方形、超椭圆、鹅卵石与圆形兼容检查的彩色/单色预览及重心校验 JSON。这些是代表性遮罩，不冒称厂商私有遮罩，也不代表所有手机型号均已实测。
- 名称与图标像素回归更新，并新增 Android 原生渲染的六遮罩无裁切/重心检查。本轮全部21文件已完成编译前完整代码/UI独立审查，发现的旧版预裁圆角和ARGB顺序问题已修复复核；本地 lint 0错误/52警告、debug/release JVM各9项通过，独立 `3.1.2 / 36` 包 **51/51设备回归通过**（49.064秒）。最终云端签名包与同源设备结果以本轮交付说明为准。

## 12. 许可证与上游署名

- 本项目原创代码：GPL-3.0-only，见 `LICENSE`。分发整体时遵守 GPLv3；不得叠加 GPL 之外的限制。
- 上游与第三方：见 `NOTICE`、`UPSTREAM-LICENSE-BSD.txt` 及各依赖许可。
- 作者：midi-HL；仓库：https://github.com/midi-HL/wastickers-importer。设置页「关于」卡片展示作者、仓库、版本与许可摘要。
