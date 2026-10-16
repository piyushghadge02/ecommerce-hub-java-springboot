# E-Commerce Order Management & Search Service — Spring Boot Port

A behavioral port of the Python/FastAPI backend to **Java 17 + Spring Boot 3**.
The API contract is identical, so the existing Vue 3 frontend works unchanged.

| Store | Owns | Screens |
|---|---|---|
| **PostgreSQL** | Orders, order items, users, fulfillment status (ACID source of truth) | Checkout, Order Details |
| **MongoDB** | Product catalog (flexible documents: variants, tags, attributes) | Storefront, Catalog Admin |
| **Elasticsearch** | Denormalized order documents + full-text search + KPI aggregations | Admin Search Dashboard |

## Prerequisites

- Java 17+, Maven 3.8+
- Docker + Docker Compose (for PostgreSQL 16, MongoDB 8, Elasticsearch 8)

## Quick start

### 1. Start the databases

```bash
docker compose up -d
```

### 2. Build

```bash
mvn -q -DskipTests package
```

### 3. Seed the demo data (deterministic, seed=42)

```bash
java -jar target/ecommerce-polyglot-1.0.0.jar --seed --reset
```

This creates 8 users, 26 products (25 active + 1 inactive), 44 orders / 93 items,
applies the deliberate "Wireless Mouse" → "Wireless Mouse Pro" catalog rename
(Mongo only — history keeps the original snapshot), and reindexes Elasticsearch.
Without `--reset`, existing users/products are skipped. `--reindex` alone rebuilds
the ES index from PostgreSQL.

### 4. Run the API

```bash
java -jar target/ecommerce-polyglot-1.0.0.jar
# or: mvn spring-boot:run
```

- API: http://localhost:8000
- Health: http://localhost:8000/api/health

### 5. Frontend

Use the shared Vue 3 frontend from the main project (it only talks REST):

```bash
cd /path/to/ecommerce-polyglot-order-management/frontend
npm install && npm run dev   # http://localhost:5173
```

## Configuration

`src/main/resources/application.properties` holds defaults; every key can be
overridden with an environment variable (Spring relaxed binding):

| Property | Env var | Default |
|---|---|---|
| `server.port` | `SERVER_PORT` | `8000` |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/ecommerce` |
| `spring.data.mongodb.uri` | `SPRING_DATA_MONGODB_URI` | `mongodb://localhost:27017/ecommerce` |
| `app.elasticsearch-url` | `ELASTICSEARCH_URL` | `http://localhost:9200` |
| `app.es-index` | `ES_INDEX` | `orders` |
| `app.sync-strategy` | `SYNC_STRATEGY` | `dual_write` |
| `app.outbox-poll-seconds` | `OUTBOX_POLL_SECONDS` | `2` |
| `app.polling-interval-seconds` | `POLLING_INTERVAL_SECONDS` | `15` |

## Synchronization strategies

`SYNC_STRATEGY` selects how PostgreSQL orders reach Elasticsearch.
All strategies upsert with the deterministic document id `order-<order_id>`.

1. **`dual_write`** (default) — index ES right after the PG commit. On ES failure
   the order still commits; an outbox row is queued and a repair loop (every 10s)
   drains it. API reports `es_sync.status: pending_retry`.
2. **`outbox`** — outbox row inserted *inside the same PG transaction* as the
   order. A `@Scheduled` worker polls every `OUTBOX_POLL_SECONDS`, indexes to ES,
   retries with exponential backoff (`2^attempts` s), marks `failed` after
   `OUTBOX_MAX_ATTEMPTS` (visible at `GET /api/admin/sync-status`).
3. **`polling`** — worker every `POLLING_INTERVAL_SECONDS` upserts orders with
   `updated_at > watermark` (persisted in `sync_state`).

Demo: place an order on `/checkout`, watch it appear on `/admin`. Stop the
elasticsearch container mid-demo — checkouts keep succeeding — then restart it
and watch the dashboard catch up. `POST /api/admin/reindex` rebuilds the index.

## API summary

| Method & path | Source | Description |
|---|---|---|
| `GET /api/users` | PG | Seeded users for "Log In As" |
| `GET /api/products[?category=&q=&active=]` | Mongo | Catalog (active-only by default) |
| `GET /api/products/{id}` | Mongo | Single product |
| `POST /api/products` | Mongo | Create product (201; 409 duplicate SKU; 422 invalid) |
| `PUT /api/products/{id}` | Mongo | Edit product — never touches order history |
| `POST /api/orders` | PG (+Mongo validation) | Transactional checkout; `idempotency_key` supported |
| `GET /api/orders/{id}` | PG | Canonical order + ES sync badge |
| `PATCH /api/orders/{id}/status` | PG → ES | Update fulfillment status |
| `POST /api/search/orders` | ES only | Omni-search, filters, sorting, pagination, aggregations |
| `GET /api/health` | all | Dependency health |
| `GET /api/admin/sync-status` | PG + ES | Outbox backlog, watermark, doc count |
| `POST /api/admin/reindex` | PG → ES | Rebuild search index |

Money serializes as JSON strings (precision-safe); errors as `{"detail": "..."}`.

## Running tests

```bash
mvn -q test
```

Tests use **isolated resources** (`ecommerce_test` PG/Mongo databases,
`orders_test` ES index) — the demo `ecommerce` data is never touched.
Unit tests cover the ES query builder, document builder, money rounding, the
deterministic seed plan, and order-service orchestration (Mockito). Integration
tests cover products, checkout (server-side pricing, totals, atomic rollback,
idempotency), search (q/filters/aggs/pagination), snapshots, and all sync
strategies against the live local databases.

## Project layout

```
src/main/java/com/ecommerce/
  EcommerceApplication.java      # Boot app, @EnableScheduling, startup init
  config/                        # AppProperties, ES client, CORS, Jackson
  model/                         # JPA entities: User, Order, OrderItem, Outbox, SyncState
  repository/                    # Spring Data JPA repositories
  mongo/                         # MongoTemplate-based product repository
  dto/                           # Request/response DTOs (snake_case JSON)
  service/                       # OrderService, OrderTxService (@Transactional),
                                 # SyncService (@Scheduled workers), SearchService
  web/                           # Controllers + ApiException/GlobalExceptionHandler
  seed/                          # SeedPlan (pure logic) + SeedRunner (--seed/--reindex)
```

## Troubleshooting

- **ES won't start**: `sudo sysctl -w vm.max_map_count=262144` (Linux Docker hosts).
- **Port conflicts**: change `ports` in `docker-compose.yml` and the matching config.
- **Search empty after seeding**: `POST /api/admin/reindex` or
  `java -jar target/*.jar --reindex`.
- **Frontend can't reach the API**: check `VITE_API_URL` and that the API is on :8000
  (CORS allows `http://localhost:5173`).
- **Money as strings**: intentional — matches the Python API; the frontend parses
  with `Number(...)`.
