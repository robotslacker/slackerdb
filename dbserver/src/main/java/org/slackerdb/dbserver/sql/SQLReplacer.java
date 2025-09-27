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

    // 定义一个队列，对于相同的SQL不重复进行替换，来提高处理效率
    private static final LRUCache lruCache = new LRUCache();

    // 加载替换规则，部分SQL在PG的通讯协议下需要进行替换，以保证PG协议的正常
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
                        "format_type(nullif(t.typbasetype, 0), t.typtypmod)","t.typname",
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
                        "pg_catalog.pg_type", catalogName + ".duck_catalog.pg_type",
                        false, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "array_upper", "array_length",
                        false, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "typinput='pg_catalog.array_in'", "false",
                        false, true)
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
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_total_relation_size", catalogName + ".duck_catalog.pg_total_relation_size",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_relation_size", catalogName + ".duck_catalog.pg_relation_size",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_extension", catalogName + ".duck_catalog.pg_extension",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_inherits", catalogName + ".duck_catalog.pg_inherits",
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
                        "::regproc", "", false,true
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
                        "(.*)pg_catalog.pg_get_expr\\(.*?\\)(.*)","$1null$2",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        ".*pg_catalog.pg_event_trigger.*", "",true,false
                )
        );
    }

    // 移除SQL中的注释
    public static String removeSQLComments(String sql)
    {
        StringBuilder result = new StringBuilder();
        boolean inString = false;    // 是否在单引号字符串中
        boolean inSingleLineComment = false; // 是否在单行注释中
        boolean inBlockComment = false;      // 是否在块注释中

        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char currentChar = sql.charAt(i);

            // 处理字符串中的转义单引号（例如：'It''s a string'）
            if (inString) {
                if (currentChar == '\'') {
                    // 检查下一个字符是否也是单引号（转义情况）
                    if (i + 1 < length && sql.charAt(i + 1) == '\'') {
                        result.append(currentChar).append(sql.charAt(i + 1));
                        i++; // 跳过下一个字符
                    } else {
                        inString = false; // 结束字符串
                    }
                }
                result.append(currentChar);
                continue;
            }

            // 处理单行注释（--）
            if (inSingleLineComment) {
                if (currentChar == '\n') {
                    inSingleLineComment = false;
                    result.append(currentChar); // 保留换行符
                }
                continue;
            }

            // 处理块注释（/* ... */）
            if (inBlockComment) {
                if (currentChar == '*' && i + 1 < length && sql.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++; // 跳过结束符号'/'
                }
                continue;
            }

            // 检测注释或字符串起始
            if (currentChar == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                inSingleLineComment = true;
                i++; // 跳过下一个'-'
                continue;
            } else if (currentChar == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++; // 跳过下一个'*'
                continue;
            } else if (currentChar == '\'') {
                inString = true; // 进入字符串
            }

            // 非注释内容直接保留
            result.append(currentChar);
        }
        return result.toString();
    }

    // 按照分号分割SQL，并依次执行
    public static List<String> splitSQLWithSemicolon(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder currentSegment = new StringBuilder();
        boolean inSingleQuote = false;

        // 移除SQL中的注释
        sql = removeSQLComments(sql);

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // 处理单引号字符串
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
            }

            // 只有在 **不在引号内部** 时，才把 `;` 作为分隔符
            if (c == ';' && !inSingleQuote) {
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

    // 对SQL进行替换，有些SQL无法通过PG的协议，所以需要进行特殊处理
    public static String replaceSQL(DBInstance pDbInstance, String sql) {
        // 如果有缓存的SQL记录，则直接读取缓存的内容
        String replacedSQL = lruCache.getReplacedSql(sql);
        if (replacedSQL != null)
        {
            if (!sql.equals(replacedSQL))
            {
                pDbInstance.logger.trace("[SERVER][SQL Rewrote]: [{}] -> [{}]", sql, replacedSQL);
            }
            return replacedSQL;
        }

        // 按照分号分割，但不要处理带有转义字符的内容
        List<String> sqlItems = splitSQLWithSemicolon(sql.trim());
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
            pDbInstance.logger.trace("[SERVER][SQL Rewrote]: [{}] -> [{}]", sql, replacedSQL);
        }
        return replacedSQL;
    }
}
