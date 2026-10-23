# 결제 회복 검증을 다시 실행하는 방법

[요구사항](requirement.md) · [실제 결과](result.md) · [상태 흐름](../states/total.md) · [운영 확인](operations.md)

## 실행 환경

commerce와 테스트 PG를 별도 JVM으로 실행한다. 이번 실험은 기존 사용자 DB와 분리한 MySQL `payment-lab-verification`의 호스트 포트 13316을 사용했다. 두 앱은 같은 MySQL 안의 `loopers`와 `loopers_test_pg`를 각각 사용한다.

[학습 DB 실행 안내](../../../docker/payment-lab/README.md)에 따라 스키마를 준비한다. 기존 데이터가 있는 DB에는 빈 DB용 `000` SQL을 다시 적용하지 않는다. 재시작할 때도 테이블·볼륨을 초기화하지 않는다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
./gradlew.bat :apps:test-pg:check :apps:commerce-api:check
./gradlew.bat :apps:test-pg:bootJar :apps:commerce-api:bootJar
```

서로 다른 터미널에서 최신 실행 JAR을 사용한다.

```powershell
# PG 터미널
$env:PG_MYSQL_PORT = '13316'
$pgJar = (Get-ChildItem apps/test-pg/build/libs/*.jar |
    Where-Object Name -NotLike '*plain*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
& "$env:JAVA_HOME/bin/java.exe" -jar $pgJar --spring.profiles.active=payment-lab
```

```powershell
# commerce 터미널
$env:COMMERCE_MYSQL_PORT = '13316'
$commerceJar = (Get-ChildItem apps/commerce-api/build/libs/*.jar |
    Where-Object Name -NotLike '*plain*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
& "$env:JAVA_HOME/bin/java.exe" -jar $commerceJar --spring.profiles.active=payment-lab --scheduler.like-count.enabled=false
```

주소는 commerce 8080·관리 8082·PG 8081이다. `local`이나 `test` 프로필을 함께 사용하지 않는다. `payment-lab`은 스키마를 validate하며, 자동 조회 시작은 기본 활성화다. `test` 프로필의 confirm 자동 시작 비활성화는 기존 검사 정리와 백그라운드 작업의 경합을 피하기 위한 설정이다.

## 새 주문으로 시작하기

관리자 API로 가격 1,000원·재고 5개 상품을 만들고, 고객 API에서 2개를 주문한다. 이번 실험은 학습용 `X-USER-ID: 1`을 사용했다. 요청마다 새 주문과 UUID를 사용하고, 재요청 실험만 같은 UUID·실효 조건을 유지한다.

```http
POST /api/v1/orders/{orderId}/confirm
X-USER-ID: 1
Content-Type: application/json

{
  "paymentAttemptKey": "새 표준 UUID",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "testOptions": {
    "preset": "LOST_SUCCESS",
    "overrides": {
      "processingDelayMs": 3000,
      "callbackMode": "DROP"
    }
  }
}
```

합성 카드만 사용하며 요청 body를 실험 로그에 출력하지 않는다. 아래 조회로 저장 상태를 확인한다.

- 고객 상세: `GET /api/v1/orders/{orderId}`
- 운영 미완료 목록: `GET /api-admin/v1/payments/incomplete?page=0&size=100`
- 명시적 수동 대사: `POST /api-admin/v1/orders/{orderId}/payment/reconcile`

관리자 경로에는 1번 fixture 헤더가 필요하다. 고객 confirm·고객/관리자 reconcile·주문 GET 본문에는 `completed` 필드가 없으므로 `status`와 `paymentAttempt.recoveryState`를 본다. confirm/reconcile은 내부 완료 여부로 HTTP200/202를 선택하며 `completed`가 응답에 노출되는 것은 별도 callback 경로다. GET으로 확인하는 polling은 PG를 직접 조회하거나 자동 작업을 다시 시작하지 않는다. [폴백 응답 설명](../resilience/fallback.md)에 실제 응답 필드와 읽는 순서를 정리했다.

## 대표 실험

모든 지연 값은 밀리초다. `callbackMode=DROP`은 자동 조회가 결과를 알아내는 경로를 분명하게 보기 위한 조건이다.

표의 ‘처리 지연’은 `processingDelayMs`, ‘lookup DELAY/1500’은 `lookupResponseMode: "DELAY"`와 `lookupResponseDelayMs: 1500`을 뜻한다. 위 JSON의 `testOptions.overrides`에 지정한다.

| 상황 | preset / overrides | 확인할 내용 |
|---|---|---|
| 생성 응답 유실 후 성공 | LOST_SUCCESS / 처리 3000·callback DROP | 최초 202·재고 3 → 자동 SUCCESS/CONFIRMED·거래/주문 기록 각 1 |
| 생성 응답 유실 후 실패 | LOST_FAILURE / 처리 3000·callback DROP | 최초 재고 3 → 자동 FAILED/PAYMENT_FAILED·재고 5·주문 기록 0 |
| 4회 소진 | NORMAL_SUCCESS / 처리 30000·callback DROP | 자동 조회 4회 후 MANUAL_REQUIRED/RETRY_EXHAUSTED·재고 3. 같은 confirm은 자동 재개 안 함 |
| 느린 조회와 회로 | NORMAL_SUCCESS / 처리 60000·callback DROP·lookup DELAY/1500 | 느린 자동 조회 4회 후 수동 조회 1회로 공유 회로 OPEN. 새 주문의 생성은 가능하나 조회는 차단 |
| OPEN 회복 | NORMAL_SUCCESS / 처리 1000·callback DROP | OPEN 중 자동 회차 1·HTTP 0 → 10초 뒤 수동 빠른 조회 1건은 HALF_OPEN, 두 번째는 CLOSED |
| PG 재시작 | NORMAL_SUCCESS / 처리 6000·callback DROP·lookup DELAY/1900 | 첫 조회 중 우리 PG 프로세스만 종료·같은 DB로 재기동. 같은 거래 키·1행·사용 회차 한도 유지 |
| 업무 반영 실패 | NORMAL_SUCCESS / 처리 2500·callback DROP | 아래 주문 기록 실패 주입 후 SUCCESS 사실만 보존·업무 rollback. 주입 제거 후 PG를 꺼도 수동 재적용 가능 |

회로의 이전 표본이 실험에 영향을 줄 수 있다. 회로 재현은 다른 미완료 자동 작업이 없는 새 commerce 프로세스에서 시작하거나 기존 정상·실패 표본을 함께 기록한다. 이번에는 정상 표본 뒤 느린 표본이 5/10을 채워 OPEN이 됐다. 회로가 닫혀도 이미 수동 전환한 자동 작업을 되살리지 않는다.

PG 재기동 시간이 길면 4회 안에 결과를 확인하지 못할 수 있다. 이 경우도 한도 소진으로 수동 확인하며, 재기동을 이유로 새 자동 예산이나 새 거래를 만들지 않는 것이 기준이다.

## DB 대기·큐 만료·접수 거절을 함께 확인하기

새 주문 5개를 준비한다. 처음 두 주문의 결제 시도 UUID에만 아래 trigger를 적용하고, 처리 지연 45000·callback DROP으로 차례대로 confirm한다. 두 worker는 회차 저장에서 기다리고 두 작업은 큐에 남는다. 다섯 번째는 접수·100ms 뒤 재접수 모두 거절된다.

```sql
DELIMITER //
CREATE TRIGGER loopers.lab_r03_delay_round
BEFORE UPDATE ON loopers.payment_recovery_tasks FOR EACH ROW
BEGIN
  IF NEW.attempt_key IN ('첫_새_UUID', '두번째_새_UUID')
     AND NEW.used_rounds > OLD.used_rounds THEN
    DO SLEEP(35);
  END IF;
END//
DELIMITER ;
```

DB 대기는 회로 측정 밖이다. 처음 두 작업은 회차 1이 commit된 뒤 30초 만료를 확인해 HTTP 없이 멈춘다. 큐의 두 작업은 실행 차례에 이미 예산이 끝나 회차 0·HTTP 0이다. 다섯 번째는 EXECUTOR_REJECTED·회차 0이다. 이 실험은 30초에 작업을 강제 종료한다는 뜻이 아님도 보여준다.

선택한 두 UUID만 대상으로 하며, 설치 전에 같은 이름의 trigger가 없는지 확인한다. 설치 이후부터 finally에 해당하는 정리 구간을 두고 아래 명령으로 제거한다. PG 완료 뒤 명시적인 수동 대사로 같은 거래를 한 번 조회해 마무리한다. 자동 회차는 늘거나 초기화되지 않아야 한다.

```sql
DROP TRIGGER loopers.lab_r03_delay_round;
```

## PG 성공 뒤 업무 rollback 확인하기

[R02 검증 방법](../r02-pg-order-payment/verification.md#내부-실패와-응답-지연-주입)과 같은 방식으로, 새 주문 하나의 `order_records` 삽입만 SQLSTATE 45000으로 실패시킨다. trigger 이름은 이번 실험용 이름으로 분리한다.

자동 조회가 성공을 관찰한 뒤 `outcome=SUCCESS`, 주문 PAYMENT_PENDING·기록 0·재고 3·차감 DEDUCTED·작업 MANUAL_REQUIRED/APPLICATION_FAILED가 함께 남아야 한다. 오류가 MySQL 데드락이 아니므로 내부 반영을 자동 반복하지 않는다.

trigger를 제거하고 PG 프로세스를 정지한다. 수동 대사가 PG HTTP 0회를 추가하면서 CONFIRMED·기록 1·CONSUMED로 끝나면 저장한 사실과 업무 반영의 분리를 확인한 것이다. PG를 다시 기동하거나 실험을 마칠 때 자신이 만든 프로세스만 정리한다.

## 회차 commit 후 HTTP 전 강제 종료

정확한 중단 위치를 고정하려면 실험 commerce를 로컬 JDWP로 실행한다. 범용 장애 옵션을 제품 코드에 추가하지 않는다.

```powershell
& "$env:JAVA_HOME/bin/java.exe" '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005' -jar $commerceJar --spring.profiles.active=payment-lab --scheduler.like-count.enabled=false
& "$env:JAVA_HOME/bin/jdb.exe" -connect 'com.sun.jdi.SocketAttach:hostname=127.0.0.1,port=5005'
```

jdb에서 다음 중단점을 설정한 뒤 다른 터미널에서 새 주문을 confirm한다. 조건은 처리 15000·callback DROP이다.

```text
stop in com.loopers.infrastructure.resilience.pay.CircuitPaymentLookupGateway.lookup
```

빈 메서드 진입에서 멈추면 하위 HTTP adapter 호출 전이다. 이번에는 `line=21 bci=0`, worker `payment-recovery-2`에서 멈췄고 별도 DB 조회로 RUNNING·usedRounds=1을 확인했다. HTTP 요청 행 로그에는 이 주문의 PG GET이 0이었다.

1. 실행 ID·회차·lastCheckedAt·차감 상태를 기록하고 자신이 띄운 commerce PID만 강제 종료한다.
2. 같은 DB로 디버거 없이 재기동한다. PG가 이미 SUCCESS여도 commerce 저장 결과는 아직 PENDING일 수 있다.
3. 목록 GET·상세 GET·동일 confirm 뒤에도 실행 ID·회차·최근 시각과 PG GET 0회가 유지되는지 확인한다.
4. 명시적인 수동 POST 후에만 새 실행 ID로 인계되고, PG 조회 1회·기록 1·재고 3으로 마무리되는지 본다. 자동 사용 회차 1은 그대로다.

## 증거와 정리

DB의 주문·결제 시도·복구 작업·차감 기록·상품·주문 기록·포인트 USE·PG 거래를 함께 확인한다. 이번 실행의 주문 ID와 효과 수는 [결과](result.md)에 기록했다.

HTTP 전송 행 계측은 실험용으로 `org.apache.hc.client5.http.impl.classic.MainClientExec=DEBUG`만 활성화했다. method·URI를 세며 요청 body·카드 원문은 기록하지 않는다. 연결 실패는 회차를 소비해도 HTTP 전송 행이 없을 수 있다. 일반 ‘자동 PG 조회 준비’ 로그나 Retry 지표를 HTTP 횟수로 세면 안 된다.

마지막에 임시 trigger 0개, 기존 POINT 기록·두 원문·사용자 JMeter 보존, 디버거/JVM 종료, 전용 DB 정지를 확인한다. DB 데이터·볼륨과 기존 사용자 MySQL/Redis는 보존한다.
