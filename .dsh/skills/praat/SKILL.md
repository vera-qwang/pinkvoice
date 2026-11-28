---
name: praat
description: Use when a task needs real Praat analysis of speech audio — formant measurement, comparing an app's F1/F2 readings against a reference, checking whether an LPC/pitch implementation matches a known-good tool, or exporting a formant table for analysis; also when a measured formant value needs to be validated, explained, or compared across recordings.
---

# Praat：真工具，不是复刻

用**真的 Praat** 做共振峰基准，不要用 Python 里复刻的 LPC 代替。

## 为什么不能只靠复刻

复刻的 LPC 和 Praat 的 Burg 是不是一回事，只有对过才知道。
PinkVoice 的分析层就是靠这一步定案的：复刻版和 Praat 在同一段音频上
F1 差 5 Hz、F2 差 11 Hz，才敢说「分析层没问题」。

反过来，如果只信复刻版，**复刻错了就一起错** —— 之前 F1 被带宽上限
滤掉、错位一格的问题，就是靠真 Praat 才看清的。

## 怎么调用

脚本在本 skill 的 `scripts/formants.sh`，自带用法：

```bash
scripts/formants.sh <音频.wav>              # 导出 + 汇总
scripts/formants.sh <音频.wav> --csv out.csv # 只导出表格
scripts/formants.sh <a.wav> <b.wav>         # 对照两份（App vs Praat）
```

Praat 在 macOS 上是 `/Applications/Praat.app/Contents/MacOS/Praat`，
用 `--hide-picture --no-pref-files --run <脚本>` 跑非交互。

PinkVoice 仓库里另有一份 `tools/praat_formants.sh`（同样功能，
随仓库走，给没有 DSH 的人用）。

## Praat 6.4 脚本的坑（都踩过）

写 Praat 脚本前先看 `references/praat-scripting.md`，要点：

1. `Print tabulated` / `To Table` / `To TableOfFormants` 对 Formant 对象**都不可用**
2. `Get value at time` 参数顺序是 **序号, 时间, 单位, 插值**
   —— 不是常见文档里的「时间, 序号」
3. 字符串拼接：`&` 是**逻辑与**不是拼接，`+` 是**数值加**也不接受字符串。
   正确做法是 `writeFileLine` / `appendFileLine` 接受多个参数，
   自己插分隔符
4. `tab$`、`chr$` **都不存在**
5. 静音段 Praat 会给出乱七八糟的高共振峰，**必须先筛出有声段再看统计量**

## Praat 的确切配置（量出来的，别猜）

Praat 是 **GPL** 许可 —— 它的代码不能进 Apache-2.0 的项目。
PinkVoice 用它当**行为参照**，不复刻它的代码。

要复刻它的共振峰分析，配置是这样（逐项对着同一段录音量出来的）：

| 参数 | 值 |
|---|---|
| 分析速率 | **11 kHz**（= 共振峰上限 5500 Hz 的两倍）|
| 阶数 | **2 × 共振峰数**（5 个 → 10 阶）|
| 窗 | **高斯窗**，σ = (n+1)/√96 |
| 预加重 | **50 Hz 起点**，系数按**重采样后**的速率算 |
| 带宽筛选 | **不做**（只挡退化极点）|

> **Praat 脚本第 4 个参数是「半窗长」**：写 0.025 实际对应 50 ms 的分析窗。
> 实测：参数 0.0125 → 25 ms 窗，0.025 → 50 ms 窗，0.05 → 100 ms 窗。
> 对照时必须用同一个窗长，否则比的是两回事。

**分析速率是最容易搞错的** —— 极点频率 = 辐角 × fs / 2π，
差一点点结果就差几十 Hz。实测：

| 分析速率 | 阶数 | 与真 Praat 的平均误差 |
|---|---|---|
| 10 kHz | 10 | 200 Hz |
| **11 kHz** | **10** | **23 Hz** |
| 12 kHz | 10 | 471 Hz |
| 12 kHz | 11 | 32 Hz |
| 16 kHz | 10–12 | 1218 Hz |

**教训：有权威实现可读的时候，不要用「猜 + 验证」。**
验证只能确认你猜对了没有，不能告诉你正确答案是什么。
本 skill 的脚本能跑出真 Praat 的结果，正是因为读了源码。

## 输出怎么读

**不要直接对整段文件求中位数。** 一段 20 秒的录音里可能只有 5 秒有人声，
静音段 Praat 照样会吐出 F1=1499 这种值 —— 直接统计会得到完全错误的结论
（实测整段中位数 F1=1499，限定到有声段后是 529，差近 1000 Hz）。

脚本已经处理了这件事：它按 RMS 找出有声段，只在有声段上统计。

**中位数比均值可靠。** 共振峰分布是长尾的，Praat 逐帧输出会有
F1=191 → 1343 这种跳（同一段 25 ms 内），均值会被拖走，中位数不会。

**看 σ 和范围，不只看中位数。** 真人唱歌的逐帧 σ 就有 100–200 Hz，
这是方法本身的性质，不是实现的问题。

## 判断一个分析实现准不准

拿**同一份音频**跑三方对照：

| 对照 | 数据来源 | 说明什么 |
|---|---|---|
| App 现场读数 | 真机日志 | 用户实际看到的 |
| 离线复刻 | 同一份 WAV | 分析算法对不对 |
| **Praat** | **同一份 WAV** | 参照标准 |

三者中位数一致 → 分析层没问题，剩下的差异在音频本身或测量方法。
不一致 → 分析层有 bug，先修分析层，不要去调别的。

## 注意

- Praat 会**先重采样到 11 kHz** 再分析（`maximumFrequency*2`）。
  直接对 48 kHz 文件跑 `To Formant` 时，Praat 内部已经降采样过了，
  所以结果和「12 kHz 上分析」不一样。
- 共振峰测量的**个体差异和录音差异可能大于男女差异**。做阈值设计时，
  先量自己的测量噪声，再定目标区宽度 —— 目标区比噪声小就永远到不了位。
- 共振峰测量的**个体差异和录音差异可能大于男女差异**。做阈值设计时，
  先量自己的测量噪声，再定目标区宽度 —— 目标区比噪声小就永远到不了位。
