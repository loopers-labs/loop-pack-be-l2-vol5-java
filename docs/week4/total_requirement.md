# 4주차 전체 요구사항 · 조회 최적화와 캐시

## 1. 목표·기준·현재 상태

기존 상품 조회의 업무 결과를 유지하면서 SQL·인덱스 비용을 줄이고, 고객 상품 상세에 Caffeine 로컬 캐시를 적용한다.
정상 변경·rollback·만료·늦은 채움을 구분해 검증한다.

2026-10-16 제출 시 사용자는 **Caffeine 로컬 캐시를 이번 구현의 최종 선택**으로 확정했다.
검증한 단일 서버의 반복 상세 조회에서는 별도 캐시 서버 없이 DB 호출을 줄였으므로 Redis를 도입하지 않는다.
Redis는 이번 구현의 남은 필수 작업이 아니다. 여러 서버의 캐시 공유와 DB 직접 수정의 전파는 지원 범위 밖이며,
Redis와 성능을 직접 비교하거나 장애·복구를 검증한 결과는 없다.

기준 자료:

- [Implementation Quest·체크리스트·Technical Writing Quest 원문](reference/implementation-quest.md)
- [학습 자료 원문](reference/learning.md)
- [현재 작업 지침](../../AGENTS.md), [현재 구조·명령 안내](../../CLAUDE.md)
- [3주차 후속 요구사항](../week3-2/total_requirement.md), [R04 구현 결과](../week3-2/r04-like-sort-index/result.md)

두 원문은 사용자 첨부를 내용 변경 없이 보존했다. 원문에는 Redis 적용·장애·복구 요구가 있으나,
이번 사용자 선택은 로컬 캐시다. 이 문서는 실제 구현 범위와 원문 대비 차이를 구분한다.

| 구분 | 상태 |
|---|---|
| 준비 기준일 | 2026-10-16 (Asia/Seoul) |
| 기준 커밋 | `72ae005d40bc5689618898340c488a19aee70993` — `corinB`의 3주차 최종 병합 |
| 기준 브랜치 | `volume-4/main` |
| 브랜치 선택 근거 | 준비 당시 `corinB`와 `origin/volume-3/main`의 파일 내용이 같음. 로컬 `volume-3/main`은 이전 상태 |
| 이번 준비 산출물 | 로컬 기준 브랜치, 원문 보존, 요구사항·현재 구현·미정 사항 정리, 지침 연결 |
| 기능·측정 상태 | R01 인덱스·변경 후 CLI 완료. R02 로컬 상세 캐시·변경 무효화·실제 HTTP SQL·전체 check·OFF/ON CLI 8개 완료. Redis는 선택 범위 밖 |
| 사용자 지정 도구 | HTTP 성능 테스트는 **JMeter** 사용 |
| 현재 작업 | R01(PR #22)·R02(PR #23) 병합 완료. 로컬 캐시 선택과 검증 근거를 정리해 원본 저장소에 제출 |

문서 작성은 구현·측정·테스트 통과를 의미하지 않는다. 과거 문서의 수치는 해당 작업 당시 결과로만 인용한다.

사용자가 최종 선택한 [R02 상품 상세 로컬 캐시](r02-product-detail-local-cache/requirement.md)는
Caffeine W-TinyLFU를 사용한다. [설계](r02-product-detail-local-cache/trade_off/total_trade_off.md)와
[계획](r02-product-detail-local-cache/plan.md)에 범위를 기록한다. 로컬 캐시의 완료와 원문 Redis 요구의 미적용을 구분한다.

## 2. 진행 순서와 완료 조건

| 순서 | 작업 | 남길 결과·완료 조건 | 현재 상태 |
|---|---|---|---|
| 1 | 기준선·조회 계약·설계 | 실제 요청·SQL·SQL 수·실행 계획·JMeter 표본과 응답 결과, 캐시 범위·쓰기 책임 | R01 부분 before·전체 after 보존. R02 상세 로컬 캐시 설계와 OFF/ON 전체 비교 완료 |
| 2 | SQL·인덱스 개선 | 같은 seed와 입력의 결과 일치, 접근 경로·rows·loops·정렬·SQL 수·지연 비교 | 기존 4개 유지·신규 2개 적용, SQL 계획·응답 검증·after CLI 12개 완료. before는 부분 기준선 |
| 3 | 한 공통 경로 cache-aside | DB-only·miss·hit 결과 일치, hit에서 비싼 원본 조회 감소, 기존 쓰기 경로 연결 | R02 Caffeine 상세 완료. Redis 미도입 선택 |
| 4 | 변경·rollback·경쟁·회귀 | 실제 MySQL 연결 테스트, 결정적 경쟁 재현, 회귀·Checkstyle·ArchUnit 결과 | R02 실제 MySQL commit/rollback·로컬 경쟁·전체 check 완료. Redis 장애·복구는 선택 범위 밖 |
| 5 | 설계 갱신·기술 글·제출 | 실제 근거로 선택·이득·새 비용·한계를 설명하는 글과 PR 요약 | 설계·결과서 작성, 제출 PR 준비. 별도 기술 글은 미작성 |

후속 작업은 주제별 `requirement.md` → `trade_off/` 문답·결정 → `plan.md` → 구현·검증 → `result.md` 순서로 진행한다.
첫 주제 R01의 폴더·작업 브랜치는 아래와 같이 확정했다. 나머지 주제·PR 분할은 후속 설계에서 확정한다.
작업 브랜치는 작업 시작 시 최신 `volume-4/main`에서 만든다.
각 주제의 실제 결과만 완료로 표시하며 미측정 개선 효과는 기록하지 않는다.

### R01. 전체 최신순·가격순 인덱스와 전후 측정

| 항목 | 결정·상태 |
|---|---|
| 작업 브랜치 / PR 대상 | `volume-4/r01-product-list-index` / `volume-4/main` |
| 문서 | [요구사항](r01-product-list-index/requirement.md), [트레이드오프](r01-product-list-index/trade_off/total_trade_off.md), [구현·측정 계획](r01-product-list-index/plan.md), [실제 결과](r01-product-list-index/result.md), [JMeter 첫 실행 안내](r01-product-list-index/jmeter-guide.md) |
| 인덱스 | 기존 4개 유지 + 전체 최신순 `(deleted, created_at DESC, id DESC)`·전체 가격순 `(deleted, price ASC, id DESC)` 2개 적용 완료 |
| 측정 규모·대상 | 상품 100만 개. 전체 최신순·전체 가격순·브랜드별 가격순, 각 `page=0&size=20` |
| 동시 부하 | JMeter 동시 1명·10명을 구분해 전후 비교 |
| 역할 | 최초 사용자 직접 실행. 이후 사용자 요청으로 Codex가 변경 후 CLI 전체를 실행하고 원본·요약 보존 |
| 현재 진행 | 새 2개로 대량 읽기·정렬 제거 확인. after CLI 12개·65,261건 오류 0건. before 최신순 1회차 두 조건만 대응 비교 |

### R02. Caffeine 상품 상세 로컬 캐시

| 항목 | 결정·상태 |
|---|---|
| 작업 브랜치 / 기준 | `volume-4/r02-product-detail-local-cache` / 최신 `origin/volume-4/main`의 `3786c0d` |
| 문서 | [요구사항](r02-product-detail-local-cache/requirement.md), [설계](r02-product-detail-local-cache/trade_off/total_trade_off.md), [계획](r02-product-detail-local-cache/plan.md), [실제 결과](r02-product-detail-local-cache/result.md), [JMeter 안내](r02-product-detail-local-cache/jmeter-guide.md) |
| 읽기 | 고객 상세 전체의 불변 View, Caffeine W-TinyLFU, 기본 OFF·1000개·expireAfterWrite 30초, 기존 TX는 우회 |
| 변경 | 상품·브랜드·재고·주문·좋아요 관계/집계/전체 재집계의 동기 AFTER_COMMIT 무효화와 세대 기반 늦은 채움 방지 |
| 검증 | 실제 MySQL 쓰기 commit/rollback, 래치 경쟁·가짜 시간원 TTL, HTTP SQL OFF/miss 1회·hit 0회, 최종 전체 333개 테스트·Checkstyle·ArchUnit 통과 |
| 측정 | 기존 100만 DB·보조 인덱스 6개 유지. 같은 새 JAR의 OFF/ON × 1명/10명 × 2회 CLI 본 측정 8개, 오류 0건 |
| 선택 범위 밖 | Redis 적용·장애·복구, 다중 인스턴스 캐시 공유·외부 SQL 변경 전파. 도입을 예약한 후속 작업으로 취급하지 않음 |

아래 6·7절은 원문의 Redis 포함 요구와 비교하기 위한 기록이다. 미실행 항목에 완료 표시를 하지 않는다.
이번 제출의 로컬 캐시 완료 범위는 위 표와 별도 결과서에서 확인하며 Redis 미적용을 남은 필수 작업으로 해석하지 않는다.

### 유지할 범위

- 기존 Product 모델·조회 Controller·관리자 API를 이어 쓴다. 별도 모델·Controller·가격 수정 전용 우회 서비스를 만들지 않는다.
- 조회는 QueryDSL Projection, 쓰기는 JPA, JPA 쓰기와 섞이지 않는 좋아요 집계 배치는 JDBC라는 현재 기준을 유지한다.
- 계층·Context 의존, 순수 Java domain, 트랜잭션·잠금 순서·rollback, TDD, Checkstyle·ArchUnit과 검사 완화 금지를 유지한다.
- 삭제 상품 비노출, 사용자별 데이터 경계, 주문 금액·상품 상태·재고·포인트 판단은 표시 신선도를 이유로 완화하지 않는다.
- 좋아요 카운터·전체 캐시·깊은 페이지·쏠림·stampede 완화는 필요에 따라 검토한다. 이벤트·별도 배치 시스템은 필수로 추가하지 않는다.

## 3. 보존할 조회 계약

기준 코드는 [고객 조회 Controller](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/mall/controller/ProductQueryController.java),
[QueryDSL DAO](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/query/mall/QueryDslProductQueryDao.java),
[페이지 입력](../../apps/commerce-api/src/main/java/com/loopers/application/common/PageCriteria.java),
[페이지 응답](../../apps/commerce-api/src/main/java/com/loopers/application/common/PageResult.java)이다.

| 항목 | 현재 계약 |
|---|---|
| 목록 | `GET /api/v1/products` |
| 상세 | `GET /api/v1/products/{productId}` |
| 브랜드 필터 | 선택적 `brandId`, 입력 시 양의 정수. 해당 활성 상품이 없으면 빈 목록 |
| 기본값·범위 | `sort=latest`, `page=0`, `size=20`; `page >= 0`, `1 <= size <= 100` |
| 최신순 | `created_at DESC, id DESC` |
| 가격순 | `price ASC, id DESC` |
| 좋아요순 | `like_count DESC, id DESC`; 전체 후보 정렬 후 페이지 선택 |
| 노출 조건 | 상품과 브랜드 모두 `deleted=false`; 상세도 동일 |
| 목록 항목 | `productId`, `name`, `price`, `brand { brandId, name }`, `likeCount` |
| 상세 추가 필드 | `description`, `stock` |
| 페이지 필드 | `items`, `page`, `size`, `totalElements`, `totalPages`; 전체 0건이면 `totalPages=0` |
| 응답 포장 | 기존 `ApiResponse`의 `meta { result, errorCode, message }`, `data` 유지 |
| 대표 오류 | 지원하지 않는 정렬·잘못된 ID·페이지·크기·타입은 HTTP 400·`meta.errorCode="Bad Request"`. 없는/삭제된 상세는 내부 `PRODUCT_NOT_FOUND` → HTTP 404·`meta.errorCode="Not Found"` |
| 사용자 입력 | 고객 상품 조회에는 `X-USER-ID` 불필요. 현재 상품 View에는 사용자별 좋아요 여부 없음 |

페이지 번호 API를 새 `limit`·커서 API로 교체하지 않는다. 동점 기준을 예시 SQL의 `id ASC`로 바꾸지 않는다.
DB 오류를 빈 목록·가격 0으로 바꾸지 않고 기존 오류 응답을 유지한다.

### 원문과 현재 사용자 정책의 차이

원문은 자신의 좋아요·주문·포인트만 다룬다는 보호 조건을 요구한다. 현재 저장소는 인증·인가를 제외한 fixture 정책이다.
좋아요 목록은 경로 `userId`, 주문 목록·포인트는 `X-USER-ID`, 주문 상세·확정은 `orderId`로 대상을 선택하며 본인 확인이 구현돼 있지 않다.
따라서 원문의 소유권 보호를 기존 코드가 모두 보장한다고 기록하지 않는다.
사용자별 값의 공용 캐시 혼입 금지는 필수로 유지하며, 원문 소유권 요구와 기존 정책의 차이·추가 범위는 후속 설계에서 확인한다.

## 4. 현재 구현과 측정할 비용

| 영역 | 코드에서 확인한 사실 | 4주차에서 확인할 것 |
|---|---|---|
| 목록 SQL | products–brands INNER JOIN, 양쪽 삭제 조건, 필터·정렬·OFFSET/LIMIT | Hibernate가 실제 실행한 열·바인딩·SQL·왕복 수 |
| 브랜드 필터 목록 | 목록 조회 1회 + products COUNT 1회의 코드 경로 | R01 브랜드 7 가격순 GET에서 실제 2 SQL 관측. 원문은 R01 결과에 보존 |
| COUNT 조건 | 상품 삭제·brandId만 검사하며 브랜드 JOIN·삭제 조건은 없음 | 브랜드 삭제와 소속 상품 일괄 논리 삭제가 같은 TX라는 기존 불변식과 페이지 결과 검증 |
| 무필터 COUNT | `ExpiringCountCache`의 로컬 30초 TTL, 생성·삭제 무효화 없음; test 프로필은 `0s` | 목록 캐시 선택 시 기존 COUNT 캐시까지 신선도 설계에 포함. DB-only라고 현재 페이지 정보의 즉시 최신성을 단정하지 않음 |
| Projection | 공용 Row에서 목록에도 description·stock·createdAt을 읽고 응답 변환 시 제외 | 실제 측정 뒤 목록 전용 Projection 축소 등을 비교할 수 있음. 아직 채택안 없음 |
| 좋아요 수 | `products.like_count`를 읽음. 상품별 좋아요 COUNT 반복 경로 없음 | N+1이 이미 제거된 상태를 기준으로 SQL 수·집계 비용 실측 |
| 좋아요 반영 | 관계 commit 후 메모리 delta, 5초 주기 JDBC 반영, 시작 시 전체 재집계 | 지연 반영의 기존 의미와 캐시 추가 지연·반영 책임을 구분. 실패 상황까지 무조건 5초 상한이라고 주장하지 않음 |
| 인덱스 | 기존 4개에 전체 최신·가격순 2개 추가, 엔티티·실험 DB 정의 일치 | 전후 계획·응답은 R01에 보존. 지연·처리량·오류율은 사용자 JMeter로 확인 |

인덱스 선언은 [ProductJpaEntity](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/persistence/mall/entity/ProductJpaEntity.java) 기준이다.
가격순에는 이미 `idx_products_deleted_brand_price (deleted, brand_id, price ASC, id DESC)`가 있다.
좋아요순에는 `(deleted, like_count DESC, id DESC)`와 `(deleted, brand_id, like_count DESC, id DESC)`가 있다.

[기존 R04 결과](../week3-2/r04-like-sort-index/result.md)와 [EXPLAIN·seed 자료](../week3-2/r04-like-sort-index/explain/)는 재사용 가능성을 검토할 참고 자료다.
해당 자료는 100만 상품·좋아요순 중심의 수기 SQL·1회 측정이며 현재 API의 Projection과 정확히 같지 않고,
이번 필수 대상인 브랜드 필터+가격순의 반복 HTTP 측정·실제 좋아요 관계 fixture를 대신하지 못한다.
공유 DB나 다른 과제의 인덱스를 삭제해 인위적으로 느린 기준선을 만들지 않는다.

## 5. 재현 데이터·SQL·JMeter 측정 요구사항

### 고정할 데이터와 요청

- 격리된 실습 DB에 상품 10,000개 이상, 필요한 브랜드·사용자·필수 값·서로 다른 사용자–상품 좋아요 관계를 재현 가능하게 준비한다.
- 브랜드·가격·좋아요 수가 한 값에 몰리지 않게 하고 0개·동점·인기 상품, 삭제 상태와 필터·페이지 경계를 확인한다.
- 기존 fixture·스키마 변경 방식을 활용해 seed·인덱스 SQL을 저장한다. 순번과 실제 생성 ID를 매핑한다.
- 첫 측정은 `GET /api/v1/products?brandId={seed에서 고정한 실제 브랜드 ID}&sort=price_asc&page=0&size=20`으로 고정한다.
- 원문의 10,000개 분포 예시를 사용할 경우 count·price 합계와 브랜드 순번 7의 count·합계도 확인한다. 예시 순번을 실제 ID로 가정하지 않는다.

### 관측과 전후 비교

- 캐시를 끈 기준선을 먼저 수집한다. 실제 SQL·바인딩·선택 열을 사용해 `EXPLAIN`과 `EXPLAIN ANALYZE`를 남긴다.
- 사용 key·접근 경로, 예상/실제 rows·loops·정렬·집계 단계와 요청당 SQL 수·좋아요 읽기/집계 비용을 기록한다.
- 실제 SQL·계획·요청당 SQL 수를 AI에 제공해 병목·대안을 비교하고 판단 근거를 남긴다. 조건을 누락한 최적화, 개인화 값의 공용 키 저장, TTL만으로 즉시 최신성을 주장하는 제안은 수정한다.
- HTTP 성능 테스트는 **JMeter**로 수행한다. 재실행할 수 있는 `.jmx`, 실행 방법과 설정, 원본 `.jtl` 및 결과 요약을 남긴다.
- JMeter·Java·애플리케이션·MySQL·Redis 버전, 실행 커밋·장비, 데이터·인덱스·프로필·endpoint를 기록한다.
- 준비 실행과 측정 실행을 분리하고 thread 수·ramp-up·반복 횟수/시간·실제 표본 수·동시 요청 조건을 남긴다. 수치는 측정 계획에서 확정한다.
- 응답 시간 분포(p50·p95·p99와 평균), 처리량·오류율을 수집한다. 표본 수가 적은 분위수와 측정 환경의 한계를 적는다.
- JMeter의 HTTP 시간·처리량과 MySQL 실행 계획·SQL 수는 별도 관측이다. SQL 시간의 합이나 EXPLAIN 노드 시간을 HTTP 지연으로 간주하지 않는다.
- 변경 전후 같은 seed·입력·측정 조건에서 정렬된 ID·가격·좋아요 수·페이지 필드를 비교한다. 체크섬 사용 시 필드·직렬화 순서를 고정하고 불일치한 실제 값을 확인한다.
- DB-only·miss·hit도 같은 결과를 비교하고, hit에 남는 권위 확인 SQL·Redis 왕복까지 비용에 포함한다.
- 특정 인덱스 이름·`Using index` 문구·배수 향상을 정답으로 고정하지 않는다. 결과가 바뀐 경우 성능 개선으로 인정하지 않는다.

JMeter는 HTTP 성능 관측 도구로 사용한다. 무효화 실패·늦은 채움의 순서 제어와 업무 정확성은 별도의 통합 테스트로 검증한다.

## 6. 원문 대비 검토 기록과 변경 책임

### Redis 관련 검토 기록 — 이번 선택 범위 밖

R02의 상품 ID 키·상세 View·30초 TTL·동기 무효화는 로컬 캐시에 확정했다.
아래 표는 원문을 검토하며 남긴 Redis 설계 항목이다. 현재 구현 계획이 아니며,
여러 서버의 캐시 공유가 필요해지는 등 요구가 바뀌면 별도 대안을 검토한다.

| 항목 | 결정할 내용 |
|---|---|
| 대상 경로·payload | 고객 목록 또는 상세의 공통 경로 하나, 재사용할 필드·제외할 사용자별 값, 삭제·접근 가능 여부의 권위 확인 |
| key·직렬화·TTL | 적용되는 필터·정렬·페이지·크기·대상 ID·응답 버전, 명시적 payload 형식, 만료 기준·TTL |
| 정합성 | 영향 쓰기·TX·commit 경계, rollback 처리, 정상 변경 후 새 순차 조회의 최신성 |
| 실패·경쟁 | 무효화 실패·늦은 채움 방지 또는 허용 표시 필드·조건·만료/재처리/DB 우회·복구 완료 판정 |
| 기존 정책과 조합 | 무필터 COUNT 30초 캐시, 좋아요 지연 집계, 기존 fixture·소유권 정책 차이 |
| Redis I/O | master/replica 선택, 연결·명령 timeout과 요청 대기 한도, 읽기/쓰기 실패 관측·DB fallback |
| 검증 기반 | 실제 Redis Testcontainer 또는 격리 Compose, 장애·재시작 대상 endpoint, latch·테스트 훅·worker 정리 |

miss는 올바른 DB 결과를 읽고 TTL과 함께 저장하며 hit는 비싼 원본 조회·집계를 줄여야 한다.
모든 조회를 DB에서 완전히 수행한 뒤 사용하지 않는 캐시만 채우는 구현은 hit 개선으로 인정하지 않는다.
원문의 DB revision 설계는 비교할 수 있는 선택지다. DB 확인 비용·공통 행 경합을 함께 평가하며 필수 구조로 채택하지 않는다.

### 영향 쓰기 경로 조사표

아래는 후보 payload 기준 조사 목록이다. 선택한 실제 필드·구성·정렬에 따라 영향 여부와 갱신 책임을 확정한다.

| 기존 진입점·경계 | 영향을 주는 값 | 확인할 책임 |
|---|---|---|
| 관리자 상품 생성 | 목록 구성·전체 건수·정렬·상세 | 기존 `CreateProductUseCase`·브랜드 활성 검증·commit 연결 |
| 관리자 상품 수정 | 이름·설명·가격, 가격순 위치 | 기존 `UpdateProductUseCase`와 연결. 전용 우회 서비스 금지 |
| 관리자 상품 삭제 | 노출·상세 존재·목록·전체 건수 | 기존 `DeleteProductUseCase`의 논리 삭제와 비노출 보호 |
| 관리자 브랜드 생성·수정 | 생성 자체의 상품 조회 영향 여부, 표시 브랜드 이름 등 | 실제 payload별 판단. 기존 `BrandService` 경로 조사 |
| 관리자 브랜드 삭제 | 소속 상품 비노출·목록·전체 건수 | 브랜드와 소속 상품 일괄 논리 삭제의 동일 TX·rollback·다른 브랜드 보존 |
| 좋아요 등록·취소 | 사용자별 관계, 이후 likeCount·인기순 | 실제 관계 변경 여부와 기존 중복 요청 규칙 유지. 관계 commit만 처리하고 집계 반영을 놓치지 않음 |
| 좋아요 delta flush | `products.like_count`·인기순 | `LikeCountDeltaFlushService`의 실제 DB 변경·commit 경계 |
| 시작 시 전체 재집계 | `products.like_count`·인기순 | `LikeCountAggregationService`·`recountAll()`의 별도 TX 경계 |
| 관리자 재고 설정 | 상세 재고 | 기존 `SetProductStockUseCase`·commit 경계; 목록에는 stock 미포함 |
| 주문 확정 | 상품 재고, 주문·포인트 기록 | `ConfirmOrderFacade`의 전체 TX·잠금 순서 유지. 재고를 캐시한 경우 반영 책임 포함 |

장애·동시 채움이 없는 정상 변경 완료 뒤 시작한 순차 조회는 변경된 결과를 반환해야 한다.
rollback된 새 값을 게시하지 않는다. DB commit 후 Redis 실패를 DB rollback으로 오인하는 응답을 만들지 않는다.
`@Transactional + delete`, after-commit 삭제 또는 TTL만으로 모든 경쟁·장애에서 최신성을 보장한다고 주장하지 않는다.
삭제 비노출·사용자 경계·주문 판단은 표시 신선도 허용 대상이 아니며 주문 가격·재고·포인트는 기존 DB 규칙을 따른다.
TTL만으로 변경 후 모든 요청에 적용되는 전역 최신성 시간 상한을 주장하지 않는다.

### Redis 현재 상태

- [RedisConfig](../../modules/redis/src/main/java/com/loopers/config/redis/RedisConfig.java): String/String 직렬화, 기본 `REPLICA_PREFERRED`, 별도 `redisTemplateMaster`는 `MASTER`.
- [redis.yml](../../modules/redis/src/main/resources/redis.yml): local/test 기본 master `localhost:6379`, replica `localhost:6380`.
- [인프라 Compose](../../docker/infra-compose.yml): master·readonly replica가 따로 존재한다. 실제 실험에서 읽은 endpoint를 기록하고 replica lag를 최신성 보장으로 해석하지 않는다.
- 현재 factory에 명시적 command/connect timeout이 없다. 설정 파일의 값만 추가하면 적용된다고 가정하지 말고 실제 factory 적용·장애 지연을 확인한다.
- commerce-api는 Redis 모듈을 의존하지만 상품 Redis 캐시는 없다. 테스트에는 Redis testFixtures 연결이 없고 test 프로필 Redis health만 비활성화돼 있다.
- 모듈의 [Redis Testcontainer 설정](../../modules/redis/src/testFixtures/java/com/loopers/testcontainers/RedisTestContainersConfig.java)은 master·replica endpoint를 동일 컨테이너로 연결한다. 이번 로컬 캐시 검증에는 사용하지 않았다.

## 7. 원문 대비 통합 검증·회귀·제출 체크리스트

아래는 원문의 애플리케이션·MySQL·Redis 연결 검증 요구를 대조하기 위한 기록이며 현재 작업 목록이 아니다.
R02는 실제 MySQL·HTTP와 로컬 캐시를 검증했다. 원문의 Redis 요구는 미적용으로 남기고 실제 수행한 범위는 R01·R02 결과서에 기록했다.

### 조회·비교

- [x] 10,000개 이상 재현 seed와 실제 모델의 브랜드·좋아요·필수 값·분포를 준비했다. R01에서 상품 100만 개를 실제 검증했다.
- [ ] 같은 브랜드 필터+가격순 첫 페이지에서 실제 SQL 수·EXPLAIN·EXPLAIN ANALYZE·JMeter 준비/측정 표본·지연 분포를 전후 비교했다.
- [ ] 실제 SQL·계획·SQL 수로 AI의 대안을 비교하고, 기존 계약을 깨는 제안을 바로잡은 판단을 남겼다.
- [ ] 정렬된 ID·가격·좋아요 수·페이지 결과가 같으며 필터·동점·삭제·오류·응답 계약을 유지했다.
- [ ] 좋아요 N+1 유무와 전체 후보의 `likes_desc` 정렬 의미를 실제 실행으로 확인했다.

### 캐시·정확성·장애

- [ ] 동일 입력의 DB-only·miss·hit 결과가 같고 hit에서 선택한 비싼 원본 호출이 줄었다. 남은 DB 확인 비용도 포함했다.
- [ ] 관리자 가격 변경 commit 후 새 가격·필요한 정렬을 반영하고 과거 주문 금액을 보존했다.
- [ ] 캐시된 상품·브랜드 일괄 삭제 후 고객 비노출, 다른 브랜드·과거 주문 보존을 확인했다.
- [ ] 생성·수정·좋아요 등록/취소·실제 집계 반영/재집계·재고 등 선택한 payload에 영향 있는 나머지 쓰기를 검증했다.
- [ ] DB rollback 후 기존 결과를 유지하고 새 데이터로 오인할 캐시를 게시하지 않았다.
- [ ] commit 후 무효화 실패를 훅으로 통제해 DB commit 사실·표시 정책·필수 보호 조건·복구 뒤 원본 일치를 검증했다.
- [ ] 이전 DB 읽기 → 변경 commit → 이전 결과 캐시 저장 → 새 조회 순서를 latch/훅으로 재현하고 선택한 방지·만료·복구 정책을 확인했다.
- [ ] 경쟁 테스트의 대기에는 유한한 timeout을 두고 모든 worker를 정리했다.
- [ ] Redis 장애 전에 복구 절차를 마련하고, 테스트가 실제 사용하는 모든 읽기 endpoint를 중단했다. master/replica가 분리돼 있다면 둘 다 대상으로 삼았다.
- [ ] DB 정상·통제 부하에서 Redis 대기를 제한하고 기존 정상 응답으로 DB fallback했다. 캐시 오류와 miss를 구분해 관측했다.
- [ ] DB까지 실패한 경우 기존 오류 계약을 유지하고 주문 결제·재고 처리를 우회하지 않았다.
- [ ] 동일 Redis를 재시작해 연결 회복·정확한 재채움·복구 완료를 확인하고 자신이 시작한 실험 자원만 정리했다.
- [ ] 공유 Redis `FLUSHALL`·다른 작업 컨테이너 중단·공유 DB 또는 다른 과제 인덱스 삭제 없이 실험했다.

### 실행·문서·제출

수정 중에는 실제 존재하는 관련 테스트부터 실행한다. 원문의 `*ProductCache*Test`는 앞으로 작성할 예시이며 현재 통과 결과가 아니다.

```powershell
.\gradlew.bat :apps:commerce-api:test --tests '*ProductQuery*Test'
# 캐시 테스트를 작성한 뒤 실제 클래스 패턴으로 실행
.\gradlew.bat :apps:commerce-api:test --tests '*ProductCache*Test'
# 완료 시 전체 회귀·Checkstyle·ArchUnit (현재 check는 slow/example까지 포함)
.\gradlew.bat :apps:commerce-api:check
```

- [ ] 영향받은 상품·브랜드·좋아요·주문 회귀와 Checkstyle·ArchUnit을 실행하고 실행 건수·실패·skip·종료 결과를 확인했다.
- [ ] 테스트 0개·연결 실패·과거 테스트 결과를 이번 구현의 성공으로 기록하지 않았다.
- [ ] 코드·테스트, seed·인덱스 SQL, 실제 실행 계획·응답 비교·JMeter 측정 근거와 갱신한 설계 문서를 남겼다.
- [ ] 의미 있었던 주제 하나를 상황·실제 근거, 핵심 개념, 선택 이유·줄인 비용·추가 비용·미확인 한계로 설명하는 기술 글로 작성했다.
- [ ] PR에 병목·선택 방법·정확성·장애 결과·측정 환경과 표본의 한계를 요약했다.

주제별 실제 결과는 R01·R02 결과서에 보존했다. 위 원문 대조표를 이번 로컬 캐시 구현의 미완료 작업 목록으로 사용하지 않는다.
Redis 적용·장애·복구는 선택 범위 밖이며, 별도 기술 글을 작성하거나 Redis를 검증했다고 주장하지 않는다.
