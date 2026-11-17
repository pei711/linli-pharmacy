# 缓存设计

## 已实现部分

原 `CacheClient` 使用 Redis 共享缓存，并新增有界 Caffeine 本地缓存（最多 10,000 项、最长 30 秒本地 TTL）。本地失效通过 Redis Pub/Sub 广播。布隆过滤器初始化尚未完成或失败时采用 fail-open，避免误把有效门店判为不存在；新增门店会加入过滤器。

布隆过滤器目前只在旧门店详情的 `queryWithPassThroughAndMutex` 调用路径中检查门店 ID。药房商品和活动目录走 `CacheClient.get`/`set`，不会使用门店布隆过滤器判断商品或活动 ID 是否存在；因此门店过滤器不会负向拦截合法药品。此结论来自代码路径审阅，仍需服务器全栈请求验证。

药房药品和活动目录已接入该缓存，键按门店隔离。Redis TTL 随机为 15–30 秒；启动时按已发布活动门店预热，最多 50 个门店。商品创建、批次入库、活动发布、预约建单、取消、核销以及订单/活动超时释放会在数据库事务提交后失效相应目录。

## 一致性与边界

失效通过 Lua 原子递增 Redis 版本并删除旧值；填充值通过 Lua 比较版本后写入。读路径在 Redis 读取前后检查版本，避免并发失效期间把旧数据写入 Redis 或本地缓存，版本发生变化时也不会把刚读到的旧值返回给调用方。跨实例失效通知包含版本号并清除本地值。Pub/Sub 不持久化，实例断线可能错过通知，因此 Redis 随机 TTL 与本地短 TTL 提供最终收敛兜底。

MySQL 是药品、批次和库存的权威来源。库存扣减与回补必须走数据库事务和库存流水，不能以缓存值作为最终库存凭据。

## 验证范围

当前开发机因 Maven TLS 环境未运行 Java 测试，Docker daemon 不可用，版本保护及多实例失效尚未在 Redis 环境并发验证。单测代码覆盖 Redis 命中不回源、本地命中、Pub/Sub 失效和失效与直接 Redis 读取竞争时拒绝旧值；这些测试仍须在服务器 Maven 构建中实际运行。相关测试列入 `.handoff/PROGRESS.md` 的部署环境清单；不能将代码实现视为并发验证通过。

`deploy/verify/cache-invalidation.py` 提供两 backend 实例的集成入口：用合成目录 JSON 分辨 Caffeine 与 Redis 命中，发送版本化 Pub/Sub 失效，再模拟通知丢失并验证本地/Redis TTL 后 MySQL 回源；并请求一条 MySQL 中存在的合法商品，断言目录读路径可返回。它需要隔离 Compose 项目和 `cache-invalidation.compose.override.yml`，尚未实际运行，不构成验证通过。
