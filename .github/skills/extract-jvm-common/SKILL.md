---
name: extract-jvm-common
description: >-
  当用户需要把多个 backend（如 bolt / velox / clickhouse）中“逻辑完全一致或高度相似、仅 backend 符号名/常量不同”的 Java/Scala 代码抽取到公共模块 backends-core（或 gluten-substrait/gluten-core 等共享层），以消除重复与未来 rebase 冲突时，使用本技能。强调“以某基线 backend 为参照、仅忽略命名差异、零行为变更、公共层放抽象基类/通用实现、backend 留 thin 门面、一组一提交”。可针对指定文件、目录或模块。触发词：`/extract-jvm-common`。
argument-hint: "[ <file/dir/module ...> ]  例: backends-bolt/src-celeborn/.../CelebornColumnarShuffleWriterFactory.scala  或  backends-bolt/src-iceberg/  或  metrics"
user-invocable: true
---

# 抽取 JVM（Java/Scala）公共代码 Skill

把多个 backend（bolt / velox / clickhouse 等）里**逻辑一致或高度相似、仅 backend 符号名/常量不同**的
Java/Scala 代码，抽到公共层（优先 `backends-core`，其次 `gluten-substrait` / `gluten-core` / `gluten-arrow` 等已有共享模块），
backend 仅保留 thin 门面（常量、配置、本地特定逻辑）。**核心准则：零行为变更，以某基线 backend 为参照，能继承复用就不重写。**

**硬性原则：所有“公共抽取”任务，必须从 `/data00/home/liyang.127/oap/gluten.dev` 的 `fake_main` 分支新建独立 git worktree + 专属分支来完成；禁止直接在当前主仓 worktree、已有脏工作区、其它对象库 worktree，或任何非 `/data00/home/liyang.127/oap/gluten.dev` `fake_main` 派生的工作区上做公共抽取。**

## 适用范围（必须支持）

用户调用时可指定范围，本 Skill 必须遵守：
- **某几个文件**：只评估并抽取这些文件。
- **某些目录**：扫描目录下候选并逐个判定。
- **某个模块**（如 celeborn / iceberg / metrics / shuffle）：先把模块映射到各 backend 下的文件集合，再判定。
- **留空**：要求用户给范围或先给出候选清单确认，不要全仓盲扫硬抽。

开始前先复述理解的范围、参照基线 backend（如以 velox 为基线）、涉及的其他 backend、以及目标公共模块。

## 第 0 步 — 抽取口径（最重要，先卡死）

1. **选定基线 backend**：以最成熟/最完整的 backend（通常 velox）为参照，其他 backend 向它对齐。
2. **唯一允许忽略的差异**：backend 符号命名 —— `Bolt`/`Velox`/`CH`、包名前缀、`BoltConfig`/`VeloxConfig`、
   常量字符串键名、本地实现类名。**其余逻辑必须一致或可参数化才抽**。分档：
   - 第一档：规范化命名后 100% 一致 → 低风险，优先抽（如 Celeborn 工厂、TopNTransformer、序列化器抽象）。
   - 第二档：主体一致、仅少量后端特定常量/钩子不同 → 抽**抽象基类**到公共层，backend 子类只填差异部分。
   - 第三档：已实质分叉（核心转换链、plan 重写、内存管理）→ 放 backlog，**不抽**。
3. **先做“净代码行数收益”检查**：预估抽取后“新增共享代码 + backend 壳代码 + 配套构建改动”的总行数，是否**严格小于**抽取前各 backend 重复实现的总行数。
   - 若**不能减少整体代码行数**，或只是把重复代码平移成“共享基类 + 同样长的 thin 壳”而总行数不降，**则不抽取**。
   - 只有在减少重复、减少维护面、且代码总量净下降时，才进入后续抽取流程。
4. **依赖方向铁律**：`gluten-core/gluten-substrait → backends-core → 具体 backend`。
   公共层**绝不能**反向依赖任何具体 backend。抽取前确认被抽代码不引用 backend 私有符号；
   若引用，则把该符号也下沉或参数化（抽象方法 / 构造参数）。

## 第 1 步 — 规范化对比确认一致（考古先于动手）

对每组待抽文件，在各 backend 间做“去名称化” diff：
```
diff <(sed 's/Velox//g;s/Bolt//g;s/CH//g' A.scala) <(sed 's/Velox//g;s/Bolt//g;s/CH//g' B.scala)
```
- diff 为空 → 纯改名拷贝，第一档，直接抽。
- 仅少量差异且都是“后端特定常量/类名/钩子” → 第二档，抽象基类 + 子类填空。
- 有实质逻辑差异 → 退回 backlog，不抽，并记录原因。

**务必同时考古 old commit**：用 `git show <add-backend-commit> -- <file>` 确认该 backend 文件原始形态，
判断差异是“有意的后端特性”还是“当初照搬后上游演进造成的脱节”。后者优先按基线 backend 对齐而非保留。

## 第 2 步 — 致命前置检查：依赖与接口是否真实存在（避免返工）

抽之前，对每个候选确认它在公共层与各 backend 都真能编：
1. `grep` 候选依赖的关键符号（类 / trait / 方法 / 常量）在目标公共模块的依赖链里是否存在、签名是否一致。
2. 上游可能已演进：基线 backend 已适配新接口，待对齐 backend 可能仍是旧签名 —— 以**当前上游接口**为准。
3. **再次确认“净代码行数收益”没有被配套改动吃掉**：把共享类、backend 薄壳、pom/profile 变更一起算进去；若整体代码行数不降，停止抽取。
4. **native/JNI 关联**：若 JVM 代码背后有 native（JNI `native` 方法），确认该 backend 的 cpp 侧是否真支持。
   反例教训：bolt 的 iceberg native 在 `cpp/bolt/CMakeLists.txt` 被注释禁用、底层库无 iceberg connector，
   此时 JVM 可对齐接口签名（纯声明不影响编译），但**不要**强行启用 native；如实告知用户该限制。

## 第 3 步 — 选择抽取手法（按差异程度，从轻到重）

1. **整体移动**（第一档，逐字一致）：把文件整体搬到公共模块，删除各 backend 重复副本。
   适用无 backend 符号依赖的通用类（如某 util / visitor / proto / transformer）。
2. **抽象基类 + thin 子类**（第二档，主流手法）：
   - 公共层放 `abstract class` / `trait`，封装共享逻辑，把后端差异点声明为**抽象方法**或**构造参数**。
   - backend 留 `class XxxFactory extends SharedXxxFactory("backend-specific-const")` 这种壳，只传差异。
   - 注意：基类若已 `extends` 某接口，子类**不要**重复 `with` 同一接口（Scala 会报错）。
   - 注意：抽象基类构造器别加多余 `override val`（编译错误）。
3. **共享测试基类**：测试 mixin/基类抽到公共层，把后端特定探测（如 `isNativeWritePlan`）留抽象方法。
   方法名要与各 backend 原调用名一致，避免改调用点。

统一规则：
- 公共文件**优先落 `backends-core`**；若属更底层通用能力且 backends-core 也依赖它，则落 `gluten-substrait`/`gluten-arrow`/`gluten-core`。
- backend 侧**保留原接口名与调用链**，上层零感知。
- profile 相关代码（celeborn / iceberg / uniffle）抽到公共模块时，公共模块也要配套加对应 profile（`src-celeborn` / `src-iceberg` 源根 + 依赖）。

## 第 4 步 — 模块/构建配置（容易漏）

- 新建公共模块时：在顶层 `pom.xml` 的 `<modules>` 注册；模块 `pom.xml` 配好 parent、scala 编译、provided 依赖。
- 各 backend `pom.xml` 加对公共模块的 `compile` 依赖。
- profile 专属代码：公共模块与 backend 都要在对应 profile 下加源根与依赖（如 celeborn 的 shaded client、iceberg 的 runtime + protoc）。
- proto 抽到公共层后，**删除各 backend 重复的 `.proto`**，避免重复生成同名类冲突。

## 第 5 步 — 工作流纪律（老规矩）

- **一组一提交**：按功能分组（如 ①MetricsUpdater ②rewrite规则 ③execution glue ④JNI/datasource ⑤Iceberg ⑥Celeborn/Uniffle 工厂 ⑦测试基类），
  每组改完编译验证再提交，避免累积错误难定位。
- **独立 worktree（硬性）**：必须从 `/data00/home/liyang.127/oap/gluten.dev` 的 `fake_main` 新建 worktree + 专属分支成型 patch，主仓只做对齐；如果当前工作目录不是从这个**指定仓库路径**与**指定基线分支**拉出的新 worktree，先停下迁移，不能就地抽取。这样可以避免后续 `cherry-pick` / `append` 时遇到“不是同一个对象库”的问题。
- **范围克制**：用户只说抽 JVM 就不碰 native/其他；社区基线可能无私有 backend，私有 backend 的 thin 壳单独手做、不混进公共 patch。

## 第 6 步 — 验证闭环（关键：分清 .m2 旧 jar 干扰）

- **优先用 reactor 全量构建**（如 `make jar_spark35`，本质 `mvn -Pspark-3.5 package`），不要 install。
- **`.m2` 旧 jar 陷阱**：单模块 `mvn -pl X -o` 或 reactor 解析依赖时，可能命中 `~/.m2` 里过期的
  `gluten-*-SNAPSHOT.jar`，报“not a member / overrides nothing”等假错。判断方法：`javap` 查 target/classes
  含新符号但报错依旧 → 是 .m2 旧 jar 污染。处理：清理 `~/.m2/repository/org/apache/gluten/*/<version>` 后用 reactor 重编。
- **区分 main vs test 编译**：`scala-compile-first`（main）通过即核心目标达成；`scala-test-compile-first`（test）
  的报错若是 backend 测试与上游 test-jar 不匹配的预存问题（如缺 `checkGlutenOperatorMatch`），需向用户确认是否在范围内。
- 改完每组都编一次；style 失败用 `./dev/format-scala-code.sh` 或 `mvn spotless:apply` 修，别手抠空白。

## 第 7 步 — 甄别“预存问题” vs “本次引入”

编译报错时，先用 `git diff <add-backend-commit> -- <file> | wc -l` 判断该报错文件是否被本次改动触碰：
- diff=0 且报错符号与本次抽取无关 → **预存问题**（backend 与上游基线脱节），如实告知用户、不擅自扩大范围。
- 与本次抽取相关 → 按基线 backend 对齐修复。

## 第 8 步 — 状态留痕

把“抽了哪些 / 每组对应 commit / 暂缓哪些 / 为何暂缓 / native 限制”记入 `plan.md` 对应章节，便于跨 session 续作与回溯。

## 一句话提炼

**先从 `/data00/home/liyang.127/oap/gluten.dev` 的 `fake_main` 新建 worktree → 先算净代码行数收益（不能减少整体代码行数则不抽）→ 选基线 backend → 规范化 diff 分档（一致/可参数化/已分叉）→ 验证依赖方向(core 不依赖 backend)与接口/native 真实性 → 按差异从轻到重选手法（整体移动<抽象基类+thin子类）→ 公共落 backends-core、backend 留壳、配齐 pom/profile/删重复 proto → 一组一提交、reactor 构建验证(警惕 .m2 旧 jar)→ 甄别预存问题不越界 → 决策入档。** 风险与依赖判断永远先于代码技巧。
