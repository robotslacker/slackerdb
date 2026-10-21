package org.slackerdb.dbcli.compare;

import java.util.Map;

/**
 * {@code _COMPARE} 的选项集合。
 *
 * <h2>选项与 IR 键的对应（名实相符）</h2>
 * <pre>
 *   caseSensitive       = IR["case"]   // IR case=true（默认）⇒ 区分大小写；false（NOCASE）⇒ 忽略
 *   ignoreHeadTailBlank = IR["trim"]   // IR trim=false（默认，NOTRIM）⇒ 不剥离；true（TRIM）⇒ 剥离首尾空白后比较
 *   mask                = IR["mask"]   // IR mask=true ⇒ 把**参考行当正则**去匹配工作行
 * </pre>
 * 本类用"正向命名"表达最终语义（{@code caseSensitive} / {@code ignoreHeadTailBlank} / {@code mask}），
 * {@link #applyIr} 按键直接赋值、**不做取反**：脚本里写 {@code SET TRIM} 就是"剥离首尾空白后比较"，
 * 与字段名同义，调用方不必再记"哪个是反的"。
 *
 * <h2>默认值</h2>
 * <ul>
 *   <li>{@code caseSensitive=true}：默认**区分**大小写（{@code CASE}）</li>
 *   <li>{@code ignoreHeadTailBlank=false}：默认**不剥离**首尾空白（{@code NOTRIM}）</li>
 *   <li>{@code mask=false}、{@code ignoreBlank=false}（产物文件名固定，没有"输出到哪"的选项）</li>
 * </ul>
 *
 * <h2>字符集</h2>
 * **固定 UTF-8，没有选项**：{@code _COMPARE} 读写文件一律按 UTF-8，不提供 {@code SET WORK|REFERENCE
 * ENCODING}（有别的编码需求就是"这个工具不支持"，而不是"可以设成别的再读成乱码"）。
 */
public final class CompareOptions {

    private boolean caseSensitive = true;
    private boolean mask;
    private boolean ignoreBlank;
    private boolean ignoreHeadTailBlank;

    public boolean caseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean value) {
        this.caseSensitive = value;
    }

    public boolean mask() {
        return mask;
    }

    public void setMask(boolean value) {
        this.mask = value;
    }

    public boolean ignoreBlank() {
        return ignoreBlank;
    }

    public void setIgnoreBlank(boolean value) {
        this.ignoreBlank = value;
    }

    /**
     * 是否"剥离首尾空白后比较"：{@code SET TRIM} ⇒ true、{@code SET NOTRIM} ⇒ false，默认 false
     * （即默认**不**剥离）。开关名与字段名同义，见类注释。
     */
    public boolean ignoreHeadTailBlank() {
        return ignoreHeadTailBlank;
    }

    public void setIgnoreHeadTailBlank(boolean value) {
        this.ignoreHeadTailBlank = value;
    }

    /** {@code _COMPARE RESET}：回到默认值。 */
    public void reset() {
        this.caseSensitive = true;
        this.mask = false;
        this.ignoreBlank = false;
        this.ignoreHeadTailBlank = false;
    }

    /** 按 IR 的键逐个套用（{@code _COMPARE SET ...} 的产出）；键名与字段语义一致，**不做取反**。 */
    public void applyIr(Map<String, Object> ir) {
        Object value;
        if ((value = ir.get("case")) != null) {
            // IR case=true ⇒ 大小写敏感
            this.caseSensitive = Boolean.parseBoolean(String.valueOf(value));
        }
        if ((value = ir.get("mask")) != null) {
            this.mask = Boolean.parseBoolean(String.valueOf(value));
        }
        if ((value = ir.get("igblank")) != null) {
            this.ignoreBlank = Boolean.parseBoolean(String.valueOf(value));
        }
        if ((value = ir.get("trim")) != null) {
            // IR trim=true ⇒ 剥离首尾空白后比较（SET TRIM）；false ⇒ 不剥离（SET NOTRIM）
            this.ignoreHeadTailBlank = Boolean.parseBoolean(String.valueOf(value));
        }
    }

    /** 深拷贝一份（{@code _COMPARE} 每次执行都基于当时的选项快照）。 */
    public CompareOptions copy() {
        CompareOptions copy = new CompareOptions();
        copy.caseSensitive = this.caseSensitive;
        copy.mask = this.mask;
        copy.ignoreBlank = this.ignoreBlank;
        copy.ignoreHeadTailBlank = this.ignoreHeadTailBlank;
        return copy;
    }
}
