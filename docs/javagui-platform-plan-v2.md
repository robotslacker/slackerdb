# SlackerDB 控制台平台 方案 v2（已被 v3 取代）

> ## ⚠️ 本文已被 [javagui-platform-plan-v3.md](javagui-platform-plan-v3.md) 取代
> 原因：服务端配合（桶 B）已经落地并跑过回归，控制台的**服务端前置清零**、工作量 304 → 293 人日、M1 可直接开工。
> v3 新增了"能力降级矩阵"（服务端信号 → UI 行为），那才是实现时的直接依据。本文仅作历史留档。

> **本文是定稿方案（v2）。** v1 的"纯桌面 JavaFX + 独立 client 模块"已被否决。
> - 复议理由与逐维度对比：见 [javagui-vaadin-evaluation.md](javagui-vaadin-evaluation.md)
> - v1 文档（现状盘点、服务端改造清单、四项需求的功能边界）仍然有效，本文不重复，按需引用：
>   [javagui-platform-assessment.md](javagui-platform-assessment.md)（评估/总方案）、[javagui-platform-design.md](javagui-platform-design.md)（详设）、[javagui-client-api-inventory.md](javagui-client-api-inventory.md)（接口清单）

---

## 0. 定稿结论

| 项 | 定稿 |
|---|---|
| **形态** | **本机 Web 控制台**：Vaadin 24（UI 用 Java 写）+ 浏览器作为显示层 |
| **部署** | **T1 独立进程**：控制台是自己一个进程，通过 HTTP/WS/JDBC 调 dbserver，**不紧耦合** |
| **不做** | JavaFX 桌面、独立 client jar、WebView/JCEF、Vaadin 商业组件、数据写回、报表表达式语言 |
| **工作量** | 控制台侧 **304 人日 ≈ 14.5 人月**（区间 10.5–15.5）；M1（MVP）**5.5 – 7.2 人月** |
| **服务端配合成本（单列）** | M0/M1 必需 **≈17 人日（0.8 人月）**、后续 ≈10 人日 —— **不含在 304 里**，见 [javagui-server-contract.md](javagui-server-contract.md) |
| **三条主线** | ① 应用内 `access` 层（无独立模块但必须有层与边界）② 服务端批次 1 接口契约先冻结 ③ M0 四项验证 |
| **模块分工** | 契约住 `common`、实现留各模块；`dbserver` 提供能力声明与会话/备份资源；`plugin-scheduler` 是 ETL 唯一真相源且必须"可安全缺席"；`dbproxy` 开放自身状态；`dbcli` 只做对齐与夹具，不改实现 |

---

## 1. 形态与部署（T1）

```
┌──────────────┐   HTTP/WebSocket    ┌────────────────────────────────────────┐
│   浏览器      │ ◄─────────────────► │  Slackerdb Console（独立进程）           │
│ (Edge/Chrome)│   Vaadin 服务端渲染   │  Vaadin 24 + Spring Boot 3.5 + Tomcat   │
└──────────────┘                     │  :8080                                  │
                                     │  ├─ view 层（Vaadin 视图）               │
                                     │  └─ access 层（普通 Java 包，禁 Vaadin） │
                                     └───────────────┬────────────────────────┘
                                                     │ HTTP / WS / JDBC
                                     ┌───────────────▼────────────────────────┐
                                     │  dbserver（本机或远端）                  │
                                     │  pgwire(port) │ 管理/数据服务(port_x)    │
                                     │  plugin-scheduler │ 插件 │ MCP          │
                                     └────────────────────────────────────────┘
```

**启动器**（解决"双击就能用"）：一个小 jar/脚本负责 —— 起控制台 → 起本机 dbserver（可选）→ 打开默认浏览器。用户视角仍是一个入口。

**"不紧耦合"的三条具体含义（可验收）**：

1. **UI 不依赖服务端 Java 类**，只走 HTTP/WS/JDBC → 同一个控制台可以管理**多台/远端** dbserver，也能连别人机器上的实例。
2. **两者独立发版**：UI 升级不需要动 dbserver，也不触发数据库重启。
3. **服务端改造的动机与 UI 无关**：批次 1/2 的存在是为了解锁缺失能力（调度写接口等），不是为了"配合 UI 渲染"。因此**不改服务端，UI 也能拿到全部既有能力**。
4. 唯一需要的服务端配合是跨域 —— 而管理口**已经是 `anyHost` CORS**（`DBInstanceX.java:340-341`），所以**零改动**。也**不新增**任何托管 UI 静态资源的路由或插件 SPI。

---

## 2. 技术栈（事实均已实测/核实）

| 用途 | 选型 | 核实情况 |
|---|---|---|
| UI 框架 | **Vaadin 24 LTS 线**（建议 M0 锁定具体补丁版本；镜像上 24 线最新为 24.10.11） | `com.vaadin:vaadin-core:24.4.0` 可从当前 aliyun 镜像解析 |
| 运行宿主 | **Spring Boot 3.5**（内嵌 Tomcat）+ Java 17 | 父 pom **已 import `spring-boot-dependencies:3.5.14`**（`pom.xml:38-44`），版本天然对齐；Vaadin 24 要求 Java 17+/Spring Boot 3+/Servlet 6+（[官方兼容表](https://raw.githubusercontent.com/vaadin/docs/refs/heads/next/articles/compatibility.adoc)） |
| 前端构建 | Node 18+ / npm / Vite（由 Vaadin Maven 插件驱动） | 本机 Node **v24.19.0**，`npm ping` → **PONG 6676ms**（registry.npmjs.org 可达） |
| SQL 编辑器 | **CodeMirror 6（MIT）** 包装为 Vaadin 组件 | 需在 M0 验证包装件集成 |
| 图表 | **ECharts 5（Apache-2.0）** 包装为 Vaadin 组件（**已确认**） | 需在 M0 验证包装件集成；用 ECharts 的**按需构建（tree-shaking）**控制体积，只引入用到的图表类型与组件 |
| 报表 PDF | **Apache PDFBox**（内嵌 `NotoSansSC-Regular.ttf`） | 仓库已有该字体 |
| Excel 导出 | Apache POI（`poi-ooxml`） | 可选池 |
| HTTP / WebSocket | **JDK `java.net.http`** | 零依赖 |
| JSON | fastjson2 | 与仓库一致 |
| 边界约束 | **ArchUnit**（替代跨模块 enforcer） | 见第 3 节 |

**明确不用**：Vaadin Charts / Grid Pro（商业）、JavaFX、JCEF/JxBrowser、iText（AGPL）、iReport 类设计器。

**两处 JS 包装件（已确认）＋ 一处代码片段**

- **两处包装件**：**CodeMirror 6**（SQL 编辑器，MIT）与 **ECharts 5**（图表，Apache-2.0）。各写一个最小 Vaadin `Component` 子类 + 必要 JS，放在 `view/jsbridge/` 下，**版本锁定**，不依赖第三方 addon。
- **一处片段（不算包装件）**：Vaadin Grid 复制到系统剪贴板需要 `navigator.clipboard` 的几行 JS，直接含在 Grid 视图内；本机 `localhost` 属安全上下文，另做"选中文本 + Ctrl+C"降级。
- 除上述外，任何视图**不得**引入 HTML/CSS/JS（由代码评审 + ArchUnit 的"view 包不得出现 `@JsModule`/`@CssImport` 注解"规则守住，`jsbridge` 包豁免）。

---

## 3. 应用内分层（替代独立 client 模块）

### 3.1 模块与包

```
ui/
└── slackerdb-console/                    ← 唯一的新模块（Vaadin 应用）
    └── src/main/java/org/slackerdb/console/
        ├── access/                       ← 访问层：普通 Java，禁止 import Vaadin/Javalin
        │   ├── error/                    ClientError / SlackerdbException（统一 5 种错误表达）
        │   ├── http/                     共享 HttpClient、multipart、重试、超时
        │   ├── jdbc/                     JdbcSession、类型映射、游标分页、元数据
        │   ├── ws/                       SqlWsClient（start/exec/fetch/cancel/close + 分页循环）
        │   ├── mgmt/                     /status /backup /upload /download /viewLog /plugin
        │   ├── dataservice/              token/context/注册表/调用
        │   ├── mcp/                      JSON-RPC 2026-07-28 + 15 条管理端点
        │   ├── scheduler/                全部 /scheduler/* + cron 校验
        │   ├── instance/                 进程启停、pid、多播发现、配置读写（同机）
        │   └── model/                    少量 POJO
        └── view/                         ← Vaadin 视图（只允许依赖 access 与 Vaadin）
            ├── shell/  sql/  service/  mcp/  report/  scheduler/  server/  settings/
```

### 3.2 为什么"层"必须在（不是洁癖，是证据）

服务端有这些东西**必须在某一处统一吸收**，否则会散落到 40+ 个界面里各写一遍：5 种错误表达（HTTP 200+`retCode` / 502 文本 / 500 文本 / 404 HTML / 404 JSON）、Resource 的 `saveMCPSource` 命名不一致、WS 错误帧可能不带 `id`、WS 分页与取消后 taskId 失效、**WS 重复列名会丢列**、token 30 分钟滑动过期、日志按执行批次切分。（**脱敏不在其列**：已裁不做，控制台不展示敏感字段即可。）
（逐条证据见 [javagui-client-api-inventory.md](javagui-client-api-inventory.md) 第 1.2 节与 A–E 各表。）

### 3.3 边界守则（用 ArchUnit 机械守住，+0.5 人日）

1. `access` 包及其子包**不得**依赖 `com.vaadin.*`、`io.javalin.*`；
2. `view` 包**不得**直接使用 `java.net.http.HttpClient` / `DriverManager` / `Connection`；
3. `view` 包**不得**出现 `@JsModule` / `@CssImport` / `@StyleSheet`（三处 JS 包装件除外，且它们独立成 `view/jsbridge` 包）。

### 3.4 即便同机也走 HTTP 的三条理由

① 调度器是 PF4J 插件，跨 classloader 直调脆弱；② 直调服务端内部类会让每次服务端重构都打穿 UI；③ 走 HTTP 才能管理多台/远端实例（T1 的核心价值）。

### 3.5 测试策略

| 层 | 手段 |
|---|---|
| **access 层（回归主力）** | JUnit 5 对着**嵌入式 `DBInstance`**（仓库既有风格，`MCPTest.java:34-57`）跑 5 类协议的成功/失败路径；不需要浏览器、不需要起 UI |
| 服务端改造项 | 各模块端到端测试（每项至少 1 条） |
| UI 冒烟 | **Playwright（免费）**：能起、能连、能执行 SQL、能关；业务逻辑不写在视图里 |
| 手工验收 | 按各需求的验收清单（见 v1 文档 3.1/3.3/3.4 节） |

---

## 4. 工作量（Vaadin + T1）

| 工作包 | v1（JavaFX） | **v2（Vaadin）** | 变化来源 |
|---|---|---|---|
| P0 `access` 层（应用内包 + 集成测试 + ArchUnit 边界） | 45.5 | **36** | 无独立制品/发布 −3.5；**依 `dbcli` 复盘再 −6**（JDBC 连接/类型映射/语句切分/取消语义有参考与既定契约，见 [javagui-reuse-from-dbcli.md](javagui-reuse-from-dbcli.md)） |
| P1 SQL 工作台 | 69 | **52** | 网格 −11（Grid 懒加载）、编辑器 +4（CodeMirror 包装件）、元数据 −2（TreeGrid）、剪贴板/上下文菜单 +2；**依 `dbcli` 复盘再 −10**（复制 statement splitter 与类型表、执行/连接层按既定契约简化） |
| **与 `dbcli` 同源测试 + 来源标注（新增）** | — | **1** | 两个复制类各一个"行为与 dbcli 一致"的对比测试 |
| P2 服务 / MCP 管理 | 40 | **34** | Grid + Binder 让 CRUD 表单显著更快 |
| P3 ETL 调度管理 GUI | 38 | **34** | 同上（含三态与一键恢复，无 HOP 体检向导） |
| P4 服务端改造（批次 1/2 必做；**已去掉脱敏**） | 38 | **37** | 框架无关；脱敏整项取消（内部工具，无鉴权，见契约 §0） |
| P5 报表 | 60 | **58** | 图表走 ECharts 包装件；PDFBox/POI 不变 |
| P6 平台化（壳/导航/i18n/Lumo 主题） | 15 | **14** | 少窗口与布局持久化 |
| P7 打包分发 | 22 | **10** | **无平台原生件**：jar + 启动器（可选 `jpackage --type app-image` 带 JRE） |
| P8 测试与 CI | 20 | **22** | 浏览器端冒烟 +2 |
| **P9 前端工程化（新增，已确认接受）** | — | **6** | Node/Vite 集成、生产 bundle、依赖锁定（lockfile 入库）、CI npm 缓存、**离线/内网构建预案**；CodeMirror 的 SQL 语言包；**ECharts 按需构建**与体积预算 |
| **合计** | **347.5** | **304** | **≈ −13%** |

**区间 10.5 – 15.5 人月**（不确定性集中在 P9、两处 JS 包装件，以及借鉴 dbcli 带来的漂移风险）。
**M1（MVP）按明细口径**：不含调度面板 ≈115 人日 ≈ **5.5 人月**；含调度只读面板 ≈151 人日 ≈ **7.2 人月**。

---

## 4.5 与 `dbcli` 模块的关系（对齐、复用与两个待决问题）

> 事实：仓库里已有 **`dbcli`**（已进 reactor 的独立模块，M0–M3 完成，406 个文件、359 例测试；自带 JDBC 数据通道 / 交互式 REPL / 比对报告 `.dif|.suc|.rpt` / `_JOB` 作业管理 / `_SSH`+SFTP；手册见 `dbcli/README.adoc`、`dbcli/README-CN.adoc`）。
> **完整复盘见 [javagui-reuse-from-dbcli.md](javagui-reuse-from-dbcli.md)**（含逐包资产盘点、三种复用策略对比、人日修正与产品分工）。

**A. 直接对齐（建议照做，成本≈0，收益是仓库一致性）**

| 项 | dbcli 的做法 | 控制台应对齐为 |
|---|---|---|
| 集成测试方式 | 不强依赖 `dbserver`，用 `@EnabledIfSystemProperty(named="dbcli.it.url")` 指向**运行中的服务端**（默认跳过） | ✅ 已定：`-Dconsole.it.url=...` 为主，`-Pembedded-it` 为可选 |
| 依赖约束 | 用 **`maven-enforcer-plugin`** | 继续用 enforcer 管依赖方向，**另加 ArchUnit** 管"包级"规则（enforcer 管不到包） |
| 文档形态 | 决策记录 + 包结构 + 踩坑 + 里程碑 + 验收命令 | 本 v2 已同形；后续按此维护 |
| 列类型命名 / NULL 表示 / 错误文案 | `sql/SqlColumnTypes.nameOf`（JDBC 常量 → 大写类型名）等规范 | **控制台的网格类型渲染应与之保持一致**（同一个产品里不该有两种 NULL 或类型名写法）—— 以 `dbcli` 既有规范为准 |

**B. 复用面结论（已定：R3 —— 复制小规则 + 借鉴契约，不依赖模块）**

| 候选 | 处置 | 理由 |
|---|---|---|
| `assertion/ResultModel`（typed rows + headers + columnTypes + status + errorCode） | ✅ **照形状定义** | 正是网格要的数据模型；33 行 record、零外部依赖 |
| `sql/SqlColumnTypes`（60 行）、`engine/StatementSplitter` + `LexicalScanner`（≈340 行） | ✅ **直接复制 + 同源测试** | 纯函数、零依赖；解决"DuckDB 返回小写类型名导致对齐错"与"执行当前语句"两个真问题 |
| `sql/JdbcSqlExecutor`（35KB） | ❌ **不拉** | **无 `setFetchSize`/`setMaxRows`（全量物化）**，接不了 100 万行网格；`connect(Map ir)` 要的是 `_CONNECT` 命令 IR，形状不适合嵌入 |
| `script/SqlAnalyzer` + `SqlIrVisitor`（ANTLR，61KB） | ❌ **不拉** | 耦合 dbcli 私有语法与生成代码；控制台用"服务器报错即未写完"的简化路径 |
| `report/`、`compare/`、`job/`、`ssh/`、`repl/` | ❌ 不复用 | 目的不同（比对报告/脚本作业/终端交互）；产品分工见下 |
| `daemon/CliDaemon` + `common/AppDaemon` | ✅ 形态对齐 | 启动器可照 `start|stop|status` + pidfile 的形态做 |

**C. 结论与剩余待决**

1. **控制台集成测试** ✅ **已定：甲为主 + 乙可选**。
   - 甲（与 dbcli 一致）：`-Dconsole.it.url=...` 属性开关测试，**默认跳过**，控制台不强依赖 `dbserver` 模块，构建更轻；代价是 CI 要先起一个服务端。
   - 乙（可选自包含）：`-Pembedded-it` 走嵌入式 `DBInstance`，测试更硬但 test 作用域耦合 `dbserver`；默认不启用。
2. **产品里将同时存在两套"调度"语义**（仍待决）：服务端 `plugin-scheduler`（Quartz，服务端常驻、跑 HOP/SQL/SHELL）与 `dbcli` 的 `_JOB`（脚本作业，客户端侧、可跨进程会合）。
   - 若二者面向同一批用户，需要在**文档与 UI 文案**上明确分工（例如："常驻调度去控制台 ETL 面板；一次性脚本作业用 dbcli `_JOB`"），否则用户会问"我该用哪个"。
   - 若希望将来统一，那是产品级决策，会引入额外工作量；**本期建议只做文案分工，不做技术统一**。

---

## 5. 路线图

| 阶段 | 交付 | 人月 | 验收 |
|---|---|---|---|
| **M0（2–3 周）** | 四项验证 + 骨架：Vaadin 应用壳、`access` 层雏形、一个能连 dbserver 执行 SQL 的页面、一个 ECharts 示例 | 0.5 | 见第 7 节 |
| **M1** | P0 + P1 精简（编辑器/网格/元数据/CSV-JSON 导出，只读、仅 JDBC）+ P2 全部 + P9 +（可选 P3 只读） | **6.3 – 8.1** | 日常查询可用；服务与 MCP 运维在控制台内完成 |
| **M2** | 批次 1 服务端改造落地 + P1 补异步面板/会话管理/Excel/EXPLAIN + P3 写操作（任务增改删、cron 编辑、一键恢复）+ 启动器与分发 | **+4 – 5** | ETL 全生命周期闭环；有可分发产物 |
| **M3** | P5 报表 A 档（R1 模板与预览 → R2 图表 → R3 PDF → R4 Excel/富文本） | **+2.5 – 3.5** | 数据+图表+PDF 的本地报表；报表刷新挂调度任务 |
| **M4（可选）** | 报表可视化设计器、目标平台扩展、自动更新 | +4 – 10 | 视需要 |

---

## 6. 风险（仅列 Vaadin/T1 新增与改写项；其余沿用 v1 风险表）

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| V1 | **Node/npm/Vite 构建链**：本仓库零前端工具链；内网/离线环境可能拉不到 npm 包 | 中高 | M0 第一项就验证生产构建；CI 加 npm 缓存；内网准备私有 npm 镜像（verdaccio/npmmirror）；把 `frontend/` 依赖锁死并提交 lockfile |
| V2 | **两处 JS 包装件的维护**（CodeMirror/ECharts 与 Vaadin 版本兼容、社区 addon 停更） | 中 | 不依赖第三方 addon，自己写最小包装件（各 1 个 `Component` 子类 + 必要 JS）；版本锁定；升级 Vaadin 时只回归这两个件 |
| V3 | **Vaadin 会话模型**：UI 状态在服务端、长任务会占住会话 | 中 | 所有 IO 走后台线程池 + `UI.access()`；对会话超时给明确提示与"重连"按钮；单机单用户场景不做集群会话 |
| V4 | Vaadin 版本升级节奏 | 低 | 跟 LTS 线；M0 锁定补丁版本；升级只影响两个 JS 包装件 |
| V5 | 服务端接口契约漂移（两边并行开发） | 中 | **批次 1 契约先冻结 + 桩响应**（已定），access 层对契约写"契约测试" |
| V6 | 结果网格在浏览器端的大数据量表现 | 中 | Grid 必须用 `DataProvider.fromCallbacks` 懒加载（不得全量塞 `setItems`）；M0 按"**100 万行 / 50 列 / 单值 1 MB 截断**"压测（验收数字见 M0 清单第 1 节） |
| V7 | 复制到剪贴板需要 JS（`navigator.clipboard`）且要求安全上下文 | 低 | 本机 `localhost` 属安全上下文；做降级（选中文本 + Ctrl+C） |
| V8 | **Vaadin 依赖在 aliyun 镜像上有缺口**（实测 `vaadin-core` 可解析，但 `vaadin-spring-boot-starter` / `vaadin-bom` 的 24.4.0 解析失败） | 中 | M0 第 1 项第一步就要判定"版本不存在"还是"镜像缺件"；必要时为 Vaadin 配独立仓库或改用 `vaadin-core` + 显式 servlet |

---

## 7. M0 验证清单（**12–16 人日 ≈ 0.6–0.8 人月**，两人并行 1.5–2 周）

> 可执行的任务步骤、验收数字与报告模板见 **[javagui-m0-checklist.md](javagui-m0-checklist.md)**（含口径更正：原写 0.5 人月是 JavaFX 三项验证的口径，Vaadin 下多了两个 JS 包装件与启动器）。

| # | 验证项 | 决定性 | 失败退路 |
|---|---|---|---|
| 1 | **Vaadin 24 + Spring Boot 3.5 跑通，并完成一次生产构建**（Vite/生产 bundle），CI 可用 npm 缓存构建 | 决定整个方案是否成立（零前端工具链 → 有） | 让 Vaadin 插件自装 Node / 降 Node 版本；仍不行则回 T3（扩展现有 console.js）或 JavaFX |
| 2 | **Grid 懒加载 + JDBC 游标分页**：`DataProvider.fromCallbacks` 接 JDBC，10 万行滚动测帧率与内存 | 同时定网格架构与"百万行怎么取数" | 游标不可用 → `LIMIT/OFFSET` 或给服务端补 portal 支持（服务端可改） |
| 3 | **CodeMirror 6 与 ECharts 的 Vaadin 包装件**：标准 `Component` + `@JsModule` 集成，含 SQL 高亮与图表联动 | 决定 SQL 工作台与报表的体验上限 | 换 Ace / Chart.js；再不行该模块退化为 TextArea / 静态图 |
| 4 | **启动器**：一条命令/一个脚本 → 起控制台 → 起本机 dbserver → 打开默认浏览器；并用 Playwright 跑通一条端到端冒烟 | 决定"双击即用"的交付形态 | 退化为手动两步启动（可接受，但文档要写清） |

**产出**：可复用骨架（应用壳 + access 层雏形 + SQL 执行页 + ECharts 示例 + 两个 JS 包装件）+ 一页验证报告（构建耗时、10 万行帧率与内存、包装件集成结论）。

---

## 8. 与 v1 的差异清单

| 类别 | 内容 |
|---|---|
| **作废** | ① 纯桌面 JavaFX；② 独立 `slackerdb-client` 模块与跨模块 enforcer；③ `jpackage` 三平台原生打包；④ 工程约定"不引入 Spring"；⑤ JavaFX 内置图表/RichTextFX 编辑器 |
| **改写** | ① UI 形态 → 本机 Web 控制台；② 访问层 → 应用内 `access` 包 + ArchUnit；③ 编辑器 → CodeMirror 6；④ 图表 → ECharts；⑤ 发行形态 → jar + 启动器（可选 app-image）；⑥ M0 清单 → 第 7 节四项；⑦ 测试 → access 层 JUnit + Playwright 冒烟 |
| **未变（仍然有效）** | 服务端改造清单（14 项，必做 ≈38 人日）与"契约先冻结"；不做数据写回；报表不引入表达式语言 / 不做屏幕分页 / 块类型 7+富文本；PDF 后置；表单化编辑替代设计器；调度 UI 三态 + 一键恢复；不做 Quartz 持久化；不做 HOP 体检向导；管理端口鉴权本期不做；批次 1/2/3 排期；取数 JDBC 为主、异步面板放 M2 |

---

## 9. 决策记录（最终 24 条）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 服务端是否可改 | 可自由改造（改造清单见 v1 第 5 节，必做 ≈38 人日） |
| 2 | MCP 管理范围 | 仅管理 SlackerDB 自身 |
| 3 | 客户端形态 | **本机 Web 控制台（Vaadin 24），非桌面应用** |
| 4 | 部署拓扑 | **T1 独立进程，不紧耦合**（UI 只走 HTTP/WS/JDBC，可管多实例；服务端零改动配合） |
| 5 | 访问层形态 | **应用内 `access` 包**，无独立 jar；ArchUnit 守边界；自带 JUnit 集成测试 |
| 6 | 两处 JS 包装件 | ✅ **已确认**：CodeMirror 6（编辑器，MIT）+ ECharts 5（图表，Apache-2.0）；另含几行剪贴板片段（不算包装件）；集中 `view/jsbridge`、版本锁定 |
| 7 | 前端构建链 | ✅ **已确认接受** Node/npm/Vite（本机实测 Node 24.19 + registry 可达）；内网需私有 npm 镜像；P9 计 6 人日 |
| 8 | 数据写回 | 不做（网格只读，改动走 SQL 编辑器） |
| 9 | 调度器 SQL 型任务 | 修成真正可执行（走 JDBC） |
| 10 | Quartz 持久化 | 不做；UI 三态提示 + 一键恢复 |
| 11 | 报表模板格式 | JSON + `$schema` |
| 12 | 报表能力边界 | 不做表达式语言（改声明式格式化/条件高亮/条件显示）；不做屏幕分页；块类型 7 种 + 富文本段 |
| 13 | PDF 时序 | 屏幕查看为主 → PDF 后置（R3） |
| 14 | 报表编辑器 | 一期表单化编辑；设计器留 M4 |
| 15 | 取数通道 | 两条都做；M1 只用 JDBC，异步面板放 M2 |
| 16 | 服务端推进方式 | 批次 1/2/3；**批次 1 契约先冻结 + 桩响应** |
| 17 | 管理端口鉴权 | 本期不做；产品层强制本机绑定 + 文档强提示 |
| 18 | 调度 UI | 三态可视化 + 一键恢复（恢复前警告 RUN_ONCE 会重跑） |
| 19 | HOP 体检向导 | 不做；可选池保留只读 `/scheduler/config`（≈1 人日） |
| 20 | 发行形态 | 控制台独立发行（jar + 启动器，可选 app-image 带 JRE）；不塞进服务端 dist |
| 21 | M0 范围 | **已改版为 4 项、12–16 人日 ≈ 0.6–0.8 人月**：Vaadin+生产构建、Grid 懒加载+JDBC 取数、两个 JS 包装件、启动器+端到端冒烟；执行步骤见 [javagui-m0-checklist.md](javagui-m0-checklist.md) |
| 22 | 与 `dbcli` 的对齐 | **对齐**：集成测试用系统属性开关、依赖用 enforcer、列类型/NULL/错误文案以 dbcli 规范为准；**不抽公共数据通道模块** |
| 23 | 数据规模上界 | ✅ **已确认：单结果 100 万行 / 50 列 / 单值超 1 MB 截断**（作为 M0 网格压测的验收标准） |
| 24 | 控制台集成测试方式 | ✅ **已确认：甲为主 + 乙可选** —— `-Dconsole.it.url=...` 属性开关（默认跳过，与 dbcli 一致）为主，`-Pembedded-it` 走嵌入式 `DBInstance` 为可选 |
| 25 | SQL 底层复用策略 | ✅ **R3：复制小规则 + 借鉴契约，不依赖 `dbcli` 模块**（复制 `SqlColumnTypes`、`StatementSplitter`+`LexicalScanner`，各配同源测试；`ResultModel` 只照形状；`JdbcSqlExecutor`/`SqlAnalyzer` 不拉） |
| 26 | 控制台产品定位 | ✅ **收缩**：不追"DBeaver 平权"；差异化价值 = 管理面 + 报表/图表 + 常驻调度可视化 + 带类型的好网格与元数据树 |
| 27 | WS 异步执行面板 | ✅ **不排期**（原 M2 计划取消；脚本化长任务交给 `dbcli` 更有优势） |
| 28 | 与 `dbcli` 的分工（原 D2 的建议答案） | ✅ **只做文档/UI 文案分工**：常驻 ETL → 控制台；一次性脚本/比对/远程文件 → dbcli；临时看数据 → 控制台；本期**不做技术统一** |

**待决**

| # | 待决项 | 选项 | 影响 |
|---|---|---|---|
| D3 | M0 代码授权 | 放行 / 暂不放行 | 未授权则方案停在文档层；M0 是"能不能做"的验证，不做等于把风险推到 M1 |
