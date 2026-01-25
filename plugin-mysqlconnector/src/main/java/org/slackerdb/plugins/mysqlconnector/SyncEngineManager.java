package org.slackerdb.plugins.mysqlconnector;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import com.lmax.disruptor.RingBuffer;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncEngineManager {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private DebeziumEngine<ChangeEvent<String, String>> engine;
    private final RingBuffer<BinlogEvent> ringBuffer;

    public SyncEngineManager(RingBuffer<BinlogEvent> ringBuffer) {
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
                            BinlogEvent event = ringBuffer.get(sequence);
                            // 获取具体的 JSON 内容
                            event.setValue(record.value());
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
            } catch (Exception e) {
                e.printStackTrace();
            }
            engine = null;
        }
    }

    public boolean isRunning() {
        return engine != null;
    }
}
