# JDBC 数据类型探针（临时证据包）

目的：用同一套用例量出 **DuckDB 原生 JDBC（duckdb_jdbc 1.5.6.0）** 与 **SlackerDB JDBC** 对 DuckDB
各数据类型的支持情况，覆盖查询 / 修改 / COPY（Appender）三条链路，重点是复合类型
（`STRUCT`、`UNION`、`MAP`、`VARIANT`）。

SlackerDB 侧的实现与契约已经落在代码和永久测试里（`dbserver/sql/CopyValueWriters.java`、
`dbserver/sql/CopyColumnTypes.java`、`dbserver/test/CopyTypeSupportTest.java`），
本目录只保留支撑那套实现的实测证据。

## 保留的文件与结论

| 文件 | 结论 |
|---|---|
| `NativeDuckDbJdbcProbe.java` + `native-duckdb-jdbc-matrix.md` | 原生基线：49 个用例 × 7 个阶段的支持矩阵（查询/字面量增改/setObject/setString+CAST/Appender/appendNull） |
| `AppenderCapabilityProbe.java` | 块 Appender **只接受 VARCHAR/ENUM/JSON**（`append(String)` 对其它列报 `invalid column type, expected one of: '[DUCKDB_TYPE_VARCHAR, DUCKDB_TYPE_ENUM]'`）；`BIT / INTERVAL / TIME_NS / BIGNUM / VARIANT` 连 `createAppender()` 都失败（`unsupported C API type: 29/15/39/35/41`） |
| `BulkAppenderNestedCastProbe.java` | 复合类型内部也**不做隐式转换**：`List<String>` 进 `INTEGER[]` 报 `String cannot be cast to Integer`，STRUCT 字段用 `append(String)` 同样被拒 |
| `BulkAppenderJavaTypeProbe.java` | 每种类型该给什么 Java 值（`List<LocalDate>`、`List<BigInteger>`、`Map<String,Object>`、`List<Map<...>>`…）与 `appendHugeInt(低位, 高位)` 的参数顺序 |
| `BlobAppendProbe.java` | **BLOB 列要用 `append(byte[])`**；`appendByteArray(byte[])` 是给 ARRAY/LIST 的（对 BLOB 报 `expected one of: '[DUCKDB_TYPE_ARRAY, DUCKDB_TYPE_LIST]'`） |
| `DuckDbPgCatalogProbe.java`、`JsonbBridgeProbe.java`、`JsonbBridgeProbe2.java` | "这些类型在 PG 里有没有对应物、PG JDBC 能不能原生解码"的证据（见下节） |

运行方式（在仓库根目录，需要 duckdb_jdbc 的 jar）：

```powershell
java -cp C:\work\repository\org\duckdb\duckdb_jdbc\1.5.6.0\duckdb_jdbc-1.5.6.0.jar `
     jdbc-type-probe\AppenderCapabilityProbe.java
```

## SlackerDB COPY 的实现要点（纯块 Appender）

块 Appender 按列物理类型分派、**不做隐式转换**，所以服务端必须把 CSV 文本解析成精确的 Java 值
（`CopyValueWriters`），再调用对应重载：

| DuckDB 列类型 | 追加时用的 Java 值 / API |
|---|---|
| BOOLEAN | `Boolean` → `append(boolean)` |
| TINYINT / UTINYINT | `Byte`（无符号取低 8 位，带值域校验） |
| SMALLINT / USMALLINT | `Short`（无符号取低 16 位，带值域校验） |
| INTEGER / UINTEGER | `Integer`（无符号取低 32 位，带值域校验） |
| BIGINT / UBIGINT | `Long`（UBIGINT 走 `BigInteger.longValue()`，带值域校验） |
| HUGEINT / UHUGEINT | `BigInteger`；UHUGEINT 超过 int128 时 `appendHugeInt(低位, 高位)` |
| FLOAT / DOUBLE / DECIMAL | `Float` / `Double` / `BigDecimal` |
| VARCHAR / JSON / ENUM | `String`（顶层原样，不去引号、不 trim） |
| BLOB | `byte[]` → `append(byte[])` |
| UUID / DATE / TIME / TIMETZ | `UUID` / `LocalDate` / `LocalTime` / `OffsetTime` |
| TIMESTAMP / TIMESTAMP_S / _MS / _NS | `LocalDateTime` |
| TIMESTAMPTZ | `OffsetDateTime`（绝对时刻；文本无偏移时按 UTC） |
| LIST / ARRAY | `Collection`，元素必须是**精确类型**（`List<LocalDate>`、`List<List<Integer>>`…） |
| MAP | `Map`（键值精确类型） |
| STRUCT | 顶层 `beginStruct()/append(...)/endStruct()`；作为集合元素时用 `Map<String,Object>` |
| UNION | 顶层（含顶层 STRUCT 的字段）：`beginUnion(tag)/endUnion()`；集合/结构体内部：`SimpleEntry<String,Object>(tag, 值)` —— Appender 只认这两种形态，tag 均由文本按声明成员顺序推断 |

文本形态：LIST 用 `[1, 2, 3]`；STRUCT 接受 `{'a': 1, 'b': 'x'}` 与 JSON `{"a": 1, "b": "x"}`；
MAP 接受 `{a=1}` / `{'a': 1}` / JSON；BLOB 接受 `\xDEADBEEF`、`\xDE\xAD\xBE\xEF`、`0xDEADBEEF`；
时间戳接受 `yyyy-MM-dd HH:mm:ss[.fraction]`（也接受 ISO 的 `T`/`Z`）。

**明确拒绝的 5 个类型**（块 Appender 建不起来）：`BIT`、`INTERVAL`、`TIME_NS`、`BIGNUM`、`VARIANT`。
注意 Appender 是按**整表列类型**校验的：只要表里有这 5 类列中的任何一种，**整张表都无法 COPY**
（哪怕 COPY 根本不写那一列），服务端在建立阶段就给出带列名/类型名的明确错误。

已知的**歧义**（不是限制）：UNION 的 tag 靠成员顺序推断，
`UNION(num INTEGER, str VARCHAR)` 下文本 `42` 会落到 `num`。
集合内部的 UNION 元素有明确的值形态（`SimpleEntry(tag, 值)`），实测可用：
`UNION(...)[]` ← `List<SimpleEntry>`、`MAP(VARCHAR, UNION(...))` ← `Map<String, SimpleEntry>`、
`STRUCT(u UNION(...))[]` ← `List<Map<String, SimpleEntry>>`；DuckDB 自己的报错也指明了这一点：
`union values must be specified as an instance of 'java.util.AbstractMap.SimpleEntry<String, Object>'`。

## DuckDB → PG 类型对应关系（另一条路线，供参考）

如果将来要把库暴露给只认 PG 类型的客户端，结论是：**PG 线协议每列只带一个 OID**，
所以"能不能被 PG JDBC 原生解码"取决于能否给出 pgjdbc 认识的 OID（`TypeInfoCache` 用
`typinput='array_in'` → ARRAY、`typtype='c'` → STRUCT、`'e'` → VARCHAR，其余 OTHER）：

| DuckDB | PG 对应物 | pgjdbc 结果 |
|---|---|---|
| 常规标量 / JSON / UUID / BIT / INTERVAL | ✅ 同名 | 原生支持 |
| 无符号整型、HUGEINT/UHUGEINT/BIGNUM | ❌（PG 无此类型） | 加宽到 int2/int4/int8 或 numeric |
| TIMESTAMP_S/_MS/_NS、TIME_NS | ⚠️ 精度损失 | 映射到 timestamp/time |
| LIST / ARRAY | ✅ PG 数组 | `getArray` 可用，但要自己写 `{}` ↔ `[]` 编解码 |
| STRUCT | ⚠️ 只有命名复合类型 | pgjdbc 只给 `PGobject` 文本，不给 Map |
| MAP / UNION / VARIANT | ❌ | 只能借 jsonb / text |

实测依据：DuckDB 的 `pg_catalog.pg_type` 只有 39 行，DuckDB 特有类型 OID 全是 0、`typarray/typelem`
恒 0、没有 `typtype='c'`；`SQLReplacer` 又把 `typinput='pg_catalog.array_in'` 替换成 `false`，
所以现在没有任何列会走 `Types.ARRAY`。JSON 桥可行性：`to_json(STRUCT/MAP/LIST)` 可用、
`from_json(json,'{"a":"INTEGER"}')` 可回转，但 **`to_json(VARIANT)` 是坏的**（要用 `CAST(v AS JSON)`），
且 `'{1,2}'` 不能直接 cast 成 DuckDB `INTEGER[]`。

## 读路径的两个类型声明缺陷（已修）

两个都是"服务端声明了一个客户端不认识的 OID"导致的，已按下面方式修掉，回归用例见
`RowDescriptionMetadataTest#intervalAndBitColumnsAreReadable`：

* **BIT 声明错 OID（服务端侧修）**：原来声明 1563（= PG 的 `_varbit`，**数组** OID），客户端按数组解析——
  `getColumnTypeName()` 得到 `_varbit`、`getObject()` 得到 `PgArray`。
  现在声明 **1560（`bit`）**；入参方向仍接受 1560/1562/1563（三者二进制载荷都是
  int4 位长 + 字节，按位串解码即可）。
* **INTERVAL 整列不可用（服务端 fake catalog 侧修）**：原来声明 1186（interval），
  而**两个客户端的内置类型表里都没有 interval**——官方 `postgresql-42.7.2` 的
  `TypeInfoCache.types` 没有这一行（只有 `PgConnection.initObjectTypes()` 按名字登记了
  `PGInterval` **类**，没登记 OID；`javap` 里整个 `types` 表的字符串常量都找不到 `interval`），
  本项目 dbdriver 同样没有。所以两边都依赖"按 OID 取类型名"的兜底查询：
  `SELECT n.nspname = ANY(current_schemas(true)), n.nspname, t.typname FROM pg_type t
  JOIN pg_namespace n ON t.typnamespace = n.oid WHERE t.oid = ?`。
  这条查询经本服务端**恒返回 0 行** → `getPGType(1186)` 得到 null →
  `PgResultSet.initSqlType()` 的 `castNonNull(...)` 直接抛
  `Misuse of castNonNull: called with a null argument`，**连 `getColumnTypeName()` 都抛**。

  根因（1.5.6 实测）：`duck_catalog.pg_type` 是启动时在 `use memory` 下物化的副本，
  它的 `typnamespace` 冻结为 **20622**；而客户端查询时 DuckDB 的 `pg_namespace.oid` 是另一个值
  **22377**（随 catalog 变），两者对不上；再加上原来 `pg_namespace` 是从空的 `duckdb_schemas`
  建的（0 行），JOIN 必然为空。

  **最终修法**：`SlackerCatalog` 里把 `pg_namespace` 放在 `pg_type` 之后建，
  并把第一条 union 分支的 oid 直接取自 `pg_type.typnamespace`
  （`select distinct typnamespace … from duck_catalog.pg_type`），保证 JOIN 永远命中。
  于是服务端可以声明真实的 **1186**：官方 pgjdbc 与 dbdriver 各拿到自己的 `PGInterval`。
  回归用例覆盖了**两个驱动**：`RowDescriptionMetadataTest#intervalAndBitColumnsAreReadable`（官方）
  与 `#intervalIsReadableThroughProjectDriverToo`（dbdriver）。

  两条被否掉的路子，别再走：
  * **服务端降级成 VARCHAR(1043)**：能救所有客户端，但代价是所有客户端（含本来正确的官方驱动、
    DBeaver）都失去 interval 语义，而且参数方向仍认 1186、两个方向不一致。
  * **只给某一个客户端补内置类型**（例如给 dbdriver 的 `TypeInfoCache.types` 加一行）：
    救不了第三方/标准驱动——`RowDescriptionMetadataTest` 就是用 `jdbc:postgresql://` 连的，
    加完 dbdriver 那一行它照样红。**服务端的问题必须服务端修。**

顺带记下这类问题的通用规律：**列 OID 必须是客户端能解析的** —— 要么在客户端内置类型表里，
要么 fake catalog 的兜底查询查得到。后者以前不可用（pg_namespace 对不上 pg_type.typnamespace），
现在可用了。`SlackerCatalog` 里那几条补 PG 标准 OID 的 `INSERT`（13=int/14=bigint/19=timestamp/25=text）
原先写死 `typnamespace=548`（旧版 DuckDB 的 main oid），也跟着一起改成实时取
`(SELECT min(typnamespace) FROM pg_catalog.pg_type)` —— 否则那些行在 JOIN 里等于不存在，
`getColumns()` 的 `TYPE_NAME` 只能是 null（`Sanity02Test` 里对此有断言与说明）。
服务端对 STRUCT/LIST/MAP/UNION/VARIANT/ENUM/BIGNUM 等未登记类型统一降级成 1043(VARCHAR)，
所以那些类型不会踩坑；INTERVAL 是唯一"声明了具体 OID 但两个客户端表里都没有"的类型。
