# 原生检索执行契约与规则来源

日期：2026-10-10。本文只说明检索执行状态、共享行为规则及其验证；不代表生图、角色编辑、通话或整个安装包全部完成。

## 产品实现与旧后端的边界

Kotlin Android 是当前手机产品的唯一功能实现，手机不依赖电脑后端。Python 后端明确为 `legacy_frozen`，只接受安全、兼容和契约正确性修复，不继续复制手机新增功能。

两侧存储和检索没有被强行合并：Android 为 BGE-small-zh-v1.5 INT8 语义主路径和 FTS4 补查，旧 Python 为 FTS5/BM25。schema、迁移、排名和模型并不等价。旧后端的48题 A/B、千/万轮增长实验不能证明手机有相同召回结果。原生自然语言召回质量需要独立冻结语料和设备实测；本轮的小维度受控向量只验证状态传播和独立原文边界。

没有迁移两个数据库的 schema 或重建私人索引来制造等价结果；没有融合、改写或删除独立原文。历史50M Unicode测试的约20秒大库检索问题仍独立存在，本次契约重构不宣称修复它。

## 单一来源与生成检查

源文件为 [shared/companion-contract.json](../shared/companion-contract.json)，含实现生命周期、状态定义，以及14条带固定 `id`、`applies_to` 的规则。生成器只使用 Python 标准库：

```powershell
python shared/generate_companion_contract.py
python shared/generate_companion_contract.py --check
```

生成文件为 `backend/app/companion_contract_generated.py` 与 `android/app/src/main/java/com/zhiyu/app/domain/CompanionContractGenerated.kt`，禁止手改。`--check` 是只读检查，源/生成文件不一致时失败；测试还检查逐条规则、作用范围及源 SHA 一致。当前 SHA-256 为 `64d88ba563de64a74552fc1dc9d247d1633f97de975600452add8b92ee824c11`。Android 的构建门禁另由主集成流程校验共享源，本文不把一次脚本通过等同于全部 Gradle 检查通过。

应用使用随包发布的固定生成代码，不读取用户提供的规则文件、不执行用户上传规则。规则按范围选择，重复范围不会重复添加同一 ID。`disabled_rule_ids` / `disabledRuleIds` 仅供单规则离线评估，不在生产 UI 或自行调教里开放证据约束开关。

## 三态和模型上下文

| 状态 | 执行含义 | 不能推断 |
| --- | --- | --- |
| `not_searched` | 没有执行旧原文检索，例如空查询、纯标点、无旧历史范围 | 用户未说过、旧事实不存在 |
| `ok` | 本次请求的有界检索路径已完成，可返回零条或多条来源 | 已穷尽全部历史、检索结果就是完整事实 |
| `partial` | 已执行部分路径，但模型不可用、索引未完、读取预算不足、编码失败或更正依据未完整提供 | 旧内容不存在、旧事实可继续当现行事实 |

Python 的 `MemoryContext` 默认 `not_searched`；未生成可用词项时不执行 lookup。真实完成但没有候选为 `ok`；未完成而没有候选为 `partial`。`complete` 仅保留为旧脚本/API的兼容投影：只有 `ok` 为 true。API `memory_search` 明确返回 `status`、兼容 `complete` 和 `next_cursor`。传入矛盾的旧布尔值与新状态时显式拒绝。

Android 的 `LocalSemanticRetrieval.status` 使用同一状态枚举；高置信语义来源且索引/更正依据完整时为 `ok`。无模型、索引待完成、超长编码、编码失败和未完整提供的更正路径为 `partial`。低分后的补查只有完整完成且无悬空更正时为 `ok`。这些状态不改变逐条原文、作者、时间、SHA、排名或人格绑定。

模型上下文分成两个块：

```text
<behavior_rules>
[absence_is_not_evidence] ...行为约束...
[natural_voice] ...自然口吻...
</behavior_rules>
<retrieval_status>
{"state":"partial","history_messages":24,"earlier_source_count":0}
</retrieval_status>
```

`retrieval_status` 只含本次实际状态和数量，不再把 `absence_proves_never_said: false` 等指令伪装成事实。三个状态始终应用“未找到不能说成你从未讲过”的规则；缺乏明确依据时自然表达自己的回想感受，不念数据库或检索过程。规则并不能保证模型绝对服从，真实模型的回答准确率与本轮确定性测试分开评价。

## 本轮证据

| 验证 | 结果与日志 | 覆盖范围 |
| --- | --- | --- |
| Python业务 RED | 11项失败，`artifacts/retrieval-contract-v4-python-red-r3.log` | 旧默认bool、未检索/空结果混淆、新状态/规则接口缺失 |
| Kotlin业务 RED | 1项断言失败，`artifacts/retrieval-contract-v4-kotlin-red-test.log` | 旧提示缺实际执行状态 |
| Python目标 GREEN | 37项通过，`artifacts/retrieval-contract-v4-python-green-r1.log` | 新状态、规则来源及旧证据/口吻测试 |
| Python扩大回归 | **176/176通过，36.88秒**，`artifacts/retrieval-contract-v4-python-regression-r4.log` | 旧事/事件、双方原话、冷归档、更正、API、人格和共享生成 |
| Kotlin纯逻辑 GREEN | **23/23通过**，`artifacts/retrieval-contract-v4-kotlin-green-r1-test.log` | 新规则7项与既有记忆/语义规则 |
| 原生冻结契约 | 主集成记录 **LocalRetrievalContractTest 7/7通过** | SQLite隔离合成原文、受控向量、三态传播、单规则关闭、作者/时间/SHA |

最初Python两次 RED 因缺 tzdata/Pillow 未收集测试，不计业务失败；之后按既有 `requirements.lock` 补齐 Pillow12.3.0/tzdata2026.5。扩大回归只有一条现有 Starlette/httpx 弃用警告。第一次扩大回归173通过、3项旧响应/旧文案断言失败；更新三态契约并保留原规则语义后，最终176通过。不把中断的原生批次计为全部通过。

Python业务RED第一次导入 `app.main` 前未设置独立数据目录，顶层 `create_app()` 可能打开默认库并走初始化/索引回填路径。之后测试均提前设置独立 `artifacts/retrieval-contract-v4-bootstrap-r2`。对此只做文件元数据及 `mode=ro&immutable=1` schema取证，未读聊天/Key行：8个默认SQLite最后写入时间均为10月8日或更早，无WAL/SHM，取证本身未改变文件大小/时间戳。没有运行前哈希，因此不能断言默认库字节严格未变，也没有猜测还原数据；详见 `artifacts/retrieval-contract-v4-default-init-metadata.json` 与 `artifacts/retrieval-contract-v4-default-init-schema-metadata.json`。

本节检索契约测试未收费调用模型、未读取私人Key、未重新运行万轮或五千万字长测。所有原生结果以隔离模拟器为准，不能代替用户红米实测；整包、签名、权限恢复和最终安装包结论由主集成报告给出。
