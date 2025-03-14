package org.slackerdb.dbserver.sql;

import org.slackerdb.dbserver.server.DBInstance;
import org.slackerdb.common.utils.LRUCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLReplacer {
    public static final List<QueryReplacerItem> SQLReplaceItems = Collections.synchronizedList(new ArrayList<>());

    private static final LRUCache lruCache = new LRUCache();

    public static void load(DBInstance dbInstance)
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
    }

    public static String removeSQLComments(String sql)
    {
        return sql.replaceAll("--.*?(\\r?\\n|$)","").trim();
    }

    public static List<String> splitSQL(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder currentSegment = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // 处理单引号字符串
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
            }
            // 处理双引号字符串
            else if (c == '"' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inDoubleQuote = !inDoubleQuote;
            }

            // 只有在 **不在引号内部** 时，才把 `;` 作为分隔符
            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                result.add(currentSegment.toString().trim());
                currentSegment.setLength(0); // 清空当前片段
            } else {
                currentSegment.append(c);
            }
        }

        // 添加最后一个 SQL 片段
        if (!currentSegment.isEmpty()) {
            result.add(currentSegment.toString().trim());
        }
        return result;
    }

    public static String replaceSQL(DBInstance pDbInstance, String sql) {
        // 如果有缓存的SQL记录，则直接读取缓存的内容
        String replacedSQL = lruCache.getReplacedSql(sql);
        if (replacedSQL != null)
        {
            if (!sql.equals(replacedSQL))
            {
                pDbInstance.logger.trace("[SERVER] SQL Rewrote: [{}] -> [{}]", sql, replacedSQL);
            }
            return replacedSQL;
        }

        // 按照分号分割，但不要处理带有转义字符的内容
        List<String> sqlItems = splitSQL(sql.trim());
        for (int i=0; i<sqlItems.size(); i++) {
            String oldSql = removeSQLComments(sqlItems.get(i));
            if (oldSql.isEmpty())
            {
                // 空语句，不再执行
                continue;
            }
            String newSql = oldSql.trim();
            for (QueryReplacerItem item : SQLReplaceItems) {
                if (item.sampleReplace())
                {
                    String regex = "(?i)" + Pattern.quote(item.toFind());
                    newSql = newSql.replaceAll(regex, item.toReplace());
                    continue;
                }
                var find = item.toFind().replaceAll("\r\n", "\n").trim();
                var repl = item.toReplace().replaceAll("\r\n", "\n").trim();
                if (item.regex())
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

        // 过滤以 -- 开头的字符串‌
        replacedSQL = String.join("\n", sqlItems);
        lruCache.put(sql, replacedSQL);
        if (!sql.equals(replacedSQL))
        {
            pDbInstance.logger.trace("[SERVER] SQL Rewrote: [{}] -> [{}]", sql, replacedSQL);
        }
        return replacedSQL;
    }
}
