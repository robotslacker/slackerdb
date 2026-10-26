package org.slackerdb.dbdesk;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dependency.JsModule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分层纪律（决策 5 / §3 的四条可验收含义）—— **用测试守，不靠评审**。
 *
 * <p>三条线：</p>
 * <ol>
 *   <li>{@code access} 层不许认识 UI/服务端框架：不 import Vaadin、Javalin、Spring。
 *       它是"换掉 UI 也能用"的那一层；</li>
 *   <li>视图层不许自己开传输：{@code HttpClient} / {@code DriverManager} 只能出现在 access 层；</li>
 *   <li>自定义 JS 只能住在 {@code jsbridge}：{@code @JsModule}/{@code @CssImport} 不许散落到视图里，
 *       否则"哪来的前端依赖"会再次变成谜。</li>
 * </ol>
 */
@AnalyzeClasses(packages = "org.slackerdb.dbdesk", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule access_is_framework_free = noClasses()
            .that().resideInAPackage("..access..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.vaadin..", "io.javalin..", "org.springframework..")
            .because("access 层只走协议（HTTP/WS/JDBC），换 UI 或换服务端都不该动它");

    @ArchTest
    static final ArchRule views_do_not_open_transports = noClasses()
            .that().resideInAPackage("..ui..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.net.http.HttpClient")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.sql.DriverManager")
            .because("视图只能编排，传输与连接属于 access 层");

    @ArchTest
    static final ArchRule js_is_confined_to_jsbridge = classes()
            .that().areAnnotatedWith(JsModule.class)
            .or().areAnnotatedWith(CssImport.class)
            .should().resideInAPackage("..jsbridge..")
            .because("自定义前端代码只能通过 jsbridge 包装件引入（决策 6）");

    @ArchTest
    static final ArchRule launcher_does_not_touch_ui_components = noClasses()
            .that().resideInAPackage("..launcher..")
            .should().dependOnClassesThat().resideInAPackage("com.vaadin..")
            .because("启动器只管进程与端口，不该耦合任何 Vaadin 组件");
}
