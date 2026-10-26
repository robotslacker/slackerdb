package org.slackerdb.dbdesk.access.definitions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 定义存储的抽象（M5）：同一套读写，既能落本地文件，也能落服务端。
 *
 * <p>为什么用 {@code Map<String,Object>} 而不是强类型：四类定义（数据集/图表/仪表板/门户）
 * 的字段会随控制台演进，服务端**不解析**业务字段。把它抽象成"按 kind 存整段 JSON"，
 * 加字段就不用动后端与契约。</p>
 *
 * <p>约定：{@code kind} 是复数（datasets / charts / dashboards / portals）；id 由调用方保证 URL 安全。</p>
 */
public interface DefinitionBackend {

    /** 后端名字，用于状态展示与排查（file / server）。 */
    String name();

    List<Map<String, Object>> list(String kind);

    Optional<Map<String, Object>> find(String kind, String id);

    /** id 为空表示新建（服务端/文件后端各自生成 id）。 */
    Map<String, Object> save(String kind, Map<String, Object> definition);

    boolean delete(String kind, String id);
}
