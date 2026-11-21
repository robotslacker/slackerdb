package org.slackerdb.dbsyncer.source;

/**
 * 避免 source 包反向依赖 target 包：快照回调只暴露源表标识。
 */
public record TableMappingRef(String sourceSchema, String sourceTable) {

}
