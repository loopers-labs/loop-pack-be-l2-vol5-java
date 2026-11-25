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
| B3 | 고객 주문에 필요한 사용자를 만들 API가 없다 | `users` 테이블은 id만 있고 생성 API가 없다 | DB에 `INSERT IGNORE INTO users (id)` | local seed SQL에 사용자 1명 추가 여부 |
| B4 | 알림·대시보드의 runbook 링크를 눌러도 열리지 않는다 | `runbook_url`·대시보드 링크가 저장소 상대 경로(`docs/runbook/week10.md`)다. Grafana에서 누르면 `localhost:3000/docs/...`(302)로 간다. `generatorURL`도 컨테이너 호스트명이다 | 저장소에서 파일을 직접 열었다 | 저장소 웹 주소(GitHub blob URL)로 바꿀지 |
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
