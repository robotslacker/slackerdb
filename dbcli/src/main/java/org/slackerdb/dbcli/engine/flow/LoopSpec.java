package org.slackerdb.dbcli.engine.flow;

/**
 * 循环头：固定次数、先判断、后判断或无限循环。
 *
 * @param mode      COUNT / WHILE / UNTIL / FOREVER
 * @param count     COUNT 的次数；其它形态为 0
 * @param condition WHILE / UNTIL 的条件正文；其它形态为 null
 */
public record LoopSpec(Mode mode, int count, String condition) {

    public enum Mode {
        COUNT,
        WHILE,
        UNTIL,
        FOREVER
    }

    public static LoopSpec count(int count) {
        return new LoopSpec(Mode.COUNT, count, null);
    }

    public static LoopSpec whileTrue(String condition) {
        return new LoopSpec(Mode.WHILE, 0, condition);
    }

    public static LoopSpec until(String condition) {
        return new LoopSpec(Mode.UNTIL, 0, condition);
    }

    public static LoopSpec forever() {
        return new LoopSpec(Mode.FOREVER, 0, null);
    }
}
