# 결제 사고 관측·동일 주문 복구·후속 결과 연결

## 1. 상태·적용 범위·읽기 기준

2026-11-23 사용자가 전체 설계를 최종 승인했다. 전체 Claude 검토와 P1~P4·후속 보완 재확인을 거쳐 마지막 Lab exec/안내 단일 writer/collector 기본 인자 세 항목도 구현 전 필수 문제 없음 의견을 받았다. AI 검토 의견과 사용자 최종 승인은 구분한다. 이 문서는 승인된 구현·검증 계약이며 작은 구현 단위의 착수·코드/테스트·실습 실행 승인은 아니다. 아래 새 타입·메서드·Gradle 작업은 구현할 계약이며 현재 존재/통과한다고 가정하지 않는다. 실제 환경/컴파일/수집·시간 예산은 실행 전 미검증이다.

이 문서와 연결된 기준 설계만으로 구현·검증 계약을 읽을 수 있어야 한다. 로컬 주차 토론 기록은 필수 근거가 아니다. 기존 제품 계약의 원본은 다음과 같으며 중복 정의하지 않는다.

- [계층·의존·TX](../common/architecture.md), [API 공통 규칙](../common/api-contract.md).
- [결제 처리](order-payment/processing.md), [PG HTTP·회로](order-payment/pg-client.md), [기존 결제 검증](order-payment/verification.md).
- [PG fixture 계약](pg-simulator/fixture-contract.md)의 W10 추가 절.
- [Outbox 발행](order-events/publication.md)의 W7 절, [소비](order-events/consumption.md).
- [주간 랭킹](weekly-ranking.md), 특히 [실제 연결·자원 소유권](weekly-ranking/verification.md) §11.4~11.7.

원문 근거는 「10주차 실습 · 원인을 구분하고 업무 결과로 복구 증명하기」 Implementation Quest §1~8·Checklist·Technical Writing Quest와 「10주차 · 사용자 여정의 증거로 사고를 복구하기」다. 원문에서 정한 수량/순서/보호 조건과 아래 구체적인 구현 선택을 구분한다.

추가 범위는 UNKNOWN 업무 지표 하나, 생성/조회/적용/TX 진단 로그, SUCCESS 뒤 응답 유실 fixture 조합, 동일 lifecycle의 20주문 연결 검증, 명시 관찰 진행과 실행별 증거다. PG·주문·쿠폰·Outbox·랭킹의 외부 API/판정/회로를 바꾸지 않는다. 새 자동 reconcile scheduler·분산 추적 backend·Loki·운영 부하/실계정/운영 장애 검증·fault 시점 추가 랭킹 공개는 포함하지 않는다. 기존 W9 재시작/revision 회귀는 유지한다.

## 2. 입력·식별·시간·불변 조건

### 2.1 고정 입력

전용 테스트 DB, 새 PG fixture, 실행별 Kafka topic/group을 사용한다. API/streamer/batch는 같은 DB를 공유하되 서로의 제품 application 의존성을 추가하지 않고 기존 자식 실행 방식을 재사용한다. 실행 전 구매 활동 테이블 부재와 DB 소유를 확인한다. 기존 행/기존 테이블이 있으면 임의 삭제하지 않고 실패한다.

상품 A는 단가1,000·시작재고100, 상품 B는 단가2,000·시작재고100이다. 각 주문은 A2개+B1개, 저장금액4,000이다. 정상 집합 `B`10개와 별도 장애 집합 `F`10개를 실제 주문 API로 만들고 서로 겹치지 않는 실제 ID 목록을 저장한다. `B`는 집합 이름, 상품 B와는 구분해서 evidence에 `baselineOrderIds`로 쓴다.

`F`를 생성한 순서의 명단에서 첫3개를 `U`로 지정하고 **어떤 confirm도 실행하기 전에** 세 실제 orderId/attemptKey/providerOrderId를 저장한다. 성공/실패가 먼저 관찰된 주문을 사후 선택하지 않는다. confirm 실행은 정상 F7개, 지정한 U3개 순서로 직렬 진행한다. 각 주문별 생성 POST는1번이다. 17개 정상 생성이 선행하므로 주입3개가 기존 회로를 뜻밖에 열어 정상7개를 막는 순서를 피하지만 실제 회로/호출 수는 별도로 확인한다.

합성 사용자1명을 실제 UserFixture로 준비하고, 다른 사용자1명은 소유권 대조에만 사용한다. PG 서버 계정은 기존 합성계정 설정을 그대로 주입한다. 사용자 ID와 PG 계정을 같은 값으로 간주하지 않는다. 각 주문의 실제 attemptKey는 기존 PgCommands의 UUID 계약을 유지한다.

비어 있지 않은 보존 대조군을 준비한다. 주 사용자에게 PointFacade.charge로10,000과 실제 충전 이력을 만들고, CouponFixture.activeCampaign(1)·storedCoupon(user,campaign)으로 기존 의미의 보유 쿠폰1개와 issuedCount1을 준비한다. 준비 commit 후 원문/본인 쿠폰 목록을 저장한다. 이 쿠폰 준비는 발급 수용/동시성 실험이 아니며 그 계약은 별도 기존 회귀로 확인한다. 이번 테스트에는 대기실 campaign-id를 주입하지 않아 쿠폰 실행기가 이 대조군을 처리하지 않는다.

### 2.2 시간과 전체 기대값

최초 주문 확정 Clock만 `2026-11-23T01:00:00Z`로 고정한다. 집계 주는 `[2026-11-23T00:00:00Z, 2026-11-30T00:00:00Z)`이다. PG 요청 시각·복구 실행 시각으로 confirmedAt을 덮어쓰지 않는다. 증거 시각·PG journal·Outbox clock·대기 한도는 실제 시각/monotonic 시간을 사용한다. 합성 업무 시각과 실행 시각을 별도 필드로 기록한다.

|단계|대상 집합의 CONFIRMED / UNKNOWN|그 집합 PG SUCCESS|누적 PG 거래 / CREATE 수|A/B 가용재고|전체 Outbox 사실|전달 완료 후 전체 활동행|수량합계 A/B|
|---|---|---:|---|---|---:|---:|---|
|BASELINE|정상10 / 0|10|10 / 10|80 / 90|10|20|20 / 10|
|FAULT|장애7 / 3|10|20 / 20|60 / 80|17|34|34 / 17|
|RECOVERY|동일 장애10 / 0|10|20 / 20|60 / 80|20|40|40 / 20|

UNKNOWN은 payment 상태이고 주문 상태는 PAYMENT_PENDING이다. DRAFT 명단을 만들었다고 payment UNKNOWN이 생성되지는 않는다. baseline/복구의 안정 시점 지표는0, fault의 안정 시점 지표는3이다. 정상17개도 TX A에서 UNKNOWN을 commit한 뒤 TX B에서 PENDING/최종 상태로 바뀌므로 confirm 처리 중 그래프에는 일시적인 UNKNOWN이 보일 수 있다. 단계 기대값은 모든 해당 업무 TX와 전달이 안정화된 뒤에만 판정한다. 지표의 범위는 DB 전체이므로 이 수치가 성립하려면 배경 UNKNOWN이 없는 전용 DB임을 확인해야 한다.

주문별 ORDER_RESERVATION은 A/B 각각1행·변경량 -2/-1이며 전체40행이다. ORDER_RELEASE와 PG에 의한 ORDER_DEDUCTION은0이다. UNKNOWN3개의 예약은 유지되고 최종 SUCCESS 적용은 재고를 다시 차감하지 않는다. 포인트 잔액/충전 이력, 쿠폰 ID/사용자/캠페인/혜택/발급시각과 campaign issuedCount는 전 단계에서 준비 직후와 같다. 단순 행수뿐 아니라 저장된 값을 대조한다.

한 주문은 Outbox 사실1개·구매 활동2행이다. broker 발행 record 수/시도 수와 DB의 업무 효과 수를 혼동하지 않는다. 최종 새 snapshot의 입력40행, 상품2개, 수량합계60, API 결과는 A40점1위·B20점2위다. 다른 주·sentinel·별도 구매 입력을 이 사고 시나리오에 삽입하지 않는다.

## 3. UNKNOWN 지표의 클래스·조회·실패 계약

### 3.1 책임과 시그니처

|위치/타입|계약|
|---|---|
|domain.payment.PaymentRepository|`long countByStatus(PaymentStatus status)` 추가. null은 호출 오류. COUNT scalar만 반환하며 Micrometer/HTTP/JPA API를 노출하지 않음|
|infrastructure.payment.PaymentJpaRepository|`countByStatus`를 status 조건 COUNT 쿼리로 명시. `jakarta.persistence.query.timeout=1000`ms hint 적용|
|infrastructure.payment.PaymentRepositoryImpl|위 count를 기존 port로 구현. Entity 목록 로딩·새 인덱스·새 캐시 없음|
|application.payment.PaymentStatusQuery|`@Transactional(readOnly=true, timeout=1)`의 `long countUnknown()`. PaymentStatus.UNKNOWN만 조회하고 오류는 그대로 전파|
|config.PaymentMetricsConfig|singleton MeterBinder와 gauge 대상 객체를 강하게 유지. 실제 등록은 애플리케이션 registry마다1회. `commerce.payment.unknown` gauge, 설명은 “Current payment rows in UNKNOWN state; excludes PENDING”. 추가 업무 label 없음|

Metric의 Prometheus 기본 노출 이름은 `commerce_payment_unknown`으로 검증한다. 실제 registry 원문에서 이름/TYPE gauge·기존 application 태그를 확인하며 없는 표본을0으로 채우지 않는다. 단위 설명은 건수로 두고 이름이 바뀌는 baseUnit suffix를 임의 추가하지 않는다.

```text
PaymentStatusQuery.countUnknown():
  기존 domain port로 COUNT(status=UNKNOWN)
  음수면 내부 오류; 정상은 long 그대로 반환
  DB 예외를0으로 바꾸지 않음

PaymentMetricsConfig가 등록한 gauge의 관측 함수:
  countUnknown()을 프록시를 통해 호출 (HTTP 수집 스레드, 상위 업무 TX 없음)
  성공 → double로 반환
  RuntimeException → 정적 오류코드 PAYMENT_UNKNOWN_QUERY_FAILED와 예외 클래스만 기록
                     Double.NaN 반환 (직전 값/초기0/가짜 성공 금지)
```

NaN은 모름이며 복구0이 아니다. endpoint HTTP200·Prometheus up1이어도 이 gauge가NaN이면 실습은 실패/중단이다. 별도 갱신 scheduler·캐시·미종결 전체 지표는 추가하지 않는다. 외부 계정/주문/사용자/traceId/runId는 metric label로 넣지 않는다.

### 3.2 시간·부하·검증

쿼리 hint와 TX timeout은1초 목표이고 기존 Hikari 획득 대기는3초다. 따라서 DB 전체 응답이1초에 끝난다고 보장하지 않는다. HTTP 관측 client는 connect/read 각각3초로 제한한다. client가 timeout됐다고 DB 취소 완료를 주장하지 않고 원인/남은 작업을 기록한다. DB socket timeout/공용 datasource 설정을 임의 변경하지 않는다. 정상 로컬 환경에서 제때 끝나지 않으면 구현 단계에서 근거와 대안을 검토한다.

계측 단독 검증은 실제 DB의 UNKNOWN3·PENDING1·SUCCESS/FAILED 대조를 준비해3만 세는지 확인하고, 변경 commit 뒤 새 scrape에서 값이 바뀌는지 확인한다. DB 예외는 계측 경계 대역으로 발생시켜 NaN·실패 코드·민감정보 미출력을 확인한다. 이를 실제 DB 장애 검증으로 쓰지 않는다. registry가 bean 대상 참조를 유지하고 새 스크랩마다 DB조회가 발생하는지도 확인한다.

선택 근거: 현재 건수 조회는 주기 캐시보다 갱신 지연·초기값·오래된 값 처리가 단순하다. 대신 수집마다 DB를 읽고 DB지연이 수집에 영향을 준다. 작은 로컬 실습의 선택이며 운영 트래픽의 COUNT비용/분산 집계/DB장애 격리를 검증한 것은 아니다. 운영 부하가 문제면 주기 캐시와 freshness·수집 실패 정책을 재검토한다.

## 4. 생성·조회·적용·TX 로그 계약

### 4.1 공통 의미와 허용 필드

기존 PG 분류/HTTP/회로 결과와 업무 예외는 바꾸지 않는다. 로그는 고정 event명과 JSON으로 안전하게 인코딩한 명시 필드로 남긴다. 실제 JSON appender 전체를 새로 구축할 필요는 없고 고정 key=value와 JSON escaping을 사용할 수 있다. 로그 출력 실패는 RuntimeException을 국소적으로 격리해 원래 결과/예외를 바꾸지 않는다. `Error`를 포괄해서 삼키지 않는다.

허용: event·실제 시각, operation/source, 임의 진단 callId/observationId, orderId/paymentId, 저장/검증된 attemptKey/providerOrderId/transactionKey, 금액·상태·효과·분류코드·예외 클래스. 식별자 값은 JSON escaping하고 잘못된 입력은 원문 대신 INVALID로 표시한다. 카드/인증헤더/비밀키/DB연결문자열/사용자 개인정보/요청·응답 DTO 전체/exception 원문·stack을 새 진단 로그에 싣지 않는다. providerAccount는 서버의 합성계정과 매핑한 evidence에서 확인하며 인증헤더를 출력하지 않는다.

callId/observationId는 로그 사건만 묶는 UUID이며 업무 식별·eventId·attemptKey·traceId를 대체하지 않는다. 외부 경계의 상관관계는 providerOrderId/transactionKey와 저장된 대응표로 이어 붙인다. 실제 분산 trace 전파를 구현했다고 쓰지 않는다.

### 4.2 PgHttpGateway의 경계 로그

CREATE1회와 QUERY1회의 각각에 callId1개를 만들고 START/RESULT 또는 START/ERROR를 연결한다. gateway에서 orderId/attemptKey를 알아내기 위한 DB조회나 PaymentRequest 필드 추가를 하지 않는다.

|event|시점과 필드|
|---|---|
|PAYMENT_PG_START|operation=CREATE/LOOKUP_KEY/LOOKUP_ORDER, callId, 허용된 target/providerOrderId. 카드/body 없음|
|PAYMENT_PG_RESULT|같은 callId, 실제 결과분류(TransactionObserved/Unavailable/Conflict/LocalNotDispatched 등), 있으면 검증된 거래키/주문/금액/상태, 분류 이유|
|PAYMENT_PG_ERROR|원래 전파할 프로그램 오류 등. callId·예외 클래스·정적 reason만 기록하고 동일 예외 전파|

```text
create(request):
  안전한 START 로그
  기존 circuit execute + transport/decoder 경로 수행
  기존 catch에서 POOL_ACQUIRE/CIRCUIT_REJECTED/CREATE_UNAVAILABLE을 기존 관찰로 변환
  분류를 정한 뒤 RESULT 로그; 같은 관찰 반환
  원래 미분류 예외는 ERROR 로그 후 동일 전파

lookup(kind,target):
  START 로그 → 기존 lookup 회로/transport/decoder
  기존 관찰/분류를 RESULT로 기록
  원래 미분류 예외는 ERROR 후 동일 전파
```

`PgCreateUnavailableException`에는 decoder/계약 외 응답 실패도 포함되므로 전부 I/O라고 표시하지 않는다. 보존된 원인 사슬에 Spring `ResourceAccessException`이 있으면 reason=TRANSPORT_UNAVAILABLE, 없으면 CREATE_RESULT_UNAVAILABLE로 기록한다. JSON 처리 예외도 IOException일 수 있으므로 IOException 존재만으로 통신 오류를 판정하지 않는다. 조회도 기존 조회 예외/회로/풀 분류를 보존하고 같은 기준으로 reason을 기록한다. 이것만으로 timeout/본문 유실을 더 좁게 단정하지 않고 실제 CLOSED_EARLY journal·HTTP 결과와 교차 확인한다. 새 로그 때문에 circuit 안에서 이미 한 집계를 수동 중복 집계하거나 retry·GET·POST를 추가하지 않는다.

### 4.3 PaymentTxDiagnostics와 실제 TX 종료

새 application.payment.PaymentTxDiagnostics는 진단 전용이며 비즈니스 판정을 하지 않는다. `observe(operation, orderId, paymentIdOrNull, sourceOrNull): String observationId`가 invocation당 한 UUID를 반환한다. operation은 BEGIN_CONFIRMATION, PREPARE_VERIFICATION, APPLY_OBSERVATION, RECORD_CONSTRAINT_CONFLICT의 유한 enum이다.

```text
observe(...):
  불변 context(observationId,operation,orderId,paymentId,source) 복사
  TX_METHOD_ENTER 기록
  실제 TX + synchronization active이면:
    TransactionSynchronization1개 등록
      afterCompletion(status):
        COMMITTED / ROLLED_BACK / UNKNOWN으로 정확히 매핑
        불변 context만 TX_COMPLETION에 기록
        Entity/추가DB조회/외부I/O/새 TX/비즈니스 재시도 없음
  아니면 TX_OBSERVATION_UNAVAILABLE(reason=NO_ACTIVE_SYNCHRONIZATION) 기록
    가짜 commit/rollback 로그·새 TX·제품 실패 합성 없음
  관측 등록/로그 RuntimeException은 TX_OBSERVATION_UNAVAILABLE로 국소 기록하고 원래 업무 유지
```

이 진단은 method body에 들어온 후의 TX만 본다. 프록시의 TX 시작 실패는 callback등록 전이므로 caller의 예외/HTTP와 별도 대조하고 rollback이 있었다고 단정하지 않는다. 로그 누락/UNKNOWN/진단 실패가 있으면 그 실행의 진단 완결성을 실패로 기록한다. 로그 부재만으로 TX미시작을 확정하지 않는다.

각 메서드 **첫 SQL 전**에 observe를 호출한다. BEGIN은 paymentId가 아직 없어도 orderId로 등록한다. 기존 command 검증/normalize 전에 원문 attemptKey를 기록하지 않는다. 주문·결제가 정상 로드/생성된 뒤 같은 observationId로 PAYMENT_CONTEXT(orderId/paymentId/저장attemptKey/providerOrderId/저장거래키/금액)를 기록한다. BEGIN에서 insert 전 실패하면 paymentId 없는 completion과 입력/SQL실패 증거를 보존한다.

```text
beginConfirmation:
  observe(BEGIN_CONFIRMATION,orderId,null,CREATE)
  기존 잠금/검증/발송권한/재고예약/결제저장 순서 유지
  저장scalar를 PAYMENT_CONTEXT로 기록
  기존 반환값 반환; 실제 commit은 afterCompletion만 표시

prepareVerification:
  observe(PREPARE_VERIFICATION,orderId,null,QUERY)
  기존 소유권/정합성/조회대상 선택
  PAYMENT_CONTEXT와 선택한 lookupKind/target 기록; 조회/적용하지 않음

applyObservation:
  observe(APPLY_OBSERVATION,token.orderId,token.paymentId,token.source)
  기존 주문잠금/결제로드 → PAYMENT_CONTEXT
  허용 필드만 PAYMENT_APPLY_INPUT에 기록
  기존 recordObservation → PAYMENT_APPLY_DECISION(effect/상태)
  기존 확정/반환/보존·Outbox기록/flush·probe·반환 유지
  commit전의 DECISION을 COMMITTED로 표현하지 않음

recordConstraintConflict:
  새 observationId로 observe(RECORD_CONSTRAINT_CONFLICT,...)
  기존 잠금·충돌요약저장·probe·반환 유지
```

같은 transaction-key unique 오류가 발생하면 첫 APPLY의 ROLLED_BACK과 다음 충돌 기록 TX의 COMMITTED는 **서로 다른 observationId**다. 충돌 기록 성공을 주문 확정 성공으로 읽지 않는다. 외부 TX가 있다면 callback은 실제 외부 종료에 따라 기록하며 메서드 반환 즉시 commit으로 간주하지 않는다.

### 4.4 증거 조합과 검증

장애 U의 기대 조합은 PG에 이미SUCCESS, PG RESULT=Unavailable(CREATE), TX B의 APPLY_INPUT=Unavailable, DECISION=UNAVAILABLE, COMPLETION=COMMITTED, DB UNKNOWN/주문PAYMENT_PENDING, 해당 주문 Outbox0이다. “결과 미확인 상태를 저장한 TX가 commit됐다”와 “주문을 성공 확정했다”는 다르다.

경쟁 가설은 최소2개 이상 비교하되 PG 지연·응답 유실·내부적용 실패 세 가지를 기본 표로 사용한다. PG SUCCESS/UNKNOWN만으로 유실과 rollback을 구분하지 않는다. TX 종료 로그는 새 DB조회/예약/Outbox와 맞춘다. 로그가 부정확하거나 빠지면 미확정으로 쓰고 관측 보완 전 가설을 확정하지 않는다. 세 가설을 모두 주입하는 새 실험은 추가하지 않는다.

필수 로그 검증: 정상 commit, BEFORE_COMMIT probe 오류로 실제 rollback, token키 충돌 뒤 별도 기록TX, 원래 adapter오류/분류, 실제 source/ID연결, 로그실패로 업무결과 불변. STATUS_UNKNOWN·no synchronization·진단 등록실패는 제어된 단위 검증으로 표현을 확인하며 실제 DB종료불명 장애를 재현했다고 쓰지 않는다. 원문 evidence에서 허용값 외 정보가 없는지 대조한다.

진단 observe 대상은 위 네 메서드다. 사전 준비 `prepareConfirmation`과 callback 후보 읽기 `resolveCallbackOrderId`의 TX는 새 observe 대상에 포함하지 않는다. 이 진단으로 모든 TX를 추적했다고 주장하지 않는다.

## 5. SUCCESS 뒤 응답 유실

공개 추가 API는 [fixture 계약의 W10 조합 절](pg-simulator/fixture-contract.md#w10-success-저장-후-생성-응답-유실)만 원본으로 삼는다. `ResponsePlan.successThenCloseEarly()`는 CREATE에만 SUCCESS+CLOSE_EARLY를 결합한다. 기본 `closeEarly()`는 기존 PENDING 의미를 유지한다. 기존 FIFO·PGmain·enum·reset/close·기존 회귀를 바꾸지 않는다.

주입은 한 요청에만 소비되는 plan이다. baseline과 recovery에서는 신규 주입을 등록하지 않는다. U의 CREATE가 끝나고 fixture activeRequest0·모든 U의 CLOSED_EARLY journal을 확인하면 이번 실행이 등록한 세 plan이 소비됐다고 판정한다. 전역 ON/OFF가 없으므로 가짜 ON/OFF marker를 통과 근거로 쓰지 않는다. 복구 중 새CREATE/새 plan등록0을 검증한다. 등록/소비/진행 작업의 구체 관측은 아래 계약을 따른다.

- 새 fixture는 W10 시나리오가 단독 소유한다. 모든 plan 등록은 시나리오의 단일 등록 helper를 거친다. helper는 operation/target/plan종류/단계/순번을 기록하고 성공한 등록을 별도로 표시한다. 등록 실패는 실행 실패이며 재등록하지 않는다. 범용 fixture 원장/API는 추가하지 않는다.
- baseline 등록 이력은0개, fault 완료 시 성공 등록은 U의 서로 다른 providerOrderId에 대한 CREATE/SUCCESS+CLOSE_EARLY 각1개, 총3개뿐이다. recovery 진입/종료에 같은 원장을 새로 비교해 추가 등록0을 확인한다.
- fixture journal에서 각U의 CREATE1개·거래키일치·CLOSED_EARLY 완료를 대조한다. 전체 CREATE20개·대상별1개와 함께 검사해 중복 수신도 숨기지 않는다.
- 작업 종료는 `activeRequests=0`, `activeCallbackJobs=0`, 미해제 gate0으로 확인한다. 이것을 남은 plan 수0이라고 부르지 않는다. 남은 plan 수는 공개되지 않으므로 단독 소유/등록3개/세 대상의 실제 소비로 이번 주입의 소진을 입증한다. helper를 우회한 임의 등록은 이 원장만으로 발견할 수 없으며, 시나리오 코드 검토에서 직접 enqueuePlan 호출이 helper 밖에 없는지도 확인한다.
- recovery 시작 journal 순번을 고정하고 이후 CREATE0·예상 조회의 완료/응답과 callback 결과를 별도 대조한다. journal에 오류가 없다는 사실만으로 RAW/DELAY plan 미등록을 증명하지 않는다. callback은 sendCallbacks를 호출할 때만 전송되며 U의 첫 callback은 복구 전 보존·진단 뒤다. 새 자동 reconcile scheduler는 만들지 않는다.

## 6. 실행 타입·모드·기본 격리

### 6.1 테스트 책임과 수명

다음 test 타입은 모두 commerce-api test 소스 `com.loopers.infrastructure.order`에 두어 기존 package-private 자식 helper를 재사용한다.

|타입|주요 메서드·계약|
|---|---|
|CheckoutIncidentScenario|`run(Mode, ScenarioInputs, EvidenceWriter, Checkpoint, Deadline)`. 유일한 시나리오/주장 구현. 단계 간 DB/PG초기화 없음|
|CheckoutIncidentTest|자동 wrapper1건, @Timeout420초. 동일 시나리오 AUTO 모드, 사람 대기/모니터링컨테이너 없음. 실제 health/Prometheus직접 GET과 실제 Kafka/배치 검증은 동일|
|CheckoutIncidentLabTest|실습 wrapper1건, @Timeout30분. OBSERVED모드, 직접 GET+실제Prometheus/Grafana+명시진행. AUTO와 업무 assertion 동일|
|IncidentEvidenceWriter|§9의 새 실행/write-once/실패/봉인 계약|
|IncidentCheckpoint|§8의 단계/nonce/advance/cancel/한도 계약. AUTO는 업무·증거 assertion 뒤에만 자동통과|
|IncidentMonitoringSupport|OBSERVED의 실행별 collector 생성/준비/샘플확인/종료, §7|

각 wrapper는 새 worker JVM의 별도 Test 작업에서만 실행한다. 일반 `test`에서는 이 **신규 두 클래스만** 정확히 제외하고 `checkoutIncidentTest`는 check에 연결한다. 기존 테스트 제외/기대값/검증 강도는 바꾸지 않는다. 수동 `checkoutIncidentLab`는 check에 연결하지 않는다. skip으로 수동 실행을 통과한 것처럼 표시하지 않는다.

두 신규 W10 작업 각각에 기존 streamer bootJar와 batch child 준비 작업 의존·파일 입력·JVM 경로 전달을 명시적으로 연결한다. 현재 batch child 연결은 일반 `tasks.test`에만 있으므로 새 Test 작업에 자동 상속된다고 가정하지 않는다. 기존 provider/준비 작업을 재사용하고 O11 작업에 불필요한 batch 의존을 추가하지 않는다. API 제품 runtime에 다른 앱을 의존시키지 않는다. JUnit플랫폼·test프로필·Asia/Seoul·-Xshare:off·1g heap·maxParallelForks1·forkEvery1·failOnNoMatchingTests를 명시한다. 실제 외부자원 검증이므로 두 작업의 up-to-date/build cache를 끄고 결과/XML/JaCoCo경로를 각 작업 이름으로 분리한다. 기존 별도 `w6OutboxRecoveryTest`(O11)와 일반 `test`에서 실행되는 `WeeklyRankingFlowTest`·관련 회귀를 유지한다. 별도 W9 Flow Test 작업이 이미 있다고 쓰지 않는다. 기존 `JacocoReport`의 O11 순서를 유지하면서 두 신규 W10 Test 작업에 대한 `mustRunAfter`도 추가해, 같은 작업 그래프에 포함된 보고서가 각 exec 생성 전에 실행되지 않게 한다. 순서 제약은 수동 Lab를 자동 실행시키는 dependsOn이 아니다. Lab의 JaCoCo destinationFile은 API 모듈 `build/checkout-incident-lab-coverage/checkoutIncidentLab.exec`로 명시한다. 기존 공통 보고서의 `build/jacoco/*.exec` glob 밖이므로 이번에 실행하지 않은 Lab의 과거 exec가 공통 보고서에 포함되지 않는다. Lab exec를 기존 glob 경로에 복사하거나 공통 executionData에 별도로 추가하지 않는다. 자동 `checkoutIncidentTest`의 exec는 기존 작업별 `build/jacoco/checkoutIncidentTest.exec`에 두고, 루트 공통 보고서 설정은 변경하지 않는다. 신규 작업을 이전의 잘못된 기본 경로로 실행한 개발 산출물 `build/jacoco/checkoutIncidentLab.exec`가 남아 있으면 소유 경로/출처를 확인해 해당 파일 하나만 별도로 보존·수집 경로 밖으로 이동한 뒤 보고서를 생성한다. 원인 미확인 exec를 임의 삭제하거나 build 전체를 정리하지 않는다. 이 경로 계약은 Lab 혼입만 방지하며 공통 보고서 전체가 이번 실행의 자료만 포함한다고 보장하지 않는다. 기존 다른 작업의 잔여 exec 가능성과 실제 테스트 수/failure/error/skip·코드 기준·실행 시각의 검증 증거를 구분한다. Lab 커버리지의 공통 보고서 통합/별도 보고서 작업은 이번 범위에 추가하지 않는다.

추가 메서드 계약:

- `IncidentMonitoringSupport.start(runDir, actualManagementPort, localImageIds, deadline)`은 새 프로젝트와 실제 published port를 반환한다. 시작 전후 자원을 즉시 소유 목록에 추가해 부분 기동 실패도 정리할 수 있어야 한다.
- `awaitFreshSamples(expectedUnknown, phaseStableAt, deadline)`은 §7.3을 충족한 두 sample과 원문 위치를 반환한다. 호스트 phaseStableAt은 기록용이고 함수 내부에서 안정화 후 collector 기준 시각을 얻는다. 다른 instance/없는 값/NaN을 허용하지 않는다.
- `IncidentCheckpoint.await(stage, immutableFacts, deadline)`은 ADVANCE 또는 취소 예외만 반환한다. 단순 시간 경과를 ADVANCE로 바꾸지 않는다.
- `IncidentEvidenceWriter.writeStage(stage, immutableAllowedSnapshot)`, `sealFault()`, `verifyFaultUnchanged()`, `recordFailure(stage, failureClass, allowedContext)`는 §9의 원문·실패 의미를 따른다. JSON에서 비유한 gauge 값은 숫자0이나 비표준 숫자로 바꾸지 않고 원문의 `NaN` 문자열과 `finite=false`를 보존한다.
- `CheckoutIncidentScenario.relayAndAwait(orderIds, deadline)`는 §8.4의 실제 eventId별 전달·receipt·consumer commit·행/수량 대조를 모두 완료해야 반환한다.

OBSERVED의 안내는 해당 Gradle 작업의 `testLogging.showStandardStreams=true` 출력과 runDir의 `operator-guide.json`으로 제공한다. 새 runDir 확보 직후 고정 prefix `W10_LAB` 한 줄에 runId·runDir·안내 예정 절대경로·status=PREPARING을 내보낸다. 이 시점에는 안내 파일이 아직 없고 관찰/명령 입력을 시작하지 않는다. runDir는 이번 실행이 새로 생성해 단독 소유하며 `operator-guide.json`은 monitoring 준비를 조율하는 한 writer만 쓴다. 운영자/다른 helper는 안내 파일에 쓰지 않는다. 이 전제 안에서 collector/실제 Grafana URL·로그 위치·control 디렉터리 준비가 완료되면 안내 JSON 전체를 같은 디렉터리의 새 임시 파일에 완성하고 닫은 뒤, 기존 최종 파일이 없음을 확인해 atomic move로 `operator-guide.json`을 한 번만 공개한다. 프로그램은 최종 파일을 한 번만 공개하며 관측한 기존 파일/충돌·atomic move 미지원/실패는 준비 실패다. atomic move는 완성 파일을 한 번에 보이게 하는 수단이고, 존재 확인과 이동 사이에 제3자가 쓰는 상황까지 덮어쓰기 방지를 보장하는 no-replace 장치가 아니다. 단독 소유/단일 writer 전제가 깨진 동시 쓰기는 지원 범위 밖이다. hard link·동시 writer 지원을 추가하지 않는다. 공개 후 status=READY와 안내 경로를 출력하고 나서 첫 checkpoint에 진입한다. 각 ready 발행 때 stage/ready 절대경로를 같은 prefix로 알리고 nonce/만료/명령 예상경로는 그 ready에서 읽는다. 공유 latest 파일이나 안내 파일을 나중에 덮어쓰지 않는다. 비밀값은 출력하지 않으며 안내/주소/ready 생성 실패는 관찰 대기로 들어가기 전에 실패한다. AUTO에는 사람 대기/안내 요구를 추가하지 않는다.

### 6.2 준비

```text
새 runDir/code상태/실제 시각/소유목록 확보 → 안전한 로그capture 부착
Docker·MySQL/Redis testcontainers·LocalKafkaTestSupport 실제 주소 준비
새 PG 시작(Settlement.DISABLED), API/management loopback 임의port 실제확인
  commerce.pg.base-url/callback을 실제 주소로 주입
  order-events topic/group은 실행고유, 자동relay.enabled=false assert
  원본 API Clock만 고정; bootstrap app의 제품schema는 새DB에 한 번만 생성
streamer 시작 전 구매테이블 부재 확인/소유선언 → 실제자식 시작/assignment
batch schema 준비child는 실제같은DB, 업무job미실행·테이블정의 확인
사용자/상품/point/coupon/명단 준비, commit후 기준값 확보
OBSERVED면 collector 시작·실제target/0 sample·Grafana 준비 확인
```

LocalKafkaTestSupport는 공유broker를 종료하지 않으며 system property→환경변수→기존localhost:19092 순서를 유지한다. 다른 DB/Redis/Kafka를 조용히 대역으로 치환하거나 연결 실패를 skip하지 않는다. 실행 환경의 포트/이미지/운영자 이름은 입력값이며 새로운 제품 정책이 아니다. 없는 환경 입력/도구는 명확히 실패하고 해결 없이 실행 성공으로 진행하지 않는다.

## 7. 실제 수집·Grafana·로그 연결 계약

### 7.1 포트와 실행 소유

API 업무 port는 기존 FlowTest처럼 loopback ServerSocket으로 임의 port를 예약·확인한 뒤 닫고 그 값을 기동 인자로 전달한다. callback도 동일한 API port를 기동 전에 주입한다. release와 bind 사이의 경쟁은 남으므로 실제 bind 실패는 실패로 기록하며 다른 서비스에 요청하지 않는다. `management.server.port=0`, 두 주소는 모두127.0.0.1로 두고 실제 management port를 Boot의 management context/LocalManagementPort에서 얻는다. 설정 문자열0·가정8081을 실제 port로 쓰지 않는다. 테스트의 `management.endpoint.health.show-details=never`를 명시해 실제 health 상태 원문에 연결 비밀/상세 예외가 섞이지 않게 한다. 상태/기본 component 이름은 조작하지 않는다. 기동 후 실제 API port와 callback/manifest가 일치하는지도 검증한다. callback 주소를 기동 후 변경하는 새 제품 기능은 추가하지 않는다.

기존 공유 `docker/monitoring-compose.yml`·prometheus.yml·datasource를 덮어쓰거나 공유stack을 restart하지 않는다. OBSERVED전용으로 runDir 아래 config를 생성하고 `w10-<runId>` 프로젝트에 Prometheus/Grafana2개만 띄운다. container_name은고정하지않고Dockerlabels/project/실제containerID를소유목록에기록한다. 호스트공개port는127.0.0.1에동적할당해9090/3000충돌을피하고 `docker compose port` 결과를검증해실제browser/API주소로저장한다.

이미지는 기존 compose의 `prom/prometheus`, `grafana/grafana`와 호환되는 로컬 이미지 ID를 실행 전 해석해 고정하고 버전/ID를 metadata에 남긴다. 이미지 부재 시 자동으로 임의버전 설치/기존stack교체를 하지 않고 준비 실패로 보고한다. 필요한 이미지 준비는 실행 승인 범위에서 별도로 확인한다. version/도구/실제 port 가용성은 문서작성만으로 검증되지 않았다.

Grafana datasource는 프로젝트 내부 `http://prometheus:9090`으로 고정하고 외부계정은 연결하지 않는다. 로그인은 기존 로컬실습 기본계정으로만 사용하며 인증값을 evidence·로그·프롬프트에 복사하지 않는다. datasource/dashboard 설정·이미지명·port·컨테이너목록은 합성/비밀아닌 값만 내보낸다. 실행용 설정파일 전체를 제출 증거로 자동복사하지 않는다.

### 7.2 수집 설정과 준비 판정

run별 prometheus.yml:

```yaml
global:
  scrape_interval: 5s
  scrape_timeout: 3s
scrape_configs:
  - job_name: checkout-incident
    metrics_path: /actuator/prometheus
    honor_timestamps: false
    static_configs:
      - targets: ['host.docker.internal:<actualManagementPort>']
```

target에는runId/orderId label을추가하지않는다. macOS Docker Desktop의host.docker.internal로loopback management에실제로도달하는지target준비에서확인한다. 실패시bind를0.0.0.0으로넓히거나관리endpoint를외부공개하는fallback을추가하지않는다. endpoint 직접 GET 성공과container도달성은별개이며실패 원문을보존한다. 환경차이로다른연결이필요하면그부분을멈추고영향/대안을확인한다.

시작 준비는60초 이내에Prometheus `/-/ready`,Grafana `/api/health`, 해당실제target의 `up=1`·빈lastError·정상scrape 및 finite UNKNOWN0을 확인한다. health/metric 노출은health/prometheus만유지하며env/heapdump를열지않는다. directendpoint와collector가서로다른API에붙지않도록metadata의actualport와targetlabel을비교한다.

Prometheus의 **예정 간격으로 sample timestamp를 정렬하는 보정**은 endpoint timestamp 존중 여부와 다른 기능이다. OBSERVED 실행 전 고정한 로컬 이미지 버전의 `scrape.adjust-timestamps` 지원을 확인하고, 이 실행 전용 collector 시작 인자에 `--scrape.adjust-timestamps=false`를 명시한다. 고정 이미지의 inspect 결과에서 Entrypoint와 기본 Cmd 인자 전체를 확인한다. 기존 Entrypoint를 유지하고, 실행 전용 compose command에는 기본 Cmd의 순서/인자(설정 파일·storage 경로 등)를 모두 보존한 목록을 전달한다. 그 목록에 scrape.adjust-timestamps가 있으면 해당 항목만 false로 교체하고, 없으면 false 항목을 추가해 중복 플래그를 만들지 않는다. config 파일 경로는 실행별 생성 설정의 mount 위치와 기존 인자가 일치하게 한다. 기본 Cmd나 Entrypoint를 해석/보존할 수 없으면 준비 실패이며 짧은 command 한 줄로 이미지의 다른 기본 인자를 지우지 않는다. evidence에는 이 실행에서 사용한 허용된 인자/설정만 기록하고 inspect 전체·환경/비밀값을 복사하지 않는다. 기동 후 같은 collector의 `/api/v1/status/flags`에서 실제 `scrape.adjust-timestamps=false`를 확인해 버전/이미지 ID·인자/유효값의 허용 부분을 evidence에 남긴다. 지원 부재·유효값 미노출·조회 실패·값 불일치는 준비 실패다. 기본값에 의존하거나 임의 오차 여유로 통과시키지 않는다. 공유 collector 인자는 바꾸지 않는다.

확인 근거: Prometheus v2.53.3의 cmd/prometheus/main.go에는 위 보정 옵션 기본 true와 timestamp-tolerance 기본2ms가 있고, scrape/scrape.go의 scrapeLoop.run은 수집 시각을 예정 간격의 시각으로 보정할 수 있다. 이는 소스 확인 사실이며 현재 로컬 이미지 버전/지원/적용의 실행 증거가 아니다. `honor_timestamps=false`만으로 이 보정을 끌 수 없다. 비활성화를 선택한 이유는 S>C가 안정화 이후 scrape 시작이라는 전제를 직접 유지하기 위해서다. tolerance를 확인해 여유를 더하는 대안은 버전별 오차 정책을 추가로 관리해야 하므로 이번에는 채택하지 않는다.

### 7.3 각 단계의 fresh sample과 화면

각 단계는 업무commit/relay활동안정화 후 호스트 `phaseStableAt`을 기록한다. 이것은 실행·화면 대응용이며 Docker collector의 sample 시각과 직접 대소 비교하지 않는다. 정상 confirm 중의 일시 UNKNOWN과 단계 안정값을 구분한다. 신선도는 같은 Prometheus의 시각 안에서 다음 순서로 판정한다.

```text
호스트 monotonic 기준 20초 예산 시작 (기준시각 조회도 예산에 포함)
업무 안정화 이후 같은 collector의 instant query time() 호출
  명시적인 과거 time 파라미터/캐시 없이 실제 응답의 scalar 값 C 확보
  요청 시작/종료 호스트 시각·C·collector 주소를 원문에 기록
이후 같은 job/실제 instance의 gauge와 up을 같은 raw sample 조회 응답에서 읽음
  실제 sample 시각 S > C인 표본만 후보; S <= C는 과거/진행 중 scrape로 보존하고 대기
  같은 S의 up=1과 gauge 기대값0/3/0을 짝지음; 서로 다른 S 두 쌍 확인
  C 이후 up=0 또는 up=1인데 같은 S의 gauge 누락/NaN/불일치면 실패
  매 poll의 time()도 보존; 관측된 collector 시각 후퇴/미래 sample 모순이면 실패
  남은 예산 안에서 두 표본을 얻지 못하면 timeout 실패
```

collector 설정은 `honor_timestamps: false`를 명시해 endpoint가 준 시각이 아닌 collector의 scrape 시각을 사용한다. sample 시각은 실제 scrape 시작 기준이고 완료/조회 시각과 구분한다. 따라서 C 이전에 시작해 이후 끝난 scrape를 새 표본으로 인정하지 않는다. 구현에서는 채택한 로컬 Prometheus 버전의 이 timestamp 의미와 실제 원문 연결을 확인한다. 호스트와 VM의 절대 시각이 같다는 가정이나 임의 1초 오차 한도는 추가하지 않는다. 관측 사이의 모든 시계 급변까지 검출한다고 보장하지 않으며 시각 불연속이 드러나면 수치를 보정해 통과시키지 않고 중단한다.

표본 대응은 instant query API의 range-vector 식 `{__name__=~"commerce_payment_unknown|up",job="checkout-incident",instance="<target>"}[20s]`로 두 metric의 raw timestamp/value 배열을 한 응답에서 읽어 수행한다. 이것은 query_range의 step별 평가 결과나 instant-vector 응답의 바깥 평가 timestamp가 아니다. 실제 sample timestamp S를 기준으로 두 metric을 대응하며, 동일 metric의 series가 여러 개면 실패한다. 기존 application 등 유한 tag는 원문에 보존하되 job/instance가 같은 target인지 확인한다. C 이후 실제 up sample이 아직 없으면 준비 중으로만 기록해 남은20초 안에서 기다린다. 신선한 up=0, 또는 up=1인데 같은 S의 gauge가 없거나 비유한/기대값불일치이면 즉시 실패한다. 뒤 성공으로 덮지 않는다. `timestamp(gauge)`를 보조로 조회할 수 있지만 그 값과 raw S를 구분하고 평가시각만으로 신선도를 판정하지 않는다.

`/api/v1/targets`의 lastScrape·health·lastError는 각 HTTP 요청 시작/종료시각과 함께 별도로 저장한다. targets 응답은 더 나중 scrape의 정보일 수 있으므로 S의 성공/실패 판정을 그 값으로 덮어쓰지 않는다. S의 수집 성공 근거는 같은 S의 up=1이다. target 식별/주소 불일치는 준비 오류로 실패한다. 각 raw sample/targets/query 원문을 순번파일에 남기고 고정 sleep이나 수집 주기 하나를 더 기다린 사실로 신선도를 대신하지 않는다.

Grafana는위datasource의해당job/instance UNKNOWN시계열을5초refresh로보는단일기본panel을준비한다. 업무자료를더넣은대시보드/알림은추가하지않는다. 화면관찰은명시checkpoint동안이뤄지고run의실제시작/종료범위와phase시각을함께본다. 캡처는부가자료이며직접원문/동일ID/효과 assertion을대체하지않는다. 앱로그는run별로그capture와streamer/batch자식파일로읽는다. Grafana에로그검색backend가구성돼있다고가정하지않는다.

## 8. 단계 진행·진단·복구·후속 결과

### 8.1 정상과 장애 처리

baseline 입력 전에 §8.1.1의 가설·예상·자동 판정 규칙을 `diagnosis-expectations.json`에 CREATE_NEW로 고정하고 metadata에 SHA-256을 남긴다. 실제 관찰을 본 뒤 예상/규칙을 바꾸지 않는다.

```text
BASELINE:
  §5 등록 helper의 성공 등록0 확인
  B10 각각 실제create/order-confirm → PG기본PENDING 확인
  실제PG거래를 SUCCESS로변경(setStatus는PGfixture제어,commerceDB직접수정아님)
  같은사용자 reconcile 실제HTTP → 권위조회/내부최종확정 확인
  각eventId를실제relay.runEvent로전달하고receipt/consumercommit/활동 확인
  새DB조회·PGstate·create수·point/coupon·health·Prometheus원문검증/저장
  OBSERVED fresh sample0 확인 → BASELINE_READY

FAULT:
  F/U명단 고정기록 완료 확인
  F-U7개는B와같은PENDING→SUCCESS→본인reconcile정상경로
  U3개는각successThenCloseEarly한plan → 실제confirm202/UNKNOWN
    callback/reconcile을아직호출하지않음
  §5 등록3개/세 대상 소비 및 activeRequests0·activeCallbackJobs0·미해제gate0 확인
  각U CLOSED_EARLY/거래SUCCESS/create1 확인
  확정17개만실제relay/활동안정화; U Outbox0/활동0 확인
  F7/UNKNOWN3/PG10·전체값표·지표3·healthUP·TX로그대조
  진단용PG읽기·TX로그/가설대조 완료, OBSERVED fresh sample3 원문도 확보
  필수 fault원문·diagnosis를 write-once 저장/봉인 → FAULT_READY
```

PG 지연/응답 유실/내부 반영 실패별예상·실제·판정(지지/배제/미확정)·근거파일을 `diagnosis.json`에 쓴다. U의진단은기존계정으로proxy baseUrl을통한PG목록/거래GET을사용하며commerce결과적용을호출하지않는다. HTTP진단 조회후commerceUNKNOWN/예약/Outbox가그대로인지새조회로확인한다. fixture.state조회와HTTP권위조회는출처를구분하며이진단 조회도lookup횟수에포함한다. 가설표를자동으로“주입코드가그러므로응답 유실”로채우지않는다.

AUTO는수집된경계결과/거래/DB/실제 TX종료가일치하는지명시assert하고가설대조표를생성한뒤승인된고정U/기존reconcile만진행한다. OBSERVED는사람이이를읽고판정/조치범위를확인한후명시 신호를발행한다. 같은두관찰을만드는다른가설을배제할근거가없으면미확정으로남기고복구 전진행을멈춘다.

### 8.1.1 예상·자동 판정·사람 판단의 분리

예상 파일은 아래 세 가설의 범위와 필수 증거를 고정한다. `diagnosis.json`은 예상 파일 hash, U별 실제 증거 경로/식별자/callId/observationId, 자동 판정과 reason을 저장한다. fixture plan 이름을 판정 입력으로 쓰지 않는다. journal은 실제 전송 경계 관찰로 다른 증거와 대조하며 단독 정답으로 쓰지 않는다.

|가설/범위|사전 예상·판정 규칙|
|---|---|
|진단 관측 시점에도 PG가 처리 중|동일 계정/주문/금액의 유일한 권위 HTTP 조회가 PENDING이면 지지, SUCCESS이면 배제, 실패/0건/복수/불일치는 미확정. 과거에 잠시 PENDING이었을 가능성까지 배제하는 뜻은 아님|
|PG SUCCESS 생성 결과를 전달 중 잃음|권위 SUCCESS + 동일 CREATE의 RESULT Unavailable 및 실제 원인에 따른 reason(TRANSPORT_UNAVAILABLE 또는 CREATE_RESULT_UNAVAILABLE) + 대응 journal CLOSED_EARLY/같은 거래 + APPLY_INPUT Unavailable·DECISION UNAVAILABLE·실제 COMMITTED + 새 DB UNKNOWN/예약 유지/Outbox0이 모두 일치하면 지지. timeout으로 좁혀 부르지 않음. 필수 증거 누락/불일치나 다른 해석이 남으면 미확정|
|성공 관찰의 내부 적용 TX 실패로 미확정 유지|해당 생성/조회 호출에서 SUCCESS 관찰·그 관찰의 APPLY_INPUT·실제 ROLLED_BACK과 새 DB 미반영이 연결되면 지지. U의 포착한 CREATE에는 성공 관찰 대신 Unavailable 입력과 그 TX의 COMMITTED가 완결되게 연결되고, fault 전 U의 callback/reconcile 호출0·다른 적용/진단오류 없음까지 확인한 경우 그 호출의 원인으로는 배제. 로그 부재만으로 배제하지 않음|

공통: ID/금액/거래·호출 대응, START→RESULT/ERROR와 실제 TX 종료의 완결성부터 검사한다. **2026-11-23 사용자 결정:** callId와 observationId는 서로 다른 UUID이므로, U별로 providerOrderId가 같은 CREATE START/RESULT 쌍과 orderId가 같은 `APPLY_OBSERVATION`(source=CREATE)을 찾고, fault 구간에서 각각 정확히 1개이며 로그 순서가 RESULT→APPLY일 때만 같은 생성 호출로 연결한다. 0개·복수·순서 역전은 미확정이다. 누락/UNKNOWN/진단 실패/예상 밖 추가 적용은 자동 판정 미확정 및 진단 실패이며 복구 전 중단한다. 각 판정은 이 실행의 포착 범위에만 적용한다. §4.4의 모든 U 조합이 성립하고 유실 가설 지지·나머지 두 가설의 해당 범위 배제가 확인돼야 AUTO가 기존 reconcile로 진행한다. 세 가지 모든 장애를 새로 주입하는 범위는 추가하지 않는다.

OBSERVED의 코드 판정/실제 증거는 FAULT_READY 전에 봉인한다. 운영자는 ready 뒤 이를 읽고 별도 `control/operator-review.json`을 완성해 atomic move로 공개한다. schemaVersion1/runId/stage=FAULT/ready nonce/operator/createdAt, 봉인 diagnosis의 상대경로·SHA-256, 현재 U 목록, 가설별 판단(지지/배제/미확정)·근거 상대경로·비어 있지 않은 이유, decision=PROCEED 또는STOP을 포함한다. 사람 판단이 자동 판정을 덮어쓰지 않는다. PROCEED는 모든 U에 대해 위 진행 조건과 일치할 때만 가능하며 이견/미확정이면 STOP/CANCEL로 남긴다. CANCEL을 소비할 때 제출된 STOP 판단 파일이 있으면 검증해 hash를 함께 보존하되, 파일이 없다는 이유로 취소 자체를 막지 않는다. 자동 실패를 사람 판단으로 우회하지 않는다. 자동 판정의 한계와 이견을 보존하고 후속 설계 토론으로 넘긴다.

사람 파일은 §8.2의 runDir 내부/비 symlink/32KiB/형식·시각·nonce/operator 검사와 같은 정책을 적용한다. accepted 기록 전에 읽은 bytes의 hash를 저장해 소비 후 수정도 확인한다. 이 기록은 사람이 어떤 근거로 판단했는지를 남기며 판단의 진실성·운영자 인증을 자동 증명하지 않는다. AUTO는 사람 판단 파일을 합성하지 않는다.

### 8.2 명령파일 계약

`IncidentCheckpoint.await(stage, facts, deadline)`은업무/증거assertion이끝난뒤에만호출한다. runDir/control은실행 전용이고외부서버/제품제어API를추가하지않는다.

- ready파일: schemaVersion1, runId, stage(BASELINE/FAULT/RECOVERY), 임의UUIDnonce, createdAt, expiresAt, evidence상대경로, 다음허용조치. `ready/<stage>.json`을CREATE_NEW로기록한다.
- 실행담당은 사용자관찰/판단과이미승인된조치범위를확인한뒤 `commands/<stage>-<nonce>.json`을임시파일에완성하고atomic move로공개한다. payload는schemaVersion/runId/stage/nonce/action=ADVANCE 또는CANCEL/operator/createdAt이며, FAULT ADVANCE에는 diagnosis와 operator-review의 상대경로·SHA-256을 추가한다. CANCEL은 사람 판단 파일 없이도 가능하다. operator는실행 전정한한사람/도구표기이며계정 비밀이아니다.
- 파서는기대파일만읽고정규화경로가runDir밖으로나가거나symlink이면거절한다. schema/형식/32KiB상한/nonce/단계/runId/action을검증하고현재ready시각이후·만료이전인지확인한다. 불일치·손상명령은진행신호로간주하지않고실패기록후취소/정리한다. 무한재시도하지않는다.
- FAULT ADVANCE는 현재U목록과 §4.4·§8.1.1 대조를 담은 봉인 diagnosis 및 ready 이후 별도 사람 판단 PROCEED 파일을 참조하고, 원문 hash/nonce/operator/목록/진행 조건 일치를 모두 검증해야 한다. fixture정답/그래프3만으로복구허가를대체하지않는다. 코드가사람의판단진실성을증명하지는못하며누가무슨근거로진행했는지기록한다.
- 소비결과는 `accepted/<stage>.json`에CREATE_NEW로남기며소비한명령을다른단계/실행에재사용하지않는다. 실행 시작 metadata에 고정한 단일 operator만 명령/사람 판단 파일을 발행하며 두 파일의 operator가 metadata와 일치해야 한다. 로컬 HANDOFF를 구현 계약이나 런타임 입력으로 읽지 않는다. 이 문자열 일치 검사는 인증/동시 writer 잠금이 아니며 실행 담당을 한 명으로 제한하는 운영 전제를 기록한다. accepted에는 소비한 명령/판단 파일 hash를 남긴다. 중복명령은새조치를만들지않고기존소비기록과대조해오류로남긴다.
- 각checkpoint 최대5분, 전체 실습30분. 남은전체시간이더짧으면그 한도가우선. poll간격200ms,monotonicdeadline,interrupt는flag복원후취소처리. timeout/CANCEL은ADVANCE나자동복구가아니다. STOP 판단은 복구를 허용하지 않고 CANCEL 또는 남은 한도 내 timeout으로 종료한다. STOP/CANCEL/timeout/interrupt는 wrapper에서 중단 예외를 정상 반환·JUnit assumption/aborted/skip으로 변환하지 않으며, 실습 미완료 실패로 기록하고 finally 정리를 수행한다. AUTO/OBSERVED 모두 완료 assertion 전 중단은 통과가 아니다. AUTO는사람명령파일을발행하지않으며같은assertion뒤진행한다.
- **2026-11-23 구현 세부 사용자 결정:** AUTO는 ready·command를 만들지 않고 `accepted/<stage>.json`에 action=AUTO_ADVANCE와 입력 사실 hash만 남긴다. OBSERVED에서 commands/에 현재 기대 파일 외의 파일이 있거나 소비한 명령·판단 파일의 bytes가 accepted hash와 달라지면 내용을 읽지 않고 파일명만 실패로 기록한 뒤 취소한다. control 파일은 `runDir/control/` 아래 `ready/`·`commands/`·`accepted/`·`operator-review.json`이며 운영자 임시 파일은 `control/tmp/`에서 완성해 atomic move한다.

### 8.3 복구와 반복 적용

```text
RECOVERY:
  fault봉인/진단/명시허가(OBSERVED) 완료 확인
  U3개이외조정대상없음, §5의 진행 요청/콜백/미해제gate0·세 주입 소비·추가 등록0 확인
  복구 시작 journal 순번/CREATE20 고정
  각U 본인 POST /api/v1/orders/{id}/payment/reconcile 실제호출
    기존계정/주문/금액/거래일치와SUCCESS로만내부확정
    호출1회에서조회0/복수/불일치/PENDING/Unavailable/비정상응답이면
      기록후중단;이번실습이자동retry/create/강제DB수정을추가하지않음
  새DB조회로같은F10개=CONFIRMED10/UNKNOWN0, 동일거래10, create증가0
  point/coupon·예약/재고·confirmedAt·Outbox사실대조
  각U reconcile1회추가 + callback2회명시전송/응답대기
    동일종결·동일거래/예약40행/반환0/Outbox20·create20 유지
  다른사용자의U주문조회/reconcile은기존404 ORDER_NOT_FOUND
    PG호출/저장효과변화없음 확인
```

정상 결과가되도록재시도하기보다최초실패 원문을남긴다. 중간에일부U만복구됐다면그상태그대로실패 원문/각TX/거래를남기고새 주문으로10건을채우지않는다. 기존product재고/결제판정과PG조회충돌보호는완화하지않는다.

### 8.4 실제 전달·랭킹

`relayAndAwait(orderIds)`는각실제eventId에기존 `OrderOutboxRelay.runEvent`를호출한다. 기존Flow처럼실제transactions/verifier/sender와receipt관측probe를사용한다. 정상결과PUBLISHED·해당topic/partition/offset와DB상태를확인한뒤streamer의APPLIED/기존중복일때ALREADY_PROCESSED와groupcommitted offset>=offset+1을기다린다. 고정1건runDueBatch기대나단순sleep를복사하지않는다. 각호출/대기는남은전역시간으로제한하고기존35초send wait/PG회로설정을바꾸지않는다.

복구U를전달한후전체40활동행과각주문두상품quantity2/1을새DB조회로대조한다. U중1개를실제 Kafka에같은v1사실로재발행해consumer중복회귀를확인해도새원문입력40행/수량은유지해야한다. 이중복재발행은한건의동일사실대조만하고Kafka전체장애/성능실험으로확대하지않는다. 업무효과/offset관측을남긴다.

기존batch child.startNew로해당주의새snapshot1개를만들고JobExplorer COMPLETED·publicationOutcome PUBLISHED·input_count40·결과2상품·API A40/B20 순위를확인한다. 기존공개본을UPDATE하지않고W9고정입력/새revision규칙을사용한다. W10에는fault추가snapshot·chunk실패를섞지않으며기존W9회귀를별도로실행한다.

복구원문·point/coupon·실제health/metric0·fresh sample0·랭킹응답을저장하고fault원문hash불변을검증한뒤RECOVERY_READY에서관찰한다. 이ready다음ADVANCE는추가결제가아니라정상종료/정리다.

## 9. 원문·실패·정리·재진입

### 9.1 write-once evidence

`IncidentEvidenceWriter.openRun(root,codeReference,mode)`는root밑에충돌하지않는새runDir를생성하고metadata를CREATE_NEW로쓴다. 기본 root는API build/checkout-incident-runs이다. runId,HEAD/작업diff기준과hash,실제시작UTC시각,합성Clock,mode,operator(실행입력),소유DB/PG/프로세스/topic/group/collectorID·이미지·실제 port를허용필드로기록한다. 비밀번호/헤더/카드/전체환경·실행명령전체는저장하지않는다. 코드 기준은 HEAD와 tracked diff hash뿐 아니라 실행에 영향을 주는 미추적 소스/빌드설정 파일의 repo-relative 경로·SHA-256 manifest도 포함한다. 새 구현 파일이 아직 untracked일 수 있으므로 git diff만으로 같은 코드라고 판정하지 않는다. 로컬 주차 운영 문서·인증/실행 비밀은 이 코드 manifest 대상에서 제외한다.

**2026-11-23 구현 세부 사용자 결정:** `metadata.json`은 openRun 시점에 아는 값만 CREATE_NEW로 한 번 쓴다. baseline 전 고정하는 예상 파일의 hash는 `metadata-expectations.json`, 준비 중에 알게 되는 소유 자원(실제 port·topic/group·자식·collector)은 `resources/<순번>-<종류>.json`으로 각각 CREATE_NEW 추가하며 어느 파일도 덮어쓰지 않는다. tracked diff는 `git diff HEAD --binary`(staged+unstaged) 전체의 hash이고, untracked manifest는 `git ls-files --others --exclude-standard` 중 `apps|modules|supports/*/src/` 아래와 `*.gradle.kts`·`gradle.properties`·`gradle/` 아래 파일이며 `docs/`는 제외한다. git 실행 실패는 준비 실패다. 단계 원문은 `baseline|fault|recovery/` 아래 이름별 파일로 두고, 구조 데이터는 JSON(비유한 값은 원문 문자열과 `finite=false`), HTTP 본문 원문은 바이트 그대로 저장한다.

`writeStage(stage,AllowedSnapshot)`는다음 필수 항목을write-once로남긴다.

- 실제주문/cohort/attempt/providerOrder/검증한합성계정참조/금액/거래/상태/확인시각 대응표.
- 주문·결제·예약/재고·Outbox·활동·point/coupon·랭킹의읽기쿼리/조회범위·시작/종료시각·허용row값.
- PG fixture.state/journal/count와실제권위HTTP진단응답(합성허용필드),baseline/fault/recovery실제health·Prometheus응답의상태/본문/시각.
- 직접수집/실제collectorAPI/그래프캡처의출처구분,각신선sample의실제 시각,진단가설/예상/관찰/판정/조치.
- parent의허용경계로그·streamer/batch자식로그위치/내용과결과,assertion실패·중단·정리결과.

로그capture는전용logger에test ListAppender/파일appender를부착하고실행끝에정확히자기appender만분리한다. 기존공유root설정·다른 실행로그를지우지않는다. 자식로그는실행소유경로로저장하고원본을임의편집하지않는다. SQLbind/rawrequest/카드가새원문에섞이지않도록출력구성을확인한다. 스크린샷도관리자로그인/계정 비밀을담지않는다.

`sealFault()`는 필수파일/내용 assertion 성공 후 fault의 SHA-256목록과 코드 진단원문·예상 파일 hash를 봉인한다. ready 뒤 작성하는 operator-review/진행 명령/accepted는 봉인 fault 파일을 수정하지 않고 별도로 저장하며 소비 hash로 대응한다. `verifyFaultUnchanged()`는복구 뒤기존파일바이트hash를비교한다. 파일존재/hash일치만으로상태/효과를통과시키지않는다. 실패 원문은별도순번/시각파일에append방식으로추가하며기존파일에덮어쓰지않는다. 증거파일쓰기실패면특히복구 전진행을중단한다. 오류기록도실패하면원래오류를가리지않고suppressed/별도실패로알린다.

build는ignore대상이므로성공/실패 원문보존과Git제출을구분한다. 승인후선별한실행의허용원문/metadata/가설/검증범위·재현명령만 `docs/week10/verification/evidence/<runId>/`에바이트그대로복사하고원본hash와대조한다. 이실제증거디렉터리는제출선별대상이며현재없다. 로컬scenario-review·HANDOFF·프롬프트를필수근거로링크하지않는다. stage/ignore변경은자동수행하지않는다.

### 9.2 제한 시간과 정리

|구간|한도/정책|
|---|---|
|AUTO wrapper|420초. 기존W9@Test420초도유지|
|OBSERVED wrapper|30분,각ready5분. 업무I/O한도는AUTO와동일|
|직접health/Prometheus/collectorAPI|connect/read각3초. 호출전체의수학적3초상한이라고쓰지않음|
|phasefreshsample/DB활동안정화|20초,실제조건poll. collector기동준비60초|
|batch child1회|최대120초와남은전역시간중작은값|
|기존relay PG/DB/child 종료|기존 계약을유지하되전체남은시간과정리예산구분. log에실제소요/종료여부기록|

cleanup은전역실행한도와별도로기존§11.6의유한종료대기를수행해자식이살아있는데종료성공이라고쓰지않는다. timeout이모든DB/프로세스를동시에취소한다는보장은아니다.

```text
try:
  setup → baseline → fault/diagnose/seal → recovery → downstream → finalchecks
catch original:
  가능한범위의failure snapshot을추가저장,원래오류유지
finally:
  이번run이향후등록할주입/콜백호출중지,등록gate가있으면자기것해제
  relay진입중단/idle대기
  자기collector정지·로그/상태 보존,그프로젝트의컨테이너/네트워크만정리
  batch/streamer자식종료확인
  API/PG context/fixture종료,자기appender분리
  자식종료가확인된경우에만소유테스트데이터/topic/group정리
  공유broker/로컬Redis/다른DB/다른compose/모든컨테이너일괄종료금지
  정리 실패는별도기록하고통과로변환하지않음
```

baseline→fault→recovery사이에cleanup/truncate/PGreset을호출하지않는다. 기존W7 cleanup helper를재사용하되collector정리와실패 원문저장을추가하고기존자식생존시데이터보존규칙을유지한다. 랭킹/Batch테이블은기존W9§11.6처럼테스트컨테이너수명에맡긴다. 물리테스트DB/PG는프로세스종료시사라질수있고이설계는완료/취소후그DB를재접속해복구를이어가는기능을보장하지않는다.

재진입은유효시간내현재checkpoint·같은실행에서만가능하다. 만료/종료후에는남은원문을보존하고새runDir/새 주문으로처음부터재현하며이전실행의미확정상태를성공으로덮지않는다. 실행중조회가불확실하면state를보존하고중단하는의미와프로세스종료뒤DB를영구보존한다는의미를혼동하지않는다.

## 10. 검증 대응·완료 근거

|ID|검증 항목|근거|
|---|---|---|
|I1|fixtureSUCCESS저장선행/유실/CREATE외거절/기존plan보존|fixture실제HTTP+PG단독조회+journal과기존 회귀|
|I2|UNKNOWN집계/상태제외/commit뒤변화/수집시조회/NaN/참조수명|실제DB·registry·Actuator,예외경계대역은별도표시|
|I3|PG분류·TXcommit/rollback/unknown/충돌별도TX/비밀미출력|기존probe/실제DB/로그capture+단위대조|
|I4|B10/F10/U고정/정상PENDING후조회/FAULT7·3·10|단계별동일ID/DB/PG원문과assertion|
|I5|사전 예상/규칙·자동/사람판정 분리·진단읽기전용/복구 전보존|판정 helper의 필수증거 누락/모순/미확정 단위대조, 실제HTTP·경계/TX로그·전후상태·봉인/판단hash|
|I6|본인복구10·0·10/create증가0/소유권/반복callback|실제controller/adapter/DB/PG/예약/Outbox|
|I7|point/coupon불변과비어있지않은대조군|잔액/기록·쿠폰목록/필드/campaign전후값|
|I8|실제relay/Kafka/consumer중복/활동40·A40/B20/배치/API|receipt/offset/commit·DB·snapshot/JobExplorer/API|
|I9|OBSERVEDtarget실제 port/보정OFF·동일시각up대응/fresh0→3→0/그래프+명시진행|유효flags·time()기준·raw S/up·진행중scrape제외·helper의시각/누락/targets갱신경쟁대조·원문·ready/accepted/화면/진단|
|I10|잘못된nonce/단계/runId/operator/경로·판단/hash/손상명령/중복/timeout/cancel|진행helper의파일/유한가짜시계단위검증,자동복구없음|
|I11|중간실패/증거쓰기실패/fault불변/정리소유/자식생존|수집helper·기존정리회귀·실제종료기록,강제미실행분기는명시|
|I12|누적기능·lint·ArchUnit·W9restart/revision|현재코드의새검사결과,0건/skip/중단/접속실패별도|

I9는AUTO의통과로대체하지않는다. I10/I11의제어된단위실패를실제외부장애/종료불명재현으로확대하지않는다. 신규UI스크린샷이나marker만으로통과시키지않는다. 기존유효회귀·기대값은삭제/완화하지않는다. 과거실행결과는이 변경의실행결과가아니다.

누적 회귀는상품/브랜드/좋아요/관리자·캐시·과거주문보존,Point/PG단일시도/예약/조회조정,Outbox발행gap/소비중복,쿠폰총량/중복/수용/본인목록,랭킹실제입력/동일instance재시작/revision/공개API를기능별로보고한다. API의기존check가O11을포함하는것을보존하고streamer/batch/PG변경모듈의check·관련회귀도포함한다. 기존W8opt-in8건은명시실행여부를구분하며skip을통과나이번새필수장애실험으로기록하지않는다.

코드/검증명령의최종클래스명과작업은구현후실재여부를확인한뒤실행한다. 설계시점의예정작업 `:apps:commerce-api:checkoutIncidentTest`·`checkoutIncidentLab`를현재실행가능하다고쓰지않는다. 구현진척/실행 시각/실패·skip/한계는실행기록에남긴다.

## 11. Runbook·제출·선택의 한계

`docs/runbook/week10.md`는구현/실습 작업에서실제명령·주소취득법을검증한후작성한다. 증상,명단고정,복구 전원문,읽기전용진단질의,경쟁가설,본인reconcile의전제/한도,중단조건,효과/후속결과,진행명령·취소·재진입과자기자원정리를포함한다. FAULT의 기존5분 한도 안에서 수행할 ready/nonce 확인 → 봉인 diagnosis 읽기 → 사람 판단 JSON 완성 → 두 파일 SHA-256 취득 → 명령 JSON atomic 공개 순서와 STOP/CANCEL 예시를 포함한다. 파일 생성/검증/hash 계산 절차는 도구로 보조할 수 있지만 가설 판단·근거·PROCEED를 자동으로 대신 작성하지 않는다. 이번 보완으로 새 진행 도구를 구현하거나 한도를 늘리지 않는다. 현재이경로의runbook이완성됐다고주장하지않는다.

Git제출후보는관측/fixture/연결테스트코드와설정·이기준설계세트·runbook·선별실행원문·기능별검증범위/남은위험·기술글이다. HTML기술글/WIL은사용자형식선택이고개인경험/감정은만들지않는다. 주차운영문서·로컬시나리오리뷰는필수 제출근거로삼지않는다.

선택/대안/재검토조건:

- 전용SUCCESS+유실plan은기존gate의시간경쟁을줄이고소규모확장으로순서를명확히한다. 일반장애DSL/원자FIFO재설계는추가하지않는다.
- UNKNOWN한정gauge는사고3→0을명확히하지만PENDING0/업무전체완료는별도검증해야한다. 미종결전체가운영질문이면label분리와관찰범위를재검토한다.
- 직접COUNT는freshness를단순하게하지만수집부하/DB지연에민감하다. 현재선택을운영규모보장으로쓰지않는다.
- afterCompletion은실제 TX결과와시도로그를분리하지만시작실패/프로세스종료/로그장애를모두설명하지못한다. 누락·UNKNOWN이면미확정,추가근거없이원인을확정하지않는다.
- 자동/명시wrapper가동일scenario를공유해검증차이를줄인다. 명시진행/collector의코드는추가비용이나사용자의실시간관찰요구에필요한범위다. 운영자동복구기능으로제공하지않는다.
- 두상품/다른수량은품목별후속효과를보지만동점·숨김·주경계·부하·재시작을이번사고에모두섞지않는다. 해당기존 회귀와이번미검증범위를구분한다.
- host.docker.internal/이미지/포트의가용성은실행 전확인이다. 도달실패를외부노출/공유설정변경으로자동해결하지않는다. 해당환경에서실행불가하면한계를보고하고연결대안을먼저검토한다.

공식계약확인출처는URL없이문서명/버전/절로남긴다: Spring Framework6.2 TransactionSynchronization API의afterCompletion/STATUS_*; Micrometer1.14 Reference의Gauges(관측시평가·대상참조수명); Prometheus Configuration의scrape_interval/scrape_timeout/static_configs/honor_timestamps 및HTTP API Querying(range-vector raw samples)/Targets/Status Flags; Prometheus v2.53.3 cmd/prometheus/main.go의scrape.adjust-timestamps/scrape.timestamp-tolerance와scrape/scrape.go scrapeLoop.run의timestamp 정렬. 참고페이지의patch버전과프로젝트의실제해석의존버전이같다고가정하지않고구현시확인한다. Spring Boot는저장소설정3.4.4,Java는21이다. 이출처확인은이번코드실행검증이아니다.
