# trace-doctor

基于 EventTrace 轨迹与 AILogger 会话日志的离线诊断工具集。**只读**：不改 App、不联网、
不调用模型，纯解析本地文件。

## 三个工具

| 脚本 | 回答什么问题 | 输入 |
| --- | --- | --- |
| `trace_metrics.py` | 我的回合成功率/成本/失败分布是什么？ | 轨迹目录 |
| `ab_compare.py` | 这次改动到底有没有变好？ | 两份 `turns.csv` |
| `failure_signatures.py` | 失败集中在哪、该补什么约束？ | 轨迹目录 |

## 用法

```bash
# 1. 取轨迹（设备 → 本地）
adb pull /sdcard/Android/data/com.aicode.iii/files/traces ./traces

# 2. 回合级指标
python3 trace_metrics.py ./traces --out-dir report-base

# 3. 失败指纹与补强建议
python3 failure_signatures.py ./traces --min-count 3 --out failures.md

# 4. 改动前后对比（各自跑一次第 2 步，再比）
python3 ab_compare.py report-base/turns.csv report-cand/turns.csv
```

## 设计纪律

- **锚定行首解析**：本项目踩过「日志被命令正文污染 → grep 自污染 → 错误数字驱动结论」的坑。
  脚本逐行完整正则解析，解析失败的行计入 `unparsed` 而非静默丢弃；`unparsed` 占比高时报告会显式警告。
- **样本量不足会明说**：`ab_compare.py` 每组少于 `--min-n`（默认 30）回合时，报告顶部直接标注
  「不要据此判断」，而不是硬给结论。
- **建议附证据**：`failure_signatures.py` 每条建议都带会话/回合/原始行，可回读确认。
- **不自动重放**：对比只用已有轨迹。要真重放需显式做（会产生真实模型调用与费用）。

## 已验证

夹具（`fixtures/`）覆盖：回合切分、token 累计、工具成功/失败、未收尾回合、
生命周期行（版本/进程启动/上轮未收尾）、失败指纹归一化。三脚本在夹具上输出均符合预期。

真机数据验证由使用者侧执行（容器读不到 `/sdcard`）。
