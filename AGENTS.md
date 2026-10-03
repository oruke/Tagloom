# Tagloom 仓库开发约束

本文件适用于 Tagloom 仓库根目录及全部子目录。系统级或用户级 `AGENTS.md` 继续生效；若约束不冲突，本文件提供更具体的项目规则。发生冲突时，以用户当前明确要求和作用域更具体的指令为准。

## 1. 项目状态与事实来源

- 产品与架构基线以 [`Tagloom_产品与技术设计草案.md`](./Tagloom_产品与技术设计草案.md) 为准。
- `README.md` 是面向开发者的项目入口，只描述已经确认的方向和当前真实实现状态。
- 当前仓库已完成 v0.2 Windows MVP 的核心实现：桌面工作区生命周期、加密快照与事务、派生索引、Block/RichText 编辑、Markdown、备份恢复、主密钥轮换和 Change checkpoint 均已有自动化验证，Windows 分发包也已完成真实启动与界面渲染检查。后续本机 API、可执行 CLI 与 stdio MCP 已提供独立受控入口，详见 `docs/local-automation.md`；远端 Change 自动合并、Android/iOS 功能对等、AI Provider 和向量数据库仍未提供完整可用入口。除非代码、测试和真实运行结果已经证明，不得把预留协议写成终端用户可用功能。
- 架构决策发生变化时，先更新设计文档中的对应章节和 ADR，再实现代码；不得让代码与文档长期维持两套事实。
- 设计文档中的示例 ID、路径、版本和性能指标是协议说明或设计预算，不得直接当成生产默认值。

## 2. 不可破坏的产品与架构原则

1. 已确认的本地加密对象快照是当前业务状态的唯一权威来源。
2. Change Log 只承担事务恢复、同步、历史、审计和撤销，可在 checkpoint 确认后压缩。
3. SQLite、全文索引、双链索引和向量索引都是可删除并从快照重建的派生数据。
4. 同一工作区在同一设备上只能有一个写入协调器；UI、CLI、MCP、AI Agent 和后台任务不得直接写工作区文件。
5. 服务端、WebDAV 和对象存储只保存密文，不持有 Workspace Master Key，也不具有覆盖本地确认状态的特殊权限。
6. MVP 不做多人实时协作。同步只面向同一用户的多设备离线变更，必须允许查看、保留和恢复冲突版本。
7. 所有外部入口复用相同的 Application Services、权限、事务、锁定状态和审计规则。
8. 远程 AI 只处理用户明确授权发送的必要上下文，不得默认扫描或上传整个工作区。

## 3. 当前模块职责

| 模块 | 职责 | 禁止事项 |
|---|---|---|
| `core` | KMP 领域模型、用例、端口、确定性解析器和跨平台协议 | 不依赖 Compose、Android API、SQLite、Ktor Server、具体加密 SDK或本地绝对路径 |
| `app/shared` | 跨平台 Compose UI、页面状态、交互编排和资源 | 不直接操作加密文件、数据库或同步协议；不复制领域规则 |
| `app/desktopApp` | JVM Desktop 入口、窗口和桌面平台装配、发行包配置 | 不承载可复用业务规则 |
| `app/automation` | JVM 回环 API、可执行 CLI、stdio MCP 和外部授权/审批适配 | 不另开工作区、不持有主密钥、不绕过客户端应用服务直接读写文件 |
| `app/androidApp` | Android 入口、生命周期和 Android 平台装配 | 不把 Android 类型泄漏到 `commonMain` |
| `app/iosApp` | iOS 入口、SwiftUI 宿主和 Apple 平台装配 | 不在 Swift 层重复 Kotlin 领域逻辑 |
| `server` | Ktor 密文同步与后续 AI Gateway 的服务端入口 | 不复用客户端文件存储实现，不读取工作区明文，不把服务端实体放入共享领域层 |

项目早期采用模块化单体。`tag-engine`、`property-engine`、`block-engine`、`crypto`、`file-store`、`query-engine` 等先作为职责清晰的 package、接口或实现存在；只有出现稳定公共 API、平台依赖隔离、独立复用或明显构建瓶颈时才拆分 Gradle 模块。

## 4. KMP 与依赖方向

依赖方向固定为：

```text
UI / CLI / MCP
→ Application Services
→ Domain
→ Ports

Platform Adapters
→ 实现 Ports
```

- 可复用领域规则、合并算法、schema、序列化协议和用例优先放入 `commonMain`。
- `commonMain` 不得引用 `java.io`、`java.nio`、Android Framework、Apple Framework、Compose UI、SQLite 驱动或具体密码库。
- 简单平台常量可以使用 `expect/actual`；文件系统、原子替换、系统钥匙串、安全随机数、窗口、通知和数据库等复杂能力优先通过接口注入。
- 平台实现分别进入 `jvmMain`、`androidMain`、`iosMain` 等源代码集，并使用公共契约测试验证行为一致性。
- 前后端只共享确实稳定的协议对象、枚举和校验规则；数据库实体、Repository 实现、认证实现和部署配置留在服务端。
- Gradle 改动遵循现有 version catalog、插件别名和类型安全项目访问器，不在子模块重复硬编码版本。
- 新增依赖前先说明用途、目标平台、包体积、许可证和可替代方案；密码学、富文本和同步依赖必须额外评估维护状态与跨平台覆盖。

## 5. 权威模型与写入协议

- Note、Tag、PropertyDef、SavedQuery 等对象使用统一的版本化快照头，至少包含 `objectId`、`objectType`、`schemaVersion`、`revision`、`lastChangeId` 和删除状态。
- 对象 ID 必须是不透明稳定 ID，不得由标题、标签名、属性名或本机路径派生。
- Block 外层结构固定为 `id/type/parentId/orderKey/content/attributes`。不得重新引入顶层 `text`、`level` 等第二套表示。
- 文本类 Block 使用规范化 `RichText.runs`。Inline Mark 使用 `{ type, attributes }`；单词级 `code` 是 Mark，多行代码是独立 Block。
- InlineRun 没有长期稳定 ID；并发与历史的最小权威单元是 Block。
- 序列化前必须执行确定性规范化，包括稳定字段语义、Mark 排序、相邻等价 Run 合并和空 Run 清理。
- 删除对象或 Block 时先生成 tombstone。达到保留期、同步确认和冲突引用清理条件之前不得物理删除。
- 所有业务写入必须经过 `TransactionCoordinator`，遵循“校验 → 加密暂存 → 持久化 prepared → 发布快照 → 更新 manifest 确认点 → 异步更新索引 → 清理”的流程。
- 索引写入失败不得回滚已经确认的业务快照；应标记索引落后并重建。

## 6. 同步预留规则

同步采用 AnySync 启发的对象级 Change DAG，但不宣称与 AnySync 协议兼容，也不在 MVP 引入完整 CRDT：

- Object ID 始终稳定；Change 使用内容标识并记录一个或多个 `parentChangeIds`。
- 没有后继的 Change 是对象 heads；多个 heads 表示并发分支。
- 设备先交换 heads/checkpoint，再按 Change ID 拉取缺失节点。
- 因果关系优先由 DAG 父边判断，Hybrid Logical Clock 只用于并发情况下的确定性排序和展示。
- 不同 Block 独立合并；同一 Block 的 RichText 使用 Base/Local/Remote 三方合并；简单标量字段使用确定性 LWW。
- 删除与更新竞争时，因果更晚者优先；并发时比较 HLC；仍相同时更新优先于删除。被覆盖版本始终进入加密历史或 ConflictRecord。
- Merge Change 必须同时引用全部被合并 heads。合并后生成并确认新的权威快照。
- checkpoint 之前的 Change 只有在快照验证、历史保留、有效设备确认和冲突引用检查全部通过后才能压缩。
- Sync Server 只负责设备认证、密文 Change/Blob 传输、幂等去重和 checkpoint 确认；合并与冲突解释发生在客户端。

## 7. 文件加密与安全约束

- 不自行设计密码算法。算法套件、密钥层次和信封字段遵循设计文档第 17 节。
- 密码通过 Argon2id 派生 KEK；KEK 包装 Workspace Master Key；HKDF 按用途派生 Wrapping Key；每次对象写入生成随机 DEK。
- 默认 AEAD 方案是版本化的 XChaCha20-Poly1305；若增加其他方案，必须通过显式 `cipherSuite` 和迁移测试支持，不能按平台隐式猜测。
- 每次加密使用安全随机 nonce。不得使用对象 ID、revision、设备 ID、时间戳或计数器直接充当 nonce。
- 文件信封公开头只保留解锁、路由和验证所需的最小字段；标题、正文、标签名、属性值、原始文件名和 MIME 等敏感数据必须位于密文中。
- Blob 使用随机 ID；如需工作区内去重，使用专用密钥计算 HMAC 标识，不得以明文 SHA-256 直接命名。
- 工作区 staging、备份和崩溃恢复文件同样必须加密，禁止把明文 JSON 或附件写入临时目录。
- 日志、异常、遥测、崩溃报告和测试快照不得包含密码、Recovery Key、密钥、标题、正文、属性值、原始文件名、远程 AI 上下文或真实账号。
- 锁定工作区时必须取消解密任务、关闭索引连接、丢弃会话密钥引用，并立即使 CLI、MCP 和 Agent 会话失效。
- 密码变更、Recovery Key 恢复、设备撤销、Workspace Master Key 轮换和算法迁移是不同业务流程，不得合并为模糊的“重置密钥”。

## 8. 注释与文档要求

- 类必须写中文 KDoc，说明职责、边界和关键约束。
- 所有业务函数，包括 `public`、`protected`、`private` 和业务构造函数，都必须写中文 Doc 注释，覆盖职责、每个参数、返回结果和关键约束。
- getter/setter 不单独写函数注释，字段语义写在属性声明正上方。
- 依赖注入构造函数若各依赖属性已有完整注释，可以不重复构造函数 Doc。
- `data class`、DTO、VO、Entity、路由资源和其他结构化承载对象的主构造参数按属性处理：逐项在属性声明上方写注释，不在类 KDoc 中堆叠 `@param`。
- 任意对象属性超过 3 个时，每个属性都必须有直接属性注释。
- 常量、关键状态、密钥/文件/事务相关字段和依赖注入字段必须说明业务语义。
- 多步骤流程必须在代码中标出阶段；状态机、幂等、防重、并发、补偿、加密和恢复逻辑必须解释“为什么这样做”。
- 注释优先使用中文，术语可以保留英文；修改逻辑时同步更新注释，禁止描述显而易见语句的无意义注释。

## 9. 文件职责与代码组织

- 编码前先检查目标文件职责和行数。超过约 500 行的文件只做局部修复或抽取，不继续塞入新功能。
- UI、协议校验、业务编排、领域状态、数据访问、平台能力、纯工具和测试分别放入对应文件或 package。
- 承载状态转换、命令编排、路径处理、协议解析、平台适配或数据映射的逻辑不得伪装成当前函数的小型 `private` 方法，应抽取为明确的领域对象、Service、Delegate、Reducer、扩展函数或 utils。
- 禁止新增语义模糊的 `*Helper`。无状态纯函数优先使用 `*Extensions` 或明确领域名称；有状态能力使用 `*Service`、`*Controller`、`*Delegate`、`*Reducer` 或协议中的准确名称。
- 触碰已有 `*Helper` 时必须评估改名或拆分，不继续扩大其职责。
- 不为尚未实现的远期功能创建大量空模块、空接口和占位类；只保留当前切片所需的最小扩展点。

## 10. 测试要求

- 领域规则优先使用 `commonTest`，测试不得依赖真实文件系统、网络、SQLite 或 UI。
- TagResolver 必须覆盖多父标签、菱形继承、稳定拓扑顺序、直接循环和间接循环。
- EffectivePropertyResolver 必须覆盖父级绑定、子级重载、笔记值优先、同级冲突以及 `dynamic/snapshot/none`。
- RichText 必须覆盖 Mark 规范化、相邻 Run 合并、加注锚点和未知 Mark 保留。
- 合并器必须覆盖 fast-forward、多个 heads、非重叠三方合并、同区间冲突、删除与更新竞争和确定性结果。
- 加密实现必须覆盖固定测试向量、错误密钥、nonce 唯一性、头部/密文篡改、截断、对象调包和跨平台兼容。
- 文件存储必须进行故障注入，覆盖每个提交阶段的进程终止、磁盘满、损坏 staging、manifest 不一致和密钥轮换中断。
- 索引测试必须证明删除索引后可以从已确认快照完整重建，并且查询不会读取未确认事务。
- 测试数据使用脱敏占位值，不包含本机用户名、真实路径、账号、Token、Cookie、内网地址或真实业务内容。

## 11. 验证命令与完成标准

Windows 环境优先使用仓库包装器：

```powershell
.\gradlew.bat :core:allTests
.\gradlew.bat :app:shared:jvmTest
.\gradlew.bat :app:shared:testAndroidHostTest
.\gradlew.bat :server:test
.\gradlew.bat :app:androidApp:assembleDebug
.\gradlew.bat :app:desktopApp:run
```

- 只运行与改动风险匹配的任务；任务名称变化时先使用 `tasks` 查询，不猜测不存在的 Gradle task。
- 修改 `commonMain` 时至少验证 JVM 测试，并说明 Android/iOS 是否已验证。
- 修改平台适配器时运行对应平台的契约测试和构建。
- 修改桌面运行时、文件系统、加密、资源或打包配置时，编译成功不等于完成；必须运行桌面应用或发行包验证真实启动、读写和错误输出。
- iOS 验证需要 macOS/Xcode；当前环境无法执行时必须在最终说明未验证范围和风险。
- 纯文档改动至少检查 Markdown fence、示例 JSON、相对链接、尾随空白和敏感硬编码。

## 12. MVP 实施顺序

首个 MVP 由设计文档的阶段一和阶段二组成，按以下顺序推进：

1. 纯 `commonMain` 对象模型、TagResolver、EffectivePropertyResolver 和单元测试。
2. 版本化加密信封与跨平台测试向量。
3. TransactionCoordinator、加密文件存储、原子写入和崩溃恢复。
4. Windows 桌面端工作区创建、Recovery Key、锁定和解锁。
5. Block/RichText 编辑、基础标签和属性。
6. Markdown 输入与导出。
7. SQLite 可重建索引、全文、标签、属性和双链查询。
8. Windows 发行包端到端验收。

同步、Android/iOS 功能对等、AI Gateway、MCP 写入、向量搜索和完整样式系统后置。预留接口不得扩大当前任务范围。

## 13. Git 与提交边界

- 修改前执行 `git status --short`，区分用户改动、已有暂存内容和当前任务文件。
- 只暂存当前用户明确要求的文件或 hunk，不使用无选择性的 `git add .`。
- 不回滚、格式化、删除或提交无关改动、IDE 配置、`local.properties`、生成产物、日志和环境文件。
- 提交前阅读相关 diff，并复核 `git diff --cached --name-only`、`--stat`、完整 cached diff 和 `git diff --cached --check`。
- 提交前对暂存内容执行私密信息检索并人工确认，禁止提交用户名、密码、本地绝对路径、局域网地址、Token、密钥、Cookie、真实邮箱和真实业务账号。
- 提交标题使用 `<type>: <业务结果>`；有任务 ID 时使用 `<type>: <任务ID> <业务结果>`。
- 用户未要求提交时，不自行创建 commit、分支、标签或远端变更。
- 用户要求提交后，报告 commit hash，并复核 author 与 committer；身份缺失或异常时先询问，不猜测。
