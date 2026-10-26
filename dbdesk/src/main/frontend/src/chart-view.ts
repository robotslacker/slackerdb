import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import {
    GridComponent,
    LegendComponent,
    TitleComponent,
    ToolboxComponent,
    TooltipComponent
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

// 按需构建（M0-V3 的硬要求：不许整包引入 echarts）
echarts.use([
    BarChart, LineChart, PieChart,
    GridComponent, LegendComponent, TitleComponent, ToolboxComponent, TooltipComponent,
    CanvasRenderer
]);

/**
 * ECharts 5 的最小 Vaadin 包装件（M0-V3）。
 *
 * 约定（与 Java 侧 `org.slackerdb.dbdesk.jsbridge.ChartView` 对应）：
 *  - 属性 `optionJson`：服务端写入的 ECharts option（JSON 字符串，客户端 parse 后 setOption）；
 *  - 方法 `getChartSize()` / `getRenderedDataUrl()`：给测试与"导出 PNG"用；
 *  - 尺寸跟随容器：用 ResizeObserver，不用 window.onresize（Grid/分栏里更可靠）。
 */
class ChartViewElement extends HTMLElement {

    private chart: echarts.ECharts | null = null;
    private optionJsonValue = '';
    private resizeObserver: ResizeObserver | null = null;

    get optionJson(): string {
        return this.optionJsonValue;
    }

    set optionJson(value: string) {
        this.optionJsonValue = value ?? '';
        this.applyOption();
    }

    connectedCallback(): void {
        if (this.chart) {
            return;
        }
        this.classList.add('chart-host');
        this.style.display = 'block';
        this.style.minHeight = '320px';

        this.chart = echarts.init(this, undefined, { renderer: 'canvas' });
        this.resizeObserver = new ResizeObserver(() => this.chart?.resize());
        this.resizeObserver.observe(this);
        this.applyOption();
    }

    disconnectedCallback(): void {
        this.resizeObserver?.disconnect();
        this.resizeObserver = null;
        this.chart?.dispose();
        this.chart = null;
    }

    /** 测试用：画布实际尺寸（没渲染出来会是 0）。 */
    getChartSize(): { width: number; height: number } {
        return { width: this.clientWidth, height: this.clientHeight };
    }

    /** "导出 PNG"用：返回 data:image/png;base64,... */
    getRenderedDataUrl(): string {
        return this.chart ? this.chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }) : '';
    }

    private applyOption(): void {
        if (!this.chart || !this.optionJsonValue) {
            return;
        }
        try {
            this.chart.setOption(JSON.parse(this.optionJsonValue), true);
            this.chart.resize();
        } catch (e) {
            console.error('ChartView: option 解析失败', e);
        }
    }
}

customElements.define('chart-view', ChartViewElement);

export { ChartViewElement };
