# DuckDB JDBC 探针归档（duckdb_jdbc 1.5.5.1 时期）

这里是原先放在仓库根目录 `jdbc-type-probe/` 的原始探针程序与实测记录，现归档到测试资源目录。

## 和测试的关系

- **日常回归不要跑这些程序**：断言化的版本已经固化在
  `dbserver/src/test/java/org/slackerdb/dbserver/test/DuckDbAppenderContractTest.java`
  （块 Appender 的能力集合、`append(String)` 只收 VARCHAR/ENUM/JSON、BLOB 用 `append(byte[])`、
  集合元素不做隐式转换、`appendHugeInt(低位, 高位)` 的参数顺序）。
  跑 `mvn -pl dbserver -am -Dtest=DuckDbAppenderContractTest test` 即可。
- 这里保留的是**原始证据与生成器**：`native-duckdb-jdbc-matrix.md` 是 39 类型 × 7 阶段的完整支持矩阵，
  由 `NativeDuckDbJdbcProbe.java` 生成；`README.md` 是当时的结论总结。
  这些程序都是默认包 + `main()`，**不参与编译**（放在 test resources 下，只作为资料）。

## 需要复跑时

这些程序要手工用当前版本的 jar 运行（jar 路径里的版本按 `dbserver/pom.xml` 里
`duckdb_jdbc` 的版本替换，当前是 **1.5.6.0**）：

```powershell
java -cp C:\work\repository\org\duckdb\duckdb_jdbc\1.5.6.0\duckdb_jdbc-1.5.6.0.jar `
     dbserver\src\test\resources\duckdb-jdbc-probes\AppenderCapabilityProbe.java
```

各文件对应的结论：

| 文件 | 结论 |
|---|---|
| `AppenderCapabilityProbe.java` | 块 Appender 只接受 VARCHAR/ENUM/JSON 的 `append(String)`；`BIT / INTERVAL / TIME_NS / BIGNUM / VARIANT` 连 `createAppender()` 都失败（`unsupported C API type: 29/15/39/35/41`） |
| `BulkAppenderNestedCastProbe.java` | 复合类型内部**不做隐式转换**：`List<String>` 进 `INTEGER[]` 报 `String cannot be cast to Integer` |
| `BulkAppenderJavaTypeProbe.java` | 每种类型该给什么 Java 值（`List<LocalDate>`、`List<BigInteger>`、`Map<String,Object>`…）与 `appendHugeInt(低位, 高位)` 的参数顺序 |
| `BlobAppendProbe.java` | BLOB 列要用 `append(byte[])`；`appendByteArray(byte[])` 是给 ARRAY/LIST 的 |
| `DuckDbPgCatalogProbe.java` / `JsonbBridgeProbe*.java` | "DuckDB 的 pg_catalog 支撑不了 pgjdbc 的类型解析"以及 STRUCT/MAP/LIST → JSON 桥的可行性证据 |
| `NativeDuckDbJdbcProbe.java` + `native-duckdb-jdbc-matrix.md` | 原生 DuckDB JDBC 在 查询 / 字面量 / setObject / setString+CAST / Appender / appendNull 各阶段的支持矩阵 |

## 版本敏感

能力集合的基线是 **duckdb_jdbc 1.5.6.0（1.5.5.1 实测完全相同）**。
若 `DuckDbAppenderContractTest` 在能力集合上失败（例如 2.0 开始支持 `BIT`/`INTERVAL`），
需要同步更新 `CopyColumnTypes.APPENDER_UNSUPPORTED` 及其注释、`PostgresTypeOids` 里的相关取舍说明，
并重新生成 `native-duckdb-jdbc-matrix.md`。
