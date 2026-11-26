# 결제 응답 유실 사고 검증 기록

> 계약과 규칙은 [결제 응답 유실 사고 대응](payment-response-lost.md), 운영 절차는 [런북](../../runbook/payment-response-lost.md)을 따른다. 이 문서는 제출 실행의 결과, 실행한 검증, 기능별로 확인한 것과 확인하지 않은 위험을 모은다.

## 제출 실행

2026-11-26에 `./gradlew :apps:commerce-api:incidentTest --rerun`으로 실행했다. 원본 증거는 `apps/commerce-api/build/incident-evidence/20261126T140046Z-4be14d/`에 있고 Git에 올리지 않는다. 아래 값은 그 폴더의 `manifest.json`, `orders.json`, 단계 JSON, `diagnosis.json`, `logs/commerce-api.jsonl`에서 옮겼다.

| 항목 | 값 |
|---|---|
| runId | `20261126T140046Z-4be14d`, 결과 PASSED |
| 실제 시각 | 2026-11-26T14:00:46Z ~ 14:01:55Z |
| 업무 시계 | baseline 2026-11-11T03:00:00Z부터 단계마다 1시간씩 |
| 사용자 | A=1, B=2, C=3. 상품 1 |
| 정리 | 끝날 때 남은 응답 유실 주입 0건. 자식 프로세스는 streamer 1개(PID 38102)와 batch Job 2회였고, 실행 뒤 남은 streamer·batch 프로세스와 테스트 PG 컨테이너는 없었다 |

### 단계별 관찰

분모와 네 분류는 [단계와 기대값](payment-response-lost.md#단계와-기대값)의 정의를 따른다. 누적 PG 거래와 create는 이 실행이 만든 주문 20건의 주문별 집계를 더한 값이다.

**결제 결과**

| 단계 | 분모 | 최종 성공 | 최종 실패 | 처리 중 | `UNKNOWN` | PG `SUCCESS` 거래 | 누적 PG 거래 | 누적 create |
|---|---|---|---|---|---|---|---|---|
| baseline | 10 | 10 | 0 | 0 | 0 | 10 | 10 | 10 |
| fault | 10 | 7 | 0 | 0 | 3 | 10 | 20 | 20 |
| recovery | 10 | 10 | 0 | 0 | 0 | 10 | 20 | 20 |

**재고와 후속 결과**

| 단계 | 가용 재고 | 예약 수량 | 지표 `UNKNOWN` | 지표 기한 초과 | 구매 활동 | 랭킹 회차 | health | 관찰 시각 |
|---|---|---|---|---|---|---|---|---|
| baseline | 20 | 0 | 0 | 0 | 10 | - | UP | 14:01:12Z |
| fault | 10 | 3 | 3 | 3 | 17 | 1 | UP | 14:01:34Z |
| recovery | 10 | 0 | 0 | 0 | 20 | 1, 2 | UP | 14:01:50Z |

표에 없는 관찰은 다음과 같다.

- health는 세 단계 모두 루트가 `UP`이다. readiness가 보는 것은 `db`, `redis`, `diskSpace`, `ping`, `ssl`, `livenessState`, `readinessState`이고 Kafka와 PG는 없다.
- 랭킹 점수는 회차 1이 17, 회차 2가 20이다. 회차 2를 만든 뒤에도 회차 1은 17이다.
- 다른 사용자 C는 장애 주문의 상세와 reconcile 모두 `404`를 받았고, 그 주문의 PG 조회 수는 그대로였다.
- A와 B의 포인트 100,000과 쿠폰 1장은 fault와 recovery에서 같다.
- 테스트 안의 검사는 테스트 PG 로그를 포함해 manifest를 뺀 증거 전체를 봤다. 실행 뒤 manifest까지 포함한 폴더 전체를 검색해서도 합성 카드 번호가 없음을 확인했다.
- 수렴 확인에서 늦은 성공 알림 3건과 fault 10건의 reconcile이 모두 `200`이었고, PG create 수와 조회 수는 늘지 않았다.
- Commerce의 PG 조회 관찰은 20줄이다. baseline 10건, fault 정상 주문 7건, recovery 3건이다.

### 가설 판정

관찰은 `diagnosis.json`에서 옮겼고, 판정은 설계 문서의 [판정 규칙](payment-response-lost.md#판정-규칙)을 그 관찰에 적용해 적었다. test 프로필의 PG 응답 timeout은 1,000ms다. 장애 3건 모두 생성 관찰이 그보다 오래 걸렸고 같은 주문·금액의 `SUCCESS` 거래가 1건이라 응답 유실을 지지한다. PG 거래가 `SUCCESS`라 처리 지연은 기각이고, 조회 관찰 `FOUND`가 없어 내부 반영 실패도 기각이다.

| attemptId | orderId | 생성 관찰 | 경과 ms | PG 거래 | 조회 관찰 | rollback 기록 | 시도 상태 | 처리 지연 | 응답 유실 | 내부 반영 실패 |
|---|---|---|---|---|---|---|---|---|---|---|
| 13 | 13 | UNKNOWN | 1021 | SUCCESS 7000 | 0건 | 0건 | UNKNOWN | 기각 | 지지 | 기각 |
| 16 | 16 | UNKNOWN | 1006 | SUCCESS 7000 | 0건 | 0건 | UNKNOWN | 기각 | 지지 | 기각 |
| 19 | 19 | UNKNOWN | 1005 | SUCCESS 7000 | 0건 | 0건 | UNKNOWN | 기각 | 지지 | 기각 |

판정에서 부족한 증거는 다음과 같다. 판정 규칙의 지지·기각 칸에는 맞지만, 이 증거가 없어서 다른 설명을 완전히 지우지는 못한다.

- PG가 늦춘 생성 응답을 실제로 보냈는지는 Commerce 쪽에서 볼 수 없다. 응답을 잃었다는 판단은 걸린 시간이 응답 timeout 이상이라는 관찰에서 나온다.
- Commerce가 timeout을 만난 순간의 PG 거래 상태는 관찰하지 않았다. PG 거래는 진단 때 읽은 `SUCCESS`만 있다.
- 내부 반영 실패를 기각한 근거는 조회 관찰이 없다는 것이다. 로그가 시도 생성부터 진단까지를 모두 담고 있다는 전제에 기댄다. 사고 실험은 같은 테스트 안에서 로그를 모으므로 이 전제가 맞지만, 운영에서는 로그 보관 기간을 따로 확인해야 한다.
- 분산 추적이 없어 요청이 어느 구간에서 기다렸는지는 로그의 걸린 시간 말고는 볼 수 없다.

### 장애 집합

baseline 집합은 A의 orderId 1~10이다. 거래 키까지 담은 전체 목록은 증거의 `orders.json`에 있다.

| orderId | attemptId | PG 주문 식별자 | 거래 키 | 응답 유실 주입 |
|---|---|---|---|---|
| 11 | 11 | `LP-ORD-0000000011-6f58ff0d` | `TEST-TX-991da105-6c6b-4d6e-9958-c8555f83826c` |  |
| 12 | 12 | `LP-ORD-0000000012-decd8304` | `TEST-TX-fc16c2ee-bd11-40b2-b93c-76c75b60e092` |  |
| 13 | 13 | `LP-ORD-0000000013-fa7cf64f` | `TEST-TX-fc53204d-09d6-4b4c-a61d-e4e26d9bdd9c` | 예 |
| 14 | 14 | `LP-ORD-0000000014-a90f7c53` | `TEST-TX-00c07c4f-3d5e-47bf-a360-c674cdea0e8a` |  |
| 15 | 15 | `LP-ORD-0000000015-1269ce46` | `TEST-TX-8ef505de-be0d-45c9-91d3-59e86bc862fd` |  |
| 16 | 16 | `LP-ORD-0000000016-7a59fa88` | `TEST-TX-75278565-ea34-4532-aaa4-104b51d25162` | 예 |
| 17 | 17 | `LP-ORD-0000000017-5eb5478b` | `TEST-TX-7e23e7df-2805-42ed-b4b7-1ad1a66dc833` |  |
| 18 | 18 | `LP-ORD-0000000018-41337ea1` | `TEST-TX-ea828308-438b-4438-8d08-4cf134425e99` |  |
| 19 | 19 | `LP-ORD-0000000019-c71e62e8` | `TEST-TX-165e0597-c4d0-43c2-8909-0a0073758ae4` | 예 |
| 20 | 20 | `LP-ORD-0000000020-f4a5f480` | `TEST-TX-442c7e83-cfe5-49ad-acd4-d48c68428413` |  |

### fault 증거 hash

| 파일 | sha256 |
|---|---|
| `raw/fault-injected-orders.json` | `c5bb00142a58fa407e54c13d9f0bdea2e60bd99e8f5213c347a68af70a3f94a3` |
| `raw/fault-injection-state.json` | `c98a5ae6564a8dde21f84728da3279119622f200029580e47cae08c68412a839` |
| `hypotheses.json` | `1a7a8c89b8446df42915a853b4af43ffa28f0ee3d0727920612482ed7f978d8b` |
| `diagnosis.json` | `a58faf648bae5f1b690df5dfb1dc9ad255e870e0af7311bcbb39e188e4c223cc` |
| `raw/fault-health.json` | `b9613659d8f4412c778e34293d5b865fb188c5814379ee3bfb8c819432e70020` |
| `raw/fault-health-readiness.json` | `9998b242cc317d22bcc643c1d8c9f497fba11379e5ea7c0f9e8e4538bb095535` |
| `raw/fault-prometheus.txt` | `df3b112f030791ba7cde72b5217055ecb9737232bae7ff9a21118bd5d91970d2` |
| `phase-fault.json` | `57b96379e6ff44f9bca9b6c71203f758904335e413ba6584f8131abe3cc86523` |

위 hash는 fault 파일을 쓴 즉시 `hashes.sha256`에 남긴 값이다. 테스트 마지막에 다시 계산해 같은지 확인했고, 실행 뒤 `shasum -a 256 -c hashes.sha256`으로도 기록된 파일 24개가 모두 일치했다. hash는 기록이 실행 뒤에 바뀌지 않았음만 보인다. 기록한 값이 맞는지는 같은 식별자, 상태, 효과 수를 검사하는 테스트 assertion이 정한다.

## 실행 결과

| 확인 | 명령 | 결과 |
|---|---|---|
| 네 모듈 전체 | `./gradlew :apps:test-pg:check :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --rerun-tasks --continue` | 성공. test-pg 113건, commerce-api 1,096건과 사고 실험 1건, streamer 87건, batch 102건. skip 0 |
| commerce-api 다시 | `./gradlew :apps:commerce-api:check --rerun-tasks` | 성공. 1,097건과 사고 실험 1건. skip 0 |
| 제출 실행 | `./gradlew :apps:commerce-api:incidentTest --rerun` | 사고 실험 1건 통과 |
| 런북 SQL | `docker/mysql/init.sql`로 만든 임시 MySQL에 시도 8건과 주문·Outbox·구매 활동 행을 넣고, 런북의 SQL 블록 6개를 문서에서 그대로 읽어 실행 | 대상 목록은 기한을 넘긴 미확인·충돌·생성 후 25시간 건만, 장기 미확인 수는 2, 재시작 뒤 `REQUESTED` 수는 1, 복구 확인 3개는 대상 주문의 행을 돌려줬다. 같은 데이터에서 기한 초과 지표 SQL은 1로, 대상 목록 중 충돌과 24시간 초과를 뺀 수와 같았다 |
| 경보 쿼리 | `python3 docker/grafana/tests/verify_outbox_alerts.py` | 성공. Outbox 두 경보와 결제 기한 초과 경보의 promtool 테스트 통과 |
| Grafana 기동 | `docker/monitoring-compose.yml`을 별도 프로젝트 이름과 포트로 띄우고 Grafana API로 조회 | Grafana 12.3.3이 결제 경보 규칙, 결제 Slack 연락처, Commerce 폴더의 결제 대시보드(세 칸)를 오류 없이 불러왔다. 지표가 없어 경보는 `inactive`, 상태는 `ok`였다. Slack은 닿지 않는 주소로 덮어 보내지 않았다. 웹훅을 비운 채 다시 띄우면 Outbox·결제 규칙과 Slack 연락처가 빠지고 대시보드만 오류 없이 올라왔다 |
| Grafana 시연 | 시연 전용 MySQL·Redis·Kafka·테스트 PG·commerce-api(local)·Prometheus·Grafana를 별도 이름과 포트로 띄우고, 테스트 PG를 `docker pause`한 채 결제 1건 요청. 두 번 했고, 두 번째는 실제 Slack 웹훅을 썼다 | 결제 요청이 3초 timeout으로 `UNKNOWN`, health `UP`. 기한 초과 지표는 5분 10초 뒤 1, 경보는 6분 31초 뒤 Firing이었고 Slack에 [발생]이 왔다. 런북대로 reconcile하자 8초 뒤 지표가 0, 23초 뒤 경보가 해제됐고, 1분 안에 Slack에 [해결]이 왔다. 주문은 `CONFIRMED`, Outbox는 `PUBLISHED`였다 |

- `check`는 commerce-api, streamer, test-pg에서 checkstyle(lint)과 테스트를, batch에서 테스트를 돌린다. commerce-api 테스트에는 계층 의존을 검사하는 `ArchitectureTest`(ArchUnit)가 들어 있다.
- 사고 실험은 `incidentTest`로 돌고 API의 `check`에 포함된다. 구성은 설계 문서의 [실행 구성](payment-response-lost.md#실행-구성)에 있다. 과제 원문의 예시처럼 `test --tests '*CheckoutIncidentTest'`로 실행하면 테스트를 찾지 못한다.
- 네 모듈 전체 실행 뒤에 지표 갱신 스케줄을 지표 클래스로 합치고 사고 실험의 확인 항목을 늘려, commerce-api만 다시 돌렸다. test-pg, streamer, batch는 이번에 바꾸지 않았다.
- 경계 로그 테스트는 생성 관찰과 조회 관찰 로그 호출을 각각 빼면 해당 테스트만 실패하는 것을 확인했다. 결제 경보의 promtool 테스트는 경보 임계값을 0에서 5로 바꾸면 실패하는 것을 확인했다. 지표 테스트는 상태별 값을 기한 초과 집계보다 먼저 반영하거나 기한 초과 값이 0이면 갱신하지 않게 바꾸면, 직전 값 유지 테스트와 0 복귀 테스트가 각각 실패하는 것을 확인했다.
- 코드 리뷰를 반영한 뒤 처음 돌린 commerce-api `check`에서는 쿠폰 Redis 테스트 3건(`RedisCouponIssueQueueRecordIntegrationTest` 2건, `CouponIssueV1ApiE2ETest` 1건)이 Redis 응답 시간 초과로 실패했다. 그때 다른 프로젝트 컨테이너로 Docker VM 부하가 높았다. 두 클래스만 다시 돌려 19건이 통과했고, 이어서 전체 `check`를 다시 돌려 통과했다. 그 뒤 지표 클래스가 미종결 상태를 도메인 정의에서 가져오도록 바꾸고, 기한 초과 지표·경보·대시보드를 더해 commerce-api `check`를 다시 돌려 통과했다.
- 전체 리뷰를 반영한 뒤 commerce-api `check`가 두 번 Redis 응답 시간 초과로 실패했다. 실패한 테스트는 상품 캐시와 쿠폰 발급으로 매번 달랐다. 그때 맥의 부하 평균이 20을 넘고 스왑이 거의 찼으며, Docker 안의 프로세스가 최대 217ms 멈췄다. 남아 있던 headless 브라우저를 정리해 멈춤을 3ms로 낮춘 뒤 다시 돌려 통과했다. 위 표의 commerce-api 결과는 이 마지막 실행이다.
- 두 시연 모두 PG를 되살렸지만 멈춘 동안의 생성 요청은 PG에 남지 않아 거래가 0건이었다. 런북의 중단 조건에 해당한다. 첫 시연에서는 런북 순서와 달리 0건을 확인하기 전에 reconcile을 한 번 불렀고, 202로 시도를 `UNKNOWN` 그대로 두었다. 그 뒤로는 PG가 거래를 늦게 등록한 상황을 테스트 PG 결제 API로 만들고, 런북의 판정과 조치 전제를 확인한 뒤 reconcile했다.
- Slack 메시지는 설정한 보내는 이름 대신 Slack 앱 이름으로 보였다. Slack 앱 웹훅은 보내는 이름 설정을 쓰지 않는다. 첫 시연은 닿지 않는 웹훅 주소를 써서 발생 알림이 실패했고, 해결 알림은 보내지 않았다.

## 기능별 확인

| 기능 | 이번에 확인한 증거 | 확인하지 않은 위험 |
|---|---|---|
| 상품·브랜드·좋아요·관리자 | 바꾸지 않았다. `check` 회귀: `Brand*`, `BrandDeletion*`, `Like*`, `ProductListCache*`, `AdminBoundaryMockMvcTest` | 사고 흐름과 같이 돌리지 않았다 |
| 포인트·주문·PG | 사고 실험: 주문별 create 1, 미확인 동안 예약 유지와 확정 때 추가 차감 없음, 포인트 불변. 회귀: `PaymentResultServiceIntegrationTest`, `OrderPaymentReconcileV1ApiE2ETest`, `PaymentCallback*`, `Point*` | PG callback으로 결과를 처음 반영하는 경로와 주기 조회 자동 회복은 기존 테스트로만 확인했다 |
| 이벤트·Outbox | 사고 실험: 실제 relay, Kafka, streamer로 주문마다 Outbox 1행과 구매 활동 1행. 회귀: `OrderConfirmedOutbox*`, `PurchaseActivityRedeliveryIntegrationTest` | 발행 gap 복구는 실험에서 일어나지 않았다 |
| 쿠폰 | 사고 실험: 발급한 쿠폰의 본인 목록이 fault와 recovery에서 같다. 회귀: `CouponIssue*`, `RedisCouponIssueQueue*` | 동시 수용은 사고 실험과 같이 돌리지 않았다 |
| 랭킹 | 사고 실험: batch 회차 1과 2, 랭킹 API 17 → 20, 회차 1 보존. 회귀: `WeeklyRankingJobTest`(순위 실패 뒤 다시 실행), `WeeklyRankingOperationsTest`, `WeeklyRankingV1ApiE2ETest` | 같은 instance 재시작은 batch 테스트로만 확인했다 |
| 이번 사고 | `CheckoutIncidentTest`: 위의 제출 실행. `PaymentBoundaryLogIntegrationTest` 5건: 두 경계 로그의 필드와 레벨, 기존 rollback error의 시도·주문 ID, 사용자 ID·시도 키·카드 정보 없음. `PaymentAttemptMetricsIntegrationTest` 4건: 상태별 집계, 기한 초과만 세는 조건과 5분 경계, 상태 인덱스 사용, 종결 뒤 상태별·기한 초과 값 0, 두 집계 중 하나라도 실패하면 둘 다 직전 값 유지. 경보 쿼리: promtool 테스트. 경보 발생·해제와 Slack 알림: 로컬 Grafana 시연 | [확인하지 않은 범위](#확인하지-않은-범위) |

## 확인하지 않은 범위

- 배포 JAR 그대로의 health와 지표. API는 테스트 컨텍스트에서 돌고, 관리 포트와 주소를 바꾸고 테스트에서 꺼지는 metric export를 켠다.
- 경보 발생·해제와 Slack 알림은 로컬 시연 두 번으로만 봤다. 경보가 1시간 넘게 이어질 때의 반복 알림은 보지 않았다.
- 운영 배포, 실제 계정, 부하, 여러 인스턴스, 다른 장애 유형, 서비스 수준 목표. 주문 20건을 한 번 돌린 로컬 통제 실험이다.
- 실제 PG의 동작. 응답 유실은 테스트 PG가 응답을 늦춰 만든다.
- 런북의 운영 실행. 운영 권한, 승인 기록, 인스턴스별 재시작은 해 보지 않았다. 런북 SQL은 임시 DB에서만 실행했다.

만들지 않은 것과 실험 장치의 한계는 설계 문서의 [한계와 남은 위험](payment-response-lost.md#한계와-남은-위험)에 있다.
