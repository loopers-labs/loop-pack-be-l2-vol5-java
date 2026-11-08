# W7 W6 누락 사건 Outbox 보충·복구 TDD 기록

2026-11-04 · [채점 기준 C07·C08](grading-criteria.md) · [구현 체크리스트](implementation-checklist.md) · [relay 기록](outbox-relay-tdd-log.md) · [중단·재발행 기록](outbox-republication-e2e-tdd-log.md)

## 이번에 해결한 문제

W6에서는 주문이 확정돼도 직접 Kafka 발행 직전에 중단되면 구매 활동이 없을 수 있다. 주문을 다시 결제·확정하지 않고 **이미 저장된 확정 사실에서 없는 Outbox만 보충**한 뒤 기존 relay와 consumer로 전달한다.

사용자는 W7-Q06의 **보존한 테스트 전용 W6 직접 발행 구성**을 선택했다. [W6 재현 가이드](../week6/fixed-clock-w7-reproduction.md)의 준비 기반과 `W6DirectPublicationTestConfiguration`을 사용한다. 과거 revision을 별도 JVM으로 실행하는 대안도 허용되지만, 이번 선택은 같은 DB·broker·PG 상태를 준비부터 복구 종료까지 유지하기 쉽다. 운영용 W6/W7 전환 스위치는 추가하지 않았다.

새 격리 실행에서 새로 준비한 사건이며 과거 사용자 DB 원본을 복원한 것이 아니다. Outbox 테이블은 테스트 기동 시 존재하지만 W6 준비 경로는 행을 기록하지 않는다. W7 행을 삭제하거나 SUCCESS를 직접 INSERT해 과거 상태를 꾸미지 않는다.

## 구현 책임과 계약

| 책임 | 구현·선택 이유 |
| --- | --- |
| 명시적 내부 진입 | [OrderOutboxBackfillService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderOutboxBackfillService.java)의 `backfill(orderId)`는 지정한 주문 하나를 처리한다. 공개 HTTP·자동 전체 주문 스캔·PG 호출을 추가하지 않는다. |
| 원본 검증 | `OrderConfirmedEvent.from`과 `OrderPaymentInfo.from`을 재사용한다. PG+CONFIRMED, 원래 confirmedAt·품목·저장 금액·CONSUMED, 저장 PaymentAttempt SUCCESS·주문/금액 일치·발송 시각·시도 키·거래 키를 확인한다. 부족하거나 모순된 사실을 현재 상품/현재 시각으로 추측하지 않는다. |
| 보호할 기존 사실 | POINT·미확정 주문을 소급하지 않는다. 카드·HMAC 키·지문을 요구하지 않으므로 지문이 없는 과거 정상 PG 사실도 처리할 수 있다. 현재 상품의 가격 변경·논리 삭제는 과거 주문의 복구를 바꾸지 않는다. |
| TX·락 | 한 주문 단위 READ_COMMITTED TX에서 기존과 같은 **주문→결제 시도** 순서로 잠근다. 같은 주문의 복구 경합을 직렬화하며 상품/재고 잠금을 추가하지 않는다. 원본을 읽고 Outbox만 쓴다. |
| 없는 사건 | 기존 serializer로 원래 eventId·key·confirmedAt·품목과 알려진 topic을 구성해 PENDING 한 행을 저장한다. 결과는 `CREATED`와 eventId/Outbox ID다. Outbox createdAt은 보충 시각이며 payload confirmedAt은 원래 확정 시각이다. |
| 이미 있는 사건 | 일치하면 `ALREADY_PRESENT`를 반환한다. 기존 PK·PENDING/PUBLISHED·attemptCount·nextAttemptAt·lastError·createdAt·publishedAt을 변경하지 않는다. |
| 충돌 검사 | [JsonOutboxMessageMatcher](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/outbox/JsonOutboxMessageMatcher.java)는 eventId·aggregate 종류/ID·eventType·schemaVersion·topic·key와 JSON 내용을 비교한다. 공백·객체 속성 순서는 무시하며 중복 키·후행 토큰·다른 내용은 거절한다. |
| 보수적인 비교 범위 | 생산자의 정규화된 품목 배열 순서까지 같아야 한다. 임의 배열 재정렬·추가 필드·목적지 변경을 자동 승인하지 않는다. 현재 mapping과 기존 topic이 다르면 충돌로 거절하고 원래 목적지를 확인한다. 저장 행을 최신 설정으로 덮어쓰지 않는다. |
| 실패 | 없는 주문·부적격 원본·충돌은 내부 예외로 드러낸다. 새로운 HTTP 오류 계약은 없다. 직렬화·실제 INSERT 후 실패는 새 Outbox를 rollback한다. 결제·주문 확정·재고 차감·반환을 재시도하지 않는다. |
| 전달 | 보충 TX가 끝난 뒤 별도 relay를 실행한다. backfill은 직접 send나 로컬 업무 이벤트를 발행하지 않는다. |

여러 대상은 주문별로 호출한다. 한 주문의 실패로 앞서 성공한 다른 주문의 보충까지 취소하는 일괄 트랜잭션은 만들지 않았다. 같은 주문의 두 호출은 주문 잠금과 eventId 유일 제약으로 한 행에 수렴한다.

## 같은 실행의 W6 준비 → W7 복구

[W6OutboxBackfillEndToEndTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/W6OutboxBackfillEndToEndTest.java)는 실제 주문 생성·확정 HTTP, 로컬 테스트 PG, API MySQL, Kafka, 별도 production streamer JVM과 구매 활동 MySQL을 연결한다.

1. **정상 사례 준비:** W6 AFTER_COMMIT 직접 발행 경로로 주문을 확정하고 실제 broker record·streamer APPLIED·DB commit·offset commit을 확인한다.
2. **누락 사례 준비:** 별도 주문을 실제 PG로 확정한다. AFTER_COMMIT의 send 직전 테스트 hook에서 별도 DB 연결로 확정·예약 소비를 확인한 뒤 실패를 주입한다. broker 전송은 호출하지 않으며 구매 활동은 없다.
3. **준비 사실 고정:** 두 주문 모두 Outbox 0, 원래 eventId·confirmedAt·품목·PG 거래를 유지한다. PG 생성/조회/거래 수와 전체 업무 snapshot을 기준값으로 기록한다.
4. **보충:** 테스트의 직접 발행 포트에 실패 guard를 두어 복구 중 호출되면 테스트가 실패하도록 한다. 제품 전환 스위치나 중간 DB 초기화 없이 실제 backfill 서비스로 두 Outbox를 추가한다. 이때 broker와 활동은 아직 변하지 않는다.
5. **전달:** 실제 relay·Kafka·기존 streamer가 누락 주문을 APPLIED, 이미 소비된 주문을 새 offset의 DUPLICATE로 처리한다. 두 이벤트의 producer metadata·실제 record·listener 로그·committed offset을 함께 확인한다.
6. **반복:** 같은 두 주문의 두 번째 backfill은 ALREADY_PRESENT이고 relay는 0건이다. Outbox 전체 행·broker 끝 offset·group commit·소비 DB 전체 행과 업무 snapshot이 모두 그대로다.

준비와 복구 사이에 DB·broker·PG를 재생성하거나 정리하지 않는다. 모든 관찰을 마친 뒤에만 streamer → inspector/admin → 소유 데이터 → PG/컨테이너를 정리한다. 준비의 정상 소비와 누락은 실제 W6 코드 경로이며 테스트 hook을 OS 강제 종료로 표현하지 않는다.

## 결과

신규 Green 실행에서 준비한 실제 값:

- topic: `w6-backfill-events-61adb7c1-a328-41d7-b7c2-93a528b7e58f`
- group: `w6-backfill-activity-2e428bda-b65d-4bfe-b245-84be81f70c63`
- 원래 confirmedAt: `2026-11-04T03:04:05.123456Z`
- 준비 기준: **PG create C=2, lookup=0, 거래 T=2**. 두 주문의 상품 수량은 각각 3·2이고 PG 결제 금액은 각각 500원이다.

| 대상 | 복구 전 | 복구 후 | 두 번째 복구 |
| --- | --- | --- | --- |
| 이미 소비된 주문 1 / `order-confirmed:v1:1` | Outbox 0, partition 1 offset 0, 완료 1·활동 2 | Outbox 1 PUBLISHED, offset 1 추가·DUPLICATE, 기존 완료/활동 **PK·수량·전체 필드 불변** | ALREADY_PRESENT, 추가 전송/저장 효과 0 |
| 누락 주문 2 / `order-confirmed:v1:2` | Outbox 0, broker 0·완료 0·활동 0 | Outbox 1 PUBLISHED, partition 0 offset 0·APPLIED, 완료 1·활동 2·수량 3/2 생성 | ALREADY_PRESENT, 추가 전송/저장 효과 0 |
| 결제·업무 사실 | PG 거래 2, 두 확정 주문·소비된 예약 | **PG create 증가 0·lookup 증가 0·요청 목록 불변·거래 2 유지**. 주문·시도·품목·재고·포인트 snapshot 불변 | 모두 그대로 |

최종 누적은 Outbox 2행·구매 완료 2건·상품 활동 4행·broker record 3개다. 복구 구간에서는 주문 결제/재고 서비스와 결제 재확인 서비스의 호출이 없는지도 spy로 확인한다. 따라서 PG 호출 수만 0이고 내부 재고 효과는 중복됐을 가능성을 snapshot과 호출 검사로 함께 배제한다.

이 표의 식별자는 위 Green 실행의 실제 사건이다. 후속 회귀는 별도 UUID 자원에서 다시 준비하며 두 실행을 하나의 역사적 사건으로 합치지 않는다.

### 2026-11-06 원본 사실 증거 보강

앞선 실행 기록은 PG 거래 수와 품목 수량을 남겼지만 **개별 PG 거래 식별자와 상품 ID별 수량의 연결**은 문서에서 바로 확인하기 어려웠다. E2E의 준비 단계에 `w6_backfill_source` 출력을 추가했다. 확정된 `payment_attempt`에서 PG 주문 ID·거래 키·금액을, `order`에서 최초 확정 시각을, `order_item`에서 상품 ID별 수량을 직접 읽는다. PG가 받은 생성 요청의 주문 ID·금액과 저장 사실도 대조한다.

출력은 `scenario / orderId / eventId / providerOrderId / transactionKey / amount / confirmedAt / productQuantities`로 제한한다. 카드 번호·HMAC 지문·비밀키·전체 결제 행·요청 본문은 기록하지 않는다. 관찰을 위해 PG 조회를 추가하지 않으므로 복구 중 추가 PG 호출 0이라는 기존 기대값도 유지한다.

2026-11-06 양 앱 전체 검사에서 이 E2E가 **1개 실행·실패/오류/skip 0**으로 통과했다. 아래 값은 해당 실행의 [테스트 XML](../../apps/commerce-api/build/test-results/test/TEST-com.loopers.application.order.W6OutboxBackfillEndToEndTest.xml)에 남은 `w6_backfill_source`·`w6_backfill_prepared`·`w6_backfill_recovered`에서 추출했다. 앞선 Green 실행과 별개의 격리 사건이며, 주문 ID가 같더라도 실행 자원과 결합해 구분한다.

- topic: `w6-backfill-events-be187c66-90ec-4afd-9bac-f8226ae3dde1`
- group: `w6-backfill-activity-8f8c895f-987b-43bd-a38f-2463f5c5096b`
- 두 주문의 원래 confirmedAt: `2026-11-04T03:04:05.123456Z` — 실행 날짜와 구분되는 고정 업무 시각이다.

| 사례·주문 ID | eventId | PG 주문 ID | PG 거래 키 | 상품 ID → 수량 | 결제 금액 |
| --- | --- | --- | --- | --- | ---: |
| 이미 소비됨 · 1 | `order-confirmed:v1:1` | `LP-ORD-0000000001` | `TEST-TX-1` | `1 → 3`, `2 → 2` | 500원 |
| 발행 누락 · 2 | `order-confirmed:v1:2` | `LP-ORD-0000000002` | `TEST-TX-2` | `3 → 3`, `4 → 2` | 500원 |

| 관찰 시점 | 같은 실행에서 확인한 결과 |
| --- | --- |
| 준비 완료 | PG create 2·lookup 0·거래 2, Outbox 0, broker record 1, 소비 완료 1·상품 활동 2. 정상 주문은 partition 1·offset 0으로 소비됐다. |
| 복구 완료 | Outbox 2 PUBLISHED, 소비 완료 2·상품 활동 4. 정상 주문은 partition 1·offset 1로 재발행되어 DUPLICATE, 누락 주문은 partition 0·offset 0에서 APPLIED. 기존 정상 주문의 소비 기록 PK·수량·전체 필드는 유지됐다. |
| 복구의 업무 효과 | PG create 증가 0·lookup 증가 0·거래 2 유지·요청 목록 불변. 주문·결제 시도·품목·재고·포인트 snapshot이 준비 완료 시점과 같다. |
| 두 번째 복구 | 두 주문 모두 ALREADY_PRESENT, relay 0건. Outbox 전체 행·broker 끝 offset·group commit·소비 DB·업무 snapshot이 그대로다. |

전체 검사 명령·집계·종료 결과는 [W7 최종 검사 기록](final-verification.md)에서 확인한다. 위 증거는 현재 테스트 XML이 교체되어도 비교할 수 있도록 이 문서에 실행별로 보존한다.

## TDD와 관련 검사

| 테스트 | 새 실행 항목 | 의미 |
| --- | ---: | --- |
| `OrderOutboxBackfillServiceTest` | 15 | 입력·POINT/DRAFT·부족/상충 결제 사실 거절, 과거 무지문 허용, 생성·기존 행 보존·충돌·직렬화 실패·잠금 순서 |
| `JsonOutboxMessageMatcherTest` | 21 | 메타데이터·strict JSON·공백/필드 순서, 잘못된 내용 거절, 전달 필드 제외·무변경 |
| `OrderOutboxBackfillIntegrationTest` | 5 | 실제 PG로 준비한 W6 저장 사실, 실제 Outbox INSERT 뒤 rollback, 동일 주문 경합, PENDING/PUBLISHED 전체 보존, 현재 상품 변경·삭제 무관, 실제 충돌 행 보존 |
| `W6OutboxBackfillEndToEndTest` | 1 | 같은 실행의 두 W6 사건 준비·보충·실제 전달·구매 활동·두 번째 복구 |
| **신규 합계** | **42** | **4개 스위트·실패/오류/skip 0** |

- **Red:** 테스트를 먼저 작성하고 미구현 서비스/비교 타입으로 컴파일 실패했다. `/private/tmp/w7-backfill-red.log`, 종료 1. assertion 실패 Red와 구분한다.
- **첫 Green 시도:** 단위·비교 36개는 통과했다. DB 테스트 정리 코드의 generic varargs 타입 추론으로 ClassCastException이 발생해 정리가 중단됐고, 뒤 사례에 테스트 데이터가 남았다. 타입이 명시된 target으로 reset하고 그 실패와 무관하게 finally에서 정리하도록 고쳤다. 운영 제약·테스트 기대값을 완화하지 않았다.
- **Green:** 신규 42개 모두 통과. `/private/tmp/w7-backfill-green.log`, BUILD SUCCESSFUL·종료 0. 최초 Green의 사건 값은 이 실행에서 추출했고, 2026-11-06 보강 표는 별도 재실행의 값이다.

### 관련 회귀·정적 검사

아래 명령은 **33개 스위트·430개 테스트**, 실패·오류·skip 0으로 통과했다. 신규 42개가 이 수에 포함되며 선행 851개 통과를 이번 실행 수로 재사용하지 않는다. API 전체 테스트 수가 아니라 아래 지정 범위다.

```bash
./gradlew :apps:commerce-api:test \
  --tests '*Outbox*' \
  --tests '*PgOrderPurchaseActivityEndToEndTest' \
  --tests 'com.loopers.interfaces.api.order.*' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-streamer:check --console=plain
```

| 검사 | 최종 결과 |
| --- | --- |
| API 관련 테스트 | Outbox 기록/relay/복구, 두 중단 E2E, 보존한 W6 E2E, 기존 주문 API 계약과 ArchUnit 포함 430개 통과 |
| API 정적 검사 | ArchUnit 실제 실행 통과, main/test Checkstyle 실제 실행·XML 위반 0 |
| streamer | E2E에서 production JVM·실제 소비/DB/ack 실행. Gradle의 streamer check는 UP-TO-DATE로 기존 결과 재사용이며 기존 99개를 이번 새 실행으로 세지 않음 |
| 종료 | `/private/tmp/w7-backfill-regression.log`, BUILD SUCCESSFUL·종료 코드 0 |
| 소유 자원 | 테스트 종료 후 컨테이너·자식 streamer 종료 확인. 사용자 DB/기존 topic·group/볼륨 삭제 없음 |
| diff | `git diff --check` 통과. 검사 기대값·lint·계층 규칙 삭제/완화 없음 |

## 남은 단계

2026-11-06 [양 앱 최종 전체 검사](final-verification.md)는 통과했다. C09의 실제 backlog·장애 후 재개는 [통신 장애 실험 기록](outbox-kafka-outage-tdd-log.md), C10의 실행·보존 한계는 [운영·보존 안내](outbox-operations-retention.md)에 별도 근거를 둔다. 과거 복구 성공만으로 다른 항목의 충족을 추정하지 않는다.

남은 것은 **PR·기술 글 등 제출 정리와 실제 제출 여부 확인**이며, 이 문서에서는 제출 완료를 확인하지 않았다. 기능 구현·전체 검사 완료와 제출 완료를 구분한다.

단일 relay·단일 broker 실험이며 분산 relay나 end-to-end exactly-once를 보장하지 않는다. 기존 payload/topic을 변경하지 않고 미발행 사실을 삭제하지 않는다. 사용자 DB·기존 topic/group·볼륨에 접근하거나 삭제하지 않았고 커밋·푸시하지 않았다.
