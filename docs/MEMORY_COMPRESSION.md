# 独立原文的无损压缩研究

2026-10-06。配合[三层记忆设计](MEMORY_LIFECYCLE.md)做离线容量评估，**尚未修改生产正文存储或启用冷记忆**。每条原文独立压缩、独立恢复，不合并消息、不做摘要。

## 数据与隔离

使用长字段耐久第三次运行d65留下的停止合成库：10,000轮、20,000消息、150,802原文片段，双方正文225,919,087 UTF-8字节。原万轮的保存/检索检查点通过、最终删除失败，原报告仍为failed；本轮研究是独立的passed报告。[原负载与结果](LONG_FIELDS_ENDURANCE.md)。

原测试卷只读挂载，先将主库、WAL、SHM复制到专用容器的/tmp，再对副本使用SQLite mode=ro和query_only。执行参数配置镜像9afa77f...、网络none、只读根目录、1GiB内存、2CPU；程序实读UID为10001。/tmp配置容量768MiB用于540MiB数据库副本，**不是旧耐久测试的128MiB临时空间条件**。没有调用云模型或读取用户实际聊天库。

实验结束容器自动移除，临时副本随之消失；原失败源卷保留，源三文件SHA前后相同。主库SHA `3abc5989d1024138ebf38aa0fa816e6a4240d74015377d1960757df05d8b45b0`，主文件566,444,032B、WAL0B、SHM32,768B。

## 正文载荷结果

每条使用zlib，难压缩或短内容保留原UTF-8字节。每条9字节实验格式头（版本、raw/zlib类型、原字节长度）已计入结果；无跨消息字典或内容去重。

| 方案 | 全部正文载荷 | MiB | 比原正文减少 | 编码累计秒 | 解码累计秒 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 原UTF-8 | 225,919,087B | 215.45 | — | — | — |
| zlib level 1 | 124,711,585B | 118.93 | 44.80% | 2.752 | 1.199 |
| zlib level 6 | 117,507,264B | 112.06 | 47.99% | 5.702 | 1.133 |
| zlib level 9 | 117,459,703B | 112.02 | 48.01% | 5.691 | 1.107 |

各方案19,976条压缩、24条原字节回退。level9相对level6仅再省47,561B；单次计时有抖动，不据此推断level9普遍更快或最佳。level6可作为后续兼容迁移原型的候选基线，还需要不同自然文本和多次测量。

累计时间只含逐条编解码，不含行/文件哈希、SQLite读取、复制、HTTP、模型调用或数据库重写。扫描/校验整体27.512秒也不含容器启动与复制，不是聊天响应延迟或线上吞吐指标。

## 无损证据与工具验证

三个方案全部20,000条正文UTF-8逐字节相等，完整行有序SHA（含ID、角色、时间、来源等）与原始行一致：`7913f0ed49071542d8eb4b83cff9361d68cbff4982d1c8384e7e317fad002e04`。全部150,802片段的字符边界有效、还原后原文一致。SQLite quick_check为ok，副本及源卷三个文件SHA前后不变。该检查验证偏移/文本等价，未执行压缩后的生产FTS查询、分页或冷召回；应用仍读取原TEXT。

工具测试覆盖空/短/长中文、emoji、组合字符、NUL、原字节回退、版本/长度/坏流/尾随数据/展开上限、非TEXT、错误偏移/孤儿片段、只读及报告禁止覆盖。新增31项在Windows Python3.13.5和Linux Python3.13.15均通过；包含原工具回归的完整scripts/tests两平台均95 passed，新工具覆盖率95.45%，ruff通过。不把此前275项生产测试当作本工具测试。Linux初次覆盖率输出目录只读而失败，设置COVERAGE_FILE=/tmp/.coverage后通过；原日志保留，最终95项运行无warning。研究frame本身没有内容校验和，等长合法UTF-8的损坏可能通过decode；本研究通过逐条原文比较发现差异，未来正式存储须验证元数据中的原文SHA。

## 结论与限制

该合成长负载有明显的正文载荷收益，因此可优先研究无损压缩，满足“原话都留着”。**47.99%是编码正文节省，不是540.20MiB主库的实测缩减**：FTS/片段约306.05MiB没有压缩，schema/页/空闲页、来源及编码元数据均要计入未来重写后的库。

合成文本含随机组合、重复结构、emoji与边界探针，不能代表自然长聊天、所有语言或高熵内容的压缩率。本轮没有减少活跃索引、删除原文、测试分类/晋升权重；完整原文持续写入仍会增长。

正式迁移先替换直接对TEXT做SQL substr/length的读取，支持解码后的Unicode偏移、旧TEXT兼容和安全损坏处理；再在专用副本验证全部历史/元数据/检索回归、实际主库/WAL/SHM/归档总空间与恢复时间。冷却另需权威active/cold状态及重启不回灌证明，见[方案](MEMORY_LIFECYCLE.md)。ZMC1是研究格式，16MiB研究展开上限不改变应用消息长度限制。

## 复核入口

- 工具：`scripts/eval_memory_compression.py`；测试：`scripts/tests/test_eval_memory_compression.py`。
- 本机报告：`artifacts/memory-compression-20261006.json`；执行日志：`artifacts/memory-compression-docker-20261006.log`。
- 复制驱动：`artifacts/run-memory-compression-20261006.py`；Windows单测日志：`artifacts/memory-compression-unit-windows-20261006.log`。
- 全工具回归：`artifacts/memory-research-tools-windows-20261006.log`、`artifacts/memory-research-tools-linux-20261006.log`。
- artifacts只在本机保留，不提交数据库、聊天正文或卷数据。

从项目根目录运行时，输入须是停止后制作的一致合成副本，输出须位于数据库目录之外且不存在：

```powershell
& backend/.venv/Scripts/python.exe scripts/eval_memory_compression.py --database '<synthetic-copy>/chat.sqlite3' --output '<report-directory>/new-report.json'
```

工具没有迁移、VACUUM或删除功能；本轮没有将该格式部署到后端。
