package org.slackerdb.sql;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLReplacer {
    public static ArrayList<QueryReplacerItem> SQLReplaceItems = new ArrayList<>();

    private static boolean isLoaded = false;

    private static void load()
    {
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_roles","duck_catalog.pg_roles",
                        true, true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_shdescription", "duck_catalog.pg_shdescription",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_namespace", "duck_catalog.pg_namespace",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_proc", "duck_catalog.pg_proc",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "pg_catalog.pg_database", "duck_catalog.pg_database",
                        true, true)
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SET\\s+extra_float_digits.*","",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SET\\s+application_name.*","",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SET\\s+client_encoding.*","",true,false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set\\s+search_path\\s*=\\s*([^'\"].*[^'\"])",
                        "set search_path = '$1'",true,false
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

    public static String replaceSQL(String sql) {
        if (!isLoaded) {
            load();
        }
        if (SQLReplaceItems.isEmpty()) {
            return sql;
        }
        sql = sql.trim();
        for (QueryReplacerItem item : SQLReplaceItems) {
            if (item.isSampleReplace())
            {
                String regex = "(?i)" + Pattern.quote(item.getToFind());
                sql = sql.replaceAll(regex, item.getToReplace());
                continue;
            }
            var find = item.getToFind().replaceAll("\r\n", "\n").trim();
            var repl = item.getToReplace().replaceAll("\r\n", "\n").trim();
            if (item.isRegex())
            {
                Pattern pattern = Pattern.compile(find, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(sql);
                if (matcher.matches())
                {
                    sql = matcher.replaceAll(repl);
                }
            }
            else
            {
                if (find.equalsIgnoreCase(sql)) {
                    sql = repl;
                }
            }
        }
        return sql;
    }
}
