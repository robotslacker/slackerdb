package org.slackerdb.dbserver.sql;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 把 DuckDB 的异常翻译成 PostgreSQL 的 SQLSTATE（五字符错误码）。
 *
 * <p><b>为什么必须自己映射</b>：DuckDB 的 JDBC 抛的是普通 {@link SQLException}，
 * 实测 {@code getSQLState()} 恒为 {@code null}、{@code getErrorCode()} 恒为 {@code 0}
 * （也没有 {@code DuckDBConstraintException} 之类的子类可以判别）。改造前服务端把
 * {@code String.valueOf(e.getErrorCode())} 直接填进 {@code ErrorResponse} 的 {@code 'C'} 字段，
 * 于是<b>所有错误在客户端看来都是同一个码 {@code "0"}</b>——唯一约束冲突、NOT NULL、
 * 表不存在、语法错误、类型转换失败，全都分不出来。</p>
 *
 * <p>SQLSTATE 是客户端"按错误分类做决策"的唯一机器可读依据：</p>
 * <ul>
 *   <li>重试/并发控制：{@code 40001} 序列化失败、{@code 40P01} 死锁；</li>
 *   <li>幂等/upsert 兜底：{@code 23505} 唯一冲突；</li>
 *   <li>数据完整性分类：{@code 23502}/{@code 23514}/{@code 23503}；</li>
 *   <li>ORM 异常翻译：Spring/Hibernate 的 SQLState 翻译器按五字符码映射到
 *       {@code DataIntegrityViolationException} / {@code BadSqlGrammarException} 等；</li>
 *   <li>连接池健康检查：{@code 08xxx} 连接类错误应丢弃连接而不是放回池子。</li>
 * </ul>
 *
 * <p><b>映射依据是 DuckDB 稳定的消息前缀</b>（{@code Constraint Error:}、{@code Catalog Error:}、
 * {@code Binder Error:}、{@code Parser Error:}、{@code Conversion Error:} …）。前缀识别不出来时
 * 回退到 {@code XX000}（PG 的 internal_error）。这意味着 DuckDB 升级若改动文案，
 * 需要靠回归测试（{@code SqlStateTest}）兜住——这是"透传不了就只能映射"的必然代价。</p>
 */
public final class SqlStateMapper {

    /** PG 的 internal_error：识别不出具体类别时的兜底码。 */
    public static final String INTERNAL_ERROR = "XX000";

    private SqlStateMapper() {
    }

    /**
     * 由异常得到 SQLSTATE。
     *
     * <p>异常自己带了合法的五字符码时优先用它（服务端自己抛的 {@code 0A000}/{@code 22P03}、
     * 或 JDBC 层将来补上的码），否则按消息映射，最后回退 {@link #INTERNAL_ERROR}。</p>
     */
    public static String fromException(SQLException exception) {
        if (exception == null) {
            return INTERNAL_ERROR;
        }
        String state = exception.getSQLState();
        if (isStandard(state)) {
            return state;
        }
        return fromMessage(exception.getMessage());
    }

    /** 由错误消息得到 SQLSTATE。 */
    public static String fromMessage(String message) {
        if (message == null || message.isEmpty()) {
            return INTERNAL_ERROR;
        }
        String text = message.toLowerCase(Locale.ROOT);

        // ---- 约束类 ----
        if (text.contains("constraint error") || text.contains("constraint failed")) {
            if (text.contains("duplicate key") || text.contains("primary key")
                    || text.contains("unique constraint") || text.contains("unique index")) {
                return "23505";   // unique_violation
            }
            if (text.contains("not null")) {
                return "23502";   // not_null_violation
            }
            if (text.contains("check constraint") || text.contains("check failed")) {
                return "23514";   // check_violation
            }
            if (text.contains("foreign key")) {
                return "23503";   // foreign_key_violation
            }
            return "23000";       // integrity_constraint_violation（泛化）
        }

        // ---- 目录（表/列/函数不存在）----
        if (text.contains("catalog error")) {
            if (text.contains("table with name") || text.contains("table \"")
                    || text.contains("does not exist")) {
                return "42P01";   // undefined_table
            }
            if (text.contains("column")) {
                return "42703";   // undefined_column
            }
            return "3F000";       // invalid_schema_name
        }

        // ---- 绑定（名字/类型解析失败）----
        if (text.contains("binder error")) {
            if (text.contains("no function matches") || text.contains("function with name")) {
                return "42883";   // undefined_function
            }
            if (text.contains("column") && (text.contains("not found") || text.contains("does not exist"))) {
                return "42703";   // undefined_column
            }
            if (text.contains("cannot cast") || text.contains("type mismatch")
                    || text.contains("cannot compare")) {
                return "42804";   // datatype_mismatch
            }
            return "42704";       // undefined_object
        }

        // ---- 语法 / 转换 / 数值 ----
        if (text.contains("parser error") || text.contains("syntax error")) {
            return "42601";       // syntax_error
        }
        if (text.contains("conversion error")) {
            return "22P02";       // invalid_text_representation
        }
        if (text.contains("out of range error")) {
            return "22003";       // numeric_value_out_of_range
        }
        if (text.contains("division by zero")) {
            return "22012";       // division_by_zero
        }
        if (text.contains("invalid input error")) {
            return "22023";       // invalid_parameter_value
        }

        // ---- 事务 / 并发 ----
        if (text.contains("transactioncontext error") || text.contains("transaction context error")) {
            if (text.contains("aborted")) {
                return "25P02";   // in_failed_sql_transaction
            }
            return "25000";       // invalid_transaction_state
        }
        if (text.contains("serialization")) {
            return "40001";       // serialization_failure
        }

        // ---- 参数绑定类：Bind 报文与语句要求的参数不一致（是客户端/服务端的协议问题）----
        if (text.contains("parameter index out of bounds")
                || text.contains("parameter count mismatch")
                || text.contains("values were not provided for the following prepared statement")) {
            return "08P01";       // protocol_violation
        }

        // ---- 资源 / 权限 / 系统 ----
        if (text.contains("out of memory")) {
            return "53200";       // out_of_memory
        }
        if (text.contains("permission error") || text.contains("permission denied")) {
            return "42501";       // insufficient_privilege
        }
        if (text.contains("not implemented")) {
            return "0A000";       // feature_not_supported
        }
        if (text.contains("io error")) {
            return "58030";       // io_error
        }
        if (text.contains("internal error")) {
            return INTERNAL_ERROR;
        }
        return INTERNAL_ERROR;
    }

    /**
     * 是否是合法的 PG SQLSTATE：五字符，且只由数字与大写字母组成。
     *
     * <p><b>刻意不排除"纯数字"</b>：PG 里大量合法码就是纯数字（{@code 23505} unique_violation、
     * {@code 22008} datetime_field_overflow、{@code 42601} syntax_error…），
     * 排掉它们会让服务端自己设置的码被丢弃、退化到按消息猜。
     * DuckDB 那种"数字 errorCode"（{@code "0"}）由长度检查挡掉即可。</p>
     */
    private static boolean isStandard(String state) {
        if (state == null || state.length() != 5) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            char c = state.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean upper = c >= 'A' && c <= 'Z';
            if (!digit && !upper) {
                return false;
            }
        }
        return true;
    }
}
