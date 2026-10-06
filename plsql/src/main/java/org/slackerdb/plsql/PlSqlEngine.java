package org.slackerdb.plsql;

import org.slackerdb.plsql.exec.Interpreter;
import org.slackerdb.plsql.parse.PlSqlCompiler;
import org.slackerdb.plsql.spi.PlSqlCanceledException;
import org.slackerdb.plsql.spi.PlSqlHost;
import org.slackerdb.plsql.types.PlSqlTypeException;

/**
 * PL/SQL 引擎
 *
 * <p>调用方（dbserver 两条协议路径、嵌入式使用）只依赖本类 + {@link PlSqlHost} SPI。
 * 内部实现：{@code PlSqlCompiler}（遮罩 + 结构文法 + AST）→ {@code Interpreter}，
 * 执行期只经 SPI 访问数据库。</p>
 */
public final class PlSqlEngine {

    private PlSqlEngine() {
    }

    /**
     * 执行一段 PL/SQL 脚本（块体，不含 {@code DO $$} 包装）。
     *
     * @throws PlSqlException 语法错误、执行错误或被取消（携带 SQLSTATE 与位置）
     */
    public static void execute(PlSqlHost host, String script) {
        execute(host, script, new Interpreter.Options());
    }

    /** 带选项的执行（语句预算等）。 */
    public static void execute(PlSqlHost host, String script, Interpreter.Options options) {
        try {
            PlSqlCompiler.Result compiled = PlSqlCompiler.compile(script);
            new Interpreter(host, options).run(compiled.block());
        } catch (PlSqlException e) {
            throw e;
        } catch (PlSqlCanceledException canceled) {
            throw new PlSqlException(canceled.getMessage(), PlSqlException.QUERY_CANCELED, canceled);
        } catch (PlSqlCompiler.PlSqlCompileException e) {
            throw new PlSqlException(e.getMessage(), PlSqlException.SYNTAX_ERROR,
                    e.getLine(), e.getColumn(), null, e);
        } catch (PlSqlTypeException e) {
            throw new PlSqlException(e.getMessage(), e.getSqlState(), e);
        } catch (RuntimeException e) {
            throw PlSqlException.from(e);
        }
    }
}
