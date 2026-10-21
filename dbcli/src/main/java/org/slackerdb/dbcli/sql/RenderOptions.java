package org.slackerdb.dbcli.sql;

/**
 * 结果输出的格式选项（{@code _SET OUTFORMAT|HEADER|LINEWIDTH|COLWIDTH}）。
 *
 * <p>这些选项只影响**怎么打印**，不影响查询与数据本身：同一个脚本换一个格式，数据库看到的东西完全相同。</p>
 *
 * @param format    输出格式（默认 {@link Format#TABLE}）
 * @param header    是否打印表头（{@code _SET HEADER OFF} 关闭；竖排本身没有表头行，忽略此位）
 * @param colWidth  单元格最大显示宽度（默认 40；超出部分用 {@code ...} 截断，列宽按截断后计算；
 *                  **不作用于 CSV**——那是导出格式，截断会毁数据）
 * @param lineWidth 一行最多多少显示列（默认 200；超出**换行**而不是截断；**不作用于 CSV**）
 */
public record RenderOptions(Format format, boolean header, int colWidth, int lineWidth) {

    /** 输出格式：三种。 */
    public enum Format {
        /** 今天的默认表格（带边框与行号列）。 */
        TABLE,
        /** 竖排：一行一条记录（psql 的 {@code \x} 风格），适合宽表。 */
        VERTICAL,
        /** RFC4180 子集的 CSV（逗号分隔、必要处加引号、LF 行尾）。 */
        CSV
    }

    /** 默认：表格、带表头、单元格 40 列、整行 200 列。 */
    public static RenderOptions defaults() {
        return new RenderOptions(Format.TABLE, true, 40, 200);
    }

    /** 换一个输出格式（其余选项不变）。 */
    public RenderOptions withFormat(Format newFormat) {
        return new RenderOptions(newFormat == null ? Format.TABLE : newFormat, header, colWidth, lineWidth);
    }

    /** 换表头开关。 */
    public RenderOptions withHeader(boolean newHeader) {
        return new RenderOptions(format, newHeader, colWidth, lineWidth);
    }

    /** 换单元格宽度上限（{@code <= 0} 表示不截断）。 */
    public RenderOptions withColWidth(int newColWidth) {
        return new RenderOptions(format, header, newColWidth, lineWidth);
    }

    /** 换整行宽度上限（{@code <= 0} 表示不换行）。 */
    public RenderOptions withLineWidth(int newLineWidth) {
        return new RenderOptions(format, header, colWidth, newLineWidth);
    }

    /**
     * 解析 {@code _SET OUTFORMAT} 的取值（大小写不敏感）。
     *
     * @throws IllegalArgumentException 不认识的格式（调用方转成可读错误，并给出合法取值）
     */
    public static Format parseFormat(String value) {
        if (value == null || value.isBlank()) {
            return Format.TABLE;
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "table" -> Format.TABLE;
            case "vertical" -> Format.VERTICAL;
            case "csv" -> Format.CSV;
            default -> throw new IllegalArgumentException(value);
        };
    }

    /** 所有合法格式名（错误提示用）。 */
    public static String validFormats() {
        return "table|vertical|csv";
    }
}
