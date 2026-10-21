# 模块间协调：dbserver / dbproxy / dbcli / console 各该做什么

> 定位：这是在**新模块动工之前**要定的架构契约。回答"整体项目里，各模块为这个平台做什么配合更合适"。
> 依据：已核实的代码事实（文中给 `文件:行号`）+ v2 定稿方案（[javagui-platform-plan-v2.md](javagui-platform-plan-v2.md)）。
> 结论先行：**平台需要的配合量不大（M0/M1 必需 ≈14 人日），但有几处"信息没有出口"的缺口必须先补，否则控制台只能靠猜。**

---

## 0. 五条原则

1. **契约住在 `common`，实现留在各模块**。共享"词汇表"（实例描述、发现公告、错误信封、apiVersion）与"测试夹具"，**不共享客户端实现** —— 这与仓库既有惯例一致（`dbcli` 就因包私有而放弃复用 `plsql` 求值器、改为自研）。
2. **每类信息只有一个真相源**：服务端运行时状态 → `dbserver`；代理路由与后端健康 → `dbproxy`；脚本/断言/作业语义 → `dbcli`；界面 → `console`。其他模块只读，不复制。
3. **管理面只讲一种协议**：HTTP + WebSocket。自定义 TCP 管理协议（`STOP`/`STATUS`/`KILL`，跑在 pgwire 端口上）保留给 CLI/运维脚本，**新客户端不碰**。
4. **平台是唯一 UI**：`dbserver` 的 `console.html` 与 `dbproxy` 的 `index.html` 降级为演示/占位，不再演进。
5. **能力自描述**：服务端与插件**声明**自己提供什么（`/capabilities` + 插件贡献位），客户端不要靠"探测一堆端点然后猜"。

---

## 1. 契约归属表（谁拥有、谁消费）

| 契约 | 真相源（Owner） | 消费者 | 现状 |
|---|---|---|---|
| pgwire 协议、`pg_catalog` 仿真 | `dbserver` | `dbdriver`（客户端驱动）、`dbcli`、`console` | ✅ 已有 |
| **管理 API 形状**（`/status`、错误信封、`apiVersion`） | `dbserver` | `dbproxy`（应复用同一形状）、`console` | ⚠️ 形状未版本化、错误有 5 种表达 |
| **实例/发现公告**（UDP JSON：`sd/type/name/alias/host/port/portX/ver/seq/ts`） | `common`（`Announcement`，`PROTOCOL_VERSION=1`） | `dbproxy` 监听、`console` 应监听 | ⚠️ **只有 dbserver 发**，无角色字段 |
| 数据服务定义（`.service` / 注册表） | `dbserver`（`APIService`） | `console`、`dbcli`（可调用） | ✅ 已有 |
| MCP JSON-RPC（2026-07-28 + 管理端点） | `dbserver`（`McpServer`） | `console` | ✅ 已有 |
| 调度器 REST（run/task/编排/历史/日志/变量） | `plugin-scheduler`（**ETL 唯一真相源**） | `console`、可选 `dbcli --scheduler` | ⚠️ 写接口缺（批次 1/2）；状态/能力/就绪缺声明（见 §2.5） |
| **结果类型名 / NULL 表示 / 语句切分 / CSV 转义** | `dbcli`（既有规范） | `console` | ⚠️ 无共享夹具，易漂移 |
| 脚本 / 断言 / `_JOB` 语义 | `dbcli` | —（产品分工，不共享） | ✅ 已定不复用 |
| 连接配置（profiles） | **待建：`common` 定格式** | `dbcli`、`console` | ❌ 不存在，两边各存一份会分叉 |
| UI/交互 | `console` | — | 新建 |

---

## 2. `dbserver` 该做的配合

### P0：M0/M1 必需（≈6 人日）

| # | 项 | 为什么平台需要它 | 落点 |
|---|---|---|---|
| 1 | **`GET /capabilities`**：`{apiVersion, serverVersion, features:{dataService,mcp,scheduler,plugin}, ports:{pg,portX,enabled}, accessMode, discovery:{sd}}` | 控制台现在要探测 `/status`+`/plugin/list`+`/scheduler/status`+`POST /mcp` 四个点再推断能力，判断还会出错（例如 `port_x` 关闭时插件拿到 null Javalin 直接 NPE） | `DBInstanceX` 新路由；`plugin-scheduler` 通过贡献位补充 |
| 2 | **统一错误信封**：HTTP 状态与 JSON 一致（`{retCode, retMsg, errorClass}`），去掉 502/500 纯文本 | 5 种错误表达是控制台/`dbcli`/第三方都要各自适配的东西 | 已在评估第 5 节批次 1，此处只是把它提到"平台契约"的高度 |
| 3 | **会话可辨识**：`sessions[]` 增加 `type`（pgwire/ws/plugin）与 **`applicationName`** | 控制台要能回答"这是我自己那条连接吗？"否则误杀自己有风险；目前 `sessions` 只含 pgwire（`DBInstance.java:1399`） | `DBInstanceX.getStatusJson`；`application_name` 目前服务端**根本没读**（只有 `SQLReplacer` 里的正则）。**脱敏不做**（已裁）：`mcpLlmKey` 等字段照旧返回，控制台不展示即可 |
| 4 | **备份资源化**：`/backup` 列表/删除/下载（修 `/download` 只允许 `./downloads` 而备份写 `./backup` 的错配） | 平台里"备份"是一等操作，现在只能建不能取 | `DBInstanceX`、`FileHandlerHelper` |

### P1：M2 前后（≈5 人日）

| # | 项 | 为什么 | 说明 |
|---|---|---|---|
| 5 | **HTTP 版杀会话**：`POST /sessions/{id}/kill` | UI 不该用 TCP 管理协议；且 `KILL` 目前靠 `AdminClientRequest` 的文本命令 | TCP 版保留给 CLI；HTTP 版走同一条 `killSession` 路径 |
| 6 | **插件贡献声明**：`/plugin/list` 增加 `contributes:{ etl:true, routes:["/scheduler"], configurable:true }`，由插件通过新 SPI 上报 | 控制台的 ETL 菜单应由"插件声明"决定，而不是探测 `/scheduler/status`（那个还硬编码返回常量） | `plugin` 模块加 `DBPlugin.contributes()` 默认空实现 |
| 7 | **`GET /health`** 轻量存活 | 启动器/控制台轮询用；`/status` 会构造完整状态（扫会话、插件、CPU），不适合每 2 秒调 | 独立路由，不触发重逻辑 |

### P2：可选（≈3 人日）

| # | 项 | 说明 |
|---|---|---|
| 8 | **发现公告扩字段**：`role`(server/proxy)、`data`、`accessMode`、`portXEnabled`；`sd` 升 2，解析兼容 1 | 控制台靠它直接建"实例树"，不用逐个 probe；`Announcement` 解析已是"缺字段给默认值"的风格，向后兼容成本低 |
| 9 | **插件配置只读**：`GET /plugin/{id}/config`（不脱敏，原样返回） | 通用性：任何插件配置都可读，控制台不必为每个插件写代码；但 HOP 就绪已由 `J-1` 覆盖，故降为可选 |
| 10 | 审计查询接口（`sysaux.SQL_HISTORY` / `API_HISTORY`） | 也可以让控制台直接用 JDBC 查，**接口版更好**（不必给控制台开 SQL 权限语义） |

> **不要做**：不要给控制台加"UI 专用聚合接口"。上面每一项都是**通用资源**（能力、会话、备份、插件配置），任何客户端都该能用。

---

## 2.5 `plugin-scheduler`（ETL 调度插件）该做的配合

**两个身份决定了它的配合方式**：① 它是 ETL 语义的**唯一真相源**（run/task/编排/历史/日志/变量都在它这里）；② 它是**可缺席**的 PF4J 插件（控制台在它没装、没启用、`port_x` 关闭时必须照常可用）。因此它要做的是"**自描述 + 只做自己的事 + 可安全缺席**"。

**现状短板（已核实）**：`/scheduler/status` 返回硬编码常量；`threadCount` 解析后从不生效；~~`createTasksFromApi` 漏写 `retryTimes/interval/mandatory/variables`~~（**已修**：4 个字段已补齐并与模板共用默认值/拍平/校验）；SQL 类型任务被执行器直接拒绝（SQL 非任务类型，写进 HOP 工作流的 SQL action）；任务日志同任务共用一个追加写文件；`plugin-scheduler.*` 配置（含 HOP）**没有任何读接口**；`port_x` 关闭时 `onStart` 直接 NPE。

### P0：M0/M1 必需（**新增 ≈4 人日**，不含已计入 P4 的写接口）

| # | 项 | 为什么平台需要 | 成本 |
|---|---|---|---|
| 1 | **`GET /scheduler/capabilities`**（或经插件贡献位）：`{contractVersion, features:{runCrud,taskCrud,templates,globalHistory,sqlTask}, quartz:{started,threads}, home, projectHome, hop:{ready,reasons[]}}` | 控制台的 ETL 菜单与按钮**由声明决定**，不靠探测；`features` 让控制台兼容不同版本的调度器（老版本自动降级） | 1.5 |
| 2 | **三态字段显式化**：`task/list` 里把 `definitionState` / `triggerState` / `runtimeState` 分开命名（现在把 DB 字段、Quartz 触发器、内存快照混在一个响应里） | 控制台的三态 UI（定义/调度/运行）**必须**有权威来源，否则只能靠猜字段含义 | 1 |
| 3 | **`lostSchedules[]` 权威源**：status 或 `GET /scheduler/state/lost` 返回"定义还在、调度已丢"的 run 列表 | "重启后一键恢复"的横幅要有服务端判定，**不该让控制台 diff 两个端点自己推断** | 1 |
| 4 | **HOP 就绪判定下沉**：`hop:{ready:false, reasons:["runScript 未配置","示例项目缺 local.json"]}` | 这比"控制台读服务器文件"或"GUI 体检向导（5 人日）"都便宜，而且 `dbcli` 也能复用同一判定 | 含在 1.5 里 |
| 5 | **未启用管理口时优雅降级**：`port_x` 关闭时不启动并 warn，不 NPE | 平台里"控制台连不上"应该是配置问题，不是插件把服务端启动路径打崩 | 0.5 |

### P1：M2（**已计入 P4 的 38 人日，不重复计**）

任务 CRUD、run 更新、模板/项目 CRUD、全局历史、真实 `/status` 与 `threadCount` 生效、~~`createTasksFromApi` 字段补全~~（已修）、SQL 类型任务走 JDBC 执行（已决定不做：SQL 写进 HOP 工作流）、日志按执行批次切分 —— 这些是控制台 ETL 面板的硬依赖，排在批次 1/2。

### P1 新增（≈4 人日）

| # | 项 | 为什么 | 说明 |
|---|---|---|---|
| 6 | **整包导出/导入**：`GET /scheduler/project/{type}/export`（zip）、`POST .../import` | 平台需要"交付一个 ETL 工程"；整包比文件级 CRUD 更安全（无路径穿越面）也更便宜 | 可**部分替代**细粒度模板接口，净成本可能更低 |
| 7 | **轮询减负**：`task/list`、`history` 支持 `since=<seq>` 或 ETag | 控制台要 2–5 秒轮询运行态，全量响应在任务多时浪费明显 | 1 |
| 8 | ~~脱敏契约化~~ **取消** | 需求方裁决：内部工具不做脱敏；`variables` 既有的按 key 屏蔽**保留不撤销**（撤销是破坏性变更） | 0 |

### 不要做

- 不自带 UI 静态资源、不自带鉴权（沿用服务端信任模型）；
- 不写 `home`/`projectHome` 之外的文件；
- **不用 WebSocket 推送**：v1 用轮询 + `since` 足够；插件里挂长连接会增加 Javalin 路由生命周期风险（插件路由不可注销，见 `README.adoc:2841,3043`）；
- 不成为控制台的编译期依赖（控制台必须在它缺席时可用）。

### 与 `dbcli` 的关系

`_JOB`（脚本作业）与 `plugin-scheduler`（常驻调度）的分工已在 v2 第 4.5 节定：**只做文档/文案分工，不做技术统一**。可选增强（后续）：`dbcli --scheduler` 直接驱动这套 REST —— 那会让**调度契约成为产品 API**，同时服务控制台与 CI 脚本。

---

## 3. `dbproxy` 该做的配合（≈4 人日）

**现状（已核实）**：`dbproxy` 的管理口是个**全捕获前缀转发器** —— `GET/POST/PUT /*` 按路径首段匹配 `forwarderPathMappings` 的别名，转发到对应后端的**管理 API**（`ProxyInstanceX.java:166-204`），**没有自己的 `/status`**，`web/index.html` 是 Hello World 占位；多播方面它是**监听方**，自己不发言。

| # | 项 | 为什么平台需要 | 备注 |
|---|---|---|---|
| 1 | **自己的 `/status` + `/capabilities`** | 平台要回答"代理活着吗、连着哪些后端、路由表是什么"，现在它只有转发 | 注意路由注册顺序：**必须在 `/*` 之前**注册，否则被通配吞掉 |
| 2 | **`GET /proxy/instances`**：`[{alias, backendHost, backendPort, state, lastError, activeSessions}]` | 控制台视图里代理应显示为"一个带下游的节点"，而不是一个黑洞 | 复用 `forwarderPathMappings` |
| 3 | **让 `dbproxy` 也发多播公告**（`role=proxy`） | 否则平台无法自动发现代理，只能手填地址 | 直接复用 `common.Announcement`，与第 2 节第 8 项一起做最省 |
| 4 | **把"前缀转发"写成契约** | 若控制台想"通过代理管多后端"，必须知道别名规则；写进 `/capabilities.managementForwarding` | 例如 `{enabled:true, prefixMode:"alias"}` |
| 5 | `web/index.html` 降级为占位/删除 | 原则 4：平台是唯一 UI | — |

> **边界**：不改 `dbproxy` 的 pgwire 转发与取消路由逻辑（`CancelRouter`）；代理不该长出插件系统或 UI。

---

## 4. `dbcli` 该做的配合（≈2 人日，且**不要求它变成库**）

| # | 项 | 为什么 | 成本 |
|---|---|---|---|
| 1 | 连接时设置 **`application_name = slackerdb-dbcli/<ver>`**（控制台设 `slackerdb-console/<ver>`） | 配合服务端第 3 项：会话列表能辨识来源，控制台不会误杀自己/CLI 的会话 | 极小 |
| 2 | **共享连接配置格式**：`common` 定 `profiles.json`（name/host/port/portX/database/user/options），`dbcli` 加 `--profile`，控制台"连接管理"读写同一份 | 否则同一个产品里会有两套"怎么连"的描述，用户要在两处各填一遍 | ≈1 人日 |
| 3 | **结果导出规范统一**（CSV 引号/NULL/日期/编码）→ 一页规范 + 两侧各一组夹具测试 | 同一个查询在 CLI 和 GUI 里导出出不同字节，是会后患无穷的细节 | ≈0.5 人日 |
| 4 | **一致性夹具落到 `common` 的 test-jar**：类型名 / NULL 表示 / 语句切分 / CSV 转义 四组用例，`dbcli` 与 `console` 的测试**都读同一份** | 这是"不共享生产代码、但防止行为漂移"的标准做法（Maven `test-jar`），比复制注释可靠 | ≈0.5 人日 |
| 5 | **不做**：不改 `dbcli` 的 `sql`/`engine`/`job`/`ssh` 实现，不为控制台抽公共模块 | 与 R3 决定一致；`dbcli` 有自己的并行工作流 | 0 |

> 可选（不进本期）：让 `dbcli` 支持 `--scheduler` 走 `/scheduler/*`，这样**同一套 REST 契约**同时服务控制台与 CI 脚本。这是"契约成为产品 API"的正向例子，但属于后续增强。

---

## 5. `common` 的落点（≈3 人日）

`common` 应该成为**契约与夹具的家**，而不是客户端实现的家：

| 加什么 | 用途 |
|---|---|
| `InstanceDescriptor`（`kind=server|proxy`、`name`、`host`、`port`、`portX`、`data`、`accessMode`、`state`、`version`） | 发现公告、`/status`、控制台的实例树**共用一个模型** |
| 发现公告 `sd=2`（加 `role`/`data`/`accessMode`/`portXEnabled`），解析兼容 `sd=1` | 第 2 节第 8 项 + 第 3 节第 3 项 |
| `ApiVersion` 常量 + 错误信封 POJO | 第 2 节第 1、2 项 |
| 连接配置 `profiles.json` 的模型与读写 | 第 4 节第 2 项 |
| **`test-jar`：一致性夹具** | 第 4 节第 4 项 |

**明确不放**：任何 HTTP/WS 客户端、任何 SQL 执行实现。

---

## 6. `console` 自己的边界（承诺不越界）

1. 只走协议（HTTP/WS/JDBC + 多播发现），**不依赖** `dbserver`/`dbproxy`/`dbcli` 的 Java 类。
2. 不要求服务端托管 UI 静态资源、不为 UI 加插件 SPI。
3. 不替服务端执行调度、不自己算集群状态（只呈现真相源）。
4. **不改 `dbcli`**（需要改就提"改造请求"）。
5. 报表的取数走 JDBC 或数据服务 API，不新增"报表专用服务端接口"。

---

## 7. 版本与兼容矩阵

| 契约 | 版本载体 | 兼容策略 |
|---|---|---|
| 管理 API | `apiVersion`（`/capabilities`） | 控制台声明 `requiresApiVersion >= N`；不匹配时**隐藏对应功能**并提示，不崩 |
| 发现公告 | `sd`（`common.Announcement.PROTOCOL_VERSION`，现 1） | 升 2 时双向容忍；未知字段忽略（解析已按"缺字段给默认值"实现） |
| 数据服务定义 | `serviceVersion`（已有）+ 文件格式 | 未知字段忽略 |
| 报表模板 | `$schema`（`slackerdb/report/v1`） | 未知版本给"由更新版本创建"提示 |
| 插件贡献 | `pluginContractVersion` | 插件未声明时按"未知能力"处理（隐藏菜单，不报错） |
| 支持矩阵 | 一处文档（本项目 `docs/`）+ 启动器检查 | 控制台与 server/proxy 的兼容范围写清楚 |

---

## 8. 构建与发布协调（一个容易踩的坑）

- **Node 只能出现在 `console` 模块里**。若把 `ui/slackerdb-console` 直接加进 root `<modules>`，则任何人跑 `mvn package` 都会触发 npm/Vite 构建（首次要下载几百 MB 前端依赖），**10 个既有模块的构建体验会被拖垮**。
- 建议：**profile 门控** —— 默认构建不含控制台；`mvn -Pconsole` 才加入。CI 分两条流水线（`build` 与 `build-console`）。
- 产物分发：`dbserver`/`dbproxy`/`dbcli` 继续进 `dist/slackerdb-dist-*.zip`；`console` 独立（jar + 启动器，可选 app-image 带 JRE），**不塞进**服务端 zip。
- 版本：`console` 版本独立，但在产物里声明兼容的 `apiVersion` 与 `serverVersion` 范围。

---

## 9. 最小配合集（M0/M1 必需）与总账

> 逐条接口形状、JSON 样例与验收标准已冻结在 **[javagui-server-contract.md](javagui-server-contract.md)**。本节只给账。

| 模块 | 新增项（桶 B） | 人日 |
|---|---|---|
| 契约与夹具 | `docs/contract/*.json` + 两侧契约测试骨架（W0） | **2** |
| `dbserver` | `/capabilities`、`/health`、会话可辨识（`application_name`）——**不含脱敏（已裁）** | **3** |
| `plugin-scheduler` | `/scheduler/capabilities`（含 HOP 就绪）、三态显式化、`lostSchedules`、优雅降级 | **4** |
| `dbproxy` | 自己的 `/status` + `/capabilities`（+ 后端列表） | **3** |
| `dbcli` | `application_name`、共享 `profiles.json`、消费夹具 | **2** |
| `common` | `InstanceDescriptor`、apiVersion/错误信封、profiles 模型 | **3** |
| **桶 B 合计** | | **≈17 人日 ≈ 0.8 人月** |
| 桶 A（**已在** P4 里） | 调度器写接口 28 + `dbserver` 管理面修复 5（备份下载、错误传输、别名、文档；**脱敏已裁**）+ WS 列类型 4 = **37** | （不重复计） |

**去重说明**：桶 A 与桶 B 曾有三项重复（`/status` 脱敏、备份可下载、错误传输统一）→ 已归入桶 A；**脱敏整项取消**后桶 A 由 38 降为 37、桶 B 由 18 降为 17。

**服务端侧总计 ≈54 人日**：桶 A 37（已含在控制台 **304** 人日里）+ 桶 B 17（需单列）。

后续可选（`dbproxy` 发公告、公告 `sd=2`、插件配置只读、审计接口、调度器 project 整包导入导出与 `since` 轮询）**≈10 人日**，排 M2/M3。

> 桶 B 的 17 人日**不在**控制台 304 人日里，属服务端侧配合成本，建议单列并与 M0 并行（控制台 M0 的 `/status`/JDBC 探测不依赖它们）。

---

## 10. 三条"不要做"（防止架构腐化）

1. **不要**把控制台做成 `dbserver` 的内置页面或插件 —— 那会重新绑死版本节奏（T1 已定）。
2. **不要**给某个界面造"UI 专用接口" —— 平台需要的一切都应该是通用资源（能力/会话/备份/插件配置/审计）。
3. **不要**为了跨模块复用去搬 `dbcli` 的代码进 `common` —— 只搬**契约与夹具**（第 4、5 节），代码留在各自模块。
