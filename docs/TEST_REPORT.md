# 2026-11-18：CacheClient 回炉 mock 收紧，Maven TLS 阻断

- 沿 `set()` 成功后本地 `get()` 的路径定位：本地值仅在 Caffeine 值与 Redis 版本水位同时存在且匹配时返回；成功 CAS 后生产路径登记水位并填入 Caffeine。缺失 Redis 版本键按 0 与 Lua 写入语义一致。
- 收紧 `setWithMissingRedisVersionIsImmediatelyReadableFromLocalCache` 的 `ValueOperations.get` mock：只接受指定版本键并返回初始缺失状态；读取其他键立即失败，避免未匹配 mock 静默返回 `null`。
- 本轮一次定向 Maven 调用在解析 Spring Boot 3.5.6 parent POM 时遇出口代理 `Unsupported or unrecognized SSL message`；项目未解析，编译/Mockito 未启动，Java 用例执行 0 项。本轮修订没有 Java 运行时结果，须在依赖可用的 JDK 17/Maven 环境执行目标用例及全量 `clean verify`。

# 2026-11-17：CacheClient 回归断言补强，Java 复验受 Maven TLS 阻断

- 在 `setWithMissingRedisVersionIsImmediatelyReadableFromLocalCache` 增加成功 CAS 后本地版本水位、Caffeine JSON 与版本字段断言，保留 set 后立即 `get()` 命中及不访问 Redis 数据键断言。该测试针对历史 `set()` 后返回 null 的路径，便于区分本地填充失败和读取未命中。
- 本轮一次定向 Maven 尝试在下载 Spring Boot 3.5.6 parent POM 时遭出口代理 `Unsupported or unrecognized SSL message` 阻断；项目未解析，编译/Mockito 未启动，Java 执行 0 项。断言尚未运行，不计为通过。
- 部署验证 Python 14/14、OCR Python 15/15 通过；药房前端 JS `node --check` 与 `git diff --check` 通过。它们不替代 CacheClient Java 测试。

---

# 2026-11-17：CacheClient 构建⑤回炉定向用例仍未执行

- 复核目标用例和 `CacheClient`：状态化 Redis/Lua mock 覆盖缺失版本键按 0、CAS 写 JSON、删除版本递增、`set()` 后本地命中及水位淘汰回源；生产成功 CAS 路径填充本地缓存并做版本复核。静态复核不能替代运行测试。
- 本轮唯一一次定向 Maven 命令在解析 Spring Boot 3.5.6 parent POM 时被出口代理 TLS 错误 `Unsupported or unrecognized SSL message` 阻断。Java 编译、Mockito 均未启动，测试运行 0 项。
- 回归仍未解除。需在 Maven 可用的 JDK 17 环境运行目标用例；失败时依据 Surefire 堆栈修复并复验，再运行 `deploy/verify/build-test.sh --docker` 全量测试。

---

# 2026-11-18：CacheClient 构建⑤回炉定向用例受 Maven TLS 阻断

- 目标用例 `CacheClientTest#usesBoundedLocalValueAndPublishesCrossInstanceInvalidation` 当前使用状态化 Redis/Lua mock，覆盖缺失版本键、CAS 写入、删除递增及 set 后本地命中；历史固定版本返回序列耗尽的问题已在测试替身中改为按当前状态读取。
- 本轮仅执行一次定向 Maven 命令。Spring Boot 3.5.6 parent POM 解析失败，错误为 `Unsupported or unrecognized SSL message`；Java 编译和 Mockito 均未启动，运行 0 项。该修复尚未获得当前轮 Java 执行确认，需在 Maven 可用的 JDK 17 容器中运行目标测试，再执行全量 `deploy/verify/build-test.sh --docker`。

# 2026-11-17：CacheClient 构建⑤目标用例仍受 Maven TLS 阻断

- 本轮只尝试一次 `CacheClientTest#usesBoundedLocalValueAndPublishesCrossInstanceInvalidation` 定向 Maven 用例。Spring Boot 3.5.6 parent POM 下载遭出口代理错误 `Unsupported or unrecognized SSL message` 阻断，项目未解析，Java 编译/Mockito 未启动，执行 0 项。
- 本轮未获得运行时复现或通过证据，不能将构建⑤问题标记为修复；现有候选代码修复仍待 Maven 依赖可用环境按 Surefire 结果确认。

# 本轮：CacheClient 构建⑤定向 Mockito 仍未启动

- 执行一次 `CacheClientTest#usesBoundedLocalValueAndPublishesCrossInstanceInvalidation` 定向 Maven 命令。Maven 下载 Spring Boot 3.5.6 parent POM 时被出口代理 `Unsupported or unrecognized SSL message` 阻断；项目未完成解析，Java 编译与测试均未启动，执行数 0。
- 目标实现和状态化 mock 当前包含缺失版本键按 0、CAS 写入、成功后记录本地水位、set 后本地命中及版本复核断言。由于测试没有运行，本轮不能确认这些路径通过；没有新增 Surefire 栈支持额外改码。
- 下一步：在 Maven 依赖可用的 JDK 17 环境运行目标用例；如失败按 Surefire 堆栈修复，再执行全量 `deploy/verify/build-test.sh --docker`。本条记录不代表 CacheClient 验收通过。

# 测试记录

## 本轮：CacheClient 目标用例未能复现（未执行）

- 静态核对 `CacheClient` 后确认目标用例已有的 Redis 回填版本读取累计次数期望（6、10）与当前调用路径一致，未保留临时的错误计数修改。该检查没有复现历史 set/get 返回 null。
- 唯一定向 Maven 执行在 Spring Boot 3.5.6 parent POM 下载时被出口代理 `Unsupported or unrecognized SSL message` 阻断；编译和 Mockito 未启动，Java 执行 0 项。构建⑤问题仍未解除。

## 本轮：CacheClient 构建⑤目标测试受 Maven TLS 阻断（未执行）

- 一次定向 Maven 命令同时选择 `usesBoundedLocalValueAndPublishesCrossInstanceInvalidation` 和 `setWithMissingRedisVersionIsImmediatelyReadableFromLocalCache`。Spring Boot 3.5.6 parent POM 下载因出口代理 `Unsupported or unrecognized SSL message` 失败，项目未解析，编译和 Mockito 未启动，Java 测试运行 0 项。
- 本次没有获得运行时复现、Surefire 失败栈或通过证据；不能据此判断是 mock 还是生产版本处理问题，也不能记为修复完成。须在 Maven 依赖可用环境运行后再按结果更新结论。

## 2026-11-18：CacheClient set 后立即读取隔离回归用例（未执行）

- 新增 `setWithMissingRedisVersionIsImmediatelyReadableFromLocalCache`，用状态化 Redis/Lua mock 覆盖缺失版本键按 0 进行 CAS、写入成功后 `set()` 立即 `get()` 命中本地 JSON，且不读取 Redis 数据键；原跨失效回归断言保留。
- 本轮定向 Maven 在 Spring Boot 3.5.6 parent POM 下载阶段因 TLS 代理 `Unsupported or unrecognized SSL message` 失败，编译和测试未启动，Java 执行 0 项。新增测试没有运行结果，不能记录为通过。

## 2026-11-18：CacheClient 成功 CAS 后本地填充路径调整（Java 未验证）

- 成功 Lua CAS 已证明写入时版本匹配；`setJson()` 现记录版本水位、写入 Caffeine 后只做一次 Redis 版本复核。若删除在 CAS 与本地填充之间发生，复核会失效本地值。Redis 回填路径仍以前后版本读取防止旧值回填。
- `CacheClientTest.usesBoundedLocalValueAndPublishesCrossInstanceInvalidation` 保留 set 后本地 JSON 命中、Caffeine 命中不读取 Redis 数据键、跨实例失效和水位淘汰回源断言，并调整版本读取次数断言。
- 本轮唯一一次定向 Maven 命令在 Spring Boot 3.5.6 parent POM 下载时受出口代理 TLS 错误 `Unsupported or unrecognized SSL message` 阻断；编译和 Mockito 未启动，Java 测试运行 0 项。此改动未获 Java 执行验证，需在依赖可用的 JDK 17/Maven 环境运行目标用例及全量 `clean verify`。

## 2026-11-16：生命周期对账断言补强

- `order-lifecycle.py` 现在在数据库查询前校验订单号；取消/核销逐个 batch ID 比较状态，校验分配总数、预留变化和 SALE_OUT/ORDER_RELEASE 流水数量。活动结束验证也逐批比较并检查 ACTIVITY_RELEASE 流水条数与总量，避免原先 `zip` 静默漏掉缺行的情况。
- 新增 5 个生命周期断言单测，覆盖 batch 顺序变化、批次缺失、核销流水偏差和活动释放账目偏差。执行 `python3 -m unittest discover -s deploy/verify/tests -v`：13 项通过、0 失败；OCR 规则/PDF 单测 15 项通过、0 失败。
- Python `compileall`、所有验证 Shell `bash -n`、三份工作台 JS `node --check`、`git diff --check` 通过。Docker CLI 可用但 daemon socket 不存在，生命周期脚本、全栈、压测、缓存/Kafka/Redis 注入均未连服务执行；真实业务指标仍未测。Java 测试数 0；本轮不重试近期因 Maven Central TLS 失败的 Maven 构建。

## 2026-11-16：OCR 规格和采购单价字段补齐（Java/Maven 未验证）

- 对照原始需求发现 OCR 候选、人工确认和准确率报告此前缺少规格与采购单价。FastAPI 规则与 LLM 候选、显式分隔表格、工作台审核表单及 20 样本评估器现覆盖这两个字段；价格按两位小数比较，规格人工确认时必须与当前门店选定 SKU 规格一致。
- 新增 Flyway V9 为审核明细保存非负采购单价；批次入库与 INBOUND 流水仍由原事务执行。新增 Java 单测覆盖价格边界和规格标准化，但 Maven 受本机代理/仓库阻断，Java 编译与测试尚未运行；V9 实际迁移及人工确认事务仍未验证。
- 本轮实跑 OCR Python 规则/PDF 单测 15 项通过、部署验证器单测 6 项通过。评估器单测生成的 20 条返回值是 HTTP 替身，仅验证规格/采购单价字段计分，不是 PaddleOCR 准确率。
- 部署/OCR Python `compileall`、所有验证 Shell `bash -n`、全部前端 JS `node --check`、OCR evaluator `--help`、`git diff --check` 与 `.handoff`/密钥/构建产物忽略检查通过。Docker CLI 29.1.3 可用，但 daemon socket 缺失，Compose 及 V9 数据库验证未运行。
- 本轮唯一 Maven `clean verify` 在解析 Spring Boot parent POM 时因 Maven Central TLS 代理错误 `Unsupported or unrecognized SSL message` 退出；编译未开始，Java 测试仍运行 0 项。本轮不再重试 Maven。
- 本轮服务器 PaddleOCR 图像、扫描 PDF 推理和 20 样本真实评测仍未运行。字段/SKU 准确率、人工修正率、峰值内存均未测；不得引用测试替身中的 100% 作为实际指标。

## 2026-11-16：扩展隔离全栈 API 冒烟（尚未执行）

- `full-stack-smoke.sh` 增加合成店主/门店导入，并调用 `business-api-smoke.py` 完成登录、商品创建、批次入库、活动发布、Redis 预约、Kafka 消费建单、查单、负责人核销和 FEFO 库存断言。全流程只作用于脚本创建的唯一临时 Compose 项目，退出时销毁项目及数据卷。
- 本轮检查 `business-api-smoke.py` 的 `py_compile`/`--help`、`full-stack-smoke.sh` 的 `bash -n` 与 `git diff --check` 均通过。这些是静态检查，不是 API/数据库/Kafka/Redis 实测。
- 本轮 `docker info` 失败：Docker CLI 29.1.3 无法连接 `/var/run/docker.sock`；因此没有运行全栈冒烟、Maven 容器构建、故障注入或压测。本轮没有再次尝试 Maven。Java 编译和测试仍未运行；压测、OCR 图像及真实模型实测数字仍为 0/未测。

## 2026-11-16：本轮本地复核（Java 构建被本机仓库权限阻断）

- 实际执行 OCR 提取/PDF 规则测试 13 项，通过 13、失败 0；部署验证器测试 6 项，通过 6、失败 0。验证器使用替身响应，不代表真实 OCR、模型或服务端结果。
- `python3 -m compileall -q deploy/verify services/ocr`、所有 `deploy/verify/*.sh` 与 `deploy/test-inbound-migration.sh` 的 `bash -n`、前端全部 JS 的 `node --check`、`git diff --check` 通过；`git check-ignore .handoff/PROGRESS.md` 确认交接目录被忽略。
- 本轮唯一 Maven 尝试为 `mvn -f AI-dianping-backend/pom.xml clean verify`，在编译前因无法创建 `/root/.m2/repository`（`LocalRepositoryNotAccessibleException`）退出。Java 测试实际运行数仍为 0；遵守每轮一次限制，本轮不再重试。
- `docker info` 显示 CLI 可用但无法连接 `/var/run/docker.sock`。全栈、压测、Kafka/Redis 故障注入、缓存多实例、真实 PaddleOCR 图像评估和真实模型评估均未执行。压测请求数 0、吞吐/延迟未测；图像 OCR 样本数 0、准确率/修正率/峰值未测；真实模型评估数 0。
- 本轮未创建 `.handoff/DONE`。`.git` 元数据按交接环境限制保持只读，全部工作区改动仍待可写 Git 环境提交。

## 2026-11-16：评估器规则回归与服务器脚本复核

- 新增部署验证器单测 6 项：覆盖 AI 经营助手 20 个用例的来源/工具断言、错误工具失败、预约压测 token 唯一性/数量校验、回环地址保护和最近秩分位数；另覆盖 OCR 修正率统计路径。测试使用替身响应，不代表真实模型、OCR 或压测结果。
- 单测发现并修复 AI 评估器 OPS-20 的拒答词断言缺陷：将四个等价拒答短语从“全部必须出现”改为“任一命中即可”。本次规则回归通过。
- 本轮实际执行：`python3 -m unittest discover -s deploy/verify/tests -v` 6 项通过；`python3 -m unittest discover -s services/ocr/tests -v` 13 项通过；Python `compileall`、部署 Shell `bash -n`、3 个前端 JS `node --check`、`git diff --check` 通过。
- Java/Maven、Docker Compose、预约压测、消息故障注入、Redis 多实例、PaddleOCR 图像和真实经营助手模型仍未运行。本机 `java`/`mvn` 可见（JDK 21/Maven 3.8.7），但本轮按每轮 Maven 限制未重试；Docker daemon socket 不可用。Java 测试数 0、压测请求数 0、OCR 图像样本 0、真实模型评估 0。

## 2026-11-16：预约对账过期批次判定修正及本轮复核

- 修正 `deploy/verify/reconcile-reservations.py`：过期批次断言改为比较批次有效期与订单创建日期，避免用活动结束后固定两天作为代理时间。隔离 Compose/MySQL/Redis 未启动，故 SQL 断言仍未实跑。
- 本轮唯一 Maven 尝试 `mvn -B -ntp clean verify` 在编译前失败：Maven 无法创建 `/root/.m2/repository`（`LocalRepositoryNotAccessibleException`）。本轮不再尝试；Java 编译/测试数仍为 0。
- `docker info` 的客户端可用，但 daemon socket `/var/run/docker.sock` 不存在；全栈、压测、缓存及故障注入未执行。OCR 规则单测 13 项与 OCR 评估器单测 1 项通过；评估器测试使用模拟 OCR 响应，不代表 OCR 图像准确率。
- 本机当前验证指标：压测请求 0、QPS/延迟未测；真实 PaddleOCR 图像样本 0、准确率/修正率/峰值未测；真实模型评估 0。`.git` 只读，提交仍待可写收尾环境。

## 2026-11-16：本机 OCR 与服务器验证脚本复核（Java/容器未运行）

本轮执行 `python3 -m unittest discover -s services/ocr/tests -v`：13 项通过、0 失败。`python3 -m compileall -q deploy/verify services/ocr`、全部验证 Shell `bash -n`、3 个前端脚本 `node --check`、所有验证 Python 脚本及预约压测脚本 `--help`、`git diff --check` 均通过。以上只验证文本提取/合成样例与脚本语法，不代表 Java、数据库、Kafka、Redis、PaddleOCR 图像推理或全栈验收通过。

复核服务器端执行条件时，当前运行环境中 `java`、`mvn`、`docker` 均不可用；本轮未重试 Maven，也未尝试 Docker。服务器 `clean verify`、全栈、压测、故障注入、缓存实测、OCR 图像评估和真实模型评估均没有新增执行证据，指标仍为未测。`.git` 只读，修改仍待可写收尾环境提交；`.handoff/` 不纳入提交。

## 2026-11-16：本机继续开发复核

- 执行 `python3 -m unittest discover -s services/ocr/tests -v`：11 项通过、0 失败，包含 20 份合成文本单据、显式分隔表格、多候选及文本 PDF 规则。该结果不代表 PaddleOCR 图像推理或真实文档准确率。
- Python 部署/评估脚本 `py_compile`、部署 Shell `bash -n`、三个前端脚本 `node --check` 和 `git diff --check` 均通过。
- `docker info` 无法连接本机 Docker daemon；全栈脚本、缓存 Redis 多实例验证、预约压测及故障注入均未执行。按本机环境注记，本轮未尝试 Maven；Java 测试仍无运行结果。
- 静态审阅确认 Bloom 过滤只应用于旧门店详情的 `queryWithPassThroughAndMutex` 路径，药房药品/活动目录不通过该门店过滤器，因此不会因商品 ID 不在门店 Bloom 中而被拒绝。该审阅不是运行时验证；合法商品访问仍需在服务器栈实测。
- `.handoff/` 已被 `.gitignore` 忽略。当前 `.git` 为只读，本轮未提交；全部恢复和后续改动待 Git 元数据可写的收尾环境提交。没有创建 `.handoff/DONE`。

## 2026-11-16：缓存直接读取并发失效保护（Java 测试未运行）

`CacheClient.get` 改为复用与目录回源路径相同的 Redis 版本前后检查：若失效与 Redis GET 交错，返回 `null` 让调用方回源，不把旧值返回给调用方，也不写入 Caffeine。新增 Mockito 单测模拟版本从 0 变为 1 的竞争窗口。本轮 OCR 提取/PDF 规则测试 13 项通过，部署/OCR Python 编译、所有部署 Shell 语法、三个前端 JavaScript 语法及 `git diff --check` 通过。该 Java 测试尚未编译或运行；Redis Pub/Sub、两实例和 TTL 实测仍未完成。

## 2026-11-16：订单生命周期验证入口（未实跑）

新增 `deploy/verify/order-lifecycle.py`，用于隔离 Compose 栈的取消、负责人核销和活动结束释放场景：通过 API 执行取消/核销并断言状态及批次库存变化；将独立测试活动结束时间设为过去值，等待生命周期任务后断言活动结束、活动批次余量清零、批次活动预留按本活动释放量减少、每个非空活动批次有释放流水。该入口仅做脚本编译、参数帮助及 diff whitespace 检查；实际取消/核销/结束释放、数据库断言均未执行。它不检查取消事件 Redis 投影，也不模拟调度任务崩溃，不能作为端到端生命周期通过证据。

## 2026-11-16：Redis 预扣后请求提交中断恢复入口

新增 `deploy/verify/recover-expired-initializing-reservation.sh`，在隔离已发布活动上构造“Redis 已预扣、MySQL 请求仍为过期 INITIALIZING”的可恢复状态，等待真实 ReservationRecoveryJob 执行，并断言 MySQL 状态为 `EXPIRED/RELEASED`、Redis 库存精确回到注入前、用户限购标记被清除、预约状态转为 EXPIRED。成功时清理自己的 Redis 预约/ZSet 探针键，MySQL 审计行保留。该脚本不实际停库或注入 MySQL 宕机；本机 Docker daemon 不可用，脚本尚未在全栈运行，结果仍为未测。

## 2026-11-16：Kafka 消费 DLT 与修复后重放入口

新增 `deploy/verify/kafka-dlt-replay.py`：记录隔离 DLT topic 各分区 offset，发送缺少 `requestId` 的 poison 消息，断言新增 DLT 记录包含唯一探针 key 和无效 payload；再将 payload 修复为已有成功请求并重放到源 topic，断言订单仍恰好一张。脚本保留 DLT 记录及报告供审计。当前只完成静态检查，未连接 Kafka；没有 DLT 或重放的实测通过结论，也没有覆盖 MySQL 提交后 Offset 精确中断。

## 2026-11-16：OCR 评估分母与输入校验修正

修正 `deploy/verify/ocr-evaluate.py`：准确率以 gold 中全部 20 行作为分母，上传失败样本不再被排除；相对样本路径基于 gold CSV 目录解析，并校验样本 ID 唯一、SKU 非空、必需列齐全及目录重复药名。该改动目前仅完成静态检查，未连接 PaddleOCR 服务；真实推理和准确率仍未测。

2026-11-16，本机 Windows，JDK 21，Maven 3.6.3，在独立 worktree 执行 `mvn -q package`，退出码 0。Surefire 报告共 8 个测试、0 失败、0 错误：原预约控制器 3 个、原 MCP 认证 3 个、新 FEFO 计算 2 个。FEFO 单测覆盖最早到期优先、同日期按 ID 排序、过期及隔离排除、库存不足和重复批次输入。

测试过程原有 `logback.xml` 尝试写入不存在的 `D:/Java_project/logback/hmdp`，文件 appender 报错，构建仍通过；需要改成可配置且跨平台的日志路径。Mockito 在 JDK 21 提示动态 agent 警告。

这些测试不验证 MySQL 锁或库存事务、Redis、Kafka、并发、OCR 或 AI。Docker 引擎检查失败，容器集成测试、压力测试和 AI 评测尚未运行，不能据此宣称任何性能或准确率。

## 2026-11-16：药房库存基础

本地 `mvn -q package` 成功，Surefire 共 10 个测试通过，包括新增的两个门店归属校验测试。服务器独立 Compose 项目的 MySQL 8.4 内另建 `npc_inventory_test_20261116_v2` 测试库，在其中建立最小 `tb_shop` 后直接执行 V1 SQL；验证成功插入有效批次，跨门店批次、负库存、不平衡库存和同门店同商品重复批次均被拒绝。脚本为 `deploy/test-inventory-migration.sh`，该测试库保留以便检查，脚本不能在同名库已存在时重复运行。

使用原测试库的表结构创建独立空库 `npc_flyway_test_20261116` 后，以候选应用容器运行 Flyway：日志显示 baseline 0 成功、V1 成功应用且应用启动。Flyway 对 MySQL 8.4 输出兼容性警告（该版本 Flyway 声明已测试至 MySQL 8.1）。候选容器已停止，原测试网站保留运行。

此验证不代表接口端到端、库存事务、FEFO 锁或并发测试已通过。当前运行在服务器的网站仍是先前提交 `41d862d`，未自动包含本次开发的变更。一次排查时测试库凭据被输出到工具结果中，已经轮换该隔离环境密码并重建相关容器；没有使用其他系统的凭据。

## 2026-11-16：MVP 浏览器联调

服务器应用容器强制重建后，Maven 包构建及原有测试共 11 项通过，前端 `node --check` 通过。通过隔离演示账号实际完成登录、创建商品、入库 20 件批次并在页面看到库存与 FEFO 查询结果。第一次传入演示店主昵称的 SQL 未指定字符集，浏览器显示乱码；种子脚本加 `SET NAMES utf8mb4` 并重写记录后重新登录，昵称“邻药云演示店主”显示正确。前端页面和表单中的中文在浏览器中正常显示。

当前版本仍是 MVP；实际药品业务、并发预约、AI、OCR 和压力评测没有完成。服务器复核时工作台 HTTP 200、五个隔离容器运行、签到服务 `xmu-wechatbot.service` 为 `active`，整机内存使用 1,149/1,998 MiB（约 57.5%）；未登录请求 API 根路径得到预期的 401。Nginx 页面响应声明 `text/html; charset=utf-8`，并在浏览器中复核中文昵称和工作台文案正常。

## 后续阶段：活动发布与 FEFO 预留

本地 JDK 21 执行 `mvn -q test`：14 项通过、0 失败、0 错误。新增服务测试覆盖处方药活动拒绝、Redis 发布失败时不将 MySQL 活动标为已发布；原 FEFO 单测覆盖有效批次排序和库存不足。服务器 `neighbor-pharmacy-test` 的 MySQL 中建立 schema-only clone `npc_activity_migration_run4` 并成功应用 V2 DDL；V2 表、复合外键和批次 ID 唯一索引均通过检查。服务器 Redis 上实际执行初始化 Lua：首次初始化成功、重复调用保留已扣减库存、部分 key 和已发布活动丢失 key 均失败关闭。


本地 JDK 21 执行 `mvn -q package`：16 项通过、0 失败、0 错误。服务器 MySQL 隔离库已应用 V2–V5。Redis 初始化、预约、释放和取消 Lua 脚本使用隔离测试键验证。

服务器真实 API 联调：演示店主登录；创建并发布活动，Redis Lua 接受预约，初始状态 `PROCESSING`；Kafka Consumer 创建订单后状态 `SUCCESS` 并返回订单号。使用同一 `Idempotency-Key` 重试仍返回同一订单；店主核销后订单 `COMPLETED`。批次核销后 `on_hand_qty=19`、`order_reserved_qty=0`；另有一个尚未开售活动占用 1 件 `activity_reserved_qty`。这验证了单请求完整链路，不代表高并发或跨系统故障恢复已证明。

联调修复：药房 Reservation Controller Bean 重名；MySQL `DATETIME` 用 `Timestamp` 读取造成容器 UTC 与业务时区偏差，现改用 `ResultSet.getObject(..., LocalDateTime.class)`。修复后活动发布和预约通过。

服务器内存检查：主机 1,420/1,898 MiB，Compose 硬限额 1,392/1,600 MiB；微信机器人服务保持 active。OCR、Agent、压测和消息故障注入尚未完成。

## 2026-11-16：经营助手与 OCR 人工入库

本地 JDK 21 执行 `mvn -q package` 成功，Surefire 当前汇总 19 项测试、0 失败、0 错误；药房经营上下文与 OCR SKU 匹配均有单测。前端脚本 `node --check` 通过，Python OCR 字段提取 3 项单测通过。

隔离服务器 Flyway 已真实将 hmdp 测试 schema 从 V5 升级到 V6。用隔离网络内明确标识的 Java mock OCR upstream，演示负责人登录后真实调用 Java OCR 代理：上传响应包含待复核候选、SKU 精确匹配；确认事务写入批次、复核审计及 `INBOUND` 流水；再次确认被拒绝。测试脚本清理了该次合成单据和批次。该测试验证 Java 代理、DB 持久化和人工确认事务，不代表 PaddleOCR 的识别准确率。

药房经营助手经演示负责人登录调用返回非空规则摘要，来源标记为 `RULES_FALLBACK`；未登录请求返回 401。真实模型 API 尚未配置或调用。真实 PaddleOCR 权重、真实供应商图片识别、20 份文档准确率和真实 LLM 抽取仍未验证。2 GiB 服务器没有运行 PaddleOCR：现有 Compose 硬限额为 1,392/1,600 MiB，Paddle 模型内存峰值未知；因此服务器上传功能当前会提示 OCR 上游不可用，手动批次入库仍可用。

资源事故记录：尝试用 `mysqldump` 复制完整测试 schema 时，256 MiB 限制下的隔离 MySQL 容器退出码 137。主机内存仍在 1,898 MiB 上限内。随后仅重启了隔离 MySQL 容器，健康检查恢复且数据卷保留；签到/微信机器人服务复核为 `active`。后续 V1+V6 DDL 验证改用空白小 schema。最后复核主机使用约 1,431/1,898 MiB，Compose 硬限额 1,392/1,600 MiB；五个正式测试容器运行，临时 mock 已移除。首页和工作台 HTTP 200，本机 SSH 隧道 `http://127.0.0.1:18082/` 可访问。

## 2026-11-16：交接新开发机复核

新开发机在 `services/ocr` 执行 `python3 -m unittest discover -s tests -v`：4 项通过、0 失败；其中新增 20 份可复现合成供应商单据文本样例，覆盖中英文标签、日期格式、批号和数量字段。这只验证规则提取，不验证图片 OCR、供应商文档准确率或人工入库链路。

本机 Java 测试未能启动。系统 Maven 默认本地仓库不可创建；改用 `/tmp/neighbor-pharmacy-m2` 后 Maven Central 依赖请求失败，错误为 `Unsupported or unrecognized SSL message`，Spring Boot parent 未解析。`docker info` 无法连接 Docker daemon，因此本机未运行 Compose、数据库/Kafka 联调或压测。此环境 Java、容器、图像 OCR 和模型验证没有新的通过结论，详见 `.handoff/PROGRESS.md`。

前端 `common.js`、`pharmacy.js` 和 `pharmacy-admin.js` 分别通过 `node --check`；工作区 `git diff --check` 通过。显式配置 egress proxy 后 Maven 仍失败（`ClientProtocolException`）；`curl` 对 Maven Central 同一 POM 返回 HTTP 200，说明不是该 POM 不存在。

## 2026-11-16：OCR 置信度边界与 AI 评估规格

OCR 提取层现在将每行置信度转为有限的 `[0,1]` 数值后再求候选均值；非法、NaN、无穷和布尔值按 0 处理，API 返回的逐行置信度同样归一化。新增测试覆盖越界值和非有限值。执行结果记录在 `.handoff/PROGRESS.md`。

新增 `docs/AI_OPERATIONS_EVAL.md`，包含 20 条经营助手合成提示、预期只读工具/拒答和门店隔离断言。它是待部署执行的评估规格，不是模型运行或准确率通过证据。

## 2026-11-16：OCR 行内字段文本

本机在 `services/ocr` 执行 Python 单测，6 项通过。新增一行多字段及竖线分隔的合成文本用例；规则提取会在下一个已知字段标签处截断药品名，避免把后续批号/日期并入名称。20 个合成供应商文本样例仍全部通过。以上只验证 OCR 输出文本的字段规则，不验证真实表格布局、多商品识别、图像准确率或 PDF。

## 2026-11-16：预约并发驱动器

新增 `deploy/load-test-reservations.py`，从私有 CSV 读取测试 token，向预约 API 并发发送请求并记录 HTTP/业务状态、吞吐及 p50/p95/p99 延迟；报告不包含 token。非回环目标默认拒绝，必须显式启用远程隔离测试目标。执行 `python3 -m py_compile deploy/load-test-reservations.py` 和 `--help` 成功。当前环境没有 Docker daemon，且没有准备测试用户 token，因此没有发送负载请求；100 件库存/1,000 用户实测与 MySQL 库存对账仍待部署验证。该工具产出请求指标，不单独证明库存正确性。

## 2026-11-16：本机文档与提取测试复核

再次运行 `python3 -m unittest discover -s services/ocr/tests -v`：6 项通过、0 失败，含 20 份合成文本样例、置信度边界、行内字段与竖线分隔文本。`common.js`、`pharmacy.js`、`pharmacy-admin.js` 通过 `node --check`；`git diff --check` 通过。README 架构图与 `docs/ARCHITECTURE.md` 已补充药房 Outbox/Kafka/FEFO 链路、可选 OCR 和只读经营助手；缓存设计文档同步到当前版本化失效实现。以上不改变 Java、Redis 并发、真实 OCR/模型或容器验证状态。

## 2026-11-16：含文本 PDF 提取

新增文本层 PDF 解析入口：限制 10 MiB、最多 10 页和最多 100,000 个提取字符；加密、损坏、空白/扫描版 PDF 拒绝。PDF 文本行置信度标为 0（未评分），提取候选仍要求人工复核。OCR 规则增加英文商品名、数量标签以覆盖常见 PDF 送货单。当前新增的 2 项 PDF 测试使用 reader 替身验证字段流转和拒绝边界，不代表真实 PDF 文件、扫描 PDF 图像 OCR、复杂表格或真实供应商文档验证通过；pypdf 依赖安装受本机代理超时影响，真实解析器验证顺延部署环境。

## 2026-11-16：部署验证入口与 OCR 评估工具

新增 `deploy/verify/build-test.sh`（宿主 Maven 或 Maven 3.9 + JDK 17 容器执行 `clean verify`）、`full-stack-smoke.sh`（隔离 Compose、迁移启动、OpenAPI paths、Swagger UI、匿名预约认证断言）、`ocr-image-selfcheck.sh`（容器化真实图片推理并断言人工复核标记）和 `ocr-evaluate.py`（恰好 20 个文件，字段/SKU 目录匹配准确率；可选人工修正 CSV）。本机只执行 Python 编译/帮助、Shell 语法检查、OCR 8 项单测、3 个前端脚本语法检查和 `git diff --check`，全部通过。没有 Docker daemon、真实图片集、SKU gold 文件或服务器密钥，因此 Maven 容器、全栈、PaddleOCR 推理和准确率没有执行；报告中没有填入虚构指标。

追加复核：Swagger/OpenAPI Nginx 前缀已设为 `^~`，服务器冒烟会请求 Swagger JS bundle；本机 Shell 语法和 diff whitespace 检查通过。本机没有 Nginx 二进制，未运行 `nginx -t` 或 HTTP 静态资源验证。

## 2026-11-16：预约压测对账入口

新增 `deploy/verify/reconcile-reservations.py`，对指定活动的 MySQL 订单、挂起请求、批次库存守恒、过期批次使用、订单内 FEFO 分配顺序、活动批次汇总，以及 Redis/MySQL 活动剩余库存执行断言。订单数上限与同用户重复订单检查覆盖活动下全部订单状态，避免取消订单掩盖超卖测试结果。压测驱动器现在拒绝重复 token，并在所有 HTTP 请求均收到响应且无传输错误时才返回成功；业务和库存正确性仍由对账器断言。

本机仅验证脚本编译、帮助输出与 Shell/Python 语法；Docker daemon 不可用，未对数据库查询或真实 Redis/MySQL 结果执行验证。该项不能视为压测通过，也没有生成任何性能数字。

## 2026-11-16：缓存单测补充（尚未执行）

`CacheClientTest` 增加 Redis 命中不回源且后续由 Caffeine 命中的断言，以及布隆过滤器完成初始化后允许已知 ID 通过的断言。当前 Java/Maven 测试未执行；这些新增测试只是待服务器 `mvn clean verify` 验证的代码，不计入任何通过数量。Pub/Sub 跨实例、消息丢失后的 TTL 兜底及并发旧值回填仍需真实 Redis 多实例验证。

## 2026-11-16：Kafka 重复预约投递验证入口

新增 `deploy/verify/replay-kafka-request.py`：限定使用文档中的隔离 Compose 项目，向指定已接受预约发送十条相同 Kafka 事件，并断言请求最终成功、MySQL 只有一笔订单且订单号唯一。该入口只做代码与参数静态检查，当前没有 Docker daemon，未发送 Kafka 消息，因此不能记为重复消费实测通过。它不覆盖 Offset 提交中断、DLT、DLT 重投或 Outbox Relay 崩溃恢复；缓存多实例/TTL/Bloom Redis 实测亦未完成。

## 2026-11-16：缓存 fail-open 与跨实例失效单测补充（未运行）

`CacheClientTest` 增加布隆过滤器快照尚未就绪时不拦截合法 ID，以及接收版本化 Pub/Sub 失效消息后丢弃本地值并回读 Redis 的单测。当前本机 Java/Maven 测试没有运行，这两条是待服务器执行的测试代码，不计为通过。真实 Redis 多实例 Pub/Sub、消息丢失后的 TTL 兜底、旧值并发回填仍未验证。
# 验证状态补充：Outbox 租约恢复入口

新增 `deploy/verify/recover-stale-outbox.py`，针对隔离 Compose 项目，将已成功预约的 SENT 事件模拟成过期 CLAIMED 租约，重启 backend 并断言 Relay 最终重新发送、事件恢复 SENT、请求保持 SUCCESS 且只有一张订单。此测试覆盖过期租约恢复与重复消费幂等；它不证明 Relay 在 Kafka 确认与数据库标记之间精确崩溃、Kafka Offset 中断、DLT 进入/重投。脚本尚未在服务器/Docker 上执行，实测结果为未测。

## 2026-11-16：压测库存对账断言增强

`deploy/verify/reconcile-reservations.py` 增加 100 件活动初始库存断言、活动库存不超配断言，以及“仍有活动余量的批次不得已过期”断言；报告明确检查活动批次总量、订单上限、用户限购、订单 FEFO、库存平衡和 Redis/MySQL 余量一致。项目参数限制为文档约定的隔离 Compose 名称。新增两项纯函数回归测试覆盖完整通过路径及错误库存大小、过期批次、Redis 漂移的失败路径。

本轮本机实测：OCR 规则/PDF 单测 15 项通过；部署验证器单测 8 项通过（其中 20 样本 OCR 评估入口使用替身 HTTP 响应，不是 OCR 识别证据）。Docker CLI 可用，但 `docker info` 因 `/var/run/docker.sock` 不存在失败；Maven 因近期已在该环境遇到 Maven Central TLS 代理错误，本轮未重试。服务器数据库对账、压测及所有容器验证仍未执行，性能指标仍未测。

## 2026-11-16：Redis 预扣后真实 MySQL 故障注入入口

新增默认关闭的 `test-pause-after-redis-ms` 验证属性及 `mysql-outage-after-redis.sh`。专属 Compose override 在预约 Lua 成功后暂停线程，脚本观察到 Redis 预扣后停止 MySQL，等待请求 TTL 过期，再启动数据库并断言恢复任务写入 `EXPIRED/RELEASED`、库存精确返还、用户限购标记清除。此前仅手工构造 INITIALIZING 状态的恢复入口仍保留。该用例需要服务器隔离环境实跑，目前没有真实故障注入证据。

## 2026-11-16：OCR 和经营助手评估入口复核

OCR 评估报告现在分别计算修正过的单据率和修正字段率，校验修正行实际改变 OCR 候选，并列出输入类型；SKU gold 必须存在于目录。`PharmacyStoreContext` 在本次 Agent 请求作用域内记录真实执行的只读工具名，聊天响应增加 `toolCalls`。新增 `deploy/verify/ai-operations-evaluate.py`，可对 20 个提示断言来源、工具名及安全拒答；可注入双门店 gold 标记、B 店负责人 token 和 fallback 探针。

本机执行 `python3 -m unittest discover -s services/ocr/tests -v`：8 项通过；Python 编译和两个评估脚本 `--help`、部署 Shell `bash -n`、三个前端脚本 `node --check` 与 `git diff --check` 均通过。Java/Maven 未运行，故工具轨迹实现与新 Java 单测尚未编译验证；真实模型、双门店 gold、fallback 探针和 OCR 图像评估均未执行。`docker info` 无法连接 daemon，隔离全栈仍未启动。

## 2026-11-16：Redis Lua 自检入口

新增 `deploy/verify/redis-reservation-lua.sh`，对文档指定的隔离 Compose 项目执行应用中的预约与释放 Lua，使用唯一测试键并在退出时清理。断言覆盖原子预扣、同一请求重放、单用户限制、售罄保护、返还幂等和不相关用户标记保留。本机已通过脚本 `bash -n`；Python OCR 单测 8 项、Python 脚本编译/帮助、部署 Shell 语法、三个前端脚本语法和 `git diff --check` 均通过。Docker daemon 不可用，本脚本未实际运行；它只验证 Redis Lua，不代表 MySQL/Kafka 预约故障链路通过。Maven 未运行。

# 2026-11-16：OCR 多行显式表格候选

- OCR 提取器新增对带可识别五字段表头的 pipe、tab 或逗号分隔表格行提取多个候选。API 保留旧的 `candidate` 字段并新增 `candidates` 数组，兼容单条文档调用；表格多行路径不调用单记录 LLM 抽取，仍强制人工复核。
- 解析依赖 OCR 文本顺序和显式分隔符；合并单元格、多行单元格、缺失表头或仅靠视觉对齐的表格仍不支持。此项是文本层合成测试，不代表 PaddleOCR 图像布局能力或图像准确率。2026-11-16 后续开发已将 `candidates` 逐行持久化、逐行显示 SKU 建议，并新增 `confirm-items` 原子批量人工复核入库及 Flyway V8。该 Java/前端链路未编译或全栈执行，不能记为通过。
- 本机执行 `python3 -m unittest discover -s services/ocr/tests -v`：11 项通过、0 失败；其中包括 20 个合成文本样例、pipe/tab/逗号分隔表格、非结构化回退及文本 PDF 规则测试。真实图像推理、字段准确率和服务器 API 联调仍未测。

# 2026-11-16：OCR 多商品候选进入人工审核链路

- Java OCR 代理现在将 FastAPI 返回的所有候选（最多 100 行）及各自的 SKU 匹配建议保存到 `inbound_document.candidate_json`；旧的 `candidate` 响应字段保留首行以兼容现有调用。待审核详情同时返回 `candidates`。
- 工作台逐行展示多商品字段与匹配建议，要求逐行选择 SKU 并复核批号、生产/有效期和数量。新增 `/confirm-items`，所有行在同一事务中验证、登记批次和审核记录；任何行失败均应整体回滚。既有单行 `/confirm` 仍保留。
- 新增 Flyway V8，允许一个入库单记录多条人工审核结果；更新隔离 DDL 验证脚本检查 V8 索引状态。复杂视觉表格和扫描 PDF 仍未支持，表格识别只依赖明确分隔符/表头。
- 本机验证：OCR Python 单测 11 项通过；部署与评估 Python `compileall`、所有部署 Shell 和隔离迁移 Shell `bash -n`、三个前端 JS `node --check`、`git diff --check` 均通过。Maven 本轮唯一一次尝试在解析项目前失败：无法创建 `/root/.m2/repository`（权限/路径环境错误），没有编译或运行任何 Java 测试；按每轮一次限制不重试。Docker、真实图像 OCR、Java 入库多商品集成和服务端端到端均未执行。
- `docs/ARCHITECTURE.md` 更新到 V1–V8。该工作仍未提交；`.git` 只读且 `.handoff/` 已忽略。本次变更及此前恢复的全部代码和文档列入收尾可写 Git 环境的待提交清单。

## 2026-11-16：扫描版 PDF OCR 代码路径（未做真实推理）

扫描版 PDF 现在通过 PyMuPDF 在单页 20 MP、整份 40 MP、最多 10 页的预算内渲染，并复用 PaddleOCR 图像识别；文本型 PDF 仍使用 pypdf，二者以 `input_mode` 区分。新增扫描版 PDF 自检入口和依赖说明。本机 OCR Python 单测 13 项通过，含假的 PDF 渲染器像素/页数预算断言；Python 编译、Shell 语法、药房工作台 JS 语法和 `git diff --check` 通过。Docker daemon 不可用，PyMuPDF/PaddleOCR 实际渲染、推理、图像准确率和峰值内存均未运行或测量；此项不构成扫描 PDF OCR 验收通过。Java/Maven 与其他全栈验证状态不变，均未运行/未测。
# 缓存双实例验证入口（2026-11-16）

- 新增 `deploy/verify/cache-invalidation.py` 与两实例资源覆盖文件。断言覆盖 Caffeine 命中、Redis 命中、两实例 Pub/Sub 失效、通知丢失后的 Redis/本地 TTL 回源，以及合法药品目录可读；仅在隔离 `npc-verify-cache-*` 项目运行，不改 MySQL 业务行。
- 本机执行 `python3 -m py_compile deploy/verify/cache-invalidation.py`、`--help`、`bash -n deploy/verify/*.sh` 和 `git diff --check` 均通过。脚本尚未连接 Docker/Redis/MySQL 执行，不能记为缓存验证通过；Maven Java 测试仍未运行。


## 2026-11-16：本轮 OCR 与本机工具链复核

本轮执行 `python3 -m unittest discover -s services/ocr/tests -v`：13 项通过、0 失败，覆盖文本规则和合成样例、显式分隔表格、文本 PDF 解析规则及扫描 PDF 渲染预算替身。部署/OCR Python `compileall`、部署 Shell `bash -n`、三个前端 JS `node --check` 和 `git diff --check` 通过。以上不证明 PaddleOCR 实际图像推理、真实 PDF 渲染或 Java 编译/测试通过。

本轮环境实际没有 `java`、`mvn` 或 Docker。按交接注记尝试运行 bootstrap，但因其日志路径位于只读工作区外而未能启动安装；本轮唯一一次 Maven 调用以 `mvn: command not found` 结束，未编译任何 Java。没有 Docker/Compose 可执行全栈、压测或故障注入脚本。Java 测试运行数仍为 0；预约负载实测请求数 0，QPS/延迟未测；真实 PaddleOCR 图像样本和经营助手真实模型评估均为 0。上述所有部署验证仍待服务器环境实跑，不得记作通过。

## Kafka Offset 中断验证入口（2026-11-16）

新增 `deploy/verify/kafka-offset-interruption.py` 和专用 Compose override。override 将消费者暂停在 MySQL 订单事务完成后、Redis 投影及手动 ACK 前；脚本确认 MySQL 已提交后 SIGKILL 隔离 backend，重启后等待重投，并断言订单仍恰为一笔、请求状态成功、Redis 预约投影完成。生产配置中的暂停属性默认关闭。脚本仅接受 `npc-offset-*` 项目并会中断其 backend；需使用可销毁的隔离数据卷。

本机执行 `py_compile` 与脚本 `--help` 通过；OCR/PDF 规则单测 13 项通过；部署/OCR `compileall`、所有部署 Shell `bash -n`、前端 JS `node --check`、`git diff --check` 通过。Docker 不可用，Kafka Offset 中断故障注入没有真实运行，不能记为通过。Java 修改尚未编译：测试暂停点加在 `PharmacyOrderConsumer` 的 MySQL 服务调用返回之后。须在 Maven 构建全绿及服务器隔离 Compose 实跑后回填真实日志和报告。
# 本轮补充：OCR 评估器人工修正 CSV 回归

- 修复 `deploy/verify/ocr-evaluate.py` 的人工修正 CSV 分支：建立 gold 样本索引，并从 `csv.DictReader` 读取列名。此前该路径分别会因 `gold_by_id` 未定义及错误读取 `fieldnames` 而异常退出。
- 新增 `deploy/verify/tests/test_ocr_evaluate.py`，以 20 行临时 gold、SKU 目录及一条修正记录运行评估入口（OCR HTTP 响应由测试替身提供），断言单据修正率 5%、字段修正率 1%。该测试只验证评估器统计/CSV 路径，不代表 OCR 推理或字段准确率。
- 实测：验证器单测 1 项通过；OCR 规则单测 13 项通过；部署/OCR Python `compileall`、部署 shell `bash -n`、三个前端脚本 `node --check`、`git diff --check` 均通过。
- 本轮环境中 `java`、`mvn`、`docker` 均不存在；未尝试 Maven、Compose、负载、故障注入、PaddleOCR 图像推理或真实模型评估。对应指标仍为未测，Java 测试运行数仍为 0。

## 2026-11-16：本机回归与敏感文件忽略规则复核

- OCR 提取层 `python3 -m unittest discover -s services/ocr/tests -v`：13 项通过。部署验证器 `python3 -m unittest discover -s deploy/verify/tests -v`：6 项通过；其中 OCR 评估器使用 HTTP 替身，输出不代表真实 OCR 图像准确率。
- `python3 -m compileall -q deploy/verify services/ocr`、全部部署 Shell 脚本 `bash -n`、三个工作台 JS `node --check`、`git diff --check` 通过。
- `.gitignore` 增加 `.env.*`（保留 `.env.example`）、密钥/证书扩展名、SQLite/DB 文件及 `deploy/app.jar` 规则；`git check-ignore` 确认这些路径和 `.handoff/` 均被忽略。
- Java 21/Maven 3.8.7 可见；本轮 Maven 已按上限尝试一次，因本机 Maven 仓库路径不可写而未进入编译，Java 测试运行数为 0。Docker CLI 可用，但 daemon socket 不存在；Compose 全栈、故障注入、压测、PaddleOCR 图像推理和真实模型评估未运行。
- 本轮指标仍为预约负载请求数 0、QPS/延迟未测、真实图像 OCR 样本数 0、字段准确率/人工修正率/峰值未测、真实经营助手评估数 0。部署验收项仍未通过或未验证。

## 2026-11-16：环境复核与 PDF 渲染资源清理

本轮唯一一次本机 Maven `clean verify` 指定可写仓库 `/tmp/npc-maven-repository`，但 Spring Boot parent POM 下载仍因 Maven Central TLS 代理错误 `Unsupported or unrecognized SSL message` 失败；未进入 Java 编译，Java 测试运行数为 0。本机 Docker 29.1.3 客户端可用，但 daemon 无法连接 `/var/run/docker.sock`。全栈、压测、Kafka/Redis/MySQL 故障注入、真实 PaddleOCR 推理和真实模型评估均未执行。

修复扫描 PDF 渲染器的异常路径资源清理，确保 PDF 页数等校验失败时也关闭 PyMuPDF document，并增加回归断言。本轮实跑 OCR 规则/PDF 单测 13 项通过，验证器单测 6 项通过；部署/OCR Python `compileall`、验证 Shell `bash -n`、三个前端 JS `node --check`、`git diff --check` 通过。OCR/PDF 的本机测试使用合成文本或渲染器替身，不代表真实图像 OCR 准确率或资源峰值。

实测业务指标仍为：预约负载请求数 0、吞吐与延迟未测；真实 OCR 图像/PDF 样本数 0、字段/SKU 准确率和人工修正率未测；真实经营助手评估数 0。服务器 `mvn clean verify`、V1–V8 全栈及 Swagger/业务冒烟，以及所有原始需求中的压测、故障注入和 AI 实测仍是严格验收待办。

## 2026-11-16：Maven TLS 阻断与验证器回归复核

本轮按限制仅执行一次 `mvn -B -ntp -Dmaven.repo.local=/tmp/neighbor-pharmacy-m2 -f AI-dianping-backend/pom.xml clean verify`。Maven 无法从 Maven Central 下载 Spring Boot 3.5.6 parent POM，报 `Unsupported or unrecognized SSL message`；项目未进入编译，Java 测试运行数仍为 0。该结果是环境阻断，不是构建通过。

本轮重新运行 OCR 文本/PDF 规则单测 13 项和部署验证器单测 6 项，均通过；后者包括 OCR 评估器的替身 HTTP 响应，不代表图像推理/准确率。Python `compileall`、全部验证 Shell `bash -n`、三个工作台 JavaScript `node --check`、`git diff --check` 通过。Docker CLI 可用但 daemon socket 不存在，服务器 Compose、压测及故障注入均未运行。真实 PaddleOCR 图像样本数、预约压测请求数、真实经营助手评估数均为 0；吞吐、延迟、OCR 准确率/修正率/资源峰值仍未测。
# 2026-11-17 回炉进展

- 核对 `.handoff/BLOCKED.md` 最新记录：CacheClient 的该失败用例已在构建⑥ 26/26 通过；按最新回炉指示未重复修改生产或测试代码。本机按本轮明确要求执行了一次定向 Maven 测试尝试，但 Spring Boot 3.5.6 parent POM 在解析阶段被 Maven Central TLS 代理阻断（`Unsupported or unrecognized SSL message`），测试没有启动。
- 排查经营助手评估夹具：原 `db/demo/005-pharmacy-mvp-demo.sql` 只准备单店/店主，没有启用药品、库存批次或销售流水，与评估日志中空店回复相符。新增 `db/demo/006-ai-evaluation-fixtures.sql`，供隔离评估库导入双店、双负责人、药品/批次及销售流水；更新服务器步骤明确登录拿双店 token 和租户 gold 断言。SQL 尚未在 MySQL 执行，真实模型 20 条评估未运行。
- Docker daemon 不可用（`/var/run/docker.sock` 不存在），本轮不能重跑服务器 Compose 验证电池。00:41 节的注册/压测、Kafka OOM、双实例缓存、脚本和真实模型项目仍以服务器复验结果为准。

# 2026-11-17：经营助手参数、补货和金标评估增强

补货工具过去接收 `targetDays`，但数量仍固定使用 21 天建议值；现按 `ceil(近 30 日日均销量 × targetDays) - 可用库存` 计算，并对无销售流水的启用商品明确返回“无法计算”，避免遗漏。新增 `PharmacyOperationsServiceTest` 覆盖不同目标天数和非负下限。聊天响应附带实际工具参数，20 案评估器增加参数断言，并新增双店合成金标检查商品、库存/补货量及跨店禁止标记。

本机部署验证器 Python 全套 14/14 通过，Gold JSON、Python 编译、前端 JS 语法和 `git diff --check` 通过。Maven 定向尝试未解析 Spring Boot 3.5.6 parent POM，因 Maven Central TLS 代理 `Unsupported or unrecognized SSL message` 退出；Java 编译/测试本机运行数为 0。真实模型评估为 0/20，服务器 AI 工具参数、门店隔离、答案数据准确性及超时降级仍未测；不得把 Python mock 测试或金标静态文件视作模型评估结果。

# 本轮：CacheClient 构建⑤定向 Mockito 仍未启动

- 执行一次 `CacheClientTest#usesBoundedLocalValueAndPublishesCrossInstanceInvalidation` 定向 Maven 命令。Maven 下载 Spring Boot 3.5.6 parent POM 时被出口代理 `Unsupported or unrecognized SSL message` 阻断；项目未完成解析，Java 编译与测试均未启动，执行数 0。
- 目标实现和状态化 mock 当前包含缺失版本键按 0、CAS 写入、成功后记录本地水位、set 后本地命中及版本复核断言。由于测试没有运行，本轮不能确认这些路径通过；没有新增 Surefire 栈支持额外改码。
- 下一步：在 Maven 依赖可用的 JDK 17 环境运行目标用例；如失败按 Surefire 堆栈修复，再执行全量 `deploy/verify/build-test.sh --docker`。本条记录不代表 CacheClient 验收通过。


## 2026-11-18：CacheClient 构建⑤定向复验再次受阻

- 本轮按回炉要求尝试运行 `CacheClientTest#usesBoundedLocalValueAndPublishesCrossInstanceInvalidation`。Maven 在解析 Spring Boot 3.5.6 parent POM 时遭出口代理错误 `Unsupported or unrecognized SSL message`，项目未解析，编译和 Mockito 均未启动（0 项）。
- 当前状态化 Redis/Lua mock 和缺失版本键独立回归用例已在源码中，但本轮无法提供 Java 执行证据，不能将其记为通过。必须在 Maven/JDK 17 依赖可用环境复验；若失败，按 Surefire 堆栈修复。

# 2026-11-18：CacheClient 构建⑤回炉证据复核

- 原始失败为 `set()` 后目标用例期望 `{"name":"test"}`、实际得到 `null`。当前测试使用状态化 Redis/Lua mock，明确覆盖缺失版本键按 0、CAS 写入 JSON、删除递增；生产成功 CAS 后写本地版本水位并填充 Caffeine。关键写后命中及不读 Redis 数据键断言仍保留。
- 后续服务器 `server-build-20261116-2341.log` 实测 CacheClient 6/6、全量 26/26，`clean verify` 为 `BUILD SUCCESS`。
- 本轮本机唯一一次定向 Maven 执行在下载 Spring Boot 3.5.6 parent POM 时因 `Unsupported or unrecognized SSL message` 失败，项目未解析，Mockito 未启动，Java 执行 0 项。本机结果不是通过证据；本轮无新的 Java 失败栈。
# 2026-11-18：CacheClient 构建⑤回炉复核

- 当前工作区的状态化 Redis/Lua mock 与 CAS 后本地缓存处理已对应历史 `set()` 后 `get()` 返回 null 的问题；已有服务器日志 `server-build-20261116-2341.log` 记录 CacheClient 6/6、全套 26/26 和 `BUILD SUCCESS`。这是服务器实测证据。
- 本轮定向 Maven 命令因默认仓库 `/root/.m2/repository` 无法创建，在 Maven 项目解析前失败；编译和 Mockito 未启动，Java 测试执行 0 项。本轮不能记作本机通过，也未重试。
- 监督方已确认其余全栈/压测/故障注入/双实例缓存/OCR/经营助手真实评估转服务器收尾阶段执行；各项入口和真实通过条件见 `.handoff/DONE`、`docs/SERVER_TEST.md`。

## Run 4 回炉：缓存读路径探针修正（服务器待复跑）

- 复核双实例缓存脚本时发现原探针先让两个 backend 都缓存 Redis 哨兵 A，再将 Redis 改为 B，却要求 backend B 返回 B；这与本地缓存语义冲突，会令健康实例也因探针顺序被判失败。现改成只预热 backend A，再改写 Redis；A 应返回本地旧值，尚未访问过该键的 backend B 应首次从 Redis 返回新值。新增回归用例检查访问顺序及拒绝 B 返回旧本地值。
- 外置收尾电池补丁增加 `p_cache()` 异常清理前采集 backend/frontend/mysql/redis/kafka 日志的逻辑，避免 `/user/code` 或登录失败时仅在拆栈后留下泛化错误。此前已有首次 Compose 启动双 backend 修订。
- Run 4 四组 Python 回归测试 39/39 通过；补丁对 `/tmp` 电池驱动副本 dry-run、应用和 `py_compile` 通过。`docker info` 显示本机 Docker CLI 可用但 daemon socket 不可连接，因此没有运行 Compose 缓存栈、压测、全栈烟雾或真实模型。
- 这修复了缓存验证器自身的错误断言并补齐失败诊断，但不证明旧 502 的服务端根因已消除。需服务器应用最新 `battery-driver-run4.patch`，复跑 02→03、01、06、07；检查压测 FAILED 码/库存对账、登录原始请求、双 backend 日志及 AI 20 案 JSON。没有新服务器全绿 summary，Run 4 仍未解除。


# 2026-11-17：CacheClient 构建⑤目标用例复现尝试

- 本轮定向 Maven 测试在解析 Spring Boot 3.5.6 parent POM 时遭出口代理错误 `Unsupported or unrecognized SSL message`；项目未解析，编译和 Mockito 未启动，测试执行 0 项。
- 当前目标用例使用状态化 Redis/Lua mock，覆盖缺失版本键按 0、CAS 写入 JSON、成功后本地水位/Caffeine 值及写后本地命中；本轮没有新的 Surefire 栈支持进一步改动。历史服务器 `clean verify` 通过记录仍作为既有证据，不计成本轮复验。
# 2026-11-17：Run 6 Mockito varargs 构建失败项修正（Java 待服务器复验）

- 针对服务器构建报告 `PharmacyOperationsServiceTest.inventoryRisksBindsExpiryWindowBeforeStoreId` 把 `Integer` 转成 `Object[]` 的错误，测试现在按 `JdbcTemplate.query(sql, mapper, arg1, arg2)` 固定双参数重载分别读取并断言绑定参数。该 Java 用例尚未执行，本机 Maven 禁止重试；需服务器 Maven 容器目标用例和全量 `clean verify` 通过后才能解除。
- Run 4 对账/鉴权/缓存/经营助手验证本轮定向 Python 单测 32/32 通过；`full-stack-smoke.sh` `bash -n`、`battery-driver-run4.patch` 应用到 `/tmp` driver 副本后的 `py_compile`、`git diff --check` 通过。driver 补丁只在临时副本验证，没有改写服务器外部文件。
- 本轮没有服务器全栈、压测、缓存双实例或真实模型执行证据；历史 Run 5 错误码结果及 11/20 AI 结果仍是失败证据，不能标成当前通过。

