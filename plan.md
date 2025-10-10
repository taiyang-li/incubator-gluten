# Bolt backend 社区化迁移当前进度与后续计划

更新日期：2026-06-27

## 1. 背景与总体目标

当前工作围绕 Apache Gluten 的 Bolt backend 社区化迁移展开，核心目标不是简单把 `add_bolt_backend` 整体提交给社区，而是把其中与 Bolt 后端无强绑定的公共代码、框架接口、测试/benchmark 资源复用等能力先抽离到基于社区 `origin/main` 的 `fake_main` 分支中，再让 Bolt 后端分支 `fake_add_bolt_backend` 基于 `fake_main` 收敛。

这样做的目的有两个：

1. 减少 `fake_add_bolt_backend` 后续 rebase 社区主干时在公共代码上的冲突。
2. 把真正可社区化的公共能力沉淀成独立、可 review、可讨论的 patch，而不是混在 Bolt backend 大提交中。

当前涉及的主要分支和 PR：

- `fake_main`：承载从 Bolt 工作中抽取出的公共 patch。
- `fake_add_bolt_backend`：承载抽取公共 patch 并解决 rebase 冲突后的 Bolt backend 代码。
- 抽取 patches PR：<https://github.com/taiyang-li/incubator-gluten/pull/2>
- 抽取 patch 后的 Bolt backend PR：<https://github.com/taiyang-li/incubator-gluten/pull/1>
- Patch1 / 社区 WIP PR commits：<https://github.com/apache/gluten/pull/12376/commits>

说明：本地工具访问 3 个 `code.byted.org/copilot/share/...` 链接时被 SSO 重定向，无法直接读取分享页正文；本文结合本轮用户提供的背景、GitHub PR 可见信息、本地 git 状态以及仓内 `bolt_rebase_conflict_analysis.md` 的历史记录整理。如果 share 页面中还有额外细节，可后续继续补充到本文。

## 2. 我与 AI 的协作方式

当前协作模式可以概括为“用户定方向和准则，AI 做上下文收集、冲突分析、patch 抽取、验证和记录”。

用户侧主要负责：

1. 明确总体路线：
   - 基于 MR 分支 `add_bolt_backend` 新建 `fake_add_bolt_backend`。
   - 基于社区分支 `origin/main` 新建 `fake_main`。
   - 先把公共代码冲突和公共接口改动抽到 `fake_main`，再让 Bolt backend 分支基于它收敛。
2. 明确优先级：
   - P0：解决 `fake_add_bolt_backend` rebase `fake_main` 时的公共代码冲突，抽取框架/接口类 patch 到 `fake_main`。
   - P1：继续分析 `fake_add_bolt_backend` 中残留的公共代码改动，尽可能再抽成 patch 追加到 `fake_main`。
   - P2：公共改动收敛后修编译、修基础 UT，至少保证基本 TPCH suite 可跑通。
3. 明确边界和取舍：
   - 内部对 `spark32`、`cpp/velox`、`backends-velox` 的改动可废弃。
   - Paimon 相关改动单独适配，由 @徐韡欣 跟进。
   - 公共代码中只保留后端无关扩展点，不把 Bolt 专属实现扩散到公共模块。

AI 侧主要负责：

1. 主动读取分支、diff、commit 和历史分析文档，梳理冲突来源。
2. 把大提交中的公共能力拆成独立 patch，并尽量做到：
   - 默认行为不变；
   - 对 Velox / ClickHouse 无行为影响；
   - 不引入 Bolt 私有类型；
   - patch 可以单独解释、单独验证、单独提交。
3. 使用临时 worktree 隔离不同任务，避免把公共 patch、Bolt 大提交和临时验证改动混在一起。
4. 对冲突解决策略和验证结果做文字记录，形成 `bolt_rebase_conflict_analysis.md` 等过程文档，便于恢复上下文和继续迭代。
5. 在需要时给出社区化拆分建议：哪些 patch 可独立贡献，哪些应合并，哪些应留在 Bolt 后端，哪些应丢弃或转给专项负责人。

### 2.1 后续处理指定文件 rebase 冲突的协作指引

#### 背景

我在把内部 bolt backend 改动移植到最新社区 Gluten 上，存在三个关键 git 参照点：

- `d70e4d9cf616bc70a05ade97060a19072f435068`（分支 `liyang/old_add_bolt_backend`）：最初的 bolt backend commit，基于去年10月的 `origin/main`。代表 bolt 改动的「原始意图」。
- `origin/main`：最新社区主线。这一年社区自身演进了很多接口（例如 `IteratorApi.genSplitInfo`、scan 框架），所以最初 commit 的很多“改动”其实是社区接口在那时本就长这样，并非 bolt 真正想改的。
- `fake_main`（即当前分支 `fake_add_bolt_backend` 的 HEAD commit「add bolt backend in gluten」的 parent，可用 `<HEAD>~1` 表示）：= 社区 `origin/main` + 一批已抽取的公共 patch（如 InputStats `ab54bf2a81`、`format_number`、InSet、HiveGenericUDTF、ColumnarBatches public 等）。

当前分支 HEAD「add bolt backend in gluten」试图在 `fake_main` 之上重放最初的 bolt commit，但残留了大量未解决的 rebase 冲突：很多文件停留在“去年10月社区接口形态 + bolt 增量”，没合并社区这一年的演进。

#### 你的任务

针对我指定的文件，解决其 rebase 冲突，判断每处 bolt 改动属于以下哪类并相应处理：

1. bolt 误带的“旧社区接口形态”（社区已演进）→ 对齐到最新形态。
2. bolt 真正的私有增量（如某些扩展点、参数）→ 评估是否必要：必要则保留为向后兼容的扩展（默认值/默认空实现，不破坏 velox/CH）；不必要/有害（如砍掉社区能力、改公共接口签名、纯噪音 logging/空行/等价改写）则 revert。
3. `fake_main` 已抽取的公共 patch（如 InputStats）→ 必须保留。

#### 关键准则（务必遵守）

1. **对齐基线是 `fake_main`（`<HEAD>~1`），不是 `origin/main`！**
   - 二者差异 = 已抽取的 patch。
   - 若用 `git checkout origin/main -- <file>` 整文件覆盖，会误删 `fake_main` 已抽取的 patch（我已踩过坑：`BasicScanExecTransformer` / `FileSourceScanExecTransformer` 的 InputStats 被误删）。
   - 正确做法：`git checkout <HEAD>~1 -- <file>`，或逐处手工合并。

2. 判断“某处是 bolt 真改 vs 社区演进”时，用三方对比：
   - `git show d70e4d9~1:<file>`（去年10月社区）
   - `git show d70e4d9:<file>`（最初 bolt commit，看 bolt 真实意图 diff）
   - `git show origin/main:<file>`（最新社区）
   - `git show <HEAD>~1:<file>`（`fake_main`，对齐目标）

3. 内部对 `spark32` / `cpp/velox` / `backends-velox` 的改动可废弃；paimon 相关改动单独适配（移交他人），不要动。

4. bolt 私有增量若要保留，确保是向后兼容的（社区 velox/CH 不受影响）。

5. 改完后核验：
   - 与 `fake_main` 应保留部分一致；
   - 无残留对已删符号/不存在类的引用；
   - 子类/override 签名与基类匹配；
   - 无未使用 import。

#### 输出要求

每个文件给出：

1. 冲突点分类清单（哪些对齐社区、哪些保留、哪些 revert，附理由）；
2. 实际改动；
3. 校验结果。

重要决策和踩坑记录追加到 `bolt_rebase_conflict_analysis.md`。

#### 参考

完整的冲突分类、已完成 patch（B1~B9、C3 等）、处理范式和踩过的坑都记录在仓库根目录 `bolt_rebase_conflict_analysis.md`，开始前请先读它。


## 3. 当前本地状态概览

当前主工作区：

- 路径：`/data00/home/liyang.127/oap/incubator-gluten`
- 当前分支：`fake_add_bolt_backend`
- 当前 HEAD：`509ce2b736 add bolt backend in gluten`
- `fake_add_bolt_backend` 相对 `liyang/fake_main` 目前只领先 1 个提交，即 Bolt backend 大提交本身。
- 当前未跟踪文件：
  - `build.sh`
  - `tidy.sh`

`liyang/fake_main` 当前已经包含一组从 Bolt 工作中抽取出的公共 patch，最近的提交链包括：

1. `b6c7ba6c08 [GLUTEN][CORE] Pass Spark task attempt id and pool name from Java to native runtime/memory-manager`
2. `cdbbb62a88 [GLUTEN][VL] Support sequence function in Velox backend`
3. `ab54bf2a81 [GLUTEN][CORE] Support optional stage InputStats plumbing in scan/input-iterator transformers`
4. `28a3a9e733 [GLUTEN][CORE] Defer literal node construction for InSet to reduce memory`
5. `f9aa6e0ffa [GLUTEN][VL] Support format_number function in Velox backend`
6. `91483bcf41 [GLUTEN][CORE] Expose ColumnarBatches.isLightBatch/ensureOffloaded as public`
7. `7bbd377877 [GLUTEN][CORE] Support HiveGenericUDTF in HiveUDFTransformer`
8. `b12003c1f1 [GLUTEN][CORE] Move overwrite sql-tests to a backend-neutral shared dir`
9. `aa18e3ba22 [GLUTEN][CORE] Move benchmark data to shared directory`
10. `90e224a833 [GLUTEN][CORE] Add backend hook for sort aggregate offload`
11. `b59a38602f [GLUTEN][CORE] Add backend hook for sequence expressions`
12. `1ae8a16664 [GLUTEN][CORE] Expose shuffle reader metrics iterator delegate`

## 4. 已完成事项

### 4.1 P0：公共冲突和框架/接口 patch 抽取已完成一轮

当前已经完成一批从 Bolt 大提交中抽出的公共 patch，并追加到 `fake_main`。这些 patch 已经体现在 <https://github.com/taiyang-li/incubator-gluten/pull/2> 中。

已抽取的公共能力包括：

1. **Java 到 native runtime / memory-manager 传递 Spark task attempt id 和 memory pool name**
   - 对应社区 WIP patch：<https://github.com/apache/gluten/pull/12376/commits>
   - 作用：让 native runtime / memory manager 能拿到任务级上下文，为 Bolt 和未来后端使用任务级内存/运行时信息提供接口。

2. **Velox `sequence` 函数支持**
   - 从 validator 黑名单中移除 `sequence`。
   - 补充函数映射、文档和测试。
   - 这是 Velox 能力增强，不属于 Bolt 专属逻辑。

3. **InputStats 公共框架**
   - 新增可选的 stage input stats 传递链路。
   - 通过 `spark.gluten.sql.enablePassStageInputStats` 控制，默认关闭。
   - 关闭时对现有 Velox / ClickHouse 行为保持 no-op。
   - 相关设计记录已经写入 `bolt_rebase_conflict_analysis.md`。

4. **InSet 字面量延迟构造**
   - `SingularOrListNode` 保留原始值，序列化 protobuf 时再生成 literal。
   - 目标是减少大 IN 列表场景的 driver heap 压力。

5. **Velox `format_number` 函数支持**
   - 增加 Spark `format_number` 到 Velox 的函数映射。
   - 补充 UT 和函数文档。

6. **公开 `ColumnarBatches.isLightBatch` / `ensureOffloaded`**
   - 将工具方法改为 public。
   - 方便包外后端或数据源复用 native batch/offload 逻辑。

7. **HiveGenericUDTF 支持**
   - 让 `HiveUDFTransformer` 识别 `HiveGenericUDTF`。
   - 未映射时仍 fallback，映射后可转 native function。

8. **overwrite SQL 测试目录迁移到 backend-neutral shared dir**
   - 避免把 backend-neutral 的 SQL 测试资源继续维护在 Velox 专属目录。

9. **benchmark data 迁移到共享目录**
   - 将 Velox benchmark 数据移动到 `cpp/benchmarks/data`。
   - 更新 Velox 测试和 micro benchmark 文档引用。
   - 为 Bolt 后续复用公共 benchmark 数据、只保留真正有差异的数据打基础。

### 4.2 `fake_add_bolt_backend` 已基于抽取后的 `fake_main` 收敛成单个 Bolt 大提交

当前 <https://github.com/taiyang-li/incubator-gluten/pull/1> 表示的是：抽取 patch 并解决 `origin/main` / `fake_main` rebase 冲突之后的 Bolt backend 代码。

本地 `fake_add_bolt_backend` 相对 `liyang/fake_main` 当前只剩 1 个提交：

```text
509ce2b736 add bolt backend in gluten
```

这说明第一轮公共 patch 抽取和 rebase 冲突处理已经有明显效果：公共 patch 被前置到 `fake_main`，Bolt 分支重新变成“在公共基线之上的 Bolt 大提交”。

### 4.3 gluten-ut 语义冲突已处理一批

已经处理/确认的测试冲突包括：

1. Spark 4.0 Dynamic Partition Pruning suite 对齐 `fake_main`，避免 Bolt 旧改动倒退社区现状。
2. Collection expressions suite 中对重复 map key 的注释改为后端中立表述，例如 `Velox/Bolt`。
3. `GlutenFallbackSuite` 中 FULL OUTER JOIN fallback 断言按后端拆分：Velox 保持社区语义，Bolt 使用自己的断言。
4. 删除 Bolt 曾引入的不合适的全局 `RAS_ENABLED=false`，避免影响 Velox 原有 fallback 行为。

### 4.4 benchmark data 去重方案已完成并进入 `fake_main` patch 集

已确认：`cpp/velox/benchmarks/data` 和 `cpp/bolt/benchmarks/data` 中 23 个跟踪数据文件里，除 `plan/q17_joins.json` 存在真实差异外，其余 22 个内容一致。

当前方案：

- 公共 benchmark 数据迁移到 `cpp/benchmarks/data`。
- `fake_main` patch 只处理社区已有的 Velox 数据移动和引用更新。
- Bolt 分支后续基于公共目录，只保留真正差异数据，例如 Bolt 版本的 `plan/q17_joins.json`。

该 patch 已作为 `aa18e3ba22 [GLUTEN][CORE] Move benchmark data to shared directory` 出现在 `liyang/fake_main`。

### 4.5 新增三个可抽取到 `fake_main` 的公共扩展点 patch

本轮又从 Bolt 大提交中拆出了三个更小的公共 patch，均在 `gluten.dev` 仓库家族下用独立 worktree 基于 `fake_main` 开发，并已进入当前 `liyang/fake_main` 提交链。

1. **SortAggregate offload 后端扩展点**
   - worktree：`/data00/home/liyang.127/oap/gluten.dev.offloadsort`
   - commit：`90e224a833 [GLUTEN][CORE] Add backend hook for sort aggregate offload`
   - 主要改动：
     - `gluten-substrait/src/main/scala/org/apache/gluten/backendsapi/SparkPlanExecApi.scala` 新增 `offloadSortAggregate(plan: BaseAggregateExec)` 默认实现，默认仍走 `HashAggregateExecBaseTransformer.fromSortAggregate(plan)`，保持 sort-based aggregate 语义。
     - `gluten-substrait/src/main/scala/org/apache/gluten/extension/columnar/offload/OffloadSingleNodeRules.scala` 中 `SortAggregateExec` offload 调用改走 backend API。
   - 目的：让 Bolt 可覆盖 SortAggregate 的 offload 行为，同时默认行为不变，不影响 Velox / ClickHouse。

2. **Sequence expression transformer 后端扩展点**
   - worktree：`/data00/home/liyang.127/oap/gluten.dev.sequencehook`
   - commit：`b59a38602f [GLUTEN][CORE] Add backend hook for sequence expressions`
   - 主要改动：
     - `gluten-substrait/src/main/scala/org/apache/gluten/backendsapi/SparkPlanExecApi.scala` 新增 `genSequenceTransformer(...)`，默认返回 `GenericExpressionTransformer`。
     - `gluten-substrait/src/main/scala/org/apache/gluten/expression/ExpressionConverter.scala` 中 `Sequence` case 改为通过 backend API 创建 transformer。
   - 目的：让不同后端可按需特化 `sequence` 表达式转换；默认路径仍保持社区原有 generic transformer 语义。

3. **ShuffledColumnarBatchRDD 命名 metrics iterator 并暴露 delegate**
   - worktree：`/data00/home/liyang.127/oap/gluten.dev.shuffleiter`
   - commit：`1ae8a16664 [GLUTEN][CORE] Expose shuffle reader metrics iterator delegate`
   - 主要改动：
     - `gluten-substrait/src/main/scala/org/apache/spark/sql/execution/ShuffledColumnarBatchRDD.scala` 将原匿名 metrics iterator 改为命名类 `ShuffleReaderWithMetricsIterator`。
     - 该类通过 `val delegate: Iterator[Product2[Int, ColumnarBatch]]` 暴露底层 shuffle reader iterator。
   - 目的：默认 metrics 统计行为保持不变，同时允许 Bolt 后端在需要时访问底层 shuffle iterator。

这三个 patch 曾经误建在 `incubator-gluten` 仓库家族，后续已通过 bundle/fetch 迁移到 `gluten.dev` 仓库家族并保持 commit hash 不变。`fake_add_bolt_backend` 已基于这三个 commit 重新 rebase；唯一冲突发生在 `ShuffledColumnarBatchRDD.scala` 中重复新增 `ShuffleReaderWithMetricsIterator`，已保留公共 patch 的注释和实现后完成 rebase。

### 4.6 cpp/core 与 Bolt 解耦做过专项分析和局部修改验证

曾在独立 worktree `/data00/home/liyang.127/oap/gluten.dev.cppdecouple` 上分析 `cpp/core` 中混入的 Bolt 专属逻辑，并形成以下原则：

1. `cpp/core` 只保留后端无关抽象和通用 JNI/native glue。
2. Bolt 专属逻辑应物理位于 `cpp/bolt`，或至少受 `GLUTEN_ENABLE_BOLT` 宏隔离。
3. 非 Bolt 构建路径不得 include Bolt 头文件、引用 Bolt Java 类名或链接 `bolt::bolt`。
4. Bolt loader 需要的 `JNI_OnLoad_Base` / `JNI_OnUnload_Base` 只在 Bolt 编译路径暴露。

该专项中已经验证过 whitespace / grep 层面的检查，但尚未跑完整 native build，也尚未形成最终可合并 commit。

## 5. 当前仍未完成 / 风险点

### 5.5 HEAD 扫描已识别的确定性编译/边界风险

对 HEAD commit 做只读扫描后，当前需要优先落地的修复项如下：

1. **修复 `cpp/core/jni/JniWrapper.cc` 中未定义变量 `conf`**
   - 位置：`cpp/core/jni/JniWrapper.cc:576`。
   - 当前 `nativeCreateKernelWithIterator` 中直接调用 `isParallelExecEnabled(conf)`，但该作用域没有定义 `conf`。
   - 这是确定性 C++ 编译失败，应作为第一优先级修复。
   - 修复时不要只补局部变量，还要确认这段 parallel / shuffle wrapper 逻辑是否应受 Bolt 编译宏隔离。

2. **切断公共 `cpp/core` 对 Bolt 头文件、符号和 Java 类名的无条件依赖**
   - `cpp/core/compute/Runtime.h` 无条件 include Bolt native memory manager 头。
   - `cpp/core/jni/JniWrapper.cc` 无条件 include / 调用 `BoltGlutenMemoryManager`。
   - `cpp/core/utils/ConfigResolver.h` 无条件 include `bolt/core/Config.h`。
   - `cpp/core/jni/JniCommon.h` 中包含 Bolt shuffle iterator wrapper 和 Bolt Java class 名。
   - 处理原则：Bolt 专属逻辑要么下沉到 `cpp/bolt`，要么用 `GLUTEN_ENABLE_BOLT` 严格隔离；非 Bolt 构建不得看到 Bolt include、Bolt symbol、Bolt Java class。

3. **重新整理 core JNI loader 入口**
   - 当前 `cpp/core/jni/JniWrapper.cc` 把标准 `JNI_OnLoad` / `JNI_OnUnload` 改成了 `JNI_OnLoad_Base` / `JNI_OnUnload_Base`。
   - 这对 Bolt loader 有用，但不能无条件改变公共 core 的加载入口。
   - 处理原则：非 Bolt 构建保留标准 JNI entrypoint；Bolt 构建才暴露 base entrypoint 给 `cpp/bolt/jni/BoltJniWrapper.cc` 调用。

4. **修复 `ConfigResolver` namespace / 链接问题**
   - `cpp/core/utils/ConfigResolver.h` 在 `namespace gluten` 下声明 `getConfigValue`。
   - `cpp/core/utils/ConfigResolver.cc` 中 `getConfigValue` 定义在全局 namespace，容易导致链接问题。
   - 如果该 helper 保留在 core，必须去掉 Bolt 依赖并统一 namespace；如果只给 Bolt 用，应移到 Bolt 目录。

5. **补齐 `GlutenConfig` 改名兼容或全量迁移引用（已处理）**
   - `VELOX_FORCE_ORC_CHAR_TYPE_SCAN_FALLBACK` / `VELOX_SCAN_FILE_SCHEME_VALIDATION_ENABLED` 被改成通用名称。
   - 但 `gluten-ut/spark35`、`gluten-ut/spark40`、`gluten-ut/spark41` 仍引用旧常量。
   - 当前已在 `GlutenConfig` 中保留旧 Velox 命名常量的兼容 alias，避免 Spark 3.5/4.0/4.1 profile 中仍引用旧常量时编译失败；同时清理了同文件中 `GLUTEN_PARALLEL_ENABLED_KEY` 相关行尾多余分号。

### 5.6 HEAD 扫描已识别的 CMake / 构建脚本风险

说明：上一轮扫描中列出的 Paimon 相关项按当前决策先忽略，继续由专项处理；公共 UT 相关项已由用户复核确认无问题，不再列为当前待办。

当前仍需跟进的是 **CMake 和构建脚本拼接痕迹**：

   - `cpp/CMakeLists.txt`、`cpp/core/CMakeLists.txt` 在 `cmake_minimum_required()` 前用 `if (ENABLE_BOLT) include(...); return()` 切换构建路径，结构需要整理。
   - `ENABLE_BOLT` / `BUILD_BOLT` 命名不统一。
   - `cpp/core/benchmarks/CMakeLists.txt` 重复 license / 无条件链接 `bolt::bolt` 已回退，后续纳入 `cpp/` 公共代码专项统一处理。
   - `dev/gen-all-config-docs.sh` 新增 Bolt 段使用裸 `mvn` 的问题已处理，现已改为 `${MVN_CMD}`。
   - `dev/docker/Dockerfile.centos8-bolt`、`dev/docker/Dockerfile.ubuntu22-bolt`、`dev/install-conan.sh`、`dev/install-gcc.sh` 中本轮可独立处理的 trailing whitespace 已清理；当前工作区 `git diff --check` 已通过。
   - 剩余 `cpp/` 侧 CMake 结构、命名和链接边界问题后续纳入 `cpp/` 公共代码专项统一处理。

## 6. 后续计划

### 6.1 P1：继续收敛 Bolt 大提交中的公共改动

建议按目录和语义继续拆分 `fake_add_bolt_backend` 相对 `fake_main` 的差异：

1. **先排除 Bolt 专属目录**
   - `backends-bolt/**`
   - `cpp/bolt/**`
   - `docs/bolt*`、Bolt quick start、Bolt function docs 等。

2. **重点审查公共目录**
   - `gluten-core/**`
   - `gluten-substrait/**`
   - `gluten-ut/**`
   - `cpp/core/**`
   - `pom.xml`、`package/pom.xml`、`Makefile`、`dev/**`。

3. **对每类公共改动做决策**
   - 后端无关接口/框架：抽 patch 到 `fake_main`。
   - Bolt 专属但暂时位于公共目录：尽量迁回 Bolt 模块或用后端 API / 编译宏隔离。
   - 内部临时改动：丢弃。
   - Paimon：单独列出，交给专项适配。

4. **先处理 HEAD 扫描出的确定性问题**
   - 修复 `JniWrapper.cc` 未定义 `conf`。
   - 恢复 / 宏隔离 core JNI entrypoint。
   - 去掉 `cpp/core` 对 Bolt 的无条件 include / symbol / Java class 依赖。
   - 修复 `ConfigResolver` namespace 和 Bolt 依赖边界。
   - 补齐 `GlutenConfig` 改名兼容或全 profile 引用迁移。（已处理：保留旧常量 alias）

5. **暂不处理 Paimon，公共 UT 不再作为风险项跟进**
   - Paimon 相关改动继续按专项处理，不纳入当前 P1 主线。
   - 公共 UT 相关改动用户已复核无问题，当前无需迁移或重分类。

6. **清理 CMake / 构建脚本拼接痕迹**
   - 整理 `cpp/CMakeLists.txt` / `cpp/core/CMakeLists.txt` 中 Bolt 构建路径切换方式。
   - `cpp/core/benchmarks/CMakeLists.txt` 重复 license 和无条件 `bolt::bolt` 链接暂不在当前轮处理，后续纳入 `cpp/` 公共代码专项。
   - `dev/gen-all-config-docs.sh` 使用 `${MVN_CMD}` 已处理。
   - `dev/docker/Dockerfile.centos8-bolt`、`dev/docker/Dockerfile.ubuntu22-bolt`、`dev/install-conan.sh`、`dev/install-gcc.sh` 的 trailing whitespace 已清理，并通过当前工作区 `git diff --check` 验证。

7. **每抽一个 patch 就重复闭环**
   - 在 `fake_main` 上形成 commit。
   - rebase / merge 回 `fake_add_bolt_backend`。
   - 确认 Bolt 分支公共 diff 继续减少。

### 6.2 P2：修编译和基础测试

当公共改动收敛到足够小后，进入编译和 UT 修复：

1. 先跑最小静态检查：
   - `git diff --check HEAD~1 HEAD`
   - Scala/Java 编译前的明显冲突检查。
2. 先做 profile 级编译风险排查：
   - Spark 3.5 / 4.0 / 4.1 对 `GlutenConfig` 旧常量引用是否已清理或有 alias。
   - Bolt profile 下新增 source dir 是否完整接入。
3. 再跑 Maven 编译，优先选择当前目标 Spark 版本和 Bolt profile。
4. 修复 native/CMake/conan 问题：
   - 重点关注 `BOLT_HOME` 传递。
   - 关注 `cpp/core` 中是否仍有非 Bolt 构建也会看到的 Bolt 依赖。
   - 关注 `JNI_OnLoad` / `JNI_OnLoad_Base` 是否符合 Bolt / non-Bolt 双路径。
5. 跑基础 TPCH suite：
   - 不追求全部 UT 一次性通过。
   - 目标是至少让基本 TPCH suite 作为内部验证基线跑通。
6. 在修编译/UT 时如果发现必须修改公共代码，继续追加到 `fake_main`，不要直接把公共修复埋在 Bolt 大提交里。

### 6.3 社区讨论阶段：整理 fake_main patch 并分批贡献

当前 `fake_main` 中的 patch 不应一次性无差别提交社区，建议进一步合并和分类：

1. **Core/API 类 patch**
   - task attempt id / pool name 传递。
   - `ColumnarBatches` helper public。
   - HiveGenericUDTF 支持。

2. **执行框架 / 统计信息类 patch**
   - InputStats plumbing。
   - 需要重点说明默认关闭、对现有后端无行为影响、为什么未来后端需要该接口。

3. **性能/内存优化类 patch**
   - InSet literal 延迟构造。
   - 需要用大 IN 列表场景解释收益。

4. **Velox 功能增强类 patch**
   - `sequence`。
   - `format_number`。
   - 这些应按 Velox 功能增强单独与社区讨论。

5. **测试/benchmark 资源中立化 patch**
   - overwrite SQL tests 共享目录。
   - benchmark data 共享目录。
   - 这类 patch 重点说明减少重复维护、为多 backend 复用做准备。

社区呈现方案应强调：

- 这些 patch 不是为了强行引入 Bolt 专属逻辑，而是从 Bolt 迁移中识别出的后端无关能力。
- 所有默认行为应保持不变。
- 对 Velox / ClickHouse 的影响要可解释、可验证。
- PR 模板中需要如实填写 generative AI tooling disclosure。

## 7. 推荐的下一步执行清单

下一步不再泛泛扫描，而是按 HEAD 扫描结果直接进入分层处理：

1. **P1-CORE-NATIVE：先修 cpp/core 与 Bolt 解耦**
   - 修 `cpp/core/jni/JniWrapper.cc:576` 未定义 `conf`。
   - 删除或宏隔离 `cpp/core/compute/Runtime.h` 中的 Bolt include。
   - 宏隔离 `JniWrapper.cc` 中的 `BoltGlutenMemoryManager`、parallel shuffle wrapper 和 `ConfigResolver` 使用。
   - 宏隔离 `JNI_OnLoad` / `JNI_OnLoad_Base`，保证 non-Bolt 构建仍有标准 JNI entrypoint。
   - 修 `ConfigResolver` namespace / 链接问题，并决定它属于 core helper 还是 Bolt-only helper。

2. **P1-SCALA-CONFIG：修公共配置改名残留（已处理）**
   - 已为 `VELOX_FORCE_ORC_CHAR_TYPE_SCAN_FALLBACK` / `VELOX_SCAN_FILE_SCHEME_VALIDATION_ENABLED` 加兼容 alias，保留旧引用编译兼容性。
   - 已清理 `GLUTEN_PARALLEL_ENABLED_KEY` / `GLUTEN_PARALLEL_ENABLED_KEY_DEFAULT` 行尾多余分号。

3. **P1-SCOPE：明确当前暂不处理项**
   - Paimon 相关改动先忽略，继续按专项适配处理。
   - 公共 UT 已由用户复核确认无问题，不再作为当前整改项。

4. **P1-CMAKE-FORMAT：清理 CMake / 脚本拼接痕迹**
   - 调整 `cpp/CMakeLists.txt` / `cpp/core/CMakeLists.txt` 的 Bolt include/return 位置和命名。
   - `cpp/core/benchmarks/CMakeLists.txt` 删除重复 license，并去掉非 Bolt 场景下的 `bolt::bolt` 链接。（已回退，后续纳入 `cpp/` 公共代码专项）
   - `dev/gen-all-config-docs.sh` 改用 `${MVN_CMD}`。（已处理）
   - `dev/docker/Dockerfile.centos8-bolt`、`dev/docker/Dockerfile.ubuntu22-bolt`、`dev/install-conan.sh`、`dev/install-gcc.sh` 中可独立清理的 trailing whitespace 已处理，当前工作区 `git diff --check` 通过。

5. **P2-VERIFY：进入最小验证闭环**
   - 先跑 `git diff --check HEAD~1 HEAD`。
   - 再做 Maven profile 编译验证，优先 Bolt 目标版本，同时覆盖会受 `GlutenConfig` 改名影响的 Spark profile。
   - 再跑 native/CMake/conan 最小验证，重点确认 `BOLT_HOME` 和 non-Bolt core 不被 Bolt 依赖污染。
   - 最后推进基本 TPCH suite。

6. **社区化整理**
   - 对修复过程中确认的后端无关能力继续抽到 `fake_main`。
   - 对 Bolt-only 能力留在 `fake_add_bolt_backend`。
   - 对 Paimon 专项形成单独清单交给专项负责人。
   - 将 `fake_main` 中 patch 按社区讨论维度整理成 PR 方案和说明材料。

## 8. 当前结论

当前工作已经完成了第一轮最关键的 P0：公共 patch 已从 Bolt 大提交中抽出并进入 `fake_main`，Bolt 分支也已基于这些 patch 收敛成单个大提交。下一阶段的重点不是继续堆功能，而是继续做 P1 的“减法”和“分类”：把剩余公共改动继续抽离或丢弃，把 Bolt 专属实现压回 Bolt 模块，把 Paimon 等专项拆出去。只有当公共改动收敛到足够小后，P2 的编译和 TPCH 基础验证才会更可控。
