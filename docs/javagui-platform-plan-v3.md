# SlackerDB 控制台平台 方案 v3（服务端配合完成后的重编版）

> **v3 = 在当前代码状态下重写的计划。** 相对 v2 的核心变化：**服务端配合（桶 B）已落地并有回归证据**，控制台的服务端前置从 9 人日清零；工作量 304 → **293 人日**；M1 可以直接开工。
> 前序文档：
> - [javagui-platform-plan-v2.md](javagui-platform-plan-v2.md)（v2 总方案，**已被本文取代**）
> - [javagui-server-contract.md](javagui-server-contract.md)（契约 + §11 实施状态与回归证据）
> - [javagui-reuse-from-dbcli.md](javagui-reuse-from-dbcli.md)（复用 R3）
> - [javagui-architecture-coordination.md](javagui-architecture-coordination.md)（模块分工）
> - [javagui-client-api-inventory.md](javagui-client-api-inventory.md)（接口清单）
> - [javagui-m0-checklist.md](javagui-m0-checklist.md)（M0 步骤，需按本文 §6 微调）

---

## 0. 结论速览

| 项 | v3 定稿 |
|---|---|
| 形态 | 本机 Web 控制台：**Vaadin 24**（Java 写 UI）+ 浏览器为显示层；**T1 独立进程**，不紧耦合 |
| 编辑器/图表 | **CodeMirror 6**（SQL）+ **ECharts 5**（图表），各自最小包装件（已确认） |
| 控制台工作量 | **293 人日 ≈ 14.0 人月**（区间 10–15）；M1 **5.3 – 6.8 人月** |
| 服务端配合 | **已完成 ≈12 人日**；剩余 **≈4 人日**（契约夹具 2 + `dbcli` 对齐 2，不阻塞 M1） |
| M1 服务端前置 | **0** —— `/capabilities`、会话辨识与终止、调度三态与 `lostSchedules`、代理状态均已可用且有测试 |
| 仍待补（桶 A，M2） | 调度器写接口 28 + `dbserver` 管理面 5 + WS 列类型 4 = **37 人日** |
| M0 | **11–15 人日 ≈ 0.5–0.7 人月**，4 项（Vaadin 生产构建 / Grid+游标分页 / 两个 JS 包装件 / 启动器+端到端） |

---

## 1. 与 v2 的差异清单

| 类别 | 变化 |
|---|---|
| **服务端能力** | 新增并上线：`/capabilities`、`/health`、`POST /sessions/{id}/kill`、`/status.sessions[].type/applicationName/database`、`/scheduler/capabilities`（含 `hop.ready/reasons`、真实 Quartz 状态）、`task/list.states{definition,trigger,runtime}`、`/scheduler/state/lost`、`dbproxy /status` 与 `/capabilities`、插件 `contributes` SPI |
| **顺带修掉的缺陷** | ① Javalin 404 error handler 覆盖显式 JSON 错误；② 驱动 `SET application_name` 被丢弃导致来源不可辨；③ Quartz 实例名写死导致同 JVM 多实例共享调度器 |
| **控制台工作量** | 304 → **293 人日**（P0 −3、P1 −1、P3 −4、P6 −1、P8 −2） |
| **M1 前置** | 由"等待服务端 W1/W2（9 人日）"变为 **0**，可直接开工 |
| **新增产物** | §4 的**能力降级矩阵**（服务端信号 → UI 行为），实现时按表写分支即可 |
| **M0** | 内容不变（那 4 项与本次服务端改动无关），但"对真实服务端冒烟"变便宜了：契约端点已有测试与稳定形状 |
| **不变** | T1 拓扑、应用内 `access` 层（无独立 client jar）、不做数据写回、报表边界（JSON 模板 + 7 块 + 富文本 + 声明式格式化，PDF 后置）、调度 UI 三态+一键恢复、不做 Quartz 持久化、不做鉴权/脱敏、`dbcli` 只对齐不复用 |

---

## 2. 服务端现在提供什么（控制台的可用底座）

| 能力 | 端点 | 控制台怎么用 |
|---|---|---|
| 能力声明 | `GET /capabilities`（dbserver）、`GET /capabilities`（dbproxy）、`GET /scheduler/capabilities`（插件） | 启动时**一次**调用决定菜单/按钮/降级，**禁止**再探测端点猜 |
| 存活检查 | `GET /health` | 状态栏每 2s 轮询；`DEGRADED` 显示红点 |
| 会话可见与终止 | `GET /status`（`sessions[]` 含 `type`/`applicationName`/`database`）、`POST /sessions/{id}/kill` | 会话面板；按 `applicationName` 标注"本机控制台 / dbcli / 其他"；终止前二次确认 |
| 调度器自描述 | `GET /scheduler/capabilities` | ETL 菜单显隐；`features` 为 `false` 的按钮直接不渲染；`hop.reasons` 直接展示 |
| 调度三态 | `GET …/task/list` → `states{definition,trigger,runtime}` | 任务表格用三列状态点，不再猜字段 |
| 调度丢失 | `GET /scheduler/state/lost` | "重启后调度已丢失"横幅 + 一键恢复（`lostSchedules` 为权威源） |
| 代理自身状态 | `GET /status`（proxy） | 实例树里显示"代理 → 后端别名"，**不画假绿灯**（`state` 恒 `UNKNOWN`） |
| 插件贡献 | `/capabilities.plugins[].contributes`、`/plugin/list` | 判断"是否有 ETL 插件"用 `contributes.etl`，不看插件 id 字符串 |

> 契约与实现细节见 [javagui-server-contract.md](javagui-server-contract.md) §11。

---

## 3. 架构（相对 v2 未变，压缩表述）

```
浏览器 ──HTTP/WS──► Slackerdb Console（独立进程：Vaadin 24 + Spring Boot 3.5 + Tomcat :8080）
                       ├─ view 层（Vaadin 视图；仅 jsbridge 包允许 JS）
                       └─ access 层（普通 Java，禁 import vaadin/javalin；自带集成测试）
                                   │ HTTP / WS / JDBC
                                   ▼
                     dbserver（pgwire + 管理口 + 插件）｜ dbproxy ｜ 多实例/远端
```

- `access` 层按服务端**领域**分包：`capabilities`、`mgmt`(status/backup/log)、`session`、`dataservice`、`mcp`、`scheduler`、`jdbc`、`ws`、`error`。
- 边界用 ArchUnit 守：`access` 不得 import `com.vaadin.*`/`io.javalin.*`；`view` 不得直接用 `HttpClient`/`DriverManager`。
- 集成测试沿用 `dbcli` 惯例：`-Dconsole.it.url=...` 属性开关为主，`-Pembedded-it` 可选。
- 发行：独立产物（jar + 启动器，可选 `jpackage --type app-image`）；**Node 只在控制台模块**，且用 `-Pconsole` profile 门控，避免污染其它 10 个模块的构建。

---

## 4. 能力降级矩阵（实现清单，直接照着写分支）

| 服务端信号 | 控制台行为 |
|---|---|
| `apiVersion == 1` | 正常启用 |
| `apiVersion` 缺失或 > 支持版本 | 只启用基础功能（SQL/服务/MCP），状态栏提示"服务端版本更新，部分功能未启用"；**不崩** |
| `features.plugins == false` | 隐藏"插件"菜单 |
| `plugins[]` 中无 `contributes.etl` | 隐藏"ETL 调度"菜单 |
| ETL 插件在，但 `/scheduler/capabilities` 不可达 | ETL 菜单置灰，提示"管理端口未启用（port_x），调度 REST 不可用" |
| `scheduler.features.taskCrud == false` | 隐藏"新增/编辑/删除任务"，保留 启停/中止/schedule |
| `scheduler.features.templates == false` | 隐藏"上传/删除模板"，保留只读浏览（由 `templateRead` 控制） |
| `scheduler.features.globalHistory == false` | 历史页只提供"按实例查询"，不显示"全局流水"入口 |
| `scheduler.features.sqlTask == false` | 新建任务时 SQL 类型置灰，提示"当前服务端不支持 SQL 任务" |
| `scheduler.hop.ready == false` | HOP 类型任务按钮置灰；悬停/弹窗展示 `hop.reasons`（原文） |
| `scheduler.quartz.started == false` | ETL 页顶部横幅"调度器未启动"+ 启动按钮 |
| `lostSchedules` 非空 | ETL 页横幅"检测到 N 个任务因重启丢失调度"+ 一键恢复（提示 RUN_ONCE 会重跑） |
| `/health` = `DEGRADED` | 状态栏红点 + 弹出最近一次错误摘要 |
| `sessions[].applicationName` 以 `slackerdb-console/` 开头 | 会话列表标注"本机控制台（勿误杀）" |
| `proxy.backends[].state == "UNKNOWN"` | 代理节点不显示健康色，只显示别名→目标；工具提示"该版本代理未采集后端健康" |

> 原则：**服务端声明为 false 的能力，客户端连按钮都不渲染**（而不是渲染后报错）。这条比"错误提示友好"更省事，也更诚实。

---

## 5. 工作量（v3）

| 工作包 | v2 | **v3** | 变化依据 |
|---|---|---|---|
| P0 `access` 层（含集成测试 + ArchUnit 边界） | 36 | **33** | 能力探测从"多点探测+推断"变成一次 `/capabilities`（−2）；会话辨识与终止走现成端点（−1） |
| P1 SQL 工作台 | 52 | **51** | 会话面板有 `applicationName` 可直接标注（−1） |
| 与 `dbcli` 同源测试 + 来源标注 | 1 | **1** | 不变 |
| P2 服务 / MCP 管理 | 34 | **34** | 不变 |
| P3 ETL 调度管理 GUI | 34 | **30** | 三态与 `lostSchedules` 由服务端给出、按钮由 `features` 驱动（不再写推断逻辑，−4） |
| P4 服务端改造（桶 A，M2） | 37 | **37** | 本轮未动 |
| P5 报表 | 58 | **58** | 不变 |
| P6 平台化（壳/导航/i18n/主题） | 14 | **13** | 启动与健康横幅走 `/health` + `/capabilities`（−1） |
| P7 打包分发 | 10 | **10** | 不变 |
| P8 测试与 CI | 22 | **20** | 服务端契约测试已存在，控制台侧可直接对接真实端点（−2） |
| P9 前端工程化 | 6 | **6** | 不变 |
| **合计** | 304 | **293** | **≈ −4%，14.0 人月**（区间 10–15） |

**M1（MVP）**：P0 33 + P1 精简 39（编辑器/网格/元数据/CSV-JSON 导出）+ P2 34 + P9 6 = **112 人日 ≈ 5.3 人月**；再加调度只读面板（P3 中约 30）= **142 人日 ≈ 6.8 人月**。

**服务端侧**：已完成 ≈12 人日（dbserver 3 + 调度器 4 + 代理 3 + common/plugin 2）；剩余 ≈4 人日（契约夹具 2、`dbcli` 对齐 2）**不阻塞 M1**。

---

## 6. M0（11–15 人日 ≈ 0.5–0.7 人月）

| # | 验证项 | 为什么仍然必须先做 | 验收 |
|---|---|---|---|
| 1 | Vaadin 24 + Spring Boot 3.5 跑通 + **生产构建**（Vite），CI 走 npm 缓存 | 仓库零前端工具链；且 aliyun 镜像上 `vaadin-spring-boot-starter`/`vaadin-bom` 曾有解析失败，必须先判定是版本问题还是缺件 | `mvn -Pproduction package` 成功；记录构建耗时/体积 |
| 2 | **Grid 懒加载 + JDBC 游标分页**（100 万行 / 50 列 / 单值 1MB 截断） | 决定网格架构与"百万行怎么取数"；三方案（游标 / LIMIT-OFFSET / WS 分页）对比 | ≥50 fps、内存增长 <30%、首屏 ≤2s；产出取数方式结论 |
| 3 | **CodeMirror 6 与 ECharts 5 包装件** | 决定 SQL 工作台与报表体验上限；两处 JS 是方案的硬前提 | 生产构建可运行；gzip 后合计 ≤700KB |
| 4 | **启动器 + Playwright 端到端** | 决定"双击即用"的交付形态 | 一条命令起控制台→探测/起本机 dbserver→开浏览器；冒烟绿；**顺带对真实服务端断言 `/capabilities` 驱动的菜单显隐** |

> 相比 v2，第 2 项与第 4 项的**判断素材更充分**：服务端契约测试已给出 `/capabilities` 的稳定形状，M0 第 4 项可以直接断言"有 ETL 插件 → ETL 菜单出现；无插件 → 菜单消失"。

---

## 7. 路线图

| 阶段 | 交付 | 人月 | 验收 |
|---|---|---|---|
| **M0** | 4 项验证 + 骨架 | 0.5–0.7 | 见 §6 |
| **M1** | P0 + P1 精简（只读、仅 JDBC）+ P2 全部 + P9 +（可选 P3 只读） | **5.3 – 6.8** | 日常查询可用；服务/MCP 运维完成；调度可视化可看（三态/日志/历史/一键恢复横幅） |
| **M2** | **桶 A 落地**（调度写接口等 37 人日）+ P1 补会话管理/Excel/异步面板 + P3 写操作 + 分发 | **+4 – 5** | ETL 全生命周期闭环；有可分发产物 |
| **M3** | P5 报表（R1 模板+预览 → R2 图表 → R3 PDF → R4 Excel/富文本） | **+2.5 – 3.5** | 数据+图表+PDF 的本地报表；刷新挂调度任务 |
| **M4（可选）** | 报表设计器、三平台、自动更新 | +4 – 10 | 视需要 |

> M2 的 37 人日桶 A 仍在**服务端侧**，应单列进项目总预算，而不是算进控制台的 293。

---

## 8. 风险（相对 v2 的新增/变化）

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| R1 | **控制台把"服务端声明"当唯一真相**，一旦服务端声明有误（如本次 `templates` 写错），客户端就显示错误按钮 | 中 | 能力声明必须如实（契约里已写死）；服务端为每个 flag 写一条断言（本次已补 `taskCrud=false`、`templateRead/templates` 拆分） |
| R2 | 契约演进：`apiVersion` 只在破坏性变更时递增，新增字段不算 → 客户端必须**容忍未知字段** | 中 | `access` 层解析用"取字段+默认值"，禁止严格反序列化；契约夹具做"字段只增不减"的 diff 检查（W0 剩余项） |
| R3 | **WS/REPL 会话仍未进 `/status`** → 控制台会话页只覆盖 pgwire | 中 | UI 明确标注"仅显示 pgwire 会话"；若产品要求全覆盖，补会话注册表（服务端 1–2 人日） |
| R4 | 代理后端健康是 `UNKNOWN` | 低 | UI 不画健康色；文案说明 |
| R5 | Vaadin 依赖在 aliyun 镜像的解析问题 | 中 | M0 第 1 项第一步判定；退路 `vaadin-core` + 显式 servlet，或配独立仓库 |
| R6 | 前端构建链进入 CI | 中 | Node 只在控制台模块；`-Pconsole` 门控；lockfile 入库；内网私有 npm 镜像 |
| R7 | 借鉴 `dbcli` 造成漂移 | 低 | 同源测试 + 来源标注（见 reuse 文档） |

（其余沿用 v2 风险表：无鉴权、报表自研、网格性能、HOP 外部依赖、测试成本等。）

---

## 9. 决策记录（最终，累计 30 条）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 服务端是否可改 | 可自由改造；**本轮已完成桶 B** |
| 2 | MCP 管理范围 | 仅管理 SlackerDB 自身 |
| 3 | 客户端形态 | 本机 Web 控制台（Vaadin 24），非桌面应用 |
| 4 | 部署拓扑 | **T1 独立进程，不紧耦合** |
| 5 | 访问层形态 | 应用内 `access` 包，无独立 jar；ArchUnit 守边界；自带集成测试 |
| 6 | 两处 JS | 接受：CodeMirror 6 + ECharts 5（+ 剪贴板片段） |
| 7 | 前端构建链 | 接受 Node/npm/Vite；`-Pconsole` 门控；内网需私有镜像 |
| 8 | 数据写回 | 不做 |
| 9 | 调度器 SQL 型任务 | 修成可执行（桶 A） |
| 10 | Quartz 持久化 | 不做；UI 三态 + 一键恢复 |
| 11 | 报表模板格式 | JSON + `$schema` |
| 12 | 报表能力边界 | 无表达式语言（改声明式格式化/条件高亮/条件显示）；无屏幕分页；7 块 + 富文本 |
| 13 | PDF 时序 | 屏幕查看为主 → PDF 后置 |
| 14 | 报表编辑器 | 表单化编辑；设计器留 M4 |
| 15 | 取数通道 | 两通道；M1 只用 JDBC，异步面板 M2 |
| 16 | 服务端推进方式 | 批次 1/2/3；契约先冻结 + 桩响应 |
| 17 | 管理端口鉴权 | 本期不做 |
| 18 | 调度 UI 三态 + 一键恢复 | 做（数据源已由服务端提供） |
| 19 | HOP 体检向导 | 不做；改由 `/scheduler/capabilities.hop` 声明（已实现） |
| 20 | 发行形态 | 控制台独立发行；不塞服务端 dist |
| 21 | M0 范围 | 本文 §6 四项 |
| 22 | 与 `dbcli` 对齐 | 属性开关式集成测试 + 不抽公共模块 |
| 23 | 数据规模上界 | 100 万行 / 50 列 / 1MB 截断 |
| 24 | 集成测试方式 | 甲为主（`-Dconsole.it.url`）+ 乙可选（`-Pembedded-it`） |
| 25 | SQL 底层复用策略 | R3：复制小规则（`SqlColumnTypes`、`StatementSplitter`）+ 借鉴契约，不依赖模块 |
| 26 | 控制台定位 | 不追 DBeaver 平权：管理面 + 报表 + 调度可视化 + 好网格/元数据树 |
| 27 | WS 异步面板 | 不排期（M2 再说） |
| 28 | 与 `dbcli` 分工 | 只做文档/文案分工 |
| 29 | **脱敏** | **不做**（服务端不脱敏；控制台不展示敏感字段） |
| 30 | **`variables` 破坏性变更** | 已改为返回原值（该接口无实际客户） |

---

## 10. 现在就值得做的三件事

1. **M0 第 1 项（Vaadin 生产构建）**：它决定整个技术选型是否成立，且与其它工作完全解耦，1–2 天可出结论。
2. **W0 剩余：契约夹具 + 两侧契约测试骨架**（2 人日，不碰生产代码）：让控制台的 `access` 层可以对着夹具先写完，不必等服务端。
3. **让服务端改动先在真实配置下跑一遍**（带 `plugins_dir` + 真实调度器配置）：确认 `/capabilities` 的 `plugins[].contributes`、`hop.reasons`、`lostSchedules` 在真实环境符合预期 —— 这是控制台所有降级分支的事实基础。
