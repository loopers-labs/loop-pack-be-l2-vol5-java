# W7 중단·재발행 전체 흐름 TDD 기록

후속 4단계의 Outbox 없는 W6 두 사건 보충은 [W6 복구 TDD 기록](w6-outbox-backfill-tdd-log.md)에서 완료했다. 아래는 두 중단 검증 당시의 범위·실행 기록으로 보존한다.

2026-11-04 · [채점 기준 C05·C06](grading-criteria.md) · [구현 체크리스트](implementation-checklist.md) · [relay 선행 기록](outbox-relay-tdd-log.md) · [TDD 계획](../week2/commerce-tdd-plan.md#w7-outbox-원자성과-복구-tdd-계획)

## 이번 검증의 범위

[PgOrderOutboxRecoveryEndToEndTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/PgOrderOutboxRecoveryEndToEndTest.java)에 **한 E2E 테스트 안의 두 중단 사례**를 추가했다. 실제 주문 생성·확정 HTTP → 로컬 테스트 PG → API MySQL/Outbox → 실제 Kafka → 별도 production streamer JVM → 별도 구매 활동 MySQL을 연결한다. W7-TDD-17~19의 실행 증거이며 W6 누락 주문의 Outbox 보충 실험과는 다르다.

운영 코드·HTTP 계약·기존 트랜잭션·락·producer/consumer 설정은 변경하지 않았다. 기존 relay와 consumer가 중단 뒤에도 같은 사실로 수렴하는지 검증하는 증분이다. 테스트용 장애 스위치나 공개 재처리 API는 추가하지 않았다.

두 사례는 한 실행에서 서로 다른 주문을 만들고 같은 격리 DB·broker·PG·streamer를 계속 사용한다. 중간 truncate나 topic/group 재생성 없이 두 사례를 마친 뒤에만 자원을 정리한다. API DB는 기존 Testcontainers 구성, streamer DB와 Kafka는 이 테스트가 만든 컨테이너이며 topic/group에는 UUID를 붙인다.

## 중단 지점과 기대 결과

각 주문은 상품 A 3개·B 2개, 합계 500원이다. 주문·Outbox 시각은 `2026-11-04T03:04:05.123456Z`로 고정한다. 신규 PG 결제로 포인트를 차감하지 않고 재고는 A 10→7, B 5→3으로 예약·소비한다.

| 관찰 | 주문 확정 후 전송 전 | Kafka ACK 후 PUBLISHED 저장 전 |
| --- | --- | --- |
| 중단 방법 | 자동 relay OFF 상태에서 실제 confirm HTTP commit 뒤 실행을 진행하지 않음 | 실제 producer future의 metadata를 받은 뒤 `markPublished` 본문 전에 테스트 전용 예외 주입 |
| 주문·시도·예약 | CONFIRMED·SUCCESS·CONSUMED | CONFIRMED·SUCCESS·CONSUMED |
| Outbox | 동일 사건 1행, PENDING·시도 0·publishedAt NULL | 동일 사건 1행, PENDING·시도 1·publishedAt NULL |
| 대상 broker record | 0 | 1, 실제 topic/partition/offset 확보 |
| 대상 구매 활동 | 완료 기록 0·활동 0 | 첫 record를 실제 소비해 완료 1·상품 활동 2, 수량 3·2 |
| 재개 | Spring의 기존 relay와 다른 새 객체가 저장 행을 조회 | 이전 relay와 다른 새 객체가 저장된 nextAttemptAt에 재시도 |
| 재개 후 대상 broker record | 1 | **2**, 동일 topic/partition의 서로 다른 offset·동일 key/payload |
| 실제 listener 결과 | APPLIED·offset commit | APPLIED → DUPLICATE, 각 record의 offset commit |
| 최종 Outbox | 같은 PK의 PUBLISHED·시도 1 | 같은 PK의 PUBLISHED·시도 2 |
| 최종 구매 활동 | 완료 1·활동 2·수량 3·2 | **완료 1·활동 2·수량 3·2 유지**, PK와 전체 필드도 불변 |

여기서 중단은 **실행 흐름 정지·통제 예외와 relay 객체 재생성**이다. API나 Kafka 프로세스를 OS에서 강제 종료했다고 주장하지 않는다. 두 번째 중단은 broker ACK 전에 발생한 실패가 아니며, 첫 실제 record와 metadata를 먼저 확보한 뒤 PUBLISHED UPDATE를 실행하지 않는 경계다. PUBLISHED를 PENDING으로 되돌리거나 Outbox를 삭제해 조건을 만들지 않는다.

재시도 시각은 DB를 직접 수정하지 않고 새 runner의 주입 Clock만 저장된 `nextAttemptAt`으로 이동한다. 그 직전 시각에는 시도 0건·행 불변, 정확한 경계에서는 재발행 성공을 확인한다. 운영의 12초 전송 예산·5→60초 backoff는 바꾸지 않는다.

## 실제로 비교하는 것

- Kafka 호출 횟수뿐 아니라 inspector가 읽은 record의 실제 topic·partition·offset과 producer metadata를 대조한다. 재발행은 같은 offset을 다시 읽는 W6 재전달과 구분한다.
- 실제 streamer의 `purchase_activity_completed ... result=APPLIED/DUPLICATE` 로그와 group의 committed offset을 함께 확인한다. 단순히 DB 행 수가 그대로라는 사실만으로 두 번째 소비를 추정하지 않는다.
- 소비 완료·상품 활동의 `SELECT *` snapshot을 비교해 PK·수량·사건 시각·기타 필드가 그대로인지 확인한다. 앞선 첫 주문의 활동도 보존한다.
- relay 전후 주문·PaymentAttempt·OrderItem·상품의 전체 snapshot과 사용자 포인트를 비교한다. 지문 바이트 배열까지 재귀 비교하며 확정·예약·차감이 추가되지 않아야 한다.
- PG create·lookup·외부 거래 수와 실제 요청 목록을 비교한다. relay 재개 중 추가 호출은 0이다. 두 주문을 처음 준비하는 PG create 2회·거래 2건과 구분한다.
- 최종 PUBLISHED 이후 relay를 다시 실행해도 추가 전송·DB 변화가 없는지 확인한다. API·streamer 로그에 합성 카드 번호가 남지 않는 것도 검사한다.

## Red 대조와 Green

이미 구현된 relay의 동작을 검증하는 단계이므로 운영 기능을 일부러 새로 만들지는 않았다. 새 테스트가 잘못된 재시도 구현을 검출하는지 임시 변이를 사용해 확인했다.

| 단계 | 실행·의미 |
| --- | --- |
| Red 대조 | `beginAttempt`의 due 조건에 임시로 `attemptCount == 0`을 추가했다. 첫 전송은 허용하지만 실패 후 재시도는 못 하는 결함을 만든 대조군이다. |
| 실제 실패 | 첫 번째 중단 사례는 복구·소비까지 통과했다. 두 번째 사례의 재개에서 기대 `Result(1,1,false)` 대신 `Result(0,0,false)`로 실패했다. 테스트 1개·실패 1개, BUILD FAILED·종료 1. 컴파일 오류나 환경 실패가 아니다. |
| 복원 | 대조 실행의 finally에서 원본 파일을 복원하고 백업과 byte 비교했다. 최종 운영 코드에는 임시 조건이 남지 않는다. |
| Green | 원래 relay로 두 사례와 관련 회귀·정적 검사를 실행한다. 최종 결과는 아래 실행 기록을 따른다. |

Red 로그는 `/private/tmp/w7-replay-red.log`, assertion을 보존한 XML은 `/private/tmp/w7-replay-red-result.xml`이다. 이것은 기존 운영 코드에서 실제 결함을 발견해 수정했다는 의미가 아니라, 새 테스트의 재시도 검출력을 확인한 대조 실행이다.

## 최종 실행 기록

실행 명령:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*Outbox*' \
  --tests '*PgOrderPurchaseActivityEndToEndTest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-streamer:check --console=plain
```

| 검사 | 이번 결과 |
| --- | --- |
| API 테스트 | **18개 스위트·101개**, 실패·오류·skip 0. 새 E2E 1개 안의 두 중단 장면, Outbox 원자성·relay 회귀, 보존된 W6 정상/누락 E2E, ArchUnit 포함 |
| API Checkstyle | test 실제 실행·위반 0. main은 생산 코드 불변으로 UP-TO-DATE·기존 위반 0 결과 재사용 |
| streamer | 새 E2E에서 별도 production JVM의 실제 listener·parser·handler·DB·ack 실행. Gradle의 streamer check 자체는 UP-TO-DATE이며 기존 99개를 이번 신규 실행 수에 합산하지 않음 |
| 종료 | `/private/tmp/w7-replay-green.log`, BUILD SUCCESSFUL·종료 코드 0 |
| diff·복원 | `git diff --check` 통과. 임시 변이 파일은 백업과 byte 비교 일치. 최종 생산 코드 변경 없음 |

같은 Green 실행의 실제 topic은 `w7-outbox-restart-1c6097c8-c7df-42d3-9e7d-20dff059f421`이다. 실행마다 새 UUID를 쓰므로 이 topic을 재사용할 필요는 없다.

| 사례 | 주문/eventId | broker 관찰 | 소비·DB 결과 |
| --- | --- | --- | --- |
| 전송 전 중단 후 재개 | 1 / `order-confirmed:v1:1` | partition 1·offset 0, 1 record | APPLIED, 완료 1·활동 2·수량 3/2, Outbox PUBLISHED·시도 1 |
| ACK 후 중단·재발행 | 2 / `order-confirmed:v1:2` | partition 0·offset **0, 1**, 2 records | APPLIED→DUPLICATE, committed offset **2**, 완료 1·활동 2·수량 3/2 및 PK/전체 필드 불변, Outbox PUBLISHED·시도 2 |

두 사례 누적은 주문·거래·Outbox·완료 기록 각각 2건, broker record 3개, 상품 활동 4행이다. 각 주문의 재개 구간에서는 PG create/lookup·요청 목록 변화와 업무 snapshot 변화가 모두 0이다. 과거 851개 통과를 이번 실행 수로 재사용하지 않는다.

자원 정리는 streamer 종료 → inspector/admin 종료 → 소유 API DB 정리 → PG·Testcontainers 종료 순서다. 마지막 `docker ps`로 임시 컨테이너 종료를 확인하며 기존 사용자 볼륨·topic/group 삭제 명령은 실행하지 않는다.

## 남은 단계

1. **W6 누락·정상 소비 두 사례 복구:** 실제 W6 직접 발행 경로로 두 사건을 준비하고, 같은 DB·broker·PG 상태에서 없는 Outbox만 기존 사실로 보충한다. 기존 구매 활동 보존·두 번째 복구 불변·복구 중 PG 추가 호출 0을 확인한다. C07·C08, W7-TDD-20~23에 해당한다.
2. **W7 최종 대조:** 전체 회귀·정적 검사, 관찰·재개·보존 문서와 채점 기준을 마무리한다. PR·기술 글·실제 제출은 구현 검증과 따로 관리한다.

이번에 검증한 두 주문은 처음부터 W7 Outbox가 저장된 주문이다. 이 결과를 W6의 Outbox 없는 과거 주문 보충 완료로 간주하지 않는다. 단일 broker·단일 relay 검증이며 다중 broker 장애 내구성이나 end-to-end exactly-once를 주장하지 않는다.

사용자 DB·기존 topic/group·볼륨에 접근하거나 삭제하지 않았고 커밋·푸시하지 않았다.
