/**
 * 跨包共享的小类型：用户输入的路径/数值解析与命令结果项。
 *
 * <ul>
 *   <li>{@link org.slackerdb.dbcli.common.PathArguments}：路径参数的唯一解析入口（非法字符给可读错误）；</li>
 *   <li>{@link org.slackerdb.dbcli.common.UserNumber}：数值参数的唯一解析入口（规则即校验，含 NaN/Infinity）；</li>
 *   <li>{@link org.slackerdb.dbcli.common.CommandOutcome}：{@code Status}/{@code Table}/{@code Error}/
 *       {@code Failure} 四种结果项（分派器 → 引擎的通用形状）；</li>
 *   <li>{@link org.slackerdb.dbcli.common.Artifacts}：产物的"删掉上一轮"语义。</li>
 * </ul>
 *
 * <p>这里放的是"多个包都要用、且语义只有一份"的东西；只服务一个包的工具应该留在那个包里。</p>
 */
package org.slackerdb.dbcli.common;
