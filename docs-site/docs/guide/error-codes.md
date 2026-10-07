# 常见错误提示

AI 请求失败或网络波动时，聊天页会先显示「正在重试 N/6」的气泡，气泡首行标注本次重试的原因；重试耗尽仍失败时，错误会以红色气泡展示。本文说明这些提示的分类含义与排查思路。

## 重试提示分类

请求中途断线或服务端暂时异常时，AiCode 会自动重试（最多 6 次、指数退避）。每次重试前气泡会先显示失败原因：

| 气泡提示 | 触发条件 | 可能原因与排查 |
| --- | --- | --- |
| 速率限制 (429) | 服务端返回 429 | 请求过于频繁，或套餐额度将尽。稍等自动重试即可；频繁出现请到供应商后台查看用量 |
| 服务器负载过高 (503) | 服务端返回 503 | 模型服务或中转站负载过高，属临时故障，等待自动重试即可 |
| 服务端错误 (5xx) | 服务端返回 500 / 502 / 504 等 | 服务端异常或网关问题，与你的配置无关，稍后重试 |
| 连接超时 | 建立连接或等待响应超时 | 网络慢、服务无响应，或中转站响应慢。检查网络，必要时更换网络或节点 |
| 连接被拒绝 | 目标端口没有服务在监听 | 供应商 Base URL 填错（域名 / IP 或端口不对），或服务未启动，到「设置 → AI 供应商」核对 |
| DNS 解析失败 | 域名解析失败 | 网络未连通，或自定义 Base URL 的域名拼写错误 |
| 连接中断 | 连接建立后被断开、流被中断 | 网络抖动、代理不稳定或服务端主动断开。自动重试即可；反复出现请检查网络与代理设置 |
| SSL 握手失败 | TLS 证书校验失败 | 自定义 Base URL 时确认协议（https）与证书正确，或服务端证书异常 |
| 网络连接断开 | 其它网络层故障 | 移动网络切换、弱网环境，检查网络后手动重试 |
| 网络异常 | 无法归类的错误 | 查看具体错误文本，仍无法解决时可附日志反馈 |

## 常见 HTTP 错误码

请求失败时，重试气泡和最终错误气泡会附上 HTTP 状态码。常见状态码含义如下：

| 状态码 | 含义 | 可能原因 |
| --- | --- | --- |
| 401 | 鉴权失败 | API Key 错误、已过期或被吊销，到「设置 → AI 供应商」检查密钥 |
| 403 | 权限不足 | 密钥没有该模型或接口的权限、地域限制、余额为 0 |
| 404 | 接口不存在 | 自定义 Base URL 路径填错（如漏了 `/v1`），到供应商设置核对 |
| 429 | 速率限制 | 请求太频繁或额度用尽，稍候重试或升级套餐 |
| 500 | 服务端内部错误 | 服务端临时故障，与配置无关 |
| 502 | 网关错误 | 中转站上游不可用 |
| 503 | 服务过载 | 服务端负载过高，稍候重试 |
| 504 | 网关超时 | 上游响应超时 |

## 与多 Key 切换的关系

「设置 → AI 供应商」开启多 Key 后，只有**密钥本身有问题**的错误（鉴权失败、权限不足、限流、余额用尽）才会消耗失败计数并触发 Key 切换；服务器 5xx、请求超时、连不上网等错误换 Key 也没有用，因此不会误切换。详见 [AI 供应商与模型 → 多 Key](/guide/providers)。

## 工具执行错误

除请求层错误外，AI 调用工具（读写文件、跑命令、操作浏览器等）失败时，聊天页的红色气泡里会带一个**全大写的错误码**。它的作用是让 AI 一眼判出「错在哪一类」，从而自己决定纠正方向（改参数、换思路、还是先探查），而不是拿同一套参数反复重试。

下表按**类别**归纳，不必逐个记。看到某个码时，先看 AI 在气泡下方的下一步说明——它通常会直接给出该怎么改。

### 参数缺失或非法

AI 调用工具时少传了必需参数，或参数格式不对。多半会被 AI 自己发现并补正后重试，无需你干预。

| 错误码 | 含义 |
| --- | --- |
| `MISSING_ACTION` / `MISSING_COMMAND` / `MISSING_INPUT` / `MISSING_ARG(S)` | 缺少必需参数（动作名 / 命令 / 输入等） |
| `MISSING_PATH(S)` / `MISSING_TAB_ID` / `MISSING_KEY` / `MISSING_VALUE` | 缺少路径 / 终端标签 / 键名 / 取值 |
| `MISSING_URL` / `MISSING_QUERY` / `MISSING_SELECTOR` / `MISSING_CONDITION` | 缺少网址 / 搜索词 / 选择器 / 等待条件 |
| `MISSING_PROMPT` / `MISSING_ITEMS` / `MISSING_NAME` / `MISSING_CONTENT` | 缺少提示词 / 条目 / 名称 / 内容 |
| `INVALID_ACTION` | 动作名不认识（或当前工具不支持该动作），AI 会被提示可用动作列表 |
| `INVALID_ARGS` | 参数组合或取值非法 |
| `INVALID_URL` / `INVALID_FILE` / `INVALID_PIPE` / `INVALID_OPTIONS` | 网址 / 文件 / 管道 / 选项不合法 |

### 文件与编辑器

| 错误码 | 含义 | 常见处置 |
| --- | --- | --- |
| `FILE_NOT_FOUND` | 目标文件不存在 | AI 会先用 `list` / `search` 确认真实路径再重试 |
| `FILE_EXISTS` | 目标已存在且未允许覆盖 | 需 AI 明确覆盖或换路径 |
| `NOT_READ` | 未先读取即尝试编辑 | 工具要求「先读后改」，AI 会先读该文件 |
| `NO_MATCH` / `MULTIPLE_MATCHES` / `STALE_CONTENT` | 待替换内容找不到 / 匹配到多处 / 文件已被改动 | AI 会重新读取文件后重新定位 |
| `EMPTY_OLD_STRING` | 替换的旧内容为空 | 参数错误，AI 会补正 |
| `NO_OP` | 改动内容与原文相同，无实际变化 | 属正常提示，非故障 |
| `READ_ERROR` / `WRITE_FAILED` / `EDIT_ERROR` | 读取、写入或编辑失败 | 见气泡附带的系统错误文本 |

### 命令与终端

| 错误码 | 含义 | 常见处置 |
| --- | --- | --- |
| `SLEEP_BLOCKED` | 命令含超长固定等待（`sleep`），被主动拦截 | 这是**有意设计**：不让 AI 用空等代替等待真实信号。AI 会改用长任务 + 完成通知 |
| `COMMAND_FAILED` | 命令执行失败 | AI 会被要求**不要用相同参数重试**，先检查或换思路 |
| `TERMINAL_NOT_FOUND` | 指定的终端标签不存在 | AI 会先用 `terminal` 的 `read` 或重开标签确认 |
| `TERMINAL_NOT_ACTIVE` | 终端已结束 | AI 会新建终端重新执行 |
| `START_FAILED` | 启动后台命令失败 | AI 可先用前台 `Bash` 验证命令本身 |
| `CONTAINER_NOT_READY` | Linux 容器尚未就绪 | 等容器启动完成，或到「设置 → 容器」检查状态 |

### 网络与联网工具

| 错误码 | 含义 | 常见处置 |
| --- | --- | --- |
| `FETCH_FAILED` | 网页抓取失败 | 检查网址可访问性，或改用搜索工具 |
| `SEARCH_FAILED` / `SEARCH_HTTP_ERROR` | 联网搜索失败（后者特指 HTTP 层失败，可稍后重试） | 稍后重试 |
| `BROWSER_NOT_READY` / `BROWSER_ERROR` | 内置浏览器未就绪或操作出错 | 重开浏览器面板后重试 |
| `TIMEOUT` | 操作超时 | 目标无响应，见气泡文本 |

### 子代理与写租约

`WRITE_LEASE` 系列是**并行安全机制**：多个子代理同时工作时，各自只允许写自己声明的路径，避免互相覆盖。

| 错误码 | 含义 |
| --- | --- |
| `WRITE_LEASE_CONFLICT` | 要写的位置已被另一个运行中的子代理占用 |
| `WRITE_LEASE_DENIED` | 要写的位置不在本子代理声明的写路径内（含截图、生成图片等落盘动作） |
| `MAX_SUBAGENTS_REACHED` | 并行子代理已达上限，需等前一个结束 |
| `NOT_YOUR_SUBAGENT` | 尝试操作不属于本会话的子代理 |
| `AGENT_NOT_FOUND` | 指定的子代理类型不存在 |
| `CAPABILITY_NOT_DECLARED` | 子代理定义仅声明支持 one-shot（一次性任务），对其已完成实例的 `send`（续聊）被拒绝。重新 create 派发新任务，或改用声明支持 `continuable` 的子代理 |

### 其它工具

| 错误码 | 对应工具 | 含义 |
| --- | --- | --- |
| `SKILL_NOT_FOUND` / `MISSING_SKILL_NAME` | 加载技能 | 技能名缺失或找不到 |
| `SHIZUKU_NOT_READY` / `SHIZUKU_EXEC_FAILED` | Shizuku | 服务未就绪或命令执行失败 |
| `MCP_SERVER_NOT_FOUND` / `MCP_MANAGE_FAILED` / `MCP_TOOL_ERROR` / `MCP_TOOL_EXEC_FAILED` | MCP | 服务器找不到 / 管理失败 / 远端工具执行失败 |
| `MISSING_SERVER_NAME` / `INVALID_SERVER_NAME` | MCP | 服务器名缺失或格式非法 |
| `MEMORY_NOT_FOUND` / `MEMORY_FAILED` / `WRITE_FAILED` / `DELETE_FAILED` / `CURATION_FAILED` / `APPLY_FAILED` | 记忆 | 记忆条目找不到或增删改失败 |
| `MISSING_RECEIPT_ID` / `MISSING_DESCRIPTION` / `MISSING_STALE_DAYS` / `INVALID_STALE_DAYS` | 记忆 | 回执号 / 描述 / 过期天数参数缺失或非法 |
| `TODO_FAILED` / `INVALID_ITEM` | 待办 | 待办操作或条目非法 |
| `EMPTY_RESULT` / `IMAGE_GEN_FAILED` / `VISION_CALL_FAILED` / `TOO_MANY_IMAGES` | 生成 / 查看图片 | 生图返回空、调用失败或图片过多 |
| `SEND_FILE_ERROR` / `MISSING_PATH` / `MISSING_PATHS` / `ARGS_MISMATCH` / `TOO_MANY_FILES` | 发送文件 | 发送失败、路径缺失、参数不匹配或文件过多 |
| `LIST_ERROR` / `UNSUPPORTED_OPTION` | 列目录 | 列目录失败或选项不支持 |
| `RG_MISSING` / `RG_ERROR` / `MISSING_ARGS` | 代码检索 | 检索工具不可用、执行失败或参数缺失 |
| `MISSING_SESSION` | 浏览历史 | 未绑定会话 |
| `MISSING_EDITS` | 编辑文件 | 未提供任何修改内容 |
| `MISSING_SCRIPT` | 浏览器 | 缺少要执行的脚本 |
| `MISSING_REASON` / `NOT_IN_PLAN` | 模式切换 | 缺少切换理由，或当前不在计划模式 |
| `NO_PARENT` | 子代理消息 | 找不到父会话 |
| `SPILL_NOT_FOUND` | 回取工具结果 | 之前落盘的大结果已不存在（可能已被清理） |
| `A11Y_NOT_ENABLED` / `OPEN_FAILED` / `CLICK_FAILED` / `SWIPE_FAILED` / `INPUT_FAILED` / `SCREENSHOT_FAILED` / `VSCREEN_FAILED` / `CLOSE_FAILED` / `MISSING_ARG` | 虚拟屏 | 无障碍未开启（见[软件权限](/guide/app-permissions)），或参数缺失、打开 / 点击 / 滑动 / 输入 / 截图 / 关闭失败 |
| `SESSION_NOT_FOUND` / `NO_SESSION` | 多工具通用 | 会话不存在或未绑定会话 |
| `NO_WORKSPACE` | 多工具通用 | 尚未打开工作区 |
| `ROUND_TOOL_LIMIT` | 框架层 | 单回合工具调用数已达上限 |
| `MODE_SWITCH_REJECTED` / `INVALID_TOOL_ARGUMENTS` | 框架层 | 模式切换被拒或工具参数非法 |
| `TOOL_TIMEOUT` / `TOOL_EXECUTION_FAILED` / `TOOL_NOT_EXECUTED` / `TOOL_NOT_FOUND` / `MISSING_STREAM_RESULT` | 框架层 | 工具执行超时 / 异常 / 未执行 / 不存在 / 流式无返回 |

### 问询工具

AI 需要你确认时用 `askUserQuestion`，参数不合格会被拦下并自行修正。

| 错误码 | 含义 |
| --- | --- |
| `MISSING_QUESTIONS` / `INVALID_QUESTION` | 缺少问题，或问题格式非法 |
| `TOO_FEW_OPTIONS` / `TOO_MANY_OPTIONS` | 选项太少或太多（需在合理区间内） |
| `TOO_MANY_QUESTIONS` | 一次问的问题过多 |

> **看到错误码该怎么办**：绝大多数情况下**不需要你处理**——错误码是给 AI 看的，它会据此自我纠正。只有在 AI 连续失败、或气泡提示要你手动操作（如开启无障碍、检查密钥、确认容器状态）时才需要介入。若某类错误码反复出现，可在「设置 → 日志」导出后反馈。

