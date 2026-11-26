# Praat 6.4 脚本的坑

写 Praat 脚本前先看这个 —— 下面每一条都实际卡过。

## 1. 表格导出：三个常见命令都不可用

对 `Formant` 对象：

| 命令 | 结果 |
|---|---|
| `Print tabulated: ...` | `not available for current selection` |
| `To Table` | 同上 |
| `To TableOfFormants` | 同上 |
| `Save as: "file.txt"` | 同上 |
| `Extract all: "f1"` | 同上 |

**能用的**：`Get value at time` 逐点取值，然后自己写文件。

## 2. `Get value at time` 的参数顺序

Praat 6.4.67 的签名是：

```
Get value at time: <共振峰序号>, <时间秒>, <单位>, <插值>
```

**序号在前，时间在后** —— 和大多数文档里写的「时间, 序号」相反。
搞反了报的是 `Argument "Time" should be a number, not a string`。

单位可选 `"Hertz"` / `"Bark"` / `"Mel"`，插值可选 `"linear"` 等。
**四个参数都要给**，少一个报 `Command requires more than the given N arguments`。

## 3. 字符串拼接：`&` 和 `+` 都不行

| 写法 | 实际含义 | 报错 |
|---|---|---|
| `"a" & "b"` | `&` 是**逻辑与** | `A condition between "if" and "then" should be a number, not a string` |
| `"a" + "b"` | `+` 是**数值加** | `Found a string expression instead of a numeric expression` |

**正确做法**：`writeFileLine` / `appendFileLine` 接受多个参数，
每个参数之间自己插分隔符：

```
appendFileLine: "out.txt", fixed$(t, 6), " ", fixed$(f1, 2), " ", fixed$(f2, 2)
```

`writeFileLine` 是**覆盖**，循环里要用 `appendFileLine`。

## 4. `tab$` 和 `chr$` 都不存在

想用制表符分隔时的三个错误尝试：

- `tab$` → `Unknown variable`
- `chr$(9)` → `Unknown function`
- `tab` → `Unknown variable`

**就用字面量 `" "`**（空格）。要真制表符，在源码里直接放一个 tab 字符。

## 5. `fixed$` 只能一个参数

`fixed$(t, 6)` → `Expected ")" but found ","`。

正确：`fixed$(t)`（小数位数由 Praat 默认决定，够用）。

## 6. undefined 的判法

Praat 在静音段可能没有共振峰，取值返回 `undefined`。

```
x = Get value at time: 1, t, "Hertz", "linear"
if x = undefined
    x = 0
endif
```

这个判断本身是对的。但**不能把多个 undefined 判断用 `or` 连起来** ——
`if f1 = undefined or f2 = undefined` 会报同样的 if/then 错误。
**分开写，一个一个判。**

## 7. 静音段会给出乱七八糟的高值

这不是脚本问题，是 Praat 的行为：20 秒录音里只有 5 秒有人声，
Praat 对静音段照样给出 F1=1499 / F2=2374 这种值。

**统计量必须先筛出有声段**，否则结论完全错
（整段中位数 F1=1499，有声段中位数 529，差近 1000 Hz）。

筛选方法：读 WAV 算 RMS，找超过峰值 25% 的连续区间。

## 8. `exitScript: "消息"` 会把消息打到 stderr

脚本正常结束时想提前退出，用 `exitScript: "done"`，
Praat 会输出 `Error: done` —— **这是正常的**，别当成失败。

## 9. 相对路径不行

Praat 用**自己的工作目录**解析路径，脚本里必须给绝对路径：

```bash
WAV="$(cd "$(dirname "$WAV")" && pwd)/$(basename "$WAV")"
```

## 10. bash 变量后跟中文标点会被吞

```bash
echo "已导出: $OUT（$(wc -l < "$OUT") 行）"
```

bash 会把 `$OUT（` 当成一个变量名（全角括号是多字节，被当成标识符的一部分），
报 `OUT?: unbound variable`。

**用 `${OUT}` 加花括号。** 或者把标点放到变量外面。

## 11. mktemp 模板

macOS 的 `mktemp` 要求 `X` 在模板**末尾**：

```bash
mktemp /tmp/praat_formants.XXXXXX      # ✓
mktemp /tmp/praat_formants.XXXXXX.praat # ✗ 不替换
```
