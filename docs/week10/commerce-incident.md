# Week 10 — 결제 사고 관측

이 문서는 10주차 결제 사고 대응의 결정을 적는다. 1~7절은 1단계(관측과 테스트 PG fixture 사고 제어), 8절은 사고 연결 테스트, 9절은 4단계 4a 운영 도구, 10절은 4b 운영 도구 확장, 11절은 5단계 늦은 성공 자동 환불, 12절은 검증 범위·남은 위험이다.
나머지 설계(1~29절)는 `docs/week8/commerce-coupon.md`를 따른다.

> 이 문서의 검증은 로컬·테스트 환경 검증이며 운영 배포·운영 완료가 아니다(12절).

- **[사용자 결정]**: 사용자가 정한 것(결정 기록 week10). 바꾸려면 승인이 필요하다.
- **[참모 지시]**: 참모 지시문·고정 규칙이다. 바꾸려면 참모 확인이 필요하다.
- **[구현 판단]**: 사용자 결정 범위 안에서 AI(PL·DEVELOPER)가 정한 내부 선택이다. 근거와 함께 적고 뒤집을 수 있다.
- **[가정]**: 과정 PG 계약에 없어 우리가 정한 외부 계약이다(11절 취소 API).

## 1. 진단 기준 [사용자 결정]

- 원인은 경계 기록으로 가른다. 두 사고 모두 health는 정상이다.
- **응답 유실:** create 결과 모름 WARN 한 줄(`result=Unknown`, reason에 timeout 예외 이름) + 그 뒤 반영 줄 없음. 주문은 결제 대기·`UNKNOWN`으로 남는다.
- **내부 반영 실패:** 조회 판정 INFO(`result=Verified`, `providerStatus=SUCCESS`) + 반영 실패 ERROR(`causeType`=예외 종류). 주문은 결제 대기로 남는다.
- 진단에 쓰는 줄은 모두 INFO 이상이다. DEBUG를 켜지 않아도 가를 수 있다.

**가설별 예상 / 실제 / 부족한 증거** — 사고 E2E(8절)가 유실 주문마다 `diagnosis.json`의 `judgments[].hypotheses[]`에 같은 세 칸(`expectedEvidence`·`verdict`·`missingEvidence`)을 남기고, `missingEvidence`가 비어 있지 않음을 단언한다. 부족한 증거 문장은 테스트 문장 그대로다.

| 가설 | 예상(이 가설이면 보일 것) | 실제(사고 E2E, 유실 주문마다) | 부족한 증거(보지 못한 것) |
| --- | --- | --- | --- |
| H1 응답 유실: PG는 성공했지만 create 응답을 받지 못했다 | create Unknown WARN(HttpTimeoutException) 1, 반영 ERROR 0, PG 조회 SUCCESS·금액 일치 | 채택. 세 조건 모두 맞음 | PG가 요청을 받고 응답을 보냈다는 것은 test-pg 내부 상태(createCalls·거래 SUCCESS)로만 봤다. 실제 PG 로그는 없다 / 응답이 어디서 끊겼는지 네트워크 계층(패킷·프록시·LB 로그)은 보지 않았다. 근거는 우리 쪽 HttpTimeoutException뿐이다 |
| H2 내부 반영 실패: PG 결과를 받거나 조회했지만 우리 DB 반영이 실패했다 | create Accepted INFO 또는 조회 Verified INFO가 있고 반영 ERROR가 있다 | 기각. 반영 ERROR 0 | "반영 ERROR 0"은 MDC orderId로 거른 캡처 로그에 ERROR가 없다는 부재 판단이다. 로그 유실·MDC 누락이면 구분할 수 없다 / DB 쪽 오류 기록(MySQL error log·rollback 통계)은 보지 않았다 |
| H3 PG 실패·미발송: 돈이 움직이지 않았다 | PG 거래가 없거나 FAILED, 또는 create NotSent WARN | 기각. PG 조회 SUCCESS·금액 일치, NotSent WARN 0 | 돈이 움직였다는 근거는 PG 조회 SUCCESS·금액 일치뿐이다. PG 정산 파일·카드사 승인 내역 대조는 없다 / "NotSent WARN 0"도 캡처 로그 기준 부재 판단이다 |

- 실제 칸은 `CheckoutIncidentE2ETest`의 단언(H1 참, H2·H3 거짓)이다. 최종 HEAD로 다시 뽑은 evidence 경로는 12-1에 적는다.

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
| `PaymentApiLatencyP99High` | warning | 결제 확정·reconcile 경로(고객 confirm·고객 reconcile·관리자 reconcile) `histogram_quantile(0.99, …[5m]) > 1s` AND 5m 요청 ≥ 100건, `for: 10m` | 1s, 100건, 10m | 정상 확정은 수십 ms다. PG read timeout 3s 앞에서 PG 지연·응답 유실을 의심할 수준이다. 건수 조건이 소량 시간대 흔들림을 거른다 **[사용자 결정 A, 참모 13:27]** |

- **[사용자 결정 D, 계획 수정 1]** Piling의 "증가 중"은 gauge `deriv`가 아니라 유입 counter로 잰다. 만료(2분)가 미확인을 빼 가서, PG가 길게 죽으면 유입과 만료가 비슷해져 gauge가 높은 채 평평해진다. 그러면 `deriv > 0`이 거짓이 되어 가장 심각할 때 울리지 않는다. 같은 이유로 만료 누적을 잡는 Abandoned를 새로 두었다.
- **[구현 판단]** 유입 0이고 unknown만 남은 정체는 Piling이 울리지 않는다. 새 유입이 없으면 자동 재조회·만료가 처리 중이다. 만료가 쌓이면 Abandoned가 잡는다.
- ~~5단계 환불 규칙은 주석 자리만 있다.~~ → 5단계 환불 규칙 3개를 더했다(11-8). 5b에서 두 축 모델 규칙 4개로 바꿨다(11-16).

라우팅(`docker/alertmanager/alertmanager.yml`):

| severity | 수신처 | repeat_interval [임시] |
| --- | --- | --- |
| critical | `guide-webhook`(가이드 서버) + `slack`(원본, `continue`) 둘 다(4b, 10-4) | 1h |
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
- **CSRF [사용자 결정 A, w10s4-5]:** 관리자 체인만 `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 쓴다. CSRF 확인 자체는 그대로다.
  - 관리자 GET 응답이 `XSRF-TOKEN` 쿠키(HttpOnly 아님)를 준다. 변경 요청은 그 값을 쿠키와 `X-XSRF-TOKEN` 헤더로 같이 보내야 통과한다.
  - 토큰이 없거나 헤더만 빠지거나 값이 다르면 맞는 Basic이어도 403이고 저장하지 않는다. 테스트로 고정한다(`AdminBasicAuthIntegrationTest$CookieCsrfTokenFlow`).
  - 처리기는 Spring Security 문서의 Single-Page Applications 권장 방식이다(`SpaCsrfTokenRequestHandler`, 6.4에는 `csrf.spa()`가 없다). 헤더 값은 쿠키 원값으로 비교하고, 그 밖의 토큰 노출·비교는 XOR(BREACH 방어)이다. 지연 토큰은 처리기에서 로드해 조회 응답에 쿠키를 싣는다.
  - Basic은 요청마다 인증하지만 인증 때 토큰을 다시 만들지 않는다. GET으로 받은 토큰을 다음 요청의 POST에 그대로 쓸 수 있다(테스트로 확인).
  - 쿠키 속성은 기본값이다 **[PL 결정]**: Path는 기본(컨텍스트 경로), SameSite 미지정, 로컬 http라 Secure 없음.
  - 별도 토큰 발급 API는 만들지 않았다(사용자 이유: 특정 상황만을 위한 API를 늘리지 않음).
  - 고객 API는 관리자 체인 밖이라 바뀌지 않는다. 응답에 `XSRF-TOKEN` 쿠키가 생기지 않고 CSRF 토큰 없이 그대로 동작한다.
- **래퍼 `scripts/ops/admin-curl.sh`:** 계정은 `COMMERCE_ADMIN_USERNAME`·`COMMERCE_ADMIN_PASSWORD`에서만 읽고 출력하지 않는다(curl 설정을 표준 입력으로 넘겨 프로세스 목록에도 남기지 않는다). 주소는 `COMMERCE_API_URL`(기본 `http://localhost:8080`).
  - GET은 Basic으로 그대로 보낸다. 변경 요청은 임시 쿠키 jar로 관리자 GET(`/api-admin/v1/payments/unresolved?size=1`)을 먼저 보내 토큰을 받고, 헤더와 쿠키로 붙여 보낸다. jar는 끝나면 지운다.
  - `/api-admin/` 밖의 경로는 거절한다.
  - 예: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=UNKNOWN&minAgeSeconds=300'`
  - 예: `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/reconcile-batch '{"orderIds":[101,102]}'`
- 위치: `common/config/AdminBoundaryConfig`, `SpaCsrfTokenRequestHandler`, `AdminBasicAuthProperties`, `application.yml` local·dev 블록.

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
- runbook(`docs/runbook/week10.md`)에는 규칙별 앵커, 가이드 메시지 읽는 법, 주문 번호로 경계 로그 찾기, AI 질문 예시가 있다(4b). 규칙별 대응 본문은 아직 짧다.
- `runbook_url`과 대시보드 패널 설명의 runbook 링크는 과제 최종 목적지인 원본 저장소 웹 주소(`https://github.com/loopers-labs/loop-pack-be-l2-vol5-java/blob/L2J4Dot/docs/runbook/week10.md#<앵커>`)다 **[PL 결정, w10s4b B4]**. PR이 머지되기 전에는 열리지 않을 수 있다. 리허설 규칙(`rules/rehearsal/`)은 상대 경로 그대로다. `dashboard_url`은 `http://localhost:3000` 고정이다.
- 실제 Slack은 연결하지 않았다. Alertmanager는 설정 파일에서 환경 변수를 펼치지 않으므로 운영은 `slack_configs`의 `api_url_file`로 비밀 파일을 읽는 방식이다. 주석만 있고 구현하지 않았다.
- ~~새 counter 두 개가 실제 앱에서 Prometheus로 수집되는지는 앱을 연결해 확인하지 않았다.~~ → 4b 리허설에서 확인(10-7). `unknown_entered`의 PENDING→UNKNOWN 경로 테스트가 없는 것은 그대로다.
- ~~래퍼 `admin-curl.sh`는 실제 앱에 붙여 실행하지 않았다.~~ → 4b 리허설에서 실제 앱에 붙여 GET 목록·POST 일괄(XSRF 쿠키 토큰) 모두 성공(10-7).
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

## 10. 운영 도구 확장(4단계 4b)

원칙 **[사용자 결정]**: critical은 근거를 밀어 주고(push), warning은 사람이 당겨 쓴다(pull). 가이드가 실패해도 원본 알림은 간다. 근거 수집은 결정적이고, LLM은 키가 있을 때만 쓴다. 비밀값은 저장소 밖에 둔다.

### 10-1. 고리에서 4b 장치의 칸

| 칸 | 4b 장치 |
| --- | --- |
| ② 안내 | critical 가이드 서버(10-4): 원본 알림에 근거를 붙인 두 번째 메시지 |
| ③ 진단 | local JSON 로그(10-2) → Alloy → Loki(10-3), 대시보드 패널 "주문 번호로 경계 로그", Grafana MCP(10-5), runbook 4b 절 |
| ① 감지(보조) | `GuideServerDown`(warning), 대시보드 패널 "가이드 서버 가동" |
| 리허설 수단 | test-pg 실행(10-6), 리허설 오버레이·`scripts/ops/rehearsal.sh`(10-7) |

### 10-2. local JSON 로그 파일

- `local`·`local-dev` 프로필에서만 JSON 파일 appender를 더한다. 콘솔은 그대로다. 다른 프로필은 바꾸지 않았다(`supports/logging/.../logback/logback.xml`).
- 형식: Boot structured logging `logstash`. MDC 키(orderId 등)는 최상위 필드로 나간다.
- 경로: 기본 `logs/<spring.application.name>.json`(api는 `logs/commerce-api.json`). `logging.file.name`이 있으면 그것을 쓴다. `logs/`는 git 무시다.
- 롤링 **[임시]**: 파일 10MB, 보관 3개, 합계 100MB. `logging.logback.rollingpolicy.*`가 있으면 그것을 쓴다(`appenders/json-file-appender.xml`).
- **[구현 판단, PL 결정]** `bootRun` 작업 폴더를 저장소 루트로 둔다(`apps/commerce-api/build.gradle.kts`, `workingDir = rootProject.projectDir`). 기본 경로가 상대 경로라 원래는 `apps/commerce-api/logs/`에 쌓여 Alloy가 못 읽었다(리허설 B1). 앱 설정의 다른 경로는 classpath라 영향이 없다.

### 10-3. Loki·Alloy

- **[PL 결정 P1]** 수집은 local JSON 파일 + Alloy다. logback Loki appender(앱 의존성 추가)는 쓰지 않는다.
- Alloy(`grafana/alloy:v1.10.1`, `docker/alloy/config.alloy`): 저장소 `logs/`를 읽기 전용으로 붙여 `*.json`을 읽는다.
  - label은 **`app`(파일 이름)·`level`만** 둔다. 값이 유한해야 색인이 늘지 않는다. `filename` label은 지운다.
  - **[PL 결정 P2]** structured metadata 4키: `orderId`·`providerOrderId`·`attemptKey`·`transactionKey`. label이 아니다. 찾기: `{app="commerce-api"} | orderId="11"`.
  - 로그 줄 본문은 바꾸지 않는다.
- Loki(`grafana/loki:3.5.3`, `docker/loki/loki.yml`): 단일 바이너리, 로컬 파일 저장, `127.0.0.1:3100`에만 연다.
  - 보존 **[임시] 72h**(compactor가 지운다). 로컬 진단용이라 사고 하루 뒤까지 되짚으면 충분하다.
  - Loki 3 기본의 `service_name` label·`detected_level` 자동 추가를 끈다. label을 app·level로 묶어 두기 위해서다.
  - 사용 통계 보고를 끈다.
- Grafana에 Loki 데이터소스(uid `loki`)를 provisioning했다. 대시보드에 "주문 번호로 경계 로그" 패널을 더했다.
- 검사: `check-alerting.sh`에 Alloy `validate` + label이 app·level뿐인지, Loki `-verify-config`를 더했다.

### 10-4. critical 가이드 서버

- **[PL 결정 P3]** `docker/guide/guide.py`. Python 표준 라이브러리만 쓴다(`python:3.12.11-slim`). 새 앱 모듈이 아니다. 호스트에 포트를 열지 않는다.
- 흐름: Alertmanager critical webhook(`guide:8080/alert`) → 근거를 결정적으로 수집 → (키가 있으면) LLM 요약 → Slack 자리(로컬은 가짜 수신기 `/slack`)로 전송.
  - Prometheus: 미확인 상태별, 결과 모름 유입 비율(30분), 만료 증가(10분). 규칙과 같은 창이다.
  - Loki: 지난 30분 `OrderApplicationService`의 WARN·ERROR 경계 로그. 종류별 건수와 대표 줄(최대 3줄). 카드·비밀값은 가린다.
  - 대시보드·runbook 링크.
- **원본 보장:** 근거 수집이 실패하거나 제한 시간 **[임시] 10s**를 넘으면 받은 원본 본문을 그대로, 실패 표시 한 줄과 함께 보낸다. 깨진 webhook 본문은 400이고 원본 전달을 시도한다.
- **[PL 결정 P4]** critical은 Alertmanager가 가이드와 Slack 자리 **둘 다**로 보낸다(`continue: true`). 가이드가 죽거나 늦어도 사람은 원본을 받는다. 4a 9-2의 critical 수신처(가짜 수신기 `/guide`)는 이것으로 바뀌었다.
  - 그래서 가이드가 실패하면 Slack 자리에 원본이 두 번 간다(Alertmanager 원본 + 가이드의 실패 표시 원본).
- **LLM [사용자 결정 w10s4b-1]:** 내부 식별자는 외부로 보내지 않는다. LLM에는 집계와 패턴만 보낸다.
  - 보내는 것: 알림 이름·상태·severity·건수, 지표 값, 경계 로그 종류별 건수와 대표 문구(값이 유한한 분류 키만 남기고 나머지 값은 가림), 볼 곳(패널 이름·runbook 앵커·Loki 쿼리 예시).
  - 보내지 않는 것: orderId·providerOrderId·attemptKey·transactionKey·userId·카드·비밀값, 로그 원문.
  - 전송 직전 확인: `LP-ORD-…`, `TR-…`, UUID, 식별자 키=값, 6자리 이상 숫자, 카드 형태, API 키 형태가 하나라도 남으면 LLM을 부르지 않는다(fail closed, `guide_llm_blocked_total`).
  - `ANTHROPIC_API_KEY`가 없으면 LLM을 부르지 않고 결정적 요약만 보낸다. 키는 셸 환경 변수로만 받는다. LLM 호출 실패도 결정적 요약은 간다.
  - 사람에게 가는 결정적 메시지의 대표 줄에는 주문 번호가 보일 수 있다(LLM에는 가지 않음).
- 생존 감시: `GET /metrics`가 `guide_up`과 처리·실패 counter(수신·근거 전송·원본 전송·잘못된 요청·Slack 실패·LLM 요청·실패·차단)를 낸다. Prometheus job `guide`가 수집한다.
  - `GuideServerDown`(warning, `up{job="guide"} == 0`, for **[임시] 5m**). `payment.yml`의 `commerce-ops` 그룹이다. critical 원본은 따로 가므로 깨우지 않는다.
- 테스트(`docker/guide/test_guide.py`, `check-alerting.sh`에서 실행, 0건·skip이면 실패): G1 정상 근거, G2 Prometheus 오류 → 원본, G3 제한 시간 → 원본, G4 키 없음 → LLM 0, G5 카드·비밀값 가림, G6 깨진 본문 400, G7 LLM이 받은 본문에 식별자 형태 없음, G7b 식별자가 남으면 미호출, `/metrics`. promtool에 `GuideServerDown` 케이스를 더했다.

### 10-5. Grafana MCP (warning = 당겨 쓰기)

- **[사용자 결정 w10s4b-2]** 다운로드는 허가, 단 가장 작은 공식 수단. 공식 `grafana/mcp-grafana` GitHub 릴리스 단일 바이너리 **v2.3.0**을 쓴다(`scripts/ops/mcp-grafana.sh`).
  - 크기 비교: 바이너리 tar.gz 17.97MB(선택) / Docker alpine 21.6MB / Docker 기본 63.5MB.
  - **[구현 판단]** v2.4.0은 2026-11-24 공개라 제외했다(공개 2주 지난 버전).
  - 처음 실행할 때만 `build/tools/` 아래로 받는다. OS·CPU별 고정 SHA-256과 다르면 실행하지 않는다.
- **[사용자 결정]** 읽기 전용(`--disable-write`, 읽는 도구 범주만 켬), 사용 통계 끔(`--usage-stats=disabled`), 토큰은 Grafana 서비스 계정 **Viewer** 토큰을 환경 변수 `GRAFANA_SERVICE_ACCOUNT_TOKEN`으로만 받는다.
- 사실: 사용 통계가 기본 켜짐인 것을 알기 전에 더미 토큰으로 `tools/list` 시험 실행을 1회 했다. 종료 때 Grafana Labs로 익명 보고 1건이 나갔을 수 있다(Grafana 주소·토큰 값은 보고 대상 아님). 이후 끔으로 고정했다.
- runbook "AI에게 이렇게 물어라" 절에 질문 예시를 두었다.
- **[사용자 결정 w10s4b-4 ①]** loki 도구 범주는 켜지 않는다(prometheus·dashboard·alerting·search·datasource·navigation만). 로그 원문의 내부 식별자를 LLM으로 보내지 않기 위해서다. `--enabled-tools`가 범주 단위라 `query_loki_patterns`만 켤 수 없어 함께 뺐다. AI는 지표·대시보드·알림으로 큰 틀, 주문 번호로 로그 찾기는 사람이 Grafana(Loki)에서 한다.

### 10-6. test-pg 실행 수단 [사용자 결정 w10s4b-3 ①]

- Gradle `:apps:commerce-api:runTestPg`(`JavaExec`, test 런타임 classpath, `TestPgServerMain`). test 소스의 `TestPgServer`를 그대로 띄운다. 같은 계약을 다시 만들지 않는다.
  - 결제 `127.0.0.1:8082`(앱 local 설정의 test-pg 주소), 제어 `127.0.0.1:8083`. 환경 변수로 포트·사용자 ID·유실 지연(기본 5s)을 바꿀 수 있다.
  - 제어 endpoint(인증 없음, loopback에만 묶음): `/control/status`, `/control/create-behavior`(전역 동작), `/control/lost-responses/{providerOrderId}`(유실 지정 PUT·해제 DELETE).
  - 래퍼 `scripts/ops/test-pg.sh`: `start`·`lose`·`clear`·`behavior`·`status`.
- 운영 classpath 비포함 확인(사실): bootJar에서 `TestPg` 이름이 맞는 항목은 기존 운영 어댑터 2개(`TestPgPaymentGateway`·`TestPgProperties`)뿐이고 테스트 클래스·라이브러리는 0건이다. `runtimeClasspath` 차이 없음.

### 10-7. 사람 흐름 리허설 1회

- 기록: `docs/week10/rehearsal.md`(캡처 `docs/week10/rehearsal/`). 2026-11-25 23:44~23:53 KST, 맥북에어 로컬, 1회. 자동 테스트가 아니다.
- **[PL 결정 P5]** 짧은 값은 오버레이로 분리했다. 운영 값 파일은 그대로다.
  - 규칙 `docker/prometheus/rules/rehearsal/payment-rehearsal.yml`(창 5m, unknown ≥ 3, for 1m, `rehearsal="true"`), `docker/prometheus/prometheus.rehearsal.yml`, `docker/monitoring-compose.rehearsal.yml`, 단계별 명령 `scripts/ops/rehearsal.sh`.
  - 앱은 자동 재조회 스케줄러를 끄고(만료도 같이 멈춤) gauge 갱신을 5s로 줄였다. 켜 두면 test-pg 조회가 정상이라 몇 초 안에 회복돼 사고가 재현되지 않는다.
- 결과 요약:
  - 응답 유실 4건 → 알림 firing → 가짜 수신기에 원본 + `[가이드]` 근거(unknown=4, 유입 비율 57.14%, Loki WARN 4줄). 사고 시작 → 근거 붙은 알림 1m46s(리허설 값 기준).
  - 대시보드 unknown=4 → Loki에서 주문 번호로 경계 로그 3줄(structured metadata 확인) → 관리자 목록 4건 → 일괄 확정 4건 모두 RESOLVED(1.05s), create 증가 0 → 지표 0, 알림 해제, resolved 2건.
  - 4a 미검증 확인: 래퍼를 실제 앱에 붙임, 새 counter 실제 수집(`started` 3 → 7, `unknown_entered` 0 → 4), 실제 JSON 로그로 Loki 질의.
- 막힌 지점 B1(로그 경로)은 고쳤다(10-2). B2~B5는 10-8과 `rehearsal.md`에 있다.

### 10-8. 한계

- streamer의 `spring.application.name`이 `commerce-api`라(기존 결함, 범위 밖) streamer를 local로 띄우면 api와 같은 로그 파일에 쓴다. 롤링 충돌과 `app` 구분 섞임이 생길 수 있다.
- 만료 경로(`PaymentAbandonedPiling`)는 리허설하지 않았다. 리허설에서 스케줄러를 꺼 만료도 멈췄다.
- `runbook_url`이 저장소 상대 경로라 Grafana·Slack에서 눌러 열리지 않는다(B4). `generatorURL`도 컨테이너 호스트명이다.
- resolved 알림은 해제 뒤 최대 5분 늦게 온다(group_interval 5m). resolved 가이드 메시지에도 30분 창 유입 비율이 높게 보여 헷갈릴 수 있다.
- 고객 사용자를 만드는 API가 없다. 리허설에서는 DB에 직접 넣었다(B3).
- 실제 LLM 호출과 Viewer 토큰으로 한 Grafana MCP 질의는 하지 않았다. Viewer 토큰 생성(접근 키 생성)은 승인 대상이라 범위 밖으로 뒀다.
- B1 수정 뒤에는 기본 경로 파일 생성만 확인했고, Loki 수집까지 다시 돌리지는 않았다.
- Grafana 이미지(`grafana/grafana`)는 버전을 고정하지 않았다(범위 밖).
- 실제 Slack은 여전히 연결하지 않았다.

## 11. 늦은 성공 자동 환불(5단계)

원칙 **[사용자 결정 D168·D175·D186·D188]**: 돈이 나갔는데 주문이 종결되면 결국 환불로 끝난다. 대형 장애 때 수천 건이 몰릴 수 있어 자동으로 한다. 경계가 잡힌 전용 스케줄러가 맡는다. 환불 요청은 한 번만 보내고, 모르면 다시 보내지 않고 조회한다.

- 11-1~11-9는 5단계 기록이다. **5b(11-10~11-18)에서 상태 모델을 두 축(돈의 상태·담당)으로 바꿨다** **[사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다"]**. 5단계 서술 중 바뀐 것은 지우지 않고 취소선과 대체 위치로 표시한다. 한 번만 규칙(11-4)·취소 [가정](11-6)·전용 스케줄러(11-7)는 그대로다.

### 11-1. 무엇이 바뀌나

- 5주차 설계(`docs/week5/commerce-payment.md`)의 두 문장을 이 절로 **대체한다**. 5주차 문서는 고치지 않는다.
  - "늦은 성공은 사람이 환불한다"(상태 전이 표·만료 주문 표) → 만료 주문의 늦은 성공은 자동 추적·자동 환불한다. 자동 추적 기한 안에 결론이 없거나 자동 판단 근거가 깨지면 사람에게 넘긴다.
  - 범위 밖 "PG 취소 API 연동(늦은 성공의 자동 환불)" → 이번 범위다. 취소 API 모양은 [가정]이다(11-6).
- 그대로인 것: 만료 주문은 EXPIRED로 남는다. 재고는 만료 때 이미 반환했다. 환불로 주문을 되살리지 않는다. 결제 기록(`payment_records`)은 손대지 않는다. 종결 주문의 반대 결과는 지금처럼 덮어쓰지 않고 `CONFLICTING_RESULT` 이상과 WARN을 남긴다(2-2).
- 과제 금지 문장은 그대로다. 재결제 없음, DB 강제 변경 없음, 조회·callback 검증·예약 소비/반환·중복 방지를 약화하지 않는다.
- **[사용자 결정 Q4]** 고객 주문 조회 응답은 바꾸지 않는다. 환불 상태는 관리자 목록에만 보인다(11-17).
- 취소 API는 [가정]이다. 실제 PG가 다르면 어댑터만 바꾼다.

### 11-2. 환불 확인 대상 `payment_refund_cases` [PL 결정 P1·P2]

- payment 컨텍스트가 소유한다. 한 행 = 만료된 PG 결제 하나. 주문당 결제 기록이 하나라 `order_id`가 PK다.
- 신규 도메인 `payment/domain/RefundCase`는 `BaseEntity`를 상속하지 않는다. 운영 스키마는 `sql/create-payment-refund-cases.sql`(IF NOT EXISTS, 엔티티와 같은 모양), local·test는 ddl create다.

| 컬럼 | 뜻 |
| --- | --- |
| `order_id` | PK. 주문 ID |
| `provider_id` | 조회·환불할 PG |
| `provider_order_id` | PG 주문 번호 |
| `amount` | 결제 금액. 조회 일치 판정과 취소 금액 |
| ~~`status`~~ | ~~대상 상태(아래 6값)~~ → `money_status`·`refund_actor`·`owner`·`owner_*`로 대체(11-10·11-15). 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다" |
| `prior_provider_status` | 만료 직전 PG 상태(SEND_STARTED·PENDING·UNKNOWN·NEEDS_REVIEW). 만료가 결제 기록의 상태를 EXPIRED로 덮기 전에 복사한다. 기능 이전 만료면 null |
| `transaction_key` | 만료 때 알던 거래 키, 또는 늦은 성공·조회로 알게 된 키. 처음 알게 된 값만 남기고 덮지 않는다 |
| `opened_at` | 대상이 열린 시각(보통 만료 시각) |
| `handoff_at` | 자동 추적 기한. `opened_at` + 2일 [임시] |
| `promise_at` | 고객 환불 약속 기한. `opened_at` + 4일 [임시] |
| `next_check_at` | 다음 조회 시각. 결론 상태면 null(5b: 돈의 상태가 끝나면 null. 사람 담당이어도 돈이 안 끝났으면 값이 있다, 11-12) |
| `check_attempts` | 결론 없이 끝난 조회 횟수 |
| `refund_requested_at` | 환불(취소) 요청을 기록한 시각 |
| `concluded_at` | 결론 시각(5b: 돈의 상태가 REFUNDED·NO_CHARGE가 된 시각. 담당 이관은 결론이 아니다) |
| ~~`reason`~~ | ~~사람에게 넘긴 사유 코드~~ → `owner_reason`으로 대체(11-10). 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다" |

- 인덱스: ~~`(status, next_check_at)`~~ → `(money_status, next_check_at)`로 대체(11-15).
- 사유 코드 **[PL 결정 P11]** (5b: 같은 코드를 `owner_reason`으로 쓴다, P2): `HANDOFF_DEADLINE`(기한 경과) · `TRANSACTION_MISMATCH`(금액·주문·키 불일치) · `MULTIPLE_TRANSACTIONS`(복수 거래) · `REFUND_REJECTED`(PG가 효과 없이 취소 거절) · `RESULT_REVERSED`(결과 뒤집힘).

### 11-3. 상태와 전이 — ~~5단계 6값 모델~~ → 11-10~11-12로 대체

> **대체 표시:** 이 절의 상태 6값, 전이 표, 합류 표는 5단계 기록이다. 돈의 사실과 담당이 한 칸에 섞여 `HANDED_TO_HUMAN`이 되면 돈의 상태를 잃고 조회도 멈췄다. 11-10(두 축)·11-11(환불 주체)·11-12(전이·합류 표)로 대체한다. 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다".

~~상태 6값: `TRACKING`(결론 대기) · `REFUND_REQUESTED`(환불 요청 기록) · `REFUND_UNKNOWN`(환불 결과 모름) · `REFUNDED`(환불 완료) · `CLOSED_NO_CHARGE`(돈 안 나감 닫기) · `HANDED_TO_HUMAN`(사람에게 넘김). 결론 상태는 `REFUNDED`·`CLOSED_NO_CHARGE`·`HANDED_TO_HUMAN`이다.~~ → 11-10으로 대체, 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다".

| 지금 | 사건 | 다음 |
| --- | --- | --- |
| (없음) | 만료 TX(`expireLocked`) | TRACKING. 첫 `next_check_at` = `opened_at`(만료 직후 1회 조회) |
| TRACKING | 조회: 금액·주문(·키)이 맞는 단일 SUCCESS | REFUND_REQUESTED를 **commit한 뒤** 취소 1회(11-4) |
| TRACKING | 조회: 맞는 단일 FAILED 또는 CANCELED | CLOSED_NO_CHARGE |
| TRACKING | 조회: PENDING · 0건 · 조회 실패 · 조회 예외 | TRACKING. `check_attempts` +1, 간격 늘림 |
| TRACKING | 조회 회로가 막힘(PG 미호출) | TRACKING. `check_attempts` 그대로, 다음 조회만 미룸 |
| TRACKING | 조회: 금액·주문·키 불일치 / 복수 거래 | HANDED_TO_HUMAN 즉시(`TRANSACTION_MISMATCH`·`MULTIPLE_TRANSACTIONS`). 취소 없음 |
| REFUND_REQUESTED | 취소 응답 Canceled | REFUNDED |
| REFUND_REQUESTED | 취소 응답 Unknown · NotSent · 호출 예외 | REFUND_UNKNOWN. 다시 보내지 않음. 호출 예외도 요청이 나갔는지 모르는 경우라 REFUND_REQUESTED로 두지 않는다(결과 모름 알림이 보게) |
| REFUND_REQUESTED | 취소 응답 RejectedNoEffect | HANDED_TO_HUMAN(`REFUND_REJECTED`) |
| REFUND_REQUESTED(취소 결과 없이 중단) · REFUND_UNKNOWN | 다음 회차 조회: CANCELED | REFUNDED. 취소 재호출 0 |
| REFUND_REQUESTED · REFUND_UNKNOWN | 다음 회차 조회: 아직 SUCCESS · 모름 · 불일치 · 복수 | 상태 유지, `check_attempts` +1. 기한이 처리한다 |
| TRACKING · REFUND_REQUESTED · REFUND_UNKNOWN | now ≥ `handoff_at` | HANDED_TO_HUMAN(`HANDOFF_DEADLINE`). 조회하지 않고 넘긴다 |
| REFUND_REQUESTED가 아닌 상태(응답을 기다리는 사이 다른 회차가 기한 이관·조회 결론을 냄) | 늦게 온 취소 결과 | 반영하지 않는다. WARN 한 줄(orderId, 지금 상태, 결과 종류만) |
| 결론 상태 | — | 끝. 스케줄러는 조회·취소하지 않는다 |

- **[사용자 결정 Q1 ①]** 거래 0건은 "돈 안 나감"의 근거가 아니다(8주차 설계 23절 [확정] 유지). 계속 추적하고 기한이 지나면 사람에게 넘긴다.
- ~~조회 CANCELED는 TRACKING에서는 돈 안 나감으로 닫고, 환불 요청 뒤에는 환불 완료로 본다.~~ → 11-11로 대체(확인 중의 CANCELED도 환불됨, 주체 EXTERNAL. 청구 없음과 섞지 않는다), 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다"(정책 4).
- 키 없이 만료된 대상은 주문 번호로 조회한다. 찾은 일치 거래의 키를 대상에 남기고 그 키로 취소한다.

**늦은 성공 합류** (callback·본인/관리자 reconcile이 종결 주문에서 CONFLICT(SUCCESS)를 낼 때, 같은 반영 TX 안) — ~~아래 표~~ → 11-12 합류 표로 대체, 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다"

| 주문·대상 | 처리 |
| --- | --- |
| 만료 주문 · 대상 TRACKING | 키 저장, `next_check_at` = 지금(바로 조회) |
| 만료 주문 · 대상 REFUND_REQUESTED·REFUND_UNKNOWN | 키만 저장, 상태 유지(재전송 없음) |
| 만료 주문 · 대상 CLOSED_NO_CHARGE | 결과 뒤집힘. HANDED_TO_HUMAN(`RESULT_REVERSED`), `next_check_at` null **[PL 결정 P8]** |
| 만료 주문 · 대상 REFUNDED·HANDED_TO_HUMAN | 그대로 |
| 만료 주문 · 대상 없음(기능 이전 만료) | 새로 TRACKING. `prior_provider_status` null, `opened_at`·기한은 지금부터 **[PL 결정 P8]** |
| 결제 실패(PAYMENT_FAILED) 주문 | 결과 뒤집힘. 대상을 HANDED_TO_HUMAN(`RESULT_REVERSED`)으로 바로 만든다. `handoff_at`·`concluded_at` = 지금, `promise_at` = 지금 + 4일, `next_check_at` null. 자동 환불 없음 **[사용자 결정 Q2 ①]** |

- 환불 요청은 합류 경로에서 보내지 않는다. 스케줄러 한 곳에서만 보낸다 **[PL 결정 P3]**.
- 이유: PG가 FAILED(또는 우리가 닫은 근거)라 했다가 SUCCESS가 된 것은 자동 판단의 근거가 깨진 것이다. 그래서 사람에게 넘긴다. (5b: 돈은 CHARGED로 적고 담당만 사람이다, 11-12.)

### 11-4. 한 번만 규칙 [PL 결정 P3·P11]

- 취소는 REFUND_REQUESTED로 바꾸는 TX를 **commit한 뒤에만** 보낸다. 그 뒤로 어떤 경로도 그 주문의 취소를 다시 보내지 않는다. 상태로 보장한다.
- 취소 결과가 NotSent(연결 전 실패)여도 REFUND_UNKNOWN으로 두고 조회만 한다. 요청 기록이 이미 commit됐으므로 "보내지 않았음"을 믿고 다시 보내지 않는다. 대가: 연결 거부가 계속되면 환불이 나가지 않고 2일 뒤 사람에게 넘어간다.
- 취소 전에 프로세스가 멈춰도 다음 회차는 REFUND_REQUESTED를 조회만 한다. 프로세스 중단은 REFUND_REQUESTED로 남고, 살아 있는 프로세스의 취소 호출 예외는 REFUND_UNKNOWN이 된다(11-3).
- 스케줄러는 조회 뒤 대상 행을 잠그고 다시 읽는다. 그 사이 다른 인스턴스가 REFUND_REQUESTED로 바꿨으면 취소를 보내지 않는다. (5b: 돈의 상태나 담당 중 하나라도 바뀌었으면 읽어 둔 판단을 반영하지 않는다.)
- (5b) 환불 요청은 **돈 CHARGED·담당 AUTO**일 때만 보낼 수 있다. 판단은 `RefundCase.canRequestRefund()` 한 곳이다(11-13).
- **행 잠금 순서:** 늦은 성공 합류 = 주문 → 대상(`findByOrderIdForUpdate`). 스케줄러 = 대상만. 스케줄러는 주문 행을 잠그지 않아 순서가 엇갈리지 않는다.
- **합류는 대상 행이 있을 때만 잠근다.** 먼저 잠금 없이 있는지 확인하고, 있으면 FOR UPDATE로 다시 읽고, 없으면 새로 INSERT한다. 없는 행을 FOR UPDATE로 읽으면 gap lock이 잡혀, 서로 다른 두 주문의 합류 INSERT끼리 데드락(1213)이 난다. 없을 때 잠그지 않아도 되는 이유: 대상을 만드는 경로(만료·합류)는 모두 주문 행 잠금 아래라 같은 주문은 이미 직렬화된다.
- 금액·주문 불일치·복수 거래·PG 거절에서는 취소하지 않고 멈춘다(사람).

### 11-5. 기한과 회차 [임시]

| 값 | [임시] 수치 | 위치 | 근거 |
| --- | --- | --- | --- |
| 자동 추적 기한(`handoff_at`) | 2일 | 도메인 상수 | 시스템 복구는 보통 하루면 된다. 이틀간 자동으로 안 되면 사람이 볼 마지막 상황이다(사용자 D188) |
| 고객 약속(`promise_at`) | 4일 | 도메인 상수 | 사용자 "3~4일". 사람에게 1일 이상, 카드사 반영 여유 포함 |
| 결론 없는 조회 뒤 간격 | ~~1m에서 배증, 최대 1h~~ → 같은 값을 jitter **상한**으로 쓴다(11-14) | 코드 상수 | 2일 동안 약 50회 이내 |
| 환불 요청·결과 모름 뒤 다음 조회 | ~~1m~~ → 상한 1m, jitter(11-14) | 코드 상수 | 취소 결과를 빨리 확인한다 |
| 회차 상한 | 20건(~~`next_check_at` 순~~ → 자동 담당 먼저, 자동·사람 합계, 11-15) | 코드 상수 | 대형 장애 때 PG를 다시 쓰러뜨리지 않는다 |
| 회차 주기 | fixed-delay 30s | yml `payment.refund.scheduler.fixed-delay` | — |

- 2일·4일은 설정으로 빼지 않았다 **[PL 결정 P8]**.
- 조회 회로가 막힌 PG(`PaymentCircuitBreakers.blocksLookup`, 9-6)의 대상은 조회하지 않고 미룬다. 다른 PG의 대상은 상한 안에서 조회한다.
- 건별 예외는 그 대상만 멈추고(ERROR, causeType) 같은 회차의 다음 대상을 계속 처리한다.

### 11-6. PG 취소 [가정 — 사용자 결정 Q3]

**과정 PG 계약에 취소 API가 없다.** 아래는 우리가 정한 가정이다. test-pg(`TestPgServer`)가 이 가정을 구현한다.

- 요청: `POST /api/v1/payments/{transactionKey}/cancel`, 본문 `{orderId, amount}`. 멱등 키는 없다.

| 경우 | 응답 |
| --- | --- |
| 승인(SUCCESS) 거래, 주문·금액 일치 | 200 `{transactionKey, orderId, amount, status: "CANCELED"}` |
| 이미 CANCELED | 같은 200 |
| 주문·금액 불일치, PENDING·FAILED 거래 | 422 `{code: "TEST_PG_CANCEL_REJECTED", transactionKey, orderId, amount, canceled: false}`. 효과 없음 |
| 거래 없음 | 404. 결과 모름으로 보고 조회로 잇는다 |

- 포트: `PaymentGateway.cancel(PaymentCancelCommand(providerOrderId, transactionKey, amount))` → `PaymentCancelResult`. 입력 검증은 없다(대상 기록에서 만든 내부 입력, P9).
- **어댑터 분류는 create와 같다:** 의심스러우면 Unknown이다. 자동 재전송은 없다.

| 결과 | 조건 |
| --- | --- |
| Canceled | 200이고 본문이 CANCELED이며 키·주문·금액이 요청과 같음 |
| RejectedNoEffect | 422이고 code가 `TEST_PG_CANCEL_REJECTED`, `canceled:false`, 키·주문·금액이 요청과 같음 |
| NotSent | 요청이 나가기 전 실패(본문 생성 실패, 연결 전 실패)만 |
| Unknown | 그 밖 전부(200 형식 오류·값 불일치, 다른 422, 404, 5xx, timeout, 연결 끊김) |

- 조회 상태에 **CANCELED**를 더했다(`PaymentTransactionStatus`). 기존 결제 흐름에서 조회 CANCELED는 `ProviderStatus.FAILED`로 매핑한다. 결제 대기 주문이면 실패 종결이고 지표 `result=failed`다 **[PL 결정 P5·P9]**. 새 enum은 없다. 지금 PG가 결제 대기 주문에 CANCELED를 내는 일은 없어 기존 동작은 같다.
- create 응답(202)의 status가 CANCELED면 지금처럼 Unknown이다. 접수 계약 밖이기 때문이다(create 분류 불변, P9).
- 취소 결과 확인은 기존 주문 단위 조회(`verifyOrder`)로 한다. 별도 취소 조회 API는 없다.
- test-pg 사고 제어(test 소스):
  - `loseCancelResponse(providerOrderId)`: 그 주문의 취소를 CANCELED로 확정한 뒤 read timeout보다 늦게 응답한다. 앱은 결과를 모른다. `clearLostCancel`로 해제한다.
  - 제어 endpoint `/control/lost-cancels/{providerOrderId}`(PUT 지정·DELETE 해제), 래퍼 `scripts/ops/test-pg.sh lose-cancel|clear-cancel`.
  - 취소 호출은 요청을 받은 순간 주문별로 센다(`cancelCalls(providerOrderId)`). create 수와 섞지 않는다. `/control/status`에 `cancelCalls`·`lostCancelOrderIds`가 있다.

### 11-7. 전용 스케줄러 [사용자 결정, 7절 규칙]

| 작업 | 스케줄러 빈 | 스레드 이름 접두사 | 켜기 설정 |
| --- | --- | --- | --- |
| 환불 추적(`payment/in/RefundTrackingScheduler`) | `paymentRefundTaskScheduler` | `sched-payment-refund-` | `payment.refund.scheduler.enabled`(test 프로필 false) |

- 결제 자동 재조회·만료와 섞지 않는다. 사용자 기준: 목적이 다른 일은 경계를 나눈다.
- 빈은 `payment/config/PaymentSchedulingConfig`가 만든다 **[PL 결정 P11]**. order 쪽 `OrderSchedulingConfig`가 아니다(ArchUnit 컨텍스트 경계).
- 회차 반복과 건별 catch는 서비스 `payment/app/RefundTrackingService.trackDueCases()`가 한다. 스케줄러는 호출만 한다.
- 대상마다 MDC `orderId`를 건다. slf4j MDC에 같은 키로 직접 넣는다(payment → order 의존을 만들지 않기 위해, P11).
- 격벽: 환불 추적이 붙잡혀도 결제 재조회·relay·gauge 갱신은 진행하고, 그 반대도 같다(`ScheduledJobIsolationTest`).

### 11-8. 관측·알림 (5단계 기록, 5b는 11-16·11-17)

**지표** **[PL 결정 P6·P10]** (label은 소문자 유한 값, orderId 없음)

| 이름 | 태그 | 갱신 |
| --- | --- | --- |
| gauge `commerce.payment.refund_cases` | ~~`status`: tracking · refund_requested · refund_unknown · refunded · closed_no_charge · handed_to_human~~ → `money_status` 6값으로 대체(11-16), 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다" | DB 주기 조회. 기존 gauge 갱신 스케줄러(`sched-unresolved-metric-`, 4-1)에 더했다. 첫 갱신 전 NaN, 갱신 실패면 마지막 값 유지 |
| counter `commerce.payment.refund_outcome` | ~~`result`: refunded · closed_no_charge · handed_to_human~~ → `result`×`actor`와 `refund_person_assigned{reason}`으로 대체(11-16), 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다" | 결론 도달이 commit된 뒤 1. 합류 경로의 `RESULT_REVERSED` 이관도 handed_to_human으로 센다. REFUND_UNKNOWN은 결론이 아니라 세지 않는다 |

- ~~counter 합계는 대상 수와 다를 수 있다. CLOSED_NO_CHARGE로 닫은 뒤 늦은 성공으로 뒤집혀 이관되면 closed_no_charge와 handed_to_human을 둘 다 센다.~~ → 11-16으로 대체, 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다".

- 경계 로그: 대상 생성 INFO, 환불 요청 INFO, 취소 호출 예외 WARN(causeType), 환불 결과 모름 WARN, 상태가 바뀐 뒤 늦게 온 취소 결과 WARN(결과 종류만), 사람 이관 WARN, 건별 실패 ERROR(causeType). 2-1 레벨 기준을 따른다.

**알림 규칙** (`docker/prometheus/rules/payment.yml`, promtool 케이스 `payment_test.yml`, runbook 앵커 `docs/runbook/week10.md`)

| 규칙 | severity | 식(요지) | [임시] 값 | 근거 |
| --- | --- | --- | --- | --- |
| ~~`PaymentRefundHandedToHuman`~~ | ~~warning~~ | ~~`max(refund_cases{status="handed_to_human"}) > 0`, `for: 15m`~~ | ~~15m~~ | 삭제. `PaymentRefundNeedsPerson`으로 대체(11-16), 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다" |
| `PaymentRefundUnknown` | warning | `max(refund_cases{money_status="refund_unknown"}) > 0`, `for: 30m` (라벨 ~~`status`~~ → `money_status`로 대체, 11-16) | 30m | 결과 모름은 다음 조회로 풀린다. 30분 넘게 못 풀면 PG 취소 조회가 막힌 것이다 |
| `PaymentRefundPiling` | critical | ~~`max(refund_cases{status="handed_to_human"}) ≥ 10` AND `increase(refund_outcome{result="handed_to_human"}[30m]) > 0`~~ → 사람 담당 중 gauge·사람 배정 counter로 대체(11-16), `for: 10m` | 10건, 증가 창 30m, 10m | 대형 장애 뒤 이관이 몰리면 사람 몫이 하루를 넘을 수 있다 |

- Piling의 "증가"는 gauge가 아니라 이관 counter로 잰다 **[PL 결정 P10]**. ~~사람 처리를 표시하는 수단이 없어 gauge가 내려가지 않는다. 건수만 보면 한 번 10건을 넘은 뒤 계속 울린다.~~ → 5b에서 사람 담당 중 gauge는 담당이 닫히면 내려간다(11-16). 증가는 계속 counter로 잰다.
- critical 가이드 서버(10-4)에 근거 두 개를 더했다: ~~상태별 대상 수, 사람 이관 증가(30분)~~ → 5b 근거 4개로 대체(11-16). 집계만 보내고 식별자는 없다. 리허설 규칙(10-7)에는 환불 규칙을 넣지 않았다.
- 대시보드 `commerce-payment-incident`에 환불 확인 대상 패널을 더했다.

**사람 조치** **[PL 결정 P7]** (~~새 전용 API 없음~~ → 사람 기록 API 1개 추가로 대체, 11-17. 사용자 결정 w10s5b Q1 ①)

- ~~기존 `GET /api-admin/v1/payments/unresolved`(9-5)의 `status`에 `HANDED_TO_HUMAN`·`REFUND_UNKNOWN`을 허용한다. 이 값이면 환불 확인 대상을 보여 준다.~~ → `refundOwner`·`refundMoneyStatus` 필터로 대체(11-17), 사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다". 같은 목록, 다른 원천이다. 카드·userId는 없다. 비관리자는 403이다.
  - 예: ~~`scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=HANDED_TO_HUMAN'`~~ → 11-17
- 사람은 목록으로 대상을 고정하고 PG 콘솔 등에서 수동으로 처리한다. (5b: PG로 확인되지 않는 결론만 사람 기록 API로 적는다, 11-17.)

### 11-9. 범위 밖

- ~~사람이 처리한 환불·종결을 시스템에 표시하는 수단(그래서 HANDED_TO_HUMAN gauge는 내려가지 않는다)~~ → 5b 범위로 옮김(PG 사실은 자동 기록 11-13, 그 밖은 사람 기록 API 11-17). 사용자 결정 w10s5b Q1 ①
- 정산 파일 대사
- 실제 PG 연동(취소 API는 [가정])
- 고객 알림(메일 등)
- 부분 환불
- 주문 되살리기, 고객 주문 응답에 환불 상태 노출(Q4)

### 11-10. 두 축 모델 — 돈의 상태와 담당 [사용자 결정 2026-11-26 "이상한 상태를 만들지 않는다"]

원칙: **한 칸 한 뜻.** 돈의 사실(`money_status`)과 다음 일을 누가 하나(`owner`)를 나눈다. 사람에게 넘겨도 돈의 사실은 계속 기록한다. 별도 "처리 완료" 상태는 없다.

**축 1 — 돈의 상태 `money_status`** (업무 사실만) **[PL 결정 P1]**

| 값 | 업무 용어 | 뜻 | 끝? |
| --- | --- | --- | --- |
| `CHECKING` | 확인 중 | 만료됐고 PG에서 돈이 나갔는지 아직 모른다 | 아니오 |
| `CHARGED` | 청구 확인 | PG가 돈이 나갔다고 했다. 환불은 아직 요청하지 않았다 | 아니오 |
| `REFUND_REQUESTED` | 환불 요청함 | 환불을 한 번 요청했고 결과를 아직 받지 못했다 | 아니오 |
| `REFUND_UNKNOWN` | 환불 결과 모름 | 요청 결과가 유실·모름. 조회로만 확인한다 | 아니오 |
| `REFUNDED` | 환불됨 | 돈이 돌아갔다 | 끝 |
| `NO_CHARGE` | 청구 없음 | 돈이 나가지 않았다 | 끝 |

- 끝난 돈의 상태는 되돌리지 않는다. 예외 하나: `NO_CHARGE` 뒤 늦은 성공은 사실이 뒤집힌 것이라 `CHARGED`로 다시 연다(11-12 합류 표).
- `CHARGED`·담당 AUTO는 같은 TX에서 바로 `REFUND_REQUESTED`가 된다. 커밋된 `CHARGED`가 남는 것은 담당이 사람일 때뿐이다.
- `concluded_at` = 돈이 끝난(REFUNDED·NO_CHARGE) 시각.

**축 2 — 담당 `owner`**

| 칸 | 값 |
| --- | --- |
| `owner` | `AUTO`(시스템이 조회·환불) / `PERSON`(사람이 판단, 시스템은 조회만) |
| `owner_reason` | 사람 담당이 된 이유. 기존 사유 코드 5개 재사용 **[PL 결정 P2]**: `HANDOFF_DEADLINE`·`TRANSACTION_MISMATCH`·`MULTIPLE_TRANSACTIONS`·`REFUND_REJECTED`·`RESULT_REVERSED` |
| `owner_changed_at` | 담당이 사람으로 바뀐 시각 |
| `owner_closed_at` | 사람 담당이 닫힌 시각 |
| `owner_closed_by` | 닫은 주체. `SYSTEM`(PG 사실로 자동 종료) 또는 관리자 인증 이름(고객 정보 아님) |

- **사람 담당 중** = `owner = PERSON` AND `owner_closed_at IS NULL`.
- 돈이 끝나면(REFUNDED·NO_CHARGE) 사람 담당은 같은 TX에서 닫힌다. 사람이 "처리 완료"를 누를 일은 없다.
- 이미 사람 담당인 건에 다른 사유가 생겨도 담당·처음 사유를 그대로 둔다(예: 사람 담당 중 취소 거절, P6).

### 11-11. 환불 주체 `refund_actor` [사용자 확인 2026-11-26 17:48]

REFUNDED일 때만 값이 있다. **우리가 본 경로**로 정한다.

| 값 | 조건 |
| --- | --- |
| `SYSTEM` | 우리가 취소를 요청한 뒤 환불됐다. 돈이 `REFUND_REQUESTED`·`REFUND_UNKNOWN`에서 취소 응답 Canceled 또는 조회 CANCELED |
| `EXTERNAL` | 효과 있는 요청이 없었는데 조회로 CANCELED를 봤다. 돈이 `CHECKING`·`CHARGED`에서 조회 CANCELED. 환불 요청이 PG에서 효과 없음으로 거절된 뒤(돈이 `CHARGED`로 돌아감) CANCELED를 본 경우도 `EXTERNAL`이다 **(정책 4: 확인 중의 CANCELED는 청구 없음이 아니라 환불됨)** [참모 확인 중 w10s5b-4] |
| `PERSON` | 관리자가 사람 기록 API로 "환불함"을 적었다(PG로 확인되지 않은 경우, 11-17) |

- PG 화면에서 사람이 환불했는지 다른 경로인지는 우리가 구분할 근거가 없어 `EXTERNAL` 하나로 둔다. 그때 담당이 사람이었는지는 `owner_*`로 따로 남는다.
- `NO_CHARGE`는 환불이 아니라 `refund_actor`가 비어 있다.

### 11-12. 전이 표 (누가, 어떤 근거로)

`*` = 담당 무관. 조회 결과 "일치"는 주문·금액(·알던 키)이 대상과 같은 단일 거래다.

| 지금(돈 / 담당) | 근거 | 누가 | 다음 |
| --- | --- | --- | --- |
| (없음) | 만료 TX(`expireLocked`) | 만료 | `CHECKING` / AUTO. 첫 조회 = 열린 시각 + jitter(상한 1m) |
| `CHECKING` / AUTO | 조회: 일치 단일 SUCCESS | 스케줄러 | `CHARGED` 기록 후 같은 TX에서 `REFUND_REQUESTED` commit → 취소 1회(11-4) |
| `CHECKING` / PERSON | 조회: 일치 단일 SUCCESS | 스케줄러 | `CHARGED` / PERSON. 취소 없음, 30m 상한으로 다시 조회(P6) |
| `CHECKING` / * | 조회: 일치 단일 FAILED | 스케줄러 | `NO_CHARGE`. 사람 담당 중이면 자동 종료(`SYSTEM`) |
| `CHECKING`·`CHARGED` / * | 조회: 일치 단일 CANCELED | 스케줄러 | `REFUNDED`, actor `EXTERNAL`. 사람 담당 중이면 자동 종료 |
| `REFUND_REQUESTED`·`REFUND_UNKNOWN` / * | 조회: 일치 단일 CANCELED | 스케줄러 | `REFUNDED`, actor `SYSTEM`. 사람 담당 중이면 자동 종료 |
| `CHECKING` / * | 조회: 불일치(금액·주문·키) / 복수 거래 | 스케줄러 | `CHARGED`(돈이 나간 근거). AUTO면 PERSON(`TRANSACTION_MISMATCH`·`MULTIPLE_TRANSACTIONS`), 이미 PERSON이면 담당 그대로(P6) |
| `REFUND_REQUESTED`·`REFUND_UNKNOWN` / AUTO | 조회: 불일치 / 복수 거래 | 스케줄러 | 돈 그대로 / **담당만 즉시 PERSON**(사유) (정책 3) |
| `CHARGED`·`REFUND_REQUESTED`·`REFUND_UNKNOWN` / PERSON | 조회: 불일치 / 복수 거래 | 스케줄러 | 돈·담당 그대로, 다음 조회 |
| `CHARGED`·`REFUND_REQUESTED`·`REFUND_UNKNOWN` / * | 조회: 일치 단일 FAILED | 스케줄러 | 돈·담당 그대로(사실이 엇갈림 — 자동으로 닫지 않는다). 다음 조회 |
| `CHARGED`·`REFUND_REQUESTED`·`REFUND_UNKNOWN` / * | 조회: 일치 단일 SUCCESS | 스케줄러 | 그대로. 다시 보내지 않는다(11-4). 다음 조회 |
| 비종결 / * | 조회: PENDING · 0건 · 조회 실패 · 조회 예외 | 스케줄러 | 그대로. `check_attempts` +1, 다음 조회 jitter |
| 비종결 / * | 조회 회로가 막힘(PG 미호출) | 스케줄러 | 그대로. `check_attempts` 그대로, 다음 조회만 미룸 |
| `REFUND_REQUESTED` / * | 취소 응답 Canceled | 스케줄러 | `REFUNDED`, actor `SYSTEM`. 사람 담당 중이면 자동 종료 |
| `REFUND_REQUESTED` / * | 취소 응답 Unknown · NotSent · 호출 예외 | 스케줄러 | `REFUND_UNKNOWN`. 다시 보내지 않음, 상한 1m로 조회 |
| `REFUND_REQUESTED` / * | 취소 응답 RejectedNoEffect | 스케줄러 | `CHARGED`(효과 없음). AUTO면 PERSON(`REFUND_REJECTED`), 이미 PERSON이면 처음 사유 유지(P6). 30m 상한 조회 |
| 비종결 / AUTO | now ≥ `handoff_at` | 스케줄러 | 돈 그대로 / PERSON(`HANDOFF_DEADLINE`) (정책 1). 그 회차는 PG를 부르지 않는다. 다음 조회 30m 상한 |
| 사람 담당 중, 돈 비종결 4값 | 관리자 "환불함" 기록 | 사람 | `REFUNDED`, actor `PERSON`, 담당 종료(관리자 이름) (11-17) |
| 사람 담당 중, ~~`CHECKING`~~ → `CHECKING`·`CHARGED` (R1) | 관리자 "청구 없음" 기록 | 사람 | `NO_CHARGE`, actor 없음, 담당 종료(관리자 이름) (11-17) |
| 사람 담당 중, 돈 비종결 4값 | 관리자 "지금 처리" | 사람 | 같은 회차를 이 건 하나에 지금 돌린다. `CHARGED`면 환불 요청 1회(11-19) |
| 끝난 돈 | — | — | 끝. 스케줄러는 조회·취소하지 않는다. `next_check_at` null |

- **R3 [참모 19:41]**: 조회 결과를 반영할 때 잠금 아래에서 `now ≥ handoff_at`이면(자동 담당) 먼저 담당을 PERSON(`HANDOFF_DEADLINE`)으로 바꾼 뒤 결과를 적용한다. 조회 중 기한을 넘긴 SUCCESS도 취소 없이 `CHARGED` / PERSON이 된다.
- 응답을 기다리는 사이 돈이 `REFUND_REQUESTED`가 아니게 된 경우 늦은 취소 결과는 반영하지 않고 WARN(orderId, 돈의 상태, 결과 종류)만 남긴다(11-3과 같다).
- 거래 0건은 여전히 "돈 안 나감"의 근거가 아니다 **[사용자 결정 Q1 ①]**.

**늦은 성공 합류** (callback·본인/관리자 reconcile의 CONFLICT(SUCCESS), 같은 반영 TX 안. 행 잠금 순서·gap lock 규칙은 11-4 그대로)

| 대상 돈의 상태 / 주문 | 처리 |
| --- | --- |
| `CHECKING` | 키 저장, `next_check_at` = 지금(바로 조회, jitter 없음 — P6) |
| `CHARGED`·`REFUND_REQUESTED`·`REFUND_UNKNOWN` | 키만 저장. 돈·담당 그대로, 재전송 없음 |
| `NO_CHARGE` | 결과 뒤집힘. `CHARGED` / PERSON(`RESULT_REVERSED`). `concluded_at`·`owner_closed_*` 비움, 다음 조회 30m 상한 — 사실이 뒤집혔음을 돈 칸에 남긴다 |
| `REFUNDED` | 그대로 |
| 대상 없음 · 만료 주문(기능 이전 만료) | 새로 `CHECKING` / AUTO. `prior_provider_status` null, 기한은 지금부터, `next_check_at` = 지금 |
| 대상 없음 · 결제 실패(PAYMENT_FAILED) 주문 | 새로 `CHARGED` / PERSON(`RESULT_REVERSED`). `handoff_at` = 지금, `promise_at` = 지금 + 4일, 다음 조회 30m 상한. 자동 환불 없음 **[사용자 결정 Q2 ①]** |

- 환불 요청은 합류에서 보내지 않는다. 스케줄러 한 곳뿐이다 **[PL 결정 P3]**.
- 이미 사람 담당 중인 대상에 늦은 성공이 다시 와도 사람 배정을 다시 세지 않는다.

### 11-13. 사람 담당 중에도 조회는 계속한다 [사용자 지시 2026-11-26 17:15]

- 대상 선택은 담당이 아니라 **돈의 상태(비종결)**로 고른다. 담당이 사람이어도 돈이 끝나지 않았으면 시스템이 계속 PG를 조회한다. 간격만 길다(30m 상한 [임시]).
- PG 사실로 돈이 끝나면(CANCELED → `REFUNDED`, `CHECKING`의 단일 FAILED → `NO_CHARGE`) 사람 담당을 같은 TX에서 자동으로 닫는다(`owner_closed_by = SYSTEM`). 사람이 PG 화면에서 환불한 건도 다음 조회에서 이렇게 닫힌다.
- **자동 환불(취소 호출)은 담당 AUTO일 때만 보낸다.** 사람 담당이면 조회만 한다(사람이 맡은 건에 시스템이 끼어들지 않는다 — 목적별 경계).
  - ~~**사람 담당 건 취소 여부는 사용자 확인 중, 현재 보내지 않음.**~~ → 자동 회차는 계속 보내지 않는다. 사람이 "지금 처리"를 누른 회차만 `CHARGED`에서 보낸다(11-19, 사용자 결정 w10s5c). 판단은 `RefundCase.canRequestRefund(trigger)` 한 곳이다.
- 기한 경계 **[R3, 참모 19:41]**: 조회 중 기한을 넘기면 반영 전에 담당이 먼저 사람으로 바뀐다(11-12). 그래서 기한 경계에서 사람 담당 건에 자동 취소가 나가지 않는다.
- 사람이 기록해야 하는 경우는 PG로 확인되지 않는 결론뿐이다(11-17).

### 11-14. 다음 조회 시각 jitter [사용자 지시 2026-11-26 17:36, PL 결정 P5]

- 다음 조회 시각 = 지금 + `LookupJitter.chooseMillis(상한)`. 결제 확정 조회(`PaymentLookupPolicy`)와 **같은 포트·같은 빈**(`FullLookupJitter`, 0~상한 균등 full jitter)을 재사용한다. 새 구현은 없다.
- 이유: 장애 한 번에 만료가 몰리면 열린 시각이 거의 같다. 고정 간격이면 `next_check_at`이 한 시각에 모여 PG 복구 직후 한꺼번에 조회한다. 회차 상한 20은 처리량만 막고 파도는 흩뜨리지 못한다.
- 도메인(`RefundCase`)은 이미 고른 지연(Duration)만 받아 시각을 계산한다. 난수는 `RefundTrackingService`·만료/합류(`OrderApplicationService`)가 포트로 주입받아 고른다(order.app → payment.app.port, 기존과 같은 방향).
- 하한은 지금이다(full jitter는 0이 나올 수 있다 — 결제 조회와 같다).

| 경우 | 상한 [임시] |
| --- | --- |
| 첫 조회(만료 직후) | 1m |
| 결론 없는 조회 뒤(AUTO) | 1m에서 배증(n번째 결론 없는 조회 뒤 1m·2^(n-1)), 최대 1h |
| 회로로 미룸(AUTO) | 마지막 상한(조회 횟수를 늘리지 않으므로 직전 상한, 0회면 1m) |
| 환불 요청 뒤 · 결과 모름 뒤 | 1m |
| 사람 담당(이관·거절·뒤집힘 직후, 사람 담당의 결론 없는 조회·회로 미룸) | 30m |
| 합류가 `CHECKING`을 당김, 대상 없이 새로 연 만료 주문 | jitter 없음, 지금(바로 확인 목적, P6) |

### 11-15. 대상 선택과 스키마

**대상 선택** (`PaymentRefundCaseJpaRepository.findDue`)

- 돈의 상태가 비종결 4값(`CHECKING`·`CHARGED`·`REFUND_REQUESTED`·`REFUND_UNKNOWN`)이고 `next_check_at ≤ 지금`인 대상.
- 순서: **담당 AUTO 먼저**, 그다음 `next_check_at`, `order_id`. 회차 상한 20건 [임시]은 AUTO·PERSON 합계다. 사람 담당 건 때문에 자동 환불이 밀리지 않게 한다.
- 대상마다 잠금 아래 다시 읽어 기한·회로를 판단한다(11-4·11-5 그대로).

**DDL** (`sql/create-payment-refund-cases.sql`, IF NOT EXISTS, 엔티티와 같은 모양)

- 더한 칸: `money_status`(NOT NULL), `refund_actor`, `owner`(NOT NULL), `owner_reason`, `owner_changed_at`, `owner_closed_at`, `owner_closed_by`.
- 지운 칸: `status`, `reason`.
- 인덱스: `(money_status, next_check_at)` — 대상 선택이 돈의 상태 기준이다.

**로컬 이전 SQL** (`sql/migrate-payment-refund-cases-v2.sql`) **[PL 결정 P3]**

- 5단계는 push·배포 전이라 운영 데이터 이전은 없다. 로컬 DB용으로 한 번 돌린다. 다시 실행하면 옛 `status` 칸이 없어 아무것도 바꾸지 않는다.
- 기존 이전 관례(information_schema + PREPARE, MySQL 8에 `ADD/DROP COLUMN IF [NOT] EXISTS` 없음)를 따른다. 순서: 새 칸 NULL 허용으로 추가 → 매핑 → `money_status`·`owner` NOT NULL → 옛 인덱스 삭제 → 옛 칸 삭제 → 새 인덱스.

| 옛 `status` | `money_status` | `owner`(`owner_reason`) | `refund_actor` |
| --- | --- | --- | --- |
| `TRACKING` | `CHECKING` | AUTO | — |
| `REFUND_REQUESTED` / `REFUND_UNKNOWN` | 같은 값 | AUTO | — |
| `REFUNDED` | `REFUNDED` | AUTO | `SYSTEM` |
| `CLOSED_NO_CHARGE` | `NO_CHARGE` | AUTO | — |
| `HANDED_TO_HUMAN`, reason ∈ `RESULT_REVERSED`·`TRANSACTION_MISMATCH`·`MULTIPLE_TRANSACTIONS`·`REFUND_REJECTED` | `CHARGED` | PERSON(옛 reason) | — |
| `HANDED_TO_HUMAN`, `HANDOFF_DEADLINE` | `refund_requested_at` 있으면 `REFUND_UNKNOWN`, 없으면 `CHECKING` | PERSON(`HANDOFF_DEADLINE`) | — |

- `HANDED_TO_HUMAN` 행 **[PL 결정 P6]**: 옛 모델은 이관을 결론으로 적었으므로 `owner_changed_at` = 옛 `concluded_at`, `next_check_at` = 옛 `concluded_at`(바로 조회), `concluded_at` = null, `owner_closed_*` = null.
- 옛 `REFUNDED`는 우리 취소 뒤에만 생겼으므로(옛 모델은 확인 중 CANCELED를 `CLOSED_NO_CHARGE`로 닫았다) `SYSTEM`이다.

### 11-16. 관측·알림 (5b)

**지표** (label은 소문자 유한 값, orderId 없음)

| 이름 | 태그 | 갱신 |
| --- | --- | --- |
| gauge `commerce.payment.refund_cases` | `money_status`: checking · charged · refund_requested · refund_unknown · refunded · no_charge | DB 주기 조회(기존 `sched-unresolved-metric-` 작업). 첫 갱신 전 NaN, 갱신 실패면 마지막 값 유지, 없는 상태는 0 |
| gauge `commerce.payment.refund_person_open` | 없음 | 사람 담당 중 건수. 담당이 닫히면 내려간다 |
| gauge `commerce.payment.refund_requested_oldest_age_seconds` | 없음 | `REFUND_REQUESTED` 중 가장 오래된 요청의 경과 초. 없으면 0 **[PL 결정 P4]** |
| counter `commerce.payment.refund_outcome` | `result`: refunded · no_charge × `actor`: system · person · external | 돈이 끝난 것이 commit된 뒤 1(rollback이면 0). 시작 때 6개 조합을 0으로 등록 |
| counter `commerce.payment.refund_person_assigned` | `reason`: 사유 5값 소문자 | 담당이 사람으로 바뀐 것이 commit된 뒤 1. 합류의 `RESULT_REVERSED` 포함. 이미 사람 담당이면 다시 세지 않는다 |

- `refund_outcome`의 `actor`는 결론을 낸 쪽이다. REFUNDED는 `refund_actor`와 같고, NO_CHARGE는 PG 사실이면 system, 사람 기록이면 person이다.
- 옛 `refund_outcome{result=handed_to_human|closed_no_charge}`는 없앴다. 담당 이관은 돈의 결론이 아니라 `refund_person_assigned`로 센다.

**알림 규칙** (`docker/prometheus/rules/payment.yml`, promtool `payment_test.yml`, runbook 앵커 `docs/runbook/week10.md`)

| 규칙 | severity | 식(요지) | [임시] 값 | 근거 |
| --- | --- | --- | --- | --- |
| `PaymentRefundNeedsPerson` | warning | `max(refund_person_open) > 0`, `for: 15m` | 15m | 사람 담당 건이 있다. 아침 확인으로 남긴다. 담당이 닫히면(PG 사실 자동 종료·사람 기록) 해소되어 계속 울리지 않는다 |
| `PaymentRefundRequestStuck` | warning | `max(refund_requested_oldest_age_seconds) > 600`, `for: 5m` | 600s, 5m | 요청 뒤 결과 조회는 1m 상한부터라 정상이면 몇 분 안에 넘어간다. 10분 넘게 요청함이면 취소 응답·조회가 막힌 것이다(정책 2) |
| `PaymentRefundUnknown` | warning | `max(refund_cases{money_status="refund_unknown"}) > 0`, `for: 30m` | 30m | 결과 모름은 다음 조회로 풀린다. 30분 넘게 못 풀면 PG 취소 조회가 막힌 것이다. 담당과 무관하게 돈의 상태로 센다 |
| `PaymentRefundPiling` | critical | `max(refund_person_open) ≥ 10` AND `sum(increase(refund_person_assigned_total[30m])) > 0`, `for: 10m` | 10건, 30m, 10m | 대형 장애 뒤 이관이 몰리면 사람 몫이 하루를 넘을 수 있다. 쌓였어도 새 이관이 멈췄으면 깨우지 않고 NeedsPerson으로 남긴다 |

- `PaymentRefundHandedToHuman`은 삭제했다.
- critical 가이드 서버(10-4) 근거: 돈의 상태별 수, 사람 담당 중 수, 사람 배정 증가(30분), 요청 정체 경과. 집계만 보내고 식별자는 없다.
- 대시보드 `commerce-payment-incident`의 환불 패널을 두 축으로 바꿨다.

### 11-17. 사람 조치 (관리자) [사용자 결정 w10s5b Q1 ①, PL 결정 P7]

**목록** — 기존 `GET /api-admin/v1/payments/unresolved`(9-5)의 환불 필터를 바꾼다(미배포라 계약 교체, P3).

- `refundOwner=PERSON`: 사람 담당 중(담당 종료 전) 대상. 허용 값은 `PERSON`뿐이다(미확인 목록의 뜻).
- `refundMoneyStatus=…`: 돈의 상태 비종결 4값(`CHECKING`·`CHARGED`·`REFUND_REQUESTED`·`REFUND_UNKNOWN`)만, 여러 값 가능. 담당과 무관하다.
- 둘을 함께 주면 둘 다 맞는 대상이다. 오래 열린 순, page·size는 그 정렬 위에서 자른다. 경과 시간 필터는 열린 시각 기준이다.
- 400: 결제 대기 `status` 필터와 섞음(원천이 다름), 옛 값(`HANDED_TO_HUMAN` 등), 끝난 돈의 상태, 모르는 값, `PERSON`이 아닌 담당. 비관리자 403.
- 항목: 돈의 상태, 담당, 담당 사유, 환불 주체, 담당 종료 시각(`ownerClosedAt`, null = 열림), 기한. 카드·userId·거래 키 값은 없다.
- 예: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundOwner=PERSON'`

**사람 기록** — `POST /api-admin/v1/payments/refund-cases/{orderId}/person-resolution`, 본문 `{"result": "REFUNDED" | "NO_CHARGE"}`

- PG로 확인되지 않는 결론만 적는다(사람이 청구 없음을 확인함, PG 조회 불가 등). PG 사실은 시스템이 조회로 적는다(11-13).
- 관리자 HTTP Basic + CSRF(9-4). 래퍼: `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/{orderId}/person-resolution '{"result":"REFUNDED"}'`.
- 허용 조건:

| 결과 | 허용 | 저장 |
| --- | --- | --- |
| `REFUNDED` | 사람 담당 중 + 돈 비종결 4값 | `REFUNDED`, actor `PERSON`, `concluded_at`·`owner_closed_at` = 지금, `owner_closed_by` = 관리자 인증 이름 |
| `NO_CHARGE` | 사람 담당 중 + 돈 ~~`CHECKING`만~~ → `CHECKING` 또는 `CHARGED` **[R1, 참모 19:41]** | `NO_CHARGE`, actor 없음, 같은 종료 칸 |

- 그 밖은 409다: 담당 AUTO, 담당이 이미 닫힘, 돈이 이미 끝남, ~~`CHARGED`·~~`REFUND_REQUESTED`·`REFUND_UNKNOWN`에 `NO_CHARGE`(~~PG가 청구를 확인했거나~~ 환불을 이미 요청한 건에 "청구 없음"은 돈 칸과 모순, P7). 409면 대상은 그대로다.
- R1 근거: 사람 담당이 된 `CHARGED`는 대부분 불일치·복수·뒤집힘이라 PG가 말한 청구가 우리 주문 것이 아닐 수 있고 최종 판단은 사람이 한다(`owner_reason`은 남아 맥락 보존). 환불 요청 뒤 돈은 우리가 취소를 보냈을 수 있어 "청구 없음"이 아니다.
- 대상 없음 404, 본문 형식 오류 400, 비관리자·미식별 403, CSRF 없음 403.
- **PG 호출·재결제·DB 강제 변경은 없다.** 사실 기록만 한다. 결론 counter(`actor=person`)는 commit 뒤 1, rollback이면 0.
- 위치: 컨트롤러 `order/in/AdminRefundCaseController`(공용 예외 advice 범위), 서비스 `payment/app/RefundPersonResolutionService`, 규칙 판단은 도메인 `RefundCase.resolveByPerson`.
- 증명 못 하는 것: 사람이 PG 화면에서 실제로 환불했는지. 우리는 관리자의 기록을 믿는다.

### 11-18. 범위 밖 (5b)

- ~~사람 담당 건에 자동 취소를 보내는 것(사용자 확인 중, 현재 보내지 않음, 11-13)~~ → 11-19로 대체(자동 회차는 보내지 않음, 지금 처리만 `CHARGED`에서 보냄)
- 운영 데이터 이전(5단계 미배포, 로컬 이전 SQL만)
- 고객 주문 응답에 환불 상태 노출(Q4 유지), 고객 알림
- 부분 환불, 정산 파일 대사, 실제 PG 연동(취소 API는 [가정])

### 11-19. 5c 막힌 원인·지금 처리 [사용자 결정 w10s5c, 참모 19:48]

- 사용자: "사람이 PG사에 전화하는 게 아니라 버튼을 눌러 보지 않을까?" / "PG는 멀쩡한데 우리 문제면 왜 그런지 DLT처럼 확인·분석할 수 있어야" / 되돌린 뒤 기한: "바로 처리가 가능해야겠지."
- 그래서 버튼은 누르면 그 자리에서 이 건 하나를 처리하고 결과를 바로 돌려준다. 끝나지 않으면 사람 담당 그대로, 막힌 원인만 갱신된다.

**마지막 확인 칸** (`payment_refund_cases`, 자동 회차·지금 처리 모두 갱신)

| 칸 | 뜻 |
| --- | --- |
| `last_check_at` | 마지막 확인 시각 |
| `last_check_outcome` | 마지막 확인 결과(아래 9값) |
| `last_check_cause` | 원인. 예외 클래스 단순 이름이나 우리 코드가 만든 고정 문자열(HTTP 상태 코드 등)만. 없으면 null |

| outcome | 언제 |
| --- | --- |
| `PG_UNREACHABLE` | 조회가 PG에 닿지 않음(타임아웃·연결·조회 예외), 조회 회로가 막혀 PG를 부르지 않음 |
| `PG_ERROR` | PG가 오류로 답함 |
| `APPLY_FAILED` | 조회 결과는 받았는데 우리 반영이 예외로 실패(DB·버그) |
| `MISMATCH` | 거래 불일치·복수 거래, 청구 확인 이후 돈에 일치 단일 FAILED(사실이 엇갈림) |
| `PENDING` | PG 거래가 아직 PENDING |
| `EMPTY` | 거래 0건 |
| `CANCEL_UNKNOWN` | 환불 요청(취소) 결과 모름·미발송·호출 예외 |
| `CANCEL_REJECTED` | PG가 취소를 효과 없이 거절 |
| `OK` | 마지막 확인이 정상 처리됨(결론 또는 진행) **[PL 결정 P1]** |

- cause 규칙 **[PL 결정 P4]**: PG가 준 텍스트(거절 코드·응답 본문)와 예외 메시지는 넣지 않는다. 회로로 PG를 부르지 않았으면 null. 최대 100자(`MAX_CHECK_CAUSE_LENGTH`, 칸 `VARCHAR(100)`), 넘으면 자른다.
- counter `commerce.payment.refund_check{outcome}`: outcome 소문자 9값, 회차당 한 번 최종 분류로 commit 뒤 센다.
- **`APPLY_FAILED`는 별도 TX로 남긴다 [PL 결정 P3·P4]**: 반영 TX가 rollback된 **뒤**(잠금이 풀린 뒤) `RefundCheckFailureRecorder`(REQUIRES_NEW)가 마지막 확인 칸만 쓴다. 반영 TX 안에서 쓰면 rollback으로 함께 사라진다. 돈의 칸은 그대로다. 원인 기록이 실패해도 원래 예외를 덮지 않는다(ERROR 로그에 `causeType`만 남기고 원래 예외를 다시 던진다).
- 알림 `PaymentRefundApplyFailed`(warning): `sum(increase(commerce_payment_refund_check_total{outcome="apply_failed"}[15m])) > 0`, `for: 5m` [임시]. PG가 아니라 우리 쪽 문제다. 대시보드 패널 "환불 추적 막힌 원인", runbook 앵커 `#paymentrefundapplyfailed`.
- 관리자 목록(11-17) 항목에 `lastCheckAt`·`lastCheckOutcome`·`lastCheckCause`를 더한다.

**지금 처리** — `POST /api-admin/v1/payments/refund-cases/{orderId}/process-now` (관리자 HTTP Basic + CSRF, 래퍼 `scripts/ops/admin-curl.sh`)

- 받는 대상: 사람 담당 중(담당 종료 전) + 돈 비종결 4값. 다음 조회 시각과 무관하게 스케줄러와 같은 회차(잠금 아래 검사 → 잠금 밖 조회 → 잠금 아래 반영 → 요청 commit 뒤 취소 → 결과 반영)를 이 건 하나에 동기로 돌린다.
- `CHARGED`(또는 이번 조회로 `CHARGED`가 된 `CHECKING`)에 일치 단일 SUCCESS면 사람 트리거이므로 환불 요청을 보낸다(`REFUND_REQUESTED` commit 뒤 취소 1회). 스케줄러 회차는 여전히 사람 담당 건에 보내지 않는다 **[PL 결정 P2: `canRequestRefund(trigger)` 한 곳]**.
- `REFUND_REQUESTED`·`REFUND_UNKNOWN`은 조회만 한다. 다시 보내지 않는다(11-4).
- 조회 회로가 막혔으면 PG를 부르지 않고 `PG_UNREACHABLE`을 남기고 돌려준다. 돈·담당은 그대로다.
- 응답: 돈의 상태, 환불 주체, 담당, 담당 종료 시각·종료자, 마지막 확인 3칸. 돈이 끝나면 담당을 같은 TX에서 닫고 종료자는 누른 관리자 이름이다. 우리 취소로 끝나면 환불 주체 `SYSTEM`.
- 409: 담당 AUTO, 담당 이미 닫힘, 돈 이미 끝남(대상 그대로). 404: 대상 없음. 403: 비관리자·CSRF 없음. 반영 실패는 오류 응답(보통 500, 잠금 획득 실패는 503)이고 그 건의 `lastCheckOutcome`이 `APPLY_FAILED`다.
- **만들지 않는 것**: 사람 담당 → AUTO 되돌리기, 새 타이머·기한(사용자: "이틀 뒤면 처리한 사람도 까먹겠다").
- 거절 뒤 재누름 **[참모 20:42 확정 ①]**: PG가 취소를 효과 없이 거절하면(`CANCEL_REJECTED`) 돈은 `CHARGED`로 돌아가고 사람 담당 그대로다. 지금 처리를 다시 누르면 취소를 다시 보낸다(누를 때마다 1회, `refund_requested_at` 덮어씀). PG가 첫 요청이 효과 없었다고 명시한 경우라 이중 환불 위험이 없다는 판단이다.
- 누를 때마다 경계 INFO 한 줄 **[참모 20:42 확정 ①]**: `지금 처리를 눌렀습니다. orderId=…, admin=…, outcome=…`. MDC `orderId`, 누른 관리자 이름(인증 이름), `outcome`(= `last_check_outcome`). 칼럼은 더하지 않는다. 비밀값·PG 텍스트·거래 키는 넣지 않는다.
  - 반영 실패로 끝나도 `outcome=APPLY_FAILED`로 한 줄 남기고 원래 예외를 다시 던진다 **[PL 결정 w10s5c-P5]**.
  - 409·404 거절도 한 줄 남긴다. `outcome` 대신 `rejected=CONCLUDED|NOT_PERSON|NOT_FOUND`(로그 전용 값, enum·칼럼 추가 없음) **[PL 결정 w10s5c-P5]**.
  - 대상 잠금을 얻지 못해 503으로 끝나면 `rejected=BUSY`로 남긴다. 원인 기록이 없는 경우라 `APPLY_FAILED`와 섞지 않는다 **[PL 결정 w10s5c-P5]**.

**로컬 이전 SQL** (`sql/migrate-payment-refund-cases-v3.sql`): v2 뒤에 실행한다. 세 칸을 NULL 허용으로 더한다(information_schema + PREPARE, v2와 같은 방식). 다시 실행하면 아무것도 바꾸지 않는다.

## 12. 검증 범위·남은 위험

무엇을 증명했고 무엇을 증명하지 못했는지 한곳에 모은다. 테스트 통과는 아래 표의 범위만 뜻한다.

> 로컬·테스트 환경 검증이며 운영 배포·운영 완료가 아니다.

### 12-1. 증명한 것

**테스트**

| 검사 | 결과 | 비고 |
| --- | --- | --- |
| `./gradlew :apps:commerce-api:check`(test·e2eTest·Checkstyle·ArchUnit) | test 1143·e2eTest 4, 실패·skip 0 (`docs/week10/evidence/20261126T120742Z-f9dbdfde/`) | 직전 기록: 84dcede에서 test 1135·e2eTest 4, 실패·skip 0 |
| 다른 모듈 검사 | commerce-streamer test 47·commerce-batch test 54, 실패·skip 0 (`docs/week10/evidence/20261126T120742Z-f9dbdfde/`) | — |
| `scripts/ops/check-alerting.sh`(promtool·amtool·대시보드·가이드 테스트) | promtool 40·guide 16 (6단계 2번 회귀) | 직전 기록: rules 12, promtool 40, guide 16, ALL CHECKS PASSED |
| 사고 연결 E2E(8절) | 제출용 한 벌 `docs/week10/evidence/20261125T082202Z-068161dd/` | H1 채택·H2·H3 기각, 복구 뒤 증거 해시 같음 |

**변이(판별력)** — 결함을 넣어 테스트가 실패하는지 본 것. 모두 원복을 diff·바이트 비교로 확인했다.

| 단계 | 변이 수 | 무엇을 깨 봤나(요지) | 잡은 테스트 |
| --- | --- | --- | --- |
| 1단계 관측 | 4 | 결과 counter를 UNCHANGED·CONFLICT에서 세기, commit 전 즉시 세기, PG 조건 제거 | C2·C2b·C3·G1 |
| 1b | 1 | 미발송을 `failed`로 세기 | `PaymentOutcomeCounterIntegrationTest` 2건 |
| 2단계 사고 E2E | 6 | create 재전송, 반영 두 번, 유실 주입 제거, 유실 거래 PENDING, 본인 확인 제거, 장부 덮어쓰기 | M1~M6, 단계별 단언 |
| 4a 운영 도구 | 일괄·counter 12, 규칙 9 | 연속 실패 리셋·집계, 회로 차단 조건, pacer 위치, 예외 시 중단, counter 즉시 증가 / 규칙 건수·for·비율 조건 제거 등 | 일괄 서비스·counter rollback 테스트, promtool |
| 4b 확장 | 가이드 17, p99 규칙 4 | 제한 시간·식별자 차단 등 / 건수·for·기준·uri | `test_guide.py`, promtool |
| 5단계 늦은 성공 환불 | 서비스 10, 규칙 8 | 재요청 허용, 요청 전 commit 제거, 결론 재처리, 상한 제거, 잠금 아래 재확인 제거, 합류 잠금 제거, 만료 TX 밖 생성, counter afterCommit 제거 2 | F2·F4·F7·F9·F10, 서비스, JoinLock, rollback 테스트 |
| 5b 두 축 모델 | 14 + 규칙 13 | jitter 제거, PERSON 취소 허용, findDue AUTO만, 자동 종료 제거, 담당 검사·NO_CHARGE 모순 검사 제거, label 대문자, counter afterCommit 제거 2 등 | S1~S10, promtool |
| 5b R1·R3 | 2 | 기한 재확인 제거, R1 조건 되돌림 | 서비스·통합 2, 도메인·HTTP 2 |
| 5c 막힌 원인·지금 처리 | 7 + 규칙 2 | 원인 기록을 반영 TX 안에서, 사람 트리거 분기를 스케줄러에, 요청 뒤 재전송, 담당 검사 제거, counter 사전 등록 제거, MISMATCH→OK, 기록기 예외로 덮기 | C1·C4·C5·C7·C8·C9·R3, QA 보강 |

- 변이 기록 원본은 단계별 review 파일이다(저장소 밖). 이 표는 요지만 옮겼다.
- 판별하지 못한 변이: 4b 가이드 LLM 여유 0 되돌리기는 5회 중 3회만 검출(시간 의존). 5단계 F3는 재요청 변이를 못 잡는다(F4·서비스 테스트가 판별).

**리허설(사람 흐름)**

| 회 | 기록 | 범위 | 결과 |
| --- | --- | --- | --- |
| 1 | `docs/week10/rehearsal.md`(2026-11-25, 로컬 1회) | 응답 유실 → 알림 → 가이드 → 대시보드 → Loki → 관리자 목록 → 일괄 확정 → 알림 해제 | 성공. 막힌 지점 B1·B3·B4 수정, B2·B5 기록 |
| 6단계 1회차(중단된 사전 실행) | `docs/week10/evidence/20261126T113318Z-6d788078/` | 응답 유실 → 알림 → 일괄 확정(중간에 중단) | 중단. 측정값만 남김: 사고→알림 1m52s, 일괄 확정 4건, createCalls 7→7(증가 0). 제출 근거는 2회차로 한다 |
| 2 | `docs/week10/evidence/20261126T121317Z-965d8579/` | — | — |

### 12-2. 증명하지 못한 것

| 항목 | 왜 못 했나 / 무엇이 위험한가 | 지금의 대응 |
| --- | --- | --- |
| 실제 PG 취소 API | 과정 PG 계약에 취소가 없다. 요청·응답 모양은 [가정](11-6)이고 test-pg만 구현한다 | 실제 PG가 다르면 어댑터만 바꾼다 |
| 새 환불 지표의 실제 Prometheus 이름 | `refund_cases`·`refund_person_open`·`refund_requested_oldest_age_seconds`·`refund_outcome`·`refund_person_assigned`·`refund_check`는 Micrometer 변환 규칙으로만 대조했다. 실제 scrape 없음. 환불 규칙은 리허설에 넣지 않았다 | promtool 시계열 테스트로 식 모양만 고정 |
| 임계값 | 모든 [임시] 값(12-3)은 운영 트래픽으로 정하지 않았다 | 측정 뒤 조정 |
| 정산 파일 대사 | PG 정산 파일과 우리 기록을 맞춰 보지 않는다. 사람 기록(11-17)은 관리자의 말을 믿는다 | 범위 밖(11-9·11-18) |
| 취소 멱등 키 | 취소 요청에 멱등 키가 없다(11-6). 한 번만은 상태(`REFUND_REQUESTED` commit 뒤 1회)로만 보장한다. 거절 뒤 지금 처리를 다시 누르면 다시 보낸다(11-19, PG가 효과 없음을 명시한 경우만) | 결과 모름이면 다시 보내지 않고 조회 |
| 대량 장애 때 사람 담당 조회가 밀림 | `findDue`가 AUTO를 먼저 채우고 회차 상한 20은 AUTO·PERSON 합계다(11-15). AUTO 대상이 계속 20건 이상이면 PERSON 건 조회가 밀려 PG 사실로 담당을 자동 종료하는 것이 늦어진다 **[참모 19:41: 처리량 상한 20 유지가 우선]** | 사람은 지금 처리(11-19)로 한 건씩 당길 수 있다 |
| 지금 처리 반영 실패의 로그 | 반영이 실패하면 응답은 500이다(첫 잠금 획득 실패는 503·`rejected=BUSY`이고 원인 기록이 없다. 다음 행). 누름 INFO(`outcome=APPLY_FAILED`)는 남지만 causeType ERROR 줄은 남지 않는다(일반 advice) | 목록의 `lastCheckOutcome=APPLY_FAILED`·`lastCheckCause`로 확인(runbook) |
| 지금 처리 두 번째 잠금 실패 시 기록과 누름 로그가 엇갈림 | 지금 처리는 잠금을 두 번 잡는다. 첫 잠금 실패(503)는 회차가 돌지 않아 원인 기록이 없고 누름 로그는 `rejected=BUSY`다. 반영 TX의 두 번째 잠금에서 실패하면 원인 기록(`last_check_outcome`)은 `APPLY_FAILED`인데 누름 로그는 `rejected=BUSY`다. 한 사건이 두 이름으로 남는다 **[w10s5c-P5 추가, 드문 경로라 동작은 둔다]** | runbook: 503이면 잠시 뒤 다시 누른다. 목록의 `APPLY_FAILED`가 같은 시각 BUSY 누름과 겹치면 잠금 경합으로 읽는다 |
| 주문의 포인트·쿠폰 실제 사용 | 주문이 포인트·쿠폰을 실제로 쓰는 경로(차감·쿠폰 사용 상태)는 주문 API에 없어(week8 23절) 증명 범위 밖이다 | E2E는 0이 아닌 잔액(5,000·10,000)·발급 쿠폰을 가진 채 사고를 겪고, 복구·반복·후속이 다시 차감·반환하지 않음(fault 시점 잔액·쿠폰 목록·가용 재고·잡힌 수량과 같음)까지 본다 **[참모 확인 중 w10s6-1]** |
| 테이블 없는 DB의 v3 이전 SQL | `migrate-payment-refund-cases-v3.sql`을 `payment_refund_cases`가 없는 DB에서 돌려 보지 않았다 | v2 뒤에 실행 |
| 다중 인스턴스 | 잠금·한 번만 규칙은 테스트 DB의 동시성 테스트로만 봤다. 여러 인스턴스 배포는 하지 않았다 | 행 잠금으로 직렬화 |
| runbook 에스컬레이션 대상 역할 이름 | **[미정]** 팀 정의 사항이다. runbook은 "에스컬레이션 대상 [미정]"으로만 적었다 | 팀이 정하면 runbook만 고친다 |
| 실제 Slack·LLM·Grafana MCP | 연결·호출하지 않았다(9-8·10-8). Viewer 토큰 생성은 승인 대상 | 가짜 수신기, 결정적 요약 |
| 만료 경로 리허설 | `PaymentAbandonedPiling`은 리허설하지 않았다(10-8) | promtool 테스트만 |

**그 밖에 남은 사실** (단계별 review "사실로 남기는 것" 중 HEAD에서 아직 유효한 것)

| 출처 | 사실 |
| --- | --- |
| 1단계 | `payment_records.provider_order_id` 인덱스 없음. callback 조회가 전체 탐색일 수 있다(6절) |
| 1단계 | 만료 INFO는 commit 전에 남긴다. 만료 TX가 rollback되면 INFO만 남을 수 있다(4-2) |
| 1b | 잠금 전 스냅샷과 잠금 뒤 재조회 사이 경합이면 Verified인데 MDC에 거래 키가 없을 수 있다(남의 키는 붙지 않음, 추론) |
| 2단계 | ~~E2E의 포인트·쿠폰 불변은 0과 0 비교라 판별력이 약하다~~ → 고쳐짐(w10s6 C2, 42ee823): 0이 아닌 잔액·발급 쿠폰을 fault 시점 값과 비교한다. 변이(복구 경로 포인트 추가 차감)가 RECOVERY에서 검출됨. 남은 범위는 위 "주문의 포인트·쿠폰 실제 사용" 행 |
| 4a | `unknown_entered`의 PENDING→UNKNOWN 경로 테스트가 없다(9-8) |
| 4a | 일괄 확정의 시작 전 회로 검사는 목록 안 종결 주문의 PG까지 본다. 종결 주문만 막힌 PG에 걸려도 전체 503이다 |
| 4a | pacer 대기가 interrupt되면 요청 전체가 예외로 끝나 부분 결과를 잃는다 |
| 4a | 미확인 목록의 결제 대기 수만 건 규모 쿼리 계획은 확인하지 않았다(9-8) |
| 4b | streamer의 `spring.application.name`이 `commerce-api`다. local로 함께 띄우면 같은 로그 파일·같은 8081을 쓴다(10-8) |
| 4b | Alloy 읽기 위치(`/var/lib/alloy/data`)가 볼륨이 아니다. 재기동하면 같은 줄이 다시 들어갈 수 있다(추정) |
| 4b | `grafana/grafana` 이미지 버전 미고정. 가이드 실패 시 Slack 자리에 원본 두 번, resolved 통지 최대 5분 늦음(10-4·10-8) |
| 5단계 | 관리자 환불 목록은 `opened_at` 순 정렬인데 그 칸 인덱스가 없다. 대상이 많이 쌓이면 정렬 비용이 커진다 |
| 5단계 | 기능 이전에 만료된 주문은 늦은 성공이 와야 대상이 생긴다(소급 생성 없음). 취소 호출 예외 때 WARN이 2줄 남는다 |
| 5b | v2 이전 SQL은 옛 `CLOSED_NO_CHARGE`의 외부 환불(CANCELED) 구분을 잃는다(로컬 데이터만) |
| 5b | 비종결 돈의 상태 문자열이 `UnresolvedPaymentQueryService`·`PaymentRefundCaseJpaRepository.findDue`·`RefundCaseMetrics`에 따로 있다(경미한 중복) |

- 이미 고쳐져 뺀 것: 기한 직후 취소 경계(R3, 11-13), 수동 reconcile 반영 실패의 원문 ERROR(6절), gauge 갱신 풀 경합(7절), 래퍼·4a counter 실제 수집(리허설), runbook 상대 경로(GitHub URL), Grafana MCP의 Loki 원시 로그(범주 제외).

### 12-3. [임시] 값

코드 경로 줄임: `RC` = `payment/domain/RefundCase.java`, `RTS` = `payment/app/RefundTrackingService.java`(모두 `apps/commerce-api/src/main/java/com/loopers/domains/` 아래), `app.yml` = `apps/commerce-api/src/main/resources/application.yml`, `rules` = `docker/prometheus/rules/payment.yml`.

| 값 | 위치(파일:줄) | 뜻 | 바꾸는 곳 | 문서 | 대조 |
| --- | --- | --- | --- | --- | --- |
| 15s | `app.yml:65` | 미확인·환불 gauge DB 갱신 주기 | yml `commerce.payment.unresolved-metric.refresh-interval` | 4-1 | 일치 |
| 0.2%·20건·10m | `rules:34·36·37` | `PaymentUnresolvedPiling` 유입 비율·unknown 건수·지속 | 규칙 + `payment_test.yml` | 9-2 | 일치 |
| 5건·10m 창·10m | `rules:50·51` | `PaymentAbandonedPiling` | 규칙 | 9-2 | 일치 |
| 15m | `rules:63` | `PaymentNeedsReview` 지속 | 규칙 | 9-2 | 일치 |
| 30%·10건·5m | `rules:78·80·81` | `PaymentDeclineSurge` | 규칙 | 9-2 | 일치 |
| 5건·5m | `rules:92·93` | `PaymentPgUnreachable` | 규칙 | 9-2 | 일치 |
| p99 > 1s·5m 100건·10m | `rules:112·115·116` | `PaymentApiLatencyP99High`(결제 API 지연) | 규칙 | 9-2 | 일치 |
| 1h·4h·4h | `docker/alertmanager/alertmanager.yml:22·27·31·17` | repeat_interval critical·warning·기본 | Alertmanager 설정 | 9-2 | 일치 |
| 50건 | `app.yml:104`(기본값 `order/config/PaymentReconcileBatchConfig.java:12`) | 일괄 확정 한 번 최대 | yml `payment.reconcile-batch.max-orders` | 9-6 | 일치 |
| 200ms(test 0ms) | `app.yml:105`(test `:185`) | 일괄 확정 PG 조회 간격 | yml `payment.reconcile-batch.interval` | 9-6 | 일치 |
| 3건 | `app.yml:106` | 일괄 확정 연속 실패 한도 | yml `payment.reconcile-batch.consecutive-failure-limit` | 9-6 | 일치 |
| 10MB·3개·100MB | `supports/logging/src/main/resources/appenders/json-file-appender.xml:6·7·8` | local JSON 로그 롤링 | `logging.logback.rollingpolicy.*` | 10-2 | 일치 |
| 72h | `docker/loki/loki.yml:32` | Loki 보존 | Loki 설정 | 10-3 | 일치 |
| 10s | `docker/guide/guide.py:90`(환경 변수 `:104`) | 가이드 근거 수집 제한 시간 | `GUIDE_TIMEOUT_SECONDS` | 10-4 | 일치 |
| 5m | `rules:205` | `GuideServerDown` 지속 | 규칙 | 10-4 | 일치 |
| 2일 | `RC:28` | 자동 추적 기한 `handoff_at` | 도메인 상수(P8: 설정 아님) | 11-2·11-5 | 일치 |
| 4일 | `RC:29` | 고객 약속 `promise_at` | 도메인 상수 | 11-2·11-5 | 일치 |
| 1m | `RC:37`(사용 `RTS:256·333`, `order/app/OrderApplicationService.java:312`) | 첫 조회·환불 요청 뒤·결과 모름 뒤 jitter 상한, 배증 시작값 | 도메인 상수 | 11-5·11-14 | 일치 |
| 1h | `RC:38`(배증 `RC:321~325`) | 결론 없는 조회 뒤 상한의 최대 | 도메인 상수 | 11-5·11-14 | 일치 |
| 30m | `RC:39`(사용 `RTS:314·355`, `OrderApplicationService.java:521·526`) | 사람 담당 조회 jitter 상한 | 도메인 상수 | 11-13·11-14 | 일치 |
| 20건 | `RTS:36` | 환불 추적 회차 상한(AUTO·PERSON 합계) | 코드 상수 | 11-5·11-15 | 일치 |
| 30s | `app.yml:98` | 환불 추적 회차 주기 | yml `payment.refund.scheduler.fixed-delay` | 11-5 | 일치 |
| 100자 | `RC:41` | `last_check_cause` 최대 길이(칸 `VARCHAR(100)`) | 도메인 상수 + DDL | 11-19 | 일치([임시] 아님, P4) |
| 15m | `rules:132` | `PaymentRefundNeedsPerson` 지속 | 규칙 | 11-16 | 일치 |
| 600s·5m | `rules:144·145` | `PaymentRefundRequestStuck` | 규칙 | 11-16 | 일치 |
| 30m | `rules:158` | `PaymentRefundUnknown` 지속 | 규칙 | 11-8·11-16 | 일치 |
| 10건·30m·10m | `rules:171·173·174` | `PaymentRefundPiling` | 규칙 | 11-16 | 일치 |
| 15m 창·5m | `rules:188·189` | `PaymentRefundApplyFailed` | 규칙 | 11-19 | 일치 |
| 2m | `app.yml:100` | 결제 만료 | yml `payment.expiry.timeout` | 8주차 26절(이 문서는 인용만) | 일치 |
| 1s·3s | `app.yml:76·77` | PG 연결·읽기 timeout | yml `payment.providers.test-pg.*` | 8주차 21절 | 일치 |

- 리허설 전용 값(10-7, `docker/prometheus/rules/rehearsal/`)은 운영 값이 아니라 표에서 뺐다.

### 12-4. 정리 범위

검증이 만든 자원만 정리한다. 평소 쓰는 자원은 건드리지 않는다. 로컬·테스트 환경 검증이며 운영 배포·운영 완료가 아니다.

| 실행 | 정리한 것 | 건드리지 않은 것 |
| --- | --- | --- |
| 사고 E2E·e2eTest | Testcontainers가 띄운 MySQL·Redis·Kafka 컨테이너(테스트 종료 때 자동 정리) | 평소 `docker` compose 프로젝트 컨테이너·볼륨(`docker_mysql-8-data` 등), 다른 프로젝트 컨테이너 |
| 리허설 1회(`docs/week10/rehearsal.md`)·6단계 1회차(중단) | 리허설 compose 프로젝트 `w10r-*`만 `scripts/ops/rehearsal.sh down`(`docker compose down -v`, 스크립트는 `w10r-infra`·`w10r-mon`만 내린다), 리허설로 띄운 test-pg·앱 프로세스(직접 종료, 스크립트 밖) | 위와 같음 |
| 리허설 2회차 | 앱(bootRun 2회)·test-pg(2회) 프로세스(직접 종료), 리허설 compose 프로젝트 `w10r-mon`·`w10r-infra`만 `rehearsal.sh down`(down -v). 종료 뒤 `w10r` 컨테이너 0, 실행 중 컨테이너 0, 8080~8083 LISTEN 없음 | 평소 `docker` 프로젝트 볼륨(`docker_mysql-8-data`·`docker_kafka-data`·`docker_redis_*`), 다른 프로젝트 볼륨·네트워크(시작 전과 목록 같음), 저장소 `logs/commerce-api.json` |
