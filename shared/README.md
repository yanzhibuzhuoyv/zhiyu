# 知语共享行为契约

`companion-contract.json` 是检索执行状态和带 ID 行为规则的单一来源。修改此文件后在项目根运行：

```powershell
python shared/generate_companion_contract.py
python shared/generate_companion_contract.py --check
```

生成的 Python/Kotlin 文件禁止手改；`--check` 只验证，不写文件，过期即失败。Python 测试验证生成内容与共享源逐条一致；Android 构建还应验证共享源 SHA。应用运行使用随代码发布的固定规则，不读取桌面 JSON、不执行用户上传规则。

该源文件在 `.gitattributes` 固定 LF，防止 Windows 自动转换换行导致源 SHA 与生成文件不一致。

Kotlin Android 是当前手机产品的唯一功能实现。Python 后端为 `legacy_frozen`，只修安全、兼容和契约正确性，不继续同步开发产品功能。两侧 SQLite schema、迁移及排名并未等价：手机是 BGE 语义主路径加 FTS4 词法补查；旧后端是 FTS5/BM25。历史后端检索 A/B 不能作为手机算法已通过的证明；不迁移或重建私人旧库来制造一致结果。

`ok` 指本次要求的有界路径完成，仍不是全历史穷尽或事实存在证明；`not_searched` 指未执行旧原文检索；`partial` 指索引、预算、路径或更正依据不完整。三态都不能推导“用户从未说过”。旧响应保留 `complete` 作为兼容投影，仅 `ok` 为 true；产品提示使用三态。

`retrieval_status` 块只放实际执行状态、已提供消息和来源数量。所有指令放 `behavior_rules`，每条有固定 `id` 与 `applies_to`。测试/离线评估可按 ID 独立关闭一条规则；生产 UI 和用户调教内容无权开关证据约束。规则重构不保证模型绝对服从，不改变独立原文、作者、时间、SHA 或固定人格。
