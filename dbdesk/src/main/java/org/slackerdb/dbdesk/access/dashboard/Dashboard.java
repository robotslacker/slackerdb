package org.slackerdb.dbdesk.access.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 仪表板：一组图表卡片 + 布局 + 全局筛选。
 *
 * <p>卡片只引用 {@code chartId}（图表再引用数据集），所以"改口径"只改数据集一处；
 * 仪表板本身只负责"摆在哪、多大、用什么筛选值"。</p>
 *
 * <p>布局用 **12 列栅格**（{@code x} 0..11、{@code w} 1..12），高度用行数（{@code h}）。
 * 不做自由像素拖拽：调试成本高、收益低，栅格足够表达 95% 的看板。</p>
 *
 * <p>全局筛选按名字下发：筛选 {@code from} → 每个数据集里叫 {@code from} 的参数。名字对不上就忽略，
 * 不会因为某个数据集没有这个参数而整块失败。</p>
 */
public record Dashboard(String id, String name, List<Filter> filters, List<Card> cards, String updatedAt) {

    /** 全局筛选（类型与数据集参数一致：text / number / date）。 */
    public record Filter(String name, String type, String defaultValue) {
        public Filter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("filter name must not be blank");
            }
            name = name.strip();
            type = type == null || type.isBlank() ? "text" : type.strip().toLowerCase(Locale.ROOT);
            defaultValue = defaultValue == null ? "" : defaultValue;
        }
    }

    /** 卡片：图表 + 栅格位置。 */
    public record Card(String chartId, int x, int y, int w, int h) {
        public Card {
            if (chartId == null || chartId.isBlank()) {
                throw new IllegalArgumentException("card must reference a chart");
            }
            chartId = chartId.strip();
            x = Math.max(0, Math.min(11, x));
            y = Math.max(0, y);
            w = Math.max(1, Math.min(12, w));
            h = Math.max(1, Math.min(12, h));
        }
    }

    public Dashboard {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dashboard name must not be blank");
        }
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
        name = name.strip();
        filters = filters == null ? List.of() : List.copyOf(filters);
        cards = cards == null ? List.of() : List.copyOf(cards);
        updatedAt = updatedAt == null || updatedAt.isBlank() ? Instant.now().toString() : updatedAt;
    }

    public Dashboard withContent(String newName, List<Filter> newFilters, List<Card> newCards) {
        return new Dashboard(id, newName, newFilters, newCards, Instant.now().toString());
    }

    /** 追加一张卡片：从最后一行的下一行开始，默认半宽。 */
    public Dashboard withCard(String chartId) {
        int nextY = cards.stream().mapToInt(Card::y).max().orElse(-1) + 1;
        List<Card> updated = new java.util.ArrayList<>(cards);
        updated.add(new Card(chartId, 0, nextY, 6, 4));
        return withContent(name, filters, updated);
    }
}
