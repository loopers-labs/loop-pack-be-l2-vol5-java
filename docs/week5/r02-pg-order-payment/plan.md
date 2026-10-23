# R02 구현·검증·리뷰 및 커밋 계획

상태: **커밋 1~9의 로컬 구현·검증 완료, 원격 게시 전**. 2026-10-22 기준 전체 검사 432건·별도 서버 실험 10개를 통과했다. [실제 결과](result.md)와 [재현 방법](verification.md)을 따른다.
아래는 승인된 구현 계약과 당시 커밋 순서다. 사용자 결정·리뷰 보완과 실제 진행 기록을 함께 남긴다.

[요구사항](requirement.md) · [결정 목록](trade_off/total_trade_off.md) · [상태 흐름](../states/total.md)

## 1. 목표와 경계

- 시작: R01 PR #24 병합 커밋 `4b81541e92e3afce2fbbba25342b979bb2328385`.
- 작업: `volume-5/r02-pg-order-payment`, 대상: `volume-5/main`. R01 기반을 실제 주문·상품에 연결한다.
- R02는 로컬 구현·검증·커밋까지 진행한다. 원격 게시/PR 생성은 요청 시 수행한다. 기존 JMeter 변경·원문을 보존한다.
- R03의 자동 재조회·회로·fallback·백그라운드 실행기는 추가하지 않는다. 현재 Resilience4j/Spring Retry 의존성도 없다.
- 새 PG 결제는 포인트를 차감하지 않는다. 포인트 충전/조회와 과거 POINT 기록·품목·금액은 유지한다.

## 2. 사용자 확정 사항

| 구분 | 확정한 동작 |
|---|---|
| 준비 | DRAFT→PAYMENT_PENDING 주문 CAS 성공자만 같은 TX에서 재고 차감·시도/발송 시작·차감/복구 작업 기록을 저장한다. commit 정상 반환 후 최초 실행만 PG POST를 보낸다. |
| 결과 | 검증 관찰을 먼저 별도 commit한다. 결제 시도 행을 짧게 잠가 저장 결과/충돌을 확인하고 PAYMENT_PENDING→CONFIRMED/PAYMENT_FAILED CAS 성공자가 같은 업무 TX에서 실제 효과를 반영한다. |
| 재고 | 성공 시 추가 차감 없이 유지, 확정 실패만 현재 가용 수량에 차감 수량을 한 번 더한다. 상품 잠금·삭제 상태·캐시 commit/rollback을 보존한다. |
| 복구 실패 | int 한도 초과 등 업무 실패는 전체 업무 rollback, PG 근거 보존, 수동 확인이다. 관리자 반환 예정량 집계 제한은 추가하지 않는다. |
| 고객 | 기존 X-USER-ID로 confirm/상세/reconcile의 주문 소유자를 확인한다. 과거 POINT 고객 상세도 포함하며 타인 주문은 없는 주문과 같은 404·ORDER_NOT_FOUND·메시지다. |
| 관리자 | 주문 관리자 조회·미완료 목록·운영자 대사는 기존 사용자 1번 fixture를 확인한다. 고객 경로는 1번도 본인 주문만 처리한다. 다른 관리자 API·실제 로그인·역할 테이블은 범위 밖이다. |
| callback | 요청 안에서 권위 조회→관찰 commit→공통 업무 반영을 동기 수행한다. PG HTTP는 TX 밖이며 별도 callback 실행기를 추가하지 않는다. |
| 수동 대사 | 저장된 검증 종결 결과 재적용은 조회 0회. 그 외 요청당 PG 조회 최대 1회, 미완료 202다. 자동 회차를 소비/초기화하거나 새 자동 대기를 시작하지 않는다. |
| 복구 실행 | READY는 claim, RUNNING/MANUAL_REQUIRED는 실행 ID·상태·회차 CAS 인계다. 옛 실행의 진행 갱신을 차단하고 자동 lease/재시작 회수는 추가하지 않는다. |
| 리뷰 보완 | 업무 반영 완료 후 늦은 충돌은 200+기존 완료 상태+주의 표시, 미반영 충돌은 202다. 실패 표시는 실패한 업무 시도 당시의 작업 상태·실행 ID·회차로만 CAS한다. 범용 save 제거는 confirm/POINT 테스트 전환과 같은 커밋에서 수행한다. |

## 3. 주문·재고 저장 계약

### 주문 CAS와 일반 저장의 분리

- OrderStatus에 PAYMENT_PENDING/PAYMENT_FAILED를 추가한다. DRAFT/PAYMENT_PENDING/PAYMENT_FAILED는 OrderRecord가 없고 CONFIRMED는 POINT/PG PAID 기록이 있다.
- 신규 주문 저장은 `OrderRepository.insert(Order)`로 명확히 나눈다. 기반 커밋에서는 insert/CAS를 추가하고 기존 POINT confirm과 save를 유지한다. 새 PG 흐름은 처음부터 CAS를 사용한다.
- 범용 save 제거는 커밋 5의 confirm PG 전환·관련 테스트/fixture 이행과 묶는다. 신규 생성/완료 사실을 가진 신규 fixture는 insert로 전환하고 기존 주문 상태를 일반 mapper save로 덮는 경로를 제거한다. 기존 주문 갱신을 insert로 대체하지 않는다.
- 상태를 추가하는 기반 커밋부터 기존 POINT confirm은 DRAFT만 허용한다. PAYMENT_PENDING/PAYMENT_FAILED를 POINT로 확정하는 중간 경로를 만들지 않는다.
- `prepareIfDraft(orderId, userId)`는 ID·소유자·DRAFT 조건의 JPQL UPDATE 건수로 선점한다. 같은 TX에서 후속 준비 실패 시 이 변경도 rollback한다.
- `completePgIfPending(orderId, terminalStatus, pgRecord)`는 PAYMENT_PENDING 조건의 CAS와 필요한 PG OrderRecord 저장을 같은 TX에서 수행한다. 실패 종결에는 기록을 만들지 않는다.
- 완료 CAS 직후 CONFIRMED/기록 없음의 중간 상태를 도메인으로 복원하지 않는다. repository 내부 JPA entity에 기록을 연결·flush한 뒤 유효한 도메인을 복원한다.
- JPQL bulk 전 flush·후 clear를 적용한다. CAS 전 읽은 도메인을 일반 save하지 않는다. CAS 실패 후 응답은 해당 TX 종료 뒤 새 조회에서 얻는다.
- 과거 POINT fixture는 결제 완료 사실을 가진 신규 Order를 insert해 준비한다. 운영용 POINT 결제 우회 API·테스트 전용 production facade는 추가하지 않는다.
- `007-order-pg-status.sql`은 기존 orders.status enum에 두 상태를 추가하는 명시 증분이다. 기존 DRAFT/CONFIRMED·주문 기록·품목은 바꾸지 않는다.

### 재고 복구와 잠금 순서

- Stock의 양수 수량 반환은 안전한 덧셈으로 int 초과를 거절한다. Product의 반환 행동은 주문 차감 기록에 따른 반환만 수행하며 삭제를 해제하지 않는다.
- 준비는 주문 CAS→상품 ID 오름차순 잠금→기존 품목 순서 전체 검사→차감·시도/차감/작업 기록 저장이다. pay 시도/작업은 최초 insert라 기존 행 잠금에 의존하지 않는다.
- 공통 반영은 결제 시도 행 잠금→현재 저장 결과/충돌 확인→주문 종결 CAS→필요한 상품 ID 오름차순 잠금/반환→차감 기록/작업 완료다. 성공은 실제 수량을 바꾸지 않으며 삭제 상품도 저장 스냅샷으로 마무리한다.
- 실패 복구 수량은 StockAdjustment의 고정 품목에서 가져온다. 관리자가 10으로 설정한 뒤 2 반환이면 12다. 재고·주문·기록·작업 변경 중 하나라도 실패하면 전체 업무 TX를 rollback한다.
- 실제 상품 변경마다 기존 ProductDetailsChangedEvent를 발행한다. 캐시는 기존 AFTER_COMMIT 정책을 유지해 rollback이 노출되지 않게 한다.

## 4. application 흐름과 컴포넌트

기존 계층/Context 규칙을 유지한다. 조율 facade에는 @Transactional을 붙이지 않는다. TX 메서드는 별도 Spring 빈의 공개 진입점이며 PG HTTP·대기는 그 밖이다.

| 역할·제안 클래스 | 책임 |
|---|---|
| ordering.facade.ConfirmOrderFacade | 고객 입력/시도 식별, 준비 결과에 따른 최초 PG POST, 관찰 저장, 공통 반영, 최신 응답 조회 |
| ordering.facade.PrepareOrderPaymentFacade | 내부 준비 UseCase 조율. TX 정상 반환의 발송 권한을 전달하고 CAS 패자·유일 제약 실패자는 원래 TX 종료 후 새 읽기 TX에서 재판정 |
| ordering.service.PrepareOrderPaymentService | REQUIRES_NEW 준비 TX. 소유권→기존 시도 일치→주문 CAS→상품 검사/차감→PaymentAttempt STARTED·StockAdjustment DEDUCTED·RecoveryTask READY 저장. 재판정도 별도 REQUIRES_NEW 읽기 TX |
| pay.service.RecordPaymentObservationService | R01의 REQUIRES_NEW 관찰 저장 재사용. 식별/종결/존재 상충을 보존하며 실패 관찰로 기존 사실을 지우지 않음 |
| ordering.facade.ApplyOrderPaymentFacade | 내부 공통 반영 UseCase. 업무 직전 작업 비교값 확보→업무 TX 호출→rollback 뒤 원래 비교값으로 수동 표시→새 TX의 완료 우선 재판정 |
| ordering.service.ApplyOrderPaymentService | REQUIRES_NEW 공통 업무 TX. 저장된 검증 SUCCESS/FAILED/NO_EFFECT 및 충돌을 확인해 주문 CAS와 한 번의 업무 효과를 반영. 경합/실패 후 재판정은 별도 읽기 TX |
| ordering.facade.ReconcileOrderPaymentFacade | 고객/관리자 권한 확인 후 이미 업무 반영 완료면 저장 상태 반환. 미완료만 작업 CAS claim/인계 후 저장 종결 재적용 또는 TX 밖 단일 PG 조회→관찰 commit→공통 반영 |
| pay.service.PaymentRecoveryProgressService | 짧은 진행 TX. 실행 ID·기대 상태/회차 조건부 갱신, 수동 조회 시각과 미완료 이유, 업무 실패 기록 |
| ordering.facade.HandlePaymentCallbackFacade | 등록된 주문/시도의 알림 식별→TX 밖 권위 조회→관찰 commit→공통 반영. 본문 status로 직접 처리하지 않음 |
| ordering.query/dao 및 infrastructure.query.ordering | QueryDSL projection으로 고객 소유 주문·관리자 주문/미완료 목록. 조회 Service/UseCase·HTTP 호출은 추가하지 않음 |

- 전역 키 충돌도 소유권 확인 뒤 구분하고 다른 주문/고객의 결과를 반환하지 않는다. DB 유일성 위반이 난 준비 TX는 종료한 뒤 새 TX/조회에서 저장 상태를 재확인한다. rollback-only TX에서 복구 조회하지 않는다.
- 같은 주문/키/금액/실효 조건은 기존 상태를 반환한다. 다른 키/조건은 409다. 카드 원문은 명령/DTO에만 잠시 존재하고 toString/오류/저장에 노출하지 않는다.
- PG 생성 뒤에는 결과 미확인·중단·commit 응답 불명확을 이유로 POST를 재전송하지 않는다. STARTED 직후 호출 전 종료도 수동 확인 대상으로 남긴다.
- R02 confirm의 PENDING/미확인은 저장 상태를 반환한다. 자동 조회 worker를 임시로 넣지 않는다. callback 또는 명시 대사로 마무리한다.
- 준비의 dispatchAllowed=true는 준비 TX commit이 정상 반환된 최초 호출에만 준다. 같은 요청의 저장 시도에서는 항상 false다. 이 내부 권한을 받아 PG HTTP로 연결하는 confirm 전환은 커밋 5에서 수행한다. 준비 조율자의 재판정은 조회 API가 아닌 쓰기 경합 처리다.

### 진행 상태와 실패 기록 보완

- 수동 대사 claim/인계 시 usedRounds는 유지한다. 조회 직전 현재 실행 ID/회차로 lastCheckedAt을 갱신하는 별도 조건부 동작을 추가하고 자동 consumeRound를 호출하지 않는다.
- lastCheckedAt은 조회 시작 직전 시각으로 설명을 보완한다. 자동 조회는 회차 소비와 함께, 수동 조회는 예산 변화 없이 기록한다. 지터/다음 예약 정보는 저장하지 않는다.
- 수동 단일 조회가 PENDING/확인 불가면 현재 실행 권한으로 MANUAL_REQUIRED와 이유를 기록한다. 옛 실행의 변경은 실패한다.
- 업무 반영 직전 또는 해당 업무 TX 안에서 확인한 작업 상태·실행 ID·사용 회차를 변경 불가능한 비교값으로 확보한다. 업무 rollback 후 별도 짧은 TX의 APPLICATION_FAILED 실패 기록기에 이 값을 전달한다. rollback 뒤 최신 실행을 다시 읽어 비교 기준으로 삼지 않는다.
- 당시 READY였다면 READY/실행 ID 없음/회차 0이 모두 같을 때만 새 표식 실행 ID와 수동 표시를 저장한다. 당시 RUNNING/MANUAL_REQUIRED였다면 당시 상태·실행 ID·회차가 모두 같아야 한다. 인계 등으로 조건이 바뀌면 갱신하지 않으며 APPLIED는 되돌리지 않는다.
- 실패 기록 자체가 DB 장애로 저장되지 못해도 주문 미완료+저장 종결 결과가 운영 목록에 남는다. 이유 저장 성공을 업무 복구 성공으로 표시하지 않는다.
- 2026-10-22 리뷰 보완 승인: 수동 실패 표식 저장만 실패하면 로그에 남기고 새 TX의 현재 상태 조회를 한 번 시도한다. 완료 상태를 우선 반환하고 미완료는 확인 필요로 표현하며, 조회까지 실패하면 오류를 전파한다. 표식 저장 재시도는 추가하지 않는다.
- callback은 정상 실행 중 작업을 선점하지 않는다. 현재 실행을 모르는 지연 처리기가 새 실행의 진행을 덮지 않도록 실패 기록 조건을 검사한다.
- 이미 적용된 뒤 늦은 PG 종결 충돌은 적용을 반전하지 않는다. 저장 Conflict를 운영 목록/응답의 주의 표시로 노출한다. 수동 대사는 권한 확인 후 이미 업무 반영 완료라면 claim/인계·PG 조회 없이 저장된 완료 상태와 주의를 반환한다. callback은 완료 후에도 권위 조회로 늦은 PG 종결 충돌을 확인한다. 이번 범위에 강제 충돌 해소·환불 API는 추가하지 않는다.

## 5. API·조회 계약 제안

### 진입 경로

| API | 입력/접근 | 응답 |
|---|---|---|
| POST /api/v1/orders/{id}/confirm | 기존 X-USER-ID, body paymentAttemptKey/cardType/cardNo/testOptions. 금액·callback URL은 고객 입력에서 받지 않음 | 종결 업무 반영 완료 200, 미완료/확인 필요 202 + 현재 주문/시도/조회 경로 |
| GET /api/v1/orders/{id}, GET /api/v1/orders | 기존 X-USER-ID, 단건/목록 모두 본인만 | 조회 전용 200. PG·재고·진행 변경 0회 |
| POST /api/v1/orders/{id}/payment/reconcile | 본인. 새 카드/금액/시도 키를 받아 결제를 바꾸지 않음 | 저장 결과 반영 또는 조회 최대 1회. 완료 200, 미완료 202. 충돌 주의는 두 응답 모두 가능 |
| GET /api-admin/v1/orders, GET /api-admin/v1/orders/{id} | 기존 X-USER-ID=1 | 기존 관리자 조회에 PG 정보 추가 |
| GET /api-admin/v1/payments/incomplete | 기존 X-USER-ID=1, 기존 page/size 계약 | 미완료·수동 확인·종결 상충 목록. 조회 전용 |
| POST /api-admin/v1/orders/{id}/payment/reconcile | 기존 X-USER-ID=1, 타인 주문 처리 허용 | 고객과 같은 내부 대사/반영 경계. 최대 1회 조회 |
| POST /api/v1/payments/callback | providerOrderId/transactionKey/status 알림. 고객/관리자 헤더가 아닌 PG 권위 확인 | 업무 반영 완료 200, 미완료 202. 충돌 주의는 두 응답 모두 가능. 응답에는 고객 주문 상세를 노출하지 않음 |

- 기존 헤더 누락/형식 오류 400·없는 사용자 404는 유지한다. 유효한 1번 이외 관리자는 403을 제안하며 기존 오류 계약에 additive 매핑한다.
- 타인 고객 주문과 없는 주문은 같은 404다. 소유권은 상태/멱등 충돌 응답·CAS·PG 호출보다 먼저 확인한다. 기존 POINT 본인 재확정은 409다.
- confirm 키/합성 카드/조건은 R01 규칙대로 검증한다. null·잘못된 형식 400, 기존 시도와 충돌 409다. 필드 오류에 카드 값을 포함하지 않는다.
- callback의 잘못된 입력 400, 미등록 주문/시도 404, 식별 상충 409를 제안한다. 본문 status는 알림으로만 사용하며 거래 키가 미결합이어도 권위 조회가 일치한 뒤 결합한다.
- 내부 업무 반영 실패를 실패 결제로 만들지 않는다. 정상 PG 조회/저장 이후 업무가 rollback되면 미완료/수동 확인을 표현한다. DB 읽기도 불가하면 기존 5xx를 유지한다.
- 처리 응답의 200/202는 업무 반영 완료 여부로 판단한다. PG 주문이 CONFIRMED/PAYMENT_FAILED이고 작업이 APPLIED라면 늦은 충돌이 있어도 200+기존 완료 상태+주의 표시다. PAYMENT_PENDING의 미반영 충돌은 202+수동 확인 필요다. GET의 조회 성공은 항상 200이다.
- 예를 들어 202를 받은 고객은 GET으로 저장 상태를 읽고 POST reconcile로 실제 재확인을 요청한다. R02에는 자동 재조회가 없다. 재고 반환 한도 초과는 PG 실패를 보존한 채 관리자가 가용 수량의 원인을 해소한 뒤 POST 관리자 대사로 저장 근거를 재적용한다(PG 조회 0회).

### 상태를 읽는 모양

- 기존 OrderView/AdminOrderView의 orderId/status/totalAmount/paymentAmount/paymentStatus/createdAt/items를 유지한다. paymentStatus는 주문 기록의 PAID/null 의미를 유지한다.
- paymentMethod(POINT/PG/null), paymentAttempt, paymentLookupUrl을 추가한다. paymentAttempt는 key·발송 상태·검증 결과·거래 키·거절·충돌·복구 진행/사용 회차·시각·이유를 구분한다. 카드/원문/비밀 입력은 반환하지 않는다.
- 내부 공통 반영 결과의 APPLIED는 성공/실패에 따른 업무 반영 완료다. attentionRequired는 충돌/이번 업무 실패의 주의 표시이며 운영 수동 상태 전체를 대신하지 않는다. API에서는 복구 작업의 MANUAL_REQUIRED도 별도로 보여준다.
- 기존 POINT는 paymentMethod=POINT, 시도 없음이다. DRAFT/시도 없음은 결제수단을 임의로 PG로 표시하지 않는다. PG 미완료는 주문 기록 필드가 null이어도 시도 결과/진행을 별도로 보여준다.
- 운영 미완료 목록은 주문 PAYMENT_PENDING 또는 작업 MANUAL_REQUIRED 또는 pay Conflict≠NONE을 포함한다. APPLIED 뒤 늦은 충돌도 누락하지 않으며 오래된 RUNNING을 확실한 프로세스 사망으로 단정하지 않는다.
- 조회는 QueryDSL projection·품목 일괄 조회를 유지한다. 고객 단건은 orderId/userId 조건을 같은 조회에 적용한다. 관리자 fixture 검사 후 전체 조회한다.

## 6. 작은 기능 커밋 순서

각 기능 커밋은 관련 최소 검사 Green과 세 관점 리뷰 후 남긴다. 중요한 수정은 사용자 확인→수정→검사→재리뷰를 따른다.

| 순서 | 커밋 제안 | 책임 | 최소 확인 |
|---|---|---|---|
| 1 | docs: PR02 상세 구현 계약과 리뷰 계획 정리 | 이 계획·연결 문서·리뷰 보완 | 링크/범위/기존 변경 보존 |
| 2 | feat: 주문 PG 상태와 조건부 저장 및 재고 반환 구현 | Order 상태/insert/CAS 추가, 기존 save/POINT confirm 유지와 DRAFT 제한, Stock/Product 반환, 증분007 | 기존 호출 컴파일·상태 불변식·대표 CAS 경합·정수 초과/삭제 유지 |
| 3 | feat: 재고 선차감과 결제 준비 선점 구현 | 준비 TX·실효 조건/키 비교·최초 발송 권한 | 중복 동시 준비의 차감 1회·충돌 rollback·부족 PG0 |
| 4 | feat: PG 결과의 공통 업무 반영 구현 | 결제 행 보호·종결 CAS·PG 기록/반환·실패 당시 비교값으로 실패 표시 | 중복 실패 반환 1회·업무 rollback/근거 보존·새 인계 보호·관리자 변경 |
| 5 | feat: 주문 확정을 PG 결제로 전환 | confirm facade/DTO/header/200·202, 관련 POINT 테스트/fixture 전환과 범용 save 제거를 함께 수행 | 기존 호출 컴파일·PG create1·포인트0·유실 상태 유지·본인/충돌 |
| 6 | feat: 고객 및 관리자 결제 상태 조회 구현 | 소유 단건/목록·관리자1·미완료 projection | POINT/PG 조회·타인404·관리자거절·GET 효과0 |
| 7 | feat: 단일 조회 수동 대사와 실행 인계 구현 | 고객/관리자 대사·수동 시각/예산 유지·진행 CAS | 저장종결 GET0·미확인 GET1·옛 실행 차단 |
| 8 | feat: 동기 callback 권위 확인과 공통 반영 연결 | callback 검증→PG 조회→관찰→반영 | 실제 controller·중복/지연·허위 본문 직접 반영0 |
| 9 | test: 실제 PG 주문 효과와 장애 및 기존 기능 검증 | 독립 프로세스 실험·필수 전체 check·result | 실제 거래/재고/포인트/상태·SQL/보존·기준 완화 없음 |

새 기능을 검증하려고 임시 공개 API·미완성 PG 상수 응답을 추가하지 않는다. 순서상 아직 노출하지 않은 기반은 직접 서비스/저장 테스트로 확인한다.

## 7. 테스트 범위와 기존 검사 이행

- 핵심 상태/저장/CAS/업무 효과만 최소 대표 사례로 묶는다. getter/DTO 복제·전체 시나리오 곱집합·성능 벤치마크는 추가하지 않는다.
- 기존 confirm의 POINT 차감 전제는 커밋 5에서 PG 전환·범용 save 제거와 함께 바꾼다. 기존 저장 실패 주입·repository 갱신·POINT fixture도 같은 커밋에서 맞추어 중간 커밋의 컴파일/관련 검사를 유지한다. 재고/기록/금액/스냅샷·rollback·경합 효과의 검증은 유지하고 새 결제의 포인트/USE 기록은 0을 검증한다.
- 과거 POINT는 저장된 완료 fixture로 조회·재확정 오류를 확인한다. 포인트 충전/조회 테스트는 그대로 보존한다. 테스트를 비활성화하거나 기준을 낮춰 통과시키지 않는다.
- 기존 IntegrationTest/E2ETest의 공통 spy 세트에 PaymentProviderGateway를 함께 추가하는 안을 제안한다. 클래스별 새 Mock/Spy 구성으로 컨텍스트를 늘리지 않는다. doReturn/doAnswer로 설정 중 실제 HTTP 호출을 방지한다. 관련 주문 테스트만 호출 결과를 제어하고 실제 adapter의 HTTP 계약 테스트는 유지한다.
- 포트 제어 테스트는 실제 PG가 항상 생성 시 PENDING을 응답한다는 계약을 따른다. 완료는 권위 조회/수동 대사/callback으로 별도 검증한다. 실제 PG 대신 mock만 통과한 것으로 최종 완료하지 않는다.
- application 효과 테스트는 실제 MySQL·기존 캐시 규칙을 사용한다. 주된 검증은 동시 confirm/create1, 같은 실패 반환1, 정상 성공·유실 후 실패, 저장 성공/업무 rollback, 복구 overflow→수동→원인 해소 후 재적용이다.
- HTTP는 본인/타인/관리자·기본 검증·200/202·callback 경계를 확인한다. 반영 완료+늦은 충돌의 200과 미반영+충돌의 202를 대표 쌍으로 확인한다. 수동/관찰의 동일 불변식을 계층마다 반복하지 않는다.
- 업무 실행 A의 rollback→수동 실행 B의 인계→A의 실패 표식 순서에서 B의 진행이 바뀌지 않는 대표 경합을 확인한다. READY에서 시작한 실패 표식도 당시 조건만 비교한다.
- 최종 실제 실험은 별도 새 MySQL 인스턴스의 두 스키마·별도 PG/commerce JVM을 사용한다. 기본 포트·007 validate, 정상 성공/유실 실패/같은 키 동시 요청의 거래 수·차감/반환 수·포인트0을 확인한다.
- PG 성공 후 내부 반영 실패·차감 후 상품 삭제·관리자 재고 변경·프로세스 종료/수동 대사의 대표 효과를 확인한다. 기존 source 변경이 생기지 않으면 R01 PG 전체 조건을 반복하지 않는다.
- 기능별 관련 검사만 실행하고 마지막에 `:apps:test-pg:check :apps:commerce-api:check`를 한 번 수행한다. 실패/새 변경 때문에 필요한 경우만 반복한다.
- 로컬 JDK21/Docker API 호환 임시 설정을 재사용하되 추적 대상 규칙을 완화하지 않는다. 실제 구현·검증 후 result.md를 작성한다.

## 8. 리뷰와 실제 진행 기록

- 확정 문답: 주문 준비/종결 CAS, 재고 실패 수동 처리, 고객 소유권/타인404, 관리자1, 동기 callback, 수동 최대 조회1.
- 이 계획: 세 관점 초안 리뷰의 UX 1건·기술 2건을 사용자가 함께 승인했고 위 계획에 반영했다. 세 관점 재리뷰에서도 중요 지적 없이 구현 진행 가능으로 확인했다. [리뷰 기록](review.md)을 따른다.
- 커밋 2: 네 주문 상태·기록 불변식·insert/준비 CAS/종결 CAS와 안전한 재고 반환을 구현했다. 새 PG 흐름은 아직 고객 API에 연결하지 않았으며 기존 POINT confirm/save는 유지한다. 기반의 기능 리뷰 세 관점 모두 중요 지적 없음이다. 관련 검사 결과는 [리뷰 기록](review.md)에 남긴다.
- 커밋 3: 준비 UseCase/조율자와 별도 TX 컴포넌트를 연결했다. 기존 상품 차감 서비스·상품 잠금·AFTER_COMMIT 이벤트를 재사용하고 모든 준비 기록을 같은 TX에 저장한다. 경합 패자는 새 TX에서 재판정하며 재요청에는 발송 권한을 다시 주지 않는다. 최소 관련 24건 통과, 세 관점 기능 리뷰 중요 지적 없음이다.
- 커밋 4: 공통 업무 TX·현재 재고 반환·실패 당시 snapshot CAS를 구현했다. 표식 저장 실패 뒤에도 새 상태 조회를 진행하는 중요 보완 1건을 사용자 승인으로 반영했고 세 관점 재리뷰 중요 지적 없음이다. 최종 핵심 10건과 Checkstyle을 통과했고 앞서 통과한 관련 20건을 포함해 중복 제외 30개 시나리오가 최종 통과 상태다. [리뷰 기록](review.md)을 따른다.
- 커밋 5: 고객 confirm을 준비 commit→TX 밖 최초 PG POST→관찰 commit→공통 업무 반영→새 응답 조회로 전환했다. 본인 헤더·합성 카드·시도 키·엄격한 테스트 옵션을 검증하며 완료 200/미완료 202와 주문·시도·조회 경로를 반환한다. 같은 요청은 PG create를 다시 보내지 않는다. 범용 OrderRepository.save와 기존 POINT 조율을 함께 제거하고 과거 POINT fixture·조회·재확정 오류는 유지했다. 관련 53개 시나리오·ArchUnit 8건·Checkstyle과 독립 PG/commerce의 정상 접수·확정 거절 흐름을 확인했다. 세 관점 기능 리뷰 중요 지적 없음이다. [리뷰 기록](review.md)을 따른다.
- 커밋 6: 고객 상세/목록·관리자 상세/목록에 결제 시도·복구 상태와 주의 표시를 연결했다. 고객 단건의 소유 조건·관리자 1번 fixture 확인과 미완료 목록을 구현했다. 관리자 조회 URL 분리는 중요 리뷰 보완으로 사용자 승인 후 반영했다. 관련 기능 28건·ArchUnit 8건·Checkstyle과 세 관점 재리뷰를 통과했다. [리뷰 기록](review.md)을 따른다.
- 커밋 7: 고객/관리자 수동 대사를 연결했다. 접근 확인 후 완료면 인계/조회 없이 반환하며 미완료는 상태·실행 ID·회차 CAS로 인계한다. 저장 종결/충돌은 조회 0회, 나머지는 시각 commit 후 PG 조회 최대 1회다. 자동 예산은 유지하며 늦은 진행·실패 표시는 당시 수동 실행 snapshot으로만 갱신한다. 관련 기능 11건·ArchUnit 8건·Checkstyle과 세 관점 기능 리뷰를 통과했다. [리뷰 기록](review.md)을 따른다.
- 커밋 8: 동기 callback을 등록 시도/키 확인→TX 밖 PG 권위 조회→알림 키 대조→관찰 commit→공통 업무 반영→최신 완료 상태 응답으로 연결했다. 본문 status는 알림용이며 결과를 결정하지 않는다. callback은 작업을 인계하거나 회차를 소비하지 않고 초기 snapshot으로 늦은 업무 실패 표시를 보호한다. 관련 HTTP/MySQL 8건·ArchUnit 8건·Checkstyle과 세 관점 기능 리뷰를 통과했다. [리뷰 기록](review.md)을 따른다.
- 커밋 9: 별도 PG/commerce JVM과 전용 MySQL에서 실제 주문·장애/재시작 실험 10개를 통과했다. 필수 전체 check는 PG 25건·commerce 407건, 총 432건이며 실패·오류·건너뛰기 0건이다. 기능 수정 없이 [result](result.md)와 [재현 방법](verification.md)을 정리했다. 기존 POINT·JMeter·원문을 보존했고 임시 trigger와 실행 프로세스도 정리했다. 기능 변경 없는 검증·문서는 기존 규칙대로 별도 기능 리뷰를 추가하지 않았다.
- 기능 코드 검증 기준은 동기 callback 커밋 `73bc6cf`다. 이후 최종 변경은 검증 기록·문서다. R02는 로컬 완료이며 원격 게시/PR 생성은 별도 요청 범위다. 자동 조회·회로·fallback은 R03이다.
