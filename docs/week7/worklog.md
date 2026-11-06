# 7주차 작업 경과 (W7 Outbox)

> 이 문서는 **어떻게 진행했나**만 적는다. 설계는 `plan.md`, 실행 결과(사건 기록)는 `incident-w7.md`, 운영 방법은 `operations.md`.

## 단계별 경과

| 단계 | 날짜 | 한 일 | 체크리스트 | 커밋 (volume-7) |
|---|---|---|---|---|
| 준비 | 11-05 | UNKNOWN 동안 확정·발행·PG 재생성 없음 테스트 4개 | C-2 | `97296aa4` |
| 0 테이블 재설계 | 11-05 | `order_outbox` 엔티티·포트·어댑터·`schema.sql`, 차례가 된 행 조회 | — | `7fe90a01` `96ec3860` |
| 핵심 1 상태 전이 | 11-05 | `OrderOutbox` Red 20 → Green. 변이 6종 잡음 | — | `01bed565` `95420062` `283a2225` |
| 핵심 3 backoff | 11-06 | Red 6 → Green. 변이 3종 잡음 | — | `e5d7da2f` `6860f12a` |
| 1 relay 한 회차 | 11-06 | TX1 SENDING → send → ack 대기 → TX2 결과. Red 11 → Green. 변이 3종 잡음 | C-4 | `72369538` `4319cefc` `879b4e50` `fb4fac90` |
| 2 직접 발행 제거 | 11-06 | 확정 TX는 `order_outbox` 행만. W6 listener·`event_outbox` 삭제, W6 테스트 확인 대상 전환 | C-1 | `b621d2d5` |
| 4 겹침 방지·재개 | 11-06 | ShedLock(작업 이름 잠금), actuator 재개 진입점. Red → Green | C-4 | `0da390c3` `77b61cad` |
| 5 중단 실험 | 11-06 | ① commit 뒤 relay 전 ② ack 뒤 저장 전 — 실제 broker | C-5 | `0591f9ea` |
| 6 누락 재현·복구 | 11-06 | `run.sh` 1막(W6 revision) → 2막(같은 DB, W7 복구) | C-7 · C-8 | `606a195e` |
| 7 E2E | 11-06 | `run.sh` 3막 — ack 뒤 저장 전 중단(JVM 1) → relay 재시작(JVM 2) | C-6 | `bd06dae4` |
| 8 운영 문서 | 11-06 | `operations.md` — 관찰 SQL·로그·재개·보존·한계 | C-9 · C-10 | |
| 9 회귀 | 11-06 | commerce-api 532/0/0 · streamer 39/0/0 · lint 통과 · 남은 자원 0. 간헐 실패 테스트 원인 수정 | C-11 | |

(3단계 재시도·BLOCKED는 핵심 3과 relay 한 회차에 함께 들어갔다.)

## 실패와 수정

| 언제 | 무엇이 실패했나 | 원인 | 수정 |
|---|---|---|---|
| 핵심 1 Red | Checkstyle | 쓰지 않는 import | 지우고 Red 커밋을 고침 |
| 1단계 변이 확인 | 변이 M1이 "안 잡힘"으로 보임 | 변이 코드가 컴파일되지 않아 옛 결과를 읽음 | 변이를 다시 쓰고 옛 결과를 지운 뒤 재확인 → 잡힘 |
| 1단계 | 앱 시작 실패 | `application.yml`에 최상위 `spring:` 키가 둘 | 기존 블록 안으로 옮김 |
| 2단계 | S-13 ③ 단언을 약하게 바꿨던 것 (리뷰에서 발견) | relay 실패 원인 문자열에 가장 안쪽 원인이 없었음 | W6 강도의 단언으로 되돌리고 relay가 가장 안쪽 원인을 덧붙이게 함 |
| 4단계 | scheduler 테스트에서 relay 호출 0회 | shedlock 행을 DELETE하면 ShedLock이 UPDATE만 시도 | 행을 지우지 않고 `lock_until`을 과거로 UPDATE (운영 문서 주의점) |
| 6단계 1차 실행 `c7-20261106-022623` | 2막 실패 | 복구 도구의 JSON 비교 — LongNode vs IntNode | "직렬화 → 다시 읽기"로 비교. 복구 TX는 rollback돼 DB에 쓰지 않음 |
| 6단계 2막 재실행 | 결과 기록 SQL 실패 | MySQL 예약어 `partition` | 별칭을 바꿈. 첫 복구가 이미 일어나 그 환경을 버리고 처음부터 재실행 |
| 6단계 | 성공 `c7-20261106-022901` | — | 7단계에서 1~3막을 한 번에 다시 돌린 `c7-20261106-024330`을 최종 근거로 삼고 022901 결과는 지움 |
| 8단계 | 관찰 SQL O-6이 "Illegal mix of collations"로 실패 | `schema.sql`을 loopers_test(utf8mb4_general_ci)에서 옮겨 collation이 고정됨. 로컬 loopers 기본은 utf8mb4_0900_ai_ci라 consumer 테이블과 JOIN이 안 됨 | `schema.sql`에서 문자셋·collation을 빼 DB 기본값을 따르게 함. verify.sh는 서버 기본값(로컬과 같음)으로 실행 |
| 8단계 | relay 주기를 실제로 잰 적이 없음 (학습자 질문) | 기존 scheduler 테스트는 자동 tick을 1시간으로 미루고 tick()을 직접 불렀음 | `OrderOutboxRelayTickIntegrationTest` 추가 — 실제 scheduler로 1.02초 간격, 1.5초 회차면 2.53초. fixedRate·tick 3초 변이 모두 실패 |
| 9단계 | `KafkaClusterSettingsTest` 간헐 실패 (W6부터 있던 테스트. 고치기 전 전체 실행 5번 중 3번 실패) | 새로 만든 probe topic의 metadata가 broker 3대에 퍼지기 전에 앱 producer(max.block.ms 1s)가 첫 send → `UNKNOWN_TOPIC_OR_PARTITION` → 1초 timeout | 첫 시도 "leader·ISR 3을 기다림"은 **효과 없음**(ISR은 생성 때부터 3) — 다시 실패. 앱 producer의 `partitionsFor`가 성공할 때까지 기다리도록 고침. 다음 전체 실행에서 같은 metadata 오류가 1번 났지만 대기가 받아 통과. 단언·앱 설정은 그대로 |

## 작업 방식

- 순서 ✅: 0 테이블 재설계 → 1 relay + 결과 기록 주체 → 2 after-commit 직접 발행 제거 → 3 재시도·BLOCKED → 4 relay 실행 방식(겹침 방지) → 5 중단 실험 두 개 → 6 누락 재현·복구·PG 호출 수 → 7 E2E → 8 운영(관찰·재개 진입점·보존·한계 문서) → 9 회귀 → 기술 글 → PR.
  - relay 생성과 "결과 기록 주체"를 한 단계로 합쳤다 — 기록 주체가 relay의 모양 자체라서 나누면 두 번 짠다.
  - 직접 발행 제거를 relay 뒤로 미뤘다 — 먼저 지우면 relay 전까지 발행이 0이 되고 W6 테스트가 깨진 채 남는다.
- 역할 ✅ (11-05 변경): 핵심 로직도 Claude가 `volume-7-claude`에서 TDD로 초안을 쓰고, 학습자가 리뷰한다(테스트는 엄격히). 처음 C안은 학습자가 직접 짜는 것이었다. 테이블·설정·어댑터·테스트 초안·문서는 Claude가 `volume-7-claude`(`../loop-pack-be-l2-vol5-java-claude`, push 안 함)에서 쓰고 학습자가 cherry-pick한다.
- 핵심 로직 (처음엔 학습자 담당 → 11-05부터 Claude 초안·학습자 리뷰. 테스트는 엄격히 본다):
  1. `OrderOutbox` 도메인의 상태 전이 — 어떤 상태에서 어디로만 가나, 거절 뒤 기존 값 유지
  2. relay 한 회차 — 고르기 → send → future 대기 → 결과(성공·명시적 실패·timeout·저장 실패)별 기록
  3. backoff·BLOCKED 판단 — 시간을 고정한 리터럴 기대값
  4. 겹침 방지 — tick과 수동 실행이 동시에 들어올 때 send는 한 번

## 결정 기록 (날짜순이 아니라 적은 순서)

- 11-03: 문서 시작. 체크리스트·작업 방식·전제·D-0(테이블 분리, 컬럼, topic은 설정)·보존 정책·한계를 적었다. C-2에 UNKNOWN 테스트 4개 추가.
- 11-03: D-0에 SENDING·lease(`next_attempt_at`) 추가, D-3(backoff + jitter, 3번 실패 → BLOCKED, 감지 수단), D-1 한 회차 정의.
- 11-04: D-1 "실패하면 회차를 멈춘다"와 비교표, 순서 구분(다른 주문 vs 같은 주문)·한정 수량 공정성 답. 한계에 같은 주문 순서 세 겹·late-arriving data·DLT 재처리 TODO·broker 중복·테이블 증가·CDC 비교 추가. partition·offset 남김 ✅, 인덱스 🔧(D-0으로 옮김), backoff 숫자 ✅, lease 책임 나눔 🔧, BLOCKED = 보내는 쪽 DLT·재개 시 attempt 0·잘못된 전이는 예외 ✅, 핵심 1 기대값 표. 0단계 구현: order_outbox 엔티티 뼈대·포트·어댑터·schema.sql, 조회 테스트 4개.
- 11-06: 7단계 C-6 — 같은 run.sh 3막. relay 재시작은 실제 새 JVM(E-2)이고 lease 경과도 실제 시각. consumer 수신은 streamer 로그(저장/no-op 줄)로, Kafka 기록은 broker를 직접 읽어 본다(send 호출 수만 세지 않음 — 과제).
- 11-06: 6단계 C-7·C-8 — 복구는 테스트 도구로만(학습자: 운영 코드는 W7 기준, W6 데이터용 코드를 제품에 넣지 않음). 스크립트가 격리 MySQL·Kafka 3대·pg-sim·W6 streamer를 소유, 1막 W6 revision → 2막 같은 DB에서 W7 복구. 2막 테스트는 `@Tag("isolated")`로 보통 test에서 빼고 `isolatedRun` 작업으로만 실행(처음엔 c7 태그·c7Recovery였고 7단계에서 이름을 바꿈). 첫 실행은 복구 도구의 JSON 비교 버그·결과 SQL 예약어로 실패해 처음부터 다시 실행(incident-w7.md).
- 11-06: 5단계 C-5 — 실제 kill 대신 W6 E-4와 같은 통제된 중단 지점(Claude 기본값 🔧): ① relay를 부르지 않음 ② PUBLISHED 저장 TX를 한 번 실패시킴. 실제 확정 경로·broker로 확인.
- 11-06: 4단계 — 재개 진입점은 관리 포트 actuator `POST /actuator/outbox/{eventId}`(local·test만 노출, 학습자 A안 "api"). 겹침 방지는 ShedLock(학습자, 확장성) — outbox 행이 아니라 작업 이름 `order-outbox-relay` 한 행의 잠금, DB 시각 기준, lockAtMostFor 5분, 못 얻으면 건너뜀. 재개는 행만 PENDING으로 돌리고 보내는 것은 scheduler(1초 fixedDelay) 한 길. 주의(운영 문서에): 앱이 도는 중 shedlock 행을 지우면 ShedLock이 UPDATE만 시도해 재시작 전까지 잠금을 못 얻는다 — 강제 해제는 lock_until을 과거로 UPDATE.
- 11-06: 2단계 연결 전환 — 확정 TX는 order_outbox에 PENDING 행만 남기고, W6 after-commit 직접 발행(listener·콜백 기록·결과 스레드풀)과 event_outbox를 지웠다. W6 미발행 행은 옮기지 않고 버린다(학습자, "기존 데이터 날려요"). C-7은 새 격리 DB에 누락 상태를 직접 만들어 확인한다(6단계). W6 테스트는 확인 테이블·발행 시점만 바꿨다(A안, S-13은 W7 버전으로). relay 실패 원인 문자열에 가장 안쪽 원인을 덧붙였다 — S-13의 W6 단언("not present in metadata")을 약하게 하지 않으려고.
- 11-06: relay TX 나눔 ✅(TX1 SENDING / send는 TX 밖 / TX2 결과). 핵심 3 backoff(Red 6 → Green, 변이 3종 잡음), 핵심 2 relay 한 회차(Red 11 → Green, 변이 3종 잡음). 설정 batch-size 200·send-wait 35s·delivery.timeout.ms 30s, Clock·backoff 빈. 전체 521/0/0.
- 11-05: 핵심 1 구현(Claude 초안 → 학습자 리뷰로 역할 변경). 예외 CONFLICT·도메인 기한 확인 🔧. N=200·tick 1초(🔧), 직렬 처리 ✅, 대기(`delivery.timeout.ms` 30s · 대기 35s · lease 1분) ✅/🔧.
