package org.slackerdb.dbsyncer.source;

import org.slackerdb.dbsyncer.model.ChangeBatch;

@FunctionalInterface
public interface BatchConsumer {
    void accept(ChangeBatch batch) throws Exception;
}
