# Neighbor Pharmacy Cloud — pharmacy reservation and operations platform

An incremental pharmacy platform based on `linli-zhiying`. The isolated test deployment currently includes nearby-pharmacy discovery, batch-backed OTC reservation, Kafka order processing, FEFO inventory, a Caffeine + Redis shop cache, an owner workspace, and a rule-based pharmacy operations assistant. OCR photo recognition is implemented as an optional FastAPI service with an audited human-confirmation path; real PaddleOCR inference has not yet been run on the 2 GiB test server.

[简体中文版](README.zh-CN.md)

## Implemented and verified

- User discovery of nearby stores, medicine catalogs, and published activities.
- Server-authenticated, request-idempotent Redis Lua reservations backed by MySQL inventory batches.
- Outbox-to-Kafka order creation, consumer idempotency, order status lookup, cancellation, and pickup confirmation.
- Additive Flyway migrations, FEFO batch locking, stock reservations, and inventory movements.
- Bounded Caffeine local cache with Redis, versioned invalidation, and Pub/Sub for legacy and pharmacy catalogs; Bloom initialization fails open.
- Store-owner inventory and catalog management, FEFO view, OCR candidate upload/review UI, and a read-only operations assistant.
- OCR candidate persistence, store-scoped SKU suggestions, reviewer correction audit, and transactional confirmation into batch inventory and `INBOUND` movement.

The synthetic demo account uses phone `19900000001` and store ID `900010`. The isolated test Compose stack can return a temporary demo verification code; this option is off by default. The production deployment is publicly available at http://111.230.167.35/ . The current tested server stack uses a 1,898 MiB host-use ceiling and 1,600 MiB aggregate container-cap budget.

The authenticated OCR upload/review/receipt path was integration-tested with an explicit mock OCR upstream; this does not measure OCR recognition accuracy. The OCR extraction/PDF rule suite has 15 tests, including 20 synthetic supplier-text cases, delimited multi-item tables, specification, and purchase price fields. Text-based PDFs can be parsed into review candidates; real PaddleOCR inference, image-field accuracy, and runtime memory peak remain unverified. The server verification battery passed 9/9 on 2026-11-18: full-stack smoke, the 1,000-user load test (100 successes / 900 sold out, full inventory reconciliation), Kafka fault injection (duplicate consumption, offset interruption, DLT replay, outbox recovery, order lifecycle), MySQL outage, cache invalidation, the AI operations evaluation (20/20 against the real model), and production restart. The operations assistant was verified on the production deployment answering with real model tool calls (`LANGCHAIN4J_AGENT`); `RULES_FALLBACK` remains only as the degradation path. Prescription medicines are not eligible for online reservations.

## Retained upstream features

- Browse local shops and categories, view shop details, and discover nearby shops.
- Explore posts, likes, follows, check-ins, coupons, and flash-sale orders.
- Create and review restaurant reservations while signed in.
- Generate review summaries, reply drafts, blog drafts, coupon copy, and short-video scripts with configurable AI Skills.
- Use the signed-in AI consultant, which can call the application's business tools. Actions such as reservation creation still run through the authenticated user flow.
- Start real-time customer-to-shop conversations from a shop page. Kafka `push-topic` handles live delivery while `store-topic` persists messages asynchronously, decoupling delivery from storage.
- Optionally expose read-only shop and voucher lookup tools over MCP. The MCP endpoint is disabled until `MCP_API_TOKEN` is set.

## Architecture

```text
Customer / pharmacy workspace -> Nginx -> Spring Boot modular monolith
                                           |-> MySQL 8 (inventory ledger, batches, orders, outbox, audit)
                                           |-> Redis 7 (Lua reservation, rate limits, shared cache)
                                           |-> Kafka (pharmacy order chain and retained chat topics)
                                           |-> FastAPI + PaddleOCR (optional; human-reviewed candidates)
                                           |-> OpenAI-compatible API (optional; read-only operations tools)
                                           |-> WebSocket / STOMP (retained real-time chat)
                                           `-> MCP HTTP endpoint (optional, bearer token)

Reservation: Redis Lua pre-reservation -> MySQL request + outbox -> Kafka -> idempotent consumer -> MySQL FEFO transaction.
```

## Quick start with Docker Compose

Requirements: Docker Desktop with Compose v2. An AI provider key is optional; without one, browsing features still work and AI calls will not return generated content.

```bash
cp .env.example .env
# Edit .env and set your DeepSeek API key to use chat and AI Skills.
docker compose up --build
```

Open <http://localhost:8080>. The API is proxied through the same origin. MySQL is available on `127.0.0.1:3307`; Redis is only exposed inside the Compose network.

The first startup initializes the schema and small, synthetic shop/category records. MySQL only runs files under `db/mysql` when its data volume is empty. To reset this demo database and its Redis data, run `docker compose down -v` and start it again.

## Run the backend without Docker

Use Java 17+, Maven 3.9+, MySQL 8+, Redis 7+, and Kafka. Create a database and apply these files in order:

```text
db/mysql/001-schema.sql
db/mysql/002-demo-data.sql
db/mysql/003-chat.sql
```

Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, and `KAFKA_BOOTSTRAP_SERVERS` as needed, then run:

```bash
cd AI-dianping-backend
mvn spring-boot:run
```

For AI features, also set `AI_BASE_URL`, `AI_API_KEY`, and `AI_MODEL_NAME`. `MCP_API_TOKEN` is optional; without it `/mcp` responds with HTTP 503. When enabled, use `Authorization: Bearer <token>`. The MCP tool set contains only read-only shop, category, and voucher lookups.

Instant messaging requires Kafka. A new Compose MySQL volume applies `db/mysql/003-chat.sql` automatically; apply that migration manually to an existing database. Map each shop to its merchant account in `tb_shop_owner`:

```sql
INSERT INTO tb_shop_owner (shop_id, user_id) VALUES (<shop_id>, <merchant_user_id>)
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);
```

Customers use “联系商家” on a shop detail page. The mapped shop owner can open `chat.html?shopId=<shop_id>` to reply. The STOMP CONNECT frame carries the login token in the `authorization` native header. A Kafka transaction writes each message to `push-topic` and `store-topic`; separate consumer groups handle WebSocket delivery and asynchronous MySQL persistence. The two Kafka writes are transactional, but Kafka and MySQL do not share a cross-system transaction.

## Tests

Run backend unit tests with:

```bash
cd AI-dianping-backend
mvn test
```

Tests are designed to run without a local MySQL or Redis service. The GitHub Actions workflow runs the same Maven test command on Java 17.

## Configuration

| Variable | Purpose | Default |
| --- | --- | --- |
| `DB_URL` | JDBC connection string | Local Compose MySQL on port 3307 |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials | `hmdp` / development-only password |
| `REDIS_HOST` / `REDIS_PORT` | Redis connection | `localhost:6379` outside Compose |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address | `kafka:19092` in Compose; `localhost:9092` for local runs |
| `KAFKA_PUSH_TOPIC` | Real-time delivery topic | `push-topic` |
| `KAFKA_STORE_TOPIC` | Asynchronous persistence topic | `store-topic` |
| `AI_BASE_URL` | OpenAI-compatible API base URL | `https://api.deepseek.com` |
| `AI_API_KEY` | DeepSeek API key | Placeholder in `.env.example`; replace it |
| `AI_MODEL_NAME` | Chat model | `deepseek-v4-flash` |
| `MCP_API_TOKEN` | Bearer token for standalone MCP endpoint | Empty means disabled |

Never commit `.env` or production credentials. Change the development database password before exposing a deployment beyond your machine.

## Project layout

- `AI-dianping-backend/`: Spring Boot API, business services, AI Skills, consultant tools, and MCP server.
- `AI-dianping-frontend/html/hmdp/`: static Vue frontend.
- `AI-dianping-frontend/conf/nginx.conf`: local/Compose Nginx configuration.
- `db/mysql/`: schema and synthetic demo data; no imported user accounts, phone numbers, or review records.

## Current limits

- SMS login delivery requires an SMS provider configuration; the repository does not include one.
- AI chat and generation require a valid compatible provider key.
- The Compose Kafka broker is a single-node development setup without production replication; shop owners must be provisioned in `tb_shop_owner`.
- The project has no verified benchmark artifact for the performance percentages sometimes quoted in project descriptions. No such performance claim is made here.
- This is a learning/demo project. Review authentication, rate limiting, storage, and deployment settings before production use.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup and pull request guidance, and [SECURITY.md](SECURITY.md) for vulnerability reports. See [UPSTREAM.md](UPSTREAM.md) for source attribution. This repository is licensed under the MIT License; see [LICENSE](LICENSE).
Frontend dependency attributions are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
