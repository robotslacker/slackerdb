package org.slackerdb.dbserver.server;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * 一个服务端游标（PG 的 {@code DECLARE ... CURSOR}）。
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li><b>live</b>（默认）：持有 JDBC {@link ResultSet}，{@code FETCH n} 逐批取——
 *       这是 {@code postgres_fdw} 的路径，也是"不把整表读进内存"的关键；</li>
 *   <li><b>物化</b>（{@code WITH HOLD}）：PG 语义要求它能跨 {@code COMMIT} 存活，
 *       而 JDBC 结果集做不到，因此声明时就把它取完并按文本编码存进内存
 *       （{@link #materialized}）。这条路会有内存代价，但语义正确、不撒谎。</li>
 * </ul>
 *
 * <p>列元数据（{@link #metaData}）在 {@code DECLARE} 时固定下来：{@code FETCH} 每次都要发
 * {@code RowDescription}，而扩展协议下 {@code Describe} 也要用它，两边必须同源。</p>
 */
public final class ServerCursor {

    public final String name;
    public final boolean withHold;
    /** 内层查询（改写后）——用于诊断与 `Explain` 风格的日志。 */
    public final String query;
    public final ResultSetMetaData metaData;

    /** live 模式：内层语句句柄（关闭它才能释放结果集）。 */
    public final PreparedStatement statement;
    /** live 模式：结果集；物化模式下为 {@code null}。 */
    public final ResultSet resultSet;
    /** 物化模式：已编码的行（每行 = 每列的字节，{@code null} = SQL NULL，全部文本格式）。 */
    public final List<byte[][]> materialized;

    /** 物化模式：下一个要发出的行下标。 */
    public int position;

    public ServerCursor(String name, boolean withHold, String query, ResultSetMetaData metaData,
                        PreparedStatement statement, ResultSet resultSet, List<byte[][]> materialized) {
        this.name = name;
        this.withHold = withHold;
        this.query = query;
        this.metaData = metaData;
        this.statement = statement;
        this.resultSet = resultSet;
        this.materialized = materialized;
    }

    /** 物化模式下的总行数；live 模式返回 -1（行数未知）。 */
    public int materializedSize() {
        return materialized == null ? -1 : materialized.size();
    }

    /** 释放底层资源（尽力而为：已经不可达的句柄关不掉也不该让协议消息失败）。 */
    public void close() throws SQLException {
        SQLException failure = null;
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException e) {
                failure = e;
            }
        }
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
