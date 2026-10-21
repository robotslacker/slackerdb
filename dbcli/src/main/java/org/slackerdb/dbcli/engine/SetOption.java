package org.slackerdb.dbcli.engine;

import org.slackerdb.dbcli.common.UserNumber;
import org.slackerdb.dbcli.sql.RenderOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code _SET} 的**选项表**：默认值、取值类型、合法取值，以及"这个值最终落到哪一层"都只在这里。
 *
 * <h2>为什么是枚举</h2>
 * <p>以前一个选项要在四处登记：{@code ScriptContext} 里的默认值表、类型表、打印顺序表，以及
 * {@code ScriptRunner.applyPrintOptions()} 的分派 switch。加一个选项得改四处，漏掉"落地点"就是
 * 一个静默 bug：{@code _SET} 看起来生效了（选项表里也变了），实际没有任何代码消费它。
 * 现在一个常量就是全部：默认值/类型/校验/落地点/打印顺序都在同一个声明里。</p>
 *
 * <h2>两段式：{@link #validate} + {@link #apply}</h2>
 * <p>先校验（错误文案与命令行口径一致，带用户写的原值），再落到输出层或执行层。
 * 这样"选项表里的值"与"真正生效的值"永远是同一个（规范化的）字符串。</p>
 */
public enum SetOption {

    ECHO("echo", Kind.BOOLEAN, "on"),
    FEEDBACK("feedback", Kind.BOOLEAN, "on"),
    TERMOUT("termout", Kind.BOOLEAN, "on"),
    TIMING("timing", Kind.BOOLEAN, "off"),
    TIME("time", Kind.BOOLEAN, "off"),
    OUTFORMAT("outformat", Kind.CHOICE, "table", List.of("table", "vertical", "csv")),
    HEADER("header", Kind.BOOLEAN, "on"),
    LINEWIDTH("linewidth", Kind.INTEGER, "200"),
    COLWIDTH("colwidth", Kind.INTEGER, "40"),
    DIFF_REPORT_HTML("diff_report_html", Kind.BOOLEAN, "off"),
    AUTOCOMMIT("autocommit", Kind.BOOLEAN, "on"),
    TIMEOUT("timeout", Kind.INTEGER, "0");

    /** 取值类型：布尔（{@code ON/OFF}）、非负整数、还是枚举取值。 */
    public enum Kind {
        BOOLEAN,
        INTEGER,
        CHOICE
    }

    /** 选项取值非法时抛出：{@code getMessage()} 就是可以直接打印给使用者的那一行。 */
    public static final class InvalidValueException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidValueException(String message) {
            super(message);
        }
    }

    private final String optionName;
    private final Kind kind;
    private final String defaultValue;
    private final List<String> choices;

    SetOption(String optionName, Kind kind, String defaultValue) {
        this(optionName, kind, defaultValue, List.of());
    }

    SetOption(String optionName, Kind kind, String defaultValue, List<String> choices) {
        this.optionName = optionName;
        this.kind = kind;
        this.defaultValue = defaultValue;
        this.choices = List.copyOf(choices);
    }

    /** 选项名（小写，就是 {@code _SET} 表里打印的那一列）。 */
    public String optionName() {
        return optionName;
    }

    public Kind kind() {
        return kind;
    }

    /** 默认值（{@code _SET} 表的 "Default value" 列）。 */
    public String defaultValue() {
        return defaultValue;
    }

    /** 枚举型选项的合法取值（其它类型为空表）。 */
    public List<String> choices() {
        return choices;
    }

    /** 按名字查（不区分大小写）；未知返回 null。 */
    public static SetOption of(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        for (SetOption option : values()) {
            if (option.optionName.equals(key)) {
                return option;
            }
        }
        return null;
    }

    /** 全部选项名，按声明顺序（{@code _SET} 无参时的打印顺序）。 */
    public static List<String> names() {
        List<String> names = new ArrayList<>(values().length);
        for (SetOption option : values()) {
            names.add(option.optionName);
        }
        return List.copyOf(names);
    }

    /**
     * 校验并规范化 {@code _SET <name> <value>} 的取值。
     *
     * @return 规范化后的值（布尔与枚举是小写，整数去掉多余的 0）
     * @throws InvalidValueException 取值非法（消息即错误行）
     */
    public String validate(String raw) {
        String value = raw == null ? "" : raw.strip();
        return switch (kind) {
            case BOOLEAN -> {
                if (!"on".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value)) {
                    throw new InvalidValueException("Set option [" + optionName
                            + "] requires ON or OFF" + (value.isEmpty() ? "." : ", got [" + value + "]."));
                }
                yield value.toLowerCase(Locale.ROOT);
            }
            case INTEGER -> {
                try {
                    yield String.valueOf(UserNumber.nonNegativeInteger(optionName, value));
                } catch (UserNumber.InvalidNumberException invalidNumber) {
                    // _SET 的句式已写进文档（Set option [x] requires <规则>, got [值]），
                    // 规则词汇取自 UserNumber：数值规则只有一份。
                    throw new InvalidValueException("Set option [" + optionName + "] requires "
                            + invalidNumber.rule().description()
                            + (value.isEmpty() ? "." : ", got [" + value + "]."));
                }
            }
            case CHOICE -> {
                String normalized = value.toLowerCase(Locale.ROOT);
                if (!choices.contains(normalized)) {
                    throw new InvalidValueException("Set option [" + optionName + "] requires "
                            + joinChoices() + (value.isEmpty() ? "." : ", got [" + value + "]."));
                }
                yield normalized;
            }
        };
    }

    /**
     * 把（已经 {@link #validate 校验过}、并且已写进选项表的）取值落到真正消费它的那一层：
     * 打印开关 → {@link ScriptOutput}，格式/宽度 → {@link RenderOptions}，autocommit/超时 → 执行层。
     */
    public void apply(ScriptContext context) {
        ScriptOutput output = context.output();
        switch (this) {
            case ECHO -> output.setEcho(context.echoOn());
            case FEEDBACK -> output.setFeedback(context.feedbackOn());
            case TERMOUT -> output.setTermOut(context.termOutOn());
            case HEADER -> output.setHeader(context.headerOn());
            case OUTFORMAT -> output.setOutputFormat(RenderOptions.parseFormat(context.option(optionName)));
            case LINEWIDTH -> output.setLineWidth(context.lineWidth());
            case COLWIDTH -> output.setColWidth(context.colWidth());
            case DIFF_REPORT_HTML -> context.dispatcher().setDiffReportHtml(context.diffReportHtmlOn());
            case AUTOCOMMIT -> context.sqlExecutor().setAutoCommit("on".equals(context.option(optionName)));
            case TIMEOUT -> context.sqlExecutor().setQueryTimeout(
                    UserNumber.nonNegativeInteger(optionName, context.option(optionName)));
            case TIMING, TIME -> {
                // 只有引擎自己读（_SET TIMING/TIME 在每条语句收尾时看），没有下层要通知
            }
        }
    }

    /** 把枚举取值排成 {@code A or B}（错误提示用）。 */
    private String joinChoices() {
        if (choices.size() <= 1) {
            return String.join("", choices);
        }
        return String.join(" or ", choices.subList(0, choices.size() - 1))
                + " or " + choices.get(choices.size() - 1);
    }
}
