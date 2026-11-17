# 服务器隔离测试记录

2026-11-16 部署 `feature/pharmacy-platform` 分支的门店工作台 MVP，包括商品创建、批次入库、库存列表和 FEFO 顺序查询。服务器目录 `/opt/neighbor-pharmacy-cloud`，Compose 项目名 `neighbor-pharmacy-test`，数据库、Redis、Kafka 使用专属数据卷；没有使用或复制本地 `.env`，服务器生成了独立随机数据库密码并在调试过程中轮换。

`deploy/server-test.compose.yml` 限制各容器内存和 CPU，当前容器硬上限合计 1,392 MiB，容器预算上限 1,600 MiB，主机用量达到 1,898 MiB 时停止后续操作。入口仅监听服务器 `127.0.0.1:18080`，通过 SSH 隧道访问。2026-11-16 复核时微信机器人 `xmu-wechatbot.service` 为 `active`。首次 Kafka 命名卷以 root 属主创建，Kafka 镜像使用 UID 1000 启动而报权限错误；将专属卷属主改为 1000 后成功启动。该 Compose 文件在新的空卷上仍需相应初始化权限。

本次验证：五个容器运行，MySQL、Redis、Kafka 健康检查通过；工作台页面 HTTP 200。未登录请求 API 根路径返回 HTTP 401，符合认证要求。门店演示账号 `19900000001` 登录成功，创建了标注为非真实销售的演示商品、登记 20 件演示批次、查询库存并得到 FEFO 查询结果。种子 SQL 显式使用 `SET NAMES utf8mb4`；重新写入并登录后，店主昵称“邻药云演示店主”、页面标题、中文导航和表单在浏览器显示正常，Nginx 返回 `text/html; charset=utf-8`。演示验证码仅在隔离服务器环境开启，默认配置关闭。未验证秒杀压测或生产级业务。模型密钥未配置，AI 调用不可用。机器重启后容器没有设置自动重启，SSH 隧道也需重新建立。

本机隧道断开后可在 PowerShell 中运行：

```powershell
ssh -N -L 127.0.0.1:18081:127.0.0.1:18080 root@111.230.167.35
```

保持该窗口运行，再访问 `http://127.0.0.1:18081/`。原项目上传及删除接口未鉴权，不应直接将该环境暴露公网。单节点 Kafka 及资源限制只适合演示和开发测试，不证明生产可靠性；长时间运行和高负载对其他服务的影响尚未验证。

## 预约压测脚本

`deploy/load-test-reservations.py` 是标准库并发请求驱动器，默认准备 1,000 名用户、最多 200 个并发请求。输入 UTF-8 CSV 的 `token` 列放隔离测试账号登录后取得的 token；可选 `ip` 列用于按测试来源分配客户端 IP。脚本不会输出 token，只在报告中保存目标地址、HTTP/业务状态计数和延迟分位数。报告默认写入当前目录的 `reservation-load-report.json`，运行前应确认该目录不对外共享。

示例（仅限隔离测试栈；需要 1,000 个不同用户的有效 token，活动应配置 100 件库存）：

```bash
python3 deploy/load-test-reservations.py \
  --base-url http://127.0.0.1:18080 \
  --activity-id 123 \
  --tokens-csv /secure/path/reservation-users.csv \
  --users 1000 --concurrency 200 \
  --output /secure/path/reservation-load-report.json
```

非本机回环地址会被拒绝，只有显式加 `--allow-remote-test-target` 才允许发送请求。该脚本目前尚未对真实 Compose 环境执行；请求状态统计不能替代压测后 MySQL 订单、活动库存、批次库存与库存流水的核对，正式指标须和数据库对账结果一并记录。

### 压测后预约和库存对账

等待活动下 `INITIALIZING`/`PROCESSING` 请求归零后，在运行该隔离栈的目录执行以下命令。对账器只读取指定活动，检查订单总数上限（含后续已取消订单）、每个用户最多一单、无挂起请求、库存余额约束、订单创建时所分配批次仍未过期、FEFO 分配顺序、活动批次汇总，以及 Redis 活动库存与 MySQL 剩余库存一致。它不清理测试数据；测试栈销毁前先保存 JSON 报告。

```bash
python3 deploy/verify/reconcile-reservations.py \
  --project neighbor-pharmacy-test --activity-id 123 --max-success-orders 100 \
  --expected-activity-stock 100 --expected-request-count 1000 \
  --output /secure/path/reservation-reconciliation.json
```

验证通过条件是命令退出码 0 且报告中所有 `assertions` 为 `true`。对账器会断言活动 `total_qty` 恰为 100、默认 1,000 条请求全部落入终态、成功订单不超过上限、成功请求数与订单数一致、每用户至多一单、失败错误码分布完整且失败仅由 `SOLD_OUT` 解释、批次库存非负且账面平衡、无过期订单批次分配或仍可预约的过期活动批次、批次分配符合 FEFO、活动批次汇总与活动余量一致，并核对 Redis 与 MySQL 余量。`--expected-activity-stock` 和 `--expected-request-count` 可用于其他明确配置的测试规模。项目名只接受 `neighbor-pharmacy-test` 或 `npc-*` 隔离项目。该对账器需要在对应 Compose 项目可访问的主机上运行，检查结果尚未实测。

### Kafka 同一预约重复投递十次

先在隔离测试栈中准备一个已被 Redis 接受、MySQL 状态为 `PROCESSING` 的预约，并记录 `request_id`。脚本向 `pharmacy-seckill-requested` 投递同一个事件十次，然后等待消费者完成并查询 MySQL，断言请求已成功且 `pharmacy_order` 恰好一行。脚本只会向指定的 Compose 项目发送 Kafka 消息；它不删除业务数据，保留单笔订单和十条消息作为审计证据。运行结束后保存 JSON 输出；清理时销毁隔离测试项目及其数据卷，不要对共享或生产项目运行。

```bash
python3 deploy/verify/replay-kafka-request.py \
  --project neighbor-pharmacy-test --request-id 00000000-0000-4000-8000-000000000001
```

该脚本未在本机 Docker 环境执行。它验证重复事件的数据库幂等结果，不覆盖 MySQL 提交后 Offset 中断、消费者持续失败进入 DLT、DLT 重投或 Outbox Relay 崩溃恢复；这些仍需单独故障注入和真实运行记录。

### Outbox Relay 崩溃租约恢复

使用一条已成功建单且 Outbox 事件为 `SENT` 的隔离预约。脚本将事件改为 Relay 进程崩溃后可能遗留的过期 `CLAIMED` 租约，重启后端，等待 Relay 重新领取、发布并标记 `SENT`；重复 Kafka 事件应保持原有唯一订单。它模拟租约过期恢复，不模拟 Kafka 已确认而 Relay 在标记 `SENT` 前被精确杀死的瞬间，也不覆盖 Offset 中断或 DLT 重投。结果可保存为 JSON；该动作会重启指定项目的 backend，且会在 Kafka 中再次发布该预约事件，只能对隔离测试项目运行。

```bash
python3 deploy/verify/recover-stale-outbox.py \
  --project neighbor-pharmacy-test --request-id 00000000-0000-4000-8000-000000000001 \
  --output /secure/path/outbox-recovery.json
```

通过条件是命令退出码 0，且事件恢复为 `SENT`、请求仍为 `SUCCESS`、订单仍恰好一笔、领取锁已清除。脚本会留下最终 `SENT` 状态作为恢复结果，不需要手动清理；隔离测试栈销毁时再清理其数据卷。当前尚未在 Docker 环境运行。

### Kafka 消费失败进入 DLT 与修复后重放

此脚本向预约 topic 投递一条缺少有效 `requestId` 的隔离 poison 消息，等待 Spring Kafka 重试并进入 `.DLT`，按投递前后的 partition offset 读取新增消息并核对唯一测试 key 与原始无效 payload。随后把该 payload 修复为一个已成功请求的事件并重新投递到源 topic，断言原请求仍只有一张订单。原始 DLT 记录保留供审计；脚本不清理消息，也不修改业务订单。

```bash
python3 deploy/verify/kafka-dlt-replay.py \
  --project neighbor-pharmacy-test --request-id 00000000-0000-4000-8000-000000000001 \
  --output /secure/path/kafka-dlt-replay.json
```

该测试证明消费者异常可进入 DLT，以及人工修复后重放不会重复建单；它不覆盖 MySQL 提交后 Kafka offset 中断的精确崩溃窗口。必须使用隔离项目和一个已成功、恰有一张订单的请求 ID。通过条件为退出码 0 且 JSON 的 `passed` 为 `true`。当前脚本仅做了本机静态检查，尚未在 Kafka 环境运行。

### Redis 预约与超时返还 Lua 自检

此自检直接执行应用资源中的预约和超时释放 Lua，使用带唯一后缀的测试键，退出时清理这些键和容器内临时脚本。断言包括成功预扣、同一请求幂等、同一用户限购、售罄不继续扣减、超时返还一次，以及返还后不影响其他用户标记。它只验证 Redis 脚本，不验证 MySQL、Kafka、活动生命周期或 API。

```bash
bash deploy/verify/redis-reservation-lua.sh neighbor-pharmacy-test
```

成功退出码为 0 且 JSON 中 `passed` 为 `true`。当前开发机 Docker daemon 不可用，因此该自检尚未实际执行。

### 双实例药品目录缓存验证

先用只含测试数据的独立 Compose 项目启动两份 backend。覆盖文件降低 JVM 上限，使 5 个容器的硬内存预算仍不超过 1,600 MiB；该用例固定使用 127.0.0.1:18080，启动前确认端口空闲。`DB_PASSWORD` 只从服务器私密环境注入：

```bash
project=npc-verify-cache-20261116
files=(-f deploy/server-test.compose.yml -f deploy/verify/cache-invalidation.compose.override.yml)
DB_PASSWORD="$DB_PASSWORD" docker compose -p "$project" "${files[@]}" up -d --build --wait --scale backend=2

收尾电池驱动也必须在首次 `compose up` 时带 `--scale backend=2`，不要先启动单副本再扩容；后扩容可能重建 backend，而已启动的 Nginx 仍使用旧 upstream 地址。
python3 deploy/verify/cache-invalidation.py --project "$project" --store-id 900010 \
  --medicine-id 900011 --backend-port 8081 --output /secure/path/cache-invalidation.json
DB_PASSWORD="$DB_PASSWORD" docker compose -p "$project" "${files[@]}" down --volumes --remove-orphans
```

把门店与药品 ID 替换成该隔离库中真实、可见的合法商品。脚本拒绝非 `npc-verify-cache-*` 项目，并要求恰好两份 backend。后端默认内部端口来自 `application.yml` 的 8081，可用 `--backend-port` 覆盖。它逐实例等待 OpenAPI paths 就绪后，先经 Nginx 对此前报 502 的 `POST /user/code` 做路由探针；探针失败会在栈销毁前分别直连两份 backend 的同一路由，并输出各实例 HTTP/传输结果及 backend/frontend 容器日志，以区分 Nginx 代理与 Spring 实例故障。缓存验证 override 先启动 MySQL、Redis、Kafka 并导入 005 演示门店，再启动 Spring，确保 BloomFilterInitializer 快照包含该门店；随后经两个 backend 的 `/shop/900010` 实际查询合法门店，任一实例被 Bloom 过滤器误拦都会失败。该 override 也显式启用测试验证码回显。验证器从 MySQL 读取药品名作为权威结果，先只让实例 A 读取带探针名称的 Redis 值并填充本地 Caffeine，再改写 Redis 值：实例 A 必须继续返回本地旧值，尚未读取过该键的实例 B 必须首次从 Redis 返回新值。这样避免两个实例都预热旧值后错误期待其中一个绕过自己的本地缓存。之后通过版本递增和 Pub/Sub 消息检查两个实例是否都失效；最后故意不发失效通知，让 Redis 探针先过期，再等本地 TTL 到期，断言回源 MySQL 得到原药品名。脚本不写业务表；运行后应销毁专属项目和数据卷。只有 JSON 中全部 `checks` 为 `true` 且退出码为 0 才算通过。

Compose 的 `up --wait` 只在容器没有健康检查时确认容器处于运行状态，不代表 Spring 已接受请求。缓存验证器会逐一探测两份 backend 的 OpenAPI JSON，确认应用路径就绪后才请求目录接口；等待超时会附带最后错误及两份 backend 最近 80 行日志，便于区分启动慢、应用未就绪与代理 502。该探测只减少启动竞态，不替代完整容器诊断。

本机只完成脚本语法与帮助检查，Docker daemon 不可用，双实例缓存验证尚未执行。

### 取消、核销与活动结束释放

`order-lifecycle.py` 在隔离项目上对预置的 `RESERVED` 订单调用取消/核销 API，并从 MySQL 比较前后订单和批次状态；活动结束场景会直接把指定活动的 `end_at` 改为过去时间，等待生命周期任务释放剩余活动库存。脚本不创建测试订单，也不清理订单、活动或流水；它会改变提供的测试订单状态及活动结束时间。每次仅使用专属空卷和无并发流量的演示项目。

```bash
NPC_CANCEL_TOKEN="$NPC_CANCEL_TOKEN" \
NPC_OWNER_TOKEN="$NPC_OWNER_TOKEN" \
python3 deploy/verify/order-lifecycle.py \
  --project neighbor-pharmacy-test --base-url http://127.0.0.1:18080 \
  --cancel-order 00000000-0000-4000-8000-000000000001 \
  --pickup-order 00000000-0000-4000-8000-000000000002 \
  --end-activity 123 --output /secure/path/order-lifecycle.json
```

取消订单需属于 `NPC_CANCEL_TOKEN` 对应用户；核销需使用活动门店负责人 token。两个订单必须是不同的待核销 fixture，活动 ID 必须是另一个可结束的隔离活动。脚本仅接受 `neighbor-pharmacy-test` 或 `npc-verify-*` Compose 项目，以及 localhost/回环 HTTP 地址。脚本在查询前校验订单号字符集，并逐个 batch ID 对齐前后快照；断言订单分配量等于订单数量、取消/核销的预留及在库变化、对应库存流水总量。活动结束检查逐批次核对活动预留释放、活动余量归零和释放流水数量/总量。若只验证其中一项，只传对应参数。结束活动会永久修改测试活动时间；清理由销毁隔离 Compose 项目及其专属卷完成。此脚本不验证取消事件在 Redis 的最终投影，也不注入异步任务崩溃；Redis Lua 行为由前述 Lua 自检覆盖。当前仅运行本地断言单测，尚未在真实 Compose 环境执行，不能计为生命周期验证通过。

若生命周期检查失败，`--output` 仍会写入 `passed:false` JSON，包含已经完成的检查结果、失败步骤和错误详情；该文件应与服务器 Compose 日志一起归档。

### Redis 预扣后请求提交中断恢复

此故障注入在隔离栈的已发布活动上，使用未参与该活动的用户 ID 写入一条已过期 `INITIALIZING` 请求，再直接构造 Redis Lua 已接受后的库存、用户限购标记、预约 Hash 和 pending ZSet 状态。后端恢复任务应将 MySQL 请求转换为 `EXPIRED/RELEASED`，只返还一次 Redis 库存，并清除该用户标记。脚本验证 Redis 库存与注入前精确相等后移除自己的 Redis 预约/ZSet 探针键；MySQL 过期请求行保留用于审计。它模拟恢复任务可见的中断后持久化状态，不会实际关闭或损坏 MySQL。

```bash
bash deploy/verify/recover-expired-initializing-reservation.sh \
  neighbor-pharmacy-test 123 900001
```

只能在没有并发流量的隔离测试活动上运行；活动 Redis 库存必须大于零，用户 ID 必须未在该活动预约。成功退出码为 0 且输出 JSON 中所有 `checks` 为 `true`。超时会保留注入状态和 request ID，便于调查；确认后销毁隔离 Compose 项目及专属卷。此项仍未实际执行，不构成故障恢复通过证据。

超时失败同样输出结构化 JSON，列出 MySQL 状态/失败码、Redis 库存、用户限购标记、预约 Hash 状态与 pending ZSET 项；探针键和 MySQL 审计行保留以便诊断。失败后归档 JSON，再根据现场清理或销毁隔离项目。

### Redis 预扣后真实 MySQL 中断与补偿

该用例使用专属 `npc-db-failure-*` 项目与 override。测试属性在 Lua 预扣成功后暂停预约线程 25 秒，预约 TTL 降为 6 秒；脚本观察到 Redis reservation 后停止该隔离项目 MySQL 12 秒，再启动并等待恢复任务将过期 `INITIALIZING` 请求置为 `EXPIRED/RELEASED`，精确返还 Redis 库存并清除用户标记。属性默认关闭，不影响普通环境。

```bash
NPC_RESERVATION_TOKEN="$NPC_RESERVATION_TOKEN" \
bash deploy/verify/mysql-outage-after-redis.sh npc-db-failure-20261116 123
```

需要一条已发布、Redis 余量大于零的隔离活动，以及该活动尚未预约的用户 token。脚本只接受 `npc-db-failure-*` 项目；出错退出时会尝试重新启动 MySQL。成功后保留过期请求行作为审计，归档输出后销毁该专属项目及数据卷。通过条件是退出码 0 且 JSON 的全部检查为 `true`。当前尚未在 Docker/MySQL 环境运行。

## OCR PDF

OCR 服务现在允许上传最多 10 MiB、10 页的 `application/pdf`。部署验证应使用含可复制文本的合成送货单，确认 API 返回 `input_mode=embedded_pdf_text`、候选字段正确且 `requires_manual_review=true`；再使用独立扫描版 PDF 运行 PaddleOCR，自检脚本应返回 `input_mode=scanned_pdf_ocr` 和非空识别行。扫描 PDF 每页最多 20 MP、整份最多 40 MP，超过限制的文档应被拒绝。还需确认加密/损坏 PDF 被拒绝、超过 10 页返回 413、错误 MIME 被拒绝。嵌入文本 PDF 行置信度为 0（未评分）；扫描 PDF 行置信度来自 PaddleOCR。PyMuPDF 渲染、实际图像推理和 Java 上传代理仍需资源充足的部署环境验证。

OCR 服务（FastAPI）表格样例应另准备一份有明确分隔符的多商品文本/图像，表头包含药品名、规格、批号、生产日期、有效期、数量和采购单价，确认响应 `candidates` 数组逐行返回所有商品，旧 `candidate` 字段仍可读取首项。Java 代理应将所有候选和各自 SKU 建议保存在待审核单中，工作台逐行显示并要求每行人工核对；用 `/confirm-items` 一次提交全部行，断言所有批次/流水同时提交或全部回滚，并确认每文档只允许一次最终审核。服务端确认时必须校验规格与所选门店 SKU 一致，采购单价写入审核记录；Flyway V9 增加该审计字段。单行兼容 `/confirm` 也应保留回归检查。合并单元格和视觉对齐表格未实现；图像用例必须使用真实 PaddleOCR 推理并独立记录，不能把文本提取结果计作图像准确率。

## 部署验证脚本

以下脚本用于服务器隔离验证；它们本身不构成执行结果。使用前确认环境指向隔离测试栈，并从服务器私密环境提供 `DB_PASSWORD`。`full-stack-smoke.sh` 会创建唯一 Compose 项目和临时数据卷，退出时删除该项目与卷；不要将其指向任何有用数据的 Compose 项目。

若通过外部 `battery-driver.py` 编排验证电池，必须先运行仓库提供的准备脚本。它会先 dry-run 补丁，成功后才应用；随后检查压测 override、双 backend、006 夹具账号和数据签名预检，并解析 Python 源码。任何标记缺失或补丁不能干净应用都会停止，禁止启动电池：

```bash
bash deploy/verify/prepare-battery-driver.sh /path/to/dev-loop/battery-driver.py
```

准备脚本幂等：所有修复标记已存在时只验证并退出；补丁不能匹配时不会改驱动。压测栈临时将同一来源 IP 的限额提高到 2,000 次/秒，以便验证库存竞争而非 IP 限流；AI 评估在请求模型前校验两家门店、负责人各自唯一且正确的店主关联、3 个启用商品及其门店/类别、2 个批次及其门店/SKU、2 条近 30 日销量流水，以及 A/B 店库存分别为 8/4、销售流水分别为 30/12，合计签名应为 `2:2:2:3:3:2:2:2:8:4:30:12`。006 fixture 重复导入时也会修复药品和批次的门店/SKU 关联，并先移除两个专用合成账号残留的店主关联，再绑定评估门店。任一签名不匹配会在调用模型前中止，需检查并重建隔离评估数据库后再运行。全栈 smoke 失败时应保留真实失败命令和行号，不让清理阶段覆盖错误现场。这些修复尚需服务器重新运行电池验证。

```bash
# Maven 容器内运行全量 Java 构建与测试
deploy/verify/build-test.sh --docker

# 隔离 Compose 启动，验证 Flyway、OpenAPI JSON、Swagger HTML 和匿名预约认证
DB_PASSWORD="$DB_PASSWORD" deploy/verify/full-stack-smoke.sh

# 资源充足的独立主机上验证 PaddleOCR 图片或扫描版 PDF 推理；首次调用可能下载模型
deploy/verify/ocr-image-selfcheck.sh /secure/path/synthetic-supplier-document.jpg
deploy/verify/ocr-image-selfcheck.sh /secure/path/synthetic-scanned-document.pdf

# 对恰好 20 份真实图片/PDF 计算字段和 SKU 目录匹配准确率
python3 deploy/verify/ocr-evaluate.py --base-url http://127.0.0.1:18090 \
  --gold-csv /secure/path/ocr-gold.csv --sku-catalog /secure/path/sku-catalog.csv \
  --reviewed-csv /secure/path/manual-corrections.csv --output /secure/path/ocr-results.json
```

全栈冒烟会先验证 Flyway V1–V9、OpenAPI paths、Swagger HTML/JS 和匿名预约认证，再向临时项目导入 `db/demo/005-pharmacy-mvp-demo.sql` 的合成店主/门店数据，调用 `business-api-smoke.py`。该栈专用 Compose override 只在隔离烟雾容器启用 `APP_AUTH_EXPOSE_CODE`，以便脚本完成验证码登录；基础和生产配置仍默认关闭。Swagger UI 或 bundle 返回非 200 时，脚本会同时输出 Nginx 响应头/响应体、frontend 容器直连 Spring 的同一路径响应，以及 backend/frontend 日志，便于区分代理路由与应用鉴权/启动问题。业务冒烟先断言匿名 `/user/me` 返回 401，再走验证码、登录换 token、带 token 读取资料；随后执行建商品、入库、活动发布、Redis 预约、Kafka 建单、查单、店主核销和 FEFO 剩余库存断言。脚本最后销毁唯一 Compose 项目及数据卷；输出仅含合成 ID，不包含登录 token。只有 `full-stack-smoke.sh` 整体退出码为 0 才算该冒烟链路通过。当前开发机没有可连接的 Docker daemon，因此该链路尚未实跑。

Gold CSV 列为 `sample_id,path,medicine_name,specification,batch_no,production_date,expire_date,quantity,purchase_unit_price,sku`，SKU CSV 列为 `sku,medicine_name`。`path` 可相对 gold CSV 所在目录填写，可包含图片、嵌入文本 PDF 或扫描版 PDF 样本。要求恰好 20 行样本、非空且唯一的 `sample_id`，每行都有预期 SKU。药品名称、规格、批号、生产日期、有效期、数量、采购单价和 SKU 匹配准确率始终以全部 20 行为分母；上传失败按错误计入。采购单价按两位小数比较，其他字段按去空白文本比较。评估输出逐字段准确率、药品名目录映射的 SKU 命中率和人工修正率；没有 `--reviewed-csv` 时人工修正率为 `null`。这些脚本尚未执行；真实 PaddleOCR、字段准确率、人工修正率和资源峰值当前均未测。

`manual_corrections.csv` 必须有 `sample_id,field,value` 三列；重复字段、未知样本、空修正或与 OCR 原候选相同的值会被拒绝。报告分别给出 20 份样本中发生过修正的单据比例和 140 个字段槽位中发生修正的比例，并按 `image_ocr` / `embedded_pdf_text` / `scanned_pdf_ocr` 统计输入类型。扫描版 PDF 由 PaddleOCR 路径推理，不得把嵌入文本 PDF 统计为图像识别。

### 经营助手 20 条真实评估

在执行真实模型评估前，先对已运行 V1–V9 的隔离数据库导入 `db/demo/006-ai-evaluation-fixtures.sql`。它为 A、B 两店分别创建负责人、启用药品、库存批次和近 30 日销售流水；评估夹具使用门店 ID `990010`/`990020`、负责人手机号 `19900000101`/`19900000102`，与演示种子 `005` 的门店及手机号分开，避免同一负责人被解析到演示门店。用 `/api/user/code` 与 `/api/user/login` 取得两名负责人的 token，并仅放入当前 shell 环境。评估请求 OPS-01–14 使用 A 店 token；OPS-15 使用 B 店 token，以 B 店授权身份确认提示不能切换到 A 店；OPS-16/17 使用 A 店 token，OPS-17 必须拒绝披露 B 店数据，最小拒答无需额外披露 A 店库存。`--store-b-token-env` 和对应环境变量是必需项；缺少 B 店 token 时评估器会在发出模型请求前退出，避免 OPS-15 静默复用 A 店身份。使用仓库 `deploy/verify/ai-operations-gold.json` 检查夹具中的商品、库存/补货数及跨店禁止标记。脚本逐条请求 API，并断言 `LANGCHAIN4J_AGENT` 来源、工具名、实参及逐项回答金标；OPS-18/19 不调用经营工具并安全拒答，OPS-20 只调用当前授权门店的 `queryInventoryRisks` 并拒绝跨店导出。输出 JSON 保存回答、工具名、工具参数和延迟，不包含 token。

将 `$project` 和 `files` 设为下方 AI 评估 Compose 命令中的值后，在仓库根目录运行：

```bash
docker compose -p "$project" "${files[@]}" exec -T mysql \
  sh -c 'exec mysql -uhmdp -p"$MYSQL_PASSWORD" hmdp' \
  < db/demo/006-ai-evaluation-fixtures.sql
```

重复导入会更新两家门店的合成档案/库存；只允许在可销毁的评估数据库中执行。若从旧夹具运行过评估，先销毁并重建隔离数据库，避免已存在的订单或库存流水影响数量断言。

```bash
NPC_AI_EVAL_TOKEN="$NPC_AI_EVAL_TOKEN" python3 deploy/verify/ai-operations-evaluate.py \
  --store-b-token-env NPC_AI_EVAL_STORE_B_TOKEN \
  --gold-json deploy/verify/ai-operations-gold.json \
  --base-url http://127.0.0.1:18080 --output /secure/path/ai-operations-evaluation.json
```

真实模型评估必须用 `deploy/verify/ai-evaluation.compose.override.yml` 启动隔离后端。该 override 强制要求 `AI_API_KEY`，避免 Compose 未收到服务器私密环境变量时静默使用 `demo-key` 并只返回 `RULES_FALLBACK`。逐例报告保留失败断言、答案和受限长度的 HTTP/网络错误详情，不记录请求头或 token，便于按 OPS 编号定位模型、参数、数据或服务故障。先在服务器私密 shell 中载入 `AI_API_KEY`，再执行：

评估器每次运行会生成独立 run ID，并为每条用例传递唯一 `conversationId`。后端将它限定在当前已授权门店和登录用户的 Redis memory key 下，因此用例之间以及不同轮评估之间不会复用历史对话来影响工具选择。普通工作台请求不传该字段时继续使用用户默认会话；评估 JSON 会记录 run ID 以便定位日志。

```bash
project=npc-ai-eval-20261117
files=(-f deploy/server-test.compose.yml -f deploy/verify/ai-evaluation.compose.override.yml)
DB_PASSWORD="$DB_PASSWORD" AI_API_KEY="$AI_API_KEY" \
  docker compose -p "$project" "${files[@]}" up -d --build --wait
NPC_AI_EVAL_TOKEN="$NPC_AI_EVAL_TOKEN" \
  python3 deploy/verify/ai-operations-evaluate.py --store-b-token-env NPC_AI_EVAL_STORE_B_TOKEN \
  --gold-json deploy/verify/ai-operations-gold.json --base-url http://127.0.0.1:18080 \
  --output /secure/path/ai-operations-evaluation.json
DB_PASSWORD="$DB_PASSWORD" AI_API_KEY="$AI_API_KEY" \
  docker compose -p "$project" "${files[@]}" down --volumes --remove-orphans
```

不要把密钥写入命令文件或报告；从仅服务器可读的私密环境载入。测试 Compose Kafka JVM 堆已压到 192 MiB，并限制直接内存以降低低内存主机上的 OOM 风险；这项调整仍需服务器启动与电池实测确认。

Gold JSON 用 `contains` / `not_contains` 检查整段回答，用 `exact_number_after` 将数量绑定到商品名并按完整数值匹配，避免期望 8 时把 18 当作通过；`not_contains_after_anchor` 则只在指定商品名所在行检查禁用说法，避免某个商品的限制误伤对其他商品的正确回答。也可用 `tool_parameters` 覆盖每个案例的预期实参。库存风险默认检查 30 天；补货用例默认 21 天，但 OPS-09 明确询问未来 30 天（下月），因此预期参数为 30 天，合成夹具对应补货量为 22 件。用 `--expect-fallback` 检查前，必须先让隔离后端的模型端点超时/不可用；不得把单独的工具断言报告为全套经营助手验收通过。

### MySQL 提交后、Kafka Offset 确认前中断

此项必须使用专属 `npc-offset-*` Compose 项目。准备一个 Redis 已接受、数据库状态 `PROCESSING`、尚无订单的预约，并先确认该隔离项目后端及 Kafka 正常。Compose override 会将消费者暂停点设置为 60 秒：`createFromRequest` 的 MySQL 事务返回后、Redis 状态投影和手动 Kafka ACK 前暂停。脚本在数据库观察到 `SUCCESS` 且恰有一笔订单后，对 backend 容器发送 `SIGKILL`，再启动 backend，等待同一 Kafka 记录重投，最后断言 MySQL 仍只有一笔订单且 Redis 预约状态为 `SUCCESS`。

```bash
python3 deploy/verify/kafka-offset-interruption.py \
  --project npc-offset-20261116 --request-id 00000000-0000-4000-8000-000000000001 \
  --output /secure/path/kafka-offset-interruption.json
```

该动作会强杀并重建指定项目的 backend，订单与 Kafka 消息作为审计数据保留；报告归档后销毁该专属 Compose 项目及数据卷即可清理。脚本拒绝非 `npc-offset-*` 项目。通过条件是退出码 0，且 JSON 中 MySQL 单订单及 Redis 最终状态断言全部为 true。暂停属性默认是 0，仅此验证 override 打开；真实 Kafka/Compose 执行前，本项仍是未验证状态。
