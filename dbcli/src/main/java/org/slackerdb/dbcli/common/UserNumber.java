package org.slackerdb.dbcli.common;

/**
 * 用户写在脚本里的**数值参数**的唯一解析入口：{@code _EXIT 99999999999}、{@code _SSH … port abc}、
 * {@code _SLEEP 1e400}、{@code _JOB CREATE … timeout=NaN}、{@code _SET LINEWIDTH -1} 都从这里过。
 *
 * <h2>为什么统一</h2>
 * <p>以前这些位置各自 {@code Integer.parseInt} / {@code Double.parseDouble}，然后各自拼错误文案，
 * 于是同一个"写错了"有三种说法（{@code expected an integer}、{@code must be a number of seconds}、
 * {@code Invalid sleep time [...]}），而且"是不是正数/是不是有限值/范围对不对"分散在各处，
 * 漏一处就是一个静默放行的坏值（{@code timeout=NaN} 曾经会把作业立刻杀掉）。</p>
 *
 * <h2>两类错误，一种词汇</h2>
 * <ul>
 *   <li>本类抛出的是**统一句式**：{@code Invalid <label> [<value>]: expected <rule>.}
 *       （{@code label} 由调用方给，例如 {@code "SSH port"}、{@code "job wait timeout"}）；</li>
 *   <li>{@code _SET} 的选项有自己**已经写进文档**的句式（{@code Set option [x] requires …}），
 *       它通过 {@link InvalidNumberException#rule()} 取回同一条 {@link Rule} 的描述，
 *       因此"规则词汇"仍只有一份，句子框架沿用各命令的既有契约。</li>
 * </ul>
 *
 * <h2>规则就是校验</h2>
 * <p>{@link Rule} 同时是"文案"和"校验"：解析成功之后还按规则检查范围/符号/有限性，
 * 因此 {@code 1e400}（Infinity）、{@code NaN}、{@code -1} 这类值不可能漏过。</p>
 */
public final class UserNumber {

    /** 数值规则：既是错误文案的一部分，也是校验本身。 */
    public enum Rule {
        INTEGER("an integer"),
        NON_NEGATIVE_INTEGER("a non-negative integer"),
        PORT("an integer in 1..65535"),
        FILE_MODE("an octal permission mode in 0..07777"),
        POSITIVE_SECONDS("a positive number of seconds"),
        NON_NEGATIVE_SECONDS("a non-negative number of seconds");

        private final String description;

        Rule(String description) {
            this.description = description;
        }

        /** 错误文案里的"期望值"描述，例如 {@code a positive number of seconds}。 */
        public String description() {
            return description;
        }
    }

    /** 数值参数不合法：{@code getMessage()} 就是可以直接打印给使用者的那一行。 */
    public static final class InvalidNumberException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final String label;
        private final String value;
        private final Rule rule;

        private InvalidNumberException(String label, String value, Rule rule) {
            super("Invalid " + label + " [" + value + "]: expected " + rule.description() + ".");
            this.label = label;
            this.value = value;
            this.rule = rule;
        }

        /** 参数名（例如 {@code SSH port}）。 */
        public String label() {
            return label;
        }

        /** 使用者写的那一串原文。 */
        public String value() {
            return value;
        }

        /** 未满足的规则（{@code _SET} 用它拼自己的句式）。 */
        public Rule rule() {
            return rule;
        }
    }

    private UserNumber() {
    }

    /**
     * 十进制整数（{@link Rule#INTEGER}）。
     *
     * @throws InvalidNumberException 不是整数或超出 {@code int} 范围
     */
    public static int integer(String label, String value) {
        return integer(label, value, Rule.INTEGER, 10);
    }

    /**
     * 指定进制的整数（SFTP 权限位用 8 进制）。
     *
     * @throws InvalidNumberException 不是该进制下的整数或超出 {@code int} 范围
     */
    public static int integer(String label, String value, Rule rule, int radix) {
        if (rule != Rule.INTEGER && rule != Rule.NON_NEGATIVE_INTEGER && rule != Rule.PORT
                && rule != Rule.FILE_MODE) {
            throw new IllegalArgumentException("not an integer rule: " + rule);
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value, radix);
        } catch (NumberFormatException invalidNumber) {
            throw new InvalidNumberException(label, value, rule);
        }
        if (rule == Rule.NON_NEGATIVE_INTEGER && parsed < 0) {
            throw new InvalidNumberException(label, value, rule);
        }
        if (rule == Rule.PORT && (parsed < 1 || parsed > 65535)) {
            throw new InvalidNumberException(label, value, rule);
        }
        // 八进制权限位：0..07777（含 setuid/setgid/sticky 位），负数不是权限
        if (rule == Rule.FILE_MODE && (parsed < 0 || parsed > 07777)) {
            throw new InvalidNumberException(label, value, rule);
        }
        return parsed;
    }

    /** 非负十进制整数（{@link Rule#NON_NEGATIVE_INTEGER}）。 */
    public static int nonNegativeInteger(String label, String value) {
        return integer(label, value, Rule.NON_NEGATIVE_INTEGER, 10);
    }

    /** 端口：1..65535 的十进制整数（{@link Rule#PORT}）。 */
    public static int port(String label, String value) {
        return integer(label, value, Rule.PORT, 10);
    }

    /**
     * 秒数：必须是**有限**的非负数（{@link Rule#NON_NEGATIVE_SECONDS}）。
     *
     * @throws InvalidNumberException {@code NaN}/{@code Infinity}（{@code 1e400}）、负数、非数字
     */
    public static double nonNegativeSeconds(String label, String value) {
        return seconds(label, value, Rule.NON_NEGATIVE_SECONDS);
    }

    /**
     * 秒数：必须是**有限**的正数（{@link Rule#POSITIVE_SECONDS}），用于 {@code timeout=}。
     *
     * @throws InvalidNumberException {@code NaN}/{@code Infinity}、0、负数、非数字
     */
    public static double positiveSeconds(String label, String value) {
        return seconds(label, value, Rule.POSITIVE_SECONDS);
    }

    private static double seconds(String label, String value, Rule rule) {
        if (rule != Rule.POSITIVE_SECONDS && rule != Rule.NON_NEGATIVE_SECONDS) {
            throw new IllegalArgumentException("not a seconds rule: " + rule);
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException invalidNumber) {
            throw new InvalidNumberException(label, value, rule);
        }
        // 有限性是硬要求：Infinity 换算成纳秒会溢出，NaN 与任何数比较都是 false
        if (!Double.isFinite(parsed)) {
            throw new InvalidNumberException(label, value, rule);
        }
        if (rule == Rule.POSITIVE_SECONDS && !(parsed > 0)) {
            throw new InvalidNumberException(label, value, rule);
        }
        if (rule == Rule.NON_NEGATIVE_SECONDS && parsed < 0) {
            throw new InvalidNumberException(label, value, rule);
        }
        return parsed;
    }
}
