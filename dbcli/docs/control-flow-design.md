# dbcli 控制流设计文档与工作计划

> 状态：已实现（`mvn -o -B -pl dbcli -am test` 通过，待评审）
> 版本：v0.2
> 日期：2026-10-30
> 适用模块：`dbcli`（SlackerDB 命令行客户端）
> 参考实现：`C:\work\testcli`（Python 版 `testcli`）
> 关联代码：`dbcli/src/main/antlr4/.../BaseLexer.g4`、`BaseParser.g4`、`script/SqlIrVisitor.java`、
> `engine/ScriptRunner.java`、`engine/ScriptSession.java`、`help/HelpCatalog.java`

---

## 0. 结论摘要

- **语法**：`_IF` / `_ELSEIF` / `_ELSE` / `_ENDIF` 与 `_LOOP` / `_ENDLOOP` 都是**单行结构标记**；条件必须写成 `{% <左值> <比较符> <右值> %}`，复用 `_ASSERT` 的固定形态，不引入脚本/表达式引擎。
- **执行模型**：采用“语句级捕获 + 控制树 + 解释执行”（方案 D）。块内语句仍由现有 `ScriptSession` 切成完整语句；关闭根块后按树执行，循环天然可重放。
- **状态归属**：`ControlFlow` 挂在 `ScriptSession` 上，`_START` / 作业 / 控制台互不串块。
- **安全与一致性**：64 层嵌套、1,000,000 轮循环上限；循环每轮输出一条 `Loop iteration ...` 进度行（`_SET LOOPTRACE`，默认 ON）；错误只走 `ErrorReporter`；退出码/`_WHENEVER`/`--ref`/`_HELP`/README 规则全部沿用。
- **工作量**：约 **10.75 人日**（含 20% 缓冲约 12.9 人日）；里程碑 M1 语法/IR → M2 核心 → M3 文档/E2E → M4 可合并。

---

## 1. 背景与目标

### 1.1 现状

`dbcli` 是从 Python 版 `testcli` 复刻出来的 Java 客户端。当前 dbcli 的脚本引擎是**逐行累积 → 分析 → 分派 → 输出**的线性模型：

- `ScriptSession` 负责把输入累积成"一条能收尾的语句"；
- `ScriptRunner` 负责分析、分派与执行；
- 语法层入口是 `prog : command EOF`，**一次只解析一条语句**；
- 脚本里没有分支、循环；命令都是一条一行（`_` 命令）或 `;` / `/` 收尾（SQL）。

Python 版已有 `_IF` / `_ENDIF`、`_LOOP BEGIN UNTIL …` / `_LOOP END` / `_LOOP BREAK` / `_LOOP CONTINUE`，但：

- 没有 `_ELSE` / `_ELSEIF`；
- 条件用 Python `eval()`（`len(lastCommandResult["rows"]) == 3`、`i=i+1`），不适合直接搬到 Java；
- 条件控制（`ifMode`）只对 SQL / HTTP 生效，普通命令在条件不满足时仍会执行（历史实现缺陷）；
- 单语句重试循环与块循环混在同一个 `_LOOP` 命令里。

本次不是在 Java 里复刻 Python 的这套实现，而是**重新设计一套适合 dbcli 的块结构控制流**。

### 1.2 目标

1. 支持块结构的条件与循环：
   - `_IF` / `_ELSEIF` / `_ELSE` / `_ENDIF`
   - `_LOOP` / `_ENDLOOP`，以及 `_LOOP BREAK` / `_LOOP CONTINUE`
2. 条件求值**不引入脚本引擎**：复用 `_ASSERT` 已有的固定形态取值器与比较符，保证可审计、可测试、无 Python 依赖。
3. 与 dbcli 现有约束完全一致：
   - stdout 逐字节可比对；错误只走 stdout；
   - 退出码规则不变（脚本内部错误默认不改退出码）；
   - `_WHENEVER ERROR`、`_EXIT`、`_SPOOL`、`_SET`、`_START`、`_JOB`、`--ref` 等既有特性继续成立；
   - `_HELP` 与中英 README 同源（`HelpCatalogReadmeTest` 继续守着）；
   - 不引入新依赖。
4. 在**交互式控制台**与批处理中行为一致：块可以逐行输入、有续行提示、Ctrl-C 可取消。
5. 对脚本作者安全：有嵌套深度、循环次数上限；不会因为写错条件而无限跑飞；不会把解析器文案泄漏给使用者。

### 1.3 非目标

- 不实现通用表达式引擎（算术、函数调用、`AND/OR/NOT`、正则以外的逻辑组合）。
- 不实现 `_FOR`、`_WHILE`、`_REPEAT`、标签化 `BREAK`、`goto`。
- 不恢复 Python 版的单语句重试循环（`_LOOP 2 UNTIL … INTERVAL 3 <单条语句>`）。
- 不实现循环 `INTERVAL` 关键字；轮询间隔用循环体里的 `_SLEEP`（它已经能被作业停止信号打断）。
- 不改变服务端 PL/SQL 的能力；控制流是**客户端脚本层**的。
- 不兼容 `_LOOP END` / `_LOOP BEGIN UNTIL` 等 testcli 旧写法（提供迁移表，见 §5）。

### 1.4 设计原则（“最合规”清单）

| dbcli 既有约束 | 本设计的做法 |
|---|---|
| 语法只解析单条语句，块结构不能塞进 ANTLR 的 `prog` | 语法只识别 `_IF`、`_LOOP` 等**单行结构标记**；块嵌套/配对由引擎的 `ControlFlow` 负责 |
| 语句切分已有唯一实现（`StatementBoundary` / `StatementSplitter` / `ScriptSession`） | 不另写切分器；块体仍走同一条累积/分析路径 |
| 错误必须可读，不能泄漏 ANTLR 位置文案 | 结构错误由 `ControlFlow` 生成固定文案；解析层错误只用于内部判定 |
| 错误计数与退出码唯一入口 | 一律走 `ScriptRunner.reportError` / `ErrorReporter`，不新开输出通道 |
| 命令实现“一命令一类” | 控制流是**结构命令**，不是叶子 `ScriptCommand`；在 `ControlFlow` 集中实现，并在帮助/测试中显式登记（见 §7.1/§7.2） |
| `_HELP` 与 README 同源 | `HelpCatalog` 增加条目与主题；两份 README 的生成块由测试强制同步 |
| 编译警告即失败（`-Werror`） | 新代码按现有风格编写，不产生未使用字段/导入 |
| 无数据库也能单测 | 核心控制流只依赖 `FlowHost` 接口；用假执行器测试 |
| 控制台与批处理同一套行为 | 控制流状态挂在 `ScriptSession` 上，两个入口共用 |

---

## 2. 需求与语义

### 2.1 命令总览

| 命令 | 作用 |
|---|---|
| `_IF {% <condition> %}` | 开始条件块；条件为真时执行到下一个同级分支标记 |
| `_ELSEIF {% <condition> %}` | 追加一个条件分支；只有前面的分支都为假时才求值 |
| `_ELSE` | 追加“否则”分支；同一个 `_IF` 最多一个，且必须是最后一个分支 |
| `_ENDIF` | 结束条件块 |
| `_LOOP` | 开始循环块；不带参数等价于 `FOREVER` |
| `_LOOP <n>` | 固定执行 `n` 次（`n` 是非负十进制整数） |
| `_LOOP WHILE {% <condition> %}` | 先判断后执行；条件为假时一次都不执行 |
| `_LOOP UNTIL {% <condition> %}` | 先执行后判断；至少执行一次，条件为真时结束 |
| `_LOOP FOREVER` | 无限循环，直到 `_LOOP BREAK`、`_EXIT`、错误退出或安全上限 |
| `_LOOP BREAK` | 立即跳出**最内层**循环 |
| `_LOOP CONTINUE` | 结束**最内层**循环的当前一轮，进入下一轮 |
| `_ENDLOOP` | 结束循环块 |

**书写约束（v1）：**

- 每条控制标记必须**单独占一行**（允许行首缩进）；`_IF` / `_ELSEIF` / `_LOOP` 的头部不能和循环体语句写在同一行。
- 控制关键字必须是该语句的第一个词。`select _IF ...` 是 SQL，不是控制流。
- 条件必须写成 `{% ... %}`，且必须与关键字在同一行（括号内不能再换行）。
- 支持行尾 `;`；支持行尾 `--` 注释（条件后的注释会被忽略）。
- 大小写不敏感（与所有 `_` 命令一致）。
- 结构标记本身**不产生 stdout 输出**，也不产生 TIMING/TIME 诊断行。

### 2.2 条件表达式

条件复用 `_ASSERT` 的固定形态：

```
{% <左值> <比较符> <右值> %}
```

- 左值：取值器或字面量；
- 右值：字面量；
- 比较符：`=`（`==`）、`<>`（`!=`）、`>`、`>=`、`<`、`<=`、`CONTAINS`、`NOTCONTAINS`、`MATCHES`、`EXISTS`；
- 字面量：数字、`'字符串'`、`"字符串"`、`NULL`、`TRUE`、`FALSE`。

可用取值器（与 `_ASSERT` 完全一致）：

| 取值器 | 含义 |
|---|---|
| `RESULT.ERRORCODE` | 最近一条 **SQL** 的错误码（0 表示正常） |
| `RESULT.ROWCOUNT` | 最近一条 SQL 的影响/返回行数 |
| `RESULT.STATUS` | 最近一条 SQL 的状态文案 |
| `RESULT.ROWS.COUNT` | 结果集行数 |
| `RESULT.HEADERS.COUNT` / `RESULT.COLUMNS.COUNT` | 列数 |
| `RESULT.ROWS[r][c]` | 结果集第 r 行第 c 列（0 基） |
| `RESULT.HEADERS[i]` / `RESULT.COLUMNS[i]` | 列标签 / 列类型名（0 基） |
| `VAR.<名字>` | 脚本变量（`_SET @name value`、`--var`、`ARGVn`） |
| `ENV.<名字>` | 环境变量 |
| `RUN.EXITCODE` | 最近一次 `_START` 子脚本的退出码 |
| `JOB.<名字>.STATUS` / `JOB.<名字>.EXITCODE` | 作业状态 / 退出码 |

> `RESULT.*` 只在下一条 **SQL** 执行后刷新；`_CONNECT` / `_COMPARE` / `_JOB` / `_SSH` 等命令不会更新它（与 `_ASSERT` 的既有口径一致）。要判断命令结果，用 `RUN.EXITCODE`、`JOB.*`，或 `_WHENEVER ERROR EXIT`。后续如需要，可另开任务增加“最近命令错误码”变量。

补充规则：

- 条件可以只写 `{% TRUE %}` 或 `{% FALSE %}`（常量条件）；
- 条件里的 `${name}` 在**求值时**展开（每次进入 `_IF`、每轮循环重新展开），推荐直接用 `VAR.name`；
  `${name}` 是文本展开，值里如果含比较符/引号，可能改变条件的形态，因此**优先用 `VAR.name`**。
- 取不到值的取值器、非法比较符、非法正则会得到一条 `... condition error: [...]`，并且**整个条件构造不执行**（不会因为一个笔误走到 `_ELSE`）；
- `_ASSERT` 的裸形态（不写 `{% %}`）仍然保留；`_IF` 不提供裸形态，原因见 §2.3。

### 2.3 `_IF` / `_ELSEIF` / `_ELSE` / `_ENDIF`

语义按“从上到下找第一个为真的分支”：

```
_IF {% cond1 %}
    ... branch 1 ...
_ELSEIF {% cond2 %}
    ... branch 2 ...
_ELSE
    ... else branch ...
_ENDIF
```

- `cond1` 为真：只执行 branch 1；`cond2` 不求值；`_ELSE` 不执行。
- `cond1` 为假、`cond2` 为真：只执行 branch 2。
- 都为假：执行 `_ELSE`（若有）。
- 没有分支命中且没有 `_ELSE`：什么都不执行。
- 分支体里的语句在**读取块时不会执行**，只有被选中的分支在 `_ENDIF` 到达后才执行。
- 交互式控制台同理：提示符会一直显示续行，直到 `_ENDIF`；`_ENDIF` 之后才看到分支的输出。
- `_IF` / `_ELSEIF` 的条件也在块执行时（`_ENDIF` 到达后）按分支顺序求值；读取时只做语法/结构检查，不执行、不展开 `${}`。
- `_ELSEIF` 可以有 0..n 个；`_ELSE` 最多一个，且必须出现在所有 `_ELSEIF` 之后。
- `_ELSEIF` / `_ELSE` 不创建新的作用域；它们共享同一个脚本会话状态（变量、连接、选项、spool）。
- 条件求值出错时：报告错误、计一次错误，并**放弃整个 `_IF`**（不执行任何分支，包括 `_ELSE`），然后继续脚本后面的语句。这样不会因为条件写错而误跑 `_ELSE`。

**为什么 `_IF` 只接受 `{% ... %}`：**

- `_ASSERT` 的裸形态是历史兼容；控制流是新语法，可以更严格；
- `{% %}` 让“条件”与“循环体”在视觉和词法上都界限清楚；
- 避免 `_IF` 后面跟任意文本时与后续语句产生歧义；
- 与 testcli 的既有写法一致，迁移成本低。
- 写成裸形态不会静默当成条件：会得到 `_IF condition must be written as {% ... %}.`，并进入“失败块”恢复（见 §4.8）。

### 2.4 `_LOOP` / `_ENDLOOP`

四种形态：

| 形态 | 语义 |
|---|---|
| `_LOOP <n>` | 执行 body 恰好 `n` 次；`n = 0` 时一次都不执行 |
| `_LOOP WHILE {% c %}` | 每轮开始前求值；`c` 为假则退出（可能 0 次） |
| `_LOOP UNTIL {% c %}` | 每轮结束后求值；`c` 为真则退出（至少 1 次） |
| `_LOOP FOREVER` / `_LOOP` | 无限循环，直到 `_LOOP BREAK` / `_EXIT` / 条件错误 / 安全上限 |

- `n` 是非负十进制**字面量**整数，不支持 `${}` 或表达式；需要“动态次数”时用 `_LOOP WHILE` + 变量/SQL 条件。
- 非数字、负数、溢出、超过安全上限都是可读错误。
- `_LOOP WHILE/UNTIL` 的条件在**每轮**求值，不是只求一次；因此可以轮询 SQL 结果、作业状态、变量。
- `_LOOP` 的 body 在读取时被缓冲，`_ENDLOOP` 到达后开始执行；循环体不会在 `_LOOP` 行之后立即执行。
- 循环结束后，`RESULT.*` 保留最后一轮最后一条 SQL 的结果；0 次循环不改变进入循环前的结果。这一点与 `_ASSERT` 的“最近一条 SQL”口径一致。
- 每轮默认打印一条 `Loop iteration ...` 状态行（`_SET LOOPTRACE ON|OFF`，默认 ON；`_SET FEEDBACK OFF` 也关）；
  一轮都没执行时打印 `Loop did not execute (0 iterations).`。
- `_LOOP` 不提供 `INTERVAL`；需要间隔就在 body 里写 `_SLEEP <秒>`（作业停止信号能打断它）。

### 2.5 `_LOOP BREAK` / `_LOOP CONTINUE`

- 只影响**最内层**循环。
- 通常写在 `_IF` 里：

```
_LOOP FOREVER
    select count(*) as n from queue;
    _IF {% RESULT.ROWS[0][0] = 0 %}
        _LOOP BREAK
    _ENDIF
    delete from queue where id = (select min(id) from queue);
_ENDLOOP
```

- `BREAK`：立即结束最内层循环，跳到 `_ENDLOOP` 之后。
- `CONTINUE`：结束当前轮；下一轮是否继续按循环形态判断：
  - `COUNT`：进入下一计数；
  - `WHILE`：重新求值条件；
  - `UNTIL`：先求值条件，再决定是否下一轮；
  - `FOREVER`：直接下一轮。
- 在循环外执行 `_LOOP BREAK` / `_LOOP CONTINUE` 报 `_LOOP BREAK used outside a loop.`（只有真的执行到才报；写在永远不会走到的分支里不报）。
- 不提供标签；嵌套循环里要跳出多层，请用变量 + 外层 `_IF` 控制，或重构脚本。

### 2.6 嵌套

支持任意层嵌套（到安全上限）：

```
_LOOP 3
    _IF {% RESULT.ROWCOUNT > 0 %}
        _LOOP 2
            insert into audit_log values (1);
        _ENDLOOP
    _ELSE
        _LOOP BREAK
    _ENDIF
_ENDLOOP
```

- `_ENDIF` 只关闭最内层 `_IF`；`_ENDLOOP` 只关闭最内层 `_LOOP`。
- 交叉关闭（例如 `_LOOP` 里直接写 `_ENDIF`）是结构错误，会报错并保留未关闭的块（见 §4.8）。
- 嵌套块在读取时建树；内层块在外层块执行时按轮次重新执行、重新求值条件。

### 2.7 错误、退出码与 `_WHENEVER`

- **结构/语法错误**（缺少 `_ENDIF`、`_ELSE` 没有 `_IF`、`_LOOP` 头部非法等）在**读取块时**报告并计数。
- **条件求值错误**（未知取值器、非法正则、`${}` 非法展开等）在**执行到该条件时**报告并计数。
- **错误动作**：
  - `_IF` / `_ELSEIF` 条件错误：放弃整个 `_IF`；
  - `_LOOP WHILE/UNTIL` 条件错误：停止该循环；
  - `_LOOP` 安全上限触发：报告并停止该循环；
  - 循环体里的普通错误（SQL 错、断言失败等）：按 `_WHENEVER` 设置处理；默认继续下一轮。
- 默认退出码仍是 **0**（脚本内部错误只打印/计数）；`_WHENEVER ERROR EXIT n` 或 `_EXIT n` 才改变退出码。控制流不改变这条全局规则。
- `_EXIT` / `_QUIT` 在块内执行时立即结束整次运行；`_LOOP` / `_IF` 立刻停止，不再执行剩余节点。
- `_WHENEVER ERROR EXIT` 在块内触发时同样立即停止。

### 2.8 限制

| 限制 | 默认值 | 行为 |
|---|---:|---|
| 块嵌套深度 | 64 | 超过时报告 `Block nesting is too deep (limit 64).`；该块进入丢弃模式，后续配对仍然平衡 |
| 单循环最大轮数 | 1,000,000 | 超过时报告 `Loop iteration limit (1000000) exceeded; the loop was stopped.`，停止该循环 |
| 固定次数 `_LOOP n` | `n <= 1,000,000` | 超过上限在读取时报错并丢弃该循环 |
| 条件行 | 单行 | 不支持跨行 `{% ... %}`；EOF 时给 `Incomplete command [_IF]` / 明确提示 |
| 条件正文 | 不含字面量 `%}` | 与 `_ASSERT` 的 `{% %}` 外观同一限制；需要比较含 `%}` 的字符串时先放进变量，用 `VAR.x` |
| 条件语法 | 固定比较形态 | 不引入算术/逻辑表达式引擎 |

> 这些上限做成 `FlowLimits` 值对象，测试可以注入更小的值；生产用默认值。后续如果用户确有需要，可以通过 `_SET` 增加选项，而不是改死代码。
>
> 轮数上限是**防跑飞**的兜底，不是超时机制：`_LOOP FOREVER` + `_SLEEP` 仍可能运行很久。要限制总时长，用作业超时/外部停止，或在脚本里用 SQL/作业状态条件自行退出。

### 2.9 示例

**条件分支：**

```
select count(*) as n from queue;
_IF {% RESULT.ROWS[0][0] = 0 %}
    _ECHO t
    queue is empty
    echo off
_ELSEIF {% RESULT.ROWS[0][0] < 10 %}
    _ECHO t
    queue is short
    echo off
_ELSE
    _ECHO t
    queue is long
    echo off
_ENDIF
```

**固定次数循环：**

```
create table t (id integer);
_LOOP 3
    insert into t values (1);
_ENDLOOP
select count(*) as n from t;
_assert RESULT.ROWS[0][0] = 3, loop-ran-three-times;
```

**轮询 + 条件跳出：**

```
_SET @done 0
_LOOP WHILE {% VAR.done = 0 %}
    delete from queue where id = (select min(id) from queue);
    _IF {% RESULT.ROWCOUNT = 0 %}
        _SET @done 1
    _ENDIF
_ENDLOOP
```

**FOREVER + BREAK：**

```
_LOOP
    select count(*) as n from queue;
    _IF {% RESULT.ROWS[0][0] = 0 %}
        _LOOP BREAK
    _ENDIF
    delete from queue where id = (select min(id) from queue);
_ENDLOOP
```

---

## 3. 总体设计

### 3.1 方案选择

| 方案 | 做法 | 结论 |
|---|---|---|
| A. 整文件预编译成 AST | 读完整脚本，先建完整语法树，再执行 | **放弃**：控制台无法预读；与逐行引擎重复；大脚本内存与错误定位复杂 |
| B. 流式 skip + 程序计数器跳转 | `_IF` 假时跳过语句，`_LOOP` 用 PC 跳回 | **放弃**：循环体需要回退，而 SQL 可能跨行；skip 嵌套和 `_ENDIF` 配对容易错 |
| C. 原样重放循环体（嵌套 Session） | 捕获原始行，每轮重新喂给子 Session | **放弃**：`ScriptSession` 不可重入；会重复压脚本帧，影响 `_START` 深度、ARGV、相对路径与停止语义；break/continue 只能用异常穿透 |
| D. 语句级捕获 + 控制树 | 每条完整语句仍由现有引擎切出；块内语句先挂到控制树，`_ENDIF`/`_ENDLOOP` 后解释执行 | **采用**：复用边界/分析/执行；循环天然可重放；控制台与批处理同一套 |

### 3.2 架构

```
ScriptSession（累积、切分、续行提示）
   │  executeStatement(raw, flow)
   ▼
ScriptRunner（分析、分派、错误、输出）
   │  控制行 / 块内语句
   ▼
ControlFlow（每 Session 一个）
   ├─ 结构识别：_IF / _ELSEIF / _ELSE / _ENDIF / _LOOP / _ENDLOOP
   ├─ 捕获：IfFrame / LoopFrame，建成 FlowNode 树
   ├─ 校验：配对、ELSE 顺序、深度、次数上限
   └─ 解释：FlowInterpreter
          ├─ ConditionEvaluator（固定形态条件求值）
          ├─ FlowHost.executeLeaf(raw)（叶子语句回到 ScriptRunner 执行）
          └─ FlowSignal（NONE / BREAK / CONTINUE / ABORT）
```

关键点：

- **语法层只认识单行结构标记**，不认识“块”。块配对完全在 `ControlFlow` 里完成。这样 `prog : command EOF` 不用改，控制台也能逐行输入。
- **控制流状态属于 `ScriptSession`，不属于 `ScriptRunner`**。`_START` 子脚本会新建 `ScriptSession`，因此父子脚本的块不会串；作业线程也有自己的 Session。
- **块体不是原始文本重放**，而是“已经完整切出的语句节点”重放；多行 SQL、`create procedure … /`、`_ECHO … echo off`、单行 `_HOST` 都保持原有切分结果。多行 `_HOST """…"""` 需要补一个小的边界收尾（见 §4.7.4），否则它只会在 EOF 收尾、无法在 `_ENDLOOP` 前被捕获。

### 3.3 组件职责

| 组件 | 职责 |
|---|---|
| `engine.flow.ControlFlow` | 每个会话的控制流状态机：识别控制行、维护帧栈、建树、在根块关闭时触发执行 |
| `engine.flow.IfFrame` / `LoopFrame` | 捕获期的容器：分支列表 / 循环头 + body 节点 |
| `engine.flow.FlowNode` | 执行期节点：`StatementNode`、`IfNode`、`LoopNode`、`BreakNode`、`ContinueNode` |
| `engine.flow.LoopSpec` | 循环形态：`COUNT(n)` / `WHILE(cond)` / `UNTIL(cond)` / `FOREVER`；`COUNT` 用 `count`、`WHILE/UNTIL` 用 `condition`，其余字段为 0/null |
| `engine.flow.FlowSignal` | 执行信号：`NONE`、`BREAK`、`CONTINUE`、`ABORT` |
| `engine.flow.FlowLimits` | 深度/轮数上限（默认值 + 测试注入） |
| `engine.flow.FlowHost` | ControlFlow 对引擎的最小依赖：条件模型、变量展开、叶子执行、报错、退出/停止检查 |
| `assertion.ConditionEvaluator` | 固定形态条件的布尔求值；内部复用 `AssertEvaluator` 的解析/比较逻辑 |
| `ScriptSession` | 持有 `ControlFlow`；把候选语句连同 flow 传给 runner；续行提示、reset、finish 时处理打开的块 |
| `ScriptRunner` | 实现 `FlowHost`；区分“叶子语句”和“控制/块内捕获”；把叶子执行重构成可复用方法 |

### 3.4 执行时序（一个 `_IF` 块）

```
读入 "_IF {% ... %}"        → 识别为控制行 → ControlFlow.push(IfFrame)
读入 "select 1;"            → 切出完整语句 → append(StatementNode)
读入 "insert ...;"          → append(StatementNode)
读入 "_ELSE"                → 结束当前分支，开始 else 分支
读入 "update ...;"          → append(StatementNode)
读入 "_ENDIF"               → 关闭 IfFrame，得到 IfNode；根块 → 立即解释执行
                              → 选分支 → 逐条 executeLeaf → 输出
```

循环同理；`_ENDLOOP` 关闭后得到 `LoopNode`，解释器按 `LoopSpec` 反复执行 body 节点。

---

## 4. 详细设计

### 4.1 词法

在 `BaseLexer.g4` 中新增（必须放在 `INT` / `String` 之前，避免被通用 `String` 抢走）：

```antlr
// 控制流关键字（顺序：ELSEIF 在 ELSE 之前；等长时先声明者胜）
IF       : '_IF'      -> pushMode(ConditionMode);
ELSEIF   : '_ELSEIF'  -> pushMode(ConditionMode);
ELSE     : '_ELSE';
ENDIF    : '_ENDIF';
LOOP     : '_LOOP'    -> pushMode(LoopHeaderMode);
ENDLOOP  : '_ENDLOOP';

mode ConditionMode;
CONDITION_SPACE     : [ \t]+ -> channel(HIDDEN);
CONDITION_BRACED    : [ \t]* '{%' ~[\n]*? '%}';
CONDITION_COMMENT   : [ \t]* '--' ~('\n')* -> channel(HIDDEN);
CONDITION_TEXT      : ~[\n;]+;              // 仅为给出可读错误而保留
CONDITION_SEMICOLON : ';';
CONDITION_CRLF      : '\n' -> type(CRLF), popMode;

mode LoopHeaderMode;
LOOP_SPACE        : [ \t]+ -> channel(HIDDEN);
LOOP_WHILE        : 'WHILE';
LOOP_UNTIL        : 'UNTIL';
LOOP_FOREVER      : 'FOREVER';
LOOP_BREAK        : 'BREAK';
LOOP_CONTINUE     : 'CONTINUE';
LOOP_INT          : [0-9]+;
LOOP_BRACED       : [ \t]* '{%' ~[\n]*? '%}';
LOOP_COMMENT      : [ \t]* '--' ~('\n')* -> channel(HIDDEN);
LOOP_SEMICOLON    : ';';
LOOP_CRLF         : '\n' -> type(CRLF), popMode;
```

要点：

- `CONDITION_COMMENT` / `LOOP_COMMENT` 必须排在 `CONDITION_TEXT` 之前，并且把前导空白一起吃进 token；否则 `_IF ...; -- 注释` 里的空格会被 `TEXT` 吞掉，注释变成条件的一部分。
- `CONDITION_BRACED` 用 `~[\n]*?` 限制在单行；跨行条件不在 v1 支持范围。
- `--` 只有在 `%}` 之外才是注释；`{% %}` 内部的 `--` 属于条件正文（与 `_ASSERT` 的 `{% %}` 外观一致）。
- `CONDITION_TEXT` 只用于把“忘了写 `{% %}`”的输入接住并给出可读错误，不参与正常条件。
- 原型验证：已把上述改动应用到 dbcli 四个 `.g4` 的**临时副本**，用 ANTLR 4.11.1 生成并解析 24 个样本（含 `_IF`/`_LOOP` 全形态、`${}`、行尾注释、`;`、既有 `_ASSERT`/`_SET`/`_connect`、SQL、存储过程），全部通过且无语法告警；生产用仓库的 4.13.2 在 CF-01 再复核一次 token 形状。
- 条件正文里不能包含字面量 `%}`（与 `_ASSERT` 的 `{% %}` 外观相同限制）；需要比较含 `%}` 的字符串时，先放进变量再用 `VAR.x`。
- 所有 token 名都不与既有 token 冲突；`_IF` 等只在默认模式生效，SQL 语句模式（`SQLStatementMode` / `SQLProcedureMode`）里的文本不会被误认成控制流。

### 4.2 语法

在 `BaseParser.g4` 的 `baseCommand` 中增加备选，并新增规则：

```antlr
baseCommand:
      assert
      | host
      | start
      | ifCommand
      | elseIfCommand
      | elseCommand
      | endifCommand
      | loopCommand
      | endloopCommand
      | whenever
      | set
      | exit
      | quit
      | sleep
      | spool
      | load
      | echo
      | ssh
      | job
      | compare
      | help
      ;

ifCommand      : IF (CONDITION_BRACED | CONDITION_TEXT)? CONDITION_SEMICOLON? CRLF?;
elseIfCommand  : ELSEIF (CONDITION_BRACED | CONDITION_TEXT)? CONDITION_SEMICOLON? CRLF?;
elseCommand    : ELSE SEMICOLON? CRLF?;
endifCommand   : ENDIF SEMICOLON? CRLF?;

loopCommand    : LOOP
                 (
                   LOOP_BREAK
                   | LOOP_CONTINUE
                   | LOOP_FOREVER
                   | LOOP_INT
                   | (LOOP_WHILE (LOOP_BRACED)?)
                   | (LOOP_UNTIL (LOOP_BRACED)?)
                 )? LOOP_SEMICOLON? CRLF?;

endloopCommand : ENDLOOP SEMICOLON? CRLF?;
```

设计说明：

- `_ELSE` / `_ENDIF` / `_ENDLOOP` 不切换 lexer mode，所以它们的 `;`/换行使用默认 token；
- `_IF` / `_ELSEIF` 进入 `ConditionMode`，`_LOOP` 进入 `LoopHeaderMode`，行尾自动 pop；
- 头部允许空（例如裸 `_IF`、裸 `_LOOP`），把“缺参数”留给引擎给可读错误，而不是让 ANTLR 报 `missing ...`；
- `LOOP_INT` 只收十进制非负整数；负数/小数/标识符走解析错误，`ControlFlow` 用原始文本报 `Invalid _LOOP header`；
- 语法不尝试识别 `_ENDIF` 与 `_IF` 的配对——那是 `ControlFlow` 的职责。

### 4.3 IR 契约

`SqlIrVisitor` 增加对应 visit 方法，IR 形状如下（字段名、大小写固定）：

| 输入 | IR |
|---|---|
| `_IF {% 3 = 3 %}` | `{name=IF, expression=" 3 = 3 "}` |
| `_IF 3 = 3` | `{name=IF, expression="3 = 3"}` + `errorCode=1`、`errorMsg="_IF condition must be written as {% <left> <op> <right> %}."` |
| `_ELSEIF {% VAR.x > 1 %}` | `{name=ELSEIF, expression=" VAR.x > 1 "}` |
| `_ELSE` | `{name=ELSE}` |
| `_ENDIF` | `{name=ENDIF}` |
| `_LOOP` | `{name=LOOP, action="BLOCK", mode="FOREVER"}` |
| `_LOOP 3` | `{name=LOOP, action="BLOCK", mode="COUNT", count=3}` |
| `_LOOP WHILE {% VAR.i < 3 %}` | `{name=LOOP, action="BLOCK", mode="WHILE", expression=" VAR.i < 3 "}` |
| `_LOOP UNTIL {% RESULT.ROWCOUNT = 0 %}` | `{name=LOOP, action="BLOCK", mode="UNTIL", expression=" RESULT.ROWCOUNT = 0 "}` |
| `_LOOP BREAK` | `{name=LOOP, action="BREAK"}` |
| `_LOOP CONTINUE` | `{name=LOOP, action="CONTINUE"}` |
| `_ENDLOOP` | `{name=ENDLOOP}` |

约定：

- `expression` 只去掉最外层 `{%` / `%}`，**保留内部前后空格**（与 `_ASSERT` 的 IR 契约一致）；求值器再 strip。
- 条件正文里的 `${name}` 原样保留在 IR 里，由 `ControlFlow` 在**求值时**展开；这样循环里的条件每轮都能看到最新变量。
- `_LOOP` 的 `count` 用 `UserNumber.nonNegativeInteger("loop count", text)` 解析；越界/非法走 visitor 的 `errorCode/errorMsg`，不抛 `NumberFormatException`。
- `_IF` / `_ELSEIF` 的空条件、裸条件、`_LOOP WHILE/UNTIL` 缺 `{% %}`、`_LOOP` 头部非法，都由 visitor 或 `ControlFlow` 给可读错误；**绝不把 ANTLR 的 `line…:…` 文案透给使用者**。

### 4.4 捕获与建树

`ControlFlow` 捕获期状态：

```
Deque<Frame> frames;        // 栈顶 = 当前正在读取的块
Frame = IfFrame | LoopFrame
IfFrame  { List<Branch> branches; Branch current; boolean sawElse; boolean failed; boolean discard; }
LoopFrame{ LoopSpec spec; List<FlowNode> body; boolean failed; boolean discard; }
Branch   { String conditionText; List<FlowNode> nodes; boolean isElse; boolean failed; }
```

#### 4.4.1 控制行识别

`ControlFlow.isControlCommand(firstWord)` 只认字面量：

```
_if / _elseif / _else / _endif / _loop / _endloop
```

- 在 `ScriptRunner.handleStatement` 里，**先看原始文本的第一个词**；是控制词才进入控制路径，否则按普通语句处理。
- 这意味着控制关键字不能由 `${}` 展开生成；`${x}` 即使值是 `_ENDIF` 也不会变成结构标记（安全、可预测）。
- 语句边界已经保证 `_ENDIF` 出现在 SQL 字符串、`_ECHO` 块、`_HOST """…"""`、存储过程体里时不会被单独切成候选，因此不会误识别。

#### 4.4.2 块内普通语句的“完整性探测”

块内一条候选语句不一定已经完整（例如 `create procedure …;` 其实还要等行首 `/`；PostgreSQL `$$…$$` 函数体里的 `;` 不是终结符）。捕获时必须判断“这条候选是否已经完整”，否则会把块语句切坏。

做法：

1. 用 `VariableResolver.maskForAnalysis(raw)` 把 `${…}` 临时替换成 `x`（只用于探测；不改变实际执行文本），避免未展开变量让词法/语法误判；
2. 对 mask 后的文本调用 `SqlAnalyzer.analyze`；
3. 只使用 `isFinished` / `incompleteInput`：
   - `!isFinished && incompleteInput` → 返回 false，继续累积；
   - 其余情况 → 认为候选已完整，挂到当前帧（即使解析有错也先挂起，执行到再报）。
4. 探测阶段**不打印错误、不执行、不产生 TIMING/TIME**。

这样既保留多行 SQL / 存储过程 / 美元引用的正确切分，又不会在未执行的分支里提前报错。

> 备选：直接分析原文。原型可行，但对 `${}` 出现在 `_HOST` 等命令参数里的形态不够稳；mask 方案与具体变量值解耦，行为更确定。

#### 4.4.3 事件处理表

| 读到的语句 | 栈顶 | 动作 |
|---|---|---|
| `_IF` | 任意 | 压入 `IfFrame`（第一个分支的条件） |
| `_ELSEIF` | `IfFrame` 且未见 `_ELSE` | 结束当前分支，追加分支 |
| `_ELSE` | `IfFrame` 且未见 `_ELSE` | 结束当前分支，追加 else 分支 |
| `_ENDIF` | `IfFrame` | 关闭，得到 `IfNode`；若栈空则执行，否则挂到父帧 |
| `_LOOP`（块形态） | 任意 | 压入 `LoopFrame` |
| `_LOOP BREAK` | 任意 | 追加 `BreakNode`（栈空则立即报“outside a loop”） |
| `_LOOP CONTINUE` | 任意 | 追加 `ContinueNode`（同上） |
| `_ENDLOOP` | `LoopFrame` | 关闭，得到 `LoopNode`；若栈空则执行，否则挂到父帧 |
| 其他语句 | 非空 | 追加 `StatementNode(raw)` |
| 其他语句 | 空 | 正常叶子执行（不经过 ControlFlow） |

**关键：只有根块关闭时才执行。** 嵌套块关闭时只把节点挂到父帧，不会提前执行；这样外层循环每轮都会重新执行内层块、重新求值内层条件。

#### 4.4.4 结构校验与恢复

读取阶段就要发现并恢复，避免错误级联。原则：**报一次、尽量保持后续配对平衡、绝不执行坏块**。

| 情况 | 动作 |
|---|---|
| `_ELSEIF` / `_ELSE` / `_ENDIF` 但栈顶不是 `IfFrame` | 报 `... without a matching _IF.`；忽略该行，不弹栈 |
| `_ENDLOOP` 但栈顶不是 `LoopFrame` | 报 `_ENDLOOP without a matching _LOOP.`；忽略 |
| `_ENDIF` 栈顶是 `LoopFrame` | 报 `_ENDIF cannot close an open _LOOP (use _ENDLOOP).`；忽略 |
| `_ENDLOOP` 栈顶是 `IfFrame` | 报 `_ENDLOOP cannot close an open _IF (use _ENDIF).`；忽略 |
| `_ELSE` 已经出现过 | 报 `_ELSE must be the last branch of an _IF block.`；忽略 |
| `_ELSEIF` 在 `_ELSE` 之后 | 报 `_ELSEIF cannot follow _ELSE.`；忽略 |
| `_IF` 条件缺失/裸形态 | 报可读错误；压入 `failed=true` 的 `IfFrame`，body 丢弃，遇到 `_ENDIF` 正常弹出 |
| `_ELSEIF` 条件缺失/裸形态 | 报可读错误；该分支标记 `failed=true`（到达时整块放弃），继续读 |
| `_LOOP` 头部非法 | 报 `Invalid _LOOP header [...]`；压入 `failed=true` 的 `LoopFrame`，丢弃到 `_ENDLOOP` |
| `_LOOP` 计数超上限 | 报错；`failed=true` 的 `LoopFrame` |
| 嵌套深度超限 | 报一次 `Block nesting is too deep (limit 64).`；该帧及子帧 `discard=true`，只维护配对 |
| 脚本结束仍 open | 每个未关闭块报一条 `... requires ... before the end of the script.`；丢弃块，不执行 |
| `_ELSE` / `_ENDIF` / `_ENDLOOP` 带多余参数 | 报 `... does not take arguments.`；**仍执行对应的结构动作**（关闭/分支恢复优先） |
| `_LOOP BREAK/CONTINUE` 栈空 | 立即报 `... used outside a loop.` |
| `_LOOP BREAK/CONTINUE` 在只有 `_IF` 的栈里 | 先挂节点；执行到且没有外层循环时再报（未执行到不报） |

`failed` / `discard` 帧的规则：该帧及其所有子帧里的节点一律不挂树；关闭时只弹出，不生成节点。这样坏块不会产生副作用。

#### 4.4.5 根块关闭

```
closeIf():
    IfFrame frame = popIfFrame();
    if (frame.failed || frame.discard) return;
    IfNode node = new IfNode(frame.branches);
    if (frames.isEmpty()) interpreter.execute(node);
    else append(node);

closeLoop(): 同理
```

### 4.5 执行

#### 4.5.1 节点与信号

```
sealed interface FlowNode permits StatementNode, IfNode, LoopNode, BreakNode, ContinueNode
record StatementNode(String rawText)
record IfNode(List<IfBranch> branches)
record LoopNode(LoopSpec spec, List<FlowNode> body)
record BreakNode()
record ContinueNode()
```

`FlowSignal`：`NONE`、`BREAK`、`CONTINUE`、`ABORT`。

`executeNodes(List<FlowNode>)` 顺序执行，遇到信号立即返回：

```
for node in nodes:
    host.checkStopRequested();       // 作业停止
    if host.exitRequested(): return ABORT;
    signal = executeNode(node);
    if signal != NONE: return signal;
return NONE;
```

所有错误路径在 `reportError` 之后都要再检查一次 `host.exitRequested()`：`_WHENEVER ERROR EXIT` 可能已经请求退出；此时返回 `ABORT`，不要继续执行后续节点。

`executeNode`：

- `StatementNode`：`host.executeLeaf(raw)`；检查 `host.exitRequested()`；
- `IfNode`：按分支顺序求值，命中则 `executeNodes(branch.nodes)`；条件错误 → 报错并返回 `NONE`（放弃整块，但不终止脚本）；
- `LoopNode`：见 §4.5.2；
- `BreakNode` / `ContinueNode`：返回对应信号。

根执行（`ControlFlow.execute(root)`）收到 `BREAK` / `CONTINUE` 时，说明它没有被任何循环消费：报 `_LOOP BREAK used outside a loop.` / `_LOOP CONTINUE used outside a loop.`，计一次错误，然后结束该根节点。

#### 4.5.2 循环执行

```
executeLoop(spec, body):
    int iteration = 0;
    while (true):
        host.checkStopRequested();
        if (host.exitRequested()) return ABORT;
        if (spec is COUNT && iteration >= n) return NONE;
        if (spec is WHILE) {
            Condition c = evaluate(spec.condition);
            if (c.error) { reportLoopError(c); return NONE; }
            if (!c.value) return NONE;
        }
        if (iteration >= limits.maxIterations) {
            report("Loop iteration limit (...) exceeded; the loop was stopped.");
            return NONE;
        }
        signal = executeNodes(body);
        if (signal == ABORT) return ABORT;
        if (signal == BREAK) return NONE;
        // CONTINUE 与 NONE 都进入下一轮
        iteration++;
        if (spec is UNTIL) {
            Condition c = evaluate(spec.condition);
            if (c.error) { reportLoopError(c); return NONE; }
            if (c.value) return NONE;
        }
```

细节：

- `COUNT`：`n=0` 直接返回；每轮执行完 `iteration++`，到 `n` 停。
- `WHILE`：先条件后 body；条件错误停止循环（不执行 body）。
- `UNTIL`：先 body 后条件；即使 body `CONTINUE` 也要在下一轮前求值条件（语义与 `do-while` 一致）。
- `FOREVER`：无条件；只有 `BREAK` / `ABORT` / 上限能停。
- 上限判断放在“即将执行下一轮”之前，允许恰好 `maxIterations` 轮自然结束而不报错。
- 每轮开始与每个节点前都检查 `exitRequested` / `stopRequested`。

#### 4.5.3 条件求值

```
evaluate(expressionText):
    resolved = host.resolveText(expressionText);   // ${} 展开；失败时已报错，返回 null
    if resolved == null: return error
    verdict = ConditionEvaluator.evaluate(resolved, host.conditionModel());
    if (!verdict.ok()): return error(verdict.error())
    return verdict.value()
```

- `host.conditionModel()` 就是 `ScriptContext.assertModel()`，与 `_ASSERT` 完全同一份数据；
- 条件错误文案：`If condition error: [<detail>].` / `Loop condition error: [<detail>].`；
- 条件错误一律放弃当前构造（IF 整块不执行 / LOOP 停止），不尝试“当成 false 继续”。

### 4.6 条件求值器

新增 `org.slackerdb.dbcli.assertion.ConditionEvaluator`：

```java
public final class ConditionEvaluator {
    public record Condition(boolean value, String error) {
        public boolean ok() { return error == null; }
    }
    public static Condition evaluate(String expression, ResultModel model) {
        // 0) 去掉最外层 {% %}（幂等；调用方传不传壳都行）
        // 1) 空条件 -> error
        // 2) TRUE/FALSE 常量 -> 直接返回
        // 3) 其余复用 AssertEvaluator.evalExpression(expression, model)
        // 4) 捕获 RuntimeException，把 message 作为 error 返回
    }
}
```

- `AssertEvaluator.evalExpression` 已经是包内可见的固定形态解析/比较入口；`ConditionEvaluator` 与它同包，不需要复制 300 行比较逻辑。
- `_ASSERT` **不改行为**：它仍然走 `AssertEvaluator.evaluate(...)`，状态文案逐字节不变。
- `ConditionEvaluator` 支持 `TRUE` / `FALSE` 常量；`_ASSERT` 不支持（保持历史行为）。
- 后续如果要给 `_ASSERT` 也支持常量，可以再统一，但不在本次范围。

### 4.7 会话集成

#### 4.7.1 状态归属

`ScriptSession` 新增字段：

```java
private final ControlFlow flow = new ControlFlow(runner/* implements FlowHost */);
```

不放在 `ScriptRunner` 上：`_START` 子脚本会新建 `ScriptSession`，作业线程也有自己的上下文；块状态必须随会话走。

`FlowHost` 只暴露最小能力（不把 `ScriptRunner` 的具体类型带进 `engine.flow`）：

```java
public interface FlowHost {
    ResultModel conditionModel();      // = ScriptContext.assertModel()
    String resolveText(String text);   // ${} 展开；失败时已报错并返回 null
    void executeLeaf(String rawText);  // 执行一条叶子语句（不 echo）
    void reportError(String message);  // 错误计数 + _WHENEVER
    boolean exitRequested();           // _EXIT / _WHENEVER ERROR EXIT
    void checkStopRequested();         // 作业停止，抛 ScriptStoppedException
}
```

#### 4.7.2 语句入口

`ScriptSession.processBuffer` 里原来的：

```java
runner.executeStatement(candidate)
```

改为：

```java
runner.executeStatement(candidate, flow)
```

`ScriptRunner`：

```java
boolean executeStatement(String raw, ControlFlow flow) {
    if (flow.isControlCommand(raw)) {
        // 不在这里解析变量；条件里的 ${} 留到求值期
        SqlAnalyzer.Result result = SqlAnalyzer.analyze(raw);
        echoInput(raw);
        flow.accept(raw, result);       // flow 自己校验/报错/恢复
        return true;
    }
    if (flow.isCapturing()) {
        SqlAnalyzer.Result probe = SqlAnalyzer.analyze(maskForAnalysis(raw));
        if (!probe.isFinished() && probe.incompleteInput()) {
            return false;               // 继续累积多行 SQL
        }
        echoInput(raw);
        flow.appendStatement(raw);
        return true;
    }
    return executeLeaf(raw, true, true) == HANDLED;   // 原有叶子路径
}
```

> `maskForAnalysis` 只是把 `${…}` 替换为 `x` 用于结构探测；实际执行仍用原文。

#### 4.7.3 叶子执行的重构

把现有 `handleStatement` 中“解析 + 分派 + 输出”的部分抽成：

```java
enum LeafResult { HANDLED, INCOMPLETE }

private LeafResult executeLeaf(String raw, boolean echo, boolean allowIncomplete) {
    // resolve 变量 -> analyze -> echo -> execute / readable IR error / SQL fallback
    // 诊断（TIMING/TIME）在这里 begin/finish：只有真正执行的叶子才有诊断
}
```

- 普通语句：`executeLeaf(raw, true, true)`，行为与现在完全一致；
- 块内重放：`FlowHost.executeLeaf(raw)` → `executeLeaf(raw, false, false)`；
  - 不重复 echo（块体在读取时已经 echo 过一次）；
  - 如果变量展开后反而不完整，报 `Incomplete command [...]` 并计错误，不执行；
- 控制行与块体捕获：不调用 `executeLeaf`，因此不产生 TIMING/TIME。

#### 4.7.4 配套：多行 `_HOST """` 的边界收尾

现有 `StatementBoundary` 对 `_ECHO … echo off` 有专门的块边界，但对 `_HOST """ … """` 没有：多行 `_HOST` 只在 EOF（`finish()` 的虚拟终结符）才会被交给分析器。单独运行没问题，但放进 `_LOOP` 后会出问题：

```
_LOOP
    _HOST """
    echo hi
    """
_ENDLOOP
```

`"""` 收尾行不是 `;`、`/`、`echo off`，边界仍是 `NONE`，于是 `_ENDLOOP` 会被一起缓冲，块无法在循环里正常关闭。

**方案**：给 `StatementBoundary` 增加一个与 `_ECHO` 同构的 `_HOST` 三引号块状态：

- 待处理语句的首词是 `_HOST`，且该行去掉空白后是 `_HOST """`（即只有一个开标记）时，进入 host block 模式；单行 `_HOST ...` 与 `_HOST """..."""`（同一行闭合）不走该模式；
- 该模式下不做普通引号/分号扫描（块体是命令文本，`#`/`;`/`_ENDLOOP` 都不应改变结构）；
- 遇到以 `"""` 结尾的行时标记“块已闭合”，`kind()` 返回 `COMMAND`；
- 未闭合到 EOF 时维持现有 `Incomplete command [_HOST]` 行为。

这是**配套修复**，不是控制流的主要逻辑；单独一个提交，带 `StatementBoundaryTest` 用例（单行 / 多行 / 块内含 `_ENDIF` 文本 / 块内 `;`）。如果评审认为范围太大，也可以把 v1 限制为“块内只支持单行 `_HOST`”，在 §2.8 增加限制；本设计推荐做这个小修复，因为否则 §4.8 的承诺不成立。

#### 4.7.5 续行提示、Ctrl-C、EOF

`ScriptSession`：

```java
public boolean isIncomplete() {
    return buffer.length() > 0 || flow.isOpen();
}

public void reset() {          // 控制台 Ctrl-C
    clearBuffer();
    flow.reset();              // 丢弃当前块
}
```

`finish()`：

```java
if (context.exitRequested()) { flow.reset(); clearBuffer(); return; }

processBuffer(true);

if (flow.isOpen()) {
    flow.finish();             // 报未关闭的 _IF/_LOOP；不执行半截块
    clearBuffer();
    return;
}
// 原有：缓冲里还剩不完整语句 -> finishPending
```

`finishPending(raw)`：

- 若首个词是控制词，走 `flow.accept(...)`，这样 EOF 时未配对的 `_ENDIF` / `_ELSE` 得到“without a matching _IF”，而不是 `Incomplete command [_ENDIF]`；
- 若是普通 `_` 命令，保持 `Incomplete command [...]`；
- 其余（SQL）保持原样送执行层。

控制台的效果：

- 输入 `_IF ...` 后，`isIncomplete()` 为 true → 提示符变 `   > `；
- 输入 `_ENDIF` 后，块执行、flow 关闭 → 回到 `dbcli> `；
- Ctrl-C 在续行提示处取消当前块（与取消多行 SQL 一致）；
- 脚本 EOF 仍未关闭：报错、计数、不执行半截块。

`processBuffer` 在 `context.exitRequested()` 时除清空缓冲外也调用 `flow.reset()`：显式退出后不应再保留半截块（与 `finish()` 的 exit 分支一致）。

#### 4.7.6 回显（ECHO）、循环进度与日志

- 块内的普通语句在**读取时**调用现有 `echoInput(raw)` 一次；循环重放时**不重复 echo**。
- 但循环每轮会额外打印一条**进度状态行**（`printStatus`）：
  - `_LOOP <n>`：`Loop iteration 1 of 3.`；
  - `WHILE` / `UNTIL` / `FOREVER`：`Loop iteration 1.`；
  - 嵌套：`Loop iteration 1 of 2 (depth 2).`；
  - 一轮都没执行：`Loop did not execute (0 iterations).`
- 进度行受 `_SET LOOPTRACE ON|OFF`（默认 ON）控制；同时它是状态行，所以 `_SET FEEDBACK OFF` 也会关掉它。
- 因此 `--logfile` 里循环体原文只出现一次，进度行与结果按轮次出现 N 次；`--ref` 也会包含进度行，循环次数因此可以被 golden 文件校验。
- 控制行本身也会 echo；`_ECHO ... echo off` 块仍按既有规则不 echo 块体。
- 因为块在 `_ENDIF` / `_ENDLOOP` 到达后才执行，日志里会先出现整段输入回显，再出现进度行与块体的结果；这与普通语句“回显一行、紧跟结果”的顺序不同，是块语义的必然结果。
- `_SET ECHO OFF` 写在块内时，由于块体已经读取/回显完毕，它只影响**块执行之后**的语句；这是“先读块、后执行”的必然结果，文档中明确说明。
- 同理，块内的 `_SPOOL` 在执行到它时才生效；块的输入回显在读取时已经发生，不会进入这个 spool，但进度行与块体结果会。
- 错误/状态/结果仍走原有通道；控制流不直接写 stdout（进度行走 `ScriptOutput.printStatus`）。

#### 4.7.7 诊断（TIMING / TIME）

- 控制行不是“执行的语句”，不产生 `elapsed` / `Current time`；
- 块体叶子在**真正执行**时产生诊断（循环每轮都会产生，符合“每条执行的语句一行”）；
- 捕获阶段不产生诊断。

### 4.8 与既有特性的交互

| 特性 | 行为 |
|---|---|
| `_WHENEVER ERROR CONTINUE`（默认） | 块内错误打印/计数，循环继续下一轮，IF 继续后续语句 |
| `_WHENEVER ERROR EXIT n` | 任一错误（含条件错误、上限错误）立刻请求退出；控制流返回 ABORT，进程退出码 n |
| `_EXIT n` / `_QUIT n` | 块内执行后立即 ABORT；不再执行剩余节点；退出码 n |
| `_ASSERT` | 块内照常执行；失败计错误；不改变 `RESULT.*`（与现在一致） |
| `_START` | 块内照常启动子脚本；子脚本有**自己的** `ControlFlow`，不会串块；`RUN.EXITCODE` 可被后续条件使用 |
| `_JOB` | 块内照常创建/启动作业；循环里重复启动同名作业由使用者负责；`JOB.*.STATUS` 可用于轮询条件 |
| `_SPOOL` | 块内 `_SPOOL` 执行时开始/停止；块体输出按源顺序进入 spool |
| `_SET` | 块内选项在块执行时生效；`ECHO OFF` 的读取期限制见 §4.7.6 |
| `_ECHO ... echo off` | 整个块作为一条语句被捕获/重放；块体里的 `_ENDIF` 等文本不会改变结构 |
| `_HOST """…"""` | 整个多行命令作为一条语句捕获/重放；内部 `_ENDIF` 不参与结构（依赖 §4.7.4 的边界配套） |
| 存储过程 / `DECLARE … /` | 由既有 `awaitingSlash` 机制等到 `/`；整段作为一个 `StatementNode` |
| PostgreSQL `$$…$$` | 结构探测用 `SqlAnalyzer` 判断完整性；内部 `;` 不会切坏 |
| `--ref` | 块体输出照常进入比对；输入回显只出现一次；结果按轮次重复，天然确定 |
| 交互式控制台 | 续行提示、Ctrl-C、粘贴多行块均按 §4.7.5 |
| 作业停止 | 每个节点/每轮前 `checkStopRequested()`；`ScriptStoppedException` 照旧向上抛 |
| `--silent` / `_SET FEEDBACK OFF` | 控制流错误走 `ErrorReporter`，照常被抑制但计数 |

### 4.9 错误处理原则

1. **不泄漏解析器文案**：`ControlFlow` 只使用自己的固定文案或 visitor 的可读文案；`SqlAnalyzer.parserError()` 为 true 时绝不把 `errorMsg` 原样打印。
2. **可恢复**：结构错误尽量只影响当前行/当前块；不因为一个 `_ELSE` 写错就让后续所有行变成语法错误。
3. **不执行坏块**：`failed` / `discard` 帧只维护配对，不产生任何副作用。
4. **错误只计一次**：同一个问题不在读取、执行、EOF 三处重复报（实现时用一个 `reported` 标记）。
5. **退出优先**：`exitRequested` 一旦为真，所有层立即停止，不再报“未关闭块”之类的次要错误（`finish()` 先看 exit）。

### 4.10 限制实现

`FlowLimits`：

```java
public record FlowLimits(int maxDepth, int maxIterations) {
    public static FlowLimits defaults() { return new FlowLimits(64, 1_000_000); }
}
```

- `ControlFlow(FlowHost host)` 用默认值；包内可见的 `ControlFlow(FlowHost, FlowLimits)` 供测试注入小值。
- 测试用 `new FlowLimits(3, 5)` 就能覆盖深度/轮数错误，不需要跑一百万次。
- 上限错误也走 `ErrorReporter`，因此 `_WHENEVER ERROR EXIT` 照常生效。

---

## 5. 兼容性与迁移

### 5.1 与 testcli 对照

| testcli 写法 | dbcli 新写法 | 说明 |
|---|---|---|
| `_IF {% expr %}` … `_ENDIF` | 同左 | 条件语法从 Python 表达式改为固定比较 |
| `_ELSE` / `_ELSEIF` | 新增 | testcli 没有 |
| `_LOOP BEGIN UNTIL {% expr %}` … `_LOOP END;` | `_LOOP UNTIL {% expr %}` … `_ENDLOOP` | 语义同为“先执行后判断” |
| `_LOOP BREAK` / `_LOOP CONTINUE` | 同左 | 只影响最内层循环 |
| `_LOOP 2 UNTIL … INTERVAL 3 <单条语句>` | `_LOOP 2` + body + `_SLEEP 3` + `_ENDLOOP` | 不再支持单语句重试形态；语义需重写 |
| `len(lastCommandResult["rows"]) == 3` | `RESULT.ROWS.COUNT = 3` | 固定取值器 |
| `i=i+1` | 不支持 | 用 SQL 结果/`_SET @done`/`_LOOP n` 表达终止条件 |
| `> {% python %}` | 不支持 | dbcli 不内嵌 Python |

### 5.2 现有测试的变更

这些是**有意**的变更，不是回归：

1. `antlr/SqlScriptParseTest.removedStatementsNoLongerParse`：删除“`_IF` / `_LOOP` 应解析失败”的断言，改为“控制流必须能解析”；保留 `_DATA` / `_MONITOR` / `_USE` 等仍被裁剪的断言。
2. `script/SqlIrContractTest` 类注释里“6 条随命令删除而作废（IF/Loop/...）”改为“4 条（Script/Data/Monitor/Plugin）”，IF/Loop 已重新加入。
3. `help/HelpCatalogReadmeTest.topicsExist` 的主题列表增加 `IF`、`LOOP`。
4. `engine/command/CommandCatalogTest`：帮助里的控制命令不再要求 `CommandCatalog`/`CommandDispatcher` 支持，而是增加 `ControlFlow.supports(name)`；同时新增“`ControlFlow.commandNames()` 全部出现在帮助里”的断言，保持双向守卫。
5. 两份 `README*.adoc` 的 `BEGIN/END GENERATED: help-catalog` 块按 `HelpCatalog.readmeBlock()` 重新生成。

### 5.3 行为差异（相对 testcli）

- **所有语句**都受 IF 控制（testcli 只拦 SQL/HTTP，普通命令仍执行）；这是修正，不是兼容。
- 条件没有 Python 的任意表达式能力，只有固定比较；换来可审计、无依赖、可测试。
- `_IF` / `_LOOP` 的 body 都在关闭标记到达后才执行；testcli 是边读边执行（IF 假时跳过）。因此输出的时间点整体后移到 `_ENDIF` / `_ENDLOOP` 之后。
- 循环体在 `_LOOP` 之后不会立即执行，而是在 `_ENDLOOP` 后开始；交互式输入时这是块语义的自然结果。
- 循环每轮多一条 `Loop iteration ...` 进度行（`_SET LOOPTRACE`，默认 ON），因此 stdout / `--ref` 会比“只回显一次”多出 N 行进度。
- 输入回显在循环里只出现一次（testcli 每轮都回显）；结果仍按轮次出现。
- 新增安全上限与结构校验。
- 不再支持 `_LOOP END`、`_LOOP BEGIN UNTIL`；迁移表见 §5.1。

---

## 6. 测试设计

### 6.1 分层

| 层 | 测试 | 目的 |
|---|---|---|
| 词法/语法 | `antlr/SqlScriptParseTest`（扩展）、新增 `FlowParseTest` | 所有形态可解析；非法形态不崩；token 形状稳定 |
| IR | 新增 `script/FlowIrContractTest` | 字段名/值/空格/错误码逐字节冻结 |
| 条件 | 新增 `assertion/ConditionEvaluatorTest` | 比较符、取值器、TRUE/FALSE、错误；`_ASSERT` 行为不变 |
| 控制流核心 | 新增 `engine/flow/ControlFlowTest` | 捕获、建树、执行、break/continue、错误、限制 |
| 会话集成 | 扩展 `engine/ScriptRunnerTest`、新增 `ScriptSessionFlowTest` | 多行 SQL/过程/`_ECHO`/`_HOST`；续行、reset、EOF、echo、timing |
| 控制台 | 扩展 `repl/InteractiveConsoleTest` / `CliConsoleModeTest` | 提示符、Ctrl-C、批处理/控制台一致 |
| CLI 端到端 | 新增 `CliFlowTest`（SQLite） | 真实 `--execute`、`--logfile`、`--ref` |
| 帮助/文档 | `HelpCatalogReadmeTest` | `_HELP` 与两份 README 同源 |
| 回归 | 全模块 `mvn test` | 不影响既有测试类（含 `Cli*`、`ScriptRunnerTest` 等） |

### 6.2 用例矩阵

| # | 用例 | 断言点 |
|---|---|---|
| 1 | `_IF {% 3=3 %}` / `_IF {% 3=4 %}` | 真/假分支只执行一个；无输出差异 |
| 2 | `_IF` / `_ELSEIF` / `_ELSE` 多分支 | 只执行第一个为真的分支；后面条件不求值 |
| 3 | `_IF` 无 `_ELSE` 且条件假 | body 一条都不执行；后续语句照常 |
| 4 | `_IF` 条件错误 | 报 `If condition error`；整块不执行；错误计数 +1 |
| 5 | `_LOOP 0` / `_LOOP 3` | body 执行 0/3 次；最后一轮的 `RESULT.*` 保留 |
| 6 | `_LOOP WHILE` 首轮为假 | body 0 次 |
| 7 | `_LOOP UNTIL` 首轮为真 | body 至少 1 次 |
| 8 | `_LOOP FOREVER` + `_LOOP BREAK` | 正确跳出；`_ENDLOOP` 之后继续 |
| 9 | `_LOOP CONTINUE` | 跳过本轮剩余语句；条件/计数按语义继续 |
| 10 | 嵌套 `_LOOP` / `_IF` 三层 | break/continue 只影响最内层；条件每轮重算 |
| 11 | `_LOOP BREAK` 在循环外 | 执行到时报错；不执行时（死分支）不报 |
| 12 | 未配对 `_ENDIF` / `_ELSE` / `_ENDLOOP` | 可读错误；不崩溃；后续行继续 |
| 13 | 交叉关闭 | 报错；未关闭块在 EOF 再报；不执行坏块 |
| 14 | 缺少 `_ENDIF` / `_ENDLOOP` 到 EOF | 一条 incomplete block 错误；不执行半截块 |
| 15 | 深度超限 / 轮数超限 | 可读错误；脚本继续；`_WHENEVER` 生效 |
| 16 | 条件里的 `${}` | 求值时展开；循环每轮看到最新值 |
| 17 | 块内 `create procedure … /` | 整段执行一次；内部 `;` 不切坏 |
| 18 | 块内 `_ECHO … echo off` | 块体只作为数据输出；其中的 `_ENDIF` 不关块 |
| 19 | 块内 `_HOST """…"""` | 多行命令完整执行；内部 `_ENDIF` 不关块 |
| 20 | 块内 `_EXIT 7` | 立即停止；退出码 7；剩余节点不执行 |
| 21 | 块内 `_WHENEVER ERROR EXIT 3` | 块内错误触发退出 3 |
| 22 | 控制台输入 `_IF` | 提示符变 `   > `；`_ENDIF` 后恢复；Ctrl-C 丢弃块 |
| 23 | 批处理 vs 控制台同一脚本 | stdout/退出码一致；日志差异只在转录行 |
| 24 | `--logfile` 循环 | 输入原文一次；进度行 N 次；结果 N 次；顺序稳定 |
| 25 | `--ref` 控制流脚本 | 首跑录制、再跑比对一致；不一致退出 1 |
| 26 | `_HELP` / `_HELP IF` / `_HELP LOOP` | 条目齐全；README 同步 |
| 27 | 既有脚本（无控制流） | 全套既有测试不变 |
| 28 | 作业里跑控制流 + `_JOB ABORT` | 停止信号在轮次/节点间生效 |
| 29 | `_SET LOOPTRACE OFF` / `FEEDBACK OFF` | 进度行消失；SQL 仍执行；退出码不变 |
| 30 | 嵌套/0 次进度行 | `(depth 2)` 与 `Loop did not execute (0 iterations).` 逐字节正确 |

### 6.3 夹具与黄金文件

- 解析夹具：`src/test/resources/scripts/flow.sql`（覆盖 IF/LOOP 各种形态，供 `SqlScriptParseTest` 逐块解析）。
- 语义夹具：测试里用 `FakeSqlExecutor` 注册 `select count(*)` 等结果，避免真实数据库。
- CLI 黄金文件：`CliFlowTest` 用 `@TempDir` 生成脚本与 ref，不提交易漂移的绝对路径。
- 控制台：复用 `ScriptedLineSource`，记录 prompt 序列。

### 6.4 性能与稳定性

- 捕获是 O(脚本大小)；循环重放会重复分析语句，v1 接受。
- 单测里轮数上限注入 5，不跑百万次；另有一个 `FakeSqlExecutor` 的 1,000 轮 smoke（可选，保持测试时间可控）。
- 不使用真实网络/数据库；SQLite 用例 hermetic。

---

## 7. 代码改动清单

### 7.1 主代码

| 文件 | 改动 |
|---|---|
| `dbcli/src/main/antlr4/.../BaseLexer.g4` | 新增控制命令 token、`ConditionMode`、`LoopHeaderMode` |
| `dbcli/src/main/antlr4/.../BaseParser.g4` | `baseCommand` 增加控制规则；新增 `ifCommand` / `elseIfCommand` / `elseCommand` / `endifCommand` / `loopCommand` / `endloopCommand` |
| `dbcli/src/main/java/.../script/SqlIrVisitor.java` | 新增 6 个 visit 方法；条件去壳；loops 的 action/mode/count/expression；可读错误 |
| `dbcli/src/main/java/.../assertion/ConditionEvaluator.java` | **新增**：固定形态条件的布尔求值（复用 `AssertEvaluator.evalExpression`） |
| `dbcli/src/main/java/.../assertion/package-info.java` | 说明该包同时服务 `_ASSERT` 与控制流条件 |
| `dbcli/src/main/java/.../engine/flow/package-info.java` | **新增**：包职责与阅读顺序 |
| `dbcli/src/main/java/.../engine/flow/ControlFlow.java` | **新增**：状态机、帧、建树、校验、执行入口 |
| `dbcli/src/main/java/.../engine/flow/FlowNode.java` | **新增**：节点类型（sealed interface） |
| `dbcli/src/main/java/.../engine/flow/FlowSignal.java` | **新增**：信号枚举 |
| `dbcli/src/main/java/.../engine/flow/LoopSpec.java` | **新增**：循环形态 |
| `dbcli/src/main/java/.../engine/flow/FlowLimits.java` | **新增**：深度/轮数上限 |
| `dbcli/src/main/java/.../engine/flow/FlowHost.java` | **新增**：ControlFlow 对引擎的最小接口 |
| `dbcli/src/main/java/.../engine/StatementBoundary.java` | 新增 `_HOST """` 三引号块边界（见 §4.7.4） |
| `dbcli/src/main/java/.../engine/ScriptSession.java` | 持有 `ControlFlow`；`executeStatement(raw, flow)`；`isIncomplete` / `reset` / `finish` / `finishPending` |
| `dbcli/src/main/java/.../engine/ScriptRunner.java` | 实现 `FlowHost`；控制行/捕获/叶子三分支；`executeLeaf` 重构；`maskForAnalysis` 调用；诊断位置；`LOOPTRACE` 输出 |
| `dbcli/src/main/java/.../engine/ScriptContext.java` | 新增 `loopTraceOn()` 选项读取 |
| `dbcli/src/main/java/.../engine/SetOption.java` | 新增 `LOOPTRACE`（默认 `on`） |
| `dbcli/src/main/java/.../engine/VariableResolver.java` | 新增 `maskForAnalysis`（仅结构探测用） |
| `dbcli/src/main/java/.../help/HelpCatalog.java` | 新增 8 条 usage（含 `_SET LOOPTRACE`）、`IF`/`LOOP` 主题、HELP 主题列表 |
| `dbcli/README.adoc` / `README-CN.adoc` | 生成块 + 新增 7.12 控制流小节 |
| `dbcli/dbcli-devguide.md` | 包表增加 `engine.flow`；扩展指引说明结构命令 |
| 根 `README.adoc` / `README-CN.adoc` | dbcli 能力表增加“流程控制”一行（可选但推荐） |

建议的 `_HELP` 速查条目（顺序可随 `HelpCatalog` 既有风格微调，但文本要稳定）：

```text
_IF {% <condition> %}
_ELSEIF {% <condition> %}
_ELSE
_ENDIF
_LOOP [<count>|WHILE {% <condition> %}|UNTIL {% <condition> %}|FOREVER]
_LOOP BREAK|CONTINUE
_ENDLOOP
_SET LOOPTRACE ON|OFF
```

### 7.2 测试

| 文件 | 改动 |
|---|---|
| `antlr/SqlScriptParseTest.java` | 更新 removed 断言；新增控制流 parse/token shape |
| `antlr/FlowParseTest.java` | **新增**（或并入上者）：所有形态/非法形态 |
| `script/FlowIrContractTest.java` | **新增**：IR 精确契约 |
| `assertion/ConditionEvaluatorTest.java` | **新增**：条件求值 |
| `engine/flow/ControlFlowTest.java` | **新增**：捕获/执行/错误/限制 |
| `engine/ScriptRunnerTest.java` | 扩展：块内语句、echo、timing、exit、whenever |
| `engine/ScriptFlowTest.java` | **新增**（实现时名为 ScriptFlowTest）：块执行、RESULT 条件、循环重放、`_START` 隔离、LOOPTRACE |
| `engine/StatementBoundaryTest.java` | 扩展：`_HOST """` 块边界（单行/多行/块内含 `_ENDIF`/块内 `;`） |
| `engine/VariableResolverTest.java` | 扩展：`maskForAnalysis` 的占位符替换 |
| `repl/InteractiveConsoleTest.java` | 扩展：`_IF` 续行提示、Ctrl-C |
| `CliFlowTest.java` | **新增**：SQLite 端到端、log/ref |
| `help/HelpCatalogReadmeTest.java` | 主题列表增加 IF/LOOP |
| `engine/command/CommandCatalogTest.java` | 接受 `ControlFlow.supports`；新增双向守卫 |
| `src/test/resources/scripts/flow.sql` | **新增**：解析夹具 |

### 7.3 文档

- `dbcli/docs/control-flow-design.md`（本文）
- 实现完成后更新 `dbcli/README.adoc`、`dbcli/README-CN.adoc`、`dbcli/dbcli-devguide.md`
- 建议在 `dbcli/README` 的“命令参考”末尾新增 `=== 7.12 _IF / _LOOP（流程控制）`，避免重排现有 7.1–7.11 的编号

---

## 8. 工作计划

### 8.1 假设与节奏

- 1 名后端开发 + 1 名评审（可兼任测试）；Java 21 / Maven 3.9 已就绪；无外部数据库依赖。
- 以“每个任务都能独立跑 `mvn -o -B -pl dbcli -am test`”为节奏；不把大改留到最后。
- 估算单位为人日；含自测，不含评审等待。

### 8.2 任务分解

| ID | 任务 | 产出 | 依赖 | 估算 |
|---|---|---|---:|---:|
| CF-00 | 基线与决策冻结：跑通现有测试、确认语义/文案/上限、准备示例脚本 | 基线记录、评审通过的本文 | — | 0.5 |
| CF-01 | 词法/语法：`BaseLexer.g4`、`BaseParser.g4`、mode 规则、token 形状 | 可生成的 parser、parse 测试 | CF-00 | 1.5 |
| CF-02 | IR：`SqlIrVisitor` 6 个 visit、条件去壳、loop 字段、可读错误 | IR 契约测试 | CF-01 | 0.5 |
| CF-03 | 条件求值：`ConditionEvaluator`、`maskForAnalysis`、`_ASSERT` 回归 | 条件测试、无回归 | CF-02 | 0.5 |
| CF-04 | 控制流核心：`engine.flow` 包（帧/节点/建树/执行/信号/上限/恢复） | `ControlFlowTest` | CF-02/03 | 2.5 |
| CF-05 | 会话/执行器集成：`ScriptSession`/`ScriptRunner`/`FlowHost`/`executeLeaf`/EOF/提示/echo/timing；`_HOST """` 边界配套；`LOOPTRACE` 进度输出 | `ScriptSessionFlowTest`、扩展 `ScriptRunnerTest` | CF-04 | 1.75 |
| CF-06 | 帮助与文档：`HelpCatalog`（含 `_SET LOOPTRACE`）、两份 README、devguide、根 README | 文档同步测试通过 | CF-02（语法冻结） | 1.0 |
| CF-07 | 端到端与回归：`CliFlowTest`（SQLite/log/ref）、控制台、作业停止、全量测试 | E2E 绿、全量绿 | CF-05/06 | 1.5 |
| CF-08 | 评审与硬化：边界用例、文案复核、提交拆分、变更说明 | 可合并的 PR | CF-07 | 1.0 |
|  | **合计** |  |  | **10.75** |
|  | 缓冲（20%） |  |  | **2.15** |

> 单人串行约 **12.9 人日（约 2.5 周）**；若文档与测试并行，可压到约 9–10 个工作日。

### 8.3 里程碑

| 里程碑 | 完成条件 |
|---|---|
| M1 语法/IR 冻结（CF-01/02） | 所有控制形态可解析；IR 契约测试绿；移除旧“必须失败”断言 |
| M2 核心可用（CF-04/05） | IF/LOOP/break/continue 在 hermetic 测试中全部正确；控制台提示正常 |
| M3 功能完成（CF-06/07） | 帮助/README 同步；SQLite E2E、log/ref、作业停止通过；全量测试绿 |
| M4 可合并（CF-08） | 评审意见关闭；无 `-Werror` 警告；提交历史清晰 |

### 8.4 提交序列（建议）

1. `feat(dbcli): 增加 _IF/_ELSEIF/_ELSE/_ENDIF/_LOOP/_ENDLOOP 语法与 IR`
2. `feat(dbcli): 增加固定形态条件求值器与变量探测`
3. `feat(dbcli): 实现控制流块捕获与解释执行`
4. `feat(dbcli): 控制流接入脚本会话与控制台`
5. `docs(dbcli): 补充控制流帮助与中英手册`
6. `test(dbcli): 控制流端到端与回归`

每个提交都应能单独构建；第 1 个提交后旧测试的“必须失败”断言要同步更新，避免中间提交红。

### 8.5 验收标准

- [ ] `mvn -o -B -pl dbcli -am test` 全绿（含既有测试与新增测试）。
- [ ] `mvn -o -B -pl dbcli -am -DskipTests package` 成功，无新增 `-Werror` 警告。
- [ ] 所有 §6.2 用例有对应测试，或明确标注为手工验证。
- [ ] `_HELP`、`_HELP IF`、`_HELP LOOP` 输出正确；两份 README 生成块逐字节同步。
- [ ] 无控制流脚本的 stdout / 退出码 / 日志与改动前一致（既有测试 + 手工抽样）。
- [ ] 控制台：`_IF` 后续行提示 `   > `；Ctrl-C 丢弃块；EOF 报未关闭。
- [ ] `--logfile` 中循环体原文一次、结果 N 次；`--ref` 可稳定录制/比对。
- [ ] 错误文案可读、不出现 ANTLR `line…:…` 文案；错误计数与 `_WHENEVER` 正确。
- [ ] 评审通过，设计文档与实现一致（文案/限制/示例）。

### 8.6 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 块体完整性探测误判（存储过程/`$$`） | 语句被提前切碎/晚执行 | 复用 `SqlAnalyzer` + mask；专项测试 17/18/19；CF-01 先做 spike |
| 词法 mode 与既有 token 冲突（`_ELSE`/`_ELSEIF`、注释） | 解析错误/误识别 | 已做 4.11.1 原型；CF-01 加 token shape 测试；`_ELSEIF` 先声明 |
| 回显/日志顺序变化 | `--logfile` 夹具变化、用户困惑 | 明确“输入回显一次 + 每轮进度 + 结果 N 次”的契约；新增 log 测试；文档说明 |
| 控制流状态串到子脚本/作业 | 块错配、行为不可预测 | 状态挂在 `ScriptSession`；`_START`/job 专项测试 |
| 死循环/深嵌套 | 脚本跑飞、栈溢出 | 64 层 / 1,000,000 轮上限；测试注入小上限 |
| 条件求值语义与 `_ASSERT` 漂移 | 同一表达式两种结果 | `ConditionEvaluator` 复用 `AssertEvaluator.evalExpression`；共享用例 |
| 解析层文案泄漏 | 用户体验/契约破坏 | `ControlFlow` 自产文案；`parserError` 分支测试 |
| 文档与实现漂移 | 构建红/误导 | `HelpCatalogReadmeTest`、IR/parse 契约测试、评审清单 |
| 交互式体验（提示符/取消） | 控制台难用 | `isIncomplete` 接入、`InteractiveConsoleTest` |

---

## 9. 决策记录与开放问题

| # | 决策 | 结论 | 理由 / 影响 |
|---|---|---|---|
| D1 | 条件语法 | **必须 `{% ... %}`**；不提供裸形态 | 与循环头消歧；与 testcli 一致；裸形态给可读错误 |
| D2 | 条件能力 | 复用 `_ASSERT` 固定比较，不引入表达式引擎 | 安全、可审计、无新依赖；代价是没有算术/`AND/OR` |
| D3 | `${}` 展开时机 | **求值时**展开（循环每轮重新展开） | 循环条件能看到最新变量；与 `VAR.*` 并存 |
| D4 | 裸 `_LOOP` | 等价 `FOREVER` | 用户要求 `_LOOP _ENDLOOP` 可用；有上限保护 |
| D5 | 循环间隔 | v1 不做 `INTERVAL` | 用 `_SLEEP`；避免新增语义面 |
| D6 | 循环进度 | 每轮一条 `Loop iteration ...` 状态行；默认 ON，`_SET LOOPTRACE OFF` / `FEEDBACK OFF` 可关 | 能看清执行进度；输入回显仍只一次，避免日志被脚本原文撑大；结果/`--ref` 按轮次可校验 |
| D7 | 条件错误 | 放弃整个构造（IF 不执行任何分支 / LOOP 停止） | 避免笔误误跑 `_ELSE`；错误已打印/计数 |
| D8 | `BREAK` 在循环外 | 执行到才报错 | 死分支不报；符合“未执行不报错” |
| D9 | 上限 | 64 层 / 1,000,000 轮 | 防跑飞；测试可注入更小值 |
| D10 | testcli 旧别名 | 不支持（`_LOOP END` 等） | 一套语法更清晰；提供迁移表 |
| D11 | `_ASSERT` 的 `TRUE/FALSE` | 不支持（保持原行为） | 避免改变既有断言契约；`_IF` 自己支持 |
| D12 | 命令注册 | 控制流不进 `CommandCatalog`，由 `ControlFlow` 实现并登记名字 | 结构命令需要捕获/非局部控制流，不适合叶子 `ScriptCommand`；测试增加第三来源 |
| D13 | 失败块恢复 | `failed`/`discard` 帧只配对、不执行 | 防止错误级联与副作用 |
| D14 | 多行条件 | v1 不支持 | `StatementBoundary` 的 COMMAND 判定与续行提示复杂；单行足够 |
| D15 | 是否加 `RUN.ERRORCOUNT` / `LAST.ERRORCODE` | 暂不 | 现有 `RESULT.*` 只反映 SQL；命令状态可用 `JOB.*`/`RUN.EXITCODE`；后续单独评估 |

**开放问题（评审时可改）：**

1. 循环上限 1,000,000 是否合适？轮询类脚本通常远小于此；如担心长轮询，可改为 100,000 或通过 `_SET` 配置。
2. 循环进度行的默认值/格式是否合适？当前默认 ON，格式 `Loop iteration 1 of 3.` / `Loop iteration 1.` / `(depth 2)`；如需更安静可 `_SET LOOPTRACE OFF`。
3. 是否需要 `_LOOP INTERVAL <秒>` 的语法糖？目前建议用 `_SLEEP`，避免新增关键字。
4. 是否需要 `RUN.ERRORCOUNT` 供条件判断命令失败？这是独立增强，建议另开任务。

---

## 10. 附录

### 10.1 建议的错误文案（实现时冻结）

> 风格与 dbcli 现有文案一致：英文、方括号包取值、句号结尾。最终以测试为准。

```
_IF requires a {% <left> <op> <right> %} condition.
_ELSEIF requires a {% <left> <op> <right> %} condition.
_IF condition must be written as {% <left> <op> <right> %}.
_ELSEIF condition must be written as {% <left> <op> <right> %}.
_ELSE without a matching _IF.
_ELSEIF without a matching _IF.
_ENDIF without a matching _IF.
_ENDIF cannot close an open _LOOP (use _ENDLOOP).
_ELSE must be the last branch of an _IF block.
_ELSEIF cannot follow _ELSE.
_ELSE does not take arguments.
_ENDIF does not take arguments.
_ENDLOOP does not take arguments.
_ENDLOOP without a matching _LOOP.
_ENDLOOP cannot close an open _IF (use _ENDIF).
_LOOP header is invalid: [<text>].
_LOOP WHILE requires a {% ... %} condition.
_LOOP UNTIL requires a {% ... %} condition.
Loop count [<n>] exceeds the limit [1000000].
Loop iteration limit (1000000) exceeded; the loop was stopped.
_IF requires _ENDIF before the end of the script.
_LOOP requires _ENDLOOP before the end of the script.
_LOOP BREAK used outside a loop.
_LOOP CONTINUE used outside a loop.
Block nesting is too deep (limit 64).
If condition error: [<detail>].
Loop condition error: [<detail>].
```

### 10.2 端到端示例脚本（SQLite）

```sql
_connect jdbc:sqlite::memory:
create table t (id integer);
_LOOP 3
    insert into t values (1);
_ENDLOOP
select count(*) as n from t;
_IF {% RESULT.ROWS[0][0] = 3 %}
    _ECHO t
    count-is-three
    echo off
_ELSE
    _assert 1 = 2, count-should-be-three
_ENDIF
```

预期：

- `count-is-three` 输出一次；
- `_LOOP` 的 3 条 insert 各产生一行状态（按实际 `_SET FEEDBACK`）；
- 断言不触发；
- `--logfile` 中 `_LOOP`/insert/`_ENDLOOP` 原文各一次，`Loop iteration 1 of 3.` 等进度行 3 条，insert 的结果/状态 3 次。

### 10.3 实现检查清单

- [ ] 语法/IR/visitor 错误路径不抛异常。
- [ ] `ControlFlow` 不使用 `ScriptRunner` 的具体类型（只依赖 `FlowHost`）。
- [ ] 每个 open 帧都有明确的关闭/EOF 处理。
- [ ] `failed`/`discard` 帧不产生节点。
- [ ] break/continue 只被最内层循环消费。
- [ ] 每轮/每节点检查 exit/stop。
- [ ] 捕获阶段无输出、无诊断、无执行。
- [ ] 叶子执行只有一条路径（普通/重放共用 `executeLeaf`）。
- [ ] 既有 `_ASSERT`/`_START`/`_JOB`/`_SPOOL` 测试全绿。
- [ ] `_HELP` 与 README 生成块一致。
- [ ] 错误文案不含 ANTLR 位置串。
- [ ] 设计文档中的决策与实现一致。

---

## 11. 参考

- Python 参考实现：`C:\work\testcli\testcli\cmdexecute.py`（IF/LOOP 的执行分支，约 1304–1359 行）、
  `C:\work\testcli\testcli\antlr\BaseLexer.g4`（`IfMode` / `LoopMode`）、
  `C:\work\testcli\testcli\antlr\BaseParser.g4`（`if` / `loop` 规则）
- dbcli 现有文档：`dbcli/README-CN.adoc`、`dbcli/README.adoc`、`dbcli/dbcli-devguide.md`
- 断言语义：`dbcli/src/main/java/org/slackerdb/dbcli/assertion/AssertEvaluator.java`
- 语句切分：`StatementBoundary.java`、`StatementSplitter.java`、`ScriptSession.java`
- 帮助同源：`HelpCatalog.java`、`HelpCatalogReadmeTest.java`
