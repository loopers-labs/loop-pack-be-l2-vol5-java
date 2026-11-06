# 요구사항 명세서 (W7) · 주문 확정과 전달할 사실을 함께 남기고, 중단 뒤 같은 구매 활동으로 수렴시킨다

원문 과제(`ref/week7/assignment.md`, 학습 자료 `ref/week7/study.md`)를 구현 · 테스트의 기준이 될 수 있는 수준으로 정제한 문서다.
W6 명세(`docs/week6/requirements.md`)의 규칙 ID 와 출처 구분을 그대로 쓰고, 이번 주에 생긴 것만 적는다.

| | |
|---|---|
| **원문** | Vol5 W7 — 확정 후 직접 발행을 Outbox 기록과 단일 relay 로 교체하고, 발행 중단 · 재시작 · 기존 누락 복구를 검증한다 |
| **이 문서가 하는 일** | ① outbox 에 무엇을 언제 남기는지 못 박는다 ② relay 가 지킬 것을 적는다 ③ 복구 · 재개 · 운영 확인의 기준을 적는다 ④ 실험이 증명할 것을 적는다 |
| **이 문서가 하지 않는 일** | 테이블 구조 · 설정값 · 진입점 형태의 근거. 설계 기록(`docs/week7/design.md`)의 몫이다 |

## 출처 구분

W2 의 표기(`원문` · `정함` · `선택` · `보안` · `범위` · `질문`)를 그대로 쓴다.

## 규칙 ID

W6 의 갈래에 넷을 더한다. `EXP` 는 W6 번호를 이어 쓴다.

| 접두사 | 다루는 것 |
|---|---|
| `OBX` | 확정 TX 안의 outbox 기록 |
| `RLY` | outbox 를 broker 로 옮기는 단일 relay |
| `RCV` | outbox 가 없는 확정 주문의 복구, `BLOCKED` 행의 재개 |
| `OPS` | 운영 확인 · 보존 |

---

## 바뀌는 기존 계약

| 기존 규칙 | 내용 | 이번 주 |
|---|---|---|
| `EVT-012` | 확정 TX 가 commit 된 뒤에 발행한다 | **commit 뒤 직접 발행을 없앤다.** 확정 TX 안에서 outbox 에 남기고(`OBX-001`), 발행은 relay 만 한다(`RLY-001`). 직접 send 와 relay 를 병행하지 않는다 |
| `EVT-013` · `EVT-017` | 발행 실패는 확정을 되돌리지 않고, 실패 지표 · 로그로 드러낸다 | 실패한 사건이 outbox 에 남아 relay 가 다시 보낸다(`RLY-004`). 확정을 되돌리지 않는 것은 같다 |
| `EVT-002` · `EVT-003` | 같은 확정 사건의 `eventId` 와 payload | 그대로다. outbox 에 저장된 key · payload 가 W6 이 보내던 것과 같은 바이트다 |
| `ACT-*` | 구매 활동 소비의 멱등 · 충돌 · 원자성 | 그대로다. relay 의 재발행은 `ACT-004` 가 흡수한다 |

---

## OBX — 확정 TX 안의 기록

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `OBX-001` | 최초 성공 반영 · 예약 소비 · 주문 `CONFIRMED` 와 **같은 데이터 소스의 같은 로컬 TX** 에서 `OrderConfirmed` 를 outbox 에 `PENDING` 으로 insert 한다. `REQUIRES_NEW` · after-commit insert 로 나누지 않는다 | 원문 | commit 뒤 주문 `CONFIRMED` 와 outbox `PENDING` 한 행이 함께 있다 |
| `OBX-002` | `eventId` 는 W6 의 같은 확정 사건 ID(`order-confirmed:v1:{orderId}`)이고 DB 유일 제약을 둔다 | 원문 | 같은 `eventId` 두 번째 insert 는 실패한다 |
| `OBX-003` | `orderId` · `eventType` · `schemaVersion` 으로 원본 주문과 계약을 잇는다. `messageKey` · `payload` 는 W6 와 같고 **생성 뒤 바꾸지 않는다** | 원문 | relay 가 보낸 record 가 저장된 행과 바이트까지 같다 |
| `OBX-004` | `status` 는 최초 `PENDING`, broker 확인 뒤 `PUBLISHED` 다. 재시도로 풀리지 않는 거절은 `BLOCKED` 로 분리한다 | 원문 + 선택(`BLOCKED`) | — |
| `OBX-005` | `createdAt` · `publishedAt` 은 기록 시각과 발행 확인 저장 시각을 구분한다. `attemptCount` · `nextAttemptAt` · `lastError` 로 재시도 · 지연 · 실패를 관찰한다. **`nextAttemptAt` 은 DB 시각이다** — 앱 서버 시계와 DB 시계가 어긋나도 "시각이 된 행" 판정이 하나다 | 원문 + 정함(DB 시각) | 새 행의 `next_attempt_at` 이 insert 시점의 DB 시각이다 |
| `OBX-006` | `PENDING` · `UNKNOWN` · 확정 `FAILED` 는 outbox 를 만들지 않는다. 같은 거래의 중복 callback · reconcile 도 한 행만 남긴다 | 원문 | 반복 반영 뒤에도 한 행 |
| `OBX-007` | outbox 기록이 실패하면 최초 성공 반영 · 예약 소비도 rollback 한다. **원래 미완료 주문 · 이전 시도 · 예약은 TX 직전 상태로 남는다** — 주문 행이 0 개가 되는 것이 기대 결과가 아니다. 외부 PG SUCCESS 는 남을 수 있고, 같은 거래 재조회로 다시 반영하며 PG create 는 늘리지 않는다 | 원문 | 주문 `PENDING` · 결제 상태 · 재고 불변 · 추가 PG create 0 · 새 outbox 0 |
| `OBX-008` | payload 직렬화도 같은 TX 안에서 하고, 그 실패는 확정 TX 의 실패다. relay 가 현재 상품 정보를 다시 읽어 payload 를 만들지 않는다 | 원문 | — |

## RLY — 단일 relay

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `RLY-001` | 한 번에 **한 relay 실행 흐름**만 활성화한다. 회차가 겹치지 않고, 다른 인스턴스에서 같은 relay 를 켜지 않는 로컬 실행 설정을 둔다. 여러 프로세스의 소유권을 메모리 flag 로 해결했다고 주장하지 않는다 | 원문 | 켜 둔 인스턴스의 relay 전용 스레드 하나가 주기마다 보낸다 |
| `RLY-002` | 재시도 시각이 된 `PENDING` 을 **작은 묶음 · 안정된 순서**로 고른다. 조회에 맞는 인덱스를 둔다 | 원문 | `PUBLISHED` · `BLOCKED` · 시각 전의 행은 고르지 않는다 |
| `RLY-003` | 확정 TX 밖에서 저장된 topic · key · payload 를 그대로 send 한다. **전송 확인은 producer future 의 성공**이고, 확인 뒤 별도 DB TX 에서 `PUBLISHED` · `publishedAt` 을 저장한다 | 원문 | 보낸 record 의 key · payload 가 행과 같고 행이 `PUBLISHED` |
| `RLY-004` | 전송 실패 · timeout 은 같은 행을 `PENDING` 으로 두고 다음 시각 · 오류를 기록한다. 실패 뒤 즉시 무한 반복하지 않는다. timeout 을 "수신 0 회" 로 단정하지 않는다 | 원문 | 시도 수 +1 · 다음 시각이 뒤로 · 원인 기록 · 그 시각 전에는 다시 고르지 않음 |
| `RLY-005` | broker 가 내용 때문에 거절한 사건(같은 record 를 다시 보내도 같은 결과)은 `BLOCKED` 로 옮기고 원인을 남긴다. 자동으로 다시 고르지 않는다 | 선택 + 정함(거절의 범위) | `BLOCKED` · 원인 기록 · 다시 고르지 않음 |
| `RLY-006` | broker 확인 뒤 상태 TX 전에 멈추면 `PENDING` 이 남아 같은 사건을 다시 보낸다. 로컬 대기를 멈춘 뒤 하위 send 가 늦게 성공해도 상태를 임의로 바꾸지 않는다. 상태 변경은 relay 의 한 경계에만 있다 | 원문 | 같은 `eventId` 가 다른 offset 으로 두 번 |
| `RLY-007` | DB 조회 · 상태 변경 TX 와 외부 전송 대기를 분리한다. 무제한 future 병렬 생성 · tick 중첩으로 범위를 넓히지 않는다 | 원문 | — |
| `RLY-008` | 오류 기록에 카드 · 비밀 · 전체 payload 를 남기지 않는다 | 원문 + 보안 | `last_error` 는 예외 종류와 메시지뿐 |

## RCV — 복구와 재개

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `RCV-001` | 확정됐지만 outbox 가 없는 주문은 **저장된 확정 사실에서 기존 `eventId` · `confirmedAt` · 품목 그대로** 한 행을 추가한다. 이미 있으면 내용 일치를 확인하고 덮지 않는다. 원본 사실 부족 · 충돌에는 추측하지 않고 오류를 드러낸다 | 원문 | 같은 바이트의 `PENDING` 한 행 · 두 번째 복구는 행을 늘리지 않음 · 다른 내용은 충돌 |
| `RCV-002` | 복구는 PG 를 다시 부르지 않고, 주문을 다시 확정하거나 예약을 다시 소비하지 않는다. 새 결제를 만들지 않는다 | 원문 | 복구 전후 PG create · 조회 · callback 증가 0 · 주문 · 재고 · 결제 불변 |
| `RCV-003` | 복구 진입점은 관리자 API 다 | 정함(Q-2) | — |
| `RCV-004` | `BLOCKED` 는 미해결 상태로 목록에 남고, 원인을 고친 뒤 **같은 행**을 `PENDING` 으로 되돌려 다시 보낸다. 대상 `eventId` 를 명시한다. 진입점은 로컬 전용이고 공개 관리자 API 로 노출하지 않는다 | 원문 + 정함(Q-1, 진입점 형태) | 재개한 행이 같은 key · payload 로 나가 `PUBLISHED` |
| `RCV-005` | 이전 POINT 확정 주문 전체를 자동 소급하지 않는다. 대상은 명시한 주문뿐이다 | 원문 | — |

## OPS — 운영 확인 · 보존

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `OPS-001` | 상태별 건수 · 가장 오래 기다린 `PENDING` 의 시각 · 시도 수를 SQL 로 확인할 수 있다. 새 모니터링 서버는 두지 않는다 | 원문 | 설계의 운영 절차에 SQL |
| `OPS-002` | `PUBLISHED` 를 소비 완료로 표시하지 않는다. consumer lag · 오류 · 실제 구매 활동을 따로 확인한다 | 원문 | — |
| `OPS-003` | 미발행 · `BLOCKED` 행을 자동 삭제하지 않는다. 보존 · 정리 정책과 소비 쪽 처리 기록(`ACT-002`) 보존의 관계를 적는다 | 원문 | — |

## EXP — 실험

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `EXP-010` | **로컬 원자성.** relay 없이 실제 DB 에서 outbox 기록 실패로 확정 TX 를 rollback 하고, 같은 거래 결과 재적용을 본다 | 원문 | `OBX-007` 의 관찰값 · 재적용에 주문 · 예약 소비 · outbox 각 한 번 |
| `EXP-011` | **commit 뒤 relay 전 중단.** 주문 · outbox `PENDING` 이 있고 구매 활동은 아직 없다. relay 를 새로 만들어 실행하면 저장 행에서 사건을 찾아 보낸다 | 원문 | — |
| `EXP-012` | **broker 확인 뒤 `PUBLISHED` 저장 전 중단.** 실제 send 성공 metadata 를 얻고 상태 저장을 하지 않는다. 같은 DB · Kafka 에서 relay 를 새로 만들어 같은 사건을 다시 보낸다. 테스트가 outbox 상태를 직접 덮어써 복구 조건을 만들지 않는다. 예외를 던진 테스트를 OS 강제 종료라고 부르지 않는다 | 원문 | 같은 `eventId` 가 다른 offset 으로 두 번 · 행은 최종 `PUBLISHED` |
| `EXP-013` | **주문부터 구매 활동까지 한 실행.** 기존 주문 API · PG fixture · 실제 DB · Kafka · 실제 구매 활동 consumer 를 잇고, relay 를 `EXP-012` 처럼 중단 · 재시작한다. broker 의 실제 record 로 두 번을 확인하고 send 호출 수만 세지 않는다. 고유 topic · group · 합성 주문을 쓰고, 테스트 자동 rollback 안에 절차를 넣지 않으며, 자신이 만든 자원만 정리한다 | 원문 + 정함(Q-3, 구성) | 상품 7 × 2 · 상품 8 × 1 한 주문에 주문 · PG 거래 각 1 · outbox 1 행 최종 `PUBLISHED` · 같은 `eventId` record 2 · 처리 기록 1 · 구매 활동 상품별 2 행(수량 2 · 1) · 예약 추가 소비 · 반환 없음 |
| `EXP-014` | **W6 누락 상태 재현 → 같은 실행 안에서 복구.** 새 격리 MySQL · Kafka · PG fixture 를 실행이 소유한다. W6 실행물로 실제 확정 · after-commit 직접 발행 경로를 돌려 A(확정 commit 뒤 send 전 중단)와 B(발행 · 소비 완료)를 만들고, 두 주문의 `orderId` · `eventId` · `confirmedAt` · `items` · PG 거래 식별자와 PG create 누적 C · 외부 거래 수 T 를 기록한다. 복구가 끝날 때까지 DB · broker · PG 를 초기화하지 않는다. 같은 DB 를 W7 로 바꿔 A · B 를 복구(`RCV-001`)하고 relay 와 기존 consumer 를 돌린다. SUCCESS 를 직접 삽입하거나 W7 outbox 행을 삭제해 준비 상태를 만들지 않는다 | 원문 + 정함(Q-4, 수단) | A: 빠졌던 구매 활동 생성. B: 재발행이 와도 활동 · 수량 불변. 두 번째 복구도 불변. 복구 중 PG create = C · 조회 · callback 증가 0 · 외부 거래 = T · 재확정 · 예약 추가 소비 0 |

### 원문 체크리스트와 규칙

| 원문의 체크 | 규칙 |
|---|---|
| 최초 PG 확정 · 예약 소비 · Outbox insert 가 같은 TX 에서 commit/rollback | `OBX-001` · `OBX-007` · `EXP-010` |
| 중복 결과 적용 · 실패 · UNKNOWN 에서 거짓 이벤트 · 추가 결제 없음 | `OBX-006` |
| 기존 주문의 rollback 결과를 신규 주문 삭제와 혼동하지 않음 | `OBX-007` |
| 단일 relay 가 저장된 identity · payload 를 그대로 보내고 broker 확인 후 `PUBLISHED` | `RLY-001` · `RLY-003` |
| commit 후 relay 전 중단 · broker 확인 후 상태 저장 전 중단 | `EXP-011` · `EXP-012` |
| 실제 broker · consumer 에서 재발행돼도 상품별 구매 효과가 늘지 않음 | `EXP-013` |
| 새 격리 DB 에서 W6 누락 · 소비 완료를 재현하고 복구 | `EXP-014` |
| 준비와 복구의 PG 호출 수를 나눠 기록, 복구 중 추가 호출 · 재확정 · 예약 소비 없음 | `RCV-002` · `EXP-014` |
| `PENDING` · `PUBLISHED` · 소비 완료 구분, 실패 · backlog · 재개 방법 | `OPS-001` · `OPS-002` · `RCV-004` |
| 미발행 사실 삭제 없음, 단일 relay · 보존 · 재시도의 한계 | `OPS-003` · `RLY-001` · `RLY-004` |
| 관련 회귀 · lint · ArchUnit, 자신이 만든 테스트 자원만 정리 | `EXP-013` · `EXP-014` |

---

## 물을 질문

| # | 질문 | 무엇이 바뀌나 |
|---|---|---|
| ~~Q-1~~ | ~~`BLOCKED` 재개를 어디서 실행하는가~~ → **기동 인자로 한 번 실행한다.** `outbox.resume.event-ids` 를 주고 앱을 띄우면 그 행들을 `PENDING` 으로 되돌리고, 보내는 일은 기존 relay 가 한다. 원문이 공개 관리자 API 를 막고 로컬 진입점 또는 테스트 도구를 허용한다. 테스트 도구만 두면 운영에서 쓸 길이 없고, 보내기까지 진입점이 직접 하면 relay 와 두 실행 흐름이 겹친다. **뒤집을 조건**: 재개를 앱 재기동 없이 자주 해야 할 때 — 인증된 내부 운영 경로를 따로 정한다 | `RCV-004` |
| ~~Q-2~~ | ~~이미 있는 복구 관리자 API 를 유지하는가~~ → **유지한다.** 원문이 공개 관리자 API 를 막은 것은 `BLOCKED` 재개 진입점이다. 복구는 관리자 인증 뒤에 있고, 주문 행 잠금 · 충돌 거절 · 반복 무해가 테스트로 확인돼 잘못 불러도 사실을 바꾸지 않는다. `EXP-014` 도 이 경로로 복구한다. **뒤집을 조건**: 관리자 API 가 사람이 아닌 외부 시스템에 열리거나, 복구가 저장된 사실 밖의 값을 받아야 할 때 | `RCV-003` |
| ~~Q-3~~ | ~~`EXP-013` 에서 실제 consumer 를 어떻게 붙이는가~~ → **commerce-api 의 JUnit 이 streamer `bootJar` 를 자식 JVM 으로 띄운다.** W6 Q-4 의 뒤집을 조건("W7 이 이 실행을 outbox 경로로 매번 자동으로 돌려야 할 때")에 해당한다. relay 중단은 프로세스 kill 타이밍이 아니라 "보내고 기록하지 않음" 으로 만들어 결정적이다. 한 JVM 에 두 앱을 넣으면 Bean · 엔티티가 섞인다. 제품 runtime 에 commerce-api → commerce-streamer 의존을 만들지 않는다. **뒤집을 조건**: 자식 JVM 기동이 `test` 시간을 크게 늘리거나 불안정할 때 — 별도 테스트 task 로 뺀다 | `EXP-013` |
| ~~Q-4~~ | ~~`EXP-014` 의 W6 경로를 어떻게 돌리는가~~ → **W6 revision(`upstream/seeun0210`) 을 git worktree 로 빌드한 jar 를 프로세스로 띄운다.** W6 의 직접 발행 코드는 지웠고, 원문이 "W6 revision 실행물" 을 허용한다. A 는 W6 Q-6 의 뒤집을 조건대로 `linger.ms` 를 길게 준 W6 api 를 확정 commit 뒤 `kill -9` 해 **흔적 없는 누락**으로 만든다. 직접 발행 코드를 테스트 소스에 보존하면 실제 W6 실행물이 아니다. **뒤집을 조건**: W6 revision 을 빌드할 수 없게 될 때 | `EXP-014` |

## 범위 밖

별도 결제수단 · 새 주문 도메인 · 범용 saga · CDC 플랫폼 · 다중 relay(원문).
여러 relay 의 소유권 · 순서 · CDC 로의 전환은 `docs/week7/beyond-outbox.md` · `docs/week7/cdc-experiment.md` 에 근거만 모은다.