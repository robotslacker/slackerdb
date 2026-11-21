# slackerdb-dbsyncer

`slackerdb-dbsyncer` 是内置于 dbserver 的 CDC 数据同步模块：

- **源**：PostgreSQL 逻辑复制（WAL / `pgoutput`）或 MySQL ROW binlog；
- **目标**：当前实例的**内嵌 DuckDB 实例库**；
- **方式**：先做存量快照，再持续应用 `INSERT / UPDATE / DELETE`；
- **配置**：一份 YAML 描述同步要求与逻辑；
- **控制**：`/dbsyncer/*` HTTP API 启动、停止、查看状态、热加载、重同步。

完整示例见 [dbserver/conf/dbsyncer.example.yml](../dbserver/conf/dbsyncer.example.yml)。

---

## 1. 能力与边界

### 支持

- PostgreSQL 10+（建议 12+）：publication / slot 自动维护，快照 + WAL 增量，LSN 检查点；
- MySQL 8.0 / 8.4：ROW binlog，快照 + binlog 增量，`(file, position)` 检查点；
- 表/列映射：include/exclude、目标表名、列裁剪、列改名、常量列、类型覆盖、主键覆盖；
- DML：INSERT 一律 append；有主键的 UPDATE/DELETE 按主键执行；无主键表跳过 UPDATE/DELETE 并告警；
  源表没有主键时可以用 `tables.include[].primaryKey` 指定业务主键，此时 UPDATE/DELETE 照常生效；
- DDL：PG 启动校验目标结构，运行期不处理 DDL，结构变化即放弃该表直到重启；MySQL 随 binlog 自动应用 DDL；
- 断点续传：检查点与目标数据同事务提交；PG 的检查点取事务结束 LSN（重启不会重放刚提交的事务）；
- resync：整作业粒度（清检查点、`DROP/CREATE` 全部目标表、全量快照）；不支持只重灌某张表（见 4.1）；
- 可观测：状态、位点、lag、行数、最近错误、最后失败 DDL；
- 热加载：`reload` 处理新增作业、更新当前未运行的作业；

## 2. 启用

前提：管理端口已启用（`port_x >= 0`），实例可写。

`dbserver.conf`：

```properties
plugin-dbsyncer.config=conf/dbsyncer.example.yml

# 可选
plugin-dbsyncer.autoStart=false
plugin-dbsyncer.maxJobs=16
plugin-dbsyncer.stopTimeoutMs=10000
plugin-dbsyncer.queryTimeoutMs=600000
```

### 2.1 `dbserver.conf` 配置项

| 键                                | 类型     | 默认       | 说明                                                           |
|----------------------------------|--------|----------|--------------------------------------------------------------|
| `plugin-dbsyncer.config`         | string | 无        | YAML 路径，相对 dbserver 工作目录或绝对路径；不配置则不启用模块                      |
| `plugin-dbsyncer.autoStart`      | bool   | `false`  | 模块启动时是否自动拉起 `autoStart=true` 的作业                             |
| `plugin-dbsyncer.maxJobs`        | int    | `16`     | 同时运行的作业数上限（1..1024）                                          |
| `plugin-dbsyncer.stopTimeoutMs`  | long   | `10000`  | 停止**全部**作业的总超时（毫秒，1000..3600000）                             |
| `plugin-dbsyncer.queryTimeoutMs` | long   | `600000` | 目标 DuckDB 单条语句上限（毫秒，`0` = 不限）；通过会话变量 `max_execution_time` 生效 |

未在上面列出的 `plugin-dbsyncer.*` 键会被当作配置错误（避免 `autoStart` 拼错后静默不生效）。
所有属性在读取 YAML、建状态表之前校验，非法配置不会留下副作用。

---

## 3. YAML 最小示例

```yaml
version: 1

connections:
  pg_app:
    type: postgres
    url: jdbc:postgresql://127.0.0.1:5432/appdb
    username: app_user
    password: change_me

targets:
  duck:
    schema: sync          # 目标Schema

jobs:
  - name: pg_orders
    source: pg_app
    target: duck
    mode: snapshot+cdc    # snapshot+cdc | snapshot | cdc
    autoStart: false
    replication:
      publication: sdb_pub_pg_orders
      slot: sdb_slot_pg_orders
    tables:
      include:
        - match: "public.orders"          # 精确；也支持 glob 或正则
          target: "orders"
          primaryKey: [id]
        - match: "public.orders{.*}"      # 动态表：orders_2026、orders_eu ...
          target: "orders{$1}"            # 分别写入 orders_2026、orders_eu ...
          primaryKey: [id]
          columns:
            exclude: [internal_note]
            rename:
              order_no: order_number
```

### 3.1 顶层结构

| 键             | 类型     | 必填 | 说明                                  |
|---------------|--------|----|-------------------------------------|
| `version`     | int    | 否  | 当前只支持 `1`                           |
| `defaults`    | object | 否  | 全局默认值（批、重连、快照等）                     |
| `connections` | map    | 是  | 源连接，key 为连接名；作业通过 `source` 引用       |
| `targets`     | map    | 是  | 目标预设，key 为目标名；作业通过 `target` 引用；至少一个 |
| `jobs`        | list   | 是  | 同步作业列表；至少一个                         |

### 3.2 `defaults`

| 键                         | 类型   | 默认         | 说明                                                  |
|---------------------------|------|------------|-----------------------------------------------------|
| `batch.maxRows`           | int  | `2000`     | 单个 SQL 块最多行数；所有块仍在同一目标事务内，不会中间提交                    |
| `batch.flushIntervalMs`   | long | `10000`    | 跨多个源事务攒批窗口；到点后统一提交并推进到最后一个事务位点                      |
| `batch.maxBufferedEvents` | int  | `100000`   | 缓冲事件上限；达到上限立即 flush，避免高吞吐时内存无限增长                    |
| `batch.maxBufferedBytes`  | long | `67108864` | 缓冲字节上限（按行值估算）；达到上限立即 flush                          |
| `batch.maxAttempts`       | int  | `3`        | 单批写入失败后的最大尝试次数；用尽即暂停作业（不推进检查点）                      |
| `batch.retryIntervalMs`   | long | `1000`     | 两次尝试之间的间隔                                           |
| `reconnect.maxAttempts`   | int  | `10`       | 源库断线连续重连次数                                          |
| `reconnect.intervalMs`    | long | `3000`     | 重连间隔                                                |
| `snapshot.mode`           | enum | `replace`  | `replace` 清空重灌 / `append` 追加 / `skipIfExists` 非空则跳过 |
| `snapshot.fetchSize`      | int  | `10000`    | 快照 JDBC fetch size，同时也是每次提交的行数（越大提交次数越少、越占内存）       |

### 3.3 `connections.<name>`

| 键             | 类型     | 必填 | 说明                                                               |
|---------------|--------|----|------------------------------------------------------------------|
| `type`        | enum   | 是  | `postgres`/`postgresql` 或 `mysql`                                |
| `url`         | string | 是  | JDBC URL；MySQL 建议带 `useCursorFetch=true&useServerPrepStmts=true` |
| `username`    | string | 是  | 源库账号                                                             |
| `password`    | string | 否  | 源库密码                                                             |
| `properties`  | map    | 否  | 额外 JDBC 属性，值为字符串                                                 |
| `replication` | object | 否  | 仅 PostgreSQL，见下表                                                 |

* MySQL 要求 `binlog_row_image=FULL`。
* `resync` 快照会执行 `FLUSH TABLES WITH READ LOCK` 来对齐"一致性快照点"和"binlog 位点"，
因此同步账号**必须**有 `RELOAD` 权限（README 第 9 节的授权已包含）。
* `binlog_transaction_compression` 必须为 `OFF`、`binlog_row_value_options` 必须为空，否则作业会在启动时直接报错。
* 源端配置了 `--binlog-do-db` / `--binlog-ignore-db` 时，被同步的库必须在允许范围内，否则启动直接报错
（而不是"连上但永远收不到事件"）。
* JDBC URL 建议带 `useCursorFetch=true`：没有它时驱动会把整个结果集读进内存，大表快照可能 OOM（模块只告警）。
* 同一个连接上不能有两个作业落到同一个 publication / 复制槽（派生名会归一化大小写和标点，
`a-b` 与 `a_b` 会撞名），配置校验阶段直接拒绝。

`replication`（PostgreSQL）：

| 键                   | 默认         | 说明                                 |
|---------------------|------------|------------------------------------|
| `publication`       | 按作业名派生     | publication 名，`[a-z0-9_]{1,63}`    |
| `slot`              | 按作业名派生     | 复制槽名，`[a-z0-9_]{1,63}`             |
| `statusIntervalMs`  | `10000`    | LSN 反馈/状态刷新间隔                      |

### 3.4 `targets.<name>`

| 键                  | 类型     | 必填    | 默认             | 说明                                                         |
|--------------------|--------|-------|----------------|------------------------------------------------------------|
| `schema`           | string | **是** | —              | 目标 schema                                                  |

DML 固定：INSERT 一律 append（`INSERT INTO`，不做主键冲突检查）；有主键的 UPDATE 使用 `INSERT OR REPLACE INTO`；
DELETE 按主键 `DELETE FROM`；无主键表跳过 UPDATE/DELETE 并告警。

写入失败语义：整批目标事务回滚后按 `batch.maxAttempts` 重试同一批（失败不提交，重试安全）；
重试用尽则**不推进检查点**、清空内存缓冲并暂停作业（`PAUSED`）。修复后重新 `start` 会从上一个成功提交的
检查点重放，**不会丢数据**；`lastError` 里能看到失败批次与原因。

UPDATE 里带有"未下发列"（PostgreSQL 未变化的 TOAST 值）时改为只更新事件里的列，
不会把目标表里未随事件下发的列写成 `NULL`；目标行不存在时按可用列补插一行。

### 3.5 `jobs[]`

| 键             | 类型     | 必填   | 默认             | 说明                                                     |
|---------------|--------|------|----------------|--------------------------------------------------------|
| `name`        | string | 是    | —              | 唯一；`[A-Za-z0-9_-]{1,64}`；用于 API 路径、slot 默认名、线程名        |
| `description` | string | 否    | 空              | 展示用                                                    |
| `source`      | string | 是    | —              | 引用 `connections` 的 key                                 |
| `target`      | string | 是    | —              | 引用 `targets` 的 key；一个作业只能一个                            |
| `mode`        | enum   | 否    | `snapshot+cdc` | `snapshot+cdc` / `snapshot` / `cdc`；`cdc` 不做快照、也不重建目标表 |
| `autoStart`   | bool   | 否    | `false`        | 模块启动时自动拉起；仍受 `plugin-dbsyncer.autoStart` 总开关约束         |
| `replication` | object | PG 否 | 继承连接           | 覆盖 `publication`/`slot`/`statusIntervalMs`             |
| `tables`      | object | 是    | —              | 表匹配、目标映射、列规则                                           |
| `snapshot`    | object | 否    | 继承 defaults    | 快照参数覆盖                                                 |

### 3.6 `tables` 与 `columns`

| 键                             | 说明                                                                          |
|-------------------------------|-----------------------------------------------------------------------------|
| `tables.include[]`            | 有序规则；`match` 支持精确、glob（`*`/`?`）、内联正则 `schema.table{.*}`、完整正则 `/^...$/`；命中即用 |
| `tables.include[].target`     | 目标表名模板：`{table}`、`{$1}`/`${1}`/`$1`；默认 `{table}`                            |
| `tables.include[].primaryKey` | 覆盖源主键（列名列表）；不写则用源库主键                                                        |
| `tables.include[].columns`    | 列规则，见下表                                                                     |
| `tables.exclude[]`            | 排除模式，优先于 include；与 `match` 使用同一套语法（`*`/`?`/`{}`/`/.../`）                    |
| `tables.caseSensitive`        | 默认 `false`；PG 存在大小写混用表名时设 `true`                                            |

`columns`：

| 键           | 说明                                            |
|-------------|-----------------------------------------------|
| `include`   | 只同步这些源列（保留源列顺序）                               |
| `exclude`   | 排除列；与 include 同时出现时先 include 再 exclude；不能排除主键 |
| `rename`    | 源列名 → 目标列名                                    |
| `constants` | 目标列名 → 常量值（只写目标，不回源）                          |
| `types`     | 目标列名 → DuckDB 类型覆盖；key 用改名后的目标列名              |

### 3.7 继承与优先级

- `snapshot`：作业级覆盖 `defaults`；
- `replication`：连接级给默认值，作业级只覆盖显式出现的键；
- `batch` 只有全局 `defaults.batch`，作业级没有 batch 覆盖；
- 目标配置只写在 `targets.<name>`，没有全局 `defaults.target`。

---

## 4. API

Base：`http://<host>:<port_x>/dbsyncer`

| 方法   | 路径                   | 参数                                       | 说明                                                                 |
|------|----------------------|------------------------------------------|--------------------------------------------------------------------|
| GET  | `/status`            | 无                                        | 模块状态、配置路径/哈希、作业数                                                   |
| GET  | `/jobs`              | 无                                        | 作业列表                                                               |
| GET  | `/jobs/{name}`       | 路径 `name`                                | 作业详情：状态、位点、lag、计数、表状态、lastError、lastFailedDdl                      |
| GET  | `/jobs/{name}/runs`  | 路径 `name`；query 可选 `limit`（默认 20，1..200） | 最近运行历史（`v$dbsync_run`，新到旧）                                         |
| POST | `/jobs/{name}/start` | 路径 `name`；body 可选 `resync`               | **异步启动**，立即返回 `202`；`resync` 默认 `false`                            |
| POST | `/jobs/{name}/stop`  | 路径 `name`；body 可选 `wait`、`timeoutMs`     | 停止（幂等）                                                             |
| POST | `/validate`          | body 可选 `content`                        | 校验 YAML；不传 `content` 则校验配置文件；合法 `200` / 非法 `400`（body 带结构化 errors） |
| POST | `/reload`            | 无                                        | 从磁盘热加载：仅新增/更新未运行作业；运行中作业跳过并日志提示                                    |

参数说明：

- 路径 `{name}`：作业名，必填；不存在返回 `404`。
- `start`：**不等待、无 `timeoutMs`**，固定异步；返回 `202 Accepted`。
  - `body` 可省略，或传 `{"resync":true}`。
  - `resync=false`：首次启动或缺少检查点时自动执行 resync；已有检查点则续传。
  - `resync=true`：删除并重建所有目标表，重新拉取全量快照，再进入增量同步。
  - resync 失败后检查点保持为空，下次 `start` 会自动重新 resync。
  - 运行中的作业再次传 `resync=true` 返回 `409`。
- `stop`：`body` 可省略。`wait` 默认 `true`；`timeoutMs` 默认 `10000`，最小 `1000`。
- `validate`：`content` 为 YAML 文本；为空时读取当前配置文件。
- `reload`：不需要请求体。

作业详情中的 `lastError` 是最近一次失败原因，`lastFailedDdl` 是最近一次 DDL 应用失败的 SQL 文本。
`startedAt` / `stoppedAt` 是本次运行的时间（未运行过为 `null`），`uptimeSeconds` 在运行结束后冻结、不再增长。

`/jobs` 与 `/jobs/{name}` 会访问源库取样 lag，因此**串行执行**：同一时刻只处理一个状态查询，
同时到达的请求最多等待 5 秒，仍排不上返回 `503`（避免轮询把源库连接数打满）。

### 4.1 resync

`resync` 提供整作业粒度：`POST /jobs/{name}/start {"resync":true}`，或首次启动、缺少检查点、
有表状态缺失（新增表、快照中途崩溃）、目标表被删除时自动触发。行为是：
清检查点 → `DROP/CREATE` 该作业全部目标表 → 全量快照 → 进入增量同步。

- 作业新增/删除表（`reload` 后重启）；
- 某张表快照未完成（进程崩溃、写入失败、`stop` 中断）；
- 目标表被外部删除或结构不一致（含 MySQL 停机期间的 DDL）；
- 需要重建某张大表或换 `snapshot.mode`。

失败与中断：resync 失败时检查点保持为空，下次 `start` 会自动重新 resync（先 DROP/CREATE，
不会残留半张表）；进程崩溃同理，重启时 `recoverInterruptedJobs()` 把作业标 `FAILED`，再次 `start` 重来。
检查点早于 binlog/WAL 保留期时只能整作业 resync；`mode: cdc` 没有快照，需要快照请改配置后整作业重建。

示例：

```bash
BASE=http://127.0.0.1:8080/dbsyncer

# 重建某个作业的全部目标表
curl -s -X POST $BASE/jobs/pg_orders/start -H 'Content-Type: application/json' -d '{"resync":true}'
```

其它调用示例：

```bash
BASE=http://127.0.0.1:8080/dbsyncer

curl -s $BASE/jobs
curl -s -X POST $BASE/jobs/pg_orders/start -H 'Content-Type: application/json' -d '{"resync":true}'
curl -s -X POST $BASE/jobs/pg_orders/stop  -H 'Content-Type: application/json' -d '{"wait":true}'
```

响应统一为：

```json
{"retCode":0,"retMsg":"OK","data":{}}
```

状态码：`200` 成功、`202` 过渡中、`400` 参数/配置非法、`404` 作业不存在、`409` 状态冲突、
`500` 内部/源/目标错误、`503` 停机中或状态查询排队超时。

---

## 5. 作业状态

```text
STOPPED → STARTING → SNAPSHOT → STREAMING → STOPPING → STOPPED
                              ↘ PAUSED / FAILED
snapshot-only: SNAPSHOT → COMPLETED
```

- `start` 对运行中作业幂等；`STOPPING` 时返回 409；
- 停止时先停读、停止攒批线程，再 flush 缓冲并关闭连接；
- 源库断开按 `defaults.reconnect` 重连，状态保持 `STREAMING`；连续失败后 `FAILED`；
- DML 批次失败：整批回滚 → 按 `batch.maxAttempts` 重试 → 仍失败则**不推进检查点**、清空缓冲、`PAUSED`；
- 目标语句卡住：DuckDB `max_execution_time`（`plugin-dbsyncer.queryTimeoutMs`）会中断它，
  这次写入按失败处理（重试 / 暂停），不会把作业线程永久挂住。

---

## 6. 检查点与一致性

- 检查点表位于 `"<实例库>".sysaux.v$dbsync_*`，**与目标数据同库**；
- 增量事件先按 `defaults.batch.flushIntervalMs`（默认 10 秒）攒批，跨多个源事务统一提交；检查点推进到该批最后一个事务位点；
- 增量批的所有 SQL 块与检查点写在同一个 DuckDB 事务里，**中间块不提交**；任一块失败会整批回滚；
- 回滚后按 `batch.maxAttempts` 重试；重试用尽则暂停并且**检查点停在上一个成功批次**（不跳位点、不丢数据）；
- 大事务按 `defaults.batch.maxRows` 拆成多个 SQL 块，仅用于控制单条语句大小，不会产生中间提交或半个事务；
- 快照失败只重做未完成的表；快照起点写进检查点，重启不会重新取当前位点。
- 检查点是"已应用到的结束位置"：PG 用事务结束 LSN（不是 commit LSN），MySQL 用事件的
  `next position`。这样重启从检查点继续时**不会重放刚提交的事务**（INSERT 是 append，重放会撞主键）。
- 任何需要快照的情形（显式 resync、无检查点、有表状态缺失、目标表被删）都走**整作业重建**；
  不做逐表补快照（会与重放重叠）。

---

## 7. 类型映射

目标表类型由 `TypeMapper` 从源库元数据推导，只输出受控的 DuckDB 类型，不会把源库原始类型字符串直接拼进 DDL。

优先级：`tables.include[].columns.types`（按目标列名覆盖） > `TypeMapper` 默认映射 > JDBC 类型兜底。

### 7.1 PostgreSQL → DuckDB

| PostgreSQL 类型                                   | DuckDB 类型                  | 说明                    |
|-------------------------------------------------|----------------------------|-----------------------|
| `bool`                                          | `BOOLEAN`                  |                       |
| `int2` / `smallint`                             | `SMALLINT`                 |                       |
| `int4` / `integer` / `serial`                   | `INTEGER`                  |                       |
| `int8` / `bigint` / `bigserial`                 | `BIGINT`                   |                       |
| `numeric(p,s)` / `decimal(p,s)`                 | `DECIMAL(p,s)`             | `p > 38` 截到 38        |
| `numeric` / `decimal`（无精度）                      | `DECIMAL(38,9)`            | 避免默认精度丢小数             |
| `float4` / `real`                               | `FLOAT`                    |                       |
| `float8` / `double precision`                   | `DOUBLE`                   |                       |
| `money`                                         | `DECIMAL(19,2)`            | 需要更高精度时用 `types` 覆盖   |
| `char` / `varchar` / `text` / `bpchar` / `name` | `VARCHAR`                  | 不保留长度                 |
| `bytea`                                         | `BLOB`                     |                       |
| `date`                                          | `DATE`                     |                       |
| `time`                                          | `TIME`                     |                       |
| `timetz`                                        | `VARCHAR`                  | 保留时区偏移文本              |
| `timestamp`                                     | `TIMESTAMP`                | 源库墙钟时间                |
| `timestamptz`                                   | `TIMESTAMP WITH TIME ZONE` | 按 UTC 瞬时值处理           |
| `interval`                                      | `VARCHAR`                  | 避免 DuckDB 类型兼容问题      |
| `uuid`                                          | `UUID`                     |                       |
| `json` / `jsonb`                                | `JSON`                     | 文本写入                  |
| `bit(1)`                                        | `BOOLEAN`                  | 位串单 bit               |
| `bit(n)` / `varbit`                             | `VARCHAR`                  | 位串文本                  |
| `inet` / `cidr` / `macaddr` / `xml`             | `VARCHAR`                  |                       |
| 数组（`int4[]` 等）                                  | `VARCHAR`                  | v1 保留 PostgreSQL 文本形式 |
| enum / 复合 / range / 几何                          | `VARCHAR`                  | 以文本落库                 |

### 7.2 MySQL → DuckDB

| MySQL 类型                                                 | DuckDB 类型       | 说明                                              |
|----------------------------------------------------------|-----------------|-------------------------------------------------|
| `tinyint` / `tinyint(1)`                                 | `TINYINT`       | 需要布尔语义时用 `types` 覆盖为 `BOOLEAN`                  |
| `tinyint unsigned`                                       | `SMALLINT`      | 0..255                                          |
| `smallint`                                               | `SMALLINT`      |                                                 |
| `smallint unsigned` / `mediumint` / `mediumint unsigned` | `INTEGER`       |                                                 |
| `int` / `integer`                                        | `INTEGER`       |                                                 |
| `int unsigned`                                           | `BIGINT`        | 0..2^32-1                                       |
| `bigint`                                                 | `BIGINT`        |                                                 |
| `bigint unsigned`                                        | `DECIMAL(20,0)` | 超出 BIGINT，保精度                                   |
| `decimal(p,s)`                                           | `DECIMAL(p,s)`  | `p > 38` 截到 38                                  |
| `numeric` / `decimal`（无精度）                               | `DECIMAL(38,9)` | 避免默认精度丢小数                                       |
| `float`                                                  | `FLOAT`         |                                                 |
| `double`                                                 | `DOUBLE`        |                                                 |
| `bit(1)`                                                 | `BOOLEAN`       |                                                 |
| `bit(n)`                                                 | `VARCHAR`       | 位串文本                                            |
| `date`                                                   | `DATE`          | 零日期需驱动参数 `zeroDateTimeBehavior=CONVERT_TO_NULL` |
| `datetime` / `timestamp`                                 | `TIMESTAMP`     |                                                 |
| `time`                                                   | `VARCHAR`       | MySQL TIME 可为负/超过 24h；需要时 `types` 覆盖            |
| `year`                                                   | `SMALLINT`      |                                                 |
| `char` / `varchar` / `text` / `enum` / `set`             | `VARCHAR`       | enum/set 已解码为成员文本                               |
| `binary` / `varbinary` / `*blob`                         | `BLOB`          |                                                 |
| `json`                                                   | `JSON`          | 解码 JSON 后文本                                     |
| `geometry` / `point` / `polygon` 等                       | `BLOB`          | WKB 原样                                          |
| `vector`（MySQL 9+）                                       | `VARCHAR`       | 未识别类型按文本兜底                                      |

### 7.3 通用规则

- 数值精度最多 38 位；`numeric/decimal` 精度超过 38 会截断，scale 不会大于 precision。
- 无符号 MySQL 整数会向上选择能容纳范围的类型，避免溢出。
- `time`、`timetz`、`interval`、`bit(n)`、数组等以 `VARCHAR` 保留文本，避免有损转换；需要类型化时用 `columns.types` 覆盖。
- 未识别类型按 JDBC 类型兜底，最终通常是 `VARCHAR`；不会拼接源库原始类型。
- `columns.types` 的 key 是**目标列名**（先按 `columns.rename` 转换），value 必须是合法 DuckDB 类型表达式。
- 类型覆盖请写 DuckDB 的规范名（`DECIMAL(10,2)`、`TIMESTAMP WITH TIME ZONE`）：启动结构校验按
  `information_schema.columns.data_type` 逐字比较，写 `NUMERIC(10,2)` 会被判定为"类型不一致"并放弃该表。
- 常量列类型由 `columns.types` 或字面量推导：`true/false` → `BOOLEAN`、整数 → `BIGINT`
  （超出 long 用 `DECIMAL(38,0)`）、小数 → `DECIMAL(p,s)`、科学计数法 → `DOUBLE`，其余 → `VARCHAR`。

## 8. DDL 行为

### 8.1 启动时结构校验（PG / MySQL）

作业启动、建立快照前，逐表比较源表（按 `tables` 规则映射后的目标结构）与目标 DuckDB 实际结构：

| 情况         | 行为                                                                       |
|------------|--------------------------------------------------------------------------|
| 源有、目标表不存在  | 记录 INFO；`snapshot+cdc`/`snapshot` 升级为整作业 `resync`（清检查点、重建全部表），`cdc` 只建空表 |
| 源有、目标结构一致  | 继续按检查点增量同步                                                               |
| 源有、目标结构不一致 | 记录 **WARN**，本次运行**放弃该表**，不写入、不应用后续变更                                     |

`mode: cdc` 从源端当前位点开始，不读快照、也不 `DROP` 目标表；第一次启动时目标表为空。

结构比较覆盖列名、列类型**和主键**；目标多列、少列、类型不一致、主键不一致都算不一致。
主键不一致必须重建（配置的 `primaryKey` 与目标表上的约束不一致时，upsert/delete 的定位假设不成立）。
被放弃的表在下次作业重启时会重新检查；修好目标结构（或调整 `tables` 配置）后重启即可恢复。
如果一个作业的所有表都被放弃，作业进入 `PAUSED`。

### 8.2 PostgreSQL 运行期

PostgreSQL 逻辑复制不传 DDL。运行期通过 `Relation` 消息发现源表结构变化：
- 新增列、删除列、改类型：记录 **WARN**，该表本次运行内**放弃**，后续行事件全部忽略；

### 8.3 MySQL 运行期

MySQL DDL 在 binlog 中，模块**自动应用**，不需要配置策略，也不设白名单：

- `ALTER TABLE ... ADD / DROP / MODIFY / CHANGE / RENAME COLUMN`，含 `FIRST` / `AFTER <col>` 的位置（列顺序会同步，行事件按位置解码）
- `ALTER TABLE ... RENAME TO new_table`
- `CREATE TABLE`：按 `match` 动态解析并建目标表，注册映射
- `RENAME TABLE`：新表名仍命中 `match` 时保留映射，并按 `target` 模板重命名目标表
- `DROP TABLE`（支持逗号分隔多表）
- `TRUNCATE TABLE`
- 索引/约束相关：`ADD/DROP INDEX|KEY|PRIMARY KEY|FOREIGN KEY|CONSTRAINT|CHECK` 忽略
- 非表对象：`CREATE/DROP/ALTER INDEX|VIEW|DATABASE|TRIGGER|FUNCTION|PROCEDURE|EVENT`、`USER|ROLE|TABLESPACE|SERVER|RESOURCE GROUP|SPATIAL REFERENCE SYSTEM|DIRECTORY`、`ALTER INSTANCE` 等一律忽略
- 列默认值/可空性：`ALTER COLUMN ... SET/DROP DEFAULT` 忽略
- 常见表选项（ENGINE/CHARSET/COLLATE/COMMENT/PARTITION/ALGORITHM/LOCK）忽略

**只有"命中被同步表、但子句无法翻译"的 DDL 才会抛错并暂停作业**；与本作业无关的对象级 DDL
（例如同实例里别的业务执行 `CREATE USER`）只记日志，不会影响同步。

DDL 应用期间会**暂停目标 flush**（先提交 DDL 之前的行事件），避免 DDL 与增量写入并发修改同一份目标结构。
binlog 里服务端追加的 `/* generated by server */` 注释会被忽略。

DuckDB 不允许修改**主键列**的类型：MySQL 对主键列做 `MODIFY`/`CHANGE` 会导致 DDL 失败并按上一条暂停，
需要先调整配置或手工重建目标表。

`CREATE TABLE` 只保证建表后的 binlog 行事件；表内已有行不会自动补快照。`RENAME TABLE`新名不再命中 `match` 时移除映射；冲突/重名不额外校验，由用户自行保证。

自动应用失败时记录 ERROR 日志并暂停，作业详情的 `lastFailedDdl` 保留失败的 DDL；
如果是"DDL 之前的行事件写入失败"导致的暂停，`lastFailedDdl` 不会被污染，`lastError` 保留真正的写入原因。

## 9. 源库准备

### PostgreSQL

```sql
ALTER SYSTEM SET wal_level = 'logical';
-- 按需调整 max_replication_slots / max_wal_senders
-- 修改后重启
SHOW wal_level;

ALTER ROLE sync_user WITH REPLICATION;
GRANT USAGE ON SCHEMA public TO sync_user;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO sync_user;
```

账号需要能创建 publication/slot；模块始终自动管理。无主键表自动执行 `REPLICA IDENTITY FULL`。

> PostgreSQL 不支持把逻辑复制槽创建在历史 LSN，也不能把已有槽回退。若槽丢失且已有检查点，模块直接失败，不会新建槽或静默从新位点启动；用户必须显式 `start` 并传 `resync=true` 重建目标后继续。

### MySQL

```sql
-- my.cnf: binlog_format=ROW, binlog_row_image=FULL, log_bin=ON
SHOW VARIABLES LIKE 'binlog_format';
SHOW VARIABLES LIKE 'binlog_row_image';

GRANT SELECT, RELOAD, SHOW VIEW ON appdb.* TO 'sync_user'@'%';
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'sync_user'@'%';
```

**重要**：如果源端配置了 `binlog_do_db`，被同步的库必须在列表内，否则 binlog不会记录该库的 DML。

---

## 10. 构建与测试

```bash
# 模块单测（不连接外部库；端到端测试默认被排除）
mvn -o -pl dbsyncer -am test

# 端到端测试（需要可连的 PostgreSQL / MySQL，会建/删自己的 schema、库、slot）
mvn -o -pl dbsyncer test -Ddbsyncer.it.excludedGroups=none

# 打包 dbserver（含 dbsyncer）
mvn -o -pl dbserver -am -DskipTests package
```

端到端测试默认指向内网测试实例，可用系统属性覆盖：

| 属性                                              | 默认                                                                        |
|-------------------------------------------------|---------------------------------------------------------------------------|
| `dbsyncer.it.pg.url` / `.user` / `.password`    | `jdbc:postgresql://192.168.183.147:5432/postgres` / `postgres` / `123456` |
| `dbsyncer.it.mysql.url` / `.user` / `.password` | `jdbc:mysql://192.168.183.147:3406/` / `root` / `123456`                  |

例如：`mvn -o -pl dbsyncer test -Ddbsyncer.it.excludedGroups=none -Ddbsyncer.it.pg.url=jdbc:postgresql://127.0.0.1:5432/postgres`。
