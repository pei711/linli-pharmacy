# 原项目复用审计

审计基线：`e2520be`（`codex/kafka-chat`）。以下是代码现状和改造方向，不代表药房功能已实现。

| 能力 | 真实代码 | 现状及复用依据 |
|---|---|---|
| 登录和身份 | `AI-dianping-backend/src/main/java/com/hmdp/service/impl/UserServiceImpl.java`、`interceptor/RefreshTokenInterceptor.java`、`utils/UserHolder.java` | UUID token 存 Redis，拦截器恢复当前用户；不是 JWT。可复用，但需增加店铺授权及角色边界。 |
| 门店和 GEO | `service/impl/ShopServiceImpl.java`、`config/ShopGeoInitializer.java`、`entity/Shop.java` | 已按店铺类型写入和查询 Redis GEO；需增加药房业务属性与半径约束。 |
| 缓存 | `utils/CacheClient.java`、`config/BloomFilter/BloomFilterInitializer.java` | Guava 本地缓存、Redis、空值、互斥重建和随机 TTL；过滤器只初始化店铺，不能直接用来判断药品。现有代码没有跨实例失效和版本防旧回填。 |
| 优惠券秒杀 | `src/main/resources/seckill.lua`、`service/impl/VoucherOrderServiceImpl.java` | Lua 原子检查库存、限购并 XADD Stream；新链路须改造脚本，不能同时发送 Stream 和 Kafka。原处理器忽略 `createVoucherOrder` 的 false 结果仍 ACK，不能作为可靠订单实现。启动时直接覆盖 Redis 库存也不适用于新预约。 |
| 订单与预约 | `entity/VoucherOrder.java`、`service/impl/ReservationServiceImpl.java`、`db/mysql/001-schema.sql` | 已有优惠券订单、门店预约和优惠券用户唯一索引，业务语义不同于药品批次预留；可借鉴认证、服务和持久化结构。 |
| Kafka | `service/ChatMessageService.java`、`websocket/ChatMessageConsumer.java`、`db/mysql/003-chat.sql` | 消息生产、两个聊天 topic 和消费者、消息 ID 唯一约束已存在；订单 Outbox、手动 offset、重试和 DLT 尚未实现。聊天 Kafka 事务不等于 MySQL 跨系统事务。 |
| AI | `service/ConsultantService.java`、`config/CommonConfig.java`、`utils/RedisChatMemoryStore.java`、`tools/ShopTool.java` | LangChain4j AI Service、Tool Calling、Redis 会话记忆已存在，可扩展只读药房 Tool；需验证异步调用的用户上下文与门店隔离。 |
| MCP | `mcp/config/McpServerConfig.java`、`McpToolsConfig.java`、`McpApiTokenFilter.java` | 已有受 token 保护的 MCP 查询入口，可保留；不能因此认为药房 Agent 或授权已完成。 |
| 公共层 | `dto/Result.java`、`config/WebExceptionAdvice.java`、`config/MvcConfig.java` | 已有统一响应、异常处理和拦截器，可沿用并审计新增 API。 |
| 上传 | `controller/UploadController.java` | 已有文件上传入口，但不能直接认定适合敏感单据；需要文件类型、大小、鉴权和安全存储设计。 |

技术基线为 Spring Boot 3.5.6、Java 17、MyBatis-Plus、MySQL、Redis、Redisson 和 Spring Kafka，见 `AI-dianping-backend/pom.xml` 和 `src/main/resources/application.yml`。未发现 Flyway、Caffeine、Spring Security/JWT、SpringDoc、PaddleOCR 或 Testcontainers 依赖。数据库初始化脚本位于 `db/mysql/`，已有数据卷不会自动重跑。原项目 `LICENSE` 为 MIT，`UPSTREAM.md` 记录上游来源与权利声明，必须保留。
