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

## 9. 低优先级 backlog：bolt / velox 可继续抽公共的文件

说明：以下内容基于“**仅忽略 backend symbol 命名差异（Bolt/Velox）**，其余逻辑必须完全一致”的口径整理，
当前确认**值得抽取但优先级不高**，先记录，后续再择机推进。

### 9.1 JVM / backend 层已识别的候选与逐步公共化实施方案

按该口径，`backends-bolt` / `backends-velox` 中已有一批文件在仅 backend rename 后完全一致，适合作为后续公共化候选。

#### 9.1.1 主代码方向（优先考虑）
- execution / expression：
  `FilterExecTransformer.scala`、`TopNTransformer.scala`、`ColumnarRangeExec.scala`、
  `ArrowColumnarTo{Bolt,Velox}ColumnarExec.scala`、`ExpressionTransformer.scala`、
  `{Bolt,Velox}BloomFilterMightContain.scala`、`DummyExpression.scala`、`HLLAdapter.scala`
- datasource / backend glue：
  `{Bolt,Velox}CarrierRowType.scala`、`{Bolt,Velox}DataSourceUtil.scala`、
  `ColumnarBatchSerializerInstance.scala`
- metrics：
  `Project/Sample/Expand/Generate/Window/WriteFiles/NestedLoopJoin/Sort/Filter/Union/LimitMetricsUpdater.scala`
- extension：
  `HLLRewriteRule.scala`、`RewriteCastFromArray.scala`、
  `BloomFilterMightContainJointRewriteRule.scala`
- spark 侧通用 glue：
  `BroadcastModeUtils.scala`、`{Bolt,Velox}FormatWriterInjects.scala`、
  `{Bolt,Velox}HiveUDFTransformer.scala`、`ShuffleUtil.scala`、`TaskStatsAccumulator.scala`
- Java JNI / util：
  `ConfigJniWrapper.java`、`JniFilesystem.java`、`OnHeapFileSystem.java`、`UdfJniWrapper.java`、
  `IteratorMetricsJniWrapper.java`、`{Bolt,Velox}MemoryProfiler.java`、
  `{Bolt,Velox}ColumnarBatchJniWrapper.java`、`{Bolt,Velox}BloomFilterJniWrapper.java`、
  `{Bolt,Velox}FileSystemValidationJniWrapper.java`、`{Bolt,Velox}CudfPlanValidatorJniWrapper.java`
- Celeborn / Uniffle：
  `CelebornPartitionWriterJniWrapper.java`、`{Bolt,Velox}CelebornColumnarShuffleWriterFactory.scala`、
  `{Bolt,Velox}CelebornColumnarBatchSerializerFactory.scala`、`PartitionPusher.scala`、
  `UnifflePartitionWriterJniWrapper.java`、`UniffleShuffleManager.java`
- Iceberg：
  `{Bolt,Velox}IcebergAppendDataExec.scala`、
  `{Bolt,Velox}IcebergOverwritePartitionsDynamicExec.scala`、
  `{Bolt,Velox}IcebergOverwriteByExpressionExec.scala`、
  `{Bolt,Velox}IcebergReplaceDataExec.scala`、
  `IcebergTransformUtil.scala`、`IcebergNestedFieldVisitor.java`、`TestConfUtil.java`

#### 9.1.2 测试 / 资源层面
- delta / hudi / iceberg / spark34 多组 test suite 在仅 backend symbol rename 后也完全一致，
  可在后续考虑抽 shared test mixin / 公共测试基类。
- `IcebergPartitionSpec.proto`、`IcebergNestedField.proto`、`log4j2.properties` 也已确认一致。

#### 9.1.3 模块承载方案：建议引入 `backends-core`

对于 9.1 中这批 JVM / backend 层公共候选，建议后续优先考虑引入一个新的共享模块作为承载点，暂命名为 **`backends-core`**。

引入它的目的不是再造一个新的全局 `gluten-core`，而是提供一个 **仅服务于 Bolt / Velox 共享实现的窄职责 JVM 层模块**，用于承接：
- backend rename 后完全一致的实现；
- 只服务于 Bolt / Velox 的 shared helper / util / base class；
- 逐步公共化后 backend 目录中只剩薄 façade 的那部分 shared implementation。

建议的 Maven 依赖关系是：
- `gluten-core` / `gluten-substrait`
- `backends-core`
- `backends-velox` / `backends-bolt`

也就是说：
- `backends-core` 可以依赖 `gluten-core` / `gluten-substrait`；
- `backends-velox` / `backends-bolt` 依赖 `backends-core`；
- `backends-core` **不能反向依赖**任何具体 backend 模块。

建议放进 `backends-core` 的内容：
1. **MetricsUpdater 与 JVM 侧共享 metrics 实现**
2. **execution / expression 的 shared glue、helper、base class**
3. **rewrite / extension 的共享实现**
4. **datasource / spark glue / Java JNI wrapper 的共享包装层**
5. **Iceberg util / visitor / proto / shared test mixin**

不建议放进 `backends-core` 的内容：
1. **全局公共 backend SPI / 契约接口**
   - 如 `BackendSettingsApi`、`SparkPlanExecApi`、`IteratorApi`、`MetricsApi`
   - 这类仍应留在 `gluten-core` / `gluten-substrait`
2. **backend-specific capability / config / validation**
   - 如 `BoltBackend.scala`、`BoltConfig.scala`、`VeloxBackend.scala`、`VeloxConfig.scala`
3. **batch type / row type / backend name / JNI class name 常量本体**
   - 这些最多保留 shared 抽象，不建议把具体 backend identity 放进 `backends-core`
4. **native 主 runtime / memory / shuffle / plan converter 主体**
   - 这些更适合继续按 9.2 的策略在 `cpp/core` 或 backend 目录中分别处理

命名上，`backends-core` 可以接受，但它在文档里的定义必须明确为：
- **不是新的全局 core**；
- **是 Velox / Bolt shared backend JVM layer**。

第一批若引入该模块，建议只迁最稳的一组：
- metrics updater
- rewrite / extension helper
- JVM util / JNI wrapper 公共包装
- Iceberg util / proto / test mixin

这样可以先验证模块边界、Maven 依赖、编译与测试链路是否稳定，再决定是否扩大覆盖范围。

#### 9.1.4 总体原则
1. **先 JVM，后 native；先完全一致，后轻度分叉。**
   - JVM 层已有一批“仅 rename 即一致”的文件，收益最高、风险最低。
   - native 侧只优先处理已确认完全一致的 23 对文件，不碰 runtime 主体。
2. **每个公共 patch 必须满足“单一抽象目的”。**
   - 一个 patch 只做一类事情：例如“抽公共 MetricsUpdater”、“抽公共 JNI wrapper 基类”、“抽公共 Iceberg util”。
   - 避免在一个 patch 中同时移动 execution + metrics + tests，降低 review 成本。
3. **公共化的默认手段是“抽公共实现 + 保留超薄 backend façade”，而不是强行合并 backend API。**
   - 对 Bolt / Velox 名字不同但逻辑一致的文件，优先抽出公共基类、trait、helper、util 或 shared object。
   - backend 目录下只保留 backend 名字/注册点、少量 batch type / row type / JNI class 名差异、capability / config / validation 差异。
4. **CH 不参与抽象来源，但必须参与回归验证。**
   - 这些 patch 的抽象来源是 Bolt / Velox。
   - 但凡落点在 `gluten-core`、`gluten-substrait`、shared JVM util、公共 JNI util，就必须把 CH 当作被影响方做 smoke 验证。
5. **禁止为了公共化而恢复旧接口或扩大 backend hook 面。**
   - 公共 patch 只能顺着当前主干接口方向抽。
   - 不允许为了让 Bolt/Velox 看起来更像，而把已经收敛掉的旧 hook、旧 shim、旧 helper 再加回去。

#### 9.1.5 建议的 patch 拆分节奏

建议按 **P0 → P4** 五个波次推进；每个波次内部仍按“小 patch 串”的方式提交。

##### P0：铺路 patch（只做基础设施，不直接大搬文件）
- 目的：为后续公共化提供稳定承载点，避免一上来就在 backend 目录大挪文件。
- 建议动作：
  - 优先评估并落地 `backends-core` 模块，作为 Bolt / Velox 共享 JVM 实现的收敛点。
  - 在 `gluten-core` / `gluten-substrait` / `backends-core` / 公共 JVM util 层补齐可复用的 shared package 落点。
  - 先抽 backend 无关的 util / updater / rewrite helper 基类，以及 backend 名字映射、factory、builder 的轻量 façade 模式。
- 产出形式：
  - 只新增 shared helper / trait / abstract class / util / 模块骨架；
  - 不要求第一批就删除 Bolt/Velox 重复文件。

##### P1：Metrics / rewrite / execution glue（最优先）
- **MetricsUpdater 公共化**：抽到 shared metrics package；Bolt / Velox `MetricsApi` 仅负责组装 metric map。
- **rewrite / extension 公共化**：`HLLRewriteRule.scala`、`RewriteCastFromArray.scala`、`BloomFilterMightContainJointRewriteRule.scala` 优先迁到公共扩展包。
- **execution glue 公共化**：`FilterExecTransformer.scala`、`TopNTransformer.scala`、`ColumnarRangeExec.scala`、`ArrowColumnarTo{Bolt,Velox}ColumnarExec.scala`、`ExpressionTransformer.scala` 先抽 shared 基类 / helper，再保留薄 backend wrapper。

##### P2：JNI wrapper / datasource glue / spark glue
- **Java JNI wrapper 公共化**：对 backend 仅 class name / native symbol 前缀不同的实现，优先抽公共父类或模板方法；backend 目录只保留 loader / class name 常量。
- **datasource / backend glue 公共化**：`{Bolt,Velox}CarrierRowType.scala`、`{Bolt,Velox}DataSourceUtil.scala`、`ColumnarBatchSerializerInstance.scala` 优先做 shared definition + backend alias / 工厂化 provider。
- **spark 侧通用 glue 公共化**：`BroadcastModeUtils.scala`、`{Bolt,Velox}FormatWriterInjects.scala`、`{Bolt,Velox}HiveUDFTransformer.scala`、`ShuffleUtil.scala`、`TaskStatsAccumulator.scala` 优先抽公共 util / base。

##### P3：Iceberg / Celeborn / Uniffle 专题 patch
- **Iceberg 专题**：先抽 util / visitor / proto / test conf，再抽 exec shared base，最后再看 Append/Overwrite/Replace 四个 exec 是否能合并为共享实现 + backend adapter。
- **Celeborn / Uniffle 专题**：先抽公共 writer factory，再抽 serializer factory；backend-specific shuffle wrapper / JNI 协议继续留 backend 目录。

##### P4：测试 / 资源 / native 已完全一致文件
- **测试层共享化**：优先抽 shared test mixin / 抽象基类，不建议第一步直接删除两边 suite。
- **资源与 proto 共享化**：`IcebergPartitionSpec.proto`、`IcebergNestedField.proto`、`log4j2.properties` 可单独成 patch。
- **native 完全一致文件专题**：只处理 9.2 中已确认完全一致的 23 对文件，每次只抽一个小簇。

#### 9.1.6 每个 patch 的推荐落地模板
1. **先引入 shared 实现，不改语义**：新增 shared class / helper / trait / util，让其中一边先开始委托 shared 实现。
2. **再让 Velox / Bolt 同步切到 shared 实现**：保持外部类名、入口、注册点不变。
3. **最后删除重复代码 / 收薄 façade**：backend 目录里只剩 type alias、object wrapper、factory 注册、backend constants。

#### 9.1.7 明确不建议优先动的区域
1. **native 主 runtime / memory manager / plan converter / shuffle runtime**：backend 行为差异已实质化，强抽公共风险高于收益。
2. **Bolt 独有路径**：shuffle wrapper、paimon native、task status listener、Bolt 特有 UDF / version。
3. **任何会扩大公共 backend hook 面的改动**：如果某 patch 需要先给 core/substrait 新增一堆只给 Bolt/Velox 用的 hook，优先暂停重审。

#### 9.1.8 验证矩阵与合入门槛
1. **最小编译验证**：`backends-velox`、`backends-bolt` compile；若 patch 落在公共 JVM 层，至少再补一轮 CH compile smoke。
2. **受影响模块定向测试**：metrics / rewrite / iceberg / celeborn / uniffle patch 需要最小定向 UT 或现有 suite 验证。
3. **native patch 验证**：仅对 native patch 额外做 CMake/conan 或最小 target build。
4. **结构性检查**：`git diff --check`、不新增 backend-only 公共 hook、不让 CH 编译路径引入 Bolt/Velox 依赖。

#### 9.1.9 建议的实际提交顺序
> 进度更新（`/data00/home/liyang.127/oap/gluten.dev.backendscore` worktree，分组 1~7 已按单独 commit 落地）

1. **MetricsUpdater 公共化串** — 已完成  
   commit: `bbaf1c218e` `[GLUTEN][CORE] Extract shared write-file metrics updater`
2. **rewrite / extension 公共化串** — 已完成  
   commit: `50bd8f717f` `[GLUTEN][CORE] Share array-to-string cast rewrite rule`
3. **execution glue 公共化串** — 已完成  
   commit: `9b911c4618` `[GLUTEN][CORE] Move TopN transformer into backends-core`
4. **JNI wrapper / datasource glue 公共化串** — 已完成  
   commit: `3d280271e0` `[GLUTEN][CORE] Move ColumnarBatchSerializerInstance to backends-core`
5. **Iceberg util / visitor / proto 公共化串** — 已完成  
   commit: `992ce0407f` `[GLUTEN][CORE] Share Iceberg visitor and proto helpers`
6. **Celeborn / Uniffle 工厂层公共化串** — 已完成（本轮实际先落 Celeborn 工厂层公共基类）  
   commit: `5ae169adc9` `[GLUTEN][CORE] Extract shared Celeborn factory bases`
7. **测试 mixin / 公共测试基类** — 已完成（本轮先落 native write 测试公共基类）  
   commit: `151f2420dc` `[GLUTEN][CORE] Share native write test checker base`
8. **native 23 对完全一致文件的小簇抽取**

补充说明：
- 上述 1~7 均遵循“**一组一提交**”原则完成，`backends-core` 已作为 Bolt / Velox shared JVM layer 引入。
- 已完成的 compile / test-compile 验证覆盖 `backends-core`、`backends-velox`，并对 ClickHouse 受影响路径做了 smoke compile 尝试。
- ClickHouse 现存的 Delta 编译失败不由本轮 backends-core 抽取引入，阻塞点位于 `backends-clickhouse/src-delta33/main/scala/org/apache/spark/sql/delta/Snapshot.scala`。

#### 9.1.10 当前建议
- 这一批文件**确实值得抽**，但当前优先级低于 rebase 冲突收敛、cpp/core 解耦和 native 构建边界整理。
- 后续若要推进，建议优先从 **metrics / JNI wrapper / execution glue / iceberg util** 这几组切入。
- 若决定引入 `backends-core`，建议先把它作为 **Bolt / Velox shared JVM layer** 使用，而不是把它扩张成新的全局 core。

### 9.2 cpp/native 侧 bolt / velox 对齐分析 backlog

同样按“**仅忽略 backend symbol 命名差异（Bolt/Velox）**，其余逻辑必须完全一致”的口径，
对 `cpp/bolt` 与 `cpp/velox` 做了初步扫描，并补充了对“第二档（copy + rename 后仅少量差异）”和“第三档（明显分叉）”的代表性复核。

#### 9.2.1 总体结论
- 完全一致文件对：**23 对**
- 路径/职责可对上但内容不完全一致：**113 对**
- Bolt 独有 / Velox 无直接对应：**16 个**
- 相比 JVM 侧，cpp/native 侧已经明显进入“**接口类似，但实现分叉**”阶段，
  不适合做大批量整文件公共化；更适合先抽公共 helper / collector / utility。

#### 9.2.2 已确认完全一致、可后续低风险抽取的候选

1. **substrait**
   - `cpp/bolt/substrait/SubstraitExtensionCollector.cc`
   - `cpp/bolt/substrait/SubstraitExtensionCollector.h`
   - `cpp/bolt/substrait/VariantToVectorConverter.cc`

2. **jni / udf 接口层**
   - `cpp/bolt/jni/JniUdf.cc`
   - `cpp/bolt/jni/JniUdf.h`
   - `cpp/bolt/jni/JniFileSystem.h`
   - `cpp/bolt/udf/Udf.h`
   - `cpp/bolt/udf/Udaf.h`

3. **operators / writer 抽象**
   - `cpp/bolt/operators/functions/Arithmetic.h`
   - `cpp/bolt/operators/functions/RegistrationAllFunctions.h`
   - `cpp/bolt/operators/writer/BoltDataSource.h`
     （与 velox 的 `VeloxDataSource.h` 在仅 symbol rename 后完全一致）

4. **lakehouse / util / test / benchmark**
   - `cpp/bolt/compute/iceberg/IcebergPlanConverter.cc`
   - `cpp/bolt/utils/JsonToProtoConverter.h`
   - `cpp/bolt/utils/Common.cc`
   - `cpp/bolt/tests/JsonToProtoConverter.h`
   - `cpp/bolt/tests/FilePathGenerator.h`
   - `cpp/bolt/tests/JsonToProtoConverter.cc`
   - `cpp/bolt/tests/OrcTest.cc`
   - `cpp/bolt/tests/utils/TestAllocationListener.h`
   - `cpp/bolt/tests/utils/TestUtils.h`
   - `cpp/bolt/tests/utils/TestStreamReader.h`
   - `cpp/bolt/benchmarks/exec/OrcConverter.cc`
   - `cpp/bolt/cudf/CudfPlanValidator.h`

#### 9.2.3 第二档（copy + rename + 少量差异）的推荐抽法

这类文件不建议整文件硬合并，更稳妥的方式是：**把公共算法骨架放到 `cpp/core`，Bolt / Velox 保留原类名、原头文件路径、原 public API，仅在 backend 目录保留超薄 wrapper / traits。**

代表性样本及判断：
- `FileReaderIterator.*`：两边几乎逐行一致，只差 memory pool 类型与异常入口，适合先抽 header-only helper / factory。
- `BoltToSubstraitPlan.*` / `VeloxToSubstraitPlan.*`：主体流程高度一致，主要差异是 expr/type convertor、row type、错误宏，适合抽 template base。
- `SubstraitParser.*`：公共主体很大，但 timestamp、ROWINDEX、function mapping 等处存在少量真实语义分叉，适合抽公共主干 + backend hook。
- `ConfigExtractor.*`：Spark/Hadoop FS 配置抽取高度相似，但最终装配目标和附加能力不同，适合抽公共 builder / helper，不适合强合并外层 API。

差异大致可归纳为四类：
1. **纯类型 / 命名空间替换**：如 memory pool、row type、expr/type convertor。
2. **错误处理 / 断言入口替换**：如 `BOLT_FAIL/BOLT_CHECK` vs `VELOX_FAIL/VELOX_CHECK`。
3. **小范围真实语义分叉**：如 timestamp 解释、ROWINDEX 支持、function mapping。
4. **共同前处理 + backend 后处理**：如 `ConfigExtractor` 中的 FS config 抽取与最终 config 封装。

对应的推荐手法：
- **类型差异类**：`cpp/core` 中放 header-only template 或 traits base。
- **错误入口类**：由 backend traits 提供 fail/check/unsupported/nyi policy。
- **少量语义分叉类**：公共主干 + hook / policy，不在 core 中写 backend if/else。
- **前处理共通、收尾不同类**：抽公共 builder / helper / parameter object，外层 API 继续留在 backend 目录。

若推进 `cpp/core`，建议目录布局克制为：
- `cpp/core/substrait/ToSubstraitPlanBase.h`
- `cpp/core/substrait/SubstraitParserBase.h`
- `cpp/core/operators/reader/FileReaderIteratorFactory.h`
- `cpp/core/utils/config/SparkFsConfigExtractor.h`
- `cpp/core/utils/config/HiveConfigMapBuilder.h`

backend 侧则保留：
- `cpp/bolt/substrait/*Traits.h`、`cpp/velox/substrait/*Traits.h`
- 现有 `SubstraitParser.*` / `*ToSubstraitPlan.*` / `ConfigExtractor.*` 的薄 wrapper

推荐的首批切入顺序：
1. `FileReaderIterator.*`
2. `BoltToSubstraitPlan.*` / `VeloxToSubstraitPlan.*`
3. `SubstraitParser.*`
4. `ConfigExtractor.*`

#### 9.2.4 明显“像但已分叉”的区域（暂不建议整文件抽公共）
- substrait 主转换链：
  `SubstraitParser.*`、`SubstraitTo{Bolt,Velox}Plan.*`、
  `SubstraitTo{Bolt,Velox}Expr.*`、`{Bolt,Velox}ToSubstrait{Expr,Plan,Type}.*`
- memory / batch / runtime 主体：
  `{Bolt,Velox}ColumnarBatch.*`、`{Bolt,Velox}MemoryManager.*`
- utils 中看似对齐但实际已分叉的实现：
  `ConfigExtractor.*`、`{Bolt,Velox}ArrowUtils.*`、`{Bolt,Velox}BatchResizer.*`、
  `{Bolt,Velox}WholeStageDumper.*`、`{Bolt,Velox}WriterUtils.*`
- operators reader / serializer / writer 主实现：
  `FileReaderIterator.*`、`ParquetReaderIterator.*`、
  `{Bolt,Velox}ColumnarBatchSerializer.*`、`{Bolt,Velox}ColumnarToRowConverter.*`、
  `{Bolt,Velox}RowToColumnarConverter.*`、`{Bolt,Velox}ColumnarBatchWriter.*`

#### 9.2.5 第三档 / 明显分叉区域的来源判断

对代表性样本复核后，**不能简单说“第三档分叉全部由 Bolt backend 特有改动带来”。**

更准确的判断是：第三档分叉由以下几类因素混合构成：
1. **Bolt-only 语义 / runtime 接入**
   - 例如 ICU regex、Bolt 自身 spiller / shuffle wrapper / 写出链路上的专属语义。
2. **Velox 后续继续演进、Bolt 未同步**
   - 例如 async executor 生命周期治理、scoped connector 注册/反注册、split/barrier 等运行时增强。
3. **backend-agnostic 修复 / 兼容项未同步**
   - 例如 `ConfigExtractor` 中直接出现的 `TODO sync bolt`、部分通用配置项未跟进、`ROWINDEX_COL` 支持缺失等。
4. **结构重排 / 本地 workaround / 未完全接线**
   - 例如 session config 的装配位置变化、memory manager 析构等待循环、Parquet writer 新配置路径存在但未完全接入等。

代表性样本的具体判断：
- `SubstraitParser.*`：以 Bolt-only 语义分叉为主，但夹杂能力缺口和未同步项，不应简单归类为纯 backend 本质差异。
- `ConfigExtractor.*`：主因更接近结构重排 + 后续演进偏移，且明确混有 backend-agnostic sync debt。
- `BoltRuntime.*` / `VeloxRuntime.*`：既有 Bolt runtime 接入，也有 Velox 后续生命周期治理演进，不能视为单边 backend 语义差异。
- `BoltMemoryManager.*` / `VeloxMemoryManager.*`：既有 backend API 差异，也有明显的异步资源释放 workaround，说明其中有非本质分叉。
- `BoltParquetDataSource.*` / `VeloxParquetDataSource.*`：既有 Bolt 写出语义，也混有结构漂移与局部未完全接线。

因此，这一档里应区分：
- **应保留 backend-specific 的差异**：runtime 行为、写出语义、特定函数映射、shuffle wrapper 等。
- **后续可回收/可同步的差异**：sync debt、生命周期治理、通用配置兼容项、局部 workaround。

##### 9.2.5.1 本轮已完成的安全同步（`Velox 后续继续演进、Bolt 未同步`）

基于“**只同步公共接口已定义、且 Bolt 已有底层支撑或可明确 no-op / fail-fast 的能力**”这一原则，本轮已经把 `9.2.5` 中一部分可安全对齐的 sync debt 落到当前 bolt 分支，且未扩大 Bolt / Velox 的耦合面。

已落地项：

1. **补齐 ResultIterator 的 split-aware 公共契约**
   - `cpp/bolt/compute/WholeStageResultIterator.h` 改为继承 `SplitAwareColumnarBatchIterator`，并补齐：
     - `addIteratorSplits(...)`
     - `noMoreSplits()`
     - `requestBarrier()`
   - 处理原则：
     - `addIteratorSplits(...)` 保持 no-op，因为 Bolt 已在 `SubstraitToBoltPlan` 阶段消费 input iterators，避免 task 创建后重复注入。
     - `requestBarrier()` 明确抛出“不支持”，保留 ABI 与公共接口语义边界，不做假实现。

2. **补齐 runtime 到 iterator 的 split 管理桥接**
   - `cpp/bolt/compute/BoltRuntime.h/.cc` 已新增并实现：
     - `noMoreSplits(ResultIterator* iter)`
     - `requestBarrier(ResultIterator* iter)`
   - 实现方式与 Velox 公共契约一致：通过 `dynamic_cast<SplitAwareColumnarBatchIterator*>` 下转后调用对应接口；若迭代器不支持 split 管理，则直接抛错。

3. **修正 Bolt task 完结 split 的覆盖范围**
   - `cpp/bolt/compute/WholeStageResultIterator.cc` 中，`tryAddSplitsToTask()` 原先只对 `scanNodeIds_` 调 `task_->noMoreSplits(...)`。
   - 本轮已补齐对 `streamIds_` 的 `noMoreSplits` 调用，避免 iterator-backed / stream 输入场景下公共 split 生命周期不完整。

4. **对齐 Hive connector session config 常量**
   - `cpp/bolt/compute/WholeStageResultIterator.cc` 中，原来 `ignore_missing_files` 仍走字符串兜底。
   - 本轮已切换为正式常量 `bolt::connector::hive::HiveConfig::kIgnoreMissingFilesSession`，与 bolt connector 侧 `ep/bolt/bolt/connectors/hive/HiveConfig.cpp` 的读取逻辑一致。

5. **明确本轮未同步的内容**
   - 没有强行对齐 Bolt 当前没有底层依赖的 Velox 生命周期治理能力，例如 async executor 生命周期管理、scoped connector 注册/反注册等。
   - 没有提前占位 `ROWINDEX_COL` 等类型系统尚未具备的能力。
   - 没有改写 Bolt 既有输入消费顺序与 runtime 主体结构。

##### 9.2.5.2 本轮验证结果

上述同步修改已在当前仓库完成一次实际 native 构建验证：

1. **环境问题已识别并绕过**
   - 直接运行 `make release` 时，默认 JDK 为 8，被仓库 Makefile 拦下。
   - 切换到 `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64` 后重新执行构建。

2. **bolt release native build 已通过**
   - 执行命令：`make -C /data00/home/liyang.127/oap/incubator-gluten release`
   - 结果：退出码 `0`，`bolt/libbolt_backend.so` 与 `bolt/libbolt_backend_static.a` 成功链接。

3. **本轮修改过的关键编译单元已确认通过**
   - `cpp/bolt/compute/BoltRuntime.cc`
   - `cpp/bolt/compute/WholeStageResultIterator.cc`

当前结论：`9.2.5` 中“Velox 后续继续演进、Bolt 未同步”的一部分可安全同步项已完成落地，并通过 bolt native release build 验证；后续如继续处理该类差异，应继续坚持“**同步公共契约 / 已有能力，不强推 Bolt 尚未具备的 runtime 语义**”的边界。

#### 9.2.6 Bolt 独有、当前不适合与 velox 对齐抽公共的部分
- shuffle wrapper：
  `cpp/bolt/shuffle/BoltShuffleReaderWrapper.h`、
  `cpp/bolt/shuffle/BoltShuffleWriterWrapper.h`、
  `cpp/bolt/shuffle/ReaderStreamIteratorWrapper.h`、
  `cpp/bolt/shuffle/RssClientWrapper.h`、
  `cpp/bolt/shuffle/SparkInputStream.h`
- paimon native：
  `cpp/bolt/compute/paimon/PaimonPlanUtils.cc`、
  `cpp/bolt/compute/paimon/PaimonPlanUtils.h`
- runtime/listener：
  `cpp/bolt/compute/TaskStatusListener.cc`、
  `cpp/bolt/compute/TaskStatusListener.h`
- backend 特有 UDF / version：
  `cpp/bolt/udf/BoltUdf.cc`、`cpp/bolt/udf/BoltUdf.h`、
  `cpp/bolt/version/version.h`、`cpp/bolt/version/version.h.in`

#### 9.2.7 当前建议
- cpp 侧后续若要抽公共，优先只看上述 **23 对完全一致文件** 与少量“第二档”代表性样本。
- 对于 **113 对“差一点相同”** 的文件，不建议整文件搬运，应该改为：
  - 抽公共 helper / util
  - 抽公共 collector / parser 辅助逻辑
  - 抽公共 header / traits / base class
- 若要优先尝试第二档，建议先以 `cpp/core` 为承载点，走“shared skeleton + backend traits / façade”的方式，避免直接放大 Bolt / Velox 的互相依赖。
- 暂时**不要急着碰** native 主 runtime、memory manager、主 plan converter、shuffle runtime，
  这些区域里既有 backend 本质差异，也有历史分叉与 sync debt，风险高于短期收益。

## 10. HEAD 相比 `liyang/fake_main` 的文件变更统计

本节记录一次只读盘点，用于量化当前 Bolt 大提交的体量和分布。

- 对比基线：`liyang/fake_main`（`e04ac1d151 [GLUTEN][CORE] Extract more shared cpp helper headers into cpp/core`）。
- 当前 HEAD：`9883363e71 add bolt backend in gluten`。
- 二者只差 1 个提交，即 Bolt backend 大提交本身（`merge-base` = `fake_main`）。所以本节统计 = 该大提交的全貌。
- 统计命令：`git diff --name-status liyang/fake_main HEAD`。

### 10.1 总览

| 状态 | 文件数 |
|---|---|
| 新增 (A) | 876 |
| 修改 (M) | 45 |
| **合计** | **921** |

无删除文件。

### 10.2 按模块分类

| 模块 | 新增 | 修改 | 说明 |
|---|---|---|---|
| `backends-bolt/` | 663 | 0 | Bolt 后端 JVM 主体，纯新增 |
| `cpp/` | 175 | 11 | native：bolt 新增 + core/velox 改动 |
| `gluten-ut/` | 10 | 22 | 测试模块，多为语义对齐改动 |
| `docs/` | 16 | 0 | Bolt 文档 |
| `dev/` | 5 | 2 | 构建脚本 |
| `gluten-substrait/` | 0 | 3 | 公共：`algebra.proto`、`IteratorApi.scala`、`GlutenConfig.scala` |
| `gluten-paimon/` | 1 | 3 | Paimon 适配（专项处理） |
| `.github/` | 3 | 0 | CI workflow |
| `shims/` | 0 | 1 | `GlutenBuildInfo.scala` |
| 根目录 / 其他 | `plan.md` / `Makefile` / `build.md` 新增；`pom.xml` / `package/pom.xml` / `README.md` 修改 | | |

### 10.3 按文件类型分类（新增 A）

| 类型 | 数量 | 主要分布 |
|---|---|---|
| `.txt` | 344 | **336 个在 `backends-bolt/src/test`**（UT 期望结果资源），其余为 CMakeLists |
| `.scala` | 204 | bolt main 123 + bolt test 70 + gluten-ut 10 |
| `.java` | 97 | bolt main 30 + bolt test 67 |
| `.cc` | 76 | cpp/bolt 73 + cpp/core 3 |
| `.h` | 75 | cpp/bolt 68 + cpp/core 7 |
| `.parquet` / `.orc` / `.csv` / `.json` | 33 | 测试数据资源 |
| `.md` | 19 | Bolt 文档 |
| `.proto` | 3 | bolt 内 proto |
| 其余 | sh / py / cmake / png / xml 等少量 | 构建与杂项 |

#### 10.3.1 backends-bolt 新增细分（main vs test）

- test 资源：`.txt` 336、`.scala` 70、`.java` 67、`.parquet` 16、`.csv` 4、`.orc` 2 等。
- main 代码：`.scala` 123、`.java` 30、`.proto` 3，以及 BoltBackend / 各 Component 等无扩展名注册文件。

#### 10.3.2 cpp 新增细分（按二级目录）

- `cpp/bolt`：`.cc` 73、`.h` 68、`.json` 10、`.txt` 5 等，是 native 主体。
- `cpp/core`：`.h` 7、`.cc` 3、`.txt` 2、`.cmake` 1（抽取出的 shared helper）。
- 顶层：`conanfile.py`、`CMakeUserPresets.json` 等。

### 10.4 45 个修改 (M) 文件分类与处理取向

这是真正涉及公共代码、需要重点 review 的部分，可分五类：

1. **公共 native（需 Bolt 宏隔离）** — 对应 P1-CORE-NATIVE：
   - `cpp/core/compute/Runtime.h`、`cpp/core/jni/JniWrapper.cc`、`cpp/core/jni/JniCommon.h`、
     `cpp/core/jni/JniCommon.cc`、`cpp/core/config/GlutenConfig.h`、`cpp/core/utils/Likely.h`、
     `cpp/CMakeLists.txt`、`cpp/core/CMakeLists.txt`。

2. **公共 JVM 接口 / proto**：
   - `gluten-substrait/.../substrait/proto/substrait/algebra.proto`、
     `gluten-substrait/.../backendsapi/IteratorApi.scala`、
     `gluten-substrait/.../config/GlutenConfig.scala`、
     `shims/common/.../GlutenBuildInfo.scala`。

3. **测试语义对齐（gluten-ut，22 个）**：
   - 覆盖 spark33 / 34 / 35 / 40 多版本的 `GlutenCachedTableSuite`、`GlutenDataFrameFunctionsSuite`、
     `GlutenCollectionExpressionsSuite`、`GlutenFallbackSuite`、`GlutenDynamicPartitionPruningSuite`、
     `GlutenDateFunctionsSuite`、`GlutenHiveSQLQuerySuite`，以及 `BackendTestSettings.scala`、
     `BackendTestUtils.scala`、各 `pom.xml`。

4. **Paimon（专项，不在当前主线）**：
   - `gluten-paimon/.../PaimonLocalFilesNode.java`、`PaimonScanTransformer.scala`、`PaimonSparkShim.scala`。

5. **可废弃 / 待确认（velox 侧残留）**：
   - `cpp/velox/jni/JniFileSystem.h`、`cpp/velox/substrait/VariantToVectorConverter.cc`、
     `cpp/velox/substrait/VariantToVectorConverter.h` —— 需确认是否随 core helper 抽取后变成可回退残留。
   - 构建脚本：`dev/gen-all-config-docs.sh`（已改 `${MVN_CMD}`）、`dev/gluten-build-info.sh`。
   - 其他：`README.md`、`pom.xml`、`package/pom.xml`。

### 10.5 结论

- 约 820 个文件（`backends-bolt` 663 + `cpp/bolt` ≈ 158）是 Bolt 专属纯新增，不构成公共代码冲突。
- 测试黄金资源（`backends-bolt/src/test` 下 336 个 `.txt` + 数十个 parquet/orc/csv）体量大但风险低。
- 真正需要持续收敛和 review 的是 **45 个 M 文件**，其中第 1、2 类是公共代码主战场，第 4 类移交 Paimon 专项，第 5 类需判断是否可回退。
