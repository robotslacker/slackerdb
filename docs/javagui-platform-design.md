# SlackerDB JavaFX 桌面平台 —— 详细方案（v1 历史稿）

> ## ⚠️ 本文为 v1 历史稿（按 JavaFX 桌面撰写）
> 定稿已改为 **Vaadin 本机 Web 控制台 + T1 独立进程**：见 **[javagui-platform-plan-v2.md](javagui-platform-plan-v2.md)**。
> 本文仍然有效的部分：A（报表范围与路线，仅"图表实现"换 ECharts）、B（SQL 工作台边界，仅"编辑器/网格实现"换 CodeMirror + Grid）、C（服务端改造取舍与排期，完全不变）、D（ETL 调度 UX，完全不变）。
> 已失效的部分：E（模块划分/依赖/打包/测试——被 v2 第 3 节取代）。
> **另一处已裁**：文中所有"脱敏"相关项（含 `common/` 的脱敏过滤器）**不做** —— 内部工具、无鉴权；控制台不展示敏感字段即可。

> 配套文档：[javagui-platform-assessment.md](javagui-platform-assessment.md)（难度评估与总体方案）
> 本文只讨论方案，不涉及代码改动。文中每个判断都给出理由与验收方式，便于逐条评审。

---

## A. 需求 3 报表：范围与实现路线

### A.1 先给"报表"下一个可交付的定义

一个报表 = **一个模板** + **一组参数** + **一个或多个数据集** + **若干展示块** + **一种导出目标**。

模板是**结构化数据**（可版本化、可 diff、可用普通编辑器改），不是二进制设计器文件。这是"一期不做设计器"仍然能交付价值的前提。

### A.2 模板格式选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **JSON + 自有 schema** | 易读写、易版本化、JavaFX 直接映射、与仓库既有风格一致（调度器模板就是 `defaultSchedulerTask.json`，并已用 `$schema` 保留键的先例） | 排版表达力弱（需要靠块类型覆盖） | **已确认采用** |
| XML（JRXML 风格） | 表达力强、可被外部工具生成 | 解析/校验/迁移都重，且容易滑向"自己造一门报表语言" | 不采用 |
| 嵌入式脚本/表达式语言 | 灵活 | 需要沙箱、调试、文档，是长期负债 | **已确认不做**（见 A.3 的声明式替代） |

模板首行带 `"$schema": "slackerdb/report/v1"`，便于后续兼容升级。

### A.3 模板模型（具体字段）

```
report
├─ name / version / description
├─ datasets[]            id, source(jdbc|dataService), sql 或 (apiVersion,apiName,method),
│                        params[](绑定报表参数), cacheTtlSeconds
├─ parameters[]          name, type(string|number|date|bool|list), required, defaultValue, description
├─ blocks[]              顺序即渲染顺序
│   ├─ title             text, level
│   ├─ richtext          paragraphs[](text, bold, color, size)   ← 已确认需要
│   ├─ text              text（支持 ${param} 占位）
│   ├─ table             datasetId, columns[](field,label,width,align,format), autoColumns,
│   │                    showHeader, zebra, rowsPerPage, repeatHeaderOnPageBreak
│   │                    （不做分组小计块 → 用 SQL 预聚合）
│   ├─ chart             datasetId, chartType(bar|line|area|pie|scatter), categoryField,
│   │                    series[](field,label,color), title, legend, axisLabels, maxCategories
│   ├─ image             path 或 datasetId+field(base64)
│   └─ spacer / pagebreak
├─ formatting            声明式格式规格（替代表达式语言，见下）
│   ├─ columnFormat      { field: "#,##0.00" | "yyyy-MM-dd" | "0.0%" | ... }
│   ├─ highlightIf       { field, op(<,<=,>,>=,=,!=), value, style(fg/bg/bold) }
│   └─ visibleIf         { param|field, op, value }   作用于块或列
└─ export                defaultFileNamePattern, paper(A4|A3|Letter), orientation, margins
```

**已确认的三条边界**（第一轮评审结论）：

1. **不做表达式语言**，改为上面 `formatting` 这一层**声明式规格**：列/单元格格式化、条件高亮（单层比较，不支持嵌套逻辑）、条件显示。它是一个**数据规格**而不是语言 —— 不需要解析器、沙箱和文档，将来扩展也只是增加"格式指令"。增量 **+5 人日**。
   - 需要 `if/运算/聚合/自定义函数` 的场景一律下沉到 SQL（用户已经会 SQL，这也是本平台的一贯立场）。
2. **不做屏幕所见即所得分页**：屏幕上滚动 + 分页标记，只有导出 PDF 时才做真正的分页。
3. **块类型 = 7 种基础块 + 富文本段**（title / richtext / text / table / chart / image / spacer / pagebreak）。富文本段用于"多段落、粗体/颜色混排"的说明与结论文字，增量 **+3 人日**。
   - **明确不做**：表格内分组小计（用 SQL 预聚合）、交叉表/pivot、并排多列区块、条码二维码、页眉页脚块。

### A.4 渲染与导出路线

| 目标 | 技术 | 主要难点（必须排进工期） |
|---|---|---|
| 屏幕预览 | JavaFX 布局（`VBox` + `TableView` + `javafx.scene.chart`） | 大表格虚拟化与预览性能 |
| **PDF** | **Apache PDFBox 逐块绘制** | ① 中文字体嵌入（复用仓库已有 `NotoSansSC-Regular.ttf`，`PDType0Font.load`）；② 表格跨页断行 + 表头重复；③ 列宽按权重/内容测量；④ 图表用 JavaFX `snapshot()` 转 PNG 再嵌入；⑤ 页眉页脚与页码 |
| Excel | Apache POI（`poi-ooxml`） | 体积大，建议按需加载；表格块→sheet，图表块→锚定图片 |
| CSV / JSON | 直接序列化数据集 | 无 |
| PNG | `Node.snapshot()` | 无 |

> **不用 HTML/CSS 的代价就落在这里**：没有"HTML 模板 → 浏览器打印成 PDF"这条捷径，PDF 必须自己排版。因此**建议 PDF 导出不要和一期报表同时交付**，而是先交付"屏幕预览 + CSV/Excel"，PDF 作为紧随其后的独立小项（约 13 人日，最容易超支的一项）。

### A.5 替代"设计器"的一期编辑体验

不做拖拽设计器，但要给一个**能用的编辑界面**：

- 左侧：块列表（可上移/下移/复制/删除/启用）
- 中间：选中块的属性表单（字段由块类型决定，带下拉而非手写 JSON）
- 右侧：参数与数据集编辑（SQL 编辑器直接复用 SQL 工作台的编辑器组件）
- 顶部：`运行预览`（参数输入弹窗 → 渲染）
- 底部：`导出` / `另存模板` / `从文件导入模板`

这覆盖了绝大多数"做一张报表"的实际需求，成本约 15 人日；而真正的拖拽设计器要 3–6 人月，且**不改变报表能力上限**，只改变编辑效率。

### A.6 与调度器结合（报表刷新）

- 报表刷新 = 调度器任务；输出落 `<runHome>/data/`（`SchedulerController.createRunDirectory` 已经建好 `data/`，是天然落点）。
- **前置依赖**：SQL 类型的调度任务当前被执行器拒绝（见评估文档 5.1 第 7 条）。方案上有两条路：
  - **(推荐) 让 SQL 任务真正可执行** —— `ProcessTaskExecutor` 目前只跑子进程；增加"SQL 任务走 JDBC 执行"的分支，成本低，且对"纯 SQL ETL""报表刷新"价值很大；
  - 或者报表刷新一律包装成 SHELL 命令（不推荐：依赖用户环境里有客户端）。
- 报表任务的失败/重试语义直接复用 `failPolicy`/`retryTimes`，GUI 不用另造一套。

### A.7 验收标准

1. 给定模板 + 参数，重复运行产出**一致**的表格与图表；
2. 10 万行表格导出 PDF 不 OOM（分页写入，不允许全量拼字符串）；
3. 中文不乱码（字体子集嵌入，且 PDF 在别的机器可读）；
4. 参数缺失/类型错误给出明确中文报错；
5. 模板带版本号，未知版本给出"模板由更新版本创建"的提示而不是崩溃。

### A.8 分期

| 期 | 内容 | 人日 |
|---|---|---|
| R1 | 模板模型 + 参数 + 数据集 + 表格块 + 屏幕预览 + CSV/JSON 导出 + **声明式格式/高亮/条件显示** | 27 |
| R2 | 图表块（JavaFX 内置图表）+ 图表导出 PNG | 10 |
| R3 | PDF 导出（PDFBox，含 CJK 与跨页表格）—— **排在预览可用之后** | 13 |
| R4 | Excel 导出 + 多数据集/主从块 + 图片块 + **富文本段** | 10 |
| R5（可选） | 可视化设计器 | +60～130 |

**合计 ≈ 60 人日 ≈ 2.9 人月**（不含可选设计器）。

---

## B. 需求 1 SQL 工作台：功能边界

### B.1 一期做 / 二期做 / 明确不做

| 归属 | 功能 |
|---|---|
| **一期** | 连接管理（多连接、可保存）、多标签、SQL 编辑器（高亮/行号/注释/多语句/选中执行）、结果网格（虚拟化/类型化/NULL/排序/列宽/复制单元格与行）、错误定位（PG 错误码 + 位置）、执行中取消、元数据树（schema/表/视图/列/类型，懒加载）、CSV/JSON 导出、本地执行历史 |
| **二期** | Excel 导出、会话管理（查看 `/status.sessions` + KILL SESSION）、批量导入（COPY IN）、EXPLAIN 结果树形展示 |
| **明确不做（已决策）** | **数据写回 / 单元格编辑 / DML 脚本生成**、ER 图、DDL 生成/对比、跨库数据同步、SQL 语义级智能补全、服务端对象管理（建表/改表） |

### B.2 取数路由（写死规则，避免实现时反复纠结）

| 场景 | 通道 | 理由 |
|---|---|---|
| 默认执行 | **JDBC（pgwire）** | 有列类型、有元数据、有标准错误码；重复列名可按列序号区分（WS 通道会因按列名做 key 而丢列） |
| 明确的长查询 / 不想占用交互连接 | **WS `/sql/ws` 异步** | 服务端异步 + 分页 + 可取消；注意一个 session 同时只能跑一个任务 |
| 数据服务调用 | 管理口 REST | 只有这一条路 |

**交付时序（已确认）**：**M1 只做 JDBC 通道**（编辑器、网格、元数据树全部走 JDBC），**异步面板放 M2**。理由：M1 的价值在于"日常查询能用"，而 JDBC 通道已经覆盖；异步面板还需要服务端补列类型（批次 3 第 10 项）配合才完整，晚一期做更顺。客户端 SDK 的两个子模块都在 P0 里交付，只是 UI 面板分两期接上。

### B.3 结果网格：本项目最重的单点

**必须提前验证的两个技术风险（放进 M0）**

1. **游标式分页是否可用**：`connection.setAutoCommit(false)` + `statement.setFetchSize(n)` 在 pgjdbc 语义下会走 portal；SlackerDB 服务端是否完整支持"打开着的 portal 跨多次 Execute 取数"**尚未验证**。若不可用，退化方案按优先级：① 服务端补 portal/游标支持；② `LIMIT/OFFSET` 窗口化（深分页会变慢）；③ 改用 WS 分页通道；④ 单次全量 + 客户端流式消费（仅适用于中小结果）。
2. **10 万行渲染性能**：JavaFX `TableView` 在自定义 cell + 类型格式化下的滚动帧率。

**类型渲染矩阵**

| 类型 | 显示 | 备注 |
|---|---|---|
| INT/BIGINT/DECIMAL | 右对齐、千分位 | 大数不转 double |
| BOOLEAN | 勾选图形 | |
| DATE/TIMESTAMP/TIME | ISO-8601 | 服务端 `JsonValues` 也按 ISO 输出，保持一致 |
| BLOB/BYTEA | `<binary N bytes>` + 十六进制查看器 | 单值 > 1 MB 只显示占位 + 按需加载 |
| LIST/ARRAY | `{a,b,c}` 可展开为多行 | 服务端已按 PG 数组文本发送 |
| STRUCT/MAP | 文本（不可展开） | 服务端退化为文本，客户端不要假装能结构化 |
| JSON | 格式化查看器 | |
| NULL | 斜体灰 | 与空字符串必须可区分 |

**单值截断阈值建议**：1 MB（超过则占位符 + "加载完整值"按钮）。

### B.4 数据写回：已决策不做（记录理由，避免后期反复）

**结论：一期、二期都不做数据写回** —— 网格只读，修改数据一律回到 SQL 编辑器（`UPDATE/DELETE/INSERT` 本来就能写，且用户能看见完整的语句与事务边界）。

做这个决定的技术依据（留档，供将来重新评估时参考）：

- 底层是 **DuckDB（分析型引擎）**，表**普遍没有主键/唯一约束**；
- 没有主键时客户端只能"整行匹配"生成 `UPDATE ... WHERE 所有列 = 原值` —— 一旦有 NULL、浮点、重复行，就是**静默改错行**；
- 服务端也没有"可更新结果集"或行标识辅助。

**因此网格的设计取向随之简化**：不存在"未提交修改"模型、不需要 PK 探测、不需要 DML 生成器、不需要乐观校验；网格只负责**显示、排序、复制、导出**，可以把省下的约 15 人日投到虚拟化性能与类型渲染上。

**配套要求**（否则"只读"会变成体验缺陷）：

1. 网格上提供"复制为 INSERT/UPDATE 语句"以外的辅助要克制 —— 不做自动 DML，但**支持复制单元格/整行/带表头的多行**，方便用户粘到编辑器里改成语句；
2. 编辑器的执行结果对 DML 要**明确显示影响行数**，并提供清晰的事务模式提示（服务端默认 `autoCommit=true`；GUI 需要显式提供"手工事务"开关，否则用户以为自己在事务里）；
3. 元数据树里对"该表是否有主键"给出提示 —— 这决定了用户写 `UPDATE` 时能不能安全地按主键定位，是有实际价值的信息。

### B.5 元数据树

- 数据源：`DatabaseMetaData`（`getSchemas/getTables/getColumns/getPrimaryKeys`）+ 必要时直接查 `pg_catalog`；
- 注意 search_path 语义：服务端数据服务会设置 `search_path`（`APIService.java:188-202`），GUI 必须显式展示当前 schema 而不是"猜"；
- 懒加载 + 节点级刷新；对象操作（建/删/改）一期不做。

### B.6 编辑器

- JavaFX 原生选 **RichTextFX**（已验证可拉取 0.11.2）；备选 RSyntaxTextArea（Swing 组件嵌入 JavaFX 需要 interop，不优先）；
- 高亮规则：关键字/字符串/注释/数字/标识符，正则驱动即可；
- 补全：**只做"名字补全"**（元数据缓存的表名、列名 + SQL 关键字 + 函数名静态表），不做语义分析级补全。

### B.7 验收标准

1. 10 万行结果滚动流畅、内存不随行数线性增长（窗口化生效）；
2. `SELECT` 出错显示 PG 错误码与错误位置，而不是一句异常堆栈；
3. 取消后服务端确实停了（用 `/status.sessions` 或另开会话确认，不能只看 GUI）；
4. 重复列名（`SELECT 1 AS a, 2 AS a`）不丢列；
5. 断开/重连、服务器重启后 GUI 状态自愈（不出现"永远转圈"）。

---

## C. 服务端改造：取舍与排期

### C.1 排期原则

按"**能否解锁客户端功能**"排序，而不是按模块归堆。据此分三批：

**批次 1 —— M1 前置（≈16 人日）：让调度面板与运维面板不"瘸腿"**

| 项 | 改造 | 人日 | 不做会怎样 |
|---|---|---|---|
| 1 | 任务写接口（增/改/删/启停/改 cron 与策略） | 6 | 调度 GUI 只能建实例后只读，需求 4 价值减半 |
| 2 | Run 更新（变量/configJson） | 3 | 改一个变量要重建 run |
| 3 | ~~`createTasksFromApi` 补写 `retryTimes/Interval/mandatory/variables`~~ **已修** | 0 | API 与模板能力已一致（默认 HOP、统一拍平与校验） |
| 4 | 让 SQL 型任务**真正可执行**（`ProcessTaskExecutor` 增加走 JDBC 的分支，替代当前"直接拒绝"） | 2 | 默认值建出来的任务永远跑不起来；报表刷新只能包装成 SHELL |
| 5 | `/status` 脱敏 `mcpLlmKey`；错误信封统一（502/500 文本 → JSON）；备份可下载；`unregisterMCPSource` 别名 | 4 | 客户端要一直写"脏"兼容代码 |

**批次 2 —— M2 前置（≈15 人日）：把"半成品控制面"补成完整控制面**

| 项 | 改造 | 人日 |
|---|---|---|
| 6 | 模板/项目 CRUD（上传/下载/删除/新建，读写 `defaultSchedulerTask.json`） | 5 |
| 7 | 全局历史/执行流水（分页 + 过滤） | 4 |
| 8 | `/scheduler/status` 真实化 + `threadCount` 生效 | 3 |
| 9 | 日志按执行批次切分 + follow/过滤 | 3 |

**批次 3 —— 可选（≈6–10 人日），按需再定**

| 项 | 改造 | 价值 | 建议 |
|---|---|---|---|
| 10 | WS `fetch` 补列类型 + 修重复列名 | 改善异步通道（M2 才启用异步面板） | 建议做（≈4 人日），但**不是必须**（JDBC 已覆盖主流程） |
| 11 | 执行线程池可配（当前固定 10） | 大批量 ETL | 视并发需求 |
| 12 | `/status.sessions` 增补 WS/REPL 会话；SQL 历史查询接口（`sysaux.SQL_HISTORY`） | 会话/审计面板 | 视需要 |
| 13 | 只读 `/scheduler/config`（暴露 home/projectHome/`hop.*`，口令类脱敏） | **体检向导取消后的廉价替代**：让 UI 能提前提示"HOP 未配置"，而不是等任务失败 | ≈1 人日，建议做 |

> **已决策不做：Quartz `JobStore` 换 JDBC 持久化。** 理由：这是单机应用，ETL 任务本身可反复运行，调度态丢失不构成数据风险。代价是每次重启后运行中的 run 会被降级为 STOPPED，因此**把"重启后的恢复"做成 UI 的一等公民**（见 D.2 ④），而不是靠服务端持久化解决。
>
> **已决策不做：管理端口 token 鉴权（本期）。** 现状是 `port_x` 上全链路无校验（注册/删除数据服务、增删 MCP 工具、启停 ETL、备份与上传文件都不校验身份），CORS 为 `anyHost`。本期不做鉴权，**代价必须由产品层承担**：
> 1. 客户端默认且强约束只连 `127.0.0.1` / 回环地址；填非回环地址时必须弹确认并显示风险说明；
> 2. 文档与首次启动提示里明确写"不要把 `port_x` 暴露到网络"；
> 3. 不开工的隐藏成本省下了：开启鉴权会连带改客户端与现有 Web 控制台 `console.js`（它不发送任何 token 头），约 4 人日。
> 若将来要补，接口上按"可选校验 + 默认关闭"设计，避免破坏本地零摩擦体验。

### C.2 兼容性与风险控制（硬约束）

1. **只增不改**：新增接口不改变既有接口的请求/响应语义；确实要改的（如 `threadCount` 生效、默认 `scriptType`）必须同步改 README 与 `conf/*.conf` 注释。
2. **不碰 pgwire 与 `pg_catalog` 仿真**：这是 DBeaver/psql/pgjdbc 能直连的根本，收益不抵风险。
3. **每个改造项都要有端到端测试**（沿用仓库现有风格：起嵌入式 `DBInstance` + HTTP 断言）。
4. **插件路由不可注销**（`README.adoc:2841,3043`）：调度器插件的接口变更只需"重启服务器"，但要写进升级说明。
5. **批次 1 的接口契约先冻结**（已确认的工作方式）：开工前先输出一份契约文档 —— 每个新增接口的路径、方法、请求字段、响应结构、错误码与语义边界，并在服务端提供**桩响应**。这样客户端与服务端可以并行开发，客户端不会被服务端的实现进度阻塞，也避免"实现出来和预期不一样"导致的返工。契约冻结发生在 M0 之后、M1 之前。

### C.3 已决策

1. **SQL 型调度任务**：**修成真正可执行**（`ProcessTaskExecutor` 走 JDBC 分支，≈2 人日）—— 报表刷新与纯 SQL ETL 直接可用，不必依赖 SHELL 包装。
2. **Quartz 持久化**：**不做**。单机应用 + 任务可反复运行 → 重启丢调度态不构成数据风险。改为在 UI 侧把"重启恢复"做成一等公民（见 D.2 ④）。
3. 因此批次 1 的四项（任务写接口 / Run 更新 / 字段补全 / SQL 任务可执行）确认为 **M1 的硬依赖**，必须先落地。

---

## D. 需求 4 ETL 调度管理：用户体验设计

### D.1 信息架构（严格贴服务端的 Run/Task 模型，不另造概念）

```
项目模板 (projectType, 服务器文件系统)
   └── 实例 Run (runId, 有独立工作目录)
          └── 任务 Task (在 Quartz 里注册，按 group/startupOrder 编排)
```

导航：左栏 `项目模板` / `实例列表`；右栏实例详情分页签：**任务 / 编排 / 历史 / 日志 / 变量 / 配置**。

### D.2 六个关键视图

**① 实例列表**：runId、项目、状态徽标、任务数/启用数、创建时间、启动时间、操作（启动/停止/中止/删除）。
注意"启动"是**异步**的（`STARTING → STARTED`，编排在后台线程），UI 必须轮询并展示进度，不能点完就当成功。

**② 任务表格**（依赖批次 1 的写接口）：

```
名称        类型    执行策略   cron(人类可读)      组    顺序  下次触发            上次结果        操作
load_dim    HOP     CRONTAB   每天 02:00           dim   10    2026-03-01 02:00   成功 2m13s     ▶ ⏸ ✎
load_fact   HOP     RUN_ONCE   —                    fact  20    —                  失败(码 1)     ▶ ⏸ ✎
export_rpt  SHELL   INTERVAL   每 300 秒            rpt   30    —                  运行中…        ⏸ ⛔
```

**③ 编排视图（本系统特有的语义，必须可视化）**

`RunOrchestrator` 的语义是"**同组内 RUN_ONCE 串行执行、成功后该组的周期任务才被激活**"。如果不画出来，用户永远在问"为什么我的 cron 任务没跑"。建议按组分列的泳道图：每列一个 group，列内按 `startupOrder` 从上到下排列，RUN_ONCE 任务画成"闸门"，周期任务画在闸门之后，闸门失败则后续置灰并显示原因。

**④ 状态必须显示三态**（这是当前实现最容易误导人的地方）：

| 态 | 来源 | 丢失条件 |
|---|---|---|
| 定义态 | DuckDB `sysaux.v$scheduler_task` | 不会被丢 |
| 调度态 | Quartz 触发器（RAMJobStore） | **重启即丢** |
| 运行态 | 内存快照（`QuartzJobStatusManager`） | **重启即丢** |

UI 用三个独立的视觉标记；检测到"定义态存在但调度态为空"时，直接提示"服务器重启导致调度丢失，需人工启动"，而不是显示成普通的"已停止"。

**并且必须提供一键恢复**（因为已决定不做 Quartz 持久化，这是唯一的补偿手段）：

- 实例列表上出现"**检测到 N 个实例因重启丢失调度**"的横幅；
- 提供「一键恢复启动」：对所有被降级的 run 依次调用 `/scheduler/run/{id}/start`，逐个显示结果；
- 恢复时必须**明确警告**：服务端的策略是"重新登记后保持暂停、不自动重跑 RUN_ONCE 任务"，而一键恢复会按编排**重新执行 RUN_ONCE 任务**——对可重复执行的 ETL 这正是想要的行为，但用户必须知情（例如"会重新加载维表"）；
- 恢复过程可中断，单个 run 失败不阻塞其它 run。

**⑤ Cron 构建器**：秒/分/时/日/月/周（Quartz 六段）分字段可视化编辑，实时调用 `/scheduler/describeCrontabExpr` 显示**未来 5 次触发时间**——这个接口服务端已经有了，属于白捡的体验。

**⑥ 日志 / 历史 / 变量**

- 日志：按任务选择、tail N 行、自动滚动、关键字过滤、另存为文件。注意同任务多次执行**共用一个 `.log`**（追加写），所以批次 2 第 9 项（按批次切分）之前，UI 要按"本次执行开始时间"打标记来切分，否则用户看到的日志是混的。
- 历史：表格（任务/开始/结束/耗时/返回码/消息）+ 按任务与时间过滤 + **成功率趋势小图**（JavaFX 图表，成本极低）。
- 变量：按来源分层展示（全局 / 模板 / 实例 / 任务 / 框架），框架变量标注"业务不可覆盖"，口令类键显示为 `******` 并标注"仅显示脱敏值"。

### D.3 HOP 环境前置：已决策不做体检向导（附代价与廉价替代）

**结论：不做 HOP 环境体检向导**（省 5 人日）。

**必须知道的代价**：GUI **从任何 API 都读不到 HOP 的配置** —— `plugin-scheduler.hop.runScript` / `hop.javaHome` 等只存在于 `dbserver.conf` 的插件属性里（`ServerConfiguration` 把它们收进 pluginProperties），`/scheduler/*` 与 `/plugin/*` 都没有暴露。因此：

- GUI **无法提前**判断 HOP 是否可用，只能在任务**已经失败之后**、从任务日志里读到 `Hop 运行环境未配置` 这句文案；
- 好在 `HopCommandBuilder` 的报错文案本身是明确的（未配置 runScript / 脚本不存在 / 工作流文件不存在 三种情况分别有不同提示），所以不会出现"失败但不知道为什么"，只是**不提前**；
- 唯一真正静默的坑（任务变量文件写成 KEY=VALUE 而非 Hop 的 JSON 结构，会**静默失效、无任何报错**）在体检向导取消后**没有兜底**，只能靠文档与模板示例规避。

**要提前提示的话，有一个便宜得多的替代**（列入可选池，不改变已定结论）：

| 替代方案 | 做法 | 成本 | 效果 |
|---|---|---|---|
| 只读 `/scheduler/config` | 插件暴露一个只读接口，返回 `home`/`projectHome`/`hop.runScript`/`hop.javaHome`/`hop.projectName`/`hop.environmentName`（口令类字段脱敏），GUI 据此判断"能否跑 HOP 任务"并提前给提示 | ≈1 人日 | 拿到体检向导 5 项里的主要一项（HOP 是否配置），但拿不到 `local.json`、父项目自引用、变量文件格式这三项 |

因此：**UI 侧仍然要做的最低限度**是"任务失败时把日志里的错误文案直接呈现给用户（可复制）"，这属于日志面板的既有功能，不额外计费。

**运维兜底路径（不变）**：环境问题解决不了时，任务可以退化为 `SHELL/COMMAND` 类型执行外部命令。

### D.4 验收标准

1. 从零到跑通的全流程在 GUI 内完成：建模板 → 建 run → 改任务 cron → 启动 → 看历史与日志；
2. 服务器重启后，UI 明确区分"定义还在、调度丢了"，并给出恢复动作；
3. cron 输错即时提示（复用 `describeCrontabExpr`），不需要等到运行失败；
4. HOP 未配置时，不出现"任务失败但不知道为什么"；
5. 删除 run 前必须显示将删除的工作目录路径（`drop` 会删目录，不可逆）。

---

## E. 模块划分与工程约定

### E.1 模块与依赖方向

```
slackerdb (parent)
├── common / plsql / dbdriver / dbserver / dbproxy / plugin*   （已有，GUI 不改其对外契约）
└── ui/
    ├── slackerdb-client   headless：JDK + fastjson2 + slf4j + dbdriver（+ dbserver 仅 test 作用域）
    └── slackerdb-ui       JavaFX：只依赖 slackerdb-client
```

**依赖规则（写进评审结论，并用构建规则机械守住）**

1. `slackerdb-client` **不得**依赖 JavaFX / Swing / Javalin：它是"能被 CLI 复用、能被 CI 直接测"的层。
2. `slackerdb-ui` **不得**直接使用 `HttpClient` / `DriverManager`：所有 IO 走 client，GUI 里不出现协议细节。
3. 服务端模块**不得**依赖 `ui/*`（单向，保证服务端可独立发布）。
4. HTTP/WS 只用 JDK `java.net.http`；JSON 统一 fastjson2（与仓库一致）。
5. **用 maven-enforcer 的 `bannedDependencies` 把上面 1–3 条变成构建失败**（已确认，+0.5 人日）：`slackerdb-client` 禁 `org.openjfx:*`、`javax.swing`（JDK 内置无法禁，改用 ArchUnit 或编译期检查）、`io.javalin:*`；`slackerdb-ui` 禁 `okhttp`/`httpclient5`/`org.postgresql:*`/`duckdb_jdbc`。规则写在父 pom 的 `pluginManagement` 里，各模块按需启用。仅靠代码评审是守不住的。

### E.2 包结构

```
org.slackerdb.client
├─ error/        SlackerdbException, ClientError（吸收 200+retCode / 502 文本 / 500 文本 / HTML 404）
├─ http/         共享 HttpClient、重试、超时、multipart
├─ jdbc/         JdbcSession、类型映射、游标分页、取消、MetadataService
├─ ws/           SqlWsClient（start/exec/fetch/cancel/close，id 关联，分页循环）
├─ mgmt/         MgmtClient（/status /backup /upload /download /viewLog /plugin）
├─ dataservice/  DataServiceClient（token/context/注册表/调用）
├─ mcp/          McpClient（JSON-RPC 2026-07-28 + 管理端点）
├─ scheduler/    SchedulerClient（全部 /scheduler/* + cron 校验）
├─ instance/     ServerProcess（启停/pid/日志跟随）、Discovery（复用 common 的多播）
└─ model/        少量 POJO（ServiceDef、McpToolDef、SchedulerRun、SchedulerTask…）

org.slackerdb.ui
├─ app/          应用壳、导航、生命周期
├─ common/       表格/表单/对话框/状态栏等共用控件、脱敏过滤器
├─ sql/          编辑器、结果网格、元数据树、执行历史
├─ service/      数据服务管理面板
├─ mcp/          MCP 工具/资源/服务面板 + 调试台
├─ report/       报表编辑（表单化）、预览、导出
├─ scheduler/    实例/任务/编排/历史/日志/变量面板
├─ server/       状态、备份、日志、插件、会话面板
└─ settings/     连接、外观、语言
```

### E.3 线程与并发约定

- 所有 IO 在后台线程池（固定 4–8 线程）；JavaFX Application Thread **只**做 UI 更新，用 `Platform.runLater` 回投。
- 每个 SQL 标签一个执行上下文：独占一条 pgwire 连接 + 一个取消句柄 + 一个结果窗口。
- 管理口 REST 共用一个 `HttpClient`（连接池复用）；数据服务调用共用。
- 长查询取消：调 `Statement.cancel()`；必要时再补 `KILL SESSION`（管理端）。
- WS 异步面板按 session 独立：服务端限制"一个 session 同时只能跑一个任务"，UI 必须反映这个限制（同一面板不允许并发提交）。

### E.4 配置与持久化

| 内容 | 位置 |
|---|---|
| 连接配置、窗口布局、编辑器历史 | `~/.slackerdb/ui/`（Windows：`%USERPROFILE%\.slackerdb\ui\`） |
| 报表模板 | 本地目录 + 可导入导出（不写服务端文件） |
| 数据服务/MCP 定义 | **一律走服务端 API**（`/api/saveRegisterService`、`/mcp/saveMCPTool`） |
| UI 日志 | `~/.slackerdb/ui/logs/ui.log`（logback，仓库已用） |

口令：当前服务端无鉴权，凭据字段仅作连接标识；**本期已决策不做管理端口鉴权**，因此不引入凭据存储；若将来补上 token，再用 OS 凭据存储（Windows DPAPI / JCEKS）而不是明文 JSON。

### E.5 错误与提示策略

- 统一 `ClientError{kind, httpStatus, retCode, retMsg, cause}`，`kind` 至少区分：网络不可达 / 未登录或 token 过期 / 服务未注册 / SQL 错误（带 PG 错误码）/ 服务端 5xx / 协议不符。
- 用户可见提示 = **人话 + 下一步动作**（例：*"管理端口未启用：请在 dbserver.conf 设置 port_x=8080 后重启服务"*）；技术细节只进日志文件。
- 能力探测：启动时探测 `/status`、`/plugin/list`、`/scheduler/status`、`POST /mcp` 的 `server/discover`；探测失败的功能**隐藏而非报错**（例如没装调度器插件就不显示 ETL 菜单）。

### E.6 测试策略（分层，这是本项目能控住质量的关键）

| 层 | 手段 | 覆盖 |
|---|---|---|
| client（主力） | JUnit 5 + 嵌入式 `DBInstance`（仓库既有风格）+ HTTP/WS 断言 | 5 类协议的成功与失败路径、错误映射、分页与取消 |
| 服务端改造 | 各模块端到端测试 | 每个改造项至少 1 条 |
| UI | TestFX 冒烟（能在 Monocle headless 下起窗口、连服务、执行、关闭） | 只验证"接线正确"，业务逻辑不写在 UI 里 |
| 手工验收 | 按 A.7 / B.7 / D.4 的清单 | 每期一次 |

### E.7 打包与分发

- 开发期：`mvn -pl ui/slackerdb-ui javafx:run`；
- 发布期：`jlink` 裁剪运行时 + `jpackage` 出 Windows 安装包；
- **不建议把 GUI 塞进服务端发行包**（`dist/slackerdb-dist_*.zip` 的 `jlib/`）：两者版本节奏不同，绑在一起会让"只升级 GUI"变得不可能。建议独立产物：`dist/slackerdb-ui-<ver>-windows-x64.msi`；
- 兼容性靠**能力探测**而不是版本号硬校验（`/status.server.version` 仅用于提示）。

### E.8 代码风格

与仓库保持一致：中文注释、无 licence 头、构造器注入、`public` 类写清"为什么这样设计"；不引入 Lombok/Spring/Guava。

---

## F. 决策记录（供评审勾选）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 报表模板格式 | **已确认：JSON + `$schema` 版本号** |
| 2 | 报表能力边界 | **已确认**：不做表达式语言（改为声明式 `formatting`，+5 人日）；不做屏幕分页；块类型 = 7 种基础 + 富文本段（+3 人日）；不做分组小计/交叉表/并排多列/条码/页眉页脚 |
| 3 | PDF 导出时序 | **已确认：报表以屏幕查看为主 → PDF 后置**（13 人日，排在 R3） |
| 4 | 报表编辑器 | **已确认：一期表单化编辑**（15 人日），可视化设计器留 M4 可选 |
| 5 | 报表刷新复用调度器；SQL 型任务修成可执行 | **已确认** |
| 6 | SQL 工作台不做数据写回 | **已确认（一期二期都不做）** |
| 7 | 数据写回方案 B1/B2 | **已确认：都不做**，修改数据回编辑器写 SQL |
| 8 | 取数默认 JDBC，长查询走 WS | **已确认**：两通道都做，但 **M1 只用 JDBC，异步面板放 M2** |
| 9 | 服务端改造按批次 1/2/3 排期 | **已确认**：按批次推进，且**批次 1 接口契约先冻结 + 服务端提供桩响应**，两边并行 |
| 10 | Quartz JDBC 持久化 | **已确认：不做**；改为 UI 一键恢复 |
| 11 | 管理端口鉴权（默认关闭） | **已确认：本期不做**；代价由产品层承担（强制本机绑定 + 文档强提示） |
| 12 | 调度 UI 必须区分定义态/调度态/运行态三态，并提供一键恢复 | **已确认** |
| 13 | HOP 环境体检向导 | **已确认：不做**（省 5 人日）；代价是 GUI 无法提前感知 HOP 未配置，只能靠任务失败日志；廉价替代（只读 `/scheduler/config`，≈1 人日）列入可选池 |
| 14 | client 不依赖 JavaFX、ui 不碰协议（依赖方向硬约束） | **已确认**，并用 maven-enforcer `bannedDependencies` 机械守住（+0.5 人日） |
| 15 | GUI 独立发行包，不进服务端 dist | **已确认：独立安装包**（`jpackage`，自带运行时） |
| 16 | M0（2–3 周）先验证：JavaFX 打包链路、10 万行网格、**JDBC 游标分页是否可用** | **已确认：三项都验证**（需单独放行写代码） |

**工作量变化记录**

| 轮次 | 变化 | 对"计划内总量"的影响 |
|---|---|---|
| 第 1 轮（数据写回 / SQL 任务 / Quartz） | 不做写回 −15；SQL 任务走 JDBC +1；调度 UI 三态+一键恢复 +2 | **−12 人日**（Quartz 持久化本来就不在计划内总量里） |
| 第 2 轮（报表四条） | 声明式格式化/条件层 +5；富文本段 +3 | **+8 人日** |
| 第 3 轮（取数 / 排期 / 鉴权 / 三态） | 异步面板由 M1 移到 M2（进度变化，总量不变）；不做鉴权 | **0**（鉴权属"可选池"，原本未计入总量） |
| 第 4 轮（HOP 体检 / enforcer / 打包 / M0） | 不做 HOP 体检向导 −5；maven-enforcer 规则 +0.5 | **−4.5 人日** |
| **累计** | | **−8.5 人日** → 计划内总量 ≈ **348 人日 ≈ 16.6 人月**（区间 12–18 人月） |
