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

        // ------------------------------------------------------------------
        // 3. 客户端会调、DuckDB 没有的函数
        // ------------------------------------------------------------------

        // 关系/表大小：DuckDB 没有 pg_relation_size，用 duckdb_tables 的估算值顶替。
        baseDdl.add("""
                create or replace macro duck_catalog.pg_total_relation_size(a) as
                (
                    select estimated_size
                    from   duckdb_tables
                    where  table_oid = a
                    and    database_name = getvariable('current_database')
                )
                """);
        baseDdl.add("""
                create or replace macro duck_catalog.pg_relation_size(a) as
                (
                    select estimated_size
                    from   duckdb_tables
                    where  table_oid = a
                    and    database_name = getvariable('current_database')
                )
                """);
        baseDdl.add("create or replace macro duck_catalog.pg_get_userbyid(a) as (select 'system')");
        baseDdl.add("create or replace macro duck_catalog.pg_encoding_to_char(a) as (select 'UTF8')");
        baseDdl.add("create or replace macro duck_catalog.pg_tablespace_location(a) as (select '')");

        // ------------------------------------------------------------------
        // 4. 类型目录（顺序敏感：pg_namespace 的 oid 来自 pg_type）
        // ------------------------------------------------------------------

        typeDdl.add("create or replace table duck_catalog.pg_type as (select * from pg_catalog.pg_type)");

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
        typeDdl.add("insert into duck_catalog.pg_type by name select 13 as oid, 'int' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 4 as typlen");
        typeDdl.add("insert into duck_catalog.pg_type by name select 14 as oid, 'bigint' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 8 as typlen");
        // oid 19 在部分 DuckDB 版本里已有行，先删再补，保证结果确定（不依赖版本差异）。
        typeDdl.add("delete from duck_catalog.pg_type where oid = 19");
        typeDdl.add("insert into duck_catalog.pg_type by name select 19 as oid, 'timestamp' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 8 as typlen");
        typeDdl.add("insert into duck_catalog.pg_type by name select 25 as oid, 'text' as typname,"
                + " (select min(typnamespace) from pg_catalog.pg_type) as typnamespace, 65535 as typlen");

        // pg_namespace：必须放在 pg_type 之后建。
        // 为什么不能直接用 DuckDB 的 pg_namespace：pg_type 是上面物化的副本，它的 typnamespace 是
        // "建副本时那个 catalog"的命名空间 oid，而客户端查询时 DuckDB 的 pg_namespace.oid 可能是另一个值
        // （1.5.6 实测：副本里 20622，客户端侧 22377），JOIN 会对不上 → 类型名解析不出来。
        // 名字必须落在当前搜索路径里（客户端据此判断 onPath、并据此去找 PGobject 实现类），
        // DuckDB 的默认 schema 就是 'main'。
        typeDdl.add("""
                create or replace view duck_catalog.pg_namespace
                as
                select t.typnamespace as oid,
                       'main' as nspname,
                       0 as nspowner,
                       null as nspacl,
                       null as description
                from   (select distinct typnamespace from duck_catalog.pg_type) t
                union
                -- 用户建的 schema（排除系统 schema）
                select oid,
                       schema_name as nspname,
                       0 as nspowner,
                       null as nspacl,
                       null as description
                from   duckdb_schemas
                where  database_name = current_catalog()
                and    schema_name not in ('duck_catalog', 'SCHEMA_NAME_UPPER_FOR_EXT', 'pg_catalog')
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
