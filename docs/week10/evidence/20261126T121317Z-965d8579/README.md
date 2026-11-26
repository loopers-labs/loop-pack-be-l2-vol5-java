# 리허설 2회차 — 응답 유실 재측정(A)·재조회 실패 멈춤·재진입(B)·만료→환불 추적(C)

- runId: `20261126T121317Z-965d8579` (시작 UTC + HEAD `965d8579`). 맥북에어 로컬. 2026-11-26 12:13~12:32 UTC (21:13~21:32 KST).
- 로컬·테스트 환경 리허설이다. 운영 배포·운영 완료가 아니다.
- 시각은 UTC. 초 단위 값은 셸 `date -u +%T`, Prometheus `query_range`(step 1s, scrape 간격 해상도), 가짜 수신기 `receivedAt`, 앱 JSON 로그 `@timestamp`(KST로 기록, 여기서는 UTC로 옮김)다.
- 실제 앱(`bootRun` local) + 리허설 compose 오버레이(`w10r-infra`·`w10r-mon`) + `runTestPg`. 관리자 계정은 무작위 값을 세션 임시 폴더의 권한 600 파일에서 환경 변수로만 넘겼다(저장소·캡처에 없음). 끝나고 그 파일을 지웠다.
- **수신 확인 명령을 미리 준비했다(1회차 B 막힘 해소).** 세션 임시 폴더의 `wait-receiver.py`가 2초마다 가짜 수신기 로그를 읽어 `[가이드] firing|resolved <알림>`을 찾으면 시각을 찍고 끝난다. 사고를 만든 직후 백그라운드로 띄워 호출기처럼 썼다.
- **사람 조치도 미리 쓴 묶음(`act.sh`: metrics → panel → loki → ERROR 30분 → 목록 → test-pg 전 → 일괄 확정 → test-pg 후)으로 실행했다.** 진단을 읽고 판단한 시간이 아니라 명령 실행 시간이다. 판단은 결과를 본 뒤 기록했다.

## 장면 A — 응답 유실 → 알림 → 목록 고정 → 일괄 확정 (1회차 재측정)

설정: `rehearsal.sh app`(자동 재조회·만료 스케줄러 끔, gauge 5s), `test-pg.sh behavior ACCEPT_SUCCESS`.

| 시각 | 단계 | 명령 | 결과 | 캡처 |
| --- | --- | --- | --- | --- |
| 12:13:26~12:13:33 | 스택 기동 | `rehearsal.sh up` | 컨테이너 11개 Up | — |
| 12:13:50 | 앱 기동 | `rehearsal.sh app` | Started 5.1s | — |
| 12:14:06~12:14:07 | 준비·기준 주문 3건 | `seed`, `order 1` ×3 | 3건 CONFIRMED. createCalls 3 | a-01~a-03 |
| **12:14:12**~12:14:24 | 사고: 응답 유실 4건(주문 4~7) | `order 1 lose` ×4 | 각 202 `PAYMENT_PENDING`/`UNKNOWN`. createCalls 7 | a-04, a-05 |
| 12:14:42 / 12:15:42 | 알림 pending / firing | Prometheus | PaymentUnresolvedPiling | a-21 |
| **12:16:11.44** | 근거 붙은 알림 수신 | 가짜 수신기 | 원본 + `[가이드]`: unknown=4, 유입 비율 57.14%, Loki WARN 4줄 | a-22 |
| 12:16:13 | 수신 확인 | `wait-receiver.py` | 감지 | — |
| **12:16:18** | 사람 조치 시작 | `act.sh a 4,5,6,7 4` | 패널 unknown=4. Loki 주문 4 WARN `result=Unknown … HttpTimeoutException`. ERROR 30분 0줄. 목록 4건(4~7), lookupAttempts 0 | a-10~a-15 |
| 12:16:18~12:16:19 | 일괄 확정 | `reconcile 4,5,6,7` | 4건 `RESOLVED`/`VERIFIED`, CONFIRMED. stopReason null | a-16~a-18 |
| 12:16:23 | 지표 0 | Prometheus | unknown 4 → 0 | a-19, a-21 |
| 12:16:26 | Prometheus 알림 해제 | 〃 | firing 마지막 표본 12:16:26, 이후 0건 | a-20, a-21 |
| 12:21:11 | 가짜 수신기 resolved | 수신기 | 원본 resolved + `[가이드] resolved` | a-22 |

| 측정 | 값 |
| --- | --- |
| 사고 시작 → 근거 붙은 알림 수신 | 12:14:12 → 12:16:11 = **1m59s** |
| 알림 수신 → 수신 확인 | **2s** (12:16:13) |
| 알림 수신 → 사람 조치 시작 | **7s** (12:16:18). 1회차 3m16s(셸 오류)에서 줄었다. 미리 쓴 묶음 실행이라 사람 판단 시간은 포함되지 않는다 |
| 조치 시작 → 일괄 확정 완료 | 1s (12:16:19) |
| 일괄 확정 완료 → 지표 0 / Prometheus 해제 | ≤4s / ≤7s (5s scrape 해상도) |
| Prometheus 해제 → Slack 자리 resolved | 4m45s (group_interval 5m) |
| test-pg createCalls | 일괄 확정 전후 7 → 7 (**증가 0**). lookupCalls 0 → 4. cancelCalls 0 |

## 장면 B — 스케줄러 켠 채 재조회 실패(B5) → 멈춤 → 재진입

설정: 앱 재기동 `./gradlew :apps:commerce-api:bootRun --args="--commerce.payment.unresolved-metric.refresh-interval=5s"`(스케줄러 기본값 켬. `rehearsal.sh app`은 스케줄러를 끄므로 쓰지 않음). test-pg도 재기동(아래 "중단한 첫 시도").

| 시각 | 단계 | 명령 | 결과 | 캡처 |
| --- | --- | --- | --- | --- |
| 12:22:19 | 앱·test-pg 기동 | 위 bootRun, `rehearsal.sh test-pg` | 앱 Started 5.5s | — |
| 12:22:2x | 준비·기준 주문 1건 | `seed`, `order 1` | CONFIRMED. createCalls 1 | b-01~b-03 |
| **12:22:25** | 사고: 조회 실패 지정 | `test-pg.sh lookup-behavior ERROR_500` | — | — |
| 12:22:25~12:22:38 | 응답 유실 4건(주문 2~5) | `order 1 lose` ×4 | 각 202 `UNKNOWN`. createCalls 5. 자동 조회 `LOOKUP_FAILED reason=HTTP 500` 계속 | b-04, b-05 |
| 12:22:57 / 12:23:57 | PaymentUnresolvedPiling pending / firing | Prometheus | firing 12:23:57~12:24:41(44s) | b-27 |
| — | **Unresolved 알림 Slack 자리 수신 없음** | 수신기 | 아래 관찰 1 | z-01 |
| 12:24:26~12:24:35 | 2분 만료 4건 | 앱(만료 스케줄러) | 주문 2~5 `PAYMENT_EXPIRED`. 환불 확인 대상 CHECKING 4(12:24:43) | c-02, c-04 |
| 12:24:42 / 12:25:42 | PaymentAbandonedPiling pending / firing | Prometheus | | b-27 |
| 12:26:03~12:26:13 | 사고 계속: 응답 유실 3건(주문 6~8) | `order 1 lose` ×3 | 결제 대기 건을 남겨 Mitigation 대상을 만들었다. createCalls 8 | b-06, b-07 |
| **12:26:11.45** | 근거 붙은 알림 수신(Abandoned) | 수신기 | `[가이드]`: 만료 증가 4, checking=4, 막힌 원인 pg_error=5·pg_unreachable=1, Loki WARN `LOOKUP_FAILED … HTTP 500` 30줄 | z-01 |
| 12:26:16 / **12:26:22** | 수신 확인 / 사람 조치 시작 | `act.sh b 6,7,8 2`, `?refundMoneyStatus=CHECKING` | 목록 3건(6~8). CHECKING 4건, `lastCheckOutcome=PG_UNREACHABLE` | b-10~b-15, b-19 |
| 12:26:22 | 일괄 확정 시도 → **503** | `reconcile 6,7,8` | "결제 조회 회로가 열려…" 처리 0건. createCalls 8 → 8 | b-16~b-18 |
| 12:26:22 | **멈춤** | — | runbook "멈출 때: 503(회로 OPEN)" | — |
| **12:26:29** | PG 회복 | `test-pg.sh lookup-behavior NORMAL` | — | — |
| 12:26:24~12:26:35 | 회로 OPEN 지속 | Loki | `조회 회로 정책으로 PG를 호출하지 않았습니다` 줄 마지막 12:26:35 | b-26 |
| 12:26:36~12:26:37 | 자동 재조회가 6·7·8 확정 | Loki | `source=AUTOMATIC, result=Verified` — 회로가 반열림→닫힘으로 넘어간 간접 근거 | b-25 |
| 12:27:00~12:27:01 | 재진입: 30s 뒤 한 건 목록 | `reconcile 6` | 503·`LOOKUP_NOT_CALLED` 없음. 결과 `ALREADY_CLOSED`(자동이 먼저 확정) | b-20 |
| 12:27:01 | 목록 다시 고정 | `rehearsal.sh unresolved` | 0건 | b-21 |
| 12:27:08 | 같은 명령 재실행 | `reconcile 6,7,8` | 3건 `ALREADY_CLOSED`. createCalls 8 → 8 | b-23, b-24 |

| 측정 | 값 |
| --- | --- |
| 사고 시작 → 첫 근거 붙은 알림 수신 | 12:22:25 → 12:26:11 = **3m46s** (받은 알림은 Abandoned. Unresolved는 오지 않음) |
| 알림 수신 → 수신 확인 / 사람 조치 시작 | 5s / **11s** |
| 조치 시작 → 멈춤 판단(503) | 0s (같은 초) |
| PG 회복 → 회로 미호출 마지막 줄 / 자동 확정 | 6s / 7s |
| 장면 B 전체 createCalls | 기준 1 + 유실 7 = 8. 멈춤·재진입·재실행 동안 **증가 0** |

## 장면 C — 만료 → 환불 확인 대상 → 늦은 SUCCESS → 환불 요청 1회 → REFUNDED

장면 B의 만료 4건(주문 2~5)이 이어진다. **"늦은 SUCCESS"는 test-pg 거래 상태를 바꾼 것이 아니다.** test-pg 제어에 거래 상태 변경 endpoint가 없다. 응답 유실 지정 주문은 test-pg가 거래를 처음부터 SUCCESS로 저장하므로, 조회 실패가 풀린 뒤 환불 추적 조회가 SUCCESS를 처음 본 것이 늦은 성공이다.

| 시각 | 단계 | 결과 | 캡처 |
| --- | --- | --- | --- |
| 12:24:26~12:24:35 | 만료 4건 | 환불 확인 대상 CHECKING 4(12:24:43) | c-02, c-04 |
| 12:24:53~12:26:23 | 환불 추적 조회 실패 | `refund_check` pg_error 5, pg_unreachable 4. 주문 2 `lastCheckOutcome=PG_UNREACHABLE`(12:26:19) | b-19, c-04 |
| 12:26:11 / 12:31:11 | PaymentAbandonedPiling 수신 / resolved 수신 | | z-01 |
| 12:26:29 | PG 조회 회복(NORMAL) | | — |
| 12:26:49.66 | 주문 5 `환불을 요청합니다` | 12:26:53 refunded 1 | c-02, c-04 |
| 12:27:19.73~12:27:19.78 | 주문 2·3·4 `환불을 요청합니다` | 12:27:23 refunded 4, checking 0 | c-02, c-04 |
| 12:31:19 | 최종 | 돈의 상태 refunded 4, 나머지 0. 사람 담당 0건. `refund_outcome` refunded 4 | z-02, z-05 |

| 측정 | 값 |
| --- | --- |
| 만료 → 환불 확인 대상 생성 | ≤8s (5s gauge 해상도) |
| PG 회복 → 첫 REFUNDED / 4건 REFUNDED | 24s / 54s (환불 추적 회차 30s 간격) |
| test-pg cancelCalls | 0 → **4** (만료 4건). 주문별 `환불을 요청합니다` 줄 각 **1** — 주문당 취소 1회 |
| createCalls | 환불 추적 동안 8 → 8 |
| REFUND_REQUESTED 관찰 | **보지 못함.** test-pg 취소(NORMAL)가 바로 CANCELED를 줘 같은 회차에 REFUNDED로 넘어갔다. 5s gauge·목록에 REFUND_REQUESTED가 잡히지 않았다 |

## runbook 조건 대조

장면 A — PaymentUnresolvedPiling

| 조건 | 관찰 | 따름 |
| --- | --- | --- |
| Diagnosis 1 숫자 확인 | unknown=4 (a-10, a-11) | 따름 |
| Diagnosis 2 Loki WARN `result=Unknown`·`HttpTimeoutException` | 주문 4 WARN 1줄(이번 실행) | 따름 |
| Diagnosis 3 대상 고정 `minAgeSeconds=300` | **0건**(주문이 5분 미만). 리허설 목록(`rehearsal.sh unresolved`, minAge 없음)으로 4건 고정 | 부분(리허설 규칙 5m 창과 운영 300s가 안 맞음) |
| Diagnosis 4 P99 알림 동시 여부 | 확인하지 않음 | 미확인 |
| 멈출 때: 503·SKIPPED·STOPPED | 없음(4건 RESOLVED) | 해당 없음 |
| 멈출 때: create 증가 | 7 → 7 | 해당 없음 |
| 멈출 때: H1 아님(반영 ERROR) | ERROR 30분 0줄(a-13) | 해당 없음(H1과 맞음) |
| 해소: unknown < 기준, 알림 해제 | 0, 해제, resolved 수신 | 따름 |

장면 B — PaymentAbandonedPiling(받은 알림) → Mitigation "결제 대기 건은 Unresolved 순서로 일괄 확정"

| 조건 | 관찰 | 따름 |
| --- | --- | --- |
| Diagnosis 1 Unresolved 동시 여부 | Prometheus에서는 12:23:57~12:24:41 firing이었다. Slack 자리에는 오지 않았다 | 부분 |
| Diagnosis 2 Loki 조회 판정 `kind`·`reason` | `LOOKUP_FAILED`, `HTTP 500` 계속 — 조회 장애 | 따름 |
| Diagnosis 3 `refundMoneyStatus=CHECKING` | 4건, `PG_UNREACHABLE` | 따름 |
| 만료된 주문은 손대지 않음 | 2~5는 일괄 확정에 넣지 않음 | 따름 |
| **멈출 때: 503(회로 OPEN)** | 12:26:22 503, 처리 0건 → 멈춤 | **따름** |
| 멈출 때: create 증가 | 8 → 8 | 해당 없음 |
| 멈출 때: H1 아님(반영 ERROR) | ERROR 30분 0줄(b-13) | 해당 없음 |
| 다시 들어갈 때 ① 회로 CLOSED 간접 확인(30s 뒤 한 건 목록이 503·`LOOKUP_NOT_CALLED` 없이) | 31s 뒤 `reconcile 6` 503·`LOOKUP_NOT_CALLED` 없음. 다만 결과가 `ALREADY_CLOSED`라 PG를 부르지 않았다. 회로 닫힘의 근거는 Loki(미호출 줄 끝 12:26:35, 자동 Verified 12:26:36) | 부분 |
| 다시 들어갈 때 ② PG 조회 1건 수동 성공(`RESOLVED`) | **못 봄.** 자동 재조회가 7s 만에 먼저 확정 | 따르지 못함(관찰 2) |
| 다시 들어갈 때 ③ 결제 대기 건으로 목록 다시 고정 | 0건 | 따름 |
| 다시 들어갈 때 ④ 같은 명령 재실행(멱등) | `ALREADY_CLOSED` ×3, createCalls 증가 0 | 따름 |

장면 C — 환불 추적(자동 담당)

| 조건 | 관찰 | 따름 |
| --- | --- | --- |
| 만료 주문은 손대지 않고 환불 추적에 맡김 | 사람 조치 없음. 자동으로 REFUNDED | 따름 |
| 재결제·환불 재요청 금지 | 사람 요청 0. 주문당 취소 1회 | 따름 |
| 사람 담당 알림(NeedsPerson 등) | 사람 담당 0건이라 해당 없음 | 해당 없음 |

## 관찰(사람이 판단할 것)

1. **같은 알림이 5분 안에 다시 울리면 Slack 자리에 안 올 수 있다.** 장면 A의 Unresolved resolved 통지(12:21:11) 뒤 같은 그룹은 group_interval 5m(12:26:11)까지 다시 통지하지 않는다. 장면 B의 Unresolved는 12:23:57 firing, 12:24:41 해제(2분 만료가 결과 모름을 치움)라 통지 시점 전에 끝났다. 사람은 Abandoned로 처음 알았다(사고 시작 3m46s 뒤). 리허설 값(만료 2m, group_interval 5m) 조합에서 나온 것이다. 운영 값에서도 "결과 모름 알림 for 10m > 만료 2m"이라 같은 일이 생길 수 있는지는 판단이 필요하다.
2. **재진입 "PG 조회 1건 수동 성공"은 스케줄러를 켠 상태에서 사람이 이기기 어렵다.** 회로 반열림 직후 자동 재조회가 1s 안에 대상을 모두 확정했다. 결과는 `ALREADY_CLOSED`로 안전했지만, runbook의 "한 건 목록이 `RESOLVED`" 확인은 할 수 없었다. "`ALREADY_CLOSED`면 자동이 먼저 확정한 것 — 목록 다시 고정으로 넘어간다" 같은 한 줄이 필요한지 판단이 필요하다.
3. **Diagnosis 3의 `minAgeSeconds=300`은 리허설 규칙(5m 창·for 1m)에서 0건이다.** 리허설은 `rehearsal.sh unresolved`(minAge 없음)를 썼다.
4. **Loki에 이전 실행 줄이 섞인다.** 저장소 `logs/commerce-api.json`이 실행 사이에 남아 새 Loki가 다시 읽었다. 주문 번호가 앱 재기동마다 1부터 다시 시작해 `orderId="4"`·`"2"` 질의에 1회차 사전 실행(20:41 KST)·장면 A·중단한 시도 줄이 함께 나왔다(a-12, c-03). 가이드 메시지의 Loki 30분 집계도 섞였다(Abandoned 메시지의 `result=Unknown` 13줄 = A 4 + 중단 시도 4 + B 4 + 두 번째 묶음 일부). 시각으로 걸러 읽었다.
5. **앱을 재기동하면 test-pg도 재기동해야 한다.** test-pg는 거래를 메모리에 남기고 앱 DB는 `ddl-auto create`로 비워져 PG 주문 번호(`LP-ORD-0000000001`…)가 겹친다. 아래 "중단한 첫 시도"가 이 때문이다.

## 중단한 첫 시도(장면 B)

- 12:21:30 앱만 재기동하고 test-pg를 그대로 둔 채 준비·유실 4건을 만들었다(12:21:38~12:21:50). test-pg에 장면 A의 같은 PG 주문 번호 거래가 남아 조회 결과가 두 건이 되는 상태였다. 장면 C(환불 추적)가 오염되므로 12:22 앱·test-pg를 둘 다 멈추고 다시 시작했다.
- 캡처는 `b0-aborted-*`로 남겼다. 그 시도의 결과 모름 gauge가 Prometheus에 잠시 남아 Unresolved pending(12:21:57~12:22:11)이 생겼다(b-27). 측정값에는 쓰지 않았다.

## 못 한 것

- 장면 B 재진입의 "PG 조회 1건 수동 성공"(관찰 2).
- 장면 B에서 PaymentUnresolvedPiling을 Slack 자리로 받기(관찰 1).
- 장면 C의 REFUND_REQUESTED 상태 관찰(같은 회차에 지나감). 취소 응답 유실(`lose-cancel`) 장면은 하지 않았다.
- test-pg 주문별 cancelCalls 값: 제어 status는 합계만 준다. 주문당 1회는 앱 로그 `환불을 요청합니다` 줄 수로 확인했다.
- PaymentApiLatencyP99High 동시 여부 확인(장면 A Diagnosis 4).
- 사람 판단 시간: 조치는 미리 쓴 묶음으로 실행했다.
- Grafana MCP 질의(서비스 계정 토큰 생성은 범위 밖).

## 정리(C6 범위)

| 정리한 것 | 확인 |
| --- | --- |
| 앱(bootRun 2회)·test-pg(2회) 프로세스 | 12:31 TaskStop. `TestPgServerMain`·`CommerceApiApplication` 프로세스 0, 8080~8083 LISTEN 없음 |
| 리허설 compose 프로젝트 `w10r-mon`·`w10r-infra` | 12:31:32~12:31:40 `rehearsal.sh down`(down -v). `w10r` 컨테이너 0, 실행 중 컨테이너 0 |
| 세션 임시 파일(관리자 계정 파일) | 삭제 |

| 건드리지 않은 것 | 확인 |
| --- | --- |
| 평소 `docker` 프로젝트 볼륨(`docker_mysql-8-data`·`docker_kafka-data`·`docker_redis_*`) | 남아 있음 |
| 다른 프로젝트 볼륨·네트워크 | 볼륨 목록 45개·네트워크 목록이 시작 전과 같다(diff 없음) |
| 저장소 `logs/commerce-api.json` | 그대로 둠(관찰 4) |

## 캡처 검사

- 관리자 계정 값·test-pg 카드 번호 문자열: 0건. 카드 번호 모양(`dddd-dddd-dddd-dddd`): 0건.
- 키워드(token·password·secret·authorization·xsrf·Slack webhook): 0건. `api key`·`ANTHROPIC` 6건은 가이드 메시지의 "AI 요약 없음: ANTHROPIC_API_KEY 없음" 문구(변수 이름)뿐이다.
- 주문 번호·PG 주문 번호·userId 1(local 시드)은 리허설 데이터라 그대로 둔다.
- 파일 해시: `SHA256SUMS`.
