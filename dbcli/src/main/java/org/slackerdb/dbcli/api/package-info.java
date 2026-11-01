/**
 * {@code _API} 的 HTTP 支持：请求解析、{@link java.net.http.HttpClient} 封装、
 * 响应读取与 {@code API.*} 运行时变量投影。
 *
 * <p>阅读顺序：</p>
 * <ol>
 *   <li>{@link org.slackerdb.dbcli.api.ApiRequestParser}：单行命令文本 → {@link org.slackerdb.dbcli.api.ApiRequest}；</li>
 *   <li>{@link org.slackerdb.dbcli.api.ApiClient}：发请求、取消、读取/解码响应；</li>
 *   <li>{@link org.slackerdb.dbcli.api.ApiResponse} / {@link org.slackerdb.dbcli.api.ApiResult}：结果与变量；</li>
 *   <li>{@link org.slackerdb.dbcli.api.JsonPaths}：JSON 叶子展开。</li>
 * </ol>
 *
 * <p>本包不依赖 {@code engine.command}，命令编排在
 * {@link org.slackerdb.dbcli.engine.command.ApiCommand}。</p>
 */
package org.slackerdb.dbcli.api;
