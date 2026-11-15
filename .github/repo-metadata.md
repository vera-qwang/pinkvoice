> 创建 GitHub 仓库时可直接复制以下内容。

## 仓库名

`pinkvoice`（本地目录名仍是 `voicetools`，不影响，远程仓库名独立）

## 描述（Description，≤ 100 字符）

```
跨性别声音训练用的实时音高(F0)监测 + 同步返听 App · 开源 · 无网络权限
```

英文版：

```
Real-time F0 pitch monitor + simultaneous voice monitoring for trans voice training. Open source, no network permission.
```

## Topics（最多 20 个，建议 10 个以内便于被发现）

```
android
kotlin
compose
pitch-detection
yin
voice-training
transgender
mtf
voice-analysis
open-source
audio-visualization
```

## About 描述

```
PinkVoice 是一个 Android 实时音高(F0)监测 + 同步返听 App，为跨性别(MTF)声音训练设计。

核心特性：边唱边看音高曲线，同时在耳机里听到自己的声音——这两件事以前很难同时做到。
采集与输出两条路径彻底解耦，蓝牙大缓冲不会反压采集线程。

隐私：麦克风音频只在设备内存里流转，不落盘、不上传，没有 INTERNET 权限。

许可：Apache-2.0
```

## 建仓后要做的

```bash
cd /Users/vera/Desktop/voicetools
git remote add origin git@github.com:vera-qwang/pinkvoice.git
git branch -M main
git push -u origin main
```

## Release 说明模板（第一个 release 用）

### v0.1.0

- 实时 F0 曲线、音名/cents 读数、音分指针表、电平表
- 同步返听（音量可调），蓝牙/有线/外放设备识别
- 男/女声参考音域带
- 延迟测量 + 自动与手动视觉对齐补偿
- 修复八度误判（谐波主导时不再把 f0 报成高八度）
- 外放啸叫检测与自动限幅

Android 7.0+ · 约 XX MB · Apache-2.0
```

## 待办

- [x] 把 `README.md` 里的 `<你的账号>` 全部替换成实际 GitHub 用户名
      （共 4 处：3 个徽章链接 + 1 处 clone 地址）
- [ ] 把 `LICENSE` 里的 `Copyright [yyyy] [name of copyright owner]` 替换成实际年份和署名
- [ ] 确认 `debug.keystore` 是否保留（保留则贡献者开箱即构建；它是标准 androiddebugkey，无生产风险）
- [ ] 给仓库加 `README` 的中文/英文切换（当前只有中文）
