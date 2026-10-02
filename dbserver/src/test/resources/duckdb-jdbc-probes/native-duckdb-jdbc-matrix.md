# DuckDB 原生 JDBC (duckdb_jdbc 1.5.6.0) 数据类型支持矩阵

每个用例的每个阶段互相独立：写入前先 `DELETE FROM`，写完 `SELECT c` 读回，并与 DuckDB 自身的文本表示（`CAST(literal AS type)::VARCHAR`）比对。

| # | 类型 | ddl | insLit | updLit | paramObj | paramText | appender | appNull | 读回 (元数据 + 值) |
|---|------|-----|--------|--------|----------|-----------|----------|---------|--------------------|
| 1 | BOOLEAN | OK | OK | OK | OK(setObject(Boolean)) | OK | OK(append(boolean)) | OK | meta=BOOLEAN Types=16 cls=java.lang.Boolean text=true ==duckdb |
| 2 | TINYINT | OK | OK | OK | OK(setObject(Byte)) | OK | OK(append(byte)) | OK | meta=TINYINT Types=-6 cls=java.lang.Byte text=-42 ==duckdb |
| 3 | SMALLINT | OK | OK | OK | OK(setObject(Short)) | OK | OK(append(short)) | OK | meta=SMALLINT Types=5 cls=java.lang.Short text=-12345 ==duckdb |
| 4 | INTEGER | OK | OK | OK | OK(setObject(Integer)) | OK | OK(append(int)) | OK | meta=INTEGER Types=4 cls=java.lang.Integer text=123456789 ==duckdb |
| 5 | BIGINT | OK | OK | OK | OK(setObject(Long)) | OK | OK(append(long)) | OK | meta=BIGINT Types=-5 cls=java.lang.Long text=1234567890123 ==duckdb |
| 6 | HUGEINT | OK | OK | OK | OK(setObject(BigInteger)) | OK | OK(append(BigInteger)) | OK | meta=HUGEINT Types=1111 cls=java.math.BigInteger text=170141183460469231731687303715884105727 ==duckdb |
| 7 | FLOAT | OK | OK | OK | OK(setObject(Float)) | OK | OK(append(float)) | OK | meta=FLOAT Types=6 cls=java.lang.Float text=1.5 ==duckdb |
| 8 | DOUBLE | OK | OK | OK | OK(setObject(Double)) | OK | OK(append(double)) | OK | meta=DOUBLE Types=8 cls=java.lang.Double text=3.141592653589793 ==duckdb |
| 9 | DECIMAL(18,4) | OK | OK | OK | OK(setObject(BigDecimal)) | OK | OK(append(BigDecimal)) | OK | meta=DECIMAL(18,4) Types=3 cls=java.math.BigDecimal text=12345678.9012 ==duckdb |
| 10 | DECIMAL(38,10) | OK | OK | OK | OK(setObject(BigDecimal)) | OK | OK(append(BigDecimal)) | OK | meta=DECIMAL(38,10) Types=3 cls=java.math.BigDecimal text=1234567890123456789012345678.9012345678 ==duckdb |
| 11 | BIGNUM | OK | OK | OK | OK(setObject(BigDecimal)) | OK | FAIL | FAIL | meta=BIGNUM Types=1111 cls=java.lang.String text=123456789012345678901234567890123456789 ==duckdb |
| 12 | UTINYINT | OK | OK | OK | OK(setObject(Short)) | OK | OK(append(byte) 位模式) | OK | meta=UTINYINT Types=5 cls=java.lang.Short text=250 ==duckdb |
| 13 | USMALLINT | OK | OK | OK | OK(setObject(Integer)) | OK | OK(append(short) 位模式) | OK | meta=USMALLINT Types=4 cls=java.lang.Integer text=60000 ==duckdb |
| 14 | UINTEGER | OK | OK | OK | OK(setObject(Long)) | OK | OK(append(int) 位模式) | OK | meta=UINTEGER Types=-5 cls=java.lang.Long text=4000000000 ==duckdb |
| 15 | UBIGINT | OK | OK | OK | OK(setObject(BigInteger)) | OK | OK(append(long) 位模式) | OK | meta=UBIGINT Types=1111 cls=java.math.BigInteger text=18446744073709551615 ==duckdb |
| 16 | UHUGEINT | OK | OK | OK | OK(setObject(BigDecimal)) | OK | OK(appendHugeInt(-1,-1)) | OK | meta=UHUGEINT Types=1111 cls=java.math.BigInteger text=340282366920938463463374607431768211455 ==duckdb |
| 17 | VARCHAR | OK | OK | OK | OK(setObject(String)) | OK | OK(append(String)) | OK | meta=VARCHAR Types=12 cls=java.lang.String text=hello 世界 ==duckdb |
| 18 | BLOB | OK | OK | OK | OK(setObject(byte[])) | OK | OK(appendByteArray(byte[])) | OK | meta=BLOB Types=2004 cls=DuckDBBlobResult text=\xDE\xAD\xBE\xEF ==duckdb |
| 19 | BIT | OK | OK | OK | OK(setObject(String)) | OK | FAIL | FAIL | meta=BIT Types=-7 cls=java.lang.String text=1010 ==duckdb |
| 20 | UUID | OK | OK | OK | OK(setObject(UUID)) | OK | OK(append(UUID)) | OK | meta=UUID Types=1111 cls=java.util.UUID text=123e4567-e89b-12d3-a456-426614174000 ==duckdb |
| 21 | JSON | OK | OK | OK | OK(setObject(String)) | OK | OK(append(String)) | OK | meta=JSON Types=1111 cls=org.duckdb.JsonNode text={"a": 1} ==duckdb |
| 22 | DATE | OK | OK | OK | OK(setObject(LocalDate)) | OK | OK(append(LocalDate)) | OK | meta=DATE Types=91 cls=java.time.LocalDate text=2024-02-29 ==duckdb |
| 23 | TIME | OK | OK | OK | OK(setObject(LocalTime)) | OK | OK(append(LocalTime)) | OK | meta=TIME Types=92 cls=java.time.LocalTime text=12:34:56.789 ==duckdb |
| 24 | TIME_NS | OK | OK | OK | OK(setString) | OK | FAIL | FAIL | meta=TIME_NS Types=92 cls=java.time.LocalTime text=12:34:56.123456789 ==duckdb |
| 25 | TIMETZ | OK | OK | OK | OK(setString) | OK | OK(append(OffsetTime)) | OK | meta=TIME WITH TIME ZONE Types=2013 cls=java.time.OffsetTime text=12:34:56.789+08:00 !=duckdb(12:34:56.789+08) |
| 26 | TIMESTAMP | OK | OK | OK | OK(setObject(LocalDateTime)) | OK | OK(append(LocalDateTime)) | OK | meta=TIMESTAMP Types=93 cls=java.sql.Timestamp text=2024-02-29 12:34:56.789 ==duckdb |
| 27 | TIMESTAMP_S | OK | OK | OK | OK(setObject(LocalDateTime)) | OK | OK(append(LocalDateTime)) | OK | meta=TIMESTAMP_S Types=93 cls=java.sql.Timestamp text=2024-02-29 12:34:56.0 !=duckdb(2024-02-29 12:34:56) |
| 28 | TIMESTAMP_MS | OK | OK | OK | OK(setObject(LocalDateTime)) | OK | OK(append(LocalDateTime)) | OK | meta=TIMESTAMP_MS Types=93 cls=java.sql.Timestamp text=2024-02-29 12:34:56.789 ==duckdb |
| 29 | TIMESTAMP_NS | OK | OK | OK | OK(setObject(LocalDateTime)) | OK | OK(append(LocalDateTime)) | OK | meta=TIMESTAMP_NS Types=93 cls=java.sql.Timestamp text=2024-02-29 12:34:56.789123456 ==duckdb |
| 30 | TIMESTAMPTZ | OK | OK | OK | OK(setObject(OffsetDateTime)) | OK | OK(append(OffsetDateTime)) | OK | meta=TIMESTAMP WITH TIME ZONE Types=2014 cls=java.time.OffsetDateTime text=2024-02-29T04:34:56.789Z !=duckdb(2024-02-29 12:34:56.789+08) |
| 31 | INTERVAL | OK | OK | OK | OK(setObject(String)) | OK | FAIL | FAIL | meta=INTERVAL Types=1111 cls=java.lang.String text=1 year 2 months 3 days 04:05:06.007 ==duckdb |
| 32 | ENUM | OK | OK | OK | OK(setObject(String)) | OK | OK(append(String)) | OK | meta=ENUM Types=1111 cls=java.lang.String text=happy ==duckdb |
| 33 | LIST(INTEGER) | OK | OK | OK | FAIL | OK | OK(append(Collection)) | OK | meta=INTEGER[] Types=2003 cls=org.duckdb.DuckDBArray text=[1, NULL, 3] ==duckdb |
| 34 | LIST(VARCHAR) | OK | OK | OK | FAIL | OK | OK(append(Collection)) | OK | meta=VARCHAR[] Types=2003 cls=org.duckdb.DuckDBArray text=[a, b] ==duckdb |
| 35 | ARRAY(INTEGER,3) | OK | OK | OK | FAIL | OK | OK(append(int[])) | OK | meta=INTEGER[3] Types=2003 cls=org.duckdb.DuckDBArray text=[1, 2, 3] ==duckdb |
| 36 | STRUCT | OK | OK | OK | FAIL | OK | OK(beginStruct/endStruct) | OK | meta=STRUCT(a INTEGER, b VARCHAR) Types=2002 cls=org.duckdb.DuckDBStruct text={'a': 1, 'b': x} ==duckdb |
| 37 | STRUCT(nested) | OK | OK | OK | FAIL | OK | OK(beginStruct/endStruct(nested)) | OK | meta=STRUCT(a INTEGER, b VARCHAR[], s STRUCT(x INTEGER)) Types=2002 cls=org.duckdb.DuckDBStruct text={'a': 1, 'b': [x, y], 's': {'x': 7}} ==duckdb |
| 38 | LIST(STRUCT) | OK | OK | OK | FAIL | OK | OK(append(Collection of Map)) | OK | meta=STRUCT(a INTEGER)[] Types=2003 cls=org.duckdb.DuckDBArray text=[{'a': 1}, {'a': 2}] ==duckdb |
| 39 | MAP | OK | OK | OK | FAIL | OK | OK(append(Map)) | OK | meta=MAP(VARCHAR, INTEGER) Types=1111 cls=java.util.LinkedHashMap text={a=1, b=2} ==duckdb |
| 40 | MAP(STRUCT) | OK | OK | OK | FAIL | OK | OK(append(Map of Map)) | OK | meta=MAP(VARCHAR, STRUCT(a INTEGER)) Types=1111 cls=java.util.LinkedHashMap text={k={'a': 1}} ==duckdb |
| 41 | MAP(INTEGER,VARCHAR) | OK | OK | OK | FAIL | OK | OK(append(Map)) | OK | meta=MAP(INTEGER, VARCHAR) Types=1111 cls=java.util.LinkedHashMap text={1=x} ==duckdb |
| 42 | UNION(num) | OK | OK | OK | OK(setObject(Integer)) | OK | OK(beginUnion('num')) | OK | meta=UNION(num INTEGER, str VARCHAR) Types=1111 cls=java.lang.String text=42 ==duckdb |
| 43 | UNION(str) | OK | OK | OK | OK(setObject(String)) | OK | OK(beginUnion('str')) | OK | meta=UNION(num INTEGER, str VARCHAR) Types=1111 cls=java.lang.String text=hi ==duckdb |
| 44 | UNION(nested) | OK | OK | OK | FAIL | FAIL | OK(beginUnion('s')+beginStruct) | OK | meta=UNION(n INTEGER, s STRUCT(a INTEGER), l INTEGER[]) Types=1111 cls=java.lang.String text={'a': 9} ==duckdb |
| 45 | VARIANT(int) | OK | OK | OK | OK(setObject(Integer)) | OK | FAIL | FAIL | meta=VARIANT Types=1111 cls=java.lang.String text=42 ==duckdb |
| 46 | VARIANT(struct) | OK | OK | OK | FAIL | OK | FAIL | FAIL | meta=VARIANT Types=1111 cls=java.lang.String text={a=1} !=duckdb({'a': 1}) |
| 47 | VARIANT(list) | OK | OK | OK | FAIL | OK | FAIL | FAIL | meta=VARIANT Types=1111 cls=java.lang.String text=[1, 2] ==duckdb |
| 48 | VARIANT(string) | OK | OK | OK | OK(setObject(String)) | OK | FAIL | FAIL | meta=VARIANT Types=1111 cls=java.lang.String text=txt ==duckdb |
| 49 | LIST(LIST) | OK | OK | OK | FAIL | OK | OK(append(Collection of Collection)) | OK | meta=INTEGER[][] Types=2003 cls=org.duckdb.DuckDBArray text=[[1, 2], [3]] ==duckdb |

## 失败明细

- `BIGNUM` / appender: `SQLException`: append(BigDecimal) → Appender error, catalog: 'null', schema: 'null', table: 'probe_11', message: unsupported C API type: 35
- `BIGNUM` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_11', message: unsupported C API type: 35
- `BIT` / appender: `SQLException`: append(String) → Appender error, catalog: 'null', schema: 'null', table: 'probe_19', message: unsupported C API type: 29
- `BIT` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_19', message: unsupported C API type: 29
- `TIME_NS` / appender: `SQLException`: append(LocalTime) → Appender error, catalog: 'null', schema: 'null', table: 'probe_24', message: unsupported C API type: 39
- `TIME_NS` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_24', message: unsupported C API type: 39
- `INTERVAL` / appender: `SQLException`: append(String) → Appender error, catalog: 'null', schema: 'null', table: 'probe_31', message: unsupported C API type: 15
- `INTERVAL` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_31', message: unsupported C API type: 15
- `LIST(INTEGER)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `LIST(VARCHAR)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `ARRAY(INTEGER,3)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `STRUCT` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `STRUCT(nested)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `LIST(STRUCT)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `MAP` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `MAP(STRUCT)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `MAP(INTEGER,VARCHAR)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `UNION(nested)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `UNION(nested)` / paramText: `SQLException`: Conversion Error: Type VARCHAR can't be cast as UNION(n INTEGER, s STRUCT(a INTEGER), l INTEGER[]). VARCHAR can't be implicitly cast to any of the union member types: INTEGER, STRUCT(a INTEGER), INTEGER[]
- `VARIANT(int)` / appender: `SQLException`: append(int) → Appender error, catalog: 'null', schema: 'null', table: 'probe_45', message: unsupported C API type: 41 | append(String) → Appender error, catalog: 'null', schema: 'null', table: 'probe_45', message: unsupported C API type: 41 | beginStruct → Appender error, catalog: 'null', schema: 'null', table: 'probe_45', message: unsupported C API type: 41 | beginUnion('value') → Appender error, catalog: 'null', schema: 'null', table: 'probe_45', message: unsupported C API type: 41
- `VARIANT(int)` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_45', message: unsupported C API type: 41
- `VARIANT(struct)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `VARIANT(struct)` / appender: `SQLException`: beginStruct → Appender error, catalog: 'null', schema: 'null', table: 'probe_46', message: unsupported C API type: 41 | append(Map) → Appender error, catalog: 'null', schema: 'null', table: 'probe_46', message: unsupported C API type: 41
- `VARIANT(struct)` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_46', message: unsupported C API type: 41
- `VARIANT(list)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type
- `VARIANT(list)` / appender: `SQLException`: append(Collection) → Appender error, catalog: 'null', schema: 'null', table: 'probe_47', message: unsupported C API type: 41 | append(String) → Appender error, catalog: 'null', schema: 'null', table: 'probe_47', message: unsupported C API type: 41
- `VARIANT(list)` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_47', message: unsupported C API type: 41
- `VARIANT(string)` / appender: `SQLException`: append(String) → Appender error, catalog: 'null', schema: 'null', table: 'probe_48', message: unsupported C API type: 41
- `VARIANT(string)` / appNull: `SQLException`: Appender error, catalog: 'null', schema: 'null', table: 'probe_48', message: unsupported C API type: 41
- `LIST(LIST)` / paramObj: `SQLException`: Invalid Input Error: Unsupported parameter type

## 复合类型子项/字段读取

- `TINYINT` : `c::INTEGER` → meta=INTEGER obj=-42 text=-42
- `UTINYINT` : `c::INTEGER` → meta=INTEGER obj=250 text=250
- `USMALLINT` : `c::INTEGER` → meta=INTEGER obj=60000 text=60000
- `LIST(INTEGER)` : `c[1]` → meta=INTEGER obj=1 text=1
- `LIST(INTEGER)` : `c[2] IS NULL` → meta=BOOLEAN obj=true text=true
- `LIST(INTEGER)` : `c[3]` → meta=INTEGER obj=3 text=3
- `LIST(INTEGER)` : `len(c)` → meta=BIGINT obj=3 text=3
- `LIST(VARCHAR)` : `c[1]` → meta=VARCHAR obj=a text=a
- `LIST(VARCHAR)` : `len(c)` → meta=BIGINT obj=2 text=2
- `ARRAY(INTEGER,3)` : `c[3]` → meta=INTEGER obj=3 text=3
- `STRUCT` : `c.a` → meta=INTEGER obj=1 text=1
- `STRUCT` : `c.b` → meta=VARCHAR obj=x text=x
- `STRUCT` : `struct_extract(c,'a')` → meta=INTEGER obj=1 text=1
- `STRUCT` : `c::VARCHAR` → meta=VARCHAR obj={'a': 1, 'b': x} text={'a': 1, 'b': x}
- `STRUCT(nested)` : `c.b[2]` → meta=VARCHAR obj=y text=y
- `STRUCT(nested)` : `c.s.x` → meta=INTEGER obj=7 text=7
- `STRUCT(nested)` : `c::VARCHAR` → meta=VARCHAR obj={'a': 1, 'b': [x, y], 's': {'x': 7}} text={'a': 1, 'b': [x, y], 's': {'x': 7}}
- `LIST(STRUCT)` : `c[1].a` → meta=INTEGER obj=1 text=1
- `LIST(STRUCT)` : `len(c)` → meta=BIGINT obj=2 text=2
- `MAP` : `c['a']` → meta=INTEGER obj=1 text=1
- `MAP` : `map_keys(c)` → meta=VARCHAR[] obj=[a, b] text=[a, b]
- `MAP` : `cardinality(c)` → meta=UBIGINT obj=2 text=2
- `MAP(STRUCT)` : `c['k'].a` → meta=INTEGER obj=1 text=1
- `MAP(INTEGER,VARCHAR)` : `c[1]` → meta=VARCHAR obj=x text=x
- `UNION(num)` : `union_tag(c)` → meta=ENUM obj=num text=num
- `UNION(num)` : `union_extract(c,'num')` → meta=INTEGER obj=42 text=42
- `UNION(num)` : `c::VARCHAR` → meta=VARCHAR obj=42 text=42
- `UNION(str)` : `union_tag(c)` → meta=ENUM obj=str text=str
- `UNION(str)` : `union_extract(c,'str')` → meta=VARCHAR obj=hi text=hi
- `UNION(nested)` : `union_tag(c)` → meta=ENUM obj=s text=s
- `UNION(nested)` : `union_extract(c,'s')` → meta=STRUCT(a INTEGER) obj={a=9} text={'a': 9}
- `VARIANT(int)` : `variant_typeof(c)` → meta=VARCHAR obj=INT32 text=INT32
- `VARIANT(int)` : `c::VARCHAR` → meta=VARCHAR obj=42 text=42
- `VARIANT(struct)` : `variant_typeof(c)` → meta=VARCHAR obj=OBJECT(a) text=OBJECT(a)
- `VARIANT(struct)` : `c::VARCHAR` → meta=VARCHAR obj={'a': 1} text={'a': 1}
- `VARIANT(list)` : `variant_typeof(c)` → meta=VARCHAR obj=ARRAY(2) text=ARRAY(2)
- `VARIANT(list)` : `c::VARCHAR` → meta=VARCHAR obj=[1, 2] text=[1, 2]
- `VARIANT(string)` : `variant_typeof(c)` → meta=VARCHAR obj=VARCHAR text=VARCHAR
- `VARIANT(string)` : `c::VARCHAR` → meta=VARCHAR obj=txt text=txt
- `LIST(LIST)` : `c[1][2]` → meta=INTEGER obj=2 text=2
