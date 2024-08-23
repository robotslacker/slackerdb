package org.slackerdb.server;
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
                "select * FROM pg_catalog.pg_namespace \n" +
                "where nspname not in ('main', 'duck_catalog', 'pg_catalog', 'information_schema')");
        fakeCatalogDDLList.add("create or replace view duck_catalog.pg_database \n" +
                " as\n" +
                " select oid, oid as datlastsysoid,datname, 0 as dattablespace, " +
                "   null as datacl, false as datistemplate, true as datallowconn," +
                "   'en_US.utf8' as datcollate,'en_US.utf8' as datctype, -1 as datconnlimit," +
                "  6 as encoding,   \n" +
                " FROM \tpg_catalog.pg_database\n" +
                " where datname not in ('system', 'temp')");
        fakeCatalogDDLList.add("create table duck_catalog.pg_proc\n" +
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
        fakeCatalogDDLList.add("CREATE MACRO pg_get_userbyid(a) AS (select 'system')");
        fakeCatalogDDLList.add("CREATE MACRO pg_encoding_to_char(a) AS (select 'UTF8')");
        Statement stmt = conn.createStatement();
        for (String sql : fakeCatalogDDLList)
        {
            stmt.execute(sql);
        }
        stmt.close();
    }
}
