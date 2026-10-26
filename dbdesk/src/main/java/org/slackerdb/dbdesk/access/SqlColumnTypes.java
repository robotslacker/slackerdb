package org.slackerdb.dbdesk.access;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/**
 * JDBC 类型常量 → 大写类型名。
 *
 * <p><b>来源</b>：复制自 {@code dbcli/sql/SqlColumnTypes}（决策 R3：只复制纯规则，不依赖模块）。
 * 两边必须保持一致 —— 同一产品不能对同一列给出两种类型名（列对齐、导出、断言都依赖它）。
 * 改动这里时，记得同步 {@code dbcli} 侧。</p>
 *
 * <p>为什么不用驱动返回的 {@code getColumnTypeName()}：SlackerDB/DuckDB 返回小写
 * {@code varchar}，而表格对齐规则按 {@code ("VARCHAR","CHAR",...)} 这类**大写**名字判断，
 * 直接用驱动名会让左右对齐都错。</p>
 */
public final class SqlColumnTypes {

    private static final Map<Integer, String> CONST_TO_NAME = build();

    private SqlColumnTypes() {
    }

    private static Map<Integer, String> build() {
        Map<Integer, String> map = new HashMap<>();
        for (Field field : Types.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            try {
                map.put(field.getInt(null), field.getName());
            } catch (IllegalAccessException ignored) {
                // 公开静态字段不会走到这里；真走到了就跳过该类型，不影响其它类型
            }
        }
        return Map.copyOf(map);
    }

    /**
     * 取列类型名。
     *
     * @param jdbcType           {@code ResultSetMetaData.getColumnType(i)}；0 表示 SQL NULL 类型
     * @param typeNameFromDriver 驱动返回的 {@code getColumnTypeName(i)}，用于表里没登记的类型
     * @return 大写类型名；{@code jdbcType==0} 时返回 null
     */
    public static String nameOf(int jdbcType, String typeNameFromDriver) {
        if (jdbcType == 0) {
            return null;
        }
        String name = CONST_TO_NAME.get(jdbcType);
        // 未登记的 JDBC 类型退回驱动给的名字，并**统一大写** —— 与 dbcli 的行为保持一致
        // （契约 C-5 的 type-names 夹具会盯住这一条；之前两侧一个原样返回、一个转大写，就是漂移）
        return name != null ? name
                : (typeNameFromDriver == null ? null : typeNameFromDriver.toUpperCase(java.util.Locale.ROOT));
    }
}
