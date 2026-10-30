# W6 코드 리뷰 요약 (10-29 새벽 리뷰 · 저녁 R-1 ~ R-6 · R-12 반영)

> 범위 `44dfeab0..33f13235` (`volume-6`). 리뷰어: domain · persistence · test (API 계약 변경이 없어 api-contract 제외).
> 지적마다 코드로 사실을 확인했다. **아무것도 고치지 않았다** — 반영 여부는 학습자가 정한다 (review 스킬 4·5절).

## 지적

| # | 심각도 | 지적 | 위치 | 사실 확인 | 제안 |
|---|---|---|---|---|---|
| R-1 | Important | send가 **동기로** 실패하면 `markFailed`가 AFTER_COMMIT 스레드에서 이미 commit된 TX에 합류해 `last_error`가 남지 않는다. 판정(`published_at` null)은 유지되지만, broker 장애로 생긴 누락이 "콜백조차 없음"과 구분되지 않는다 | `outbox/application/OutboxPublishService.java` catch 분기 → `EventOutboxJpaRepository` `@Transactional`(REQUIRED) | ✅ `KafkaTemplate.doSend`(spring-kafka 3.3.4)는 즉시 실패한 future를 `KafkaException("Send failed")`로 던진다 — 소스 확인. metadata timeout(`max.block.ms`, broker 전체 장애 = E-6 상황)이 이 경로다. TX 합류로 기록이 사라지는 부분은 Spring 문서·소스 기준 추론이고 실행으로는 확인하지 않음 | ✅ 반영(10-29) — 동기 예외 주입 테스트가 Red(5초 안에 last_error 없음)로 실행 확인, 결과 executor로 옮겨 Green |
| R-2 | Important | "재시도 없이 DLT"(충돌·계약 위반)를 테스트가 증명하지 않는다. DLT 대기 10s > backoff 합 7s라서 `addNotRetryableExceptions`를 지워도 통과한다 | `OrderConfirmedListenerIntegrationTest` 충돌·계약 위반 테스트 | ✅ 대기 `Duration.ofSeconds(10)`, backoff 1+2+4s | ✅ 반영(10-29) — 학습자가 "모든 실패를 재시도 뒤 DLT"로 정함. 계약 위반·충돌 모두 시도 1+3번을 파싱 횟수로 단언 |
| R-3 | Important | "성공했을 때만 ack"를 잡는 테스트가 없다. `ack.acknowledge()`를 `consume` 앞으로 옮겨도 E-3의 최종 상태(DLT 1 · n+1 · 0행)는 같다. 운영에서는 재시도 중 프로세스가 죽으면 유실 | E-3 `retriesThenDeadLettersAndReplaysOnce`, `OrderConfirmedListener` | ✅ 단언이 최종 상태만 본다 | ✅ 반영(10-29) — 재시도 도중 committed offset ≤ n 단언. ack를 처리 앞으로 옮기면 실패함을 확인 |
| R-4 | Important | C-6 "동시 중복" 테스트가 경합을 강제하지 않는다. 두 스레드가 직렬로 돌면 빠른 길(SELECT)로 끝나, 1062 → 새 TX 재조회 분기를 지워도 통과할 수 있다 (persistence·test 공통) | `PurchaseActivityServiceIntegrationTest#concurrentDuplicatesSaveOnce` | ✅ 지난 실행 로그에 `Duplicate entry 'evt-42'`가 있어 그때는 지나갔지만 보장은 없다 | ✅ 반영(10-29) — CyclicBarrier로 두 스레드가 모두 SELECT를 지난 뒤 INSERT. 1062 재판정을 지우면 실패함을 확인 |
| R-5 | Important | C-2 "reconcile 두 번 → 기록 1건"은 결제 단계 멱등을 보여 줄 뿐, `if (changed)` 가드를 증명하지 않는다 — 두 번째는 `ALREADY_APPLIED`로 `applyToOrder`에 가지 않는다 | `OrderConfirmedPublishIntegrationTest#repeatedApplyPublishesOnce`, `PaymentResultApplier` `if (changed)` | ✅ 테스트가 `ALREADY_APPLIED`를 단언한다 | ✅ 반영(10-29) — 테스트 이름을 "결제 단계에서 막힘"으로, "결제 PENDING · 주문 이미 CONFIRMED → 기록 0" 테스트 추가. 가드를 지우면 실패함을 확인 |
| R-6 | 중간 | streamer 파싱이 공용 Jackson의 느슨한 변환을 따른다 — `"quantity": 1.9` → 1, `"productId": "7"` → 7, `items`가 배열이 아니어도 통과. 계약 위반이 DLT가 아니라 **조용히 바뀐 값으로 저장**된다 | `purchase/adapter/in/kafka/OrderConfirmedMessageParser.java` | ✅ 실수·문자열 숫자를 거절하는 `StrictNumberInputConfig`는 commerce-api에만 있다 | ✅ 반영(10-29) — parser 전용 엄격 ObjectMapper. 1.9 · "7" · 단일 객체 items 거절, listener에서 1+3번 뒤 DLT |
| R-7 | 중간 | partition 수가 api(`OrderEventsTopicConfig`)·streamer(DLT `.partitions(3)`) 두 앱에 박혀 있다. order-events만 늘리면 DLT의 같은 partition 번호가 없어 E-3의 "DLT 발행 실패로 partition 정지"가 다시 난다 | `PurchaseActivityKafkaConfig` resolver·DLT topic | ✅ resolver가 `record.partition()` 그대로 | ✅ 반영(10-29) — resolver partition `-1`(key로 선택). key hash와 다른 partition에 보낸 record가 DLT에서 key의 partition에 쌓이는 테스트로 Red → Green |
| R-8 | 낮음 | `whenCompleteAsync` 성공 분기에서 `markPublished`가 실패하면 로그 없이 사라진다 (실패 분기에만 try/catch) | `OutboxPublishService` | ✅ | ⏸ W7로 미룸(학습자 10-29) — relay가 같은 코드를 다룰 때 콜백 전체를 try/catch로 감싸고 broker 결과를 로그에 남긴다. 체크리스트 밖 |
| R-9 | 낮음 | 트랜잭션 위치가 ADR-11(서비스 공개 메서드) 밖이다 — JPA 리포지토리 `@Transactional`, 소비 쪽 `TransactionTemplate` | `EventOutboxJpaRepository`, `PurchaseActivityService` | ✅ 소비 쪽은 "rollback 뒤 새 TX 재판정" 때문에 programmatic이 합리적 | 변경 이력에 ADR-11 예외로 한 줄. `findExisting` readOnly |
| R-10 | 낮음 | payment가 order의 구체 클래스 `OrderConfirmedRecorder`를 직접 부른다(선례 `StockReservationUseCase`는 port.in). 주문 쪽이 topic 이름을 안다 | `PaymentResultApplier`, `OrderConfirmedRecorder` | ✅ | 유지하면 선례로 적기. topic 라우팅은 W7 relay 때 옮길 후보 |
| R-11 | Minor | NoEvent 테스트 이름의 "record 없음"은 broker를 읽지 않는다. rollback 테스트는 예약 RESERVED 유지를 보지 않는다 | `OrderConfirmedPublishIntegrationTest$NoEvent` | ✅ | broker 확인 또는 이름 수정, 예약 상태 단언 추가 |
| R-12 | Minor | confirmedAt을 Instant로 비교하고 형식 정규식이 Z·offset 둘 다 허용 | 같은 파일 `payloadMatchesStoredFacts` | ✅ | ✅ 반영(10-29) — KST `+09:00` 고정으로 정했고 정규식을 `+09:00`으로 좁혔다 |
| R-13 | Minor | `carriesNoSensitiveOrExtraFields`는 record 구성 요소만 보는 변경 감지기 — 실제 보호는 api의 shape 테스트 | `OrderConfirmedTest` | ✅ Red에서 이미 통과 | 유지하되 C-3 근거는 shape 테스트로 |
| R-14 | Minor | 거절 테스트 빈칸: 음수 수량, 형식 아닌 confirmedAt, productId null. `@DisplayName`에 규칙 ID·`// arrange·act·assert` 주석이 거의 없다 | streamer 도메인 테스트 | ✅ | 사례 추가, 관례 맞추기 |
| R-15 | Minor | S13 1번의 `doNothing().doCallRealMethod()`는 "첫 publish 호출"에 걸린다 — 앞 장면의 늦은 비동기 publish가 stub을 가져가면 거짓 실패 | `S13PublishGapScenarioTest` | ✅ | `argThat`으로 eventId·aggregateId에 거는 stub |
| R-16 | Minor | E-5에 plan이 적은 "send 콜백 실패 → outbox published_at null"이 없다(raw producer만). `@Order` 상태 공유라 메서드 하나만 돌리면 결과가 다르다 | `BrokerFailureReplicationTest` | ✅ | outbox 쪽은 S13 주입 실패로 대신한다고 plan에 적기 |
| R-17 | Minor | D-6·outbox 표의 `event_id PK`와 달리 구현은 대리키 `id` + `event_id` UNIQUE. 소비 테이블에 쓰지 않는 `deleted_at` | plan.md, 엔티티 | ✅ "구현 때 확정 🔧" 범위 | 변경 이력에 한 줄 |

기각한 지적: 없음. domain 4번(동기 실패는 드물다)은 R-1과 같은 지적인데 빈도 판단이 틀렸다 — metadata timeout도 `KafkaTemplate`에서 동기로 던진다.

## 판정

**PR 전에 고치는 것을 권한다 — R-1 ~ R-6.** → 10-29 저녁 모두 반영. 고친 뒤 재리뷰는 하지 않고 검사만 다시 돌렸다(review 스킬 5절). 기능은 설계대로 동작하고 거짓 이벤트 경로는 없다. 다만 R-2 ~ R-5는 체크리스트(C-2·C-6·C-7)가 인용한 테스트가 주장보다 덜 증명하고, R-1·R-6은 ADR-W6-03·06이 약속한 동작이 특정 경로에서 지켜지지 않는다.

## 🤔 학습자가 정할 것

1. **R-1 ~ R-6을 고칠지**, 고친다면 각 테스트의 기대값 (특히 R-6: 1.9 / "7" / 단일 객체 items를 DLT로 보낼지).
2. **R-5의 경로** — "결제 APPLIED인데 주문은 이미 CONFIRMED"가 실제로 생길 수 있나? 없으면 `if (changed)`는 방어 코드로 두고 테스트 이름만 고친다.
3. **`be537c5e` 확인** — E-5 테스트 producer만 `max.block.ms`를 1s → 15s로 늘렸다(새 topic metadata 대기). 거절 판단은 broker의 NotEnoughReplicas라 단언이 약해지지는 않았지만, 테스트 설정을 바꾼 것이므로 동의를 받는다 (과제 AI 규칙 4).
4. **plan 열린 질문 2** — payload confirmedAt을 UTC로 고정할지 (R-12와 연결).

## 참고

- 테스트 DB는 Testcontainers가 아니라 compose MySQL의 `loopers_test`이고 api·streamer가 함께 쓴다. 두 모듈 테스트를 동시에 돌리면 서로 테이블을 지운다 — 이번 변경 전부터의 구조.
- api 473건은 새벽 실행 결과다. 그 뒤 리뷰어가 `ArchitectureTest`만 다시 돌려 결과 XML이 덮어써졌다. 아침에 `verify`로 다시 돌린다.
