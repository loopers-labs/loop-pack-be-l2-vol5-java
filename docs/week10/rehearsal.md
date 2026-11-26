# 10주차 사람 흐름 리허설(game day) 기록

- 일시: 2026-11-25 23:44~23:53 KST, 1회. 맥북에어 로컬.
- 목적: 알림 → Alertmanager → 가이드 서버(critical) → 가짜 수신기 → 대시보드 → runbook 앵커 → Loki 진단 → 관리자 목록 → 일괄 조회 확정 → 지표 0 복귀·알림 resolved를 사람이 따라 하는 순서로 한 번 실행한다.
- 재현 명령: `scripts/ops/rehearsal.sh` (단계 이름은 아래 표의 "명령" 열).
- 캡처: `docs/week10/rehearsal/` (커밋 전 관리자 계정·카드 번호·토큰 문자열 검사: 0건).
- 리허설은 자동 테스트가 아니다. 통과 여부는 이 기록으로 판단한다.

## 리허설 설정(운영 값 파일은 그대로)

| 대상 | 리허설 값 | 위치 |
| --- | --- | --- |
| 알림 규칙 | PaymentUnresolvedPiling: 창 5m, unknown ≥ 3, for 1m / PaymentAbandonedPiling: 창 5m, ≥ 3, for 1m. `rehearsal="true"` label | `docker/prometheus/rules/rehearsal/payment-rehearsal.yml` (promtool check rules 통과, 2 rules) |
| Prometheus 설정 | 운영 규칙 + 리허설 규칙을 함께 로드 | `docker/prometheus/prometheus.rehearsal.yml` (promtool check config 통과) |
| compose | Prometheus 설정만 바꿔 붙이는 오버레이 | `docker/monitoring-compose.rehearsal.yml` |
| compose 프로젝트 | `w10r-infra`, `w10r-mon` | 평소 `docker` 프로젝트 볼륨(`docker_mysql-8-data`)을 `down -v`로 지우지 않기 위해 |
| 앱(명령줄) | `--payment.lookup.scheduler.enabled=false`, `--commerce.payment.unresolved-metric.refresh-interval=5s` | `rehearsal.sh app`. 리허설 당시에는 B1 우회로 `--logging.file.name=<저장소>/logs/commerce-api.json`도 붙였다. B1 수정 뒤에는 필요 없다 |
| test-pg | create 동작 `ACCEPT_SUCCESS`, 유실 지연 5s(기본) | `test-pg.sh behavior ACCEPT_SUCCESS` |
| Alertmanager | 운영 설정 그대로(group_wait 30s, group_interval 5m) | 바꾸지 않음 |

- 자동 재조회 스케줄러를 끈 이유: test-pg 조회는 정상이라, 켜 두면 유실된 결제가 몇 초 안에 자동 회복된다. "자동 재조회가 못 따라잡는" 사고를 만들려면 꺼야 했다. 같은 스케줄러가 만료도 돌리므로 결제 만료 2분도 함께 멈췄다(만료 값은 바꾸지 않음).
- 관리자 계정은 무작위 값을 만들어 셸 환경으로만 넘겼다. 기록·캡처에 남기지 않았다.

## 단계별 결과

| # | 시각 | 단계 | 명령 | 결과 | 근거 |
| --- | --- | --- | --- | --- | --- |
| 1 | 23:44:05~23:44:13 (8s) | 스택 기동 | `rehearsal.sh up` | 성공 | 인프라 4·모니터링 7 컨테이너 Up |
| 1 | 23:44:36 | 앱·test-pg 기동(기본 `bootRun`) | `gradlew :apps:commerce-api:bootRun`, `test-pg.sh start` | **로그 경로 실패** | JSON 로그가 `apps/commerce-api/logs/commerce-api.json`에 생김. Alloy는 저장소 `logs/`를 읽는다 → 막힌 지점 B1 |
| 1 | 23:45:00 | 앱 재기동(당시 B1 우회: `--logging.file.name` override) | `rehearsal.sh app`(당시 판) | 성공 | 저장소 `logs/commerce-api.json` 생성(22KB, logstash JSON). 기동 4.3s |
| 2 | 23:45:05 | 데이터 준비 | `rehearsal.sh seed` | 성공(우회) | 사용자: API가 없어 DB에 직접 INSERT(B3). 브랜드·상품: 관리자 API(래퍼). 포인트 충전 |
| 2 | 23:45:09~23:45:21 | 기준 주문 3건 | `rehearsal.sh order 1` ×3 | 성공 | 3건 CONFIRMED. Prometheus: started=3, unknown_entered=0, outcome confirmed=3, unresolved 전부 0 (`01-baseline-metrics.json`) |
| 3 | 23:45:25~23:45:38 | 사고: 응답 유실 4건 | `rehearsal.sh order 1 lose` ×4 | 성공 | 각 3.1s 후 202 `PAYMENT_PENDING`/`providerStatus=UNKNOWN`. test-pg createCalls 7, 유실 지정 4건 |
| 3 | 23:45:37 / 23:45:52 | gauge 반영 | `rehearsal.sh metrics` | 성공 | unknown 1 → 4 (새 counter `unknown_entered_total`=4 실제 수집 확인) |
| 4 | 23:45:45 | 알림 pending | `rehearsal.sh alerts` | 성공 | PaymentUnresolvedPiling pending (rehearsal=true) |
| 4 | 23:46:41 | 알림 firing | 〃 | 성공 | pending 후 56s. Alertmanager 1건 (`02-…`, `03-…`) |
| 4 | 23:47:11 | 가짜 수신기 `/slack` 2건 | `rehearsal.sh receiver` | 성공 | ① 원본 ② `[가이드]` 근거: unknown=4, 유입 비율 57.14%, Loki WARN 4줄 `result=Unknown … HttpTimeoutException` (`04-fake-receiver.jsonl`). 첫 유실 confirm부터 1m46s |
| 5 | 23:47:21 | 대시보드 패널 | `rehearsal.sh panel` (Grafana `/api/ds/query`) | 성공 | "미확인 결제 상태별" 식 결과 unknown=4 (`05-…`) |
| 5 | 23:47:21 | runbook 앵커 | 알림 `runbook_url` → `docs/runbook/week10.md#paymentunresolvedpiling` | 부분 성공 | 앵커는 runbook 제목과 맞다. 다만 상대 경로라 Grafana·Slack에서 눌러 열리지 않는다(B4) |
| 6 | 23:47:26 | Loki 진단(주문 4) | `rehearsal.sh loki 4` | 성공 | 3줄: WARN `PG 결제 생성 시도 결과 … result=Unknown, reason=ResourceAccessException(HttpTimeoutException)`, INFO 시작, DEBUG gateway. structured metadata orderId·providerOrderId·attemptKey 확인 (`06-…`) |
| 7 | 23:47:35 | 관리자 미확인 목록 | `rehearsal.sh unresolved` (`admin-curl.sh GET`) | 성공 | 4건(4~7), lookupAttempts 0 (`07-…`). ID 고정: 4,5,6,7 |
| 7 | 23:47:39 | 일괄 조회 확정 | `rehearsal.sh reconcile 4,5,6,7` (`admin-curl.sh POST`) | 성공 | 1.05s. 4건 모두 `RESOLVED`/`VERIFIED`, CONFIRMED (`09-…`). test-pg 유실 지정은 해제하지 않음 |
| 8 | 23:47:40 | PG 호출 수 | `test-pg.sh status` | 성공 | createCalls 7 → 7(증가 0), lookupCalls 0 → 4 (`08-…`, `10-…`) |
| 8 | 23:47:52 | 지표 0 복귀 | `rehearsal.sh metrics` | 성공 | unknown 0, outcome confirmed=7 (`11-…`) |
| 8 | 23:47:58 | Prometheus 알림 해제 | `rehearsal.sh alerts` | 성공 | 알림 0건 (`12-…`) |
| 8 | 23:52:11 | 가짜 수신기 resolved 2건 | `rehearsal.sh receiver` | 성공 | 원본 resolved + `[가이드] resolved`. 해제 후 4m13s(group_interval 5m) |
| 9 | 23:53 | 정리 | TaskStop(앱·test-pg), `rehearsal.sh down` | 성공 | w10r-* 컨테이너·볼륨·네트워크 삭제. 8080~8083 등 LISTEN 없음. `docker_mysql-8-data`는 남음 |

- 사고 시작(23:45:25) → 사람이 근거 붙은 알림을 받기까지 1m46s(리허설 값 기준). 알림 수신 → 일괄 확정 완료 28s(명령을 미리 알고 있던 경우).
- Grafana MCP 질의는 하지 않았다. Viewer 서비스 계정 토큰을 새로 만들어야 하는데, 접근 키 생성은 승인 대상이라 범위 밖으로 뒀다.

## 막힌 지점

| ID | 막힌 지점 | 원인 | 이번 처리 | 고칠 곳(판단 필요) |
| --- | --- | --- | --- | --- |
| B1 (발견 → 수정) | 기본 `bootRun`으로 띄우면 JSON 로그가 `apps/commerce-api/logs/`에 쌓여 Loki에 안 들어간다 | `json-file-appender.xml` 기본 경로가 상대 경로 `logs/…`이고, Gradle `bootRun` 작업 디렉터리가 `apps/commerce-api`였다. Alloy는 저장소 `logs/`만 붙인다 | 리허설 중에는 `--logging.file.name` override로 우회 | **수정(PL 결정):** `apps/commerce-api/build.gradle.kts`의 `bootRun`에 `workingDir = rootProject.projectDir`. 앱 설정의 다른 경로는 classpath뿐이다. 확인(23:55): 인프라만 띄우고 override 없이 `./gradlew :apps:commerce-api:bootRun` → 저장소 루트 `logs/commerce-api.json` 생성(JSON 줄 파싱 OK, 시작 로그의 작업 디렉터리 = 저장소 루트), `apps/commerce-api/logs/`는 생기지 않음 |
| B2 | 저장소 `logs/`가 앱 기동 전부터 있다 | 모니터링 compose의 Alloy 바인드 마운트가 디렉터리를 만든다 | — | "logs/가 있다"를 앱 로그 생성 근거로 쓰면 안 된다(파일로 확인) |
| B3 | 고객 주문에 필요한 사용자를 만들 API가 없다 | `users` 테이블은 id만 있고 생성 API가 없다 | DB에 `INSERT IGNORE INTO users (id)` | local seed SQL에 사용자 1명 추가 여부 → **반영(4b 마무리):** `sql/local/seed-users.sql`(id 1, 없을 때만 넣음)을 local `spring.sql.init`에 더했고 `rehearsal.sh seed`의 직접 INSERT를 뺐다 |
| B4 | 알림·대시보드의 runbook 링크를 눌러도 열리지 않는다 | `runbook_url`·대시보드 링크가 저장소 상대 경로(`docs/runbook/week10.md`)다. Grafana에서 누르면 `localhost:3000/docs/...`(302)로 간다. `generatorURL`도 컨테이너 호스트명이다 | 저장소에서 파일을 직접 열었다 | 저장소 웹 주소(GitHub blob URL)로 바꿀지 → **반영(4b 마무리, PL 결정):** 운영 규칙·대시보드 링크를 원본 저장소 `L2J4Dot` blob URL로. 머지 전에는 열리지 않을 수 있다. 리허설 규칙은 상대 경로 그대로 |
| B5 | 자동 재조회를 켜 두면 사고가 재현되지 않는다 | test-pg 조회가 정상이라 스케줄러가 몇 초 안에 회복시킨다. test-pg 제어에 조회 실패 지정이 없다 | 스케줄러를 끔(만료도 같이 멈춤) | test-pg 제어에 조회 동작(`LookupBehavior`) 변경 endpoint를 더하면 스케줄러를 켠 채 "재조회가 실패하는" 사고를 재현할 수 있다 |

## 사람이 어려웠을 점

- 유실 주문을 지정하려면 주문 생성 뒤 confirm 전에 PG 주문 번호(`LP-ORD-%010d`)를 손으로 만들어야 한다. 래퍼(`rehearsal.sh order … lose`)가 없으면 실수하기 쉽다.
- resolved 가이드 메시지에도 "결과 모름 유입 비율(30분) 57.14%"가 그대로 보인다. 30분 창이라 해제 직후에도 높다. resolved인데 비율이 높아 헷갈릴 수 있다.
- resolved 알림은 해제 후 최대 5분 늦게 온다(Alertmanager group_interval 5m). 사람은 Prometheus·대시보드에서 먼저 해제를 본다.
- 가이드 메시지의 대표 줄에 주문 번호가 그대로 나온다(LLM에는 보내지 않음, 설계대로). 사람은 이 번호로 바로 Loki를 찾을 수 있어 편했다. 다만 일괄 확정 대상은 관리자 목록에서 고정해야 한다(대표 줄은 최대 3줄).
- Loki 결과는 structured metadata 조합마다 다른 stream으로 나뉜다. `count_over_time`도 줄마다 1로 나와 합계를 따로 봐야 한다.
- 인프라 compose의 `container_name`(redis-master·kafka 등)이 고정이라 같은 이름 컨테이너가 다른 프로젝트에 있으면 충돌한다. 이번에는 없었다.

## 4a·4b 미검증 항목 확인 결과

| 항목 | 결과 |
| --- | --- |
| 래퍼(`admin-curl.sh`)를 실제 앱에 붙임 | 확인. GET 목록, POST 일괄(XSRF 쿠키 토큰) 모두 성공 |
| 새 counter(`commerce_payment_unknown_entered_total`·`started_total`) 실제 수집 | 확인. 3 → 7, 0 → 4 |
| 앱 실제 JSON 로그로 Loki 질의 | 확인. orderId structured metadata로 경계 로그 3줄 |
| 기본 로그 경로 `logs/commerce-api.json` 생성 | 리허설 때 기본 `bootRun`에서 실패(B1) → 수정 후 override 없이 생성 확인(23:55). 수정 뒤 Loki 수집까지 다시 돌리지는 않았다 |
| PaymentAbandonedPiling(만료) 경로 | 미검증. 이번 리허설은 만료를 멈췄다 |
| Grafana MCP 질의 | 미검증(토큰 생성 승인 필요) |

## 6단계 2회차(2026-11-26)

- 일시: 2026-11-26 12:13~12:32 UTC (21:13~21:32 KST), 1회. 맥북에어 로컬. 로컬·테스트 환경 리허설이며 운영 완료가 아니다.
- 기록 원본: `docs/week10/evidence/20261126T121317Z-965d8579/README.md`(캡처·`SHA256SUMS` 포함). 아래 숫자는 그 README 값을 그대로 옮겼다.
- 사전 실행 `docs/week10/evidence/20261126T113318Z-6d788078/`(11:33~11:50 UTC)은 PL 지시로 중단했다. 그 실행에서 B1 수정(override 없이 기본 `bootRun` 로그가 Loki에 들어감)을 확인했다. 제출 근거는 이 2회차다.
- 설정은 1회차와 같다(리허설 규칙·오버레이·Alertmanager 운영 값). 다른 점: 장면 B·C는 `rehearsal.sh app` 대신 스케줄러를 켠 `bootRun`(`--commerce.payment.unresolved-metric.refresh-interval=5s`)과 `test-pg.sh lookup-behavior ERROR_500`(B5 도구)을 썼다.
- 사람 조치는 미리 쓴 명령 묶음(`act.sh`)으로 실행했다. "알림 수신 → 사람 조치 시작"은 명령 실행 시간이며 사람 판단 시간이 아니다.

### 측정값

장면 A — 응답 유실 4건 → PaymentUnresolvedPiling → 목록 고정 → 일괄 확정(1회차 재측정)

| 측정 | 값 |
| --- | --- |
| 사고 시작 → 근거 붙은 알림 수신 | 12:14:12 → 12:16:11 = 1m59s |
| 알림 수신 → 수신 확인 / 사람 조치 시작 | 2s / 7s (1회차 3m16s는 셸 오류였다) |
| 조치 시작 → 일괄 확정 완료 | 1s. 4건 `RESOLVED`/`VERIFIED` |
| 일괄 확정 완료 → 지표 0 / Prometheus 해제 | ≤4s / ≤7s (5s scrape 해상도) |
| Prometheus 해제 → Slack 자리 resolved | 4m45s (group_interval 5m) |
| test-pg createCalls / lookupCalls / cancelCalls | 7 → 7(증가 0) / 0 → 4 / 0 |

장면 B — 스케줄러 켠 채 재조회 실패(`lookup-behavior ERROR_500`) → 멈춤 → 재진입

| 측정 | 값 |
| --- | --- |
| 사고 시작 → 첫 근거 붙은 알림 수신 | 12:22:25 → 12:26:11 = 3m46s. 받은 알림은 PaymentAbandonedPiling. PaymentUnresolvedPiling은 Prometheus에서 12:23:57~12:24:41(44s) firing이었으나 Slack 자리에 오지 않음(B6) |
| 알림 수신 → 수신 확인 / 사람 조치 시작 | 5s / 11s |
| 조치 시작 → 멈춤 판단 | 0s. 일괄 확정 503(회로 OPEN), 처리 0건 |
| PG 회복(12:26:29) → 회로 미호출 마지막 줄 / 자동 확정 | 6s / 7s (Loki `source=AUTOMATIC, result=Verified`) |
| 재진입 한 건 목록(12:27:00) | 503·`LOOKUP_NOT_CALLED` 없음. 결과 `ALREADY_CLOSED`(B7) |
| 목록 다시 고정 / 같은 명령 재실행 | 0건 / 3건 `ALREADY_CLOSED` |
| createCalls | 기준 1 + 유실 7 = 8. 멈춤·재진입·재실행 동안 증가 0 |

장면 C — 만료 4건 → 환불 확인 대상 → 늦은 SUCCESS → 환불 요청 1회 → REFUNDED

- "늦은 SUCCESS"는 test-pg 거래 상태를 바꾼 것이 아니다. 유실 지정 거래는 처음부터 SUCCESS로 저장되고, 조회 실패가 풀린 뒤 환불 추적 조회가 그것을 처음 본 것이다.

| 측정 | 값 |
| --- | --- |
| 만료(12:24:26~12:24:35) → 환불 확인 대상 CHECKING 4 | ≤8s (5s gauge 해상도) |
| 조회 실패 동안 환불 추적 | `refund_check` pg_error 5·pg_unreachable 4, `lastCheckOutcome=PG_UNREACHABLE` |
| PG 회복 → 첫 REFUNDED / 4건 REFUNDED | 24s / 54s (환불 추적 회차 30s 간격) |
| test-pg cancelCalls | 0 → 4. 주문별 `환불을 요청합니다` 줄 각 1 |
| createCalls(환불 추적 동안) | 8 → 8 |
| 최종(12:31:19) | refunded 4, 나머지 0. 사람 담당 0건 |
| PaymentAbandonedPiling 수신 / resolved 수신 | 12:26:11 / 12:31:11 |

### runbook 조건 대조

| 장면 | runbook 조건 | 관찰 | 따름 |
| --- | --- | --- | --- |
| A | Diagnosis 3 대상 고정 `minAgeSeconds=300` | 0건. 리허설 목록(minAge 없음)으로 4건 고정 | 부분(B8) |
| A | Diagnosis 4 P99 알림 동시 여부 | 확인하지 않음 | 미확인 |
| A | 멈출 때: 503·`SKIPPED`·`STOPPED` / create 증가 / H1 아님(반영 ERROR) | 없음 / 7 → 7 / ERROR 30분 0줄 | 해당 없음 |
| A | 해소: 결과 모름 기준 미만, 알림 해제 | 0, 해제, resolved 수신 | 따름 |
| B | Diagnosis 1 Unresolved 동시 여부 | Prometheus firing 있음, Slack 자리 없음 | 부분(B6) |
| B | Diagnosis 2·3 조회 판정 `kind`·`reason`, `refundMoneyStatus=CHECKING` | `LOOKUP_FAILED`·`HTTP 500` / 4건 `PG_UNREACHABLE` | 따름 |
| B | 만료된 주문은 손대지 않음 | 주문 2~5를 일괄 확정에 넣지 않음 | 따름 |
| B | **멈출 때:** 503(회로 OPEN) | 12:26:22 503, 처리 0건 → 멈춤 | 따름 |
| B | 멈출 때: create 증가 / H1 아님 | 8 → 8 / ERROR 30분 0줄 | 해당 없음 |
| B | **다시 들어갈 때** ① 회로 CLOSED 간접 확인 | 31s 뒤 한 건 목록이 503·`LOOKUP_NOT_CALLED` 없음. 다만 `ALREADY_CLOSED`라 PG를 부르지 않았다. 닫힘 근거는 Loki(미호출 줄 끝 12:26:35, 자동 Verified 12:26:36) | 부분 |
| B | 다시 들어갈 때 ② PG 조회 1건 수동 성공(`RESOLVED`) | 자동 재조회가 7s 만에 먼저 확정 | 따르지 못함(B7) |
| B | 다시 들어갈 때 ③ 목록 다시 고정 / ④ 같은 명령 재실행 | 0건 / `ALREADY_CLOSED` ×3, create 증가 0 | 따름 |
| C | 만료 주문은 환불 추적에 맡김, 재결제·환불 재요청 금지 | 사람 조치 0. 주문당 취소 1회 | 따름 |
| C | 사람 담당 알림(PaymentRefundNeedsPerson 등) | 사람 담당 0건 | 해당 없음 |

### 못 한 것

- 장면 B 재진입의 "PG 조회 1건 수동 성공"(`RESOLVED`): 스케줄러가 먼저 확정했다(B7).
- 장면 B에서 PaymentUnresolvedPiling을 Slack 자리로 받기(B6).
- 장면 C의 `REFUND_REQUESTED` 상태 관찰: test-pg 취소(NORMAL)가 바로 CANCELED를 줘 같은 회차에 REFUNDED로 넘어갔다. 5s gauge·목록에 잡히지 않았다.
- 취소 응답 유실(`lose-cancel`) 장면: 하지 않았다.
- PaymentApiLatencyP99High 동시 여부 확인(장면 A Diagnosis 4).
- 사람 판단 시간: 조치는 미리 쓴 묶음으로 실행했다.
- Grafana MCP 질의: Viewer 서비스 계정 토큰 생성은 승인 대상이라 범위 밖.

### 발견(B6~)

| ID | 발견 | 원인 | 이번 처리 | 고칠 곳(판단 필요) |
| --- | --- | --- | --- | --- |
| B6 | 같은 알림이 resolved 통지 뒤 다시 울리면 Slack 통지가 늦거나, 그 사이 풀리면 빠진다. 장면 B에서 사람은 다른 알림(PaymentAbandonedPiling)으로 사고를 알았다(3m46s) | Alertmanager는 `alertname`·`severity`로 묶고 같은 그룹은 `group_interval` 5m마다 통지한다(`group_wait` 30s, critical `repeat_interval` 1h). 장면 A의 Unresolved resolved 통지(12:21:11) 뒤 다음 통지 시점은 12:26:11이었다. 장면 B의 Unresolved는 12:23:57 firing, 12:24:41 해제(2분 만료가 결과 모름을 치움)라 그 전에 끝났다. 원인은 설정 값과 시각으로 맞춘 설명이다 | Prometheus에서 firing 이력을 직접 봤다 | runbook에 "알림이 늦거나 안 오면 Prometheus/Grafana에서 firing 이력을 본다" 추가(반영). 운영 값(for 10m > 만료 2m)에서도 같은 일이 생기는지, `group_interval` 조정 여부는 판단 필요 |
| B7 | 스케줄러가 켜져 있으면 "다시 들어갈 때"의 수동 성공 1건이 `ALREADY_CLOSED`로 끝나 `RESOLVED`를 볼 수 없다 | 회로 반열림 직후 자동 재조회가 1s 안에 대상을 모두 확정했다 | 회로 닫힘은 Loki 줄로 간접 확인 | runbook "관리자 도구"에 "`ALREADY_CLOSED`면 자동이 먼저 처리한 것(정상), 다음 단계로" 추가(반영) |
| B8 | runbook Diagnosis 3의 `minAgeSeconds=300` 목록이 리허설에서 0건이다 | 리허설 규칙은 창 5m·for 1m이라 알림 때 주문이 5분 미만이다. 운영 규칙(for 10m)과 값이 다르다 | `rehearsal.sh unresolved`(minAge 없음)로 고정 | 리허설 절차에서만 minAge 없는 목록을 쓴다. runbook은 운영 값 그대로 |
| B9 | Loki 질의·가이드 메시지 집계에 이전 실행 줄이 섞인다 | 저장소 `logs/commerce-api.json`이 실행 사이에 남아 새 Loki가 다시 읽는다. 주문 번호가 앱 재기동마다 1부터라 `orderId="4"` 같은 질의에 1회차·사전 실행 줄이 함께 나온다. Abandoned 가이드의 `result=Unknown` 13줄도 여러 실행의 합이다 | 시각으로 걸러 읽었다 | 리허설 시작 전 로그 파일을 비우거나 옮길지(리허설 절차) |
| B10 | 앱만 재기동하면 test-pg에 같은 PG 주문 번호 거래가 남아 조회 결과가 두 건이 된다 | test-pg는 거래를 메모리에 두고, 앱 DB는 `ddl-auto create`로 비워져 `LP-ORD-0000000001`…이 겹친다 | 첫 시도(12:21:30~)를 중단하고 앱·test-pg를 함께 재기동 | 리허설 절차: 앱을 재기동하면 test-pg도 재기동한다 |
