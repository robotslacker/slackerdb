package org.slackerdb.dbdesk.jsbridge;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * ECharts 5 的图表包装件（M0-V3）。
 *
 * <p>只做三件事：把 Java 侧构造的 option 送进浏览器、跟随容器尺寸 resize、能导出 PNG。
 * 报表里的"图表块"直接复用它（P5），所以这里刻意不绑定任何业务结构。</p>
 *
 * <p><b>按需构建</b>：客户端 {@code src/main/frontend/src/chart-view.ts} 只 {@code echarts.use()}
 * 了 bar/line/pie + grid/legend/title/toolbox/tooltip + canvas 渲染器，没有整包 import
 * （整包 ECharts 单文件就 >1 MB）。</p>
 *
 * <p><b>为什么传 JSON 字符串而不是对象</b>：Vaadin 的 {@code Element.setProperty} 对任意
 * Map/POJO 的序列化行为随版本变（早期走 elemental.json，后来走 Jackson）。传字符串 +
 * 客户端 {@code JSON.parse} 是最稳的跨版本写法，也方便在日志里直接看。</p>
 *
 * <p><b>注意 Jackson 版本</b>：本模块跑在 Spring Boot 4 上，Jackson 是 **3.x**
 * （包名 {@code tools.jackson.*}，不是 {@code com.fasterxml.jackson.*}）—— 用老包名会编译不过。</p>
 */
@Tag("chart-view")
@JsModule("./src/chart-view.ts")
@NpmPackage(value = "echarts", version = "5.6.0")
public class ChartView extends Component implements HasSize {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChartView() {
        setHeight("360px");
    }

    /** 设置 ECharts option（例如 {@code {"xAxis":…, "series":[…]}}）。 */
    public void setOption(Map<String, Object> option) {
        // Jackson 3 的异常是非受检的（JacksonException extends RuntimeException），不必包 try/catch
        getElement().setProperty("optionJson", objectMapper.writeValueAsString(option));
    }

    /** 导出 PNG 的 data URL（服务端拿不到 canvas，需要时用 {@link #getElement()}.callJsFunction）。 */
    public String optionJson() {
        String json = getElement().getProperty("optionJson");
        return json == null ? "" : json;
    }
}
