# 결제 사고 관측·동일 주문 복구·후속 결과 연결

## 1. 상태·적용 범위·읽기 기준

2026-11-23 전체 설계 승인 후 작은 단위로 구현했고, AUTO 사고·복구·후속 결과와 단일 check를 검증했다. 이 문서는 현재 구현·검증 계약이며, 실행 기준·수량·미실행·한계는 실행 기록(`docs/verification/checkout-incident.md`)을 따른다. 운영 배포·실계정·부하·모든 장애의 검증 완료를 뜻하지 않는다.

이 문서와 연결된 기준 설계만으로 구현·검증 계약을 읽을 수 있어야 한다. 로컬 주차 토론 기록은 필수 근거가 아니다. 기존 제품 계약의 원본은 다음과 같으며 중복 정의하지 않는다.

- [계층·의존·TX](../common/architecture.md), [API 공통 규칙](../common/api-contract.md).
- [결제 처리](order-payment/processing.md), [PG HTTP·회로](order-payment/pg-client.md), [기존 결제 검증](order-payment/verification.md).
- [PG fixture 계약](pg-simulator/fixture-contract.md)의 W10 추가 절.
- [Outbox 발행](order-events/publication.md)의 W7 절, [소비](order-events/consumption.md).
- [주간 랭킹](weekly-ranking.md), 특히 [실제 연결·자원 소유권](weekly-ranking/verification.md) §11.4~11.7.

원문 근거는 「10주차 실습 · 원인을 구분하고 업무 결과로 복구 증명하기」 Implementation Quest §1~8·Checklist·Technical Writing Quest와 「10주차 · 사용자 여정의 증거로 사고를 복구하기」다. 원문에서 정한 수량/순서/보호 조건과 아래 구체적인 구현 선택을 구분한다.

추가 범위는 UNKNOWN 업무 지표 하나, 생성/조회/적용/TX 진단 로그, SUCCESS 뒤 응답 유실 fixture 조합, 동일 lifecycle의 20주문 연결 검증과 실행별 증거다. PG·주문·쿠폰·Outbox·랭킹의 외부 API/판정/회로를 바꾸지 않는다. 새 자동 reconcile scheduler·분산 추적 backend·Loki·운영 부하/실계정/운영 장애 검증·fault 시점 추가 랭킹 공개는 포함하지 않는다. 기존 W9 재시작/revision 회귀는 유지한다.

**제외 범위와 이유:** 사용자 결정(2026-11-23)으로 원문 필수가 아닌 OBSERVED Lab·실행별 collector/Grafana·사람 명령/판단 파일 진행을 제외했고 전용 코드를 삭제했다. 이 문서에는 현재 AUTO 계약만 남긴다. 변경 전 상세 절차는 Git 이력에 보존한다. 실제 health·Prometheus 원문·경계 로그는 AUTO가 직접 수집한다. 이미 떠 있는 공유 Prometheus·Grafana는 보조 관측으로만 재사용하며 수정·재시작·중지하지 않는다(§7).

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

주문별 ORDER_RESERVATION은 A/B 각각1행이며 전체40행이다. `changed_quantity`는 기존 `StockChange` 계약대로 변경량의 크기 2/1이고 감소 방향은 `before_quantity−after_quantity`=2/1로 확인한다(2026-11-23 사용자 결정: 기존 표기 -2/-1은 감소 방향 표기였다). ORDER_RELEASE와 PG에 의한 ORDER_DEDUCTION은0이다. UNKNOWN3개의 예약은 유지되고 최종 SUCCESS 적용은 재고를 다시 차감하지 않는다. 포인트 잔액/충전 이력, 쿠폰 ID/사용자/캠페인/혜택/발급시각과 campaign issuedCount는 전 단계에서 준비 직후와 같다. 단순 행수뿐 아니라 저장된 값을 대조한다.

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

테스트 타입은 commerce-api test 소스 `com.loopers.infrastructure.order`에 두어 기존 package-private 자식 helper를 재사용한다.

|타입|책임·주요 메서드|
|---|---|
|CheckoutIncidentScenario|`run(IncidentMode, ScenarioInputs, IncidentEvidenceWriter, IncidentCheckpoint, IncidentDeadline)`. AUTO의 유일한 업무 시나리오·assertion 구현. 단계 사이 DB/PG 초기화 없음|
|CheckoutIncidentTest|자동 wrapper 1건, `@Timeout(420)`. 업무 예산은 `IncidentDeadline.after(360초)`. 사람 대기·모니터링 컨테이너 없음|
|IncidentEvidenceWriter|새 실행·write-once·실패 기록·fault 봉인/불변 확인(§9)|
|IncidentCheckpoint|`create(writer)`, `await(stage, facts)`. assertion 뒤 AUTO 진행 기록과 판정 불허 시 중단(§8.2)|
|IncidentDiagnosis|사전 예상과 수집 증거를 대조해 가설별 판정·진행 가능 여부 반환(§8.1.1)|

`CheckoutIncidentTest`는 일반 `test`에서 제외하고 별도 `checkoutIncidentTest` 작업으로 실행하며 `check`에 연결한다. 기존 O11 별도 작업(`w6OutboxRecoveryTest`)과 일반 test의 `WeeklyRankingFlowTest`·기존 회귀는 유지한다.

전용 작업은 다음을 명시한다.

- 기존 streamer bootJar·batch child 준비 작업 의존, 파일 입력과 JVM 경로 전달. 일반 test 설정이 자동 상속된다고 가정하지 않고 API 제품 runtime에 다른 앱을 의존시키지 않는다.
- JUnit Platform·test 프로필·Asia/Seoul·`-Xshare:off`·heap 1g·`maxParallelForks=1`·`forkEvery=1`·`failOnNoMatchingTests=true`.
- 실제 외부 자원을 검증하므로 up-to-date/build cache를 끄고 결과/XML/JaCoCo 경로를 작업별로 분리한다. AUTO exec는 `build/jacoco/checkoutIncidentTest.exec`다.
- 기존 `JacocoReport`에 O11·AUTO 이후 실행 순서를 지정한다. 공통 보고서의 잔여 exec 가능성은 별도 한계이므로 테스트 수·실패/skip·코드 기준·실행 시각을 함께 확인한다. 과거 Lab exec를 공통 수집 경로로 복사하거나 출처 불명 산출물을 삭제하지 않는다.

아래 시나리오·증거 의사코드는 처리 계약이며 실제 파일별 메서드 분할을 강제하지 않는다. `relayAndAwait(orderIds)`는 실제 eventId별 전달·receipt·consumer commit·활동 대조를 마쳐야 반환한다(§8.4).

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
```

LocalKafkaTestSupport는 공유broker를 종료하지 않으며 system property→환경변수→기존localhost:19092 순서를 유지한다. 다른 DB/Redis/Kafka를 조용히 대역으로 치환하거나 연결 실패를 skip하지 않는다. 실행 환경의 포트·자원 주소는 입력값이며 새로운 제품 정책이 아니다. 없는 환경 입력/도구는 명확히 실패하고 해결 없이 실행 성공으로 진행하지 않는다.

## 7. 실제 health·Prometheus 수집과 보조 화면

### 7.1 포트와 노출

API port는 loopback의 빈 port를 사전 선택하고 callback URL과 함께 기동 전에 주입한다. 선택과 bind 사이의 경쟁은 남으므로 bind 실패·실제 API port/callback 불일치는 준비 실패다. 기동 뒤 callback URL을 바꾸거나 다른 서비스에 요청하지 않는다.

management는 기본 `management.server.port=0`으로 기동하고 Boot가 알려 준 실제 port를 읽는다. API·management 주소는 모두 `127.0.0.1`이다. `management.endpoint.health.show-details=never`를 명시하고 health·prometheus만 기존 설정대로 노출한다. 설정값 0이나 가정한 8081을 실제 port로 쓰지 않으며 env·heapdump·외부 공개 fallback을 추가하지 않는다.

### 7.2 직접 수집과 로그

각 단계의 업무 TX·전달·활동이 안정된 뒤 실제 management 주소의 `/actuator/health`와 `/actuator/prometheus`를 직접 GET한다. 각 응답을 받으면 assertion 전에 `<endpoint>-response.txt`에 본문을, `<endpoint>-response.json`에 HTTP 상태·수신 시각·본문 존재 여부를 write-once로 저장한다. 본문이 없으면 빈 파일과 `bodyPresent=false`로 구분한다. health 검사에서 실패하면 다음 조회는 하지 않으며, 뒤 조회가 통신 실패해도 앞서 받은 원문은 유지한다. 저장 실패도 전파해 진행을 중단한다. 성공 시 기존 결합 원문 `direct-observation.txt`도 유지하며 다음을 assertion으로 확인한다.

- health HTTP 200·UP.
- Prometheus HTTP 200, UNKNOWN gauge 표본 하나와 finite 기대값 BASELINE 0·FAULT 3·RECOVERY 0. 없는 표본·NaN을 0으로 채우지 않는다.
- 지표는 DB 전체의 UNKNOWN을 세므로 전용 DB·배경 UNKNOWN 부재를 전제로 한다(§2~3).

관측 client의 connect/read 제한은 각각 3초다. 직접 GET은 collector의 scrape 성공·신선도나 Grafana 화면 수집을 입증하지 않는다. 로그는 전용 capture와 실행 소유 streamer/batch 파일에서 읽으며, 개별 식별자·호출·TX를 연결한다(§4·§9).

### 7.3 공유 Prometheus·Grafana

선택 속성 `-Pw10ObservePort=<port>`로 AUTO management port를 고정할 수 있다. 실제 bind port가 요청값과 같은지 검사하며 충돌은 준비 실패다. 공유 수집 대상은 `docker/monitoring-compose.yml`의 `host.docker.internal:8081`이므로 그 화면을 볼 때는 8081을 선택한다.

공유 설정·datasource·컨테이너를 수정·재기동하지 않는다. container에서 management로 도달 가능한지는 직접 GET과 별개이며, 공유 수집 누락은 AUTO 통과 근거로 사용하지 않는다. Grafana는 보조 화면이고 캡처는 원문·동일 ID·업무 효과 assertion을 대체하지 않는다. 로그 검색 backend나 분산 추적이 구성됐다고 가정하지 않는다.

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
  assertion 뒤 BASELINE 진행 기록

FAULT:
  F/U명단 고정기록 완료 확인
  F-U7개는B와같은PENDING→SUCCESS→본인reconcile정상경로
  U3개는각successThenCloseEarly한plan → 실제confirm202/UNKNOWN
    callback/reconcile을아직호출하지않음
  §5 등록3개/세 대상 소비 및 activeRequests0·activeCallbackJobs0·미해제gate0 확인
  각U CLOSED_EARLY/거래SUCCESS/create1 확인
  확정17개만실제relay/활동안정화; U Outbox0/활동0 확인
  F7/UNKNOWN3/PG10·전체값표·지표3·healthUP·TX로그대조
  진단용 PG 읽기·TX 로그/가설 대조 완료
  필수 fault 원문·diagnosis를 write-once 저장/봉인 → FAULT 진행 기록
```

PG 지연/응답 유실/내부 반영 실패별예상·실제·판정(지지/배제/미확정)·근거파일을 `diagnosis.json`에 쓴다. U의진단은기존계정으로proxy baseUrl을통한PG목록/거래GET을사용하며commerce결과적용을호출하지않는다. HTTP진단 조회후commerceUNKNOWN/예약/Outbox가그대로인지새조회로확인한다. fixture.state조회와HTTP권위조회는출처를구분하며이진단 조회도lookup횟수에포함한다. 가설표를자동으로“주입코드가그러므로응답 유실”로채우지않는다.

AUTO는 수집된 경계 결과·거래·DB·실제 TX 종료의 일치를 assert하고 가설 대조표를 생성한 뒤 승인된 고정 U의 기존 reconcile만 진행한다. 가설을 구분할 근거가 부족하면 미확정으로 남기고 복구 전에 중단한다. 이는 통제된 테스트 절차이며 운영 자동 복구 정책이 아니다.

### 8.1.1 사전 예상과 증거 기반 자동 판정

예상 파일은 아래 세 가설의 범위와 필수 증거를 고정한다. `diagnosis.json`은 예상 파일 hash, U별 실제 증거 경로/식별자/callId/observationId, 자동 판정과 reason을 저장한다. fixture plan 이름을 판정 입력으로 쓰지 않는다. journal은 실제 전송 경계 관찰로 다른 증거와 대조하며 단독 정답으로 쓰지 않는다.

|가설/범위|사전 예상·판정 규칙|
|---|---|
|진단 관측 시점에도 PG가 처리 중|동일 계정/주문/금액의 유일한 권위 HTTP 조회가 PENDING이면 지지, SUCCESS이면 배제, 실패/0건/복수/불일치는 미확정. 과거에 잠시 PENDING이었을 가능성까지 배제하는 뜻은 아님|
|PG SUCCESS 생성 결과를 전달 중 잃음|권위 SUCCESS + 동일 CREATE의 RESULT Unavailable 및 실제 원인에 따른 reason(TRANSPORT_UNAVAILABLE 또는 CREATE_RESULT_UNAVAILABLE) + 대응 journal CLOSED_EARLY/같은 거래 + APPLY_INPUT Unavailable·DECISION UNAVAILABLE·실제 COMMITTED + 새 DB UNKNOWN/예약 유지/Outbox0이 모두 일치하면 지지. timeout으로 좁혀 부르지 않음. 필수 증거 누락/불일치나 다른 해석이 남으면 미확정|
|성공 관찰의 내부 적용 TX 실패로 미확정 유지|해당 생성/조회 호출에서 SUCCESS 관찰·그 관찰의 APPLY_INPUT·실제 ROLLED_BACK과 새 DB 미반영이 연결되면 지지. U의 포착한 CREATE에는 성공 관찰 대신 Unavailable 입력과 그 TX의 COMMITTED가 완결되게 연결되고, fault 전 U의 callback/reconcile 호출0·다른 적용/진단오류 없음까지 확인한 경우 그 호출의 원인으로는 배제. 로그 부재만으로 배제하지 않음|

공통: ID/금액/거래·호출 대응, START→RESULT/ERROR와 실제 TX 종료의 완결성부터 검사한다. **2026-11-23 사용자 결정:** callId와 observationId는 서로 다른 UUID이므로, U별로 providerOrderId가 같은 CREATE START/RESULT 쌍과 orderId가 같은 `APPLY_OBSERVATION`(source=CREATE)을 찾고, fault 구간에서 각각 정확히 1개이며 로그 순서가 RESULT→APPLY일 때만 같은 생성 호출로 연결한다. 0개·복수·순서 역전은 미확정이다. 누락/UNKNOWN/진단 실패/예상 밖 추가 적용은 자동 판정 미확정 및 진단 실패이며 복구 전 중단한다. 각 판정은 이 실행의 포착 범위에만 적용한다. §4.4의 모든 U 조합이 성립하고 유실 가설 지지·나머지 두 가설의 해당 범위 배제가 확인돼야 AUTO가 기존 reconcile로 진행한다. 세 가지 모든 장애를 새로 주입하는 범위는 추가하지 않는다.

### 8.2 AUTO 진행 기록

각 단계의 업무·증거 assertion 뒤 `IncidentCheckpoint.await(stage, facts)`를 호출한다. `facts`에는 단계 원문 상대경로·summary를 두고 FAULT에는 봉인 diagnosis 경로·SHA-256·현재 U 목록·`autoProceed`를 추가한다.

```text
await(stage, facts):
  FAULT 자동 판정이 진행 불허이면:
    AUTO_JUDGMENT_FAILED 실패를 기록하고 Aborted 전파
    정상 반환·skip·자동 복구로 변환하지 않음
  control/accepted/<stage>.json에 CREATE_NEW로 기록
    schemaVersion / runId / stage / action=AUTO_ADVANCE / factsSha256 / acceptedAt
  같은 단계 재기록·쓰기 실패는 실패로 전파
```

사람 명령·ready·nonce·판단 파일은 생성하지 않는다. 시간 경과를 성공으로 바꾸지 않으며 업무 전체 기한은 wrapper와 deadline으로 제한한다(§6.1·§9.2).

### 8.3 복구와 반복 적용

```text
RECOVERY:
  fault 봉인·증거 기반 자동 진단과 진행 조건 충족 확인
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

복구 원문·point/coupon·실제 health/metric 0·랭킹 응답을 저장하고 fault 원문 hash 불변을 확인한 뒤 RECOVERY 진행 기록을 남기고 종료·정리한다.

## 9. 원문·실패·정리·재진입

### 9.1 write-once evidence

`IncidentEvidenceWriter.openRun(root, codeReference, mode, operator, syntheticClock)`는root밑에충돌하지않는새runDir를생성하고metadata를CREATE_NEW로쓴다. 기본 root는API build/checkout-incident-runs이다. runId,HEAD/작업diff기준과hash,실제시작UTC시각,합성Clock,mode,operator(실행입력),소유 DB/PG/프로세스/topic/group·실제 port를허용필드로기록한다. 비밀번호/헤더/카드/전체환경·실행명령전체는저장하지않는다. 코드 기준은 HEAD와 tracked diff hash뿐 아니라 실행에 영향을 주는 미추적 소스/빌드설정 파일의 repo-relative 경로·SHA-256 manifest도 포함한다. 새 구현 파일이 아직 untracked일 수 있으므로 git diff만으로 같은 코드라고 판정하지 않는다. 로컬 주차 운영 문서·인증/실행 비밀은 이 코드 manifest 대상에서 제외한다.

**2026-11-23 구현 세부 사용자 결정:** `metadata.json`은 openRun 시점에 아는 값만 CREATE_NEW로 한 번 쓴다. baseline 전 고정하는 예상 파일의 hash는 `metadata-expectations.json`, 준비 중에 알게 되는 소유 자원(실제 port·topic/group·자식)은 `resources/<순번>-<종류>.json`으로 각각 CREATE_NEW 추가하며 어느 파일도 덮어쓰지 않는다. tracked diff는 `git diff HEAD --binary`(staged+unstaged) 전체의 hash이고, untracked manifest는 `git ls-files --others --exclude-standard` 중 `apps|modules|supports/*/src/` 아래와 `*.gradle.kts`·`gradle.properties`·`gradle/` 아래 파일이며 `docs/`는 제외한다. git 실행 실패는 준비 실패다. 단계 원문은 `baseline|fault|recovery/` 아래 이름별 파일로 두고, 구조 데이터는 JSON(비유한 값은 원문 문자열과 `finite=false`), HTTP 본문 원문은 바이트 그대로 저장한다.

`writeStage(stage, StageSnapshot)`는다음 필수 항목을write-once로남긴다.

- 실제주문/cohort/attempt/providerOrder/검증한합성계정참조/금액/거래/상태/확인시각 대응표.
- 주문·결제·예약/재고·Outbox·활동·point/coupon·랭킹의읽기쿼리/조회범위·시작/종료시각·허용row값.
- PG fixture.state/journal/count와실제권위HTTP진단응답(합성허용필드),baseline/fault/recovery실제health·Prometheus응답의상태/본문/시각.
- 직접 수집 원문의 출처·관찰 시각과 진단 가설/예상/관찰/판정/조치. 보조 화면은 직접 수집 증거와 구분한다.
- parent의허용경계로그·streamer/batch자식로그위치/내용과결과,assertion실패·중단·정리결과.

로그capture는전용logger에test ListAppender/파일appender를부착하고실행끝에정확히자기appender만분리한다. 기존공유root설정·다른 실행로그를지우지않는다. 자식로그는실행소유경로로저장하고원본을임의편집하지않는다. SQLbind/rawrequest/카드가새원문에섞이지않도록출력구성을확인한다. 보조 화면에도 로그인·계정 비밀을 담지 않는다.

`sealFault()`는 필수파일/내용 assertion 성공 후 fault의 SHA-256목록과 코드 진단원문·예상 파일 hash를 봉인한다. accepted 진행 기록은 봉인 fault 파일과 별도로 저장한다. `verifyFaultUnchanged()`는복구 뒤기존파일바이트hash를비교한다. 파일존재/hash일치만으로상태/효과를통과시키지않는다. 실패 원문은별도순번/시각파일에append방식으로추가하며기존파일에덮어쓰지않는다. 증거파일쓰기실패면특히복구 전진행을중단한다. 오류기록도실패하면원래오류를가리지않고suppressed/별도실패로알린다.

build는ignore대상이므로성공/실패 원문보존과Git제출을구분한다. 승인후선별한실행의허용원문/metadata/가설/검증범위·재현명령만 `docs/week10/verification/evidence/<runId>/`에바이트그대로복사하고원본hash와대조한다. 2026-11-23 실행 `20261123T124038Z-670e178c`의 사본·manifest를 해당 경로에 보존했다. Git 포함 여부·최종 제출 판단은 별도이며 현재 untracked다. 로컬scenario-review·HANDOFF·프롬프트를필수근거로링크하지않는다. stage/ignore변경은자동수행하지않는다.

### 9.2 제한 시간과 정리

|구간|한도/정책|
|---|---|
|AUTO wrapper / 업무 예산|420초 / 360초. 기존 W9의 420초 한도는 유지|
|직접 health/Prometheus|connect/read 각각 3초. 호출 전체의 수학적 3초 상한이 아님|
|DB 활동 안정화|20초, 실제 조건 poll|
|batch child1회|최대120초와남은전역시간중작은값|
|기존relay PG/DB/child 종료|기존 계약을유지하되전체남은시간과정리예산구분. log에실제소요/종료여부기록|

cleanup은 전역 실행 한도와 별도로 주간 랭킹 검증 설계 §11.6의 유한 종료 대기를 수행한다. 자식이 살아 있는데 종료 성공이라고 쓰지 않는다. timeout이모든DB/프로세스를동시에취소한다는보장은아니다.

데이터 정리는 활성 API의 정리 bean을 사용하므로 API 종료 전에 수행한다. 기존 `OrderOutboxE2eLifecycleSupport.release()`의 순서를 재사용하며, relay idle·자식 종료를 확인하지 못하면 해당 데이터는 보존하고 종료 실패를 보고한다. API/PG 종료 등 독립적인 자원 정리는 계속한다.

```text
try:
  setup → baseline → fault/diagnose/seal → recovery → downstream → finalchecks
catch original:
  가능한범위의failure snapshot을추가저장,원래오류유지
finally:
  이번run이향후등록할주입/콜백호출중지,등록gate가있으면자기것해제
  relay진입중단/idle대기
  소유 topic 설정을 변경했다면 복원
  batch/streamer 자식 종료 확인
  relay idle·자식 종료가 확인된 경우에만 활성 API로 소유 API/Redis 데이터 정리
  자기 appender 분리 → API context 종료 → PG fixture 종료
  relay idle·자식 종료가 확인된 경우에만 이번 실행 소유 구매 테이블 정리
  topic 관리 client 종료 → 자식 종료가 확인된 경우에만 소유 Kafka topic/group 정리
  공유broker/로컬Redis/다른DB/다른compose/모든컨테이너일괄종료금지
  정리 실패는별도기록하고통과로변환하지않음
```

baseline→fault→recovery사이에cleanup/truncate/PGreset을호출하지않는다. 기존 W7 cleanup helper와 실패 원문 기록을 사용하고 자식 생존 시 데이터 보존 규칙을 유지한다. 랭킹/Batch테이블은기존W9§11.6처럼테스트컨테이너수명에맡긴다. 물리테스트DB/PG는프로세스종료시사라질수있고이설계는완료/취소후그DB를재접속해복구를이어가는기능을보장하지않는다.

현재 AUTO에는 종료·실패한 실행의 재개 기능이 없다. 남은 원문을 보존하고 새 runDir·새 테스트 주문으로 처음부터 재현하며 이전 실행의 미확정 상태를 성공으로 덮지 않는다. 실행 중 조회가 불확실하면 상태를 보존하고 중단한다는 의미와 종료 뒤 테스트 DB를 영구 보존한다는 의미를 혼동하지 않는다. 상태가 남은 환경에서 같은 주문을 다시 진단하는 판단 순서와 운영 미검증 범위는 runbook §4에서 구분한다.

## 10. 검증 대응·완료 근거

|ID|검증 항목|근거|
|---|---|---|
|I1|fixtureSUCCESS저장선행/유실/CREATE외거절/기존plan보존|fixture실제HTTP+PG단독조회+journal과기존 회귀|
|I2|UNKNOWN집계/상태제외/commit뒤변화/수집시조회/NaN/참조수명|실제DB·registry·Actuator,예외경계대역은별도표시|
|I3|PG분류·TXcommit/rollback/unknown/충돌별도TX/비밀미출력|기존probe/실제DB/로그capture+단위대조|
|I4|B10/F10/U고정/정상PENDING후조회/FAULT7·3·10|단계별동일ID/DB/PG원문과assertion|
|I5|사전 예상/규칙·증거 기반 자동 판정·진단 읽기 전용·복구 전 보존|판정 helper의 누락/모순/미확정 단위 대조, 실제 HTTP·경계/TX 로그·전후 상태·봉인 hash|
|I6|본인복구10·0·10/create증가0/소유권/반복callback|실제controller/adapter/DB/PG/예약/Outbox|
|I7|point/coupon불변과비어있지않은대조군|잔액/기록·쿠폰목록/필드/campaign전후값|
|I8|실제relay/Kafka/consumer중복/활동40·A40/B20/배치/API|receipt/offset/commit·DB·snapshot/JobExplorer/API|
|I9|제외：OBSERVED 직접 관찰·실행별 collector·명시 진행(§1)|AUTO 통과를 I9 완료로 기록하지 않음|
|I10|AUTO 진행 기록·판정 불허 시 중단·같은 단계 재기록 거절|IncidentCheckpointTest 3건의 검증 대상. 제외된 명령 파일 검증을 요구하지 않음|
|I11|중간실패/증거쓰기실패/fault불변/정리소유/자식생존|수집helper·기존정리회귀·실제종료기록,강제미실행분기는명시|
|I12|누적기능·lint·ArchUnit·W9restart/revision|현재코드의새검사결과,0건/skip/중단/접속실패별도|

I9는 제외됐으며 AUTO 통과를 I9 완료로 쓰지 않는다. I10/I11의제어된단위실패를실제외부장애/종료불명재현으로확대하지않는다. 신규UI스크린샷이나marker만으로통과시키지않는다. 기존유효회귀·기대값은삭제/완화하지않는다. 과거실행결과는이 변경의실행결과가아니다.

누적 회귀는상품/브랜드/좋아요/관리자·캐시·과거주문보존,Point/PG단일시도/예약/조회조정,Outbox발행gap/소비중복,쿠폰총량/중복/수용/본인목록,랭킹실제입력/동일instance재시작/revision/공개API를기능별로보고한다. API의기존check가O11을포함하는것을보존하고streamer/batch/PG변경모듈의check·관련회귀도포함한다. 기존W8opt-in8건은명시실행여부를구분하며skip을통과나이번새필수장애실험으로기록하지않는다.

AUTO 전용 작업은 `:apps:commerce-api:checkoutIncidentTest`이며 `check`에 연결되어 있다. 실제 실행 명령·코드 기준·시각·실패/skip·한계는 실행 기록과 runbook에서 구분한다. 삭제된 Lab 작업을 재현 명령으로 제시하지 않는다.

## 11. Runbook·제출·선택의 한계

`docs/runbook/week10.md`에는 증상·명단 고정·복구 전 보존·읽기 전용 진단·경쟁 가설·본인 reconcile의 전제/중단 조건·복구/후속 결과 확인·재진입·소유 자원 정리를 기록한다. AUTO 재현은 실행 기록을 따르고, 상태가 남은 주문의 수동 운영 재진단은 미검증 판단 순서로 구분한다. 실제 SQL 수동 실행·운영 자동 복구를 검증한 것으로 쓰지 않는다.

원문 제출 항목은 관측·작은 fixture·연결 테스트 코드와 PR, 사고 증거, runbook, 기능별 검증 범위/남은 위험, 기술 글이다. 설계 문서는 원문이 별도 요구한 제출물이 아니라 협업의 구현 기준이며 Git 포함 후보로 관리한다. 기술 글/WIL의 HTML은 사용자 형식 선택이다. 최종 파일·증거의 Git 포함과 제출 판단은 사용자에게 있으며 자동 stage/커밋하지 않는다. 주차 운영 문서·로컬 시나리오 리뷰는 필수 제출 근거가 아니다.

선택/대안/재검토조건:

- 전용SUCCESS+유실plan은기존gate의시간경쟁을줄이고소규모확장으로순서를명확히한다. 일반장애DSL/원자FIFO재설계는추가하지않는다.
- UNKNOWN한정gauge는사고3→0을명확히하지만PENDING0/업무전체완료는별도검증해야한다. 미종결전체가운영질문이면label분리와관찰범위를재검토한다.
- 직접COUNT는freshness를단순하게하지만수집부하/DB지연에민감하다. 현재선택을운영규모보장으로쓰지않는다.
- afterCompletion은실제 TX결과와시도로그를분리하지만시작실패/프로세스종료/로그장애를모두설명하지못한다. 누락·UNKNOWN이면미확정,추가근거없이원인을확정하지않는다.
- 두상품/다른수량은품목별후속효과를보지만동점·숨김·주경계·부하·재시작을이번사고에모두섞지않는다. 해당기존 회귀와이번미검증범위를구분한다.
- host.docker.internal/이미지/포트의가용성은실행 전확인이다. 도달실패를외부노출/공유설정변경으로자동해결하지않는다. 해당환경에서실행불가하면한계를보고하고연결대안을먼저검토한다.

공식 계약 확인 출처: Spring Framework 6.2 TransactionSynchronization API의 afterCompletion/STATUS_*; Micrometer 1.14 Reference의 Gauges(관측 시 평가·대상 참조 수명). 출처 확인은 실행 검증이 아니며 참고 patch 버전과 실제 해석 의존 버전의 일치를 가정하지 않는다.

저장소 설정은 Spring Boot 3.4.4·Java 21이다.
