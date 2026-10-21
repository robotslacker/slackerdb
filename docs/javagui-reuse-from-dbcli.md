# 复盘：dbcli 完成之后，控制台方案要怎么改

> 触发：`dbcli` 模块（406 个文件、359 例测试、M0–M3 完成、已进 reactor）是在本方案成稿之后才完成/发现的。
> 本文回答一个问题：**在 dbcli 已经做完的基础上回头看，v2 方案哪里要改、哪里不用改。**
> 结论先行：**架构骨架不变（Vaadin + T1 + 应用内 access 层），但有 3 处要改**，总量 ≈ **305 人日 ≈ 14.5 人月**（v2 为 320）。

---

## 0. 结论（3 处改动）

| # | 改动 | 为什么 | 人日影响 |
|---|---|---|---|
| 1 | **SQL 底层"借鉴契约 + 复制小规则"，但不依赖 dbcli 模块** | dbcli 已有一份经 359 例测试验证的 SQL 执行契约与小工具；但它的执行器是 CLI 形态（全量物化、IR 入参），不适合控制台的 100 万行网格 | P0 −6、P1 −10 |
| 2 | **控制台定位收缩：不追"DBeaver 平权"** | dbcli 已经覆盖脚本化执行/REPL/比对/作业/SSH；控制台的差异化价值是"管理面 + 报表 + 调度 + 好用的网格"，SQL 工作台做到"够用"即可 | P1 内含在 −10 里（异步面板不再排期） |
| 3 | **与 dbcli 的分工写进文档（回答 D2）** | 同一产品里将有两套"调度/执行"概念，不写清楚用户会问"我该用哪个" | 0（文档） |

---

## 1. dbcli 资产盘点（带证据与体量）

| 包 | 关键资产 | 体量 | 对控制台的价值 |
|---|---|---|---|
| `sql/` | **`SqlExecutor`（接口，干净的"执行层唯一接缝"）**、`JdbcSqlExecutor`、`SqlColumnTypes`、`CellRenderer`、`SqlTableRenderer`、`ResultRenderers`、`RenderOptions`、`JdbcUrlConnect`、`JdbcDriverLoader` | 接口 5.6KB；执行器 35KB；类型表 2.4KB；渲染 ≈35KB；连接/驱动 ≈19KB | **最高**。类型名、对齐规则、连接串语法、取消/超时/自动提交的语义都在这里 |
| `assertion/` | **`ResultModel`（record：typed rows + headers + columnTypes + status + errorCode）**、`AssertEvaluator` | 1.4KB / 19KB | **高**。正是网格需要的数据模型形状；`_ASSERT` 的比较语义可对齐报表的 `highlightIf` |
| `engine/` | **`StatementSplitter`（顶层 `;` 切分）、`LexicalScanner`（唯一词法规则）、`StatementBoundary`**、`ScriptRunner`、`ScriptSession`、`ScriptOutput` | 4KB / 6KB / 12.9KB；Runner 36.8KB | **高**（前三个）。编辑器"执行当前语句/选区"正需要它 |
| `script/` | `SqlAnalyzer`、`SqlIrVisitor`（ANTLR IR，61KB）、ANTLR 语法 `BaseParser.g4` | 8.6KB / 61KB | 中。语句完整性判定有用，但耦合 dbcli 私有语法与生成代码 |
| `repl/` | `InteractiveConsole`、`JLineLineSource` | 7.7KB / 11KB | 低。基于 jline 的终端交互，控制台用不上 |
| `report/` `compare/` | `ReferenceReport`、`CompareReporter`、`LineDiffer`、`.dif/.suc/.rpt` 格式 | ≈100KB | 低。是**脚本比对报告**，不是业务报表 |
| `job/` | `JobManager`（40KB）、进程/线程执行、`rendezvous/`（SQLite 跨进程会合） | ≈100KB | 低（但概念上与控制台 ETL 重叠 → 见第 5 节） |
| `ssh/` | `SshClient`、21 个 SFTP 动作 | ≈35KB | 本期低 |
| `daemon/` | `CliDaemon`、`PidFile` | 12KB | 中。控制台启动器可参考其 `start|stop|status` + pidfile 形态 |

---

## 2. 逐项对照：我的计划 vs dbcli 已有什么

| 我的工作项（v2） | dbcli 对应资产 | 处置 |
|---|---|---|
| 结果数据模型（列名/列类型/typed 行/影响行数/错误码） | `assertion/ResultModel`（record，**已带 typed rows + 大写列类型名**） | ✅ **照它的形状定义**（复制形状，不复制依赖） |
| 列类型名与对齐规则 | `sql/SqlColumnTypes.nameOf`（60 行，解决 DuckDB 返回小写 `varchar` 导致对齐错） | ✅ **直接复制**（60 行，标注同源） |
| 语句切分（"执行当前语句"） | `engine/StatementSplitter`（88 行）+ `engine/LexicalScanner`（6KB） | ✅ **直接复制**（纯函数、无外部依赖） |
| 多行/块边界判定（PL-SQL 块未写完） | `engine/StatementBoundary`、`script/SqlAnalyzer` | ⚠️ **借鉴思路**：先用"服务器报错即未写完"的简化路径；不引入 ANTLR 私有语法 |
| 执行/取消/超时/自动提交 | `SqlExecutor` 契约（`cancelCurrentStatement`、`setQueryTimeout`、`setAutoCommit`、`executeAll`） | ✅ **照契约自研**（控制台需要 fetchSize/分页，dbcli 实现做不到） |
| JDBC 连接与连接串 | `script/JdbcUrlConnect`（语法 `[user/pw@]jdbc:slackerdb://host:port/db`）、`sql/JdbcDriverLoader` | ✅ **照语法自研**（控制台要多连接、连接池、探活） |
| 结果渲染约定（NULL、数字右对齐、日期） | `sql/CellRenderer`、`SqlTableRenderer`、`RenderOptions` | ✅ **照约定**（控制台渲染到 Grid，不渲染文本表格） |
| 条件高亮 / 条件显示（报表 `highlightIf`） | `assertion/AssertEvaluator` 的比较语义 | ✅ **语义对齐**（同一套 op 与字面量规则），不引入其表达式语法 |
| 元数据树（schema/表/列/主键） | **无** | 🔧 控制台自研（`DatabaseMetaData`） |
| 服务/MCP/调度 REST 封装 | **无** | 🔧 控制台自研（v2 的 access 层主体） |
| WS `/sql/ws` 异步面板 | **无** | ⏸️ **不排期**（dbcli 覆盖脚本化长任务；M2 之前不再规划） |
| 业务报表 + 图表 + PDF | 无（`report/` 是比对报告） | 🔧 控制台自研 |
| 本地启动器（起服务端/开浏览器） | `daemon/CliDaemon` + `common/AppDaemon` 的 `start|stop|status`+pidfile | ✅ **形态对齐** |

---

## 3. 复用策略：三个选项与推荐

| 策略 | 做法 | 成本 | 问题 |
|---|---|---|---|
| **R1 依赖 dbcli 模块** | `console → dbcli` 加一条模块依赖，直接用 `SqlExecutor`/`JdbcSqlExecutor`/`StatementSplitter` | 0 重构 | ❌ 三条硬伤：① `JdbcSqlExecutor` **没有 `setFetchSize`/`setMaxRows`**（全量物化，见其 `getObject` 路径），接不了 100 万行网格；② `connect(Map<String,Object> ir)` 要的是 **`_CONNECT` 命令的 IR**，控制台得先构造 CLI 命令的 IR，形状不对；③ 把 jline/jsch/sqlite-jdbc/picocli 全拖进控制台 |
| **R2 抽公共模块**（如 `slackerdb-sqlkit`） | 把 `ResultModel`/`SqlExecutor`/`JdbcSqlExecutor`/`SqlColumnTypes`/`Statement*` 抽出去给两方用 | 5–8 人日重构 | ❌ 与本项目既有惯例冲突：dbcli 自己的**决策 4** 就是"因为包私有而**放弃**复用 plsql 求值器、改为自研"——仓库惯例是本模块内自洽；且 dbcli 仍在活跃演进（最近 5 个 commit 都是它），抽取会制造持续冲突 |
| **R3 复制小规则 + 借鉴契约**（推荐） | 只复制两个纯函数类（60 行 + ≈280 行），其余按契约自研；借鉴处标注同源 | +1 人日（同源测试） | 存在"上游改了、我们没跟"的漂移风险 → 用同源测试兜住 |

**推荐 R3，理由**：控制台真正需要的"资产"不是 dbcli 的代码，而是它用 359 例测试换来的**决策与契约**（类型名要用大写、顶层分号怎么切、取消/超时/自动提交的语义、NULL 怎么显示、连接串怎么写）。这些东西的**复制成本接近零**（两个类加起来 ≈ 340 行、零外部依赖），而**依赖成本很高**（IR 形状 + 无分页 + 依赖包袱 + 活跃演进）。

---

## 4. 人日修正

| 工作包 | v2 | **复盘后** | 依据 |
|---|---|---|---|
| P0 access 层 | 42 | **36** | JDBC 连接/类型映射/语句切分/取消语义有参考与既定契约，少走 6 人日弯路；其余（mgmt/dataservice/mcp/scheduler/error/测试）不变 |
| P1 SQL 工作台 | 62 | **52** | 编辑器 16→13（复制 splitter、完整性判定简化）、网格 14→12（类型名与对齐规则沿用）、执行/取消 8→6（契约既定）、连接 8→7（连接串语法有参考） |
| 新增：同源测试与来源标注 | — | **+1** | 两个复制类各一个"与 dbcli 行为一致"的对比测试 |
| 其余（P2/P3/P4/P5/P6/P7/P8/P9） | 238 | **238** | 复盘未触及 |
| **合计** | 320 | **305** | **≈ 14.5 人月**（区间由 11–16 变为 **10.5–15.5**） |

---

## 5. 产品层影响（比人日更重要）

### 5.1 控制台的定位要收缩

dbcli 已经覆盖：脚本化执行、交互式 REPL、与参考文件比对、`_JOB` 作业、SSH/SFTP、守护运行。
因此控制台**不应该**去追"DBeaver 平权"，它的差异化价值是：

1. **管理面**（数据服务注册表、MCP 工具/资源/服务、插件）—— dbcli 完全没有；
2. **业务报表 + 图表**—— dbcli 的 report 是比对报告，不是这个；
3. **常驻 ETL 调度的可视化**（run/任务/编排/历史/日志）；
4. **带类型的好网格 + 元数据树**（"看一眼数据"这件事，GUI 比 CLI 强）。

**建议的 P1 取舍**：编辑器只做"高亮 + 行号 + 执行当前语句/选区 + 分号切分"，**不做补全**；异步 WS 面板**不排期**；把省下的精力放在网格与元数据树上。

### 5.2 D2（两套调度/执行语义）的建议答案

| 场景 | 用谁 | 理由 |
|---|---|---|
| 常驻定时 ETL（cron/interval、组与顺序、HOP 工作流） | **控制台 ETL 面板**（服务端 `plugin-scheduler`） | 服务端常驻、重启后仍在（除调度态）、有编排语义 |
| 一次性脚本、批处理、与参考结果比对、远程文件 | **dbcli** | 它就是为此而生，且有 `_JOB` 与 SSH |
| "看一眼数据 / 临时查询" | **控制台 SQL 工作台** | GUI 网格 + 元数据树更快 |
| 体检/运维脚本化 | **dbcli** | 可脚本、可进 CI |

**不做技术统一**（本期）：把这张表写进两边文档与 UI 提示即可。

---

## 6. 新增风险与纪律

| # | 风险/纪律 | 说明与对策 |
|---|---|---|
| D-R1 | **借鉴漂移**：dbcli 仍在活跃演进（近 5 个 commit 都是它），我们复制的 `SqlColumnTypes`/`StatementSplitter` 可能与上游分叉 | 复制处标注来源（类名 + 抓取时的 commit）；各写一个"同源行为测试"；**允许有意分叉**（如控制台需要分页），但必须在注释里写明"此处与 dbcli 有意不同" |
| D-R2 | **不要修改 dbcli** | M0/M1 期间对 dbcli **零改动**（它有自己的并行工作流）；若发现必须改，记录成"对 dbcli 的改造请求"，走它自己的工作流 |
| D-R3 | **三套 SQL 路径**（dbserver 内部、dbcli、控制台）行为可能不一致 | 用"统一约定"兜住：列类型名、NULL 表示、错误文案、状态行文案以 dbcli 为基准；控制台的 access 层测试里对这几项写断言 |
| D-R4 | 误以为"dbcli 有执行器就不用写执行层" | `JdbcSqlExecutor` 无分页、入参是 IR → 控制台**必须**自研带游标的执行器；这是 P0 里不可省的部分 |

---

## 7. 对 v2 的具体修订

1. §4 工作量表：P0 42→**36**、P1 62→**52**、新增"同源测试 +1"，合计 320→**305**（区间 10.5–15.5）。
2. §4.5（与 dbcli 的关系）：把"待决 D1"确认（甲为主 + 乙可选，已生效）；把复用候选的"⚠️ 可选"改为明确结论（**复制两个纯函数类、其余按契约自研、不依赖模块**）。
3. §9 决策表：新增"SQL 底层复用策略 = R3"、"控制台定位收缩（不追 DBeaver 平权）"、"异步 WS 面板不排期"。
4. §5 路线图：M1 的 P1 交付项去掉"异步面板"相关的隐含期待（本来就在 M2，现明确不排期）。
5. 修正失效引用：v2 §4.5 里引用的 `docs/dbcli-design.md` 已被删除，改为引用 `dbcli/` 模块与 `dbcli/README.adoc`。
6. M0 清单：V2 项增加"先读 dbcli 的 `SqlExecutor` 契约与 `ResultModel` 形状，再定控制台执行层的接口"作为前置步骤。
