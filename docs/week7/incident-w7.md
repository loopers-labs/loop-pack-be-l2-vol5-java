# 7주차 사건 기록 — W6 누락의 재현과 W7 복구 (C-7·C-8)

> 실행: `bash docs/week7/c7/run.sh` (2026-11-06 02:29 KST, 실행 ID `c7-20261106-022901`). 원본 결과: `docs/week7/c7/results/c7-20261106-022901/{facts.json, result.json}`.
> W6 기록(`docs/week6/incident-w6.md` E-4)과는 별개인, **이번 재현 실행의 사건**이다. 역사적 원본 행의 복원이 아니다.

## 환경 — 스크립트가 수명주기를 소유

| 자원 | 내용 |
|---|---|
| MySQL | 새 컨테이너 `mysql:8.0`, DB `loopers_c7` (포트 33307) |
| Kafka | 새 broker 3대 `apache/kafka:3.8.1` (KRaft quorum 3, RF 3, min.insync 2). `order-events`는 앱보다 먼저 만듦 |
| PG | `docs/week6/k6/pg-sim.py` (auto: 1초 뒤 SUCCESS + callback). 1막·2막 내내 같은 프로세스 — 호출 수를 이어서 셈 |
| consumer | W6 revision의 commerce-streamer jar. 1막부터 2막 끝까지 계속 실행 (= 기존 consumer) |
| 1막 코드 | W6 revision `388bc941` (after-commit 직접 발행이 있는 마지막 revision) worktree + `docs/week7/c7/W6GapPreparationRun.java` |
| 2막 코드 | W7 (`volume-7-claude`) + 테스트 전용 복구 도구 `src/test/.../outbox/c7/W6GapRecovery` (운영 코드에는 W6용 코드를 넣지 않음 — 학습자 결정 11-06) |

1막과 2막 사이에 DB·broker·PG를 초기화하지 않았다. 모든 확인 뒤에 스크립트가 자기가 만든 컨테이너·네트워크·worktree·프로세스만 지웠다.

## 1막 — W6 코드로 누락 상태 만들기

주문 API(MockMvc) → 결제 요청(202) → pg-sim 생성 → 1초 뒤 SUCCESS callback → 결과 적용 경계 → 확정. A는 확정 commit 뒤 after-commit publish를 한 번 건너뛰었다(W6 S-13과 같은 통제된 지점).

| 사례 | orderId | providerOrderId | transactionKey | eventId | confirmedAt (DB, UTC) | items | 주문 | 예약 | W6 발행 기록 | 구매 활동 |
|---|---|---|---|---|---|---|---|---|---|---|
| A send 직전 중단 | 1 | LP-ORD-0000000001 | 20261106:TR:6675d20 | bd8e55f5-871a-41af-86e3-b6a316b0e83e | 2026-11-05T17:29:51.588598 | 상품 1×2, 상품 2×1 | CONFIRMED | CONSUMED ×2 | published_at **null** | **0행** |
| B 발행·소비 완료 | 2 | LP-ORD-0000000002 | 20261106:TR:f4aeaf3 | 2389854c-81c5-43b2-b3b6-65d7411369e8 | 2026-11-05T17:29:52.817626 | 상품 1×1 | CONFIRMED | CONSUMED ×1 | published_at 17:29:53.436687 | 1행 (상품 1×1) |

- W7 Outbox(`order_outbox`)는 이 시점에 테이블 자체가 없었다(information_schema로 확인).
- 소비 완료 기록(`consumed_order_confirmation`): B의 eventId 1건.

## 2막 — 같은 DB에서 W7으로 복구

1. 같은 DB에 W7 테이블 추가 (`docs/week7/schema.sql`에서 `DROP TABLE event_outbox`만 빼고 실행 — A의 eventId가 남은 곳이라 지우지 않음).
2. 복구 도구(테스트 전용)가 대상 두 주문마다: W6 발행 기록의 eventId·payload를 읽고 → 주문이 CONFIRMED이고 저장된 확정 시각·품목으로 다시 만든 payload가 W6 payload와 같은지 확인 → `order_outbox`에 같은 eventId가 없을 때만 **W6 payload 원문 그대로** PENDING 행 추가.
3. relay 한 회차 → 기존 streamer가 소비.
4. 같은 대상을 한 번 더 복구 → relay 한 회차.

| 확인 | 결과 |
|---|---|
| 첫 복구 | A `CREATED` · B `CREATED`, 행의 payload = W6 payload 원문 |
| relay 1회차 | 고름 2 · PUBLISHED 2 (A partition 0 · offset 0, B partition 2 · offset 1) |
| **A 구매 활동** | 0행 → **상품 1×2, 상품 2×1** (예약 품목과 같음). 소비 기록에 A의 원래 eventId |
| **B 구매 활동** | 상품 1×1 → **상품 1×1 그대로** (재발행이 왔지만 같은 eventId라 no-op) |
| 두 번째 복구 | A `ALREADY_PRESENT` · B `ALREADY_PRESENT`, Outbox 2행 그대로 |
| relay 2회차 | 고름 0 (둘 다 PUBLISHED) — 구매 활동·소비 기록 변화 없음 |

## C-8 — 준비와 복구의 PG 호출 수

| 구간 | 생성 | 조회 | callback |
|---|---|---|---|
| 준비 전 | 0 | 0 | 0 |
| 준비 뒤 (1막: 주문 2건 확정) | 2 | 2 | 2 |
| 복구 전 | 2 | 2 | 2 |
| 복구 뒤 (2막: 복구 2회 + relay 2회) | 2 | 2 | 2 |

**복구 중 PG 추가 호출 0.** 주문 상태·확정 시각, 결제 상태·거래 키, 예약 상태·수량도 복구 전과 같다(재확정·예약 추가 소비 없음 — `W6GapRecoveryRun` 단언).

## 처음 실행에서 겪은 것 (같은 날, 기록으로 남김)

- 1차 실행(`c7-20261106-022623`): 1막 성공, 2막에서 **복구 도구의 비교 버그**로 실패 — `valueToTree`(Long → LongNode)와 `readTree`(작은 수 → IntNode)가 같은 JSON을 다르다고 봤다. 복구 TX가 rollback돼 DB에는 쓰지 않았다. 비교를 "문자열로 직렬화 → 다시 읽기"로 고쳤다.
- 같은 환경에서 2막만 다시 실행: 복구·relay·두 번째 복구는 끝까지 됐고(A 활동 생김, B 그대로) **결과 기록 SQL**이 MySQL 예약어(`partition`)로 실패했다. 첫 복구가 이미 일어나 "첫 복구"를 다시 볼 수 없으므로 그 환경을 정리하고 처음부터 다시 실행했다 → 위 기록(`c7-20261106-022901`)이다.

## 이 실험이 증명하지 않은 것

- 원본 W6 DB의 복원이 아니다. W6 임시 DB는 남아 있지 않아, 같은 경로로 누락 상태를 새로 만들었다.
- A의 "send 직전 중단"은 OS 강제 종료가 아니라 publish를 한 번 건너뛰는 통제된 지점이다(W6 E-4와 같음).
- 대상은 두 주문뿐이다. 이전 주문 전체를 자동 소급하지 않는다.
