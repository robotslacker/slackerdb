# 复议：Vaadin 替代 JavaFX、取消独立 client 模块

> 触发：需求方提出「否决 JavaFX 原生桌面方案，建议 Vaadin」「否决专门 client，直接把封装放进程序内部」，
> 并明确「dbserver 及本项目内所有东西都可以改」。
> 本文评估这两条建议，并给出因此被推翻/需要复议的既有决策、修正后的工作量与改版后的 M0。

---

## 0. 结论

| 建议 | 裁决 | 一句话理由 |
|---|---|---|
| **不搞独立 client jar，封装放进程序内部** | ✅ **采纳** | 独立"制品"确实没必要；但"层"必须留（服务端有 5 种错误表达 + 多处命名不一致），只是它从"独立模块"变成"应用内的一个包" |
| **Vaadin 替代 JavaFX** | ⚠️ **有条件采纳** | 在结果网格、免跨平台打包、管理类界面三处明显更优；但必须接受 4 个前提：**构建链引入 Node/npm、SQL 编辑器必须包 JS 组件、图表也必须包 JS 组件、UI 形态变成"本机 Web 服务 + 浏览器"** |

**因为"dbserver 可改"，多出一个必须你来拍的决策：UI 与 dbserver 的部署拓扑（T1 独立进程 / T2 嵌入同 JVM）。** 见第 4 节。

---

## 1. 建议 A：不搞独立 client jar —— 采纳，但有三个条件

### 1.1 为什么"独立制品"没必要

- **更正（原先此处的理由不成立）**：本文初稿写"没有第二个消费者（没有 CLI）"—— 这是错的。仓库里已有成熟的 **`dbcli` 模块**（已进 reactor，M0–M3 完成，自带 JDBC 数据通道 / REPL / 比对报告 / `_JOB` / SSH；手册见 `dbcli/README.adoc` 与 `dbcli/README-CN.adoc`）。但它的存在**并不改变结论，反而支持结论**：dbcli 的做法正是"每个消费方模块自带自己的数据通道，只在有 public facade 时才跨模块复用"（它明确因为 `ExpressionBuilder`/`LocalEvaluator` 是包私有而**放弃复用** plsql 的求值器、改为自研）—— 也就是说，本仓库的既有惯例就是**不提前造共享客户端**；
- 独立模块会带来额外仪式：版本号、发布、跨模块依赖规则、文档 —— 约 3 人日；
- 单机工具场景下，把访问层与控制台打包在一起是合理的。

> 与 `dbcli` 的对齐与复用问题（是否共用数据通道实现、集成测试方式、两个"调度"概念并存）另见 [javagui-platform-plan-v2.md](javagui-platform-plan-v2.md) 第 3.6 节。

### 1.2 为什么"层"不能省（这是证据问题，不是洁癖）

服务端的接口面有大量**必须在某一处统一吸收**的差异，否则这些代码会散落到 40+ 个界面里各写一遍：

| 必须集中处理的东西 | 证据 |
|---|---|
| **5 种错误表达**：HTTP 200 + `retCode:-1`、502 纯文本、500 纯文本、404 HTML、404 JSON | `APIService.java:263-277,628`、`PluginService.java`（一律 200+retCode）、`DBInstanceX.java:352-361,640-643` |
| 路径**命名不一致**：Resource 的 save/dump 用 `Source` 后缀，register/unregister/load 用 `Resource` | `McpServer.java:896-1675` |
| WS 错误帧**可能不带 `id`** → 请求-响应关联要能容忍 | `SqlReplServer.java:731-739` |
| token / context 生命周期（30 分钟滑动过期、重启失效、只在 3 个端点上有效） | `APIService.java:348-352,480-542` |
| WS 分页循环（`fetch` 直到 `hasMore=false`）+ 取消后 taskId 失效 | `SqlReplServer.java:514-623,686-691` |
| **列名去重**（WS 通道按列名做 key，重复列名会丢列） | `SqlReplServer.java:634-640` |
| 日志按执行批次切分（同任务共用一个追加写 `.log`） | `QuartzJob.java:276-283` |
| ~~脱敏~~ | **已裁：不做**（内部工具、无鉴权；控制台不展示敏感字段即可） |

### 1.3 具体形态（三个条件）

1. **一个包，不是散在各处**：例如 `org.slackerdb.console.access.{mgmt,dataservice,mcp,scheduler,ws,jdbc,error}`；UI 只允许通过它访问服务端。
2. **该包禁止出现 UI 技术**：不许 import Vaadin（也不许 import Javalin）。建议用 **ArchUnit** 或 enforcer 守住这条 —— 只需 0.5 人日，但能保证半年后不破口。理由：只有这样才能用普通 JUnit 对着嵌入式 `DBInstance` 做集成测试（不需要浏览器、不需要启动 UI），否则回归测试成本会翻几倍。
3. **它自己带测试**：沿用仓库既有风格（起嵌入式服务器 + HTTP/WS/JDBC 断言）。这套测试就是整个平台的回归主力。

**放弃独立模块会失去什么**：将来若要做 CLI 或批处理工具，不能直接复用 —— 但那时候把包抽成模块比现在先建模块更便宜（抽包几乎零成本，补测试才是成本）。

---

## 2. 建议 B：Vaadin 替代 JavaFX —— 有条件采纳

### 2.1 事实核查（已实测 / 已核实）

| 事实 | 结论 | 证据 |
|---|---|---|
| Vaadin 制品能否拉取 | ✅ `com.vaadin:vaadin-core:24.4.0`、`vaadin-charts-flow:24.4.0`、`vaadin-grid-flow:24.4.0` 均可从当前 aliyun 镜像解析 | 本机 `mvn dependency:get` |
| Vaadin 版本线 | 镜像上有完整的 24.x（至 24.10.11）与 25.x（至 25.3.2） | aliyun `maven-metadata.xml` |
| Java 基线 | **Vaadin 24 要求 Java 17+**（与项目 `maven.compiler.target=17` 一致）；Servlet 6+、Spring Boot 3.0+、**Node.js 18+** | [官方兼容表](https://raw.githubusercontent.com/vaadin/docs/refs/heads/next/articles/compatibility.adoc) |
| 前端构建链 | ✅ 本机已装 Node **v24.19.0**；`npm ping` → **PONG 6676ms**（registry.npmjs.org 可达）→ Vaadin 的 Vite 构建能跑 | 本机实测 |
| Spring 依赖 | ✅ **父 pom 已经 import 了 `spring-boot-dependencies:3.5.14`**（`pom.xml:38-44`），`dbserver/pom.xml` 里也有 spring-boot-starter-test/tomcat → 引入 Spring Boot 版 Vaadin **不与现有依赖管理打架** | `pom.xml`、`dbserver/pom.xml` |
| 商业组件 | Vaadin Flow 平台为 Apache-2.0；**Charts / Grid Pro 等属商业（Pro）组件**。注意：`vaadin-charts-flow` 能从 Maven 拉到 **≠ 可免费商用** | [Vaadin 许可文档](https://vaadin.com/docs/latest/flow/configuration/licenses) |

### 2.2 逐维度对比（只比对本项目真正相关的能力）

| 维度 | JavaFX（原方案） | **Vaadin（本建议）** | 谁更优 |
|---|---|---|---|
| 结果网格（10 万行+） | 自研虚拟化模型，25 人日 | **Grid 自带虚拟滚动 + `DataProvider.fromCallbacks` 懒加载**，与我们要验证的 JDBC 游标分页天然契合；需自写类型渲染器 | **Vaadin 明显更优**（≈ −11 人日） |
| SQL 编辑器 | RichTextFX（Java 组件，BSD） | **平台内没有代码编辑器** → 必须包 Monaco/CodeMirror/Ace（JS）；不包就退化为 TextArea | **JavaFX 更优**（Vaadin ≈ +4 人日，且引入 JS） |
| 图表 | JavaFX 内置 `javafx.scene.chart` | 免费路线 = **Chart.js（MIT）/ ECharts（Apache）包装件**；自带 Charts 是商业组件 | 平（都要写渲染/交互，Vaadin 需多写一个 JS 包装件） |
| 元数据树 / 表单类管理界面（≈30 个 CRUD 屏） | 手写 TreeView/TableView/Form | **TreeGrid + Binder + 现成表单校验**，管理类界面开发速度显著更快 | **Vaadin 明显更优**（≈ −6 人日） |
| 报表排版与 PDF | JavaFX 布局 + PDFBox | Vaadin 布局 + PDFBox（同样要自绘排版；除非改用 HTML+无头浏览器打印，但那与你的偏好冲突） | 平 |
| 跨平台打包 | `jpackage` 三平台 + JavaFX native（22 人日） | **无需按平台打包原生件**：一个 jar（可选 jpackage app-image 带 JRE） | **Vaadin 明显更优**（≈ −12 人日） |
| 构建链 | 纯 Maven，零前端工具链 | **新增 Node/npm/Vite**（本机已验证可达，但 CI 要加 npm 缓存与离线预案） | **JavaFX 更优** |
| 本地能力（启动/停止服务进程、读写配置文件、打开日志目录） | 天然具备 | **只要 Vaadin 服务端跑在本机就同样具备**（浏览器只是显示层；Java 侧照样能起进程、开 `explorer.exe`） | 平 |
| 离线可用性 | 完全离线 | 需要浏览器（本机页面 + 本机浏览器即可；不依赖外网） | 略偏 JavaFX |
| 测试 | TestFX 冒烟 | access 层单测相同；UI 冒烟可用 **Playwright（免费）**，或用 Karibu 做 view 单测 | 平（+2 人日） |
| 会话模型 | 客户端状态在本地 | **UI 状态在服务端**（每个浏览器标签一个 session，长任务必须后台线程 + `UI.access()`）→ 内存与并发要注意 | 略偏 JavaFX |
| 技术栈单一性 | 纯 Java | Java + 少量 JS（编辑器、图表、剪贴板） | 略偏 JavaFX |

**净结论**：Vaadin 在"结果网格 + 管理界面 + 免平台打包"上赢约 **29 人日**；在"编辑器 JS + 图表 JS + 前端构建链 + 会话模型"上输约 **12 人日** → 总盘子约 **−8%**。**这不是"省很多钱"的选择，而是"用前端构建链换网格与打包"的选择。**

### 2.3 Vaadin 会新引入的四项成本（必须接受，不能忽略）

1. **Node/npm/Vite 构建链**：本仓库现在零前端工具链。本机可达已实测，但要准备好：CI 的 npm 缓存、离线/内网构建预案、Node 版本策略（本机 Node 24 高于 Vaadin 24 文档里的 18+，若遇兼容问题可让 Vaadin 插件自装 Node 或降到 LTS）。
2. **SQL 编辑器必然是 JS 组件**（Monaco/CodeMirror/Ace）：这是整个方案里唯一"无法用 Java 解决"的硬缺口。**如果你连这一处都不接受，Vaadin 就不成立**（SQL 工作台是四项需求里最重的一项）。
3. **图表也是 JS 组件**（Chart.js/ECharts）：你已排除商业组件，方向正确；但要知道这是第二个 JS 包装件。
4. **UI 形态变化**：从"一个桌面窗口"变成"本机 Web 服务 + 浏览器"。单机本地访问没有安全与性能问题，但会带来三件小事：浏览器标签即会话（刷新/多标签的行为差异）、长任务必须异步化、以及"开机自启/托盘"这类桌面特性没有了（可以用启动器脚本弥补）。

### 2.4 免费组件对应关系（替代商业组件的方案）

| 需求 | 商业（不用） | 免费替代 |
|---|---|---|
| 图表 | Vaadin Charts | **Chart.js（MIT）** 或 **ECharts（Apache-2.0）**——图表类型多、数据量大时选 ECharts；简单柱线饼选 Chart.js |
| SQL 编辑器 | — | **CodeMirror 6（MIT）** 或 Ace（BSD）或 Monaco（MIT） |
| 大网格高级编辑 | Grid Pro | 免费 Grid 已够（我们已决定不做数据写回） |
| 其余（Grid/TreeGrid/Binder/Dialog/Upload/Download/Tabs/SplitLayout/Notification） | — | **均在免费平台内** |

> **已确认（本轮）**：图表选 **ECharts 5**（Apache-2.0，报表场景图表类型更多、大数据量表现更好；用按需构建控制体积），编辑器选 **CodeMirror 6**（MIT，体积小、SQL 语法简单、易与补全挂钩）。两处均自写最小 Vaadin 包装件，不用第三方 addon。

---

## 3. 建议 A 与 B 合起来看：访问层怎么放

```
┌─────────────────────────────── Slackerdb Console（Vaadin 应用）─────────────────────────────┐
│  UI 层（Vaadin 视图）                                                                        │
│    SQL 工作台 │ 服务/MCP │ 报表 │ ETL 调度 │ 服务器/日志/插件 │ 设置                            │
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│  access 层（普通 Java 包，禁止 import Vaadin；自带 JUnit 集成测试）                            │
│    MgmtClient │ DataServiceClient │ McpClient │ SchedulerClient │ SqlWsClient │ JdbcSession    │
│    ClientError 统一错误模型 │ 脱敏 │ 分页循环 │ 列名去重 │ 日志批次切分                          │
└───────────────┬──────────────────────────────────────────────────────────────────────────────┘
                │ HTTP/WS/JDBC（就是"直接调用 dbserver 的 API"）
┌───────────────▼──────────────────────────────────────────────────────────────────────────────┐
│  dbserver（pgwire + port_x 管理/数据服务/MCP + plugin-scheduler）                             │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

**关键点**：即便同 JVM，也建议走 HTTP 而不是直接调服务端 Java 类。理由：① 调度器属于 PF4J 插件，跨 classloader 直调脆弱；② 直调内网实现会让每次服务端重构都打穿 UI；③ 走 HTTP 才能同时管理**多台/远端** dbserver（你要的是"平台"）。

---

## 4. 部署拓扑（因为 dbserver 可改，多出这个选择）

Vaadin 需要 **Jakarta Servlet 6** 容器，而 dbserver 的管理口是 **Javalin（Jetty 12，但非 servlet 模型）** → 直接"塞进 port_x"不行，有以下三条路：

| 拓扑 | 形态 | 优点 | 缺点 | 我的评价 |
|---|---|---|---|---|
| **T1 独立 UI 进程** | 一个 Spring Boot + 内嵌 Tomcat 的 Vaadin 应用（可再包一层启动器：起 UI → 起本机 dbserver 进程 → 打开浏览器） | 解耦、可管理多台/远端 dbserver、UI 独立升级、开发期互不干扰、不碰现有 Javalin 代码 | 两个进程（用启动器收敛成"双击一个东西"） | **推荐** |
| **T2 嵌入 dbserver 同 JVM** | 在 Javalin 的 Jetty 上挂一个 `ServletContextHandler` 承载 `VaadinServlet`（Javalin 有 `config.jetty.modifyServer(...)`）；或同 JVM 再起第二个 servlet 容器端口 | 单进程、单启动器、无 CORS，UI 甚至可直调内部服务 | UI 与服务端**版本绑定**（放弃"独立升级"）、Vaadin 前端构建进入 dbserver 构建、重启即断所有会话、耦合 Javalin/Jetty 内部机制 | 可行但要吃下耦合代价 |
| **T3 扩展现有 Web 控制台** | 不引 Vaadin，直接在 `console.js` 上加 Monaco + Chart.js/ECharts | **最省（≈5–9 人月）**、无新构建链、已有 SQL Shell/服务/MCP 面板可复用 | UI 代码是 JS/HTML；与"用 Java 开发"的偏好冲突 | 只有在"团队接受 JS"时才考虑 |

**建议**：**T1 + 启动器**。理由：你要建的是"平台"（管理服务/MCP/报表/ETL），不是 dbserver 的一个功能页；T1 保留了远程管理的可能性，也让 UI 与服务端各自发版。如果你更在意"单机双击即用"，T2 也能做，但要明确把"UI 随服务端一起发版"作为代价接受下来。

---

## 5. 被推翻 / 需要复议的既有决策

| # | 原决策 | 状态 |
|---|---|---|
| 3 | 客户端是纯桌面 Java（JavaFX），不内嵌浏览器、不用 WebView、不依赖 HTML/CSS | **作废** → 改为 Vaadin 本机 Web UI（接受少量 JS：编辑器/图表/剪贴板） |
| 4 | 不做数据写回 | 不受影响 |
| 6 | SQL 工作台一期只读、仅 JDBC 通道 | 不受影响（Grid 懒加载与 JDBC 游标分页正好配套） |
| 11 | 异步执行面板放 M2 | 不受影响（Vaadin 下异步更必要，因为长任务会占住 UI 会话） |
| 14 | 依赖方向：client 不依赖 JavaFX、ui 不碰协议（enforcer） | **改写** → access 包禁止 import Vaadin/Javalin，用 ArchUnit/enforcer 守（+0.5 人日） |
| 15 | HOP 体检向导不做 | 不受影响 |
| 16 | GUI 独立安装包（jpackage，自带运行时） | **改写** → T1 下 UI 独立发行但形态变为"jar + 启动器"（可选 jpackage app-image 带 JRE）；T2 下该决策作废（UI 进服务端包） |
| 17 | M0 三项验证：JavaFX 打包链路 / 10 万行网格 / JDBC 游标分页 | **改版** → 见第 7 节 |
| — | 工程约定「HTTP 只用 JDK HttpClient；不引入 Spring」 | **作废** → Vaadin 走 Spring Boot 最省事，且父 pom 已 import Spring Boot BOM，天然一致 |

---

## 6. 修正后的工作量（Vaadin + T1 拓扑）

| 工作包 | JavaFX 方案 | **Vaadin 方案** | 变化 |
|---|---|---|---|
| P0 access 层（应用内包，含集成测试 + 边界规则） | 45.5 | **42** | −3.5 |
| P1 SQL 工作台 | 69 | **62** | −7（网格 −11、编辑器 +4、元数据 −2、剪贴板/上下文菜单 +2） |
| P2 服务/MCP 管理 | 40 | **34** | −6 |
| P3 ETL 调度 GUI | 38 | **34** | −4 |
| P4 服务端改造 | 38 | **38** | 0 |
| P5 报表 | 60 | **58** | −2（图表走 JS 包装件） |
| P6 平台化（壳/导航/i18n/主题） | 15 | **14** | −1 |
| P7 打包分发 | 22 | **10** | −12（无平台原生件） |
| P8 测试与 CI | 20 | **22** | +2（浏览器端冒烟） |
| **P9 前端工程化（新增）**：Node/Vite 集成、生产 bundle、依赖锁定、CI npm 缓存、离线构建预案 | — | **6** | +6 |
| **合计** | **347.5** | **320** | **−27.5（−8%）** |

- 区间：**11 – 16 人月**（Vaadin 的不确定性主要来自前端构建链与 JS 包装件的维护）
- **口径更正（重要）**：我此前写的"MVP 4–6 人月"与 P 表明细不自洽 —— 单是 P0（2.0 人月）+ P2（1.6 人月）就已经 3.6 人月。按明细重算：
  - **M1（不含调度面板）**：P0 42 + P1 精简 ~50 + P2 34 + P9 6 ≈ **132 人日 ≈ 6.3 人月**
  - **M1（含调度只读面板）**：+ P3 只读 ~38 ≈ **170 人日 ≈ 8.1 人月**
  - 按 JavaFX 方案同口径重算是 ≈ 8.6 人月 → 两者 M1 差不多，**Vaadin 的节省主要出现在后期（打包与网格打磨）**。

---

## 7. 改版后的 M0 验证清单（2–3 周，0.5 人月）

| # | 验证项 | 为什么决定性 | 失败后的退路 |
|---|---|---|---|
| 1 | **Vaadin 24 + Spring Boot 3.5 + 内嵌 Tomcat 跑通**，并完成一次**生产构建**（Vite/生产 bundle），在 CI 里能用 npm 缓存构建 | 决定 Vaadin 方案是否成立（本项目零前端工具链） | 降级 Node 版本 / 让 Vaadin 插件自装 Node；仍不行则退 T3 或 JavaFX |
| 2 | **Grid 懒加载 + JDBC 游标分页**：`DataProvider.fromCallbacks` 接 JDBC，滚动 10 万行测帧率与内存 | 同时验证"网格架构"与"百万行怎么取数"（原 M0 第 2、3 项合并） | 游标不可用则退 `LIMIT/OFFSET` 或给服务端补 portal 支持（服务端可改） |
| 3 | **CodeMirror 6 与 ECharts 的 Vaadin 24 包装件**：能否以标准 `Component` + `@JsModule` 方式集成，含 SQL 高亮与图表联动 | 决定 SQL 工作台与报表的体验上限 | 换 Ace/Chart.js；再不行则该模块退化为 TextArea/静态图 |
| 4 | （若选 T2）Javalin 的 `config.jetty.modifyServer(...)` 能否挂载 `ServletContextHandler` + `VaadinServlet` | 决定 T2 是否可行 | 走 T1 |

**产出**：可复用骨架（Vaadin 应用壳 + access 层 + 一个 SQL 执行页 + 一个 Chart 示例）+ 一页验证报告（含内存/帧率/构建耗时数据）。

---

## 8. 三项待拍板事项 —— 已裁决

| # | 事项 | 裁决 | 备注 |
|---|---|---|---|
| 1 | **部署拓扑** | ✅ **T1：独立 UI 进程 + 启动器，不紧耦合** | 因此第 4 节 T2 的具体实现路径（`config.jetty.modifyServer` 挂 `VaadinServlet`）**不再需要验证**；M0 第 4 项相应改为"启动器与端到端冒烟" |
| 2 | **两处 JS 包装件** | ✅ **接受**（由选择 Vaadin 隐含） | 边界收敛为三处：CodeMirror 6（编辑器）、ECharts（图表）、剪贴板小片段；集中放置、版本锁定 |
| 3 | **Node/npm/Vite 构建链** | ✅ **接受** | 本机已实测可达；内网/离线环境需准备私有 npm 镜像，并计入 P9（6 人日） |

**定稿方案**：[javagui-platform-plan-v2.md](javagui-platform-plan-v2.md)（Vaadin + T1，320 人日 ≈ 15.2 人月，M1 6.3–8.1 人月）。
