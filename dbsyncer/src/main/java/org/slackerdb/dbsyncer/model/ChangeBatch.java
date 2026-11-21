package org.slackerdb.dbsyncer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个或多个源事务聚合后的变更集合。
 */
public record ChangeBatch(List<ChangeEvent> events, TxPosition position) {

    public ChangeBatch(List<ChangeEvent> events, TxPosition position) {
        this.events = events == null ? new ArrayList<>() : events;
        this.position = position;
    }
}
