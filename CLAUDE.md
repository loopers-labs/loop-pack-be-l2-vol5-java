# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working rules for `apps/commerce-api`

The repo is now in its Week-3 iteration (`volume-3/*` branches). [AGENTS.md](AGENTS.md) is titled as the Week-2 rule set but its technical conventions (package layout, layer dependencies, domain/UseCase conventions, testing, Checkstyle/ArchUnit gates, what not to change) still apply and are the baseline to read before making changes. For branching, scope, and per-requirement workflow, [docs/week3/total_requirement.md](docs/week3/total_requirement.md) is authoritative and supersedes AGENTS.md's own "작업 순서" (work-order) section. Week-1/Week-2 docs (`docs/week1/`, `docs/week2/`) are preserved as historical reference, not updated for Week 3.

Key points worth restating because they're easy to violate accidentally:
- Base branch is `volume-3/main`; work branches are `volume-3/r<nn>-<name>` (one per requirement folder under `docs/week3/`), never merged directly into each other.
- Auth/authz (Spring Security, ADMIN role, CSRF, ownership checks) remains out of scope — do not add it. `X-USER-ID` is still a fixture-user input, not authentication.
- Concurrency control is **no longer blanket-excluded**: R02 (`docs/week3/r02-order-consistency/`) explicitly brings locking/version-check/retry/concurrent-request testing into scope for order-confirm/stock/point consistency. Don't assume the old Week-2 "skip concurrency" rule still holds — check the specific requirement's `requirement.md` and `trade_off/` docs before adding or omitting it.
- Each requirement (`R01`, `R02`, ...) gets its own folder under `docs/week3/<r-id>-<name>/` with a fixed doc set: `requirement.md` (scope/rules/scenarios), `trade_off/total_trade_off.md` + numbered topic files (design alternatives, decision, open questions), `plan.md` (commit-by-commit TDD plan, written before implementation), `result.md` (actual outcome/deviations, written after). Follow that structure for new requirements; don't skip straight to code without `requirement.md` + `trade_off/` + `plan.md` agreed first.
- `volume-3/r01-brand-bulk-delete` (brand bulk-delete cascades to owned products in one transaction with full rollback on failure) is implemented, reviewed, and merged into `volume-3/main` — see [docs/week3/r01-brand-bulk-delete/result.md](docs/week3/r01-brand-bulk-delete/result.md).
- Currently on `volume-3/r02-order-consistency`: order-confirm atomicity plus stock/point concurrency control, replacing `ConfirmOrderWriter`'s current JDBC-based save with JPA `PESSIMISTIC_WRITE` locked reads (order → the order's user's point → product ids ascending) across confirm, charge, product-stock-set, and brand bulk-delete. Trade-offs and [plan.md](docs/week3/r02-order-consistency/plan.md) (7 commits) are agreed; implementation not yet started as of this writing.
- Never relax Checkstyle/ArchUnit rules or test expectations to make a check pass; fix the code instead.

## Commands

All commands run from the repo root; the `commerce-api` app is the only one with real feature code.

```bash
# start local infra (MySQL, etc.) the app depends on for the `local` profile
docker-compose -f ./docker/infra-compose.yml up

# optional local monitoring stack (prometheus/grafana on :3000, admin/admin)
docker-compose -f ./docker/monitoring-compose.yml up

# full check (build + tests + Checkstyle + ArchUnit) for commerce-api
./gradlew :apps:commerce-api:check

# tests only
./gradlew :apps:commerce-api:test

# a single test class or method
./gradlew :apps:commerce-api:test --tests "com.loopers.domain.shopping.user.UserTest"
./gradlew :apps:commerce-api:test --tests "com.loopers.domain.shopping.user.UserTest.메서드이름"

# Checkstyle only
./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
```

Integration tests use Testcontainers (MySQL) via `modules/jpa`'s `MySqlTestContainersConfig`, so Docker must be running for any test that touches the DB.

## Architecture

Multi-module Gradle project (Java 21, Spring Boot). Module roles, per [README.md](README.md):
- `apps/*` — runnable Spring Boot applications: `commerce-api` (the active one), `commerce-batch`, `commerce-streamer`.
- `modules/*` — reusable, non-domain-specific configuration: `jpa`, `redis`, `kafka`. `modules/jpa` provides `BaseEntity`, `DataSourceConfig`/`JpaConfig`/`QueryDslConfig`, and test fixtures (`MySqlTestContainersConfig`, `DatabaseCleanUp`) shared across apps.
- `supports/*` — add-ons: `jackson`, `logging`, `monitoring`.

Root `build.gradle.kts` applies shared config to all subprojects (Java 21 toolchain, Lombok, JUnit5 + Mockito + Instancio + Testcontainers, jacoco), disables `bootJar`/`jar` appropriately per module type, and forces `apps/*` container projects (`apps`, `modules`, `supports` themselves) to skip their own tasks.

### `commerce-api` package structure

Packages are organized **layer → bounded context → feature**, not feature-first. Contexts are `mall`, `shopping`, `ordering`, `pay` (business domains from the Loopers assignment), plus a legacy `example` context kept as reference-only scaffolding (do not extend it, do not delete it).

```
com.loopers
├── interfaces.api.<context>.<feature>     # Request/Response DTOs, Controller, HTTP-facing validation
├── application.<context>.<feature>        # UseCase interface + *Service impl, Command/Result, QueryDao + query records
├── domain.<context>.<feature>             # pure domain model, Repository interface, domain exceptions
└── infrastructure.<context>.<feature>     # JpaEntity, Spring Data JpaRepository, RepositoryImpl, EntityMapper, JdbcClient-based QueryDao impl
```

Dependency direction is enforced by ArchUnit (`LayerArchitectureTest`, `DomainPurityArchitectureTest`):
- `domain` depends on nothing else in `com.loopers` (and, for the four real contexts + `domain.shared`, on no Spring/JPA/Servlet types, and not on `BaseEntity`).
- `application` must not depend on `interfaces` or `infrastructure`.
- `interfaces` must not depend on `infrastructure`.
- `infrastructure` must not depend on `interfaces`, nor on any `application.*Service` class (it may depend on `application` QueryDao contracts and query-record types).

Writes flow `interfaces → application UseCase → domain + infrastructure RepositoryImpl`. Reads bypass UseCase/Service entirely: a query-only Controller calls an `application` `QueryDao` contract directly. Simple or aggregate lookups are implemented with Spring JDBC `JdbcClient` (`JdbcBrandQueryDao`, `JdbcUserQueryDao`, `JdbcProductLikeCountQueryDao`); the one query that needs dynamic multi-condition filtering/sorting (product listing) is implemented with QueryDSL instead (`QueryDslProductQueryDao`, backed by `modules/jpa`'s `QueryDslConfig`). There is intentionally no query UseCase/Service layer.

Domain classes split validation into two exception styles. Structural invariants checked at construction/`restore` time (non-null/positive id, non-null `createdAt`, positive foreign-key ids) throw a plain `IllegalArgumentException` with a Korean message — see `Brand.restore`, `Product`'s constructor (`brandId` check), `Like`'s constructor. Business-rule validation that carries a stable external error code (name/description length, price/stock rules, deleted-state guards) throws `DomainException` with a `DomainErrorCode` entry instead. `User` is the one outlier that uses `DomainException(INVALID_USER_ID)` for its structural id check — don't copy it as the template for new structural checks.

All four contexts now have full domain/infrastructure/application/interfaces implementations from Week 2: `mall` (brand, product), `shopping` (user, like — including the aggregation scheduler), `ordering` (order), `pay` (point, orderbill). Use `mall.brand`/`mall.product`/`shopping.user` as the cleanest reference for new work.

Two exception hierarchies coexist by design (see `ApiControllerAdvice`): `DomainException`/`DomainErrorCode` for domain-layer business-rule failures, `ApplicationException`/`ApplicationErrorCode` for application-layer existence/cross-object checks, and the older `CoreException`/`ErrorType` from the `example` scaffolding — all three are mapped to the same `ApiResponse` contract via `ApiErrorMapper`.

`@XUserId` (in `interfaces.api.support`) resolves the `X-USER-ID` header by calling `UserQueryDao.findById`: malformed header → 400 without calling the DAO, well-formed but unknown user → 404, otherwise the numeric user id is injected into the controller method. Write-side Services do not re-check user existence — callers are expected to supply a valid user id already resolved upstream.

Checkstyle config is at [config/checkstyle/checkstyle.xml](config/checkstyle/checkstyle.xml), applied only to `commerce-api` (star imports and unused imports banned, zero warnings tolerated). `.editorconfig` caps line length at 130 outside `*Test.java` files, which are exempt.
