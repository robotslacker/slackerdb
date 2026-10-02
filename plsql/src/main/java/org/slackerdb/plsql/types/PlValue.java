package org.slackerdb.plsql.types;

/**
 * 变量值：类型 + 值（{@code null} 合法）。
 */
public record PlValue(PlType type, Object value) {

    public static PlValue of(PlType type, Object value) {
        return new PlValue(type, value);
    }

    /** 未初始化变量的值：NULL。 */
    public static PlValue nullOf(PlType type) {
        return new PlValue(type, null);
    }

    public boolean isNull() {
        return value == null;
    }

    @Override
    public String toString() {
        return value + ":" + type.displayName();
    }
}
