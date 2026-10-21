# M0 验证任务清单（Vaadin + T1）

> 配套：[javagui-platform-plan-v2.md](javagui-platform-plan-v2.md)（定稿方案）第 7 节。
> **纪律**：M0 只写"回答能不能"的验证代码；**不改 `dbserver`、不改 `dbcli`**。骨架允许丢弃重写，唯一必须留下的是**一页验证报告**。
> 状态：**尚未授权写代码**（需求方当前要求"先不写代码"）；本文是把 M0 变成可执行清单，授权后照此执行。

---

## 1. 已定的验收数字（M0 用它们判合格）

| 项 | 数值 | 来源 |
|---|---|---|
| 数据规模上界 | **单结果 100 万行 / 50 列 / 单值超 1 MB 截断** | 已确认 |
| 网格滚动 | 连续滚动 10 秒，平均帧率 **≥ 50 fps**（目标 60） | 本文设定 |
| 内存 | 滚完 100 万行 vs 10 万行，**进程内存增长 < 30%**（即不得随行数线性增长） | 本文设定 |
| 首屏可用 | 连接 → 结果首屏 ≤ **2 秒**（本机） | 本文设定 |
| 集成测试 | `-Dconsole.it.url=...` 开关式为主（默认跳过）；`-Pembedded-it` 为可选自包含 | 已确认（甲为主 + 乙可选） |

> 这些是**验收线**，不是硬门禁：不达标时必须在报告里给出**实测值 + 是否可接受 + 需要什么改造（含服务端改动与人日）**，不允许只写"不达标"。

---

## 2. V1：Vaadin 24 + Spring Boot 3.5 + 生产构建（3–4 人日）

**目标**：证明"零前端工具链的仓库"能长出 Vaadin 应用，并且**生产构建**（不是 dev 模式）能过。

**步骤**

1. **先摸清依赖坐标与镜像缺口（这是已知的第一个坑）**：此前实测 —— `com.vaadin:vaadin-core:24.4.0` 能从 aliyun 解析，**但 `com.vaadin:vaadin-spring-boot-starter:24.4.0` 与 `vaadin-bom:24.4.0` 解析失败**。必须判定是"版本不存在"还是"镜像缺件"：
   - 用 `vaadin-spring-boot-starter` 的**实际存在版本**（镜像 metadata 覆盖 24.x/25.x）重试；
   - 若仍失败，检查是否缺传递依赖，必要时为 Vaadin 配置独立仓库（或改用 `vaadin-core` + 显式注册 `VaadinServlet`）。
2. 建模块 `ui/slackerdb-console`（Spring Boot 3.5，继承父 pom 已 import 的 `spring-boot-dependencies:3.5.14`），跑一个"Hello 视图"。
3. 跑一次**生产构建**（`-Pproduction`），并让 CI 第二次构建走 npm 缓存。
4. 记录：首次构建下载量/耗时、二次构建耗时、产物大小、启动到可用耗时。

**验收**：`mvn -pl ui/slackerdb-console package -Pproduction` 成功；`java -jar` 起服务后浏览器可访问；上面四项数据齐全。

**退路**：starter 坐标/镜像不通 → `vaadin-core` + 显式 servlet；Node/Vite 不通 → Vaadin 自装 Node 或降 Node 版本；仍不通 → 回 T3（扩展现有 `console.js`）或 JavaFX。

---

## 3. V2：Grid 懒加载 + JDBC 取数（4–5 人日，**最关键**）

**目标**：一次回答两个问题 —— 网格架构、以及"100 万行怎么取"。

**步骤**

0. **（新增前置，≈0.5 人日）先读 `dbcli` 的两处契约，再定控制台执行层的接口**：
   - `dbcli/assertion/ResultModel`（typed rows + `headers` + `columnTypes` + `status` + `errorCode`）→ 控制台的结果模型**照这个形状定义**；
   - `dbcli/sql/SqlExecutor` 的接口文档（`cancelCurrentStatement` / `setQueryTimeout` / `setAutoCommit` / `executeAll` 的语义）→ 控制台执行层照这套语义自研，但**必须带分页**（`JdbcSqlExecutor` 没有 `setFetchSize`/`setMaxRows`，是全量物化，不能直接拿来用）。
   - 顺带确认要复制的两个类：`sql/SqlColumnTypes`（60 行）与 `engine/StatementSplitter`+`LexicalScanner`（≈340 行），各配一个同源行为测试。
1. **先只在服务端侧做能力探测（不写 UI）**，用 JDBC 对比三种取数方式，各测 10 万 / 100 万行：
   - 甲：`autoCommit=false` + `setFetchSize(n)`（游标 / portal 行为）
   - 乙：`LIMIT/OFFSET` 深分页（记录 offset 到 50 万、100 万时的耗时曲线）
   - 丙：WS `/sql/ws` 的 `exec`+`fetch` 分页（作为对照，注意它无列类型）
2. 造数：用 `COPY FROM STDIN`（仓库已有高性能导入）或 DuckDB 生成函数造 100 万行 × 50 列；同时造一个"单值 > 1 MB"的列用于截断验证。
3. 用 `DataProvider.fromCallbacks` 把选定的取数方式接进 Vaadin Grid，做窗口化（禁止全量 `setItems`）。
4. 记录：滚动帧率、内存、首屏时间；三方案的耗时/内存对比表。

**验收**：达到第 1 节全部数字；并给出**取数方式结论**（甲/乙/丙，及是否需要服务端配合）。

**退路**：游标不可用 → 服务端补 portal 支持（服务端可改，需估人日）或退 `LIMIT/OFFSET` + UI 提示"深分页较慢"。

---

## 4. V3：两个 JS 包装件（3–4 人日）

**目标**：证明两处 JS 能以最小 `Component` 包装件的形式稳定集成。

**步骤**

1. **CodeMirror 6**：SQL 高亮、行号、`Ctrl+Enter` 执行回调、只读/编辑两态、内容读写 API（Java ↔ JS）。
2. **ECharts 5**：按需构建（只引 bar/line/pie + tooltip/legend/toolbox）、`resize` 跟随布局、导出 PNG、接收 Java 侧的数据（列式结构）。
3. 两者都进生产构建，记录 bundle 体积。

**验收**：两个组件可编译可运行；生产产物 **gzip 后总量建议 ≤ 700 KB**（记录实测值）；ECharts 按要求按需构建而非整包引入。

**退路**：CodeMirror → Ace；ECharts → Chart.js；再不行该模块退化为 `TextArea` / 静态图（但那样 SQL 工作台体验不成立，需回头复议）。

---

## 5. V4：启动器 + Playwright 端到端（2–3 人日）

**目标**：证明"双击即用"的交付形态成立。

**步骤**

1. 启动器（jar 或脚本）：起控制台 → 探测并（可选）启动本机 `dbserver`（读 `conf/dbserver.conf` 的 `port`/`port_x`、`pid` 文件）→ 打开默认浏览器。
2. Playwright 冒烟：打开页面 → 填连接 → 执行 `SELECT 42` → 断言结果表出现 → 关闭。
3. 记录：从执行启动器到页面可用的耗时；服务端已在运行时是否复用而不重复启动。

**验收**：一条命令跑通全流程；Playwright 冒烟在 CI headless 下绿。

**退路**：退化为两步启动（先起服务端、再起控制台），文档写清。

---

## 6. M0 工时与排期（口径更正）

| 项 | 人日 |
|---|---|
| V1 Vaadin + 生产构建 | 3–4 |
| V2 Grid 懒加载 + JDBC 取数（含造数与三方案对比） | 4–5 |
| V3 两个 JS 包装件 | 3–4 |
| V4 启动器 + 端到端冒烟 | 2–3 |
| **合计** | **12–16 人日 ≈ 0.6 – 0.8 人月** |

> **口径更正**：v2 第 7 节原写 M0 = 0.5 人月，那是 JavaFX 方案下"三项验证"的口径；改成 Vaadin 后多了 ECharts/CodeMirror 两个包装件与启动器，正确的量级是 **0.6–0.8 人月**。两人并行可压到 **1.5–2 周日历时间**（V2 与 V3 可并行）。

---

## 7. 交付物清单

1. **骨架**：`ui/slackerdb-console` 模块（应用壳 + `access` 层雏形 + 一个 SQL 执行页 + ECharts 示例 + 两个包装件 + 启动器 + Playwright 冒烟）。
2. **一页验证报告**（模板见下）。
3. **结论**：三选一 —— ① 确认可行，进 M1；② 个别项不达标，给出改造项与增量人日；③ 整体不可行，回退 T3 或 JavaFX。

### 验证报告模板

| 验证项 | 目标 | 实测 | 结论 | 后续动作 |
|---|---|---|---|---|
| V1 生产构建 | 构建成功 + 记录耗时/体积 | | | |
| V2 取数方式 | 三种方式耗时/内存对比 | | | |
| V2 网格 | ≥50 fps、内存增长 <30%、首屏 ≤2s | | | |
| V3 CodeMirror | 集成可用 + 体积 | | | |
| V3 ECharts | 按需构建 + 体积 | | | |
| V4 启动器 | 一条命令 + 端到端绿 | | | |

---

## 8. M0 期间就要遵守的仓库约定（与 `dbcli` 对齐）

1. 集成测试用系统属性开关（`-Dconsole.it.url=...`，对称于 dbcli 的 `-Ddbcli.it.url`），**默认跳过**；可选 `-Pembedded-it` 走嵌入式 `DBInstance`。
2. 依赖方向：`maven-enforcer-plugin` 管依赖 + **ArchUnit 管包级规则**（`access` 包不得 import Vaadin/Javalin；`view` 包不得直接用 `HttpClient`/`DriverManager`）。
3. 列类型名 / NULL 表示 / 错误文案**以 `dbcli` 既有规范为准**（`SqlColumnTypes.nameOf` 等），避免同一产品出现两种写法。
4. 文档随代码更新：新增决策写进 v2 第 9 节；踩坑写进对应模块的设计文档。
5. **不修改 `dbserver` 与 `dbcli`**：M0 若发现必须改服务端才能达标（例如游标/portal），只**记录改造项与人日**，留到 M2 的批次里做。
