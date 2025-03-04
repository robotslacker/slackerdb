package org.slackerdb.connector.postgres;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jakarta.jms.*;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.jms.pool.PooledConnectionFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.util.ServiceStopper;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

class EmbeddedMessageProducer
{
    public Connection jmsConn;
    public Queue queue;
    public MessageProducer producer;
    public Session session;
}

public class EmbeddedActiveMQ {
    private static BrokerService brokerService = null;
    private PooledConnectionFactory connectionFactory = null;
    private final Logger logger;

    private final ConcurrentHashMap<String, EmbeddedMessageProducer> messageProducerMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MessageConsumer> messageConsumerMap = new ConcurrentHashMap<>();

    public EmbeddedActiveMQ(Logger logger)
    {
        Logger mqLogger = (Logger) LoggerFactory.getLogger("org.apache.activemq");
//        if (!logger.getLevel().equals(Level.TRACE)) {
            mqLogger.setLevel(Level.OFF);
//        }
        this.logger = logger;
    }

    public void startBroker(int port) throws Exception {
        int defaultMaxJmsConnections = 10;
        startBroker(port, defaultMaxJmsConnections);
    }

    public void startBroker(int port, int maxJmsConnections) throws Exception {
        String brokerName = "localhost";
        String brokerAddress = "tcp://" + brokerName + ":" + port;

        if (brokerService == null)
        {
            brokerService = new BrokerService();
        }

        brokerService.setBrokerName(brokerName);
        brokerService.addConnector(brokerAddress);
        brokerService.start();

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerAddress);
        connectionFactory = new PooledConnectionFactory();
        connectionFactory.setConnectionFactory(factory);
        connectionFactory.setMaxConnections(maxJmsConnections);
    }

    public void newMessageChannel(String queueName, IWALMessage consumeMessage) throws JMSException {
        if (this.messageProducerMap.containsKey(queueName))
        {
            // 已经存在这个消息队列
            return;
        }
        Connection jmsConn = connectionFactory.createConnection();
        Session session = jmsConn.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        EmbeddedMessageProducer embeddedMessageProducer = new EmbeddedMessageProducer();
        Queue queue = session.createQueue(queueName);
        MessageProducer messageProducer = session.createProducer(embeddedMessageProducer.queue);

        // 创建生产者
        embeddedMessageProducer.jmsConn = jmsConn;
        embeddedMessageProducer.session = session;
        embeddedMessageProducer.queue =  queue;
        embeddedMessageProducer.producer = messageProducer;
        this.messageProducerMap.put(queueName, embeddedMessageProducer);

        // 创建消费者
        MessageConsumer consumer = session.createConsumer(queue);
        // 监听消息（异步）
        consumer.setMessageListener(message -> {
            if (message instanceof TextMessage textMessage) {
                try {
                    consumeMessage.consumeMessage(textMessage.getText());
                    message.acknowledge();
                } catch (JMSException e) {
                    logger.error("", e);
                    try {
                        session.recover();
                    }
                    catch (JMSException ex)
                    {
                        logger.error("", ex);
                    }
                }
            } else {
                logger.error("Unknown message type: {}", message);
            }
        });
        // 启动消费者
        jmsConn.start();
        this.messageConsumerMap.put(queueName, consumer);
    }

    public void sendMessage(String queueName, String textMessage) throws JMSException {
        EmbeddedMessageProducer embeddedMessageProducer = this.messageProducerMap.get(queueName);
        TextMessage message = embeddedMessageProducer.session.createTextMessage(textMessage);
        embeddedMessageProducer.producer.send(embeddedMessageProducer.queue, message);
    }

    public void stopBroker() throws Exception {
        synchronized (this) {
            for (MessageConsumer messageConsumer : this.messageConsumerMap.values())
            {
                messageConsumer.close();
            }
            for (EmbeddedMessageProducer embeddedMessageProducer : this.messageProducerMap.values())
            {
                embeddedMessageProducer.producer.close();
                embeddedMessageProducer.session.close();
                embeddedMessageProducer.jmsConn.close();
            }

            if (connectionFactory != null) {
                connectionFactory.stop();
                connectionFactory = null;
            }
            if (brokerService != null && !brokerService.isStopped()) {
                brokerService.stopAllConnectors(new ServiceStopper());
                brokerService.stop();
                brokerService = null;
            }
        }
    }

//    public static void main(String[] args) throws Exception {
//        Logger logger = AppLogger.createLogger("testCDC", "INFO", "CONSOLE");
//
//        EmbeddedActiveMQ embeddedActiveMQ = new EmbeddedActiveMQ(logger);
//        embeddedActiveMQ.startBroker(26126);
//
//        MyConsumeMessage1 myConsumeMessage1 = new MyConsumeMessage1();
//        MyConsumeMessage2 myConsumeMessage2 = new MyConsumeMessage2();
//        embeddedActiveMQ.newMessageChannel("hello1", myConsumeMessage1);
//        embeddedActiveMQ.newMessageChannel("hello2", myConsumeMessage2);
//
//        embeddedActiveMQ.sendMessage("hello1", "1xx1");
//        embeddedActiveMQ.sendMessage("hello2", "2xx2");
//        Thread.sleep(5*1000);
//        embeddedActiveMQ.sendMessage("hello1", "1yy1");
//        embeddedActiveMQ.sendMessage("hello2", "2yy2");
//        Thread.sleep(5*1000);
//
//        embeddedActiveMQ.stopBroker();
//
//        System.out.println("sleep 2");
//        Thread.sleep(10*1000);
//    }
}
