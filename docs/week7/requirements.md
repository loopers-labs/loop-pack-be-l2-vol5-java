# 요구사항 명세서 (W7): 주문 확정과 전달할 사실을 함께 남기고, 중단 뒤 같은 구매 활동으로 수렴시킨다

원문 과제(`ref/week7/assignment.md`, 학습 자료 `ref/week7/study.md`)를 구현과 테스트의 기준으로 쓸 수 있게 정리한 문서다.
W6 명세(`docs/week6/requirements.md`)의 규칙 ID 와 출처 구분을 그대로 쓰고, 이번 주에 생긴 것만 적는다.

| | |
|---|---|
| 원문 | Vol5 W7. 확정 후 직접 발행을 outbox 기록과 단일 relay 로 바꾸고, 발행 중단, 재시작, 기존 누락 복구를 검증한다 |
| 이 문서가 하는 일 | outbox 에 무엇을 언제 남기는지, relay 가 지킬 것, 복구와 재개와 운영 확인의 기준, 실험으로 증명할 것을 적는다 |
| 이 문서가 하지 않는 일 | 테이블 구조, 설정값, 진입점 형태의 근거. 설계 문서(`docs/week7/design.md`)에 적는다 |

## 출처 구분

W2 의 표기(`원문`, `정함`, `선택`, `보안`, `범위`, `질문`)를 그대로 쓴다.

## 규칙 ID

W6 의 갈래에 넷을 더한다. `EXP` 는 W6 번호를 이어 쓴다.

| 접두사 | 다루는 것 |
|---|---|
| `OBX` | 확정 TX 안의 outbox 기록 |
| `RLY` | outbox 를 broker 로 옮기는 단일 relay |
| `RCV` | outbox 가 없는 확정 주문의 복구, `BLOCKED` 행의 재개 |
| `OPS` | 운영 확인과 보존 |

---

## 바뀌는 기존 계약

| 기존 규칙 | 내용 | 이번 주 |
|---|---|---|
| `EVT-012` | 확정 TX 가 commit 된 뒤에 발행한다 | commit 뒤 직접 발행을 없앤다. 확정 TX 안에서 outbox 에 남기고(`OBX-001`) 발행은 relay 만 한다(`RLY-001`). 직접 send 와 relay 를 병행하지 않는다 |
| `EVT-013`, `EVT-017` | 발행 실패는 확정을 되돌리지 않고 실패 지표와 로그로 드러낸다 | 실패한 사건은 outbox 에 남아 relay 가 다시 보낸다(`RLY-004`). 확정을 되돌리지 않는 것은 같다 |
| `EVT-002`, `EVT-003` | 같은 확정 사건의 `eventId` 와 payload | 그대로다. outbox 에 저장된 key 와 payload 는 W6 이 보내던 것과 같은 바이트다 |
| `ACT-*` | 구매 활동 소비의 멱등, 충돌, 원자성 | 그대로다. relay 의 재발행은 `ACT-004` 가 막는다 |

---

## OBX: 확정 TX 안의 기록

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `OBX-001` | 최초 성공 반영, 예약 소비, 주문 `CONFIRMED` 와 같은 데이터 소스의 같은 로컬 TX 에서 `OrderConfirmed` 를 outbox 에 `PENDING` 으로 insert 한다. `REQUIRES_NEW` 나 after-commit insert 로 나누지 않는다 | 원문 | commit 뒤 주문 `CONFIRMED` 와 outbox `PENDING` 한 행이 함께 있다 |
| `OBX-002` | `eventId` 는 W6 의 같은 확정 사건 ID(`order-confirmed:v1:{orderId}`)이고 DB 유일 제약을 둔다 | 원문 | 같은 `eventId` 의 두 번째 insert 는 실패한다 |
| `OBX-003` | `orderId`, `eventType`, `schemaVersion` 으로 원본 주문과 계약을 잇는다. `messageKey` 와 `payload` 는 W6 과 같고 생성 뒤 바꾸지 않는다 | 원문 | relay 가 보낸 record 가 저장된 행과 바이트까지 같다 |
| `OBX-004` | `status` 는 처음에 `PENDING`, broker 확인 뒤 `PUBLISHED` 다. 다시 보내도 풀리지 않는 거절은 `BLOCKED` 로 따로 둔다 | 원문, 선택(`BLOCKED`) | |
| `OBX-005` | `createdAt` 과 `publishedAt` 으로 기록 시각과 발행 확인을 저장한 시각을 구분한다. `attemptCount`, `nextAttemptAt`, `lastError` 로 재시도와 지연과 실패를 본다. `nextAttemptAt` 은 DB 시각이어서, 앱 서버 시계와 DB 시계가 달라도 시각이 된 행을 고르는 기준이 하나다 | 원문, 정함(DB 시각) | 새 행의 `next_attempt_at` 이 insert 시점의 DB 시각이다 |
| `OBX-006` | `PENDING`, `UNKNOWN`, 확정된 `FAILED` 는 outbox 를 만들지 않는다. 같은 거래의 callback 이나 reconcile 이 여러 번 와도 한 행만 남긴다 | 원문 | 반복 반영 뒤에도 한 행 |
| `OBX-007` | outbox 기록이 실패하면 최초 성공 반영과 예약 소비도 rollback 한다. 원래의 미완료 주문, 이전 시도, 예약은 TX 직전 상태로 남는다. 주문 행이 없어지는 것이 기대 결과가 아니다. 외부 PG 의 SUCCESS 는 남을 수 있으므로 같은 거래를 다시 조회해 반영하고, PG create 는 늘리지 않는다 | 원문 | 주문 `PENDING`, 결제 상태와 재고 그대로, 추가 PG create 0, 새 outbox 0 |
| `OBX-008` | payload 직렬화도 같은 TX 안에서 하고, 직렬화 실패는 확정 TX 의 실패다. relay 가 현재 상품 정보를 다시 읽어 payload 를 만들지 않는다 | 원문 | |

## RLY: 단일 relay

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `RLY-001` | 한 번에 한 relay 실행 흐름만 켠다. 회차가 겹치지 않게 하고, 다른 인스턴스에서 같은 relay 를 켜지 않도록 로컬 실행 설정을 둔다. 여러 프로세스 사이의 소유권을 메모리 flag 로 해결했다고 주장하지 않는다 | 원문 | relay 를 켠 인스턴스의 전용 스레드 하나가 주기마다 보낸다 |
| `RLY-002` | 재시도 시각이 된 `PENDING` 을 작은 묶음으로, 순서가 바뀌지 않는 기준으로 고른다. 조회에 맞는 인덱스를 둔다 | 원문 | `PUBLISHED`, `BLOCKED`, 시각이 되지 않은 행은 고르지 않는다 |
| `RLY-003` | 확정 TX 밖에서 저장된 topic, key, payload 를 그대로 send 한다. 전송 확인은 producer future 의 성공이고, 확인한 뒤 별도 DB TX 에서 `PUBLISHED` 와 `publishedAt` 을 저장한다 | 원문 | 보낸 record 의 key 와 payload 가 행과 같고 행이 `PUBLISHED` 다 |
| `RLY-004` | 전송 실패와 timeout 은 같은 행을 `PENDING` 으로 두고 다음 시각과 오류를 기록한다. 실패 뒤 곧바로 끝없이 반복하지 않는다. timeout 을 broker 가 받지 않았다는 뜻으로 단정하지 않는다 | 원문 | 시도 수가 1 늘고, 다음 시각이 뒤로 가고, 원인이 기록되고, 그 시각 전에는 다시 고르지 않는다 |
| `RLY-005` | broker 가 내용 때문에 거절한 사건(같은 record 를 다시 보내도 결과가 같은 것)은 `BLOCKED` 로 옮기고 원인을 남긴다. 자동으로 다시 고르지 않는다 | 선택, 정함(거절의 범위) | `BLOCKED`, 원인 기록, 다시 고르지 않음 |
| `RLY-006` | broker 확인 뒤 상태 TX 전에 멈추면 `PENDING` 이 남아 같은 사건을 다시 보낸다. 기다리기를 멈춘 뒤 send 가 늦게 성공해도 상태를 바꾸지 않는다. 상태를 바꾸는 곳은 relay 의 한 경계뿐이다 | 원문 | 같은 `eventId` 가 다른 offset 으로 두 번 |
| `RLY-007` | DB 조회와 상태 변경 TX 를 외부 전송 대기와 분리한다. future 를 제한 없이 만들거나 회차를 겹쳐서 범위를 넓히지 않는다 | 원문 | |
| `RLY-008` | 오류 기록에 카드 정보, 비밀 값, payload 전체를 남기지 않는다 | 원문, 보안 | `last_error` 에는 예외 종류와 메시지만 있다 |

## RCV: 복구와 재개

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `RCV-001` | 확정됐지만 outbox 가 없는 주문은 저장된 확정 사실에서 기존 `eventId`, `confirmedAt`, 품목을 그대로 써서 한 행을 추가한다. 이미 있으면 내용이 같은지 확인하고 덮지 않는다. 원본 사실이 부족하거나 충돌하면 추측하지 않고 오류를 낸다 | 원문 | 같은 바이트의 `PENDING` 한 행. 두 번째 복구는 행을 늘리지 않는다. 내용이 다르면 충돌 |
| `RCV-002` | 복구는 PG 를 다시 부르지 않고, 주문을 다시 확정하거나 예약을 다시 소비하지 않는다. 새 결제를 만들지 않는다 | 원문 | 복구 전후로 PG create, 조회, callback 이 늘지 않고 주문, 재고, 결제가 그대로다 |
| `RCV-003` | 복구 진입점은 관리자 API 다 | 정함(Q-2) | |
| `RCV-004` | `BLOCKED` 는 미해결 상태로 남고, 원인을 고친 뒤 같은 행을 `PENDING` 으로 되돌려 다시 보낸다. 대상 `eventId` 를 직접 지정한다. 진입점은 로컬 전용이고 공개 관리자 API 로 열지 않는다 | 원문, 정함(Q-1, 진입점 형태) | 재개한 행이 같은 key 와 payload 로 나가 `PUBLISHED` 가 된다 |
| `RCV-005` | 이전 POINT 확정 주문 전체를 자동으로 소급하지 않는다. 대상은 지정한 주문뿐이다 | 원문 | |

## OPS: 운영 확인과 보존

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `OPS-001` | 상태별 건수, 가장 오래 기다린 `PENDING` 의 생성 시각, 시도 수를 SQL 로 확인할 수 있다. 새 모니터링 서버는 두지 않는다 | 원문 | 설계 문서의 운영 절차에 SQL 이 있다 |
| `OPS-002` | `PUBLISHED` 를 소비 완료로 보지 않는다. consumer lag, 오류, 실제 구매 활동을 따로 확인한다 | 원문 | |
| `OPS-003` | 발행되지 않은 행과 `BLOCKED` 행을 자동 삭제하지 않는다. 보존과 정리 정책이 소비 쪽 처리 기록(`ACT-002`)의 보존과 어떤 관계인지 적는다 | 원문 | |

## EXP: 실험

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `EXP-010` | 로컬 원자성. relay 없이 실제 DB 에서 outbox 기록 실패로 확정 TX 를 rollback 하고, 같은 거래 결과를 다시 적용한다 | 원문 | `OBX-007` 의 관찰값. 다시 적용하면 주문 확정, 예약 소비, outbox 가 각각 한 번 |
| `EXP-011` | commit 뒤 relay 전 중단. 주문과 outbox `PENDING` 은 있고 구매 활동은 아직 없다. relay 를 새로 만들어 실행하면 저장된 행에서 사건을 찾아 보낸다 | 원문 | |
| `EXP-012` | broker 확인 뒤 `PUBLISHED` 저장 전 중단. 실제 send 성공 metadata 를 얻고 상태는 저장하지 않는다. 같은 DB 와 Kafka 에서 relay 를 새로 만들어 같은 사건을 다시 보낸다. 테스트가 outbox 상태를 직접 덮어써 복구 조건을 만들지 않는다. 예외를 던진 테스트를 OS 강제 종료라고 부르지 않는다 | 원문 | 같은 `eventId` 가 다른 offset 으로 두 번 나가고, 행은 최종 `PUBLISHED` |
| `EXP-013` | 주문부터 구매 활동까지 한 실행. 기존 주문 API, PG fixture, 실제 DB 와 Kafka, 실제 구매 활동 consumer 를 연결하고, relay 를 `EXP-012` 처럼 멈췄다가 다시 돌린다. 두 번 나간 것은 broker 의 실제 record 로 확인하고 send 호출 수만 세지 않는다. 이 실행만 쓰는 topic, group, 주문을 쓰고, 테스트 자동 rollback 안에서 진행하지 않으며, 이 실행이 만든 자원만 정리한다 | 원문, 정함(Q-3, 구성) | 상품 7 × 2, 상품 8 × 1 인 주문 하나에 대해 주문과 PG 거래 각 1, outbox 1 행이 최종 `PUBLISHED`, 같은 `eventId` record 2 개, 처리 기록 1 건, 구매 활동은 상품별로 2 행(수량 2, 1), 예약 추가 소비와 반환 없음 |
| `EXP-014` | W6 누락 상태를 재현하고 같은 실행 안에서 복구한다. 새로 띄운 MySQL, Kafka, PG fixture 를 실행이 직접 관리한다. W6 실행물로 실제 확정과 after-commit 직접 발행 경로를 돌려 A(확정 commit 뒤 send 전 중단)와 B(발행과 소비 완료)를 만들고, 두 주문의 `orderId`, `eventId`, `confirmedAt`, `items`, PG 거래 식별자와 PG create 누적 횟수 C, 외부 거래 수 T 를 기록한다. 복구가 끝날 때까지 DB, broker, PG 를 초기화하지 않는다. 같은 DB 를 W7 로 바꿔 A 와 B 를 복구(`RCV-001`)하고 relay 와 기존 consumer 를 돌린다. SUCCESS 를 직접 넣거나 W7 outbox 행을 지워서 준비 상태를 만들지 않는다 | 원문, 정함(Q-4, 수단) | A 는 빠졌던 구매 활동이 생긴다. B 는 재발행이 와도 활동과 수량이 그대로다. 두 번째 복구도 아무것도 바꾸지 않는다. 복구 중 PG create 는 C 그대로, 조회와 callback 은 늘지 않고, 외부 거래 수는 T 그대로이며, 재확정과 예약 추가 소비가 없다 |

### 원문 체크리스트와 규칙

| 원문의 체크 | 규칙 |
|---|---|
| 최초 PG 확정, 예약 소비, outbox insert 가 같은 TX 에서 commit 또는 rollback 된다 | `OBX-001`, `OBX-007`, `EXP-010` |
| 중복 결과 적용, 실패, UNKNOWN 에서 거짓 이벤트나 추가 결제가 생기지 않는다 | `OBX-006` |
| 기존 주문의 rollback 결과를 신규 주문 삭제와 혼동하지 않는다 | `OBX-007` |
| 단일 relay 가 저장된 identity 와 payload 를 그대로 보내고 broker 확인 뒤 `PUBLISHED` 로 바꾼다 | `RLY-001`, `RLY-003` |
| commit 후 relay 전 중단과 broker 확인 후 상태 저장 전 중단을 검증한다 | `EXP-011`, `EXP-012` |
| 실제 broker 와 consumer 에서 재발행돼도 상품별 구매 효과가 늘지 않는다 | `EXP-013` |
| 새 격리 DB 에서 W6 누락과 소비 완료 상태를 재현하고 복구한다 | `EXP-014` |
| 준비와 복구의 PG 호출 수를 나눠 기록하고, 복구 중 추가 호출, 재확정, 예약 소비가 없다 | `RCV-002`, `EXP-014` |
| `PENDING`, `PUBLISHED`, 소비 완료를 구분하고 실패, 쌓인 행, 재개 방법을 확인한다 | `OPS-001`, `OPS-002`, `RCV-004` |
| 미발행 사실을 지우지 않고, 단일 relay 와 보존과 재시도의 한계를 설명한다 | `OPS-003`, `RLY-001`, `RLY-004` |
| 관련 회귀, lint, ArchUnit 을 실행하고 자신이 만든 테스트 자원만 정리한다 | `EXP-013`, `EXP-014` |

---

## 물을 질문

| # | 질문과 답 | 관련 규칙 |
|---|---|---|
| ~~Q-1~~ | ~~`BLOCKED` 재개를 어디서 실행하는가~~ 기동 인자로 한 번 실행한다. `outbox.resume.event-ids` 를 주고 앱을 띄우면 그 행들을 `PENDING` 으로 되돌리고, 보내는 일은 기존 relay 가 한다. 원문은 공개 관리자 API 를 막고 로컬 진입점이나 테스트 도구를 허용한다. 테스트 도구만 두면 운영에서 쓸 방법이 없고, 진입점이 직접 보내기까지 하면 relay 와 실행 흐름이 둘이 된다. **뒤집을 조건**: 앱을 다시 띄우지 않고 재개를 자주 해야 할 때. 인증된 내부 운영 경로를 따로 정한다 | `RCV-004` |
| ~~Q-2~~ | ~~이미 있는 복구 관리자 API 를 유지하는가~~ 유지한다. 원문이 공개 관리자 API 를 막은 대상은 `BLOCKED` 재개 진입점이다. 복구는 관리자 인증 뒤에 있고, 주문 행 잠금과 충돌 거절과 반복 호출의 무해함이 테스트로 확인돼 있어 잘못 불러도 사실을 바꾸지 않는다. `EXP-014` 도 이 경로로 복구한다. **뒤집을 조건**: 관리자 API 를 사람이 아닌 외부 시스템이 부르게 되거나, 복구가 저장된 사실 밖의 값을 받아야 할 때 | `RCV-003` |
| ~~Q-3~~ | ~~`EXP-013` 에서 실제 consumer 를 어떻게 붙이는가~~ commerce-api 의 JUnit 이 streamer `bootJar` 를 자식 JVM 으로 띄운다. W6 Q-4 의 뒤집을 조건("W7 이 이 실행을 outbox 경로로 매번 자동으로 돌려야 할 때")에 해당한다. relay 중단은 프로세스를 죽이는 시점에 기대지 않고 보내고 기록하지 않는 방식으로 만들어 결과가 매번 같다. 한 JVM 에 두 앱을 넣으면 Bean 과 엔티티가 섞인다. 제품 runtime 에 commerce-api 에서 commerce-streamer 로 가는 의존을 만들지 않는다. **뒤집을 조건**: 자식 JVM 기동이 `test` 시간을 크게 늘리거나 불안정할 때. 별도 테스트 task 로 뺀다 | `EXP-013` |
| ~~Q-4~~ | ~~`EXP-014` 의 W6 경로를 어떻게 돌리는가~~ W6 revision(`upstream/seeun0210`)을 git worktree 로 빌드한 jar 를 프로세스로 띄운다. W6 의 직접 발행 코드는 지웠고, 원문이 W6 revision 실행물을 허용한다. A 는 W6 Q-6 의 뒤집을 조건대로 `linger.ms` 를 길게 준 W6 api 를 확정 commit 뒤 `kill -9` 해서 지표에도 남지 않는 누락으로 만든다. 직접 발행 코드를 테스트 소스에 남기면 실제 W6 실행물이 아니다. **뒤집을 조건**: W6 revision 을 빌드할 수 없게 될 때 | `EXP-014` |

## 범위 밖

별도 결제수단, 새 주문 도메인, 범용 saga, CDC 플랫폼, 다중 relay(원문).
relay 를 CDC 로 바꿀 때의 근거는 `docs/week7/cdc-experiment.md` 에 모은다.
