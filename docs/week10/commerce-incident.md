# Week 10 — 결제 사고 관측

이 문서는 10주차 결제 사고 대응의 결정을 적는다. 1~7절은 1단계(관측과 테스트 PG fixture 사고 제어), 8절은 사고 연결 테스트, 9절은 4단계 4a 운영 도구다.
나머지 설계(1~29절)는 `docs/week8/commerce-coupon.md`를 따른다.

- **[사용자 결정]**: 사용자가 정한 것(결정 기록 week10). 바꾸려면 승인이 필요하다.
- **[참모 지시]**: 참모 지시문·고정 규칙이다. 바꾸려면 참모 확인이 필요하다.
- **[구현 판단]**: 사용자 결정 범위 안에서 AI(PL·DEVELOPER)가 정한 내부 선택이다. 근거와 함께 적고 뒤집을 수 있다.

## 1. 진단 기준 [사용자 결정]

- 원인은 경계 기록으로 가른다. 두 사고 모두 health는 정상이다.
- **응답 유실:** create 결과 모름 WARN 한 줄(`result=Unknown`, reason에 timeout 예외 이름) + 그 뒤 반영 줄 없음. 주문은 결제 대기·`UNKNOWN`으로 남는다.
- **내부 반영 실패:** 조회 판정 INFO(`result=Verified`, `providerStatus=SUCCESS`) + 반영 실패 ERROR(`causeType`=예외 종류). 주문은 결제 대기로 남는다.
- 진단에 쓰는 줄은 모두 INFO 이상이다. DEBUG를 켜지 않아도 가를 수 있다.

## 2. 로그 레벨과 경계 로그

### 2-1. 레벨 기준 [사용자 결정]

| 레벨 | 뜻 |
| --- | --- |
| ERROR | 확인된 실패 |
| WARN | 정상 경로 이탈. 실패로 단정할 수 없다(결과 모름 포함) |
| INFO | 정상 사건 |
| DEBUG | 재현용 세부 |

- 설계 23절의 "시도한 providerId와 결과 분류는 INFO 로그에 남긴다" 문장은 이 기준으로 대체한다. create 결과 모름(Unknown)·미발송(NotSent)은 WARN이다. 받음(Accepted)·효과 없는 거절(RejectedNoEffect)은 INFO다.
- PG가 FAILED로 확정한 결제(카드 거절 등)는 우리 시스템의 실패가 아니다. INFO에 `result=FAILED`로 남긴다.

### 2-2. 경계 로그 (logger = `OrderApplicationService`)

| 경계 | 레벨 | 메시지 값 |
| --- | --- | --- |
| 결제 시작(TX A commit 뒤, 새로 시작한 경우만) | INFO | orderId, providerId |
| create 받음 | INFO | orderId, providerId, result=Accepted, status |
| create 효과 없는 거절 | INFO | orderId, providerId, result=RejectedNoEffect |
| create 결과 모름 | WARN | orderId, providerId, result=Unknown, reason |
| create 미발송 | WARN | orderId, providerId, result=NotSent, reason |
| 반영: 확정으로 처음 바뀜 | INFO(commit 뒤) | orderId, source |
| 반영: 결제 실패로 처음 바뀜 | INFO(commit 뒤) | orderId, result(FAILED·NOT_SENT, 4-2와 같은 분류), providerStatus, source |
| 반영: 이미 같은 결과(UNCHANGED) | 줄 없음 | create·판정 줄이 이미 남긴다 |
| 반영: 종결 주문에 반대 결과(CONFLICT) | WARN | orderId, status, providerStatus |
| confirm 반영 TX 실패 | ERROR | orderId, providerStatus, causeType |
| 수동 reconcile(본인·관리자)·callback 반영 TX 실패 | ERROR(Scope 안) | orderId, source(MANUAL·CALLBACK), causeType. 원문·스택 없음 |
| 조회 판정 Verified | INFO | orderId, source, providerStatus |
| 조회 판정 Uncertain | WARN | orderId, source, kind, summary, reason(조회 실패일 때) |
| 조회 판정 Review | WARN | orderId, source, kind, summary |
| 조회 회로 정책으로 미호출 | INFO | orderId, providerId, source |
| 알 수 없는 주문의 callback | WARN | 메시지 값 없음. providerOrderId는 MDC에만 있다 |
| 만료 종결 | INFO(즉시) | orderId |
| 자동 재조회·만료 건별 실패 | ERROR | orderId, causeType |

- 확정·실패 종결 INFO는 commit 뒤에만 남긴다. 반영 TX가 rollback되면 "확정했습니다" 줄도 없다.
- **[구현 판단]** adapter(`TestPgPaymentGateway`)의 create·조회 실패 줄은 DEBUG다. 같은 사실을 application 한 줄이 reason과 함께 남긴다. 같은 사건을 두 번 찍지 않기 위해서다.
- **[구현 판단]** adapter 줄의 이름은 `providerOrderId={}`다. 값이 PG 주문 번호이기 때문이다.
- **[구현 판단]** 메시지 안 `orderId={}`는 MDC와 별도로 유지한다. local·test 콘솔 패턴은 업무 MDC 키를 보여 주지 않는다.
- 예외는 종류(SimpleName)만 남긴다. 카드 번호·인증 헤더·예외 원문은 남기지 않는다.
- **[사용자 승인 w10s1b-1 안 B]** 수동 reconcile·callback의 반영 TX(조회 판정 뒤 잠금·저장) 실패는 Scope 안에서 위 ERROR 한 줄로 남긴다. 그 뒤 같은 예외에 `common.error.AlreadyLogged` 표식을 suppressed로 붙여 그대로 다시 던진다. `ApiControllerAdvice`의 일반 ERROR와 `CommerceExceptionAdvice`의 잠금 실패 WARN은 표식이 있을 때만 로그를 건너뛴다. 같은 사건을 두 번 찍지 않기 위해서다. 응답 코드·본문과 예외 타입은 그대로다. 표식 없는 다른 오류는 지금처럼 advice가 원문·스택과 함께 남긴다. 자동 재조회는 기존 건별 ERROR만 남긴다. PG 조회 실패처럼 반영 TX 전에 끝나는 경우는 이 줄을 남기지 않는다.

## 3. MDC 업무 열쇠 [사용자 결정]

- 닻은 `orderId`다. confirm과 reconcile은 다른 요청이라 traceId만으로는 잇지 못한다.
- 함께 넣는 키: `attemptKey`, `providerOrderId`, `transactionKey`. `transactionKey`는 알게 된 뒤에만 넣는다.

| 진입점 | 넣는 시점 |
| --- | --- |
| confirm | 진입 즉시 orderId. TX A 뒤 attemptKey·providerOrderId. create가 Accepted면 transactionKey |
| callback | 진입 즉시 providerOrderId만. 결제 기록을 찾은 뒤 orderId·attemptKey |
| 본인·관리자 reconcile | 진입 즉시 orderId. 결제 기록을 읽은 뒤 attemptKey·providerOrderId |
| 자동 재조회 | 주문마다 열고 닫는다. 건별 try/catch 바깥이다 |
| 만료 | 주문마다 열고 닫는다 |
| 조회(공통) | 저장된 거래 키(create Accepted로 받아 저장한 키)가 있으면 진입 때 그 값. 없으면 조회로 찾은 거래의 키를 판정이 Verified일 때만 |

- **[구현 판단]** `order/app/PaymentLogContext.Scope`(try-with-resources)가 열 때 네 키의 이전 값을 저장하고 비운다. 닫을 때 이전 값으로 복원한다. 없던 키는 지운다. confirm이 안에서 결과 반영을 부르듯 진입점이 겹쳐도 바깥 문맥이 지켜지고, 스레드 풀에 키가 남지 않는다.
- adapter는 호출한 스레드에서 동기로 돈다. 그래서 MDC를 그대로 물려받는다. adapter 시그니처는 바꾸지 않았다.
- 확인 사실: HTTP 요청 스레드 MDC에는 `traceId`·`spanId`가 이미 들어 있다(테스트 실행으로 확인).
- dev·qa·prd 로그는 logstash JSON(Boot structured logging)이다. MDC 키마다 최상위 JSON 필드로 나간다.
- 조회로 찾은 거래 키는 판정이 Verified(우리 주문·금액과 일치)일 때만 MDC `transactionKey`로 넣는다. 불일치·복수·0건(Uncertain·Review)이면 넣지 않는다. 남의 거래 키가 이 주문의 것처럼 찍히지 않게 하기 위해서다(w10s1b 1-1). 판정 WARN에는 kind·summary만 남는다.

## 4. 지표

### 4-1. 미확인 결제 gauge `commerce.payment.unresolved`

- 태그 `status`: send_started · pending · unknown · needs_review. 네 시계열을 시작부터 등록한다.
- 대상: 주문이 PAYMENT_PENDING인 PG 결제 기록 수를 provider_status별로 센다.
- DB를 주기 조회한다. 주기는 `commerce.payment.unresolved-metric.refresh-interval: 15s` **[임시]**다. 값은 주기만큼 늦다. test 프로필에서는 갱신 스케줄러가 꺼져 있다.
- 첫 갱신 전 값은 NaN이다. 0이면 "미확인 없음"으로 읽히기 때문이다 **[구현 판단]**.
- 갱신이 실패하면 마지막 값을 유지하고 WARN(causeType)을 남긴다 **[구현 판단]**.
- 갱신 스케줄은 전용 단일 스레드(`sched-unresolved-metric-`)에서 돈다(7절). 결제 조회·relay가 길게 막혀도 갱신은 밀리지 않는다(재조회와 relay를 함께 막아 둔 테스트로 확인). 단 DB 연결 풀은 함께 쓰므로 풀이 고갈되면 갱신도 늦어질 수 있다.
- 인스턴스마다 같은 값을 낸다. 여러 인스턴스는 합하지 말고 max로 본다.
- orderId·userId·traceId는 태그로 넣지 않는다.

### 4-2. 결제 종결 결과 counter `commerce.payment.outcome` [사용자 결정]

- 태그 `result`: confirmed · failed · not_sent · expired. 네 시계열을 시작부터 등록한다(0도 시계열이 있다). orderId는 태그로 넣지 않는다.
- `failed`: PG가 거래를 만들고 FAILED로 확정한 경우(카드 거절 등)만이다.
- `not_sent`: 거래가 생기기 전에 끝난 실패다. 모든 PG 미발송(ALL_NOT_SENT)과 효과 없는 거절(REJECTED_NO_EFFECT)이다. 돈이 움직이지 않았다 **[사용자 결정 w10s1-3 B, REJECTED_NO_EFFECT 위치는 PL 결정]**.
- 나눈 이유: 거절 급증(`failed`)이 카드사·PG 판정 문제만 가리키게 하기 위해서다. 실패 종결 INFO의 `result`도 같은 분류다(`FAILED`·`NOT_SENT`).
- 주문이 종결로 처음 바뀔 때만 1 늘린다. 같은 결과 재확인(UNCHANGED)·반대 결과(CONFLICT)·확인 필요 유지·재조회 반복은 세지 않는다.
- commit 뒤에만 센다. rollback된 종결을 세지 않기 위해서다. 활성 트랜잭션 없이 부르면 예외로 막는다.
- 차이: 만료 종결 INFO는 commit 뒤가 아니라 즉시 남긴다. 만료 TX가 rollback되면 만료 INFO만 남을 수 있다. counter는 commit 뒤에만 센다.
- 목적은 거절 급증(PG·카드사 이상) 감지다. 이 counter를 쓰는 알림 규칙은 9-2절이다.

## 5. 테스트 PG fixture 사고 제어 (test 소스)

- `TestPgServer.loseResponseAfterSuccess(providerOrderId)`: 그 주문의 create를 SUCCESS로 저장한 뒤 응답을 늦춘다. 클라이언트 read timeout(500ms)을 넘겨 결과 모름이 된다. `clearOrderBehavior`로 해제한다. 지정하지 않은 주문은 기존 전역 동작을 따른다.
- **[구현 판단]** 유실은 연결 끊기가 아니라 지연으로 만든다. 진단 기준이 create timeout이기 때문이다.
- create는 요청을 받은 순간 주문별로 센다(오류 응답이어도 센다). `createCalls(providerOrderId)`, `duplicateCreateOrderIds()`(2회 이상)로 중복 create를 잡는다.
- fixture에는 callback을 보내는 기능이 없다. callback은 테스트가 직접 호출한다.

## 6. 한계

- **[사용자 결정]** 분산 추적은 구현하지 않음. 요청 간·서비스 간 끊기는 구간은 확인하지 않았다.
- 수동 reconcile·callback의 반영 TX 실패는 Scope 안 ERROR 한 줄(orderId MDC, source, causeType, 원문·스택 없음)로 남는다. `AlreadyLogged` 표식 때문에 두 advice는 같은 사건을 다시 기록하지 않는다. 응답·예외 타입은 그대로이고, 표식 없는 오류는 기존대로 기록된다(2-2, w10s1b-1 안 B). 그래서 원문·스택은 이 경로의 로그에 없다.
- callback의 providerOrderId는 인증 없는 외부 입력이다. 형식·길이 검사 없이(빈 값만 거절) MDC에 들어간다. 메시지 본문에는 넣지 않는다.
- `payment_records.provider_order_id`에는 인덱스가 없다. callback의 결제 기록 조회는 전체 탐색이 될 수 있다. 이번 단계에서는 고치지 않는다.

## 7. 스케줄 작업 스레드 [사용자 결정]

- 8주차 설계 28절의 "스케줄러 풀 2"를 **작업마다 전용 단일 스레드 스케줄러**(격벽)로 대체한다. 한 작업이 PG timeout·broker 대기로 길게 막혀도 다른 작업은 제 주기에 돈다. 8주차 문서는 고치지 않는다.

| 작업 | 스케줄러 빈 | 스레드 이름 접두사 |
| --- | --- | --- |
| 결제 자동 재조회·만료(`PaymentLookupScheduler`) | `paymentLookupTaskScheduler` | `sched-payment-lookup-` |
| Outbox relay(`OrderOutboxRelayScheduler`) | `outboxRelayTaskScheduler` | `sched-outbox-relay-` |
| 미확인 결제 gauge 갱신(`UnresolvedPaymentMetricsScheduler`) | `unresolvedMetricTaskScheduler` | `sched-unresolved-metric-` |

- 앞으로 생길 스케줄 작업(대사·환불 등)도 같은 규칙이다. 전용 스케줄러를 하나 더 만들고 `@Scheduled(scheduler = ...)`로 붙인다.
- **[구현 판단]** 빈은 `order/config/OrderSchedulingConfig`가 작업 켜기 설정과 무관하게 항상 만든다. 스레드는 첫 작업이 들어올 때 만들어지므로 작업이 꺼져 있으면 그 스케줄러의 스레드도 없다. Boot `ThreadPoolTaskSchedulerBuilder`로 만들어 `spring.task.scheduling.shutdown.*` 설정이 빈마다 적용된다. 종료 대기는 Boot 기본(기다리지 않음)이다. 이 빈들이 있어 Boot 기본 `taskScheduler`는 만들어지지 않고, `spring.task.scheduling.pool.size` 설정은 지웠다.
- 이 빈들은 `Executor`이기도 해서 Boot 기본 `applicationTaskExecutor`도 만들어지지 않는다(`@ConditionalOnMissingBean(Executor.class)`, 테스트 컨텍스트의 조건 평가 보고서로 확인). 지금은 `@Async`·`@EnableAsync`·비동기 MVC를 쓰지 않아 영향이 없다. 앞으로 `@Async`를 쓰면 executor 빈을 따로 둬야 한다.
- 남는 공유 자원: DB 연결 풀과 PG는 작업끼리 여전히 나눠 쓴다.

## 8. 사고 연결 테스트 증거 [사용자 결정]

- 테스트: `apps/commerce-api/src/e2eTest/.../order/in/CheckoutIncidentE2ETest.java`. 같은 DB·같은 PG 거래로 정상 10건 → 응답 유실 3건 → 증거 보존 → 가설 판정 → 본인 reconcile 복구 → 반복·보호 → 구매 활동·주간 랭킹을 잇는다.
- 실행 증거는 `build/incident-evidence/<runId>/`에 실행마다 새로 쌓인다(빌드 산출물이라 `clean`이면 지워진다).
- 제출용 한 벌: `docs/week10/evidence/20261125T082202Z-068161dd/`(커밋 873d848의 깨끗한 실행, 실패 없음). 단계별 JSON 6개와 `SHA256SUMS`가 있다.
  - `diagnosis.json` 판정: 유실 주문 11·12·13 모두 H1(응답 유실) 채택, H2(내부 반영 실패)·H3(PG 실패·미발송) 기각.
  - `fault.json` sha256 `759ae143…5e8f28`(복구 뒤에도 같음을 테스트가 단언).
  - 카드번호·인증 헤더·비밀값 문자열 없음(복사 전 검사).

## 9. 운영 도구(4단계 4a)

### 9-1. 고리에서 각 장치의 칸

| 칸 | 장치 |
| --- | --- |
| ① 감지 | gauge `commerce.payment.unresolved`(4-1), counter `commerce.payment.outcome`(4-2), 새 counter `started`·`unknown_entered`(9-3), 알림 규칙(9-2) |
| ② 안내 | Alertmanager 라우팅(9-2), Grafana 대시보드 `commerce-payment-incident`, runbook 앵커 `docs/runbook/week10.md` |
| ③ 진단 | 관리자 미확인 결제 목록 API(9-5), 경계 로그(2-2) |
| ④ 조치 | 관리자 고정 목록 일괄 조회 확정 API(9-6) |
| ⑤ 확인 | 일괄 응답의 주문별 전/후 상태, 대시보드(gauge 감소·알림 해제) |

- 대시보드 패널: 미확인 결제 상태별 · 시작 대비 결과 모름 유입 비율(30분) · 종결 결과별 증가(10분) · 만료 종결 증가(10분) · PG 결제 시작 수(10분) · 결제 알림 상태(ALERTS).

### 9-2. 알림 규칙과 라우팅

규칙은 `docker/prometheus/rules/payment.yml`, 모양은 `payment_test.yml`(promtool test rules)로 고정한다. 수치는 모두 **[임시]**다 **[사용자 결정 Q2: 그대로]**. label에는 `severity`만 붙이고 orderId·userId는 없다. annotation은 summary·runbook_url·dashboard_url이다.

| 규칙 | severity | 식(요지) | [임시] 값 | 근거 |
| --- | --- | --- | --- | --- |
| `PaymentUnresolvedPiling` | critical | `increase(unknown_entered[30m]) / clamp_min(increase(started[30m]),1) > X` AND `max(unresolved{status="unknown"}) ≥ N`, `for: M` | X=0.2%, N=20, M=10m | 피크 1만 건 중 50건(0.5%)은 울리고 새벽 10건 중 2건은 건수 미달로 안 울린다. M은 자동 재조회로 풀리는 일시 증가를 거른다 |
| `PaymentAbandonedPiling` | critical | `increase(outcome{result="expired"}[10m]) ≥ K`, `for: 10m` | K=5 | 만료는 돈이 움직였을 수 있는 주문을 우리가 포기한 것이다. 10분에 5건이면 우연이 아니다 |
| `PaymentNeedsReview` | warning | `max(unresolved{status="needs_review"}) > 0`, `for: 15m` | 15m | 자동으로 풀리지 않는 사람 확인 상태다. 깨우지는 않는다 |
| `PaymentDeclineSurge` | warning | failed 비율(종결 중) > 30% AND `increase(failed[10m]) ≥ 10`, `for: 5m` | 30%, 10건, 5m | 카드사·PG 판정 이상. 건수 조건이 소량 시간대의 우연한 거절을 거른다 |
| `PaymentPgUnreachable` | warning | `increase(not_sent[10m]) ≥ 5`, `for: 5m` | 5건, 5m | PG에 요청이 닿지 않은 것이라 거절과 원인이 달라 규칙을 나눈다 |

- **[사용자 결정 D, 계획 수정 1]** Piling의 "증가 중"은 gauge `deriv`가 아니라 유입 counter로 잰다. 만료(2분)가 미확인을 빼 가서, PG가 길게 죽으면 유입과 만료가 비슷해져 gauge가 높은 채 평평해진다. 그러면 `deriv > 0`이 거짓이 되어 가장 심각할 때 울리지 않는다. 같은 이유로 만료 누적을 잡는 Abandoned를 새로 두었다.
- **[구현 판단]** 유입 0이고 unknown만 남은 정체는 Piling이 울리지 않는다. 새 유입이 없으면 자동 재조회·만료가 처리 중이다. 만료가 쌓이면 Abandoned가 잡는다.
- 5단계 환불 규칙은 주석 자리만 있다.

라우팅(`docker/alertmanager/alertmanager.yml`):

| severity | 수신처 | repeat_interval [임시] |
| --- | --- | --- |
| critical | `guide-webhook`(지금은 가짜 수신기 `/guide`, 4b에서 가이드 서버로 교체) | 1h |
| warning | `slack`(로컬은 가짜 수신기 `/slack`) | 4h |
| 없음 | `slack`(기본 수신처) **[구현 판단: 깨우지 않는 쪽]** | 4h |

- `group_by: [alertname, severity]`, `group_wait 30s`, `group_interval 5m`.
- 이미지는 버전을 고정했다: `prom/prometheus:v3.5.0`(promtool과 버전 일치), `prom/alertmanager:v0.28.1`, 가짜 수신기 `python:3.12.11-slim` + `docker/alertmanager/fake-receiver/receiver.py`.
- Prometheus(`docker/grafana/prometheus.yml`)에 `rule_files`와 `alerting`(alertmanager:9093)을 연결했다.

### 9-3. 새 counter

| 이름 | 태그 | 증가 시점 |
| --- | --- | --- |
| `commerce.payment.started` | `provider` | confirm의 TX A가 commit된 뒤, 새로 시작한 경우(`newlyStarted`)만 1. 같은 시도의 재요청은 세지 않는다 |
| `commerce.payment.unknown_entered` | `provider` | 결제 기록이 UNKNOWN이 아닌 상태에서 UNKNOWN으로 바뀐 반영이 commit된 뒤 1. 이미 UNKNOWN인 기록의 재보고는 세지 않는다 |

- 둘 다 afterCommit이다. rollback된 변경은 세지 않는다. orderId는 태그에 없다.
- **[구현 판단, PL 승인]** 시작 시 PG 어댑터 빈(`PaymentGateway`)의 providerId마다 0으로 등록한다. 등록되지 않은 providerId가 오면 예외 대신 그 자리에서 등록해 센다. commit 뒤라 예외를 내면 안 되기 때문이다.
- 위치: `order/out/MicrometerPaymentProgressRecorder`(포트 `order/app/port/PaymentProgressRecorder`), 호출은 `OrderApplicationService`.

### 9-4. 관리자 HTTP Basic [사용자 결정 Q1 ①]

- local·dev 프로필에서만 `commerce.admin.basic-auth.enabled: true`다. 계정은 환경 변수 `COMMERCE_ADMIN_USERNAME`·`COMMERCE_ADMIN_PASSWORD`로만 받고 기본값이 없다.
- 계정이 비어 있으면 인증 수단을 붙이지 않는다. 관리자 API는 전부 403이다.
- 비밀번호는 시작 때 bcrypt(Spring 위임 인코더 기본)로 인코딩해 메모리에 둔다. 부모 AuthenticationManager는 두지 않는다. Boot 기본 사용자로 관리자 인증이 넘어가지 않게 하기 위해서다.
- **[구현 판단]** Boot 기본 `UserDetailsServiceAutoConfiguration`은 commerce-api에서 제외한다(`CommerceApiApplication`). 시작 로그에 생성 비밀번호(`Using generated security password`)가 찍히지 않게 하기 위해서다(비밀값 출력 금지). 테스트로 고정한다.
- 응답: 헤더 없음 → 403, 틀린 자격 증명 → 401, 맞는 자격 증명 → 통과. 고객 API에는 영향이 없다.
- prd·qa 프로필에서 `enabled`가 켜지면 시작이 실패한다(`IllegalStateException`).
- CSRF는 그대로다. 그래서 사람이 Basic만으로 관리자 POST(일괄 확정)를 보내는 방법은 **미정**이다(questions w10s4-5 대기). GET 목록은 Basic만으로 된다.
- 위치: `common/config/AdminBoundaryConfig`, `AdminBasicAuthProperties`, `application.yml` local·dev 블록.

### 9-5. 관리자 미확인 결제 목록 API

- `GET /api-admin/v1/payments/unresolved?status=&minAgeSeconds=&page=&size=`
- 대상: 주문이 PAYMENT_PENDING인 PG 결제 중 provider_status가 SEND_STARTED·PENDING·UNKNOWN·NEEDS_REVIEW인 것. `status`를 생략하면 네 개 전부다(여러 개 지정 가능).
- `minAgeSeconds`(기본 0): 결제 시작이 그만큼 지난 것만. 경계값(같음)은 포함한다.
- 정렬: `started_at` 오름차순, 같으면 orderId. `page`(0)·`size`(20, 최대 100)는 기존 관리자 관례다.
- 응답 항목: orderId, providerOrderId, providerId, providerStatus, startedAt, elapsedSeconds, lookupAttempts·nextLookupAt(자동 조회 schedule, 없으면 null), latestAnomaly(해결되지 않은 이상 중 가장 최근의 kind·summary·lastSeenAt, 없으면 null).
- 카드·사용자 정보(userId 포함)는 없다.
- **[구현 판단, PL 결정]** 이 응답과 일괄 응답만 null도 내보낸다(`@JsonInclude(ALWAYS)`). 운영자가 "값 없음"과 "필드 없음"을 구분하게 하기 위해서다. 전역 설정은 null 생략이다.
- **[PL 결정 P5]** 스키마는 바꾸지 않았다. "최근 조회 결과"는 저장하는 곳이 없어 schedule·이상의 기존 값만 보여 준다.
- 오류: size 상한 초과·음수 page·음수 경과 시간·네 상태 밖의 값 → 400. 비관리자·미식별 → 403.
- 위치: `order/in/AdminUnresolvedPaymentController` → `order/app/UnresolvedPaymentQueryService` → `order/out/JdbcUnresolvedPaymentFinder`(JdbcTemplate).

### 9-6. 관리자 고정 목록 일괄 조회 확정 API

- `POST /api-admin/v1/payments/reconcile-batch`, 본문 `{ "orderIds": [..] }`. 사람이 고정한 목록만 받는다. 필터·전체 선택은 없다.
- **한도 [임시]:** 원 목록 길이(중복 포함) 1~50건. 빈 목록·누락·양수 정수가 아닌 값·51건 이상 → 400이고 하나도 처리하지 않는다. 중복 orderId는 처음 위치에서 한 번만 처리한다.
- **간격 [임시]:** PG를 조회하는 주문 사이 200ms(초당 5건). 조회하지 않는 항목(이미 종결·대상 아님) 앞에서는 기다리지 않는다. test 프로필은 0ms다.
- **시작 전 회로 판단:** 목록 주문의 PG 결제 provider 중 하나라도 조회 회로가 "열림이고 열림 대기(open-duration)가 남음"이면 503이고 하나도 처리하지 않는다(create·lookup 0). 대기가 지났으면 다음 조회가 탐색 호출이 되므로 시작한다. **[구현 판단, PL 결정]** 자동 전환이 꺼져 있어 대기가 지나도 상태가 OPEN으로 남기 때문에 열린 시각을 따로 기록해 판단한다(`PaymentCircuitBreakers.blocksLookup`).
- **주문마다:** 이미 종결·대상 아님은 PG를 조회하지 않는다. 결제 대기만 수동 reconcile과 같은 경로(`reconcileForOperator` → `verifyPayment`)를 탄다. NEEDS_REVIEW도 수동 reconcile과 같게 다룬다. create는 부르지 않는다(테스트로 고정).
- 응답: `requested`(중복 제거 건수), `processed`(SKIPPED 제외), `stopReason`, 주문별 `orderId·beforeStatus·afterStatus·beforeProviderStatus·afterProviderStatus·assessment·outcome·reason`.

| outcome | 뜻 | reason 값 |
| --- | --- | --- |
| RESOLVED | 판정 Verified이고 종결됨 | null |
| STOPPED | 성공으로 바꾸지 않고 멈춤 | `STILL_PENDING`(Verified인데 계속 대기), 판정 kind(Uncertain: `LOOKUP_FAILED` 등 / Review: `TRANSACTION_MISMATCH` 등), `LOOKUP_NOT_CALLED`(회로로 미호출), `NOT_ASSESSED`(잠금 아래에서 판정하지 않음), `PROCESSING_FAILED`(반영 예외, 그 주문만 멈추고 계속) |
| ALREADY_CLOSED | 이미 종결 | null |
| NOT_APPLICABLE | 대상 아님 | `NOT_FOUND`, `NOT_PG` |
| SKIPPED | 중단으로 미처리 | `CONSECUTIVE_FAILURES` |

- `assessment`: VERIFIED·UNCERTAIN·REVIEW·NOT_CALLED 또는 null.
- **연속 실패 [임시 3건]:** 조회 실패(Uncertain `LOOKUP_FAILED`)와 도중 회로 미호출(NOT_CALLED)만 센다. RESOLVED·ALREADY_CLOSED가 나오면 0으로 돌아간다. 나머지 결과는 세지도 되돌리지도 않는다. 3건에 닿으면 남은 주문은 PG 조회 없이 SKIPPED다 **[PL 결정]**.
- **재진입:** 같은 목록을 다시 보내면 모두 ALREADY_CLOSED이고 주문·결제 상태·Outbox·종결 counter·PG 호출이 그대로다.
- 새 사유 값은 응답 문자열로만 있다. 도메인 enum을 늘리지 않았다 **[PL 결정]**.
- 감사: 시작·끝을 INFO 한 줄씩 남긴다(requester, 건수, 결과 집계, stopReason). 시작 거절은 WARN(requester, providerIds). 주문별 줄은 기존 경계 로그(2-2)다.
- 위치: `order/in/AdminPaymentReconcileBatchController` → `order/app/PaymentReconcileBatchService`(정책 `ReconcileBatchPolicy`, 설정 `payment.reconcile-batch.*`, 대기 포트 `ReconcilePacer` → `order/out/SleepingReconcilePacer`), 회로 포트 `payment/app/port/PaymentLookupCircuitState`.

### 9-7. 검사 스크립트 `scripts/ops/check-alerting.sh`

- 단계: promtool `check rules`·`check config` → promtool `test rules`(실행 0건·선언과 실행 수 불일치·실패·skip이면 실패) → amtool `check-config`와 라우팅 3건(critical·warning·severity 없음) → 대시보드 JSON·datasource uid·`dashboard_url`·runbook 앵커 일치.
- Docker로 도구 이미지를 `--network none`으로 돌린다. **[사용자 결정 Q4]** gradle check에는 넣지 않는다(Docker 필요). review에 실행 결과를 붙인다.

### 9-8. 한계

- 수집 대상은 api 하나(`host.docker.internal:8081`)다 **[PL 결정 P2]**. 세 앱의 management 포트가 모두 8081이고(공통 `monitoring.yml`), streamer의 `spring.application.name`이 `commerce-api`다. 앱별 정리는 범위 밖이다.
- runbook(`docs/runbook/week10.md`)은 규칙별 앵커와 뜻만 있다. 대응 본문은 아직 없다.
- `runbook_url`은 저장소 상대 경로, `dashboard_url`은 `http://localhost:3000` 고정이다.
- 실제 Slack은 연결하지 않았다. Alertmanager는 설정 파일에서 환경 변수를 펼치지 않으므로 운영은 `slack_configs`의 `api_url_file`로 비밀 파일을 읽는 방식이다. 주석만 있고 구현하지 않았다.
- 새 counter 두 개가 실제 앱에서 Prometheus로 수집되는지는 앱을 연결해 확인하지 않았다. `unknown_entered`의 PENDING→UNKNOWN 경로 테스트가 없다.
- 관리자 POST를 사람이 보내는 방법(CSRF)이 미정이다(9-4).
- 목록 쿼리 계획 **[구현 판단]**: 깨끗한 HEAD 복사본의 임시 테스트(MySQL 8.0.46, analyze 후)에서 확인했다. 주문 1만 건(결제 대기 100건·나머지 확정), 결제 기록 1만, schedule 100, 이상 234(미해결 34)다. 실제 `JdbcUnresolvedPaymentFinder` SQL, page 0·size 20.

| 표 | type | key | rows | Extra |
| --- | --- | --- | --- | --- |
| o(orders) | ref | idx_orders_status_confirmed_at | 100 | Using index; Using temporary; Using filesort |
| p(payment_records) | eq_ref | uk_payment_records_order_id | 1 | Using where |
| s(schedules) | eq_ref | PRIMARY | 1 | — |
| a(anomalies) | eq_ref | PRIMARY | 1 | Using where |
| latest(종속 부분 질의) | ref | uk_payment_anomalies_order_kind | 1 | Using where; Using filesort |

  - 필터 없음과 `status=UNKNOWN`의 계획이 같다. 상태 조건은 p를 고유 키로 읽은 뒤 거른다. 실측 0.7ms·0.3ms다.
  - 80건 규모에서 보였던 payment_records 전체 탐색은 1만 건 규모에서 사라졌다. orders 상태 인덱스 선두 경로가 나온다.
  - 정렬(temporary·filesort)은 결제 대기 주문 전체 위에서 한다. 비용은 전체 주문 수가 아니라 결제 대기 건수와 page 깊이에 비례한다. 대기 건수가 수만 건으로 늘 때의 계획은 확인하지 않았다.
