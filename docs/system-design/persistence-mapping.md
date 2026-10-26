# 영속성 매핑 설계

Commerce 도메인 모델과 JPA 저장 모델의 경계를 정리한다.<br>
주문 결제의 트랜잭션·조건부 갱신 동작은 [주문 결제 요청](use-cases/order-payment-request.md)과 [결과 반영](use-cases/order-payment-result.md)을 참고한다.

## 매핑 경계

| 관심사 | Domain | Infrastructure |
| --- | --- | --- |
| 상품과 브랜드 관계 | `Product`가 `brandId` 보유 | `ProductJpaEntity`가 `BrandJpaEntity`와 외래 키 연관관계 보유 |
| 주문 품목 | `Order` 내부의 식별자 없는 `OrderItem` 값 | `OrderItemJpaEntity`가 테이블 행의 DB 식별자 보유. 상품 ID는 DB FK로 설정하지 않음 |
| 영속성 매핑 | Commerce 도메인 객체는 JPA 어노테이션과 공통 JPA `BaseEntity`에 의존하지 않음 | JPA 엔티티가 테이블·연관관계를 매핑하고, 해당 엔티티의 공통 생성·수정·삭제 필드에는 `BaseEntity` 사용 |
| 객체 변환 | Repository 인터페이스로 조회·저장 계약 제공 | Repository 구현체와 Mapper가 도메인 객체와 JPA 엔티티 변환 |
| 결제 시도 | `PaymentAttempt`가 주문을 `orderId`로 참조 | `payment_attempts` 테이블과 시도 키·주문 ID·`providerOrderId` 유일 제약을 두고 Repository에서 변환. 저장 모델은 [아래 결제 저장 설계](#저장-모델과-데이터-전환) |
| 결제 실패의 재고 보상 기준 | `OrderItem`의 상품 ID·수량 스냅샷 | 기존 `order_items`의 저장 값을 사용한다. 결제 요청 이후 품목·수량은 갱신하지 않는다. 시도 종결과 재고 반환의 원자성은 [결과 반영 설계](use-cases/order-payment-result.md#재고-보상-트랜잭션)를 따른다 |
| 주문 결제수단 | `Order.paymentMethod` | `orders.payment_method`를 그대로 복원한다. 주문 상태로 결제수단을 추론하지 않는다 |
| 주문 확정 시각 | `Order.confirmedAt` | `orders.confirmed_at`을 그대로 복원한다. 비어 있으면 다른 시각으로 채우지 않는다. 저장은 [결과 반영의 조건부 갱신](use-cases/order-payment-result.md#중복-방지)만 한다 |
| 상품 구매 활동 | `commerce-streamer`의 구매 활동과 소비 완료 기록 | `commerce-streamer`의 JPA 엔티티가 `product_purchase_activity`, `processed_order_confirmation`을 매핑한다. 주문·상품 테이블에 FK를 두지 않고, `commerce-api`는 이 테이블을 읽거나 쓰지 않는다 |

## 선택 이유와 영향

Commerce 도메인 모델과 JPA 엔티티를 분리해 업무 규칙이 JPA 프록시와 영속성 컨텍스트의 생명주기에 묶이지 않게 한다. `Order`는 도메인에서 `OrderItem`을 내부 값으로 보유하고, 저장 관계와 행 식별자는 Infrastructure의 JPA 엔티티에 둔다.

이 구조에서는 매퍼가 필요하고, 도메인 객체의 변경을 Repository 저장·갱신 계약으로 명시적으로 반영해야 한다.<br>
주문 결제처럼 동시성 제어가 필요한 변경은 유스케이스에 맞는 조건부 갱신 계약을 사용한다.

## 상품 갱신

상품 이름·가격 수정, 재고 설정과 단독 삭제는 `id`와 `deleted_at IS NULL` 조건으로 필요한 컬럼만 갱신한다. 대상 행이 없으면 기존 없는 상품 오류로 변환한다. 같은 값으로 설정하는 요청은 [API 계약](api-contracts.md#상품재고)의 성공 계약을 유지한다.

상품 수정은 재고를 덮어쓰지 않는다. 재고 차감·설정·반환 사이의 순서와 트랜잭션 규칙은 [결제 요청](use-cases/order-payment-request.md#관리자-재고-설정과의-기대값)과 [결과 반영](use-cases/order-payment-result.md#재고-보상-트랜잭션)을 따른다.

## 좋아요 관계 저장

좋아요의 `insertIfAbsent`는 같은 DataSource·트랜잭션의 JDBC INSERT로 관계를 추가한다. 좋아요 관계의 추가는 이 경로만 사용하며, 조회·삭제는 JPA 매핑을 유지한다. JDBC INSERT의 생성·수정 시각은 UTC로 기록한다. 동시 중복의 판정과 트랜잭션 처리 이유는 [오류 변환 설계](error-mapping.md#좋아요-동시-중복-등록)에 정리한다.

## 저장 모델과 데이터 전환

기존 `order_items`의 상품 ID·수량 스냅샷을 재고 차감과 실패 보상의 기준으로 그대로 쓰며, 결제 요청 이후 품목·수량은 바꾸지 않는다. 카드 입력은 저장하지 않는다.

| 저장 모델 | 변경 | 제약과 복원 규칙 |
| --- | --- | --- |
| `orders` | `payment_method` 추가. 상태에 `PAYMENT_PENDING`·`PAYMENT_FAILED`, 결제 결과에 `FAILED` 허용 | `payment_method`는 DRAFT에서 NULL, 결제 시작 이후 `POINT` 또는 `PG`다. |
| `orders` 확정 시각 | nullable `confirmed_at` `DATETIME(6)` 추가 | `PAYMENT_PENDING → CONFIRMED` 조건부 갱신이 같은 UPDATE에서 기록한다. 이후 바꾸지 않는다. 이벤트의 `confirmedAt`과 같은 값이 되도록 마이크로초로 자른 시각을 저장한다. |
| `processed_order_confirmation` (신규, streamer) | ID, `event_id`, `order_id`, `BaseEntity` 공통 시각 | `event_id`와 `order_id`는 각각 유일하고 필수다. 저장 뒤 바꾸지 않는다. 이미 처리한 이벤트인지만 표시하며 확정 시각과 품목은 갖지 않는다. 같은 사실인지는 [사실의 비교](use-cases/order-confirmed-event.md#사실의-비교)에 따라 구매 활동과 비교한다. |
| `product_purchase_activity` (신규, streamer) | ID, `order_id`, `product_id`, `quantity`, `confirmed_at`, `BaseEntity` 공통 시각 | `(order_id, product_id)`는 유일하고 수량은 양수다. 같은 이벤트의 소비 완료 기록과 한 트랜잭션에서 저장한다. `confirmed_at`은 주문 단위 값을 품목 행마다 두는 [의도한 반정규화](#구매-활동의-확정-시각)다. 랭킹 조회용 인덱스는 랭킹 설계에서 정한다. |
| `payment_attempts` (신규) | ID, `payment_attempt_key`(소문자 UUID 36자), `order_id`, `provider_order_id`, `amount`, `status`, nullable `transaction_key`, nullable `conflict_reason`·`conflict_detected_at`·`last_checked_at`, `BaseEntity` 공통 시각 | 시도 키, 주문 ID와 providerOrderId는 각각 유일하고 필수다. 금액·상태도 필수이며 금액은 저장된 주문 총액과 같다. 충돌 사유는 TX A에서 NULL로 저장하고 [충돌 기록](use-cases/order-payment-result.md#충돌-기록) 규칙으로만 채운다. [주기 조회](use-cases/order-payment-recovery.md#주기-조회)를 위해 `(status, last_checked_at)` 인덱스를 둔다. 미종결 시도가 전체 시도보다 훨씬 적다는 전제다. |

### 결제수단과 과거 주문 전환

PG 결제를 켜기 전에 변경 SQL로 상태·결제 결과의 ENUM 확장, `payment_method` 추가, `payment_attempts` 생성과 기존 `CONFIRMED` 주문의 `payment_method = POINT` 채우기를 한 번에 적용하고, `docker/mysql/init.sql`도 같은 스키마로 갱신한다. 기존 주문의 결제액·결제 결과·품목은 바꾸지 않고, DRAFT의 결제수단은 NULL로 둔다.<br>
이후 새 결제는 TX A에서 `PG`를 저장한다. 복원은 저장된 `payment_method`를 그대로 쓰며, 결제수단이 없는 확정 주문은 데이터 정합성 오류로 본다.

예약 수량은 기존 주문·품목을 합산하고 별도 집계 컬럼은 두지 않는다. `order_items(product_id)` 인덱스로 해당 상품의 품목을 좁혀 조회하며, 관리자 설정의 잠금과 격리 수준은 [결제 요청의 관리자 재고 규칙](use-cases/order-payment-request.md#관리자-재고-설정과의-기대값)을 따른다.

이미 PG 스키마를 쓰는 DB는 모든 쓰기를 멈춘 뒤 `docker/mysql/pg-payment-safety-migration.sql`을 한 번 적용한다. POINT 전용 기존 DB는 `pg-payment-migration.sql` 다음에 적용하고, 새 DB는 갱신된 `init.sql`을 사용한다. `order_items(product_id)` 인덱스를 추가하며 기존 가용 수량과 과거 주문·결제 사실은 변경하지 않는다. 용량 불변식에 위배되는 기존 값은 전환 전에 확인하고, 이 데이터 확인을 생략한 채 기동하지 않는다.

### 주문 확정 시각 전환

확정 이벤트를 켜기 전에 애플리케이션 쓰기를 멈추고 `docker/mysql/order-confirmed-at-migration.sql`로 `orders.confirmed_at`을 추가한다. 새 DB는 같은 컬럼과 구매 활동 테이블을 포함한 `init.sql`을 사용한다.

이미 확정한 주문(POINT·PG)의 `confirmed_at`은 NULL로 둔다. `updated_at` 등 다른 시각으로 추정해 채우지 않으며, 이 주문들의 확정 이벤트를 소급 발행하지 않는다.

구매 활동 테이블은 `commerce-streamer`가 소유하며 템플릿 구성에 따라 같은 MySQL schema에 둔다. 서비스별로 DB를 나누더라도 두 앱이 서로의 테이블을 읽지 않는 경계는 같다.

### 구매 활동의 확정 시각

`product_purchase_activity`의 키는 `(order_id, product_id)`이고 `confirmed_at`은 `order_id`에 딸린 값이므로, 같은 주문의 품목 행마다 같은 시각이 반복된다. 주문 단위 테이블로 나누지 않고 의도적으로 반정규화한다.

- 확정 시각은 바뀌지 않고, 한 주문의 행은 같은 이벤트에서 한 트랜잭션으로 함께 저장된다. 행끼리 값이 어긋날 갱신 경로가 없다.
- 이후 랭킹이 확정 시각 기간과 상품별 합산을 이 테이블 하나에서 조인 없이 처리한다.
- 처리 기록(`processed_order_confirmation`)은 중복 판정 장치이므로 업무 시각을 두지 않는다. 랭킹이 판정 장치를 조인해 업무 값을 읽지 않게 한다.

### 기존 PG DB의 enum 호환성

현재 `PaymentConflictReason`은 `MULTIPLE_TRANSACTIONS`, `TRANSACTION_MISMATCH`, `RESULT_CONTRADICTION`을 지원한다. 과거 버전에서 저장한 `MULTIPLE_TRANSACTIONS`도 그대로 복원하며 기존 기록을 삭제하거나 다른 사유로 치환하지 않는다.

충돌 사유를 두 개만 허용하는 기존 PG DB에는 애플리케이션 쓰기를 멈추고 신규 코드 기동 전에 [충돌 사유 확장 SQL](../../docker/mysql/pg-payment-conflict-migration.sql)을 적용한다. 기존 사유·감지 시각·상태·거래 키는 유지하면서 DB ENUM에 `MULTIPLE_TRANSACTIONS`를 허용한다. 현재 `init.sql` 또는 `pg-payment-migration.sql`로 새로 만든 테이블에는 이미 세 사유가 포함돼 있으므로 추가 적용이 필요하지 않다. 생성 SQL을 수정하는 것만으로 기존 테이블의 ENUM이 확장되지는 않는다.

기존 PG DB를 재사용한다면 다음 읽기 전용 조회로 현재 enum이 지원하지 않는 충돌 사유를 확인한다.

```sql
SELECT id, order_id, conflict_reason, conflict_detected_at
FROM loopers.payment_attempts
WHERE conflict_reason IS NOT NULL
  AND conflict_reason NOT IN ('MULTIPLE_TRANSACTIONS', 'TRANSACTION_MISMATCH', 'RESULT_CONTRADICTION');
```

결과가 없으면 지원하지 않는 충돌 사유는 없다. 이는 데이터 확인이며 DB ENUM 확장 여부는 별도로 확인한다. 결과가 있다면 신규 코드로 기동하기 전에 해당 기록의 보존·전환 방식을 정해야 한다. 알 수 없는 사유의 자동 삭제나 다른 충돌 사유로의 치환은 수행하지 않는다.

초기화한 로컬 DB나 새 DB에 현재 `init.sql`을 적용하는 경우에는 과거 값이 없으므로 이 데이터 전환이 필요하지 않다. 이 안내의 조회는 확인용이며 데이터나 스키마를 변경하지 않는다.

### 검증 기준

저장 뒤 `flush/clear`하고 새로 조회해 값과 관계를 확인한다. 결제 시도 저장소의 조건부 갱신은 [결과 반영의 관찰 반영 규칙](use-cases/order-payment-result.md#관찰-반영-규칙)을 기준으로 실제 MySQL에서 검증한다.

| 영역 | 상황 | 기대값 |
| --- | --- | --- |
| 저장·복원 | 결제 요청 저장 뒤 재조회 | 결제수단 `PG`, `PAYMENT_PENDING`, 시도 `REQUESTED`, 품목 스냅샷, 차감된 재고. 과거 POINT 주문과 함께 조회해도 결제수단이 섞이지 않음 |
| 데이터 전환 | 전환 전 포인트 확정 주문과 DRAFT | 확정 주문은 `POINT`와 기존 결제액·결과·품목 유지, DRAFT의 결제수단은 NULL |
| 충돌 사유 확장 | 기존 두 사유의 ENUM에 복수 거래 사유 추가 | 기존 사유·감지 시각·상태·거래 키 유지, NULL 유지, `MULTIPLE_TRANSACTIONS` 저장 가능 |
| 확정 시각 전환 | 전환 전 POINT·PG 확정 주문과 미확정 주문 | 기존 주문의 `confirmed_at`은 NULL, 다른 값 불변. 이후 성공 확정만 확정 시각 저장 |
| 구매 활동 저장 | 소비 저장 뒤 `flush/clear`하고 재조회 | 완료 기록 1건과 품목별 활동, 수량·확정 시각 보존, 유일 제약 위반 시 전체 rollback |
