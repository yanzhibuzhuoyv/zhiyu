# 实际验证记录

## 2026-10-07 11:04：本地旧事语义路由三轮研究

研究“当下足够 / 需要旧事 / 不确定”，没有新增同步云意图调用。现有BGE离线CPU模型先试最大原型相似度，再试三邻居一致，最后固定L2监督意图头；三套共380条唯一原创合成正文，gold由独立代理预先编写。train拟合、validation择阈值、profile冻结后才编码各轮新test，Q须独立通过、C只能否决、实际完整token超512/模型失败保留检索。全部是实验模块，backend/app、Android、数据库和运行镜像未改。[方法、原始目录及边界](MEMORY_ROUTE_EVAL.md)。

首轮25保留题：重要误跳1/15、日常正确跳过3/10，1,000展示变体40误跳均来自该错误底题，gate=false。第二轮新50题：重要0/30、日常0/20，2,000变体全检索，gate=false。两失败报告原样保留，不能以“无错但全检索”宣称优化成功。

第三轮仅用V2 train100拟合固定C1/L2/balanced头，新validation40/test40先冻结。450阈值仅用新validation选择，完整系数/截距/映射与nearest0.50、未校准affinity严格大于0.61保存后才测新test。新40题重要误跳 **0/24**、日常正确跳过 **5/16（31.25%）**，总请求正确跳过5/40（12.5%）；热p50/p95 **10.598/25.062ms**。1,600展示变体重要误跳0/960、日常200/640；48次超长执行（8组输入，含长Q/长C与近16k汉字）全input_limit。预定gate=true只表示进一步受控研究资格，未启用生产自动跳过。

固定第三轮profile逐字节重放前两轮75已知保留题，重要误跳0/45、日常9/30，修表店问题现保留检索；没有重拟合/调参，不合入新40题泛化分母。profile SHA `d0bc19180783fbfadd80cb85301298d6edb28eca3a0ae122af07f6469877ab90`，独审全部逐题结果、450/756选择、源/模型SHA一致，无未解决C/H。跨批同训练文本的14向量最多9.42e-8浮点差，不改模型或gold；审查者首次过严bit断言失败原log保留，校正为文本同源与数值近似后通过。

最终Windows与无网络、只读Docker Linux全部脚本各 **289 passed / 实验代码97.72%覆盖**（701语句 / 16缺口），日志 `artifacts/memory-route-final-windows-scripts-r2.log` 和 `-linux-scripts-r2.log`。新109契约/数据测试与独审113（加4自有）重叠不相加；三近邻5RED、拟合4RED、冻结策略加载8RED为有效失败，修后通过。新增代码项目Ruff/diff通过；全目录既有静态规则差异日志保留，旧冻结脚本未改，不宣称全部静态检查绿。两次早期错误import和原284回归日志保留，最终追加5输入完整性边界后重跑289。没有读取Key、私人聊天库或调用云，依赖隔离 `backend/.venv-route`；真实BGE仅Windows实测，Linux是离线契约测试。

本轮未重跑已证752后端、Android65/19、旧千轮/两万条或180付费样本。应用仍后台记忆版本2ab镜像；路由尚未进入聊天主路径、未测自然长期拟人度/真人gold/并发资源或整链路净提速。分类耗时可能比本地FTS更高，不能从skip比例推算聊天加速。下一步受控观察连续对话与旧事噪声，再判断接入策略。

## 2026-10-07 10:10：旧事适时呈现与后台记忆第一版

聊天先回应当下分享与情绪，仅在有帮助时带出独立旧原话；近期已提供的来源附提示，仍保留原文和证据。双方原文、索引、pending状态和后台任务原子保存后，HTTP即可返回；语义识别随后在后台完成。没有增加同步云意图分类调用，没有融合或删改原文，固定AI与preset规则未变。[行为与边界](MEMORY_ATTENTION.md)。

最终Windows / Docker Linux各 **752 passed，覆盖率95.02%**（3412语句、170未覆盖），各一项现有Starlette依赖警告；日志为 `artifacts/memory-background-final-windows-r2.log` 与 `-final-linux.log`。全部脚本回归180项通过，13.04秒，未重跑旧千轮、两万条或180次冻结付费实验。首次全后端19失败/728通过均为旧冷归档夹具缺少生产已有的recognition表；补齐夹具并新增缺表安全拒绝反例后通过，没有放松生产保护。

有效TDD包含后台pending映射、受阻识别时第二次聊天与原文保存、owner FIFO、凭据失效/重连、lease和源SHA保护、识别失败与双方标注故障回滚、未认证重启不发云请求、pending原文对不能冷却、近期来源提示隔离/删除与保存失败，以及semantic-v2对任务指令的边界。独立审查最终118项专项通过，无未解决CRITICAL/HIGH/P2；与全量结果重叠，不相加。三个已复现P2（release故障后恢复缓存、旧token清除新token、lifespan异常跳过provider关闭）已用四个真实断言失败记录修复并独立复测。证据见本机 `artifacts/background-review-20261007/README.md`。

新镜像 `sha256:2ab853fb3244d3ceb7f604d9a7cdfbb09cd2df343215d79f7846454712c071ad` 已在原数据卷部署，healthy；45个应用Python文件SHA、UID10001、只读根、cap_drop ALL和回环端口均核验。升级前完整三文件和身份备份SHA一致，旧卷/备份保留。[部署与socket恢复](DOCKER.md)。54次真实Docker HTTP请求全部符合预期；14,827字符合成用户原文、双方四条完整消息和分页/重新连接后元数据不变，两次发送22.958 / 11.619毫秒均先返回pending，demo后台最终unavailable且无语义标签。preset固定、永久保护、冷查原文区间/恢复、stale409、跨身份404、超限422无写、注销401及自身数据清理通过。首harness长度断言错误在发送前失败，原结果保留，修后重跑通过；未改应用。demo后台过快，公开metadata未观测pending/running竞争，该竞争以blocking-provider专项证据为准；演示耗时不等于真实模型延迟。

隔离完整FastAPI ASGI应用使用已授权Key和官方HTTPS，新合成5轮共 **11次调用**（连接1、聊天5、后台5，无重试）；每次HTTP都先返回pending，随后任务各attempt1/ok。含实际本地旧原文检索的完整回复0.510–1.437秒，中位1.263秒，回复后后台整理0.436–0.879秒、中位0.620秒。64条双方消息与保存时完整字典逐条一致；只读SQLite quick_check、10条job源SHA和1条标签区间SHA通过，源码与原Key配置SHA前后不变。结果只证明这个生产路径小样本；`ok`为格式/来源/事务通过，不是100%语义准确率或长期自然度结论。时间不含Android绘制/跨设备网络，不能与不同题目、模式和时段的旧串行报告计算加速比。[新样本与限制](MEMORY_ATTENTION_EVAL.md)。

Android **65项unit通过**，debug/debugAndroidTest构建通过。重新验证SDK、adb和服务后，API36模拟器连接新镜像8791的 **19项instrumentation首轮全绿，15.128秒**：14映射、3后台状态Compose UI、2实际记忆管理流程。新APK及测试APK SHA与构建时一致；旧28项属于历史阶段，不重复计入。[APK与实际联测](ANDROID.md)。本轮未做真机、公网HTTPS/签名发布、独立语义意图路由或全双工语音；当前自然呈现依赖同一次聊天模型的选择，还需更广泛自然对话评测。

## 2026-10-06 22:41–23:36：DeepSeek Flash 真实模式与完整等待

用户授权读取原RAG配置中的Key，仅向官方HTTPS发送合成输入。`deepseek-flash`思考开启（high）与关闭，两轮各12语义+4回复×2次×2模式，128/128 HTTP200。首轮4096预算、第二轮16384；原Key文件与各冻结源码SHA均不变，无重试或事后改题。思考内容不落盘。独立调用前审查无未解决C/H；四个执行器合计57离线测试/92%覆盖、Ruff通过。[完整方法、结果与定性盲评](DEEPSEEK_FLASH_EVAL.md)。

首轮语义完整断言关闭13/24、开启16/24；开启6次4096token全部用于思考、最终答案空。第二轮放宽预算后64个最终响应完整、0截断；语义完整断言关闭16/24、开启21/24，耗时中位1.02/13.09秒、思考最大34.16秒。两种模式仍有错误历法、保存意图或引用位置问题，不能把固定断言当真实准确率。首轮8对Codex盲评、生日字面误报与明确日期/档案范围问题单列，不是人类用户满意度研究。

另官方GET模型信息确认393216输出上限。24新合成流程/48真实SSE请求，全HTTP200/DONE/stop；无总时长/read截止，完整收到答案。聊天/记忆关关、开开、关开各8流程，发送至保存后返回中位 **2.47/32.23/24.63秒**，最大6.73/53.02/46.29秒。24流程原文SHA/作者全部核验；19/24识别严格格式来源通过，5识别失败仍完整保存双方原话；不是该生成内容的准确率评分。源/key SHA同，独立SQLite48消息quick_check=ok。等待重放页在8797本机；它不产生云调用。

另两组真实6000–8000汉字创作长输出，各聊天+识别一次，共4请求HTTP200/DONE/stop。关闭生成6485汉字，完整返回34.05秒；开启8209汉字（超过要求上限209字），完整返回93.01秒。双方原文及标签区间SHA均通过，SQLite只读quick_check=ok；开启7条创作任务wish/preference标签均为mid，无永久升级，语义含义与是否值得跨轮复用尚无预注册gold。没有补齐、重试或把格式成功称为分类准确。四组共180次生成调用，另有模型信息查询。

本轮仅测试与文档，未改应用、默认模式或Android；没有重跑已完成718/千轮/两万条，真实模型小样本不冒充自然万轮或实际检索召回测试。用户关于日常分享的路由／后台记忆问题已记录为待实现方案，当前串行等待仍存在。

## 2026-10-06晚间：语义识别、三层加权与可逆冷归档

最终Windows/Docker Linux后端各 **718 passed，覆盖率95.18%**；日志`artifacts/memory-correction-final-windows.log`与`-linux.log`。各一项现有Starlette依赖弃用警告，无失败。此前各714/95.17%的冻结日志保留。工具两平台各123通过，工具与负载脚本SHA在最终更正修复后不变；Ruff、diff检查通过。

反例覆盖所选provider/model/Key的一次额外语义调用、多种表达、严格JSON/作者/日期/唯一原文区间、独立事件/复制去重、承诺未兑现、故障至提交全回滚、安全503、冷状态不回灌、活跃/冷同时召回及保护晋级恢复。语义/API测试使用可控provider，未用真实云模型；原文SHA校验不证明模型理解正确。[行为与限制](MEMORY_TIERS.md)。

Android **63项unit通过**，debug/debugAndroidTest/release R8构建通过；实际API36模拟器 **28项instrumentation首轮通过**，含映射13、记忆UI2、旧UI5、Keystore8。21:48连接冻结镜像`0eb64f...`的8791，demo识别显示不可用，原话/层级/归档恢复/重启和旧绑定流程通过。最终后端仅修更正查找，API与APK未再改，未把这28项称为最终镜像重新跑过。[Android证据](ANDROID.md)。

同`0eb64f...`冻结镜像从空库新写千轮：1,000轮/2,000消息、1,051 HTTP/零意外失败/零云、6原文探针、重建前后20页全部签名及14,979片段覆盖、隔离与最终清理通过。harness未启用自动语义标注，不是千轮付费分类准确率或语义调用延迟测试。

两万条长原文副本完整规范签名与源文件SHA不变，157批正文压缩、157批冷归档通过；20,000冷目录、150,802片段变0、启动不回灌。早期1/250轮两个探针首次未找到，显式三页续查后找到，诚实返回`complete=false`。恢复两条后及只读整理副本的六探针通过。原文载荷225,919,087→118,166,256B；工作main仍540.20MiB，另整理输出224.29MiB（19,998冷+2活跃）。这是合成几乎全冷场景，不能当真实节省保证。[容量、负载与分页边界](MEMORY_COLD_PROOF.md)。临时COPY卷精确归属核验后移除，旧失败源卷与备份保留。

独审发现的WAL/SHM硬链接边界、更正片段被已有head候选覆盖、跨topic错误锚点均修复；后两项四个有效TDD在旧实现全红，修后专项88通过，已纳入718。最初两份夹具配置错误日志保留，不当功能TDD证据。独立补审237及88项通过，两批重叠不相加，无未解决CRITICAL/HIGH。最终只更新`memory_annotations.py`、`memory_lookup.py`并新增`memory_corrections.py`；其余正文/索引/冷目录/负载helper与大实测SHA相同，见`artifacts/memory-final-code-hashes.json`。未重复整千轮/两万条或改旧万轮failed状态。

最终运行镜像`sha256:1c69b81f21422a094bea093813f3597736a7e1a4ce0f5a22a362848f696b1495`；测试镜像`sha256:ea1d578de2f4ff834f3bc755ca788fe8bfa15e57198baf2be2a1328ded697ac0`。22:06升级前应用卷和身份文件一致备份`zhiyu-memory-predeploy-5bf379457ca149df9e6373dbdb92a2d4`，原卷不换。最终真实HTTP5780字符原文、demo识别不可用、永久保护、冷查/恢复、stale409与完整历史元数据不变通过，自己的会话删除及注销通过。首轮harness误用内部`turns`字段和不存在的logout路由失败日志保留，改为实际公开API后通过，没有因此改应用。[当前运行入口](DOCKER.md)。

截至本实现阶段，真实云Key、模型分类准确率、任意同义查询召回/权重效果、真机、公网HTTPS及发布签名尚未验证；之后DeepSeek Flash合成小样本实测见本页最新记录，不能推广为真实用户准确率或同义召回。自动标注可关闭；没有自动生产批量冷却或原话删除功能。

## 2026-10-06：正式正文无损存储与兼容迁移

正文实测冻结阶段Windows/Linux各478 passed/95.28%、工具各101 passed。最终审查修复验证CLI失败路径写入和混合来源重复计数后，两平台全后端各481 passed/95.29%，包含codec56、存储集成32、实验分层118项；工具各106 passed，Ruff通过，独立专项129 passed。正文读取兼容旧TEXT与新版本BLOB，损坏安全503；逐条作者/ID/时间/来源和Unicode片段偏移不变。首次集成在旧实现15红/2绿，最终32项覆盖混存、故障回滚和迁移预算；根目录测试启动空数据留在ignore中，没有提交身份文件。最终Linux首次漏挂scripts的collection失败日志保留，补只读挂载后全量通过。

固定正式镜像的独立20k原文副本迁移通过：157批、19,976压缩/24TEXT，前后全部HTTP历史签名及150,802片段覆盖一致，早期双方中尾原文召回、应用重建、单批故障完整回滚和备份恢复通过，源文件SHA不变。原文225,919,087→118,166,256B，减少47.70%；工作文件未立即缩小，整理输出副本540.20→435.20MiB（含短探针增量）。没有迁移原失败源卷或启用自动冷索引。

另从空库1,000长字段轮次/2,000消息完整端到端passed，1,051次HTTP零意外失败、云调用0、6原文探针及前后20页全文核对、最终清理成功，driver0/脚本SHA不变/专用卷已移除。正式新镜像未重新执行从零万轮，旧failed保持原样。当前本机8791已部署新后端healthy，升级前完整卷备份保留。详细方法、报告位置与边界见[正文存储](BODY_STORAGE.md)。三层策略仅验证已标注输入，未测自然语言识别准确率，也未接入生产排序。

## 2026-10-06：双方长字段万轮及大库删除修复

实际从零完成10,000轮/20,000消息，千轮与万轮检查点12项原文探针、全部分页哈希/作者/时间/出处、150,802片段覆盖与同Key实例重建恢复通过。长字段主库566,444,032B（540.20MiB），原文225,919,087B、索引320,917,504B；不是首版短回复40.46MiB负载。最终删除失败，**该万轮report仍failed、1次意外HTTP失败、driver exit1**，专用卷保留诊断。

删除修复后Windows与Linux各 **275 passed、94.55%覆盖率**，Ruff通过。旧实现600消息回归3失败/1通过，新实现2200消息4通过；中后段故障完整回滚、安全503、重试204、身份隔离及health并发均覆盖。新生产镜像在原万轮库的独立副本上通过真实HTTP故障→回滚→重试，两万条删除约98.70秒，其他会话保留，FTS integrity/quick_check与源文件哈希核验通过。这是删除修复独立证据，不把旧failed改称完整万轮成功。

测试工具Windows/Linux64项、PowerShell卷归属9项回归通过；224MiB原文/128MiB临时目录的真实SQLite统计反例通过。新镜像千轮端到端已通过：1000轮/2000消息、6探针、重建前后全20页哈希、14979片段覆盖与身份隔离通过，1051次HTTP零意外失败；清理回到0消息/0索引、driver exit0、脚本hash稳定且专用卷移除。详细负载、限制、增长与各次独立失败见[长字段耐久报告](LONG_FIELDS_ENDURANCE.md)。Android没有改动，以下39单元＋8存储＋5UI与APK证据继续有效。

原8791后端本次恢复命令受到自动审批拦截，仅返回 `blocked by policy`；仍离线，不能把历史healthy当作当前状态。新测试均使用独立容器和数据卷。

## 0.1.1首次验证：AI恋人单页、长期记忆与体积优化

2026-10-05（北京时间）的历史验证；2026-10-06删除修复与最新回归以页首记录为准，后文保留首版历史。

| 验证 | 实际结果 |
| --- | --- |
| 后端Windows与Linux容器 | 最新逐消息FTS/加权原文版 **271 passed，覆盖率94.60%**；memory_index/conversation_memory/source_codec/companion/schemas100%，ranking98%，ruff通过 |
| Docker实际构建与运行 | 新0.1.1镜像构建、UID10001/只读root/隔离卷、健康检查、TXT/MD/PDF/DOCX/413/身份隔离、restart和force-recreate后原Key恢复均通过 |
| 长聊真实HTTP | 60轮日常后检索到最初纪念日原文；关闭文档RAG仍有memory_refs；验证的是检索与传递，无真实云AI回复正确率结论 |
| 真实10,000轮 | **20,000消息、16/16原文探针、20/200页全部哈希核对通过**，10,247次HTTP/0意外失败，同Key恢复/隔离/最新修订/长文中尾/多原文同次召回均通过。全量p50=303.711ms/p95=358.640ms，总约50分钟；演示云调用0 |
| 万轮空间与内存 | 主库42,430,464B（40.46MiB），聊天索引18,010,112B（17.18MiB），RSS97,956kB；只清理自身3会话后恢复基线6会话8消息/8片段，空闲页可复用。见LONG_CHAT_TEST.md，不能推成真实AI长回复的同等体积 |
| 双方长字段Docker HTTP | **36条user16k、32条AI16k＋4条AI64k**；6/6中尾原文探针、134条分页/重启全hash、16001→422无写与2162片段全覆盖通过。原文3.15MiB/索引4.68MiB/临时主库8.87MiB；合成回复非真实云生成，见LONG_FIELDS_TEST.md |
| 测试工具回归 | 新增16长字段反例，连同30压力工具回归，Windows与LinuxDocker均46 passed；所有项目scripts按后端ruff配置通过。新增Docker长字段helper单独真实exit0；原完整驱动记录仍单独保留 |
| 权重试验 | 六组权重、16合成场景/24期望命中；默认和另一组均全通过，不称最优。通用共享时间词在音乐/心情、海边/流星、食物/感冒中保持多条原文、不融合；关联未确认 |
| 历史分页真实HTTP | 124消息按100+24读取，原顺序、无重复缺失、跨Key404；客户端100条窗口单元回归通过 |
| 恋人设定真实HTTP | 首创preset固定，改性格/名字/模式/关闭409；custom可调整、自调→preset拒绝；同Key重新验证恢复档案 |
| 无损来源 | API与历史引用一致、删除原文档后当时引用仍可读；旧TEXT/新BLOB/重启与损坏边界回归通过 |
| 合成库体积 | 加入聊天FTS之前来源压缩阶段：160轮每轮5段重复资料2,195,456B→147,456B，原消息相同。只说明该重复引用场景，不代表新版一般聊天整体比例 |
| Android单元 | **39 passed**：输入边界、身份/人格固定、自调最新档案重开恢复、旧连接升级、单聊天与100条分页等 |
| Android构建 | debug、debugAndroidTest、release R8实际编译通过；release未配置公网地址、未签名发布 |
| 真实API36模拟器→Docker8791 | **8项Keystore通过＋5项UI通过**：首次取消不绑定/成功确认、单页引用与资料弹窗、退出重验保留公司/model、preset只读、自调修改/取消及退出Activity重建后保持新内容 |
| 独立审查 | 两个Android HIGH已复现修复；后端审查无CRITICAL/HIGH，文档边界小修3回归通过；见REVIEW.md |

首轮instrumentation共13项，8个存储和4个UI通过，唯一失败为测试将密码框圆点与原Key比较；语义InputText实际为key-draft。修为主动显示测试Key后断言，重编测试APK，受影响UI套件5项全部绿色；没有忽略失败或把adb退出码0当成JUnit通过。

证据：`artifacts/docker-unit-tests.log`、`docker-validation.json`、`companion-validation.json`、`companion-instrumentation.log`（首轮真实失败保留）、`companion-ui-final.log`（5项最终绿色）。`scripts/Test-Docker.ps1`已纳入新长聊烟测；`smoke_companion.py`只使用UUID演示身份，明确不继承环境代理，清理自身测试文档和会话，无Key/token写进报告。

交付APK：`artifacts/ZhiYu-0.1.1-debug.apk`，18,663,244B，SHA256 `992E9F1EF4F9F918250DDBAC80AA55EB636225EA2A10C285FADB818506D7217F`。默认是模拟器本地Docker地址，普通页面无需填后端；真机或发布需构建配置有效服务。

恢复10:05时Docker再次在启动前报临时socket error1920，正常start/stop失败；保留并重建仅0字节socket目录后10:11引擎及新版8791恢复，再通过长聊真实HTTP。未reset/prune/删除其他数据卷，临时恢复不代表Docker根因已永久解决。当前在线状态下次仍须重验。

本轮没有真实云Key、真机、公网HTTPS、正式签名或容器BGE验证。绑定只适用于本次安装，清数据/卸载/换设备不保留；Key变化不自动迁移历史。聊天原文与FTS持续保存必然增长，分页只降低客户端负担，检索聚合成本随命中数增加；固定字符预算不等于所有模型精确token限制。[存储维护与限制](STORAGE.md)。

日期：2026-10-04。项目位于 `C:/Users/32202/Desktop/99999/zhiyu`。

| 验证 | 实际结果 |
| --- | --- |
| Python 自动测试 | **121 passed**；app 语句覆盖率 **92%**，1080 条语句覆盖；API、鉴权、隔离、持久化、服务商 MockTransport、中文检索、解析与旧索引导入 |
| 静态检查 | `ruff check app tests` 通过 |
| 真实 HTTP | 健康检查、显式演示登录、上传、检索、带来源聊天、历史保存通过；无 Content-Length 的超限请求实际返回 413 |
| 真实 BGE | 复用本机缓存 BAAI/bge-small-zh-v1.5，`mode=bge-hybrid`；中文换说法检索、2 个 SQLite 向量、身份隔离、缓存重用均通过；未下载模型 |
| Android 单元测试 | **16 passed**：6 个输入规则、10 个凭据/会话协调测试 |
| Android 模拟器 instrumentation | **3 passed**：2 个 Keystore 加密测试、1 个真实后端 Compose 登录/聊天/引用/知识库列表/退出流程 |
| 手动模拟器检查 | Android 系统选择器从 Downloads 导入 demo-guide.md 成功；界面显示来源文档，确认删除后显示 0 份；后端重启后出现 401→自动验证→原会话恢复 |
| APK | debug 构建、实际安装与运行通过；release R8 构建通过，产物未签名 |
| 审查 | 独立审查发现的 HIGH 上传问题已修复；最终复核见 REVIEW.md |
| Docker Linux 自动测试 | **121 passed**，app 覆盖率 **92.41%**；无网络、UID10001、只读测试容器，ruff 通过 |
| Docker 真实 HTTP | 默认演示关闭与鉴权、TXT/MD/PDF/DOCX、中文检索、两轮聊天来源、RAG 开关、身份隔离、超限文件/chunked 请求、删除/退出全部通过 |
| Docker 持久化与运行 | `restart` 与 `--force-recreate` 后旧 token 401、同 Key 恢复全部消息/文档/检索、其他 Key 仍隔离；健康检查 healthy、UID10001、只读根目录、数据卷无测试 Key 原文 |

debug 可安装文件：`artifacts/ZhiYu-0.1.0-debug.apk`，约 17.8 MiB；release 未签名：`android/app/build/outputs/apk/release/app-release-unsigned.apk`，约 1.7 MiB。release 需要用户的发布签名和 HTTPS 后端后才能正式分发。

## 可复现命令

```powershell
cd backend
uv sync --frozen --extra dev
uv run --no-sync pytest --cov=app --cov-report=term-missing
uv run --no-sync ruff check app tests
# 另一个终端在项目根目录运行 .\scripts\Start-Backend.ps1 -Demo
.venv/Scripts/python.exe ../scripts/smoke_backend.py
```

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
# emulator-5554 就绪，演示后端已启动，smoke_backend.py 已导入演示指南后：
.\gradlew.bat :app:connectedDebugAndroidTest
```

真实语义烟测工具为 `scripts/smoke_semantic.py`，需要 semantic 依赖和本地模型目录。此机单独 `.venv-semantic` 复用了已有系统 PyTorch；普通安装应使用 `uv sync --extra semantic`，不要把此机的测试环境当作可移植部署环境。

测试存在 1 条 Starlette 对旧 httpx TestClient 的弃用提醒；测试均成功，不影响生产后端的 httpx 服务商调用。Android 构建有旧 Compose 测试入口/图标的弃用提醒，无编译错误。

## Docker 本轮实际验证

2026-10-04 23:13（北京时间），`scripts/Test-Docker.ps1` 整体退出码 **0**，包括构建、121 自动测试、ruff、真实 HTTP 与两种恢复验证。复跑时设置冲突环境变量 `COMPOSE_PROJECT_NAME=zhiyu-isolation-check`，测试仍只操作 `zhiyu-docker-test`，冲突项目下没有创建容器。Docker Desktop 4.88.1，Linux Engine 29.7.2，Python 3.13.15。运行镜像只含生产依赖，实际 `pip check` 无依赖冲突，未包含 pytest 或测试源代码。Docker 构建内容改为白名单，额外 `.venv-semantic` 与私人数据不会进入上下文。两份 SQLite 的 `PRAGMA integrity_check` 均为 ok。

当时测试服务为 `http://127.0.0.1:8791`，独立项目与数据卷；运行镜像本次 ID 为 `sha256:1f6a0beb4b26c37dea996178d75f86ea40676aade68d60ddfe7fc05b0ea56343`，本地大小约60.1MiB。实际证据位于 `artifacts/docker-unit-tests.log` 与 `artifacts/docker-validation.json`。Docker Desktop 临时 socket 故障的处理和重跑步骤见 [DOCKER.md](DOCKER.md)。Android APK 没有因本轮后端容器测试而重新构建。

## 2026-10-04阶段尚未执行的外部验证（历史）

该历史阶段没有使用用户的真实 API Key，不能据此宣称 10 家厂商在线调用都已实测。适配使用官方文档核验与模拟响应验证。2026-10-06已单独完成DeepSeek Flash真实Key合成测试，见本页最新记录；其他厂商的区域、模型授权、余额和多轮行为仍未实测。

未向公网发布服务，未进行真机安装。本轮 Docker 使用默认 BM25 与显式演示模型；容器内真实 BGE 推理和安卓连接 Docker 端口未实测。旧 RAG 原私人索引只读统计了 2 文档/2 块，没有擅自导入新库。
