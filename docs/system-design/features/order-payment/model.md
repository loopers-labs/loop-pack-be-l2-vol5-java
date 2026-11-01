# 결제 상태·저장 모델과 불변조건

기존 절 번호 참조: [2.8](pg-client.md#28-pg-http-도구와-테스트-서버의-구성).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

기존 머리말의 목표 설계/POINT 전환 표현은 작성 당시 설명을 보존한 것이다. 현재 구현·검증 상태는 [설계 진입점](../../system-design.md)과 실행 기록을 따른다.

PG 상태 규칙은 [3.5](#35-pg-결제의-상태와-불변조건), 저장 필드와 제약은 [3.6](#36-pg-저장-모델과-제약)을 따른다. 아래 내용은 목표 설계이며 기존 POINT 구현에서 전환해야 한다. 과거 POINT 주문은 별도 PG 시도를 만들지 않고 보존한다.

## 3.5 PG 결제의 상태와 불변조건

### 주문·결제·확인 결과의 구분

주문 상태는 내부 업무의 완료 여부, `payment.status`는 저장한 결제 결과, `payment.verificationStatus`는 마지막 확인 결과다. HTTP 응답 코드는 해당 요청의 처리 결과이며 이 세 상태를 대신하지 않는다. 결제 시도가 결과와 확인 정보를 저장한다. 동시 결과는 Order 잠금 아래 반영하고 최근 확인 정보는 DB에 마지막 반영한 관찰을 나타낸다. 요청 시작 순서나 PG의 사건 시각 순서를 보장하지 않는다.

|상황|주문 상태|payment.status|verificationStatus|재고|
|---|---|---|---|---|
|DRAFT 생성·TX A 전 생성 회로 차단|DRAFT|payment 객체 생략|생략|변경 없음|
|TX A commit 후 첫 PG 결과 확인 전|PAYMENT_PENDING|UNKNOWN|NOT_CHECKED|예약 유지|
|유효한 PG PENDING 확인|PAYMENT_PENDING|PENDING|VERIFIED|예약 유지|
|첫 생성 응답 유실·해석 불가|PAYMENT_PENDING|UNKNOWN|UNAVAILABLE|예약 유지|
|TX A 후 생성 회로의 확실한 미발송을 TX B에 반영|PAYMENT_FAILED|FAILED, failure_kind=CIRCUIT_REJECTED|기존 확인 정보 유지, 최초 NOT_CHECKED|예약 한 번 반환|
|TX A 후 생성 POST의 확실한 최초 풀 획득 timeout을 TX B에 반영|PAYMENT_FAILED|FAILED, failure_kind=POOL_ACQUIRE_TIMEOUT|기존 확인 정보 유지, 최초 NOT_CHECKED|예약 한 번 반환|
|유효한 SUCCESS를 TX B에 반영|CONFIRMED|SUCCESS|VERIFIED|예약 소비, 추가 차감 없음|
|유효한 FAILED 또는 생성 전 확정 거절을 TX B에 반영|PAYMENT_FAILED|FAILED|VERIFIED|예약 한 번 반환|
|기존 시도의 조회 불가·0건|현재 상태 유지|현재 상태 유지|UNAVAILABLE|현재 예약 처리 유지|
|복수 거래·식별·금액·종결 결과 충돌|현재 상태 유지|현재 상태 유지|CONFLICT|임의 소비·반환 없음|
|종결 뒤 시작한 새 권위 조회가 PENDING 보고|종결 유지|종결 유지|CONFLICT|변경 없음|
|과거 POINT 확정 주문|CONFIRMED|SUCCESS, method=POINT|생략|과거 처리 보존|

마지막 행의 POINT 결제에는 PG 시도·요청 금액·거래 키를 만들어 붙이지 않는다. 실제 저장한 포인트 사용액과 성공 결제액을 보존한다. 신규 PG 결제의 포인트 사용액은 0이며, 결제 대기·실패에서는 성공 결제액을 응답하지 않는다.

### 결과 반영의 불변조건

1. 하나의 주문에는 하나의 PG 결제 시도만 허용한다. 동일 시도의 confirm 재요청은 재고를 다시 확보하거나 PG 생성을 다시 호출하지 않는다. 실패 후 다른 키로 새 시도를 만들지 않는다.
2. TX A 전 생성 회로 차단은 DRAFT·결제 행 없음·재고 효과 없음으로 유지해 이후 고객 confirm을 허용한다. 이미 결제 행이 있는 요청은 회로보다 저장 결과를 우선한다. TX A의 예약·시도·주문 대기 상태는 함께 commit하거나 함께 rollback한다. TX A 실패에는 외부 PG 호출이 없다. commit 후 외부 호출의 실패는 이미 저장한 예약을 자동 rollback하지 않는다.
3. TX B는 결제 결과·주문 상태·예약 소비 또는 반환·반환에 따른 재고와 이력을 함께 commit한다. 하나라도 저장에 실패하면 TX B 전체를 rollback한다. 외부 PG에서 완료한 사실까지 rollback되는 것은 아니다.
4. SUCCESS·FAILED는 업무 효과의 종결 상태다. 동일 종결 결과를 다시 확인해도 주문 확정·예약 소비·반환을 반복하지 않는다. 반대 종결 결과는 기존 결과를 덮어쓰지 않고 충돌로 남긴다. 이 종결 비교는 같은 거래로 확인된 관찰에만 적용하며, 저장 키와 다른 유효 거래·금액 불일치는 먼저 [업무 충돌 코드의 판정 순서](#확인-정보의-반영-순서와-종결-처리)를 따른다.
5. 종결을 먼저 반영한 뒤 도착한 생성 PENDING·timeout은 종결 상태를 후퇴시키지 않는다. 종결 후 별도 대사의 실패는 종결을 유지하면서 이번 확인 결과만 UNAVAILABLE로 표현하고 202를 반환한다. 반면 종결 뒤 새 권위 조회가 PENDING을 보고하면 CONFLICT·409이며 종결은 유지한다.
6. 콜백 본문은 확인 계기다. 고정 PG 계정 범위의 권위 조회로 주문·거래·금액을 검증한다. 생성의 즉시 200 SUCCESS는 동일한 식별·금액 검증 뒤 추가 조회 없이 반영할 수 있다.
7. 미확인·PENDING·조회 0건·시간 경과만으로 실패 종결·재고 반환·PG 재생성을 수행하지 않는다. 대사는 기존 거래 조회와 로컬 결과 반영만 수행한다.
8. 반환은 예약 수량을 현재 가용 수량에 한 번 더하는 보상이다. 주문 이전의 재고 값으로 되돌려 다른 주문이나 관리자 변경을 덮어쓰지 않는다. 예약 후 상품이 삭제되어도 결과 처리를 마무리하고 삭제 상태는 보존한다.

공개 필드·HTTP·오류 계약은 [5장](../../common/api-contract.md), 외부 호출 전후 순서는 [4.3](processing.md#43-주문-확정)을 따른다.

## 3.6 PG 저장 모델과 제약

저장 단위·FK 부재의 근거와 대안은 [A.17](../../decisions/payment.md#a17-결제-시도재고-예약의-저장-단위), 확인 순서·종결의 숨은 비용과 재검토 조건은 [A.20](../../decisions/payment.md#a20-업무-종결과-확인-순서충돌-요약)에 둔다.

기존 Domain/JPA 통합 모델 원칙을 유지한다. 아래 필드 외 공통 id·created_at·updated_at·deleted_at은 BaseEntity 매핑을 사용한다. Payment·PaymentConflict의 deleted_at은 항상 null이며 삭제 API는 없다. 시각은 기존 UTC 저장 규칙을 따르고, Java enum은 이름 문자열로 저장한다. 기존 ENUM 열은 MySQL ENUM을 유지하고 신규 enum 열은 표의 VARCHAR로 매핑하며 상세는 아래 [열 타입과 ORM 매핑](#열-타입과-orm-매핑)을 따른다. 금액은 Money를 BIGINT 원 단위로 매핑한다.

### Orders의 확장과 POINT 보존

|필드|SQL 타입·null|의미·초깃값|
|---|---|---|
|`status`|기존 MySQL ENUM, NOT NULL 유지. 허용값 확장|DRAFT / PAYMENT_PENDING / CONFIRMED / PAYMENT_FAILED. 기존 DRAFT·CONFIRMED 행 보존|
|`payment_method`|VARCHAR(16), nullable|DRAFT null, 과거 확정 POINT, 신규 결제 시작 PG|
|`point_amount`|기존 nullable BIGINT|DRAFT null, 신규 PG는 0, 과거 POINT 저장값 보존|
|`paid_amount`|기존 nullable BIGINT|실제 성공한 결제액. 신규 PG 대기·실패 null|
|`total_amount`·품목|총액 BIGINT NOT NULL·품목 매핑 유지|생성 당시 값. 결제 시작·조회에서 현재 상품 가격으로 재산정하지 않음|

2026-10-22 승인한 목표 명칭은 totalAmount/total_amount, pointAmount/point_amount, paidAmount/paid_amount와 시도의 amount/amount다. 새 DB는 이 명칭으로 생성한다. 현재 코드의 orders는 기존 테이블이다. 보존할 데이터가 있는 DB의 **기존 데이터 보존 전환**과, 새 코드가 빈 테스트 DB에 만드는 **테스트용 새 스키마 생성**을 구분한다. 보존 전환은 아래 순서를 따른다. 기존 order_total·used_point_amount·payment_amount의 값은 이름만 전환하고 보존한다. 아래 SQL은 기존 스키마의 전환 계약 예시이며 DB 실행 기록이 아니다. 새 마이그레이션 도구는 도입하지 않는다.

1. **사전 확인(읽기 전용):** 대상 DB에서 `SHOW CREATE TABLE orders`·`SHOW CREATE TABLE stock_history`와 상태/원인별 건수를 확인한다. 기대 정의는 `orders.status ENUM('CONFIRMED','DRAFT') NOT NULL`, `stock_history.cause ENUM('ADMIN_CHANGE','ORDER_DEDUCTION') NOT NULL`이다. 이는 과거 테스트 실행 로그(`apps/commerce-api/build/test-results/test/TEST-com.loopers.CommerceApiContextTest.xml`)의 Hibernate 생성 DDL에서 관찰한 값이며 현재 DB 타입을 확인한 근거가 아니다. 기존 CONFIRMED의 used_point_amount·payment_amount·order_total이 기존 전액 POINT 계약과 일치하는지, 기존 DRAFT의 used_point_amount·payment_amount가 null인지도 확인한다. payment_method는 4단계에서 추가하는 신규 열이므로 이 단계에서 조회하지 않으며, SHOW CREATE TABLE에 기존 금액 열 이름이 있고 payment_method·새 금액 열 이름이 아직 없는지 확인한다.
2. **중단 기준:** 실제 열이 ENUM이 아니거나, 허용값이 기대 목록과 다르거나(새 값이 이미 일부 들어간 경우 포함), 저장 값에 목록 밖 값·빈 문자열이 있거나, 금액/DRAFT 검사가 불일치하거나, payment_method·새 금액 열 이름이 이미 있는 등 부분 전환 상태이면 전환을 중단한다. 자동 재실행·임의 복구는 하지 않는다. 값·허용값·열을 임의로 제거·수정·변환하지 않고 원인을 확인해 사용자와 결정한다.
3. **기존 ENUM 허용값 확장:** 기존 값과 그 순서를 그대로 두고 새 값을 뒤에 추가한다. NOT NULL과 SHOW CREATE TABLE에 나타난 기본값·문자셋·collation·주석 등 기존 열 속성은 MODIFY에 그대로 다시 적고 바꾸지 않는다. 아래 예시는 기본값이 없는 과거 생성 DDL 기준이다. 새 값은 이름순으로 붙여 과거 로그에서 관찰한 Hibernate의 이름순 생성 목록과 같아지도록 하며, 실제 새 스키마와의 일치는 4.5에서 확인한다. point_history.cause는 바꾸지 않는다.
4. **금액 컬럼 이름 전환·결제 방식 열 추가, 과거 POINT 보정:** 아래 SQL의 후반부다.
5. **신규 테이블:** payment·payment_conflict를 아래 표의 타입·nullable·unique·index·CHECK와 [열 타입과 ORM 매핑](#열-타입과-orm-매핑)대로 만든다. 이 DDL의 작성·실행은 구현 단위에서 수행한다.
6. **사후 확인과 새 코드 기동:** 상태/원인별 건수와 기존 행의 금액·품목·이력 값이 전환 전과 같고, 확장한 열의 nullable·기본값이 바뀌지 않았으며, 열 추가·보정 뒤 DRAFT의 payment_method·point_amount·paid_amount가 null이고 기존 CONFIRMED의 payment_method가 POINT인지 확인한다. 보존 데이터가 있는 DB에서는 새 코드를 create/drop 계열 ddl-auto로 기동하지 않으며 validate로 기동한다. 기존 local 프로필의 create 설정을 그대로 쓰면 이 보존 계약을 위반하므로 기동 설정은 구현 단위에서 분리한다. 테스트의 재시작 검증은 [5.7](../pg-simulator/fixture-contract.md#57-테스트-pg-제어와-상태-보존)의 validate 계약을 따른다.

```sql
-- 3) 기존 ENUM 허용값 확장: 기존 값·순서 유지, 새 값만 뒤에 추가
ALTER TABLE orders
  MODIFY COLUMN status ENUM('CONFIRMED','DRAFT','PAYMENT_FAILED','PAYMENT_PENDING') NOT NULL;
ALTER TABLE stock_history
  MODIFY COLUMN cause ENUM('ADMIN_CHANGE','ORDER_DEDUCTION','ORDER_RELEASE','ORDER_RESERVATION') NOT NULL;

-- 4) 금액 컬럼 이름 전환·결제 방식 열 추가, 과거 POINT 보정
ALTER TABLE orders
  RENAME COLUMN order_total TO total_amount,
  RENAME COLUMN used_point_amount TO point_amount,
  RENAME COLUMN payment_amount TO paid_amount,
  ADD COLUMN payment_method VARCHAR(16) NULL;
UPDATE orders
SET payment_method = 'POINT'
WHERE status = 'CONFIRMED' AND payment_method IS NULL;
```

ENUM 목록 끝에 값을 추가하는 MODIFY는 MySQL 8.0 Reference Manual의 Online DDL Operations 절에서 저장 크기가 변하지 않는 한 즉시 또는 in-place 변경으로 설명하는 경우다. 대상 DB에서 실제 수행 방식은 확인하지 않았다. 컬럼 이름 변경 뒤에도 point_amount·paid_amount·total_amount의 값과 관계를 유지한다. 신규 테이블 생성 후 PG 시도가 있는 행에 위 과거 보정을 무차별 재실행하지 않는다. 기존 확정 주문에 Payment·PaymentConflict를 역으로 만들지 않는다. 신규 PG 주문에는 confirmWithPoints를 호출하지 않는다.

### 열 타입과 ORM 매핑

현재 Hibernate ORM 6.6의 MySQL 생성 DDL에서 `@Enumerated(EnumType.STRING)`만 둔 Java enum 필드는 native ENUM 열이 된다. 기존 orders.status·stock_history.cause가 ENUM으로 생성된 것을 과거 테스트 로그에서 관찰한 근거이며 신규 필드에 대한 실행 확인은 아니다. 기존 ENUM은 유지하고 신규 enum 열은 설계의 VARCHAR가 되도록 매핑을 명시한다.

|구분|대상|DB 타입|매핑 계약|
|---|---|---|---|
|기존 ENUM 열|`orders.status`, `stock_history.cause`|MySQL ENUM 유지·허용값 확장|기존 `@Enumerated(EnumType.STRING)` 유지. VARCHAR로 바꾸지 않음. point_history.cause는 변경 없음|
|신규 enum VARCHAR 열|`orders.payment_method` VARCHAR(16) nullable, `payment.status` VARCHAR(16) NOT NULL, `payment.failure_kind` VARCHAR(32) nullable, `payment.verification_status` VARCHAR(16) NOT NULL, `payment_conflict.code` VARCHAR(48) NOT NULL|VARCHAR, 값 CHECK 없음|enum 이름 문자열 `AttributeConverter`(`@Convert`) + 표의 길이·nullable을 적은 `@Column`. Hibernate가 native ENUM이나 값 CHECK를 만들지 않게 함. 알 수 없는 저장 값은 읽기 실패|
|그 밖의 문자열 열|attempt_key, provider, provider_account, provider_order_id, transaction_key, observed_order_id·observed_transaction_key, observed_status|각 표의 타입|Java String이면 enum 매핑 없이 `@Column`의 길이·nullable. CHAR(36)·ASCII binary collation·TEXT처럼 기본 생성으로 나오지 않는 정의는 열 정의를 명시해 표와 일치시킴. Java enum으로 모델링한다면 위 신규 enum VARCHAR 규칙을 적용|

**매핑 변경(2026-10-23 사용자 승인):** 처음 계획한 `@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.VARCHAR)`는 I3-1 스키마 검증에서 Hibernate 6.6이 VARCHAR enum 열마다 `CHECK (열 in (...))`를 자동 생성함을 확인했다. 이 CHECK는 위 표(CHECK는 payment의 amount > 0만)와 보존 전환 DB에 없고, enum 값 추가 때 CHECK도 바꿔야 하는 비용이 있다. 그래서 신규 enum 열은 이름 문자열 변환기로 매핑하고 허용값 결합은 도메인 guard로 보호한다. 대안인 값 CHECK 유지는 DB가 잘못된 값을 더 막지만 설계 표·전환 SQL에 같은 CHECK를 추가해야 한다. 열 정의 명시의 결과와 validate 동작은 4.5의 스키마 타입 검증으로 확인한다. 타입 선택과 무관하게 SQL 타입·길이·nullable·collation은 각 표가 원본이다.

### PaymentModel — payment

주문당 한 결제 행으로 준비·UNKNOWN·PENDING·SUCCESS·FAILED를 모두 표현한다. 성공 전용 payment나 별도 시도 테이블을 추가하지 않는다. 결제 본체에는 결제 상태와 최근 확인 결과·시각을 두고 충돌 상세는 payment_conflict로 분리한다. 확인 시작/적용 카운터는 두지 않는다.

|필드|SQL 타입·null|의미|
|---|---|---|
|`order_id`|BIGINT NOT NULL|주문. 생성 뒤 불변|
|`attempt_key`|CHAR(36) ASCII, NOT NULL|기존 paymentAttemptKey 계약. UUID를 소문자 표준 형식으로 저장·비교|
|`provider`|VARCHAR(16) ASCII, NOT NULL|고정 TEST_PG|
|`provider_account`|VARCHAR(32) ASCII, NOT NULL|고정 합성 계정 135135. 내부 고객 ID와 구분|
|`provider_order_id`|VARCHAR(32) ASCII, NOT NULL|LP-ORD- + 내부 양수 주문 ID의 최소 10자리 숫자. 저장 뒤 불변|
|`transaction_key`|VARCHAR(255) ASCII, nullable|PG에서 검증한 거래 키. 한번 결합하면 변경 금지|
|`amount`|BIGINT NOT NULL|저장 total_amount의 양수 값. 생성 뒤 불변|
|`status`|VARCHAR(16) NOT NULL|UNKNOWN / PENDING / SUCCESS / FAILED. 초기 UNKNOWN|
|`failure_kind`|VARCHAR(32), nullable|FAILED에서 PG_FAILED / REJECTED_NO_EFFECT / CIRCUIT_REJECTED / POOL_ACQUIRE_TIMEOUT. CIRCUIT_REJECTED는 TX A 후 actual pgCreate decorator의 권한 획득 거절이고 그 호출의 실행 본문 미진입(POST 0)이 함께 확인된 확실한 미발송 실패이며 PG 거절이 아님. 판정 조건은 [2.8 미발송 판정 장치](pg-client.md#생성-회로와-사전-차단). OPEN 차단·HALF_OPEN 탐색 슬롯 소진을 포함. POOL_ACQUIRE_TIMEOUT은 TX A 후 생성 POST의 최초 연결 풀 획득 timeout이고 그 실행의 POST 전송이 없음이 확인된 확실한 미발송 실패이며, 판정 조건은 같은 2.8 절의 POOL_ACQUIRE_TIMEOUT 판정. 일반 연결/읽기 timeout·후속 풀 오류는 포함하지 않음. Java enum에 값만 추가하고 DB 열은 그대로. 그 외 null|
|`dispatch_claimed_at`|UTC 시각, NOT NULL|TX A의 발송 권한 소비 시각. 실제 HTTP 전달 완료의 증거가 아님|
|`verification_status`|VARCHAR(16) NOT NULL|NOT_CHECKED / VERIFIED / UNAVAILABLE / CONFLICT. 초기 NOT_CHECKED|
|`verification_checked_at`|UTC 시각, nullable|마지막 적용 확인의 처리 시각. 최초 null|

- `uk_payment_order`: UNIQUE(order_id). 한 주문의 결제 행 하나.
- `uk_payment_key`: UNIQUE(attempt_key). 다른 주문에서도 같은 UUID를 재사용하지 못한다.
- `uk_payment_transaction`: UNIQUE(provider, provider_account, transaction_key). 외부 거래를 두 결제에 결합하지 못한다. 결합 전 null은 허용한다.
- `idx_payment_provider_order`: 일반 INDEX(provider, provider_account, provider_order_id). 외부 주문 ID는 내부 order_id의 결정적 매핑이므로 별도 unique는 두지 않는다. 생성·결과 적용 때 저장 외부 ID가 해당 내부 주문의 매핑과 정확히 일치하는지 검증한다. 이 선택은 잘못된 우회 SQL까지 DB에서 차단하지 못한다.
- 키 열은 대소문자를 구분하는 ASCII binary collation을 사용한다. transactionKey는 `[A-Za-z0-9_-]{1,255}`이며 trim·대소문자 변환을 하지 않는다. UUID만 값으로 파싱해 정규화한다.
- CHECK는 amount > 0이다. 실패 종류·결제/주문 상태 결합은 도메인 guard와 통합 검증으로 보호한다.
- 물리 FK는 추가하지 않는다. 주문·상품·결제의 참조는 같은 TX에서 확인하며 하드 삭제 경로를 제공하지 않는다. unique·CHECK는 고아 참조나 테이블 간 상태 일치를 대신하지 않는다.

외부 ID 예: 주문 42 → LP-ORD-0000000042. 콜백은 저장한 제공자 범위·외부 ID로 기존 결제를 찾고 숫자 역파싱으로 라우팅하지 않는다. 일반 인덱스 조회 결과 0건은 ORDER_NOT_FOUND, 2건 이상은 내부 정합성 오류 500이며 임의 선택·PG 조회·결과 변경을 하지 않는다. 후보 식별은 별도 readOnly TX에서 Entity 없는 orderId 목록으로 수행하고 중복을 숨기지 않는다. 정확히 한 행이면 orderId만 반환한 뒤 TX를 종료한다. 새 업무 TX의 첫 업무 SQL로 Order를 잠그고 Payment를 처음 일반 조회하여 제공자/계정·외부 ID 매핑과 현재 상태를 검증한다. 후보 읽기의 스냅샷·Entity는 재사용하지 않는다. 거래 키는 주문·금액·제공자 범위를 검증한 뒤 결합하며 이후 바꾸지 않는다.

### 고정 주문 품목과 재고 확보·반환

stock_reservation 테이블·Entity·Repository는 두지 않는다. 확보 수량의 원본은 생성 뒤 변경·삭제하지 않는 order_item의 product_id·quantity다. 같은 상품의 중복 품목은 생성 시 병합한다. 주문당 결제 하나·모든 품목 일괄 확보·부분 결제/취소 없음의 현재 범위에서 주문·결제 상태로 확보 여부를 판정한다.

|주문·결제 상태|재고 의미|
|---|---|
|DRAFT·결제 행 없음|확보 전. 재고 변경 없음|
|PAYMENT_PENDING·UNKNOWN/PENDING|TX A에서 모든 고정 품목 수량을 선차감한 상태. 확보 유지|
|CONFIRMED·SUCCESS|확보 수량의 소비 완료. 추가 차감 없음|
|PAYMENT_FAILED·FAILED|모든 고정 품목 수량을 한 번 반환한 상태|

TX A는 Order 잠금 아래 DRAFT·결제 부재를 검사하고, 활성 Product를 ID 오름차순으로 잠가 전체 수량을 확인한다. 선차감·ORDER_RESERVATION 이력·Payment 생성·PAYMENT_PENDING을 함께 commit하거나 함께 rollback한다. Payment 행과 주문 대기가 확보 완료의 영속 표시이며 DB 변경 전후의 프로세스 중단도 같은 TX의 원자성을 따른다.

TX B는 Order를 먼저 잠근 뒤 Payment를 처음 일반 조회하여 현재 주문·결제 상태와 고정 품목을 확인한다. 미종결의 유효 SUCCESS는 주문·결제만 종결하고 재고를 더 차감하지 않는다. 미종결의 확정 FAILED/REJECTED_NO_EFFECT 또는 실제 생성 회로가 확실히 미발송한 LOCAL_NOT_DISPATCHED만 삭제 포함 Product를 ID 오름차순으로 잠그고 저장 품목 수량을 현재 가용량에 더한다. ORDER_RELEASE 이력·PAYMENT_FAILED·FAILED를 같은 TX에서 commit한다. 이미 같은 FAILED이면 재고와 반환 이력을 변경하지 않는다. 반대 종결·식별 충돌·시간 경과·조회 실패에는 반환하지 않는다.

Stock.increase는 양수·Math.addExact 범위를 검사한다. overflow·품목 누락·상품 물리 누락은 500과 TX 전체 rollback이며 임의 수량 보정·부분 반환은 없다. 삭제 상태·브랜드·가격은 보존하고 commit 뒤 기존 캐시 DEL을 연결한다. ORDER_RESERVATION·ORDER_RELEASE를 사용하고 과거 ORDER_DEDUCTION은 유지한다. 성공에는 추가 StockHistory가 없다. 상품별 독립 예약 상태는 조회할 수 없으며 부분 반환·다중 시도·품목 변경이 필요하면 이 모델을 다시 검토한다.

### PaymentConflictModel — payment_conflict

충돌이 발생한 결제에 마지막 요약 0~1행만 저장한다. 모든 관찰이나 충돌의 이력을 쌓지 않는다. 기존 BaseEntity의 id·created_at·updated_at·deleted_at 매핑을 사용하며 삭제 경로는 없고 deleted_at은 null이다.

|필드|SQL 타입·null|의미|
|---|---|---|
|`payment_id`|BIGINT NOT NULL|기존 Payment의 scalar ID. 생성 뒤 불변|
|`code`|VARCHAR(48) NOT NULL|MULTIPLE_TRANSACTIONS / IDENTITY_MISMATCH / AMOUNT_MISMATCH / TERMINAL_MISMATCH / TERMINAL_REGRESSION / TRANSACTION_ALREADY_BOUND|
|`observed_order_id`·`observed_transaction_key`|TEXT, nullable|파싱한 PG 충돌 식별자. 카드·원문 JSON은 저장하지 않음|
|`observed_amount`|BIGINT, nullable|파싱한 관찰 금액|
|`observed_status`|VARCHAR(16), nullable|파싱한 PG 상태|
|`observed_count`|INT, nullable|복수 거래 건수. 임의 거래의 키·금액·상태를 대표값으로 선택하지 않음|
|`detected_at`|UTC 시각, NOT NULL|마지막 요약의 저장 시각|

`uk_payment_conflict_payment`: UNIQUE(payment_id)로 결제당 요약 하나를 보장한다. FK는 두지 않고 같은 TX에서 Payment 존재와 관찰의 주문/결제 연결을 검사한다. 확인 순번 컬럼·관련 CHECK는 두지 않는다. 최근 verification_status와 마지막 충돌 요약은 독립이며 정상 확인으로 요약을 지우지 않는다.

충돌 경로는 Order를 먼저 잠근 뒤 Payment와 기존 PaymentConflict를 일반 조회한다. 요약이 없을 때도 같은 Order 잠금으로 생성을 직렬화하며 자식의 선행 잠금 조회는 하지 않는다. 충돌을 반영할 때마다 요약을 생성/교체한다. 더 일찍 시작한 조회가 늦게 도착한 충돌도 이 TX에서 마지막 반영하면 현재 요약을 교체한다. 시작 시각·응답 수신 시각·detected_at을 비교해 교체를 막지 않는다. 조회·생성·교체·확인 정보 갱신은 같은 TX B에서 commit한 뒤 409를 반환한다. summary 저장 실패는 TX 전체 rollback·500이다. 외부 거래 unique 위반은 TX B rollback 뒤 새 recordConstraintConflict TX에서 같은 순서로 요약을 저장한다. 보통의 주문 응답은 payment만 읽고 충돌 상세를 조인하지 않는다. 새 충돌 조회/관리 API는 추가하지 않는다.

### 확인 정보의 반영 순서와 종결 처리

최근 확인 결과는 Order 잠금 아래 **DB에 마지막으로 반영해 commit한 관찰**이다. 요청 시작 시각·PG 처리 시각의 최신 상태를 뜻하지 않는다. 별도 확인 순번, 발급 카운터, 순번 토큰/비교는 사용하지 않는다. verification_checked_at·detected_at은 표시용 시각이며 역전 방지나 덮어쓰기 순서 판정에 쓰지 않는다.

TX A는 Order 잠금 아래 payment의 verification_status=NOT_CHECKED·verification_checked_at=null로 최초 INSERT한다. 콜백 후보 식별은 별도 readOnly TX에서 orderId만 얻고 종료한다. 콜백/대사의 prepareVerification은 새 짧은 일반 @Transactional 업무 TX의 첫 업무 SQL로 Order를 잠그고 Payment를 일반 조회하여 소유권·결제 존재·조회 식별자와 종결 여부를 확인한 뒤 `(paymentId, orderId, source, terminalAtStart)`를 복사하고 종료한다. 업무 TX는 기존 REPEATABLE READ를 유지하고, Order 잠금 전 일반 조회나 다른 TX의 Entity 재사용을 금지한다. 번호 발급이나 확인 정보 갱신을 하지 않으며 PG 호출은 이 TX가 끝난 뒤 수행한다. Order 잠금을 사용하므로 업무 TX를 readOnly로 바꾸지 않는다.

TX B는 Order 잠금 후 처음 일반 조회한 최신 결제 상태와 잠근 주문 상태에 관찰을 검증·적용한다. 모든 결제/충돌 쓰기는 같은 Order 잠금 아래 commit까지 수행하며, 업무 TX의 선행 일반 조회·Entity 재사용이 이 조건을 깨뜨리지 않는지 실제 MySQL로 검증한다. 아래 무시 조건과 LOCAL_NOT_DISPATCHED를 제외하고 적용한 PG 관찰의 verificationStatus·verification_checked_at을 기록한다. LOCAL_NOT_DISPATCHED는 PG 응답/조회가 아니므로 기존 확인 정보를 유지하며 최초 NOT_CHECKED/null인 FAILED도 허용한다. 거래 키를 만들지 않으며 확인 불가·프로그램 오류를 이 관찰로 바꾸지 않는다. 예를 들어 먼저 시작한 A가 PENDING을 늦게 반환해 나중에 시작한 B의 조회 실패 뒤에 commit하면 마지막 확인은 VERIFIED다. B가 마지막으로 commit하면 UNAVAILABLE다. 유효 SUCCESS·FAILED는 아직 미종결이면 요청 시작 순서와 관계없이 반영한다. 종결 뒤 QUERY 조회 불가는 업무 종결을 유지한 채 UNAVAILABLE로 기록한다. 이 표시 비용을 수용하고 시작 순서 기준 최신성은 보장하지 않는다.

기존 종결 보호는 유지한다. 종결 뒤 도착한 CREATE UNAVAILABLE은 거래를 확인할 수 없는 관찰이므로 같은 거래 확인을 요구하지 않고 업무 상태와 확인 정보 모두 변경하지 않은 채 저장 종결을 반환한다. 종결 뒤 도착한 CREATE PENDING은 저장 거래 키와 같은 거래이고 식별·금액 충돌이 없을 때만 같은 방식으로 변경하지 않으며, 다른 유효 거래이거나 금액이 다르면 아래 판정 순서의 충돌로 기록한다. QUERY의 PENDING은 준비 때 미종결이면 늦은 관찰로 무시하고, 준비 때부터 종결이면 TERMINAL_REGRESSION 충돌이다. 이를 위해 source와 terminalAtStart는 토큰에 유지한다. 반대 종결, REJECTED_NO_EFFECT와 결합 거래 공존, PG 효과 0건/로컬 미발송 실패 뒤 유효 거래 관찰은 TERMINAL_MISMATCH다. 같은 FAILED 문자열이라도 거래 존재와 효과 0건의 모순을 덮지 않는다.

**업무 충돌 코드의 판정 순서 (2026-10-22 합의):** 형식 검증을 통과한 거래 관찰은 아래 순서로 판정한다. 앞 단계에서 충돌이 나면 그 코드로 기록하고 뒤 단계의 비교 결과로 코드를 바꾸지 않는다. 새 코드·필드·컬럼·테이블은 추가하지 않고 기존 충돌 코드를 쓴다. 이는 업무 충돌 코드의 우선순위이며 pgCreate/pgLookup 회로 표본 분류는 2.8을 그대로 따른다.

1. **복수 거래:** 주문별 조회의 유효 거래가 2건 이상이면 MULTIPLE_TRANSACTIONS다. 혼합 주문 목록의 우선순위·회로 분류를 포함한 기존 복수 거래 계약을 유지하며, 임의 거래를 고르거나 개별 거래 상태로 코드를 덮지 않는다.
2. **거래 식별 불일치:** 제공자/계정, 외부 주문 ID, 조회 요청/응답 거래 키(adapter의 직접 대상 위반), 이미 결합된 저장 거래 키 중 하나라도 다르면 IDENTITY_MISMATCH다. 저장 키 KA와 관찰 키 KB가 다르면 금액도 달라도 AMOUNT_MISMATCH가 아닌 IDENTITY_MISMATCH다. KA를 KB로 바꾸거나 KB를 결합하지 않는다. KB의 상태가 저장 종결과 반대여도, PENDING이어도 이 판정이 먼저이며 다른 거래의 결과를 같은 거래의 종결 충돌로 비교하지 않는다.
3. **금액 불일치:** 식별이 일치하는 거래에서 금액만 다르면 기존 AMOUNT_MISMATCH다.
4. **종결 비교:** 식별·금액 검증을 통과한 같은 거래에만 기존 종결 규칙을 적용한다. 같은 종결은 업무 효과 0, 반대 종결은 TERMINAL_MISMATCH, 종결 전에 준비한 QUERY PENDING은 무시, 종결 후 준비한 QUERY PENDING은 TERMINAL_REGRESSION, 종결 뒤 같은 거래의 늦은 CREATE PENDING은 변경 없음이다. 저장 키가 null인 효과 0건/로컬 미발송 실패 뒤 거래 발견은 기존 TERMINAL_MISMATCH다.

1~3의 충돌은 업무 상태·저장 거래 키·재고·이력을 유지하고 충돌 요약과 확인 정보를 기존 TX B 계약대로 commit한 뒤 409다. 요약에는 선택한 코드와 함께 파싱한 관찰 주문 ID·거래 키·금액·상태·건수를 기존 필드로 보존하며, 코드 우선순위 때문에 관찰 금액을 버리지 않는다. 거래를 확인할 수 없는 UNAVAILABLE(CREATE·QUERY)은 이 순서의 대상이 아니며 기존 규칙을 따른다.

**adapter와 TX B의 일치:** adapter가 정하는 MULTIPLE_TRANSACTIONS와 직접 대상 위반 IDENTITY_MISMATCH는 위 1·2단계에 해당하므로 TX B가 그대로 유지한다. 저장 키와 내부 기대 금액은 TX B만 알기 때문에, TX B는 adapter 관찰(CONFLICT 근거 포함)에 담긴 파싱한 거래 키·외부 주문 ID·금액으로 위 순서를 다시 적용한다. adapter가 준 코드보다 앞 단계의 불일치를 발견하면 그 코드로 기록하고, 뒤 단계 코드로 내리지 않는다. 예를 들어 생성 응답의 금액만 요청과 달라 adapter가 CONFLICT로 넘겼더라도 관찰 키가 저장 키 KA와 다르면 IDENTITY_MISMATCH다. 이 판정에는 기존 PaymentObservation의 거래·주문·금액·상태 근거면 충분하며 새 필드·타입은 필요하지 않다.

거래 키가 아직 null이면 저장 키 비교를 하지 않고 기존 최초 결합·다른 결제의 거래 결합 unique 위반(TRANSACTION_ALREADY_BOUND 복구 TX) 계약을 따른다. 콜백 알림 키가 저장 키와 다르다는 사실만으로는 판정하지 않으며, 권위 조회가 그 거래를 반환한 뒤에 위 순서를 적용한다(정상 거래 부재 404는 기존 UNAVAILABLE).

결제/확인 정보 판정은 Payment.recordObservation, 마지막 충돌 요약 반영은 PaymentConflict.record(evidence)가 담당한다. 정상 관찰은 verificationStatus를 VERIFIED/UNAVAILABLE로 바꿀 수 있지만 과거 충돌 요약을 지우지 않는다. 다음 충돌만 요약을 교체하며 전체 감사 이력은 보장하지 않는다. 같은 종결을 반복해도 재고·이력 효과는 없고 반대 종결은 보존·충돌 기록이다. 실패한 TX의 확인 정보·충돌 요약은 업무 변경과 함께 rollback되어 직전 commit 결과를 유지한다.

### 도메인 메서드의 사전·사후 조건

|모델·메서드|사전 조건과 결과|
|---|---|
|`OrderModel.startPgPayment()`|DRAFT·결제 방식/사용액/성공액 null. PG·pointAmount=0·PAYMENT_PENDING으로 변경|
|`OrderModel.confirmPg(amount, confirmedAt)`|PG·PAYMENT_PENDING, amount=total_amount. paid_amount·CONFIRMED·최초 시각 함께 기록. 6주차 목표 확장은 3.7이며 구현 전|
|`OrderModel.failPg()`|PG·PAYMENT_PENDING. paid_amount=null·PAYMENT_FAILED|
|`PaymentModel.bindTransaction(key)`|미결합이면 검증한 키 결합, 같은 키면 변경 없음. 다른 키는 recordObservation이 결합 전에 IDENTITY_MISMATCH로 판정하므로(금액 불일치보다 우선) 결합·교체하지 않음. DB unique 위반은 TX 경계에서 처리|
|`PaymentModel.recordObservation(...)`|식별·금액·종결 판정 결과 반환. 위 복수 거래 → 거래 식별 → 금액 → 종결 판정 순서를 따름. 결제·확인 정보만 변경하고 충돌 근거를 TX 조율자에 반환|
|`PaymentConflictModel.record(evidence)`|부모 Order 잠금·결제/관찰 연결 검증 뒤 이 TX의 충돌 내용으로 마지막 요약 생성/교체|
|`ProductModel.releaseStock(quantity)`|고정 주문 품목의 양수 수량을 Stock.increase로 위임. 활성 상태 요구·상품 restore 없음|
|`Stock.increase(quantity)`|양수·long 가산 overflow 검사 후 변경, StockChange 반환|

TX B는 PG 주문과 결제의 orderId·amount·외부 주문 ID·상태 및 고정 품목의 양수 수량·총액을 검사한다. PAYMENT_PENDING은 결제 UNKNOWN/PENDING·pointAmount=0·paidAmount=null, CONFIRMED는 SUCCESS·paidAmount=amount, PAYMENT_FAILED는 FAILED·paidAmount=null이어야 한다. 재고 확보/반환 완료는 이 상태 전이를 고정 품목·재고·이력과 같은 TX에서 수행한 사실로 판정한다. 불일치·누락 품목은 500·rollback이며 일부 품목만 임의 처리하지 않는다. 같은 종결의 멱등 처리도 상태 정합성을 검사한 뒤 수행한다.
