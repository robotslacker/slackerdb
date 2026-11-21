# offline-package-maven-plugin 开发文档

> 适用版本：`1.0.0`。本文面向维护者，说明插件解决的问题、模块结构、关键设计、扩展点与调试方法。
> 使用侧步骤（生成离线包、在离线机构建）见使用方项目根目录的离线包章节；
> 本文第 2 节只给出维护者最常用的最短命令。

---

## 1. 模块定位

`offline-package-maven-plugin` 是一个**独立构建工具**，不是产品模块。它把一次联网构建所需的
Maven 依赖（以及可选的 npm 依赖）收集成一个自包含的离线包，让目标机器可以在 `-o`（offline）模式下完成
`clean package`。

| 项 | 值 |
|---|---|
| 坐标 | `org.robotslacker.tools:offline-package-maven-plugin:1.0.0` |
| `packaging` | `maven-plugin` |
| `goalPrefix` | `offline-package` |
| Goal | `collect`、`verify` |
| 字节码目标 | Java 17（`maven.compiler.release=17`） |
| Maven API | 3.9.16 |
| 父 POM | 无。刻意不继承产品根 POM |
| 进入产品 reactor | 仅 `-Poffline-package` |
| 产物 | `offline/` 目录：两个 zip、manifest、校验和等 |

### 1.1 两个 goal

| Goal | 作用 | 是否写文件 |
|---|---|---|
| `collect` | 解析当前 reactor 的 Maven/npm 闭包，生成 `offline/` 离线包 | 是 |
| `verify` | 用 `javarepo` 作为本地仓库，在 offline 会话中重新解析 reactor，并审计 POM 闭包 | 只写 `verify-report-*.txt` |

### 1.2 为什么是独立 POM

产品根 POM 有项目级 `<dependencies>`（fastjson2、logback、JUnit 等）、JDK 21 enforcer 和大量插件配置。
插件只需要 Maven API，独立 POM 可以：

- 避免把产品依赖带进 Maven 插件的类加载器，尤其是 slf4j/logback 冲突；
- 允许插件自身用 Java 17 构建和运行，不抬高使用门槛；
- 让插件可以被单独 `install` 到本地仓库，再被根 POM 的 `-Poffline-package` 调用。

**不要**把该模块改成继承产品根 POM；这会让插件构建受产品 reactor 约束，也会改变它的依赖闭包。

### 1.3 非目标

- 不是“把 Maven Central 全量镜像下来”：只收集**当前 reactor 真正需要**的依赖，加上少量落选版本保险。
- 不负责构建产品：只准备依赖仓库。
- 不实现 npm 的依赖解析：lockfile 是事实源；tarball 先查本地 npm 缓存，只有本地缺失时才执行 `npm pack`。

---

## 2. 快速开始

### 2.1 构建、测试、安装

```bash
# 编译 + 跑单元测试（PomGraphTest）
mvn -f offline-package-maven-plugin/pom.xml clean package

# 安装到本地仓库，供 -Poffline-package 调用
mvn -f offline-package-maven-plugin/pom.xml install
```

必须先 `install`。根 POM 的 `offline-package` profile 会在 `validate` 阶段调用 `collect`，此时插件必须已经
存在于本地仓库；不能指望“同一次 reactor 构建里先编译插件、再执行插件”。

### 2.2 生成离线包

在仓库根目录：

```bash
mvn -Poffline-package validate
```

常用变体：

```bash
# 覆盖已有输出（默认遇到已存在目录会失败）
mvn -Poffline-package validate -Doffline.clean=true

# 只做 Maven、不做 npm；只跑一个平台；保留未压缩目录，便于检查
mvn -Poffline-package validate \
  -Doffline.npm.skip=true \
  -Doffline.platforms=windows-x86_64 \
  -Doffline.zip=false \
  -Doffline.clean=true
```

### 2.3 校验离线仓库

先解压 `javarepo.zip`（注意不要多解压一层），然后在离线机上：

```bash
# 1. 用包内仓库构建并安装插件（源码包内包含本模块）
mvn -o -f offline-package-maven-plugin/pom.xml install \
  -Dmaven.repo.local=/tmp/m2 \
  -Dmaven.repo.local.tail=<offline>/javarepo

# 2. 校验 javarepo
mvn -o -N org.robotslacker.tools:offline-package-maven-plugin:1.0.0:verify \
  -Dmaven.repo.local=/tmp/m2 \
  -Dmaven.repo.local.tail=<offline>/javarepo \
  -Doffline.dir=<offline>
```

`verify` 会把 `offline/javarepo` 当作本地仓库，重新解析当前 reactor 的依赖，并审计整个仓库的 POM 闭包。
报告写到 `offline/verify-report-<os>-<arch>.txt`。

> `/tmp/m2` 只是“可写主仓库”的占位；Windows 上换成 `%TEMP%\m2` 等可写目录即可。`<offline>` 指离线包解压后
> 的目录（其中直接包含 `javarepo/` 和 `npmrepo/`）。

> `javarepo` 不包含插件自身，因为插件也是 reactor 模块（见 §4.2）。上面的第 1 步就是为此准备的：离线机
> 先从源码包安装插件，再执行 `verify`。

### 2.4 参数

| Maven 属性 | Mojo 字段 | 默认值 | 说明 |
|---|---|---|---|
| `offline.dir` | `outputDirectory` | `${project.basedir}/offline` | 输出目录。staging 目录建在它的同级 |
| `offline.clean` | `clean` | `false` | 输出目录已存在时是否替换；`false` 直接失败 |
| `offline.zip` | `zip` | `true` | 是否生成 `javarepo.zip` / `npmrepo.zip`，并删除未压缩目录 |
| `offline.npm.skip` | `skipNpm` | `false` | 跳过整个 npm 采集 |
| `offline.npm.tarballs` | `npmTarballs` | `true` | 是否生成 tarball。`true` 时优先复用本地缓存，缺失的包才执行 `npm pack`；`false` 只生成包清单 |
| `offline.npm.executable` | `npmExecutable` | 空 | npm 可执行文件。空值时 Windows 用 `npm.cmd`，其他平台用 `npm` |
| `offline.npm.cache.dirs` | `npmCacheDirs` | 空 | 额外的本地 npm 缓存目录，逗号或分号分隔（见 §4.7）；不会因为列了目录就联网 |
| `offline.npm.cache.global` | `npmGlobalCache` | `true` | 是否把本机 npm 缓存（`npm config get cache`）也作为复用来源 |
| `offline.npm.allow.empty` | `npmAllowEmpty` | `false` | 采集不到任何 npm 依赖时是否只记 warning。`false`（默认）直接失败：空的 `npmrepo` 看起来像成功，实际离线机一个前端依赖都没有（见 §4.7） |
| `offline.candidate.jars` | `candidateJars` | `true` | 是否打包“落选版本”的 jar（见 §4.6） |
| `offline.platforms` | `platformIds` | `windows-x86_64,linux-amd64,linux-arm64` | 逗号分隔的目标平台 id |

`offline.platforms` 只接受 `Platform.DEFAULTS` 里的 id；未知值会以 `MojoFailureException` 失败。

### 2.5 产物

`offline.zip=true`（默认）时：

| 文件 | 内容 |
|---|---|
| `javarepo.zip` | 干净的 Maven 本地仓库，顶层目录为 `javarepo/` |
| `npmrepo.zip` | npm cache 根 + tarball 镜像，顶层目录为 `npmrepo/` |
| `manifest.json` | 机器可读清单（schemaVersion 1） |
| `MANIFEST.txt` | 人读摘要 |
| `gaps.txt` | 需要人工确认的 warning；没有则为 `No gaps recorded.` |
| `README-offline.md` | 发给离线机的使用说明，由插件生成 |
| `checksums.sha256` | `javarepo.zip` / `npmrepo.zip` 的 SHA-256 |

`offline.zip=false` 时保留 `javarepo/`、`npmrepo/` 目录，`checksums.sha256` 改为覆盖目录内所有文件。
`checksums.sha256` **不覆盖** manifest、README、gaps 等元数据文件。

---

## 3. 模块结构

### 3.1 目录树

```text
offline-package-maven-plugin/
├── pom.xml                          # 独立 POM（不继承产品根 POM）
├── README.md                        # 本文
└── src/
    ├── main/java/org/robotslacker/tools/offline/
    │   ├── CollectMojo.java         # goal collect：编排 + staging + 替换输出
    │   ├── VerifyMojo.java          # goal verify：离线解析 + POM 闭包审计
    │   ├── MavenCollector.java      # Maven 侧采集核心
    │   ├── PomGraph.java            # POM 引用图（父/import/relocation/依赖/profile）
    │   ├── NpmCollector.java        # npm 侧采集核心
    │   ├── ManifestWriter.java      # manifest/摘要/gaps/README/校验和
    │   ├── ZipPackager.java         # 目录 → zip
    │   ├── Platform.java            # 三个目标平台定义
    │   ├── ArtifactRecord.java      # 已解析工件的坐标、文件与仓库相对路径
    │   ├── ProjectGavFilter.java    # 解析时排除 reactor 自身模块
    │   └── OfflineLog.java          # 与 Maven Log 解耦的最小日志接口
    └── test/java/org/robotslacker/tools/offline/
        ├── NpmCollectorTest.java    # 本地 npm 缓存复用（不启动 npm）
        └── PomGraphTest.java        # POM 引用闭包的单元测试
```

所有类在同一个包 `org.robotslacker.tools.offline` 下。模块规模小、职责集中，刻意不拆多包；纯内部协作类
尽量包私有（`PomGraph`、`ProjectGavFilter`），Mojo、collector、`Platform`、`ArtifactRecord`、`OfflineLog`
等需要跨类/跨测试使用的类型才是 public。

### 3.2 类职责

| 类 | 职责 | 关键点 |
|---|---|---|
| `CollectMojo` | 解析参数、建 staging、依次调用 Maven/npm 采集、打 zip、写 manifest、替换输出 | `aggregator=true`；只在根项目执行；失败保留 staging |
| `VerifyMojo` | 用 `javarepo` 建 offline 会话，逐项目解析依赖，审计 POM 闭包 | 报告按 os/arch 命名；profile-only 缺口只告警 |
| `MavenCollector` | 三平台循环采集依赖/插件/ surefire provider；展开原生 family；物化仓库 | 每个平台独立 session 缓存；POM 缺口直接失败 |
| `PomGraph` | 从一个 POM 找出所有会被 Maven 读取的 POM 引用 | 父链、import BOM、relocation、compile/runtime 依赖、profile 依赖 |
| `NpmCollector` | 读 lockfile、按平台筛选、复用本地 npm 缓存、必要时 `npm pack`、校验 integrity、写 `packages.json` | lockfile 是事实源；本地命中不启动 npm |
| `ManifestWriter` | 统计文件数/大小，写 `manifest.json`、`MANIFEST.txt`、`gaps.txt`、`README-offline.md`、`checksums.sha256` | manifest 是稳定契约，字段变更需同步本文 |
| `ZipPackager` | 把目录压成 zip，保留顶层目录名 | `BEST_SPEED`；Zip64 由 JDK 自动处理 |
| `Platform` | 三个目标平台的 Maven/npm 属性 | `id` 是外部参数；`osName`/`osArch`/`npmOs`/`npmCpu`/`glibc` 供两个 collector 共用 |
| `ArtifactRecord` | 工件的 GAV、classifier、extension、文件、来源 | `key()=gav:classifier:extension`；`repositoryPath()` 生成仓库路径 |
| `ProjectGavFilter` | 依赖图过滤器，排除 reactor 模块 | 避免 Maven 去远程下载尚未构建的本项目模块 |
| `OfflineLog` | `info`/`warn` 两个方法 | 让 collector 不依赖 Maven `Log`，便于单测和复用 |

### 3.3 数据流

```text
mvn -Poffline-package validate
  └─ CollectMojo.execute()
       ├─ parsePlatforms()
       ├─ staging = <output 父目录>/.<输出目录名>.staging-<yyyyMMdd-HHmmss>/
       ├─ MavenCollector.collect(staging/javarepo)
       │    ├─ for each platform:
       │    │    ├─ collectDependencies()      # reactor 各模块的依赖
       │    │    ├─ collectPlugins()           # build/plugins + lifecycle + clean
       │    │    └─ collectSurefireProvider()  # surefire-junit-platform
       │    ├─ expandPlatformFamilies()        # 原生 classifier/artifact 三平台补齐
       │    └─ materialize()                   # 复制 jar + POM 闭包 + 落选 jar
       ├─ NpmCollector.collect(staging/npmrepo)
       ├─ ZipPackager × 2
       ├─ ManifestWriter.write()
       ├─ delete staging/javarepo, staging/npmrepo   # 仅 zip=true
       └─ replace output/ with staging/
```

`VerifyMojo` 不走 `CollectMojo`，它直接读取已有 `javarepo`：

```text
mvn ...:verify
  └─ VerifyMojo.execute()
       ├─ 检查 <offline>/javarepo 存在
       ├─ 构造 offline RepositorySystemSession（local repo = javarepo）
       ├─ for each project: ProjectDependenciesResolver.resolve()
       └─ auditPomClosure(): 遍历 javarepo 中所有 .pom，检查引用目标是否存在
```

### 3.4 POM 结构

`pom.xml` 是独立的，关键内容如下：

| 类别 | 依赖/插件 | 作用 |
|---|---|---|
| packaging | `maven-plugin` | 产出 Maven 插件描述符 |
| API（provided） | `maven-plugin-api`、`maven-core`、`maven-model`、`maven-artifact` 3.9.16 | Mojo、项目模型、依赖解析入口 |
| API（provided） | `maven-resolver-api`、`maven-resolver-util` 1.9.27 | Aether 会话、工件/依赖解析、过滤器 |
| API（provided） | `maven-plugin-annotations` 3.15.1 | `@Mojo`/`@Parameter`/`@Component` |
| 运行期 | `jackson-databind` 2.18.2 | 读写 `package-lock.json`、`packages.json`、`manifest.json` |
| 测试 | `junit-jupiter` 6.0.3 | `PomGraphTest` |
| 构建 | `maven-compiler-plugin` 3.15.0 | `release=17` |
| 构建 | `maven-plugin-plugin` 3.15.1 | 生成插件描述符；`goalPrefix=offline-package` |
| 构建 | `maven-surefire-plugin` 3.5.5 | 跑单元测试 |

所有 Maven API / resolver 依赖都是 `provided`：它们由运行插件的 Maven 进程提供，不打进插件 jar，
避免与 Maven 自身版本冲突。新增依赖前先判断它是否已由 Maven 提供。

---

## 4. 关键设计

### 4.1 三个目标平台

平台定义在 `Platform.DEFAULTS`：

| id | os.name | os.arch | os.detected.classifier | npm os/cpu |
|---|---|---|---|---|
| `windows-x86_64` | Windows 10 | amd64 | windows-x86_64 | win32 / x64 |
| `linux-amd64` | Linux | amd64 | linux-x86_64 | linux / x64 |
| `linux-arm64` | Linux | aarch64 | linux-aarch64 | linux / arm64 |

每个平台都用独立的 `DefaultRepositorySystemSession`：

- 覆盖 `os.name`、`os.arch`、`os.version`、`os.detected.name`、`os.detected.arch`、
  `os.detected.classifier`（同时写入 system 和 user properties）；
- **必须**换新的 `RepositoryCache` / `SessionData`。POM model 和 descriptor 结果会按 session 缓存，跨平台复用
  会让第二个平台直接读到第一个平台的 model，从而漏掉平台相关依赖。

### 4.2 Maven 依赖闭包

`MavenCollector.collectDependencies()` 对 reactor 中每个项目调用 `ProjectDependenciesResolver.resolve()`：

- scope 过滤：`compile`、`runtime`、`test`、`provided`，排除 `system`；
- `ProjectGavFilter` 排除 reactor 自身的 GAV（模块产物不在 `javarepo`，由源码包在离线机现场构建）；
- 任何 unresolved dependency 或 collection error 都直接抛出，不做“尽力而为”。

采集范围包含 test/provided，因为离线构建要能跑测试；这也意味着这些依赖的 POM 会被纳入闭包。

### 4.3 插件与 surefire 闭包

离线 `mvn package` 不只解析 `<dependencies>`，还要解析构建插件。`collectPlugins()` 处理三类来源：

1. **项目声明的插件**：`project.getBuildPlugins()`。版本为空时从 `pluginManagement` 补齐；仍为空直接失败。
2. **packaging 对应的 lifecycle 插件**：通过 `session.getContainer().lookup(LifecycleMapping, packaging)`
   取 `default` 生命周期各 phase 的插件坐标。
3. **clean 插件**：`LifecycleMapping` 不包含 clean 生命周期，所以显式补
   `maven-clean-plugin`，版本取 POM 中的声明，否则回退 `3.4.1`。

对每个插件：

- 用 `collectWithTransitives(gav, "plugin-runtime")` 解析插件 artifact 及其运行期依赖；
- 通过 `MavenPluginManager.getPluginDescriptor()` 拿到 descriptor，把插件 artifact 和
  `descriptor.getArtifacts()` 纳入仓库；
- 插件 POM 里 `<dependencies>` 声明的依赖（例如 asciidoctor 的 JRuby/PDF 依赖）不一定出现在
  `PluginDescriptor.getArtifacts()`，因此再按 `plugin.getDependencies()` 逐个解析传递闭包；
- 跳过插件自身（`org.robotslacker.tools:offline-package-maven-plugin`）。

另外 `collectSurefireProvider()` 会对每个用到的 surefire 版本解析
`org.apache.maven.surefire:surefire-junit-platform:<version>`。Surefire 在运行期才加载这个 provider，
常规依赖解析不会把它算进来；不显式收集，离线跑测试就会失败。

### 4.4 POM 描述符闭包

**为什么不能只复制最终 classpath 上的 jar：**

Maven 在依赖收集阶段会读取每个候选节点的 POM，包括被“就近原则（nearest-wins）”淘汰的版本；还会读取父 POM、
`dependencyManagement` 里 import 的 BOM、relocation 指向的 POM。在线构建缺什么就临时下载；离线构建只会打印

```text
[WARNING] The POM for ... is missing, no dependency information available
```

然后继续，产出不可信的结果。因此 `javarepo` 必须包含完整的 **POM 引用闭包**。

`PomGraph.references()` 产生五类引用：

| Kind | 来源 | 是否要求目标 POM 必须存在 |
|---|---|---|
| `PARENT` | `<parent>` | 是 |
| `IMPORT` | `dependencyManagement` 中 `type=pom, scope=import` | 是 |
| `RELOCATION` | `distributionManagement/relocation` | 是 |
| `DEPENDENCY` | POM 顶层 `<dependencies>` | 是 |
| `PROFILE_DEPENDENCY` | `<profiles>/<profile>/<dependencies>` | collect 时打包；缺失在 verify 中只告警 |

规则细节：

- 只跟随 `compile` / `runtime` 依赖，跳过 `test`、`provided`、`system`、`optional`。这与 Maven 收集传递依赖
  的行为一致；reactor 项目自己的 test/provided 直接依赖已经由 `collectDependencies()` 单独解析并采集，
  不会因为这里跳过而缺失。
- 依赖没有 version 时，通过当前 POM 的**有效 dependencyManagement** 补齐：继承父链 → import 的 BOM
  （先 import 的优先）→ 本 POM 自己的条目。
- 属性插值支持父链继承和 `project.*` / `pom.*` / 旧式 `groupId`/`version`/`artifactId` 别名；
  插值最多 20 轮，仍有未解析的 `${...}` 就记为 problem。
- profile 依赖也会被收集。Maven 会按 OS/JDK/用户属性自行激活 profile；无法确定坐标的 profile 依赖只记
  `notes()`，不阻塞打包。
- 循环引用通过 `resolvingProperties` / `resolvingManagement` 防护；缓存避免重复解析。

`MavenCollector.materialize()` 从“所有已收集工件的 GAV”出发，广度优先遍历这个图，把每个 POM 复制到
`javarepo` 的规范路径。reactor POM 里直接 import 的 BOM 还会额外播种，因为 effective model 不暴露 import
坐标（`projectBomGavs()` 解析原始 POM 得到）。

**失败策略：只要闭包里有一个引用目标 POM 无法打包，就整体失败**（无法解析坐标的 profile 依赖除外，见上），
不写出不完整的离线包。`graph.problems()` 与解析失败清单会拼进异常信息，直接指出缺哪个 GAV、被谁引用。

### 4.5 平台原生 family

有些依赖用 classifier 或 artifactId 区分平台，例如：

```text
io.netty:netty-transport-native-epoll:4.1.x:linux-x86_64
com.example:demo-native:1.0:native-windows-x86_64
```

`MavenCollector` 在三个平台都采集完之后执行 `expandPlatformFamilies()`：

1. 识别 token：artifact 名以 `native-`/`natives-` 开头或包含 `-native` 时先看 artifactId；匹配不到再看
   classifier 是否等于或以某个 token 结尾。token 列表按长度倒序匹配，避免短 token 抢先。
2. 把 artifactId/classifier 里的 token 替换成 `{platform}` 得到 family key。
3. 对三个目标平台依次尝试 `TARGET_TOKENS` 里的别名，解析 artifact + POM。
4. **任一平台缺变体就整体失败**：说明该依赖无法在三个目标平台使用。

目标 token 别名：

| 平台 id | 可接受的 token |
|---|---|
| `windows-x86_64` | `windows-x86_64`、`win32-x86-64`、`windows-amd64`、`win-x64` |
| `linux-amd64` | `linux-x86_64`、`linux-amd64`、`linux-x64` |
| `linux-arm64` | `linux-aarch64`、`linux-arm64`、`linux-arm_64` |

以下 token 被显式列为“不支持”，命中即失败（避免静默漏包）：
`osx-aarch_64`、`osx-x86_64`、`macos-aarch64`、`macos-x86_64`、`darwin-arm64`、
`darwin-x86_64`、`linux-riscv64`、`linux-armv7`、`linux-s390x`、`linux-ppc64le`、
`win32-arm64`、`windows-aarch64`。

**例外：Netty 的可选加速器。** `io.netty` 下 `netty-transport-native-*`（`unix-common` 除外）和
`netty-resolver-dns-native-*` 被当作“OS 可选加速器”：仍然打进 `javarepo`，但不参与三平台门禁，
并记录到 manifest 的 `optionalAccelerators`。原因是 Linux UDS 需要 epoll，Windows 可以退回 NIO，
不应该因为 macOS/kqueue 或 riscv 变体不存在就让整个离线包失败。

### 4.6 落选候选版本的 jar

Maven 的就近原则会让某些版本只被“读 POM”而不被解析 jar。默认 `offline.candidate.jars=true` 时，
`materialize()` 对 `DEPENDENCY` / `PROFILE_DEPENDENCY` 引用额外尝试解析并复制**无 classifier 的 jar**：

- 只对依赖引用尝试，不对 parent/import/relocation 尝试；
- 解析失败不视为打包缺口（这些 jar 本来就不在当前 reactor 的 classpath 上），只是“保险”；
- 计数写入 manifest：`javarepoCandidateJarCount` / `javarepoCandidateJarCandidateCount`；
- 关掉：`-Doffline.candidate.jars=false`。离线改了模块 POM 或解析顺序变化时，这些 jar 能让仓库继续可用。

### 4.7 npm 侧

`NpmCollector` 的输入是 reactor 各模块根目录下的 `package-lock.json`，需要 lockfile v2/v3 的 `packages`
映射。

> **lockfile 必须在磁盘上，而且要入库。** npm 只在安装时写 `package-lock.json`；Vaadin/Flow 还会在
> `build-frontend` 结束时删掉“自己生成”的 `package.json` + `package-lock.json`。所以没有入库 lockfile 的
> 前端模块在两次构建之间是没有 lockfile 的，`collect` 只能采到 0 个包。做法：模块根目录提交
> `package.json` 和 `package-lock.json`（Flow 会把新依赖合进已存在的 `package.json` 并保留它），
> 升级依赖后重新跑一次 `mvn package` 再提交。`-Doffline.npm.allow.empty=true` 可以把“采不到就不报错”
> 的旧行为放回来。

1. 只处理 `packages` 里以 `node_modules/` 开头的条目；
2. `version`、`resolved`、`integrity` 任一为空 → warning 并跳过；
3. npm alias（`"@scope/x": "npm:real@1.2.3"`）用条目里的 `name` 作为真实包名，lockfile key 只作为
   `installName`；
4. 按 `os` / `cpu` / `libc` 过滤：数组为空视为匹配；`libc` 非空时只接受含 `glibc` 的包（三个目标平台
   都是 glibc）；
5. 按真实 `name@version` 去重，合并 `platforms`；
6. 需要 tarball 时**先查本地缓存**，按以下顺序（重复的目录会去重）：

   1. 上一版 `offline/npmrepo`（在同一个 `offline.dir` 里重新生成时自动加入）；
   2. 环境变量 `NPM_CONFIG_CACHE` 指向的目录；
   3. `-Doffline.npm.cache.dirs` 列出的目录（逗号或分号分隔）；
   4. 本机 npm 缓存：运行时执行 `npm config get cache` 得到；取不到时用平台默认值
      （Windows 为 `%LOCALAPPDATA%\npm-cache`，其他平台为 `~/.npm`）。可用
      `-Doffline.npm.cache.global=false` 关闭。

   每个目录既可以是 npm cache 根（含 `_cacache/`），也可以是旧 `npmrepo`，或只是放 tarball 的目录
   （tarball 直接放在根下或 `tarballs/` 下都行）。命中方式：

   - `_cacache/content-v2/<algo>/<aa>/<bb>/<rest>` 与 integrity 匹配 → 直接复用内容，不启动 npm；
   - 目录里已有 `<name>-<version>.tgz` → 直接复用，不启动 npm。

   命中后把 tarball 硬链接/复制到 `<npmrepo>/tarballs/`，并把内容写进 `<npmrepo>/_cacache/content-v2/`；
   如果源缓存的 `index-v5` 里有对应条目，还会把 tarball 的索引项和该包的 packument（最新的一条）一起带过来，
   这样离线机的 `npm ci` / `npm install` 仍然能用这份缓存。
7. 只有本地缓存都没有时才联网执行：

   ```text
   npm pack <name>@<version> --pack-destination <npmrepo>/tarballs \
     --cache <npmrepo> --prefer-offline \
     --ignore-scripts --no-audit --no-fund --loglevel error
   ```

   并强制设置 `NPM_CONFIG_CACHE`、`NPM_CONFIG_IGNORE_SCRIPTS` 等环境变量。`--prefer-offline` 让 npm 优先
   使用已经播种的缓存，只有确实缺文件时才访问 registry；
8. 校验 `sha512-` integrity，不匹配直接失败；目前不支持其他算法；
9. 写 `<npmrepo>/packages.json`：`lockfileVersion: 3` + `packages[]`，每项包含
   `name`、可选 `installName`、`version`、`resolved`、`integrity`、`tarball`、`platforms`；
10. 删除 npm 的 `_logs`、`_update-notifier-last-checked`，保持包干净。

日志会打印实际使用的缓存目录，以及 `N reused from local cache` / `N fetched with npm pack` 的计数。本地
缓存只包含部分元数据时（例如只有 tarball、没有 packument），包本身仍可离线安装；如果离线机修改了
`package.json` 需要重新解析依赖，缺失的元数据仍需要联网补齐。

没有找到任何 `package-lock.json` 时：默认失败，异常信息列出扫过多少模块、哪些模块只有 `package.json`
没有 lockfile，并给出修法（先构建一次让 npm 写 lockfile，再把 `package.json` + `package-lock.json` 入库）；
`-Doffline.npm.allow.empty=true` 时返回 0 包并只记 warning。lockfile 存在但按 `offline.platforms`
过滤后一个包都不剩，同样按这个开关处理。

### 4.8 打包与替换输出

`CollectMojo` 不在输出目录里就地写：

1. 先在同级建 `.<输出目录名>.staging-<时间戳>/`；
2. 所有采集、zip、manifest 都在 staging 完成；
3. staging 全部成功后才处理输出：若输出已存在（此时必然配置了 `offline.clean=true`）先删除，再把 staging
   的子项移入；
4. 采集/打包阶段失败时 staging 保留，异常信息会打印它的路径，便于检查半成品（“输出已存在”这类前置检查
   失败发生在建 staging 之前，不会留下 staging）。

`offline.zip=true` 时，zip 完成后会删除 staging 里的 `javarepo/`、`npmrepo/`，避免离线包里同时留下大量
小文件；zip 是传输载体。`offline.zip=false` 时保留目录，manifest 的 `*Archived` 为 `false`。

`mvn clean` 不会删除 `offline/`：离线包同时是离线构建的 `-Dmaven.repo.local.tail` 仓库，清掉它等于删掉
构建自己要用的仓库。根 POM 的 clean 配置只清理 `dist/`。

### 4.9 校验（verify）

`VerifyMojo` 做两件事：

1. **离线解析检查**：复制当前 `RepositorySystemSession`，设置 `offline=true`、新的 cache/session data，
   把本地仓库换成 `javarepo`；对每个 reactor 项目重新 `resolve()`。失败条件与 collect 一致：有
   unresolved dependency 或 collection error。
2. **POM 闭包审计**：不经过 resolver，直接遍历 `javarepo` 中所有 `.pom`，用 `PomGraph` 找出引用并检查目标
   POM 是否存在。非规范的 POM 路径会写 `SKIP`；`PROFILE_DEPENDENCY` 缺口只写 WARN，其余缺口导致失败。

报告文件名：`verify-report-<os>-<arch>.txt`，其中 os 归一化为 `windows`/`macos`/`linux`，arch 把
`amd64`/`x86_64` 归一化为 `x86-64`，`aarch64`/`arm64` 归一化为 `arm64`。报告包含每个项目的解析结果、
POM 闭包统计和 `RESULT: OK/FAIL`。

注意两个细微差别：

- 在 **POM 审计阶段**，`verify` 把 `graph.problems()` / `graph.notes()` 只记为 `WARN`；真正导致审计失败的是
  “引用目标 POM 不存在”（profile-only 缺口也降级为 WARN）。
- `collect` 更严格：`graph.problems()` 会作为缺口直接失败。
- 推荐用 `-N` 运行：此时 Maven 只加载根项目，依赖解析检查只覆盖根项目，但 POM 闭包审计仍然覆盖整个
  `javarepo`。去掉 `-N` 才会逐模块解析。

---

## 5. 开发指南

### 5.1 环境要求

- JDK 17 或更高（插件按 release 17 编译；用 JDK 21 构建也可以）。
- Maven 3.9.16（POM 的 `<prerequisites>` 与 API 版本都锁在这个版本）。
- 首次构建/生成离线包需要网络；之后本地缓存（Maven 本地仓库、npm 缓存、上一版 `npmrepo`）里已有的文件
  不会重新下载。
- 只有 npm 采集遇到本地缓存没有的包、或需要查询 `npm config get cache` 时才需要 Node/npm 在 `PATH` 中
  （或用 `offline.npm.executable` 指定）；查不到 npm 时会退回平台默认缓存路径。

### 5.2 常用命令

```bash
# 单元测试
mvn -f offline-package-maven-plugin/pom.xml test

# 只编译
mvn -f offline-package-maven-plugin/pom.xml compile

# 安装后生成（根目录）
mvn -f offline-package-maven-plugin/pom.xml install
mvn -Poffline-package validate

# 快速迭代：一个平台、不跑 npm、保留目录、覆盖旧输出
mvn -Poffline-package validate \
  -Doffline.dir=target/offline-dev \
  -Doffline.platforms=windows-x86_64 \
  -Doffline.npm.skip=true \
  -Doffline.zip=false \
  -Doffline.clean=true
```

完整生成（三平台 + npm + zip）耗时较长、产物数百 MB，日常改动先用上面的快速命令验证逻辑。

### 5.3 修改流程

1. 改代码；
2. `mvn -f offline-package-maven-plugin/pom.xml test`；
3. `mvn -f offline-package-maven-plugin/pom.xml install`；
4. 用快速参数跑一次 `collect`，检查输出目录、`manifest.json`、`gaps.txt`；
5. 跑完整三平台 + npm；
6. 解压 zip，在 `-o` 模式下做一次真实 `clean package`（或至少 `verify`）；
7. 如果改了参数、输出文件或行为，同步更新本文和根 README 的离线章节。

### 5.4 新增或修改参数

在对应的 Mojo 里加字段：

```java
@Parameter(property = "offline.foo", defaultValue = "false")
private boolean foo;
```

约定：

- 属性名统一 `offline.` 前缀；字段名用 camelCase；
- 布尔参数用 `-Doffline.foo=true/false` 覆盖；
- 默认值要能保证“不传参数也能生成完整包”；
- 改完同步 §2.4 参数表、根 README 的离线章节，以及 `ManifestWriter` 里生成的说明（如果需要）。

### 5.5 新增目标平台

需要同时改三处，缺一不可：

1. `Platform.DEFAULTS`：增加 `Platform` 记录，填好 `id`、`osName`、`osArch`、`osVersion`、
   `detectedClassifier`、`npmOs`、`npmCpu`、`glibc`；
2. `MavenCollector.TARGET_TOKENS`：为这个 `id` 增加一组可接受的 classifier/artifactId token；
3. 评估 `UNSUPPORTED_TOKENS`：原本“不支持”的 token 如果现在支持了，要从列表移除。

注意：

- `TARGET_TOKENS` 的 key 必须与 `Platform.id()` 完全一致；
- token 会用于 artifactId/classifier 字符串替换，别选过短的 token（如 `x64`），否则会误伤其他 artifact；
- 平台增加后，所有已识别的原生 family 都必须能在新平台解析，否则 collect 会失败——这是有意的门禁；
- npm 侧的 `npmOs`/`npmCpu` 必须与 lockfile 中的取值一致，否则包会被过滤掉；
- 三个平台的 `glibc` 目前都是 `true`；如果引入 musl 平台，需要同时改 `NpmCollector.matches()`。

### 5.6 新增原生 token

如果新的依赖使用未覆盖的命名（例如 `native-linux-x64-gnu`）：

1. 在 `TARGET_TOKENS` 对应平台下加入该 token；
2. 确认 `detectToken()` 能命中：artifactId 需要以 `native-`/`natives-` 开头或包含 `-native`，或者 token
   等于 classifier / 位于 classifier 结尾；
3. 如果它是“不支持平台”的 token，加入 `UNSUPPORTED_TOKENS`，让 collect 明确失败而不是静默漏包；
4. 用 `-Doffline.platforms=...` 跑一次 collect，检查 manifest 的 `platformFamilies`。

### 5.7 扩展 `PomGraph`

新增一种“Maven 会读取的 POM 引用”时：

1. 在 `PomGraph.Kind` 增加枚举值；
2. 在 `references()` 里产生该引用（注意属性插值和循环防护）；
3. 决定它在 collect 和 verify 中的严格程度：
   - 必需 → 缺失时进入 `missing` / 抛异常；
   - 仅 profile 可达 → 作为 `PROFILE_DEPENDENCY` 或 `notes()`，只告警；
4. 在 `PomGraphTest` 增加最小 POM 用例（父链、BOM、属性、缺失场景各覆盖一次）。

不要绕过 `PomGraph` 直接在 `MavenCollector` 里手写 POM 解析；闭包规则必须只有一处事实源。

### 5.8 修改输出契约

`ManifestWriter` 负责所有离线包元数据。新增字段/文件时：

1. 在 `manifest.json` 增加字段，并在本文 §6 的字段表补充；
2. 如果新增独立文件，决定是否加入 `checksums.sha256`。当前只校验 `javarepo`/`npmrepo`（zip 或目录），
   新增文件默认不校验；
3. 更新 `README-offline.md` 的说明（它由 `ManifestWriter.readme()` 生成）；
4. 保持字段只增不改：`schemaVersion` 目前是 `1`，破坏性变更要升版本。

### 5.9 测试

现有测试只有 `PomGraphTest`，覆盖：

- 父 POM、import BOM、compile/runtime 传递依赖、dependencyManagement 补版本；
- test/provided/optional 依赖不进入引用集；
- profile 依赖标记为 `PROFILE_DEPENDENCY`；
- 父链自身的 import BOM；
- 旧式 `${version}` 别名；
- 缺失 POM 抛 `MissingPomException`；
- 仓库路径与 GAV 的双向映射。

新增测试优先选择**纯逻辑**类，不需要构造 Maven `MavenSession`：

- `PomGraph`：用 `@TempDir` 造一个假仓库，写 POM 文件；
- `Platform`、`ArtifactRecord`、`ZipPackager`、`ManifestWriter` 的纯函数/小对象；
- `NpmCollector` 的 lockfile 解析目前耦合在 `collect()` 里，若要单测筛选规则，建议先把
  “lockfile 节点 → SelectedPackage”抽成包私有静态方法，再补测试。

`collect`/`verify` 的端到端行为依赖真实 Maven 解析和网络，模块内不做集成测试；由日常生成 + 离线构建验证。

### 5.10 调试手册

| 现象 | 排查方向 |
|---|---|
| `Offline directory already exists` | 加 `-Doffline.clean=true`，或换 `-Doffline.dir` |
| `Unsupported offline platform` | 检查 `offline.platforms` 的 id 是否在 `Platform.DEFAULTS` |
| `Plugin has no version` | 在项目 POM 或 `pluginManagement` 里声明版本 |
| `Native family uses a platform outside the three targets` | 依赖引入了 macOS/riscv 等变体；确认是否要支持，或从依赖中排除 |
| `Platform family ... is missing variants for [...]` | 该依赖没有为某个目标平台发布变体；换依赖/排除/新增平台 |
| `The offline Maven repository would be incomplete` | 先联网构建一次，确认依赖可解析；查看异常列出的 GAV 与引用者 |
| `POM was resolved to a missing file` | `-Dmaven.repo.local.tail` 指向了多一层/少一层；tail 必须直接包含 `io/`、`org/` 等目录 |
| npm `pack` 失败 | 说明本地缓存没有这个包；检查网络/npm，或让 `NPM_CONFIG_CACHE` / `-Doffline.npm.cache.dirs` 指向含该 tarball 的旧 `npmrepo` |
| `Integrity mismatch` | 删除对应 tarball 和缓存条目后重跑；不要手改 tarball |
| `verify` 失败但 collect 成功 | 看 `verify-report-*.txt` 的 MISSING POMs 段；通常是生成后手工删了 POM 或 tail 路径错误 |

其他手段：

- 失败时看日志里的 staging 路径：`.offline.staging-<时间戳>/` 保留了现场；
- `-Doffline.zip=false` 直接检查 `javarepo/`、`npmrepo/` 的目录结构；
- `-X` 打开 Maven debug 日志；`CollectMojo` 对“非根模块跳过”有一条 debug 日志；
- `offline/README-offline.md` 和 `gaps.txt` 是给使用者的第一手信息，改行为时别忘记它们。

### 5.11 版本与发布

插件版本 `1.0.0` 出现在多个位置，升级时必须一起改：

| 位置 | 说明 |
|---|---|
| `offline-package-maven-plugin/pom.xml` 的 `<version>` | 插件自身版本 |
| 根 `pom.xml` 的 `pluginManagement` | `-Poffline-package` 调用时使用的版本 |
| `ManifestWriter.readme()` | 生成文案里的 `...:1.0.0:verify` 坐标 |
| 根 `README-CN.adoc` / `README.adoc` | 离线章节里的 `...:1.0.0:verify` |
| `offline/README-offline.md` | 重新生成时自动更新，不手工改 |

发布/分发注意：

- 插件模块**不在**默认 reactor 中；只有 `-Poffline-package` 会把它加进来。加进来的目的不是把插件打进
  `javarepo`（插件是 reactor 模块，会被 `ProjectGavFilter`/`add()` 排除），而是让它的运行期依赖（Jackson）
  和构建插件进入采集范围；
- `release/src/main/assembly/source.xml` 会把本模块打进源码包，离线机因此可以重新构建插件；
- 插件产物不进入 `javarepo`，离线机要先从源码包安装插件。

### 5.12 代码约定与不变量

- **Java 17**：不要用 21 的语法/API；插件要能在 Maven 运行的较低 JDK 上工作。
- **不继承产品 POM**：不要添加 `<parent>`，不要依赖产品模块。
- **运行期依赖最小化**：目前只有 Jackson（lockfile/manifest JSON）。新增运行期依赖会进入插件的类加载器，
  也会增加离线包体积，必须谨慎。
- **Maven API 用 `provided`**：`maven-plugin-api`、`maven-core`、`maven-model`、`maven-artifact`、
  resolver API/util、plugin annotations 都由 Maven 运行时提供。
- **异常语义**：参数/环境错误用 `MojoFailureException`；意外失败用 `MojoExecutionException`；
  collector 内部用 `IllegalStateException` 表达“无法继续”，由 Mojo 统一包装。
- **日志**：collector 只用 `OfflineLog`，不直接依赖 `org.apache.maven.plugin.logging.Log`；Mojo 负责把
  `getLog()` 适配进去。
- **确定性**：`LinkedHashMap` / 排序后的集合用于 manifest 和 POM 顺序，避免同样的输入产出不同的清单。
- **失败要响**：POM 缺口、family 缺口、integrity 不匹配必须失败，不能降级成 warning 后继续产出。
- **不提前破坏旧包**：新包在 staging 完成前，不删除已有输出。
- **不写输出目录之外**：除了 staging 和 `~/.m2` 的解析缓存，不产生副作用。

---

## 6. 输出契约

### 6.1 `manifest.json`

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaVersion` | int | 当前 `1` |
| `generatedAt` | string | ISO-8601 带偏移 |
| `mavenVersion` | string | 生成时的 Maven 版本 |
| `javaVersion` | string | 生成时的 JVM 版本 |
| `javarepoFileCount` | int | `javarepo` 文件数（zip 前统计） |
| `javarepoSizeBytes` | long | `javarepo` 字节数 |
| `javarepoArtifactCount` | int | 收集到的工件数（Maven 解析结果，含带 classifier 的工件） |
| `javarepoPomCount` | int | 实际复制的 POM 数（描述符闭包） |
| `javarepoCandidateJarCount` | int | 成功打包的落选 jar 数 |
| `javarepoCandidateJarCandidateCount` | int | 尝试打包的落选 jar 数 |
| `npmrepoFileCount` | int | `npmrepo` 文件数 |
| `npmrepoSizeBytes` | long | `npmrepo` 字节数 |
| `javarepoZipBytes` / `npmrepoZipBytes` | long | zip 大小；未生成时为 0 |
| `javarepoArchived` / `npmrepoArchived` | boolean | 是否生成了 zip |
| `platforms[]` | array | `id`、`osName`、`osArch`、`npmOs`、`npmCpu` |
| `platformFamilies` | object | key 为 family，值含 `base`、`token`、`targets{平台:别名或 null}` |
| `optionalAccelerators[]` | array | 豁免三平台门禁的 Netty 加速器（`gav[:classifier]`） |
| `npm` | object | `enabled`；启用时还有 `packageCount`、`tarballCount`、`registry` |
| `warnings[]` | array | Maven + npm 侧的告警 |

`npm.registry` 目前恒为空字符串（构造函数保留字段，尚未接入 registry 探测）；使用方不要依赖它。
`offline.npm.skip=true` 时 `npm` 对象只有 `{"enabled": false}`。

### 6.2 zip 布局

```text
javarepo.zip
└── javarepo/
    ├── io/netty/...
    ├── org/apache/maven/...
    └── ...

npmrepo.zip
└── npmrepo/
    ├── _cacache/
    ├── tarballs/
    └── packages.json
```

解压时把 zip 解到 `<offline>` 目录（即 `javarepo/` 的上一级），保证
`-Dmaven.repo.local.tail=<offline>/javarepo` 直接指向包含 `io/`、`org/` 的目录。多解一层是
`ensurePom()` 报“POM was resolved to a missing file”的最常见原因。

### 6.3 `gaps.txt`

内容来自 Maven 的 `warnings` 与 npm 的 `warnings`；为空时写 `No gaps recorded.`。
典型内容：lifecycle 插件坐标格式不对、找不到 packaging 的 `LifecycleMapping`、lockfile 条目缺字段、
没有找到 `package-lock.json` 等。生成后应默认认为“`gaps.txt` 非空需要人工确认”。

profile-only 相关的问题不会进入 `gaps.txt`：坐标无法解析时 collect 只记 info 日志；坐标可解析但目标 POM
缺失时 collect 直接失败；verify 则把它记入 `verify-report-*.txt` 的 WARN。

---

## 7. 已知限制

1. **只支持三个目标平台**。出现 macOS/riscv 等原生变体会直接失败；这是有意的完整性门禁。
2. **对每个 POM 只跟随 compile/runtime 依赖**。POM 内部的 test/provided/optional 依赖不进入描述符闭包；
   reactor 项目的 test/provided 直接依赖由顶层依赖解析单独采集，不受影响。
3. **profile 依赖不保证打包**。坐标无法解析时只记 note、不打包；坐标可解析但目标 POM 缺失时 collect 仍会
   失败；verify 中 profile-only 缺口只告警，不失败。
4. **落选 jar 是尽力而为**。解析失败不会让 collect 失败，manifest 的计数会体现实际复制数量。
5. **npm 只读取模块根目录里已经在磁盘上的 `package-lock.json`**（lockfile v2/v3）；不读取
   `npm-shrinkwrap.json`，也不支持 yarn/pnpm 的 lockfile。插件不会自己跑 `npm install` 去造 lockfile，
   所以前端模块必须把 `package.json` + `package-lock.json` 入库（Vaadin/Flow 会删掉自己生成的临时文件，
   见 §4.7）。嵌套 workspace 需要也是 Maven 模块才会被收集。
6. **npm integrity 只支持 `sha512-`**；其他算法直接失败。
7. **npm registry 字段未实现**，manifest 中恒为空。
8. **`mvn deploy` 不能用 `-o`**（Maven deploy 插件限制）；离线包文档里已明确说明，其他命令可以用 `-o`。
9. **zip 使用 `BEST_SPEED`**，体积会比最高压缩率大，换取生成速度。
10. **collect/verify 没有端到端自动化测试**，依赖真实 Maven 解析与网络；由生成和离线构建验证。
    `NpmCollector` 的本地缓存复用有单元测试（不启动 npm）。

---

## 8. 建议的阅读顺序

1. `CollectMojo`：先看一次 `collect` 的完整流程和 staging/替换语义；
2. `MavenCollector.collect()`：三平台循环 → family → materialize 的主线；
3. `MavenCollector.materialize()` + `PomGraph`：理解“为什么要 POM 闭包”和“缺口为什么必须失败”；
4. `NpmCollector.collect()`：lockfile → 过滤 → 本地缓存优先 → 必要时 pack → integrity → packages.json；
5. `VerifyMojo`：离线解析与 POM 审计的双重检查；
6. `ManifestWriter` / `ZipPackager`：输出契约和打包细节；
7. `NpmCollectorTest` / `PomGraphTest`：看测试如何构造假 npm 缓存/假仓库，锁定复用与引用集合。

遇到“这个 GAV 为什么在/不在包里”的问题，按这个顺序定位最快：
`CollectMojo`（是否执行/输出目录）→ `MavenCollector`（哪个采集入口/哪个平台）→ `PomGraph`（是否被引用、
是否被跳过）→ `ManifestWriter`（是否只是没出现在摘要里）。
