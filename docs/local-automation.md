# 本机 API、CLI 与 MCP

桌面应用是工作区的唯一宿主。API、CLI 和 MCP 复用当前已解锁的会话、权限、索引、事务及加密审计，不另开工作区，也不要求外部 Agent 持有 Recovery Key。自部署 `server` 继续只处理密文。

## 启动

桌面 API 需要显式配置以下环境变量：

| 变量 | 用途 |
| --- | --- |
| `TAGLOOM_API_OWNER_TOKEN` | 持有者凭据，32–1024 字符，建议使用安全随机的 32 字节 Base64URL 文本 |
| `TAGLOOM_API_PORT` | 本机可用端口，1–65535；没有默认监听端口 |

两项均未配置时，桌面不启动 API；配置不完整或端口绑定失败时，桌面仍可运行，stderr 输出 `local_api_start_failed`。仅监听回环地址，并校验请求的 Host 与 Origin。

Windows PowerShell 示例，端口仅供示意：

```powershell
$randomBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$env:TAGLOOM_API_OWNER_TOKEN = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
[Array]::Clear($randomBytes, 0, $randomBytes.Length)
$env:TAGLOOM_API_PORT = '8088'
.\gradlew.bat :app:desktopApp:run
```

在桌面打开工作区后，使用另一个已配置相同地址与凭据的终端。CLI/MCP 只需要：

| 变量 | 用途 |
| --- | --- |
| `TAGLOOM_API_URL` | `http://localhost:<实际端口>` 或显式 IPv4 回环 URL |
| `TAGLOOM_API_TOKEN` | 持有者凭据，或通过持有者授予的独立 Agent 凭据 |

构建可执行客户端：

```powershell
.\gradlew.bat :app:automation:installDist
.\app\automation\build\install\tagloom\bin\tagloom.bat help
```

macOS/Linux 使用同目录下的 `bin/tagloom`，需要 JDK 21。分发目录由 Gradle 生成，不入库。桌面发行包的本机 API 也使用上述环境配置。

## CLI

以下示例假定 `tagloom` 已加入终端 PATH；ID 和 revision 必须来自实际读取结果，类型名称不能代替类型 ID。

```text
tagloom tools
tagloom list --limit 20 --offset 0
tagloom search "sample"
tagloom get <noteId>
tagloom block <noteId> <blockId>
tagloom backlinks <noteId>
tagloom tags
tagloom tag-schema <typeId>
tagloom schema
tagloom queries
tagloom saved-query <queryId>
tagloom run-query <queryId>
tagloom create --title "Sample" --content "- item" --tag <typeId>
tagloom update <noteId> --revision <actorId:sequence> --title "Updated sample"
tagloom add-tag <noteId> --revision <actorId:sequence> --tagId <typeId>
tagloom export <noteId> --revision <actorId:sequence> --mode extended
tagloom delete <noteId> --revision <actorId:sequence>
tagloom call <tool_name> --json <JSON对象>
tagloom result <callId>
tagloom cancel <callId>
```

属性精确查询示例使用 JSON 字符串值，PowerShell 通过单引号保留内部双引号：

```powershell
tagloom query --tag '<typeId>' --where '<propertyId> = "option-active"'
```

`get` 返回包含 Block、RichText、属性和 revision 的 JSON。Markdown 导出使用 `export`，标准模式为 `standard`，Tagloom 扩展模式为 `extended`，结果包含能力损失列表。

写入、导出与删除返回 `state: pending` 和 `callId`，此时业务状态尚未变化。持有者审批：

```text
tagloom approvals
tagloom approve <callId>
```

`approve` 先在终端显示具体参数与冻结预览，输入 `confirm` 后执行；删除或导出还要求输入 `confirm-risk`。没有跳过确认的命令行标志。业务结果以 JSON 输出到 stdout，预览和错误写入 stderr。已完成工具失败退出码为 1，参数解析错误为 2。

## Agent 授权

持有者使用自己的凭据授予独立 token，Agent 使用返回的 token。默认授予 `READ_NOTES`、`SEARCH_NOTES`，不具备审批或再授权能力：

```powershell
tagloom grant --json '{"scopes":["READ_NOTES","SEARCH_NOTES"]}'
tagloom grant --json '{"scopes":["READ_NOTES","SEARCH_NOTES","WRITE_NOTES"],"allowedNoteIds":["<noteId>"],"maxItemsPerCall":20}'
tagloom revoke '<principalId>'
```

`allowedNoteIds` 未指定表示当前工作区；空数组表示无笔记权限。限制到已有 Note 的 token 可以修改授权笔记，不能创建未知身份的新笔记。搜索和双链在分页前取授权交集；schema 只返回授权笔记相关类型、祖先和属性。受限 token 不发现工作区保存查询定义，但可以执行已知查询并获得授权命中。

删除还需要 `DELETE_NOTES` scope 和显式 `includeDangerous: true`；持有者仍需二次确认。导出需要 `EXPORT_NOTES`。远程 AI scope 当前不接受授予。

每个独立 token 只在一次解锁会话内有效。锁定、重新解锁或切换工作区后，需要重新授予；撤销后，该身份的缓存结果和待确认计划同时清除，取消记录写入加密审计。持有者配置凭据属于桌面宿主生命周期，重新解锁后可以发起新的管理操作；已启动的 MCP 和正在审批的 CLI 命令绑定原解锁会话，同样不能跨会话继续访问。

## MCP

MCP 使用官方 Kotlin SDK 的 stdio 传输，启动命令是 `tagloom mcp`。启动前须解锁桌面工作区，并配置 API 地址与独立 Agent token。工作区锁定后工具调用立即失效，重新授予后启动新的 MCP 进程。

对于支持 `mcpServers` 的客户端，Windows 配置示例：

```json
{
  "mcpServers": {
    "tagloom": {
      "command": "cmd.exe",
      "args": ["/d", "/c", "<install-directory>\\bin\\tagloom.bat", "mcp"],
      "env": {
        "TAGLOOM_API_URL": "http://localhost:<configured-port>",
        "TAGLOOM_API_TOKEN": "<independent-agent-token>"
      }
    }
  }
}
```

macOS/Linux 可直接使用 `<install-directory>/bin/tagloom`，参数为 `mcp`。具体配置文件位置与字段由所用 MCP 客户端决定。

工具发现按 scope 过滤，包含输入 JSON Schema、风险 annotations 和结构化结果。工具分组：

| 能力 | 工具 |
| --- | --- |
| 笔记读取 | `list_notes`、`get_note`、`get_block` |
| 查询与双链 | `search_notes`、`query_notes`、`get_backlinks` |
| 类型与属性 | `get_tags`、`get_tag_schema`、`get_workspace_schema` |
| 保存查询 | `get_saved_queries`、`get_saved_query`、`run_saved_query` |
| 本地建议 | `suggest_tags`、`suggest_properties` |
| 笔记写入 | `create_note`、`update_note`、`add_tag_to_note`、`delete_note` |
| 导出 | `export_note` |

此外提供 `tagloom_get_call_result` 和 `tagloom_cancel_call`，供 Agent 等待持有者批准或取消自己的提案；MCP 不提供批准工具。

资源支持 `note://<noteId>`、`tag://<typeId>`、`workspace://schema`、`query://saved/<queryId>`，同样经过授权及加密审计。类型建议使用本地名称匹配，属性建议使用已有继承解析器；这两项不调用模型。

按照 [MCP 工具错误规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#error-handling)，未知或当前身份未发现的工具返回 JSON-RPC `-32602` 和 `tool_unavailable`；缺少工具名等协议结构错误也返回 `-32602`。已发现工具的参数校验、权限、版本冲突和 API 离线等执行失败返回 `isError: true`，文字内容是 `structuredContent` 的相同 JSON，错误码位于 `errorCode` 或 `result.errorCode`。资源读取失败返回 `-32002` 和稳定失败码。这些错误不会终止 stdio 会话；锁定、撤销或跨解锁会话后，原进程的业务访问仍持续失效。

## API 与业务约束

[OpenAPI 3.1](../app/automation/src/main/resources/openapi.json) 是完整 HTTP 契约，运行时可从 `/v1/openapi` 读取。

| 路径 | 用途 |
| --- | --- |
| `GET /v1/tools` | 可发现工具与当前 sessionId |
| `POST /v1/calls` | 统一工具调用；只读返回 200，待确认返回 202 |
| `GET /v1/calls/{callId}`、`DELETE /v1/calls/{callId}` | 获取结果、取消提案 |
| `GET /v1/approvals`、`PUT /v1/approvals/{callId}` | 持有者预览和确认 |
| `POST /v1/grants`、`DELETE /v1/grants/{principalId}` | 持有者授权和撤销 |
| `/v1/notes`、`/v1/search`、`/v1/tags`、`/v1/schema`、`/v1/saved-queries`、`/v1/resources` | 复用同一工具入口的资源访问 |

认证使用 Bearer。长驻客户端先读取 sessionId，再在每次请求携带 `X-Tagloom-Session-Id`；服务端在同一调用锁中复核，避免会话检查和业务调用之间发生切换。

修改、设定类型、删除、导出必须带 `expectedRevision`，不能用时间戳替代。创建和更新预览冻结完整 Note；批准前 Note revision 或 schema 变化会拒绝旧计划。`update_note` 可提供 `title` / `markdown`，也可提供完整 `note`，两种方式不能混用。完整 Note 值按已实现属性类型校验，固定或自由选项都必须引用已经确认的 option ID；自由选项的创建仍由桌面维护入口完成。只读、公式和未知属性不能被外部覆盖，既有未知值可以原样保留。必填但未填写的属性沿用桌面草稿语义，不阻止空笔记创建。类型模板编辑及模板自动应用不属于本次外部能力。

成功写入审计与 Note/Change 使用同一 manifest 确认事务；读取、拒绝、取消、失败有独立加密审计。审计不保存参数、标题、正文或凭据。外部写入通知桌面刷新已保存文档；脏草稿及其旧 revision 保留，旧版本保存返回冲突。刷新期间锁定或重新打开工作区会丢弃旧会话的返回结果；当前会话的刷新失败显示本地化错误提示。

相同身份在当前会话内重复相同 `requestId` 与参数会返回原结果，改动参数返回 `idempotency_conflict`。确认及幂等缓存保留十分钟；过期的未批准请求取消并记录审计。每身份每分钟最多 120 个请求，每会话最多 128 个授权、256 个保留调用；JSON 请求不超过 1 MiB，客户端响应不超过 16 MiB，集合页最多 100 项。

HTTP 边界失败返回 `application/problem+json` 和稳定 `code`。已执行工具的业务失败在 `result` 中返回 `succeeded: false`：

| 失败 | 含义 |
| --- | --- |
| `unauthorized` / 401 | 凭据缺失、撤销或已跨解锁会话 |
| `owner_required` / 403 | 当前身份不能授权或批准 |
| `workspace_locked`、`session_changed` / 423 | 工作区未解锁或长驻客户端会话过期 |
| `confirmation_required` / 409 | 尚未完成规定的确认 |
| `revision_conflict`、`schema_changed` | 权威版本变化，需要重新读取并生成提案 |
| `property_readonly`、`property_value_invalid` | 完整 Note 写入违反属性约束 |
| `rate_limited`、`call_limit`、`grant_limit` / 429 | 超过本机入口预算 |

本次提供 Windows 桌面本机 API、JVM CLI 和 stdio MCP；类型/属性/保存查询维护写入、Database 外部工具、Streamable HTTP、无桌面宿主的解锁、公开网络 API、远程 AI Provider 和向量检索仍未接入。

## 验证

`./gradlew :app:automation:test`（Windows 使用 `gradlew.bat`）会启动真实加密工作区、SQLite、回环 API 和安装分发的 CLI/MCP 子进程，核对确认事务、授权过滤、撤销、过期、版本冲突及重新解锁后的失效。测试资料和密钥均限制在隔离临时目录中。

独立客户端验收使用官方 TypeScript SDK 和 MCP Inspector，不增加生产依赖，也不修改用户的 MCP 配置或 Inspector catalog。准备 Node.js 22.19 或更新版本后，在仓库根目录运行：

```powershell
npm.cmd install --prefix app/automation/build/mcp-client-acceptance --no-audit --no-fund @modelcontextprotocol/sdk@1.32.0 @modelcontextprotocol/inspector@2.9.0
.\gradlew.bat :app:automation:mcpClientTest
```

macOS/Linux 使用 `npm` 和 `./gradlew`。依赖只写入已忽略的 `build` 目录；已有独立安装目录可通过 `-PmcpClientHome=<directory>` 指定。常规 `test` 不运行这两项需要 Node 的跨客户端场景，`mcpClientTest` 会完整执行，并在缺少依赖时明确失败。

`McpClientAcceptanceTest` 使用独立 SDK 的 initialize、工具与全部资源读取、Inspector 严格 schema 检查、授权分页、异常输入、owner 审阅审批、幂等重试、Unicode 正文、Markdown 导出及取消/删除流程。JVM 宿主独立核对加密 Note、Change 和审计同一事务，删除后重新打开工作区复核持久化结果。另一个场景保持原 MCP 进程，验证撤销、锁定、重新解锁和 API 离线，确认缓存正文不可继续获取、新会话仍可读取已确认笔记。凭据只通过环境传递，不写入配置文件或真实进程命令行。

`McpProtocolIntegrationTest` 进入常规 `test`，不依赖 Node。它验证未知及未获授权工具、非法协议结构、非法业务参数、未知方法、严格资源 URI、失败后的合法读取和 stdin EOF 正常退出。

[固定 MCP 评估问题](../app/automation/src/test/resources/mcp-evaluation.xml) 包含十个独立的只读问题，资料由测试中的 `McpReadWorkflowScenario` 创建。`McpReadWorkflowIntegrationTest` 从实际 stdio 工具返回值推导并核对答案，覆盖保存查询、分页、继承默认值、代码块、链接和归档范围判断。这证明工具资料链能够完成这些场景；尚未进行真实模型效果评估。
