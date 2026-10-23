# 실제 PG 주문·장애 실험 재현

검증 결과와 실제 주문 ID는 [result.md](result.md)를 따른다. 아래는 같은 경계를 다시 확인하는 절차다. 새 실험마다 새 상품·주문·UUID를 사용한다.

## 실행과 기본 요청

[실행 문서](../../../docker/payment-lab/README.md)에 따라 **전용 학습 DB**의 SQL을 준비하고 두 앱을 별도 JVM으로 실행한다. 재시작에는 `payment-lab`을 사용하고 기존 DB에 빈 DB용 000 SQL을 재적용하지 않는다.
이번 확인은 host MySQL 13316, commerce 8080/관리 8082, PG 8081을 사용했다. PG와 commerce의 MySQL 포트 환경 변수는 각각 `PG_MYSQL_PORT`, `COMMERCE_MYSQL_PORT`다.

1. 관리자 API로 브랜드와 가격 1,000원·재고 5개인 상품을 만든다.
2. `X-USER-ID: 1`로 해당 상품 2개를 주문한다. 관리자 대사도 이 fixture 헤더를 사용한다.
3. 아래 예시의 주문 ID와 UUID를 바꾸어 confirm한다. 금액은 body로 받지 않고 주문에서 결정한다.

```http
POST http://127.0.0.1:8080/api/v1/orders/{orderId}/confirm
X-USER-ID: 1
Content-Type: application/json

{
  "paymentAttemptKey": "새 표준 UUID",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "testOptions": {
    "preset": "NORMAL_SUCCESS",
    "overrides": {}
  }
}
```

합성 카드만 사용한다. 요청 body나 카드 값을 실험 로그에 출력하지 않는다. 같은 주문을 재요청할 때는 최초 UUID와 실효 조건을 그대로 사용한다.
상태 확인은 고객 `GET /api/v1/orders/{id}`, 운영 목록은 `GET /api-admin/v1/payments/incomplete?page=0&size=100`이다. 대사는 본문 없이 관리자 `POST /api-admin/v1/orders/{id}/payment/reconcile`를 호출한다.

## 실험별 조건과 확인 순서

지연 값은 밀리초다. 실험용 polling은 유한한 시간 동안만 상태를 확인하며 애플리케이션의 자동 재조회 구현과 구분한다.

| 상황 | preset / overrides | 확인 순서 |
|---|---|---|
| callback 먼저·중복 요청 | NORMAL_SUCCESS / createResponseMode=DELAY, createResponseDelayMs=1700 | 같은 요청 4개를 동시에 보냄 → callback이 지연 응답보다 먼저 완료 → 최종 SUCCESS/CONFIRMED·재고 3·PG 거래/주문 기록 각 1 |
| 유실 뒤 실패 | LOST_FAILURE / processingDelayMs=6000, callbackMode=DROP | confirm 202·재고 3 → PG FAILED까지 대기 → 수동 대사 200·재고 5 → 같은 confirm/대사 반복에도 변화 없음 |
| 관리자 수량 변경 | NORMAL_FAILURE / processingDelayMs=5000 | confirm → `PUT /api-admin/v1/products/{id}/stock`에 `{"stock":10}` → callback 실패 처리 후 재고 12 |
| 상품 삭제 | NORMAL_FAILURE / processingDelayMs=5000 | confirm → `DELETE /api-admin/v1/products/{id}` → 실패 후 재고 5·deleted=true 유지 |
| 성공 후 내부 실패 | NORMAL_SUCCESS / processingDelayMs=2500 | 아래 실패 trigger 설치 → confirm → SUCCESS/PAYMENT_PENDING·재고 3·기록 0·MANUAL_REQUIRED → trigger 제거·PG 종료 → 대사로 CONFIRMED |
| commerce 중단 | NORMAL_SUCCESS / processingDelayMs=10000, callbackMode=DROP, lookupResponseMode=DELAY, lookupResponseDelayMs=1900 | confirm → 비동기로 수동 POST → DB에서 RUNNING/lastCheckedAt 확인 직후 commerce 종료 → 같은 DB로 재시작 → 목록 GET → 명시 대사 |
| PG 중단 | NORMAL_SUCCESS / processingDelayMs=20000, callbackMode=DROP | confirm 후 PG PENDING 확인 → PG 종료·같은 DB로 재시작 → 같은 거래 키 SUCCESS → 수동 대사 |
| 생성 전 거절 | REJECT_BEFORE_CREATE / 없음 | confirm 200·PAYMENT_FAILED·재고 5·거래 0·RESTORED |
| callback 응답 timeout | NORMAL_SUCCESS / processingDelayMs=2000 | 아래 지연 trigger 설치 → confirm → PG 송신 실패 로그와 commerce CONFIRMED를 함께 확인 → trigger 제거 |
| 반환 한도 초과 | NORMAL_FAILURE / processingDelayMs=2500, callbackMode=DROP | confirm → 재고 2147483647 설정 → PG FAILED 후 대사 202·FAILED 근거/차감 유지·MANUAL_REQUIRED → 재고 10 설정 → 대사 200·재고 12 |

재시작 때는 **이 실험에서 기동한 정확한 Java 프로세스만** 종료한다. 테이블·볼륨은 그대로 둔다.
commerce 중단 사례는 재시작 직후에도 주문 PAYMENT_PENDING, 작업 RUNNING, 실행 ID·사용 횟수·최근 조회 시각이 같아야 한다. GET 뒤에도 같고 수동 POST 뒤에만 실행 ID가 바뀌어야 한다.
PG 중단 사례는 재시작 뒤 거래 키와 거래 수가 같아야 한다. PG가 완료됐어도 callback=DROP이면 수동 확인 전 commerce는 대기로 남는다.

## 내부 실패와 응답 지연 주입

다음 SQL은 **새 전용 실험 DB에서 선택한 한 주문에만** 사용한다. 같은 이름의 trigger가 없는지 먼저 확인하고 `실험_주문_ID`를 숫자로 바꾼다. 애플리케이션 코드에 장애 옵션을 추가하지 않는다.
MySQL CLI에서 DELIMITER를 포함한 파일로 실행한다. 자동화한다면 설치 이후부터 finally 정리 구간을 둔다.

```sql
DELIMITER //
CREATE TRIGGER loopers.lab_r02_reject_record
BEFORE INSERT ON loopers.order_records FOR EACH ROW
BEGIN
  IF NEW.order_id = 실험_주문_ID THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='R02 lab forced record failure';
  END IF;
END//
DELIMITER ;
```

SUCCESS 관찰이 저장되고 주문 기록 삽입만 실패하면 주문 상태·차감 기록·작업 완료가 모두 rollback되어야 한다. 기록 0·재고 3·주문 PAYMENT_PENDING·작업 MANUAL_REQUIRED를 함께 확인한다. trigger를 제거한 뒤 PG를 종료해도 저장된 성공으로 수동 대사가 끝나야 한다.

callback 응답 timeout은 새 주문에 별도 `lab_r02_slow_record` trigger를 같은 방식으로 만들되 IF 내부를 `DO SLEEP(3);`로 바꾼다. PG의 callback 송신 응답 제한은 2초이므로 해당 거래 키의 `PG callback 전달 실패` 로그가 남는다. commerce는 3초 뒤 주문 기록·CONFIRMED·APPLIED를 저장해야 한다.

두 trigger는 각각 해당 실험의 finally에서 제거한다. 실험이 중단됐어도 재기동 전에 남은 trigger를 확인한다.

```sql
DROP TRIGGER IF EXISTS loopers.lab_r02_reject_record;
DROP TRIGGER IF EXISTS loopers.lab_r02_slow_record;
```

## DB에서 같이 확인할 값

각 스키마의 읽기 권한이 있는 실험 계정으로 확인한다. 도메인 fixture와 재고 변경은 위 실제 API를 사용한다.

| 테이블 | 확인할 항목 |
|---|---|
| loopers.orders | status가 성공 CONFIRMED / 실패 처리 완료 PAYMENT_FAILED / 미완료 PAYMENT_PENDING |
| loopers.payment_attempts | order_id별 1행, outcome·transaction_key·conflict_reason·저장 테스트 조건 |
| loopers.payment_recovery_tasks | state·execution_id·used_rounds·last_checked_at·last_reason |
| loopers.stock_adjustments | 성공 CONSUMED / 실패 RESTORED / 미완료 DEDUCTED |
| loopers.products | 가용 stock·deleted 유지 |
| loopers.order_records | 성공 주문만 PG 기록 1행. 실패는 0행 |
| loopers.point_bills | 실험 order_id의 type=USE가 0행 |
| loopers_test_pg.pg_transactions | payment_attempts.provider_order_id와 연결한 거래 수·동일 키·최종 status |

완료 후 두 JVM과 실험용 MySQL만 정지하고 데이터는 보존한다. 임시 trigger 0개와 기존 POINT 기록의 금액·상태·시각 보존도 확인한다.
기존 사용자 MySQL/Redis, 다른 작업의 프로세스, JMeter 변경과 원문은 정리 대상으로 삼지 않는다.
