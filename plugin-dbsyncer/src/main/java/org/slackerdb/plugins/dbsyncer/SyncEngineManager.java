package org.slackerdb.plugins.dbsyncer;

import com.lmax.disruptor.dsl.Disruptor;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import com.lmax.disruptor.RingBuffer;

import java.sql.Connection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncEngineManager {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private DebeziumEngine<ChangeEvent<String, String>> engine;
    private final RingBuffer<DebeziumEvent> ringBuffer;
    private Disruptor<DebeziumEvent> disruptor = null;
    private StartEngineRequest startEngineRequest;
    private List<SyncerRuleInfo> rules;
    private String syncerName;
    private String offsetFilePath;
    private Connection dbConnection;

    public Connection getDbConnection()
    {
        return this.dbConnection;
    }

    public void setDbConnection(Connection connection)
    {
        this.dbConnection = connection;
    }

    public SyncEngineManager(RingBuffer<DebeziumEvent> ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    public synchronized void startEngine(Properties props) {
        if (engine != null) return;

        this.engine = DebeziumEngine.create(Json.class)
                .using(props)
                .notifying((List<ChangeEvent<String, String>> records, DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer) -> {
                    // 这里的 records 是一个 List，可以使用 foreach
                    for (ChangeEvent<String, String> record : records) {
                        long sequence = ringBuffer.next();
                        try {
                            DebeziumEvent event = ringBuffer.get(sequence);
                            // 获取具体的 JSON 内容
                            event.setValue(record.value());
                            // 设置同步器名称用于后续规则匹配
                            if (this.syncerName != null) {
                                event.setSyncerName(this.syncerName);
                            }
                        } finally {
                            ringBuffer.publish(sequence);
                        }
                        // 标记该记录已处理完成
                        committer.markProcessed(record);
                    }
                    // 这一批次处理完后，执行位点提交（标记这批记录已成功放入队列）
                    committer.markBatchFinished();
                })
                .build();

        executor.execute(engine);
    }

    public synchronized void stopEngine() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ignored) {}
            engine = null;
        }
    }

    public boolean isRunning() {
        return engine != null;
    }

    public synchronized  void setDisruptor(Disruptor<DebeziumEvent> disruptor)
    {
        this.disruptor = disruptor;
    }

    public void setStartEngineRequest(StartEngineRequest startEngineRequest)
    {
        this.startEngineRequest = startEngineRequest;
        this.rules = startEngineRequest.rules;
        this.syncerName = startEngineRequest.syncer_name != null ?
                startEngineRequest.syncer_name : startEngineRequest.rule_name;
    }

    public List<SyncerRuleInfo> getRules() {
        return rules;
    }

    public void setRules(List<SyncerRuleInfo> rules) {
        this.rules = rules;
    }

    public String getOffsetFilePath() {
        return offsetFilePath;
    }

    public void setOffsetFilePath(String offsetFilePath) {
        this.offsetFilePath = offsetFilePath;
    }

    public String getSyncerName() {
        return syncerName;
    }

    public StartEngineRequest getStartEngineRequest() {
        return startEngineRequest;
    }
}
