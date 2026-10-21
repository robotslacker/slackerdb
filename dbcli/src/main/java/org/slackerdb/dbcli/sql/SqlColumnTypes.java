package org.slackerdb.dbcli.sql;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/**
 * JDBC 类型常量 → 大写类型名。
 *
 * <p><b>为什么要这张表</b>：{@code columnTypes} 不取 {@code ResultSetMetaData.getColumnTypeName()}，
 * 而是把 {@code java.sql.Types} 的常量反射成名字。表格渲染的对齐规则依赖这些**大写**名字
 * （{@code ("NVARCHAR","NCHAR","VARCHAR","LONGVARCHAR","CHAR","CLOB","NCLOB","STRUCT","ARRAY","DATE")}
 * 判"字符串左对齐"），若直接用驱动返回的小写类型名（SlackerDB/DuckDB 会返回 {@code varchar}）
 * 就会**左右对齐都错**。</p>
 *
 * <p>反射 {@code java.sql.Types} 的公开静态 int 字段即可，不必手抄一张表。</p>
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
     * @param jdbcType      {@code ResultSetMetaData.getColumnType(i)}；0 表示 SQL NULL 类型
     * @param typeNameFromDriver 驱动返回的 {@code getColumnTypeName(i)}，用于表里没登记的类型（同序优先用反射名）
     * @return 大写类型名；{@code jdbcType==0} 时返回 null
     */
    public static String nameOf(int jdbcType, String typeNameFromDriver) {
        if (jdbcType == 0) {
            return null;
        }
        String name = CONST_TO_NAME.get(jdbcType);
        return name != null ? name : typeNameFromDriver;
    }

    /** 供测试与排错用：当前 JVM 里登记了多少个类型常量。 */
    public static int registeredCount() {
        return CONST_TO_NAME.size();
    }
}
