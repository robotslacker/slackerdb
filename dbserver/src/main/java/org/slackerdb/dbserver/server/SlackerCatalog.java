package org.slackerdb.dbserver.server;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SlackerCatalog {
    public static void createFakeCatalog(DBInstance pDbInstance, Connection conn) throws SQLException
    {
        List<String> fakeCatalogDDLList = new ArrayList<>();

        // 以下为了解决DBeaver的显示问题
        fakeCatalogDDLList.add("create schema if not exists duck_catalog");
        fakeCatalogDDLList.add("""
                CREATE or replace TABLE duck_catalog.pg_roles
                (
                    rolname varchar,
                    rolsuper bool NULL,
                    rolinherit bool NULL,
                    rolcreaterole bool NULL,
                    rolcreatedb bool NULL,
                    rolcanlogin bool NULL,
                    rolreplication bool NULL,
                    rolconnlimit int4 NULL,
                    rolpassword text NULL,
                    rolvaliduntil timestamptz NULL,
                    rolbypassrls bool NULL,
                    rolconfig varchar,
                    "oid" oid NULL
                )""");
        fakeCatalogDDLList.add("""                
                CREATE TABLE if not exists duck_catalog.pg_shdescription (
                    objoid oid,
                    classoid oid,
                    description text
                )""");
        fakeCatalogDDLList.add("""
                CREATE or replace MACRO duck_catalog.pg_total_relation_size(a) AS
                (
                	select 	estimated_size
                	from 	duckdb_tables
                	where  	table_oid = a
                	and 	database_name = getvariable('current_database')
                )
                """);
        fakeCatalogDDLList.add("""
                CREATE or replace MACRO duck_catalog.pg_relation_size(a) AS
                (
                	select 	estimated_size
                	from 	duckdb_tables
                	where  	table_oid = a
                	and 	database_name = getvariable('current_database')
                )
                """);
        fakeCatalogDDLList.add("create or replace view duck_catalog.pg_database " +
                " as" +
                " select oid, oid as datlastsysoid, " +
                "        case when datname='memory' then '" + pDbInstance.serverConfiguration.getData().toLowerCase() + "' else datname end as datname," +
                "        0 as dattablespace, " +
                "   null as datacl, false as datistemplate, true as datallowconn," +
                "   'en_US.utf8' as datcollate,'en_US.utf8' as datctype, -1 as datconnlimit," +
                "  6 as encoding,   " +
                " FROM  pg_catalog.pg_database" +
                " where datname not in ('system', 'temp')");
        fakeCatalogDDLList.add("""
                create or replace view duck_catalog.pg_namespace
                as
                select oid, schema_name as nspname, 0 as nspowner, null as nspacl, null as description
                FROM   duckdb_schemas
                where  database_name = current_catalog()
                and    schema_name not in ('duck_catalog', 'SCHEMA_NAME_UPPER_FOR_EXT', 'pg_catalog')
                """);
        fakeCatalogDDLList.add("""
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
                );
                """);
        fakeCatalogDDLList.add("""
                CREATE or replace TABLE  duck_catalog.pg_extension
                (
                 oid               int,
                 extname           text,
                 extowner          int,
                 extnamespace      int,
                 extrelocatable    bool,
                 extversion        text,
                 extconfig         int,
                 extcondition      text
                );
                """);
        fakeCatalogDDLList.add("""
                create or replace table duck_catalog.pg_inherits
                (
                	inhrelid   int,
                	inhparent   int,
                	inhseqno    int
                )
                """);
        fakeCatalogDDLList.add("CREATE OR REPLACE MACRO duck_catalog.pg_get_userbyid(a) AS (select 'system')");
        fakeCatalogDDLList.add("CREATE OR REPLACE MACRO duck_catalog.pg_encoding_to_char(a) AS (select 'UTF8')");
        fakeCatalogDDLList.add("CREATE OR REPLACE MACRO duck_catalog.pg_tablespace_location(a) AS (select '')");
        Statement stmt = conn.createStatement();
        for (String sql : fakeCatalogDDLList)
        {
            stmt.execute(sql);
        }
        stmt.close();
    }
}
