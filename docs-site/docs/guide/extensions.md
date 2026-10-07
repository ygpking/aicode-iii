# 扩展（贡献 manifest）

扩展是**声明式资源包**：一个目录 + 一份 `manifest.json`，向 agent 的能力机制批量贡献技能、提示词片段、记忆与 MCP 配置。不含可执行代码——跨沙箱分发可执行代码在 Android 上不可行，扩展只搬资源。

## 目录约定

两级扩展根，与技能/记忆/MCP 的「全局/项目」两级一致：

```
~/.aicode/extensions/<extId>/            全局（跨项目）
<workspace>/.aicode/extensions/<extId>/  项目级（随工作区，可 git 追踪）
  ├─ manifest.json
  ├─ skills/<skill-dir>/SKILL.md
  ├─ prompts/30-<名称>.md
  ├─ memory/<name>.md
  └─ mcp.json
```

## manifest.json

```json
{
  "id": "my-pack",
  "name": "My Pack",
  "version": 1,
  "description": "一句话说明",
  "contributes": {
    "skills": ["skills"],
    "prompts": ["prompts"],
    "memory": ["memory"],
    "mcp": "mcp.json"
  }
}
```

- `id` 必填，`[a-z0-9-]`；非法时按目录名修正并在扩展错误中标注。
- `contributes` 各路径**相对于扩展根**，解析时校验不越界：越界的贡献被跳过、记入该扩展的错误清单，不影响其它扩展。
- 任一字段可省略：省略即不贡献该类。

## 层叠次序（近者胜）

| 层 | 优先级 |
| --- | --- |
| `prompts.custom/`（用户显式覆盖） | 最高 |
| 项目级扩展 | ↑ |
| 全局级扩展 | ↑ |
| 项目级内置目录 | ↑ |
| 全局内置目录 | 最低 |
| APK 内置资源 | 基线 |

- 技能/记忆同名：项目 > 全局、扩展 > 内置（后者胜）。
- 提示词数字片段：custom 覆盖扩展覆盖内置；扩展的新增数字片段并入静态基线按数字排序。
- MCP server 同名：项目侧（含项目扩展）覆盖全局侧。
- `prompts.custom/.no-builtin`（完全禁用内置）不影响扩展：扩展是用户主动安装的内容，保留生效。

## 管理与故障排查

- 扩展由 AI 经文件工具直接创建/修改（写 manifest 与资源文件），新开会话生效。
- manifest 解析失败或路径越界**不会拖垮其它扩展**：该扩展的问题记录在错误清单，对应贡献被跳过。
- 不想要某个扩展：删除其目录，或改名让它不被扫描；不需要逐条删技能。
