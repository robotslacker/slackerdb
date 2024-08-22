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
                ");\n");
        fakeCatalogDDLList.add("create or replace view duck_catalog.pg_namespace \n" +
                "as\n" +
                "select * FROM pg_catalog.pg_namespace \n" +
                "where nspname not in ('main', 'duck_catalog', 'pg_catalog', 'information_schema');\n");
        Statement stmt = conn.createStatement();
        for (String sql : fakeCatalogDDLList)
        {
            stmt.execute(sql);
        }
        stmt.close();
    }
}
