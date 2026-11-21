# dbsyncer 开发指南

本文面向维护者，说明 `dbsyncer` 模块的目录划分、核心类职责、主要流程和当前设计约束。

配置项和 API 的对外契约见 `README.md`；本文重点讲实现。

---

## 1. 模块定位

`dbsyncer` 是 dbserver 的内置模块，不是 PF4J 插件：

- 数据源：PostgreSQL 逻辑复制（`pgoutput`）、MySQL ROW binlog；
- 目标：当前 dbserver 实例的内嵌 DuckDB；
- 模式：先快照，再持续应用 INSERT / UPDATE / DELETE；
- 状态：检查点、表状态、运行记录保存在目标实例的 `sysaux.v$dbsync_*` 表；
- 管理：通过 Javalin 的 `/dbsyncer/*` 路由控制，不做鉴权。

模块不直接持有 dbserver 的 `DBInstanceX`，只依赖：

- `DuckDbHost`：复制宿主的 DuckDB 连接；
- `Javalin`：注册管理路由；
- 配置文件路径和 `plugin-dbsyncer.*` 属性。

---

## 2. 目录结构

```text
dbsyncer/
├─ README.md                 对外配置/API/运维说明
├─ devGuide.md               本文件
├─ pom.xml
└─ src/main/java/org/slackerdb/dbsyncer/
   ├─ DbSyncerModule.java    模块装配与生命周期
   ├─ DbSyncerConfigException.java
   ├─ api/                   管理 API
   ├─ config/                YAML 模型、绑定、校验
   ├─ engine/                作业生命周期、攒批、指标
   ├─ model/                 跨层共享的数据模型（事件、位点、表结构、映射、DDL 契约）
   ├─ source/                数据源抽象、快照读取、PG/MySQL 实现
   ├─ state/                 状态表访问
   ├─ target/                DuckDB 目标、表路由、写入
   └─ util/                  标识符转义、表名匹配、复制对象命名
```

依赖方向（编译期）：

```text
api ─→ engine ─→ source
             └→ target ─→ state
             └→ state
                     ↓
        model / config / util（更底层）
```

- `model` 与 `util` 不依赖模块内其它包；`config` 只依赖 `util`；
- `source`/`target` 依赖 `model`、`config`、`util`；`target` 额外依赖 `state`（目标写线程是状态表的事务写者）；
- **`source` 不依赖 `target`**：MySQL DDL 翻译器只通过 `model.DdlWriter`/`model.DdlAction`
  操作目标，`DuckDbWriter` 实现该接口；
- `engine` 是唯一同时依赖 `source`/`target`/`state` 的包；`api` 只依赖 `engine`、`config`、`state`。


### 2.1 `api`

| 文件               | 职责                                                                                         |
|------------------|--------------------------------------------------------------------------------------------|
| `SyncController` | 注册 `/dbsyncer/status`、`/jobs`、`/jobs/{name}`、`/jobs/{name}/runs`、`start`、`stop`、`validate`、`reload`；统一响应信封和状态码 |

`start` 是异步接口，固定返回 `202`，不等待快照或 CDC 启动；请求体只接受 `resync` 布尔值。

### 2.2 `config`

| 文件                  | 职责                                                                                                                                         |
|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `SyncConfig`        | 配置模型；`Defaults`、`ConnectionConfig`、`TargetConfig`、`JobConfig`、`TablesConfig`、`TableRule`、`ColumnRule`、`ReplicationConfig`、`SnapshotConfig` |
| `SyncConfigLoader`  | 读取 YAML、要求单文档、转交绑定和校验                                                                                                                      |
| `ConfigBinder`      | 手写严格绑定；未知键报错；密码只接受明文；处理默认值和作业级快照继承                                                                                                         |
| `ConfigValidator`   | 跨对象校验：作业名、引用、target schema、表规则、目标表归属                                                                                                       |
| `ConfigFingerprint` | 配置哈希，用于 `reload` 判断定义是否变化                                                                                                                  |
| `ConfigMerge`       | PG 连接级 `replication` 默认值 + 作业级覆盖                                                                                                           |

配置模型只保留当前支持的键。`targets.<name>` 只有 `schema`；不再有 `type`、`createSchema`、`createTable`、`conflict`、`includeMetadata`、`updateMissingRow` 等字段。

### 2.3 `engine`

| 文件                    | 职责                                                                     |
|-----------------------|------------------------------------------------------------------------|
| `JobManager`          | 作业注册表、`reload`、`maxJobs`、自动启动、全部停止                                     |
| `JobRuntime`          | 单作业状态机、快照、CDC、攒批、flush（含重试/暂停）、检查点、错误处理                              |
| `BatchBuffer`         | 跨源事务事件缓冲；按时间/事件数/字节数触发 flush；暂停时整体丢弃                                |
| `BatchBuffer.Pending` | flush 快照，含事件与结束位点                                                     |
| `JobState`            | `STOPPED/STARTING/SNAPSHOT/STREAMING/STOPPING/PAUSED/FAILED/COMPLETED` |
| `SyncMetrics`         | 无锁计数器和最近提交时间                                                           |

`JobRuntime` 里有两把锁：`stateLock` 保护状态机，`flushLock` 串行化"批写入"和"DDL"。
`applyDdl()` 持有 `flushLock` 执行"先 flush → 再 DDL"，因此定时 flush 线程在 DDL 期间自然被挡住，
不需要暂停/恢复线程池。

### 2.4 `model`

跨层共享的数据模型；本包不依赖模块内的其它包（`TargetTable` 用 `util.SqlIdentifiers` 拼限定名除外）。

| 文件                    | 职责                                                        |
|-----------------------|-----------------------------------------------------------|
| `ChangeEvent`         | 单条逻辑变更：INSERT/UPDATE/DELETE/TRUNCATE                        |
| `ChangeBatch`         | 一个源事务或多次 flush 聚合后的事件集合 + 结束位点                              |
| `TxPosition`          | 统一位点：PG LSN 或 MySQL `(file, position)`                      |
| `SyncPauseException`  | 需要暂停作业的业务异常（MySQL DDL 失败、写入重试用尽）                           |
| `SourceTable` / `SourceColumn` | 源表元数据                                             |
| `TargetTable` / `TargetColumn` | 目标表结构和列定义；`remove/rename` 供 DDL 翻译器就地维护                 |
| `TableMapping`        | 源表、目标表、作业名的组合；行映射与主键判断                                   |
| `TypeMapper`          | 源类型 → 受控 DuckDB 类型                                         |
| `DdlWriter` / `DdlAction` | 目标侧 DDL 操作与事务动作的最小契约；`DuckDbWriter` 实现它，`source` 只依赖接口 |

### 2.5 `source`

| 文件                             | 职责                                           |
|--------------------------------|----------------------------------------------|
| `SourceConnections`            | 按连接配置打开 JDBC 连接（驱动加载、凭据与属性合并、连接级覆盖）          |
| `TableMappingRef`              | 快照回调里只暴露源 schema/table，避免 source 反向依赖 target |
| `TableSnapshotReader`          | PG/MySQL 共用的快照流式读取与分块回调                       |
| `BatchConsumer`                | CDC 批次回调                                     |
| `SnapshotConsumer`             | 单表快照行回调                                      |
| `SnapshotTableConsumer`        | resync 全表一致性快照的 begin/rows/end 回调（只接收 `TableMappingRef`） |
| `FlushHandler`                 | 重连或 DDL 前要求 `JobRuntime` flush 缓冲的回调         |
| `DdlExecutor`                  | 源线程应用 DDL 的准入点：独占目标 flush + 提交 DDL 前的行事件（`model.DdlAction`）    |

#### PostgreSQL

| 文件                | 职责                                                                  |
|-------------------|---------------------------------------------------------------------|
| `PostgresSource`  | publication/slot 管理、快照、WAL 读取、LSN 反馈、无主键表 `REPLICA IDENTITY FULL`   |
| `PgOutputDecoder` | 解析 `pgoutput` 的 Begin/Commit/Relation/Insert/Update/Delete/Truncate |

- 使用 `pgoutput`、协议版本 1；
- 启动时自动创建/更新 publication、创建 slot（名字由 `util.ReplicationNames` 从作业名派生，配置校验阶段判重）；
- 无主键表自动设置 `REPLICA IDENTITY FULL`；
- 运行期 Relation 变化：WARN 并放弃该表，直到重启；
- 检查点存在但 slot 丢失：**直接失败**，不重建、不静默处理；用户显式 `resync`；
- slot 位点领先检查点：直接失败，避免跳过 WAL；
- WAL 用**阻塞** `stream.read()` + `withStatusInterval` 读取（不再 50ms 轮询）；
  `stop()` 会异步关闭复制连接并 `pg_terminate_backend` 掉本次 walsender，
  因为 pgjdbc 的阻塞读和 `close()` 会互相抢锁，直接 close 可能卡到下一次心跳。

#### MySQL

| 文件                  | 职责                             |
|---------------------|--------------------------------|
| `MySqlSource`       | binlog 连接、ROW 事件解码、快照、位点推进、重连  |
| `MySqlDdlApplier`   | 解析 binlog 中的 DDL 并映射到目标 DuckDB |
| `MySqlValueDecoder` | MySQL 二进制值转 Java/DuckDB 可绑定值   |

- 要求 `binlog_format=ROW`、`binlog_row_image=FULL`；`binlog_transaction_compression` 必须 OFF、
  `binlog_row_value_options` 必须为空（压缩事务/部分 JSON 更新当前不支持，`prepare()` 会直接拒绝）；
- 源端若配置了 `--binlog-do-db` / `--binlog-ignore-db`，`prepare()` 会对照 `Binlog_Do_DB`/`Binlog_Ignore_DB`
  校验被同步的 schema，不匹配直接报错（否则作业会"STREAMING 但零事件"）；
- JDBC URL 没有 `useCursorFetch=true` 时只告警：驱动会把整个结果集读进内存，大表快照可能 OOM；
- server-id 由 `连接串 + 作业名` 派生到 `100_000..900_100_000`（仍会与源库 `@@server_id` 比较）；
- 无主键表跳过 UPDATE/DELETE 并告警；
- 事务边界：`XID` 事件与 `COMMIT` 语句（非事务表只有后者）都会 flush；`COMMIT` 识别会先去掉注释、折叠空白；
- **重连前必须清空未提交的 `events`**：半个事务既不会提交、也不该保留，否则重放后同一批行会被应用两次；
- **`source().columns` 的顺序必须与 binlog `TABLE_MAP` 的列顺序一致**：行事件是按位置解码的，
  所以 `ADD/MODIFY/CHANGE ... FIRST|AFTER <col>` 必须把列插到真实下标（`MySqlDdlApplier.splitPosition`）；
- `resync` 快照用 `FLUSH TABLES WITH READ LOCK` 把"快照点"和"位点"放进同一个临界区
  （顺序：加锁 → 开一致性快照 → 读位点 → 解锁），需要 `RELOAD` 权限；否则会漏掉窗口内提交的事务；
- DDL 自动应用，且 DDL 与检查点在同一 DuckDB 事务中提交；应用前通过 `DdlExecutor` 独占目标写入；
- DDL 文本会先去掉 `/* ... */` 注释：MySQL 8 会给服务端生成的 DDL 追加 `/* generated by server */`，
  不处理会把最后一个标识符污染成"未映射的表"而静默跳过；
- 与本作业无关的对象级 DDL（`CREATE USER`、`ALTER INSTANCE`、`CREATE TABLESPACE`……）只记 WARN 忽略，
  只有"命中映射表但子句无法翻译"才暂停（否则同实例的无关 DDL 会误杀作业）。

### 2.6 `state`

| 文件               | 职责                                   |
|------------------|--------------------------------------|
| `SyncStateStore` | `sysaux` 状态表的建表、迁移、作业/检查点/表状态/运行记录读写 |

状态表：

- `v$dbsync_job`：作业状态、配置哈希、目标信息、最近错误、`started_at`/`stopped_at`
  （每次 `upsertJob` 都刷新 `started_at` 并清空 `stopped_at`；`markStopped` 让 `phase` 与 `state` 一致）；
- `v$dbsync_checkpoint`：LSN 或 binlog file/pos、已应用行数；
- `v$dbsync_table`：每张表的快照完成状态、最近操作、最近事件时间；
- `v$dbsync_run`：每次运行的历史记录；`/dbsyncer/jobs/{name}/runs` 读取，
  `recoverInterruptedJobs()` 会把服务重启时未结束的 run 闭合（`state=FAILED, error='server restart'`）。

**写连接归属**（DuckDB 不允许两个连接同时写同一张表）：

| 表 | 唯一写者 |
|----|---------|
| `v$dbsync_job` / `v$dbsync_run` | 状态连接（`DuckDbHost.duplicate()`） |
| 目标数据表 / `v$dbsync_checkpoint` / `v$dbsync_table` | `DuckDbTarget` 单写线程 |

因此 `saveCheckpoint` / `upsertTableState` / `clearCheckpoint` / `clearTableStates`
都要求传入目标写连接，`JobRuntime.clearState()` 用 `target.call(...)` 包住清理动作。读取可以用任一连接。

### 2.7 `target`

| 文件                             | 职责                                                    |
|--------------------------------|-------------------------------------------------------|
| `DuckDbHost`                   | 宿主 DuckDB 入口：复制连接、catalog 名（DuckLake 实例不特殊处理） |
| `DuckDbTarget`                 | 单线程写执行器；所有目标操作串行化到 `dbsync-writer`；连接上设 `max_execution_time`                    |
| `DuckDbWriter`                 | 数据批、快照、DDL、表重建、检查点写入；实现 `model.DdlWriter`；DDL 事务上下文按线程保存                  |
| `DuckDbDdlGenerator`           | 建 schema/表、truncate、列操作、结构/主键比较                          |
| `TableRouter`                  | 根据 `tables.include/exclude/columns` 生成 `TableMapping`（表名匹配用 `util.TablePattern`） |

`DuckDbTarget` 是并发边界：即使多个作业同时同步，所有 DuckDB 写操作都通过同一个单线程执行器。
`DuckDbWriter` 只在 `applyDdlTransaction()` 内部把"当前线程正处于 DDL 事务"记进 ThreadLocal，
其他线程（其他作业）看不到这个上下文；DDL 期间再调用 `prepare()`/`addColumn()` 等会就地执行，
不会再次提交到同一个单线程执行器而自锁。

### 2.8 `util`

| 文件                | 职责                                     |
|-------------------|----------------------------------------|
| `SqlIdentifiers`  | schema/table/column 的安全引用和 catalog 限定名 |
| `TablePattern`    | 表匹配（精确/glob/内联正则/完整正则）和目标名模板；include/exclude 共用 |
| `ReplicationNames`| PG publication / slot 名的派生与归一化           |

---

## 3. 启动与装配

```text
dbserver
  └─ new DbSyncerModule(app, logger, host, properties)
       ├─ 读取 plugin-dbsyncer.config
       ├─ SyncConfigLoader.load()
       ├─ host.duplicate() 建立状态连接
       ├─ SyncStateStore.initialize()
       ├─ recoverInterruptedJobs()
       ├─ new DuckDbTarget(host)
       ├─ new DuckDbWriter(...)
       ├─ new JobManager(...)
       └─ new SyncController(...)
```

`DbSyncerModule.start()`：

1. 注册 `/dbsyncer/*` 路由；
2. 若 `plugin-dbsyncer.autoStart=true`，调用 `JobManager.startAutoStart()`；
3. 否则所有作业保持 `STOPPED`。

`DbSyncerModule.stop()`：

1. `JobManager.stopAll(stopTimeoutMs)`；
2. 关闭 `DuckDbTarget`；
3. 关闭状态连接。

`plugin-dbsyncer.config` 缺失时模块完全不启用；配置存在但非法时宿主启动失败。

---

## 4. 作业生命周期

```text
STOPPED
  └─ POST /jobs/{name}/start (async)
       └─ STARTING
            ├─ 需要快照 → SNAPSHOT → STREAMING
            ├─ snapshot-only → SNAPSHOT → COMPLETED
            └─ 失败 → FAILED
  STREAMING
       ├─ DDL/不可恢复错误 → PAUSED
       └─ stop → STOPPING → STOPPED
```

`start` 总是异步：`JobRuntime.start(resync)` 只创建 runner 线程并返回 `STARTING`。

### 4.1 启动判定

`JobRuntime.runJob()` 先读取检查点和表状态：

```text
requestedResync = start(resync=true) || 无检查点
resync = snapshotMode && (requestedResync || 存在未完成快照的表)
```

`requestedResync` 为真时，会在解析源表之前清空检查点和表状态。这样即使后续解析/建表失败，下次 start 仍会自动 resync。

`mode: cdc` 的 `snapshotMode` 为 false：既不因为"表状态为空"触发 resync，也不会 `DROP` 目标表；
表缺失时只建空表，从复制槽/binlog 当前位点开始。`mode: snapshot` 完成一次后再次 start 会直接进入 `COMPLETED`。

### 4.2 表映射

1. `SourceConnections.open()` 打开源连接；
2. `TableRouter.resolve()` 读取源表/列/主键；
3. 根据 `tables.include` 的 match、exclude、列规则生成 `TableMapping`；
4. 目标表名根据 `target` 模板展开。

### 4.3 目标结构

- 非 resync：
  - 表缺失：`snapshot+cdc`/`snapshot` 加入强制快照集合，`cdc` 只建空表；
  - 结构不一致（列/类型/**主键**）：WARN，本次运行放弃该表；
  - 全部放弃：作业 PAUSED；
  - `DuckDbWriter.prepare()` 补建缺失的 schema/表。
- resync（仅 `snapshot+cdc`/`snapshot`）：跳过结构比较，`DuckDbWriter.rebuild()` 在一个事务内 DROP/CREATE 所有目标表。

主键也必须一致：`INSERT OR REPLACE` 按**表上的**主键判冲突、`DELETE WHERE` 按**配置里的**主键定位；
两者不一致时会出现重复行或误删多行，所以 `primaryKeyMismatch()` 把它算作结构不一致。

### 4.4 快照

非 resync 快照按表进行，每张表使用源端一致性读事务；resync 使用全表一致性快照：

- PG：同一个 `REPEATABLE READ` 事务读取所有表，并在事务内取 `pg_current_wal_lsn()` 作为 CDC 起点；
- MySQL：同一个 `REPEATABLE READ + CONSISTENT SNAPSHOT` 事务读取所有表，并取 binlog position 作为 CDC 起点。

两种快照都复用 `TableSnapshotReader`（读行、按 `snapshot.fetchSize` 分块、尾块回调），
块大小同时决定"每块提交的行数"：调大 `fetchSize` 会减少提交次数，也相应增加内存占用。
源实现只负责方言化的 `SELECT *` 与事务边界。`skipIfExists` 命中时该表整体跳过（不写行、不标完成）。

快照完成后才写入 resync 的检查点；快照失败则检查点保持为空，下次 start 重新 resync。普通启动的检查点可能在快照前写入起点，但未完成的表状态会让下次启动继续快照/触发 resync。

### 4.5 CDC 与攒批

源线程不直接写目标，而是把 `ChangeBatch` 放入 `BatchBuffer`：

```text
source callback
  └─ BatchBuffer.add(batch)
       ├─ 累计事件数
       ├─ 累计估算字节数
       └─ 记录最后位点
```

`JobRuntime` 的 flush 线程按 `defaults.batch.flushIntervalMs`（默认 10 秒）触发：

```text
BatchBuffer.take()
  └─ DuckDbWriter.apply()
       ├─ 一个 DuckDB 事务
       ├─ 按 maxRows 拆 SQL 块
       ├─ INSERT（append）/ INSERT OR REPLACE（UPDATE）/ 局部 UPDATE / DELETE
       ├─ 更新 v$dbsync_table
       ├─ 写 v$dbsync_checkpoint（最后位点）
       └─ commit
```

达到 `maxBufferedEvents` 或 `maxBufferedBytes` 也会立即 flush。

flush 失败（不丢数据）：

1. `DuckDbWriter.apply()` 整批回滚，检查点不动；
2. 同一批按 `batch.maxAttempts` 重试（间隔 `batch.retryIntervalMs`）；事务回滚后重试是安全的；
3. 重试用尽：`pauseAfterApplyFailure()` 停源、`buffer.clear()`（丢弃内存事件）、置 `PAUSED`，
   记录 `lastError`。重启后从**上一个成功提交的检查点**重放。

### 4.6 PG ACK

PG 的 `appliedLsn` 不在源读取线程推进，而是在目标批提交并写检查点后由 `JobRuntime.flushBuffer()` 调用 `source.acknowledge(lsn)`。

这样 PG 反馈的 LSN 永远不会领先于目标已提交数据。

### 4.7 MySQL DDL

DDL 在 binlog 中到达时：

1. `DdlExecutor.applyDdl()` 取得 `flushLock`（定时 flush 线程被挡住）；
2. 先 `flushBuffer()` 提交 DDL 之前的行事件；
3. `DuckDbWriter.applyDdlTransaction()` 开启一个 DuckDB 事务；
4. `MySqlDdlApplier.apply()` 修改目标结构；
5. 同一事务写 DDL 位点；
6. commit，释放 `flushLock`。

DDL 失败整体回滚并暂停作业，`lastFailedDdl` 保留 SQL。

### 4.8 停止

`POST /jobs/{name}/stop`：

1. 状态置 `STOPPING`；
2. 停止源读取（PG：异步关连接 + `pg_terminate_backend` 打断阻塞读；MySQL：`disconnect()`）；
3. 中断 runner；
4. runner finally 中停止 flush 线程；
5. flush 剩余缓冲（写入已进入失败暂停时不重试）；
6. 关闭源连接，状态置 `STOPPED`。

### 4.9 resync（只有整作业粒度）

`resync` 只做整作业重建：显式 `start(resync=true)`、没有检查点、有表状态缺失/未完成、
或 `snapshot+cdc`/`snapshot` 作业的目标表不存在时，都走 `clearState()` + `writer.rebuild()`
+ 全量快照。**不做逐表补快照**：逐表快照会与"从检查点重放"重叠，而 INSERT 是 append，
重叠的 INSERT 会撞主键（这是放弃单表 resync 的原因）。

实现要点：

- 判定集中在 `JobRuntime.runJob()`：`resync = snapshotMode && (requestedResync || needsSnapshot(states))`，
  目标表缺失时对 snapshot 模式同样升级为 `resync=true`；`cdc` 模式只建空表。
- 快照失败/中断不落检查点：`clearState()` 在快照前执行，失败后检查点保持为空，下次 `start` 重新 resync；
  半成品表会在下一次 `rebuild()` 中先被 DROP/CREATE 覆盖。
- 失败/中断的处置以 README 第 4.1 节为准。

---

## 5. 数据写入语义

| 源操作               | 目标操作                                          |
|-------------------|-----------------------------------------------|
| INSERT            | `INSERT INTO`，append（不做主键冲突检查，比 upsert 快得多） |
| UPDATE            | `INSERT OR REPLACE INTO`，按目标主键整行替换            |
| UPDATE（事件缺列）      | 只 `UPDATE` 事件里出现的列，保留目标表已有值（PG 未变化 TOAST）     |
| DELETE            | 按目标主键 `DELETE FROM`                           |
| TRUNCATE          | 目标表 `TRUNCATE`                                |
| 无主键 UPDATE/DELETE | 跳过并 WARN（表规则里的 `tables.include[].primaryKey` 覆盖会写进目标主键，因此不算无主键） |
| 主键变更 UPDATE       | 删除旧主键行 + 替换新主键行                               |

所有 DML 都在一个目标事务内完成，检查点与数据同事务提交。
INSERT 一律走 append 而不是 upsert：upsert 需要按主键做冲突检查，代价明显更高；正常 CDC 的重新投递
从上一个成功检查点开始，本来就不会重复。PG 的检查点用事务结束 LSN（`PgOutputDecoder.onCommit(endLsn)`），
所以重启不会重放刚提交的事务；MySQL 用事件的 `next position`。也正因为如此，不做逐表补快照
（否则快照会与重放重叠，append 的 INSERT 会撞主键）。

---

## 6. 线程模型

```text
Javalin 请求线程
  └─ JobManager / JobRuntime 状态操作

每个作业一个 runner 线程
  └─ 快照、源 CDC 读取

每个作业一个 flush 线程
  └─ 定时把 BatchBuffer 交给 DuckDbWriter

DuckDbTarget 单写线程
  └─ 所有 DuckDB 读写、DDL、检查点

PG/MySQL 源线程
  └─ 读 WAL/binlog，回调 BatchConsumer

PG 重连/MySQL 重连
  └─ 先调用 FlushHandler.flush()，再重连
```

关键并发约束：

- DuckDB 只有一个写线程；
- `JobRuntime.mappings` 是 volatile + `CopyOnWriteArrayList`，`byKey` 是 `ConcurrentHashMap`；
- DDL 和 flush 都通过 DuckDB 单写线程串行化，并且在 `JobRuntime` 层由 `flushLock` 互斥
  （DDL 持有锁期间 flush 线程等待）；
- 源连接重连前必须 flush，避免缓冲事件与重放重复；
- 同一个作业同一时刻只能有一个 runner：上一个 runner 的 finally 没跑完时 `start()` 直接报错；
- `/jobs`、`/jobs/{name}` 通过 `SyncController` 的锁串行化（lag 会新开源库连接），
  排队超过 5 秒返回 `503`。

---

## 7. 错误处理

| 场景               | 行为                                         |
|------------------|--------------------------------------------|
| 源库暂时断线           | 按 `defaults.reconnect` 重连，状态保持 `STREAMING` |
| 重连前缓冲            | 先 flush，再重连                                |
| DML 批次失败         | 整批回滚 → 按 `batch.maxAttempts` 重试同一批；**不推进检查点** |
| 写入重试用尽          | 停源、清空内存缓冲、`PAUSED`；重启从上一个成功检查点重放（不丢数据）      |
| 目标语句超时           | `max_execution_time` 中断语句（`SQLTimeoutException`），按写入失败处理 |
| MySQL DDL 失败     | 回滚 DDL+检查点，转 `PAUSED`，记录 `lastFailedDdl`   |
| MySQL DDL 前置 flush 失败 | 保留真正的写入错误（`lastError`），不改写成 DDL 失败、不污染 `lastFailedDdl` |
| MySQL 无关对象级 DDL | WARN 后忽略，不影响作业                            |
| MySQL 压缩事务/部分 JSON | `prepare()` 直接拒绝；运行期遇到 `TRANSACTION_PAYLOAD` 转 `PAUSED` |
| PG 运行期结构变化       | WARN，本次运行放弃该表                              |
| PG slot 丢失且已有检查点 | 直接 FAILED，不重建 slot；用户显式 resync             |
| PG slot 位点领先检查点  | 直接 FAILED，避免跳 WAL                          |
| 所有表因结构不匹配被放弃     | `PAUSED`                                   |
| MySQL 事件线程捕获的致命错误 | 直接失败，不再当作可重连故障                            |

---

## 8. 状态表与检查点

`SyncStateStore` 是唯一的状态表访问入口。

检查点写入有两个入口：

- `DuckDbWriter.apply()`：DML 批 + 表状态 + 检查点同事务；
- `DuckDbWriter.applyDdlTransaction()`：DDL + 检查点同事务。

不要在 `JobRuntime` 中用状态连接直接写检查点，否则会失去与目标数据的原子性。

`initialize()` 只做 `CREATE IF NOT EXISTS`：状态表结构就是当前版本，不再保留历史列迁移代码。

---

## 9. 扩展点

### 新增数据源

1. 在 `source` 下实现源读取和快照；
2. 产出 `ChangeEvent` / `ChangeBatch`；
3. 通过 `BatchConsumer` 交给 `JobRuntime`；
4. 用 `TxPosition` 表达位点；
5. 在 `SyncConfig.SourceType`、`TableRouter`、`JobRuntime` 中接入。

### 新增目标

当前目标固定为宿主 DuckDB。若要扩展：

- 保留 `DuckDbWriter` 的事务/检查点语义；
- 新增目标实现时必须保证“数据 + 检查点”原子提交；
- 不要在目标实现内部自行提交中间块。

### 新增 DDL 语法

修改 `MySqlDdlApplier`：

- 能安全映射的列/表操作：调用 `DuckDbWriter` 的 DDL 方法；
- 索引、约束、视图、库、触发器等非目标对象：显式记录并忽略；
- 无法安全翻译：抛出 `UnsupportedOperationException`，作业暂停，不静默跳过。

---

## 10. 本地验证

```bash
# 单测（不连外部库；端到端测试默认排除）
mvn -o -pl dbsyncer test

# 端到端测试（真实 PG/MySQL，默认指向 192.168.183.147 的测试实例）
mvn -o -pl dbsyncer test -Ddbsyncer.it.excludedGroups=none

# 打包 dbserver（含 dbsyncer）
mvn -o -pl dbserver -am -DskipTests package
```

单测不依赖外部库：`DuckDbWriterTest`、`DuckDbDdlGeneratorTest`、`DuckDbDdlApplierTest`、
`TablePatternTest`、`TypeMapperTest`、`PgOutputDecoderTest` 都只用内存 DuckDB 或纯字节缓冲。

端到端测试（`@Tag("integration")`，文件名带 `IntegrationTest`）用真实源库跑完整链路：

- `PostgresSyncIntegrationTest`：快照 → INSERT/UPDATE/DELETE → 未变化 TOAST → TRUNCATE；
- `MySqlSyncIntegrationTest`：快照 → DML → ALTER/CREATE/RENAME/DROP/TRUNCATE（DDL 自动应用）；
- 源库地址用系统属性覆盖（`dbsyncer.it.pg.*` / `dbsyncer.it.mysql.*`），默认值见 README；
- MySQL 测试库必须在源端 `--binlog-do-db` 列表内（默认 `jtls_sdc`），测试只建/删自己前缀的表。

外部 live 测试需要：

- PostgreSQL：`wal_level=logical`，账号可建 publication/slot，能终止自己的复制后端；
- MySQL：`binlog_format=ROW`、`binlog_row_image=FULL`，账号有 REPLICATION 权限。

---

## 11. 维护约定

1. 对外配置只写在 `dbsyncer/README.md` 和示例 YAML；不要在两个文档重复维护配置表。
2. 删除配置项时同时删除 `ConfigBinder` 的 `checkKeys`、模型字段、README 和示例。
3. 检查点必须与目标数据同事务，不能在 `JobRuntime` 层单独写。
4. 源重连、MySQL DDL 前必须 flush 缓冲。
5. 任何“静默跳过 WAL/事件”的优化都必须先证明不会漏数据；PG slot 丢失就是反例。
6. 不保留“暂时用不到”的公开方法与配置字段：无调用方的 API 直接删除，恢复成本远低于维护成本。
7. 跨层依赖只允许 `api/engine → source|target|state → model|config|util`（`target → state` 例外：
   目标写线程是状态表的事务写者）；`source` 的回调参数只用 `TableMappingRef`，
   不要为了少写一次查表而把 `TableMapping` 塞回接口签名。`source` 不得依赖 `target`：
   MySQL DDL 翻译器通过 `model.DdlWriter` 操作目标。
8. 两处相同逻辑（快照分块、连接属性、表名匹配）优先合并到 `source`/`target` 的一个实现里，
   不要在两个数据源各写一份。
9. 写入失败**永远不推进检查点**：要么重试成功，要么暂停等人处理。任何“跳过坏事务并推进位点”的改动都算数据丢失。
10. 新增/修改解析类逻辑（PG 消息、MySQL DDL）必须同时补单测和一条端到端用例；
    pgoutput 的 Truncate 格式、MySQL 的 `/* generated by server */`、`ADD COLUMN ... AFTER` 都是单测写错、
    端到端才发现的反例。
11. **MySQL 列顺序不变量**：`mapping.source().columns` 必须与 binlog `TABLE_MAP` 的列顺序逐一对应
    （行事件按位置解码）。任何改列序的 DDL 都要同步维护这个顺序，端到端用例必须覆盖 `AFTER/FIRST`。
12. **快照位点与快照点必须原子**：MySQL 的位点读取必须在 `FLUSH TABLES WITH READ LOCK` 保护下、
    与 `START TRANSACTION WITH CONSISTENT SNAPSHOT` 一起完成；不要退回"先开快照再读位点"。
13. INSERT 一律 append，不要为了"至少一次投递"改成 upsert：检查点从上一个成功批之后继续，
    不会重放已提交事务（PG 用事务结束 LSN）；需要快照时走整作业 resync，不做逐表补快照。
14. 新增 `plugin-dbsyncer.*` 属性时必须同时加进 `DbSyncerModule.KNOWN_PROPERTIES`，
    否则 dbserver 启动会报"unknown dbsyncer property"；所有属性在动数据库之前解析。
15. 目标表结构校验必须同时比较列、类型和主键（`DuckDbDdlGenerator.schemaMismatch` +
    `primaryKeyMismatch`）；不要只比列，否则 upsert/delete 的定位假设会和表上的约束脱节。
