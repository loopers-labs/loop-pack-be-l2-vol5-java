# 회귀 전체 + 리허설 재실행 — 중단된 사전 실행

- runId: `20261126T113318Z-6d788078` (검사 시작 UTC + HEAD `6d788078`). 맥북에어 로컬. 2026-11-26 11:33~11:50 UTC (20:33~20:50 KST).
- **중단된 사전 실행이다.** PL 지시(참모 20:47 새 지시로 순서 변경)로 리허설 2회차(만료·환불 추적)를 시작 전에 멈추고 정리했다. 제출용 리허설 기록이 아니다. 지시에 따라 폴더는 그대로 둔다.
- 시각은 UTC. 초 단위 측정은 셸 `date -u +%T`와 Prometheus·Alertmanager·가짜 수신기 기록 시각이다.

## 1. 회귀 검사

명령: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 후 `./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --rerun-tasks`
11:33:18~11:39:48, `BUILD SUCCESSFUL in 6m 29s`, 42 tasks executed. 건수는 `build/test-results/*/*.xml` 집계.

| 모듈 | 작업 | 파일 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- | --- |
| commerce-api | test | 135 | 1137 | 0 | 0 | 0 |
| commerce-api | e2eTest | 4 | 4 | 0 | 0 | 0 |
| commerce-streamer | test | 16 | 47 | 0 | 0 | 0 |
| commerce-batch | test | 15 | 54 | 0 | 0 | 0 |

Checkstyle(main·test·e2eTest) 통과.

## 2. 알림 검사

명령: `bash scripts/ops/check-alerting.sh` (11:40:04~11:40:38, exit 0)

| 단계 | 결과 |
| --- | --- |
| promtool check rules / config | SUCCESS, 12 rules |
| promtool test rules | executed=40 declared=40 failed=0 skipped=0 |
| amtool check-config, routes test | SUCCESS (라우팅 11건) |
| dashboard / rule links | OK |
| alloy validate, loki verify-config | OK |
| guide unittest | 16 tests, failed=0 errors=0 skipped=0 |

## 3. 리허설 1회차(결과 모름 → 알림 → 일괄 조회 확정)

설정: `rehearsal.sh up`(compose `w10r-infra`·`w10r-mon`, 리허설 오버레이), `rehearsal.sh test-pg`, `rehearsal.sh app`(자동 재조회·만료 스케줄러 끔, gauge 5s). **로그 경로 override 없음**(B1 수정 확인용). 관리자 계정은 무작위 값을 셸 환경으로만 넘겼다.

| 시각 | 단계 | 명령 | 결과 | 캡처 |
| --- | --- | --- | --- | --- |
| 11:40:46~11:40:54 | 스택 기동 | `rehearsal.sh up` | 컨테이너 11개 Up | — |
| 11:41:12 | 앱 기동 | `rehearsal.sh app` | Started 7.4s | — |
| 11:41:20~11:41:21 | 준비·기준 주문 3건 | `test-pg.sh behavior ACCEPT_SUCCESS`, `seed`, `order 1` ×3 | 3건 CONFIRMED, createCalls 3 | p1-01, p1-02 |
| **11:41:49**~11:42:01 | 사고: 응답 유실 4건(주문 4~7) | `order 1 lose` ×4 | 각 202 `PAYMENT_PENDING`/`UNKNOWN`. createCalls 7 | p1-03, p1-04 |
| 11:42:11 | 알림 pending | Prometheus `/api/v1/alerts` | PaymentUnresolvedPiling pending | — |
| 11:43:12 | 알림 firing | 〃 | firing (startsAt 11:43:11) | p1-05 |
| **11:43:41** | 가짜 수신기 `/slack` 2건 | 수신기 로그 | 원본 + `[가이드]`: unknown=4, 유입 비율 57.14%, 만료 0, 환불 대상 전부 0. AI 요약 없음(키 없음) | p1-15 1~2줄 |
| **11:46:57** | 사람 조치 시작 | `panel`, `loki 4`, `unresolved` | 패널 unknown=4. Loki 3줄(WARN `result=Unknown … HttpTimeoutException`). 목록 4건, lookupAttempts 0 | p1-07, p1-08, p1-09 |
| 11:46:57~11:46:59 | 일괄 조회 확정 | `reconcile 4,5,6,7` | 4건 RESOLVED/VERIFIED, CONFIRMED | p1-10, p1-11, p1-12 |
| 11:47:09 | 지표 0 | Prometheus | unknown 0 (11:47:06엔 2) | p1-13 |
| 11:47:12 | Prometheus 알림 0건 | 〃 | 0건 | p1-14 |
| 11:48:41 | 가짜 수신기 resolved 2건 | 수신기 로그 | 원본 resolved + `[가이드] resolved` | p1-15 3~4줄 |

측정값:

| 항목 | 값 |
| --- | --- |
| 사고 시작 → 근거 붙은 알림 수신 | 11:41:49 → 11:43:41 = **1m52s** |
| 알림 수신 → 사람 조치 시작 | 3m16s. **실행자 쪽 지연이다**(수신기 확인 명령의 셸 오류로 수신을 늦게 알아챘다). 사람 판단 시간 측정값이 아니다 |
| 사람 조치 시작 → 일괄 확정 완료 | 11:46:57 → 11:46:59 = 2s (명령을 미리 알고 스크립트로 연속 실행) |
| 일괄 확정 완료 → 지표 0 / Prometheus 알림 해제 | 10s / 13s |
| Prometheus 해제 → Slack 자리 resolved | 1m29s |
| test-pg createCalls | 7 → 7 (일괄 확정 전후 증가 0, 재결제 없음). lookupCalls 0 → 4 |
| test-pg cancelCalls | 0 (이 회차는 환불 경로 없음) |

## 4. B1·B5 재확인

| B | 결과 |
| --- | --- |
| B1 | **확인.** override 없이 `rehearsal.sh app`(기본 bootRun)으로 띄웠고 Loki에서 주문 4의 경계 로그 3줄을 찾았다(p1-08, WARN·INFO·DEBUG). 저장소 루트 `logs/commerce-api.json`에 이번 실행 줄이 쌓였다 |
| B5 | **미확인.** 스케줄러를 켠 2회차(`lookup-behavior ERROR_500`)는 앱·test-pg 기동까지 하고 주문 전에 중단했다 |

## 5. 못 한 것

- 리허설 2회차 전체: 스케줄러 켠 채 조회 실패(B5) → 2분 만료 → PaymentAbandonedPiling → 환불 추적(늦은 성공 → 환불 요청 1회 → REFUNDED, cancelCalls 1). PL 중단 지시로 시작하지 않았다. 측정값 없음.
- Grafana MCP 질의(서비스 계정 토큰 생성은 승인 대상).
- 알림 수신 → 사람 조치 시작 시간은 위 사유로 유효한 측정값이 아니다.

## 6. 정리

- 11:48 앱·test-pg 종료(TaskStop). 8080~8083 LISTEN 없음, 앱·test-pg 프로세스 없음.
- `rehearsal.sh down`(w10r-mon·w10r-infra `down -v`). w10r 컨테이너·볼륨·네트워크 0개. 실행 중 컨테이너 0개. 평소 프로젝트 볼륨 `docker_mysql-8-data`는 건드리지 않았다.

## 7. 캡처 검사

- 관리자 계정 값·test-pg 카드 번호 문자열: 0건. 카드 번호 모양(`dddd-dddd-dddd-dddd`): 0건.
- 키워드(token·password·secret·api key·authorization·xsrf·Slack webhook): 값 0건. `ANTHROPIC_API_KEY`는 가이드 메시지의 "키 없음" 문구(변수 이름)뿐이다.
- 주문 번호·PG 주문 번호·userId 1(local 시드)은 리허설 데이터라 그대로 둔다.
- 파일 해시: `SHA256SUMS`.
