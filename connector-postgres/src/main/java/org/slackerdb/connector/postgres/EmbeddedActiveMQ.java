package org.slackerdb.connector.postgres;

import org.apache.activemq.broker.BrokerService;

public class EmbeddedActiveMQ {
    public static BrokerService startBroker() throws Exception {
        BrokerService broker = new BrokerService();
        broker.setBrokerName("localhost");
        broker.addConnector("tcp://localhost:21617");  // ActiveMQ 默认连接端口
        broker.start();
        return broker;
    }

    public static void stopBroker(BrokerService broker) throws Exception {
        broker.stop();
    }
}
