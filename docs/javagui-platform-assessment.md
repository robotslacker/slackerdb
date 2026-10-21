# SlackerDB Java GUI 用户界面平台：难度评估与实施方案（v1，历史稿）

> ## ⚠️ 本文为 v1 历史稿，框架相关部分已被否决
> 需求方后续决定：**否决纯桌面 JavaFX**，改用 **Vaadin（本机 Web 控制台）+ T1 独立进程**；同时**取消独立 client 模块**（访问层放进应用内部）。
> **定稿方案请看 [javagui-platform-plan-v2.md](javagui-platform-plan-v2.md)**；复议理由见 [javagui-vaadin-evaluation.md](javagui-vaadin-evaluation.md)。
> 本文仍然有效的部分：现状盘点（第 2 节）、服务端改造清单（第 5 节）、四项需求的功能边界与验收（第 3 节）、风险表（第 8 节）——这些与 UI 框架无关。

> 评估对象：在 `C:\Work\slackerdb`（SlackerDB 0.2.1）之上，用 **Java 桌面 GUI** 构建一个用户界面平台，覆盖 4 项需求：
> 1) SQL 查询/修改（对标 DBeaver）　2) API 服务管理 + MCP 本地管理　3) 本地报表模板 + 图表展现　4) 本地 ETL 调度管理
>
> 评估方式：以仓库现有代码为证据（文中均给出 `文件:行号`），只评估"难度与方案"，不含实现。

---

## 0. 结论速览

**技术上 4 项需求全部可行，没有不可逾越的障碍；难度差异极大，且难点基本都不在"Java GUI"本身。**

| 需求 | 服务端就绪度 | GUI 工作量 | 难度 | 真正的难点 |
|---|---|---|---|---|
| 1. SQL 查询/修改（DBeaver 类） | **高**（pgwire + pg_catalog 仿真 + WS 异步分页 + 取消 + COPY IN/OUT） | 大 | ★★★★☆ | 结果网格与单元格编辑、元数据树，是长期打磨项，不是协议问题 |
| 2. API 服务管理 + MCP 本地管理 | **很高**（注册/注销/列表/导入导出/保存加载 + MCP 工具/资源/服务三套 + JSON-RPC 端点） | 中 | ★★☆☆☆ | 几乎没有；只是 CRUD 表单 + JSON Schema 编辑 |
| 3. 本地报表模板 + 图表 | **零**（无报表引擎、无图表库、无导出库） | 大且不确定 | ★★★☆☆（极简）～★★★★★（可视化设计器） | 需求边界：要不要"设计器"，决定 2 人月还是 8 人月 |
| 4. 本地 ETL 调度管理 | **高但半成品**（Quartz + Hop，REST 以只读为主，缺全部写接口） | 中 | ★★★☆☆ | 需要补服务端写接口；HOP 需外部安装；运行态全部在内存 |

**总量级（1 名资深 Java 桌面开发，1 人月 = 21 人日）**

> ⚠️ **本文部分结论正在复议**：需求方提出「改用 Vaadin 替代 JavaFX」「取消独立 client 模块」，并明确"dbserver 及项目内所有东西都可改"。
> 复议评估见 **[javagui-vaadin-evaluation.md](javagui-vaadin-evaluation.md)**：其中第 3/16/17/18 条决策与"MVP 人月"口径已被修正，最终结论以复议文档为准。

- **MVP**（SQL 工作台精简版 + 服务/MCP 管理 + 调度只读监控 + 无报表设计器）：**6 – 8 人月**（口径更正：原写 4.5–6.5 与 P 表明细不自洽，单是 P0 2.0 + P2 1.6 就已 3.6 人月）
- **完整版**（4 项齐全，报表无可视化设计器，**含第 5 节的全部服务端改造**）：**12 – 18 人月**（Vaadin 方案测算为 11 – 16 人月）
- **上界参考**（再加上类 iReport 的报表可视化设计器）：**20 人月以上 —— 不建议一步到位**
- 注：因已决策**不做数据写回**，"对标 DBeaver"不再作为目标（DBeaver 的招牌能力正是网格编辑）；本方案的目标是"日常查询够用 + 服务/MCP/ETL 全流程在 GUI 内闭环"。

**已确认的前提（需求方已拍板，本文按此定稿）**

1. **服务端可以自由改造** → 需求 4 缺的写接口、以及一批服务端缺陷修复全部纳入实施范围，单独列出（见第 5 节）。
2. **MCP 只管理 SlackerDB 自身暴露的工具/资源/服务**，不做"本机多 MCP Server 宿主"（原含义 B 已排除，省 0.8–1.5 人月）。
3. **客户端是纯桌面 Java（JavaFX）**：不内嵌浏览器、不用 WebView、不依赖 HTML/CSS 排版 → 报表必须用 JavaFX 原生布局 + PDF/Excel 导出实现（第 4 节已据此收敛选型）。
4. **不做数据写回**（网格只读，修改走 SQL 编辑器；省 15 人日）。
5. **调度器的 SQL 型任务修成真正可执行**（走 JDBC，+2 人日）。
6. **不做 Quartz 调度持久化**（单机应用、任务可反复运行）→ 改为 GUI 侧"三态提示 + 一键恢复启动"补偿。

**三条最重要的判断**

1. **先做无 GUI 依赖的客户端 SDK 模块**（`slackerdb-client`），把 5 类协议（JDBC/pgwire、管理 REST、SQL WebSocket、调度 REST、MCP JSON-RPC）封装并对着真实服务器做集成测试，GUI 只做呈现。这是本方案能把风险压下来的关键。
2. **需求 3 必须切分立项**：一期只做"结构化模板 + 预览 + 导出"。由于不引入 HTML/CSS，报表排版要自己用 JavaFX 布局绘制、用 PDFBox/POI 导出 —— 工作量比"HTML 模板 + 浏览器打印"路线更高，但技术栈单一。
3. **需求 4 的成败取决于第 5 节的服务端写接口是否补齐**（已获授权）；补齐后需求 4 从"只读监控"升级为"全生命周期管理"，是本项目性价比最高的一块。

---

## 1. 评估范围与前提

**选型已定：纯 JavaFX 桌面客户端**（评估过的 Swing、内嵌浏览器两条路线在第 4 节留档说明为何不选）。含义是：

- 不内嵌 WebView / JCEF，**不依赖 HTML/CSS 排版**，现有 Web 控制台 `console.js` 不作为复用路径（仅作为功能对照与协议参考）。
- 结果网格用 JavaFX `TableView` 自绘虚拟模型；图表用 JavaFX 内置 `javafx.scene.chart`；报表用 JavaFX 布局绘制 + 导出 PDF/Excel。
- 好处是 UI 技术栈单一、无前端构建链、离线可用、打包可走 `jlink`/`jpackage`；代价是网格/报表排版这类"重活"全部自研（见第 7 节工作量）。

**共同前提**

- 客户端是**桌面程序**，连接本机或内网的 `dbserver`：`port`（pgwire，DBeaver 等 PG 客户端用的口）与 `port_x`（管理/数据服务口，Javalin）。
- `port_x` 必须启用：插件拿到的是管理端 Javalin 实例，`port_x` 关闭时 `dbInstanceX == null`，调度器插件的 `onStart` 会 NPE（`DBInstance.java:1182-1188`，`PluginScheduler.java:52,101`）。
- 不重新实现数据库内核；全部能力通过既有协议组合。
- **服务端无任何鉴权**（见 2.3），因此该平台只能定位为"本机/受信内网工具"。

**已确认的环境事实**

- 构建：Maven 多模块，`maven.compiler.target=17`，本机运行 JDK 21，镜像为 aliyun public，`localRepository=C:\work\repository`。
- 该本地仓库（`C:\work\repository`）**不含任何 GUI 相关依赖**（`org/openjfx`、`com/fifesoft`、`org/jfree`、`io/jcef`、`org/apache/poi` 均不存在），已有的是 javalin / pf4j / quartz 等；
- **但镜像可达性已实测通过**：以工作区内的临时仓库执行 `mvn dependency:get`，成功下载 `com.fifesoft:rsyntaxtextarea:3.4.1` 与 `org.openjfx:javafx-controls:21.0.2:jar:win`（exit 0）。→ **引入 JavaFX / 编辑器控件 / POI 等新依赖是可行的**，跑一次 `mvn` 即可把它们灌进 `C:\work\repository`。
- 发行包：`dist/slackerdb-dist_0.2.1.zip` → `bin/ conf/ jlib/ plugins/`，脚本硬编码该布局（`build/src/main/assembly/deployment.xml:5-152`）。GUI 产物应落在 `jlib/` 并新增 fileSet。

---

## 2. 现状盘点：可复用资产与硬约束

### 2.1 服务端已经具备的能力（GUI 的直接抓手）

| 能力 | 位置 | 对 GUI 的意义 |
|---|---|---|
| PostgreSQL 线协议 + `pg_catalog` 仿真 | 能力清单见 `README.adoc:12-23` | 任何 JDBC/ODBC 客户端可直连；元数据查询能跑通 |
| 官方 JDBC 驱动（pgjdbc fork） | `dbdriver` 模块（`org.slackerdb.jdbc.Driver`） | GUI 侧取数的首选通道；也可用标准 PG 驱动 |
| SQL REPL WebSocket（异步 + 分页 + 取消） | `dbserver/.../repl/SqlReplServer.java:57`（`/sql/ws`） | 长查询/大结果的另一条通道，控制台已在用 |
| 数据服务注册表（SQL → HTTP 端点 + 缓存 + 版本） | `dbserver/.../service/APIService.java:480-827` | 需求 2 的后端本体，含 list/dump/save/load 全套 |
| MCP 服务端（2026-07-28 版规范，POST `/mcp`、别名 `/jsonrpc`） | `dbserver/.../service/McpServer.java:427-1675` | 需求 2 的 MCP 部分；工具/资源/服务三套 CRUD + 配置目录 |
| 插件管理 API + PF4J 生命周期 | `dbserver/.../service/PluginService.java:36-40` | GUI 可做插件装载/启停面板 |
| 调度器插件（Quartz + Hop，Run/Task 模型，cron、组、顺序、重试、历史、日志、变量） | `plugin-scheduler/.../controller/SchedulerController.java:77-111` | 需求 4 的后端主体 |
| 管理端口 `/status`、`/backup`、`/upload`、`/download`、`/viewLog`、`/logfile` | `dbserver/.../server/DBInstanceX.java:441-624` | 状态面板、备份、日志面板 |
| 多播服务发现 | `common/.../discovery/*` | GUI 可以像 DBeaver 一样"自动发现本机/内网实例" |
| SQL 审计历史（可选） | `sysaux.SQL_HISTORY`（`DBInstance.java:1060`，`sql_history=ON` 时启用） | 可做"全局 SQL 历史"视图 |
| 取消/杀会话 | CancelRequest + `KILL SESSION`（`AdminClientRequest.java:276-283`） | 会话管理面板 |
| COPY IN / COPY OUT（文本） | `dbserver/.../sql/CopyProtocolHandler.java:376-514` | 大批量导入导出（二进制 COPY OUT 未实现，`:417`） |

### 2.2 已存在的 Web 控制台：功能对照与协议参考（不作为复用路径）

- 资源：`dbserver/src/main/resources/web/console.{html,js,css}`，其中 `console.js` **150 KB**、`console.css` 32 KB。
- 已实现面板（`README.adoc:2478-2517`，代码核对见 `console.js`）：SQL 交互 Shell（WS `/sql/ws`，分页自动续取、取消）、**Data Service 管理**、**MCP Tool 管理**、**MCP Resource 管理**、**MCP Service 管理**、AI Chat（`/aichat`）、Status 弹窗、Backup 弹窗、Log Viewer。
- 覆盖度：**需求 1 的骨架 + 需求 2 的绝大部分**（浏览器形态）。
- 官方定位：`README.adoc:2482` 明确"**不是生产可用功能**，是演示工具"；表格渲染为朴素 `<table>`，无类型、无虚拟滚动、无行数上限。
- 已知缺陷（我们的新客户端必须绕开，同时按 5.2 清单在服务端修掉）：`console.js:2198` 调用了不存在的 `/mcp/unregisterMCPSource`（服务端是 `/mcp/unregisterMCPResource`，`McpServer.java:962`）；备份文件自动下载失效（备份写 `./backup`，而 `/download` 限定 `./downloads`，`DBInstanceX.java:513` vs `FileHandlerHelper.java:27`）。

> **它的正确用途**：作为**接口调用序列与协议行为的参考实现**（哪些端点按什么顺序调、参数怎么传），以及验收时的**功能对照基线**；UI 本身不复用（已被"纯桌面、不用 HTML/CSS"的前提排除）。

### 2.3 硬约束（会直接影响方案设计）

1. **全链路无鉴权**：pgwire 永远回 `AuthenticationOk`，用户名/口令无意义（`StartupRequest.java:212-215`，`README.adoc:2568-2572`）；`/api/login` 不校验任何凭据，只发一个 UUID token，且只用于 logout/setContext/removeContext（`APIService.java:480-542`）。`/sql/ws`、`/status`、`/backup`、`/upload`、`/download`、`/viewLog`、`/logfile`、`/plugin/*`、`/mcp*`、服务注册表接口**全部无鉴权**，CORS 还是 `anyHost`（`DBInstanceX.java:340-341`）。
   → **GUI 必须默认只连 `127.0.0.1`，并把"不要把 port_x 暴露到网络"写进产品提示。**
2. **`/status` 明文回显 `mcpLlmKey`**（`DBInstanceX.java:766`）→ **已裁：不做脱敏**；控制台不展示该字段、不写日志即可（内部工具、无鉴权，脱敏无真实收益）。
3. **错误传输不一致**：`/api/unRegisterService` 参数缺失返回 HTTP 502 纯文本（`APIService.java:628`）；数据服务 SQL 出错返回 HTTP 200 + `retCode:-1`（`:263-277`）；`/plugin/*` 一律 HTTP 200 + `retCode`；未捕获异常返回 500 纯文本（`DBInstanceX.java:640-643`）。→ 客户端 SDK 必须做统一错误模型。
4. **WS 取数的类型损失**：`fetch` 只回 `columns=[getColumnLabel]`，**没有列类型/OID**；行是**按列名做 key 的对象**，重复列名会互相覆盖（`SqlReplServer.java:550-558,634-640`）；没有总行数、没有 maxRows 上限、没有查询超时、不做截断。→ **结果网格应以 JDBC 为主通道**（有类型、有元数据、可流式游标），WS 只用于异步长查询。
5. **复杂类型**：LIST 已按 PG 数组文本发送（最新提交 `aad96d2` "Support list column"，`RowEncoder.java:390-412`），但 STRUCT/MAP、多层嵌套仍退化为文本（`RowEncoder.java:567-571`）；`README.adoc:2574-2579` 的"类型受限"说明仍然部分成立。→ 网格需要"数组字面量解析 + 未知类型按文本渲染"两条兜底。
6. **数据服务响应整体物化**：`/api/{ver}/{name}` 一次性返回全部行，无游标/流式（`APIService.java:226-245`，`README.adoc:1446-1447`）。
7. **调度器运行态全是内存**：Quartz 用 RAMJobStore（`DynamicQuartzScheduler.java:51`），线程池固定 10（`PluginScheduler.java:95`）；`/scheduler/start` 的 `threadCount` 参数**被解析但从未生效**（`SchedulerController.java:128-142`），`/scheduler/status` 是硬编码常量（`:159-170`）；重启后所有 STARTING/STARTED 的 run 被静默降级为 STOPPED，RUN_ONCE 不会自动补跑（`SchedulerController.java:1045-1075`）。
8. **HOP 需外部安装**：`hop-run.bat|sh` 以子进程方式执行（`HopCommandBuilder.java:13-97`），未配置 `plugin-scheduler.hop.runScript` 时 HOP 任务直接失败（`:51-53`）；发行包里**故意不含 HOP**（`deployment.xml:139-140`）。
9. **依赖环境**：项目 target JDK17、本机 JDK21；本地 maven 仓库无任何 GUI 库，但 aliyun 镜像已实测可下载（见第 1 节）。仍有两个实际约束：**OpenJFX 按平台 classifier 分发**（win / mac / mac-aarch64 / linux 各一个 artifact），以及 JavaFX 是模块化组件 —— 建议用 `javafx-maven-plugin` + `jlink`/`jpackage`，**不要打 fat jar**。
10. **插件不能贡献静态资源**：Javalin 的静态目录在启动前固定为 classpath `/web`（`DBInstanceX.java:334-338`），插件只能自己写 handler；且已注册路由无法注销，热卸载不安全（`README.adoc:2841,3043`）。→ **GUI 不能靠"做成插件"来分发 Web 资源。**

---

## 3. 四项需求逐项评估

### 3.1 需求 1：SQL 查询 / 修改（对标 DBeaver）

**后端就绪度：高。** 缺的几乎都是"客户端侧"的东西。

两条取数通道的取舍（建议都用，按场景路由）：

| 通道 | 优点 | 缺点 | 用途 |
|---|---|---|---|
| **JDBC（pgwire）** | 有列类型/元数据、可流式游标、可事务、可 PreparedStatement、标准生态（连接池、`DatabaseMetaData`） | 长查询需自己分页；结果集持有连接 | **默认通道**：编辑器执行、元数据浏览、数据编辑 |
| **WS `/sql/ws`** | 异步、服务端分页（`hasMore`/`fetch`）、可取消、PL/SQL 与多语句脚本、无需 JDBC 驱动 | 无类型、重复列名丢列、无总行数、无上限、无超时 | 长查询/大结果异步执行、脱离 JDBC 的轻量模式 |

**GUI 功能拆解与工作量（人日，1 名资深桌面开发）**

| 功能点 | 人日 | 说明 / 依赖 |
|---|---|---|
| SQL 编辑器（高亮、补全、多语句、片段、格式化） | 12 | 需要第三方编辑控件；补全数据可来自元数据缓存 |
| **结果网格**（虚拟滚动、类型化渲染、NULL/BLOB/数组、复制、列宽/排序/过滤、导出） | 25 | **本项目最重的单点**；必须自研虚拟化模型 |
| 元数据浏览器（schema/表/视图/列/类型/PK 提示、DDL 查看） | 10 | 服务端无元数据 REST，需自写 SQL（`DatabaseMetaData` 可用） |
| 执行/取消/多标签/会话与服务器会话列表（`/status.sessions` + KILL SESSION） | 8 | 会话列表只含 pgwire 会话（`DBInstance.java:1399`） |
| 导出 CSV/JSON/Excel | 6 | Excel 需 POI（新依赖）或先只做 CSV/JSON |
| 连接管理、凭据（其实是空口令）、布局持久化 | 8 | 无鉴权 → "凭据"仅作连接标识 |
| ~~单元格编辑 / 生成 DML（原 15 人日）~~ | **0** | **已决策不做数据写回**：DuckDB 表普遍无主键，"整行匹配"会静默改错行；修改数据回到 SQL 编辑器 |
| **小计** | **≈ 69 人日 ≈ 3.3 人月** | 若要"接近 DBeaver"的成熟度，再乘 2–3 |

**难点排序**：结果网格（性能 + 类型渲染 + 窗口化取数）> 元数据树与错误映射 > 编辑器。

**必须先解决的技术决策**
- 网格模型：`JTable` 自绘虚拟模型（Swing）或 `TableView` + 自定义 cell（JavaFX）；百万行必须分页/游标，不能全量入内存。
- 大值策略：WS 无截断，需在客户端设阈值（如单值 > 1 MB 显示占位符 + 按需加载）。
- 复杂类型渲染：数组按 `{a,b,c}` 解析为可展开列表；STRUCT/MAP 按文本展示。

---

### 3.2 需求 2：API 服务管理 + MCP 本地管理

**后端就绪度：很高，几乎是"有 API 缺界面"。**

- 数据服务：`/api/login`、`/api/setContext`、`/api/registerService`、`/api/unRegisterService`、`/api/listRegisteredService`、`/api/dump|downloadRegisteredService`、`/api/saveRegisterService`、`/api/loadRegisterService`、`GET|POST /api/{apiVersion}/{apiName}`（`APIService.java:480-827`）。
- 服务定义格式：JSON 对象/数组，字段 `serviceName/serviceVersion/category/serviceType/sql/description/searchPath/snapshotLimit/parameters[]`（`APIService.java:366-425`，样例 `dbserver/conf/data_service_example.service`）；支持"目录 + `*.service`"分文件加载（`:446-477`）。
- MCP：协议端点 `/mcp`、`/jsonrpc`（`McpServer.java:427-429`，GET 必须 405 以兼容旧 SDK，`:443-446`）；管理端点 `/mcp/{register,unregister,load,save,dump}MCPTool|MCPResource|MCPSource|MCPService`（`:799-1675`）；只读默认 + 上限 + 超时（`:127-142`）；工具/资源/服务可按 category 落多个配置文件。
- 插件：`/plugin/list|load|unload|start|stop`（`PluginService.java:36-40`）。

**GUI 工作量**

| 功能点 | 人日 |
|---|---|
| 数据服务列表/新建/编辑/删除/批量、SQL 与 `${参数}` 编辑、searchPath 与缓存 TTL、联调执行面板 | 12 |
| 导入/导出（`dump`/`load`/`save` 到服务器配置目录）、分类目录视图 | 6 |
| MCP 工具/资源/服务面板（含 `inputSchema` JSON Schema 编辑与校验、只读开关提示） | 15 |
| MCP 客户端调试台（`server/discover`、`tools/list`、`tools/call`、`resources/list|read`，显示 JSON-RPC 往返） | 8 |
| 插件管理面板（装载/启停、状态与错误） | 5 |
| **小计** | **≈ 46 人日 ≈ 2.2 人月** |

**难点：低。** 主要风险是服务端小毛病要在客户端兜住（`unRegisterService` 的 502 文本、失败仍 200 + `retCode`、README 与实现字段名不一致：文档写 `pluginState`，实现回 `state`）。

**"MCP 本地管理"的范围已确认（按含义 A 实施）**

- **实施范围（已确认）**：管理 SlackerDB 自己作为 MCP Server 暴露的工具/资源/服务，以及它们的本地配置文件（`mcp_config` 指向的单文件或目录，按 category 分文件）。→ 就是上面这套面板。
- **已排除**：像 Claude Desktop / DSH 那样作为宿主纳管本机多个 MCP Server（stdio/HTTP 传输、`initialize`/`tools/list`/`tools/call` 客户端、进程托管、健康检查与日志）。该子系统约 0.8 – 1.5 人月且与 SlackerDB 服务端无关，本期不做。

---

### 3.3 需求 3：本地报表模板 + 报表数据图表展现

**后端就绪度：零。** 全仓库没有任何报表/图表/导出的依赖或代码（`pom.xml` 无 POI/iText/Jasper/JFreeChart；Java 源码中的 "report/chart/xlsx/pdf" 命中全部是无关文本）。唯一可复用的是 **CJK 字体资源**：`build/src/main/resources/pdf/NotoSansSC-Regular.ttf` 与 asciidoctor 中文主题，导出 PDF 时可省掉字体选型这一步。

**必须把需求切成三档，否则工作量无法估计**

| 档位 | 内涵 | 人日 | 难度 |
|---|---|---|---|
| **A. 极简（已确认为一期）** | 报表 = 可编辑的模板定义（JSON/表单：数据源 SQL、参数、若干"块"：标题/富文本/文本/表格/图表/图片）+ 声明式格式与条件高亮 + 运行预览 + 导出 | ≈ 60（见下） | ★★★☆☆ |
| **B. 中档** | 模板模型 + 简单可视化布局编辑器（拖拽块、属性面板、绑定字段）+ 多页/分页/页眉页脚 | ≈ 110 | ★★★★☆ |
| **C. 对标 iReport/JasperReports** | 分带（band）设计器、表达式语言、子报表、交叉表、多数据源 | 200+ | ★★★★★ |

**A 档工作量拆解**

| 功能点 | 人日 |
|---|---|
| 报表模型 + 模板格式（JSON + `$schema`）+ 校验 + 存取 | 15 |
| 报表运行器：参数解析、取数（JDBC 或数据服务 API，可复用其缓存）、结果缓存、失败重试 | 12 |
| 图表渲染与交互（柱/线/饼/散点、多序列、钻取可选） | 12 |
| 声明式格式化 / 条件高亮 / 条件显示（替代表达式语言，已决策） | 5 |
| 富文本段块（已决策需要） | 3 |
| 导出：PDF（复用 NotoSansSC）、Excel（POI，新依赖）、CSV、PNG | 13 |
| **小计** | **≈ 60 人日 ≈ 2.9 人月** |

**图表选型（与第 4 节选型联动）**

| 方案 | 依赖 | 优点 | 缺点 |
|---|---|---|---|
| JavaFX 内置 `XYChart`/`PieChart` | OpenJFX | 无额外依赖、原生交互 | 图表类型少、样式偏朴素、大数据量性能一般 |
| JFreeChart | LGPL 库 | 图表类型最全、导出 PNG/SVG 成熟、与 Swing 亲合 | 外观陈旧、LGPL 需注意合规 |
| WebView + ECharts（离线 bundle） | 内嵌浏览器 | 效果最好、报表排版用 HTML/CSS 极省力 | 引入第二套渲染栈；WebView 打包体积与稳定性成本 |
| 自绘 Canvas | 无 | 完全可控 | 不划算 |

**与调度器结合**：报表数据刷新可直接落成 SQL 型调度任务（`plugin-scheduler`）。注意 `taskScriptType` 的有效集合是 `HOP|SHELL|COMMAND`，SQL 不是任务类型（写进 HOP 工作流的 SQL action）。~~此前 API 默认值是 `"SQL"`、而执行器一律拒绝，属于一个 bug~~ **已修**：API 与模板共用默认值 `HOP`，且创建时就按白名单校验、不合格整单 400（见 `TaskTemplateLoader.DEFAULT_SCRIPT_TYPE`）。

**不引入 HTML/CSS 带来的额外成本（必须计入）**

既然客户端是纯桌面、不做 HTML 排版，报表就**失去了"HTML 模板 + 浏览器打印成 PDF"这条最省力的路线**：PDF 必须用 PDFBox 之类的库**逐块绘制**，表格跨页断行、页眉页脚、页码、图表嵌入都要自己算。上面 A 档 13 人日的导出工作量已按此估算，实际最容易超支的就是这一项 —— 建议一期**先交付"屏幕预览 + CSV/Excel 导出"，PDF 导出作为紧随其后的独立小项**。

**结论**：需求 3 是四项里**唯一有从零构建风险**的部分。建议一期只做 A 档并明确"模板是结构化配置，不是所见即所得设计器"。

---

### 3.4 需求 4：本地 ETL 调度管理

**后端就绪度：高，但控制面"只读为主 + 半成品"。**

已有 REST（全部挂在管理端口，`SchedulerController.java:77-111`）：`/scheduler/start`、`/status`、`/project/list`、`/project/{type}`、`/run/create`、`/run/list`、`/run/{id}`、`/run/{id}/{start|stop|abort|drop}`、`/run/{id}/task/{schedule|start|stop|abort|list|history}`、`/run/{id}/log`、`/run/{id}/variables`、`/describeCrontabExpr`。

模型完整度很高：任务字段含 `scriptType`(HOP/SHELL/COMMAND)、`runPolicy`(RUN_ONCE/CRONTAB/INTERVAL)、`failPolicy`(STOP/CONTINUE/RETRY)、`parallelPolicy`(PARALLEL/SERIAL_*)、`crontabExpr`(Quartz 6/7 段)、`interval`、`timeout`、`group`、`startupOrder`、`mandatory`、`retryTimes/Interval`、`variables`（`entity/SchedulerTask.java`）。元数据落 DuckDB `sysaux.v$scheduler_run|v$scheduler_task|v$scheduler_task_history`，**可用普通 SQL 直接查**（`meta/SchedulerMeta.java:35-80`）。变量分层清晰：全局 → 模板 → run → task，框架变量最高优先且业务不可覆盖（`runner/VariableSet.java:28-38`），口令类键按名字脱敏（`:88-109`）。日志按 `<workHome>/runs/<runId>/logs/<taskName>.log` 追加，可 tail 读取（`QuartzJob.java:276-283`）。

**必须在服务端补的接口（GUI 强依赖，`plugin-scheduler` 全模块无 PUT/PATCH/DELETE）**

| 缺失 | 影响 | 估计人日 |
|---|---|---|
| 任务 CRUD（增/改/删、启用停用、改 cron/策略/超时/组/顺序） | 没有它 GUI 只能"创建实例后只读" | 6 |
| run 更新（`configJson`/变量/项目绑定） | 变量改一次就得重建 run | 3 |
| 模板/项目 CRUD（列表之外的上传、下载、校验、建新项目） | 模板只在服务器文件系统上，GUI 无法交付模板 | 5 |
| 全局历史/执行流水（跨 run、分页、过滤） | 只能逐 run 查（`:829-838`） | 4 |
| 修正：`/status` 真实化、`threadCount` 生效、日志 follow/搜索 | 面板看起来"假" | 3 |
| ~~修正：`createTasksFromApi` 未写 `retryTimes/Interval/mandatory/variables`~~ **已修**（4 个字段已补齐并纳入创建期校验） | API 建的任务丢失一半能力 | 0 |
| **小计** | | **≈ 23 人日 ≈ 1.1 人月** |

**GUI 工作量**

| 功能点 | 人日 |
|---|---|
| Run 列表/详情/生命周期操作（start/stop/abort/drop）、项目模板浏览器 | 8 |
| 任务表格（含 `triggerState`/`nextFireTime`/`lastRetCode`/运行中标志）+ 组/顺序编排视图（时间线或泳道） | 12 |
| Cron 构建器（复用 `/describeCrontabExpr` 实时校验与未来 5 次触发时间） | 4 |
| 日志查看器（tail、自动滚动、关键字过滤） | 6 |
| 历史与变量面板（分层来源标注；口令键沿用服务端既有屏蔽显示 `******`） | 6 |
| 三态可视化（定义/调度/运行）+ 重启后"一键恢复启动"（含 RUN_ONCE 会重跑的警告） | 2 |
| ~~HOP 环境体检向导（原 5 人日）~~ | **0** |
| **小计（不含服务端补口）** | **≈ 38 人日 ≈ 1.8 人月** |

**难点**
- **状态语义**：运行态在内存，重启即丢且被降级为 STOPPED；GUI 必须把"上次状态"与"当前调度态"分开呈现，否则用户会误判。
- **HOP 外部依赖（已决策不做体检向导）**：GUI **从任何 API 都读不到** `plugin-scheduler.hop.*` 配置（它只在 `dbserver.conf` 的插件属性里），所以无法提前判断 HOP 可用性，只能在任务失败后从日志读到 `Hop 运行环境未配置`。幸好 `HopCommandBuilder` 的三种失败文案本身是明确的，不会"失败但没原因"，只是不提前；UI 侧的最低限度是**把失败日志文案可复制地呈现**（属日志面板既有功能）。廉价替代：插件加只读 `/scheduler/config`（≈1 人日，已列入可选池）。退路：HOP 用不了时任务可退化为 `SHELL/COMMAND`。
- 任务日志文件为**追加写**，同任务多次执行共用一个 `.log`（没有按次切分），日志面板需要按时间锚点切分呈现。

---

## 4. 技术选型：Java GUI 路线对比

| 维度 | A. 纯 Swing | B. 纯 JavaFX | C. Java 壳 + 内嵌浏览器 | D. Java 后端 + 浏览器 UI（对照） |
|---|---|---|---|---|
| 新增依赖 | 0（编辑器/图表另计） | OpenJFX（5 个平台 jar，可 jlink 裁剪） | JavaFX WebView（含在 OpenJFX）/ JCEF（Chromium，重）/ JxBrowser（商业） | 0（已有 `console.js`） |
| 结果网格 | `JTable` 自绘虚拟模型，最成熟可控 | `TableView` 够用，需自定义 cell | 交给 JS 表格（AG-Grid/Handsontable 有许可问题） | 同 C |
| SQL 编辑器 | 需 RSyntaxTextArea（BSD） | 同 A（Swing 控件可嵌入）或用 RichTextFX | CodeMirror/Monaco（现成，最强） | 同 C |
| 图表 | JFreeChart（LGPL） | 内置图表（无额外依赖） | ECharts（最好看） | ECharts |
| 复用现有 `console.js` | 不可 | 不可 | **可（WebView 直接加载 `/console.html`）** | **完全可** |
| 跨平台打包 | jar + JRE，或 jpackage | jpackage/jlink 成熟，需带 native | JCEF 需各平台 native（体积大） | 零客户端分发 |
| UI 自动化测试 | AssertJ-Swing | TestFX | Selenium（成熟） | Selenium/Playwright（成熟） |
| 长期维护成本 | 中（代码量大但可控） | 中 | **高（两套栈、桥接、版本耦合）** | 低 |
| 与本仓库风格契合 | 高（纯 Java、无前端构建链） | 高 | 低 | 中（需前端构建/静态资源进 jar） |
| 许可 | BSD/JDK | GPL+CE（OpenJFX 可商用） | JCEF BSD；JxBrowser 商业 | — |

**决策：B（纯 JavaFX 桌面），不使用内嵌浏览器。** 理由与落地细节：

1. JavaFX 内置图表（`javafx.scene.chart` 在 `javafx-controls` 里）→ 需求 3 的图表零额外依赖；`jpackage`/`jlink` 路线成熟 → 平台化成本可控；控件观感优于 Swing，桌面工具长期体验更好。
2. UI 技术栈单一：不引入 WebView/JCEF/JxBrowser，不产生"两套渲染栈 + 桥接"的长期维护成本，也不需要前端构建链。
3. **依赖可达性已实测通过**：`org.openjfx:javafx-controls:21.0.2:jar:win` 与 `com.fifesoft:rsyntaxtextarea:3.4.1` 均可从 aliyun 镜像下载（exit 0）。落地注意两点：按目标平台引入对应 classifier；以 module-path + `jlink`/`jpackage` 方式运行打包（配 `javafx-maven-plugin`），**不要打 fat jar**。
4. A 方案（Swing）作为**降级备选**保留：若某平台 JavaFX 打包受阻，Swing + 纯 JDK 网格可退守，代价是观感与打包体验变差。
5. C/D 方案（内嵌浏览器 / 浏览器 UI）**已排除**：确实能用 1/3 成本交付需求 1、2（现有 `console.js`），但与"纯桌面、不用 HTML/CSS"的既定前提冲突。它们的价值在于快速验证功能，可作为需求确认阶段的临时对照。

**M0 起即锁定的一组客户端依赖**

| 用途 | 选型 | 许可 | 说明 |
|---|---|---|---|
| UI 框架 / 图表 | OpenJFX 21.0.2（controls/base/graphics） | GPL+CE（可商用） | 图表内置；`TableView` 做虚拟化结果网格 |
| SQL 编辑器 | RichTextFX（`org.fxmisc.richtext`）或 RSyntaxTextArea | BSD-3 | 需求"语法高亮 + 行号 + 折叠"；RichTextFX 是 JavaFX 原生，优先 |
| PDF 导出 | Apache PDFBox | Apache-2.0 | 需内嵌 CJK 字体（复用仓库已有 `NotoSansSC-Regular.ttf`） |
| Excel 导出 | Apache POI（`poi-ooxml`） | Apache-2.0 | 体积较大，建议做成可选/按需加载；一期可只做 CSV/JSON |
| HTTP / WebSocket | **JDK `java.net.http`** | — | 零依赖：`HttpClient` + `WebSocket` 足够覆盖管理 REST 与 `/sql/ws` |
| JSON | fastjson2（仓库已有） | Apache-2.0 | 与仓库一致 |
| 图表库（备选） | JFreeChart | LGPL | 仅当 JavaFX 内置图表不够用时才引入，需注意合规声明 |

**明确不引入**：JCEF、JxBrowser、Electron、任何 ECharts/前端框架、iText（AGPL）、JasperReports（其设计器路线与"纯桌面"目标不匹配）。

---

## 5. 服务端改造清单（已获授权）

服务端可以自由改造，于是原方案里的"GUI 只能只读"这一约束解除。以下是**按优先级排序的改造清单**，每条都说明为什么 GUI 需要它、以及改哪里。

### 5.1 调度器（`plugin-scheduler`）—— 必做，GUI 强依赖

| # | 改造项 | 为什么需要 | 落点 |
|---|---|---|---|
| 1 | **任务写接口**：`POST/PUT/DELETE /scheduler/run/{runId}/task`、启用/停用、改 cron/策略/超时/组/顺序 | 当前全模块无任何 PUT/PATCH/DELETE，实例建完就改不动 | `controller/SchedulerController.java`，落到 `meta/SchedulerMeta.java` 的 upsert |
| 2 | **Run 更新**：`PUT /scheduler/run/{runId}`（`configJson`/变量/项目绑定） | 变量改一次就得重建 run | 同上传；`SchedulerMeta.updateRunStatus` 之外新增字段更新 |
| 3 | **模板/项目 CRUD**：列表之外支持上传、下载、删除、新建项目、读写 `defaultSchedulerTask.json` | 模板只在服务器文件系统上，GUI 无法交付模板 | 复用 `meta/TaskTemplateLoader` 的解析与校验 |
| 4 | **全局历史/执行流水**：`GET /scheduler/history?runId&taskName&from&to&page&size` | 现在只能逐 run 查（`SchedulerController.java:829-838`） | `sysaux.v$scheduler_task_history` 已有数据，加查询接口即可 |
| 5 | 修正 `/scheduler/status`（现在是硬编码常量）与 `threadCount` 参数（解析了但从不生效） | 面板显示"假状态"会误导运维 | `SchedulerController.java:128-142,159-170` |
| 6 | ~~修正 `createTasksFromApi` 未写入 `taskRetryTimes/Interval/mandatory/variables`~~ **已修**（4 个字段已补齐，且与模板共用同一份默认值/拍平/校验逻辑） | API 建的任务与模板建的任务能力一致 | `SchedulerController.createTasksFromApi` / `TaskTemplateLoader.flattenVariables` |
| 7 | 让 `taskScriptType=SQL` **真正可执行**（走 JDBC，替代当前的直接拒绝） | 默认值建出来的任务永远跑不起来 | `ProcessTaskExecutor.java:187-189` |
| 8 | 日志接口增强：按执行批次切分、支持 follow/关键帧过滤 | 现在同任务所有执行共用 `<taskName>.log`，追加写，GUI 无法按次呈现 | `QuartzJob.java:276-283` + `/scheduler/run/{id}/log` |
| 9 | （**已决策：不做**）Quartz `JobStore` 换 JDBC 持久化 | 单机应用 + 任务可反复运行，重启丢调度态不是数据风险；改为在 GUI 侧做"三态提示 + 一键恢复启动"补偿 | `quartz/DynamicQuartzScheduler.java:51` |
| 10 | （可选）执行线程池可配（现在固定 10） | 大批量 ETL 会被 10 并发卡住 | `PluginScheduler.java:95` |

### 5.2 `dbserver` 管理面 —— 建议顺手修（成本低、收益直接）

| # | 改造项 | 落点 |
|---|---|---|
| 1 | ~~`/status` 脱敏 `mcpLlmKey`~~ **已裁：不做**（控制台不展示即可；内部工具 + 无鉴权，脱敏无真实收益） | ~~`DBInstanceX.java:766`~~ |
| 2 | 备份可下载：`/download` 允许 `./backup`，并补 `/backup` 的列表/删除/恢复 | `DBInstanceX.java:513` vs `FileHandlerHelper.java:27` |
| 3 | 补 `/mcp/unregisterMCPSource` 别名（或改 `console.js:2198` 的拼写） | `McpServer.java:962` |
| 4 | 统一错误传输：`/api/unRegisterService` 的 502 纯文本 → JSON；未捕获异常 500 纯文本 → JSON 信封 | `APIService.java:628`、`DBInstanceX.java:640-643` |
| 5 | 文档与实现对齐（`pluginState` vs `state`、`/backup` 文件名前缀） | `README.adoc:536,3344-3357` |
| 6 | **WS `fetch` 补列类型（JDBC type name）、修"重复列名丢列"**（行改数组或加列唯一键） | `repl/SqlReplServer.java:550-558,634-640` —— 直接改善 GUI 网格 |
| 7 | （可选）`/status.sessions` 增补 WS/REPL 会话；新增 SQL 历史查询接口（`sysaux.SQL_HISTORY`） | `DBInstance.java:1399`、`DBInstance.java:1060` |
| 8 | （**已决策：本期不做**）管理端口 token 校验 | 开启会连带改客户端与现有 Web 控制台 `console.js`（它不发送 token 头），约 4 人日；代价由产品层承担：客户端强制只连回环地址 + 文档强提示 | `DBInstanceX.java:364-437` 的 filter 链 |

### 5.3 明确**不要**改的

- **不改 pgwire 协议语义、不改 `pg_catalog` 仿真行为** —— 现有 PG 客户端兼容性是核心资产，收益不抵风险。
- **不为 GUI 新增"元数据 REST"** —— JDBC `DatabaseMetaData` 已经足够，避免同一份真相维护两遍。
- **不把 GUI 做成服务端插件** —— 插件无法贡献静态资源、已注册路由无法注销（见 2.3 第 10 条）。

### 5.4 服务端改造的工作量与验收

| 改造包 | 人日 | 验收方式（沿用仓库测试风格） |
|---|---|---|
| 调度器写接口（1、2） | 9 | 对嵌入式服务器跑"建 run → 改任务 cron → 重启调度 → 断言下次触发时间"的端到端测试 |
| 模板 CRUD（3） | 5 | 上传→校验→建 run→断言任务数；非法模板必须报错而非静默跳过 |
| 全局历史（4） | 4 | 造 3 个 run 的历史，断言分页与过滤 |
| 状态与参数修正（5、6、7） | 7 | 断言 `/status` 反映真实 Quartz 状态；threadCount 生效；API 建的任务字段与模板一致；SQL 型任务能真正执行 |
| 日志增强（8） | 3 | 同一任务执行两次，断言可按批次分别读取 |
| dbserver 管理面修复（2–5；**脱敏已裁**） | 5 | 备份可下载；错误响应均为 JSON；`unregisterMCPSource` 别名可用 |
| WS `fetch` 列类型与重复列（6） | 4 | 断言 `SELECT 1 AS a, 2 AS a` 不丢列且列带类型名 |
| **必做小计** | **≈ 37 人日 ≈ 1.8 人月** | |
| 可选项（线程池可配、会话列表、SQL 历史接口） | 3 – 6 | 视需要排期（鉴权、脱敏均已裁，从可选池移除） |

> **已决策不做**：Quartz `JobStore` 持久化（省 4–6 人日）。改用 GUI 的"三态提示 + 一键恢复启动"补偿。

---

## 6. 推荐架构

### 6.1 模块划分（沿用仓库多模块风格）

```
slackerdb (parent)
├── common                  （已有）多播发现、日志、工具
├── dbserver / dbproxy      （已有）不被 GUI 修改
├── dbdriver                （已有）JDBC 驱动
├── plugin-scheduler        （已有）需补写接口（task/run/模板 CRUD）
└── ui/                     （新增）
    ├── slackerdb-client     ← 无 GUI 依赖的 Java 客户端 SDK（关键层）
    │     ├─ jdbc/           pgwire 连接、类型映射、游标分页、取消
    │     ├─ ws/             /sql/ws 会话、分页、取消、重连
    │     ├─ mgmt/           /status /backup /upload /download /viewLog /plugin
    │     ├─ dataservice/    /api/* 注册表 + 调用 + token/context
    │     ├─ mcp/            JSON-RPC 客户端 + /mcp 管理端点
    │     ├─ scheduler/      /scheduler/* + Quartz cron 校验
    │     ├─ instance/       进程启停、pid、日志跟随、多播发现
    │     └─ error/          统一错误模型（吸收 502 文本 / 200+retCode / 500 文本）
    └── slackerdb-ui         ← JavaFX 应用（只依赖 slackerdb-client）
```

### 6.2 运行时结构

```
┌──────────────────────────── slackerdb-ui (JavaFX) ─────────────────────────┐
│  工作台(SQL) │ 服务/MCP │ 报表 │ ETL 调度 │ 服务器/会话/日志/插件 │ 设置      │
└───────────────────────────────┬───────────────────────────────────────────┘
                                │  仅依赖
┌───────────────────────────────▼───────────────────────────────────────────┐
│  slackerdb-client (headless, 可单测/可被 CLI 复用)                          │
└───┬──────────────┬───────────────┬──────────────┬───────────────┬─────────┘
    │ JDBC/pgwire  │ REST(port_x)  │ WS /sql/ws   │ JSON-RPC /mcp │ 多播发现
    ▼              ▼               ▼              ▼               ▼
┌───────────────────────────────────────────────────────────────────────────┐
│  dbserver:  pgwire(port)  |  管理/数据服务(port_x)  |  plugins(scheduler)  │
└───────────────────────────────────────────────────────────────────────────┘
```

### 6.3 关键设计约定

1. **能力探测与降级**：启动时探测 `/status`、`/plugin/list`、`/scheduler/status`、`/mcp` 能力，按服务端实际能力显示/隐藏功能，而不是硬编码假设（例如未启用 `port_x`、未装载 scheduler 插件、未配置 HOP）。
2. **取数路由**：默认 JDBC；用户可切"异步执行（WS）"用于长查询；对数据服务 API 的调用只走 REST 并展示其 `cached` 标记。
3. **一切危险操作二次确认**：drop run、unregister service、KILL SESSION、插件卸载。
4. ~~**脱敏**~~ **已裁：不做脱敏**。做法改为"控制台不展示敏感字段、不写日志"（`/status.mcpLlmKey` 等照旧返回）；调度器 `variables` 的既有按 key 屏蔽**保留不撤销**（撤销是破坏性变更）。
5. **安全默认（重要）**：**本期不做鉴权**，所以产品层必须承担：客户端默认且**强约束只连 `127.0.0.1`/回环地址**；用户填非回环地址时弹确认并显示风险说明；文档与首次启动提示明确写"不要把 `port_x` 暴露到网络"（服务端在该端口上对注册服务、增删 MCP 工具、启停 ETL、备份/上传文件**全部不校验身份**，CORS 为 `anyHost`）。
6. **本地持久化**：连接配置、布局、编辑器历史存用户目录（`~/.slackerdb/ui/`），**不写服务器文件**；模板/服务定义的写操作一律走服务端 API。
7. **可测试性**：`slackerdb-client` 必须能在无 GUI 的 CI 中对真实 `dbserver` 跑集成测试（沿用仓库现有端到端测试风格，例如 `dbserver/src/test/java/.../CustomerEndToEndTest.java`）。

---

## 7. 工作量估算与路线图

### 7.1 汇总（人日 / 人月，1 人月 = 21 人日）

| 工作包 | 人日 | 人月 |
|---|---|---|
| P0 `slackerdb-client` SDK（5 类协议 + 统一错误 + 集成测试 + enforcer 依赖约束） | 45.5 | 2.2 |
| P1 SQL 工作台（3.1，**不含数据写回**） | 69 | 3.3 |
| P2 服务/MCP 管理（3.2，MCP 仅管理自身） | 40 | 1.9 |
| P3 ETL 调度管理 GUI（3.4，含三态提示与一键恢复，**不含 HOP 体检向导**） | 38 | 1.8 |
| P4 **服务端改造**（第 5 节必做项） | 38 | 1.8 |
| P5 报表（3.3，含声明式格式层与富文本段） | 60 | 2.9 |
| P6 平台化：应用壳/设置/i18n/主题/异常上报 | 15 | 0.7 |
| P7 打包分发：jpackage 独立安装包、文档 | 22 | 1.0 |
| P8 测试与 CI（GUI 冒烟 + 端到端 + 回归） | 20 | 1.0 |
| **合计** | **≈ 347.5** | **≈ 16.6** |

区间：**12 – 18 人月**（乐观=单平台、服务端改造顺利；悲观=报表要设计器、三平台打包、网格反复打磨、服务端接口返工）。
两人并行可压到 **9 – 12 个月日历时间**（P0 是共同前置，之后 P1 与 P2/P3/P4 可并行）。

### 7.2 分期路线（已按"服务端可改 + MCP 仅自身 + 纯桌面"重排）

| 阶段 | 交付 | 人月 | 验收标准 |
|---|---|---|---|
| **M0（2–3 周）** | 技术验证：`ui/slackerdb-client` + `ui/slackerdb-ui` 双模块落地；JavaFX 模块化骨架与 `jpackage` 冒烟；JDBC / WS / 管理 REST 三通道打通并对嵌入式服务器跑通集成测试 | 0.5 | `mvn test` 绿；窗口内执行 SQL 并分页展示 10 万行不卡 |
| **M1（MVP，3–4 个月）** | P0 + P1 精简（编辑器 + 网格 + 元数据树 + CSV/JSON 导出，**只读、仅 JDBC 通道**）+ P2 全部（服务/MCP 仅自身）+ P3 只读监控（run/任务/日志/历史） | **4 – 6** | 能替代 DBeaver 做日常查询（**含日常 DML，但走编辑器**）；服务与 MCP 日常运维在 GUI 内完成；调度可视化可看 |
| **M2（+2–3 个月）** | 批次 1 服务端改造全部落地 + P1 补异步执行面板（WS）/会话管理/Excel 导出/EXPLAIN 树 + P3 写操作（任务增改删、cron 编辑、一键恢复启动）+ 安装包 | **4 – 5** | ETL 全生命周期（建模板→建 run→改任务→启停→看历史）在 GUI 内闭环；有可安装的 Windows 包 |
| **M3（+2–3 个月）** | P5 报表 A 档：结构化模板 + JavaFX 图表 + 屏幕预览 + CSV/Excel 导出；随后补 PDFBox 的 PDF 导出 | **2.5 – 3.5** | 可交付"数据 + 图表 + PDF"的本地报表；报表刷新可挂调度任务 |
| **M4（可选）** | 报表可视化设计器、三平台打包、自动更新、管理端口鉴权 | 4 – 10 | 视业务是否需要再定 |

### 7.3 对照：被排除的浏览器 UI 路线（仅留档）

| 工作包 | 人月 |
|---|---|
| 复用 `console.js` 覆盖需求 1 骨架 + 需求 2 | 0.5 |
| 网格/编辑器升级（Monaco + 虚拟表格）+ 数据编辑 | 2 – 3 |
| 需求 4 调度面板（含服务端补口） | 1.8 |
| 需求 3 报表（ECharts + HTML 排版 + 服务端导出） | 1.5 – 2 |
| 服务端静态资源托管/权限/打包 | 0.5 |
| **合计** | **5 – 9 人月** |

**该路线已排除**（需求方明确要求纯桌面、不使用 HTML/CSS），此处仅作为成本对照留档：它确实能用约 1/3 的成本交付需求 1、2，但代价是双技术栈、长期维护成本高，且本方案的报表/图表要额外依赖服务端渲染。

---

## 8. 风险清单与对策

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| 1 | **服务端无鉴权**，管理端口能力全开 | 高 | **已决策本期不做鉴权** → 产品层强制：客户端默认且强约束只连回环地址、非回环需二次确认；文档与首启提示强警告；将来要补时按"可选校验 + 默认关闭"设计（同时需改 `console.js`） |
| 2 | 需求 3 从零构建，范围易失控；且不用 HTML/CSS 后 PDF 排版要自研 | 高 | 一期只做 A 档：先交付屏幕预览 + CSV/Excel，PDF 导出作为紧随其后的独立小项；设计器单独立项 |
| 3 | 服务端改造引发既有行为回归（pgwire/调度语义） | 中高 | 改造限定在 5.1/5.2 清单内，**不碰 pgwire 与 pg_catalog**；每项都要有端到端测试（仓库已有此风格） |
| 4 | 结果网格性能（百万行 / 大字段） | 中高 | JDBC 游标 + 窗口化取数；单值截断阈值；禁止全量入内存；**M0 就压测** |
| 5 | WS 通道无类型、重复列名丢列 | 中 | 以 JDBC 为主通道；WS 只用于长查询；服务端修复已列入 5.2(6)（P4） |
| 6 | 新依赖的平台适配（OpenJFX classifier、模块化运行、POI 体积） | 低 | 镜像可达性已实测通过；按平台引入 classifier + `javafx-maven-plugin`/`jlink`，避免 fat jar；新依赖统一进父 pom 的 `dependencyManagement` |
| 7 | HOP 外部安装、环境差异 | 中 | **已决策不做体检向导** → UI 呈现失败日志文案（可复制）；可选池里有廉价替代（只读 `/scheduler/config`，≈1 人日）；退路是任务退化为 `SHELL/COMMAND` |
| 8 | 调度器运行态易失（RAMJobStore、重启降级） | 中 | **已决策不做持久化**（单机应用、任务可反复运行）；改为 UI 侧补偿：三态可视化 + "检测到 N 个实例丢失调度"横幅 + 一键恢复启动（恢复时明确警告 RUN_ONCE 会重跑） |
| 9 | 服务端小毛病污染体验（502 文本、200+retCode、备份下载失效、`unregisterMCPSource` 拼错） | 中 | 客户端 SDK 统一收敛（M0 已按此实现），同时按 5.2 清单在服务端修掉 |
| 10 | GUI 测试成本 | 中 | 把 90% 逻辑放在 `slackerdb-client`（普通 JUnit 集成测试），GUI 只做冒烟 |
| 11 | 三平台打包（native 依赖、jpackage/WiX） | 中 | 一期只出 Windows 安装包 + 绿色版；macOS/Linux 后置 |
| 12 | i18n（仓库注释/文档以中文为主，README 双语） | 低 | 资源包 + 中英双语；错误码映射服务端 `message.properties` |
| 13 | 许可证合规 | 低 | OpenJFX(GPL+CE)、RichTextFX/RSyntaxTextArea(BSD)、PDFBox/POI(Apache)、JFreeChart(LGPL 需声明)；**避免** JxBrowser(商业)、iText(AGPL) |

---

## 9. 已确认的决策与剩余待定项

**已确认（本文按此定稿）**

| # | 决策 | 结论 |
|---|---|---|
| 1 | 服务端是否可改 | **可以自由改造** → 第 5 节清单纳入实施范围（必做 ≈38 人日） |
| 2 | "MCP 本地管理"含义 | **仅管理 SlackerDB 自身**的工具/资源/服务；宿主式纳管不做 |
| 3 | 客户端形态 | ⚠️ **复议中**：原"纯桌面 JavaFX"被否决 → 改为评估 **Vaadin**（本机 Web UI，接受"SQL 编辑器 + 图表"两处 JS 包装件） |
| 4 | 数据写回（网格编辑） | **一期、二期都不做**；修改数据回到 SQL 编辑器写 DML（省 15 人日） |
| 5 | 调度器的 SQL 型任务 | **修成真正可执行**（`ProcessTaskExecutor` 走 JDBC 分支，+2 人日） |
| 6 | Quartz 调度持久化 | **不做**（单机应用 + 任务可反复运行）；改用 UI 一键恢复（省 4–6 人日，UI +2 人日） |
| 7 | 报表模板格式 | **JSON + `$schema` 版本号** |
| 8 | 报表能力边界 | **不做表达式语言**，改为声明式格式化 + 条件高亮/条件显示（+5 人日）；**不做屏幕分页**；块类型 = 7 种基础 + **富文本段**（+3 人日） |
| 9 | PDF 导出时序 | 报表以**屏幕查看为主** → PDF 后置（13 人日，排在 R3） |
| 10 | 报表编辑器 | 一期**表单化编辑**（15 人日）；可视化设计器留 M4 可选 |
| 11 | 取数通道 | **两通道都做**，但 **M1 只用 JDBC，异步执行面板放 M2** |
| 12 | 服务端改造推进方式 | 按批次 1/2/3；**批次 1 的接口契约先冻结 + 服务端提供桩响应**，两边并行开发 |
| 13 | 管理端口鉴权 | **本期不做**；产品层强制本机绑定 + 文档强提示 |
| 14 | 调度 UI 三态 + 一键恢复 | **做**（定义态/调度态/运行态三态可视化 + 重启后批量恢复，恢复前警告 RUN_ONCE 会重跑） |
| 15 | HOP 环境体检向导 | **不做**（省 5 人日）；代价：GUI 无法提前感知 HOP 未配置，只能靠任务失败日志；廉价替代（只读 `/scheduler/config`，≈1 人日）在可选池 |
| 16 | 依赖方向约束 | ⚠️ **复议中（改写）**：不再做跨模块 enforcer，改为"应用内 access 包禁止 import Vaadin/Javalin"，用 ArchUnit 守住（+0.5 人日） |
| 17 | GUI 发行包 | ⚠️ **复议中**：形态取决于部署拓扑 —— 独立 UI 进程 = jar + 启动器（可选 jpackage app-image）；嵌入 dbserver = 该决策作废 |
| 18 | M0 验证范围 | ⚠️ **已改版**：① Vaadin 24 + Spring Boot 3.5 内嵌容器 + 生产构建（Vite）跑通；② Grid 懒加载 + JDBC 游标分页（10 万行帧率/内存）；③ CodeMirror/ECharts 包装件集成；④（若走嵌入拓扑）Javalin 的 Jetty 能否挂载 `VaadinServlet` |

**剩余待定（都不阻塞开工，但需要在对应里程碑前给答案）**

| # | 待定项 | 选项 | 影响 | 需要给答案的时间 |
|---|---|---|---|---|
| 1 | 目标平台 | Windows 单平台 / 三平台 | 打包工作量差 0.5–1 人月；也影响 JavaFX classifier 策略 | M2 打包前 |
| 2 | 数据规模上界 | 单结果行数 / 列数 / 单值大小（建议先内部定 100 万行 / 50 列 / 1 MB 截断） | 决定网格虚拟化与截断策略 | **M0 之前**（M0 要压测网格） |
| 3 | 可选池做不做 | 只读 `/scheduler/config`（1 人日）、WS `fetch` 补列类型（4 人日）、线程池可配、会话列表、SQL 历史接口 | 3–6 人日，按需 | M2 期间 |
| 4 | 报表可视化设计器（M4） | 做 / 不做 | +60～130 人日 | M3 结束后 |
| 5 | **M0 是否放行写代码** | 放行 / 暂不放行 | M0 是"能不能做"的验证，不做就等于把风险推到 M1 | 现在（决策已定，只差授权） |

---

## 10. 一句话总结

SlackerDB 的**服务端已经把 4 项需求中 3 项的后端做完了**（SQL 通道、服务/MCP 管理、ETL 调度），因此这个纯 JavaFX 桌面平台在本质上是**"客户端工程 + 一个从零开始的报表子系统 + 一批服务端接口补齐"**的组合：需求 2 最容易（≈2 人月），需求 1 最费工（3.3 人月，且不做写回后目标收敛为"日常查询够用"），需求 4 中等但依赖第 5 节的服务端改造（已获授权，≈1.8 人月），需求 3 最难（2.9 人月，已砍掉表达式语言与设计器）。**计划内总量 ≈348 人日 ≈ 16.6 人月（区间 12–18），MVP 4–6 人月。**控制风险的两条主线是：先立 `slackerdb-client` 无 GUI 客户端层，以及 M0 就把 JavaFX 打包链路、10 万行网格、JDBC 游标分页这三件"能不能做"的事打穿。
