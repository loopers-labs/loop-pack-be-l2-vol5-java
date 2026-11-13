# 커머스 스키마 적용

[전체 설계](commerce-erd-draft.md) · [완료 체크리스트](commerce-completion-checklist.md) · [W5 주문·결제 저장 TDD](../week5/order-payment-persistence-tdd-log.md) · [W5 요청 지문 저장 TDD](../week5/payment-request-fingerprint-persistence-tdd-log.md) · [W5 지문 키 설정 TDD](../week5/payment-fingerprint-key-configuration-tdd-log.md) · [W5 주문 HMAC 연결 TDD](../week5/order-payment-fingerprint-connection-tdd-log.md) · [W2 전체 스키마 TDD](commerce-tdd-schema-log.md)

## 제공 범위와 적용 순서

W2의 `001`~`006`은 커머스의 6개 업무 테이블을 빈 MySQL 8 데이터베이스에 수동으로 최초 생성하는 SQL이다. W4는 조회 인덱스 `007`과 목록 캐시 버전 메타데이터 `008`을 추가했다. W5는 주문·품목의 전진 이행 `009`, 결제 시도 테이블 `010`, 요청 지문 컬럼의 전진 이행 `011`을 제공한다. W7은 공통 Outbox 테이블 `012`, W8은 쿠폰 캠페인·예약·보유 쿠폰 세 테이블 `013`과 대기 예약 조회 인덱스 `014`를 추가한다. **API 신규 설치는 14개 스크립트로 업무 테이블 10개, 캐시 메타데이터 1개, Outbox 1개를 생성한다.** W6 streamer의 별도 001·두 테이블은 아래 소비 저장 절에서 다룬다. [인덱스 기록](../week4/product-query-optimization.md)·[캐시 기록](../week4/product-list-cache-tdd-log.md)·[선행 W5 저장 연결 기록](../week5/order-payment-persistence-tdd-log.md)과 [지문 저장 증분](../week5/payment-request-fingerprint-persistence-tdd-log.md)을 구분한다.

| 순서·파일 | 역할·주요 제약 |
| --- | --- |
| [001-brand.sql](../../apps/commerce-api/src/main/resources/db/schema/001-brand.sql) | brand, 이름 VARCHAR(100), 생성·수정·논리 삭제 시각 |
| [002-user.sql](../../apps/commerce-api/src/main/resources/db/schema/002-user.sql) | user, fixture가 지정하는 ID, 비음수 BIGINT 잔액 |
| [003-product.sql](../../apps/commerce-api/src/main/resources/db/schema/003-product.sql) | product, brand FK, 1원 이상 BIGINT 가격·비음수 INT 재고, 논리 삭제 |
| [004-like.sql](../../apps/commerce-api/src/main/resources/db/schema/004-like.sql) | 인용한 `like`, user/product FK, UNIQUE(user_id, product_id) |
| [005-order.sql](../../apps/commerce-api/src/main/resources/db/schema/005-order.sql) | 인용한 `order`의 이전 구조. user FK, DRAFT/CONFIRMED, 금액·결제 상태 CHECK. W5 확장은 009가 담당 |
| [006-order-item.sql](../../apps/commerce-api/src/main/resources/db/schema/006-order-item.sql) | order_item의 이전 구조. order/product FK, UNIQUE(order_id, product_id), 양수 수량·생성 스냅샷. 예약 상태는 009가 추가 |
| [007-product-brand-price-index.sql](../../apps/commerce-api/src/main/resources/db/schema/007-product-brand-price-index.sql) | 기존 product에 `idx_product_brand_price (brand_id, deleted_at, price ASC, id DESC)` 추가. 테이블·데이터·기존 인덱스는 유지 |
| [008-product-list-revision.sql](../../apps/commerce-api/src/main/resources/db/schema/008-product-list-revision.sql) | `product_list_revision` 메타데이터·singleton `(id=1, revision=0)` 초기화. 업무 데이터는 변경하지 않음 |
| [009-order-payment-state.sql](../../apps/commerce-api/src/main/resources/db/schema/009-order-payment-state.sql) | 주문 상태 확장·nullable 결제수단 추가, 기존 CONFIRMED만 POINT 이행, 상태 CHECK 교체, 품목 예약 상태 NOT_RESERVED 추가 |
| [010-payment-attempt.sql](../../apps/commerce-api/src/main/resources/db/schema/010-payment-attempt.sql) | 별도 payment_attempt. 주문 FK·주문/UUID/외부 식별 유일 제약, 비음수 금액·관찰 상태 CHECK, 영속 발송 시작 시각 |
| [011-payment-request-fingerprint.sql](../../apps/commerce-api/src/main/resources/db/schema/011-payment-request-fingerprint.sql) | 기존 payment_attempt에 nullable VARBINARY(32) 지문·INT 형식/키 버전 추가. 전체 NULL 또는 정확히 32바이트·양수 두 버전만 허용하는 CHECK. 기존 행 재작성·지문 소급 생성 없음 |
| [012-outbox.sql](../../apps/commerce-api/src/main/resources/db/schema/012-outbox.sql) | 공통 outbox_message 추가. eventId 유일성·완성 JSON 문자열·목적지·불변 사실, PENDING/PUBLISHED·시도/시간 정보. 기존 주문·결제·재고·소비 DB 변경이나 과거 사건 소급 생성 없음 |
| [013-coupon-issuance.sql](../../apps/commerce-api/src/main/resources/db/schema/013-coupon-issuance.sql) | 쿠폰 캠페인·사용자별 예약·보유 쿠폰. 캠페인·사용자당 예약 한 건, 예약당 보유 쿠폰 한 장, 대기 수와 발급 수의 합은 캠페인 한도 이하 |
| [014-coupon-waiting-index.sql](../../apps/commerce-api/src/main/resources/db/schema/014-coupon-waiting-index.sql) | 캠페인별 커밋된 대기 예약을 순서대로 조회하는 인덱스 |

SQL 파일은 애플리케이션 시작 시 자동 실행되지 않는다. 새 마이그레이션 프레임워크나 자동 스키마 적용 프로파일을 추가하지 않았다. 파일의 번호는 수동 적용 순서다. 기존 예시 API의 ExampleModel 테이블은 이 12개 테이블 범위에 포함하지 않는다.

## W8 쿠폰 예약·발급 스키마

4단계 작업자는 지정 캠페인에서 `WAITING` 예약을 ID 오름차순으로 찾는다. 013이 적용된 DB에는 014가 `(campaign_id, status, id)` 인덱스를 추가한다. 쿠폰·예약 데이터 변경은 없다. 적용 여부는 `SHOW INDEX FROM coupon_reservation`로 확인한다.

기존 API DB에 012까지 적용했다면 013을 한 번 적용한다. 예약은 캠페인과 기존 `user`를 참조하고, 보유 쿠폰은 예약을 참조한다. 적용 도중 중단되면 각 테이블의 존재 여부와 `SHOW CREATE TABLE` 결과를 대조한 뒤 미적용 구문부터 이어간다. 사용자 DB에는 이 스크립트를 실행하지 않았다.

| 저장 단계 | 커밋 뒤 확인할 상태 |
| --- | --- |
| 예약 | `coupon_reservation`에 고객의 `WAITING` 예약이 한 건 생기고, 같은 트랜잭션에서 `coupon_campaign.waiting_count`가 1 증가한다. 이 커밋이 한 장 확보의 근거다. |
| 발급 | 같은 예약이 `ISSUED`로 바뀌면서 `owned_coupon` 한 장이 생긴다. 대기 수 1 감소와 발급 수 1 증가를 같은 트랜잭션에서 커밋한다. 기존 예약을 다시 처리해도 같은 쿠폰 한 장을 유지한다. |

두 단계 모두 `waiting_count + issued_count <= total_quota`를 지킨다. DB의 캠페인 수량 제약과 예약·보유 쿠폰의 유일 제약은 서로 다른 사실을 보호한다. 장애 후 복구 기준은 [W8 예약·발급 설계](commerce-erd-draft.md#w8-쿠폰-예약과-발급)를 따른다.

앱을 재시작해 기존 예약을 복구할 때는 같은 MySQL 스키마와 데이터를 보존한다. 이 저장소의 `local`·`test` 프로필은 기본값이 `ddl-auto=create`여서, 복구 실험은 최초 DDL 준비 후 모든 API JVM에 `spring.jpa.hibernate.ddl-auto=validate`를 명시한다. [JPA 프로필 설정](../../modules/jpa/src/main/resources/jpa.yml)

## W7 Outbox의 추가 DDL과 배포 순서

**2026-11-04 저장 증분:** `012-outbox.sql`과 JPA 모델을 추가했다. 아래는 수동 적용 절차이며 **사용자 DB에 적용한 기록이 아니다.** 실제 수동 DDL·JPA 왕복·유일 제약·rollback을 다루는 전용 MySQL 컨테이너의 [OutboxSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/outbox/OutboxSchemaIntegrationTest.java)의 5개 테스트가 모두 통과했다. 실제 명령·범위·전체 rollback 결과는 [W7 원자성 기록](../week7/outbox-atomicity-tdd-log.md)을 따른다. 현재 진행 상태는 [W7 체크리스트](../week7/implementation-checklist.md)와 구분한다.

| 저장 항목 | 타입·제약·이유 |
| --- | --- |
| `id` · `event_id` | BIGINT 자동 생성 PK, VARCHAR(255) 사건 ID에 `uq_outbox_event_id` 유일 제약. 한 사건을 여러 전송 시도로 나누어 새 행을 만들지 않음 |
| `aggregate_type` · `aggregate_id` | VARCHAR(100)·VARCHAR(128). 현재 주문 사건은 `Order`·주문 ID 문자열. 공통 테이블이므로 특정 주문 FK를 두지 않으며 주문의 적격성·사실 구성은 주문 책임에서 검증 |
| `event_type` · `schema_version` | VARCHAR(100)·양수 INT. W6 `OrderConfirmed`·버전 1의 계약 보존 |
| `topic` · `message_key` · `payload` | VARCHAR(249)·VARCHAR(255)·LONGTEXT. 사용자 선택대로 목적지까지 함께 저장. JSON_VALID CHECK로 완성 JSON인지 검사하고, 원문 문자열은 변환 없이 보존. 이미 저장된 사건은 이후 topic 설정이나 현재 상품 정보로 재작성하지 않음 |
| `status` · `published_at` | PENDING/PUBLISHED enum·nullable DATETIME(6). PENDING이면 published_at NULL, PUBLISHED이면 필수. PUBLISHED는 broker 확인 기록이며 소비 완료가 아님 |
| `created_at` · `next_attempt_at` · `attempt_count` · `last_error` | DATETIME(6)·DATETIME(6)·비음수 INT·nullable VARCHAR(1000). 최초 PENDING·시도 0·다음 시각=기록 시각·오류 NULL. confirmedAt은 payload의 원래 사건 시각으로 별도 보존. relay는 전송 전 시도 수·재시도 가능 시각을 먼저 commit하고 결과에 따라 전달 상태만 갱신. 세부는 [relay 기록](../week7/outbox-relay-tdd-log.md) 참조 |
| 인덱스·문자 비교 | `idx_outbox_due(status, next_attempt_at, id)`는 due 조회와 안정된 정렬을 위한 기반. 문자열은 `utf8mb4_0900_bin`으로 대소문자·끝 공백을 구분. relay의 실제 due 조회·제한·정렬은 격리 DB에서 검증. 대규모 실행 계획·처리량 측정은 이번 증분에 포함하지 않음 |

1. 대상이 기존 001~011까지의 스키마인지 확인한다. 이 테이블은 **주문·PaymentAttempt·예약과 같은 데이터 소스/DB**에 둔다. 별도 Outbox DB나 streamer DB에 만들면 요구한 로컬 원자성을 보장하지 못한다.
2. W7의 커밋 전 기록 코드로 전환하기 **전에 012를 한 번 수동 적용**한다. 기존 테이블이 있으면 구조를 먼저 대조하고 삭제·재생성하거나 CREATE 재실행으로 차이를 숨기지 않는다.
3. 012에는 기존 업무 데이터의 UPDATE/DELETE나 과거 사건 backfill이 없다. 기존 주문·결제·재고와 과거 POINT 사실을 보존한다. W6 누락 복구는 별도 절차이며 이 DDL 실행만으로 완료되지 않는다.
4. 저장 구조를 확인한 뒤 자동 재생성을 끈 상태로 W7 코드를 시작한다. relay를 아직 실행하지 않거나 전송 설정을 꺼도 Outbox **기록은 필요**하므로 012를 생략하지 않는다. 누적 PENDING을 삭제하거나 과거 직접 발행 경로를 병행하는 것으로 대체하지 않는다.

```sql
SELECT DATABASE(), VERSION(), @@SESSION.sql_mode;
SOURCE apps/commerce-api/src/main/resources/db/schema/012-outbox.sql;
SHOW CREATE TABLE outbox_message;
SHOW INDEX FROM outbox_message;
```

012는 InnoDB 테이블을 추가하는 DDL이며, **DDL 적용 자체**를 주문 트랜잭션의 commit/rollback에 넣는다는 뜻은 아니다. 이후 신규 확정 요청에서 수행하는 Outbox INSERT가 기존 주문 확정과 같은 로컬 TX에 참여한다. JPA의 불변 필드는 `updatable=false`이고 공개 변경 메서드를 제공하지 않는다. 수동 SQL·운영 권한까지 불변성을 강제하는 DB trigger는 추가하지 않았다. 자동 생성 스키마만으로 CHECK·collation을 보장했다고 간주하지 않으며 실제 012로 따로 검증한다.

### 기존 데이터로 relay를 실행·재개할 때

현재 설정의 명령 예시·관찰 SQL·장애별 재개 방법은 [W7 운영·보존 안내](../week7/outbox-operations-retention.md)에 모았다. API의 local 프로파일은 공유 jpa.yml에서 `ddl-auto=create`를 사용하므로, **기존 주문·Outbox를 이어 사용하는 실행은 기동 인자 `--spring.jpa.hibernate.ddl-auto=none` 등 명시적인 재정의가 필요하다.** relay를 켜기 전에 같은 API DB와 준비된 스키마를 확인한다. 재시작 때 012를 다시 적용하거나 DB·볼륨을 초기화하지 않는다. 이 문서 작업에서는 사용자 DB·실행 설정을 변경하지 않았다.

## W6 streamer 구매 활동 DDL

[001-purchase-activity.sql](../../apps/commerce-streamer/src/main/resources/db/schema/001-purchase-activity.sql)은 **streamer가 소유하는 두 테이블의 최초 생성 파일**이다. API의 001~014와는 별도의 데이터 소스·적용 경로다. 사용자 DB에는 실행하지 않았고 실제 적용·검사는 격리 DB에서만 수행하며 [소비 TDD 기록](../week6/purchase-activity-consumer-tdd-log.md)에 남긴다.

| 테이블·컬럼 | 보호할 사실 |
| --- | --- |
| `processed_order_confirmation` | eventId·orderId 각각 유일, 고정 eventType/schemaVersion·최초 confirmedAt |
| `product_purchase_activity` | `(orderId, productId)` 유일, 양수 quantity·최초 confirmedAt. 완료 기록과 모든 품목을 같은 TX에 저장 |
| `confirmed_at_second` + `confirmed_at_nano` | BIGINT 초와 INT 나노초(0~999,999,999)로 Instant를 손실 없이 보존. DATETIME(6) 절삭으로 서로 다른 사실을 같다고 판단하지 않음 |
| `event_id VARCHAR(255) COLLATE utf8mb4_0900_bin` | 대소문자와 끝 공백을 구분하는 유일성. 초과 길이는 저장 오류로 전파하며 자르거나 중복 성공으로 바꾸지 않음 |
| FK | 활동의 order_id → 완료 기록의 order_id만 참조. API의 현재 주문·상품 테이블 FK나 조회 의존 없음 |

실행할 streamer DB를 확인한 뒤 두 테이블이 없는 경우에만 이 파일을 한 번 수동 적용한다. 기존 테이블이 있으면 구조를 대조하고 재실행·삭제·초기화로 대체하지 않는다. DDL은 자동 실행되지 않으며 여러 CREATE가 전체 원자적으로 rollback된다고 가정하지 않는다.

streamer는 [purchase-activity.yml](../../apps/commerce-streamer/src/main/resources/purchase-activity.yml)을 마지막에 import해 `spring.jpa.hibernate.ddl-auto=validate`를 적용한다. 공유 jpa.yml의 local `create`가 재시작 때 완료 기록을 지우지 않도록 한 설정이다. 아직 테이블이 없으면 시작이 실패하므로 먼저 위 수동 준비가 필요하다. producer·consumer·admin에는 같은 실제 broker 주소를 사용하며 테스트는 고유 topic/group·전용 DB에서 검증한다. API DB 설정·기존 데이터는 이번 증분에서 변경하지 않는다.

현재 완료·활동 테이블에는 자동 삭제 기능이 없다. 활동은 완료 기록을 `ON DELETE RESTRICT`로 참조하며, consumer는 두 테이블로 원래 사건 전체를 비교하므로 완료 기록만 TTL 캐시처럼 지우거나 활동만 정리하지 않는다. Outbox·Kafka·두 소비 테이블을 함께 보는 [보존 관계](../week7/outbox-operations-retention.md)를 따른다. 구체 삭제 일수·정리 기능·영구 보존 SLA는 이번 문서에서 새로 확정하지 않았다.

## W5 설계와 현재 DDL의 경계

**2026-10-23 복구·callback HTTP 연결 설계:** 기존 `payment_attempt`의 제공자계정·주문/거래 식별자와 TX B를 재사용하며 추가 DDL·충돌 이력 테이블·outbox는 선택하지 않는다. 내부 식별자와 고정 사유만 남기는 WARN 로그는 영속 감사 보장이 아니다. HTTP 경로는 현재 연결했으며 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 참조한다. 사용자 DB 이행은 하지 않았고 001~011과 기존 데이터 보존을 유지한다. [선택 이유](commerce-policy-decisions.md#w5-복구callback-http의-위임-선택)와 [테스트 계획](commerce-tdd-plan.md#복구callback-http-연결의-tdd-증분)을 따른다.

**2026-10-23 저장 연결 증분:** `009`·`010`과 Order·OrderItem·PaymentAttempt의 JPA 매핑을 추가했다. [예약 상태 첫 증분](../week5/order-reservation-state-tdd-log.md)·[결제 시도 순수 모델](../week5/payment-attempt-state-tdd-log.md) 이후의 변경이며, 실제 실행 결과는 [이번 저장 연결 기록](../week5/order-payment-persistence-tdd-log.md)에서 확인한다. [선택한 저장 구조 A](commerce-policy-decisions.md#w5-예약-저장-구조-비교와-선택)를 따르며 별도 StockReservation 테이블은 만들지 않는다. **사용자 로컬·공유·운영 DB에는 이 SQL을 적용하지 않았다.** 실제 PG·재고·목록 버전까지 연결한 TX A/B의 완료와 구분한다.

**같은 날 후속 지문 저장 증분:** PaymentAttempt·JPA의 지문/버전 필드와 011을 추가했다. 사용자가 승인한 기존 무지문 행의 세 컬럼 NULL 보존·비교 불가를 격리 Testcontainers DB에서 검증하며 결과는 [새 기록](../week5/payment-request-fingerprint-persistence-tdd-log.md)을 따른다. 사용자 DB에는 011을 적용하지 않았다. 이후 환경변수 키 공급·버전 선택·Spring 포트 bean을 추가했고, [주문 HMAC 연결](../week5/order-payment-fingerprint-connection-tdd-log.md)에서 내부 TX A의 지문 공동 저장·재요청 비교까지 연결했다. 고객 confirm·조회도 [고객 API 증분](../week5/customer-payment-api-tdd-log.md)에서 연결했으며 callback/reconcile HTTP도 연결했으며 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 참조한다. [키 설정 증분](../week5/payment-fingerprint-key-configuration-tdd-log.md)과 고객 연결은 추가 SQL·사용자 DB 접근 없이 검증했고 001~011은 유지한다.

| 대상 | 이번 저장 구조·보존 조건 |
| --- | --- |
| 결제 시도 | PaymentAttempt 별도 JPA 저장. 주문당 한 시도와 호출자 UUID의 서비스 전역 유일성, 상태 5개·send_started_at을 보존한다. UUID는 BINARY(16), 금액은 signed BIGINT, 발송 시각은 DATETIME(6). 발송 전이·커밋은 내부 TX A와 coordinator가 보호하며 startDispatch가 true인 것만으로 여러 실행의 선점을 보장하지 않음 |
| 요청 지문 · 모델/JPA/011 구현 | `request_fingerprint VARBINARY(32) NULL`, `fingerprint_version INT NULL`, `key_version INT NULL`. CHECK는 세 값 모두 NULL 또는 모두 존재하며 32바이트·양수 버전인 조합만 허용한다. 내부 prepareNew·prepareOrReuse·prepareCustomer의 신규 준비는 6인자 생성자로 지문을 필수 저장한다. 기존 5인자 생성자는 legacy 자료·fixture 호환용이며 신규 준비에서는 사용하지 않는다. 사용자 승인대로 기존 행을 소급 채우지 않으며 지문 미보유를 비교 불가로 다룬다. 환경변수 포트·TX A·재요청 비교와 고객 confirm의 PG 전환·HTTP 오류를 연결했다. PG OFF에서도 기존 시도 비교는 유지하며 새 준비는 하지 않는다. 카드 원문·본문·복원용 암호문·비밀키는 저장하지 않는다. |
| 주문 품목 | 기존 order_item의 불변 quantity와 ENUM reservation_status를 사용한다. 기본값 NOT_RESERVED, 준비 후 HELD, 성공 CONSUMED·실패 RELEASED. 관리자 HELD 합계·실재고 연결은 선행 내부 재고 기록의 범위이며 이번 011은 이를 변경하거나 예약 합계용 새 조회 인덱스를 추가하지 않음 |
| 기존 주문·품목 | 기존 CONFIRMED는 payment_method=POINT, DRAFT는 null로 이행한다. 기존 POINT·DRAFT 품목은 NOT_RESERVED이며 가짜 PG 시도·확보 예약을 만들지 않는다. 기존 금액·결과·확정/감사 시각·품목·재고·잔액을 재계산하거나 덮어쓰지 않음 |
| 목록 버전 | 기존 008·버전값을 보존. 예약·반환 시 같은 업무 TX에서 버전을 올리는 연결은 W5 구현·검증 범위이며 메타데이터 초기화로 대체하지 않음 |

로컬 기존 기록 유무는 아직 확정하지 못했으므로 **0건이라고 단정하지 않고 보존을 전제로 한다.** 기존 DB에 최초 생성 SQL을 재실행하거나 `ddl-auto=create`·DB/볼륨 초기화로 W5 이행을 대신하지 않는다. 실행 전 대상 DB·프로파일을 읽기 전용으로 확인하고, 빈 DB 스키마·기존 POINT/DRAFT 이행·기존 PG의 NULL 지문 보존 검증을 분리한다. 관련 계획은 [W5-TDD-02·03 및 HMAC 계획](commerce-tdd-plan.md#w5-pg-결제-tdd-계획)을 따른다. 아래 명령은 적용 절차이며 사용자 DB에 실행했다는 기록이 아니다.

## 빈 데이터베이스에 최초 생성

프로젝트 루트에서 MySQL 클라이언트를 실행한다. 아래 접속값은 [로컬 compose](../../docker/infra-compose.yml)의 값이며 대상 환경의 연결 정보로 바꿀 수 있다. DB 자체는 먼저 생성되어 있어야 한다. 비밀번호는 프롬프트에서 입력한다.

```shell
mysql --host=127.0.0.1 --port=3306 --user=application --password \
  --default-character-set=utf8mb4 loopers
```

다음 SOURCE를 **한 줄씩**, 직전 단계의 성공을 확인하고 실행한다. 오류가 발생하면 남은 단계를 진행하지 않는다.

```sql
SOURCE apps/commerce-api/src/main/resources/db/schema/001-brand.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/002-user.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/003-product.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/004-like.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/005-order.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/006-order-item.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/007-product-brand-price-index.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/008-product-list-revision.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/009-order-payment-state.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/010-payment-attempt.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/011-payment-request-fingerprint.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/012-outbox.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/013-coupon-issuance.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/014-coupon-waiting-index.sql;
```

`001`~`006`은 각각 한 테이블의 CREATE TABLE이고 `007`은 CREATE INDEX, `008`은 메타데이터 CREATE와 초기 INSERT다. `009`는 기존 주문·품목의 ALTER와 POINT 식별 UPDATE, `010`은 결제 시도 CREATE, `011`은 지문 세 컬럼·CHECK의 ALTER, `012`는 Outbox CREATE, `013`은 쿠폰 테이블 세 개의 CREATE, `014`는 예약 인덱스 CREATE다. MySQL DDL의 암묵적 커밋 때문에 전체가 하나의 트랜잭션으로 취소되지는 않는다. 중간 실패 시 실제 상태를 확인하며 기존 테이블을 삭제하고 처음부터 다시 실행하지 않는다.

이미 대상 테이블이 있으면 해당 CREATE가 실패한다. 기존 데이터를 지우거나 `IF NOT EXISTS`로 구조 차이를 숨기지 않는다. 이전에 브랜드 SQL만 적용했다면 기존 brand 구조가 검증한 001과 같은지 확인한 후 002부터 진행한다. 운영 중인 다른 스키마를 변경·교체하는 마이그레이션은 이번 최초 생성 파일의 역할이 아니다.

## 기존 W2~W4 데이터베이스에 W5 적용

`001`~`008`과 같은 구조의 기존 DB에만 **`009` → `010` → `011`을 한 번씩** 적용한다. 이전 파일을 다시 실행하지 않는다. 이미 010까지 적용된 DB는 다음 절의 **011만 적용하는 절차**를 따른다. 다른 이력의 스키마를 자동 판별·수정하는 migration은 아니다.

1. 대상 DB·프로파일·현재 DDL을 읽기 전용으로 확인한다. 실제 구조가 001~008과 다르면 적용을 멈추고 차이를 먼저 검토한다.
2. 기존 앱과 관리 작업의 **쓰기를 중지**하고 진행 중 트랜잭션을 정리한다. 변경 전 스키마·데이터의 백업과 복구 가능 여부를 확인한다. 무중단·무잠금 이행을 보장하지 않는다.
3. 다음 세 파일을 순서대로, 앞 파일의 성공과 결과를 확인한 뒤 실행한다. 오류·연결 중단이 나면 다음 파일로 진행하지 않는다.
4. 기존 행·금액·확정/감사 시각·품목·잔액·재고·삭제 시각·revision을 백업/사전 확인값과 비교한다. 기존 CONFIRMED=POINT, DRAFT=null, 기존 품목=NOT_RESERVED, 새 payment_attempt=0건을 확인한다.
5. W5 코드와 전체 구조를 확인하고 `ddl-auto=none`으로 실행한다. W7 코드로 시작할 때는 위 Outbox 배포 절의 012도 먼저 적용한다. ExampleModel까지 준비된 환경에서만 앱 전체 `validate`를 사용할 수 있다. 확인이 끝난 뒤 쓰기를 재개한다.

```sql
SELECT DATABASE(), VERSION(), @@SESSION.sql_mode;
SHOW CREATE TABLE `order`;
SHOW CREATE TABLE order_item;
SELECT * FROM product_list_revision;

SOURCE apps/commerce-api/src/main/resources/db/schema/009-order-payment-state.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/010-payment-attempt.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/011-payment-request-fingerprint.sql;

SHOW CREATE TABLE `order`;
SHOW CREATE TABLE order_item;
SHOW CREATE TABLE payment_attempt;
SELECT status, payment_method, COUNT(*) FROM `order` GROUP BY status, payment_method;
SELECT reservation_status, COUNT(*) FROM order_item GROUP BY reservation_status;
SELECT COUNT(*) FROM payment_attempt;
```

`009` 안에서도 **구조 확장 → 기존 POINT 식별 → CHECK 교체 → 품목 확장**이 여러 구문으로 실행된다. 기존 CHECK는 새 CHECK를 추가하는 동일 ALTER에서 교체하지만, 전체 migration은 원자적이지 않다. 중간에 쓰기가 들어오면 이행 기준이 달라질 수 있으므로 위 쓰기 중지를 유지한다. UPDATE는 payment_method만 바꾸며 기존 updated_at을 변경하지 않는다.

실패·중단 뒤에는 `SHOW CREATE TABLE`·행 조회로 어느 구문까지 반영됐는지 확인한다. **파일을 처음부터 무조건 재실행하거나 새 컬럼/테이블을 삭제해 재시도하지 않는다.** 전체 적용된 파일은 재적용하지 않고, 부분 적용이면 실제 구조·데이터와 일치하는 미완료 구문만 별도 검토해 재개한다. 앱 트랜잭션 rollback으로 DDL까지 되돌아갔다고 가정하지 않는다. 역방향 파괴적 rollback 스크립트는 제공하지 않는다.

## 기존 001~010 데이터베이스에 지문 저장 추가

대상 스키마가 010까지의 구조임을 확인하고 쓰기 중지·백업 후 **011만 한 번** 적용한다. 001~010은 수정하거나 재실행하지 않는다. 기존 PG 시도와 POINT·DRAFT·품목·재고·잔액·목록 버전을 보존하며, PG 시도의 새 세 컬럼은 모두 NULL로 남긴다. 카드 원문이 없으므로 새 카드 입력·기본값·가짜 해시로 지문을 소급 생성하지 않는다.

```sql
SELECT DATABASE(), VERSION(), @@SESSION.sql_mode;
SHOW CREATE TABLE payment_attempt;
SELECT status, COUNT(*) FROM payment_attempt GROUP BY status;

SOURCE apps/commerce-api/src/main/resources/db/schema/011-payment-request-fingerprint.sql;

SHOW CREATE TABLE payment_attempt;
SELECT COUNT(*) FROM payment_attempt
WHERE request_fingerprint IS NULL AND fingerprint_version IS NULL AND key_version IS NULL;
```

쓰기 중지 상태에서 기존 행 수와 NULL 묶음 수, 기존 컬럼의 값을 사전 보존값과 대조한다. 위 집계는 지문 값을 출력하지 않는다. 이미 011이 반영됐거나 구조가 다르면 자동 재적용하지 말고 확인한다. 011도 DDL이므로 앱 트랜잭션 rollback으로 되돌리지 않으며 컬럼 삭제나 DB 초기화를 복구로 사용하지 않는다. **현재 사용자 DB에는 이 절차를 실행하지 않았고, 검증은 격리 Testcontainers DB에서만 수행한다.**

## 기존 6개 테이블에 W4 인덱스 추가

`001`~`006`을 이미 적용한 DB에서는 테이블 생성 SQL을 다시 실행하지 않고, 대상 DB와 실제 product 구조를 확인한 뒤 **`007`만 한 번 수동 적용**한다.

```sql
SELECT DATABASE();
SHOW CREATE TABLE product;
SHOW INDEX FROM product;
```

`idx_product_brand_price`가 없을 때만 다음을 실행한다. 같은 이름이 이미 있으면 컬럼 순서와 방향이 `(brand_id ASC, deleted_at ASC, price ASC, id DESC)`인지 먼저 확인한다. 동일한 구조면 재적용하지 않고, 이름만 같거나 구조가 다르면 원인을 확인한 뒤 별도 변경을 판단한다. 실패·연결 중단 뒤 재시도할 때도 실제 생성 여부를 먼저 확인하며, 기존 인덱스를 임의로 삭제하지 않는다.

```sql
SOURCE apps/commerce-api/src/main/resources/db/schema/007-product-brand-price-index.sql;
SHOW INDEX FROM product;
```

인덱스 생성도 암묵적으로 커밋되는 DDL이므로 애플리케이션 트랜잭션의 rollback으로 되돌릴 수 있다고 가정하지 않는다. 메타데이터 잠금과 대기·실행 중 자원 사용이 발생할 수 있으므로 대상 DB의 활성 트랜잭션·작업 시간대를 확인한다. 이 스크립트는 무중단·무잠금 적용을 보장하지 않는다. 추가 인덱스의 저장 공간과 상품 생성·가격 변경·논리 삭제 시 인덱스 유지 비용도 부담한다.

이번 변경은 SQL 제공과 격리된 테스트 DB 검증 범위이며, 공유·운영 DB에 직접 적용한 것은 아니다.

## 저장 형태와 제약

### W4 버전 테이블 배포·캐시 활성화

기존 DB에는 새 코드 배포 전에 `008`을 적용하고 `SELECT * FROM product_list_revision`으로 `(1,0)`을 확인한다. **캐시 OFF도 쓰기의 버전 증가를 생략하지 않으므로 008이 필요하다.** CREATE 성공 후 INSERT만 실패했다면 트래픽을 막고 실제 행 상태부터 확인한다. 이미 존재하는 버전을 0으로 덮어쓰지 않는다. 빈 메타데이터의 동시 최초 INSERT는 교착이 발생할 수 있어 초기화를 사용자 요청에 맡기지 않는다.

로컬은 사용자 선택으로 캐시 기본 OFF다. 기존 local `ddl-auto=create`는 앱 재시작 시 DB를 재생성하기 때문이다. 현재 API 코드로 로컬 캐시를 실험하려면 먼저 014까지 수동 스키마를 준비하고 다음처럼 자동 재생성을 끄고 활성화한다. `experiment-1`은 이 DB 수명에만 사용하는 예시 구분값이다.

```shell
./gradlew :apps:commerce-api:bootRun --args='--spring.jpa.hibernate.ddl-auto=none --commerce.product-list-cache.enabled=true --commerce.product-list-cache.namespace=commerce:local:experiment-1:products:v1'
```

앱 전체에 `validate`를 사용하려면 위 커머스 SQL에 포함되지 않은 기존 `ExampleModel` 테이블까지 준비되어 있어야 한다. 선행 W5 커머스 전용 스키마 테스트의 **8개 엔티티**, W7 Outbox 전용 검증, 앱 전체 validate는 범위가 다르다. validate만으로 FK·유일·CHECK·collation·인덱스까지 모두 검증했다고 간주하지 않는다.

DB를 초기화·복원하거나 버전을 되돌릴 때는 **모든 앱의 namespace를 새 값으로 전환**하고 진행 중 이전 앱 요청을 정리한 후 트래픽을 재개한다. 이전 Redis 키와 재사용된 버전이 충돌하지 않게 하기 위한 조건이다. 운영 중 메타데이터 삭제·reset은 지원하지 않는다. 직접 SQL로 업무 데이터를 바꾸는 운영 작업도 같은 트랜잭션의 버전 증가 또는 트래픽 중단·namespace 교체 절차가 필요하다. 공유 Redis FLUSHALL을 복구 방법으로 사용하지 않는다.

### 업무 테이블

기존 업무·캐시 버전 테이블은 InnoDB·기본 utf8mb4_general_ci를 사용한다. **결제 시도의 외부 식별자 3개 컬럼은 utf8mb4_0900_bin**으로 대소문자·후행 공백을 구분한다. W7 Outbox는 위 절의 별도 binary collation을 사용한다. ID·가격·금액·잔액은 signed BIGINT, 재고·수량은 signed INT다. 업무 테이블에서 user ID만 애플리케이션 fixture가 지정하며 나머지 업무 ID는 AUTO_INCREMENT다. 별도 메타데이터 product_list_revision은 id=1 singleton이다. 브랜드·상품명과 주문 상품명 스냅샷은 VARCHAR(100)이고 이름에 유일 제약을 추가하지 않는다.

기존 6개와 payment_attempt→order를 더한 **7개 FK**는 모두 필수이며 참조 행의 물리 삭제를 RESTRICT한다. 상품·브랜드 논리 삭제는 deleted_at만 갱신하므로 주문항목·좋아요를 보존한다. 좋아요 취소는 좋아요 관계 행만 물리 삭제한다. 기존 복합 유일 제약은 사용자별 동일 상품 좋아요 한 건, 주문별 동일 상품 항목 한 건을 보장하며 각 단일 컬럼을 유일하게 만들지 않는다.

DB CHECK는 잔액·재고의 비음수, 현재 상품 가격의 1원 이상, 주문 수량의 양수, 스냅샷 단가·총액·결제액·시도 금액의 비음수를 보장한다. 주문의 새 상태 CHECK는 다음 조합을 사용한다. 필수 값에 `IS NOT NULL`을 명시해 SQL CHECK의 UNKNOWN 판정이 누락값을 허용하지 않도록 한다.

| 주문 상태 | payment_method | paid_amount · confirmed_at |
| --- | --- | --- |
| DRAFT | null · 미선택 | 모두 null |
| PAYMENT_PENDING / PAYMENT_FAILED | PG 필수 | 모두 null |
| CONFIRMED | POINT 또는 PG 필수 | paid_amount=total_amount, 두 값 모두 필수 |

### 결제 시도의 유일성·관찰 상태와 JPA의 차이

| 저장 항목 | 물리 제약·의미 |
| --- | --- |
| order_id | 주문 FK·UNIQUE. 주문당 한 시도 |
| attempt_key | BINARY(16)·NOT NULL·UNIQUE. UUID의 서비스 전역 유일성 |
| provider_account · provider_order_id | VARCHAR(100)·VARCHAR(255), 두 값 조합 UNIQUE |
| provider_account · transaction_key | transaction_key는 nullable VARCHAR(255), 알려진 거래의 두 값 조합 UNIQUE. 여러 미확인 NULL 거래 키를 허용 |
| request_fingerprint · fingerprint_version · key_version | nullable VARBINARY(32)·INT·INT. `ck_payment_attempt_fingerprint`는 전체 NULL 또는 정확히 32바이트와 두 양수 버전만 허용. 지문에는 유일 인덱스를 추가하지 않음 |
| NOT_OBSERVED | transaction_key=null. send_started_at=null 또는 시각. 로컬 발송 이력과 PG 관찰을 분리 |
| PENDING / SUCCESS | transaction_key·send_started_at 필수 |
| UNKNOWN / FAILED | send_started_at 필수, transaction_key는 null 가능. 효과 0건 확정 거절의 거래 키를 꾸미지 않음 |

PaymentAttempt의 `orderId`는 **scalar 필드**이며 JPA Order 객체 연관을 추가하지 않았다. 따라서 Hibernate 자동 생성 스키마만으로 payment_attempt→order FK가 생기지 않으며 **010의 실제 FK를 수동 SQL 테스트로 검증**한다. 관찰 상태 CHECK와 011의 지문 묶음 CHECK도 수동 SQL의 책임이다. JPA 저장·재조회 테스트와 실제 DDL 제약 테스트를 서로 대신하지 않는다.

지문은 BINARY(32)의 짧은 값 패딩을 피하기 위해 VARBINARY(32)와 `OCTET_LENGTH=32`를 함께 사용한다. 부분 NULL이 SQL의 UNKNOWN으로 통과하지 않도록 유효 묶음에 각각 `IS NOT NULL`을 명시한다. DB는 양수 형식/키 버전을 보존할 뿐 계산기가 그 버전을 지원한다고 보증하지 않는다. 모델의 `getRequestFingerprint()`는 미보유를 빈 Optional로 나타내고 `requireRequestFingerprint()`는 비교 불가 예외를 낸다. 기존 5인자 생성자는 과거 무지문 자료·fixture 호환용으로 남지만 실제 신규 준비에는 사용하지 않는다. 신규 지문 필수 저장과 내부 재요청 비교는 [주문 HMAC 연결](../week5/order-payment-fingerprint-connection-tdd-log.md), 고객 confirm의 같은 보호 규칙과 PG OFF 재요청은 [고객 API 연결](../week5/customer-payment-api-tdd-log.md)의 증거로 구분한다. 이번 고객 연결로 추가 DDL을 만들거나 사용자 DB를 변경하지 않았다.

DB의 행 단위 CHECK는 “주문 대기와 시도·모든 품목 HELD가 함께 커밋됐다”는 교차 행 조건까지 보장하지 않는다. NOT_OBSERVED·미발송 행을 표현할 수 있다는 것도 별도 준비 커밋을 채택했다는 뜻이 아니다. 내부 TX A의 실제 재고·시도·예약·주문 대기·발송 기록·목록 버전 공동 커밋은 선행 내부 연결 기록의 범위이며, 후속 주문 HMAC 연결에서 지문도 같은 TX에 포함했다. UUID 전역 유일 제약의 최종 충돌은 고정 문구의 PaymentRequestConflictException으로 전달하며 다른 DB 오류와 구분한다. DB 유일 제약만으로 PG의 POST 1회까지 보장했다고 주장하지 않는다.

FK는 브랜드의 미삭제 상태나 주문 소유권을 확인하지 않는다. 이름 strip, 주문 항목 최소 한 건, 소계·총액 계산, 재고·포인트 검증과 여러 행의 원자성은 JPA 모델과 application 트랜잭션이 담당한다. 운영 CHECK를 추가한 이유로 해당 도메인 검증이나 HTTP 오류 처리를 삭제하지 않았다.

인덱스는 브랜드의 미삭제 상품 존재 조회, 고객 상품의 최신·가격 정렬, 상품별 좋아요 집계, 본인 좋아요·주문 최신순 조회를 지원한다. W4의 `007`은 브랜드·미삭제 조건을 적용한 뒤 가격 오름차순·ID 내림차순으로 읽는 경로를 지원한다. 좋아요 수 정렬은 관계를 집계한 다음 정렬하며 캐시 카운터를 추가하지 않았다. 인덱스의 존재·컬럼 순서 검증 및 W4의 고정 seed 측정은 대용량 운영 부하의 처리량 보장을 뜻하지 않는다.

## 애플리케이션 설정과 초기 사용자

[환경변수 HMAC 키 설정](commerce-policy-decisions.md#키-관리와-검증-범위)은 DB 설정과 별개다. `PAYMENT_FINGERPRINT_CURRENT_KEY_VERSION`과 `PAYMENT_FINGERPRINT_KEYS`(`{"1":"<Base64-encoded-key>"}` 형식 설명용 placeholder)를 Spring `systemEnvironment`에서만 읽으며 YAML·CLI·Java system properties의 대체값은 사용하지 않는다. 키는 DB에 저장하지 않고 시작 시 한 번 구성하며 자동 refresh·기본 키·시작 시 랜덤 생성은 없다. 잘못된 설정은 앱 기동 실패가 아니라 계산·비교 불가로 처리한다. 합성 키의 실행 결과는 [키 설정 기록](../week5/payment-fingerprint-key-configuration-tdd-log.md)을 따른다. 실제 환경변수 키 설정·사용자 DB 접근·추가 SQL 실행은 하지 않았다. 설정만으로 기존 무지문 행을 채우지 않는다. 내부 신규 준비가 설정 포트로 계산한 지문을 TX A에 저장하며 기존 NULL 행에 소급 생성하지 않는다.

[jpa.yml](../../modules/jpa/src/main/resources/jpa.yml)의 local/test 기본값은 `ddl-auto=create`다. `007`을 위한 모델의 인덱스 annotation은 추가하지 않았으므로 Hibernate가 테이블을 다시 생성하면 수동 인덱스도 유지되지 않는다. 수동 생성한 스키마·데이터·인덱스를 유지하려면 실행 시 자동 생성을 끈다.

```shell
./gradlew :apps:commerce-api:bootRun --args='--spring.jpa.hibernate.ddl-auto=none'
```

사용자 ID·역할·외부 식별자는 [FixtureUserIdentityRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserIdentityRepository.java)의 단일 매핑을 따른다. 운영 SQL에 같은 매핑을 복사하거나 별도 외부 ID 컬럼·회원 가입 기능을 추가하지 않았다. [FixtureUserInitializer](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserInitializer.java)가 애플리케이션 시작 시 `alice=1`, `bob=2`, `admin=3`의 빠진 user 행을 잔액 0으로 만든다. 이미 있는 행은 잔액과 감사 시각을 덮어쓰지 않는다. 재시작으로 충전한 잔액이 0으로 돌아가지 않는 동작은 사용자 영속성 테스트에서 별도로 검증한다.

모든 감사·확정·발송 시작 시각은 DATETIME(6)이다. DATETIME 자체에 시간대가 없으므로 기존 `hibernate.timezone.default_storage=NORMALIZE_UTC`·`hibernate.jdbc.time_zone=UTC`를 유지하며 UTC로 해석한다. 직접 SQL로 시각을 넣을 때도 UTC 값을 사용한다. 이전 한글·이모지 100 코드 포인트와 마이크로초 시각의 JPA 왕복 검증에 W5 발송 시각·결과 보존 검증을 연결한다.

배포 대상에서도 strict SQL mode와 CHECK 집행 여부를 확인한다. SQL 파일 자체가 세션 SQL mode를 변경하지는 않는다.

```sql
SELECT VERSION(), @@SESSION.sql_mode;
SHOW CREATE TABLE product;
SHOW CREATE TABLE `order`;
SELECT id, point_balance FROM `user` ORDER BY id;
```

## 검증 방법과 테스트 데이터 경계

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.infrastructure.schema.CommerceSchemaIntegrationTest' \
  --tests 'com.loopers.infrastructure.schema.W5PaymentSchemaIntegrationTest' \
  --tests 'com.loopers.infrastructure.payment.PaymentAttemptPersistenceIntegrationTest' \
  --tests 'com.loopers.infrastructure.payment.PaymentAttemptFingerprintPersistenceIntegrationTest' \
  --tests 'com.loopers.application.coupon.CouponIssuanceIntegrationTest' \
  --tests 'com.loopers.infrastructure.outbox.OutboxSchemaIntegrationTest' \
  --tests 'com.loopers.infrastructure.brand.BrandSchemaIntegrationTest' \
  --console=plain -q
```

선행 전체 스키마 테스트는 전용 임시 MySQL 컨테이너에서 `001`~`011` 수동 SQL을 적용하고 **업무 7개 + 버전 메타데이터, 총 8개 엔티티**를 Hibernate `validate`로 검사한다. 기존 FK·유일·수치·Unicode·시각·singleton·revision 보존 검사를 유지한다. DDL 재실행 실패가 기존 버전 42를 0으로 초기화하지 않는지도 확인한다. `007` 인덱스는 별도 assertion으로 검사한다. 테스트 소유 DB만 사용한다.

W7 `OutboxSchemaIntegrationTest`는 별도 소유 MySQL에 **수동 012만 적용**해 Outbox 매핑 validate, 완성 문자열·메타데이터·시각의 왕복, 실제 INSERT 뒤 rollback, 동시 eventId 충돌, binary 식별·JSON/상태 CHECK를 검증한다. 이 저장 구조 검사만으로 주문·Outbox 공동 TX나 실제 relay·broker·consumer를 검증했다고 보지 않는다. 공동 TX는 [원자성 기록](../week7/outbox-atomicity-tdd-log.md), 실제 DB·broker 연결은 [relay 기록](../week7/outbox-relay-tdd-log.md)으로 구분한다. 2단계 relay에 추가 DDL은 없으며 사용자 DB에 적용하지 않았다.

W5PaymentSchemaIntegrationTest는 **빈 DB에 전체 SQL 적용**, **001~008의 POINT·DRAFT fixture에 009~011 적용**, **001~010의 기존 PG fixture에 011만 적용**을 구분한다. 기존 컬럼 보존·지문 전체 NULL 보존·32바이트/양수 버전/부분 NULL 제약과 기존 상태 CHECK·FK·유일성 등을 검증한다. PaymentAttemptPersistenceIntegrationTest의 기존 식별·상태·rollback 검증에 PaymentAttemptFingerprintPersistenceIntegrationTest의 지문 왕복·별도 재조회·불변성과 실제 flush 후 rollback을 연결한다. 선행 [W5 저장 기록](../week5/order-payment-persistence-tdd-log.md)은 당시 증거로 보존하고 지문 저장 당시의 실행 결과는 [지문 저장 기록](../week5/payment-request-fingerprint-persistence-tdd-log.md), 내부 서비스 연결은 [주문 HMAC 연결 기록](../week5/order-payment-fingerprint-connection-tdd-log.md)을 따른다. 문서의 실행 명령이나 검사 계획 자체를 통과 증거로 세지 않는다.

API-13의 저장 데이터 손상 방어 테스트는 Hibernate가 생성한 테스트 스키마에 수량 0을 직접 준비해 서버의 500 처리·무차감 상태 보존을 확인한다. 실제 운영 SQL의 CHECK(quantity > 0)는 그 직접 UPDATE부터 거절한다. 두 테스트의 저장소 준비 방식과 검증 책임을 구분하며, 운영 제약을 완화해서 손상 fixture를 허용하지 않는다. 이전 주 실행 기록·측정은 [W2 전체 스키마 기록](commerce-tdd-schema-log.md)에 그대로 보존한다.
