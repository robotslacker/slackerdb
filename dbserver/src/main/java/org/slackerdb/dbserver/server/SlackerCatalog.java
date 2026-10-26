package org.slackerdb.dbserver.server;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 伪造一份"够第三方客户端用"的 PostgreSQL 系统目录（{@code duck_catalog} schema）。
 *
 * <p><b>为什么需要</b>：DBeaver / pgjdbc / psql 这类客户端会直接查 PG 的系统目录
 * （{@code pg_type}、{@code pg_namespace}、{@code pg_roles}…）。DuckDB 自带的
 * {@code pg_catalog} 覆盖不全、形状也与 PG 不一致，客户端会报错或显示异常。
 * 于是启动时建一个 {@code duck_catalog} schema 放这些"补丁关系"，
 * 再由 {@code SQLReplacer} 把客户端 SQL 里的 {@code pg_catalog.X} 改写成
 * {@code <catalog>.duck_catalog.X}（替换表见 SQLReplacer）。</p>
 *
 * <p><b>两条硬规则</b>（改这个文件最容易踩的两件事）：</p>
 * <ol>
 *   <li>整套对象都要建在 {@code use memory} 之下 —— 它们属于 memory 这个 catalog，
 *       客户端连到别的库时靠 SQLReplacer 的改写指过来。</li>
 *   <li>{@code oid} 必须自洽：客户端用
 *       {@code pg_type JOIN pg_namespace ON typnamespace = n.oid} 反查类型名，
 *       拿不到名字就在 {@code PgResultSet.initSqlType()} 的 {@code castNonNull} 上直接抛异常
 *       （整列连 {@code getColumnTypeName()} 都不可用）。所以 {@code pg_namespace} 的 oid
 *       取自 {@code pg_type.typnamespace}，且必须等 {@code pg_type} 建好之后再建
 *       —— 这就是下面把 DDL 分成 base / type 两批的原因，不要合并回一个列表。</li>
 * </ol>
 *
 * <p><b>这里的关系是"够用"的近似，不追求与 PG 全等</b>：{@code pg_proc} 刻意留空、
 * {@code pg_database} 的 collate/encoding 是常量。每个对象上面都注明了它补的是哪个缺口。</p>
 */
public final class SlackerCatalog {

    /**
     * 返回 {@code bigint} 的 PG 统计函数（名字里的 {@code pg_stat_get_} 之后那一段）。
     *
     * <p>DuckDB 没有任何运行期统计，但客户端展示"扫描次数 / 增删改行数 / 元组数 / 读写块数 /
     * VACUUM、ANALYZE 次数"时会直接调这些函数。名字取自 PG 的 {@code pg_stat_all_tables}、
     * {@code pg_stat_all_indexes}、{@code pg_statio_all_tables} 三张视图所用到的函数，
     * 覆盖客户端统计面板的实际取数面。</p>
     */
    public static final List<String> PG_STAT_COUNTER_FUNCTIONS = List.of(
            "numscans",            // seq_scan / idx_scan
            "tuples_returned",     // seq_tup_read / idx_tup_read
            "tuples_fetched",      // idx_tup_fetch
            "tuples_inserted",     // n_tup_ins
            "tuples_updated",      // n_tup_upd
            "tuples_deleted",      // n_tup_del
            "tuples_hot_updated",  // n_tup_hot_upd
            "live_tuples",         // n_live_tup
            "dead_tuples",         // n_dead_tup
            "mod_since_analyze",   // n_mod_since_analyze
            "ins_since_vacuum",    // n_ins_since_vacuum
            "blocks_fetched",      // heap_blks_read / idx_blks_read
            "blocks_hit",          // heap_blks_hit / idx_blks_hit
            "vacuum_count",
            "autovacuum_count",
            "analyze_count",
            "autoanalyze_count");

    /** 返回 {@code timestamptz} 的 PG 统计函数（"最后一次 VACUUM / ANALYZE 是什么时候"）。 */
    public static final List<String> PG_STAT_TIME_FUNCTIONS = List.of(
            "last_vacuum_time",
            "last_autovacuum_time",
            "last_analyze_time",
            "last_autoanalyze_time");

    private SlackerCatalog() {
    }

    /**
     * 建好整套 fake catalog。任何一条 DDL 失败都会带上出错的语句抛出
     * （否则上层只会看到 "Attach failed" 这种无从下手的报错）。
     */
    public static void createFakeCatalog(DBInstance dbInstance, Connection conn) throws SQLException {
        // 不依赖 pg_type 的部分
        List<String> baseDdl = new ArrayList<>();
        // 依赖 pg_type 的部分：顺序敏感，必须在 pg_type 建好之后执行
        List<String> typeDdl = new ArrayList<>();

        // ------------------------------------------------------------------
        // 0. 环境
        // ------------------------------------------------------------------
        baseDdl.add("use memory");
        baseDdl.add("create schema if not exists duck_catalog");

        // ------------------------------------------------------------------
        // 1. 角色 / 库 / 描述
        // ------------------------------------------------------------------

        // DuckDB 没有 pg_roles（实测 pg_catalog.pg_roles 不存在），而 DBeaver 启动就会查它。
        // 只建结构不填数据：客户端看到"没有角色"也不会报错。
        baseDdl.add("""
                create or replace table duck_catalog.pg_roles
                (
                    rolname        varchar,
                    rolsuper       bool,
                    rolinherit     bool,
                    rolcreaterole  bool,
                    rolcreatedb    bool,
                    rolcanlogin    bool,
                    rolreplication bool,
                    rolconnlimit   int4,
                    rolpassword    text,
                    rolvaliduntil  timestamptz,
                    rolbypassrls   bool,
                    rolconfig      varchar,
                    "oid"          oid
                )
                """);

        // 客户端的"共享描述"查询会碰这张表，DuckDB 没有对应关系：建空结构即可。
        baseDdl.add("""
                create or replace table duck_catalog.pg_shdescription
                (
                    objoid      oid,
                    classoid    oid,
                    description text
                )
                """);

        // pg_database 的真实目录 DuckDB 有，但它暴露的库名是内部的 'memory'，
        // 这里换成配置里的 data 名，让客户端显示的库名与连接串一致。
        // 其余列（collate/encoding/connlimit…）是给客户端看的常量。
        baseDdl.add("""
                create or replace view duck_catalog.pg_database
                as
                select oid,
                       oid as datlastsysoid,
                       case when datname = 'memory' then '%s' else datname end as datname,
                       0 as dattablespace,
                       null as datacl,
                       false as datistemplate,
                       true as datallowconn,
                       'en_US.utf8' as datcollate,
                       'en_US.utf8' as datctype,
                       -1 as datconnlimit,
                       6 as encoding
                from   pg_catalog.pg_database
                where  datname not in ('system', 'temp')
                """.formatted(dbInstance.serverConfiguration.getData().toLowerCase()));

        // ------------------------------------------------------------------
        // 2. 函数 / 扩展 / 继承
        // ------------------------------------------------------------------

        // 刻意留空：只满足"关系存在 + 列齐全"，不承诺函数列表准确。
        // （DuckDB 真实的 pg_catalog.pg_proc 有 2900+ 行，但列形状与 PG 不一致。）
        baseDdl.add("""
                create or replace table duck_catalog.pg_proc
                (
                 oid             int          ,
                 proname         text         ,
                 pronamespace    int          ,
                 proowner        int          ,
                 prolang         int          ,
                 procost         real         ,
                 prorows         real         ,
                 provariadic     int          ,
                 prosupport      text         ,
                 prokind         char         ,
                 prosecdef       boolean      ,
                 proleakproof    boolean      ,
                 proisstrict     boolean      ,
                 proretset       boolean      ,
                 provolatile     char         ,
                 proparallel     char         ,
                 pronargs        smallint     ,
                 pronargdefaults smallint     ,
                 prorettype      int          ,
                 proargtypes     text         ,
                 proallargtypes  int          ,
                 proargmodes     text         ,
                 proargnames     text         ,
                 proargdefaults  text         ,
                 protrftypes     int          ,
                 prosrc          text         ,
                 probin          text         ,
                 prosqlbody      text         ,
                 proconfig       text         ,
                 proacl          text
                )
                """);

        baseDdl.add("""
                create or replace table duck_catalog.pg_extension
                (
                 oid               int,
                 extname           text,
                 extowner          int,
                 extnamespace      int,
                 extrelocatable    bool,
                 extversion        text,
                 extconfig         int,
                 extcondition      text
                )
                """);

        baseDdl.add("""
                create or replace table duck_catalog.pg_inherits
                (
                 inhrelid   int,
                 inhparent  int,
                 inhseqno   int
                )
                """);

        // pg_aggregate：客户端枚举聚合函数时按 PG 的写法
        //   pg_proc p JOIN pg_aggregate a ON a.aggfnoid = p.oid
        // 取聚合标记；DuckDB 没有这张关系，整条查询直接报
        //   Catalog Error: Table with name pg_aggregate does not exist
        // 本地 pg_proc 本来就是空表（见上面），所以这里也只建**空表**：
        // 客户端会得到"没有聚合函数"的空结果，而不是一条报错。
        baseDdl.add("""
                create or replace table duck_catalog.pg_aggregate
                (
                 aggfnoid        oid,
                 aggkind         char,
                 aggnumdirectargs smallint,
                 aggtransfn      text,
                 aggfinalfn      text,
                 aggcombinefn    text,
                 aggserialfn     text,
                 aggdeserialfn   text,
                 aggmtransfn     text,
                 aggminvtransfn  text,
                 aggmfinalfn     text,
                 aggfinalextra   bool,
                 aggmfinalextra  bool,
                 aggfinalmodify  char,
                 aggmfinalmodify char,
                 aggsortop       oid,
                 aggtranstype    oid,
                 aggtransspace   integer,
                 aggmtranstype   oid,
                 aggmtransspace  integer,
                 agginitval      text,
                 aggminitval     text
                )
                """);

        // pg_stat_activity：同样只建**空表**（列与 PG 16 对齐）。
        //
        // 为什么不做成实时的：DuckDB 的后端连接看不到 Java 侧的 PG 会话表，要做实时只能
        // 在每条查询的改写阶段现造 SQL，或者维护一张随登录/退出增删的表 —— 前者把改写器
        // 变成兼容性运行时，后者要在连接生命周期里做写事务（失败模式、并发、事务归属都很难
        // 交代）。这两样都不划算：会话的观测与终止已经有明确的权威入口 ——
        //   * GET  /status                  （sessions 列表：pid/库/用户/应用名/正在执行的 SQL）
        //   * POST /sessions/{sessionId}/kill
        //   * SQL  KILL <sessionId>
        // 所以 PG 侧的这张关系保持零行：客户端面板显示"没有会话"，而不是整条查询失败。
        baseDdl.add("""
                create or replace table duck_catalog.pg_stat_activity
                (
                 datid            oid,
                 datname          text,
                 pid              integer,
                 leader_pid       integer,
                 usesysid         oid,
                 usename          text,
                 application_name text,
                 client_addr      text,
                 client_hostname  text,
                 client_port      integer,
                 backend_start    timestamptz,
                 xact_start       timestamptz,
                 query_start      timestamptz,
                 state_change     timestamptz,
                 wait_event_type  text,
                 wait_event       text,
                 state            text,
                 backend_xid      bigint,
                 backend_xmin     bigint,
                 query_id         bigint,
                 query            text,
                 backend_type     text
                )
                """);

        // ------------------------------------------------------------------
        // 3. 客户端会调、DuckDB 没有的函数
        // ------------------------------------------------------------------

        // 关系/表大小：DuckDB 没有 pg_relation_size，用 duckdb_tables 的估算值顶替。
        // 参数既可能是 OID（driver / postgres_fdw 翻译后的形式），也可能是**表名**
        // （客户端直接写 pg_relation_size('t')）：两种都支持，名字可以带 schema 前缀。
        baseDdl.add("""
                create or replace macro duck_catalog.pg_total_relation_size(a) as
                (
                    select estimated_size
                    from   duckdb_tables
                    where  database_name = getvariable('current_database')
                    and    (table_oid = try_cast(a as bigint)
                            or (schema_name || '.' || table_name) = try_cast(a as varchar)
                            or table_name = try_cast(a as varchar))
                )
                """);
        baseDdl.add("""
                create or replace macro duck_catalog.pg_relation_size(a) as
                (
                    select estimated_size
                    from   duckdb_tables
                    where  database_name = getvariable('current_database')
                    and    (table_oid = try_cast(a as bigint)
                            or (schema_name || '.' || table_name) = try_cast(a as varchar)
                            or table_name = try_cast(a as varchar))
                )
                """);
        baseDdl.add("create or replace macro duck_catalog.pg_get_userbyid(a) as (select 'system')");
        baseDdl.add("create or replace macro duck_catalog.pg_encoding_to_char(a) as (select 'UTF8')");
        baseDdl.add("create or replace macro duck_catalog.pg_tablespace_location(a) as (select '')");
        // pg_table_size：psql 的 `\dt+`/`\di+` 用它算 Size 列。
        // DuckDB 没有等价函数，复用 pg_relation_size 的估算语义即可（客户端只是显示大小）。
        baseDdl.add("""
                create or replace macro duck_catalog.pg_table_size(a) as
                (
                    select estimated_size
                    from   duckdb_tables
                    where  database_name = getvariable('current_database')
                    and    (table_oid = try_cast(a as bigint)
                            or (schema_name || '.' || table_name) = try_cast(a as varchar)
                            or table_name = try_cast(a as varchar))
                )
                """);

        // 运行期统计函数：DuckDB 既没有 pg_stat_* 视图，也没有 pg_stat_get_* 函数
        // （pg_catalog 下一个都没有）。客户端展示表/索引的统计面板时直接调
        //   pg_catalog.pg_stat_get_numscans(i.indexrelid)
        // 旧行为是整条元数据查询报
        //   Catalog Error: Scalar Function with name pg_stat_get_numscans does not exist
        // 面板直接空白 —— 缺的不是一列数据，而是整条查询。
        //
        // 这里为常用的一组建"空壳宏"，值一律为 NULL（= 未知）。**不要返回 0**：
        // 在 PG 里 0 表示"确实一次都没有发生过"，会被客户端和人读成"这个索引从未被使用"，
        // 据此删索引就是被编出来的数字误导了；NULL 才是"SlackerDB 没有这项统计"的诚实表示
        // （客户端要显示 0 可以自己 coalesce，那是它的选择）。
        //
        // 返回类型必须与 PG 一致（bigint / timestamptz），否则客户端按列类型解码会出错；
        // SQLReplacer 按命名空间前缀改写，所以以后补一个名字只需加进上面两个列表。
        for (String statistic : PG_STAT_COUNTER_FUNCTIONS) {
            baseDdl.add("create or replace macro duck_catalog.pg_stat_get_" + statistic
                    + "(a) as (select cast(null as bigint))");
        }
        for (String statistic : PG_STAT_TIME_FUNCTIONS) {
            baseDdl.add("create or replace macro duck_catalog.pg_stat_get_" + statistic
                    + "(a) as (select cast(null as timestamptz))");
        }

        // ------------------------------------------------------------------
        // 4. 类型目录（pg_type 是 view，底层是 pg_type_raw）
        // ------------------------------------------------------------------

        typeDdl.add("create or replace table duck_catalog.pg_type_raw as (select * from pg_catalog.pg_type)");

        // 为什么要补这几行：DuckDB 的 pg_attribute.atttypid 存的**不是 PG 的 oid，而是它自己的内部类型序号**。
        // 客户端要按 atttypid 反查类型名（DatabaseMetaData.getColumns 的 TYPE_NAME），于是会出现两种结果
        // （1.5.6 实测，左=列类型，右=当前能解析出的名字）：
        //   序号在 DuckDB 的 pg_type 里不存在 → 名字是 null：
        //     int=13、bigint=14、varchar=25、timestamp=19、date=15、boolean=10、interval=27
        //   序号与 PG 的某个 oid 撞车 → 解析成**错误的**类型名：
        //     double=23→int4、time=16→bool、decimal=21→int2
        // 下面只补最常用的 4 个（int/bigint/timestamp/varchar），让它们至少能显示出名字。
        // 名字按 PG 语义取：25=text 与 PG 一致；13/14 在 PG 里并不存在，取 'int'/'bigint' 只为客户端显示得出名字。
        //
        // 已知边界（本文件不解决，别把这里当成"类型名一定准"）：
        // double/time/decimal 这类撞车行是 DuckDB 真实 pg_type 里的行，补不了；
        // date/boolean/interval 等没补的仍然是 null。要彻底修得改 pg_attribute 侧的 atttypid 映射。
        // typnamespace 取实时值 —— 不要写死：旧版 DuckDB 的 main 是 548，1.5.6 是别的值。
        typeDdl.add("insert into duck_catalog.pg_type_raw by name select 13 as oid, 'int' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 4 as typlen");
        typeDdl.add("insert into duck_catalog.pg_type_raw by name select 14 as oid, 'bigint' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 8 as typlen");
        // oid 19 在部分 DuckDB 版本里已有行，先删再补，保证结果确定（不依赖版本差异）。
        typeDdl.add("delete from duck_catalog.pg_type_raw where oid = 19");
        typeDdl.add("insert into duck_catalog.pg_type_raw by name select 19 as oid, 'timestamp' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 8 as typlen");
        typeDdl.add("insert into duck_catalog.pg_type_raw by name select 25 as oid, 'text' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 65535 as typlen");

        // pg_type 必须做成 view：原生 pg_type.typnamespace 指向内存库 main（实测 20736），
        // 而用户表在当前实例库的 main（实测 22996）。把 typnamespace 统一改到当前库 main，
        // 既让 DuckDB postgres 扩展的 catalog JOIN 成立，也让客户端
        // pg_type JOIN pg_namespace 的 qualified 查询成立，同时 pg_namespace 里不需要
        // 伪造 pg_catalog 行（DatabaseMetaData.getSchemas() 不会多出用户没建过的 schema）。
        typeDdl.add("""
                create or replace view duck_catalog.pg_type
                as
                select * replace (
                    (select oid from duckdb_schemas()
                     where database_name = current_database()
                     and   schema_name = 'main') as typnamespace
                )
                from duck_catalog.pg_type_raw
                """);

        // pg_namespace：当前实例库的真实 schema。
        // 必须用 duckdb_schemas() 表函数：不带括号的 duckdb_schemas 是绑定在 memory 上的
        // 视图，从实例库查询时看不到 sdb.main。
        typeDdl.add("""
                create or replace view duck_catalog.pg_namespace
                as
                select oid,
                       schema_name as nspname,
                       0 as nspowner,
                       null as nspacl,
                       null as description
                from   duckdb_schemas()
                where  database_name = current_database()
                and    schema_name not in ('duck_catalog', 'SCHEMA_NAME_UPPER_FOR_EXT',
                                           'pg_catalog', 'information_schema')
                """);

        // pg_attribute：把 DuckDB 的内部类型序号翻译成 PG 的 type oid。
        //
        // DuckDB 的 pg_attribute.atttypid 不是 PG oid：INTEGER=13、VARCHAR=25、
        // DECIMAL=21、TIMESTAMP=19。PG 的 pg_type 里对应的是 int4=23、varchar=1043、
        // numeric=1700、timestamp=1114。二者直接 JOIN 会一行都对不上，客户端（包括
        // DuckDB postgres 扩展）看到的表就没有列。
        //
        // DECIMAL 的 atttypmod 也要翻译：DuckDB 是 precision*1000+scale（10,2 -> 10002），
        // PG 是 ((precision << 16) | scale) + 4（655366）。不翻译会让 DuckDB 把
        // DECIMAL(10,2) 读成 DECIMAL(?,14)，二进制 COPY 的数值整体缩小 10^12。
        // VARCHAR/CHAR 在 DuckDB 里没有长度概念，统一报 65535（PG 的 typmod 上限）。
        typeDdl.add("""
                create or replace view duck_catalog.pg_attribute
                as
                select * replace (
                    case atttypid
                        when 10 then 16    -- BOOLEAN    -> bool
                        when 11 then 21    -- TINYINT    -> int2
                        when 12 then 21    -- SMALLINT   -> int2
                        when 13 then 23    -- INTEGER    -> int4
                        when 14 then 20    -- BIGINT     -> int8
                        when 15 then 1082  -- DATE       -> date
                        when 16 then 1083  -- TIME       -> time
                        when 17 then 1114  -- TIMESTAMP_S
                        when 18 then 1114  -- TIMESTAMP_MS
                        when 19 then 1114  -- TIMESTAMP
                        when 20 then 1114  -- TIMESTAMP_NS
                        when 21 then 1700  -- DECIMAL    -> numeric
                        when 22 then 700   -- FLOAT      -> float4
                        when 23 then 701   -- DOUBLE     -> float8
                        when 24 then 1042  -- CHAR       -> bpchar
                        when 25 then 1043  -- VARCHAR    -> varchar
                        when 26 then 17    -- BLOB       -> bytea
                        when 27 then 1186  -- INTERVAL   -> interval
                        when 28 then 1700  -- HUGEINT    -> numeric
                        when 29 then 2950  -- UUID       -> uuid
                        else atttypid
                    end as atttypid,
                    case
                        when atttypid in (21, 28) and atttypmod >= 0
                        then (((atttypmod / 1000)::BIGINT << 16) | (atttypmod % 1000))::INTEGER + 4
                        when atttypid in (24, 25) then 65535
                        else atttypmod
                    end as atttypmod
                )
                from pg_catalog.pg_attribute
                """);

        apply(conn, baseDdl);
        apply(conn, typeDdl);
        conn.commit();

        dbInstance.logger.debug("[SERVER][CATALOG    ] fake catalog ready: {} DDL statements",
                baseDdl.size() + typeDdl.size());
    }

    /** 顺序执行一批 DDL；失败时把出错的语句带进异常，便于定位（分两批调用以保证依赖顺序）。 */
    private static void apply(Connection conn, List<String> ddlList) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String sql : ddlList) {
                try {
                    stmt.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException("Fake catalog DDL failed: "
                            + sql.trim().replaceAll("\\s+", " "), e);
                }
            }
        }
    }
}
