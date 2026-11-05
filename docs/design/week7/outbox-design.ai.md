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

- 결정 필요 항목: 영구 오류를 `BLOCKED`로 분리할지. 분리하면 정상 대상이 계속 진행되지만 BLOCKED는 "사람이 판단할 미해결 작업"으로 목록에 남기고 같은 행을 재개하는 경로가 필요하다. 3단계 골격 제안에서 정한다. 정하기 전에는 PENDING + attemptCount + lastError로 관찰한다.
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

- 단일 실행: 스케줄러 `fixedDelay`(같은 작업은 fixedRate여도 겹치지 않는다. 회차가 끝난 뒤 쉬는 시간을 보장하려고 고른다) + `relayOnce`의 `tryLock`(같은 프로세스에서 수동 실행과 겹치면 건너뜀). 활성화 설정 `outbox.relay.enabled`는 기본 false이고 `local` 프로필만 true. 다중 인스턴스에서 동시에 켜지 않는 것은 실행 설정의 약속이며 메모리 flag로 해결했다고 주장하지 않는다.
- 전송 확인은 send 호출이 반환된 것이 아니라 producer future의 성공이다. `acks=all`, 멱등 유지(W6). 단일 broker 한계 유지.
- timeout 뒤에도 하위 send가 나중에 성공할 수 있다. 같은 eventId를 유지하고 수신 0회라고 단정하지 않는다.
- 설정값(초안, 3단계에서 확정): 묶음 크기, send 대기 시간, 재시도 간격(고정 또는 시도 수에 따른 증가), 최대 간격. 테스트는 `Clock`을 고정하거나 짧은 유한 대기를 쓴다.
- 오류 메시지에는 예외 종류와 짧은 원인만 남긴다.

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
| backlog | `status = PENDING`의 건수, `MIN(createdAt)`로 가장 오래 기다린 시각, `MAX(attemptCount)`, `lastError` 목록 (SQL) |
| 로그 | relay 회차마다 구조화 로그: 선택 건수, 성공·실패 건수. 건별 로그에 eventId·orderId·attemptCount |
| 재개 | eventId를 지정해 relay 한 회차를 실행하는 로컬 전용 진입점(테스트 도구 또는 작은 내부 컴포넌트). 공개 관리자 API로 노출하지 않는다 |
| 추적 | eventId·orderId로 주문 → Outbox → broker(topic/partition/offset) → 구매 활동을 따라간다 |
| 보존 | 미발행·격리 행은 자동 삭제하지 않는다. PUBLISHED도 즉시 삭제하지 않는다. Outbox 보존, Kafka retention, 소비 완료 기록(`processed_order_confirmation`) 보존 기간은 함께 정한다. 오래된 이벤트를 다시 보낼 수 있는데 dedup 기록만 먼저 지우면 중복 효과가 생긴다 |
| 구분 | PUBLISHED를 소비 완료로 표시하지 않는다. consumer lag·오류·실제 구매 활동을 따로 본다 |

## 8. W6 누락 재현·복구

W7 코드에서는 확정 TX가 Outbox를 함께 쓰므로 같은 경로로는 누락 상태를 만들 수 없다. 합의: **테스트가 W6 경로를 흉내 낸다**(Outbox 없이 확정 TX만 실행하고 send 직전에서 멈춘다).

복구 대상은 이번 재현 실행의 두 주문뿐이다. 이전 POINT 주문을 소급하지 않는다.

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
| 10 | 재시도·재개 | 재시도와 재처리의 책임 | broker 복구 뒤 기존 PENDING이 처리된다 / 지정한 eventId 한 건만 재개 / backlog 조회 |
| 11 | W6 복구 | 선택을 설명하기 | 8장 확인 표 |
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
