package org.slackerdb.dbserver.sql;

public class SqlCommentStripper {

    private enum State {
        NORMAL,         // 正常代码
        IN_STRING,      // 单引号字符串中
        IN_LINE_COMMENT,  // -- 注释
        IN_BLOCK_COMMENT  // /* */ 注释
    }

    public static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        State state = State.NORMAL;
        int len = sql.length();

        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);

            switch (state) {
                case NORMAL:
                    // 进入字符串
                    if (c == '\'') {
                        state = State.IN_STRING;
                        out.append(c);
                    }
                    // -- 注释开始
                    else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                        state = State.IN_LINE_COMMENT;
                        i++; // skip next -
                    }
                    // /* 注释开始 */
                    else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                        state = State.IN_BLOCK_COMMENT;
                        i++; // skip *
                    }
                    else {
                        out.append(c);
                    }
                    break;

                case IN_STRING:
                    out.append(c);
                    if (c == '\'') {
                        // 字符串内 '' 代表一个单引号，不算结束
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            out.append('\'');
                            i++; // 跳过第二个 '
                        } else {
                            state = State.NORMAL;
                        }
                    }
                    break;

                case IN_LINE_COMMENT:
                    // 遇到换行结束 -- 注释
                    if (c == '\n' || c == '\r') {
                        state = State.NORMAL;
                        out.append(c);
                    }
                    break;

                case IN_BLOCK_COMMENT:
                    // 检查到 */
                    if (c == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                        state = State.NORMAL;
                        i++; // skip '/'
                    }
                    break;
            }
        }
        return out.toString();
    }
}

