# 주문 확정 Outbox 상세 (W7, AI용)

사람용 [outbox-design.md](outbox-design.md)의 구현 상세다. 코드를 보면 알 수 있는 내용은 적지 않는다. 세부 개념은 `docs/note/week7/발제.md`, 요구사항은 `docs/note/week7/실습 과제.md`를 따른다.

## 1. 변경 전 사실 (W6)

- CONFIRMED 전이는 `PaymentStatusUpdater.succeed()` 한 곳이다. 진입은 `PaymentResultLookupFacade.syncWithPg`의 `writeTransaction.execute(updateByStatusLookupResult)`뿐이다.
- `updateByStatusLookupResult`는 호출 전후 주문 상태를 비교해 최초 확정일 때만 `OrderConfirmedInfo`를 `PaymentStatusUpdateResult.confirmed`에 담는다. Facade가 `execute` 반환 뒤 `OrderEventPublisher.publish`를 부른다.
- `OrderEventKafkaPublisher`가 `OrderConfirmedInfo`를 Avro bytes로 만들어 `KafkaTemplate.send(topic, orderId, bytes)`를 부르고 결과를 로그로만 남긴다. send 실패는 삼킨다.
- W6 테스트: `OrderConfirmedPayloadTest`, `OrderConfirmedPublicationTest`, `OrderConfirmedPublishFailureTest`, `OrderConfirmedPublicationGapTest`, `OrderEventKafkaPublisherTest`.
- 이벤트 계약(topic, key, eventId, Avro 스키마, 소비 검사 순서)은 [W6 상세](../week6/order-event-design.ai.md) 2장을 그대로 따른다. 이번 주에 바꾸지 않는다.

## 2. Outbox 데이터

| 필드 | 규칙 | 불변 |
|---|---|---|
| id | PK | |
| eventId | `order-confirmed:v1:{orderId}`, **유일 제약** | O |
| orderId | 원본 주문 | O |
| eventType, schemaVersion | `OrderConfirmed`, `v1` | O |
| messageKey | `String.valueOf(orderId)` | O |
| payload | Avro single-object encoding bytes (확정 당시 snapshot) | O |
| status | `PENDING` → `PUBLISHED` | |
| createdAt | Outbox 기록 시각 (`BaseEntity`) | O |
| publishedAt | PUBLISHED 저장 시각. 브로커 기록 시각이 아니다 | |
| attemptCount | 전송 시도를 시작할 때 올린다. broker 수신 수가 아니다 | |
| nextAttemptAt | 이 시각 이후에 재시도 대상. 생성 시 createdAt | |
| lastError | 마지막 실패의 짧은 원인. 카드·비밀·전체 payload 금지 | |

- 인덱스: `(status, nextAttemptAt, id)`. 조회는 `status = PENDING AND nextAttemptAt <= now ORDER BY id LIMIT n`으로 안정된 순서를 쓴다.
- payload는 생성 뒤 바꾸지 않는다. 엔티티에 payload·key·eventId 변경 메서드를 두지 않는다.
- 이미 PUBLISHED인 행에 다시 PUBLISHED를 적용해도 효과가 없다(멱등).

## 3. 상태 전이

| 현재 | 사건 | 다음 | 기록 |
|---|---|---|---|
| (없음) | 최초 확정 TX | PENDING | createdAt, nextAttemptAt = createdAt |
| PENDING | 시도 시작 | PENDING | attemptCount + 1 |
| PENDING | broker 성공 확인 + 상태 TX 성공 | PUBLISHED | publishedAt |
| PENDING | 전송 실패·timeout | PENDING | nextAttemptAt = now + 간격, lastError |
| PENDING | broker 성공 뒤 상태 TX 실패 | PENDING | 변경 없음. 다음 회차에 같은 사건 재발행 |
| PUBLISHED | 어떤 사건이든 | PUBLISHED | 변경 없음 |

- `BLOCKED` 상태는 두지 않는다. payload는 확정 TX에서 Avro 계약으로 만든 bytes(상한 16KB)를 그대로 보내므로 특정 record만 영원히 실패할 여지가 거의 없고, 실패는 broker·topic·partition 단위로 함께 일어난다. 그런 장애에 행을 BLOCKED로 막으면 회복 뒤 사람이 모두 되살려야 해 자동 수렴이 깨진다. 장애는 회로 차단(10-2)으로 다루고, 반복 실패하는 행은 PENDING + attemptCount + lastError로 관찰한다.
- 늦은 callback이 상태를 바꾸지 못하게, 상태 변경은 relay 루프 안의 상태 TX 한 곳에서만 한다. send의 `whenComplete` 콜백에서 DB를 바꾸지 않는다.

## 4. 확정 TX

```
syncWithPg:
  TX 밖: 기존 PG 거래 조회·검증
  writeTransaction.execute:
    주문·시도 잠금 (getForUpdate) 과 관찰 일치 검증
    이미 같은 SUCCESS이면 기존 결과 반환 (Outbox 추가 없음)
    attempt SUCCEEDED, 주문 CONFIRMED + confirmedAt, 예약은 시작 때 차감한 그대로
    저장된 품목으로 OrderConfirmed 생성 → Avro bytes
    Outbox PENDING insert
  commit
```

| 상황 | Outbox | 주문·예약 |
|---|---|---|
| 최초 SUCCESS | 1행 PENDING | CONFIRMED |
| 같은 거래 callback·reconcile 반복 | 추가 없음 (유일 제약 + `applyToTerminated`) | 그대로 |
| PENDING·UNKNOWN·FAILED | 없음 | 해당 전이만 |
| Outbox insert·직렬화 실패 | 없음 | **TX 직전 상태로 rollback** (주문 행이 사라지는 것이 아니다) |
| 이전 POINT 확정 주문 | 소급 없음 | 그대로 |

- PG SUCCESS는 로컬 rollback과 무관하게 남는다. 같은 거래를 다시 조회·적용하면 성공 반영을 다시 시도할 수 있고 PG create는 추가하지 않는다.
- Facade에서 `publish` 호출과 `PaymentStatusUpdateResult.confirmed`는 제거한다. 직접 send와 relay를 병행하지 않는다.
- 레이어: Outbox 저장 포트는 application이 소유하고 infrastructure가 구현한다. 도메인 규칙(상태 전이)은 Outbox 엔티티가 가진다. ArchUnit 의존 방향을 지킨다.

## 5. Relay 루프

```
runOnce:
  1. 조회 TX (짧게): PENDING & nextAttemptAt <= now, id 오름차순, limit n → 대상 목록, attemptCount + 1 저장
  2. 대상마다 직렬로 (TX 밖):
       send(topic, messageKey, payload) → future.get(sendTimeout)
  3. 결과마다 상태 TX:
       성공 → PUBLISHED, publishedAt
       실패·timeout → nextAttemptAt, lastError
```

- 단일 실행: 스케줄러 `fixedDelay`(같은 작업은 fixedRate여도 겹치지 않는다. 회차가 끝난 뒤 쉬는 시간을 보장하려고 고른다). 같은 작업은 스케줄러가 동시에 실행하지 않으므로 회차 잠금은 두지 않는다(운영 코드에서 `relayOnce`를 부르는 곳은 스케줄러뿐). 활성화 설정 `outbox.relay.enabled`는 기본 false이고 `local` 프로필만 true. 다중 인스턴스에서 동시에 켜지 않는 것은 실행 설정의 약속이며 메모리 flag로 해결했다고 주장하지 않는다.
- 전송 확인은 send 호출이 반환된 것이 아니라 producer future의 성공이다. `acks=all`, 멱등 유지(W6). 단일 broker 한계 유지.
- timeout 뒤에도 하위 send가 나중에 성공할 수 있다. 같은 eventId를 유지하고 수신 0회라고 단정하지 않는다.
- 설정값: `batch-size` 20, `send-timeout` 5s, `retry-interval` 30s(고정), `fixed-delay` 5s. 한 회차는 최악 20 × 5초다. 테스트는 `Clock`을 고정하거나 짧은 유한 대기를 쓴다.
- 재시도 간격은 시도 횟수에 따라 늘리지 않는다(지수 백오프 없음). 백오프는 행마다 간격을 늘리는 도구인데, 이 relay의 실패는 broker 단위라 장애 동안 모든 행의 간격이 상한까지 늘어나 회복 뒤 전송이 늦어지기만 한다. 장애 동안의 부하는 회로 차단이 줄인다.
- 오류 메시지에는 예외 종류와 짧은 원인만 남긴다.
- broker 장애는 회로 차단(resilience4j `outboxPublish`)으로 다룬다. 전송 실패·timeout을 회로에 기록하고, 최근 5회 중 3회 이상 시도에서 실패율 50% 이상이면 연다. 열린 동안 relay는 회차를 통째로 건너뛴다(대상 선택·시도 횟수 증가·broker 대기 없음, 로그 한 줄). 30초 뒤 반열림에서 시험 전송 1회로 회복을 확인한다.
- 반열림 자동 전환(`automatic-transition-from-open-to-half-open-enabled: true`)이 필요하다. 기본 설정은 누군가 호출을 시도할 때 반열림으로 넘어가는데, relay는 열린 회로에 시도하지 않으므로 이 설정이 없으면 회로가 영원히 열린 채 relay가 멈춘다.
- 회차 도중 회로가 열리면 이미 고른 나머지 행은 "회로 열림" 실패로 기록되어 `retry-interval` 뒤 다시 대상이 된다. 이때 시도 횟수가 1씩 늘지만 시도 횟수는 수신 수가 아니다.

## 6. 중단 지점별 기대 상태

| 중단 위치 | 주문·예약 | Outbox | Kafka 기록 | 구매 활동 |
|---|---|---|---|---|
| Outbox insert 전후 실패 (TX rollback) | 직전 미완료 상태 | 0 | 0 | 0 |
| commit 후, relay 실행 전 | CONFIRMED | PENDING 1 | 0 | 0 |
| relay 재생성 후 실행 | CONFIRMED | PUBLISHED 1 | 1 | 정상 생성 |
| broker 확인 후, PUBLISHED 저장 전 | CONFIRMED | **PENDING 유지** | 1 | 소비되면 생성 |
| 위 상태에서 relay 재시작 | CONFIRMED | PUBLISHED 1 | 같은 eventId, 다른 offset **2** | 활동 행·수량 불변 |

- 중단 hook은 그 단계가 끝난 **뒤** 다음 단계로 못 가게 둔다. "broker 확인 뒤 중단"은 실제 send 성공 metadata를 확보한 다음 PUBLISHED 저장을 하지 않는다. 예외를 던진 테스트를 OS 강제 종료 시험이라고 부르지 않는다.
- 재시작은 relay 객체·실행 흐름을 새로 만들고 같은 DB·Kafka·eventId를 쓴다. 테스트가 Outbox 상태를 PENDING으로 덮어써 조건을 만들지 않는다.
- 반례: PUBLISHED를 먼저 저장하면 "send 전 중단"에서 미발행 사건이 완료로 남아 relay가 다시 찾지 못한다.

## 7. 운영

| 항목 | 내용 |
|---|---|
| backlog | 아래 SQL로 미발행 규모, 가장 오래 기다린 사건, 반복 실패 중인 사건과 원인을 본다 |
| 로그 | relay 회차마다 구조화 로그: 선택 건수, 성공·실패 건수. 건별 로그에 eventId·orderId·attemptCount |
| 재개 | 기본 재개 경로는 자동 재시도다. 실패한 행은 `retry-interval` 뒤 정기 회차가 다시 보내고, broker가 회복되면 기존 PENDING이 그대로 흘러간다. 급하면 아래 SQL로 다음 시도 시각만 당긴다. 전송은 언제나 relay 하나가 하며, 재개용 전송 진입점이나 공개 관리자 API는 만들지 않는다 |
| 추적 | eventId·orderId로 주문 → Outbox → broker(topic/partition/offset) → 구매 활동을 따라간다 |
| 보존 | 미발행·격리 행은 자동 삭제하지 않는다. PUBLISHED도 즉시 삭제하지 않는다. Outbox 보존, Kafka retention, 소비 완료 기록(`processed_order_confirmation`) 보존 기간은 함께 정한다. 오래된 이벤트를 다시 보낼 수 있는데 dedup 기록만 먼저 지우면 중복 효과가 생긴다 |
| 회로 | "회차 건너뜀. 브로커 장애로 전송을 멈춘 상태" 로그가 이어지면 broker 장애다. 회복되면 반열림 시험 전송 뒤 쌓인 PENDING이 자동으로 나간다 |
| 구분 | PUBLISHED를 소비 완료로 표시하지 않는다. consumer lag·오류·실제 구매 활동을 따로 본다 |

```sql
-- 미발행 규모와 가장 오래 기다린 사건
select count(*) as pending, min(created_at) as oldest, max(attempt_count) as max_attempts
from outbox where status = 'PENDING';

-- 반복 실패 중인 사건과 원인
select event_id, order_id, attempt_count, next_attempt_at, last_error
from outbox where status = 'PENDING' and attempt_count >= 3 order by id;

-- 재개: 지정한 사건을 다음 회차의 대상으로 당긴다 (PUBLISHED는 건드리지 않는다)
update outbox set next_attempt_at = now(6) where event_id = ? and status = 'PENDING';
```

## 8. W6 누락 재현·복구

W7 코드에서는 확정 TX가 Outbox를 함께 쓰므로 같은 경로로는 누락 상태를 만들 수 없다. 합의: **테스트가 W6 경로를 흉내 낸다**(Outbox 없이 확정 TX만 실행하고 send 직전에서 멈춘다).

복구 대상은 이번 재현 실행의 두 주문뿐이다. 이전 POINT 주문을 소급하지 않는다.

- 실제로 복구할 운영 데이터가 없으므로 복구 경로는 **테스트용 도구**(`support.outbox.MissingOutboxRecovery`)로 둔다. 생성은 운영의 `OrderConfirmedOutboxRecorder.record`를 그대로 써서 확정 때와 같은 eventId·`confirmedAt`·품목이 나온다. 도구는 그 앞에서 확정 사실이 부족하면(CONFIRMED 아님·`confirmedAt` 없음) 거절하고, 이미 있으면 디코드해 orderId·`confirmedAt`·품목(순서 무시)이 같은지만 확인하며 다르면 충돌로 거절한다. 실제 누락이 생기면 이 도구를 운영 코드로 옮긴다.
- W6 경로 재현: 실제 `PaymentStatusUpdater`에 아무것도 기록하지 않는 Outbox 기록기를 넣어 확정 TX만 실행한다.
- 11-1(복구 도구·DB 확인)과 11-2(재현한 A·B를 relay와 실제 consumer로 흘려 구매 활동 확인, 12단계 consumer 배선 뒤)로 나눈다.

```
준비 (새 격리 MySQL·Kafka·로컬 PG fixture, 테스트가 수명주기를 소유)
  주문 API + 로컬 PG로 합성 주문 A, B 확정
  A: 확정 commit 뒤 send 직전 중단 → Outbox 없음, 구매 활동 0
  B: 확정 + 발행 + 소비 완료 → 구매 활동 있음
  기록: A·B의 orderId, eventId, confirmedAt, items, PG 거래 식별자
        PG create 누적 횟수 C, 외부 거래 수 T (두 주문이면 C=2, T=2)
  복구가 끝날 때까지 DB·broker·PG를 초기화하거나 @AfterEach 정리를 실행하지 않는다

복구
  A·B의 저장된 확정 사실로, Outbox가 없을 때만 기존 eventId·confirmedAt·items 그대로 한 행씩 추가
  이미 있으면 내용 일치만 확인. 사실이 부족하거나 충돌하면 오류 (추측 금지)
  relay + 기존 consumer 실행
```

| 확인 | 기대 |
|---|---|
| A 구매 활동 | 빠졌던 활동이 생김 |
| B 구매 활동 | 재발행이 와도 행·수량 불변 |
| 두 번째 복구 | Outbox·구매 효과 증가 0 |
| PG | create 횟수 = C, 외부 거래 수 = T, 조회·callback 추가 호출 0 |
| 주문·예약 | 재확정·예약 추가 소비 0 |

- 준비 상태를 만들려고 SUCCESS를 직접 insert하거나 Outbox 행을 삭제하지 않는다. 제품에 운영용 우회 스위치를 만들지 않는다.
- 실제 W6 원본 DB를 보존했을 때만 "원본 사건의 복구"라고 부른다. 이번 방식은 "재현한 누락의 복구"다.

## 9. E2E (주문 → 구매 활동)

한 테스트가 자원 준비·consumer 시작·API 호출·relay 중단과 재시작·조회를 소유한다.

- api `testImplementation`에 streamer를 추가하고 consumer·parser·handler·listener factory만 별도 context로 띄운다. 제품 runtime 의존은 만들지 않는다. 두 앱의 전체 component scan 충돌을 피한다.
- 테스트 자동 rollback 안에 두지 않는다. relay·consumer가 다른 TX에서 볼 수 있게 실제 commit을 확인한다.
- 고유 topic·group·합성 주문을 쓰고, 마지막에 자신이 만든 consumer·broker·HTTP fixture만 정리한다.
- broker 준비·consumer 배정·결과 대기에는 deadline을 두고 실패한 단계의 실제 상태를 출력한다.

품목이 상품 7×2, 상품 8×1인 주문의 기대값:

| 관찰 | 기대 |
|---|---|
| 주문·PG 거래 | 확정 주문 1, 외부 거래 1 |
| Outbox | 동일 eventId 1행, 최종 PUBLISHED |
| Kafka | 같은 eventId의 별도 record 2개 이상 (실제 broker 기록으로 확인, send 호출 횟수가 아님) |
| consumer | 같은 사건 수신 2회 이상 |
| 완료 기록 | 같은 orderId·eventId 1 |
| 구매 활동 | 상품별 2행, 수량 2·1 |
| 재고·포인트 | 예약 추가 소비·반환 없음, 포인트 추가 차감 없음 |

## 10. 태스크와 테스트 목록 (후보)

태스크마다 발제 근거를 확인하고 골격 제안 → 테스트 검토 → 운영 코드 순으로 진행한다. 아래 테스트 이름은 후보이며 골격 제안에서 확정한다. 한 유스케이스에 3~5개를 넘기지 않는다.

| 단계 | 태스크 | 근거(발제) | 테스트 후보 (규칙 하나당 하나) |
|---|---|---|---|
| 1 | Outbox 모델 | 무엇을 저장할 것인가 | PENDING으로 생성된다 / 발행 확인하면 PUBLISHED와 publishedAt / 실패하면 PENDING 유지·다음 시각·오류 기록 / 이미 PUBLISHED는 변경 없음 |
| 2 | 저장소·테이블 | 무엇을 저장할 것인가 | 같은 eventId는 두 번 저장되지 않는다 / 재시도 시각이 된 PENDING만 id 순으로 찾는다 |
| 3 | (4단계에 합침) payload 직렬화는 별도 클래스 없이 `OrderConfirmedOutboxRecorder`가 맡는다 | Immutable payload | 4단계 첫 테스트에서 저장 payload를 계약으로 디코드해 확인한다 |
| 4 | 확정 TX 편입, 직접 send 제거 | Transactional Outbox | 최초 SUCCESS는 CONFIRMED와 Outbox 1행 / 같은 거래 재적용은 Outbox 1행 / PENDING·FAILED는 Outbox 0 / Outbox 저장 실패면, 또는 Outbox 저장 뒤 다른 변경이 실패해도 주문·시도가 직전 상태이고 Outbox 0 |
| 5 | W6 발행 테스트 정리 | | 제거·이관 대상 목록을 먼저 제안하고 승인받는다. 검사를 통과시키려고 지우지 않는다 |
| 6 | Kafka 발행 어댑터 | Relay는 어디까지 | 저장된 key·payload 그대로 보낸다 / future 성공을 반환 / 실패·timeout을 구분해 반환 |
| 7 | relay 1회 실행 | Relay는 어디까지 | 성공하면 PUBLISHED / 실패하면 PENDING·다음 시각 / 재시도 시각 전 행은 건드리지 않는다 |
| 8 | 스케줄·단일 실행 | 단일 Relay 다음 | 실행이 겹치지 않는다 / 설정이 꺼져 있으면 돌지 않는다 |
| 9 | 중단 실험 | 두 번 발행되는 것이 정상인 구간 | commit 후 relay 전 중단 → 새 relay가 발행 / broker 확인 후 PUBLISHED 저장 전 중단 → PENDING 유지, 재시작 후 다른 offset 재발행 (실제 Kafka) |
| 10-1 | 재시도·재개 (문서만) | 재시도와 재처리의 책임 | 코드 없음. 자동 재시도가 재개 경로, backlog·재개 SQL, BLOCKED·백오프 미도입 이유, 보존 정책 |
| 10-2 | broker 장애 회로 차단 | 재시도와 재처리의 책임 | 회로가 열리면 relay가 회차를 건너뛰어 시도 횟수를 올리지 않는다 / 실패가 이어지면 회로가 열려 broker에 보내지 않고 거절한다 |
| 11-1 | W6 누락 재현·복구 도구 | 선택을 설명하기 | 누락 주문에 저장된 사실 그대로 Outbox를 채우고 PG·주문·재고를 건드리지 않는다 / 두 번 복구해도 효과 없음 / 확정되지 않은 주문은 거절 |
| 11-2 | 복구 뒤 구매 활동 확인 | 선택을 설명하기 | 8장 확인 표 (12단계 consumer 배선 뒤) |
| 12 | E2E | 두 번 발행되는 것이 정상인 구간 | 9장 기대값 표 |
| 13 | 회귀·제출 | | `check` 두 앱, PR 본문, 기술 글 |

## 11. 결정 기록

| 대안 | 버린 이유 |
|---|---|
| PUBLISHED를 send보다 먼저 저장 | 중단 시 미발행 사건이 완료로 숨고 relay가 다시 찾지 못한다 |
| Outbox를 `REQUIRES_NEW`나 after-commit에서 insert | 주문 확정과 따로 commit되어 W6와 같은 빈틈이 생긴다 |
| relay가 현재 상품을 다시 읽어 payload 생성 | 재발행 결과가 달라지고, 확정 당시 사실이 바뀐다 |
| send 콜백에서 상태 변경 | 늦은 callback이 다음 시도의 상태를 덮어쓸 수 있다. 상태 변경은 relay 루프 한 곳에 둔다 |
| 타임아웃마다 새 eventId | 소비자의 같은 사건 중복 처리가 깨진다 |
| 다중 relay (claim/lock) | 소유권·만료·순서 문제가 추가된다. 이번에는 단일 relay와 실행 설정으로 제한한다 |
| CDC (Debezium 등) | connector·offset·스키마 운영이 필요하다. 이번에는 도입 비교만 한다 |
| 전체 이력을 쌓는 event sourcing | Outbox는 전달할 사건만 보존한다. 원본은 주문·결제 DB다 |
| `BLOCKED` 상태로 영구 오류 격리 | 특정 record만 실패할 여지가 거의 없고, broker 장애에 쓰면 회복 뒤 수동으로 모두 되살려야 한다 |
| 시도 횟수에 따른 지수 백오프 | 실패가 broker 단위라 장애 동안 모든 행의 간격이 늘어나 회복 뒤 전송만 늦어진다. 장애는 회로 차단으로 다룬다 |
| 재개용 직접 전송 진입점 | 새 프로세스에서 보내면 relay 실행자가 둘이 되고, 자동 재시도로 줄일 대기는 최대 30초뿐이다 |
| relay 회차 잠금 (`tryLock`) | 스케줄러가 같은 작업을 동시에 실행하지 않고 다른 호출자도 없어 막는 상황이 없다 |
