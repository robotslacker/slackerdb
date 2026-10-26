package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.aura.Aura;

/**
 * 应用壳：打开服务端推送（{@code @Push}）+ 声明主题（Aura）。
 *
 * <p><b>为什么要推送</b>：SQL 工作台在后台线程跑查询、用 {@code ui.access(…)} 回填结果。
 * 没有推送时，回填只会停在"待发送"，要等用户下一次操作才带回浏览器 —— 现象是
 * "点了执行，状态永远停在『执行中…』"（本轮实测踩到）。</p>
 *
 * <p><b>主题写法</b>：Vaadin 25 的内置主题用 {@code @StyleSheet(Aura.STYLESHEET)} 声明
 * （{@code @Theme("aura")} 会要求项目里有同名主题目录，内置主题不适用）。</p>
 *
 * <p><b>与 CodeMirror 的冲突</b>：Aura 会把自己的样式表放进 {@code document.adoptedStyleSheets}，
 * 而 CodeMirror 也用构造式样式表注入基础样式，两者互相覆盖 → 编辑器失去全部布局。
 * 解决办法在 {@code sql-editor.ts}：把编辑器装进它自己的 shadow root，样式只作用在 shadow root 里。</p>
 *
 * <p>注意：{@code @Push} 这类 app-shell 注解只能放在 {@link AppShellConfigurator} 实现类上；
 * 放到 {@code @Route} 视图上会启动失败（{@code InvalidApplicationConfigurationException}）。</p>
 */
@Push
@StyleSheet(Aura.STYLESHEET)
public class AppShell implements AppShellConfigurator {
}
