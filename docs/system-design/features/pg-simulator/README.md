# PG 시뮬레이터 앱·HTTP 계약

기존 절 번호 참조: [2.8](../order-payment/pg-client.md#28-pg-http-도구와-테스트-서버의-구성) · [4.5](../order-payment/verification.md#45-pg-연동의-검증과-관찰) · [5.7](fixture-contract.md#57-테스트-pg-제어와-상태-보존).

읽기: [테스트 래퍼·프록시](fixture-contract.md) · [PG HTTP adapter](../order-payment/pg-client.md) · [선택 이유](../../decisions/pg-simulator.md) · [전환 검증 기준·과거 실행 기록](../../verification/pg-simulator.md).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

## 기존 PG 연결과 실행 구성

### 테스트와 수동 실행에서 사용하는 PG

PG는 별도 모듈 `apps/pg-simulator`의 단독 실행 앱이다(2026-10-25 전환·검증 완료). 수동 실행에서는 고정 포트로 띄워 commerce-api의 기본 base URL로 호출하고, 테스트에서는 같은 코드를 임의 포트로 시작한다. commerce의 JPA·주문 로직에 의존하지 않는다. 고객 결제 API의 요청·응답은 [로컬 PG HTTP 계약](#55-로컬-pg-http-계약)을, 앱 구성·실행·수명은 [2.10](#210-pg-시뮬레이터-앱의-책임과-수명)을 따른다.

테스트는 PG와 그 앞의 장애 주입 프록시를 loopback 임의 포트에 시작하고 프록시 주소를 base URL로 adapter에 주입한다. 이 PG 포트는 커머스의 콜백 수신 포트와 별개이며, 커머스 포트와 callback URL은 기동 전 선택·동시 주입하는 [5.7](fixture-contract.md#57-테스트-pg-제어와-상태-보존) 절차를 따른다. 테스트가 만든 거래·시나리오만 사용하며 종료 시 HTTP 서버·worker·client를 정리한다. 즉시 SUCCESS·콜백·생성/조회 회로·실제 timeout/풀·재시작 검증은 유지한다.

**근거·대안·비용:** 5주차에는 과제 검증과 YAML 속성 비교가 테스트 안에서 가능하다는 이유로 테스트 내부 fixture만 두었다. 그 결과 commerce-api를 직접 실행하면 PG가 없어 결제가 확정되지 않았다. 2026-10-25 사용자가 직접 실행 확인을 요구해 A.31의 재검토 조건("직접 앱 시연 요구")에 해당했고, 단독 실행 앱으로 전환했다. 모듈·실행 설정 관리와 테스트 래퍼·프록시 분리 비용이 늘어난다. 상세는 [A.33](../../decisions/pg-simulator.md#a33-pg-단독-실행-앱으로의-전환)에 둔다.

## 2.10 PG 시뮬레이터 앱의 책임과 수명

**상태:** 2026-10-25 사용자 검토를 마친 설계다(2f62a88). 같은 날 R1(PG 모듈)·R2(테스트 래퍼·프록시로 기존 fixture 교체)를 구현하고 R3(전체 회귀)·R4(수동 실행)·R5(최종 회귀)로 확인했다. 실행 근거는 4.5의 전환 검증 소절에 둔다. 5주차 테스트 fixture의 계약은 이 절의 이전 판(커밋 `7cffe2d` 시점)에 있다. 결정의 문제·대안·비용은 [A.33](../../decisions/pg-simulator.md#a33-pg-단독-실행-앱으로의-전환)에 둔다.

**선택·근거:** 새 모듈 `apps/pg-simulator`에 JDK 21 `HttpServer` 기반 PG 앱을 둔다. 같은 코드를 두 방식으로 쓴다.

- **수동 실행:** `main`으로 고정 포트(기본 18080)에 띄운다. commerce-api의 기본 `commerce.pg.base-url`과 같은 주소라 commerce-api 설정을 바꾸지 않고 실제 결제 흐름을 끝까지 확인할 수 있다. 결과는 금액 규칙으로 정하고 일정 시간 뒤 자동으로 콜백을 보낸다.
- **테스트:** commerce-api 테스트가 같은 클래스를 임의 포트로 시작한다. 자동 처리는 끄고, 결과 지정·상태 변경·콜백 전송·장애 주입·관측은 commerce-api 테스트 쪽 래퍼가 담당한다([5.7](fixture-contract.md#57-테스트-pg-제어와-상태-보존)).

PG main에는 PG 동작과 **확장점**만 둔다. 테스트 전용 조작(응답 계획, gate, 응답 손상, 요청 기록)은 main에 넣지 않는다. 메모리 저장은 유지하므로 PG 앱을 끄면 거래가 사라진다. 영속 저장·관리 화면·HTTP 제어 endpoint는 두지 않는다.

PG는 commerce의 domain/JPA를 참조하지 않는다. commerce-api main 코드·설정도 이 전환으로 바뀌지 않는다.

### 모듈과 빌드

- `settings.gradle.kts`에 `:apps:pg-simulator`를 추가한다. 루트 빌드는 `apps` 아래 모듈의 일반 jar를 끄고 bootJar를 켜므로, pg-simulator 빌드 파일에서만 일반 jar를 다시 켠다. commerce-api는 `testImplementation(project(":apps:pg-simulator"))`로 의존한다. 이 조합이 실제로 테스트 classpath에 들어가는지는 R1에서 빌드로 확인하며, 안 되면 핵심 로직을 라이브러리 모듈로 나누는 대안을 사용자와 다시 정한다.
- 의존성은 Jackson(JSON)과 Apache HttpClient 5(콜백 전송)이며 버전은 기존 의존성 관리(Boot BOM)를 따른다. Spring Web·Spring 컨텍스트는 쓰지 않는다. 루트 빌드가 모든 하위 모듈에 넣는 공통 의존성은 그대로 둔다.
- 패키지는 `com.loopers.pgsimulator`로 한다. commerce-api의 ArchUnit 검사는 jar를 포함해 `com.loopers` 아래 클래스를 가져오고 `..domain..`·`..application..`·`..infrastructure..`·`..interfaces..` 패키지와 이름 끝의 `Controller`·`Service`·`Repository`·`Facade`에 규칙을 건다(`ArchitectureTest` 코드 확인). PG 앱은 이 패키지 이름과 이름 접미사를 쓰지 않아 규칙 대상이 되지 않게 한다.

### 구성 요소

|`com.loopers.pgsimulator` 타입|공개 여부|책임|
|---|---|---|
|`PgSimulator`|public|`start(PgSimulatorOptions)` → AutoCloseable handle. `baseUrl()`, `transactions()`(저장소 접근), `callbacks()`(콜백 전송기), `close()`. 테스트 래퍼가 쓰는 확장점은 이 세 접근자와 옵션의 결과 정책이 전부다|
|`PgSimulatorOptions`·`Settlement`|public record|`port`(0이면 임의 포트), `outcomePolicy`, `settlement`(`Settlement.DISABLED` 또는 `Settlement.after(delay)`, 음수 지연 거절). 주소는 항상 `127.0.0.1`|
|`OutcomePolicy`|public 인터페이스(확장점)|`CreateOutcome decide(CreateAttempt attempt)`. `CreateAttempt`는 검증을 통과한 생성 요청의 `orderId`·`amount`만 담는다. 카드 값은 넘기지 않는다|
|`CreateOutcome`|public enum|`PENDING_THEN_SUCCESS`, `PENDING_THEN_FAILED`, `IMMEDIATE_SUCCESS`, `REJECT_BEFORE_CREATE`. 앞의 둘은 생성 응답이 202 PENDING이고 자동 처리가 켜져 있을 때만 뒤의 결과로 바뀐다|
|`AmountModuloOutcomePolicy`|public|수동 실행 기본 정책. 아래 금액 규칙|
|`TransactionStore`|public(확장점)|`findAll`, `findByKey`, `findByOrderId`, `setStatus`, `clear`는 public, `create`는 패키지 내부. 동기화한 짧은 메모리 연산과 불변 snapshot. 같은 orderId의 생성도 새 UUID 거래 키로 추가(5.5)|
|`TransactionSnapshot`|public record|`transactionKey`, `orderId`, `amount`, `status`, `callbackUrl`. HTTP 응답에는 앞의 4필드만 쓴다|
|`PaymentStatus`|public enum|`PENDING`, `SUCCESS`, `FAILED`|
|`PaymentHttpHandler`|패키지 내부|세 API의 헤더·입력 검증, 정책 호출, 거래 저장, 자동 처리 예약, 응답|
|`AutoSettlement`|패키지 내부|자동 처리. 단일 스케줄러 스레드로 지연 뒤 상태 결정과 콜백 1회 전송|
|`CallbackSender`|public(확장점)|`CompletableFuture<CallbackResult> send(TransactionSnapshot)`. 저장된 callbackUrl로 알림 1회. 전용 worker 2개·전용 Apache client(연결 300ms·응답 1초·풀 2·풀 대기 200ms, 자동 retry·redirect 끔). 상태를 바꾸지 않고 재전송하지 않는다|
|`CallbackResult`|public record|`sent`, `httpStatus`, `failure`(`CallbackFailure`: 연결 실패·송신 timeout·I/O 오류·종료로 중단), `errorDetail`. 응답 코드와 오류 중 하나만 있다. `sent=false`는 PG 종료로 전송을 시작하지 못한 경우이며, 종료 뒤 `send`도 예외 대신 이 결과로 완료한다|
|`PgSimulatorApplication`|public|`main`. 실행 인자 해석, `AmountModuloOutcomePolicy`·자동 처리로 시작, 종료 hook 등록|

5주차 fixture의 `ScenarioRegistry`·`GateRegistry`·`RequestJournal`·`CallbackJournal`·`LifecycleGuard`·`ResponsePlan`은 PG main으로 옮기지 않는다. 필요한 기능은 [5.7](fixture-contract.md#57-테스트-pg-제어와-상태-보존)의 테스트 래퍼가 맡는다.

### 수동 실행의 결과 규칙과 지연

|`amount % 3`|생성 응답|자동 처리(지연 뒤)|
|---|---|---|
|0|202 PENDING|상태를 SUCCESS로 바꾸고 콜백 1회|
|1|200 SUCCESS|상태 유지, 콜백 1회|
|2|202 PENDING|상태를 FAILED로 바꾸고 콜백 1회|

- 예: 6000원 → PENDING 뒤 SUCCESS, 7000원 → 즉시 SUCCESS, 8000원 → PENDING 뒤 FAILED. 주문 금액은 상품 가격 × 수량의 합계다.
- **422 생성 전 거절은 금액 규칙에 넣지 않는다.** 실습 문서가 요구하는 422 검증은 HTTP 계약 테스트와 업무 시나리오 테스트이고 수동 실행 시연은 요구하지 않는다. 응답을 만드는 코드는 PG main에 두며, 테스트는 결과 정책으로 이 결과를 고른다.
- 무작위 결과·확률은 쓰지 않는다. 같은 금액은 항상 같은 경로로 간다.
- **자동 처리 지연은 기본 1초이며 실행 인자로 바꿀 수 있다(0 허용).** 이유: 0이면 생성 응답 직후 콜백이 도착해 수동 실행에서 주문의 결제 대기 상태를 조회로 관찰할 틈이 거의 없다. 1초는 사람이 confirm 응답을 받고 주문을 조회해 PAYMENT_PENDING을 확인한 뒤 확정을 다시 확인할 수 있게 하려는 관찰용 값이다. **정합성을 위한 값이 아니다.** 지연이 0이어서 콜백이 commerce의 생성 결과 반영보다 먼저 도착해도, commerce는 콜백 내용을 믿지 않고 거래를 권위 조회해 같은 결과 반영 경계로 처리한다(4.3의 callback-first). 운영 PG의 실제 처리 시간을 흉내 낸 값도 아니다.
- 즉시 SUCCESS에도 콜백을 보내는 이유는 실제 PG처럼 알림이 중복으로 와도 추가 효과가 없는지(5.5) 수동 실행에서도 볼 수 있게 하려는 것이다.
- 자동 콜백이 실패해도(commerce가 꺼져 있는 등) 재전송하지 않고 로그만 남긴다. 회복은 commerce의 고객 reconcile이 맡는다. 이 경로는 R4 수동 실행에서 확인했다(4.5).

### 실행

실행 인자 이름은 R0 검토에서 사용자가 바꾸지 않아 그대로 구현했다.

```bash
./gradlew :apps:pg-simulator:bootRun --args='--port=18080 --settlement-delay-ms=1000'
```

- 인자를 생략하면 포트 18080, 지연 1000ms다. 잘못된 값(음수·숫자 아님·포트 범위 밖)이면 시작하지 않고 오류를 출력한다.
- 바인딩 주소는 `127.0.0.1` 고정이다. 외부에 공개하지 않는다.
- 로그는 요청 종류·대상·응답 코드, 자동 처리 결과, 콜백 전송 결과만 남긴다. 카드 값과 요청 원문은 남기지 않는다.
- `bootRun`·`bootJar`가 일반 `main` 메서드로 동작함을 R1에서 확인했다. 실행 인자 이름은 제안대로 구현했다.
- **주의(R4에서 확인):** commerce-api를 `local` 프로필로 띄우면 `ddl-auto: create` 때문에 로컬 `loopers` DB의 엔티티 테이블이 지워지고 다시 만들어진다. 기존 로컬 데이터를 보존하려면 별도 DB를 쓰거나, 스키마가 이미 맞는 DB에서는 `--spring.jpa.hibernate.ddl-auto=validate`로 다시 띄운다. 수동 실행용 프로필·DB 분리는 이번 범위가 아니다.

### 처리 순서

```text
handle(exchange):                         // HTTP worker 16개 고정 풀
  경로·메서드 분류. 계약 밖 → 404/405
  X-USER-ID: 135135 하나가 아니면 → 400 INVALID_PROVIDER_USER
  operation별 처리 → 응답 작성
  예상 밖 예외 → 500 INTERNAL_ERROR (거래 효과가 이미 있으면 지우지 않음)

handleCreate(body):
  본문 상한 16KB, JSON 객체, orderId 6자 이상 문자열, cardType=TEST, cardNo=0000-0000-0000-0000,
  amount 양의 정수, callbackUrl = http://127.0.0.1:{port}/api/v1/payments/callback 만 허용
    → 하나라도 아니면 400 INVALID_REQUEST (정책 호출·거래 생성 없음)
  outcome = outcomePolicy.decide(CreateAttempt(orderId, amount))
  REJECT_BEFORE_CREATE → 거래 저장·자동 처리 예약 없이 5.5의 422 반환
  IMMEDIATE_SUCCESS    → 거래를 SUCCESS로 저장 → 200
  PENDING_THEN_*       → 거래를 PENDING으로 저장 → 202
  자동 처리가 켜져 있고 거절이 아니면 autoSettlement.schedule(transactionKey, outcome)
  // 저장 뒤 응답 전송이 실패해도 거래는 남는다

handleLookupByKey(key):
  key 형식 [A-Za-z0-9_-]{1,255} 아님 또는 없음 → 404 TRANSACTION_NOT_FOUND
  있으면 200 + 4필드

handleLookupByOrder(query):
  orderId query가 비어 있지 않은 값 하나가 아니면 → 400
  200 + 같은 orderId 거래 배열 (없으면 빈 배열)

AutoSettlement.schedule(key, outcome):     // 단일 스케줄러 스레드
  delay 뒤 실행:
    PENDING_THEN_SUCCESS → store.setStatus(key, SUCCESS)
    PENDING_THEN_FAILED  → store.setStatus(key, FAILED)
    IMMEDIATE_SUCCESS    → 상태 변경 없음
    snapshot = store.findByKey(key)
    callbacks.send(snapshot) 완료 시 결과 로그. 실패해도 재전송 없음

CallbackSender.send(snapshot):
  저장 callbackUrl로 {providerOrderId, transactionKey, status} POST 1회를 전용 worker에 제출
  HTTP 응답 코드 또는 통신 오류를 CallbackResult로 완료. 거래 상태는 바꾸지 않음

close():                                   // 여러 번 호출해도 안전
  HTTP server stop → 자동 처리 스케줄러 shutdownNow
  HTTP worker·콜백 worker shutdown → 제한시간(5초) 대기 → 남으면 shutdownNow 후 재대기
  콜백 client 종료
  worker 종료를 확인하지 못하면 IllegalStateException으로 보고 (안전한 재사용으로 기록하지 않음)
```

- 저장소 잠금은 거래 생성·상태 변경·조회의 메모리 연산에만 짧게 잡는다. 응답 전송·콜백 전송·대기 중에는 잡지 않는다.
- 테스트 시작 시에는 자동 처리를 `DISABLED`로 두므로 스케줄러 작업이 생기지 않는다. 생성 뒤의 상태 변경과 콜백은 테스트가 명시적으로 호출할 때만 일어난다(5.7). 5주차 fixture의 "상태 자동 변경·자동 콜백 없음"은 테스트 모드에서 그대로 유지된다.

### 검증

- **pg-simulator 모듈 테스트(R1):** 5.5 계약(202/200/422/400/404, 동일 orderId 2회 생성 시 거래 2건, 조회 0/1/복수), 금액 규칙 세 경로, 자동 처리의 지연 뒤 상태 변경·콜백 1회·실패 시 재전송 없음, 콜백 전송 결과 구분, 자동 처리 끈 상태에서 상태 변경·콜백 0, close 뒤 worker 종료.
- **commerce-api 테스트(R3):** 기존 PG 관련 테스트가 새 구조에서 같은 기대값으로 통과한다. 5주차 fixture 자체 테스트는 PG 동작 검증이 모듈 테스트로, 장애 주입·관측 검증이 5.7 래퍼 테스트로 옮겨 간다. 옮기지 않는 항목은 제거 근거를 남긴다.
- **수동 확인(R4):** PG 앱과 commerce-api를 함께 띄워 세 금액 경로의 주문 최종 상태, 콜백 유실(commerce 중지 중 자동 콜백) 뒤 reconcile 회복을 확인하고 실행 기록을 남긴다.

## 5.5 로컬 PG HTTP 계약

커머스와 별도 HTTP 경계를 가진 로컬 PG의 계약이다. 수동 실행 앱과 테스트가 같은 PG 코드([2.10](#210-pg-시뮬레이터-앱의-책임과-수명))로 이 계약을 제공한다. 실제 카드·실금전·외부 PG 계정은 사용하지 않는다. 아래 생성·거래 조회·주문별 조회 세 요청은 고정 제공자 계정 `X-USER-ID: 135135`를 사용한다. 이 헤더는 커머스 고객 인증과 별개다.

|요청|입력|응답|
|---|---|---|
|POST /api/v1/payments|JSON orderId·cardType·cardNo·amount·callbackUrl|202 PENDING 또는 200 SUCCESS, transactionKey·orderId·amount·status|
|GET /api/v1/payments/{transactionKey}|문자열 거래 키|200과 거래 객체, 없는 거래는 404|
|GET /api/v1/payments?orderId={providerOrderId}|생성 때 사용한 동일 문자열 주문 ID|200과 거래 객체 배열. 빈 배열도 표현|

- orderId는 6자 이상 문자열이고 amount는 원화 정수다. 내부 숫자 orderId와 외부 providerOrderId는 구분하며 생성·주문별 조회에 동일한 매핑을 쓴다. 매핑은 LP-ORD-와 내부 양수 주문 ID의 최소 10자리 숫자를 결합한다. 예: 42 → LP-ORD-0000000042. 자릿수 초과를 자르지 않고 저장한 값을 재사용한다.
- cardType=TEST·cardNo=0000-0000-0000-0000의 합성 값만 사용한다. 생성 요청의 callbackUrl은 서버 로컬 설정으로 고정하고 고객이 임의 외부 주소를 지정하게 하지 않는다. 카드 번호는 DB·로그에 저장하지 않는다.
- 생성은 호출자 멱등 키를 받지 않으며 같은 orderId로 새 POST를 보내면 중복 거래가 생길 수 있다. paymentAttemptKey를 PG가 중복 제거해 준다고 가정하지 않는다.
- 정상 비동기 생성은 202·PENDING이다. 즉시 성공은 200·SUCCESS로 반환한다. 고정 PG 경계와 요청 주문·금액·거래 정보를 검증한 즉시 SUCCESS는 추가 조회 없이 공통 TX B로 반영한다.
- transactionKey는 `[A-Za-z0-9_-]{1,255}`이며 대소문자를 구분하고 trim하지 않는다. 콜백의 잘못된 키 형식은 400 INVALID_REQUEST, PG 응답의 잘못된 형식은 UNAVAILABLE 관찰로 처리한다. 형식이 유효한 콜백 알림의 거래 키는 권위 조회 대상으로만 사용하며, 저장 키와 다르다는 이유만으로 조회 전에 결합하거나 충돌을 확정하지 않는다. 그 키의 정상 거래 부재 404는 UNAVAILABLE이며 기존 업무 상태·저장 키·확보 재고를 유지한다. PG가 거래를 반환하면 조회 요청 대상과 응답의 일치, 내부 주문·저장 거래 관계를 검증하고 불일치는 기존 CONFLICT 계약을 따른다. 요청/응답 키 불일치와 내부 주문/저장 거래 불일치의 회로 분류는 [2.8절](../order-payment/pg-client.md#합의한-조회-회로-표본-분류)을 유지한다. 같은 제공자/계정·주문·금액의 유효 거래를 정확히 반환했지만 저장 키(KA)와 다른 키(KB)이면 회로 성공·업무 충돌이며, TX B가 종결 비교보다 먼저 IDENTITY_MISMATCH로 판정해 요약·확인 정보를 commit한 뒤 409 PAYMENT_RESULT_CONFLICT를 반환한다. KA 교체·KB 결합·업무 상태/재고 변경은 없고, KB 상태가 저장 종결과 반대여도 TERMINAL_MISMATCH로 비교하지 않는다. 주문별 조회의 유효 2건 이상은 기존 MULTIPLE_TRANSACTIONS다. 판정 순서의 원본은 [3.6](../order-payment/model.md#확인-정보의-반영-순서와-종결-처리)이다.
- 조회 GET의 200 이외 2xx 및 3xx는 계약 외 응답으로 회로 실패 집계 후 UNAVAILABLE로 처리한다. 3xx 자동/수동 redirect는 하지 않는다. 조회 202 본문의 PENDING·204를 빈 배열·3xx 본문의 성공/실패로 해석하지 않으며 Location 추가 호출도 없다. 생성 POST의 202 PENDING·200 SUCCESS 계약과 구분한다. 이유·처리·검증 기준은 [2.8절](../order-payment/pg-client.md#28-pg-http-도구와-테스트-서버의-구성)을 따른다.
- 거래 객체의 상태는 PENDING·SUCCESS·FAILED다. HTTP 2xx만으로 SUCCESS로 해석하지 않고, 응답 필드·타입·주문·금액·거래 관계를 검증한다. query는 URI builder로 인코딩하고 거래 키를 경로로 사용할 때도 올바르게 인코딩한다.

### 거래 생성 전 확정 거절

PG가 거래 저장·후속 처리 예약 전에 종료하고 해당 요청의 거래를 나중에도 생성하지 않는 결과만 다음 계약으로 표현한다.

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/json

{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042","amount":7000,"transactionCreated":false}
```

고정 PG의 생성 응답에서 422, 정확한 code, boolean false, 요청 orderId·amount 일치를 모두 검증한 경우에만 내부 REJECTED_NO_EFFECT 관찰로 매핑한다. 로컬 회로 거절·생성 최초 풀 획득 timeout의 확실한 미발송은 별도 LOCAL_NOT_DISPATCHED(원인별 증거는 2.8)이며 이 PG 계약으로 위장하지 않는다. 이 결과는 PAYMENT_FAILED·예약 한 번 반환으로 연결하고 transactionKey를 꾸며 저장하지 않는다.

본문 없는 422·다른 code·다른 4xx/5xx·응답 유실·해석 불가 응답은 효과 0건의 증거가 아니다. 초기 관찰은 UNKNOWN으로 남기고 기존 거래를 확인한다. 조회 404·빈 목록도 이 생성 거절과 다르다.

### PG 생성·콜백 예시

```json
{
  "orderId": "LP-ORD-0000000042",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "amount": 7000,
  "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback"
}
```

다음 거래 객체는 PENDING 응답에서는 202, 즉시 SUCCESS 응답에서는 200으로 반환한다. 아래 예시는 즉시 성공이다.

```json
{
  "transactionKey": "TEST-TX-0042",
  "orderId": "LP-ORD-0000000042",
  "amount": 7000,
  "status": "SUCCESS"
}
```

PG는 callbackUrl로 아래 알림을 보낼 수 있다. 수동 실행 앱은 금액 규칙에 따른 자동 처리로 1회 보내고(2.10), 테스트는 명시적으로 보낸다(5.7). 테스트에서는 콜백의 유실·중복·지연·순서 변경을 제어해 검증하며, 즉시 성공 응답 뒤 콜백이 와도 추가 효과가 없어야 한다. 거래 상태를 응답하는 것과 콜백 전달 성공은 서로 다른 사실이다.

```json
{
  "providerOrderId": "LP-ORD-0000000042",
  "transactionKey": "TEST-TX-0042",
  "status": "SUCCESS"
}
```

### PG 오류와 내부 오류의 구분

|상황|커머스의 판단|내부 주문·재고|
|---|---|---|
|생성 200 SUCCESS를 식별·금액 검증|최종 성공 관찰|공통 TX B 성공 반영|
|생성 202 PENDING|처리 중 거래 존재|미확정·예약 유지|
|유효한 거래 조회 FAILED|확정 실패|공통 TX B 실패·예약 반환|
|검증한 생성 전 확정 거절|외부 효과 0건|공통 TX B 실패·예약 반환|
|생성 응답 유실·timeout·형식 해석 불가|최종 결과를 확인하지 못함|UNKNOWN·예약 유지, create 재시도 없음|
|조회 실패·회로 차단·404·0건|이번 확인 불가. 기존 성공·실패로 종결할 증거 없음|기존 상태·예약 유지|
|복수 거래·파싱 가능한 식별/금액 불일치·상충 종결|결과 충돌|기존 종결 덮어쓰기·임의 반환 없음, PAYMENT_RESULT_CONFLICT|
|커머스 TX B 저장 실패|내부 기술 오류|해당 TX rollback·500. 같은 외부 거래를 대사해 재반영|
