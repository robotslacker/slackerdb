# COPY BINARY 入库性能：基线与优化后对比

本文记录 `COPY ... FROM STDIN (FORMAT BINARY)` 服务端入库路径的性能基准、优化前后的实测对比，
以及优化过程中确认的边界语义与残余风险。

- **被测代码**：`dbserver/src/main/java/org/slackerdb/dbserver/message/request/CopyDoneRequest.java`
  的 BINARY 分支（原实现先整段解析成 `List<Object[]>` 再逐格派发；现改为单遍流式解码）。
- **基准入口**：`dbserver/src/test/java/org/slackerdb/dbserver/test/CopyBinaryPerf.java`（带 `main`，不属于 `mvn test`）。
- **环境**：JDK 17.0.15，8 vCPU，`duckdb_jdbc` 1.5.6.0，客户端为真实 pgjdbc 42.7.2 的 `CopyManager`。

## 怎么跑

```bash
JAVA_HOME=<jdk17> mvn -o -q -pl dbserver test-compile
JAVA_HOME=<jdk17> mvn -o -pl dbserver exec:java \
    -Dexec.classpathScope=test \
    -Dexec.mainClass=org.slackerdb.dbserver.test.CopyBinaryPerf \
    -DcopyPerf.rows=1000000 -DcopyPerf.rounds=3
```

可用参数：`-DcopyPerf.rows`（每场景行数）、`-DcopyPerf.rounds`（每场景轮数）、`-DcopyPerf.warmup`、
`-DcopyPerf.outDir`（报告目录，默认 `dbserver/target/copy-binary-perf`）。

结果同时打印到控制台并写入报告目录的 `latest.txt`。

**必须用 JDK 17**：JDK 21 的 `DriverManager` 会对每个已注册驱动调用 `connect()`，本项目自带的
`org.slackerdb.jdbc.Driver` 会抢先抛 `Unable to parse URL`。基准内部已显式注销该驱动、只走真 pgjdbc，
但仍建议与 CI 保持同一 JDK。

## 负载场景

| 场景 | 形态 | 载荷（100 万行） |
|---|---|---|
| `mixed-8col` | 8 列混合：INTEGER / BIGINT / VARCHAR / DOUBLE / BOOLEAN / TIMESTAMP / NUMERIC(18,4) / REAL | 84.1 MB |
| `numeric-3col` | 3 列定长数值：BIGINT / BIGINT / DOUBLE | 36.2 MB |
| `edges-null` | 5 列边界：NULL、空串、多字节 UTF-8、200 字符长串、时间戳 0 值 | 81.2 MB |

载荷按 **PG 线格式**自行编码（基本类型大端、TIMESTAMP 为自 2000-01-01 的微秒、NUMERIC 为 base-10000
数字组）。基准**刻意不复用** `PostgresSQLUtil.convertPGRowToByte` —— 后者是测试用编码器，其时间戳与
数值符号的编码约定与 PG 标准不一致（详见下文"残余风险"）。

每个场景先做一次自检扫描，确认载荷结构合规、且写入结果与首轮完全一致后才计分。

## 实测结果

每格为一次独立进程运行的中位数；共 3 次独立运行。

| 场景 | 基线（原实现）中位数 | 优化后中位数 | 提升 |
|---|---|---|---|
| `mixed-8col` | 2380 / 2209 / 2123 ms → **2209 ms** | 1939 / 1657 / 1638 ms → **1657 ms** | **1.33×** |
| `numeric-3col` | 515 / 738 / 600 ms → **600 ms** | 517 / 529 / 427 ms → **517 ms** | **1.16×** |
| `edges-null` | 1133 / 1179 / 1445 ms → **1179 ms** | 1052 / 1043 / 1055 ms → **1052 ms** | **1.12×** |

吞吐（优化后，取代表性一次运行的 `best`）：

| 场景 | rows/s | MB/s |
|---|---|---|
| `mixed-8col` | ~610,000 | ~51 |
| `numeric-3col` | ~2,340,000 | ~85 |
| `edges-null` | ~959,000 | ~78 |

`mixed-8col` 收益最大：它每行有 8 个单元格、含 VARCHAR 与 NUMERIC 两个变长列，
正是"逐格分配 + 逐格字符串派发"开销最重的形态。`edges-null` 收益最小（NULL 分支在两条路径上都不做解码）。

## 优化做了什么

1. **单遍流式解码**（`BinaryRowSink`）。删除中间 `List<Object[]>`：原实现
   `PostgresSQLUtil.convertPGByteToRow` 为**每个单元格**分配一个 `byte[]` 并装进 `Object[]`、
   再逐行 `ArrayList.add`；现在直接持有载荷 `byte[]`，边解析边 `append`，不再为单元格分配对象。
2. **列类型派发移出热循环**。原来每个单元格都要 `columnTypeCode(类型名)` 做一串字符串比较、
   外加 `List.get(i)` 装箱；现在建流时按列算一次 `kind`（编译期常量，JIT 可折叠为直接分派）。
3. **双位置分离**。明确区分"该表列在 COPY 数据流里的字段下标（`copyPos`）"与"在目标表里的物理下标"。
   原实现靠 `row[nPos]` 隐含完成这件事；重构时必须显式保留，否则
   `COPY t(CNT, ID, ...)` 这类重排列清单会把数据写错列（回归测试 `testBinaryCopy6` 抓到了这一点）。
4. **TIMESTAMP 走 `appendEpochMicros`**。原来每格构造
   `Instant.ofEpochMilli(...)` + `ZoneId.of("UTC")`（每次查表）+ `LocalDateTime`；
   现在直接用原生 epoch 微秒写入。实测读回精确等于 `TIMESTAMP '2020-01-05 23:50:50'`。
5. **列数校验前移到建流阶段**（`validateBinaryColumnCounts`）。这是**正确性**要求而非性能项：
   原实现"先整段解析再校验列数"，报错时 Appender 还没有写过任何一行；改成边解析边写后，
   报错那一刻 Appender 里已留半行，随后统一的 `flush()` 会抛出第二个错误
   （`all columns must be appended to before calling 'endRow'`）把真正的"列数不符"顶掉。
   现在只在建流前做一次只读扫描，超限数据在 Appender 打开之前就被拒绝，与改造前数据影响完全一致。

## 已确认的 Appender 边界语义

这些都用 `duckdb_jdbc` 1.5.6.0 实测确认，优化后的快路径依赖它们：

| 行为 | 实测结论 |
|---|---|
| `appendEpochMicros(long)` | 语义为"自 1970-01-01 的微秒"，读回精确 |
| `append((String) null)` | 写为 NULL |
| `append("")` | 写为**空字符串**而非 NULL（PG CSV 的空字段=NULL 语义仍必须由解析器判定，不能在写入层偷懒） |
| `appendDefault()` | 可空列无 DEFAULT → 写 NULL；**NOT NULL 且无 DEFAULT → 报 `NOT NULL constraint failed`**（在 flush 时暴露） |
| `append(byte[])` | BLOB 专用，**无带长度参数的重载**，故 VARCHAR 无法零拷贝切片 |
| `append(int[])` / `append(int[], boolean[])` | **不是批量标量写入**：数组被当作 LIST/ARRAY 列的一行值，对 INTEGER 列报 `invalid column type` |
| `appendDayMicros` / `putDayMicros` | 受 `unsafeBreakThreadConfinement()` 门控，不要使用 |

**由此得到的天花板**：`DuckDBAppender` 只有逐单元格 `append` 接口，STRING/BigDecimal 连批量重载都没有。
所以不要期待 5~10× 的收益 —— 那需要换驱动或走 DuckDB 原生读入，而 PG BINARY 格式没有原生读入路径。

## PG BINARY 线上格式约定（已按标准修正）

这两条约定曾经偏离 PG 标准，且因为**编码端与解码端出自同一处**，往返测试一直自洽通过、
测不出偏差。已修正并由真实服务端验证：

| 项 | 正确约定 | 曾经的写法 | 真实服务端实测 |
|---|---|---|---|
| TIMESTAMP / TIMESTAMPTZ | int64，自 **2000-01-01 00:00:00 UTC** 的微秒 | 自 1970 的微秒（`getTime() * 1000`） | 修正前：发送对应 2020-01-05 的标准值，读回 **1990-01-05**（偏移 30 年）；修正后读回 2020-01-05 ✅ |
| NUMERIC 符号位 | 负数 `0x4000`，正数/零 `0x0000` | 负数写成/读成 `1` | 修正前：`sign=0x4000` 读回 **+1234.5678**（丢负号）；修正后读回 -1234.5678 ✅ |

修正点：

- `PostgresSQLUtil.PG_EPOCH_MICROS` 把纪元常量收敛到一处，编码与解码共用。
- `toPgTimestampMicros(Instant)` 用 `Instant` 而非 `Timestamp.getTime()` 编码，
  避免同一时刻在不同默认时区的 JVM 上编出不同字节（`getTime()` 依赖默认时区）。
- `convertPGBigDecimalToByte` 写 `0x4000`，`convertPGByteToBigDecimal` 读 `0x4000`，两侧对称。
- `CopyDoneRequest` 的 `TYPE_TIMESTAMP` 分支加上纪元常量后走 `appendEpochMicros`，
  顺带把精度从"毫秒"提升到"微秒"（原实现 `/1000` 截断到毫秒）。

验证方式（可复现）：直接构造符合 PG 标准的 BINARY 载荷，用本项目真实服务端 COPY 进来再读回比对；
另用 `convertPGRowToByte` 造载荷做一次端到端往返。

## 回归验证

- `mvn -o -pl dbserver -am test`：**471 个测试（common）+ 308 个测试（dbserver），0 失败 0 错误**。
- 其中 COPY 契约测试 79 个全绿，覆盖：BINARY 各类型往返（`testBinaryCopy1~10`）、列数不符、
  截断流、NULL 与空串、重排列清单、未映射列走默认值、失败 COPY 的整体不生效、CopyFail 与刷新，
  以及 `CopyOptionsTest` / `CopyPayloadLimitTest` / `CopyTypeSupportTest` / `CopyFailFlushTest`。
- `CopySanityTest` 等仍使用 `jdbc:postgresql://`，依赖测试类路径上**源码版** `slackerdb-dbdriver`
  （其 `acceptsURL` 接受该方案）。若单独跑 `-pl dbserver`（不带 `-am`），会解析到本地仓库里的旧构件
  而出现 39 个 `Unable to parse URL` —— 这是构建方式问题，不是被测代码问题。

## 残余风险与未做的事

1. **整段缓冲仍在**：`CopyDataRequest` 依然把全部 CopyData 堆进
   `session.copyLastRemained`（上限 2 GiB），`CopyDoneRequest` 才解析。
   真正的流式摄取（在 CopyData 里增量解析并 append）能同时消掉 2 GiB 上限与峰值内存，
   但需要重写 `CopyPayloadLimitTest` 所保护的契约，本次未做。

2. **列数校验扫两遍**：为保住"报错不碰 Appender"的语义，建流前会多扫一遍行头。
   它与写入阶段的判据必须保持一致，改动其一时要同步另一处。

3. **时间戳语义集中在两处**：PG 纪元常量（`PostgresSQLUtil.PG_EPOCH_MICROS`）同时被编码路径
   （`toPgTimestampMicros`）与解码路径（`CopyDoneRequest` 的 `TYPE_TIMESTAMP` 分支）使用。
   改动时必须两侧同步，否则又会回到"编解码各自自洽、但对标准客户端全错"的状态。
