package org.slackerdb.dbproxy.test;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slackerdb.common.discovery.Announcement;
import org.slackerdb.dbproxy.server.PostgresProxyTarget;
import org.slackerdb.dbproxy.server.ProxyInstance;
import org.slackerdb.dbproxy.server.ProxyInstanceX;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组播发现落到代理状态上的行为验收：登记、幂等刷新、搬家告警、租约过期、BYE 摘除。
 *
 * <h3>为什么直接调 {@code applyAnnouncement} 而不起组播</h3>
 * <p>
 * 这是"注入式"测试：把报文投喂到落库逻辑的入口，跳过不可靠的组播收发。它覆盖的是真正容易
 * 出错、且排查成本最高的那部分行为——尤其是<b>幂等</b>与<b>租约</b>：
 * </p>
 * <ul>
 *   <li>通告每 5 秒来一条，如果每次当成"新目标"处理，日志会被刷爆、创建时间也会失去意义；</li>
 *   <li>如果给遗留 REGISTER 登记的条目也加上租约，升级代理之后老版本 dbserver 会在 15 秒内
 *       全部消失——这会是一个很难归因的升级事故。</li>
 * </ul>
 *
 * <p>断言消息用英文：surefire 在中文控制台上会把非 ASCII 打成乱码。</p>
 */
@SuppressWarnings("deprecation")
class ProxyInstanceDiscoveryTest {
    private static ProxyInstance newProxyInstance() throws Exception {
        ProxyInstance proxyInstance = new ProxyInstance(
                new org.slackerdb.dbproxy.configuration.ServerConfiguration());
        // 生产路径下 logger 一定在 start() 里先于任何通告被赋值；这里手工补上，
        // 免得为了一个单元测试去真的起一套监听端口
        proxyInstance.logger = (Logger) LoggerFactory.getLogger("test");
        return proxyInstance;
    }

    private static Announcement hello(String name, String host, int port, int portX) {
        return Announcement.hello(name, name, host, port, portX, "0.2.1", 1);
    }

    // ------------------------------------------------------------------ 登记

    @Test
    @DisplayName("HELLO 登记别名：地址取数据报源地址，期限取协议租约")
    void helloRegistersTarget() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();

        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000),
                InetAddress.getByName("192.168.1.50"));

        PostgresProxyTarget target = proxyInstance.lookupTarget("mydb");
        assertNotNull(target, "the alias must be routable right after a HELLO");
        assertEquals("192.168.1.50", InetAddress.getByName(target.host).getHostAddress(),
                "the datagram source address wins over the self-reported host");
        assertEquals(5432, target.port);
        assertEquals(8000, target.portX);
        assertEquals("mydb", target.database);
        assertTrue(target.leaseExpireAtMs > System.currentTimeMillis(),
                "a discovered target must carry a future lease");
    }

    @Test
    @DisplayName("别名可以与库名不同：报文里的 alias 字段决定选路用的名字")
    void aliasMayDifferFromDatabaseName() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();

        proxyInstance.applyAnnouncement(
                Announcement.hello("mydb", "reporting", "10.9.9.9", 5432, -1, "", 1),
                InetAddress.getByName("192.168.1.50"));

        assertNull(proxyInstance.lookupTarget("mydb"));
        assertEquals("mydb", proxyInstance.lookupTarget("reporting").database);
    }

    @Test
    @DisplayName("重复的同一条通告是幂等的：创建时间不变、目标对象不换、租约被续期")
    void repeatedAnnouncementIsIdempotent() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();
        InetAddress source = InetAddress.getByName("192.168.1.50");

        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000), source);
        PostgresProxyTarget first = proxyInstance.lookupTarget("mydb");
        LocalDateTime firstCreated = first.createdDateTime;
        long firstLease = first.leaseExpireAtMs;

        // 模拟下一个通告周期：租约必须往后推
        Thread.sleep(5);
        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000), source);
        PostgresProxyTarget second = proxyInstance.lookupTarget("mydb");

        assertNotSame(first, second, "the entry is replaced, so readers always see fully built fields");
        assertEquals(firstCreated, second.createdDateTime, "首次登记时间必须保留，否则看不出别名何时出现");
        assertTrue(second.leaseExpireAtMs >= firstLease, "each announcement must renew the lease");
        assertEquals(1, proxyInstance.proxyTarget.size());
    }

    @Test
    @DisplayName("地址搬家：同一个别名换到另一台机器时更新目标（并会打 WARN 提示）")
    void movedAliasUpdatesTarget() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();

        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000),
                InetAddress.getByName("192.168.1.50"));
        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000),
                InetAddress.getByName("192.168.1.60"));

        assertEquals("192.168.1.60", proxyInstance.lookupTarget("mydb").host);
        assertEquals(1, proxyInstance.proxyTarget.size(), "one alias maps to exactly one target for now");
    }

    // ------------------------------------------------------------------ portX 转发映射

    @Test
    @DisplayName("portX 有效时建立 HTTP 转发映射，portX=-1 时不建立")
    void maintainsForwarderMapping() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();
        proxyInstance.proxyInstanceX = new ProxyInstanceX(proxyInstance.logger, proxyInstance);
        InetAddress source = InetAddress.getByName("192.168.1.50");

        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000), source);
        assertEquals("http://192.168.1.50:8000",
                proxyInstance.proxyInstanceX.forwarderPathMappings.get("/mydb"));

        // 同一台机器换成"管理端口未启用"：旧映射必须被删掉，否则会指着已经不存在的端口
        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, -1), source);
        assertFalse(proxyInstance.proxyInstanceX.forwarderPathMappings.containsKey("/mydb"));
    }

    @Test
    @DisplayName("代理自身没启用管理端口时，登记与摘除都不应因缺少转发器而 NPE")
    void toleratesMissingForwarder() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();
        assertNull(proxyInstance.proxyInstanceX);

        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000),
                InetAddress.getByName("192.168.1.50"));
        proxyInstance.removeAnnouncement("mydb", "test");

        assertNull(proxyInstance.lookupTarget("mydb"));
    }

    // ------------------------------------------------------------------ 摘除与租约

    @Test
    @DisplayName("BYE 立即摘除条目及其转发映射")
    void byeRemovesTargetImmediately() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();
        proxyInstance.proxyInstanceX = new ProxyInstanceX(proxyInstance.logger, proxyInstance);

        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000),
                InetAddress.getByName("192.168.1.50"));
        proxyInstance.removeAnnouncement("mydb", "BYE from 192.168.1.50");

        assertNull(proxyInstance.lookupTarget("mydb"));
        assertFalse(proxyInstance.proxyInstanceX.forwarderPathMappings.containsKey("/mydb"));
    }

    @Test
    @DisplayName("租约到期后条目被摘除：这是 dbserver 被强杀后唯一能自动清理的路径")
    void expiredLeaseIsRemoved() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();
        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, 8000),
                InetAddress.getByName("192.168.1.50"));

        // 还没到期：不能动
        proxyInstance.expireLeases();
        assertNotNull(proxyInstance.lookupTarget("mydb"));

        // 把租约拨到过去，模拟"连续多个通告周期都没收到"
        proxyInstance.lookupTarget("mydb").leaseExpireAtMs = System.currentTimeMillis() - 1;
        proxyInstance.expireLeases();

        assertNull(proxyInstance.lookupTarget("mydb"));
    }

    @Test
    @DisplayName("无租约的条目（lease=0）不会被过期扫描摘除")
    void leaseFreeEntryNeverExpires() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();

        // 组播发现的条目一定带租约；lease=0 是留给直接写入 proxyTarget 的调用方
        // （插件、将来的管理接口）的约定，否则它们放进去的目标会在 15 秒后被静默摘掉
        PostgresProxyTarget byHand = new PostgresProxyTarget();
        byHand.createdDateTime = LocalDateTime.now();
        byHand.lastActivatedTIme = LocalDateTime.now();
        byHand.host = "127.0.0.1";
        byHand.port = 5432;
        byHand.portX = -1;
        byHand.database = "byHand";
        byHand.leaseExpireAtMs = 0;
        proxyInstance.proxyTarget.put("byHand", byHand);

        for (int i = 0; i < 3; i++) {
            proxyInstance.expireLeases();
        }
        assertNotNull(proxyInstance.lookupTarget("byHand"));
    }

    @Test
    @DisplayName("组播条目与无租约条目可以共存，过期扫描只清带租约且已到期的那一个")
    void expiryLeavesLeaseFreeEntriesAlone() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();

        PostgresProxyTarget byHand = new PostgresProxyTarget();
        byHand.createdDateTime = LocalDateTime.now();
        byHand.lastActivatedTIme = LocalDateTime.now();
        byHand.host = "127.0.0.1";
        byHand.port = 5432;
        byHand.portX = -1;
        byHand.database = "byHand";
        byHand.leaseExpireAtMs = 0;
        proxyInstance.proxyTarget.put("byHand", byHand);

        proxyInstance.applyAnnouncement(hello("discovered", "10.9.9.9", 5432, -1),
                InetAddress.getByName("192.168.1.50"));
        proxyInstance.lookupTarget("discovered").leaseExpireAtMs = System.currentTimeMillis() - 1;
        proxyInstance.expireLeases();

        assertNotNull(proxyInstance.lookupTarget("byHand"));
        assertNull(proxyInstance.lookupTarget("discovered"));
    }

    @Test
    @DisplayName("摘除不存在的别名是安全的：BYE 可能重复到达或晚于过期扫描")
    void removingUnknownAliasIsSafe() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();
        proxyInstance.removeAnnouncement("never-seen", "BYE");
        proxyInstance.removeAnnouncement(null, "BYE");
        assertEquals(0, proxyInstance.proxyTarget.size());
    }

    @Test
    @DisplayName("lookupTarget 是选路的唯一入口，未登记时返回 null")
    void lookupTargetReturnsNullForUnknownAlias() throws Exception {
        ProxyInstance proxyInstance = newProxyInstance();
        assertNull(proxyInstance.lookupTarget("nope"));
        proxyInstance.applyAnnouncement(hello("mydb", "10.9.9.9", 5432, -1),
                InetAddress.getByName("192.168.1.50"));
        assertSame(proxyInstance.proxyTarget.get("mydb"), proxyInstance.lookupTarget("mydb"));
    }
}
