package org.slackerdb.dbsyncer.config;

import org.slackerdb.dbsyncer.DbSyncerConfigException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * dbsyncer.yml 加载入口：安全加载 → 手写绑定 → 交叉校验。
 */
public final class SyncConfigLoader {

    /** YAML 文本上限；防止把配置文件当成数据通道。 */
    public static final long MAX_YAML_BYTES = 1024 * 1024;

    private SyncConfigLoader() {
    }

    public static SyncConfig load(Path path) throws DbSyncerConfigException {
        if (path == null) {
            throw new DbSyncerConfigException("plugin-dbsyncer.config", "configuration path is required");
        }
        Path absolute;
        try {
            absolute = path.toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new DbSyncerConfigException("plugin-dbsyncer.config", "invalid path [" + path + "]", e);
        }
        if (!Files.isRegularFile(absolute)) {
            throw new DbSyncerConfigException("plugin-dbsyncer.config",
                    "file [" + absolute + "] does not exist or is not a regular file");
        }
        try {
            if (Files.size(absolute) > MAX_YAML_BYTES) {
                throw new DbSyncerConfigException("plugin-dbsyncer.config",
                        "file [" + absolute + "] exceeds " + MAX_YAML_BYTES + " bytes");
            }
            String text = Files.readString(absolute, StandardCharsets.UTF_8);
            return loadText(text, absolute.toString());
        } catch (IOException e) {
            throw new DbSyncerConfigException("plugin-dbsyncer.config", "cannot read [" + absolute + "]", e);
        }
    }

    public static SyncConfig loadText(String text, String source) throws DbSyncerConfigException {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit((int) MAX_YAML_BYTES);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(50);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        List<Object> documents = new ArrayList<>();
        try {
            for (Object document : yaml.loadAll(text)) {
                documents.add(document);
            }
        } catch (RuntimeException e) {
            throw new DbSyncerConfigException(source, "invalid YAML: " + rootMessage(e), e);
        }
        if (documents.isEmpty() || documents.getFirst() == null) {
            throw new DbSyncerConfigException(source, "empty YAML document");
        }
        if (documents.size() != 1) {
            throw new DbSyncerConfigException(source, "exactly one YAML document is required, found " + documents.size());
        }
        try {
            SyncConfig config = new ConfigBinder().bind(documents.getFirst());
            ConfigValidator.validate(config);
            return config;
        } catch (RuntimeException e) {
            throw new DbSyncerConfigException(source, rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
