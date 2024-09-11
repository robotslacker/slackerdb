package org.slackerdb.server;
import org.slackerdb.configuration.ServerConfiguration;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SlackerCatalog {
    public static void createFakeCatalog(Connection conn) throws SQLException
    {
        List<String> fakeCatalogDDLList = new ArrayList<>();

        // 创建一个默认的新Schema，不使用默认的main
        fakeCatalogDDLList.add("create schema if not exists public");

        // 以下为了解决DBeaver的显示问题
        fakeCatalogDDLList.add("create schema if not exists duck_catalog");
        fakeCatalogDDLList.add("CREATE TABLE if not exists duck_catalog.pg_roles\n" +
                "(\n" +
                "\trolname varchar,\n" +
                "\trolsuper bool NULL,\n" +
                "\trolinherit bool NULL,\n" +
                "\trolcreaterole bool NULL,\n" +
                "\trolcreatedb bool NULL,\n" +
                "\trolcanlogin bool NULL,\n" +
                "\trolreplication bool NULL,\n" +
                "\trolconnlimit int4 NULL,\n" +
                "\trolpassword text NULL,\n" +
                "\trolvaliduntil timestamptz NULL,\n" +
                "\trolbypassrls bool NULL,\n" +
                "\trolconfig varchar,\n" +
                "\t\"oid\" oid NULL\n" +
                ")");
        fakeCatalogDDLList.add("\n" +
                "CREATE TABLE if not exists duck_catalog.pg_shdescription (\n" +
                "    objoid oid,\n" +
                "    classoid oid,\n" +
                "    description text\n" +
                ")");
        fakeCatalogDDLList.add("create or replace view duck_catalog.pg_namespace \n" +
                "as\n" +
                "select oid, schema_name as nspname, 0 as nspowner, null as nspacl, null as description \n" +
                "FROM   duckdb_schemas \n" +
                "where  database_name = getvariable('current_database') \n" +
                "and    schema_name not in ('duck_catalog', 'SCHEMA_NAME_UPPER_FOR_EXT', 'pg_catalog')");
        fakeCatalogDDLList.add("create or replace view duck_catalog.pg_database \n" +
                " as\n" +
                " select oid, oid as datlastsysoid, " +
                "        case when datname='memory' then '" + ServerConfiguration.getData().toLowerCase() + "' else datname end as datname," +
                "        0 as dattablespace, " +
                "   null as datacl, false as datistemplate, true as datallowconn," +
                "   'en_US.utf8' as datcollate,'en_US.utf8' as datctype, -1 as datconnlimit," +
                "  6 as encoding,   \n" +
                " FROM \tpg_catalog.pg_database\n" +
                " where datname not in ('system', 'temp')");
        fakeCatalogDDLList.add("create or replace table duck_catalog.pg_proc\n" +
                "(\n" +
                " oid             int          ,\n" +
                " proname         text         ,\n" +
                " pronamespace    int          ,\n" +
                " proowner        int          ,\n" +
                " prolang         int          ,\n" +
                " procost         real         ,\n" +
                " prorows         real         ,\n" +
                " provariadic     int          ,\n" +
                " prosupport      text         ,\n" +
                " prokind         char         ,\n" +
                " prosecdef       boolean      ,\n" +
                " proleakproof    boolean      ,\n" +
                " proisstrict     boolean      ,\n" +
                " proretset       boolean      ,\n" +
                " provolatile     char         ,\n" +
                " proparallel     char         ,\n" +
                " pronargs        smallint     ,\n" +
                " pronargdefaults smallint     ,\n" +
                " prorettype      int          ,\n" +
                " proargtypes     text         ,\n" +
                " proallargtypes  int          ,\n" +
                " proargmodes     text         ,\n" +
                " proargnames     text         ,\n" +
                " proargdefaults  text         ,\n" +
                " protrftypes     int          ,\n" +
                " prosrc          text         ,\n" +
                " probin          text         ,\n" +
                " prosqlbody      text         ,\n" +
                " proconfig       text         ,\n" +
                " proacl          text\n" +
                ");\n");
        fakeCatalogDDLList.add("CREATE or replace MACRO pg_get_userbyid(a) AS (select 'system')");
        fakeCatalogDDLList.add("CREATE or replace MACRO pg_encoding_to_char(a) AS (select 'UTF8')");
        Statement stmt = conn.createStatement();
        for (String sql : fakeCatalogDDLList)
        {
            stmt.execute(sql);
        }
        stmt.close();
    }
}
