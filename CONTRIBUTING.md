# 贡献指南

感谢你愿意为 PinkVoice 出力。这个 App 服务的是跨性别女性用户，代码里的每一处改动
都会真实影响到某个人的练声过程，所以我们在工程规范之外，也希望有几句额外的话。

---

## 环境

| 依赖 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK | compileSdk 36 / buildTools 36.1.0 |
| Gradle | 8.13（用仓库自带的 wrapper） |
| Kotlin | 2.1.20 |

`local.properties` 已被 gitignore（里面是本机的 SDK 路径）。用 Android Studio 打开会自动生成；
命令行环境请自己写：

```properties
sdk.dir=/path/to/android/sdk
```

或者设置环境变量 `ANDROID_HOME`。

## 构建与测试

```bash
./gradlew :app:assembleDebug          # 出 APK
./gradlew :app:testDebugUnitTest      # 跑单测（不需要设备）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

单测覆盖 YIN 基频检测、FIR 抽取器、迟滞跟踪器——**这三个是数值算法，改动它们请务必跑测试**。
目前 21 个用例，其中几条是专门守住真机踩过的坑的（八度误判、梳状滤波毛刺、起音瞬态）。

CI 会在每个 push 和 PR 上自动跑一遍单测和编译，绿了再合。

## 提交 PR

1. 从 `main` 切分支，命名随意但能看懂（如 `fix/octave-error`）
2. 改动尽量小而聚焦，一个 PR 做一件事
3. PR 描述里写清楚：**改了什么、为什么、真机上验证过吗**
4. 如果涉及 UI，尽量附上真机截图（模拟器不能拾取麦克风，曲线截图基本是空的）
5. 保持 CI 绿

## 代码约定

- Kotlin 官方风格（`kotlin.code.style=official`），4 空格缩进
- 注释写**为什么**，不写**做了什么**。尤其是算法部分，能写清推导就别只留结论——
  这个项目的代码里到处是"真机实测踩到的坑"，那是它最有价值的部分
- 公共 API 的改动在 PR 描述里单独说明
- 不要引入新的运行时依赖，除非确实必要；这个 App 的一个卖点是**没有音频框架依赖**，
  纯 `AudioRecord`/`AudioTrack` PCM 链路

## 关于签名

仓库里带了一个 `debug.keystore`（标准 androiddebugkey），只用于 debug 构建，
这样在 CI 和沙箱环境里不需要写 `~/.android`。**发布版请用你自己的签名密钥，不要提交**。

发布用的密钥通过 GitHub Secrets 注入，CI 里优先用正式密钥，
没配就退回仓库内的 debug keystore（保证 workflow 不会失败）：

| Secret | 内容 |
|---|---|
| `PINKVOICE_KEYSTORE_BASE64` | keystore 文件的 base64 编码（`base64 -i release.keystore \| tr -d '\n'`）|
| `PINKVOICE_STORE_PASSWORD` | 密钥库口令 |
| `PINKVOICE_KEY_ALIAS` | 密钥别名 |
| `PINKVOICE_KEY_PASSWORD` | 密钥口令 |

本地构建 release 版时也可以用同样的方式：

```bash
export PINKVOICE_KEYSTORE_PATH=/path/to/release.keystore
export PINKVOICE_KEYSTORE_PASSWORD=...
export PINKVOICE_KEY_ALIAS=pinkvoice
export PINKVOICE_KEY_PASSWORD=...
./gradlew :app:assembleRelease -PversionName=0.1.0 -PversionCode=26111512
```

> keystore 一旦丢失，**已发布的 APK 就再也装不上了**（系统认签名，
> 签名变了只能卸载重装，App 数据会丢）。请备份到密码管理器或安全硬盘，
> 不要只放在一台机器上。

## 发版

CI 的 `Release` workflow 只在**打标签**时触发：

```bash
git tag v0.1.0
git push origin v0.1.0
```

会构建 release APK 并发布到 [Releases](https://github.com/vera-qwang/pinkvoice/releases)。
普通提交不触发——每次提交都构建一遍 Release 纯属浪费额度。

## 关于隐私

麦克风音频只在设备内存里流转，**不落盘、不上传**。如果你的改动引入了录音保存、
网络请求或任何形式的遥测，请在 PR 里明确说明——这个承诺对使用者很重要。

## 交流

中文或英文都可以。技术问题建议先开 issue 讨论再动手。
