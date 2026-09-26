# RetroPro · 羽毛球日记

> **记分只是开始，复盘才是目的。**
>
> 一个离线优先的羽毛球「逐球复盘」记录 App —— 把每一分的得失记下来，而不只是记个比分。
>
> *An offline-first badminton journal app built with Kotlin & Jetpack Compose, focused on
> point-by-point rally review with fully on-device speech recognition.*

---

## 为什么做这个

打完一场球，通常只记得「赢了还是输了」。但真正决定胜负的往往是几个具体的球：
接发球冒高、被动时的回球线路、关键时刻的连续失误。

现有记分 App 的复盘点只到「一局」；而**逐球**记录才能回答「我到底丢在哪儿」。
RetroPro 就是围绕这一点设计的：以「球」为最小单位记录，用标签 + 语音/文字心得
把每一分的原因固化下来，再由统计把规律翻出来。

全部数据留在本机、不上传、不需要账号；唯一联网处是「版本与更新」页里由你主动触发的更新检查。

---

## 核心特性

| 模块 | 说明 |
|---|---|
| **逐球复盘** | 以「球」为最小记录单位：得分方、失分原因标签、文字或语音心得。可对任意一球回看与补录 |
| **计分板** | 横屏计分界面，手动/自动记分；比分是唯一事实来源，局分与场分由逐球记录实时汇总 |
| **语音记录** | 本地语音识别（sherpa-onnx + SenseVoice-Small int8），说出「我网前搓球下网」即自动解析成结构化记录；含 VAD 静音切分，**不上传任何音频**。模型按需下载（约 229 MB），不占安装包 |
| **装备管理** | 球拍 / 球线 / 穿线记录（含磅数与穿线师）/ 球 / 球鞋 / 球衣 / 手胶分类追踪，支持品牌标签与成本统计 |
| **数据统计** | 胜率、得失分趋势、失分原因分布、机会球、按对手维度的交手统计与对比 |
| **外观与材质** | MIUIX / HyperOS 风格；液态玻璃材质（模糊 + 折射 + 镜面高光），可在设置页一键关闭或切换档位 |
| **版本与更新** | 手动检查 GitHub Releases 的版本号；安装包走 GitHub / AtomGit 双渠道，语音模型走应用内下载（镜像站 / 魔搭双源） |
| **深浅色** | 白底 / 黑底双主题，玻璃的暗化与折射参数按主题分别标定 |

### 界面说明

界面遵循 MIUIX（HyperOS）设计语言：超椭圆圆角、语义化配色、液态玻璃面板、
按下时的横向拉伸与箭头位移微反馈。截图待补（当前为个人自用开发阶段）。

---

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Kotlin | 2.4.x | 由 AGP 内置提供 |
| Jetpack Compose | 1.12.x | 声明式 UI |
| AGP / Gradle | 9.4.x / 9.6 | |
| minSdk / targetSdk | 31（Android 12）/ 36 | |
| Room | 2.8.x | 本地持久化，导出 schema 便于写迁移 |
| MIUIX | 0.9.4 | HyperOS 风格组件库（Apache-2.0） |
| Haze | 2.0 | 毛玻璃 / 折射材质（Apache-2.0） |
| Backdrop | 2.0 | 液态玻璃折射，基于 AGSL RuntimeShader（Apache-2.0） |
| sherpa-onnx | 1.13.8 | 端侧语音识别运行时（Apache-2.0） |
| SenseVoice-Small int8 | 2024-07-17 | 中英日韩粤语音识别模型 |

### 材质降级链（设计要点）

不同设备的 GPU 能力差异很大，玻璃材质按能力**逐级降级**，永不白屏：

```
Backdrop 折射（AGSL RuntimeShader，API 33+）
    ↓ 不支持折射 / 熔断
Haze 玻璃（模糊 + 折射，API 33+）
    ↓ API 31/32（Android 12 无 RuntimeShader）
Haze 纯模糊（RenderEffect，API 31+）
    ↓ 渲染异常熔断
纯色卡片（App 依然完整可用）
```

渲染层的异常会按组件粒度熔断（`GlassGuard`），玻璃总开关可随时关闭。

---

## 数据模型

```
sessions（场次）
  └── matches（比赛）
        └── games（局：比分由 rallies 汇总）
              └── rallies（球：得分方 + 失分原因标签 + 心得）★ 最小记录单位

equipment：rackets / string_jobs（穿线）/ shuttles / shuttle_usages / gear_items / gear_tags
meta：opponents / reminders / dashboard_cards
```

两条硬约束：

1. **比分是唯一事实来源** —— `games.myScore / oppScore` 由 `rallies` 汇总，
   局分与场分都是派生镜像；读取方一律比较比分，不读派生的结果字段。
2. **跨表不变式收进事务** —— 任何改动 `rallies` 的写操作，都在同一个事务里跑完
   `rallies → games → matches` 的一致化，避免散落在 ViewModel 里漏更新。

数据库**禁用破坏性迁移**：schema 变更一律写显式 Migration，用户数据不丢。

---

## 构建

### 前置要求

- JDK 21（推荐 Android Studio 自带的 JBR）
- Android SDK，含 **API 37** 平台与 build-tools
- Gradle 9.6+（或自行生成 wrapper）

### 步骤

```bash
git clone git@github.com:zeyueryu/RetroPro.git
cd RetroPro

# 1) 配置 SDK 路径
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

# 2) 拉取未入库的依赖：sherpa-onnx AAR（约 50 MB）
bash tools/fetch_deps.sh            # 中国大陆可加 MIRROR=ghfast

# 3) 构建（产物约 57 MB）
./gradlew :app:assembleDebug        # 或 gradle :app:assembleDebug
```

> **中国大陆网络提示**：Gradle 分发包默认走 `services.gradle.org`，
> 若下载卡住，把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl`
> 换成腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip` 即可。

**构建只依赖 sherpa-onnx 的预编译 AAR（约 50 MB）**，它不入库是因为二进制放进 git 会让
仓库无限膨胀。语音模型（约 229 MB）**不再参与构建** —— 它已从 APK 剥离，由 App 运行时按需下载。

### 语音模型是「插件」

模型（约 229 MB）不打进 APK：应用内「我的 → 版本与更新 → 语音模型」按需下载到应用私有目录
（`filesDir/asr/`，无需存储权限）。这样安装包只有 ~57 MB，**升级只装小包，模型只需下一次**。

- 下载走**两个源自动故障转移**：HuggingFace 国内镜像站 → 魔搭 ModelScope；VAD 模型走 GitHub 官方
- 完整性以**精确字节数**校验（上游未提供校验和），不匹配自动换源重下
- 未安装模型时语音输入会自动退到系统识别器，功能不中断

> 想顺手裁掉用不上的 CPU 架构？默认只打包 `arm64-v8a`（省 ~91 MB）。
> 需要 x86 模拟器或 32 位设备时，在 `app/build.gradle.kts` 的 `ndk { abiFilters }` 里加回 `armeabi-v7a` / `x86_64`。


### 发布签名（可选）

仓库内**不含**任何密钥。需要出正式签名包时，在 `local.properties` 里补：

```properties
REPRO_STORE_FILE=/path/to/your.jks
REPRO_STORE_PASSWORD=****
REPRO_KEY_ALIAS=****
REPRO_KEY_PASSWORD=****
```

四项缺任意一项，签名配置整体跳过 —— 此时仍可正常构建未签名包。
配置齐全后 `gradle :app:assembleRelease` 会额外产出 v1+v2+v3 三签名齐全的
`RetroPro-<版本>-signed.apk`。

### 自动构建与发版（GitHub Actions）

推送 `v*` 标签即触发 [`.github/workflows/release.yml`](.github/workflows/release.yml)：
拉取依赖 → 构建 release → 校验签名 → 把 APK 挂到同名 Release。

若希望 CI 产出**已签名**包，先在 `Settings → Secrets and variables → Actions` 添加四个 Secret：

| Secret | 内容 | 生成方式 |
|---|---|---|
| `REPRO_KEYSTORE_BASE64` | 密钥库文件的 base64 | Linux：`base64 -w0 your.jks`；macOS：`base64 -i your.jks` |
| `REPRO_STORE_PASSWORD` | 密钥库密码 | — |
| `REPRO_KEY_ALIAS` | 密钥别名 | — |
| `REPRO_KEY_PASSWORD` | 密钥密码 | — |

四个缺任意一个时，流水线仍会正常构建并上传**未签名**包（只是不能用于覆盖升级）。
单次运行约 4 分钟（依赖只有 ~50 MB 的 AAR，模型不参与构建）。

在 GitHub Release 之外，若再配一个 `ATOMGIT_TOKEN`，同一次构建还会把 APK 同步到
AtomGit 的 Release（未配置则自动跳过），见下节。

### 同步到 AtomGit

AtomGit 的仓库实际托管在 **GitCode**（`atomgit.com` 只是入口域名，SSH 地址是
`git@gitcode.com:<owner>/<repo>.git`）。本项目把两边都配成一次推送：

```bash
# 一次性配置：给 all 这个远端加两条 push 地址（origin 仍是 GitHub，行为不变）
git remote add all git@github.com:<owner>/<repo>.git
git remote set-url --add --push all git@github.com:<owner>/<repo>.git
git remote set-url --add --push all git@gitcode.com:<owner>/<repo>.git

# 之后每天只需要这一条：代码 + 标签同时推到两个平台
git sync            # = git push all main && git push all --tags
```

**发版产物**由 CI 自动同步，需要在仓库 Secret 里加一个 `ATOMGIT_TOKEN`
（[gitcode.com/setting/token-classic](https://gitcode.com/setting/token-classic) 生成）。
CI 用它调 GitCode 的 v5 接口：建 Release → 取预签名上传地址 → PUT 上传 → 回读附件直链。

> SSH 密钥只能推代码，**发版必须用令牌** —— 两者不能互相替代。

发版命令：

```bash
git tag v1.2.0-m1
git sync            # 标签同时推到 GitHub 与 AtomGit；GitHub 侧随即触发 CI
```

---

## 项目结构

```
app/src/main/java/com/retropro/
├── data/          数据层：Room 实体 / DAO / 仓储（跨表一致性在此保证）
├── feature/       功能页：score 计分板 · review 逐球复盘 · record 记录
│                  equipment 装备 · stats 统计 · voice 语音 · profile 设置
├── glass/         液态玻璃渲染层（档位、能力检测、熔断、统一出口 GlassPanel）
├── uikit/         自建组件与主题（AppColors / AppTypography / 上下文菜单 / 导航）
└── util/          性能监控等工具

tools/             开发脚本：图标生成、语音解析镜像、依赖拉取
app/schemas/       Room 导出的 schema（版本管理用，便于写迁移）
```

---

## 隐私

- **离线优先**：核心数据与功能全部在本机。联网只有两处、都由你主动触发：「检查更新」访问 GitHub Releases 比对版本；「下载语音模型」从模型托管站拉取模型文件。无账号、无登录、无云端。
- **语音不出本机**：录音仅在本机内存中做识别，不落盘、不上传；识别模型下载到应用私有目录，只在设备上运行。
- **数据自持**：数据库为本地 SQLite 文件，可随时自行导出备份。
- 需要的权限只有两个：`RECORD_AUDIO`（语音记录，运行时申请，可拒绝）与 `INTERNET`（版本检查，普通权限、安装即授予）。

---

## 已知限制

- **首次使用语音需要下载约 229 MB 的模型**（「我的 → 版本与更新」里有入口）；之后升级只装 ~57 MB 的小包。
- APK 只打包 `arm64-v8a`：x86 模拟器与 32 位设备需自行改 `abiFilters` 后从源码构建。
- 玻璃折射需要 Android 13+；Android 12 上自动降级为纯模糊（视觉略有差异）。
- 低内存设备会跳过折射档，直接使用模糊或纯色。
- 目前仅支持羽毛球单一场地/个人使用场景，不支持约球、社交、排行榜等功能（有意为之）。

---

## 开源协议

本项目基于 **Apache License 2.0** 发布，详见 [LICENSE](LICENSE)。
第三方组件与商标声明见 [NOTICE](NOTICE)。

简单说：你可以自由使用、修改、分发（包括商用），但需保留版权与许可声明，
且不得使用本项目作者的名义做背书。

---

## 致谢

站立在 Kotlin、Jetpack Compose、sherpa-onnx、SenseVoice、Silero VAD、MIUIX、Haze、Backdrop
以及 AndroidX 的肩膀上 —— 没有这些开源项目，这个 App 不会存在。
