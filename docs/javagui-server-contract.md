# 服务端配合契约（批次 1 冻结稿）

> 用途：这是控制台动工前要冻结的**服务端配合契约**。控制台按它写 `access` 层，服务端按它实现，两边并行、互不阻塞。
> 配套：[javagui-architecture-coordination.md](javagui-architecture-coordination.md)（为什么这么分工）、[javagui-platform-plan-v2.md](javagui-platform-plan-v2.md)（总体方案）。
> 状态：**待评审**（评审通过即冻结；之后只允许"只增不改"）。

---

## 0. 先理清账：服务端配合到底是多少人日

之前的口径有一处**重复计算**：`/status` 脱敏、备份可下载、错误传输统一这三项，在 P4（服务端改造批次 1/2，已含在控制台的人日预算里）已经算过，不该在"配合成本"里再算一遍。理清后：

| 桶 | 内容 | 人日 | 是否已含在控制台预算 |
|---|---|---|---|
| **A（已计）** | 调度器写接口（28）+ `dbserver` 管理面修复（5，**脱敏已裁**）+ WS `fetch` 列类型（4）＝ 原 P4 | **37** | ✅ 已含（控制台合计 **304**） |
| **B（新增）** | 本轮细化出的配合项：契约与夹具(2)、`dbserver` 能力与会话(3)、`plugin-scheduler` 自描述(4)、`dbproxy` 自身状态(3)、`dbcli` 对齐(2)、`common` 契约(3) | **17** | ❌ 未含，需单列 |
| — | 后续可选（wave 4） | ≈10 | ❌ 未含 |

**所以：服务端侧总计 ≈54 人日**，其中 37 在控制台预算内、17 需单列。

**`scheduler` 包含在这个配合里吗？包含，但分两处**：
- **写接口 28 人日** → 桶 A（已在 P4 / 控制台预算里）；
- **自描述、三态显式化、`lostSchedules`、优雅降级 4 人日** → 桶 B（新增）。

**脱敏：不做**（已裁）。理由：内部工具、无鉴权、本机访问；任何能打到 `port_x` 的人本来就能直接跑 SQL，脱敏不产生真实收益。具体落地：
- 服务端**不为 UI 做脱敏**（不加工作项）；
- 控制台**不展示**敏感字段（`/status` 里的 `mcpLlmKey` 等），也不写进日志 —— 这是 UI 侧的一行纪律，不是工作项；
- **已有行为不撤销**：调度器的 `variables` 端点本来就按 key 名屏蔽（`VariableSet`），保留现状（撤销它属于破坏性变更，比留着更贵）。代价是变量面板会显示 `******`；若排查需要明文，后续可加"临时显示"开关（0.5 人日，不排期）。

---

## 1. 契约的组织方式

```
docs/contract/                        ← 冻结的契约（本文件的扩展）
├─ capabilities.dbserver.json         ← 响应样例（夹具）
├─ capabilities.scheduler.json
├─ status.sessions.json
├─ error.envelope.json
├─ scheduler.task-list.json
├─ scheduler.lost.json
├─ proxy.status.json
└─ profiles.json
```

**两边怎么用同一份夹具**（这是"并行开发"的关键）：

| 使用方 | 用法 |
|---|---|
| 服务端 | 每个端点的契约测试**读夹具**，断言"我返回的结构包含夹具里的所有字段与类型"（不要求等于夹具，允许多字段） |
| 控制台 `access` 层 | 用一个 **stub HTTP server 返回夹具 JSON**，先写完整解析与错误映射测试；服务端实现上线后，把 stub 换成真实服务端跑同一批断言 |
| CI | 契约测试与夹具的**字段集合**做 diff：夹具中的字段若消失，构建失败（防"只增不改"被破坏） |

---

## 2. `common` 契约（3 人日）

### C-1 `InstanceDescriptor`（新增，两条链路共用）

```java
public record InstanceDescriptor(
    Kind kind,          // SERVER | PROXY
    String name,        // 数据库名或实例名
    String alias,
    String host,
    int port,           // pgwire；PROXY 为代理口
    int portX,          // 管理口；-1 = 关闭
    String database,
    AccessMode accessMode,  // READ_ONLY | READ_WRITE；PROXY 为 null
    String state,       // RUNNING | STOPPING | STOPPED ...
    String version,
    long seq, long timestamp
) {}
```

**使用方**：`Announcement`（发现）、`dbserver /status`、`dbproxy /status`、控制台实例树。

### C-2 发现公告升 `sd=2`

在现有字段（`sd/type/name/alias/host/port/portX/ver/seq/ts`）上**只增**：

| 新字段 | 含义 | 缺省（`sd=1` 报文） |
|---|---|---|
| `role` | `"server"` / `"proxy"` | `"server"`（历史上只有 dbserver 发） |
| `accessMode` | `"READ_ONLY"` / `"READ_WRITE"` | `null`（未知） |
| `data` | 数据库名（与 `name` 可能不同） | 取 `name` |

兼容规则：解析**同时接受 1 与 2**；发送按配置（默认 2）；未知字段一律忽略（现有解析已是"缺字段给默认值"风格）。

### C-3 错误信封 POJO + `apiVersion`

```json
{ "retCode": -1, "retMsg": "human readable", "errorClass": "VALIDATION|NOT_FOUND|CONFLICT|SQL|INTERNAL|UNAVAILABLE", "detail": null }
```

`ApiVersion.CURRENT = 1`。**兼容策略**：业务错误继续用 `HTTP 200 + retCode`（老客户端依赖它），但**必须**带 `errorClass`；传输层错误（参数非法/不存在/冲突）用正确的 4xx/5xx + 同一信封。

### C-4 `profiles.json`（`dbcli` 与控制台共用）

```json
{ "version": 1, "defaultProfile": "local",
  "profiles": [ { "name":"local", "kind":"server", "host":"127.0.0.1", "port":4309, "portX":8080,
                  "database":"db1", "user":"", "options":{} } ] }
```

位置：`~/.slackerdb/profiles.json`（Windows `%USERPROFILE%\.slackerdb\`）。口令**不落盘**（服务端无鉴权）。

### C-5 一致性夹具（test-jar）

`common` 发布 `test-jar`，内含四组用例（TSV）：`type-names`、`null-rendering`、`statement-splitting`、`csv-escaping`。`dbcli` 与 `console` 的测试都读它 —— 不共享生产代码，但**行为不许漂移**。

---

## 3. `dbserver` 契约（新增 3 人日；另 6 人日见桶 A）

> 脱敏不做（见 §0）。`/status` 里的 `mcpLlmKey` 等字段**照旧返回**，由控制台选择不展示。

### S-1 `GET /capabilities`（新增）

```json
{
  "apiVersion": 1,
  "server": { "name": "db1", "version": "0.2.1", "pid": 12345 },
  "ports": { "pg": 4309, "management": 8080, "managementEnabled": true },
  "accessMode": "READ_WRITE",
  "features": { "dataService": true, "mcp": true, "plugins": true, "sqlRepl": true,
                "sqlHistory": false, "apiHistory": true, "copyOut": true },
  "plugins": [ { "id": "plugin-scheduler", "state": "STARTED",
                 "contributes": { "etl": { "prefix": "/scheduler", "contractVersion": 1 },
                                  "configReadable": true } } ],
  "discovery": { "sd": 2, "advertised": true },
  "contracts": { "managementApi": 1, "mcp": "2026-07-28" }
}
```

语义要求：
- **便宜**：只读启动期快照 + 插件注册表缓存；**不做** DB 查询、不扫会话、不算 CPU（这些是 `/status` 的事）。
- 无鉴权、无副作用；任何时候 200。
- 插件装载/卸载后需失效缓存（`PluginService` 的 4 个变更端点里清缓存）。

### S-2 会话可辨识（`/status` 增量）

`sessions[]` 每项**新增**：`type`（`"pgwire"|"ws"|"plugin"`）、`applicationName`、`database`。

`applicationName` 来源：pgwire `StartupRequest` 的 `application_name` 参数 —— **目前服务端根本没读**（只有 `SQLReplacer` 里的一个正则）。约定值：`slackerdb-console/<ver>`、`slackerdb-dbcli/<ver>`。

### S-3 `GET /health`（新增）

```json
{ "status": "UP", "database": "UP", "management": "UP", "ts": 1700000000 }
```

供启动器与控制台每 2 秒轮询；**不得**触发 `/status` 那套重逻辑。

### S-4 `POST /sessions/{id}/kill`（新增，替代 UI 用 TCP 协议）

成功 `200 {"killed":true,"sessionId":"..."}`；未找到 `404 {"retCode":404,"retMsg":"...","errorClass":"NOT_FOUND"}`。
内部走与 `KILL SESSION`（`AdminClientRequest`）**同一条** `dbInstance.killSession(...)` 路径，避免两套取消语义。

### S-5 插件配置只读（**降级为可选**，且不脱敏）

`GET /plugin/{id}/config` → 插件属性原样返回（`plugin-scheduler.*` 等）。

- **不脱敏**（已裁）：控制台不展示 `PASSWORD/SECRET/TOKEN` 这类键即可，服务端不做值替换。
- **优先级下调**：原用途是"让控制台判断 HOP 是否配置"，而这条已被 `J-1 /scheduler/capabilities` 的 `hop.ready` 覆盖 → 移到 wave 4 可选，不计入桶 B。
- 保留它的理由：通用性（任何插件配置都可读，控制台不必为每个插件写代码），但**不做也能交付**。

### S-6 备份资源化（**已在桶 A**，此处只固定形状）

```
GET    /backups                → [ { "name":"b1.db", "size":123, "createdAt":"..." } ]
GET    /backups/{name}         → 二进制下载（替代走不通的 /download）
DELETE /backups/{name}         → 204
POST   /backup  {backupTag}    → 既有行为不变
```
`restore` **本期不做**（需要停库/重启语义，属独立议题）。

---

## 4. `plugin-scheduler` 契约（新增 4 人日；写接口 28 人日见桶 A）

### J-1 `GET /scheduler/capabilities`（新增）

```json
{
  "contractVersion": 1,
  "features": { "runCrud": true, "taskCrud": false, "templates": false,
                "globalHistory": false, "sqlTask": false, "zipImport": false },
  "quartz": { "started": true, "threads": 10, "jobCount": 7 },
  "home": "D:/slackerdb/data/scheduler",
  "projectHome": "D:/slackerdb/data/scheduler/projects",
  "hop": { "ready": false, "reasons": ["hop.runScript 未配置", "示例项目缺少 metadata/workflow-run-configuration/local.json"] },
  "limits": { "logTailMax": 10000 }
}
```

语义要求：
- `features` 是**降级开关**：控制台按它决定按钮可用性（例如 `taskCrud:false` 就隐藏"编辑任务"），这样老版本调度器也能被新控制台正确呈现。
- `hop.ready/reasons` 由插件自己判断（它最懂规则：`runScript` 存在且可执行、`default/` 父项目不自引用、变量文件是 Hop JSON 格式）—— 这就是"体检向导下沉"，比 GUI 读文件或向导便宜得多，`dbcli` 也可复用。
- 便宜：不扫 Quartz 全量触发器（`jobCount` 可用计数器）。

### J-2 三态显式化（`task/list` 增量）

现有响应里 `taskEnabled`（DB）、`triggerState`/`nextFireTime`（Quartz）、`running`/`lastRetCode`（内存快照）**混在一起**。新增：

```json
"states": { "definition": "ENABLED|DISABLED", "trigger": "NORMAL|PAUSED|NONE|BLOCKED", "runtime": "IDLE|RUNNING|QUEUED" }
```

旧字段全部保留（只增不改）。控制台的三态 UI 只用 `states`，不再猜字段含义。

### J-3 `GET /scheduler/state/lost`（新增）

```json
{ "lostSchedules": [ { "runId": "20261015010101", "taskName": "load_dim" } ] }
```

定义：**DB 里存在且启用、但 Quartz 里未登记**的任务。这是"重启后一键恢复"横幅的**权威源** —— 不该让控制台 diff `run/list` 与 `task/list` 自己推断。

### J-4 管理端口缺失时的优雅降级（修正）

`port_x` 关闭时插件拿到的 Javalin 为 null（`DBInstance.java:1182-1188`），现在 `onStart` 直接 NPE。改为：记 `warn`（文案含"未启用 port_x，调度器 REST 不可用"）→ 插件进入 `STOPPED` 态，**不影响服务端启动**。

### J-5 桶 A 的写接口（已在 P4，形状在这里固定）

| 端点 | 语义要点 |
|---|---|
| `POST /scheduler/run/{runId}/task` | 新建任务；`PUT` 更新；`DELETE` 删除；`PATCH` 启停 |
| `PUT /scheduler/run/{runId}` | 更新 `configJson`/变量 |
| `GET/POST/DELETE /scheduler/project/{type}` | 模板读取/上传（zip）/删除 |
| `GET /scheduler/history` | 全局历史，支持 `runId/taskName/from/to/page/size` |
| `GET /scheduler/status` | 返回**真实** Quartz 状态（替换硬编码） |
| `POST /scheduler/start` | `threadCount` 真正生效（或在文档与响应里删除该参数） |
| 日志 | `GET …/log?taskName=&execution=<n>` 按执行批次切分 |

---

## 5. `dbproxy` 契约（新增 3 人日）

### P-1 `GET /capabilities`（与 S-1 同一信封）

```json
{ "apiVersion": 1, "proxy": { "version": "0.2.1", "pid": 23456 },
  "ports": { "pg": 4409, "management": 8081, "managementEnabled": true },
  "managementForwarding": { "enabled": true, "prefixMode": "alias" },
  "discovery": { "sd": 2, "advertised": false } }
```

### P-2 `GET /status`（新增，**必须是它自己的**）

```json
{ "role": "proxy", "version": "0.2.1", "uptimeSeconds": 120,
  "activeSessions": 3,
  "backends": [ { "alias": "db1", "host": "10.0.0.5", "port": 4309,
                  "state": "UP|DOWN|UNKNOWN", "lastError": null, "activeSessions": 3 } ] }
```

**实现约束**：注册顺序必须在 `/*` 通配**之前**，否则被前缀转发吞掉（`ProxyInstanceX.java:166-204`）；并补一条测试断言"`/status` 不会被转发"。

### P-3 多播公告（wave 4，+0.5 人日）

`dbproxy` 也发 `HELLO/BYE`，`role="proxy"`。当前多播只有 dbserver 在发，平台无法自动发现代理。

---

## 6. `dbcli` 契约（新增 2 人日，**不改实现**）

### L-1 连接时设置 `application_name`

`slackerdb-dbcli/<version>`（控制台设 `slackerdb-console/<version>`）。配合 S-2，会话列表即可辨识来源，控制台不会误杀自己或 CLI 的会话。

### L-2 `--profile <name>` 读写 `profiles.json`

与 C-4 同一格式；`dbcli` 只新增"读 profile"的能力，不改变既有连接串语法（`[user/pw@]jdbc:slackerdb://host:port/db` 保持可用）。

### L-3 消费 C-5 的一致性夹具

`dbcli` 侧把现有的类型名/NULL/切分规则对接夹具（它已是基准，这一步主要是**把规则固化成测试**，防止以后漂移）。

> **不改**：`sql/`、`engine/`、`job/`、`ssh/` 的实现一律不动（它有自己的并行工作流）。

---

## 7. 实施波次与依赖

| 波次 | 内容 | 人日 | 解锁什么 | 依赖 |
|---|---|---|---|---|
| **W0** | 本契约评审通过 + `docs/contract/*.json` 夹具 + 两侧契约测试骨架 | 2 | 两边可并行开工 | 无 |
| **W1** | `common` C-1/C-3（+`ApiVersion`）；`dbserver` S-1/S-3；`scheduler` J-1/J-4 | 5 | 控制台的**能力探测与菜单开关**可做完 | W0 |
| **W2** | `dbserver` S-2/S-4（`application_name`、杀会话）；`scheduler` J-2/J-3 | 4 | 控制台的**会话面板、三态 UI、一键恢复**可做完 | W1 |
| **W3** | 桶 A：调度器写接口 + 管理面修复 + WS 列类型（P4） | 38 | 控制台 M2（ETL 写操作） | W1（契约） |
| **W4** | `dbproxy` P-1/P-2/P-3；`dbcli` L-1/L-2/L-3；`common` C-2/C-4/C-5；可选 S-5 | 10 | 代理管理、CLI 对齐、发现升级 | W1 |

**W1 + W2 = 9 人日**是控制台 M1 的最小前置；W0 的 2 人日可以立刻做（纯文档 + 夹具，不碰生产代码）。

---

## 8. 验收标准（每条都要有，且都在**服务端侧**模块里）

| 契约 | 验收（例） |
|---|---|
| S-1 | 起服务 → `GET /capabilities` 返回含 `apiVersion/features/plugins`；关闭 `port_x` 时该端口不存在但服务正常；装载调度器插件后 `plugins[].contributes.etl` 出现 |
| S-2 | `psql`/JDBC 带 `application_name=x` 连接 → `/status.sessions[i].applicationName == "x"`；WS 会话出现在列表且 `type=="ws"` |
| S-3 | `/health` 在 DB 可用/不可用两种状态下分别返回 `UP` / `DEGRADED`，且耗时 < 5ms（不含 DB 查询） |
| S-4 | 杀掉一个会话后该会话的查询被中断（与 TCP `KILL` 行为一致）；未知 id 返回 404 信封 |
| J-1 | 未配置 HOP → `hop.ready=false` 且 `reasons` 非空；配置好后变 `true` |
| J-2 | 同一任务在"定义启用 + 触发器暂停 + 正在运行"时，`states` 三个字段分别为 `ENABLED/PAUSED/RUNNING` |
| J-3 | 重启服务端后 → `lostSchedules` 列出被降级的 run，控制台据此显示恢复横幅 |
| J-4 | `port_x=-1` 启动 → 服务端正常起、调度器记 warn 并 STOPPED，无 NPE（对照现状） |
| P-2 | 启动代理 → `/status` 返回自身状态而不被转发；下载 `/status` 与后端 `/status` 内容不同 |

---

## 9. 需要你确认的三点

1. **本契约是否冻结**（冻结后只增不改；控制台按它并行开发）。
2. **`scheduler` 的自描述 4 人日**是否与写接口 28 人日**同批**做？（建议：自描述先做，因为它解锁 M1 的菜单与三态；写接口留 M2）
3. **W0（契约 + 夹具，2 人日）是否现在就可以做**？它不碰生产代码，只新增 `docs/contract/*.json` 与两侧测试脚手架 —— 如果你希望"继续只讨论方案"，我连这个也不做，只把契约以文档形式定下来（就是当前这份）。

---

## 10. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 本轮 | **去掉脱敏**：`dbserver` 不为 UI 脱敏；`S-5` 降级为可选且不脱敏（4→3 人日，桶 B 18→17） | 需求方裁决：内部工具、无鉴权、本机访问，脱敏无真实收益；控制台不展示敏感字段即可 |
| 本轮 | 调度器 `variables` 的既有屏蔽**保留不撤销** | 撤销是破坏性变更，成本高于保留；代价是面板显示 `******`（如需明文，后续可加"临时显示"开关 0.5 人日，不排期） |
| 前一轮 | 修正重复计算：`/status` 脱敏、备份下载、错误传输归入桶 A | 与 P4 重叠 |

> **已被后续决定覆盖**：上述"保留 `variables` 屏蔽"一条已作废 —— 需求方确认该接口**目前没有实际客户**，可做破坏性更改，故已改为返回原值（见 §11）。

---

## 11. 实施状态（本轮已落地）

### 11.1 已完成

| 契约项 | 状态 | 落地位置 |
|---|---|---|
| C-3 部分（`apiVersion` 常量） | ✅ | `common/.../common/api/ApiVersion.java`（新增，`MANAGEMENT=1`、MCP 版本常量） |
| 插件贡献声明（S-1 依赖） | ✅ | `plugin/.../PluginContribution.java`（新增）+ `DBPlugin.contributes()` 默认 `null`；`PluginService` 列表输出 `contributes` |
| S-1 `GET /capabilities` | ✅ | `DBInstanceX`：声明 apiVersion/端口/accessMode/features/plugins（含 contributes）/contracts；**刻意不做 IO**，不泄漏敏感参数 |
| S-2 会话可辨识 | ⚠️ 部分 | `DBSession.applicationName` + `/status.sessions[]` 新增 `type`（当前恒 `pgwire`）、`applicationName`、`database` |
| S-3 `GET /health` | ✅ | `DBInstanceX`：只读实例状态，无 IO（测试断言 < 1s，实测毫秒级） |
| S-4 `POST /sessions/{id}/kill` | ✅ | `DBInstanceX`：走既有 `dbInstance.killSession(...)` 同一路径；未知 id 返回 404 JSON 信封（含 `errorClass`） |
| J-1 `GET /scheduler/capabilities` | ✅ | `SchedulerController`：契约版本、features 开关（如实把 `taskCrud` 标为 false）、Quartz 真实状态、`hop.ready/reasons`、limits |
| J-2 三态显式化 | ✅ | `SchedulerController.handleListTasks` 新增 `states:{definition,trigger,runtime}`，旧字段保留（只增不改） |
| J-3 `GET /scheduler/state/lost` | ✅ | `SchedulerController`：按 run 对比 DB 定义与 Quartz 登记，输出 `lostSchedules[]` |
| J-4 管理口缺失时优雅降级 | ✅ | `PluginScheduler.onStart`：`app == null` 时显式失败并给可操作提示，不再 NPE |
| 破坏性变更：`variables` 返回原值 | ✅ | `SchedulerController.handleRunVariables` 改用 `asMap()`；`VariableSet.masked()` 保留（仅内部 `toString` 用） |
| P-1 `dbproxy /capabilities` | ✅ | `ProxyInstanceX`：同信封；声明 `managementForwarding:{enabled,prefixMode:"alias"}` |
| P-2 `dbproxy /status` | ✅ | `ProxyInstanceX`：role/version/uptime/ports/backends（别名→目标）；**注册在 `/*` 之前**并用测试锁住 |
| 新增测试 | ✅ | `dbserver` 的 `CapabilitiesApiTest`、`plugin-scheduler` 的 `SchedulerContractApiTest`、`dbproxy` 的 `ProxyApiTest` 增补用例 |

`DynamicQuartzScheduler.isStarted()` 为 `J-1` 的"Quartz 真实状态"新增（过去 `/scheduler/status` 是硬编码常量）。

### 11.2 施工中发现的两个真问题（都已修）

1. **Javalin 的 404 error handler 会覆盖端点显式写的 JSON 错误**。
   Javalin 对任何 4xx 响应都会走 `error(404, ...)`，而该 handler 在非 `/api/` 路径下直接写 HTML —— 于是 `POST /sessions/999999/kill` 的 JSON 信封被替换成 `<!DOCTYPE html>`。
   **修复**：handler 先判断"响应体已存在则不覆盖"，并把 `/sessions/`、`/capabilities`、`/health`、`/mcp`、`/plugin/`、`/backup(s)` 纳入 JSON 错误前缀。这条对以后所有新增的 JSON 端点都有效。

2. **驱动的 `SET application_name` 走的是 Parse（扩展协议）路径，且会被 `SQLReplacer` 丢弃**。
   `dbdriver` 在握手后用 `SET application_name = '...'` 上报来源（`ConnectionFactoryImpl:925-932`），而服务端 `SQLReplacer` 把这条语句替换成空语句（DuckDB 不支持）。因此只在 `startupOptions` 里找永远是空的。
   **修复**：`SQLReplacer.extractApplicationName()` + 在 `ParseRequest`（扩展协议）、`QueryRequest`、`ExecuteRequest` 三处**替换之前**截留并写入 `DBSession.applicationName`；`/status` 优先读该字段，回退到握手参数（libpq/psql 会在 startup packet 里带）。

3. **Quartz 调度器实例名写死导致"同 JVM 内共享调度器"**（真实缺陷，不只是测试问题）。
   `DynamicQuartzScheduler.createScheduler` 原本把 `org.quartz.scheduler.instanceName` 固定为 `SlackerDBScheduler`，而 `StdSchedulerFactory` **按名字缓存单例** —— 同一 JVM 里第二个调度器会拿到同一份 RAMJobStore，表现为"别人的触发器出现在我的暂停列表里"。产品支持同 JVM 多实例，这是实实在在的串台风险。
   **修复**：实例名改为 `SlackerDBScheduler-<pid>-<seq>`（可用 `-Dslackerdb.scheduler.instanceName=` 覆盖）。修复后调度器全套测试（12 例引擎 + 2 例契约）可在同一 JVM 内一起跑绿。

### 11.2.1 一处语义修正

`/scheduler/capabilities.features` 初版把 `templates` 写成了 `true`，但实际只有"读模板目录"、没有写。已拆成两个如实的开关：`templateRead=true`、`templates=false`（写：上传/删除/zip 导入）。**能力声明必须如实**，否则客户端会显示一个点了就失败的按钮 —— 这条在下面的降级矩阵里是硬约束。

### 11.3 明确**未做**（偏差清单，不含在前述人日里）

| 项 | 为什么没做 | 后续位置 |
|---|---|---|
| `/status.sessions` 纳入 **WS/REPL 会话** | `SqlReplServer.sessions` 是私有 map，需要新增会话注册表并贯穿；属于"值得单独做一次"的改动 | 控制台需要"看到并管理 WS 会话"时补 |
| `dbproxy` 后端健康采集 | 代理目前不跟踪后端状态/错误/会话数 | `state` 恒为 `UNKNOWN`；需要时加探测 |
| `dbproxy` 发多播公告（P-3）、公告 `sd=2`（C-2） | 属 wave 4，且要同时改 `common` 解析与两个模块 | W4 |
| `S-5` 插件配置只读 | 已降级为可选（HOP 就绪已由 `J-1` 覆盖） | 可选池 |
| `C-4` `profiles.json`、`C-5` 一致性夹具 | 需要 `dbcli` 侧同步改动 | 与 `dbcli` 对齐时做 |
| `dbcli` 的 `application_name` / `--profile` | `dbcli` 有自己的并行工作流，本轮**零改动**以免冲突 | 下一轮与 `dbcli` 一起做 |
| 桶 A 全部（调度器写接口、模板 CRUD、全局历史、备份资源化、WS 列类型…） | 属 P4（38 人日），不在本轮"配合"范围 | M2 |

### 11.4 回归证据

| 模块 | 命令（均 `-o` 离线） | 结果 |
|---|---|---|
| `plugin-scheduler` | `mvn -pl plugin-scheduler -am test` | **14/14 绿**（12 例引擎 + 2 例新契约测试，同 JVM 一起跑） |
| `plugin-scheduler`（新契约测试） | `mvn -pl plugin-scheduler -am test -Dtest=SchedulerContractApiTest` | **2/2 绿** |
| `dbserver`（定向：契约端点 + 状态 + 会话 + SQL 替换器回归） | `mvn -pl dbserver -am test -Dtest=CapabilitiesApiTest,InstanceXApiTest,InstanceXBasicServiceTest,SessionConcurrencyTest,SqlReplacerGoldenTest,SqlReplacerCacheTest,PlSqlSimpleQueryTest` | **35/35 绿** |
| `dbproxy`（含新 `/status`、`/capabilities` 用例） | `mvn -pl dbproxy -am test -Dtest=ProxyApiTest,ProxyTest` | **4/4 绿** |
| `dbcli`（`common` 的重度消费者，作为 `common` 改动的回归） | `mvn -pl dbcli -am test` | **600/600 绿**（12 例按系统属性跳过） |
| 全仓 | `mvn -o -DskipTests compile` | **通过**（含 `dbcli`，reactor 全绿） |

### 11.5 对控制台的影响

- `access` 层可以**只依赖声明**工作：启动读 `/capabilities`（`apiVersion` + `features` + `plugins[].contributes`），不再探测 `/scheduler/status`；
- 会话面板可用 `applicationName` 区分"我自己的连接 / CLI / 别人"，并安全地走 `POST /sessions/{id}/kill`；
- 调度面板的三态与"一键恢复"横幅有了权威数据源（`states` + `/scheduler/state/lost`），不需要客户端 diff 推断；
- 调度器缺席/版本较老时按 `features` 自动降级，不再出现"点了按钮才知道接口不存在"。
