package org.slackerdb.protocol.postgres.sql;

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
                        "SET\\s+extra_float_digits.*","",true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SET\\s+application_name.*","",true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "(.*)::regclass(.*)", "$1$2", true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "SHOW search_path", "SELECT current_setting('search_path') as search_path", false
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "set search_path = (.*),", "set search_path = $1", true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        ".*pg_catalog.pg_get_keywords.*", "",true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "(.*)pg_catalog.pg_get_partkeydef\\(c\\.oid\\)(.*)", "$1null$2",true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        "(.*)pg_catalog.pg_get_expr\\(ad\\.adbin, ad\\.adrelid, true\\)(.*)","$1null$2",true
                )
        );
        SQLReplaceItems.add(
                new QueryReplacerItem(
                        ".*pg_catalog.pg_event_trigger.*", "",true
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
        for (QueryReplacerItem item : SQLReplaceItems) {
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
