# PG 테스트 래퍼·프록시 계약

기존 절 번호 참조: [2.9](../order-payment/pg-client.md#29-http-제한과-pg-회로의-초기-정책) · [4.3](../order-payment/processing.md#43-주문-확정) · [4.5](../order-payment/verification.md#45-pg-연동의-검증과-관찰).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

## 5.7 테스트 PG 제어와 상태 보존

**상태:** 2026-10-25 PG 단독 실행 앱 전환([2.10](README.md#210-pg-시뮬레이터-앱의-책임과-수명), [A.33](../../decisions/pg-simulator.md#a33-pg-단독-실행-앱으로의-전환))에 맞춘 설계다(사용자 검토 완료). 아래 "테스트 구성과 책임 분리"가 새로 추가된 부분이고, 나머지 소절의 제어 메서드·plan·관측·콜백 판정·수명 계약은 **테스트가 보는 의미를 유지**한다. 각 소절에서 바뀐 것은 구현 위치뿐이며, 바뀐 곳에 그 사실을 적었다. 예외는 관측 의미의 callbackSent 집계 시점 하나다(2026-10-25 사용자 결정). **2026-10-25 R2 구현 완료:** 기존 `fixture/pg`를 그 자리에서 교체해 이 구조로 동작한다. 전체 회귀(R3·R5)도 통과했다(근거는 4.5).

### 테스트 구성과 책임 분리

```text
commerce adapter ──HTTP──▶ PgFaultProxy (commerce-api test) ──HTTP──▶ PgSimulator (pg-simulator main)
                             · 수신 기록·카운터                        · 5.5 계약·거래 저장
                             · plan의 reply 적용                       · ScriptedOutcomePolicy로 결과 결정
                               (DELAY·GATE·CLOSE_EARLY·RAW)            · 자동 처리 DISABLED
commerce callback ◀──HTTP── PgSimulator.callbacks()  (테스트 래퍼가 명시 호출할 때만, 프록시를 거치지 않음)
```

commerce-api 테스트 소스의 `com.loopers.fixture.pg`에 아래 래퍼를 둔다. 기존 테스트가 쓰는 공개 API(`PgFixtureServer`의 메서드, `ResponsePlan`·`CreateOutcome`·`PgOperation`·`FixtureState`와 하위 record)는 이름과 의미를 유지한다. 그래서 기존 테스트의 변경은 import 정리 수준으로 제한하고 기대값은 바꾸지 않는다. 단 거래 상태 enum은 PG main의 `com.loopers.pgsimulator.PaymentStatus`를 그대로 쓰므로 해당 import가 바뀐다.

|테스트 래퍼 타입|책임|
|---|---|
|`PgFixtureServer`|`start()`: `PgSimulator`를 임의 포트·`ScriptedOutcomePolicy`·자동 처리 `DISABLED`로 시작하고, 그 앞에 `PgFaultProxy`를 임의 포트로 시작한다. `baseUrl()`은 **프록시 주소**다. 아래 제어 메서드를 PG 확장점(`transactions()`·`callbacks()`)과 프록시·기록 구성 요소로 구현한다|
|`PgFaultProxy`|JDK `HttpServer`(loopback, worker 16). 요청을 PG에 그대로 전달하고 PG 응답을 끝까지 받은 뒤 plan의 reply를 적용해 commerce에 응답. 수신 기록·카운터·진행 중 요청 수·gate 상태의 출처|
|`ScriptedOutcomePolicy`|PG main의 `OutcomePolicy` 구현. orderId별 createOutcome FIFO에서 하나를 꺼내 PG의 `CreateOutcome`으로 바꾼다: 테스트 plan의 PENDING → `PENDING_THEN_SUCCESS`, SUCCESS → `IMMEDIATE_SUCCESS`, REJECT_BEFORE_CREATE → `REJECT_BEFORE_CREATE`. 비어 있으면 PENDING과 같다. 자동 처리가 꺼져 있으므로 `PENDING_THEN_SUCCESS`여도 상태는 PENDING에 머문다|
|`ScenarioRegistry`|operation+target별 reply FIFO. 프록시가 원자적으로 하나 소비. 비어 있으면 NORMAL|
|`GateRegistry`·`RequestJournal`·`CallbackJournal`|5주차와 같은 역할. 프록시와 래퍼만 쓴다|
|`LifecycleGuard`|프록시 요청 접수·콜백 작업 접수와 reset·close를 직렬화. 5주차보다 범위가 좁다(PG 내부 수명은 `PgSimulator.close()`가 맡음)|

**plan의 분리와 소비 정렬:** `enqueuePlan(CREATE, orderId, plan)`은 plan 하나를 createOutcome(→ `ScriptedOutcomePolicy`의 FIFO)과 reply(→ `ScenarioRegistry`의 FIFO)로 나눠 같은 순서로 넣는다. 조회 plan은 reply만 넣는다. 두 FIFO는 다음 규칙으로 함께 한 칸씩 소비되므로 어긋나지 않는다.

- PG는 헤더·입력 검증을 통과한 생성 요청에서만 정책을 호출한다(createOutcome 소비). 검증 실패는 400이며 정책을 호출하지 않는다.
- 프록시는 PG 응답이 **400이 아닐 때만** reply를 소비한다. PG가 400을 내는 경우는 헤더·입력 검증 실패뿐이다. 그래서 5주차의 "헤더·입력 거절은 plan을 소비하지 않는 400" 의미가 유지된다.
- PG 500(예상 밖 예외)은 PG 코드 결함이다. 프록시는 reply를 소비하고 500을 그대로 전달하며, 그 테스트는 실패로 다룬다. 정렬이 어긋날 수 있으나 이미 실패한 실행이다.
- **한계(2026-10-25 사용자 수용):** 같은 orderId에 plan이 2개 이상 등록된 상태에서 생성 요청이 동시에 들어오면 두 FIFO의 짝이 요청별로 엇갈릴 수 있다. PG는 요청을 처리할 때 createOutcome을, 프록시는 PG 응답을 받은 뒤 reply를 꺼내므로 두 시점의 순서가 다를 수 있기 때문이다. 예를 들어 [즉시 SUCCESS+DELAY, PENDING+NORMAL]을 등록했는데 요청 A가 PG에서 먼저 처리되고 요청 B의 응답이 프록시에 먼저 돌아오면, A는 즉시 SUCCESS+NORMAL, B는 PENDING+DELAY를 받는다. 5주차 fixture는 plan 하나를 한 번에 꺼냈으므로 이 엇갈림이 없었다. **plan을 둘로 나눠 새로 생긴 제약이다.**
  - **통제:** 이런 조합이 필요한 테스트는 앞 요청이 끝난 뒤 다음 요청을 보내거나, gate로 앞 요청의 응답을 붙잡아 실제 수신 순서를 강제한다. 같은 orderId에 plan을 하나만 등록하는 테스트는 영향이 없다.
  - **실제로 드문 이유:** commerce는 같은 주문의 PG 생성 POST를 한 번만 보낸다(동시 confirm도 발송 권한을 얻은 실행 하나만 보냄, 4.3). 같은 orderId로 생성을 여러 번 보내는 경우는 테스트가 commerce를 거치지 않고 직접 보내는 복수 거래 준비뿐이며 순차 전송이다. 기존 테스트에 이 조합이 실제로 없는지는 R3에서 확인한다(미확인).
  - **고르지 않은 대안:** 프록시가 plan을 통째로 꺼내 createOutcome을 요청 헤더로 PG에 넘기면 엇갈림이 없다. 대신 PG main이 테스트가 붙인 헤더를 읽는 코드를 갖게 되어, main에 테스트 조작을 넣지 않는다는 결정([A.33](../../decisions/pg-simulator.md#a33-pg-단독-실행-앱으로의-전환))과 부딪힌다. 위 조합이 드물다는 판단으로 main을 깨끗하게 두는 쪽을 택했다. R3에서 이 조합이 필요한 테스트가 여럿 나오면 재검토한다.

```text
PgFaultProxy.handle(exchange):
  lifecycle.tryEnterRequest() 실패(종료 중) → 응답 없이 닫음
  경로·메서드 분류 → 계약 밖이면 PG에 전달만 하고 기록하지 않음
  journal.begin(operation)  // createReceived 등 카운터의 출처
  요청 본문 읽기(상한 16KB). CREATE면 본문 JSON의 orderId를 target으로 기록(해석 불가면 null)
  PG에 같은 메서드·경로·query·헤더(X-USER-ID 포함)·본문으로 전달, 응답 전체 수신
    PG 연결 실패 → 502로 응답하고 journal에 기록 (테스트 장치 오류)
  PG 응답 400 → 그대로 전달, plan 소비 없음
  그 외 → plan = scenarios.poll(operation, target) 또는 NORMAL
    CREATE 응답이면 transactionKey를 journal에 기록
    NORMAL → PG 응답 전달
    RAW → plan의 상태·본문·헤더로 응답 (PG 응답은 버림)
    DELAY → delayMs 대기(종료 시 중단) 뒤 PG 응답 전달
    GATE → gate 해제까지 최대 10초 대기. 시간 초과·종료 중이면 응답 없이 닫고 실패 기록
    CLOSE_EARLY → 전체보다 긴 Content-Length를 선언하고 본문 절반만 보낸 뒤 연결 종료
  journal.complete(응답 코드 또는 실패 종류); lifecycle.exitRequest()
```

PG가 거래를 저장한 뒤 응답하고 프록시는 그 뒤에 응답을 제어하므로, "거래 효과를 먼저 만들고 응답을 지연·손상한다"는 5주차 순서가 그대로 성립한다. 조회도 PG가 snapshot을 응답한 뒤 프록시가 대기하므로 GATE 중 상태가 바뀌어도 응답 내용은 대기 전 snapshot이다.

### fixture 제어 메서드와 거래 저장의 분리

테스트 준비/관측은 PgFixtureServer handle의 메서드로 수행한다(구현은 위 테스트 래퍼). 별도 제어 HTTP endpoint·JSON 요청/응답·HTTP 400/404/409 규격은 만들지 않는다. 실제 PG 결제 생성/조회 세 HTTP와 PG → 커머스 콜백 HTTP는 유지한다. fixture는 커머스 DB/Entity를 직접 변경하지 않는다.

|fixture 메서드|입력/출력·효과|
|---|---|
|state()|불변 FixtureState snapshot: transactions(key/orderId/amount/status), counts(createReceived/lookupByKeyReceived/lookupByOrderReceived/callbackSent), activeRequests, activeCallbackJobs, gates. callbackUrl·카드·원문 본문/변경 가능한 내부 컬렉션은 노출하지 않음|
|setStatus(key, status)|존재 거래의 PENDING/SUCCESS/FAILED만 변경. 콜백 자동 전송·등록 plan 변경·커머스 DB 직접 변경 없음|
|enqueuePlan(operation, target, plan)|CREATE/LOOKUP_TRANSACTION/LOOKUP_ORDER + 대상(orderId/transactionKey/orderId)의 FIFO에 검증한 typed plan 하나 등록. 다음 실제 요청이 원자적으로 한 번 소비. 실제 거래 상태/카운터는 등록만으로 바뀌지 않음|
|sendCallbacks(key, count)|1~10회의 명시적 알림 전송. count는 의도한 전송 횟수이며 retry 횟수가 아님. 호출 때 거래/콜백 URL snapshot을 복사하고 각 알림을 독립 작업으로 기존 callback 전용 executor(worker 2개)에 제출한 뒤 CompletionStage<CallbackBatchResult> 반환. 모든 전송의 결과를 집계한 뒤 완료. 같은 호출의 알림은 병렬 실행될 수 있으며 수신/완료 순서를 보장하지 않음. 순차/병렬 선택 파라미터·전송 모드 없음. 테스트는 유한 기한으로 완료를 기다리고 각 전송의 HTTP 응답/통신 오류를 읽음. PG 상태 변경·추가 retry 없음|
|releaseGate(gateId)|등록 gate 해제. 반복 해제는 성공, 없는 gate는 잘못된 테스트 준비로 거절. 거래·plan·카운터 변경 없음|
|reset()|조용한 fixture의 거래·plan·journal/count·gate 초기화. 진행 중 결제 handler/콜백 job이 있으면 IllegalStateException, 기존 상태 유지. 새 fixture 사용을 기본으로 하며 재시작 테스트의 중간에는 reset 금지|

**구현 위치(2026-10-25 목표):** state()의 transactions는 `PgSimulator.transactions().findAll()`, counts·journal·activeRequests·gates는 프록시, callbackSent·activeCallbackJobs는 래퍼의 콜백 기록에서 읽는다. setStatus는 `transactions().setStatus`, enqueuePlan은 위 두 FIFO, sendCallbacks는 count번의 `callbacks().send(snapshot)`을 모아 CallbackBatchResult로 만든다. releaseGate는 프록시의 gate, reset은 래퍼의 두 FIFO·journal·gate와 `transactions().clear()`를 초기화한다.

잘못된 enum/형식/범위·없는 거래/gate·잘못된 plan 조합은 IllegalArgumentException으로 테스트 준비를 실패시킨다. close 이후 새 제어/콜백 접수는 IllegalStateException으로 거절한다. 메서드 제어 오류를 PG HTTP 응답이나 커머스 업무 오류로 전달하지 않는다.

**역할·동시성:** 거래 저장·plan FIFO·관측 journal은 역할을 분리하고 각 변경/스냅샷은 동기화한다. reset의 busy 검사/전체 초기화와 새 handler/콜백 job 접수는 같은 lifecycle guard로 직렬화해 검사 직후 새 작업이 끼어들지 못하게 한다. 네트워크 대기·gate·콜백 응답 대기 중 store/registry/lifecycle 잠금을 유지하지 않는다. 병렬 테스트는 별도 fixture/임의 포트/거래/회로를 사용한다. 같은 대상의 경쟁 테스트에서 plan 적용 순서가 중요하면 gate/장벽으로 실제 수신 순서를 통제한다.

**관측 의미:** createReceived/각 lookupReceived는 해당 PG endpoint의 실제 수신 시 증가하고, 입력 검증 실패/응답 유실도 수신 사실은 남긴다. 2026-10-25 목표 구조에서는 프록시가 수신 시점에 세고, 계약 경로의 모든 요청을 PG에 전달한 뒤에 응답을 제어하므로 프록시 수신 수와 PG 수신 수가 같다. 이 등식은 테스트가 PG에 보내는 모든 요청이 `baseUrl()`(프록시)을 거칠 때만 성립한다. 테스트는 PG 주소로 직접 요청하지 않는다. 거래 수는 실제 store 생성 결과이며 수신 횟수와 다르다. 회로 거절·풀 획득 실패는 PG 수신 0일 수 있다. callbackSent는 실제 알림 전송 시도 수이고 CallbackBatchResult의 HTTP 응답/통신 오류는 전송 결과다. **2026-10-25 사용자 결정:** 전송은 PG main의 콜백 전송기가 하고 래퍼는 결과만 받으므로, callbackSent는 전송 결과가 나온 뒤 실제로 보낸(sent=true) 알림만 센다. 진행 중인 전송은 아직 세지 않으며, 끝난 뒤의 값은 이전과 같다. 2026-10-25 B2 정리로 래퍼의 콜백 기록 목록(`state().callbacks()`)은 없앴고 callbackSent는 단순 카운터다. 각 전송 결과는 CallbackBatchResult로 확인한다. count=2는 의도적 중복 알림 2회이며 실패 후 retry가 아니다. 준비/관측 메서드 호출은 PG HTTP 수신·콜백 전송·회로 표본에 포함하지 않는다. 콜백 HTTP 200도 그 자체로 PG 결제 성공의 증거는 아니다. 진행 중 state() 관측만으로 업무 완료를 단정하지 않고 관련 HTTP/콜백 작업 완료 뒤 새 커머스 API/DB 조회로 최종 결과를 확인한다.

**콜백 전송 결과와 검증 완료:** 콜백 송신 timeout 자체는 별도 실험·필수 시나리오로 두지 않는다. 실습의 timeout·회복 검증 대상은 커머스 → PG HTTP이며, 아래는 테스트 장치의 최소 완료 계약이다.

- CallbackBatchResult는 각 전송의 HTTP 응답 또는 통신 오류(연결 실패·송신 timeout·I/O 오류)를 나타낸다. 결과의 나열 순서를 커머스 수신/처리 완료 순서로 해석하지 않는다. CompletionStage가 완료됐다는 사실은 모든 전송 시도가 끝났다는 뜻일 뿐 커머스 업무 완료의 선언이 아니다.
- 정상 콜백 검증은 각 전송에서 기대한 HTTP ACK를 받은 뒤 새 커머스 API/DB 조회로 상태와 업무 효과를 확인한다. 검증·종결 반영과 같은 종결 재확인의 기대 ACK는 [PG 콜백 수신](../order-payment/api-contract.md#pg-콜백-수신)의 200이며, [4.3](../order-payment/processing.md#43-주문-확정)의 콜백 처리 순서상 이 응답은 결과 반영 TX가 commit된 뒤 반환된다.
- 송신 timeout·통신 오류가 있거나 기대한 ACK를 받지 못한 실행은 정상 콜백 검증 통과로 세지 않는다. 뒤늦게 DB 상태가 기대값과 맞아졌다는 이유로 그 실행을 정상 콜백 통과로 바꾸지 않는다.
- 송신 오류는 커머스가 받지 못했는지, 처리 중인지, 이미 commit했는지 알 수 없는 전송 관측이다. 결제 FAILED·재고 반환·fixture나 테스트의 자동 재전송 근거로 사용하지 않는다.
- 송신 오류 뒤에도 커머스 처리가 진행 중일 수 있으므로 아래 종료 계약의 유한 대기·진행 작업 종료/자원 정리를 그대로 적용한다. 종료를 확인하지 못한 환경은 안전한 재사용·검증 통과로 기록하지 않는다. 업무 결과 polling이나 송신 timeout 복구 시나리오는 추가하지 않으며, 콜백이 오지 않은 주문의 회복은 기존 콜백 유실 → 고객 reconcile 검증이 맡는다.
- count≥2는 중복 알림 검증이다. worker 2개에서 병렬 실행될 수 있지만 그것만으로 실제 요청 겹침을 입증했다고 보지 않으며 동시 적용 통과 근거로 쓰지 않는다. 같은 주문 결과 반영의 실제 동시 적용은 [4.5 부모 잠금 검증](../order-payment/verification.md#부모-잠금과-일반-조회의-실제-mysql-검증)의 장벽·SQL/처리 관측으로 겹침을 입증한다.

### 응답 plan

|필드|입력·기본값|
|---|---|
|createOutcome|CREATE에서만 PENDING(기본)/SUCCESS/REJECT_BEFORE_CREATE. 마지막 경우 거래 저장·콜백 예약 없이 5.5의 422 반환|
|reply|NORMAL(기본)/RAW/DELAY/CLOSE_EARLY/GATE|
|delayMs|DELAY에서 0~10000ms. header 전 실제 대기, 종료 시 중단. 업무 코드에 sleep을 넣는 것이 아님|
|gateId|GATE에서 필요한 비어 있지 않은 문자열. snapshot/CREATE 효과 이후 응답 전 대기. 최대 10초 안에 release가 없으면 서버가 응답을 종료하고 timeout을 journal에 기록|
|httpStatus·body·headers|RAW에서 필수 상태코드/문자열 본문과 선택 header map. HTTP/JSON 손상·외부 식별/금액·혼합 목록·redirect 응답을 저장소 수정 없이 재현|

CREATE 외에 createOutcome을 주거나 값이 범위를 벗어나면 enqueuePlan에서 IllegalArgumentException으로 거절한다. REJECT_BEFORE_CREATE는 reply=NORMAL만 허용해 확정 무효과 계약을 모호하게 만들지 않는다. 2026-10-25 B2 정리로 plan은 팩토리(`normal`·`outcome`·`delay`·`gate`·`closeEarly`·`raw`)로만 만들 수 있어, RAW 필드를 다른 reply에 주거나 REJECT_BEFORE_CREATE에 응답 제어를 섞는 조합은 만들 수 없다. plan 소비 뒤 실제 snapshot·거래 효과를 먼저 만들고 응답을 제어하므로 CREATE DELAY/닫힘 뒤에는 이미 거래가 존재한다(2026-10-25 목표 구조에서는 PG가 거래를 저장·응답한 뒤 프록시가 reply를 적용해 같은 순서가 된다. createOutcome은 PG의 `ScriptedOutcomePolicy`가, reply는 프록시가 소비한다). CLOSE_EARLY는 전체보다 긴 Content-Length를 선언한 뒤 일부 바이트만 전송하고 연결을 닫아 수신 도중 I/O 오류를 재현한다. 클라이언트가 parser 오류로 관찰할 가능성도 있으므로 해당 검증에서는 실제 예외 원인/카운터를 함께 확인한다. 연결 수립 자체의 실패와는 별도다.

아래 JSON은 typed plan의 설정값 표현 예시이며 제어 HTTP 요청 본문이 아니다.

```json
{"createOutcome":"PENDING","reply":"DELAY","delayMs":1500}
```

위 CREATE plan은 거래를 만든 뒤 응답만 지연한다. commerce는 1초 읽기 제한으로 UNKNOWN/예약 유지할 수 있으며, PG state에는 거래가 남는다. 그 거래를 status=SUCCESS로 변경한 뒤 commerce의 고객 reconcile을 호출하면 기존 거래를 조회해 TX B에 반영한다. 대사 시 PG create 카운터는 늘지 않아야 한다.

콜백 유실은 **상태만 바꾸고 sendCallbacks를 호출하지 않는 것**으로, 중복은 count로, 늦은/역순 알림의 HTTP 수신 순서는 전송 시점으로 제어한다. 늦은 생성/조회 관찰의 적용 순서와 종결 보호는 HTTP GATE·읽기 대기에 묶지 않고 [4.5의 업무 TX 경계 검증](../order-payment/verification.md#업무-관찰-순서의-tx-경계-검증)에서 입증한다. 콜백 본문 status가 도착 순서와 달라도 commerce는 권위 조회로 판단한다. 정상 PG 저장소 자체를 잘못된 금액/다른 주문으로 바꾸기보다 RAW 응답으로 조회 계약 위반을 재현한다. 복수 거래는 테스트가 commerce를 거치지 않고 `baseUrl()`로 동일 orderId의 PG POST를 두 번 보내 만들 수 있고 commerce의 생성 멱등성 검증과 구분한다.

plan target은 서버 저장/요청과 같은 문자열 식별자로 직접 전달한다. PG 조회 URI는 adapter가 인코딩하고 Java 제어에는 URI 변환을 추가하지 않는다. reset 없이 시나리오를 시작할 때는 테스트별 고유 주문/거래와 plan을 사용한다. 서버가 콜백을 보낼 수 있는 URL은 `http`·`127.0.0.1`·명시 포트·`/api/v1/payments/callback`으로 제한하고 redirect를 따르지 않는다. 임의 외부 주소를 fixture 제어로 주입하지 않는다.

### 기본 PENDING 이후 정상 콜백 완료

아래 정상 경로를 독립된 필수 검증으로 둔다. 테스트 제어 순서는 즉시 성공/응답 손상/중복 시나리오와 분리한다.

1. 기본 생성 응답을 202 PENDING으로 두고 고객 confirm을 호출한다. 주문 PAYMENT_PENDING·결제 PENDING·확보 재고 유지·createReceived=1을 확인한다.
2. state()에서 해당 외부 주문 ID의 유일한 transactionKey를 확인하고 setStatus(key, SUCCESS)를 호출한다. 이때 콜백/조회/커머스 상태 변경은 아직 없다.
3. sendCallbacks(key, 1)을 호출해 실제 커머스 callback HTTP → 실제 PG 거래 GET 1회 → 공통 TX B 순서로 처리하고 CallbackBatchResult 완료를 유한 기한으로 기다린다. 전송 결과가 기대한 200 ACK인지 확인한다. 송신 timeout·통신 오류이면 위 전송 결과 계약대로 이 실행을 통과로 세지 않는다.
4. 새 주문 조회/DB TX에서 CONFIRMED·payment SUCCESS·paidAmount=저장 총액, 같은 거래 키, createReceived=1·lookupByKeyReceived=1·callbackSent=1, 재고 추가 차감/반환·포인트 변화 0을 확인한다. 한 번의 ORDER_RESERVATION만 유지한다.
5. 같은 방식의 PENDING → FAILED 정상 콜백은 PAYMENT_FAILED·재고/ORDER_RELEASE 한 번 반환·paidAmount 생략을 확인한다. 중복 알림(count)은 별도 사례에서 추가 업무 효과 0을 확인하고, 동시 경쟁은 4.5의 부모 잠금 검증이 맡는다.

### 테스트 시작·초기화·상태 보존

아래는 구현할 테스트 수명 계약이다. 테스트는 별도 PG 프로세스나 수동 실행 중인 PG 앱에 의존하지 않고, 같은 JVM 안에서 PG와 프록시를 시작한다. 수동 실행용 main·`bootRun`은 [2.10](README.md#210-pg-시뮬레이터-앱의-책임과-수명)에 있으나 테스트 조건이 아니다. pg-demo 프로필·데모 DB는 만들지 않는다. 현재 문서 변경으로 코드/DB를 생성하거나 초기화하지 않았다.

- 일반 테스트는 PgFixtureServer.start()로 loopback 임의 포트 서버(목표 구조에서는 PG와 프록시)를 먼저 시작하고 `baseUrl()`(목표 구조에서는 프록시 주소)을 commerce.pg.base-url에 주입한다. 이 PG 포트를 커머스 포트와 혼동하지 않는다. 문서 2.9 YAML의 URL은 형식 예시이며 테스트는 여러 테스트가 공유하는 고정 포트/평소 켜둔 서버에 의존하지 않는다. 테스트별 포트·fixture 격리를 유지한다.
- **콜백 HTTP 테스트의 커머스 포트·callback URL:** 커머스 기동 전에 테스트가 사용할 빈 포트를 선택한다. 그 값을 `server.port`와 `commerce.pg.callback-url`(`http://127.0.0.1:{선택 포트}/api/v1/payments/callback`)에 함께 주입한 뒤 컨텍스트를 기동한다. `server.port=0`으로 기동한 뒤 포트를 알아내 callback URL을 바꾸는 방식은 쓰지 않으며 PgHttpProperties의 불변 바인딩을 유지한다. 기동 완료 후 웹 서버의 실제 수신 포트와 주입한 callback URL의 포트·주소·경로가 일치하는지 확인한 뒤에만 confirm·콜백 검증을 시작한다.
- 빈 포트의 사전 선택은 포트 예약이 아니다. 선택 뒤 다른 프로세스가 점유해 bind에 실패하거나 기동 후 포트/URL이 불일치하면 해당 실행은 테스트 준비 실패로 기록하며 검증 통과로 세지 않는다. 별도 포트 예약 장치나 자동 재시도 정책은 두지 않는다.
- YAML 속성 비교는 테스트별 초기 상태·설정·요청열을 통제한다. 실제 Boot registry/adapter에 적용된 최종값과 회로 상태/표본·PG 수신/거래 수를 기록한다. 회로 순수 전이는 주입 clock으로 보완하고 HTTP timeout/상태 타이머의 실제 동작은 실제 시간으로 확인한다. 구체 글 실험값/결과는 구현 뒤 정한다.
- 테스트 DB는 기존 MySQL fixture를 사용하고 기존 로컬 loopers/POINT 데이터를 초기화하지 않는다. 새 테스트의 스키마 준비와 종료 정리는 해당 테스트 DB 안에서만 수행한다. 기존 데이터 전환의 보정/DDL 계약은 3.6을 유지하며 테스트용 빈 DB 생성과 혼동하지 않는다.
- 재시작 테스트는 같은 PG fixture와 MySQL container/스키마를 두 commerce 컨텍스트보다 긴 수명으로 유지한다. 첫 컨텍스트의 스키마 준비/주문·결제 생성 뒤 종료하고, 두 번째는 ddl-auto=validate로 시작한다. 두 컨텍스트 사이에 create/cleanup/TRUNCATE/reset을 실행하지 않는다. 기존 test/local의 create 설정을 두 번째 컨텍스트의 최종값으로 두지 않는다. 각 컨텍스트는 기동 전에 자기 커머스 포트와 callback URL을 위 절차대로 함께 주입하고 기동 후 일치를 확인하며, 두 컨텍스트가 같은 커머스 포트를 쓸 필요는 없다. 첫 컨텍스트 종료와 진행 요청/콜백·worker 정리는 아래 종료 계약을 따른다.
- 두 번째 컨텍스트의 필수 복구 경로는 고객 주문 조회 → reconcile이다. 같은 거래를 확인하고 create 추가 0·주문/재고 효과 한 번을 검증한다. PG 거래에 저장된 첫 컨텍스트의 callback URL을 두 번째 주소로 고치거나 이전 주소로 콜백을 받는 기능은 추가하지 않으며, 이전 주소로 향한 콜백 전송 결과를 복구 근거로 쓰지 않는다. 주문·결제·발송 기록은 DB에서 유지되고 client/회로 메모리 상태는 새로 시작한다. PG fixture까지 종료해 거래가 사라진 사례를 거래 복구 성공으로 기록하지 않는다.
- 종료 시 진행 commerce 요청/콜백·gate를 해제하고 실제 작업 종료를 확인한 뒤 commerce 컨텍스트/client → PG fixture → DB 순으로 정리한다. worker 종료를 확인하지 못하면 실패를 보고하고 해당 환경의 안전한 재사용/검증 통과로 기록하지 않는다. 일반 테스트는 매번 새 fixture를 사용하며 재시작 테스트의 두 컨텍스트 사이만 의도적으로 공유한다.
