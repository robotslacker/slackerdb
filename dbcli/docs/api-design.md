# dbcli `_API` 设计文档（HTTP 客户端命令）

> 状态：已实现（`mvn -o -B -pl dbcli -am test` 通过，待评审）
> 版本：v1.0
> 日期：2026-10-30
> 变更：v1.0 实现 `_ECHO @name` + `bodyvar=` 请求体捕获；默认跟随 3xx；`follow=false` 可关闭。
> 适用模块：`dbcli`（SlackerDB 命令行客户端）
> 关联代码：`dbcli/src/main/antlr4/.../BaseLexer.g4`、`BaseParser.g4`、
> `script/SqlIrVisitor.java`、`engine/command/CommandCatalog.java`、
> `engine/ScriptContext.java`、`engine/VariableResolver.java`、
> `assertion/AssertEvaluator.java`、`help/HelpCatalog.java`

---

## 0. 结论摘要

- **定位**：给 dbcli 脚本增加一个**单行、 curl 风格但明显更小**的 HTTP 客户端命令
  `_API`，用于在 SQL 测试脚本里调用 REST API、检查响应、把响应中的值带入下一次调用。
- **语法**：`_API <METHOD> <URL> [header='Name: Value']... [body='...' | bodyfile=<file> | bodyvar=<name>] [timeout=<秒>] [allow=<状态码|all>] [follow=true|false]`。
  命令按行收尾，不需要 `;`（写了也接受）；请求体支持单行内联、文件，或由
  `_ECHO @name` 捕获的多行块（再用 `bodyvar=name` 引用）。
- **响应**：默认先打印一行 `HTTP <status>`，再把响应体按文本逐行作为数据输出；
  非预期状态码按一次脚本错误处理（默认 2xx 成功），可用 `allow=404` / `allow=all` 放宽。
- **重定向**：默认跟随 3xx（JDK `Redirect.NORMAL`），可用 `follow=false` 关闭。
- **取值**：每次请求后刷新一组 `API.*` 运行时变量：
  `API.STATUS`、`API.OK`、`API.BODY`、`API.ERROR`、`API.HEADER.<name>`、
  `API.JSON.<path>`。它们既可用于 `_ASSERT` / `_IF`，也可用 `${API.JSON.token}`
  带入下一次请求。
- **实现**：使用 JDK 21 自带的 `java.net.http.HttpClient`；JSON 用仓库已有的
  `fastjson2`。**不新增第三方依赖**，不引入脚本/表达式引擎。
- **明确不做**：自定义重定向策略/最大次数、Cookie 持久化、代理、multipart、二进制下载、
  失败重试、TLS 跳过校验、HTTP 代理认证、`--insecure` 之类 curl 选项。
- **工作量**：约 **6.5 人日**，含 20% 缓冲约 **7.8 人日**；里程碑见 §8.2
  （语法/IR → HTTP 核心 → 会话/断言集成 → 文档/E2E）。

---

## 1. 背景与目标

### 1.1 现状

dbcli 目前的脚本能力集中在数据库与“本机/远端命令”：

- SQL：连接、执行、结果渲染、`_ASSERT`、`_COMPARE`、`--reference`；
- 本地命令：`_HOST`；
- 远端命令与文件：`_SSH` / SFTP；
- 并发：`_JOB`；
- 控制流：`_IF` / `_LOOP`；
- 变量：`_SET @name value`、`--var`、`${name}`。

没有 HTTP 调用能力。涉及 REST API 的联调/回归脚本只能：

1. 用 `_HOST curl ...` 调外部 curl，再解析 stdout；或
2. 在 dbcli 之外用别的工具串联，失去“一个脚本跑完 DB + API”的能力。

`_HOST curl` 的问题很直接：

- 依赖目标机器安装 curl；
- 命令文本、引号、平台差异都要脚本作者自己处理；
- 响应状态码、响应头无法结构化取值；
- 失败信息与 dbcli 的错误计数、`_WHENEVER`、`--silent`、`_SPOOL` 不统一；
- 无法把 JSON 字段直接带入下一条 SQL 或下一次请求。

### 1.2 目标

1. 提供 `_API` 命令，覆盖 API 测试最常用的 20% 功能：
   - 方法、URL；
   - 请求头；
   - 请求体（内联文本 / 文件 / `_ECHO @name` 捕获的多行块）；
   - 超时；
   - 响应状态、响应头、响应体；
   - 预期状态码；
   - 默认跟随 3xx，可关闭。
2. 响应可被脚本消费：
   - `_ASSERT {% API.STATUS = 200 %}`；
   - `_IF {% API.OK = false %}`；
   - `_SET @token ${API.JSON.access_token}`；
   - `${API.BODY}` / `${API.HEADER.CONTENT_TYPE}`。
3. 与 dbcli 既有纪律一致：
   - stdout 逐字节可比较，行尾固定 `\n`；
   - 错误走 stdout（经 `ScriptOutput`），并计一次错误；
   - `_WHENEVER ERROR`、`_EXIT`、`_SPOOL`、`--logfile`、`--ref`、`--silent` 继续成立；
   - `_HELP` 与中英 README 同源；
   - 不新增依赖，不引入表达式引擎。
4. 在交互式控制台、`--execute`、`_START`、`_JOB` 中行为一致。
5. 安全默认：
   - 默认校验 TLS；
   - 默认 30 秒超时；
   - 默认跟随 3xx（可用 `follow=false` 关闭）；
   - 响应体有大小上限；
   - 不自动持久化 Cookie；
   - 敏感值推荐通过 `${变量}` 传入，日志回显的是 `${变量}` 原文而不是展开值。

### 1.3 非目标

以下功能**明确不在 v1**，需要时再单独立项：

- 自定义重定向策略 / 最大跳转次数（默认跟随 3xx，`follow=false` 关闭）；
- Cookie jar / 会话保持；需要时手工取 `API.HEADER.Set-Cookie`，下一次手工发 `Cookie`；
- multipart/form-data、文件上传、二进制响应落盘；
- 代理、PAC、系统代理自动发现；
- `--insecure` / 跳过 TLS 校验；
- 客户端证书、OAuth 自动流程、Basic/Digest 自动认证；
- 重试、退避、限速、并发请求池；
- `_API` 自带的多行 `body="""..."""` 块（多行体用 `_ECHO @name` + `bodyvar=` 或 `bodyfile=`）；
- HTTP/2 强制、压缩协商、`Content-Encoding: gzip` 解压；
- 断点续传、下载进度、响应头打印开关；
- `_API` 自己的断言 DSL（统一用 `_ASSERT`）。

### 1.4 设计原则（与 dbcli 既有约束对齐）

| dbcli 既有约束 | 本设计的做法 |
|---|---|
| 一条命令一个类，命令只认 IR + `CommandCall` | `_API` 是普通叶子 `ScriptCommand`，登记在 `CommandCatalog` |
| 语法层只做“取出文本”，语义在命令/引擎层 | 语法只捕获 `_API` 之后的整行原文；选项解析放在 `api` 包 |
| 命令按行收尾 | `_API` 单行；`StatementBoundary` 现有的 `COMMAND` 分支直接适用 |
| 错误只走 `ErrorReporter` / `CommandHost` | 请求失败、非预期状态、参数错误都走 `call.reportError` / `call.countFailure` |
| stdout 逐字节可比较 | 状态行固定 `HTTP <code>`；不打印 `Date`、`Server` 等响应头；行尾统一 `\n` |
| `_ASSERT` 只对显式建模的数据取值 | 新增 `API.*` 取值器；值来自最近一次 `_API` 的运行时变量 |
| `_SET` 选项集中管理 | v1 **不增加** `_SET` 选项；超时等用命令参数 |
| `_HELP` 与 README 同源 | `HelpCatalog` 增加条目与主题；两份 README 同步 |
| 编译警告即失败（`-Werror`） | 新代码按现有风格编写，不留下未使用字段/导入 |
| 无数据库也能单测 | 用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起本地 HTTP 夹具 |

---

## 2. 需求与语义

### 2.1 命令总览

| 形态 | 作用 |
|---|---|
| `_API GET <url>` | 发 GET，打印状态与响应体 |
| `_API POST <url> body='...'` | 发 POST，带内联请求体 |
| `_API PUT/PATCH/DELETE <url> ...` | 其它常用方法 |
| `_API HEAD <url>` | 只取状态/响应头，无响应体输出 |
| `_ECHO @name` … `echo off` | 把一段多行文本捕获成变量（不打印），供 `bodyvar=` 使用 |
| `header='Name: Value'` | 追加请求头，可重复 |
| `body='...'` | 单行内联请求体 |
| `bodyfile=<file>` | 从 UTF-8 文件读取请求体（多行 JSON 可用这个或 `_ECHO @name`） |
| `bodyvar=<name>` | 使用 `_ECHO @name` 捕获的请求体 |
| `timeout=<秒>` | 单次请求总超时；0 表示不限；默认 30 |
| `allow=404` / `allow=404,409` / `allow=all` | 放宽“预期状态码”；默认 2xx |
| `follow=true/false` | 是否跟随 3xx；默认 `true` |

方法：`GET`、`HEAD`、`POST`、`PUT`、`PATCH`、`DELETE`、`OPTIONS`。
方法不区分大小写，IR 里统一成大写。

### 2.2 语法

```text
_API <METHOD> <URL>
     [header='<Name>: <Value>']...
     [body='<text>' | bodyfile=<path> | bodyvar=<name>]
     [timeout=<seconds>]
     [allow=<code>[,<code>...] | all]
     [follow=true|false]
```

规则：

1. `_API` 之后的所有内容都在**同一行**；命令按行收尾，不需要 `;`。
   行尾一个可选的 `;` 会被忽略；如果 URL/请求体本身以 `;` 结尾，请加引号或改用文件。
   另外，dbcli 的语句切分仍把**引号外**的顶层 `;` 当作分隔符：URL 或请求体里含 `;`
   （如矩阵参数、部分表单/JSON 文本）时，必须把整个值加引号，或改用 `bodyfile=` /
   `_ECHO @name`。
2. 前两个位置参数是**方法**和 **URL**，顺序固定，不能省略。
3. 其余参数都是 `name=value`：
   - `header=` 可重复；
   - `body=` / `bodyfile=` / `bodyvar=` 最多出现一个；
   - `timeout=`、`allow=`、`follow=` 最多出现一次；重复给可读错误。
4. 选项名不区分大小写；方法不区分大小写；URL 与值大小写敏感。
5. 值可以用单引号或双引号包住，以便包含空格：
   - 外层引号会被去掉；
   - **引号内的内容按字面量处理，不做反斜杠转义**（这样 JSON 里的 `\n`、`\"` 不会被 dbcli 吃掉）；
   - JSON 请求体推荐用单引号包住，例如 `body='{"name":"demo"}'`；
   - 如果内容同时需要单引号和双引号，或需要多行，用 `_ECHO @name` + `bodyvar=`（或 `bodyfile=`）。
6. 命令行里的 `${name}` 在解析前由现有 `VariableResolver` 展开；
   展开结果里即使有空格，只要仍在外层引号内，就不会被拆成多个选项。
7. URL 必须是绝对 `http://` 或 `https://` URL；不支持相对 URL、`file:`、`ftp:`。
   空格、中文等字符必须由脚本作者先 percent-encode（例如 `%20`），dbcli 不做 URL 重写。
8. 请求头格式是 `Name: Value`，按**第一个冒号**切分；名称不能为空，值允许为空。
   值首尾空白会被去掉。
9. `body=` / `bodyfile=` / `bodyvar=` 不允许用于 `GET`、`HEAD`；`DELETE` 允许带体。
10. `_API` 行内不要写 `--` 注释：dbcli 的语句扫描器会把**引号外**的 `--` 当行注释，
    URL/请求体里含 `--` 时必须加引号或改用 `bodyfile=` / `_ECHO @name`；
    `#` 只在行首才是注释。

### 2.2.1 用 `_ECHO @name` 生成请求体

请求体可以先按 `_ECHO` 的块写法生成，再用 `bodyvar=` 交给 `_API`：

```sql
_ECHO @login_body
{
  "user": "demo",
  "password": "${password}",
  "tags": ["a", "b"]
}
echo off

_API POST https://api.example.com/login header='Content-Type: application/json' bodyvar=login_body
```

语义：

- `_ECHO @name` 是现有 `_ECHO … echo off` 块的**捕获形态**：块内每一行仍是数据，
  `${}` 仍在该命令执行时展开；但**不打印**到 stdout/日志，而是把整段文本存进脚本变量 `name`。
- 捕获文本就是 `_ECHO` 的块正文（`echo off` 分隔符本身不计入），**不额外补行尾**，内部空行保留。
- 变量名规则与 `_SET @name` 一致（字母/下划线开头，可含字母、数字、下划线和点）；
  非法名字或空的 `@` 报可读错误。
- 捕获结果就是一个普通脚本变量，因此 `${login_body}` 也可用于 `_ECHO` / `_SET` 等；
  但**不要把多行变量直接塞进 `_API` 命令行**（换行/`;`/`--` 会被语句切分或注释规则截断），
  要用 `bodyvar=login_body`。
- `bodyvar=` 的名字必须已定义；缺失时给
  `Unknown _API body variable [login_body].`，不会静默发送空体。
- 空块是合法的，捕获为空串；`bodyvar=` 会发送空请求体。
- `_ECHO @name` 不改变普通 `_ECHO <标题>` 的打印行为。

这样做的好处：多行 JSON 不再需要临时文件，也不需要给 `_API` 增加新的多行块语法；
`;`、`--`、引号都处于 `_ECHO` 数据块内，不会被语句切分器误伤。

### 2.3 选项表

| 选项 | 必填 | 说明 |
|---|---|---|
| `header=<Name: Value>` | 否，可重复 | 追加请求头。重复同名头会按给定顺序发送（如多个 `Accept`）。 |
| `body=<text>` | 否 | 单行内联请求体，按 UTF-8 发送；内容按字面量处理。 |
| `bodyfile=<path>` | 否 | 从文件读取请求体；相对路径按**当前脚本工作目录**解析；UTF-8；去掉 UTF-8 BOM；上限 16 MiB。 |
| `bodyvar=<name>` | 否 | 使用 `_ECHO @name` 捕获的文本作为请求体；不再做二次 `${}` 展开。 |
| `timeout=<seconds>` | 否 | 单次请求总超时（连接 + 等待 + 读取）。非负整数；`0` 表示不限；默认 `30`。 |
| `allow=<codes>` | 否 | 逗号分隔的状态码，或 `all`。默认仅 2xx 算成功。 |
| `follow=<bool>` | 否 | 是否跟随 3xx；`true`（默认）/ `false`。 |

默认请求头（用户未显式提供时）：

- `User-Agent: dbcli`
- `Accept-Encoding: identity`

不默认加 `Content-Type`、`Accept`、`Authorization`。带请求体时是否加 `Content-Type` 由脚本作者决定，避免猜错。

`allow` 的语义（针对**最终**响应状态）：

- 默认：`200..299` 成功；其它状态（含 4xx、5xx）算一次错误。
- `allow=404`：404 也算成功；其它非 2xx 仍算错误。
- `allow=404,409`：多个状态码。
- `allow=all`：任何 HTTP 状态都算成功（传输失败仍然算错误）。
- `allow` 只影响“是否计错误”和 `API.OK`，不影响 `API.STATUS`，也不抑制响应体输出。
- 默认跟随 3xx；`follow=false` 时 3xx 作为最终状态返回，默认算非预期状态，
  可用 `allow=302` 接受，并用 `API.HEADER.Location` 读目标地址。

### 2.4 响应与输出

一次 `_API` 的 stdout 形状：

```text
HTTP 200
{"status":"ok"}
```

- 第一行是状态行，固定格式 `HTTP <code>`，不带 reason phrase、不带耗时、不带响应头。
  这样不会把 `Date`、`Server`、`X-Request-Id` 等易变内容带进 golden 结果。
- 状态行通过 `printStatus` 输出，受 `_SET FEEDBACK` 控制；`_SET FEEDBACK OFF` 后只留响应体。
- 响应体通过 `printData` 逐行输出，受 `_SET TERMOUT` 控制；`_SET TERMOUT OFF` 后既不显示也不进日志/spool。
- 非预期状态用 `countFailure("HTTP 500 (unexpected status).")` 输出：只打印一次、计一次错误、
  触发 `_WHENEVER ERROR`；响应体仍然打印，便于看错误详情。
- 传输失败（DNS、连接拒绝、TLS、超时、读取失败）不打印状态行，打印一条可读错误，
  例如：`API request failed [GET https://host/x]: Connection refused`。
- `HEAD`、`204`、`304` 等没有响应体时只打印状态行。
- 响应体按文本解码，行尾统一规范成 `\n`；末尾的一个换行不会额外多打一个空行。
- 响应体是数据，不做 JSON 美化、不重排、不压缩。

示例（`_SET FEEDBACK OFF` 后做纯响应体比对）：

```sql
_set feedback off
_api GET https://api.example.com/health
```

stdout 只剩：

```text
{"status":"ok"}
```

### 2.5 `API.*` 取值

每次 `_API` **尝试完成**（成功、非预期状态、传输失败）后，替换上一组 `API.*` 运行时变量；
参数解析失败、文件读失败、命令被中止时不替换（见 §4.7）。

| 取值器 | 类型/内容 | 说明 |
|---|---|---|
| `API.STATUS` | 整数文本 | **最终** HTTP 状态码（默认已跟随 3xx）；传输失败为 `0`。 |
| `API.OK` | `true` / `false` | 传输成功且状态在允许集合内。 |
| `API.BODY` | 文本 | 解码后的响应体；行尾统一为 `\n`；可为空串。 |
| `API.ERROR` | 文本 | 传输/读取/超限等请求级错误的说明；成功时**不存在**。 |
| `API.HEADER.<name>` | 文本 | 响应头值；同名多值用 `, ` 连接；不存在时断言取值为 `null`。 |
| `API.HEADER.<CANONICAL>` | 文本 | 响应头的规范名别名：大写、非字母数字换成 `_`，例如 `CONTENT_TYPE`、`SET_COOKIE`。 |
| `API.JSON.<path>` | 标量文本 | JSON 标量叶子；路径用点号，数组下标用 `.0` 或 `[0]`；不存在/`null`/非标量取值为 `null`。 |

补充规则：

- `API.STATUS` / `API.OK` / `API.BODY` / `API.ERROR` 作为 `_ASSERT` / `_IF` 取值器时大小写不敏感。
- `API.HEADER.*` 的头名大小写不敏感（HTTP 头本来就不区分大小写）。
- `API.JSON.*` 的 **JSON 键区分大小写**；数组下标两种写法等价：
  `API.JSON.items[0].id` 与 `API.JSON.items.0.id` 指向同一个值。
- `API.JSON.*` 只暴露**标量叶子**（字符串、数字、布尔）。对象/数组不注册成变量；
  需要对象/数组内容时用 `API.BODY`，或在后续版本再扩展。
- JSON 路径不支持键名里含 `.`、`[`、`]` 的情况；也不支持负下标、通配符、过滤器。
- 响应不是合法 JSON 时，所有 `API.JSON.*` 都取不到（断言为 `null`）。
- JSON 数字用十进制 plain string（不用科学计数法）；布尔是 `true` / `false`；字符串原样。
- 响应头多值（如 `Set-Cookie`）用 `, ` 连接；`Set-Cookie` 的逗号语义特殊，v1 不承诺可还原。
- 没有发过 `_API` 时，`API.*` 全部不存在。
- 默认跟随 3xx 时，`API.STATUS` / `API.BODY` / `API.HEADER.*` 都是**最终响应**的；
  只有 `follow=false` 或 JDK 不跟随的降级跳转才会看到 3xx 与 `Location`。
- `${API.*}` 的变量名沿用全局规则**区分大小写**，建议写大写（`${API.STATUS}`、
  `${API.JSON.access_token}`）；`API.JSON` 的路径部分也必须与 JSON 键大小写一致。
- JSON 键名含 `-` 等 `VariableResolver` 不接受的字符时，只能通过 `_ASSERT` / `_IF`
  取值（如 `API.JSON.x-rate`），不能写进 `${...}`。

在 `_ASSERT` / `_IF` 中的用法：

```sql
_assert {% API.STATUS = 200 %}
_assert {% API.OK = true %}
_assert {% API.JSON.access_token EXISTS %}
_assert {% API.JSON.items[0].id = 42 %}
_assert {% API.HEADER.Content-Type CONTAINS "json" %}

_if {% API.OK = false %}
  _echo t
  API call failed: ${API.ERROR}
  echo off
_endif
```

在变量替换中的用法：

```sql
_set @token ${API.JSON.access_token}
_api GET https://api.example.com/me header='Authorization: Bearer ${token}'
```

**严格命名空间**：`${API.*}` 在 API 命名空间里查不到时**报错**，不会原样保留。
原因：把 `${API.JSON.token}` 原样发给服务端几乎总是脚本 bug；普通变量 `\${name}` 的
“未定义保持原样”语义不适用于 API 命名空间。要输出字面量，写 `\${API.JSON.token}`。

### 2.6 错误、退出码与 `_WHENEVER`

| 场景 | 输出 | 错误计数 | 退出码 |
|---|---|---|---|
| 参数错误（缺方法、未知选项、头格式错等） | 一条可读错误 | +1 | 不变（仍按 README §9.3） |
| 请求体文件读不了 / 超 16 MiB / `bodyvar` 未定义 | 一条可读错误 | +1 | 不变 |
| 传输失败 / 超时 / 重定向循环 | 一条可读错误 | +1 | 不变 |
| 非预期 HTTP 状态 | `HTTP 500 (unexpected status).` + 响应体 | +1 | 不变 |
| 预期状态（含 `allow=`） | `HTTP 200` + 响应体 | 0 | 不变 |
| `_WHENEVER ERROR EXIT n` | 按既有规则立刻退出 | +1 | `n` |
| 作业被 abort 中断请求 | 不再输出结果，按停止流程收尾 | 不计普通错误 | 按作业终态 |

与 dbcli 既有口径完全一致：**脚本内部错误不自动改变进程退出码**；
要让 API 失败成为门禁，写 `_WHENEVER ERROR EXIT 1` 或结尾 `_EXIT 1`。

### 2.7 限制

| 限制 | 值 | 理由 |
|---|---|---|
| 请求体大小 | 16 MiB | 防止误读大文件；API 测试不应上传大文件 |
| 响应体大小 | 16 MiB | 防止内存失控；超出按请求级错误处理 |
| 默认超时 | 30 秒 | CI 不应无限挂起 |
| 连接超时 | 10 秒 | 比总超时更早失败，错误更明确 |
| JSON 叶子变量数 | 100,000 | 防止超大 JSON 展开成变量表；超出部分不注册（不报错） |
| JSON 递归深度 | 64 | 防止极深嵌套导致栈问题；更深部分不注册 |
| 单行长度 | 由终端/脚本限制 | 单行内联体过长时，改用 `_ECHO @name` + `bodyvar=` 或 `bodyfile=` |

### 2.8 示例

#### 2.8.1 基本 GET + 断言

```sql
_api GET https://api.example.com/health
_assert {% API.STATUS = 200 %}
_assert {% API.JSON.status = ok %}

_api GET https://api.example.com/users/1
_assert {% API.JSON.id = 1 %}
_set @name ${API.JSON.name}
_echo t
user=${name}
echo off
```

#### 2.8.2 POST JSON + 取 token + 链式调用

dbcli 脚本**不支持反斜杠续行**，下面这条长命令必须写成一行：

```sql
_api POST https://api.example.com/login header='Content-Type: application/json' body='{"user":"demo","password":"${password}"}'
_assert {% API.STATUS = 200 %}
_set @token ${API.JSON.access_token}

_api GET https://api.example.com/me header='Authorization: Bearer ${token}'
_assert {% API.STATUS = 200 %}
_assert {% API.JSON.user.name = demo %}
```

#### 2.8.3 用 `_ECHO @name` 生成多行 JSON

```sql
_ECHO @login_body
{
  "user": "demo",
  "password": "${password}",
  "note": "contains ; and -- safely"
}
echo off

_api POST https://api.example.com/login header='Content-Type: application/json' bodyvar=login_body
_assert {% API.STATUS = 200 %}
```

`_ECHO @login_body` 只捕获、不打印；块里的 `;`、`--`、引号都不会进入语句切分/注释逻辑。

#### 2.8.4 多行 JSON 用文件

`payload.json`：

```json
{
  "name": "demo",
  "tags": ["a", "b"]
}
```

脚本：

```sql
_api POST https://api.example.com/items header='Content-Type: application/json' bodyfile=payload.json
_assert {% API.STATUS = 201 %}
```

`bodyfile=` 的内容按字面量发送，**不做** `${}` 展开；需要模板化时用内联 `body=` 或
`_ECHO @name` + `bodyvar=`，或先用其它工具生成文件。

#### 2.8.5 预期的错误状态

```sql
_api DELETE https://api.example.com/items/999 allow=404
_assert {% API.STATUS = 404 %}
_assert {% API.JSON.error = not_found %}
```

#### 2.8.6 关闭重定向

默认跟随 3xx；要观察原始 3xx，用 `follow=false`：

```sql
_api GET http://api.example.com/old follow=false allow=301
_assert {% API.STATUS = 301 %}
_set @location ${API.HEADER.LOCATION}
_api GET ${location}
```

#### 2.8.7 只比较响应体

```sql
_set feedback off
_api GET https://api.example.com/version
```

`--ref` 拿到的就是响应体本身（前提是响应体稳定）。

---

## 3. 总体设计

### 3.1 方案选择

#### 方案 A：直接调用 `_HOST curl`

- 优点：零实现。
- 缺点：依赖外部 curl；跨平台引号差异；状态/头/JSON 无法结构化；错误计数不统一。
- 结论：不采用。

#### 方案 B：完整 curl 兼容的选项解析

```text
_api -X POST -H "..." -d "..." --max-time 10 -L -k ...
```

- 优点：熟悉。
- 缺点：dbcli 脚本不是 shell，`-d @file`、`--data-urlencode`、`-F`、`-c/-b` 等大量选项
  与本项目“简化”的目标冲突；短选项组合、引号/转义规则会引入第二套 shell 解析器。
- 结论：不采用。

#### 方案 C：位置参数 + `k=v` 选项（采用）

```text
_api POST https://host/path header='Content-Type: application/json' body='{"a":1}' timeout=10 allow=404
```

- 与 `_JOB CREATE ... k=v`、`_SSH SET ... = ...` 的既有风格一致；
- 解析器小，测试面窄；
- 头部可重复，请求体可来自文件；
- 不引入短选项组合与 shell 转义规则。

#### 方案 D：`_ECHO @name` 捕获 + `bodyvar=`（采用）

```text
_ECHO @login_body
{
  ...
}
echo off

_api POST https://host/path header='Content-Type: application/json' bodyvar=login_body
```

- 优点：复用现有 `_ECHO` 的“数据块 + `echo off`”语义；块内 `;`、`--`、引号不会进入
  语句切分/注释逻辑；`_API` 仍是单行命令，不需要新增多行词法状态。
- 代价：`_ECHO` 增加一个捕获形态（`@name`），`_API` 增加 `bodyvar=` 选项；两者都是小改动。
- 结论：采用。`_API` 自带的 `body="""..."""` 块不做。

### 3.2 架构

```text
ScriptRunner
  ├─ CommandCatalog.find("ECHO") ──► EchoCommand
  │    `_ECHO @name` … echo off ──► context.variables().setVariable(name, block)
  │
  └─ CommandCatalog.find("API") ──► ApiCommand
                                       ├─ ApiRequestParser
                                       │    raw args ──► ApiRequest
                                       │    bodyvar=name ──► 读取已捕获变量
                                       ├─ ScriptContext.apiClient()
                                       │    ├─ followClient（Redirect.NORMAL，默认）
                                       │    └─ noFollowClient（Redirect.NEVER，follow=false）
                                       ├─ ApiResponse
                                       └─ ScriptContext.variables()
                                            └─ setRuntimeVariables(ApiResult.variables())

_ASSERT / _IF ──► AssertEvaluator ──► ResultModel.variables（含 API.*）
```

输出统一走 `ScriptOutput`：

- 状态行 → `printStatus` / `countFailure`；
- 响应体 → `printData`；
- 参数/传输错误 → `call.reportError`。

### 3.3 组件职责

| 组件 | 职责 |
|---|---|
| `ApiCommand` | 叶子 `ScriptCommand`：解析参数、发请求、写输出、刷新 `API.*` 变量 |
| `ApiRequestParser` | 只做文本 → `ApiRequest`：方法、URL、头、体、超时、allow、follow；所有参数错误在这里给可读消息 |
| `ApiRequest` | 不可变请求对象：`method`、`URI`、`headers`、`body`、`timeout`、`allowedStatuses`、`follow` |
| `ApiClient` | `HttpClient` 的懒创建（跟随/不跟随两个实例）、请求执行、取消、响应读取、大小/编码校验、关闭 |
| `ApiResponse` | 状态码、响应头、解码后的文本响应体、请求级错误 |
| `ApiResult` | 把 `ApiResponse` 转成 `API.*` 变量表；JSON 叶子展开在这里 |
| `JsonPaths` | JSON 路径规范化/展开；只暴露标量叶子 |
| `ScriptContext` | 持有本会话的 `ApiClient`，关闭时释放 |
| `EchoCommand` | `_ECHO @name` 捕获块到脚本变量；普通 `_ECHO` 行为不变 |
| `VariableResolver` | 新增“运行时变量”层；替换 `API.*` 时严格报错 |
| `AssertEvaluator` | 新增 `API.*` 取值器；JSON 路径支持 `[n]` 与 `.n` 两种写法 |
| `HelpCatalog` | `_API` 条目与 `_HELP API` 主题；README 生成块 |
| `CommandCatalog` | 登记 `new ApiCommand()` |

### 3.4 一次请求的时序

```text
1. ScriptSession 收到一整行 `_API ...`
2. SqlAnalyzer / SqlIrVisitor 产出 { name: "API", arguments: "<原文>" }
3. ScriptRunner 从 CommandCatalog 找到 ApiCommand
4. ApiCommand 调 ApiRequestParser：
     - 校验方法/URL/选项/体/超时/allow/follow
     - bodyfile 读文件（相对脚本工作目录）
     - bodyvar 读取 `_ECHO @name` 捕获的变量（缺失则报错）
5. ApiCommand 调 context.apiClient().send(request, host)
     - 按 follow 选择 followClient / noFollowClient
     - 组装 HttpRequest，带默认头
     - sendAsync + 轮询等待，响应停止请求则 cancel
     - 读取响应体，校验大小与 Content-Encoding
     - 解码文本
6. ApiResult 生成 API.* 变量：
     - STATUS / OK / BODY / ERROR
     - HEADER.*（原样名 + 规范名）
     - JSON.*（标量叶子）
7. context.variables().setRuntimeVariables(...)
8. 输出：
     - 预期状态 → printStatus("HTTP 200")
     - 非预期状态 → countFailure("HTTP 500 (unexpected status).")
     - 逐行 printData(响应体)
9. 后续 _ASSERT / _IF / ${API.*} 立即可见
```

---

## 4. 详细设计

### 4.1 词法

在 `BaseLexer.g4` 增加：

```antlr
API : '_API' -> pushMode(ApiMode);

mode ApiMode;
API_SPACE      : [ \t]+ -> channel(HIDDEN);
API_EXPRESSION : ~[\r\n]+;
API_CRLF       : CRLF -> popMode;
```

设计说明：

- `_API` 之后整行原样捕获，**不在词法层解析** URL/头/体。这样引号、冒号、斜杠、JSON 大括号、
  `&`、`=`、`;` 都不会干扰 ANTLR 语法。
- `API_EXPRESSION` 包含 `;`，因此行尾 `;` 由 `ApiRequestParser` 在“最后一个字符、且不在引号内”时去掉；
  请求体/URL 需要以 `;` 结尾时用引号、`bodyfile=` 或 `_ECHO @name`。
- `_API` 是单行命令，不新增多行块状态；`StatementBoundary` 现有的
  “单行 `_` 命令 → `Kind.COMMAND`”直接适用。但语句切分仍会看**引号外**的顶层 `;`
  与 `--`：含 `;` / `--` 的 URL/请求体必须加引号或用 `bodyfile=` / `_ECHO @name`（见 §2.2）。
- `_API` 在 `_IF` / `_LOOP` 块内与普通叶子命令一样被捕获/重放，不需要 `ControlFlow` 特殊处理。

### 4.2 语法

在 `BaseParser.g4` 的 `baseCommand` 增加：

```antlr
api : API API_EXPRESSION? API_CRLF?;
```

并把 `api` 加入 `baseCommand` 的备选。

语法层只做两件事：

1. 认得出这是 `_API`；
2. 把后面的整行文本交给 IR。

不把 `header` / `body` / `timeout` 写成 ANTLR 关键字，避免为“值里可能出现同名关键字”写复杂的
词法/优先级规则。

### 4.3 IR 契约

`SqlIrVisitor.visitApi` 产出：

```java
{
  "name": "API",
  "arguments": "<_API 之后的整行原文，去掉首尾空白>"
}
```

约定：

- `arguments` 保留原始大小写与内部空白；行尾 `;` 也保留，由 `ApiRequestParser` 处理。
- `arguments` 为空时 IR 仍然合法，由 `ApiCommand` 报
  `_API requires a method and a URL.`。
- 访问器不因缺参数设置 `errorCode`；HTTP 参数是命令语义，不是 ANTLR 语法错误。
- 这样 `SqlIrVisitor` 不需要知道 HTTP 的任何概念，后续加选项也不用改语法。

#### 4.3.1 `_ECHO @name` 捕获的实现位置

`_ECHO` 的 IR 已经包含：

```java
{
  "name": "ECHO",
  "param": "<_ECHO 后的标题/目标>",
  "block": "<块内正文>"
}
```

`EchoCommand` 按 `param` 分流：

- `param` 不以 `@` 开头：保持现有行为，逐行 `printData(block)`；
- `param` 以 `@` 开头：取 `@` 后的变量名，校验后
  `context.variables().setVariable(name, capturedText)`，**不打印**；
  `block` 为空时也要把变量设为 `""`（不能走现有的“空块直接 return”分支）；
- `capturedText` 就是块正文（`echo off` 分隔符不计入），不额外补行尾，内部换行/空行保留；
- 变量名非法或为空 → `Invalid echo capture name [..].`；
- 变量展开仍由 `ScriptRunner.executeLeaf` 在进入 `EchoCommand` 之前完成
  （与普通 `_ECHO` 完全一致）。

因此 `_ECHO @name` 不需要改词法/语法/`StatementBoundary`，只改 `EchoCommand` 与帮助文档。

### 4.4 参数解析

`ApiRequestParser` 是**唯一**解析 `arguments` 的地方，输入是原始字符串，输出是 `ApiRequest` 或
`ApiParseException`（消息可直接打印）。

#### 4.4.1 分词

按“引号外的空白”切分：

- 单引号 `'...'` / 双引号 `"..."` 内的空白不切；
- 引号可以出现在 token 中间（如 `header='Name: Value'`）；
- 引号内的内容**不做反斜杠转义**；
- 引号不闭合 → `Unclosed quote in _API arguments.`；
- 空 token 不产生。

#### 4.4.2 位置参数

- 第 1 个 token：方法，转大写，必须在允许集合；
- 第 2 个 token：URL，去掉成对的外层引号后必须是绝对 `http(s)` URI；
- URL 带 userinfo（`https://user:pass@host`）直接拒绝，提示改用 `Authorization` 头；
- 第 3 个起：必须是 `name=value`。

#### 4.4.3 选项

- 按**第一个 `=`** 切分选项名和值；值里可以再有 `=`（如 query 字符串、JSON）。
- 值去掉成对的外层引号；不去反斜杠。
- `header=`：
  - 值按第一个 `:` 切分；
  - 名称 strip 后不能为空；
  - 值 strip 后允许为空；
  - 交 `HttpRequest.Builder.header` 前先做可读校验；JDK 保留头（`Host`、`Connection`、
    `Content-Length`、`Expect`、`Upgrade`）由 JDK 拒绝，统一转成
    `Header [X] is managed by the HTTP client.`。
- `body=` / `bodyfile=` / `bodyvar=`：
  - 三者互斥，最多一次；
  - GET/HEAD 上出现 → `_API option [body] cannot be used with GET or HEAD.`；
  - `bodyfile` 用 `CommandCall.resolvePath`，UTF-8 读取，去 BOM，超过 16 MiB → 错误；
  - `bodyvar` 读取 `VariableResolver` 里的脚本变量；变量不存在 →
    `Unknown _API body variable [name].`；变量值按字面量发送，**不再做** `${}` 展开
    （`_ECHO @name` 捕获时已经展开过）。
- `follow=`：
  - 只接受 `true` / `false`（大小写不敏感）；
  - 默认 `true`；`false` 时使用不跟随重定向的客户端。
- `timeout=`：
  - 非负整数秒；非法值给 `_API option [timeout] requires a non-negative integer, got [x].`；
  - `0` 表示不设总超时。
- `allow=`：
  - 逗号分隔；每项是 `all` 或 100..599 的整数；
  - 非法值给可读错误；
  - 一个 `_API` 命令只允许一个 `allow=`；重复写报错，多个状态码写在同一个值里；
  - `all` 不能与具体状态码混写。
- 未知选项：`Unknown _API option [x].`
- 同一个 `body` / `bodyfile` / `bodyvar` / `timeout` / `allow` / `follow`
  出现两次：可读错误。

#### 4.4.4 默认值

| 项 | 默认 |
|---|---|
| `timeout` | 30 秒 |
| `allow` | 2xx |
| `follow` | `true`（跟随 3xx） |
| `User-Agent` | `dbcli` |
| `Accept-Encoding` | `identity` |
| `Content-Type` | 不加 |

### 4.5 HTTP 执行

`ApiClient` 封装 `java.net.http.HttpClient`：

- 每个 `ScriptContext` 一个 `ApiClient`；`ApiClient` 内部按需懒创建两个 `HttpClient`：
  - `followClient`：`followRedirects(NORMAL)`，默认使用；
  - `noFollowClient`：`followRedirects(NEVER)`，`follow=false` 时使用。
- `HttpClient` 配置（两者相同）：
  - `connectTimeout(10s)`；
  - 不用系统代理（自定义 `ProxySelector` 返回空列表），避免环境变量导致行为漂移；
  - 专用 daemon 线程池；关闭时 `shutdownNow`，不留非 daemon 线程；
  - 默认 TLS（`SSLContext.getDefault()`），不做 trust-all；
  - HTTP/2 优先，失败自动回退（JDK 行为，不额外配置）。
- 重定向语义：
  - `follow=true`（默认）使用 JDK `Redirect.NORMAL`：跟随 301/302/303/307/308，
    但**不跟随 HTTPS → HTTP** 的降级；跳转次数上限由 JDK 控制；
  - 301/302/303 的方法/请求体重写规则以 JDK `Redirect.NORMAL` 为准；需要精确控制时
    用 `follow=false` 手工重发；
  - 跳转失败/循环 → `IOException` → `API request failed [...]`；
  - 最终响应状态参与 `allow` 判定；
  - `follow=false` 时 3xx 原样返回，`API.HEADER.Location` 可读；
  - 安全提示：跟随重定向时 JDK 可能把 `Authorization` / `Cookie` 带给新目标；
    不可信重定向请用 `follow=false` 手工处理（见 §4.13）。
- `HttpRequest` 配置：
  - `uri`、`timeout`；
  - `method(method, bodyPublisher)`；GET/HEAD 用 `BodyPublishers.noBody()`；
  - 头按解析顺序添加；默认头仅在用户未提供时添加；
  - 请求体 `BodyPublishers.ofString(body, UTF_8)`。
- 发送用 `sendAsync(..., BodyHandlers.ofInputStream())`：
  - 主线程按 50 ms 轮询 `future.get`；
  - 每轮检查 `host.stopRequested()`；为真时 `future.cancel(true)` 并抛
    `ScriptStoppedException`（由作业停止流程处理）；
  - 整体截止时间同时覆盖“等响应头”和“读响应体”：`ApiClient` 记录开始时间，
    读取循环每轮检查剩余时间，超时就关闭流并按 `ApiTimeoutException` 处理；
  - `HttpRequest.timeout` 负责请求前半段，读取看门狗负责后半段，避免服务端
    发完响应头后无限慢速发送。
- 响应体读取：
  - 先看 `Content-Encoding`；不是 `identity`/空 → 请求级错误
    `Unsupported Content-Encoding [gzip].`；
  - 读取上限 16 MiB；超过 → 请求级错误；
  - 关闭 `InputStream`；
  - `Content-Type` 的 `charset=` 决定解码字符集；没有/非法时 UTF-8；
  - 用 `CodingErrorAction.REPLACE`，不因个别坏字节让整次请求失败；
  - 去 UTF-8 BOM；解码后把 `\r\n` / `\r` 统一成 `\n`，`API.BODY` 与输出共用这份文本。

### 4.6 响应体与状态

`ApiResponse` 字段：

```java
record ApiResponse(
    int status,
    Map<String, List<String>> headers,
    String body,
    String error) {}
```

- `error != null` 表示请求级失败（传输、超时、重定向循环、编码、超限）；此时 `status = 0`、`body = ""`。
- `status` 是**跟随重定向之后**的最终状态码；`follow=false` 时 3xx 原样记录。
- 状态是否成功由 `ApiRequest` 的 `allowedStatuses` 判定：
  - `allowAll` 为真 → 任何 HTTP 状态都成功；
  - 否则 `status / 100 == 2` 或 `allowedStatuses.contains(status)`。
- 非预期状态**不算** `ApiResponse.error`；它是 HTTP 层面的结果，由 `ApiCommand` 计一次失败。
- `ApiCommand` 输出顺序固定：状态行 → 响应体。这样 `_COMPARE` / `--ref` 的结果稳定。

### 4.7 `API.*` 运行时变量

`ScriptContext` 不新增 `ResultModel` 字段，而是复用现有变量模型：

- `VariableResolver` 新增一层 `runtimeVariables`：
  - 优先级：脚本变量 `_SET @x` > 运行时变量 > `--var` > `ARGVn` > 环境变量；
  - `snapshot()` 也包含运行时变量，因此 `VAR.API.STATUS` 也能用；
  - `_API` 每次完成后调用 `setRuntimeVariables(map)` **整体替换**，不会留下上一次的
    `API.JSON.old_token`；
  - 名字以 `API.` 开头、但运行时表里没有的替换目标抛
    `Unknown API value [API.JSON.x].`（严格命名空间）；
  - 其它未定义变量仍按原规则原样保留。
- `ApiResult.variables()` 生成键：
  - `API.STATUS`、`API.OK`、`API.BODY`；
  - `API.ERROR`（仅请求级失败时）；
  - `API.HEADER.<原样名>` 与 `API.HEADER.<规范名>`；
  - `API.JSON.<点路径>`（仅标量叶子）。
- 传输失败也替换变量：
  - `API.STATUS=0`、`API.OK=false`、`API.BODY=""`、`API.ERROR=<消息>`；
  - 这样脚本可以用 `_assert {% API.STATUS = 0 %}` 或 `_if {% API.ERROR EXISTS %}` 判断。
- 参数解析失败 / `bodyfile`、`bodyvar` 读失败 / 作业停止：**不替换**，保留上一次结果。
  理由：请求根本没发出去，不应伪装成一次 HTTP 结果。

### 4.8 JSON 路径与展开

`ApiResult` 在请求完成后解析一次 JSON：

- 用 `fastjson2` 的 `JSON.parse(body)`；
- 解析失败：不注册任何 `API.JSON.*`，不报错（响应体仍然输出）；
- 递归展开：
  - 对象：`parent.key`；
  - 数组：`parent.index`（如 `items.0.id`）；
  - 根数组：`0.id`；
  - 标量：注册 `API.JSON.<path>`；
  - `null`：不注册（断言取值 `null`）；
  - 对象/数组：不注册（避免大对象复制；需要时用 `API.BODY`）；
- 限制：
  - 最多 100,000 个标量叶子；
  - 最大深度 64；
  - 超出部分静默不注册（不改变请求成功/失败）。
- 标量文本：
  - 字符串原样；
  - 整数/小数用 `BigDecimal.toPlainString()`；
  - 布尔 `true` / `false`。
- 路径不支持键名含 `.`、`[`、`]`；不支持通配符/过滤器/负下标。

`AssertEvaluator` 的 `API.JSON.*` 查找：

1. 先按原文精确查（大小写敏感）；
2. 再把 `[n]` 规范成 `.n` 后查；
3. 都没有 → `null`（不是语法错误），所以 `EXISTS` 为 false，`= NULL` 为 true。

### 4.9 `_ASSERT` / `_IF` 集成

`AssertEvaluator.resolveSelector` 增加：

```java
if (upper.startsWith("API.")) {
    return lookupApiValue(selector, result.variables());
}
```

规则：

- `API.STATUS` / `API.OK` / `API.BODY` / `API.ERROR`：大小写不敏感；
- `API.HEADER.*`：头名大小写不敏感；
- `API.JSON.*`：前缀大小写不敏感，路径部分大小写敏感；
- 缺失值返回 `null`，不抛“未知取值器”；
- 其它未知 `API.*`（如 `API.FOO`）也返回 `null`：API 命名空间是动态的，
  断言会失败 / `EXISTS` 为 false，但 `${API.FOO}` 仍然严格报错。

`_IF` / `_LOOP WHILE|UNTIL` 复用同一个求值器，因此不需要额外改动。

### 4.10 输出格式

- 状态行：`HTTP <code>`；非预期时 `HTTP <code> (unexpected status).`
- 响应体：按 `\R` 切行，去掉末尾一个空行，逐行 `printData`。
- 空响应体：不打印数据行。
- 响应体里的 `\r\n` / `\r` 统一成 `\n`。
- 不打印响应头；需要时用 `API.HEADER.*` 断言。
- 不打印耗时、字节数、请求 ID 等易变信息。
- `API.BODY` 保留原始文本（仅解码与行尾规范化），不做 trim。

### 4.11 生命周期与中断

- `ScriptContext` 新增：

```java
private ApiClient apiClient;
public ApiClient apiClient() { ... }  // 懒创建
public void close() {
    if (apiClient != null) apiClient.close();
    output.close();
    dispatcher.close();
    sqlExecutor.close();
}
```

- 每个作业线程有独立 `ScriptContext`，因此有独立 `ApiClient`，不会共享连接或 `API.*` 变量。
- `_START` 子脚本共享父会话的 `ApiClient` 与 `API.*` 变量（与连接、变量语义一致）。
- 作业 abort 时，`ApiClient` 的请求轮询看到 `stopRequested()` 后取消 future；
  `ApiClient.close()` 再兜底关闭客户端与线程池。
- 控制台 Ctrl-C 与 `_HOST` 采用同一策略：执行线程被中断（`InterruptedException`）时，
  `ApiClient` 取消 future 并让异常按既有中断路径收尾；不额外引入控制台专用取消协议。
  即使中断没有及时到达，`timeout` 仍会兜底。

### 4.12 错误文案（建议冻结）

| 场景 | 文案 |
|---|---|
| 缺方法/URL | `_API requires a method and a URL.` |
| 方法不支持 | `Unsupported _API method [FOO].` |
| URL 非法 | `_API URL must be an absolute http:// or https:// URL: [x].` |
| URL 带用户信息 | `_API URL must not contain user info; use an Authorization header.` |
| 选项未知 | `Unknown _API option [x].` |
| 选项重复 | `_API option [body] was given twice.` |
| 头格式错 | `_API option [header] requires '<Name>: <Value>'.` |
| 头名非法 | `_API option [header] has an invalid name: [x].` |
| 保留头 | `Header [Content-Length] is managed by the HTTP client.` |
| GET/HEAD 带体 | `_API option [body] cannot be used with GET or HEAD.` |
| 超时非法 | `_API option [timeout] requires a non-negative integer, got [x].` |
| allow 非法 | `_API option [allow] requires HTTP status codes or 'all', got [x].` |
| follow 非法 | `_API option [follow] requires true or false, got [x].` |
| 体文件读失败 | `Cannot read _API body file [path]: <原因>` |
| 体文件超限 | `_API request body exceeds 16777216 bytes.` |
| bodyvar 未定义 | `Unknown _API body variable [name].` |
| 响应超限 | `_API response body exceeds 16777216 bytes.` |
| 编码不支持 | `Unsupported Content-Encoding [gzip].` |
| 请求失败 | `API request failed [GET https://host/x]: <原因>` |
| 非预期状态 | `HTTP 500 (unexpected status).` |
| 未闭合引号 | `Unclosed quote in _API arguments.` |
| API 变量未命中 | `Unknown API value [API.JSON.token].` |
| ECHO 捕获名非法 | `Invalid echo capture name [x].` |

说明：错误文案里的 URL/方法使用**用户输入的大小写**；状态码固定 3 位数字。

### 4.13 安全设计

- **TLS**：始终校验证书与主机名；私有 CA 请通过 JVM `trustStore` 配置，不提供跳过开关。
- **重定向**：默认跟随 3xx。JDK 跟随重定向时可能把 `Authorization` / `Cookie` 带给新目标；
  对不可信重定向用 `follow=false`，手工读 `Location` 并只向信任的主机发送敏感头。
- **Cookie**：不自动保存/发送；避免跨脚本串会话。
- **代理**：不使用系统代理，避免环境变量改变请求路径。
- **日志**：`_SET ECHO` 记录的是**展开前**的命令行原文，所以
  `header='Authorization: Bearer ${token}'` 落盘的是 `${token}` 而不是真实 token。
  如果脚本里直接写明文密钥，它会被记录；这是脚本作者的责任。
- **响应体**：默认打印；敏感响应可用 `_SET TERMOUT OFF` 或 `--silent` 控制终端输出，
  文件产物仍按 dbcli 既有规则处理。
- **大小/超时**：防止一个坏端点拖垮 CI。
- **URL 用户信息**：不支持 `https://user:pass@host`；请用 `Authorization` 头。

---

## 5. 兼容性

- `_API` 是新增命令；此前以 `_API` 开头的输入会被报 `Unknown command [_API]`，不存在兼容包袱。
- 新增 `API` 词法 token 不会与 SQL 冲突：SQL 语句不会以 `_API` 开头。
- `VariableResolver` 新增运行时层，只影响 `API.*` 名字；既有变量优先级、未定义保留、转义规则不变。
- `EchoCommand` 增加 `_ECHO @name` 捕获形态；普通 `_ECHO <标题>` 的打印行为不变。
  注意：标题以 `@` 开头的既有脚本会进入捕获模式（不再打印块体），这是有意的保留前缀；
  这类脚本请把标题改成不以 `@` 开头。
- `AssertEvaluator` 只新增 `API.*` 分支，既有取值器/比较符/状态文案不变。
- `SetOption` 不变；`_SET` 的帮助表不变。
- `ScriptContext.close()` 新增关闭 `ApiClient`，不影响未使用 `_API` 的运行（懒创建，不创建就不关闭）。
- 不新增依赖：`java.net.http` 来自 JDK 21；`fastjson2` 已在父 POM 的公共依赖里。
- 既有 `--silent` / `--logfile` / `--ref` / `_SPOOL` / `_WHENEVER` 行为不变；`_API` 只是新增输出源。

---

## 6. 测试设计

### 6.1 分层

| 层 | 内容 |
|---|---|
| 词法/语法 | `_API` 行被识别；缺参数/引号/分号不导致 ANTLR 文案泄漏 |
| IR | `name=API`、`arguments` 原文（含空格、引号、`;`） |
| 参数解析 | 方法、URL、头、体、bodyfile、bodyvar、timeout、allow、follow、重复/未知选项、错误文案 |
| HTTP 客户端 | GET/POST/HEAD、状态、头、体、字符集、超时、默认跟随 3xx、`follow=false`、404、gzip、大小上限 |
| JSON | 嵌套对象/数组、点/中括号路径、数字格式、null、非法 JSON、深度/数量限制 |
| 引擎集成 | `_ASSERT`、`_IF`、`_SET @x ${API...}`、`_WHENEVER`、`_SPOOL`、`--ref` |
| CLI E2E | 脚本文件 + 本地 HTTP 服务；stdout、退出码、日志 |
| 并发/中断 | `_JOB` 中并发请求；abort 取消；线程不泄漏 |

### 6.2 用例矩阵

| 用例 | 预期 |
|---|---|
| `_api GET http://127.0.0.1:PORT/ok` | `HTTP 200` + body |
| `_api GET .../echo?x=1` | 服务端收到 query |
| `_api POST ... header='Content-Type: application/json' body='{"a":1}'` | 服务端收到头与精确 body |
| 请求体含 `;` / `--`（加引号） | 不被语句切分/注释截断 |
| `bodyfile=payload.json` | 文件内容原样发送 |
| `_ECHO @body … echo off` + `bodyvar=body` | 多行 JSON 原样发送；不打印捕获内容 |
| `_ECHO @body` 含 `${x}` | 捕获时展开；`bodyvar` 不再二次展开 |
| `bodyvar=missing` | 可读错误，不发送请求 |
| `timeout=1` + 慢端点 | 可读超时错误，计 1 次错误 |
| `_api GET .../missing` | `HTTP 404 (unexpected status).`，计 1 次错误 |
| `_api GET .../missing allow=404` | `HTTP 404`，不计错误；`API.OK=true` |
| `allow=all` + 500 | 不计错误 |
| 302 → `/ok`（默认） | 跟随；`API.STATUS=200`；body 是最终响应 |
| 302 + `follow=false` | 不跟随；`API.STATUS=302`；`Location` 可取 |
| 重定向循环 | 请求级错误，计 1 次错误 |
| `_assert API.STATUS = 200` | `Assert successful.` |
| `_assert API.JSON.items[0].id = 1` | 通过 |
| `_assert API.JSON.items.0.id = 1` | 同上 |
| `_set @t ${API.JSON.token}` | 变量被替换；后续头里可见 |
| 非法 JSON + `_assert API.JSON.x EXISTS` | 断言失败（不是进程崩溃） |
| `${API.JSON.missing}` | 可读错误，不把字面量发出去 |
| `_set feedback off` | 只输出 body |
| `_set termout off` | 不输出 body，日志也不含 body |
| `--ref` | 状态行 + body 参与逐行比对 |
| `_if {% API.OK = false %}` | 分支正确 |
| `_whenever error exit 1` + 404 | 退出码 1 |
| `_job` 中并发两个请求 | 各自独立 `API.*`，互不覆盖 |
| aborted job 的慢请求 | future 取消，作业按停止流程结束 |

### 6.3 夹具

- 用 JDK 自带 `com.sun.net.httpserver.HttpServer`，绑定 `127.0.0.1:0`（临时端口），
  测试里把端口注入脚本文本。
- 端点：
  - `/ok`：200 JSON；
  - `/echo`：回显 method/headers/body；
  - `/status/<code>`：指定状态；
  - `/slow?ms=...`：延迟；
  - `/redirect`：302 + `Location: /ok`（默认跟随）；
  - `/redirect-loop`：302 指回自己（循环错误）；
  - `/charset`：`text/plain; charset=GBK`；
  - `/bad-json`：200 + 非 JSON；
  - `/gzip`：`Content-Encoding: gzip`（用于错误用例）。
- 测试结束后 `server.stop(0)`，避免线程泄漏。
- 不访问公网；CI 可离线运行。

### 6.4 黄金文件

- 状态行固定 `HTTP <code>`，不含 reason phrase，保证跨 JDK/平台稳定。
- 不打印响应头，避免 `Date`/`Server` 漂移。
- 响应体按原样输出；动态字段用 `_compare MASKLINE` 或 `--ref` 的规则处理。
- 测试覆盖 `--ref` 一致/不一致两种情况。

---

## 7. 代码改动清单

### 7.1 主代码

| 文件 | 改动 |
|---|---|
| `dbcli/src/main/antlr4/.../BaseLexer.g4` | 新增 `API` token 与 `ApiMode` |
| `dbcli/src/main/antlr4/.../BaseParser.g4` | 新增 `api` 规则并加入 `baseCommand` |
| `script/SqlIrVisitor.java` | 新增 `visitApi`，产出 `{name: API, arguments}` |
| `engine/command/CommandCatalog.java` | 登记 `new ApiCommand()` |
| `engine/command/ApiCommand.java` | 新增；命令执行与输出 |
| `engine/command/EchoCommand.java` | 新增 `_ECHO @name` 捕获形态；普通 `_ECHO` 不变 |
| `api/ApiRequest.java` | 新增；不可变请求对象（含 `bodyvar` 解析后的 body、`follow`） |
| `api/ApiRequestParser.java` | 新增；选项解析/错误文案（bodyfile/bodyvar/follow） |
| `api/ApiClient.java` | 新增；`HttpClient` 封装（跟随/不跟随两个实例）、取消、关闭 |
| `api/ApiResponse.java` | 新增；状态/头/体/错误 |
| `api/ApiResult.java` | 新增；`API.*` 变量表 |
| `api/JsonPaths.java` | 新增；JSON 路径展开 |
| `api/package-info.java` | 新增；包职责说明 |
| `engine/ScriptContext.java` | 懒创建/关闭 `ApiClient` |
| `engine/VariableResolver.java` | 运行时变量层 + `API.*` 严格命名空间 |
| `assertion/AssertEvaluator.java` | `API.*` 取值器、JSON 路径规范化 |
| `help/HelpCatalog.java` | `_API` 条目与 `_HELP API` 主题；`_ECHO @name` 说明 |

### 7.2 测试

| 文件 | 内容 |
|---|---|
| `api/ApiRequestParserTest.java` | 解析与错误文案（body/bodyfile/bodyvar、follow、allow） |
| `api/JsonPathsTest.java` | JSON 路径、数字、null、限制 |
| `api/ApiClientTest.java` | 本地 HttpServer 的请求/响应/超时/编码/HEAD/跟随重定向 |
| `engine/ApiScriptIntegrationTest.java` | `_ASSERT` / `_IF` / `_SET` / `API.JSON` / `_ECHO @name` / `allow` / `follow=false` |
| `CliApiTest.java` | CLI `--execute` 端到端：状态/JSON/echo 捕获请求体 |
| `engine/command/CommandCatalog*Test.java` | 命令登记与 `_HELP` 同源 |
| `help/HelpCatalogReadmeTest.java` | `_HELP` / README 生成块同步 |

> CLI 入口同时由现有 `CliSmokeTest` / `CliBatchModeTest` 覆盖；`_API` 的行为在
> `CliApiTest` 与 `ScriptRunner` 集成测试里验证。作业并发隔离/中止沿用
> `_JOB` 的既有测试，`_API` 只是共享 `ScriptContext` 的 `ApiClient`。


### 7.3 文档

- `dbcli/README-CN.adoc`：功能列表、命令速查（生成块）、`7.x _API` 小节、`API.*` 取值表、
  `_ECHO @name` 捕获说明。
- `dbcli/README.adoc`：英文对应内容。
- `dbcli/dbcli-devguide.md`：包表增加 `api`；扩展指引无需改（`_API` 就是普通叶子命令）。
- 根 `README-CN.adoc` / `README.adoc`：dbcli 功能概括里加一句 HTTP API 调用。
- 本设计文档归档到 `dbcli/docs/api-design.md`。

---

## 8. 工作计划

### 8.1 任务分解

| 任务 | 产出 | 估算 |
|---|---|---:|
| M1 语法/IR | Lexer/Parser/Visitor/IR 测试 | 0.75 人日 |
| M2 参数解析 | `ApiRequestParser` + 单测（含 bodyvar/follow） | 0.75 人日 |
| M3 HTTP 核心 | `ApiClient`/`ApiResponse` + 本地 HttpServer 测试（含重定向） | 1.75 人日 |
| M4 ECHO 捕获 | `EchoCommand` + `_ECHO @name` 测试 | 0.5 人日 |
| M5 结果/变量/断言 | `ApiResult`/`JsonPaths`/`VariableResolver`/`AssertEvaluator` | 1.0 人日 |
| M6 命令与输出 | `ApiCommand`/`CommandCatalog`/`ScriptContext` | 0.5 人日 |
| M7 帮助/文档 | `HelpCatalog` + 中英 README + devguide | 0.5 人日 |
| M8 E2E/回归 | CLI 测试、`--ref`、作业中断、全量构建 | 0.75 人日 |
| **合计** | | **6.5 人日** |
| 缓冲 20% | | **约 7.8 人日** |

### 8.2 里程碑

- **M1（可评审语法）**：`_API` 能被解析成 IR，`_HELP API` 有草稿。
- **M2（可发请求）**：本地 HttpServer 上 GET/POST 成功，状态/体输出正确。
- **M3（可写测试）**：`_ASSERT` / `_IF` / `${API.*}` 可用，`_ECHO @name` + `bodyvar=` 可用，
  404/超时/`allow`/默认跟随 3xx/`follow=false` 正确。
- **M4（可合并）**：文档、帮助、E2E、全量测试通过。

### 8.3 提交序列（建议）

1. `feat(dbcli): add _API grammar and IR`
2. `feat(dbcli): add HTTP request parser and client`
3. `feat(dbcli): add _ECHO capture for request bodies`
4. `feat(dbcli): expose API.* runtime values`
5. `feat(dbcli): print API responses and count status failures`
6. `docs(dbcli): document _API and _ECHO capture`
7. `test(dbcli): cover API command end to end`

### 8.4 验收标准

- [ ] `_API` 在控制台、`--execute`、`_START`、`_JOB` 中行为一致。
- [ ] GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS 可用；GET/HEAD 拒绝请求体。
- [ ] `header=` 可重复；请求体内联/文件/`_ECHO @name` + `bodyvar=` 都可发送。
- [ ] 默认 2xx 成功；404 默认计错；`allow=404` / `allow=all` 正确。
- [ ] 默认跟随 3xx；`follow=false` 保留原始 3xx 与 `Location`；重定向循环报错。
- [ ] `API.STATUS`/`API.OK`/`API.BODY`/`API.ERROR`/`API.HEADER.*`/`API.JSON.*` 可用。
- [ ] `${API.JSON.token}` 可用于后续请求头。
- [ ] 非预期状态、传输失败、超时、响应超限都只打印一行可读错误并计一次错误。
- [ ] `_WHENEVER ERROR EXIT n` 对 API 错误生效。
- [ ] `_SET FEEDBACK OFF` 只留响应体；`_SET TERMOUT OFF` 不输出体。
- [ ] `--ref`/`--logfile`/`_SPOOL`/`--silent` 行为符合既有约定。
- [ ] 作业 abort 能取消在途请求；无线程泄漏。
- [ ] `_HELP`、`_ECHO @name` 与中英 README 同步（`HelpCatalogReadmeTest` 通过）。
- [ ] `mvn -o -B -pl dbcli -am test` 通过；无新增依赖。

### 8.5 风险与缓解

| 风险 | 缓解 |
|---|---|
| 引号/转义解析边界多 | 只支持“外层引号 + 字面内容”，不模拟 shell；复杂体用 `_ECHO @name` / `bodyfile=`；单测覆盖 |
| 长单行命令可读性差 | `_ECHO @name` + `bodyvar=` / `bodyfile=`；文档示例 |
| 响应体过大/无限流 | 16 MiB 上限 + 默认 30s 超时 |
| 作业停止时请求不取消 | `sendAsync` + 50ms 轮询 + `future.cancel` + `close` 兜底 |
| `API.*` 变量与用户变量冲突 | 脚本变量优先；`API.` 前缀保留给运行时；文档说明 |
| JSON 键名含点/括号 | v1 明确不支持；用 `API.BODY` 或后续扩展 |
| 默认跟随重定向把敏感头带到新主机 | 文档安全提示；不可信跳转用 `follow=false` 手工处理 |
| 私网自签证书不可用 | 文档说明用 JVM trustStore；不提供 `insecure` |
| 日志泄露密钥 | 回显展开前原文；推荐 `${token}`；文档安全提示 |

---

## 9. 决策记录与开放问题

### 9.1 已定决策

| 编号 | 决策 | 理由 |
|---|---|---|
| D1 | 命令名用 `_API` | 用户指定；比 `_HTTP` 短，和 `_ASSERT` 等 `_` 命令一致 |
| D2 | 用 `k=v` 选项，不做 curl 短选项 | 解析器小；与 `_JOB`/`_SSH` 风格一致；避免第二套 shell 转义 |
| D3 | 多行请求体用 `_ECHO @name` + `bodyvar=` | 复用现有 echo 块；不增加 `_API` 多行块/边界状态 |
| D4 | 默认跟随 3xx，`follow=false` 可关闭 | 用户要求默认跟随；保留安全退出通道 |
| D5 | 默认 2xx 成功，非 2xx 计错 | CI 中静默放过 500 更危险；负向用例用 `allow=` |
| D6 | 状态行固定 `HTTP <code>`，不打印响应头 | stdout 逐字节稳定；需要头用 `API.HEADER.*` |
| D7 | 响应体默认打印 | 类似 curl；`_SET TERMOUT/FEEDBACK` 已有开关 |
| D8 | `API.*` 放入运行时变量层 | 复用 `${}` 与 `_ASSERT`，不新增表达式引擎 |
| D9 | `API.JSON.*` 只暴露标量叶子 | 避免大对象复制；令牌/ID 场景足够 |
| D10 | 不新增 `_SET` 选项 | 超时/allow/follow 是单次请求属性；减少全局状态 |
| D11 | `java.net.http` + `fastjson2` | JDK 21 自带 + 仓库已有；零新增依赖 |
| D12 | 每个 `ScriptContext` 一个 `ApiClient` | 作业隔离；`_START` 共享父会话；关闭可控 |
| D13 | `_ECHO @name` 只捕获、不打印 | 避免请求体在 stdout 出现两次；需要查看时用普通 `_ECHO` |

### 9.2 开放问题（后续版本）

1. **自定义重定向策略**：是否需要限制跳转次数、跨主机时剥离
   `Authorization`/`Cookie`？本版用 JDK `Redirect.NORMAL` + `follow=false`。
2. **`save=<file>`**：二进制/大响应落盘；与“只做文本 API 测试”的定位冲突，暂缓。
3. **对象/数组取值**：`API.JSON.data` 返回 compact JSON 还是 JSONPath 子串？
   本版只做标量。
4. **`bodyfile` 模板展开**：是否对文件内容也做 `${}` 替换？本版明确不展开，
   避免“文件是数据还是模板”含糊。
5. **响应头打印开关**：调试时有用，但会破坏 golden 稳定性；暂不提供。
6. **`_API` 自己的 `expect=`/`assert=`**：本版统一用 `_ASSERT`，不复制断言语法。

---

## 10. 附录

### 10.1 与 curl 的功能对照

| curl | `_API` | 说明 |
|---|---|---|
| `curl URL` | `_API GET URL` | 方法必须显式写 |
| `-X POST` | `_API POST` | 位置参数 |
| `-H 'Name: Value'` | `header='Name: Value'` | 可重复 |
| `-d '...'` | `body='...'` | 单行内联 |
| `--data-binary @file` | `bodyfile=<file>` | UTF-8 文本 |
| heredoc / 多行体 | `_ECHO @name` + `bodyvar=name` | 复用 echo 数据块 |
| `--max-time 10` | `timeout=10` | 秒；0 不限 |
| `-f`（HTTP 错误即失败） | 默认行为 | 非 2xx 计错；`allow=` 放宽 |
| `-L` | 默认行为 | 默认跟随 3xx；`follow=false` 关闭 |
| `-k` | 不支持 | 始终校验 TLS |
| `-c/-b` | 不支持 | 手工 `Set-Cookie` / `Cookie` |
| `-F` | 不支持 | 不做 multipart |
| `-o file` | 不支持 | 响应体走 stdout / `--logfile` |
| `-u user:pass` | `header='Authorization: Basic ...'` | 不内置 base64 |
| `--proxy` | 不支持 | 不使用代理 |
| `-s` | `_SET FEEDBACK OFF` | 不是完全静默；完全静默用 `--silent` |
| `-i` | 不支持 | 用 `API.HEADER.*` 断言 |

### 10.2 `_HELP API` 文案草稿（英文，最终以 `HelpCatalog` 为准）

```text
_API <METHOD> <URL> [header='<Name>: <Value>']...
     [body='<text>' | bodyfile=<file> | bodyvar=<name>]
     [timeout=<seconds>] [allow=<codes>|all] [follow=true|false]
  Sends one HTTP request and prints 'HTTP <status>' plus the response body.
  Methods: GET HEAD POST PUT PATCH DELETE OPTIONS. GET/HEAD take no body.
  header= may repeat. bodyfile is UTF-8 and is not template-expanded.
  bodyvar names a variable captured by '_ECHO @name ... echo off'.
  timeout defaults to 30 seconds; 0 means no limit.
  A status outside 2xx counts as an error unless allow= lists it (or allow=all).
  Redirects are followed by default; follow=false returns the original 3xx.
  After the call, API.STATUS / API.OK / API.BODY / API.ERROR /
  API.HEADER.<name> / API.JSON.<path> are available to _ASSERT, _IF and ${...}.
  Cookies are not persisted.
```

### 10.3 完整示例脚本

```sql
-- 任何 API/SQL 错误都让本次运行失败
_whenever error exit 1

-- 登录，取 token（请求体用 echo 块生成）
_ECHO @login_body
{
  "user": "demo",
  "password": "${password}"
}
echo off

_api POST https://api.example.com/login header='Content-Type: application/json' bodyvar=login_body
_assert {% API.STATUS = 200 %}
_assert {% API.JSON.access_token EXISTS %}
_set @token ${API.JSON.access_token}

-- 用 token 查当前用户
_api GET https://api.example.com/me header='Authorization: Bearer ${token}'
_assert {% API.OK = true %}
_assert {% API.JSON.user.name = demo %}
_set @userId ${API.JSON.user.id}

-- 预期 404
_api GET https://api.example.com/users/999999 allow=404
_assert {% API.STATUS = 404 %}
```

### 10.4 实现检查清单

- [ ] Lexer/Parser 编译通过，`_API` 不被当成未知命令。
- [ ] `arguments` 原文完整（空格、引号、`;`、`=`、`&`）。
- [ ] `_ECHO @name` 捕获正确，且普通 `_ECHO` 不回归。
- [ ] 方法/URL/头/体/超时/allow/follow 的合法与非法输入都有单测。
- [ ] `bodyfile` 相对路径按脚本工作目录；`bodyvar` 缺失报错。
- [ ] 默认跟随 3xx；`follow=false` 保留 3xx；重定向循环报错。
- [ ] 默认头只加一次；用户覆盖时不重复。
- [ ] 状态行/响应体走正确输出通道（feedback/termout）。
- [ ] 非预期状态只计一次错误，响应体仍输出。
- [ ] 传输失败不留下旧 `API.*`。
- [ ] `API.ERROR` 只在请求级失败时存在。
- [ ] JSON 路径点/中括号两种写法一致。
- [ ] `${API.JSON.x}` 未命中严格报错；`_ASSERT` 未命中为 null。
- [ ] `ScriptContext.close()` 关闭客户端，无线程泄漏。
- [ ] 作业 abort 取消在途请求。
- [ ] `_HELP`、中英 README、devguide 同步。
- [ ] `mvn -o -B -pl dbcli -am test` 通过。

---

## 11. 参考

- `dbcli/docs/control-flow-design.md`：本文档的结构与“设计原则/测试/工作计划”写法参考。
- `dbcli/dbcli-devguide.md`：模块划分、输出纪律、扩展指引。
- `dbcli/README-CN.adoc` / `dbcli/README.adoc`：命令手册与 `_ASSERT` 取值器。
- JDK 21 `java.net.http.HttpClient` / `HttpRequest` / `HttpResponse`。
- `fastjson2`（仓库父 POM 已有依赖）。
