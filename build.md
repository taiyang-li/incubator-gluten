# Gluten + Bolt 构建 / 对齐记录

更新时间：2026-06-29（Scala/Java 对齐结果已补充）

## 当前状态

- 已在 `/data00/home/liyang.127/oap/incubator-gluten` 下完成顶层 `make release` 回归验证。
- Native Release 构建成功，产物位于：
  - `cpp/build/releases/libbolt_backend.so`
  - `cpp/build/releases/libglutenlibloader.so`
- `backends-bolt` 已在 JDK11 下完成单模块增量编译验证：
  - 命令：`export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 && ./build/mvn -pl backends-bolt -am compile -Pspark-3.5 -Pbackends-bolt -DskipTests -Denforcer.skip=true`
  - 结果：`BUILD SUCCESS`

## 本轮已完成

### 1. Scala / Java：`backends-bolt` 主干对齐完成并已可编译通过

- 对齐原则：**以当前 `backends-velox` 为基线，只回放 Bolt 的真实增量**。
- 结果：此前记录的 `backends-bolt` 大批 Scala/Java 编译错误已完成收敛。
- 本轮关键处理：
  - **Arrow CSV 整组下线**：当前 `backends-velox` 已无对应基线实现，Bolt 旧副本整体删除。
  - **Backend / Rule / Iterator API 对齐**：`BoltBackend`、`BoltRuleApi`、`BoltIteratorApi` 已切到当前主干接口。
  - **SparkPlan / Metrics / Transition API 对齐**：`BoltMetricsApi`、`BoltSparkPlanExecApi`、`rowType0() -> rowType()` 等漂移已修复。
  - **其余主失败组已按“Velox 基线 + Bolt 增量”回放**：包括 `ColumnarRangeExec`、`HashAggregateExecTransformer`、BloomFilter、Python、Serializer 等路径。

### 2. 当前残留的非阻塞项

- 非阻塞 warning：`backends-bolt/src/main/scala/org/apache/spark/sql/execution/unsafe/UnsafeBytesBufferArray.scala:134`
  - 现象：`finalize` 已被 JDK 标记为 deprecated
  - 状态：不阻塞编译，可后续单独清理

### 3. 本轮下线的 Arrow CSV 旧路径

- 已删除：
  - `backends-bolt/src/main/scala/org/apache/gluten/datasource/ArrowCSVFileFormat.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/datasource/ArrowCSVOptionConverter.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/datasource/v2/ArrowCSVPartitionReaderFactory.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/datasource/v2/ArrowCSVScan.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/datasource/v2/ArrowCSVScanBuilder.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/datasource/v2/ArrowCSVTable.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/execution/datasource/v2/ArrowBatchScanExec.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/extension/ArrowConvertorRule.scala`
  - `backends-bolt/src/main/scala/org/apache/gluten/extension/ArrowScanReplaceRule.scala`
  - `backends-bolt/src/main/scala/org/apache/spark/sql/execution/ArrowFileSourceScanExec.scala`
  - `backends-bolt/src/test/scala/org/apache/gluten/execution/ArrowCsvScanSuite.scala`

### 4. C++ Native 编译阻塞项已修复

#### 1. `conf` 未定义导致 `JniWrapper` 编译失败

- 问题位置：`cpp/core/jni/JniWrapper.cc:577`
- 现象：`nativeCreateKernelWithIterator` 中调用 `isParallelExecEnabled(conf)`，但作用域内没有 `conf`。
- 修复：在调用前显式获取 `ctx->getConfMap()`。
- 当前代码：`cpp/core/jni/JniWrapper.cc:577`

#### 2. Bolt Runtime / Factory 签名与 core `Runtime` 基类失配

- 问题位置：
  - `cpp/bolt/compute/BoltRuntime.h:45`
  - `cpp/bolt/compute/BoltRuntime.cc:65`
  - `cpp/bolt/compute/BoltBackend.cc:88`
- 现象：core `Runtime` 构造函数和 `Factory` 已引入 `ThreadManager*` 参数，但 Bolt 侧构造函数与 factory 仍沿用旧签名，导致：
  - `override` 不成立
  - 工厂函数无法注册
  - `Runtime(kind, vmm, confMap, taskId)` 调用不匹配
- 修复：
  - `BoltRuntime` 构造函数补齐 `ThreadManager*`
  - `boltRuntimeFactory(...)` 补齐 `ThreadManager*`
  - `createResultIterator(...)` 对齐基类当前签名

#### 3. `BoltColumnarBatchSerializer` 与基类接口不兼容

- 问题位置：
  - `cpp/bolt/operators/serializer/BoltColumnarBatchSerializer.h:31`
  - `cpp/bolt/operators/serializer/BoltColumnarBatchSerializer.cc:62`
- 现象：core `ColumnarBatchSerializer` 现在要求实现：
  - `append(...)`
  - `maxSerializedSize()`
  - `serializeTo(...)`
  - `deserialize(...)`
  但 Bolt 侧仍保留旧的 `serializeColumnarBatches(...) override`，导致：
  - `override` 报错
  - 类变成抽象类，无法实例化
- 修复：
  - 改为实现新的三段式接口
  - 保留 `serializeColumnarBatches(...)` 作为内部 helper
  - 用 `batches_ + serializedBuffer_` 做一次性缓存，复用现有序列化逻辑

#### 4. `ArrowMemoryPool` 构造参数不匹配

- 问题位置：`cpp/bolt/memory/BoltMemoryManager.cc:326`
- 现象：Bolt 侧还在按“两参数构造 + 析构回调删除缓存”的旧写法创建 `ArrowMemoryPool`，但当前 `ArrowMemoryPool` 仅接受 `AllocationListener*` 一个参数，导致编译失败。
- 修复：
  - 改成与 Velox 一致的实现方式，只传 `blockListener_.get()`
  - 发现缓存中的 `weak_ptr` 已失效时，先 `arrowPools_.erase(name)` 再插入新 pool，避免同名 expired entry 一直占位
- 当前代码：`cpp/bolt/memory/BoltMemoryManager.cc:326`

### 5. 本轮顺手清理的 Native warning

#### 1. `WholeStageResultIterator` 未使用变量 warning（已处理）

- 原位置：`cpp/bolt/compute/WholeStageResultIterator.cc:644`
- 原现象：
  - `auto [currentConcurrency, concurrencyVersion] = getExecutorConcurrency();`
  - 当前 structured binding 未被后续 metrics 逻辑使用
- 处理：直接删除该行无效 structured binding，不再保留无语义消费的局部变量
- 验证：重编 `bolt_backend` / `bolt_backend_static` 时该 warning 未再出现

#### 2. `SubstraitToBoltPlan` 未使用静态函数 warning（已处理）

- 原位置：`cpp/bolt/substrait/SubstraitToBoltPlan.cc:820`
- 原现象：`extractUnnestFieldExpr(...)` 在仓库内无任何调用点，是纯死代码
- 处理：删除整个未使用 helper，减少 warning 与阅读噪音
- 验证：重编 `bolt_backend` / `bolt_backend_static` 时该 warning 未再出现

### 6. 本轮已完成的低价值路径清理

#### 1. `dropMemoryPool(...)` 已确认死代码并删除

- 原位置：
  - `cpp/bolt/memory/BoltMemoryManager.h:107`
  - `cpp/bolt/memory/BoltMemoryManager.cc:340`
- 原现象：当前 `getOrCreateArrowMemoryPool(...)` 已改为“访问缓存时清理 expired weak_ptr”路径，不再依赖旧的析构回调删除 map 条目。
- 评估结论：
  - 全仓 grep 后，除 `build.md` 文字记录外，`dropMemoryPool(...)` 无任何代码调用点
  - Velox 对应实现也没有该方法，Bolt 保留它没有额外价值
  - 因此该方法已退化为死代码
- 处理：删除 `BoltMemoryManager` 中该方法的声明与定义，使 Bolt Arrow pool 生命周期管理与当前 Velox 路径保持一致
- 验证：重编 `bolt_backend` / `bolt_backend_static` 成功，未引入新错误

## 未解决风险与后续事项

以下问题来自之前的只读扫描，虽然本次 `make release` 已通过，但它们仍属于后续应继续收敛的技术债：

### 1. 公共 `cpp/core` 对 Bolt 头文件/符号的无条件依赖

- 记录位置：`plan.md:273`
- 主要表现：
  - `cpp/core/compute/Runtime.h` 无条件依赖 Bolt memory manager 相关头
  - `cpp/core/jni/JniWrapper.cc` 无条件 include / 调用 Bolt 逻辑
  - `cpp/core/utils/ConfigResolver.h` 无条件 include `bolt/core/Config.h`
  - `cpp/core/jni/JniCommon.h` 混入 Bolt shuffle wrapper / Java class 名
- 风险：会持续抬高 core 的耦合度，使“非 Bolt 构建路径”更脆弱
- 建议：Bolt 专属逻辑逐步下沉到 `cpp/bolt`，或以 `GLUTEN_ENABLE_BOLT` 严格隔离

### 2. Core JNI loader 入口被改成 Bolt 专用 base 入口

- 记录位置：`plan.md:280`
- 现象：`JNI_OnLoad` / `JNI_OnUnload` 被改成 `JNI_OnLoad_Base` / `JNI_OnUnload_Base`
- 风险：Bolt loader 受益，但公共 core 的标准 JNI 入口语义被改变
- 建议：
  - 非 Bolt 构建保留标准 JNI entrypoint
  - Bolt 构建再额外暴露 base entrypoint 给 `cpp/bolt/jni/BoltJniWrapper.cc` 调用

### 3. `ConfigResolver` namespace / 链接风险

- 记录位置：`plan.md:285`
- 现象：`cpp/core/utils/ConfigResolver.h` 在 `namespace gluten` 中声明，但 `.cc` 定义不一致，且仍带 Bolt 依赖背景
- 风险：可能再次演化为链接或 ODR 相关问题
- 建议：
  - 若保留在 core，去掉 Bolt 依赖并统一 namespace
  - 若仅 Bolt 使用，则迁移到 `cpp/bolt`

### 建议后续动作

1. 先把 warning 清理掉，确保 Release 构建日志只剩真正需要关注的信息。
2. 单独做一次 `cpp/core` / `cpp/bolt` 解耦清理，优先处理 `plan.md:273`、`plan.md:280`、`plan.md:285` 这三项。
3. 若后续要继续提交/整理 patch，可把本文件作为本轮 native 构建问题的阶段性记录。

## 对齐方法论与历史背景（长期有效）

### 1. 历史失败面的典型分组

- 历史验证入口：`make jar_spark35`
- 当时 `Makefile` 已临时去掉 `-Ppaimon`，避免 Paimon 单独适配问题阻塞 Bolt 主链。
- 当时的失败并不是零散 typo，而是典型的 **“旧 Bolt 副本脱离当前主干接口演进”**，主要分为四组：
  1. **Arrow CSV / ArrowUtil 整组失配**：旧 CSV 读链路引用了已删除的 Arrow helper 与旧 Java API。
  2. **执行器父类签名漂移**：如 `ColumnarToColumnarExec`、`HashAggregateExecBaseTransformer` 等公共父类接口已变化。
  3. **`ColumnarRangeExec` 上下文过期**：对父类字段、构造约定的假设已整体失效。
  4. **Spark shim / Backend API 漂移**：旧 shim 方法、旧 backend hook、旧 settings 扩展点已不存在。

### 2. 本轮验证有效的修复策略

- 基准 commit：`d70e4d9cf616bc70a05ade97060a19072f435068`
- 核心原则：**当前 `backends-velox` 是唯一基线，Bolt 只保留真实私有增量**。
- 判断方式：
  - 先在老 commit 中比较 `backends-bolt` / `backends-velox` 对应文件；
  - 对 `Bolt/Velox`、`bolt/velox` 做归一化；
  - 归一化后若内容一致，则视为“仅改名”；否则视为“改名 + Bolt 实质增量”。

### 3. 实际落地规则

1. **仅改名文件**：直接复制当前 `backends-velox` 对应文件到 `backends-bolt`，再做命名替换。
2. **存在 Bolt 实质增量的文件**：先复制当前 `backends-velox` 文件并完成命名替换，再只回放老 commit 中的 Bolt 私有逻辑。
3. **禁止恢复旧接口形态**：回放增量时，只迁移 backend-specific 逻辑，不恢复已经被主干删除的 shim、helper、构造签名或 hook。

### 4. Bolt 实质增量通常集中区域

- `BoltBackend.scala` / `BoltConfig.scala`：Bolt 配置、能力开关、校验策略
- `BoltIteratorApi.scala` / `BoltSparkPlanExecApi.scala`：native iterator、shuffle reader、metrics 桥接
- shuffle / serializer / JNI 路径：writer type、wrapper、协议、metrics 字段
- 少量表达式限制、写路径与测试层差异

### 5. 仍保留的构建链路临时取舍

- `jar_spark35` 暂未带 `-Ppaimon`
- 已移除 `gluten-ras-common:test-jar` 依赖
- 已删除 `backends-bolt/src/test/scala/org/apache/gluten/extension/columnar/enumerated/planner/BoltRasSuite.scala`
