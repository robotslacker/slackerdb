package org.slackerdb.dbserver.sql;

import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.LRUCache;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLReplacer {
    public static final ArrayList<QueryReplacerItem> SQLReplaceItems = new ArrayList<>();

    private static boolean isLoaded = false;

    private static final LRUCache lruCache = new LRUCache();

    private static void load(DBInstance dbInstance)
    {
        String   catalogName;

        if (dbInstance.serverConfiguration.getData_Dir().trim().equalsIgnoreCase(":memory:"))
        {
            catalogName = "memory";
        }
        else
        {
            catalogName = dbInstance.serverConfiguration.getData().trim().toLowerCase();
        }
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "format_type(nullif(t.typbasetype, 0), t.typtypmod)","null::TEXT",
                        false, true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_roles",catalogName + ".duck_catalog.pg_roles",
                        true, true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_shdescription", catalogName + ".duck_catalog.pg_shdescription",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_namespace", catalogName + ".duck_catalog.pg_namespace",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_proc", catalogName + ".duck_catalog.pg_proc",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_database", catalogName + ".duck_catalog.pg_database",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_extension", catalogName + ".duck_catalog.pg_extension",
                        true, true)
        );

        // 以下的这几个set， Duck都不支持，但是第三方IDE工具总是查询，所以指向空语句
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+application_name.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+session\\s+character.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+extra_float_digits.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+DateStyle.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+IntervalStyle.*",
                        "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+client_min_messages.*",
                        "",true,false
                )
        );
        // duckdb不支持事务级别
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "START\\s+TRANSACTION.*",
                        "",true,false
                )
        );
        // duckdb的客户端字符集无法设置
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+client_encoding.*",
                        "",true,false
                )
        );
        // PG的search_path需要特殊处理
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+search_path\\s*=\\s*(.*)",
                        "set search_path = $1",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "::regclass", "", false,true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SHOW search_path",
                        "SELECT current_setting('search_path') as search_path", false,
                        true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "show transaction isolation level",
                        "select 'read committed' as transaction_isolation",
                        false,true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "show transaction_isolation",
                        "select 'read committed' as transaction_isolation",
                        false,true
                )
        );

        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SHOW datestyle", "SELECT 'ISO, MDY' as DateStyle",
                        false,true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        ".*pg_catalog.pg_get_keywords.*", "",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "(.*)pg_catalog.pg_get_partkeydef\\(c\\.oid\\)(.*)", "$1null$2",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "(.*)pg_catalog.pg_get_expr\\(ad\\.adbin, ad\\.adrelid, true\\)(.*)","$1null$2",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        ".*pg_catalog.pg_event_trigger.*", "",true,false
                )
        );
        isLoaded = true;
    }

    public static String replaceSQL(DBInstance pDbInstance, String sql) {
        if (!isLoaded) {
            load(pDbInstance);
        }

        // 如果有缓存的SQL记录，则直接读取缓存的内容
        String sourceSQL = sql;
        String replacedSQL = lruCache.getReplacedSql(sql);
        if (replacedSQL != null)
        {
            if (!sourceSQL.equals(replacedSQL))
            {
                pDbInstance.logger.trace("[SERVER] SQL Rewrote: [{}] -> [{}]", sourceSQL, replacedSQL);
            }
            return replacedSQL;
        }

        sql = sql.trim();
        List<String> sqlItems = new ArrayList<>(List.of(sql.split(";")));
        for (int i=0; i<sqlItems.size(); i++) {
            String oldSql = sqlItems.get(i);
            String newSql = oldSql.trim();
            for (QueryReplacerItem item : SQLReplaceItems) {
                if (item.isSampleReplace())
                {
                    String regex = "(?i)" + Pattern.quote(item.getToFind());
                    newSql = newSql.replaceAll(regex, item.getToReplace());
                    continue;
                }
                var find = item.getToFind().replaceAll("\r\n", "\n").trim();
                var repl = item.getToReplace().replaceAll("\r\n", "\n").trim();
                if (item.isRegex())
                {
                    Pattern pattern = Pattern.compile(find, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                    Matcher matcher = pattern.matcher(newSql);
                    if (matcher.matches())
                    {
                        newSql = matcher.replaceAll(repl);
                    }
                }
                else
                {
                    if (find.equalsIgnoreCase(newSql)) {
                        newSql = repl;
                    }
                }
            }
            // PG的查询路径需要特殊处理，以去掉中间的空格和双引号。DUCK不支持空格和双引号
            if (newSql.startsWith("set search_path ="))
            {
                newSql = "set search_path = '" +
                        newSql.replaceAll("set search_path =", "")
                        .replaceAll("[ \"']", "") + "'";
            }
            if (!oldSql.equals(newSql))
            {
                // SQL已经发生了变化
                sqlItems.set(i, newSql);
            }
        }
        replacedSQL = String.join(";", sqlItems);
        lruCache.put(sourceSQL, replacedSQL);
        if (!sourceSQL.equals(replacedSQL))
        {
            pDbInstance.logger.trace("[SERVER] SQL Rewrote: [{}] -> [{}]", sourceSQL, replacedSQL);
        }
        return replacedSQL;
    }
}
