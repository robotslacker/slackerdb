package org.slackerdb.dbcli.assertion;

import java.util.List;
import java.util.Map;

/**
 * 断言可用的"结果模型"：把"上一条命令的结果"里断言能取到的值集中在一处。
 *
 * <p>为什么要有这一层：断言表达式只能对**显式建模**的数据取值（没有脚本引擎可以访问任意运行时对象），
 * 因此把可取的值集中在这里；{@code _ASSERT} 的取值器就是对这些字段的读取。</p>
 *
 * @param errorCode  上一条语句的错误码（0 表示正常）
 * @param rowCount   上一条语句影响/返回的行数
 * @param status     上一条语句的状态文案（如 {@code 2 rows selected.}）
 * @param rows       结果集（每行是一个值列表）；查询类语句才有
 * @param headers    列标签
 * @param columnTypes 列类型名（由 java.sql.Types 反射得到的大写名，见 SqlColumnTypes）
 * @param variables  {@code --var} / map / 环境变量解析出的变量
 */
public record ResultModel(
        int errorCode,
        int rowCount,
        String status,
        List<List<Object>> rows,
        List<String> headers,
        List<String> columnTypes,
        Map<String, String> variables) {

    /** 空模型：断言里只用到常量或环境变量时可用。 */
    public static ResultModel empty() {
        return new ResultModel(0, 0, null, List.of(), List.of(), List.of(), Map.of());
    }
}
