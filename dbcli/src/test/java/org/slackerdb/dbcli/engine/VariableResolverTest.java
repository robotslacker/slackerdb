package org.slackerdb.dbcli.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变量替换层的 hermetic 测试。
 *
 * <p>锁四件事：唯一外观 {@code ${}}（双花括号写法保持原样、不报错）、三步解析顺序（已定义变量 →
 * 同名环境变量 → 原样保留）、未知名字保持原样、非法替换目标给可读错误。</p>
 */
class VariableResolverTest {

    @Test
    @DisplayName("唯一外观 ${x}：--var 取得到；双花括号写法原样保留")
    void dollarPlaceholderIsTheOnlyForm() {
        VariableResolver resolver = new VariableResolver(Map.of("who", "world"));

        assertEquals("hello world", resolver.resolve("hello ${who}"));
        assertEquals("hello world", resolver.resolve("hello ${ who }"));
        // 双花括号不是替换语法：既不替换也不报错，按普通文本原样保留
        assertEquals("hello {{who}}", resolver.resolve("hello {{who}}"));
        assertEquals("{{who}} / world", resolver.resolve("{{who}} / ${who}"));
    }

    @Test
    @DisplayName("ARGVn：1 基，越界视为未定义（保持原样）")
    void argvPlaceholders() {
        VariableResolver resolver = new VariableResolver(Map.of());
        resolver.setArgv(List.of("one", "two"));

        assertEquals("one-two", resolver.resolve("${ARGV1}-${ARGV2}"));
        assertEquals("${ARGV3}", resolver.resolve("${ARGV3}"));
        assertEquals("${ARGV0}", resolver.resolve("${ARGV0}"));
    }

    @Test
    @DisplayName("ARGVn 的序号超出 int：等同于未定义，不能抛 NumberFormatException")
    void argvIndexOverflowIsUndefined() {
        VariableResolver resolver = new VariableResolver(Map.of());

        assertEquals("${ARGV99999999999999}", resolver.resolve("${ARGV99999999999999}"));
    }

    @Test
    @DisplayName("来源优先级：脚本变量 > --var > ARGV；环境变量是最后一步兜底")
    void sourcePriority() {
        VariableResolver resolver = new VariableResolver(Map.of("who", "from-var"));
        assertEquals("from-var", resolver.resolve("${who}"));
        // 脚本变量赢 --var
        resolver.setVariable("who", "from-script");
        assertEquals("from-script", resolver.resolve("${who}"));

        VariableResolver second = new VariableResolver(Map.of());
        second.setArgv(List.of("from-argv"));
        assertEquals("from-argv", second.resolve("${ARGV1}"));
        // 前两步都没有时才取同名环境变量
        assertEquals(System.getenv("PATH"), second.resolve("${PATH}"));
    }

    @Test
    @DisplayName("环境变量兜底：${PATH} 取同名环境变量；都没有的名字原样保留；${ENV(PATH)} 已不是特殊写法")
    void environmentVariables() {
        VariableResolver resolver = new VariableResolver(Map.of());
        String path = System.getenv("PATH");

        assertEquals(path, resolver.resolve("${PATH}"));
        assertEquals("${NO_SUCH_VARIABLE_XYZ}", resolver.resolve("${NO_SUCH_VARIABLE_XYZ}"));
        // ${ENV(NAME)} 已移除：它不再是特殊形态，按普通文本原样保留（要环境变量直接写 ${NAME}）
        assertEquals("${ENV(PATH)}", resolver.resolve("${ENV(PATH)}"));
        assertEquals("${ENV(NO_SUCH_VARIABLE_XYZ)}", resolver.resolve("${ENV(NO_SUCH_VARIABLE_XYZ)}"));
    }

    @Test
    @DisplayName("定义了就用当前值：--var / _SET 覆盖同名环境变量（PATH），环境变量只是兜底")
    void definedVariableWinsOverEnvironment() {
        VariableResolver byVar = new VariableResolver(Map.of("PATH", "from-var"));
        assertEquals("from-var", byVar.resolve("${PATH}"));

        byVar.setVariable("PATH", "from-script");
        assertEquals("from-script", byVar.resolve("${PATH}"));

        // 没定义时才轮到同名环境变量
        assertEquals(System.getenv("PATH"), new VariableResolver(Map.of()).resolve("${PATH}"));
    }

    @Test
    @DisplayName("非法替换目标：${a+b} / ${f(x)} / ${name with space} 都报可读错误（不静默）")
    void illegalExpressions() {
        VariableResolver resolver = new VariableResolver(Map.of());

        VariableExpressionException sum = assertThrows(VariableExpressionException.class,
                () -> resolver.resolve("${a+b}"));
        assertTrue(sum.getMessage().startsWith("Unsupported variable expression [${a+b}]"),
                sum.getMessage());

        VariableExpressionException call = assertThrows(VariableExpressionException.class,
                () -> resolver.resolve("${f(x)}"));
        assertTrue(call.getMessage().startsWith("Unsupported variable expression [${f(x)}]"),
                call.getMessage());

        VariableExpressionException spaced = assertThrows(VariableExpressionException.class,
                () -> resolver.resolve("${name with space}"));
        assertTrue(spaced.getMessage().startsWith("Unsupported variable expression [${name with space}]"),
                spaced.getMessage());
    }

    @Test
    @DisplayName("转义形态一律不处理：\\${PATH} 与 $\\{PATH\\} 原样保留（PATH 存在也不替换、不报错）")
    void escapedPlaceholdersAreLeftAlone() {
        VariableResolver resolver = new VariableResolver(Map.of());
        String path = System.getenv("PATH");

        // 反斜杠在 $ 前：转义 → 原样保留（连反斜杠一起，不被吃掉、不改写、不查环境变量）
        assertEquals("\\${PATH}", resolver.resolve("\\${PATH}"));
        // 花括号被转义：本来就不匹配 ${...} 模式，同样原样保留
        assertEquals("$\\{PATH\\}", resolver.resolve("$\\{PATH\\}"));
        // 对照：未转义的写法照常取同名环境变量
        assertEquals(path, resolver.resolve("${PATH}"));
    }

    @Test
    @DisplayName("转义按出现位置逐个判断：同一行里 \\${PATH} 原样、${PATH} 被替换")
    void escapeAppliesPerOccurrence() {
        VariableResolver resolver = new VariableResolver(Map.of());
        String path = System.getenv("PATH");

        assertEquals("\\${PATH} | " + path, resolver.resolve("\\${PATH} | ${PATH}"));
    }

    @Test
    @DisplayName("空名字不是变量引用：${} / ${ } 原样保留（不查环境变量、不报错）")
    void emptyNamesArePlainText() {
        VariableResolver resolver = new VariableResolver(Map.of("who", "world"));

        assertEquals("${}", resolver.resolve("${}"));
        assertEquals("${ }", resolver.resolve("${ }"));
        assertEquals("${   }", resolver.resolve("${   }"));
    }

    @Test
    @DisplayName("双花括号里的表达式/空名都只是普通文本：不报错、不替换（也不会被当成变量）")
    void bracesArePlainText() {
        VariableResolver resolver = new VariableResolver(Map.of("a", "A"));
        resolver.setArgv(List.of("first"));

        assertEquals("{{a+b}}", resolver.resolve("{{a+b}}"));
        assertEquals("{{}}", resolver.resolve("{{}}"));
        assertEquals("{{ARGV1}}", resolver.resolve("{{ARGV1}}"));
    }

    @Test
    @DisplayName("单遍替换：值里再出现占位符不会被重新扫描（避免自我递归）")
    void replacementIsNotRescanned() {
        VariableResolver resolver = new VariableResolver(Map.of("a", "${b}", "b", "B"));

        assertEquals("${b}", resolver.resolve("${a}"));
    }

    @Test
    @DisplayName("snapshot：给 _ASSERT 的 VAR.<名> 取用（脚本变量 + --var + ARGVn）")
    void snapshotForAssertions() {
        VariableResolver resolver = new VariableResolver(Map.of("cli", "1"));
        resolver.setVariable("script", "2");
        resolver.setArgv(List.of("first"));

        Map<String, String> snapshot = resolver.snapshot();
        assertEquals("1", snapshot.get("cli"));
        assertEquals("2", snapshot.get("script"));
        assertEquals("first", snapshot.get("ARGV1"));
    }
}
