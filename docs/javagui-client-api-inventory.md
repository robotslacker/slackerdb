# slackerdb-client SDK 能力清单（能实现 / 受限 / 待服务端改造）

> 判据说明。本清单只回答一个问题：**服务端现有（或已批准改造的）接口，客户端 SDK 能封装到什么程度。**
> 状态图例：
> - ✅ **能实现** —— 接口存在、语义清楚，SDK 直接封装即可
> - ⚠️ **能实现但受限** —— 可用，但信息有损、语义受限或需要客户端补偿（已注明补偿方式）
> - 🔧 **现在不能实现，需服务端改造** —— 标注归属批次（批次 1/2 为必做，可选池见评估文档 5.4）
> - ❌ **服务端无此语义，做不了** —— 不是 SDK 的能力问题，是服务端不提供；除非新增服务端设计
>
> 证据来源：`dbserver/src/main/java/**`、`plugin-scheduler/src/main/java/**`、`common/.../discovery/**` 与 `README.adoc`（逐条核对过行号，见另两份文档）。
> 传输：管理口 `port_x`（HTTP/WS）+ pgwire `port`（JDBC + 自定义 TCP 管理协议）+ UDP 多播发现。

---

## A. 管理面 REST（8 个端点）

| 能力 | 端点 | 状态 | 限制 / 客户端补偿 |
|---|---|---|---|
| 服务器状态 | `GET /status` | ⚠️ | 返回 `server/database/parameters/usage/extensions/plugins/sessions`；`parameters.mcpLlmKey` 等敏感字段**照旧返回**（**已裁：不做脱敏**）→ 控制台**不展示**、不写日志即可；`sessions` **只含 pgwire 会话**，不含 WS/REPL 会话 |
| 创建备份 | `POST /backup {backupTag}` | ✅ | tag 不允许 `[<>:"\|?*\\/.]`；落 `./backup/<tag>.db` |
| 下载备份 | `GET /download?filename=` | 🔧 批次 1 | `/download` 限定 `./downloads`，而备份写 `./backup` → **备份下不来**；同批次修 |
| 上传文件 | `POST /upload?filename=` | ⚠️ | multipart 字段名固定 `file`；限定 `./uploads`；响应是**JSON 字符串** `"uploaded: <abs path>"`（非结构化），需客户端二次解析 |
| 下载文件 | `GET /download?filename=` | ✅ | 限定 `./downloads`；支持 `Range`/206/ETag → 可做断点续传与进度条 |
| 日志查看 | `GET /viewLog?filename=&lines=` | ✅ | 限定 `./logs`；默认 100 行、上限 10000；**不是流式** → SDK 用轮询模拟 tail |
| 整份日志 | `GET /logfile` | ⚠️ | 未文档化；只能取配置里第一个非 `CONSOLE` 的日志文件 |
| 首页/帮助 | `GET /`、`GET /help` | ✅ | 返回 HTML（控制台页与 README）；SDK 一般不需要，可做"打开浏览器控制台/帮助" |
| 备份列表 / 删除 / 恢复 | — | ❌ | 服务端只有"创建"，没有列表/删除/恢复语义 |

---

## B. 插件管理（5 个端点）

| 能力 | 端点 | 状态 | 限制 / 客户端补偿 |
|---|---|---|---|
| 插件列表 | `GET /plugin/list` | ⚠️ | 实现返回字段名是 **`state`**，而 README 写 `pluginState` → SDK 按实现解析，别按文档 |
| 装载插件 | `POST /plugin/load {filename}` | ✅ | 允许绝对路径，拒绝 `..` |
| 卸载插件 | `POST /plugin/unload {plugid}` | ⚠️ | 已注册路由**无法注销**、热重载不支持 → UI 必须提示"卸载后需重启服务器才算干净" |
| 启动/停止 | `POST /plugin/start|stop {plugid}` | ⚠️ | 失败一律 **HTTP 200 + `retCode:-1`**；重复启动返回 -1 → SDK 统一错误模型吸收 |
| 读取/修改插件配置 | — | ❌ | 插件属性只存在于 `dbserver.conf`（`<pluginId>.<key>`），**没有任何接口可读** → 远程无法管理配置 |

---

## C. 数据服务（12 个端点）

| 能力 | 端点 | 状态 | 限制 / 客户端补偿 |
|---|---|---|---|
| 登录 | `POST /api/login` | ⚠️ | **不校验任何凭据**，仅返回 `token`（base64url UUID）；token 只对 logout/setContext/removeContext 有效 → 不能当"用户体系"用 |
| 登出 | `POST /api/logout` | ✅ | 需 `Authorization: <token>`；token 存内存（1000 上限、30 分钟滑动过期、重启即失效）→ SDK 要处理过期 |
| 设置上下文 | `POST /api/setContext` | ✅ | body 是扁平 `key→string` map；需 token；上下文变量会作为 SQL 参数注入 |
| 移除上下文 | `POST /api/removeContext` | ✅ | `{removedKeyList:[...]}`；需 token |
| 注册服务 | `POST /api/registerService` | ⚠️ | `parameters` 是 **JSON 数组字符串**（不是结构化对象）→ SDK 要负责序列化/转义，容易出错；`serviceName+serviceVersion+serviceType(GET|POST)+sql` 必填 |
| 注销服务 | `POST /api/unRegisterService` | ⚠️ | 参数缺失时返回 **HTTP 502 纯文本**（不是 JSON）→ 必须专门适配；幂等成功仍返回 JSON |
| 服务列表 | `GET /api/listRegisteredService` | ⚠️ | 返回**裸 JSON 数组**（无 `retCode` 包裹），与其它接口风格不一致 |
| 导出定义 | `POST /api/dumpRegisteredService`、`POST /api/downloadRegisteredService` | ✅ | 两者载荷相同，附件名 `services.json` |
| 保存定义到服务器 | `POST /api/saveRegisterService` | ✅ | 依赖 `data_service_schema` 配置；文件→单文件，目录→按 category 落多个 `.service` |
| 从定义加载 | `POST /api/loadRegisterService` | ✅ | 接受对象或数组；重复项静默跳过 |
| 调用服务 | `GET|POST /api/{apiVersion}/{apiName}` | ⚠️ | 响应一次性物化（无游标/流式）；SQL 错误返回 **HTTP 200 + `retCode:-1`**；未注册返回 404 JSON；占位符未解析返回 `retCode:-1` 而非执行 |
| 取消正在执行的服务调用 | — | ❌ | 无取消语义，HTTP 调用只能客户端超时放弃 |
| 流式读取大结果 | — | ❌ | 单响应全量返回（受 `query_result_cache_size` 影响），无分页/游标变体 |

---

## D. MCP（协议面 2 + 管理面 15）

### D.1 协议面（JSON-RPC over Streamable HTTP）

| 能力 | 端点/方法 | 状态 | 限制 / 客户端补偿 |
|---|---|---|---|
| 单入口 | `POST /mcp`（规范端点）；`POST /jsonrpc`（兼容别名） | ✅ | 必须带 `Mcp-Method` 头（工具/资源调用还要 `Mcp-Name`），`params._meta["io.modelcontextprotocol/protocolVersion"]="2026-07-28"` |
| 能力探测 | `GET /mcp` → **405** | ⚠️ | 405 是**规范要求**（老 SDK 靠它判断"无 SSE"），SDK 不能把 405 当故障 |
| 服务发现 | `server/discover` | ✅ | 2026-07-28 用它替代已移除的 `ping` 与 `logging/setLevel` |
| 工具 | `tools/list`、`tools/call` | ✅ | 无 `initialize`、无 `Mcp-Session-Id`（无状态协议） |
| 资源 | `resources/list`、`resources/read` | ✅ | 含 `slackerdb://services` 等；服务清单只有一份真相（读 APIService 注册表） |
| 提示词 | `prompts/*` | ⚠️ | README 有 Resources and prompts 一节，但具体方法未逐一核实 → SDK 按 `server/discover` 的能力声明动态启用 |
| 内置工具（7 个） | `open_session`/`close_session`/`run_sql`/`list_tables`/`describe_table`/`sample_rows`/`list_services` | ✅ | 跨调用状态靠**服务端铸造的 handle**（`open_session` 返回）→ 客户端必须自己保存并传递 handle |
| 只读约束 | — | ⚠️ | 服务端默认只读（有上限与超时）；若服务端 `access_mode=READ_ONLY` 则不可 opt-out → UI 不要假设可以写 |

### D.2 管理面（15 个端点，三套 CRUD）

| 类别 | 端点 | 状态 |
|---|---|---|
| Tool | `registerMCPTool` / `unregisterMCPTool` / `loadMCPTool` / `saveMCPTool` / `dumpMCPTool` | ✅ |
| Resource | `registerMCPResource` / `unregisterMCPResource` / `loadMCPResource` / **`saveMCPSource`** / **`dumpMCPSource`** | ⚠️ |
| Service | `registerMCPService` / `unregisterMCPService` / `loadMCPService` / `saveMCPService` / `dumpMCPService` | ✅ |

- ⚠️ **命名不一致**：Resource 的 save/dump 用 `Source` 后缀（`saveMCPSource`/`dumpMCPSource`），而 register/unregister/load 用 `Resource` → **SDK 必须硬编码这 15 条路径，禁止做命名推导**。
- ⚠️ `save*` 按 category 或目录落**多个文件**；`load*` 从文件/目录加载 → SDK 的"分类"概念要与服务端一致。
- ❌ **读不到配置文件的原文**：管理面只有"注册表视角"（list/dump 是内存里的定义），没有"文件视角"的读取与更新 → 无法做"编辑服务器上那个 .mcp 文件"。

---

## E. SQL REPL WebSocket（`ws /sql/ws`）

| 能力 | 消息 | 状态 | 限制 / 客户端补偿 |
|---|---|---|---|
| 建会话 | `start` | ✅ | 服务端为会话复制一条后端连接，`autoCommit=true` |
| 执行 | `exec` | ✅ | 立即返回 `taskId`；**一个 session 同时只能跑一个任务**；多语句只有**最后一个结果集**保留（前面的被关闭）；DML 计数累加 |
| 取数 | `fetch` | ⚠️ | **无列类型**（只有 `columns` 标签）；行是**按列名做 key 的对象 → 重复列名会丢列**；无总行数；无 `maxRows` 上限；无查询超时；单值不截断 |
| 取消 | `cancel` | ⚠️ | 只按 `sessionId` 取消当前任务；成功后会重置 `taskId` → 旧 taskId 再 fetch 会报 `Invalid taskId` |
| 关会话 | `close` | ✅ | 明面契约可用 |
| 心跳 | 文本 `"ping"` → `"pong"` | ✅ | 服务端 10s 心跳 + 存活探测；`onClose` 在该 Javalin/Jetty 组合下**不触发** → 客户端要自己实现"断线检测 + 重连" |
| 错误容忍 | — | ⚠️ | 错误响应**可能不带 `id`/`type`** → SDK 的请求-响应关联逻辑要能容忍无 id 的错误帧 |
| 会话回收 | — | ⚠️ | 空闲会话按 `client_timeout`（默认 600s）回收，RUNNING 除外 → SDK 要有重连/重建会话逻辑 |
| 结构化类型 | — | ⚠️ | 数组→JSON 数组、STRUCT→对象、BLOB→Base64、时间→ISO 字符串；**LIST/STRUCT/MAP 与 pgwire 侧表现不同**，SDK 需要按通道分别映射 |
| WS 会话列表 | — | ❌ | `/status.sessions` 只列 pgwire 会话，WS/REPL 会话**看不见** |
| AI 对话 | `ws /aichat` | ⚠️ 条件能力 | 仅当 `mcp_llm_server` 配置时存在；消息 `chat` / `clear_history` → SDK 要按探测结果启用/隐藏该面板 |

---

## F. 调度器（`plugin-scheduler`，20 个端点）

| 能力 | 端点 | 状态 | 限制 / 客户端补偿 |
|---|---|---|---|
| 调度器状态 | `GET /scheduler/status` | ⚠️ | **硬编码常量**，不可信 → UI 不把它当真实状态源 |
| 启动调度器 | `POST /scheduler/start {threadCount}` | ⚠️ | `threadCount` **被解析但从不生效**（线程池固定 10）→ UI 不要暴露该输入 |
| 项目模板 | `GET /scheduler/project/list`、`GET /scheduler/project/{projectType}` | ✅ | 只读；模板是服务器文件系统上的目录 |
| 建实例 | `POST /scheduler/run/create` | ⚠️ | 只能写部分字段：**`retryTimes`/`retryInterval`/`mandatory`/任务级 `variables` 写不进去**（🔧 批次 1 修）；内联任务的默认 `scriptType=SQL` |
| 实例列表/详情 | `GET /scheduler/run/list`、`GET /scheduler/run/{runId}` | ✅ | 列表**无分页** → 实例多时客户端要自己分批或加过滤 |
| 实例生命周期 | `POST /scheduler/run/{runId}/start|stop|abort|drop` | ✅ | `start` 是**异步**的（STARTING→STARTED）→ UI 必须轮询；`drop` 会**删工作目录**，必须二次确认并显示路径 |
| 单任务操作 | `POST /scheduler/run/{runId}/task/schedule|start|stop|abort` | ✅ | `stop` 对不存在的任务也返回 200 → 客户端要自己校验 |
| 任务列表 | `GET /scheduler/run/{runId}/task/list` | ✅ | 含 `triggerState`/`nextFireTime`/`previousFireTime`/`running`/`queued`/`lastRetCode` 等运行态 |
| 执行历史 | `GET /scheduler/run/{runId}/task/history` | ⚠️ | 只能**逐实例**查，无全局流水、无分页过滤（🔧 批次 2 补全局历史） |
| 任务日志 | `GET /scheduler/run/{runId}/log?taskName=&tail=` | ⚠️ | 同任务多次执行**共用一个 `.log`（追加写）** → 客户端要按时间锚点切分；无 follow/搜索（🔧 批次 2 增强） |
| 变量预览 | `GET /scheduler/run/{runId}/variables?taskName=` | ✅ | 已按名字脱敏（PASSWORD/SECRET/TOKEN 等）；返回分层合并结果 + 框架变量清单 |
| Cron 校验 | `GET /scheduler/describeCrontabExpr?expr=` | ✅ | 返回 `valid` + 未来 5 次触发时间 → 白捡的 Cron 构建器体验 |
| **任务增/改/删、启用停用、改 cron/策略/超时/组/顺序** | — | 🔧 **批次 1** | 当前全模块无 PUT/PATCH/DELETE → GUI 只能"建实例后只读" |
| **实例配置/变量更新** | — | 🔧 **批次 1** | 建后不可改 |
| **模板 CRUD**（上传/下载/删除/新建） | — | 🔧 **批次 2** | 现在只能读 |
| **HOP 是否可用（提前判断）** | — | 🔧 可选池 | `hop.*` 配置无任何接口暴露；替代方案是新增只读 `/scheduler/config`（≈1 人日） |
| 重启后调度态恢复 | — | ⚠️ | 服务端用 RAMJobStore，重启把运行中的 run 静默降级为 STOPPED → **已决策不做持久化**，由 UI 的"三态提示 + 一键恢复"承担 |

---

## G. 自定义 TCP 管理协议（跑在 pgwire 端口上）

| 能力 | 命令 | 状态 | 限制 / 客户端补偿 |
|---|---|---|---|
| 停服务器 | `STOP` | ✅ 能实现 | 协议简单：连 pgwire 端口 → 发固定 header → 发 `!`+int 长度+命令文本 → 读文本响应。**不要拖 Netty 进客户端**，约 40 行裸 socket 即可 |
| 服务器状态 | `STATUS` | ⚠️ | 返回**文本**（非 JSON）→ 客户端要解析文本；信息与 `/status` 有重叠，优先用 HTTP |
| 杀会话 | `KILL <sessionId>` | ✅ | 与 `CancelRequest` 同一条跨线程取消路径 |

> 也可直接复用 `dbserver` 的 `AdminClient` 类，但它依赖 Netty（会把整套 Netty 拉进客户端），**不推荐**。

---

## H. pgwire / JDBC（客户端 SDK 的主力通道）

| 能力 | 状态 | 说明 |
|---|---|---|
| 连接/认证 | ✅ | 用户名口令被忽略（服务端永远 `AuthenticationOk`）→ 不要做登录界面 |
| 查询与类型化取数 | ✅ | `ResultSetMetaData` 给列标签 + JDBC 类型；按列序号可区分重复列名 |
| 元数据浏览 | ✅ | `DatabaseMetaData` + `pg_catalog` 仿真（schemas/tables/columns/primaryKeys） |
| 预编译与批量 | ✅ | `PreparedStatement`、批量 DML（服务端累加影响行数） |
| 取消 | ✅ | `Statement.cancel()` → 协议 `CancelRequest`；另有 `KILL SESSION` |
| 事务 | ⚠️ **M0 待验证** | `autoCommit` 与 `BEGIN` 支持已提交过；但**游标式分页（`autoCommit=false`+`fetchSize` 走 portal）是否可用尚未验证**，它决定百万行怎么取数 |
| 批量导入 | ✅ | `COPY ... FROM STDIN`，CSV 与 BINARY，实测 0.6–2.3M 行/秒 |
| 批量导出 | ⚠️ | `COPY TO STDOUT` **只有文本**，二进制未实现 |
| 复杂类型 | ⚠️ | `LIST` → PG 数组文本（`{a,b,c}`，可解析）；`STRUCT`/`MAP`/多层嵌套 → 退化为文本（不可结构化展示） |
| PL/SQL 与多语句 | ✅ | PL/SQL 块（单独模块）、多语句脚本（注意：最后一次结果集保留） |
| 服务端 SQL 审计 | ✅ | `sql_history=ON` 时可查 `sysaux.SQL_HISTORY`；`data_service_history=ON` 时有 `sysaux.API_HISTORY` → **两个审计能力走 JDBC 就能实现，不需要新接口** |
| Unix Domain Socket | ✅ | 服务端支持（`socket` 配置），驱动侧有 `UnixDomainSocketFactory` |

---

## I. 非 HTTP 能力（本地/网络侧）

| 能力 | 状态 | 说明 |
|---|---|---|
| 实例自动发现 | ✅ | 复用 `common` 的 `MulticastListener` + `Announcement`：UDP JSON 字段 `sd/type/name/alias/host/port/portX/ver/seq/ts` → 可做"自动发现本机/内网实例"（需服务端配 `advertise_address`，客户端配同一多播组） |
| 进程启停/状态 | ✅ | `java -jar slackerdb-dbserver-<ver>-standalone.jar start|stop|status` + pid 文件 + daemon 模式 |
| 服务器配置编辑 | ⚠️ 仅同机 | **没有 API**；GUI 与服务端同机时可直接读写 `conf/dbserver.conf`（属本地文件操作），改完需重启生效 |
| 远程服务器配置 | ❌ | 无接口，做不到 |
| 真鉴权 / 权限 / 多用户隔离 | ❌ | 服务端无此语义（`/api/login` 不看凭据）→ **本期已决策不做鉴权**，产品层强制只连回环地址 |

---

## J. 汇总

| 状态 | 数量 | 说明 |
|---|---|---|
| ✅ 能实现 | **≈ 58 项** | 覆盖四大需求的绝大多数操作面 |
| ⚠️ 能实现但受限 | **≈ 20 项** | 主要靠客户端补偿：错误模型统一、轮询 tail、容忍无 id 错误帧、按时间切分日志、三态展示（**脱敏已裁，不计入**） |
| 🔧 需服务端改造（批次 1/2） | **8 项** | 调度器任务 CRUD、实例更新、模板 CRUD、全局历史、`/status` 真实化、`threadCount` 生效、日志批次切分、备份可下载 |
| 🔧 可选池 | **3 项** | WS `fetch` 列类型、只读 `/scheduler/config`、线程池可配/会话列表/SQL 历史接口 |
| ❌ 服务端无此语义 | **9 项** | 备份列表/删除/恢复、数据服务取消、数据服务流式、MCP 配置文件原文读写、插件配置读取、WS 会话列表、远程配置读写、真鉴权/权限、远程 HOP 配置 |

### 三条对 SDK 设计影响最大的结论

1. **SDK 里的"错误模型"是第一等公民**：服务端至少有 5 种错误表达（HTTP 200+`retCode:-1`、502 纯文本、500 纯文本、404 HTML、404 JSON）→ 必须统一成 `ClientError{kind, httpStatus, retCode, retMsg}`，否则每种调用点都要写脏代码。
2. **两条取数通道不能混用类型映射**：JDBC 有类型、按列序号；WS 无类型、按列名（会丢重复列）→ 网格的数据模型必须能表达"有无类型"两种来源（这也是"异步面板放 M2"的合理性之一）。
3. **有 8 项能力卡在服务端**，其中 7 项是批次 1/2 的活儿 —— 这印证了"**批次 1 接口契约先冻结**"这条工作方式：客户端可以先把 SDK 按契约写好，服务端实现跟上即可，不用互相等。
