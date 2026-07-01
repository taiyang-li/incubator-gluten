---
name: extract-cpp-common
description: >-
  当用户需要把 cpp/ 下多个 backend（如 bolt / velox）中“逻辑完全一致、仅 backend 符号名不同”的原生代码抽取到公共目录 cpp/core，以消除未来 rebase 冲突时，使用本技能。强调“仅忽略符号命名差异、零行为变更、公共落 cpp/core、backend 留壳”。触发词：`/extract-cpp-common`。
argument-hint: "[ <file/dir/module ...> ]  例: cpp/bolt/substrait/VariantToVectorConverter.cc  或  cpp/bolt/jni/  或  jni"
user-invocable: true
---

# 抽取 cpp/ 公共代码 Skill

把 `cpp/` 下多个 backend 里**逐字一致、仅 backend 符号名不同**的代码，抽到公共目录 `cpp/core`，
让未来各 backend rebase 不再在这些文件上冲突。**核心准则：零行为变更，能逐字搬运就不重写。**

## 适用范围（必须支持）

用户调用时可以指定代码范围，本 Skill 必须遵守：
- **某几个文件**：只评估并抽取这些文件。
- **某些目录**：扫描目录下候选并逐个判定。
- **某个模块**：先把模块映射到 `cpp/<backend>/<...>` 的文件集合，再判定。
- **留空**：要求用户给范围或先给出候选清单确认，不要全仓盲扫硬抽。

开始前先复述理解的范围与涉及的 backend（如 bolt + velox）。

## 第 0 步 — 抽取口径（最重要，先卡死）

唯一允许忽略的差异：**backend 符号命名**，例如
`Bolt`/`Velox`、`bytedance::bolt`/`facebook::velox`、`BOLT_*`/`VELOX_*` 宏、backend 头路径。
**其余逻辑必须逐字一致才抽**。分档：
- 第一档：100% 一致仅符号名不同 → 低风险，本 Skill 主战场。
- “像但已分叉”（主 plan converter / memory manager / shuffle runtime / 主转换链）→ 放 backlog，**不抽**。

## 第 1 步 — 致命前置检查：依赖是否真实存在（避免返工）

抽之前，对每个候选、每个 backend 都确认它真的能编：
1. `grep` 候选里依赖的关键符号（类型 / 命名空间 / 宏）在该 backend 的头或 conan 包里是否存在。
2. 看该 backend 的 `CMakeLists.txt`：该源文件是否被注释 / 不参与编译。
3. 看上层引用（如 `*PlanConverter` / `WholeStageResultIterator`）对它的 include 是否也是注释。

反例教训：`IcebergPlanConverter` 在 bolt 侧依赖的 iceberg connector 在 bolt conan 包里根本不存在，
文件本就被注释、从不编译——这类**某 backend 缺依赖**的文件不要纳入该 backend 的抽取，
应只在有依赖的 backend + core 抽，或直接删除该 backend 的死文件（先确认引用全是注释再删）。

## 第 2 步 — 逐字对比确认一致

对每对 backend 文件做 diff，把已知的 backend 符号差异归一后，确认剩余**逐字一致**。
有任何实质逻辑差异 → 退回 backlog，不抽。

## 第 3 步 — 选择抽取手法（按依赖深度，从轻到重）

1. **纯声明 / 无 backend 依赖** → 直接下沉到 `cpp/core/...` 头，backend 头 `#include` 它。最干净。
   （例：`registerJolFileSystem(uint64_t)` 这种无类型依赖声明，core 头 `#include <cstdint>` 即自洽。）
2. **实现依赖 backend 类型（靠 `using namespace` 才可见）** → 公共实现放 `cpp/core`，
   backend 文件先 `using namespace bytedance::bolt;`（或 velox）再 `#include` core 头/实现。
3. **实现还依赖 backend 宏** → 用 `GLUTEN_*` 占位宏参数化：
   - core `.cc` 顶部 `#ifndef GLUTEN_x ... #error` 强制约定；用 `GLUTEN_x` 写逻辑。
   - backend `.cc`：先 include backend 头 → `#define GLUTEN_x BOLT_x` → `#include "../../core/.../X.cc"` → `#undef`。
   - 说明：`#include .cc` 是反模式，仅在“逐字搬运比重写更安全”时采用，并要能讲清替代方案
     （core 头里重写一份不依赖 backend 宏的实现），由用户取舍。

统一规则：
- 公共文件**一律落 `cpp/core`**（不要新建别的公共目录）。
- backend 侧**保留原接口名与调用链**（如 `initBoltJniFileSystem` / `using VeloxDataSource = GlutenDataSource`），上层零感知。
- 若公共 `.cc` 是被 backend `.cc` 文本复用的，则**不需要**改 `cpp/core/CMakeLists.txt`（编译入口仍由各 backend target 持有）；
  只有当 core 真正独立编译某 `.cc` 时才往 core target 加 source。

## 第 4 步 — 工作流纪律（老规矩）

- **公共抽取必走独立 worktree**：基于干净基线分支（如 `gluten.dev` 的 `fake_main`）新建 worktree + 专属分支，
  在那里成型 patch；主仓只做对齐。先确认基线分支与 worktree 命名后再建。
- **认清基线差异**：社区基线可能没有私有 backend（如 bolt 私有）。社区 patch 通常只含 `core + <公开 backend>`；
  私有 backend 的薄包装在主仓单独手做，**不要混进公共 patch**。
- **范围克制**：用户说只动 cpp 就不碰其他；回退要干净（`git rm` 死文件 + `git checkout` 还原 + 删空目录）。

## 第 5 步 — 验证闭环

- **先窄后宽**：用 `compile_commands.json` 抠出受影响文件的真实编译命令做增量编译定位问题，
  再按需 `make release` / 全量构建收口。
- 改了头文件要 `touch` 强制重编，避免 “no work to do” 假通过。
- 提交前 `git diff --check` 查空白；commit message 讲清“为什么”（消除 rebase 冲突 / 零行为变更）并带 `Generated-by`。

## 第 6 步 — 状态留痕

把“抽了哪些 / 暂缓哪些 / 为何暂缓 / 缺依赖证据路径”记入 `plan.md` 对应章节，便于跨 session 续作与回溯。

## 一句话提炼

**先验证依赖再动手 → 逐字确认仅符号差 → 按依赖深度选最轻手法（声明<类型<宏注入）→ 公共落 cpp/core、backend 留壳 → 独立 worktree 出 patch → 增量编译打底全量收口 → 决策入档。** 风险判断永远先于代码技巧。
