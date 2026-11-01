# 결제 검증 기준

기존 절 번호 참조: [2.8](pg-client.md#28-pg-http-도구와-테스트-서버의-구성) · [3.5](model.md#35-pg-결제의-상태와-불변조건) · [3.6](model.md#36-pg-저장-모델과-제약) · [4.3](processing.md#43-주문-확정) · [5.7](../pg-simulator/fixture-contract.md#57-테스트-pg-제어와-상태-보존).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

## 4.3 결제 구현 검증 기준

### 구현 검증 기준

- 생성 회로 사전 차단은 503·DRAFT·결제/발송 기록/재고/이력 변경 0·POST 0이며 회복 후 같은 주문/키의 고객 confirm을 허용한다. 기존 결제는 회로 OPEN이어도 저장 결과를 우선 반환한다. 사전 허용 뒤 실제 권한 거절은 POST 0·CIRCUIT_REJECTED 실패·반환 1회이고 중복/경쟁도 추가 반환 0이다. 생성 POST의 확실한 최초 풀 획득 timeout은 PG 수신 0·POOL_ACQUIRE_TIMEOUT 실패·반환 1회이며 연결/읽기 timeout·후속 풀 오류는 이 실패로 처리하지 않는다. DB 오류·TX A rollback·STORED·사전 확인은 실제 PG 성공/실패/느림 표본을 만들지 않는다.

- 실제 MySQL에서 같은 주문/다른 주문의 키·외부 거래 경쟁을 검증한다. payment unique 세 개·amount > 0 CHECK, 외부 주문 ID 일반 인덱스·매핑 검증, payment_conflict의 UNIQUE(payment_id)를 확인한다. 콜백 후보 0/1/복수에서 임의 선택·추가 외부 호출이 없는지 확인한다.
- 같은 주문 동시 confirm은 결제 행·선차감·ORDER_RESERVATION을 한 번만 만들고 PG create 1회다. 주문 품목은 결제 시작 뒤에도 불변이며 별도 예약 테이블에 기대지 않는다.
- 부모 잠금/최신 읽기: 서로 다른 주문·키·상품의 첫 TX A와 서로 다른 결제의 첫 충돌 요약 생성을 각각 장벽으로 겹쳐 모두 commit되는지 확인한다. 업무 TX의 첫 업무 데이터 접근 SQL이 Order PK 잠금이고 Payment/PaymentConflict의 선행 FOR UPDATE가 없는지 확인한다. 잠금 대기 중 앞선 TX가 종결·충돌 요약을 commit한 뒤, 뒤 TX가 최신 상태/기존 요약을 읽어 종결 후퇴·재고 중복 반환·요약 중복 INSERT가 없는지 반드시 검증한다. 콜백 후보 식별 뒤 다른 TX가 종결한 경우 새 prepareVerification의 terminalAtStart=true·PG PENDING 후 TERMINAL_REGRESSION/409를 확인한다. 상세 통제와 기대값은 4.5를 따른다.
- 실제 PG 응답을 지연하는 동안 TX A의 DB 연결·재고 잠금을 보유하지 않는지 확인하고, 다른 관리자 재고 변경이 같은 Product 잠금 규칙으로 진행되는지 검증한다.
- TX A·성공 TX B·실패 반환 TX B에서 실제 SQL 뒤 예외를 넣고 새 TX로 전체 rollback을 확인한다. 같은 FAILED의 반복·경쟁은 고정 품목 수량을 한 번만 반환하고 ORDER_RELEASE도 한 번이다.
- callback-first·lookup-first·생성 응답 유실·반영 순서 역전·반대 종결·종결 후 PENDING·효과 0건과 거래 존재 충돌, 저장 키와 다른 유효 거래의 IDENTITY_MISMATCH 선행 판정을 고정한다. 검증한 거래 키의 한 번 결합·변경 금지, 순번 컬럼/발급 없음, payment 확인 정보와 payment_conflict 요약의 마지막 commit 기준·독립 갱신, 정상 확인 후 충돌 보존을 확인한다. 각 기대값을 입증하는 계층(실제 HTTP·업무 TX 경계·부모 잠금)은 4.5의 [업무 관찰 순서의 TX 경계 검증](#업무-관찰-순서의-tx-경계-검증)을 따른다.
- 충돌 요약 commit 뒤 409, 요약 SQL 뒤 실패의 전체 rollback·500, 거래 unique rollback 뒤 새 요약 TX를 확인한다. 같은 결제 동시 첫 충돌도 한 요약만 만들고 마지막 반영 충돌이 요약을 교체한다. rollback 시에는 직전 commit 요약·확인 정보를 유지한다.
- 관리자 가용량 변경·삭제 후 실패 반환·가산 overflow·품목/상품 누락·캐시 commit 뒤 DEL·과거 POINT 금액/품목 보존을 검증한다. Point 없는 고객의 PG 결제를 거절하지 않고 포인트 변경도 0이다. 과거 POINT 결과를 PG 통과 근거로 사용하지 않는다.

## 4.5 PG 연동의 검증과 관찰

아래는 **구현 후 실행할 필수 검증 기준**이다. 설계 문서 작성으로 통과한 검사는 없다. 기존 POINT 구현·별도 회로 학습 테스트의 과거 결과를 PG 연동의 근거로 사용하지 않는다. HTTP 계약, 도메인 상태, 실제 DB 경쟁의 목적을 나누어 같은 조합을 모든 계층에서 중복하지 않는다.

### 검증 경계와 책임

|경계·목표 테스트|필수 입력/통제|관찰·기대 결과|
|---|---|---|
|PG fixture 자체 계약(2026-10-25 목표: pg-simulator 모듈 테스트와 테스트 래퍼 테스트로 나눔, 아래 [PG 단독 실행 앱 전환의 검증](../../verification/pg-simulator.md#pg-단독-실행-앱-전환의-검증--2026-10-25-목표))|임의 포트·고유 주문·메모리 store|202 PENDING/200 SUCCESS/422 무효과, 동일 orderId 2회 생성 시 다른 거래 2건, 조회 0/1/복수, state 변경과 callback 전송 분리, reset/종료|
|PgHttpGatewayContractTest|실제 RestClient+Apache → 테스트 내부 PG HTTP 서버|method/path/header/숫자 금액/문자열 ID·URI 인코딩, 정상/손상 JSON·식별/금액 불일치·거래 없음, create 1회/조회 최대 1회, redirect 대상 수신 0|
|PgCircuitIntegrationTest|Boot YAML registry+실제 adapter, 케이스별 새 회로|2.8의 성공/실패/제외·recordResult CONFLICT 보존·회로 밖 fallback, POST는 pgCreate·GET은 pgLookup·로컬 TX 제외, 공통 초기값/각 Customizer 실제 적용|
|Payment/PaymentConflict 도메인 테스트|저장 상태·관찰·토큰을 순수 값으로 입력|불변조건·종결 중복·반영 순서·terminalAtStart·충돌 유형, 프레임워크 없이 전이 검증|
|PaymentTransactionIntegrationTest|실제 MySQL·짧은 TX·failure seam|TX A/B/충돌 기록의 commit/rollback, unique/CHECK·고정 품목/상태·충돌 요약 분리·POINT 보정, 실제 SQL 후 실패 보상. 늦은 CREATE·QUERY 관찰과 확인/충돌 반영 순서는 아래 [업무 관찰 순서의 TX 경계 검증](#업무-관찰-순서의-tx-경계-검증). 열 타입·기존 데이터 전환은 아래 [스키마 타입과 기존 데이터 전환](#스키마-타입과-기존-데이터-전환-검증)|
|PaymentFlowIntegrationTest|실제 Facade/TX/DB+테스트 내부 PG HTTP|callback-first/lookup-first의 순차 HTTP 흐름·생성 응답 유실 뒤 정상 콜백/대사 종결·DB 실패 뒤 같은 거래로 회복·중복 효과 0. 늦은 응답의 적용 순서는 HTTP 대기에 묶지 않고 TX 경계 절에서 입증|
|PaymentConcurrencyIntegrationTest|독립 TX/connection·gate·시작 장벽|동시 confirm·최종 결과 적용·다른 주문의 같은 거래 결합, create 1회·고정 품목/이력/가용량 정합성·별도 충돌 요약 commit|
|Order/Payment API 테스트|실제 controller·validation·인증 경계|소유권·callback 비고객 인증·400/404/409/500/503·200/202·payment 필드 생략/0·관리자 URL·과거 POINT 보존|
|위조 콜백 controller 검증|테스트 코드가 유효 형식의 조작 JSON을 commerce의 실제 callback controller에 실제 HTTP로 직접 POST. commerce는 실제 adapter로 테스트 PG를 권위 조회. fixture sendCallbacks는 사용·변경하지 않음|본문 status·거래 키만으로 종결·결합하지 않음. 아래 [위조 콜백 본문](#위조-콜백-본문의-실제-controller-검증) A~C의 ACK·권위 GET 1회·commit 뒤 API/DB 기대값|
|PaymentRestartIntegrationTest|같은 DB/PG, commerce context 두 번. 각 컨텍스트는 기동 전 자기 포트·callback URL 동시 주입|발송 기록·미확인 상태·예약 유지, client/회로 재생성 뒤 고객 조회·reconcile로 동일 거래 확인, create 증가 0. 첫 컨텍스트 주소의 callback URL 수정·이전 주소 수신 없음|
|테스트 내부 전체 HTTP 흐름|5.7의 PG fixture(임의 포트→base-url)와 기동 전 선택·주입한 commerce 포트·callback URL·실제 controller|기동 후 실제 수신 포트와 callback URL 일치 확인 뒤 실제 고객 주문/confirm/조회/reconcile·콜백 결과와 PG state/count 대조. 별도 수동 서버/단독 앱 기동은 검증 조건이 아님|

목표 클래스명은 목적별 배치 안내이며 계약이 달라지지 않는 병합·분리는 가능하다. 콜백 HTTP를 수신하거나 callback controller에 직접 POST하는 테스트는 5.7의 기동 전 포트 선택·`server.port`/callback URL 동시 주입·기동 후 일치 확인을 준비 조건으로 둔다. 선택 포트의 bind 실패나 포트/URL 불일치는 테스트 준비 실패이며 검증 통과로 세지 않고, 포트 예약·자동 재시도를 추가하지 않는다. PG fixture 제어 메서드는 테스트 준비/관측이고 최종 결과는 실제 commerce API/DB 새 조회로 확인한다. 대사를 DB를 직접 수정하는 것으로 대체하지 않는다.

### 시나리오별 재현과 기대값

공통 기준은 fixture 선행 commit, 테스트 부모 TX 없음, 요청별 독립 TX/connection, case별 새 회로/고유 주문이다. DB 검증은 worker 종료·commit 뒤 새 TX로 읽는다. sendCallbacks를 쓰는 사례는 CallbackBatchResult의 각 전송에서 기대한 ACK를 받은 뒤에만 새 API/DB 조회로 판정하고, 송신 timeout·통신 오류가 난 실행은 정상 콜백 통과로 세지 않는다([5.7 전송 결과 계약](../pg-simulator/fixture-contract.md#57-테스트-pg-제어와-상태-보존)). 아래 외부/내부 수량을 함께 검증한다.

|시나리오|재현 순서|핵심 기대값|
|---|---|---|
|정상 PENDING → SUCCESS 콜백|재고 5·수량 2, 기본 202 PENDING confirm 완료 → setStatus SUCCESS → sendCallbacks 1회 완료 → 새 API/DB 조회|confirm 직후 미확정·가용 3. 콜백의 권위 GET 1회 뒤 CONFIRMED·SUCCESS·저장 금액/거래 일치, createReceived=1·callbackSent=1·추가 차감/반환/포인트 효과 0|
|정상 PENDING → FAILED 콜백|기본 PENDING confirm 완료 → setStatus FAILED → sendCallbacks 1회 완료|PAYMENT_FAILED·paidAmount 생략·가용 5·ORDER_RELEASE 1회. 같은 콜백 중복 알림(count)도 추가 반환 0. 동시 경쟁은 아래 부모 잠금 절에서 입증|
|즉시 성공|재고 5·수량 2, CREATE SUCCESS plan으로 confirm. 콜백 없이 생성 200 SUCCESS 응답만으로 확정|외부 거래/create 수신 1·즉시 확정 시 callbackSent=0, 가용 3·CONFIRMED·paidAmount=저장 총액. 추가 GET/재고 차감/포인트 변화 0, 뒤늦은 같은 콜백의 업무 효과 0|
|사전 차단/실제 생성 회로 거절|생성 회로 OPEN·사전 허용 후 최종 권한 거절을 각각 통제|사전 차단 503·DRAFT·결제/재고/POST 효과 0, 회복 뒤 첫 시도 가능. TX A 후 actual 권한 거절의 본문 미진입·POST 0·FAILED/CIRCUIT_REJECTED·재고 반환/ORDER_RELEASE 1·거래 키 없음·PG 확인 정보 유지. 반환 TX 실패 시 전체 rollback·500, UNKNOWN을 조회 0건으로 임의 종결하지 않음|
|거래 생성 전 거절|정확한 REJECT_BEFORE_CREATE plan, 같은 관찰 중복 적용|거래 0·주문 실패·가용 재고 원상량·반환 이력 1. 일반 422/5xx와 구분|
|생성 후 응답 유실|CREATE DELAY 1500ms 또는 CLOSE_EARLY, 이후 PG SUCCESS·고객 reconcile|create 수신/거래 1·초기 UNKNOWN/UNAVAILABLE·예약 유지. 같은 거래 SUCCESS로 한 번 확정·create 추가 0|
|발송 직전 종료|TX A 완료 뒤 발송 전 테스트 seam으로 중단·commerce 재시작·0건 조회|거래 0·UNKNOWN/예약 유지. 0건을 무효과 확정으로 삼지 않고 재발송 0. 자동 완료되지 않는 한계를 근거에 표시|
|외부 성공 후 저장 실패|테스트 PG가 거래 SUCCESS를 유지한 채, TX B의 실제 UPDATE/flush 뒤 commit 전에 테스트에서 예외 주입|TX B만 전체 rollback·500, PG 거래 SUCCESS와 TX A의 PAYMENT_PENDING/확보 재고는 유지. 같은 기존 거래를 재조회해 한 번 확정·create 추가 0. 실패 반환도 실제 수량 변경 SQL 뒤 commit 전 실패의 전체 rollback을 별도 검증|
|생성 응답 유실 뒤 콜백 종결·중복(실제 HTTP)|CREATE DELAY 1500ms 또는 CLOSE_EARLY로 confirm 완료(UNKNOWN/UNAVAILABLE·202) → setStatus SUCCESS → sendCallbacks 1회 완료 → 새 API/DB 조회. 이어 sendCallbacks count=2와 고객 reconcile. 대조로 reconcile 먼저 종결 뒤 콜백(lookup-first)|콜백 권위 GET 1회로 같은 거래를 한 번 종결(CONFIRMED·SUCCESS·거래 키 결합), create 추가 0·ORDER_RESERVATION 1회·추가 차감/반환 0. 중복 콜백·reconcile·뒤늦은 콜백은 업무 효과 0. 콜백 처리 전체를 생성 HTTP의 1초 안에 끝내야 통과하는 조건은 두지 않음|
|늦은 관찰·확인/충돌 반영 순서(업무 TX 경계)|아래 [업무 관찰 순서의 TX 경계 검증](#업무-관찰-순서의-tx-경계-검증)의 토큰·관찰·장벽 통제|종결 뒤 같은 거래의 늦은 CREATE PENDING·CREATE UNAVAILABLE의 후퇴 없음, 종결 뒤 다른 거래 CREATE의 IDENTITY_MISMATCH, 복합 불일치 코드 순서, 종결 전 준비 QUERY PENDING 무시·종결 후 준비 QUERY PENDING의 TERMINAL_REGRESSION, 확인 정보/충돌 요약의 마지막 commit 기준, 반대 종결 보존·중복 효과 0. 순번 컬럼·발급 없음|
|0건/복수/식별·금액·종결 충돌|정상 빈 목록/중복 PG 거래/RAW 응답 제어|2.8 표본 분류와 3.5/4.3 결과 적용 일치. 0건 UNAVAILABLE, 충돌 요약 commit/409, 업무·예약·기존 종결 보존|
|같은 주문의 다른 유효 거래 키(실제 HTTP)|주문 A를 기본 PENDING confirm으로 준비해 저장 키 KA 확인 → 테스트가 A의 providerOrderId·같은 금액·커머스 callback URL로 PG에 직접 POST해 KB 생성 → setStatus(KB, SUCCESS) → sendCallbacks(KB, 1). 대조로 KA SUCCESS 콜백으로 먼저 종결한 뒤 setStatus(KB, FAILED) → sendCallbacks(KB, 1)|기대 ACK 409 PAYMENT_RESULT_CONFLICT. GET KB 1회·회로 성공 1(요청 KB/응답 KB, 직접 대상 위반 아님)·create 추가 0. IDENTITY_MISMATCH 요약 commit·verificationStatus=CONFLICT, 저장 키 KA 유지·KB 결합 0·재고/이력 효과 0. 대조는 TERMINAL_MISMATCH가 아니며 성공 보존·반환 0. 직접 POST 금액을 다르게 한 변형도 회로 성공 1·IDENTITY_MISMATCH이며 요약에 관찰 금액 보존. 주문별 조회의 KA·KB 두 거래는 위 행의 MULTIPLE_TRANSACTIONS, 알림 키의 PG 404는 아래 위조 콜백 B의 UNAVAILABLE·202·요약 0을 그대로 적용|
|위조 콜백 본문|정상 confirm으로 준비한 주문에 테스트가 유효 형식의 조작 JSON을 실제 callback controller로 직접 POST|아래 A(PG PENDING·본문 SUCCESS)·B(PG에 없는 거래 키)·C(다른 주문의 유효 거래 키)의 사례별 기대값. 본문만으로 확정·실패·거래 키 결합·재고 효과 없음|
|예약 뒤 관리자 변경·삭제|수량 2 예약→관리자 가용 10 설정 또는 상품 soft delete→FAILED 조회|가용 12·반환 1회, 삭제 상태 유지·소비 시 추가 차감 없음. 캐시 DEL은 commit 후, rollback에는 DEL 없음|
|POINT 보존/주변 기능|과거 CONFIRMED+POINT 행·당시 금액/품목, 신규 고객 Point 없음|과거 결제 사실과 권한 유지. 신규 PG에 Point 요구/차감/ORDER_USE 생성 0. 포인트 충전·조회는 기존 검증 유지|

### 위조 콜백 본문의 실제 controller 검증

실습 요구 "조작한 callback 본문만으로 성공하지 않는지 확인"을 기존 [PG 콜백 수신 계약](api-contract.md#pg-콜백-수신)·위 콜백 의사코드·[2.8 조회 표본 분류](pg-client.md#합의한-조회-회로-표본-분류)로 검증하는 필수 기준이다. 실제 A/B/C 사례는 PaymentRecoveryFlowIntegrationTest.forgedCallbackHasExactlyOneAuthoritativeReadAndNoExtraEffects로 구현했으며 실행 경계는 마지막 I5 근거를 따른다. 새 상태·오류·정책을 추가하지 않는다.

fixture의 sendCallbacks는 저장 거래의 정상 snapshot만 보내므로 변경하지 않고 이 검증에 사용하지 않는다. 테스트의 HTTP client가 5.7 절차로 기동 전 선택·주입한 포트에서 실행 중인 commerce의 `POST /api/v1/payments/callback`에 조작한 JSON을 직접 보내고, commerce는 실제 adapter·pgLookup으로 [5.7](../pg-simulator/fixture-contract.md#57-테스트-pg-제어와-상태-보존)의 테스트 PG를 권위 조회한다. 커머스 콜백 수신은 테스트 요청 수로 세며 fixture callbackSent와 혼동하지 않는다.

- **독립·형식:** 각 사례는 새 fixture/회로와 서로 다른 고유 주문·상품을 사용하고 테스트 부모 TX를 두지 않는다. providerOrderId는 저장 매핑(LP-ORD- + 최소 10자리 숫자), transactionKey는 `[A-Za-z0-9_-]{1,255}`, status는 PENDING/SUCCESS/FAILED 중 하나로 보낸다. 입력 검증 400이나 후보 0건 404로 PG 조회 전에 끝난 실행, 해당 거래의 권위 GET 수신이 1이 아닌 실행은 이 검증의 통과 근거가 아니다.
- **공통 관측:** 콜백 POST 전후 fixture state를 비교해 해당 lookupByKeyReceived +1, lookupByOrderReceived·createReceived·거래 수·거래 상태·callbackSent 변화 0을 확인한다. pgLookup 표본은 2.8대로 각 사례 성공 1·실패 0이며 pgCreate 표본은 변하지 않는다. ACK만으로 판정하지 않고 응답 뒤 새 API/DB 조회로 주문·결제 상태, 저장 거래 키, paidAmount, 가용 재고, ORDER_RESERVATION 1회·ORDER_RELEASE 0회, 포인트 잔액·ORDER_USE 0, verificationStatus·verification_checked_at, payment_conflict를 확인한다. 200/202 ACK는 data를 생략한 ApiResponse이고 409는 기존 오류 ApiResponse다.

|사례|준비와 직접 POST 본문|권위 조회|기대값|
|---|---|---|---|
|A. PG PENDING·본문 SUCCESS|재고 5·수량 2 주문 A를 기본 202 PENDING confirm으로 준비해 결제 PENDING·저장 키 KA·가용 3을 확인한다. setStatus는 호출하지 않아 PG의 KA는 PENDING이다. 본문 `{A의 providerOrderId, KA, SUCCESS}`|GET KA 1회가 실제 PENDING 반환|HTTP 202·data 생략. 주문 PAYMENT_PENDING·결제 PENDING·verificationStatus=VERIFIED·checked_at 갱신·저장 키 KA 유지·paidAmount 생략. 가용 3·추가 차감/반환·포인트 효과 0, 충돌 요약 생성 0|
|B. PG에 없는 거래 키|A와 같은 방식으로 PENDING·저장 키 KA의 주문을 준비한다. fixture state에 없는 것을 확인한 유효 형식 키 X(KA와 다름)를 사용한다. 본문 `{해당 주문의 providerOrderId, X, SUCCESS}`|GET X 1회가 고정 PG 계약의 거래 부재 404 반환|HTTP 202·data 생략·verificationStatus=UNAVAILABLE. 주문/결제 PENDING·저장 키 KA·확보 재고 유지. X 결합·성공/실패 종결·반환·충돌 요약 생성 0. 대조: 저장되지 않은 providerOrderId는 후보 식별에서 404 ORDER_NOT_FOUND·PG GET 0이며 이 사례로 세지 않는다|
|C. 다른 주문의 유효 거래 키|서로 다른 상품·같은 총액(예: 7,000원)의 주문 A·B를 각각 기본 PENDING confirm으로 준비해 저장 키 KA·KB를 확인한다. setStatus(KB, SUCCESS)만 호출하고 B의 콜백은 보내지 않는다. 본문 `{A의 providerOrderId, KB, SUCCESS}`|GET KB 1회가 B의 정확한 SUCCESS 거래(orderId=B의 providerOrderId) 반환|HTTP 409 PAYMENT_RESULT_CONFLICT. A의 payment_conflict 한 행 commit: IDENTITY_MISMATCH·관찰 주문 ID=B의 providerOrderId·관찰 거래 키=KB·관찰 금액·관찰 상태 SUCCESS. A는 verificationStatus=CONFLICT, PAYMENT_PENDING·PENDING·저장 키 KA 유지(KB 결합 없음)·가용 재고 유지·추가 차감/반환 0. B는 PAYMENT_PENDING·PENDING·저장 키 KB·확인 정보·재고 변화 0, 충돌 요약 없음. 요청 KB/응답 KB이므로 직접 조회 대상 위반이 아니며 회로 성공 1|

세 사례 모두 콜백 처리 구간의 PG GET 증가는 1, POST/create와 외부 거래 증가는 0이다. 같은 거래의 정상 snapshot 콜백(위 정상 PENDING → SUCCESS/FAILED 콜백)과 이 조작 본문 검증은 서로 대체하지 않는다.

### 업무 관찰 순서의 TX 경계 검증

**분리 이유:** 생성/조회 HTTP 응답을 GATE로 잡아 둔 동안 다른 HTTP·TX를 끝내야 하는 사례는, 그 과정이 제품 읽기 timeout 1초를 넘으면 의도한 늦은 PENDING 대신 timeout만 검증한다. 테스트 환경이 빠를 것이라는 가정으로 해결하지 않으며, 제품 읽기 timeout·회로 초기값을 바꾸거나 테스트의 PG 읽기 timeout만 늘리지 않는다. 그래서 기존 "중복·경쟁·callback 선도착"과 "확인/충돌 반영 순서 역전"의 업무 순서 기대값은 이 절의 TX 경계 검증으로 옮기고, 실제 HTTP의 지연·timeout·유실·회로·정상 콜백 종결은 위 시나리오 표와 아래 실제 timeout·풀·회로 절의 증거로 유지한다. 기대값은 삭제·완화하지 않는다. 실제 TX 경계 사례는 PaymentObservationOrderIntegrationTest로 구현했으며 실행 근거는 마지막 I5 절을 따른다.

**통제:** 실제 PaymentTransactionService의 public TX 메서드를 프록시로 호출하고 실제 MySQL에 commit한다. 외부 HTTP 없이 adapter가 만들 수 있는 유효한 PaymentObservation(저장 외부 주문 ID·금액과 일치하는 거래 키·상태, 충돌 사례는 표에 적은 키·금액만 바꾼 값)과 PaymentCheckToken을 준비하고, 적용 순서는 테스트 장벽과 호출 순서로 정한다. 준비는 정상 beginConfirmation으로 주문을 PAYMENT_PENDING·UNKNOWN·재고 확보 상태로 만들고 CREATE 토큰(terminalAtStart=false)을 받는 것으로 시작한다. QUERY 토큰은 실제 prepareVerification으로 얻는다. 각 사례는 고유 주문을 쓰고 테스트 부모 TX를 두지 않으며 결과는 commit 뒤 새 TX로 읽는다. 장벽의 유한 대기(전체 10초 이내)는 HTTP timeout과 별개이며 finally에서 해제한다. 단순 mock 호출 순서나 순수 도메인 테스트는 이 검증을 대체하지 않고 보완 계층으로 유지한다.

|사례|적용 순서|기대값|
|---|---|---|
|종결 뒤 늦은 CREATE PENDING|QUERY 토큰으로 저장 키 KA의 SUCCESS(또는 FAILED)를 applyObservation해 commit → TX A의 CREATE 토큰으로 같은 KA·정상 금액의 유효 PENDING 적용|FINAL·저장 종결 유지(CONFIRMED/SUCCESS 또는 PAYMENT_FAILED/FAILED). verificationStatus·checked_at은 종결 반영 때 값 그대로. 추가 차감·반환·이력 0, 충돌 요약 0|
|종결 뒤 다른 거래의 CREATE PENDING|위와 같이 KA로 종결 commit → CREATE 토큰으로 같은 제공자/계정·외부 주문·금액의 유효 KB PENDING 적용|IDENTITY_MISMATCH 요약 commit(관찰 키 KB)·CONFLICT·verificationStatus=CONFLICT. 기존 종결·저장 키 KA·재고·이력 보존, KB 결합 0|
|종결 뒤 늦은 CREATE UNAVAILABLE|위와 같은 종결 commit → CREATE 토큰으로 UNAVAILABLE(거래 키 없음) 적용|거래 키 확인을 요구하지 않고 위와 같은 종결·확인 정보 보존. UNKNOWN/UNAVAILABLE로 후퇴하지 않음, 충돌 요약 0|
|종결 전 준비한 QUERY PENDING|미종결에서 prepareVerification으로 토큰 P(terminalAtStart=false) 준비 → 다른 QUERY 토큰으로 SUCCESS commit → P에 유효 PENDING 적용|무시·FINAL. 종결·확인 정보 유지, 충돌 요약 0|
|종결 후 준비한 QUERY PENDING|SUCCESS commit → 새 prepareVerification으로 토큰 R(terminalAtStart=true) 준비 → R에 유효 PENDING 적용|TERMINAL_REGRESSION 요약 commit·CONFLICT·verificationStatus=CONFLICT. 성공 종결·재고 유지|
|확인 결과 반영 순서 역전|미종결 PENDING에서 QUERY 토큰 A·B 준비 → B에 UNAVAILABLE 적용·commit → A에 유효 PENDING 적용. 대조로 반대 순서|마지막 commit 관찰이 확인 정보가 됨(A 나중이면 VERIFIED, B 나중이면 UNAVAILABLE). 업무 상태·재고 변화 0|
|충돌 요약 반영 순서 역전|토큰 A·B 준비 → B의 충돌 관찰(예: IDENTITY_MISMATCH) commit → A의 다른 충돌 관찰(예: AMOUNT_MISMATCH) commit|요약 한 행이 마지막 commit 내용(A)으로 교체. 시작 순서·표시 시각으로 교체를 막지 않음. 업무 상태·재고 변화 0|
|반대 종결·같은 종결 재적용|저장 키 KA로 SUCCESS commit → 같은 KA의 반대 FAILED 관찰 적용, 별도로 같은 SUCCESS 재적용|반대 종결은 SUCCESS 보존·TERMINAL_MISMATCH·반환 0. 같은 종결 재적용은 업무·재고 효과 0|
|저장 키와 다른 유효 거래(미종결)|저장 키 KA로 유효 PENDING commit → QUERY 토큰에 같은 제공자/계정·외부 주문·금액의 유효 KB 관찰(PENDING·SUCCESS 각각) 적용|IDENTITY_MISMATCH 요약 commit(관찰 거래 키 KB)·CONFLICT·verificationStatus=CONFLICT. PAYMENT_PENDING·PENDING·저장 키 KA 유지, KB 결합 0, 재고·이력 효과 0|
|저장 키·금액 복합 불일치|저장 키 KA로 유효 PENDING commit → QUERY 토큰에 같은 외부 주문이지만 키 KB·금액도 다른 유효 관찰 적용|AMOUNT_MISMATCH가 아닌 IDENTITY_MISMATCH 요약 commit. 요약의 관찰 금액에 KB의 금액 보존·관찰 키 KB. 업무 상태·저장 키 KA·재고 효과 0|
|식별 일치·금액만 불일치|저장 키 KA로 유효 PENDING commit → QUERY 토큰에 같은 KA·외부 주문이고 금액만 다른 유효 관찰 적용|기존 AMOUNT_MISMATCH 요약 commit·관찰 금액 보존. 업무 상태·저장 키·재고 효과 0|
|저장 키와 다른 유효 거래(종결 뒤)|저장 키 KA로 SUCCESS commit → 새 QUERY 토큰(terminalAtStart=true)에 같은 주문·금액의 유효 KB·FAILED 관찰 적용|TERMINAL_MISMATCH가 아닌 IDENTITY_MISMATCH 요약 commit·CONFLICT. CONFIRMED/SUCCESS·저장 키 KA 보존, KB 결합 0, 반환·ORDER_RELEASE 0. 같은 KA의 반대 FAILED는 위 행의 기존 TERMINAL_MISMATCH|

**계약과 입증 계층:** callback-first·lookup-first·동시 적용의 계약(위 콜백과 대사 절)은 다음처럼 나눠 입증한다. 순차 HTTP의 callback-first/lookup-first 수렴·중복 콜백/대사 효과 0·생성 응답 유실 뒤 정상 콜백 종결은 위 시나리오 표의 실제 HTTP 사례가 맡는다. 종결 뒤 늦은 생성 응답과 늦은 조회의 무시/충돌, 확인/충돌 반영 순서는 이 절이 맡는다. 같은 주문 TX B의 동시 적용과 잠금 대기 후 최신 종결은 아래 부모 잠금 절이 맡는다. 복합 불일치의 업무 충돌 코드 순서(복수 → 식별 → 금액 → 종결)는 이 절의 TX 경계 사례가, 그 관찰의 회로 표본 분류가 바뀌지 않았다는 점은 아래 실제 timeout·풀·회로 절의 2.8 분류 검증과 위 시나리오 표의 실제 HTTP 사례가 맡는다. sendCallbacks count≥2의 중복 알림은 callback worker 2개에서 병렬 실행될 수 있어도 실제 겹침을 입증하지 않으므로 동시 적용의 통과 근거로 쓰지 않는다. 실제 HTTP 실행에서 timeout만 발생했다면 "늦은 PENDING 응답 검증"으로 기록하지 않는다. 풀 고갈·HALF_OPEN 탐색 허용량처럼 HTTP GATE가 필요한 검증은 아래 절의 기존 timeout·장벽·유한 대기·정리 조건대로 유지한다.

### 부모 잠금과 일반 조회의 실제 MySQL 검증

아래는 필수 검증 기준이다. PaymentParentLockIntegrationTest·PaymentOverlapEvidenceIntegrationTest로 구현했으며 실행 근거는 마지막 I5 절을 따른다. HTTP·재고 경쟁과 분리하여 업무 TX를 독립 connection으로 실행하고, 겹침은 유한 장벽/SQL 관측으로 통제한다. 공유 상품 때문에 직렬화되어 잠금 문제를 가리지 않도록 서로 다른 주문·키·상품을 사용한다. 테스트 부모 TX·동일 Entity 재사용은 금지하며 worker 종료/commit 뒤 새 TX로 결과를 읽는다.

- **SQL/수명:** prepareConfirmation·TX A·prepareVerification·TX B·recordConstraintConflict 각각 첫 업무 데이터 접근 SQL이 Order PK SELECT FOR UPDATE인지 SQL 기록으로 확인한다. 엔티티뿐 아니라 스칼라·exists·native 조회도 같은 순서 검사에 포함하고, 연결/TX 초기화용 제어문은 분리한다. Payment/PaymentConflict에 FOR UPDATE가 없음을 확인한다. 콜백 스칼라 식별 TX는 prepareVerification 전에 끝나며 두 호출이 같은 TX/영속성 컨텍스트를 공유하지 않는지 확인한다. INSERT/UPDATE의 DB 내부 잠금 부재를 기대값으로 삼지 않는다.
- **독립 첫 생성:** 두 TX A가 모두 Payment 일반 조회를 완료하고 INSERT 직전 장벽에 도달한 뒤에만 어느 쪽이든 INSERT를 실행한다. 각 TX의 조회 반환·장벽 도달 기록과 SQL 실행 순서로 이 겹침을 입증한다. IDENTITY INSERT가 save 시 실행될 수 있으므로 commit 직전이 아닌 실제 INSERT 직전에 장벽을 둔다. 이후 각 SEND 결과의 commit·결제/ORDER_RESERVATION 한 번·정확한 재고를 확인한다. 서로 다른 결제의 첫 충돌 요약도 두 TX가 모두 PaymentConflict 일반 조회를 완료한 뒤 첫 INSERT 직전 장벽에 도달해야 해제하며, 같은 방식으로 겹침과 각 CONFLICT 결과/요약 한 행의 commit을 확인한다. 이 서비스 경계 테스트는 PG를 호출하지 않으며, 409 응답 변환은 별도 API 경계에서 확인한다. 통제한 독립 키 사례의 deadlock/lock timeout은 실패이며 모든 경쟁에서 발생하지 않는다는 일반화는 하지 않는다.
- **같은 부모의 생성/교체:** 같은 주문 동시 confirm의 한 결제/발송 권한, 같은 결제의 동시 첫 충돌·요약 교체를 확인한다. 요약은 한 행이고 마지막 commit 내용이며 500으로 대체되지 않아야 한다. 다른 주문의 같은 시도 키·외부 거래 결합은 기존 unique 분류와 rollback/새 기록 TX를 별도 확인한다.
- **잠금 대기 후 최신 종결 — 필수:** T1이 실패 반환의 실제 SQL 뒤 commit 전에 멈추고 T2가 같은 Order 잠금을 기다리는 것을 SQL/잠금 관측으로 확인한다. T1 commit 뒤 T2의 일반 조회가 FAILED를 읽어 추가 반환/ORDER_RELEASE가 0인지 확인한다. SUCCESS commit 뒤 반대 FAILED를 적용하는 대조군도 기존 성공 보존·충돌·반환 0이어야 한다. 기존 요약 생성 commit을 기다린 TX가 요약을 교체하고 중복 INSERT/500이 없는지도 확인한다. 잠금 대기 동안 스냅샷/Entity를 미리 읽는 경로가 없어야 한다.
- **콜백 후보 읽기와 종결 사이:** 후보 스칼라 읽기 종료 뒤 다른 TX가 SUCCESS를 commit하고, 새 prepareVerification이 Order 잠금 후 terminalAtStart=true를 복사하도록 통제한다. 이 콜백의 PG 조회가 PENDING이면 TERMINAL_REGRESSION 요약 commit·409, 성공 상태/재고 보존이어야 한다. 후보 읽기의 PENDING 상태나 Entity로 판정하지 않는다.

장벽/worker/잠금 관측 대기는 전체 10초 이내로 제한하고 finally에서 해제·정리한다. 독립 생성 사례에서 양쪽 조회 완료/장벽 도달 전 INSERT가 실행되거나 겹침을 입증하지 못한 실행은 독립 생성 통과 근거가 아니다. 최신 읽기 사례에서 권한 부족으로 잠금 관측이 안 되거나 대기 순서를 입증하지 못한 실행도 최신 읽기 통과 근거가 아니다. 이 필수 검증이 실패하면 원인을 확인해 사용자와 설계를 재검토하며, 자식 잠금·격리 수준 변경·자동 retry로 조용히 우회하지 않는다.

### 스키마 타입과 기존 데이터 전환 검증

아래는 [3.6의 전환 순서와 열 타입·ORM 매핑](model.md#orders의-확장과-point-보존)을 확인하는 필수 검증 기준이다. PaymentSchemaIntegrationTest·PgPaymentMigrationIntegrationTest로 구현했으며 테스트 DB의 실행 근거는 마지막 I5 절을 따른다. 테스트용 새 스키마 생성과 기존 데이터 보존 전환을 별도 사례로 실행한다. 실제 DB 확인 결과만 근거로 쓰고 과거 DDL 로그나 문서의 SQL 예시를 현재 DB 상태의 확인으로 대체하지 않는다.

- **새 스키마의 열 타입:** 새 코드가 빈 테스트 MySQL에 만든 스키마를 `INFORMATION_SCHEMA.COLUMNS`의 DATA_TYPE·COLUMN_TYPE·문자 길이·IS_NULLABLE·collation으로 3.6 표와 비교한다. 신규 enum 열 5개(orders.payment_method, payment.status·failure_kind·verification_status, payment_conflict.code)는 native ENUM이 아닌 표의 VARCHAR 길이·nullable이어야 한다. orders.status·stock_history.cause는 ENUM이며 새 값을 포함한다. CHAR(36)·ASCII binary collation·TEXT 등 그 밖의 문자열 열도 표와 일치해야 한다. 불일치는 매핑 수정 대상이며 통과가 아니다. 새 스키마의 두 ENUM 허용값 목록은 보존 전환 뒤의 목록과도 비교해 차이를 기록한다.
- **기존 데이터 보존 전환의 재현:** 테스트 MySQL에 과거 생성 DDL과 같은 orders·stock_history(기존 ENUM·기존 금액 열 이름)를 SQL로 준비하고, 과거 POINT CONFIRMED·DRAFT 주문과 ADMIN_CHANGE·ORDER_DEDUCTION 이력을 넣는다. 3.6의 사전 확인 → ENUM 확장 → 금액 열 이름 전환·POINT 보정 → 신규 테이블 생성 순서를 실행한다. 전환 전후 상태/원인별 건수와 기존 행의 금액·품목·이력 값이 같고, 확장한 열의 nullable·기본값이 바뀌지 않았는지 확인한다. 사전 확인에서는 기존 DRAFT의 used_point_amount·payment_amount null만 보고 아직 없는 payment_method는 조회하지 않는다. 열 추가·보정 뒤에는 DRAFT의 payment_method·point_amount·paid_amount가 null이고 기존 CONFIRMED의 payment_method가 POINT인지 확인한다.
- **새 값의 저장:** 전환한 스키마에서 PAYMENT_PENDING·PAYMENT_FAILED 주문과 ORDER_RESERVATION·ORDER_RELEASE 이력의 실제 INSERT/UPDATE가 오류·빈 문자열 대체 없이 저장되는지 확인한다. 이어서 새 코드를 ddl-auto=validate로 기동해 과거 POINT 주문 조회 결과의 보존과, 신규 PG confirm의 PAYMENT_PENDING·ORDER_RESERVATION 저장을 확인한다. validate가 ENUM 허용값 목록까지 비교하는지는 확인하지 않았으므로 허용값은 COLUMN_TYPE으로 별도 확인한다.
- **중단 기준:** 사전 확인 결과가 3.6의 기대 정의·값과 다르면 전환 SQL을 실행하지 않고 그 결과를 실행 기록에 남긴다. 이 재현 검증의 통과를 로컬 loopers 등 실제 보존 대상 DB의 전환 완료로 쓰지 않으며, 그 전환은 별도 승인된 실행 단위에서 실제 SHOW CREATE TABLE·값 확인부터 다시 수행한다.

### 실제 timeout·풀·회로 전이

최종 Apache bean의 timeout·풀·retry/redirect 실행 체인은 저장소의 PgHttpConfigTest.appliesTimeoutsPoolAndNoRetryOrRedirectToActualClient로 확인한다. I5 당시의 독립 임시 probe는 과거 실행 이력이며, 현재 설정 적용의 필수 근거를 임시 파일에만 두지 않는다. 이 설정 검사를 실제 connect timeout·네트워크 취소 재현으로 확대하지 않는다.

- **읽기 timeout:** 실제 HTTP에서 header 전 DELAY와 본문 중단을 각각 재현해 경과 시간·원인·실패 표본·UNAVAILABLE·연결 반환을 확인한다. 플랫폼 오차를 허용한 제한시간 안에 종료하는지 검사하고 정확히 1000ms 또는 API 전체 1초를 assert하지 않는다. 이 HTTP timeout 사례의 테스트 전체 제한은 10초, worker 종료 대기는 5초로 두고 초과/종료 실패는 실패다.
- **연결:** 닫힌 loopback port의 연결 거절을 실제 adapter로 확인한다. connect timeout 300ms의 설정 적용은 최종 Apache 설정으로 검증한다. 별도 통제 경로 없이 loopback 연결 거절을 실제 connect timeout 재현이라고 쓰지 않는다. 실제 connect timeout을 재현하지 못했다면 실행 근거에 그 한계를 남긴다.
- **풀 대기 제외:** GATE 10개로 실제 PG 연결 leased=10을 관측하고 해제 전 11번째 요청을 실행한다. 200ms lease timeout이 read timeout보다 먼저 발생하도록 시작/도달 장벽을 사용한다. 11번째의 PG 수신 0·회로 표본 0·UNAVAILABLE, 나머지 gate 해제 후 leased/pending=0을 확인한다. 우연히 10개 요청이 오래 걸린 것을 풀 부족 재현으로 기록하지 않는다.
- **생성 최초 풀 획득 timeout 미발송:** 제품 timeout·공유 풀 크기·회로 초기값은 바꾸지 않는다. 위와 같이 GATE로 실제 공유 풀 leased=10을 관측한 상태에서 사전 확인과 TX A를 마친 생성 요청을 실행한다. 시작/도달 장벽으로 그 생성 요청의 최초 lease timeout(200ms)이 점유 요청들의 읽기 timeout보다 먼저 났음을 입증한다. 기대값은 다음과 같다.
  - 생성의 PG createReceived·외부 거래 증가 0, pgCreate 성공·실패·느림·전체 표본 증가 0이다.
  - CREATE/LOCAL_NOT_DISPATCHED(POOL_ACQUIRE_TIMEOUT), confirm 200 PAYMENT_FAILED·결제 FAILED/failure_kind=POOL_ACQUIRE_TIMEOUT, 거래 키 없음, verificationStatus·checked_at은 기존 값(NOT_CHECKED/null) 유지, 재고/ORDER_RELEASE 반환 한 번이다.
  - 같은 관찰 재적용은 추가 반환 0이고, 반환 SQL 뒤 commit 전 실패를 주입하면 TX B 전체가 rollback되어 UNKNOWN·확보 재고가 남으며 조회 0건으로 임의 종결하지 않는다.
  - 대조군: 같은 점유 상황의 조회 GET은 기존 UNAVAILABLE·반환 0이다. 생성의 연결 거절/timeout·응답/읽기 timeout·interrupt/취소·원인 사슬에 ConnectionRequestTimeoutException이 없는 풀 관련 오류는 POOL_ACQUIRE_TIMEOUT으로 처리되지 않고 기존 분류(생성 UNKNOWN·재고 유지)를 따른다.
  - 최종 client 설정에서 Apache 자동 retry·redirect 비활성, 재실행 exec interceptor·RestClient 재시도 interceptor 부재를 확인한다(2.8 보장 조건). 사전 확인 통과 뒤 실제 회로 거절 검증(아래 미발송 판정)은 그대로 유지한다.
  - 기존 GATE·유한 대기(전체 10초)·worker/연결 정리 기준을 따른다. lease timeout이 먼저 났음을 입증하지 못했거나 PG 수신이 생긴 실행은 통과로 세지 않는다.
- **실패율:** 새 회로에 빠른 정상 조회 2회+빠른 5xx 조회 3회를 순서대로 실행한다. 4회까지 CLOSED·5회 뒤 실패 3/5로 OPEN. OPEN의 후속 요청은 PG GET 0·UNAVAILABLE다. 이 케이스는 느림 조건을 충족하지 않게 해 실패율만의 원인을 검증한다.
- **느림 비율:** 실패하지 않는 정상 조회 5회 중 3회를 800ms 초과·읽기 timeout 미만으로 통제한다. 정상 성공 표본이어도 느림 3/5로 OPEN인지 확인한다. 로컬 scheduling으로 timeout이 발생했다면 ‘느린 성공’ 재현으로 인정하지 않고 원인을 기록해 재실행한다. 경계값/전이 조합은 주입 clock 단위 테스트로 보완하되 실제 HTTP 증거와 구분한다.
- **회복 판정:** 새 회로/시나리오별로 OPEN 후 5초가 지난 다음 요청이 HALF_OPEN 탐색을 시작하는지 확인한다. 빠른 정상 2회 완료면 CLOSED, 빠른 성공 1·실패 1이면 OPEN이다. 자동 OPEN→HALF_OPEN 전환 false·maxWait=10초를 최종 config로 확인한다. 아래 허용량·만료·비취소는 독립 사례로 검증한다.
- **탐색 허용량:** 실제 PG HTTP 탐색 두 요청을 GATE로 짧게 보류하고 세 번째가 PG 수신 없이 거절되는지 확인한다. 이 순서는 HTTP 읽기 timeout/회로 만료 전에 완료하고 finally에서 두 gate를 해제해 작업/연결을 정리한다. 1초 읽기 timeout인 두 HTTP를 10초 동안 묶어 두는 방식으로 만료 검증을 대신하지 않는다.
- **HALF_OPEN 10초 만료:** 빠른 정상 PENDING 조회 한 번만 완료한 뒤 두 번째 탐색을 보내지 않는다. 10초 전 HALF_OPEN·판정 미완료 10초 경과 뒤 OPEN을 실제 타이머로 확인한다. 만료만으로 추가 PG 요청/주문 실패/재고 반환/확인 정보 변경이 발생하지 않아야 한다. 5초 OPEN 대기·10초 탐색 대기와 정리를 포함한 테스트 전체 제한은 30초다.
- **진행 중 작업 비취소:** 별도 회로 계층 테스트에서 latch로 완료를 보류한 작업과 실제 상태 타이머를 사용해 만료 후에도 작업이 취소/중단되지 않았음을 확인하고, 테스트가 직접 latch를 해제해 완료/종료를 확인한다. 이 사례만 테스트 전용 짧은 maxWait를 사용할 수 있으며 실제 HTTP 종료의 증거로 취급하지 않는다. 실제 승인값 10초/PG·업무 효과는 앞 만료 통합 사례에서, HTTP timeout/자원 반환은 별도 실제 HTTP 사례에서 검증한다. 제품 실행 체인에 executor/TimeLimiter를 추가하지 않는다.
- **생성 회로:** 사전 권한 반환으로 HALF_OPEN 탐색 슬롯이 새지 않는지, TX A 후 actual decorator 거절/허용 경쟁에서 POST 0/1·반환 1회가 지켜지는지 검증한다. 생성만 OPEN인 상태에서 lookup은 가능하고 반대도 가능한지 확인한다. 승인 표본 분류와 공통 초기값의 전이는 각 instance를 독립 초기화해 검증하며 DB 대기·처리는 생성 느림 표본에서 제외한다.
- **미발송 판정:** [2.8 미발송 판정 장치](pg-client.md#생성-회로와-사전-차단)의 조건을 아래 사례로 검증한다. 사례마다 새 회로·고유 주문을 사용하며 대조군을 위해 새 오류 정책을 만들지 않는다.
  - **사전 확인 통과 뒤 actual 권한 거절:** 사전 확인 통과 → TX A commit 뒤, create 호출 전에 pgCreate를 OPEN으로 전이한다(또는 HALF_OPEN 탐색 슬롯 소진). 실행 본문 미진입, PG createReceived 0, CREATE/LOCAL_NOT_DISPATCHED(CIRCUIT_REJECTED), 회로 성공·실패·느림 표본 0을 확인한다. 이어서 기존 TX B 계약대로 FAILED/CIRCUIT_REJECTED·PAYMENT_FAILED·반환/ORDER_RELEASE 한 번·거래 키 없음·PG 확인 정보 유지를 확인한다.
  - **본문 진입 뒤 같은 타입 예외(대조군):** 테스트 seam이 실행 본문의 첫 동작 뒤, 본문 안 catch 범위의 POST 전에 pgCreate 이름의 CallNotPermittedException을 던진다. 원인을 보존한 PgAdapterProgrammingException으로 식별되어 pgCreate 성공·실패·느림·전체 표본 증가 0, confirm HTTP 500, TX B 호출 0, LOCAL_NOT_DISPATCHED·CREATE_UNAVAILABLE 관찰 합성 0, CIRCUIT_REJECTED 실패·재고 반환/ORDER_RELEASE 0, PG createReceived 0·자동 재전송 0을 확인한다. 상태 보존으로 TX A 뒤의 PAYMENT_PENDING·결제 UNKNOWN·확보 재고·확인 정보가 그대로인지 새 TX로 확인한다.
  - **호출별 독립성:** 실제 PgHttpGateway.create를 서로 다른 주문으로 두 번 호출한다. 첫 호출을 CREATE GATE로 보류해 본문 진입·PG 수신을 확인한 뒤 pgCreate를 OPEN으로 전이하고 두 번째를 호출한다. 두 번째만 미진입·POST 0·LOCAL_NOT_DISPATCHED이고, 첫 호출은 PG 수신 1이며 LOCAL_NOT_DISPATCHED가 아닌지 확인한다. 한쪽의 진입 표시가 다른 쪽 판정에 쓰이지 않아야 한다. gate는 읽기 timeout 전에 해제하고 finally에서 정리한다.
  - **미진입이지만 해당 회로 거절이 아님:** decorator 실행 호출 전 seam에서 ① pgLookup 이름의 CallNotPermittedException, ② 일반 RuntimeException을 각각 던진다. 진입 표시는 미진입이지만 pgCreate 표본 증가 0(수동 기록 없음), confirm HTTP 500, TX B 호출 0, LOCAL_NOT_DISPATCHED·CREATE_UNAVAILABLE 관찰 합성 0, PG createReceived 0, 재고 반환/ORDER_RELEASE 0·자동 재전송 0을 확인한다. 상태 보존도 위 대조군과 같다. 사전 확인 BLOCKED는 기존 503·DRAFT 계약으로 따로 검증하며 이 분기와 합치지 않는다.
- **분류:** 2.8 각 행은 parameterized case로 검증한다. 성공·실패·제외 증가와 실제 PG 수신/일반 fallback 횟수·업무 결과를 별도로 측정한다. recordResult 실패를 예외 fallback으로 바꾸지 않고 한 요청을 두 번 집계하지 않는다.
- **자원:** response body/connection close를 finally 또는 request factory lifecycle로 보장한다. fixture/server/client/executor 종료, worker 제한시간 내 종료를 확인한다. future.cancel 반환만으로 HTTP 작업 종료를 가정하지 않는다. 종료가 불명확하면 DB를 재사용하며 통과했다고 기록하지 않는다.

### 기존 테스트의 유지·교체

전환 근거는 신규 DRAFT 결제수단의 POINT→PG 변경이다. 테스트 전체를 삭제하거나 기대값만 느슨하게 만들지 않는다. 아래 현재 파일의 **해당 사례**를 식별해 제거 사유와 대체 테스트명을 구현 diff/검증 기록에 남긴다.

|현재 테스트 파일/사례|처리·대체 검증|
|---|---|
|OrderConfirmFacadeIntegrationTest / 신규 결제 포인트 차감·ORDER_USE·잔액 부족·Point 미생성 거절|해당 POINT 결제 실행 기대만 교체. payment/고정 품목의 확보·반환/TX B, 포인트 변화 0·Point 없는 고객 성공으로 대체. 소유권·재고 부족/삭제·동일 주문 보호는 유지|
|OrderTransactionIntegrationTest / Point/Product/Order 단일 TX rollback|TX A와 TX B의 실제 SQL 뒤 rollback 및 외부 성공 뒤 내부 실패 회복으로 재구성. 원자성 검증 목적 유지|
|OrderConcurrencyIntegrationTest / Point 잔액 경쟁·충전과 주문 사용의 직렬화|신규 주문의 잔액 소비 기대는 교체. 동시 confirm의 발송 1회·재고 경쟁·관리자 변경/삭제 경쟁·callback/reconcile 경쟁으로 대체. 독립 포인트 충전 경쟁 검증은 유지|
|OrderV1ApiE2ETest / confirm 입력 없음·신규 전액 POINT·INSUFFICIENT_POINT|합성 카드/UUID 입력·PG payment 응답·202/200으로 교체. 비소유/없는 주문·재고 부족·확정 POINT 재요청 오류는 보존|
|OrderModelTest·조회/목록/관리자 주문 테스트|새 PG 상태 전이를 추가하고 과거 POINT snapshot을 유지. DRAFT 재고 미차감·당시 품목/금액·소유권·삭제 이후 과거 주문 조회 검증 유지|
|PointModel/PointHistory/PointFacade·PointV1ApiE2ETest·Stock/History·캐시·ArchitectureTest|독립 기존 기능은 유지. PG 예약/반환 원인·commit 뒤 무효화·새 의존 경계를 보강. 코어 학습 테스트를 PG 통합 검증으로 대체하지 않음|

### 실행 근거와 비교 실험의 경계

각 실행은 코드/설정 버전, 초기 데이터·plan·재현 순서, 예상/실제 HTTP·외부 거래 수, 주문·결제·고정 품목·가용 재고·포인트·확인 정보/별도 충돌 요약, 회로 표본/상태, 지연, 자원 종료와 미검증 조건을 기록한다. 실행 실패·0건·skip·인프라 연결 실패는 통과가 아니다. 실제 MySQL·Redis·controller 회귀와 Checkstyle·ArchUnit을 포함하고, 실패를 통과시키기 위한 보호 검사 삭제는 허용하지 않는다.

구현 필수 검증과 글의 비교 실험은 구분한다. 필수 검증은 위 계약 준수 확인이다. 글은 구현 뒤 사용자와 축/대안을 정하고, 같은 요청열·PG 상태·지연·동시성·HTTP timeout을 통제한 채 한 설정 또는 명시한 상호작용을 바꿔 본다. 관찰 후보는 호출별 표본/실패율/느림/상태, 실제 PG GET 수, 차단/정상 요청 거절 수와 회복 지연이다. `COUNT_BASED`/`TIME_BASED`, 창 크기·최소 표본·느린 호출 임계값 등은 비교 후보이며 최종 실험값·횟수·결과를 지금 확정하지 않는다. 로컬 결과를 운영 일반론으로 확대하지 않는다.
