# dbdriver 改造规范

本模块 = 上游 pgjdbc 源码 + 下列 9 处改造。除这 9 处，其余文件**逐行等于上游**（仅包名不同）。
改任何一处后按 §10 校验。

---

## 1. 命名与资源约定

| 项       | 值                                                                             |
|---------|-------------------------------------------------------------------------------|
| 包名      | `org.postgresql` → `org.slackerdb.jdbc`                                       |
| JDBC 协议 | `jdbc:slackerdb:`                                                             |
| SPI 文件  | `META-INF/services/java.sql.Driver` = `org.slackerdb.jdbc.Driver`             |
| 日志器     | `org.slackerdb.jdbc`、`org.slackerdb.jdbc.Driver`                              |
| 资源包     | `org.slackerdb.jdbc.translation.messages`（`*_xx.java` 翻译类与之同包）                |
| 字节码目标   | `release 17`（`dbdriver/pom.xml` 的 `maven.compiler.release`，**不要**跟父 POM 的 21） |

**搬树脚本**（把上游源码树变成本模块的 300 个文件）：

```powershell
$up   = '<上游源码>\pgjdbc\src\main\java\org\postgresql'
$ours = 'dbdriver\src\main\java\org\slackerdb\jdbc'
Remove-Item $ours -Recurse -Force
New-Item -ItemType Directory -Path $ours -Force | Out-Null
Copy-Item "$up\*" $ours -Recurse -Force
Get-ChildItem $ours -Recurse -File -Include *.java | ForEach-Object {
  # 必须用 [System.IO.File]：Windows PowerShell 的 Get-Content 按 ANSI(936) 解码 UTF-8，会写坏中文注释
  $t = [System.IO.File]::ReadAllText($_.FullName) -replace 'org\.postgresql\.', 'org.slackerdb.jdbc.'
  [System.IO.File]::WriteAllText($_.FullName, $t, (New-Object System.Text.UTF8Encoding($false)))
}
```

脚本会顺带改掉 `GT.java` 的 bundle 字符串（正确结果就是 `org.slackerdb.jdbc.translation.messages`）。

---

## 2. `Driver.java`

| 行                | 内容                                                                            |
|------------------|-------------------------------------------------------------------------------|
| `:81`            | `private static final String URL_PREFIX = "jdbc:slackerdb:";`                 |
| `:84-86`         | `hasSlackerdbPrefix(url)`：`regionMatches(true, 0, URL_PREFIX, 0, ...)`，大小写不敏感 |
| `:277-279`       | `connect()`：非本驱动 URL → FINE 日志 + **`return null`**                            |
| `:582-586`       | `parseURL()`：同一判定，再切前缀                                                        |
| `:69-70`, `:775` | logger 名；`getParentLogger()` 返回 `org.slackerdb.jdbc`                          |
| `:523-541`       | `getMajorVersion`/`getMinorVersion`/`getVersion` 读 `DriverInfo`               |

**硬约束**：`connect()` 的前缀判定必须在 `parseURL()` 之前，且与 `acceptsURL()` 用同一个函数。
否则本驱动会抢 `jdbc:postgresql:` 的请求并抛 `Unable to parse URL`（回归 `JdbcDriverContractTest`）。

---

## 3. `util/DriverInfo.java`（新增文件，上游没有）

```java
public static final String DRIVER_VERSION = "42.7.5";                               // 改这里
public static final int MAJOR_VERSION = 42, MINOR_VERSION = 7, PATCH_VERSION = 5;   // 同步改
public static final String DRIVER_NAME = "PostgreSQL JDBC Driver";                  // 保留：工具靠它识别
public static final String DRIVER_SHORT_NAME = "PgJDBC";
public static final String JDBC_VERSION = "4.2";
```

引用点：`Driver.java:523-541`、`PgDatabaseMetaData.java:286-300`。

---

## 4. `ds/common/BaseDataSource.java`

| 行       | 值                                                                 |
|---------|-------------------------------------------------------------------|
| `:1402` | `url.append("jdbc:slackerdb://");`（`getUrl()` 拼 DataSource 的 URL） |
| `:1795` | `return Logger.getLogger("org.slackerdb.jdbc");`                  |

---

## 5. `jdbc/PgDatabaseMetaData.java`（整文件自研）

**不要**用上游的实现（上游查 `pg_catalog`）。本文件查 DuckDB 系统表函数，
结构照 pgjdbc 的 `DatabaseMetaData` 骨架。

### 5.1 结构

| 行          | 内容                                                                                 |
|------------|------------------------------------------------------------------------------------|
| `:20-61`   | `enum DuckDBColumnType`：38 项（含 `TIME_NS`/`VARIANT`/`GEOMETRY`）                     |
| `:67-118`  | `appendEqualsQual` / `appendLikeQual` / `nullPatternToWildcard`（JDBC 的 null/"" 语义） |
| `:121-148` | `type_to_int()`：DuckDB 类型 → `java.sql.Types`（无符号取上一档、`GEOMETRY→BLOB`、兜底 `OTHER`）   |
| `:150-169` | `dataMap` + `typeNameInDuckDB()`：SQL `CASE` 的键（**只**把 `*_WITH_TIME_ZONE` 换成空格）     |
| `:171-185` | `makeCase()`                                                                       |
| `:187-194` | `makeDataMap()`：`...[]` 先判 `Types.ARRAY`，其余走 `CASE`                                |

### 5.2 方法 → 数据源

| 方法                                                                                                      | 数据源                                                                    | 行       |
|---------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|---------|
| `getTables`                                                                                             | `duckdb_tables()` ∪ `duckdb_views()`（`internal` → `SYSTEM TABLE/VIEW`） | `:1197` |
| `getColumns`                                                                                            | `duckdb_columns()` + `makeDataMap`                                     | `:1332` |
| `getPrimaryKeys`                                                                                        | `duckdb_constraints` + `unnest(constraint_column_names)`               | `:1423` |
| `getIndexInfo`                                                                                          | `duckdb_indexes()`                                                     | `:1690` |
| `getCrossReference`（`getImportedKeys`/`getExportedKeys` 委托它）                                            | `information_schema.referential_constraints` 四表 JOIN                   | `:1493` |
| `getTypeInfo`                                                                                           | 字面量 `UNION ALL`（不查库）                                                   | `:1605` |
| `getUDTs`                                                                                               | `duckdb_types()`（`internal = FALSE`）                                   | `:1828` |
| `getFunctions`                                                                                          | `duckdb_functions()`                                                   | `:1942` |
| `getCatalogs`/`getSchemas`                                                                              | `information_schema.schemata`                                          | —       |
| `getSQLKeywords`/`getNumericFunctions`/`getStringFunctions`/`getSystemFunctions`/`getTimeDateFunctions` | `duckdb_keywords()` / `duckdb_functions().parameter_types`             | —       |
| `getProcedures`/`getProcedureColumns`/`getFunctionColumns`                                              | `SELECT NULL WHERE FALSE`（空集）                                          | —       |
| 其余                                                                                                      | `throw new SQLFeatureNotSupportedException("方法名")`                     | —       |

### 5.3 这些返回值是契约，不要"顺手改"

| 方法                               | 值                                                             | 说明                                                                       |
|----------------------------------|---------------------------------------------------------------|--------------------------------------------------------------------------|
| `nullsAreSortedAtEnd()`          | `true`；`High`/`Low`/`AtStart` 均 `false`                       | DuckDB `NULLS_LAST`：ASC/DESC 下 NULL 都在最后；JDBC 要求四个里恰好一个 true（`:245-266`） |
| `supportsResultSetType()`        | `type != TYPE_SCROLL_SENSITIVE`                               | 滚动结果集可用（`:1750`）                                                         |
| `supportsResultSetConcurrency()` | `type != SCROLL_SENSITIVE && concurrency == CONCUR_READ_ONLY` | 可更新结果集已裁剪（`:1763`）                                                       |
| `supportsGetGeneratedKeys()`     | `true`                                                        | `RETURN_GENERATED_KEYS` 走 `RETURNING`，可用（`:2016`）                        |
| `getDatabaseProductName()`       | `"PostgreSQL"`                                                | 工具靠这个名字分流                                                                |

---

## 6. `jdbc/PgResultSet.java`：删除可更新 ResultSet

35 处方法体改成 `throw new SQLFeatureNotSupportedException("方法名")`（区间 `:1059-3530`）：

```
insertRow updateRow deleteRow refreshRow cancelRowUpdates moveToInsertRow moveToCurrentRow
rowDeleted updateNull updateBoolean updateByte updateShort updateInt updateLong updateFloat
updateDouble updateBigDecimal updateString updateBytes updateDate updateTime updateTimestamp
updateAsciiStream updateBinaryStream updateCharacterStream updateObject(×3) updateSQLXML(×2)
（含 byName 重载）
```

同时删掉只服务于它的代码：`isUpdateable()`、`checkUpdateable()`、`clearRowBuffer()`、
`updateRowBuffer()` 的写回路径、`usingOID`、`PrimaryKey` 内部类。

**成对约束**：`PgResultSet.isUpdateable()` 会调
`PgDatabaseMetaData.getPrimaryUniqueKeys(...)`。
本模块两边都不留：`PgResultSet` 不调，`PgDatabaseMetaData` 也**不要**加这个方法。

---

## 7. `jdbc/PgResultSetMetaData.java:198-261`

`fetchFieldMetaData()` 用「值表 + oid 过滤」，不要用上游的行值 `IN`：

```sql
SELECT <oid> AS oid, <attnum> AS attnum
UNION ALL SELECT ...
JOIN ( ... ) vals ON (c.oid = vals.oid AND a.attnum = vals.attnum)
WHERE c.oid IN (<去重后的 oid>)      -- :256，oidSet 在 :227 去重
```

原因：服务端的 `pg_class`/`pg_attribute` 是按需伪造的视图，行值 `IN` 与全表 JOIN 走不通。

---

## 8. Unix Domain Socket

| 位置                                     | 内容                                                                                     |
|----------------------------------------|----------------------------------------------------------------------------------------|
| `UnixDomainSocketFactory.java`（新增）     | 基于 `java.net.UnixDomainSocketAddress`；默认路径 `/tmp/.s.slackerdb.4309`                    |
| `core/SocketFactoryFactory.java:28-30` | `UNIX_DOMAIN_SOCKET_FACTORY_CLASS_NAME = "org.slackerdb.jdbc.UnixDomainSocketFactory"` |
| `core/SocketFactoryFactory.java:44-57` | 只给 `socketFactoryArg`、没给 `socketFactory` 时自动选 UDS                                      |

用法：只设 `socketFactoryArg=<socket 路径>`，不必显式指定工厂类。

---

## 9. 必须保留的旧名字（兼容开关，不要"清理"）

| 位置                                                                        | 保留的旧引用                                                                    | 作用                                       |
|---------------------------------------------------------------------------|---------------------------------------------------------------------------|------------------------------------------|
| `PGEnvironment.java:23,40`                                                | 系统属性 `org.postgresql.pgpassfile` / `pgservicefile`                        | 用户既有配置的输入名；改名会让现存 pgpass/service 配置静默失效  |
| `jdbc/PGStatement.java:53-54`                                             | `org.postgresql.forceBinary`（新名 `org.slackerdb.jdbc.forceBinary` 优先，旧名回退） | 兼容从 pgjdbc 迁来的测试脚本                       |
| `ds/common/PGObjectFactory.java:42-52`、`xa/PGXADataSourceFactory.java:34` | 老的 `org.postgresql.*` 类名                                                  | 老 JNDI `java.naming.factory.object` 配置分派 |
| `core/SocketFactoryFactory.java:77`                                       | `org.postgresql.ssl.LibPQFactory`                                         | 命中时给 deprecation 警告                      |

除上表外，代码里不应再出现 `org.postgresql` 字样（注释里说明"为什么"的除外）。

---

### 回归覆盖

| 测试                                                     | 覆盖                                  |
|--------------------------------------------------------|-------------------------------------|
| `JdbcDriverContractTest`                               | §2 URL 判定、与标准 pgjdbc 共存             |
| `JdbcDatabaseMetaDataTest`（13 例）                       | §5 全部方法 + 类型映射 + §5.3 的契约           |
| `JdbcGeneratedKeysTest`（7 例）                           | generated keys、表类型命名、可滚动结果集、§6 裁剪边界 |
| `JdbcDataSourceAndTranslationTest`（2 例）                | §4 `getUrl()` 前缀 + 端到端建连；§1 资源包基名   |
| `JdbcArrayDecodingTest` / `ClientTest`                 | 数组解码；`getColumns` 逐字段断言             |
| `RowEncoderTest` / `UnsignedIntegerTest` / `Copy*Test` | 类型往返                                |
| `dbcli` 的 `Jdbc*`                                      | 连接池 / DataSource / 驱动装载             |

---
