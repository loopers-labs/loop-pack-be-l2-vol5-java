# 커머스 TDD 계획

[전체 설계](commerce-erd-draft.md) · [API 계약](commerce-api-contract.md) · [정책 선택과 설계 검토](commerce-policy-decisions.md)

재고·사용자 식별·브랜드·주문 수량 규칙과 API-01~24의 기대값, 작은 기능의 구현 순서를 다룬다. 각 절은 도메인 단위 검증, application·실제 DB 연결, HTTP 완료를 구분한다. 최신 완료 증거와 전체 검사 결과는 [전체 완료 체크리스트](commerce-completion-checklist.md)에 모으고, 실제 Red·Green·Refactor는 기능별 실행 기록에 남긴다. 기존 응답·금액·잠금 정책은 후속 추천 묶음 승인(P08·P12·P13)으로 확정한 선행 계약이다. W5에서 명시한 결제 경계의 변경은 아래 W5 계획을 우선하고, 변경 대상이 아닌 계약은 유지한다.

## W5 PG 결제 TDD 계획

**2026-10-23 현재 상태: 고객 confirm·주문 조회의 PG 연결과 최초 요청의 제한 조회·조회 회로 연결.** 고객 confirm은 신규 지문 공동 저장·동일 재요청 비교에 이어 TX A → TX 밖 PG → TX B를 사용하고, 고객·관리자 조회는 저장된 주문·결제 상태를 함께 읽는다. 최초 발송 실행만 요청 진입부터 180초 목표/175초 외부 예산·최대 12회 조회를 사용하며 동일 재요청은 긴 루프를 만들지 않는다. **callback·수동 reconcile HTTP도 연결했으며, 실제 요청의 180초 하드 상한이나 5초 내 DB 커밋 보장을 주장하지 않는다.** 이번 검증 결과와 한계는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 따른다. [고객 결제 API TDD 기록](../week5/customer-payment-api-tdd-log.md)은 선행 confirm·조회 증거로 보존한다.

[예약 enum 12개](../week5/order-reservation-state-tdd-log.md)·[PaymentAttempt 81개와 당시 회귀 151개](../week5/payment-attempt-state-tdd-log.md), [주문·예약·JPA·009/010 SQL·POINT 이행](../week5/order-payment-persistence-tdd-log.md), [재고](../week5/order-stock-reservation-tdd-log.md)·[PG HTTP](../week5/local-test-pg-http-tdd-log.md)·[내부 HTTP 연결](../week5/order-payment-http-connection-tdd-log.md)은 선행 증거로 보존한다. [HMAC 순수 계산·비교의 신규 54개](../week5/payment-request-fingerprint-tdd-log.md), [지문·버전 저장](../week5/payment-request-fingerprint-persistence-tdd-log.md), [환경변수 키 공급](../week5/payment-fingerprint-key-configuration-tdd-log.md), [주문 지문 연결](../week5/order-payment-fingerprint-connection-tdd-log.md), [고객 계약 경계](../week5/customer-payment-contract-tdd-log.md)도 각 증분의 당시 범위로 구분한다. 과제 해석 확인은 선택 사항이고 구현 착수의 필수 선행 조건이 아니다. 승인한 저장 A안·UUID·PG 규격 카드 입력·시간/회로 초기값·커밋 승자만 발송하는 규칙을 유지한다. 아래 계획 자체나 이전 주의 통과 수는 W5 전체 완료 증거가 아니다. 근거는 [과제 원문](../week5/assignment-original.md)·[9개 채점 기준](../week5/grading-criteria.md)이며 [전체 설계](commerce-erd-draft.md#w5-pg-결제-설계)·[API](commerce-api-contract.md)·[정책](commerce-policy-decisions.md#w5-결제-교체의-선택과-질문)을 함께 따른다.

### 6단계 진행 순서와 변경 책임

선행 저장 연결 증분의 최종 전체 검사는 **2026-10-23 11:01 KST, 88개 스위트·992개 통과**였다. 실패·오류·skip 0, ArchUnit·전체 Java 모듈 Checkstyle 위반 0의 [당시 기록](../week5/order-payment-persistence-tdd-log.md#최종-실행-결과)을 보존한다. 선행 HTTP 검사는 [HTTP 기록](../week5/local-test-pg-http-tdd-log.md), 내부 재고·TX 증분 검사는 [재고 기록](../week5/order-stock-reservation-tdd-log.md), 내부 실제 HTTP·DB 연결은 [선행 연결 기록](../week5/order-payment-http-connection-tdd-log.md), 최신 고객 연결은 [고객 API 기록](../week5/customer-payment-api-tdd-log.md)을 따른다.

한 단계 전체를 한 번에 구현하지 않고, 아래 사례 중 작은 행동 하나의 Red → Green → Refactor를 마친 뒤 확장한다. 승인한 저장 책임·대기 설정도 실제 구현과 구분하며, 남은 callback ACK·충돌 기록·callback/reconcile 요청 예산을 임의로 확정하지 않는다.

| 단계 | 먼저 정리·검증할 것 | 변경 후보 책임·파일 범위 | 공식 채점 연결·현재 상태 |
| --- | --- | --- | --- |
| 1. 설계·계약 | 과거 POINT와 신규 PG의 구분, 상태·식별자·예약·TX A/HTTP/TX B, 아래 테스트와 미정 질문 | 전체 설계·API 계약·정책 선택·이 TDD 계획 | 1~8의 검증 계획. 남은 정책·기술 선택을 구분하며 해당 구현 전에 확인 |
| 2. 모델·DB·POINT 이행 | 허용 상태 전이, 주문당 한 시도·요청 일치·발송 권한, 예약 수량과 이전 POINT 기록 보존 | `Order`·`OrderStatus`·`OrderPaymentMethod`·별도 `PaymentAttempt`·기존 `OrderItem`, 저장소·JPA·009/010/011 SQL·스키마 테스트 | 모델·저장·격리 DB의 POINT 이행·HMAC 지문/버전·기존 NULL 보존·키 공급을 구현·검증했다. 내부 TX A/B·실제 재고·고객 HTTP 연결은 4단계 증거로 구분한다. 사용자 DB에는 미적용. 별도 StockReservation 없음 |
| 3. 실제 HTTP fixture·adapter | 작은 로컬 서버의 통제된 거래·호출 수와 세 요청의 실제 형식, strict 422·불확실 응답 구분 | `PaymentGateway`·`PaymentCreateRequest`·`PaymentGatewayResult`, `HttpTestPgClient`·로컬 설정, JDK `LocalTestPg`·실제 HTTP 계약/timeout 테스트 | 독립 HTTP 하위 범위 구현·검증. [실행 기록](../week5/local-test-pg-http-tdd-log.md) 참조. 예약 효과·고객 confirm은 후속 연결 기록으로 구분하며 callback HTTP도 연결했으며 검증 결과는 최신 복구 기록 참조 |
| 4. 기본 결제 흐름 | TX A의 사전 검사·재고 확보·시도 저장 → TX 밖 PG → TX B의 결과 적용, 포인트 변화 0 | `OrderPaymentCoordinator.startAndAwait`·`OrderPaymentStockService`·고객 confirm·`OrderPaymentQueryService`·고객/관리자 응답 | 고객 confirm의 PG/HMAC 연결과 저장 상태 조회를 구현. POINT 재확정409·소유권·조회 사실 보존. 실제 HTTP·DB·조회 및 회귀 결과는 [최신 연결 기록](../week5/customer-payment-api-tdd-log.md) 참조 |
| 5. 결과 복구·조회 보호 | callback·수동 reconcile 공통 결과 적용, 응답 유실·재시작·중복·저장 실패, 전체 예산·조회 회로 차단 | 내부 `refreshOnce`, `PaymentConfirmationDeadline`·`PaymentConfirmationWaiter`, 조회 회로와 실제 HTTP client 연결. callback/reconcile API도 연결 | 최초 요청 180초 목표/175초 외부 예산·최대12회·조회 회로를 연결했다. callback/reconcile ACK·단건 GET/10초 예산은 위임 선택 완료, HTTP를 연결했으며 검증 결과는 최신 복구 기록으로 구분. 제어 시계 검증과 실제 180초 하드 상한 검증은 구분 |
| 6. 관련 회귀·증거 정리 | 기존 POINT·포인트 API·소유권·삭제·관리자 재고·캐시, 실제 SQL 롤백·경합, Checkstyle·ArchUnit | 영향받은 기존 테스트·W5 통합/HTTP 테스트·실행 기록·완료 체크리스트 | 각 증분 회귀는 실행 기록으로 구분. 최종 PG 교체 통합 검증·PR·기술 글의 작성/제출은 별도 상태 |

고객 confirm은 기존 `OrderService.confirm()` 안에 PG 호출을 추가하는 방식이 아니라 coordinator의 별도 TX A/B 프록시를 사용한다. 외부 HTTP·조회 대기 중에는 기존 쓰기 트랜잭션과 잠금을 유지하지 않는다. **사용자 승인한 발송 보호:** PG 호출 전에 발송 시작 상태를 원자적으로 변경하고 커밋한 그 실행만 최초 POST할 수 있다. 전이 또는 커밋 실패는 POST하지 않고, 커밋 후 생성 호출 예산이 부족해도 자동 재발송하지 않는다. 로컬 발송 시작은 외부 PG의 PENDING 수락과 구분하며 UNKNOWN·재시작 뒤에도 보존한다. 고객 동일 요청은 지문 비교 뒤 현재 상태를 반환하고 새 PG 생성·긴 대기를 만들지 않는다. 별도 발송 권한 테이블·claim TX는 추가하지 않았다.

**2026-10-23 사용자 선택 W5-Q08:** 준비와 발송 시작 기록을 **TX A에 함께** commit한다. 재고 예약·시도·주문 대기·발송 기록·목록 버전의 공동 저장/rollback과 커밋 승자만 최초 POST하는 기대를 고정하며 별도 발송 선점 TX는 추가하지 않는다. [선택 이유·미채택 대안](commerce-policy-decisions.md#발송-시작-기록의-tx-배치-비교)을 보존한다. 카드 입력은 로컬 PG 규격의 합성 값을 해당 실행에서만 사용하고 DB·로그에는 저장하지 않는다. 특정 예시 번호만 허용하는 제한은 추가하지 않는다. D10의 HMAC 계산·비교·TX A 공동 저장도 고객 confirm에 연결했다. 담당자 사전 승인을 필수 조건으로 두지 않는다. 기존 잠금 순서는 주문 → 필요한 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순 → 필요한 시도 → 목록 revision 마지막으로 유지한다. 동일 요청 비교는 주문·시도만 잠그고 현재 상품을 다시 계산하지 않는다. 관리자 HELD 합계는 상품 잠금 아래 비잠금 집계로 읽으며 예약·반환도 같은 상품 잠금에 참여한다. 포인트 차감 제거가 재고·주문 보호까지 제거한다는 뜻은 아니다.

**2026-10-23 HMAC 계산·비교·저장·환경변수 키 공급·내부 TX·고객 confirm 연결.** [W5-D10](commerce-policy-decisions.md#w5-hmac-요청-지문-제안)에 따라 최초/재요청 검증 중 카드 입력을 일시 사용하고 발송 권한을 얻은 실행만 PG에 전달한다. 카드 유래 지문 저장에 관한 과제 해석 질문은 Q04의 선택적 보완 사항으로 남기며 구현을 막는 조건으로 두지 않는다. [계산](../week5/payment-request-fingerprint-tdd-log.md)·[저장](../week5/payment-request-fingerprint-persistence-tdd-log.md)의 선행 증거와 [고객 연결](../week5/customer-payment-api-tdd-log.md)의 최신 증거를 구분한다. 이를 담당자의 공식 승인이나 W5 전체 완료로 해석하지 않는다.

**W5-Q07 승인 구조:** 결제 시도는 별도 PaymentAttempt에 두고, 예약은 기존 OrderItem의 불변 `quantity`와 `NOT_RESERVED → HELD → CONSUMED 또는 RELEASED` 상태로 표현한다. 아래의 예약 수·예약 기록은 이 논리적 품목 예약을 가리키며 별도 StockReservation 행을 요구하지 않는다. 기존 POINT·기존/신규 DRAFT는 W5 예약이 없는 NOT_RESERVED에서 시작한다. 과거 POINT를 미예약으로 이행한다는 것은 당시 결제·재고 차감 사실을 없애는 뜻이 아니다.

### 공통 관찰값과 테스트 경계

**4단계 첫 증분 당시 범위:** `OrderPaymentStockService.prepareNew`는 DRAFT 신규 준비만 수행하며 기존 시도 재호출은 추가 확보 없이 내부 오류로 거절했다. 당시 카드 입력 비교·HMAC·고객 confirm 재요청은 연결하지 않았다. 내부 TX A/B의 JDBC 결과·롤백·경합은 [재고 실행 기록](../week5/order-stock-reservation-tdd-log.md)에서 검증하며 후속 실제 PG·고객 HTTP 증거와 구분한다. 현재도 기존 REQUIRED·READ_COMMITTED를 유지하므로 외부 TX에 참여한 메서드 반환을 커밋 증거로 보지 않는다.

**선행 내부 HTTP 연결 증분 당시 범위:** `startNew`는 활성 외부 TX를 거절하고 별도 프록시 준비 커밋 후 생성 1회·TX B를 실행한다. `refreshOnce`는 소유권·시도와 저장 식별자로 조회 최대 1회·TX B를 실행하며 종결 시도는 HTTP 없이 반환한다. 신규 전용 메서드의 거절은 고객 동일 confirm의 응답 계약이 아니고 단건 조회도 고객 reconcile 또는 최초 180초 루프가 아니다. 당시 후속이던 HMAC·고객 DTO/confirm·제한 조회·회로는 각 후속 증분에 연결했다. callback/reconcile HTTP도 연결했으며 이번 검증 결과는 최신 복구 기록으로 구분한다. [선행 HTTP·DB 기록](../week5/order-payment-http-connection-tdd-log.md)의 당시 검사를 최신 고객 API 검사로 합산하지 않는다.

| 구분 | 검증 기준 |
| --- | --- |
| 고정 수량 장면 | 다른 재고 변경이 없는 상품 재고 5·주문 수량 2. 확보 후 가용 3, SUCCESS 후 가용 3·예약 소비 1회, 확정 FAILED 후 가용 5·반환 1회. UNKNOWN/PENDING은 가용 3·예약 유지. 모든 PG 경로의 포인트 변화는 0 |
| 비교할 값 | HTTP 상태·주문/시도 상태·조회 경로뿐 아니라 외부 거래 수·클라이언트 create/lookup 시도 수·fixture에 실제 도달한 호출 수·내부 시도 수·품목별 예약 상태/수량·가용 재고·포인트·품목 스냅샷을 대조. 연결 실패와 CB 차단은 서버 도달 수가 같아도 다른 사건으로 기록 |
| 테스트 계층 | 순수 상태 전이는 단위 테스트, 실제 HTTP 형식/timeout은 adapter→로컬 HTTP 서버, DB 제약·롤백·경합은 기존 MySQL fixture, callback·접근 제어는 실제 controller 경유. 같은 모든 사례를 모든 계층에 반복하지 않음 |
| 데이터 분리 | 기존 확정 POINT fixture, 교체 이전부터 존재한 DRAFT fixture, 새 DRAFT fixture를 따로 준비. 이전 DRAFT도 새 결제를 시작할 때 PG를 사용하고, POINT 확정 주문에는 새 시도·거래를 만들지 않음 |
| 결과 판정 | PENDING 수락은 성공 결제 아님. timeout·일반 4xx/5xx·조회 404/0건은 효과 0의 증거 아님. 종결된 결과를 늦은 PENDING/UNKNOWN/상충 결과로 덮어쓰지 않음 |

PG fixture는 [직접 준비한 로컬 HTTP 테스트 장치](commerce-erd-draft.md#이번-과제의-테스트-pg는-무엇인가)다. 업체 테스트 계정·API 키 없이 테스트 JVM 안의 JDK HttpServer가 실제 Apache HttpClient 요청을 받는다. 3단계는 독립 거래 저장·응답 유실·클라이언트 재생성과 조회를 검증했으며 주문·예약 DB 효과는 포함하지 않았다. 후속 내부 연결은 같은 DB·PG 거래를 유지하며 client·조율 객체를 재생성하는 W5-TDD-12·16 하위 범위를 다룬다. 고객 API와 전체 복구 완료 여부는 별도이며 메서드 mock만으로 실제 HTTP·DB 검증을 대신하지 않는다.

**3단계 선행 증거의 경계:** W5-TDD-04의 생성/조회 형식과 URL·비노출, 05·06의 HTTP 분류, 12·16·17의 외부 거래/조회 부분, 27의 응답·풀·전체 호출 취소 부분을 [HTTP 기록](../week5/local-test-pg-http-tdd-log.md)에 연결한다. 당시 strict 422의 외부 거래 0건과 응답 유실의 거래 1건·재발송 0회는 검증했지만 실제 예약 반환/유지는 포함하지 않았다. 연결 3초는 설정 검증과 네트워크 지연 실측을 구분한다. 당시 미구현이던 180/175초 예산·12회 조회·회로는 [후속 고객 연결](../week5/customer-payment-api-tdd-log.md)의 별도 증거다.

### 주문·결제 상태 전이의 테스트 기대값

**2026-10-23 계획 작성 후 주문·시도 모델과 DB 연결 증분 구현.** [ORD/SEND/PAY 상태 전이표](commerce-erd-draft.md#주문결제-시도의-상태-전이표)를 기존 W5-TDD-01·02 및 관련 통합 사례로 구체화한다. 아래 ID는 시나리오 이름이지 새 채점 항목이나 구현한 테스트 수가 아니다. NOT_OBSERVED·sendStartedAt의 JPA·SQL과 [Q08](commerce-policy-decisions.md#발송-시작-기록의-tx-배치-비교)의 실제 재고·발송 시작 기록 공동 저장 TX A를 구현했다. 커밋 뒤 실제 발송은 [내부 HTTP 연결 기록](../week5/order-payment-http-connection-tdd-log.md), 고객 HTTP 표현·최초 대기는 [최신 고객 연결 기록](../week5/customer-payment-api-tdd-log.md)으로 구분한다. [선행 저장 기록](../week5/order-payment-persistence-tdd-log.md)과 [내부 서비스 기록](../week5/order-stock-reservation-tdd-log.md)의 당시 증거는 보존한다.

#### 먼저 할 순수 상태·모델 테스트

DB·PG 없이 상태와 불변 값, 허용/금지 행동을 검증한다. Order의 중복 변경 메서드를 무조건 성공으로 바꾸는 것이 아니라 **같은 PG 결과를 적용하는 경계에서 추가 변경이 없는지**를 나누어 확인한다. 실제 재고·커밋·HTTP 보장은 다음 통합 테스트에 맡긴다.

| ID · 연결 | 준비·실행 | assertion할 기대값 |
| --- | --- | --- |
| W5-STATE-01 · ORD-01·02 | 새/기존 DRAFT에서 PG 준비 전이를 호출. DRAFT와 추가 3개 주문 상태에 requireDraft 검사를 각각 수행 | 준비 후 PAYMENT_PENDING, 주문 금액·품목 수량/단가 불변, paidAmount/confirmedAt 없음. requireDraft는 DRAFT만 허용. 모델 테스트를 TX A rollback 증거로 세지 않음 |
| W5-STATE-02 · ORD-04 | PAYMENT_PENDING에서 검증된 성공 적용, 다른 시각으로 같은 성공 재적용 | CONFIRMED·paidAmount=저장 totalAmount·최초 확정 시각 유지. 중복이 최초 값을 덮어쓰지 않음 |
| W5-STATE-03 · ORD-05 | PAYMENT_PENDING에서 검증된 실패 적용·중복 적용 | PAYMENT_FAILED, 성공 paidAmount/confirmedAt 없음. 실패 금액을 0으로 만들어 성공 기록처럼 표현하지 않음 |
| W5-STATE-04 · ORD 금지 전이 | DRAFT에서 PG 결과 직행, 종결→DRAFT/반대 종결, 대기/실패 주문의 신규 시작 시도 | 금지한 상태 변경·두 번째 시도 허용 없음. 내부 금지 전이와 외부 오류 HTTP 매핑은 구분 |
| W5-STATE-05 · PAY-01·SEND-01~04 | 새 시도의 초기 상태, 발송 선점 행동 2회, 이후 모든 결과 상태에서 시작 이력 조회 | 초기 NOT_OBSERVED·PG 관찰 없음. 최초 선점만 상태를 바꾸며 반복 선점은 권한 없음. 최초 발송 시각·주문/키/amount 불변. 단위 테스트에서 DB commit이나 동시 POST 1회를 증명했다고 하지 않음 |
| W5-STATE-06 · PAY-02·03 | 발송을 시작한 미완료 시도 각각에 유효 PENDING과 확인 불가를 적용. PENDING→UNKNOWN→같은 거래 PENDING 순서도 수행 | PENDING/UNKNOWN 의미 구분, 기존 거래 키·발송 이력 보존. 주문 PAYMENT_PENDING·예약 HELD를 유지할 결과를 반환. 조회 404/0건과 일반 오류는 FAILED 아님 |
| W5-STATE-07 · PAY-04~06 | 미완료 상태 각각에서 같은 거래의 SUCCESS/FAILED, 별도로 엄격한 효과 0건 거절 적용 | SUCCESS 또는 FAILED로 종결할 수 있음. 일반 거래 결과는 검증된 transactionKey 필요, 효과 0건 거절은 null 허용. 이미 확인한 거래가 있는데 효과 0건이라면 충돌·기존 상태 불변 |
| W5-STATE-08 · PAY-07·08 | SUCCESS/FAILED 각각에 동일 종결, 유효한 늦은 PENDING, timeout/조회 불가 적용 | 같은 종결은 추가 효과 없음. 늦은 관찰은 종결·최초 금액/시각·거래 키·발송 이력을 바꾸지 않음 |
| W5-STATE-09 · PAY-09·거래 연결 | 같은 결과 문자열이지만 계정/주문/거래/금액이 각각 다른 응답, 반대 종결·복수 거래를 구성. null 거래 키의 최초 연결·같은 키 재연결·다른 키 교체도 구분 | 불일치·모순은 충돌, 기존 상태·식별·금액 불변. 유효한 최초 연결/같은 키만 허용. 충돌 저장 형식·HTTP는 Q05 결정 후 추가 |
| W5-STATE-10 · PAY-10 | 상태 읽기·동일 confirm 저장 결과 반환·시간 예산 소진만 발생, 별도로 발송 미시작 상태에 PG 결과 적용 시도 | 읽기·시간 경과만으로 결과를 새로 만들지 않음. 발송 미시작 결과 적용은 거절. 대기/미확인 결과의 예약 반환·새 생성 허용 없음 |

`PaymentAttempt`의 발송 이력·관찰 규칙은 [선행 81개 단위 테스트](../week5/payment-attempt-state-tdd-log.md)로 구현했다. 이후 Order·OrderStatus·OrderItem 행동과 JPA·SQL 이행을 연결했고 당시 관리자 필터는 기존 두 상태로 보호했다. 최신 고객·관리자 조회 연결에서는 PAYMENT_PENDING·PAYMENT_FAILED 필터도 확장했다. W5-STATE-05~10의 모델·저장 증거와 실제 HTTP·고객 조회 증거를 구분하며 raw callback의 실제 controller·권위 조회 검증은 최신 복구 기록으로 구분한다.

#### 실제 DB·HTTP 연결 때 검증할 결과

고정 장면은 **가용 재고 5, 주문 수량 2, 포인트 잔액 10,000**이고 다른 재고·포인트 변경이 없다고 둔다. 상태 문자열뿐 아니라 아래 수치·시각·식별 관계를 **TX 종료 뒤 별도 DB 조회**로 대조한다. 실제 SQL 뒤 실패 주입·worker 순서 제어로 검증하며 sleep이나 테스트 외곽 rollback만으로 대신하지 않는다.

| ID · 기존 계획 연결 | 어떻게 시험할지 | 최종 DB·외부 효과 기대값 |
| --- | --- | --- |
| W5-STATE-11 · TDD-07·08 | 준비와 발송 시작을 함께 저장한 TX A 커밋을 PG 호출 전에 별도 연결로 조회. 별도 사례에서 발송 기록을 포함한 실제 일부 저장 후 TX A 실패 주입 | 성공: PAYMENT_PENDING·시도 1·발송 시작 기록 있음·품목 HELD·가용 3·관련 목록 버전 증가. 실패: DRAFT·시도/발송 기록 0·NOT_RESERVED·가용 5·목록 버전 원복·create 0. 포인트는 둘 다 10,000. 준비만 커밋되고 발송 미시작으로 남는 중간 단계 없음 |
| W5-STATE-12 · TDD-05·09 | 검증된 SUCCESS/FAILED·효과 0건 거절을 각각 적용하고 반복 | 성공: CONFIRMED/SUCCESS/CONSUMED·가용 3. 실패: PAYMENT_FAILED/FAILED/RELEASED·가용 5(7 아님). 효과 0건 거절은 외부 거래 0·transactionKey 없음. 모든 경로 포인트 10,000 |
| W5-STATE-13 · TDD-10·11·16 | 독립 연결의 동시 confirm, TX A 커밋 전/후 HTTP 진입 통제, 준비·발송 기록 공동 커밋 직후 HTTP 전 중단 후 서비스 재생성 | 실제 호출까지 진행한 동시 정상 장면은 시도 1·create 1·거래 1·가용 3. 중단 장면은 거래 0건이어도 재시작 POST 0·발송 기록/HELD 유지. 커밋 미확인 실행도 POST 0. PG 대기 중 TX/업무 락 없음. 테스트는 실제 HTTP 0을 관찰하지만 재시작 실행은 저장 기록·조회 0건만으로 미발송을 확정하지 않음 |
| W5-STATE-14 · TDD-12~14·17 | 거래 생성 뒤 응답 유실, callback-first/lookup-first/동시 적용, 본문만 SUCCESS인 가짜 알림·다른 거래 결과 주입 | 유실 후 UNKNOWN·HELD·가용 3, 같은 거래로 복구·create 추가 0. 진짜 SUCCESS는 한 번 확정, FAILED는 한 번 반환. 알림 본문/불일치 결과는 주문을 변경하지 않음. 첫 연결 거래 키·종결 결과 불변 |
| W5-STATE-15 · TDD-15 | **외부 거래가 존재하는 SUCCESS/FAILED**의 TX B에서 각각 일부 실제 SQL 성공 뒤 다음 저장 실패. 실패 해제 뒤 같은 거래 재조회 | 시도·주문·예약·재고·변경한 목록 버전 전부 TX B 진입 전 값으로 rollback, PG 거래 결과는 남음. 재적용 후 성공 가용 3/실패 가용 5·create 추가 0. 상태만 종결됐거나 품목 일부만 소비/반환된 행 없음 |
| W5-STATE-16 · TDD-20~24 | POINT 과거 주문 조회/재확정, 예약 전/후 삭제, 관리자 가용 설정·캐시 변경, PG 중 포인트 충전 | POINT 원래 사실과 본인 재확정409 유지. 예약 전 삭제 create 0; 예약 후 종결 가능·비노출 유지. 가용3·HELD2에서 관리자 가용10 설정 뒤 성공10/실패12. 재고 변경과 캐시 버전 원자성, PG 포인트 차감0·기존 충전 독립 유지 |

W5-STATE-15의 추가 반례는 **효과 0건 거절의 TX B rollback 뒤 프로세스 재생성으로 원응답 증거도 잃는 경우**다. 이때 fixture 거래는 0건이며, 후속 조회 0건으로 실패를 복원하지 못한다. 내부 미완료·UNKNOWN/HELD·가용 3·재발송 0을 기대한다. 조회 가능한 거래의 FAILED와 달리 자동 종결을 보장할 수 없으며, 거절 증거의 별도 저장 체계를 채택한 것으로 간주하지 않는다.

실제 HTTP의 200/202·소유권404·POINT 재확정409와 PG 세 요청 형식은 API 계약대로 검증한다. 고객 confirm의 요청 충돌409·과거 무지문409·서버 검증 불가503·손상500은 사용자 선택 계약을 따른다. **callback ACK·충돌 기록은 후속 위임 선택 REC-D03·05**를 따르며 실제 HTTP 연결 증거는 아래 복구 증분으로 구분한다. strict 422는 실제 adapter(W5-TDD-05·06), HMAC 요청 동일성·오류 응답은 최신 고객 API 증거로 연결한다. 담당자 확인은 선택 사항이며 표 작성 자체를 테스트 통과로 보지 않는다.

### 구체 테스트 사례

W5-TDD-01의 모델과 W5-TDD-02·03의 저장·이행 증거는 [선행 기록](../week5/order-payment-persistence-tdd-log.md), 실제 재고·내부 TX·DB 경합은 [재고 기록](../week5/order-stock-reservation-tdd-log.md), 내부 PG 연결은 [HTTP·DB 기록](../week5/order-payment-http-connection-tdd-log.md), 고객 confirm·조회·제한 조회/회로는 [최신 기록](../week5/customer-payment-api-tdd-log.md)을 따른다. callback/reconcile HTTP까지의 복구 통합 완료와 구분한다. 최초 대기·조회·회로 수치는 [승인한 초기 설정](commerce-policy-decisions.md#w5-최초-결제-요청의-대기조회회로-설정)을 단일 근거로 유지한다. 후속 위임 선택한 callback ACK·충돌 구조 로그·단건 복구 예산은 아래 W5-REC 계획의 기대값으로 유지하고 실제 통과 결과와 구분한다.

| 사례 ID | 준비·실행 방법 | 기대 결과·남길 증거 |
| --- | --- | --- |
| W5-TDD-01 | 주문·PaymentAttempt·OrderItem 예약의 단위 상태 전이를 표로 만들고 같은 관찰을 두 번, 종결 뒤 PENDING/UNKNOWN과 반대 최종 결과를 적용 | 품목의 불변 quantity로 NOT_RESERVED→HELD→CONSUMED/RELEASED를 관리. PENDING/UNKNOWN은 미확정·HELD 유지, SUCCESS는 확정·소비, FAILED는 실패·반환. 중복 효과·종결 덮어쓰기 금지. **Order·OrderItem 연결과 requireDraft 확장·결제수단 null 경계도 구현.** 선행 [저장](../week5/order-payment-persistence-tdd-log.md)·[재고](../week5/order-stock-reservation-tdd-log.md)·[내부 HTTP](../week5/order-payment-http-connection-tdd-log.md) 증거와 최신 [고객 API 기록](../week5/customer-payment-api-tdd-log.md)을 구분. callback HTTP도 연결했으며 검증 결과는 최신 복구 기록 참조 |
| W5-TDD-02 | 호출자 UUID 입력을 검증하고, 독립 MySQL 트랜잭션에서 같은 주문의 새 키·같은 키의 다른 주문/요청, 발송 시작 상태의 중복 선점과 커밋 실패를 실행 | UUID 키는 서비스 전체에서 중복 보호하고 주문당 한 PaymentAttempt를 유지. DB 제약·원자적 전이를 성공시키고 커밋한 실행만 발송 가능하며 단순 상태 읽기·전이/커밋 실패는 권한 없음. 품목별 예약 상태와 가용 수량은 같은 TX에서 반영하고 중복 확보하지 않음. 요청 불일치는 충돌이며 메모리 Set만으로 통과하지 않음. HMAC-SHA256 계산·저장·TX A·고객 HTTP의 증거를 각 실행 기록으로 구분한다. W5-Q04의 선택적 해석 확인은 선행 조건이 아님 |
| W5-TDD-03 | 기존 스키마의 POINT 확정·DRAFT 및 품목/잔액 fixture를 준비한 뒤 이행 SQL 적용·JPA validate·별도 재조회 | 기존 확정 주문은 POINT·금액·결과·시각·품목 사실을 보존. PG 거래를 꾸며 넣지 않음. 기존 POINT·DRAFT 품목의 예약 상태는 NOT_RESERVED이며 기존 DRAFT는 PG 시작 대상. 별도 시도와 기존 품목 예약 상태/식별 관계의 DB 제약·수량 불변을 검증. W5-Q01의 실제 데이터 유무는 미확인이므로 이행 전에 읽기 전용 확인·보존 계획을 세우며 DB 초기화하지 않음 |
| W5-TDD-04 | 실제 adapter가 과제 로컬 PG 규격의 합성 cardType/cardNo를 사용해 생성·transactionKey 조회·providerOrderId 목록 조회를 요청 | 정확한 method/path, 세 요청 모두 `X-USER-ID: 135135`, 문자열 orderId·정수 amount·동일한 주문 매핑, 카드 입력과 JSON 타입, URI builder 인코딩, 고정 callback URL 확인. 생성 202·조회 200/404·빈 배열·외부 상태 매핑 검증. 고객 입력으로 외부 URL을 바꾸지 못하고 합성 카드 번호가 DB·로그에 남지 않음. 원문의 예시 번호를 유일한 허용 값으로 바꾸지 않고 실제 카드·업체 PG를 사용하지 않음 |
| W5-TDD-05 | fixture가 거래 저장·처리 예약 전에 strict 422로 종료하고 같은 거절 관찰을 중복 적용 | `422 + TEST_PG_REJECTED_BEFORE_CREATE + boolean false + 요청 orderId/amount 일치`를 모두 충족할 때만 REJECTED_NO_EFFECT. 외부 거래 0·FAILED·예약 반환 1회·가용 5, 꾸민 transactionKey 없음. 이후에도 fixture가 해당 거래를 만들지 않음을 확인 |
| W5-TDD-06 | 본문 없는 422, 다른 code, 문자열 `"false"`/true, 잘못된 JSON, 주문/금액 불일치, 일반 4xx/5xx를 각각 반환 | 확정 거절로 오인하지 않음. 다른 확정 결과가 없다면 UNKNOWN·예약 유지, create 추가 0. 이미 종결했다면 그 결과 보존. 상태 코드만으로 반환하지 않음 |
| W5-TDD-07 | 실제 TX A에서 소유권·입력·삭제·재고/저장 수량 실패를 만들고, 여러 품목 중 일부 SQL 실행 후 다음 저장 실패 주입. 발송 시작 기록 flush 후 후속 실패도 포함 | PG create 0·시도/예약/주문 대기/발송 시작 기록 및 해당 요청의 재고·목록 버전 변경 전체 rollback. 종료 후 별도 DB 조회로 확인. TX A가 전부 취소된 DRAFT는 조건 개선 후 다시 시작할 수 있으며, 이미 생성한 시도의 최종 실패와 구분 |
| W5-TDD-08 | 기존/신규 DRAFT의 confirm에 실제 PG 생성 수락 PENDING을 반환하고, 승인한 제한 조회에서도 유효한 PENDING을 유지한 뒤 주문 GET 수행 | 시도 1·해당 품목의 HELD 예약 1, 가용 3, PAYMENT_PENDING·결제 PENDING·포인트 변화 0. 제한 조회의 횟수/시간 예산이 끝나도 결제 실패가 아니라 202·상태·조회 경로 반환. 도중 권위 조회에서 종결되면 저장한 결과로 조기 종료하는 장면은 W5-TDD-09/32로 구분. 일반 주문 GET의 create/외부 효과 0 |
| W5-TDD-09 | 확보된 같은 거래를 권위 조회해 SUCCESS 또는 FAILED로 종결하고 동일 결과를 반복 적용 | SUCCESS: CONFIRMED·저장 금액·예약 소비 1·가용 3. FAILED: PAYMENT_FAILED·예약 반환 1·가용 5. 추가 차감·중복 반환 0, 모든 PG 경로의 포인트 변화 0. 같은 종결 시도의 재요청은 저장 결과 반환, 다른 키로 재결제 불가 |
| W5-TDD-10 | 같은 주문·같은 키의 confirm을 독립 worker로 동시에 호출하고, 승자의 커밋 뒤 실제 HTTP 전 다른 실행이 발송 시작 상태를 읽게 통제. 다른 키/불일치 요청도 별도 실행 | 시도·예약 각 1, 발송 시작 전이를 성공시키고 커밋한 실행의 실제 create 1·외부 거래 1. 같은 상태를 읽은 다른 실행은 저장 상태만 반환하고 추가 POST 없음. DB 선점 전 또는 커밋 전에는 HTTP가 시작되지 않으며 로컬 발송 시작만으로 외부 PENDING 수락을 꾸미지 않음. 동시 요청의 허용 응답은 관찰 시점의 상태와 일치. 기술 오류를 정상 충돌이나 성공으로 세지 않음 |
| W5-TDD-11 | PG create/lookup 진입을 fixture에서 유한하게 보류하고 그 시점의 TX 활성 여부·독립 연결의 재고 행 접근을 관찰 | 외부 HTTP 동안 업무 DB TX·재고 잠금을 보유하지 않음. TX A 결과는 별도 연결에서 이미 확인 가능. PG 종료 뒤 TX B가 별도로 열려 적용됨. annotation 이름이나 같은 클래스 자기 호출만으로 경계가 성립했다고 판단하지 않음 |
| W5-TDD-12 | 실제 HTTP fixture가 거래 1건을 저장한 후 응답만 유실시키고, 같은 거래를 이후 SUCCESS 조회로 노출 | 생성 응답 유실 직후 내부 UNKNOWN·가용 3·예약 유지·외부 거래 1. 동일 confirm 재요청은 저장 상태만 반환하고 새 create/lookup을 시작하지 않는다. 최초 confirm의 제한 조회 또는 별도 수동 reconcile로 동일 거래를 확인해 한 번 확정하며 create 추가 0. 내부 단건 조회는 refreshOnce로 검증한다. 첫 응답 전 종결되면 저장 결과 반환, 예산 안에 확인하지 못하면 202·예약 유지. HTTP 호출 전 예외만 던지는 대역으로 응답 유실 증거를 대신하지 않음 |
| W5-TDD-13 | 실제 callback controller와 수동 reconcile을 callback-first·lookup-first·동시 순서로 호출 | 모두 권위 조회한 같은 transactionKey·최종 상태로 수렴. 재고 추가 차감/중복 반환 0. 알림 body의 SUCCESS만으로 확정되지 않으며 callback은200·meta.SUCCESS·acknowledged=true, 확인 불가503·충돌409를 실제 HTTP로 검증 |
| W5-TDD-14 | callback 선종결 뒤 늦은 생성 PENDING/timeout, 반대 최종 결과, 다른 주문·transactionKey·금액/제공자 계정 범위의 조회 결과를 적용 | 기존 종결 결과 불변, 상태 퇴행·다른 주문 변경·재고 추가 효과 없음. 불일치/상충은 거절·충돌 기록. 고객/콜백 충돌409와 내부orderId·attemptUUID·고정source/reason WARN을 검증하고 카드·외부문자열은 남기지 않음 |
| W5-TDD-15 | PG SUCCESS 적용 TX B와 FAILED 적용 TX B 각각에서 실제 일부 SQL 성공 후 다음 저장 실패 주입 | 주문·시도·예약·재고·관련 버전의 내부 변경 전체 rollback. 외부 결과는 그대로 남음. 동일 거래 재조회 후 SUCCESS는 한 번 확정, FAILED는 한 번 반환하며 create 추가 0. 실패 반환 SQL만 남거나 반환 상태만 남지 않음 |
| W5-TDD-16 | 발송 시작 DB commit 직후 실제 HTTP 전 중단, 별도로 외부 거래 생성 후 중단·UNKNOWN 전환을 재현. DB/fixture를 유지한 채 서비스·클라이언트를 재생성 | 시도·예약·발송 기록 보존. 외부 거래가 0건인 전자도 자동 재발송 0·예약 유지. 후자는 기존 거래 조회로 복구. UNKNOWN 전환으로 발송 시작 사실을 지우거나 새 앱 객체가 저장 상태를 읽었다는 이유로 발송 권한을 다시 얻지 않음. 메모리 상태를 잃어도 중복 create하지 않음 |
| W5-TDD-17 | transactionKey 없음/있음의 복구 경로에서 조회 0건·PENDING·복수 거래·404·조회 실패를 각각 반환 | 임의 첫 거래 선택·성공/실패 확정·예약 반환·새 POST 없음. 미완료/충돌 상태 보존. 거래 식별자가 확보되면 해당 거래 조회를 연결하고, 빈 결과를 strict 생성 거절과 혼동하지 않음 |
| W5-TDD-18 | 실제 HTTP 서버의 응답 대기를 제한하고, 동일 JVM의 공통 PG 조회 회로에 실패 누적→OPEN→HALF_OPEN→회복 및 재차단 순서 통제 | 승인한 HTTP timeout과 회로 초기 수치가 실제 연결 경계에서 작동하는지 확인. OPEN 동안 외부 lookup 0, fallback은 저장 상태·확인 불가이며 FAILED/가짜 성공/포인트 대체/예약 반환 없음. 회복 뒤 조회 재개. 수치 경계와 오류 분류는 W5-TDD-27/29~31로 상세 검증 |
| W5-TDD-19 | 최초 confirm의 제한 조회 횟수·간격·전체 예산과 실제 호출 수를 대조하고, client/decorator의 숨은 create/lookup retry도 확인 | 승인한 최초 요청 전체 deadline이 호출 수·간격보다 우선. create 최대 1회·자동 재시도 없음. CB 허용 후 lookup 시도 최대 12회에는 연결 실패와 providerOrderId 조회도 포함하고, CB 차단은 외부 호출 0으로 분리. 별도 callback/reconcile의 조회를 이 12회에 섞지 않음. 한도 소진은 새 create/예약 반환 근거가 아니며 수동 reconcile 기본 경로·scheduler 비필수 유지 |
| W5-TDD-20 | 과거 POINT·대기/UNKNOWN·PG 성공/실패를 고객/관리자 조회하고, 타인 접근·같은 PG 시도 재요청·과거 POINT의 본문 없는 재확정 실행 | 과거 POINT 사실과 기존 `409 ORDER_ALREADY_CONFIRMED` 보존. PG 필수 키·카드 검사가 이 과거 요청을 400으로 바꾸지 않음. POINT 타인 요청은 기존 404, 신규 본인 DRAFT의 PG 필수 입력 누락은 400. PG 동일 종결 시도는 200 저장 결과, 미완료는 202 상태·조회 경로. 외부 PG 고정 헤더를 내부 인증으로 사용하지 않음 |
| W5-TDD-21 | 예약 전 삭제와 예약 완료 후 삭제를 나누고 후자의 SUCCESS/FAILED를 적용. 상품 삭제·브랜드 일괄 삭제 양쪽 확인 | 삭제 후 DRAFT 새 확정은 거절·PG 0. 이미 확보한 PAYMENT_PENDING은 스냅샷·예약으로 종결. 성공 추가 차감 없음, 실패 반환 1회여도 deleted 상태·비노출 유지. 참조/품목과 다른 대상은 보존 |
| W5-TDD-22 | 예약 2·가용 3 상태에서 관리자가 가용 10으로 최종 설정하고 이후 SUCCESS/FAILED 적용, 설정/예약/반환 경합도 검증 | 설정 후 가용 10·예약 2 유지. 다른 변경이 없으면 SUCCESS 뒤 10, FAILED 뒤 12. W5-Q03 사용자 승인: 상품 잠금 후 `설정할 가용 수량 + 해당 상품의 모든 HELD 예약 합계 <= Integer.MAX_VALUE`를 안전하게 검사. 동등 경계 허용·초과 설정 거절 및 상태 불변. 아래 경계 사례로 반환 여유와 동시성 검증 |
| W5-TDD-23 | 실제 캐시를 채운 뒤 예약·실패 반환·각 TX rollback·늦은 채움·Redis 장애/복구와 상품 조회를 연결 | 가용 재고를 담는 기존 payload의 변경과 DB 버전이 같은 TX에 반영/rollback. 커밋 후 새 요청은 올바른 재고·삭제 제외 결과. SUCCESS는 재고 추가 차감 없음. 기존 TTL 60초·키 대상·진행 중 조회 허용 계약은 변경하지 않음 |
| W5-TDD-24 | PG 진행 중 별도 포인트 충전/조회, 주문 생성·품목 스냅샷·본인 목록·관리자 권한/CSRF·상품/좋아요 회귀 | PG로 포인트를 차감하거나 대체 결제하지 않음. 예: 초기 10,000 + 충전 2,000이면 PG 결제액과 무관하게 잔액 12,000. 기존 충전 규칙·입력/소유권·삭제 비노출·수량/금액 범위 유지 |
| W5-TDD-25 | 작은 증분의 관련 테스트부터 전체 check까지 실제 실행하고 결과·잔여 자원 확인 | 실행 건수·실패·오류·skip·종료 코드, Checkstyle·ArchUnit을 기록. 테스트 0건·skip·환경 접속 실패를 통과로 세지 않음. 자신이 시작한 서버·worker·연결만 정리하며 기존 DB/볼륨을 삭제하지 않음 |

W5-TDD-22의 반환 상한과 후속 `409 STOCK_CAPACITY_EXCEEDED`·“예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.”는 사용자 답변으로 확정하고 [재고 증분](../week5/order-stock-reservation-tdd-log.md)에서 연결했다. 다음 수량·저장 기대값은 고객 PG 전환에서도 유지한다.

| 경계 준비·실행 | 기대값 |
| --- | --- |
| HELD 예약 2가 있는 상품에 가용 `Integer.MAX_VALUE - 2` 설정 후 확정 FAILED | 설정 허용, 예약 유지. 실패 반환 후 가용 `Integer.MAX_VALUE`, 반환 1회. 중복 FAILED에는 수량 증가 없음 |
| 같은 예약 2에 가용 `Integer.MAX_VALUE - 1` 또는 `Integer.MAX_VALUE` 설정 | 반환 여유 부족으로 설정 거절. 가용 수량·예약·주문·목록 revision 등 해당 요청의 저장 상태 불변 |
| 같은 상품에 여러 주문의 HELD 품목과 소비/반환 종결·NOT_RESERVED 품목이 함께 존재 | 모든 미종결 HELD 수량만 안전하게 합산. 한 주문만 세거나 CONSUMED·RELEASED·NOT_RESERVED 수량까지 더하지 않음. 합산 과정에서 int overflow·음수 우회를 허용하지 않음 |
| 상한 근처 관리자 설정과 예약 확보/반환의 경합 | 같은 상품 행의 보호 경계 안에서 판단·변경. 최종 `가용 + HELD 합계`가 상한 이내이고 성공한 요청 효과 보존. 잠금 전에 읽은 예약 합계로 설정을 승인하지 않음 |
| HELD 예약 0에서 가용 `Integer.MAX_VALUE` 설정 | 기존 최대 가용 수량 허용 계약 유지. 예약이 없는데 새 업무 한도를 임의로 낮추지 않음 |

### 복구·callback HTTP 연결의 TDD 증분

**2026-10-23 위임 선택을 구현한 복구 HTTP의 기대값이다. 두 경로는 코드에 연결했고 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 참조한다.** [복구 API 계약](commerce-api-contract.md#복구callback-http-계약-선택)과 [선택 이유](commerce-policy-decisions.md#w5-복구callback-http의-위임-선택)를 따른다. 기존 내부 `refreshOnce`·TX B·실제 SQL 뒤 rollback·client/서비스 재생성·결과 복구의 완료 증거를 미구현으로 되돌리지 않는다. 이번 증분은 실제 controller 진입과 두 경로의 경합·응답 증거를 보강한다. 표는 통과 결과나 새 테스트 개수를 미리 선언하는 것이 아니다.

| ID | 준비·실행 | 기대값·관찰 |
| --- | --- | --- |
| W5-REC-01 | 본인/타인/없는 주문의 reconcile을 본문 없음·`{}`·추가 필드·배열/타입 오류로 호출 | 본인 기존 PG만 복구. 소유권 우선 404, DRAFT 409 `PAYMENT_NOT_STARTED`, POINT 409 `ORDER_ALREADY_CONFIRMED`; PG 입력 오류 400. 카드·시도 키는 요구하지 않음. 거절 시 PG 0회·DB 불변 |
| W5-REC-02 | PG 종결 SUCCESS/FAILED를 정상 설정·PG OFF·지문 전체 NULL·HMAC 키 불가에서 reconcile | 저장 결과 200·PG 0회·신규 시도/예약/포인트 변화 0. 실패도 정상적인 저장 결제 결과임을 확인 |
| W5-REC-03 | 미완료 PG의 transactionKey 있음/없음에서 reconcile, 권위 결과 PENDING/SUCCESS/FAILED | 각각 거래 GET/주문별 GET 최대 1회. PENDING 202·HELD, SUCCESS 200·소비, FAILED 200·반환 1회. 반복 호출 뒤 추가 create/차감/반환 0 |
| W5-REC-04 | 미완료 reconcile에 조회 404·빈 목록·응답 유실/timeout·CB OPEN·PG OFF를 구분 | 404/빈 목록/실조회 실패는 202·확인 불가·예약 유지, CB OPEN은 PG 0회·저장 상태 보존·202, PG OFF는 503 `PAYMENT_PROVIDER_UNAVAILABLE`. 새 결제나 POINT 대체 없음 |
| W5-REC-05 | callback 세 필드 누락/추가·타입·길이(코드 포인트)·공백·잘못된 status 입력 | 400·PG 0회·DB 불변. providerOrderId 6~255, transactionKey 1~255, status 3종. 외부 식별자 정규화나 고객 헤더 신뢰 없이 고정 계정으로 처리 |
| W5-REC-06 | 없는 providerOrderId·저장 transactionKey와 다른 알림 키 | 각각 404 `PAYMENT_ATTEMPT_NOT_FOUND` / 409 `PAYMENT_RESULT_CONFLICT`, PG 0회·기존 주문/시도/예약 보존 |
| W5-REC-07 | 알림 SUCCESS/권위 PENDING, 알림 FAILED/권위 SUCCESS 등 서로 다른 힌트와 실제 결과 | 본문만으로 확정/반환하지 않음. 고정 계정의 실제 GET 거래·주문·금액을 검증해 권위 결과 적용. 유효한 PENDING/중복/종결은 200 `meta.SUCCESS`·`acknowledged=true` |
| W5-REC-08 | callback에서 거래 404·UNKNOWN·회로 차단·PG OFF. 미완료와 종결 모두 준비 | 앞 세 경우 503 `PAYMENT_RESULT_UNAVAILABLE`, OFF 503 `PAYMENT_PROVIDER_UNAVAILABLE`. CB 차단은 PG 0회·새 관찰 0. 실제 조회 실패의 미완료 UNKNOWN 가능, 종결은 언제나 보존. ACK 성공·FAILED·반환으로 숨기지 않음 |
| W5-REC-09 | 종결 후 callback에 동일 결과·늦은 PENDING·반대 종결, 거래/주문/금액 불일치·복수 조회 거래 | 동일/늦은 비종결은 권위 조회 뒤 200·최초 결과 유지. 반대 종결/식별 충돌은 409·추가 효과 0. reconcile 주문별 복수 거래도 임의 첫 항목 선택하지 않음 |
| W5-REC-10 | 실제 callback-first·reconcile-first·동시 요청, 최초 confirm의 늦은 PENDING/timeout까지 조합 | 성공은 추가 차감 없이 한 번 확정, 실패 반환 1회. 주문·시도·품목 예약 일치·포인트 변화 0·create 추가 0. DB 최종 숫자뿐 아니라 각 요청 응답·PG 호출/거래 수 대조 |
| W5-REC-11 | callback 대상 사전 조회 뒤 PG 대기 중 다른 실행이 transactionKey를 연결하거나 종결 | 사전 결과/같은 JPA 객체를 맹신하지 않고 공통 TX B의 최신 잠금 상태로 재검증. 다른 거래는 409, 늦은 비종결은 최초 종결 보존. HTTP 중 DB TX·업무 잠금 없음 |
| W5-REC-12 | 두 HTTP 경로에서 SUCCESS/FAILED 적용 중 실제 SQL 변경 후 저장 실패. 종료 뒤 별도 DB 조회, 같은 거래로 재확인 | HTTP 500 또는 기존 잠금 409를 성공 ACK/202로 숨기지 않음. 해당 TX 전체 rollback·외부 결과 유지. 재확인으로 정확히 한 번 확정/반환. 기존 내부 rollback 증거에 HTTP 경계만 추가 |
| W5-REC-13 | 두 경로에 지연 HTTP·CB OPEN을 주고 호출/대기 횟수 계측 | 허용된 GET 최대 1회, 자동 retry·sleep·180초 루프 0. 기존 호출 전체 10초 제한과 자원 정리 유지. DB 처리까지 포함한 엄격한 10초 보장이나 실제 180초 운영 상한 검증으로 표시하지 않음 |
| W5-REC-14 | 조작한 외부 식별자/카드 유사 문자열을 담은 충돌과 손상 응답을 로그 수집 상태로 호출 | WARN에 내부 orderId·attempt UUID·고정 source/reason만. callback 원문·카드·HMAC·외부 문자열·예외 원문 없음. 충돌 로그가 영속 감사/자동 복구를 보장한다고 주장하지 않음 |
| W5-REC-15 | 고객/관리자 주문·POINT 기록·포인트 API·삭제 상품의 기예약 성공/반환·기존 캐시 회귀 | 기존 접근/저장 사실 유지, 신규 PG 포인트 변화 0. 삭제 상품을 반환해도 재노출하지 않음. 사용자 DB는 접근/이행하지 않고 격리 MySQL/로컬 HTTP만 사용 |
| W5-REC-16 | 새 HTTP·경합·롤백과 핵심 confirm 회귀·ArchUnit·Checkstyle, latch/worker/fixture 종료를 유한하게 확인 | 사용자 속도 요청에 따라 이번에는 전체 check 반복을 생략하고 실행한 범위·스위트/테스트/실패/error/skip/종료 코드를 기록한다. 이전 전체 검사 1,764개는 당시 증거이며 이번 전체 통과로 표시하지 않음. 자신이 시작한 자원만 정리, 기존 DB/볼륨 삭제 없음. 완료 판정은 실제 결과 확인 후 갱신 |

Red는 아직 없는 경로의 응답·요청 횟수·DB 상태가 기대와 다름을 확인하고, Green은 기존 내부 경계를 연결해 최소한의 기능으로 통과시킨다. Refactor에서는 외부 결과 분류·오류 변환·중복 효과 방어가 어느 한 경로에서 빠지지 않는지 확인한다. 상세 기대값을 낮추거나 기존 내부 복구 테스트를 삭제해 검사를 통과시키지 않는다.

### 승인한 최초 요청 예산·조회·회로의 상세 검증

아래는 W5-TDD-08·12·18·19를 [승인한 초기 설정](commerce-policy-decisions.md#w5-최초-결제-요청의-대기조회회로-설정)에 맞게 나눈 테스트 계획이다. 3단계의 개별 HTTP 제한·adapter 분류는 선행 증거이고, 전체 예산·조회 반복·회로는 [최신 고객 연결 기록](../week5/customer-payment-api-tdd-log.md)으로 구분한다. 최초 발송 권한을 얻은 confirm 실행만 해당 루프를 소유하며 동일 재요청은 긴 루프를 추가하지 않는다. 수치의 선택 이유는 정책 문서를 따르고 여기서는 입력·경계·관찰 증거를 정의한다.

| 사례 ID | 준비·실행 방법 | 기대 결과·남길 증거 |
| --- | --- | --- |
| W5-TDD-26 · 단일 시간 예산 | 제어 가능한 경과 시계로 요청 진입, TX A, 생성 호출, 조회·대기 시간을 순서대로 진행. 외부 마감 직전/도달·다음 호출 예산 부족을 각각 구성 | 요청 진입부터 전체 목표 180초·외부 확인 마감 175초이며 TX A 종료/timeout마다 시작 시점을 갱신하지 않음. 호출 전체 제한과 최종 처리 여유를 확보할 수 없으면 새 외부 호출을 시작하지 않고 대기도 잔여 예산 이내. 종결 결과가 없다면 202·예약 유지이며, 5초 여유만으로 DB 완료나 실제 180초 상한을 증명하지 않음 |
| W5-TDD-27 · 실제 HTTP 제한 | 실제 HTTP client와 통제된 로컬 연결/응답 장치로 풀 대기·연결·응답 지연 및 합산 지연을 각각 재현 | 풀 대기 1초·연결 3초, 생성 응답 60초/호출 전체 65초, 조회 응답 5초/호출 전체 10초 제한의 연결을 검증. 실제 요청 중단·연결 회수·숨은 재시도 0을 확인하고 Future 대기 종료만으로 통과하지 않음. 초기 전체 예산이 부족한 호출은 시작하지 않으며, timeout은 PG 거래 취소나 확정 FAILED의 증거가 아님 |
| W5-TDD-28 · 간격·횟수 | 고정 시계와 제어한 jitter 값으로 첫 조회와 이후 완료 시점을 기록. 연결 실패·주문별 조회→거래별 조회·CB 차단을 섞어 원시 시도 수와 fixture 수신 수 대조 | 첫 조회는 시간·회로가 허용하면 즉시. 후속 간격은 이전 호출 완료 뒤 5→10→15초 상한의 ±20%인 4~6/8~12/12~18초이며 조회를 겹쳐 보내지 않음. 선택한 전체 간격과 다음 HTTP 10초 예산을 확보하지 못하면 종료하며 간격을 임의로 압축하지 않음. CB 허용 후 시작한 lookup 최대 12회에 연결 실패·주문별/거래별 조회를 모두 포함. CB 차단은 외부 0회이며 횟수를 깎지 않아도 deadline으로 종료. callback·별도 reconcile 조회는 이 횟수와 구분 |
| W5-TDD-29 · 회로 표본 분류 | 실제 adapter 결과를 timeout·I/O·5xx·잘못된 JSON·필수 필드 누락과 유효 상태/404/빈 목록으로 나누고, 정상 응답의 지연도 별도 주입 | 앞의 장애는 실패 표본. 유효한 PENDING/SUCCESS/FAILED·계약상 조회 404/빈 목록은 통신 실패가 아니며 3초 초과면 지연 표본. 형식이 유효한 주문·거래·금액 불일치는 충돌로 분리해 업무 상태를 적용하지 않음. 내부 TX B 저장 실패를 PG 통신 실패에 섞지 않음. 계약 밖 4xx 분류는 adapter 설계에서 명시하며 임의 확정 실패·반환 금지 |
| W5-TDD-30 · CLOSED 임계값·공유 | 같은 JVM/PG 계정에서 여러 주문의 조회를 한 회로에 누적. 빠른 실패와 느린 정상 표본을 분리하고 최근 20건 창의 이동·최소 표본 경계를 통제 | 최소 10건 전에는 비율만으로 열리지 않음. 10건 중 빠른 실패 4건은 실패율 조건 미충족, 5건이면 50%로 OPEN. 정상 응답도 10건 중 5건이 3초 초과면 OPEN, 정확히 3초는 느린 호출이 아님. 주문별 회로·POST/confirm 단위 집계가 아니며 오래된 표본은 창에서 제외. 이 회로가 동시 호출 수 제한이나 다중 서버 전체 동기화라고 주장하지 않음 |
| W5-TDD-31 · OPEN·탐색·재차단 | OPEN 30초 직전/도달, HALF_OPEN 탐색 2건의 정상/장애/지연, 탐색 미완료 40초를 제어. 최초 요청 잔여 예산이 충분/부족한 장면 분리 | OPEN 동안 실제 lookup 0·차단 자체를 새 장애 표본에 더하지 않음. 예산 안에서 TX 없이 유한 대기 후 허용된 탐색만 진행. 탐색 2건의 실패율·지연율 모두 50% 미만이면 CLOSED, 하나라도 50% 이상이거나 40초 안에 탐색을 끝내지 못하면 OPEN. 예산 부족은 미완료 202·예약 유지이며 회로를 retry로 우회하지 않음 |
| W5-TDD-32 · 조기 반환·callback 경합 | 생성 PENDING 후 제한 조회에서 SUCCESS/FAILED 확인. 별도로 조회 대기·OPEN 대기 중 callback이 먼저 종결하고 늦은 timeout/PENDING을 전달 | 2~3분을 채우지 않고 저장한 종결 결과로 조기 반환. 외부 조회 전·종료 직전 저장 상태를 확인해 callback 선종결을 보존하고 늦은 timeout으로 UNKNOWN/202로 되돌리지 않음. 끝까지 미확인이면 202·HELD 유지·추가 create 0·포인트 변화 0. 최종 저장 실패는 단순 미확인 202로 숨기지 않고 W5-TDD-15의 rollback·재조회 복구로 검증 |
| W5-TDD-33 · 실제 전체 시간·자원 | 실제 요청 진입부터 TX A/HTTP/조회 대기/회로/최종 DB 처리/응답까지 시간을 관찰하고, DB 대기·클라이언트 취소·인바운드 제한·동시 긴 요청을 별도 점검 | 기존 DB 락 대기 3초를 유지하고 HTTP/대기 중 DB TX를 잡지 않음. HTTP 작업 중단·worker/연결 회수와 실제 응답 시간, 실패·자원 점유를 기록. 최종 처리 5초 여유 및 180초 목표의 성립 조건/미달을 공개하며 시계 제어 테스트만으로 하드 상한 달성을 선언하지 않음. callback·별도 reconcile은 후속 선택한 GET 최대1회·호출전체10초이며 이180초 루프를 적용하지 않음 |

<a id="hmac-요청-지문의-조건부-검증-계획"></a>

### HMAC 요청 지문의 검증 계획

**선행 증거: 2026-10-23 W5-D10의 순수 계산·비교 첫 증분을 구현·검증했다.** [실행 기록](../week5/payment-request-fingerprint-tdd-log.md): 신규 54개, 관련 회귀·ArchUnit 포함 343개 통과 및 Checkstyle main/test 위반 0. W5-HMAC-01의 계산 범위와 05·06의 순수 하위 범위이며 DB/HTTP·전체 비노출·키 교체 검증 완료는 아니다. 과제 해석 확인은 선택 사항으로 남기며 담당자 사전 승인을 구현·테스트의 필수 조건으로 두지 않는다. 후속 실행 승인에 따라 계산 단위 테스트를 추가했으며 키 설정·DDL·고객 HTTP는 이번 증분에서 변경하지 않았다. 기존 W5-TDD-02·04·10·16의 요청 일치·카드 비저장·중복 효과 방지 요구는 그대로 유지하며 이 사례들을 공식 추가 채점 조건으로 만들지 않는다.

목표는 **같은 내용의 재요청은 기존 상태로 연결하고, 같은 키의 변경 입력은 거절하면서 추가 결제·예약을 만들지 않는 것**이다. 계산 단위 다음으로 [지문 저장 TDD](../week5/payment-request-fingerprint-persistence-tdd-log.md)를 진행했다. 기존 PG의 지문·두 버전은 전체 NULL로 보존하고 이후 카드 입력으로 채우지 않는다. 전용 Testcontainers MySQL에서 011 실제 적용·JPA 재조회·부분 NULL/길이/버전 제약·독립 연결 rollback·유일 제약 경합을 검증한다. 이는 W5-HMAC-03의 저장 하위 범위이며 실제 TX A의 재고/revision·PG POST 수나 고객 동시 재요청 응답을 검증한 것은 아니다. 이 저장 증분 당시 기존 5인자 생성자와 prepareNew는 무지문 경로였으며, 후속 [키 설정 TDD](../week5/payment-fingerprint-key-configuration-tdd-log.md)에서 환경변수 공급·현재/과거 키 선택·설정 오류 비노출을 DB 없이 구현했다. 후속 [주문 지문 연결](../week5/order-payment-fingerprint-connection-tdd-log.md)에서는 `prepareNew`와 `prepareOrReuse`가 모두 지문을 저장하고 `startOrReuse`가 동일 재요청의 저장 결과를 반환한다. 기존 5인자 생성자는 과거 무지문 fixture 호환용으로 남기며 신규 경로에서는 사용하지 않는다. 현재 고객 confirm·제한 조회/회로는 [최신 고객 연결](../week5/customer-payment-api-tdd-log.md)을 따르고 callback/reconcile HTTP도 연결했으며 이번 검증 결과는 최신 복구 기록을 따른다. 컨텍스트 재생성·키 교체 검증을 실제 서버 재시작/여러 서버 배포 검증으로 확대하지 않는다.

| 사례 ID | 준비·실행 방법 | 기대 결과·남길 증거 |
| --- | --- | --- |
| W5-HMAC-01 · 계산·표현 | 구현 전에 고정할 직렬화 규격과 시험용 키로 동일 입력을 반복 계산. JSON 공백/필드 순서만 다른 입력과 사용자·주문·저장 금액·카드 종류·유효한 합성 카드 번호가 각각 달라진 입력을 비교. RFC 테스트 벡터도 사용 | 동일한 의미·같은 키/형식 버전은 같은 32바이트 지문, 선택한 변경 입력은 다른 지문. 필드 경계가 모호한 이어 붙이기나 원본 JSON 비교가 아니며 PG 계약 밖 임의 카드 정규화 없음. client 제공 지문을 서버 계산 대신 신뢰하지 않음 |
| W5-HMAC-02 · 재요청·충돌·호환 | 같은 키/같은 내용과 카드만 바꾼 confirm을 진행 중·종결 후 각각 요청. 다른 주문의 같은 키·같은 주문의 새 키와 타인 접근도 실행 | 같은 내용은 저장 상태/결과 반환, 변경 내용은 충돌이며 기존 지문/버전·예약·결제 결과 불변, create 추가 0. 소유권 우선·기존 POINT 본문 없는 409 유지. GET·reconcile·callback은 카드/지문 재제출 없이 기존 식별·권위 조회 사용 |
| W5-HMAC-03 · 동시성·원자성 | 독립 MySQL TX/worker로 같은 키의 동일 내용 및 서로 다른 카드 요청을 각각 경합. 최초 시도/지문을 실제 flush한 뒤 TX A 실패를 별도로 주입하고 종료 후 독립 연결로 재조회 | 커밋한 시도/지문 한 벌만 남고 기존 값을 덮어쓰지 않음. 동일 내용은 기존 상태, 다른 내용은 충돌. 발송 전이·커밋 승자만 create 1회, 예약 중복 없음. TX A 실패 시 시도·지문·형식/키 버전·주문 대기·발송 기록·예약·가용 수량·목록 revision 전체 rollback, PG 0회 |
| W5-HMAC-04 · 재시작·키 교체 | 같은 DB를 유지한 채 서비스 재생성·다른 인스턴스에서 재요청. 새 시도용 키 버전을 바꾸고 이전 시도는 기록된 키/형식 버전으로 비교 | 재시작·서버 교체에도 동일/변경 요청의 판단 유지. 새 키로 기존 지문을 임의 재작성하지 않음. 발송 이력 보존·create 추가 0. 기존 키/형식 지원을 유지하는 비용을 기록하고 자동 키 교체 제품 구축을 요구하지 않음 |
| W5-HMAC-05 · 비교 불가 | 필요한 비밀키 누락·지원하지 않는 형식/키 버전을 주입해 신규/기존 confirm 실행. 별도로 기존 거래 reconcile 수행 | 비교 불가를 동일 요청·입력 불일치·PG 확정 실패로 단정하지 않음. 해당 confirm은 기술 오류로 처리하고 기존 지문/상태/예약 보존·새 POST 0. 원문으로의 fallback·현재 지문 덮어쓰기 없음. 카드 비교가 불필요한 기존 거래 복구를 방해하지 않음. HTTP/code 세부값은 Q05 선택 후 고정 |
| W5-HMAC-06 · 비노출 | 정상·입력 오류·예외 경로에서 DB/HTTP 응답과 SQL bind·HTTP 요청/응답·DTO/toString·trace 로그를 관찰 | 카드 원문·복원용 암호문·원본 요청 본문이 DB에 없고 카드 원문이 로그/응답에 없음. 실제 저장 구현 시 카드 유래 비교 정보로는 지문·형식/키 버전만 시도 DB에 보존하고 지문·비밀키를 로그/응답에 노출하지 않음. 실제 카드 대신 합성 값만 사용하며 테스트 진단 출력에도 원문을 남기지 않음 |

HMAC으로 PG 결과를 인증하거나 callback을 신뢰하지 않는다. 기존 W5-TDD-09·13~16의 TX B 한 번 적용·rollback·발송 이력 검증은 별도로 유지한다. 지문 비교 테스트 통과가 외부 거래의 정확히 한 번 완료나 W5 전체 통과를 의미하지 않는다.

### 9개 채점 항목과 증거 연결

표의 번호는 공식 채점 항목이며 아래 사례 수나 단계 수를 새 배점으로 만들지 않는다.

| 공식 항목 | 연결할 W5-TDD 사례 | 필요한 판정 증거·현재 상태 |
| --- | --- | --- |
| 1. PG 교체·포인트 이중 차감 금지 | 03·07~10·20·24 | 기존/신규 DRAFT의 고객 confirm PG 연결과 포인트 변화 0. 실제 HTTP·DB 및 관련 회귀 결과는 [최신 기록](../week5/customer-payment-api-tdd-log.md) 참조 |
| 2. 과거 POINT·품목·소유권 보존 | 03·20·21·24 | 과거 POINT의 기존409·품목·금액·시각과 고객/관리자 조회·접근 보호를 연결. 격리 DB·실제 API 검사 결과는 최신 기록 참조 |
| 3. 수락·처리 중·종결·미확인 구분 | 01·05·06·08·09·14·17·20·26·32 | 상태 전이·202/200·조회 경로·일관된 조회 snapshot·상태 퇴행 금지를 고객 API에 연결. callback 순서 검증 결과는 최신 복구 기록 참조 |
| 4. DB 중복 보호·HTTP 동안 TX/잠금 미보유 | 02·07·10·11·16 | 내부 신규 시작은 [HTTP·DB 연결 기록](../week5/order-payment-http-connection-tdd-log.md), 고객 동일 confirm의 HMAC 비교·생성1회·중복 루프 금지·HTTP 동안 TX 미보유는 최신 고객 기록 참조 |
| 5. 예약 소비·확정 실패 반환 한 번 | 05·07~10·13·15·21~23 | 가용/예약 수량·중복 효과0·내부 원자성의 [재고 기록](../week5/order-stock-reservation-tdd-log.md)과 최신 고객 PG 연결 증거를 구분. callback HTTP 경합 검증 결과는 최신 복구 기록 참조 |
| 6. 불확실할 때 재발송·임의 반환 금지 | 06·12·16~19·26~32 | 내부 발송 보호·예약 유지에 더해 고객 confirm·전체 조회 예산·회로를 연결. 시간 종료/회로 차단을 FAILED로 바꾸지 않음. callback/reconcile HTTP 복구도 연결했으며 검증 결과는 최신 복구 기록 참조 |
| 7. 실제 adapter·callback 권위 조회 | 04~06·12~14·17·27·29 | 로컬 HTTP 형식·관찰 분류는 [3단계 기록](../week5/local-test-pg-http-tdd-log.md). 실제 callback controller·저장 주문 검증 결과는 최신 복구 기록 참조 |
| 8. 중복·순서·저장 실패·timeout/회로/복구 | 10~19·26~33 | 개별 HTTP·내부 DB·단건 복구의 선행 증거와 고객 confirm·예산·회로의 최신 증거를 구분. callback-first/lookup-first·수동 reconcile HTTP의 통합 복구 검증 결과는 최신 복구 기록 참조 |
| 9. 관련 회귀·lint·ArchUnit·자원 정리 | 20~25·33 | 증분별 실행 기록에 검사·자원 종료 증거를 보존. W5 교체 전체 완료 검사는 아직 아님 |

### 미정 질문이 테스트에 미치는 영향

질문 ID와 답변 상태는 [W5 정책 선택 문서](commerce-policy-decisions.md#w5-결제-교체의-선택과-질문)의 W5-Q01~09를 따른다. 승인·구현·검증을 구분하며, 질문이 남았다는 이유로 원문이 이미 정한 포인트 변화 0·UNKNOWN 예약 유지·create 재시도 금지까지 미정으로 돌리지 않는다.

| 질문 | 결정 상태·테스트에 남은 확인 |
| --- | --- |
| W5-Q01 · 로컬 기존 데이터 | 기존 데이터 유무는 여전히 확인되지 않았으며 0건으로 단정하지 않는다. 사용자 승인에 따라 사용자 DB를 건드리지 않고 전용 Testcontainers DB의 기존 PG·POINT·DRAFT fixture로 이행을 검증한다. 실제 DB 적용·초기화는 하지 않음 |
| W5-Q02 · 대기 응답과 수동 조회 | **최초 confirm 안에서 제한된 추가 조회 선택·연결.** 종결 시 조기 반환, 시간/횟수 한도 소진 시 미완료202·상태·조회 경로. 동일 재요청은 긴 루프를 추가하지 않음. 최신 검사 결과는 고객 연결 기록 참조. 수동 reconcile의 단건 GET·호출전체10초는 선택했으며 HTTP를 연결했으며 검증 결과는 최신 복구 기록 참조 |
| W5-Q03 · 예약 반환 수치 상한 | **수량 정책 승인됨:** 설정할 가용 수량 + 모든 HELD 예약 합계가 Integer 상한 이하일 때만 설정. 동등 허용·초과 거절·안전 합산을 W5-TDD-22에서 검증. 후속 사용자 승인으로 `409 STOCK_CAPACITY_EXCEEDED`·“예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.”를 확정 |
| W5-Q04 · 키·요청 동일성 | **UUID·서비스 전체 키 유일성·로컬 PG 합성 카드 입력·HMAC 선택 및 고객 confirm 연결.** 특정 카드 번호 한 종류 제한은 미채택. 같은 키의 다른 주문/내용은 충돌이며 카드 번호는 DB/로그에 남기지 않는다. 기존 전체NULL 지문을 보존하고 소급 생성하지 않는다. 환경변수 현재/과거 키 선택·TX A 공동 저장·재요청 비교와 사용자 선택409/503/500을 연결했다. 계산·저장·설정·내부/고객 HTTP의 증거를 각 실행 기록으로 구분한다. 과제 해석 질문은 선택 사항이며 공식 담당자 승인으로 주장하지 않음 |
| W5-Q05 · callback 응답·충돌 기록 | **위임 선택 완료:** 유효관찰200 ACK·확인 불가503·충돌409, 내부 식별자와 고정 사유만의 WARN. 알림만으로 확정하지 않고 권위 조회하며 W5-REC-05~14에서 HTTP 증거 보강 |
| W5-Q06 · timeout·재조회·회로 | **초기 수치 승인·개별 HTTP 및 최초 제한 조회/회로 연결.** Apache HttpClient 5.4.2의 [선행 증거](../week5/local-test-pg-http-tdd-log.md)와 180/175초·횟수/간격·회로의 [최신 증거](../week5/customer-payment-api-tdd-log.md)를 구분. 실제180초 하드 상한은 주장하지 않으며 callback/reconcile은 GET 최대1회·호출전체10초·반복대기없음으로 선택하고 HTTP를 연결했으며 검증 결과는 최신 복구 기록 참조 |
| W5-Q07 · 예약 저장 위치 | **A안 승인·저장 연결 구현:** PaymentAttempt 별도 + 기존 OrderItem 예약 상태, 수동 SQL 009/010. 저장·이행 검증과 실제 서비스의 재고·TX·경합 검증을 구분한다. 별도 StockReservation은 미채택 |
| W5-Q08 · 발송 TX 배치 | **사용자 TX A 동시 저장 선택·내부/고객 HTTP 연결:** 준비·발송 기록 공동 커밋/rollback, 커밋 후 POST와 예산 부족/발송 전 중단 뒤 재발송 금지. 고객 요청 일치·응답까지 연결했으며 별도 claim TX는 미채택 |
| W5-Q09 · 결제수단 null | **사용자 확인·모델/저장/고객 조회 연결:** DRAFT는 null, PG 준비 후 PG 필수. 이전 POINT는 POINT 보존. 시도 없는 DRAFT를 UNKNOWN으로 꾸미지 않으며 손상된 PG 주문/시도 조합은500. 각 계층의 검증 증거는 실행 기록으로 구분 |

### 고객 결제 HTTP 계약의 TDD 증분

2026-10-23 사용자가 [JSON·오류·PG 주문 ID 계약](commerce-api-contract.md#고객-결제-api-상세-계약)을 선택했다. DB 없는 parser/DTO·오류 분류·식별자 생성의 [선행 계약 기록](../week5/customer-payment-contract-tdd-log.md)과 실제 controller PG 전환·조회 서비스·최초 대기/회로의 [최신 연결 기록](../week5/customer-payment-api-tdd-log.md)을 구분한다. 아래 표는 두 증분의 기대값 색인이며 실제 실행 결과는 각 기록을 따른다.

| ID | 계약 경계의 기대값 | 실제 API 연결 검사의 기대값 |
| --- | --- | --- |
| W5-HTTP-01 | 정확한 세 필드·UUID·문자열 필수, 잘못된 타입/누락/null/추가 필드 거절. 카드 원문 정규화 없음·toString 비노출 | PG 분기에서만 필수 입력 검사. 본문 없는 POINT 본인409·타인404 유지 |
| W5-HTTP-02 | 기존 주문 필드 보존·PG 다섯 상태·paymentMethod·payment·조회 경로, null 생략. API 정상 반환과 결제 결과 분리 | 실제 GET/confirm에서 상태·품목·금액을 일관된 저장 snapshot으로 조합. 목록 N+1 방지 |
| W5-HTTP-03 | PG 종결 confirm200·미완료202, GET은 별도200 정책 | 최초 요청은 승인된 제한 조회 이후202, 동일 재요청은 긴 루프/POST 추가 없이 현재 저장 상태 |
| W5-HTTP-04 | 내용 충돌409·전체NULL 지문409·서버키/형식503·저장 손상500. message 문자열 파싱 없이 분류 | 실제 HTTP의 소유권 우선·추가 PG/재고 효과0·키 문제 신규 준비 전체rollback |
| W5-HTTP-05 | 신규 PG ID의0패딩·Long 경계·기존 매핑 보존·다른 주문의 시도 거절 | 주문 잠금/소유권 확인 안에서 저장 ID를 선택해 재요청·조회에 사용. 기존 다른 매핑도 정상 재사용 |
| W5-HTTP-06 | 응답·오류에 카드·지문·키/버전 비노출, 기존 POINT/DRAFT 표현 구분 | 실제 controller/로그·조회·권한/관리자 필터·기존 POINT·포인트 API 회귀 |

### 기존 테스트 전환과 과거 증거 보존

기존 POINT 결제 테스트의 **실행 기록·당시 기대값은 삭제하지 않는다.** 다만 미확정 주문의 새 결제가 PG로 바뀐 뒤에도 신규 confirm에서 포인트 차감을 기대하는 테스트를 그대로 현재 계약으로 둘 수는 없다. W5 명세를 근거로 변경 대상·보존 대상을 나누고 변경한 기대값과 이유를 실행 기록에 남긴다.

| 기존 범위·대표 테스트 | W5에서 유지하거나 전환할 것 |
| --- | --- |
| `OrderTest`, `OrderV1ApiE2ETest`의 재확정·과거 조회 | 이전 POINT fixture의 409·금액·시각·품목 보존을 유지. 신규 PG에는 동일 시도 결과 재반환·새 키 충돌·대기 상태를 별도 검증 |
| `OrderTransactionTest`, `OrderServiceIntegrationTest`의 재고·포인트·확정 단일 TX | 과거 실행 증거는 보존. 신규 PG는 TX A 전체 rollback과 TX B 성공/실패 반영 rollback을 분리하고, 외부 거래는 내부 rollback으로 취소되지 않는 복구를 추가 |
| `OrderConcurrencyIntegrationTest`, `OrderStockConcurrencyIntegrationTest` | 재고 초과 판매·부분 변경·중복 효과 금지는 유지. PG 동일 시도의 create/예약 1회·저장 상태 반환과 포인트 변화 0으로 연결 |
| `OrderPointConcurrencyIntegrationTest`, `PointChargeOrderConcurrencyIntegrationTest`, `PointOrderJourneyApiE2ETest` | 기존 POINT 차감 경쟁의 과거 기록은 유지. 새 주문을 포인트로 결제하는 경로를 회귀 목적으로 다시 열지 않으며 신규 PG·충전 분리와 PG 포인트 변화 0 검증으로 전환 |
| `CommerceSchemaIntegrationTest`, 브랜드 삭제·관리자 재고·캐시 회귀 | 과거 POINT 사실·기존 관계/제약을 보존하고, 새 상태/시도/예약 제약·삭제 전후·가용 재고 설정·버전 원자성 기대값을 추가 |

순서 검증에는 테스트 fixture의 유한 래치·제어 가능한 외부 결과를 사용하고 생산 코드에 테스트용 sleep·장벽을 넣지 않는다. 승인한 조회 대기 정책은 별도 책임이며, 테스트에서는 제어 가능한 경과 시계·고정 jitter로 마감/간격/회로 경계를 결정적으로 검증한다. 무작위 지연·성공 확률로 필수 장면 재현을 대신하지 않는다. 실제 HTTP timeout·연결 종료 테스트와 시계/대역으로 진행하는 예산·회로 상태 테스트의 증거를 구분하고, 회로 설정만 존재한다는 이유로 실제 차단이나 180초 하드 상한을 주장하지 않는다.

초기 데이터를 먼저 commit하고 테스트 전체를 한 TX로 감싸지 않는다. DB 변경 실패는 실제 SQL 성공 뒤 주입하며, 서비스 종료·모든 worker 종료 후 독립 연결로 최종 상태를 확인한다. HTTP 중단·재시작 시험은 같은 DB·외부 거래 상태를 유지한다. 기다림·조회 횟수에 상한을 두고 `finally`에서 자신이 만든 서버·executor·connection을 정리한다. 실행 전에 관련 테스트·lint·ArchUnit 범위를 제시하고, 실행 후에만 실제 Red/Green·통과 수를 기록한다.

## W2~W4 선행 계획과 실행 기록의 범위

아래 당시 수치·테스트 ID·순서는 과거 증거로 보존한다. W2~W3의 포인트 결제, 모든 확정 재요청의 409, 재고·포인트·확정 결과를 한 TX로 처리하는 설명은 **W5 신규 PG 결제 계약이 아니다.** 과거 POINT 조회·기존 오류와 변경 대상이 아닌 업무 규칙은 계속 보호한다. W4 조회·캐시 계약도 유지하되, 예약/반환으로 추가되는 쓰기 경계는 위 W5 사례에서 다시 검증한다.

**W3 변경 상태:** 브랜드 일괄 논리 삭제의 `W3-BRAND-01..08`을 구현·검증했다. 정상·경계·전체 롤백에 이어 고객·관리자 연결, DRAFT 확정 거절, 과거 주문 보존과 삭제·주문·등록 경합을 확인했다. 결과는 [네 번째 증분](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증), W2와 앞선 증분은 과거 기록으로 구분한다. 후속 승인 P16의 주문 재확정 거절도 `W3-RECONFIRM-01..06` 구현·검증을 완료했으며 [별도 실행 기록](../week3/order-reconfirmation-tdd-log.md)에 증거를 남겼다. 이번 증분은 재확정 계약에 한정하며 W3의 8개 주문 재고 경쟁·3개 주문 포인트 경쟁·주문 내부 저장 경계 실패/롤백 검증·갱신 유실 대조군 과제 완료를 뜻하지 않는다.

**후속 검증 완료:** 주문 확정 중 실제 변경 SQL 이후 실패·전체 롤백을 `W3-ORDER-TX-01..03`으로 검증했다. 신규 4개 사례와 전체 545개 테스트가 통과했으며 [주문 트랜잭션 실행 기록](../week3/order-transaction-tdd-log.md)에 결과를 남겼다. 별도 경쟁 수치 시나리오와 갱신 유실 대조군은 이번 범위가 아니다.

**대조군 검증 완료:** 갱신 유실 대조군 `W3-LOST-UPDATE-01`을 테스트로 구현해 커밋 2·기술 오류 0·최종 재고 4를 확인했다. [계획·실행 기록](../week3/stock-lost-update-control-plan.md)에 증거를 남겼으며 기존 서비스의 잠금이나 업무 계약은 변경하지 않았다.

**재고 경쟁 검증 완료:** `W3-STOCK-RACE-01`에서 실제 서비스의 고유 주문 8개가 재고 5개를 놓고 경쟁해 확정 5·재고 부족 3·기술 오류 0·최종 재고 0을 확인했다. [실행 기록](../week3/stock-order-concurrency-tdd-log.md)에 구매자별 잔액·주문 상태 보존 증거를 남긴다.

**포인트 경쟁 검증 완료:** `W3-POINT-RACE-01`에서 한 사용자의 4,000원 주문 3개가 잔액 10,000원을 놓고 경쟁해 확정 2·포인트 부족 1·기술 오류 0·잔액 2,000을 확인했다. [실행 기록](../week3/point-order-concurrency-tdd-log.md)에 성공 상품만 차감·거절 주문/상품 보존 증거를 남긴다.

**충전·결제 경쟁 검증 완료:** `W3-CHARGE-ORDER-01`에서 초기 10,000+충전 2,000−결제 7,000=잔액 5,000과 두 요청 성공·오류 0을 확인했다. [실행 기록](../week3/point-charge-order-concurrency-tdd-log.md)에 당시 관련 50개·Checkstyle test 결과를 보존한다. 2026-10-09에는 [전체 최종 검사](commerce-completion-checklist.md#최신-통합-검사)도 60개 스위트·549개 통과했다. PR 초안은 준비했으며 기술 글·실제 제출은 별도 작업이다.

## W4 상품 조회 기준선 TDD

첫 증분은 [기준선 기록](../week4/product-query-baseline.md)에 따라 운영 조회·인덱스·캐시를 바꾸지 않고 측정 기반을 추가한다. SQL 관찰 도구는 실패하는 단위 테스트부터 구현하고, 실제 기존 API의 정확성은 별도 MySQL 통합 테스트로 확인한다. 기존 조회가 처음부터 기대값을 만족하면 인위적으로 Red를 만들지 않는다.

| 사례 | 기대값과 증거 |
| --- | --- |
| W4-BASE-01 | 준비 데이터의 상품 10,000개·브랜드 100개·좋아요 46,955개와 0개·동점·삭제·품절 분포를 확인한다. 기존 수동 DDL과 실제 인덱스를 사용한다. |
| W4-BASE-02 | 실제 브랜드 1007·가격순·page 0·size 20 요청의 HTTP 200·SUCCESS·전체 응답을 독립 기대값과 비교한다. 총 99개·5페이지와 정렬된 ID·가격·좋아요·재고·브랜드를 확인한다. |
| W4-BASE-03 | JDBC 관찰은 요청 중 성공한 SELECT의 SQL·바인딩을 기록한다. 비활성 구간, 파라미터 변경·null, HTTP worker 스레드, 실패 전파, 중첩 측정 거절을 단위 테스트한다. |
| W4-BASE-04 | 실제 SQL과 바인딩으로 EXPLAIN·EXPLAIN ANALYZE를 수집한다. 준비 실행 5회·측정 20회의 응답 정확성과 지연 분포를 기록하며 고정된 SQL 개수·밀리초 성능 목표는 assertion하지 않는다. |
| W4-BASE-05 | 기존 상품 조회 회귀·ArchUnit·관련 Checkstyle을 실행한다. 새 테스트가 실행됐는지와 실패·skip·종료 코드를 확인한다. |

기준선 작성 당시 캐시 대상·신선도·실패·복구 정책은 미정이었다. 후속 사용자 답변과 미정 기대값은 아래 [캐시 TDD 제안](#w4-캐시-tdd-제안)에서 구분한다. 이전 W3 테스트 통과를 W4 완료 증거로 재사용하지 않는다.

후속 1단계 [상품 조회 SQL·인덱스 개선](../week4/product-query-optimization.md)을 완료했다. 페이지 경계·동점·전체 인기순 회귀는 기존 구현에서 Green을 확인한 뒤 유지했다. 실제 스키마의 신규 인덱스 부재 Red → 007 추가 SQL Green을 검증하고, 고객 가격순·최신순 쿼리와 인덱스 조합을 측정 후 채택했다. 당시 최종 전체 **559개·Checkstyle·ArchUnit 통과**이며 실행 결과·채택 이유·한계는 해당 기록을 따른다. 이 과거 검사와 이후 완료한 [캐시 검증](../week4/product-list-cache-tdd-log.md)·[최신 검사](commerce-completion-checklist.md#최신-통합-검사)는 구분한다.

### W4 후속: 무필터 최신순·가격순의 페이지 한정 집계

사용자가 2026-10-15 승인한 다음 증분은 Redis 도입 전에 고객 무필터 `latest`·`price_asc`의 좋아요 집계 범위를 줄이는 것이다. 브랜드 필터 경로·`likes_desc`·상세·관리자·본인 좋아요와 기존 응답 계약은 유지한다. 신규 인덱스·비관적 읽기 락·Redis는 추가하지 않는다.

| 사례 | 기대값과 확인 방법 |
| --- | --- |
| W4-GLOBAL-01 | 여러 브랜드의 노출 상품 25개와 삭제 상품·삭제 브랜드 상품을 섞고 두 정렬에서 20/5/빈 페이지, 전체 DTO·동점 ID 내림차순·좋아요 0·품절·total을 검증한다. 기존 구현에서도 Green일 수 있는 결과 보존 테스트다. |
| W4-GLOBAL-02 | 실제 HTTP의 SELECT를 관찰하여 `전체 개수 → 좋아요 없는 상품 페이지 → 선택 ID의 일괄 좋아요 집계`를 검증한다. size 1/20/100과 첫·다음 페이지에서도 집계 바인딩은 해당 페이지 ID와 같고 SELECT가 상품 수에 비례해 증가하지 않아야 한다. 현 상관 조회는 이 선택한 구현 경계를 충족하지 못한다. |
| W4-GLOBAL-03 | 범위 밖·매우 큰 페이지는 전체 개수와 빈 품목을 반환하고 불필요한 페이지·좋아요 SQL을 실행하지 않는다. 노출 상품이 0개인 상태도 빈 items·total 0·totalPages 0과 저장 상태 보존을 확인한다. |
| W4-GLOBAL-04 | 실제 서비스 트랜잭션에서 상품 페이지 SELECT 직후 다른 연결의 가격·좋아요 동시 변경을 commit한다. 기존 실제 RR 읽기 트랜잭션·동일 연결을 확인하고 현재 응답은 변경 전 값으로 일관되며 다음 요청은 변경 후 값을 반환해야 한다. 래치·worker는 유한 대기 후 정리한다. 삭제와 겹친 조회의 미정 제품 정책은 이 테스트에서 확정하지 않는다. |
| W4-GLOBAL-05 | 같은 수동 DDL·10,000개 seed·실제 HTTP로 전후 2라운드 반복 측정한다. 응답 전체 일치·SELECT 수·실행 계획·원시 표본·p50/p95를 기록한다. 추가 왕복을 포함한 이득을 확인하며 고정 ms 합격선을 두지 않는다. |

구조적 Red 확인 후 최소 구현, 기존 회귀·스냅샷 검증, 성능 재측정 순서로 진행했다. `readOnly` 선언만으로 스냅샷을 보장한다고 가정하지 않으며 서비스의 실제 격리 수준을 검증한다. 세 SELECT는 선택한 구현의 비용이지 과제에서 지정한 정답 개수가 아니다. 전체 개수 계산과 상품 정렬 비용은 여전히 남는다. 실제 Red·Green과 전후 측정·최종 검증은 [무필터 목록 개선 기록](../week4/global-list-query-optimization.md)에 남긴다.

### W4 후속: 무필터 좋아요순의 좁은 순위 집계

2026-10-16 사용자가 첫 miss의 집계 비용 개선을 승인했다. 고객 무필터 `likes_desc`의 모든 page/size에 `count → 전체 후보 ID/count 순위 페이지 → 선정 ID 상세 IN → 기존 순위로 합성`을 적용한다. 기본 크기는 20이며 전체 순위를 계산하기 전에 임의로 20개를 자르지 않는다. 브랜드 필터·다른 정렬·상세·관리자·본인 좋아요, API 계약과 기존 읽기 TX/RR·쓰기 락·Redis TTL/payload는 유지한다.

| 사례 | 기대값과 확인 방법 |
| --- | --- |
| RANK-01 | 실제 HTTP·1만 seed의 size 1/20/100과 여러 페이지에서 전체 DTO·순서·total을 독립 기대값과 비교한다. 비어 있지 않은 DB-only의 count/rank/detail 3 SELECT, rank의 ID/count projection·ID GROUP BY, 상세 IN의 ID가 선정 페이지와 같은지 관찰한다. |
| RANK-02 | 좋아요 0개·동점 ID 내림차순·품절·삭제 상품/브랜드와 첫/마지막/범위 밖·매우 큰 페이지, 전체 노출 0개를 확인한다. 빈 페이지는 count만 실행하고 빈 IN이나 불필요한 상세 조회를 만들지 않는다. |
| RANK-03 | 실제 rank SELECT 직후 다른 DB 연결에서 가격·좋아요 변경 또는 상품/브랜드 삭제를 커밋한다. 버전/count/rank/detail의 동일 연결·RR·readOnly TX와 비잠금 조회를 관찰하고 진행 중 전체 DTO는 이전 snapshot, 다음 조회는 변경된 값·순위·노출 결과여야 한다. |
| RANK-04 | 비대상 조회와 기존 캐시·변경·장애·복구·요청 합치기·W3 동시성 회귀를 실행한다. 직접 계산하는 비어 있지 않은 miss는 초기 버전 1+snapshot 4=5 SELECT, hit는 버전 1회다. SQL 관측값만 새 경로에 맞추며 응답·삭제·정합성 기대값을 완화하지 않는다. |
| RANK-05 | 같은 수동 DDL·seed·요청 조건으로 기존 HTTP 및 실제 Redis miss/hit 측정을 변경 전후 반복한다. 추가 상세 왕복까지 포함한 실행 계획·SQL·원시 표본·p50/p95와 전체 결과를 비교하며 고정 ms 합격선을 두지 않는다. 과거 raw 증거를 보존한다. |

실제 Red·Green·회귀와 성능·최종 검사 범위는 [좋아요 순위 집계 TDD 실행 기록](../week4/product-like-ranking-tdd-log.md)의 `RANK-01..05`에서 관리한다. 여기의 계획을 전체 검사 완료로 간주하지 않는다. 이번 변경은 DB 내부 조회 분리이며, 순위 ID만 캐시하고 최신 상세를 별도로 합성하는 미채택 캐시 대안 C와 구분한다.

### W4 후속: 서버 재시작 직후 첫 조회 관찰

사용자가 2026-10-16 요청한 측정으로, 워밍을 구현하지 않고 실제 앱 JVM을 매번 새로 띄워 현재 버전 Redis 캐시 있음/없음 각각 8회의 첫 HTTP를 비교했다. 사전 HTTP 없이 ready를 확인하고 전체 DTO·고유 PID·첫 요청 순번 1·TTL·SQL 구성·hit/miss/오류·DB 상태를 검증했다. DB/Redis는 유지하며 기동 시간과 첫 응답 시간은 분리했다. [측정 방법·원시 결과·해석 한계](../week4/product-list-cold-start-measurement.md)를 따른다. 후속 사용자 승인으로 이번 과제에서는 워밍 미도입·요청 시 채움 유지를 선택했으며, 워밍을 남은 필수 구현으로 두거나 새 시간 합격선을 만들지 않는다.

## W4 캐시 TDD 제안

2026-10-16 최초 제안 뒤 사용자가 Redis + DB 버전 방식(B)의 TDD 구현을 승인했다. 절 제목은 기존 링크를 위해 유지한다. 무필터 좋아요순 첫 페이지와 기존 정렬·응답을 유지하며 아래 기대값으로 구현·검증했다. 후속 실측과 사용자 승인으로 이번 과제의 TTL은 60초 유지로 확정했고 서버 시작 워밍은 도입하지 않는다. 연결/명령 100ms는 기존 설정을 유지한다. 로컬은 사용자의 후속 선택에 따라 기본 OFF이며 캐시 통합 테스트에서 명시적으로 켠다. 최초 Red·Green·검증 범위는 [캐시 실행 기록](../week4/product-list-cache-tdd-log.md), 후속 완료한 [TTL·메모리 실측](../week4/product-list-cache-ttl-memory.md)과 [최신 검사](commerce-completion-checklist.md#최신-통합-검사)는 별도로 연결한다. 현재 선택과 운영 최적성·실제 홈 분포의 추가 검증은 구분한다.

목표는 같은 결과를 더 적은 비용으로 읽으면서 변경·롤백·장애·늦은 채움에서 계약을 지키는 것이다. 단위 테스트만으로 끝내지 않고 실제 애플리케이션·MySQL·Redis 연결에서 검증한다.

| 사례 ID | 준비·통제 방법 | 기대값·기록할 증거 |
| --- | --- | --- |
| W4-CACHE-01 | 키·대상 판별 단위 테스트와 실제 HTTP에서 기본값/명시값·다른 page/size·필터·정렬·사용자 헤더·잘못된 입력 비교 | 대상만 캐시를 사용하고 비대상은 DB에서 정상 제공. 개인화 정보가 공통 payload에 없어야 한다. 좋아요 전부 0·동점도 기존 ID 내림차순이며 오류 계약 유지. |
| W4-CACHE-02 | 동일 seed의 실제 API를 DB-only → 캐시 없음 → 같은 키 재조회 순서로 실행하고 JDBC SQL 관찰 | 전체 DTO·순서·total·페이지 동일. miss는 원본 조회·채움, hit는 비싼 전체 집계 감소. B면 버전 SELECT를 비용에 포함하며 DB 0회를 요구하지 않는다. |
| W4-CACHE-03 | 비어 있는 정상 페이지 채움 후 상품 생성, 현재 첫 페이지 밖 상품의 좋아요 증가·취소, 가격·이름·재고 변경 | 기존 관리자/고객 API를 통해 새 결과·페이지 구성·좋아요 수 반영. 단순 캐시 항목 제거로 개수·total이 틀어지지 않음. 변경 없는 중복 요청의 계약과 무관 데이터 보존. |
| W4-CACHE-04 | 기존 관리자 가격 수정·상품 삭제·브랜드 이름 변경·브랜드 일괄 삭제를 실제 커밋한 후 새 조회 | 새 가격·브랜드명, 삭제 상품 제외와 정확한 페이지. 다른 브랜드·과거 주문 스냅샷·소유권 유지. 정상 변경 직후 TTL 만료까지 기다려야 통과하는 테스트는 금지. |
| W4-CACHE-05 | 전체 영향 쓰기에서 실제 SQL 변경 후 실패 주입 → HTTP/서비스 종료 뒤 독립 DB 재조회 | 업무 변경 전체 롤백, 변경 전 조회 결과 유지, 미커밋 결과 게시 금지. B면 버전도 원상태이며 버전 증가 실패가 업무 변경까지 롤백되는지 별도 검증. |
| W4-CACHE-06 | 서로 다른 상품 변경의 동시 커밋·롤백, no-op 요청, bulk 삭제·주문 확정의 변경 감지 저장 관찰 | 모든 영향 쓰기의 버전 참여와 증가 유실 없음. 업무→버전 잠금 순서·실제 flush SQL 검증. 기존 W3 동시성 결과를 유지하고 버전 행 대기 비용 기록. |
| W4-CACHE-07 | 실제 프록시 읽기에서 버전 SELECT 후 다른 연결 변경 커밋을 끼우고 나머지 원본 조회 수행 | 실제 RR·같은 연결의 버전/total/items 스냅샷 일치. 읽기 종료 후 스냅샷이 반환한 버전에만 저장. `readOnly` 표시만 확인하거나 최신 버전에 옛 데이터를 붙이지 않음. |
| W4-CACHE-08 | 래치로 `옛 DB 결과 읽기 → 관리자 변경 커밋 → 옛 결과 늦게 저장 → 새 조회` 순서를 강제 | 선택한 방식의 늦은 채움 정책 검증. B면 옛 키가 실제 남아도 새 버전만 사용. 삭제 전 진행 중 요청은 이전 스냅샷을 허용하되 삭제 완료 뒤 새 요청은 제외. |
| W4-CACHE-09 | 실제 Redis 중단 중 DB 변경을 커밋하고 옛 키가 남은 상태에서 재조회·복구 | 커밋 사실·기존 관리자 성공 응답·삭제 보호·원본 일치 검증. B는 물리 DEL을 전제하지 않으므로 없는 DEL 실패를 주장하지 않고 옛 키 잔존·실제 GET/SET 오류와 논리적 무효화의 결과를 기록. |
| W4-CACHE-10 | 설정 가능한 짧은 테스트 TTL로 실제 만료를 유한 시간 내 확인, 손상 JSON·다른 응답 스키마 입력 | 만료 뒤 miss·올바른 재채움, hit가 만료를 연장하지 않음. 손상 payload는 잘못된 정상 응답 대신 DB 우회·오류 관찰. 이 기능 테스트만으로 운영 TTL이 최적이라고 판단하지 않음. |
| W4-CACHE-11 | 실제 연결 template·읽기 endpoint를 기록하고 자신이 시작한 Redis 중단·동일 Redis 재시작 | 장애 전 miss/hit, 장애 중 제한된 합산 대기·DB 응답·오류, 복구 후 정확한 재채움/다음 hit 확인. DB 실패는 기존 오류. master/replica를 모두 읽는 설정이면 모두 장애 대상인지 확인. |
| W4-CACHE-12 | 같은 10,000개 seed에서 DB-only/miss/hit, TTL 5/30/60초와 읽기/쓰기 혼합 부하 비교 | 결과가 같은 실행의 원시 표본·p50/p95·비싼 SQL 호출률·대상 비율·hit/miss/오류·키 수/메모리 기록. B의 hit DB 비용·쓰기 대기까지 비교. 고정 ms·임의 적중률을 합격선으로 만들지 않음. |
| W4-CACHE-13 | 영향받은 상품·브랜드·좋아요·주문·포인트·기존 동시성 회귀와 전체 check 실행 | 기존 정확성·락·롤백·접근 경계 유지, Checkstyle·ArchUnit 통과. 실제 건수·실패·skip·종료 코드 기록. 현재 SQL 개선의 과거 통과 수를 새 캐시 증거로 재사용하지 않음. |
| W4-CACHE-14 | `DB 버전 읽기 → 삭제 커밋 → Redis payload 읽기`의 hit 경합을 래치로 통제 | Q05 사용자 확정: 삭제 전 버전을 확인한 진행 중 hit는 옛 결과를 허용한다. 이후 새 조회는 삭제 대상을 제외한다. miss의 늦은 채움과 별도 사례로 검증한다. |
| W4-CACHE-15 | 같은 초기 버전의 동시 miss, 지연된 최초 miss, 이전 버전 계산 중 새 버전 요청 | 고정 버전의 snapshot/SET 공유, 리더 재확인으로 추가 계산 방지, 새 버전 독립과 실제 snapshot 버전 저장. lookup과 snapshot 버전이 바뀌는 혼합 부하에서 무조건 버전당 1회를 요구하지 않음. |
| W4-CACHE-16 | 리더 실패·대기 timeout·interrupt·진행 중 항목 상한·Redis 재확인 실패 | 원래 예외 전파와 항목/슬롯 회수. timeout은 공유 작업을 취소하지 않고 DB 우회·SET 생략. interrupt는 플래그 복구 후 실패. 재확인 GET 오류도 DB 우회. 기존 TX·락 유지. |

### 측정과 자원 정리

2026-10-16 사용자가 W4-CACHE-12의 TTL 5/30/60초·키 수/메모리 실측을 요청했다. [고정한 실험 조건](../week4/product-list-cache-ttl-memory.md)에 따라 실제 앱 3개의 지속·드문·변경 동반 조회와 자연 만료를 비교했다. 운영 설정은 유지했고, 긴 실측은 신규 태그/전용 `ttlMeasurement` 태스크에서 실행했다. 기존 회귀를 제외하거나 계획만으로 실험 완료를 표시하지 않는다.

**후속 실행 완료:** 조회 11,727회·좋아요 변경 390회, TTL별 실제 만료·메모리·종료 후 61초 키 0을 검증했다. 지속 조회의 재계산은 39/7/4회, 변경 조회는 각각 390회로 같았고 키 할당 메모리 최대는 약 31/187/374 KiB였다. 일반 `check` 742개와 전용 측정 1개는 각각 통과했고 Checkstyle 위반 0이다. [결과·실패한 계측 해시 수정·확정 선택](../week4/product-list-cache-ttl-memory.md)을 따르며 TTL 60초·업무 코드/트랜잭션/락은 유지했다. 이후 사용자 승인으로 TTL 유지와 워밍 미도입을 확정했으며 이 문서 결정 때문에 기존 검사 기대값을 바꾸지는 않는다. 아래 과거 증분의 미완료 표시는 당시 범위를 뜻한다. 실제 홈 분포·운영 최적성은 별도 검증 사항이다.

W4-CACHE-12의 첫 후속 증분은 TTL 60초 고정·조회 200회에 좋아요 변경 0/20/100회를 섞는 OFF/ON 비교다. [혼합 실험 계획·결과](../week4/product-list-cache-mixed-load.md)에 테스트 방법과 한계를 분리했다. 2026-10-16 Docker 초기화 실패 후 사용자 승인으로 앱 프로세스만 복구하여 실제 측정을 완료했다. OFF/ON 두 라운드의 전체 응답·요청별 SQL·최종 DB 관계와 버전·마지막 별도 변경 후 새 조회를 검증했다. 초당 10회 변경에서 이득이 없었던 결과도 보존하며 기대값이나 운영 정책을 완화하지 않는다. 당시에는 TTL 비교·메모리까지 측정하지 않았고 이후 위 후속 실행에서 완료했다. 운영 적중률은 여전히 미확인이다.

같은 날 사용자 요청으로 [버전별 중복 계산·miss 시간 분해](../week4/product-list-cache-diagnostics.md)를 추가 측정했다. 테스트 전용 `RequestStageProbe`의 단위 테스트 9개, 기존 혼합 조건의 실제 lookup/snapshot/store 버전과 단계 시간, 별도 8명 동시 조회 3회를 검증했다. 반환 버전과 fixture 전체 DTO의 일치를 추가로 검사하고 기존 SQL·DB 기대값은 유지했다. 12:07 전체 705개·Checkstyle·ArchUnit 통과. 이 진단 증분에서는 운영 코드·TTL·락을 변경하지 않았고, 당시 미구현 제안이던 요청 합치기는 다음 증분에서 구현·검증했다.

그 뒤 사용자 승인으로 W4-CACHE-15·16의 [요청 합치기 TDD](../week4/product-list-single-flight-tdd-log.md)를 완료했다. primitive Red 13개 중 8실패 → Green 13통과, 서비스 Red 4개 중 3실패 → 연결 후 보강 포함 6통과를 확인했다. DB-only/기존 캐시/요청 합치기 세 모드를 같은 실행에서 비교하며 초기 GET·재확인·결과 공유·DB 우회와 실제 집계를 분리했다. 해당 증분의 성능 수치는 연결한 기록을 보존하고 이후 [좋아요 순위 집계 개선](../week4/product-like-ranking-tdd-log.md)·[최신 검사](commerce-completion-checklist.md#최신-통합-검사)와 구분한다.

- 성능 비교 전에 요청 조건·읽기/쓰기 비율·빈도·동시성·준비 실행·표본 수·기간을 고정한다. 변경 없는 반복 읽기만으로 운영 적중률을 주장하지 않는다. TTL별 실험은 각각 여러 만료 주기를 포함한다.
- 적중률은 캐시 대상 요청 중 유효 hit 비율로 기록하고 miss·Redis 오류 우회·비대상 요청도 따로 공개한다. 실제 첫 페이지 접근 비율을 모르므로 전체 API가 같은 비율로 빨라진다고 확대 해석하지 않는다.
- 경합은 단순 동시 시작과 결정적 순서 통제를 구분한다. 시간 초과·기술 오류를 정상 경합 결과로 숨기지 않으며 모든 래치·worker·연결은 유한 대기와 `finally` 정리를 둔다.
- 테스트 트랜잭션 하나로 전체 상황을 감싸지 않는다. 커밋/롤백 뒤 별도 DB 연결과 실제 HTTP 결과를 대조한다. 장애 실험은 소유한 자원만 중단·복구하며 공유 Redis의 FLUSHALL을 하지 않는다.

### 구현 순서와 영향 파일

| 순서 | 먼저 검증할 것 | 변경 후보 책임·파일 범위 |
| --- | --- | --- |
| 1 | Q02의 B 선택과 Q05 진행 중 hit 허용 답변을 반영하고 기대값 구체화 | 설계·정책·TDD 문서. 최초 시간 설정은 초기 실험값으로 구분했고, 후속 비교·승인으로 TTL 60초 유지를 확정했다. |
| 2 | DB 원자성·영향 쓰기·경합부터 검증 | `AdminProductService`, `AdminBrandService`/브랜드 일괄 삭제, `LikeService`, `OrderService`. 버전 포트·저장 구현·수동 DDL/초기값·스키마 검증 추가. 캐시를 켜기 전에도 쓰기 비용은 발생한다. |
| 3 | 키·대상·payload·TTL → miss/hit → 실제 스냅샷·변경 연결 | `ProductQueryService`의 조율/DB 읽기 경계, domain 포트·결과, infrastructure Redis 어댑터·조회 구현과 전용 설정을 검토. Controller/API 계약은 유지. |
| 4 | 롤백·늦은 채움·실제 장애/복구 → 성능 비교 → 전체 회귀 | 단위·통합·HTTP·경합·성능 테스트와 실행 기록. 기존 `RedisConfig`의 다른 사용처에 미치는 설정 영향도 확인. |

각 증분은 테스트 작성 → 의도한 Red 확인 → 최소 구현 → 회귀/Refactor로 진행한다. 이미 만족하는 기존 계약 테스트는 처음부터 Green일 수 있으며 인위적으로 깨뜨리지 않는다. 버전 저장·영향 쓰기·캐시 조율·Redis 어댑터에서 기대 실패를 확인하고 구현했다. 이후 실제 HTTP·스냅샷·장애·성능 테스트는 구현 후 확장 검증이며 과거 Red로 포장하지 않는다.

## 전체 구현 완료 목표

이 절은 W2~W4의 완료 범위·과거 진행 순서다. W5의 추가 API·시도·예약·PG 복구 완료 범위는 위 [6단계와 9개 항목](#w5-pg-결제-tdd-계획)으로 별도 판정한다.

2026-09-18 사용자가 현재 구상한 범위의 TDD를 끝까지 진행하도록 요청했다. 작은 증분 하나의 통과를 전체 완료로 간주하지 않는다. [전체 완료 체크리스트](commerce-completion-checklist.md)에 API·테스트·운영 검증의 증거를 기록한다. 최종 범위는 C01~C12·A01~A13의 25개 API, 아래 API-01~24의 업무·경계·저장·동시성 사례, 실제 DB 제약·운영 스키마, 기존 예시 회귀와 Checkstyle·ArchUnit, 구현과 일치하는 문서다.

진행 순서는 브랜드 HTTP → 관리자 브랜드·상품 → 상품 조회·좋아요 → 포인트 → 주문 생성·조회·확정·관리자 조회 → 경합·롤백과 최종 검증이다. 테스트를 먼저 실행해 의도한 실패를 확인한 뒤 최소 구현·리팩터링을 반복한다. C01 이후 금액·나머지 API·잠금 세부 정책도 사용자 승인으로 확정했다. JPA를 유지하며 기능 구현을 먼저 진행하고 나머지 운영 수동 DDL은 기능 구현 후 작성·검증한다(P14).

## 현재 단계와 이번 구현

아래 표의 “현재”는 W5 교체 전 구현 기록을 뜻한다. 신규 PG 코드·테스트 완료를 나타내는 표가 아니며, 특히 포인트 차감·확정 재요청 계약의 전환은 위 W5 계획을 따른다.

| 단계 | 현재 상태와 증거 |
| --- | --- |
| 1. 재고 차감·최종 수량 설정 | ProductStock 규칙을 Product의 생명주기·JPA·관리자 재고 API·주문 차감에 연결했다. [상품 기록](commerce-tdd-product-log.md) |
| 2. 사용자 식별·권한 | 단일 fixture, DB 초기 잔액 0과 재실행 보존, 본인·관리자 진입 검사 완료. [포인트·좋아요 기록](commerce-tdd-point-like-log.md) |
| 3. 브랜드 CRUD | W2의 고객 상세·관리자 CRUD·상품 존재 조회·잠금·경합을 검증했다. [브랜드 HTTP 기록](commerce-tdd-admin-brand-http-log.md)은 과거 증거다. W3 일괄 삭제의 `W3-BRAND-01..08`은 아래 계획과 별도 실행 기록에서 완료 증거를 추적한다 |
| 4~7. 상품·좋아요·포인트 | 변경·조회·집계·페이지·현재 관계·충전과 잔액 조회를 HTTP까지 검증했다. [상품](commerce-tdd-product-log.md) · [포인트·좋아요](commerce-tdd-point-like-log.md) |
| 8~10. 주문 | W2의 생성·스냅샷·조회·확정·재요청·동시성·실제 flush 후 롤백 증거는 [당시 주문 기록](commerce-tdd-order-log.md)에 보존한다. P16의 재확정 409 변경은 W3-RECONFIRM-01..06 구현·검증 완료이며 [W3 재확정 기록](../week3/order-reconfirmation-tdd-log.md)으로 구분 |
| W3 주문 저장 경계 | 실제 JDBC UPDATE 일부 성공 후 다음 UPDATE 실패와 전체 롤백, 같은 DRAFT 재시도를 W3-ORDER-TX-01..03으로 검증 완료. [이번 실행 기록](../week3/order-transaction-tdd-log.md)으로 기존 W2 증거와 구분 |
| W3 갱신 유실 대조군 | W3-LOST-UPDATE-01 구현·검증 완료. 두 커밋 이후 재고 4·불변식 위반을 재현했으며 생산 코드 변경 없음. [계획·실행 기록](../week3/stock-lost-update-control-plan.md) |
| W3 재고 8주문 경쟁 | W3-STOCK-RACE-01 검증 완료. 확정 5·재고 부족 3·기술 오류 0·재고 0, 구매자별 성공 결제만 차감. [실행 기록](../week3/stock-order-concurrency-tdd-log.md) |
| W3 포인트 3주문 경쟁 | W3-POINT-RACE-01 검증 완료. 확정 2·포인트 부족 1·기술 오류 0·잔액 2,000, 거절 주문/상품과 무관 데이터 보존. [실행 기록](../week3/point-order-concurrency-tdd-log.md) |
| W3 충전·결제 경쟁 | W3-CHARGE-ORDER-01 검증 완료. 두 요청 성공·잔액 5,000·주문/재고 정합성, 당시 관련 50개와 후속 전체 549개·Checkstyle·ArchUnit 통과. [실행 기록](../week3/point-charge-order-concurrency-tdd-log.md) |

**W2 기준 25개 API가 HTTP부터 실제 DB까지 연결됐다.** 이 증거가 W3 일괄 삭제의 통과를 뜻하지는 않는다. 테스트 수나 API 수를 전체 개발 완료율로 환산하지 않는다. 최종 전체 회귀·Checkstyle·ArchUnit·수동 SQL·문서 검증은 [완료 체크리스트](commerce-completion-checklist.md#최신-통합-검사)에 실제 실행 결과를 기록한다.

Layer-first·빌드 모듈·기존 ArchUnit 규칙을 유지한다. application은 domain의 약속을 사용하며 infrastructure 구현이나 controller DTO를 참조하지 않는다. HTTP 오류는 커머스 경로의 공통 처리기로 변환하고 Example 계약은 유지한다. 실습 fixture를 사용하며 로그인은 추가하지 않는다.

아래는 작은 도메인 규칙에서 시작해 연결한 **단계별 기대값과 선행 증거**다. 과거 단계의 테스트 수를 최신 전체 검사 수로 사용하지 않는다. 정책은 P00~P16의 확정 계약을 따른다. 일반 Spring 테스트는 ddl-auto=create, 운영 SQL 검증은 별도 MySQL에서 수동 적용 후 validate를 사용한다.

## W3 주문 재확정 거절의 TDD 계획

2026-10-08 사용자가 P16을 승인했다. 본인 CONFIRMED 주문의 재POST는 `409 ORDER_ALREADY_CONFIRMED` / `이미 확정된 주문입니다.` / `meta.result=FAIL`·`data` 생략으로 변경했다. W2의 성공 재사용·같은 주문 동시 성공 결과 공유는 과거 계약으로 남기고, 현재 API-14·API-23은 아래 새 기대값을 사용한다. **W3-RECONFIRM-01..06 구현·검증 완료**이며 [재확정 실행 기록](../week3/order-reconfirmation-tdd-log.md)에 실제 Red·Green·회귀 결과를 남겼다.

| 사례 ID | 준비·실행 | 기대 결과 |
| --- | --- | --- |
| W3-RECONFIRM-01 | 도메인 Order를 정상 확정한 뒤 두 번째 확정 행동 호출 | 이미 확정된 상태 오류. 최초 결제액·확정 시각·스냅샷·상태 보존 |
| W3-RECONFIRM-02 | 실제 최초 확정 성공 후 본인이 같은 주문을 재POST | `409 ORDER_ALREADY_CONFIRMED`, 정확한 실패 본문·data 생략. 추가 재고·포인트 차감이나 저장 변경 없음. 별도 GET은 최초 확정 결과 반환 |
| W3-RECONFIRM-03 | 타인이 CONFIRMED 주문에 재POST | 상태 오류가 아니라 소유권 검사에서 먼저 `404 ORDER_NOT_FOUND`. 상태·소유자 정보 미노출, DB 불변 |
| W3-RECONFIRM-04 | 최초 확정 뒤 상품 또는 브랜드를 삭제하고 재POST·GET | 재POST는 동일한 409 상태 오류이며 상품 없음 오류로 바뀌지 않음. GET으로 기존 확정 스냅샷 조회, 추가 차감·저장 변경 없음 |
| W3-RECONFIRM-05 | 조건을 충족한 같은 DRAFT에 확정 요청을 동시에 실행 | 최초 성공 한 건만 200·CONFIRMED·한 번의 재고/포인트 차감. 성공 이후 상태를 확인한 요청은 `409 ORDER_ALREADY_CONFIRMED`. 잠금 타임아웃·교착을 상태 오류나 성공으로 숨기지 않음 |
| W3-RECONFIRM-06 | 재고 또는 포인트 부족으로 DRAFT 확정 실패 후 조건을 개선하고 다시 요청 | 최초 실패 시 DRAFT·저장 상태 보존, 조건 개선 후 확정 성공. 이미 성공한 주문의 재확정 거절과 구분 |

도메인 Red → 최소 상태 검증 → HTTP와 저장 상태 보존 → 소유권·삭제 경계 → 같은 주문 경합·기존 DRAFT 재시도 회귀 순서로 진행한다. 기존 200 기대값을 바꾸는 근거는 P16 사용자 승인이고 단순 검사 완화가 아니다. 과거 W2 주문 실행 로그와 브랜드 W3 완료 기록은 수정하거나 삭제하지 않는다. 이번 계획은 W3의 별도 재고 8주문·포인트 3주문 경쟁, 주문 내부 저장 경계 실패/롤백 검증, 갱신 유실 대조군 과제를 대신하지 않는다.

## W3 주문 저장 경계 실패와 롤백의 TDD 계획

사용자가 승인한 다음 증분은 여러 품목의 최초 확정이 **일부 SQL까지 실행된 뒤 실패해도 전부 취소되는지** 확인하는 것이다. 기존 업무 정책·트랜잭션을 유지하고 테스트부터 작성했다. 생산 코드 변경 없이 기존 구현이 기대값을 만족함을 확인했다. 환경 오류·테스트 시각 비교 교정과 최종 통과를 [주문 트랜잭션 TDD 기록](../week3/order-transaction-tdd-log.md)에 구분하며, 인위적인 업무 Red나 구현 변경을 만들지 않았다.

| 사례 ID | 준비·실행 | 기대 결과 |
| --- | --- | --- |
| W3-ORDER-TX-01 | A 단가 1,000·수량 2·재고 5, B 단가 2,000·수량 1·재고 4, 잔액 10,000으로 DRAFT를 생성하고 실제 HTTP로 최초 확정 | `200`·CONFIRMED·총액/결제액 4,000·확정 시각 저장. 재고 A=3·B=3, 잔액 6,000. 품목 스냅샷과 무관한 데이터 보존 |
| W3-ORDER-TX-02 | 같은 준비에서 JDBC UPDATE의 두 번째 또는 네 번째 실행 직전에 테스트 전용 일반 SQLException 주입. 앞선 실제 UPDATE는 각각 1회 또는 3회 성공해야 함 | `500 INTERNAL_ERROR`·`meta.result=FAIL`·`data` 생략. 실패 직전 같은 connection에서 미커밋 변경이 존재함을 관찰하고, HTTP 종료 뒤 별도 JDBC 조회의 6개 테이블 전체 행은 요청 전과 동일. DRAFT·재고·잔액·결제 결과 보존 |
| W3-ORDER-TX-03 | TX-02의 실패 장치를 해제한 뒤 동일 DRAFT를 실제 HTTP로 다시 확정 | 정상 `200`·한 번의 차감으로 TX-01 결과에 도달. 이후 재확정은 기존 P16의 `409 ORDER_ALREADY_CONFIRMED`이며 추가 변경 없음 |

검증은 `OrderTransactionTest`의 RANDOM_PORT HTTP → 실제 Spring 서비스·repository·MySQL을 통과한다. 초기 데이터를 요청 전에 commit하고 테스트 전체에 `@Transactional`이나 외부 트랜잭션을 두지 않는다. 테스트 전용 JDBC 장치는 SQL 준비만 센 것이 아니라 실제 delegate의 execute 성공을 수집한 후 다음 UPDATE에서 실패시킨다. 실패 직전 같은 DB connection의 관찰값은 중간 변경의 증거이며, 롤백 증거는 HTTP 종료 후 별도 조회에서 얻는다. 6개 테이블은 `brand`·`product`·`order`·`order_item`·`user`·`like`다.

Hibernate가 실행하는 UPDATE 순서를 도메인 메서드 호출 순서와 같다고 가정하지 않는다. 성공한 SQL과 대상·중간 저장 상태를 함께 기록하며 일반 SQLException을 업무상 재고/포인트 부족이나 잠금 충돌로 집계하지 않는다. assert는 요청 종료 뒤 수집값에 대해 수행하고 실패 장치는 항상 정리한다. 호출·커밋 경계는 [전체 설계](commerce-erd-draft.md#최초-확정의-실제-트랜잭션-진입과-sql-저장-경계)를 따른다.

이번 범위는 주문 내부 저장 경계의 정상·실패·재시도 검증이다. 갱신 유실 대조군·재고 8주문·포인트 3주문·충전/결제 경쟁은 아래 별도 증분에서 검증했다. 이를 포함한 전체 검사와 실제 제출 상태는 [완료 체크리스트](commerce-completion-checklist.md)에서 구분한다.

## W3 갱신 유실 대조군의 TDD 계획

현재 상태는 **테스트 구현·재현 검증 완료**다. 제품 코드의 실패를 새로 만드는 작업이 아니라, 보호하지 않은 읽기·쓰기의 문제를 테스트 안에서 재현하는 음성 대조군이다. 자세한 절차·자원 정리·영향 범위는 [계획·실행 기록](../week3/stock-lost-update-control-plan.md)을 따른다.

| 사례 ID | 준비와 실행 | 기대 결과 |
| --- | --- | --- |
| W3-LOST-UPDATE-01 | 커밋된 재고 5를 서로 다른 연결의 READ_COMMITTED 트랜잭션 2개가 일반 SELECT로 조회. 두 값이 5임을 확인한 뒤 각자 계산한 상수 4를 저장 | 커밋 성공 2·기술 오류 0·최종 재고 4, `2 + 4 ≠ 5`를 assertion. 정상적인 최종 재고 3과 다른 현상이 재현되면 대조군 테스트는 통과 |

구현한 클래스는 [StockLostUpdateControlTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/StockLostUpdateControlTest.java)다. HTTP·실제 OrderService를 통하지 않고 테스트 내부 JDBC로 재현하며, 실제 서비스의 Repository 잠금은 제거하지 않는다. UPDATE 반환 행 수를 커밋 성공 횟수로 사용하지 않는다. 조회 후 장벽은 이 대조군에만 두고, 두 worker가 커밋까지 끝난 뒤 별도 DB 조회로 검증한다. timeout·SQL 오류·미종료를 재현 성공으로 처리하지 않는다.

새 테스트는 첫 실행부터 통과했다. 잘못된 업무 결과의 재현 자체를 assertion하는 증분이므로 업무 Red를 관찰했다고 기록하거나 기존 기대값을 완화하지 않는다. 관련 회귀·전체 검사 결과는 연결한 실행 기록에서 관리하며, 대조군의 통과는 후속 8개 주문 경쟁의 완료 증거가 아니다.

## W3 재고 8주문 경쟁의 TDD 계획

`W3-STOCK-RACE-01`은 재고 5개를 각 1개씩 구매하는 서로 다른 주문 8개의 경쟁이다. **테스트 추가·재현 검증 완료**이며 [준비 조건과 실행 기록](../week3/stock-order-concurrency-tdd-log.md)을 따른다. alice·bob의 잔액을 각각 10,000원으로 준비해 포인트 부족이 재고 경쟁을 가리지 않도록 했다.

| 사례 ID | 실행 | 기대값 |
| --- | --- | --- |
| W3-STOCK-RACE-01 | 독립 worker 8개의 시작만 맞춰 실제 서비스 프록시로 각 DRAFT 확정. 모두 종료한 뒤 DB 재조회 | 성공 5·INSUFFICIENT_STOCK 3·기술 오류 0·재고 0. 주문 ID별 확정·거절 저장 상태, 수량 보존식과 구매자별 잔액 보존식, 품목 스냅샷·무관 데이터 보존 |

실제 서비스의 트랜잭션·비관적 잠금을 유지하고 테스트부터 실행해 첫 실행에서 통과했다. 기존 2개 주문 경쟁·갱신 유실 대조군을 이 8개 주문의 증거로 대신하지 않으며, 관련 회귀·전체 결과는 별도 실행 기록과 완료 체크리스트에 남긴다.

## W3 포인트 3주문 경쟁의 TDD 계획

`W3-POINT-RACE-01`은 잔액 10,000원인 한 사용자의 서로 다른 4,000원 주문 3개 경쟁이다. **테스트 추가·검증 완료**이며 [확인한 계약과 실행 기록](../week3/point-order-concurrency-tdd-log.md)을 따른다. 주문별 브랜드·상품을 분리하고 재고를 5개씩 준비하여 재고 부족이나 공통 상품 잠금이 포인트 경쟁을 가리지 않도록 했다.

| 사례 ID | 실행 | 기대값 |
| --- | --- | --- |
| W3-POINT-RACE-01 | 독립 worker 3개의 시작만 맞춰 실제 서비스 프록시로 각 DRAFT 확정. 모두 종료한 뒤 DB 재조회 | 성공 2·INSUFFICIENT_POINTS 1·기술 오류 0·잔액 2,000. 성공 결제 합 8,000, 성공 상품 재고 각 4·거절 상품 5, 거절 DRAFT 전체 행·품목·무관 데이터 보존 |

테스트부터 추가해 첫 실행에서 통과했으므로 서비스·잠금 정책은 변경하지 않았다. 실제 Red·Green 여부와 관련 회귀 결과는 연결한 실행 기록을 따른다. 과거 W2의 2주문 경쟁이나 W3 재고 8주문 통과를 이번 지정 수치의 완료 증거로 대신하지 않는다.

## W3 충전과 주문 결제 경쟁의 TDD 계획

`W3-CHARGE-ORDER-01`은 잔액 10,000원에 충전 2,000원과 주문 확정 7,000원을 동시에 반영하는 검증이다. **신규·관련·전체 검증 완료**이며 [준비 조건과 실행 기록](../week3/point-charge-order-concurrency-tdd-log.md)에 실제 결과를 구분한다. 첫 실행부터 통과하여 생산 코드는 수정하지 않았다.

| 사례 ID | 실행 | 기대값 |
| --- | --- | --- |
| W3-CHARGE-ORDER-01 | 충분한 재고의 두 품목 DRAFT를 준비하고 독립 worker 2개의 시작만 맞춰 실제 충전·주문 서비스 프록시 호출. 모두 종료한 뒤 DB 재조회 | 두 요청 성공·업무 거절 0·기술 오류 0, 잔액 10,000+2,000−7,000=5,000. 같은 주문의 CONFIRMED·결제액 7,000·품목별 재고 차감과 스냅샷·무관 데이터 보존 |

충전 응답의 잔액은 실행 순서에 따라 12,000 또는 5,000이며 최종 DB 잔액 5,000과 구분한다. P09·P13의 기존 트랜잭션·사용자 행 잠금은 유지한다. 신규·관련 회귀에 이어 전체 Checkstyle·ArchUnit 포함 재검사도 완료했다. 제출용 기술 글은 사용자가 작성하며 PR 초안 준비와 실제 제출은 구분한다.

## 대표 도메인 규칙의 TDD 계획

대표 규칙은 **상품 재고보다 많은 수량을 차감할 수 없으며, 성공하면 남은 재고가 정확해야 한다**다. 상품 자신의 상태와 차감 수량으로 판단하므로 `Product` 책임을 제안한다. 첫 구현은 상품이 소유할 `ProductStock` 객체에 수량 규칙을 둔다. 이 선행 단계 이후 상품 엔티티·삭제 상태(STOCK-06)와 API를 연결했으며 증거는 위 현재 상태와 기능별 실행 기록에 구분한다.

| 사례 ID | 초기 상태·입력 | 도메인 단위 테스트 기대값 | API 연결 |
| --- | --- | --- | --- |
| STOCK-01 | 미삭제 상품, 재고 5, 2개 차감 | 성공, 재고 3 | C10 성공 시 상품 상태 변경 |
| STOCK-02 | 미삭제 상품, 재고 5, 6개 차감 | 재고 부족 오류, 재고 5 유지 | C10의 `409 INSUFFICIENT_STOCK` |
| STOCK-03 | 재고 5, 5개 차감 | 성공, 재고 0 | 정확히 재고만큼 주문 확정 가능 |
| STOCK-04 | 재고 0, 1개 차감 | 재고 부족 오류, 재고 0 유지 | 품절 상품 주문 확정 거절 |
| STOCK-05 | 재고 5, 0 또는 -1개 차감 | 양수 수량 규칙 위반, 재고 5 유지 | C09의 입력 오류는400, C10의 저장 수량 불변식 위반은500으로 구분 |
| STOCK-06 | 삭제 상품, 재고 5, 2개 차감 | 삭제 상태 오류, 재고 5 유지 | C10의 `404 PRODUCT_NOT_FOUND` |

이번 확장은 `ProductStock`의 **0 이상인 최종 수량 설정**이다. 증감량을 더하지 않고 현재 수량을 교체하며, 잘못된 수량은 변경 전에 거절한다. 상품 엔티티·관리자 API보다 이 수량 규칙을 먼저 구현하는 순서는 사용자 승인 범위다.

| 사례 ID | 초기 상태·입력 | 도메인 단위 테스트 기대값 | 후속 연결 |
| --- | --- | --- | --- |
| STOCK-SET-01 | 재고 10에서 최종 수량 3 설정 | 재고 3. 기존 수량에 더하지 않음 | A11·API-19 |
| STOCK-SET-02 | 양수 재고에서 최종 수량 0 설정 | 재고 0 허용 | A11·API-19 |
| STOCK-SET-03 | 기존 재고에서 -1 또는 `Integer.MIN_VALUE` 설정 | 잘못된 재고 수량 오류, 기존 수량 유지 | A11·API-19의 오류 변환은 별도 검증 |
| STOCK-SET-04 | `Integer.MAX_VALUE`, 현재보다 큰 수량, 현재와 같은 수량 설정 | 각각 입력한 최종 수량을 정확히 유지 | A11·API-19. Integer 초과 HTTP 입력 검증은 별도 |
| STOCK-SET-05 | 수량 설정 후 차감, 차감 후 수량 설정 | 두 동작이 같은 현재 수량을 사용하고 각각의 규칙을 유지 | A11·C10의 상품 연결·경합도 상품/주문 통합 테스트에서 검증 |

도메인 테스트는 HTTP 상태를 반환하도록 엔티티를 만들지 않는다. 도메인 실패를 위 응답으로 변환하는 책임은 API 계층이고, 그 연결은 별도 API 테스트에서 검증한다.

1. **Red:** 먼저 STOCK-01·02의 정상·대표 오류 기대값을 테스트로 작성한다. 최소 타입·메서드 골격을 준비하고, 의도한 재고 상태 또는 부족 검사가 없어 실패하는지 확인한다. DB 접속·환경 설정 실패를 업무 규칙의 Red로 기록하지 않는다.
2. **Green:** 해당 테스트를 통과시키는 최소 재고 검사·차감만 구현한다. 변경 전에 검증하여 실패 시 재고를 유지한다.
3. **Refactor:** 중복 검사·테스트 준비 코드를 정리하고 이름·책임을 다듬는다. 같은 테스트를 재실행해 기대값이 유지되는지 확인한다.
4. STOCK-03~05와 STOCK-SET-01~05를 한 사례씩 추가하며 같은 사이클을 반복한다. STOCK-06은 상품 생명주기와 연결할 때 구현한다. 테스트나 기대값을 삭제·완화해서 통과시키지 않는다.

[ProductStockTest](../../apps/commerce-api/src/test/java/com/loopers/domain/product/ProductStockTest.java)는 STOCK-01~05와 STOCK-SET-01~05, 초기 재고·정수 경계·연속 차감을 검증한다. 파일명 자체는 요구사항이 아니다. 아래 명령의 실제 실패·성공 이유와 리팩터링 결과는 [실행 기록](commerce-tdd-stock-log.md)에 남긴다.

```shell
./gradlew :apps:commerce-api:test --tests '*ProductStockTest'
```

상품 한 개의 수량 단위 테스트는 상품 엔티티의 삭제 상태, 관리자 권한·HTTP 계약, DB 행 잠금이나 주문 전체의 원자성·동시성을 증명하지 않는다. 이 연결은 상품/주문 통합 테스트에서 별도로 검증했다. C10의 여러 상품·잔액·주문 상태와 동시성은 OrderConcurrencyIntegrationTest와 주문 HTTP/저장 테스트가 증거다.

## 사용자 식별·권한 해석의 TDD 계획

선행 사용자 식별 TDD에서는 application의 `UserResolver`가 domain 소유 `UserIdentityRepository`를 통해 외부 ID를 내부 사용자 ID·역할(`UserIdentity`, `UserRole`)로 해석하고 관리자 여부를 확인하는 것이다. 공통 규칙은 조회 대역으로, 확정한 매핑은 실제 fixture와 Spring 연결로 검증한다. 실패는 HTTP와 독립된 `UserResolutionException` 사유로 구분한다.

| 사례 ID | 초기 상태·입력 | 단위 테스트 기대값 |
| --- | --- | --- |
| IDENTITY-01 | 조회 대역에 등록된 외부 ID로 사용자 해석 | 연결된 내부 사용자 ID와 역할 반환 |
| IDENTITY-02 | 조회 대역에 없는 외부 ID로 사용자 해석 | 사용자를 찾을 수 없는 사유로 거절 |
| IDENTITY-03 | `null`, 빈 문자열, 공백뿐인 외부 ID로 사용자 해석 | 잘못된 식별 입력 사유로 거절하고 repository를 조회하지 않음 |
| IDENTITY-04 | ADMIN 사용자의 외부 ID로 관리자 검사 | 해석한 관리자 식별 정보 반환 |
| IDENTITY-05 | CUSTOMER 사용자의 외부 ID로 관리자 검사 | 권한 부족 사유로 거절 |
| IDENTITY-06 | 잘못된 입력 또는 미등록 외부 ID로 관리자 검사 | IDENTITY-03·02의 식별 실패 사유를 유지하며 권한 부족으로 바꾸지 않음 |
| IDENTITY-07 | 실제 fixture에 alice·bob·admin 조회 | 각각 1/CUSTOMER·2/CUSTOMER·3/ADMIN 반환 |
| IDENTITY-08 | 실제 fixture에 unknown·문자열 1·2·3 조회 | 매핑 없음. 내부 숫자 ID를 외부 식별값으로 대체하지 않음 |
| IDENTITY-09 | Alice·ADMIN 또는 앞뒤 공백이 붙은 alice·admin 조회 | 원문이 다르므로 매핑 없음. trim·대소문자 변환 없음 |
| IDENTITY-10 | Spring이 fixture 구현을 UserResolver에 주입 | 확정 매핑·관리자 역할 검사·원문 비교가 조립 후에도 유지 |

P03의 후속 답변으로 고정 실습값·역할과 원문 일치 정책을 확정했다. `FixtureUserIdentityRepository`가 불변 Map 한 곳에서 매핑을 관리한다. 기존 조회 대역의 임의 값은 이 실습 매핑과 구분한다. DB 사용자 행·잔액·HTTP 진입점은 후속 [포인트·좋아요 TDD](commerce-tdd-point-like-log.md)에서 연결·검증했다.

이 단위 테스트만으로 **API-21 전체 완료를 뜻하지 않는다**. 관리자 HTTP 경로는 후속 P15에 따라 fixture 역할 대신 Spring Security 역할을 사용하며, 해당 필터·CSRF 연결은 [관리자 Security 기록](commerce-admin-security-log.md)에서 검증한다. 관리자 경로의 권한 검사를 HTTP 입력 바인딩보다 먼저 실행하는 P10 연결과 구체 오류 응답·헤더 처리는 별도로 검증한다. 현재 `UserResolver`와 fixture 구현은 Spring 컴포넌트로 등록하고 domain 조회 계약을 생성자로 주입한다. 실제 Red·Green·Refactor 결과는 [사용자 식별 실행 기록](commerce-tdd-user-identity-log.md)에 남긴다.

## 브랜드 이름의 TDD 계획

P02에서 확정한 브랜드 이름 규칙을 `domain/brand/BrandName` 불변 값객체가 담당한다. 이름 생성 시 앞뒤 공백을 제거하고 결과가 Unicode 코드 포인트 기준 1~100자인지 검사한다. 실패는 HTTP와 독립된 `BrandNameException` 사유로 구분한다. 브랜드 이름의 선행 승인과 구분하여, 상품명의 같은 계산 방식도 후속 P02 묶음 승인으로 확정했다.

| 사례 ID | 입력 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-NAME-01 | 앞뒤 공백·탭·개행이 붙은 `Loopers  Brand` | 앞뒤 공백만 제거하고 내부 공백·대소문자 유지 |
| BRAND-NAME-02 | null·빈 문자열·공백만 있는 문자열 | EMPTY_NAME으로 거절 |
| BRAND-NAME-03 | 앞뒤 공백이 붙은 한글·이모지 각각 1개 또는 100개 | 공백 제거 후 원문 유지. 😀 하나를 1자로 계산 |
| BRAND-NAME-04 | 한글·이모지 각각 101개 | NAME_TOO_LONG으로 거절 |

4개 규칙의 13개 실행 사례를 [BrandNameTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandNameTest.java)로 검증했다. 실제 실패·통과와 리팩터링은 [브랜드 이름 실행 기록](commerce-tdd-brand-name-log.md)에 남긴다. 이름 규칙을 `Brand`의 생성·이름 변경·생명주기에 연결한 결과는 다음 절들에 구분한다. Brand의 기본 저장·조회는 아래 영속성 단계에 연결했다. 유스케이스·HTTP 연결과 API-16·18의 DB 상태 보존은 후속 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)에서 검증했다.

## 브랜드 생성·이름 변경의 TDD 계획

이름 규칙을 다시 구현하지 않고 `BrandName`을 소유하는 `Brand`에 연결한다. 기존 이름을 교체하기 전에 새 이름을 검증하여 실패 시 현재 상태를 보존한다. 관련 계약은 P02와 API-16·18의 이름 입력 및 실패 후 상태 보존이다. HTTP 응답과 DB 저장값은 도메인 단위 검증과 별도로 후속 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)에서 검증했다.

| 사례 ID | 입력·동작 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-01 | 앞뒤 공백이 있는 이름으로 생성 | 정리된 유효한 이름을 보관 |
| BRAND-02 | null·공백뿐인 이름·101자 이름으로 생성 | BrandNameException의 EMPTY_NAME 또는 NAME_TOO_LONG으로 거절 |
| BRAND-03 | 유효한 새 이름으로 변경 | 같은 브랜드 객체에 정리된 새 이름 반영 |
| BRAND-04 | null·공백뿐인 이름·101자 이름으로 변경 | 해당 이름 실패 사유를 유지하고 기존 이름 보존 |
| BRAND-05 | 코드 포인트 100자인 한글·이모지 이름으로 생성·변경 | 두 경로 모두 허용하고 같은 이름 검증 규칙 적용 |
| BRAND-06 | 잘못된 변경 후 유효한 이름으로 다시 변경 | 실패가 후속 변경을 막지 않으며 새 이름 반영 |

6개 규칙의 13개 실행 사례를 [BrandTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandTest.java)로 검증했다. 실제 Red·Green·Refactor 결과는 [브랜드 모델 실행 기록](commerce-tdd-brand-log.md)에 남긴다. 삭제 후 이름 변경 거절은 아래 생명주기 사례로 구분하며, A02·A04/API-16·18의 후속 HTTP 증거는 관리자 브랜드 기록에 구분한다.

## 브랜드 생명주기의 TDD 계획

P00·P04의 논리 삭제와 재삭제 상태 보존을 `Brand`의 메모리 상태에 연결했다. `deletedAt` 하나를 상태로 보관하고 `isDeleted()`는 이 값에서 도출한다. `delete(ZonedDateTime)`는 package-private이며 서버가 제공하는 시각을 받는다. HTTP에서 삭제 시각을 입력받는 계약을 추가한 것이 아니다. 삭제 후 이름 변경은 HTTP와 독립된 `BrandStateException.Reason.DELETED_BRAND`로 거절한다.

| 사례 ID | 입력·동작 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-LIFECYCLE-01 | 새 브랜드 생성 | 미삭제 상태, deletedAt은 null |
| BRAND-LIFECYCLE-02 | 서버 시각으로 첫 삭제 | 삭제 상태로 전환하고 전달한 시각 기록, 기존 이름 유지 |
| BRAND-LIFECYCLE-03 | 삭제 후 다른 서버 시각으로 재삭제 | 최초 deletedAt과 이름 유지, 추가 변경 없음 |
| BRAND-LIFECYCLE-04 | 삭제 후 유효한 새 이름 또는 같은 이름으로 변경 | 두 경우 모두 DELETED_BRAND로 거절하고 이름·삭제 시각 유지 |
| BRAND-LIFECYCLE-05 | 이름 변경 후 삭제 | 변경한 이름을 유지하고 삭제 시각 기록 |
| BRAND-LIFECYCLE-06 | 첫 삭제에 null 서버 시각 전달 | 내부 호출 오류인 NullPointerException으로 거절하고 미삭제 상태·이름 유지 |

6개 규칙의 7개 실행 사례를 [BrandLifecycleTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandLifecycleTest.java)로 단위 검증했다. 실제 과정과 검사 결과는 [브랜드 모델 실행 기록](commerce-tdd-brand-log.md)에 남긴다. 이 생명주기 단위 단계에서는 삭제된 브랜드와 잘못된 이름의 복합 오류를 다루지 않았다. HTTP 연결은 승인한 공통 처리 순서·오류 계약으로 검증한다. 미삭제 상품 조회 결과를 이용한 삭제 조건은 아래 서비스에서 검증하며, Brand ID·JPA·실제 저장·조회는 아래 영속성 단계에서 구현했다. BaseEntity는 상속하지 않으며, 후속 관리자 브랜드 단계에서 삭제 유스케이스의 DB 저장·행 잠금·HTTP를 연결했다.

## 브랜드 삭제 조건의 TDD 계획

이 절의 `BRAND-DELETE-01..07`은 **W2의 삭제 거절 정책을 검증한 과거 기록**이다. 당시 기대값과 실행 결과를 보존하며, W3의 일괄 삭제 성공 사례로 의미를 바꾸어 재사용하지 않는다. 새 계약은 다음 절의 `W3-BRAND-01..08`로 구분한다.

W2 당시 `domain/brand/BrandDeletionService`는 `BrandRepository.findById(long)`로 삭제된 브랜드까지 포함해 대상을 조회했다. 이미 삭제됐다면 그대로 반환하고, 미삭제 상태라면 `domain/product/ProductRepository.existsNonDeletedByBrandId(long)`의 결과로 삭제 가능 여부를 판단했다. 외부에서 브랜드 객체와 ID를 따로 받지 않으며, 성공하면 해당 `Brand`의 삭제 상태를 변경해 반환했다. 이 반환 자체가 DB 저장을 뜻하지는 않았다.

| 사례 ID | 조회 결과·동작 | 도메인 단위 테스트 기대값 |
| --- | --- | --- |
| BRAND-DELETE-01 | 대상 존재, 미삭제 상품 존재 결과 false | 해당 브랜드를 서버 시각으로 삭제하고 같은 객체 반환 |
| BRAND-DELETE-02 | 대상 존재, 미삭제 상품 존재 결과 true | NON_DELETED_PRODUCTS_EXIST로 거절하고 이름·미삭제 상태 유지 |
| BRAND-DELETE-03 | 대상 브랜드 없음 | BRAND_NOT_FOUND로 거절, 상품 조회 생략, 다른 브랜드 상태 유지 |
| BRAND-DELETE-04 | 이미 삭제된 대상 | 상품 조회 없이 기존 브랜드 반환, 최초 삭제 시각 유지 |
| BRAND-DELETE-05 | 상품 존재 조회 중 실패 | 실패를 전달하고 브랜드 이름·미삭제 상태 유지 |
| BRAND-DELETE-06 | 두 브랜드 ID에 서로 다른 상품 존재 결과 | 각 ID의 대상·조건만 적용하고 다른 브랜드와 혼동하지 않음 |
| BRAND-DELETE-07 | true 결과로 거절된 뒤 false 결과로 재요청 | 첫 실패 상태를 보존하고 조건이 바뀐 재요청에서 삭제 성공 |

당시에는 대상 없음·미삭제 상품 존재의 실패 사유를 HTTP와 독립된 `BrandDeletionException`으로 구분했다. 조회 대역으로 검증한 7개 사례와 결과는 [브랜드 모델 실행 기록](commerce-tdd-brand-log.md)에 보존한다. 현재 [BrandDeletionServiceTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandDeletionServiceTest.java)는 W3 계약에 맞춘 8개 사례이며 과거 7개와 다르다. W2 boolean 대역은 실제 SQL·DB 행 잠금을 검증하지 못했고, `findById` 자체도 잠금·저장을 보장하지 않는다. W2의 실제 저장·잠금·Spring·HTTP 연결 증거는 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)를 따른다.

## W3 브랜드·상품 일괄 논리 삭제의 TDD 계획

근거는 사용자가 제공한 W3 과제의 「브랜드와 연관 상품의 일괄 처리」·「실패·경쟁 결과 확인」과 [이번 변경의 합의·구현 선택](commerce-policy-decisions.md#w3-브랜드상품-일괄-논리-삭제)이다. 브랜드와 연결된 미삭제 상품을 하나의 트랜잭션에서 soft delete하고 비관적 락을 유지한다. 아래 표의 **`W3-BRAND-01..08`을 검증 완료**했으며 선후 결과의 순차 검증과 실제 동시 요청의 검증은 구분한다.

| 사례 ID | 준비·실행 | 기대 결과 |
| --- | --- | --- |
| W3-BRAND-01 | 브랜드 1개와 미삭제 상품 2개(하나는 재고 0), 다른 브랜드·상품, 과거 주문을 준비하고 삭제 | `200`, 대상 브랜드·상품 모두 삭제 상태. 행·참조는 보존하며 다른 브랜드·상품과 과거 주문의 품목·수량·단가·총액·포인트 결제 결과는 불변 |
| W3-BRAND-02 | 상품 없는 브랜드 삭제, 없는 브랜드 삭제, 브랜드 재삭제, 이미 삭제된 상품을 포함한 브랜드 삭제를 각각 실행 | 상품 없어도 `200`, 없는 브랜드는 기존 `404 BRAND_NOT_FOUND`, 재삭제는 `200`·추가 변경 없음. 이미 삭제된 상품의 최초 삭제 시각도 유지 |
| W3-BRAND-03 | 실제 상품 변경 SQL 실행 뒤 다음 브랜드 저장 단계에서 테스트 전용 예외 발생 | `500 INTERNAL_ERROR`·`meta.result=FAIL`·`data` 생략. 서비스 트랜잭션 종료 후 별도 DB 조회에서 브랜드·상품 모두 변경 전 상태. 다른 대상·과거 주문도 불변. 일부 성공 응답이나 독립 commit 없음 |
| W3-BRAND-04 | 삭제 후 고객 브랜드·상품 조회, 새 좋아요, 내 좋아요 목록, 기존 본인 좋아요 취소, 새 주문 요청 | 고객 목록에서 제외·상세는 기존 없음 오류, 새 좋아요·새 주문 거절, 내 좋아요 목록 제외. 기존 본인 좋아요 취소는 성공하며 다른 사용자의 관계는 보존 |
| W3-BRAND-05 | 삭제 대상 이름·가격·재고 변경 요청, 일반·미식별 사용자의 삭제, CSRF 누락·불일치 요청 | 삭제 대상 수정·재고 변경은 기존 없음 오류. 권한·CSRF는 기존 `403` 계약 유지. 거절 요청으로 DB 변경 없음 |
| W3-BRAND-06 | 삭제 전에 생성한 DRAFT 주문을 브랜드 삭제 후 최초 확정 | 잠금 확보 후 삭제 상태를 다시 확인해 기존 `404 PRODUCT_NOT_FOUND`로 거절. 주문은 DRAFT, 이 요청의 재고·포인트 차감과 결제 결과는 없음 |
| W3-BRAND-07 | 브랜드 삭제와 주문 생성·최초 확정의 경합을 각각 검증 | 삭제가 먼저 커밋되면 후속 생성·확정 거절. 생성이 먼저 끝나도 이후 삭제되면 그 DRAFT의 확정 거절. 최초 확정이 먼저 끝나면 이후 삭제해도 확정·결제 결과 보존 |
| W3-BRAND-08 | 브랜드 삭제와 상품 등록을 동시에 실행 | 삭제가 먼저 끝나면 등록 거절. 등록이 먼저 커밋되면 그 상품도 후속 일괄 삭제 대상. 삭제 브랜드에 미삭제 상품이 남지 않음 |

`W3-BRAND-01..06`은 과제의 브랜드 정상·실패·접근 경계를 검증한다. `07`은 합의한 설계의 추가 동시성 검증이고, `08`은 과제에서는 선택 확장이지만 기존 `BrandConcurrencyIntegrationTest`의 변경된 기대값을 확인하기 위한 회귀 계획이다.

| 범위 | 2026-10-08 실행 상태 |
| --- | --- |
| W3-BRAND-01 | 완료. 실제 관리자 요청의 `200`·대상 브랜드/상품 삭제·다른 대상과 확정 주문/잔액 보존을 검증했다. 기술 검증으로 상품 UPDATE 1회와 이미 로딩된 상품 갱신·다른 관리 객체 보존도 확인했다 |
| W3-BRAND-02 | 완료. 빈 브랜드 삭제, 없는 브랜드의 실제 DELETE `404`·오류 본문·DB 불변, 미삭제·기삭제 상품 혼합 및 전부 기삭제인 경우를 추가 검증했다. 기존 삭제 상품의 전체 행과 재삭제 후 전체 저장 상태를 보존한다. [두 번째 증분](../week3/brand-removal-tdd-log.md#두-번째-증분--w3-brand-02-경계-검증) |
| W3-BRAND-03 | 완료. 상품 bulk UPDATE와 브랜드 `saveAndFlush()`의 실제 SQL 반영을 JDBC로 관찰한 뒤 테스트 예외를 발생시켰다. HTTP `500 INTERNAL_ERROR`·실패 본문과 서비스 종료 후 브랜드·상품·주문·항목·사용자·좋아요 6개 테이블의 전체 행 원복을 검증했다. [세 번째 증분](../week3/brand-removal-tdd-log.md#세-번째-증분--w3-brand-03-롤백-검증) |
| W3-BRAND-04 | 완료. [고객 API 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandRemovalCustomerApiTest.java): 실제 일괄 삭제 후 세 정렬·페이지·브랜드 필터·상세의 비노출, 새 좋아요·주문 거절, 내 좋아요 제외, 본인 취소·재취소와 타인 관계 보존 |
| W3-BRAND-05 | 완료. [관리자 접근 테스트](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandRemovalAccessApiTest.java): 삭제 대상 수정·재고 변경·상품 등록 거절, 일반·미식별·CSRF 오류와 DB 불변, 관리자 삭제 행 조회 |
| W3-BRAND-06 | 완료. 고객 API 테스트에서 삭제 전 DRAFT의 최초 확정 `404 PRODUCT_NOT_FOUND`와 주문·재고·잔액·결제 결과 보존. 확정 주문은 현재 상품 이름·가격 변경과 브랜드 삭제 후에도 고객·관리자 목록·상세에서 기존 스냅샷 반환 |
| W3-BRAND-07·08 | 완료. [선후·경합 테스트](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalConcurrencyTest.java): 삭제와 생성·최초 확정·등록의 선후 결과는 순차 호출로 각각 확인하고, 별도 실제 경합은 worker 시작만 맞춘다. 성공·업무 거절에 맞는 최종 저장 상태를 검증하며 기술 오류는 실패 처리한다. 동시 경합의 모든 실행 순서를 강제로 재현했다는 뜻은 아니다 |

첫 증분에서는 새 테스트 2개·도메인 8개·관리자 HTTP 11개·브랜드 경합 3개, 관련 24개와 Checkstyle이 통과했다. 이후 `:apps:commerce-api:check`의 52개 스위트·509개 테스트와 ArchUnit도 통과했다. 당시 Red·Green과 명령·범위는 [첫 증분 실행 기록](../week3/brand-removal-tdd-log.md)을 보존한다.

두 번째 증분은 `BrandRemovalTransactionTest`에 4개 실행 사례(신규 메서드 3개 중 혼합·전부 기삭제의 파라미터 사례 2개)를 추가했다. 2026-10-08 11:31 KST, 해당 클래스 6개와 Checkstyle main/test가 최초 실행부터 통과해 생산 코드는 변경하지 않았다. 같은 날 11:33 KST 전체 `:apps:commerce-api:check`는 종료 코드 0, 52개 스위트·513개 테스트의 실패·오류·건너뜀 0개, ArchUnit 1개 통과, Checkstyle XML 11개의 위반 0개를 확인했다. 없는 브랜드의 실제 DELETE 검증은 이번에 추가한 것이며, 기존 도메인 대역·고객 조회 검증으로 대신하지 않는다. [두 번째 증분 실행 기록](../week3/brand-removal-tdd-log.md#두-번째-증분--w3-brand-02-경계-검증)을 따르며, 전체 회귀 통과를 아직 작성하지 않은 위 사례의 증거로 사용하지 않는다.

세 번째 증분은 같은 클래스에 `rollsBackProductAndBrandUpdatesWhenBrandSaveFailsAfterFlush`를 추가했다. 상품 변경 뒤 `BrandRepository.save()`의 spy가 실제 저장을 호출해 브랜드 UPDATE까지 실행한 후 테스트 전용 예외를 던진다. 중간 JDBC 상태와 트랜잭션 활성 여부를 수집하고 HTTP 요청 밖에서 검증해, 예외 처리기가 assertion 실패를 `500`으로 바꾸는 거짓 통과를 막았다. 2026-10-08 11:39 KST, 해당 클래스 7개와 Checkstyle main/test가 최초 실행부터 통과했으며 생산 코드는 변경하지 않았다. 같은 날 11:41 KST 전체 `:apps:commerce-api:check`도 종료 코드 0으로 통과했다. XML 52개 스위트·514개 테스트의 실패·오류·건너뜀 0개, ArchUnit 1개 통과, Checkstyle XML 11개의 위반 0개를 확인했다. 상세 증거는 [세 번째 증분 실행 기록](../week3/brand-removal-tdd-log.md#세-번째-증분--w3-brand-03-롤백-검증)을 따른다. 이 DB 보존 검증만으로 연결 조회까지 완료 처리하지 않았으며, 후속 고객·관리자 API 테스트에서 직접 검증했다.

네 번째 증분은 위 고객·관리자·선후 및 경합 테스트를 추가했다. 기존 구현으로 업무 기대값을 만족해 생산 코드는 변경하지 않았다. DRAFT 비교의 시각 표현 차이는 삭제 전후 동일한 GET 응답 전체를 비교하도록 고쳤다. 최초 동시성 테스트의 잠금 직후 대기 장치는 과제 조건에 맞지 않아 제거했고, 최종본은 순차 경계 테스트와 시작만 맞춘 실제 경합 테스트로 나눴다. 정확한 실행 건수·결과와 한계는 [네 번째 증분 실행 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증)을 따른다.

| 검증 경계 | 작성 시 지킬 조건 |
| --- | --- |
| 실제 롤백 | 초기 데이터를 먼저 commit한다. 상품 UPDATE가 실제 실행된 뒤 다음 저장 경계에서 예외를 내며, 앞 단계까지 모두 mock하지 않는다. 서비스 호출 종료 후 새 트랜잭션 또는 별도 JDBC 조회로 변경 전후 DB 값을 비교한다 |
| 동시성 | 실제 Spring 서비스·repository·MySQL을 사용하고 각 worker는 독립 트랜잭션으로 실행한다. 시작만 맞추며 제품 코드에 장벽·sleep을 넣지 않는다. 모든 worker 종료 후 DB를 다시 읽고 성공·업무 거절·기술 오류를 구분한다 |
| 자원 정리 | 대기·future에 제한 시간을 두고 `finally`에서 worker·executor·connection을 정리한다. 타임아웃·교착을 삭제 성공이나 업무 거절로 숨기지 않는다 |
| bulk UPDATE 구현 | 대상 ID에 `deleted_at`·`updated_at`을 한 번에 갱신한다. bulk 이전 flush·이후 이미 로딩된 Product만 refresh하며 전체 clear는 하지 않는다. 정상 경로의 상품 UPDATE 1회와 관리 객체 보존은 01에서, 이미 삭제된 상품의 삭제·갱신 시각을 포함한 전체 행 보존은 02에서 검증했다 |
| 잠금 순서 | `READ_COMMITTED` 호출자가 Brand 잠금을 보유한 상태에서 미삭제 Product ID 정렬 조회 → ID별 기본 키 `FOR UPDATE` → flush → bulk UPDATE한다. P13을 바꾸지 않으며 상품 수만큼 잠금 SELECT가 발생한다. UPDATE 1회가 전체 SQL 1회나 성능 개선 측정 결과를 뜻하지는 않는다 |

작은 순서는 정상 일괄 삭제의 Red → 최소 구현 → 기본 경계 → 실제 SQL 이후 실패·롤백 → 삭제 후 사용 제한·보존 → 경합·회귀다. 첫 증분의 Red는 기대 `200`과 기존 응답 `409`의 차이였고, 최소 구현 후 통과했다. 두 번째 증분의 경계 테스트와 세 번째 증분의 롤백 테스트는 기존 구현에서 바로 통과했으므로 인위적인 Red나 생산 코드 변경을 만들지 않았다. 기존 거절 테스트는 W3 요구에 따른 계약 전환 때문에 변경했으며 검사를 통과시키기 위한 완화가 아니다. W2 실행 로그는 고치지 않고 이후 증분도 실제 실행 범위만 완료로 기록한다.

## 브랜드 저장·조회 매핑의 TDD

[BrandRepositoryIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/brand/BrandRepositoryIntegrationTest.java)는 실제 MySQL과 Spring으로 주입한 domain 저장소 계약을 사용한다. 테스트 전체를 하나의 트랜잭션으로 감싸지 않고 저장·조회 트랜잭션을 분리해 DB 왕복을 확인한다.

| 사례 ID | 검증할 행동 |
| --- | --- |
| BRAND-PERSIST-01 | 신규 저장의 양수 ID·감사 시각과 실제 이름 컬럼 |
| BRAND-PERSIST-02 | 커밋 후 다른 트랜잭션·다른 객체로 ID·이름·미삭제 상태 복원 |
| BRAND-PERSIST-03 | 없는 ID의 빈 결과 |
| BRAND-PERSIST-04 | 한글·이모지 각각 100개 이름 왕복, 2개 실행 사례 |
| BRAND-PERSIST-05 | SQL로 준비한 삭제 행을 삭제 시각까지 반환. 삭제 유스케이스 실행과 구분 |
| BRAND-PERSIST-06 | 저장 후 호출자 트랜잭션 실패 시 INSERT 롤백 |
| BRAND-PERSIST-07 | 변경 감지에 의한 이름 저장, createdAt 보존·updatedAt 갱신 |

이 7개 규칙은 8개 실행 사례로 검증한다. 도메인 이름 검증을 converter에 복제하지 않고 `BrandName`을 이용한다. 수정·삭제 유스케이스의 권한·업무 실패·경합·잠금과 운영 DDL은 별도 [관리자 브랜드](commerce-tdd-admin-brand-http-log.md)·[스키마 TDD](commerce-tdd-schema-log.md)에서 검증했다.

## 관리자 브랜드 신규 등록의 TDD

`BrandRegistrationService`는 전달된 `UserRole`을 `AdminAuthorization.requireAdmin`으로 확인한 뒤 Brand를 생성·저장한다. 초기 TDD의 fixture 기반 검사를 P15의 Spring Security 역할 경계에 맞춰 교체했다. 입력 이름 검증과 생성 상태는 기존 도메인에 맡기고 `AdminBrandInfo`로 내부 결과를 반환한다. 정상 등록 1개, 고객의 정상/잘못된 이름 2개, 관리자의 null·공백·101자 3개, 같은 이름의 별도 등록 1개를 실제 MySQL에서 검증했다. 총 7개 통합 사례와 Red·Green 과정은 [등록 기록](commerce-tdd-brand-registration-log.md)에 남긴다. A02의 HTTP 계약과 바인딩 전 관리자 게이트도 후속 [관리자 브랜드 TDD](commerce-tdd-admin-brand-http-log.md)에서 검증했다.

## 주문 수량 합산의 TDD

P01/P08의 확정 규칙을 `domain/order/OrderQuantities`로 구현했다. 금액·저장·HTTP와 독립된 상품별 수량의 불변 결과를 제공하고, 각 항목의 양수 ID·양수 수량과 동일 상품 합계의 Integer 범위를 검증한다. 별도 `OrderQuantityException`은 HTTP 상태 없이 사유만 구분한다.

| 사례 ID | 기대값 |
| --- | --- |
| ORDER-QUANTITY-01 | 서로 다른 상품의 수량을 각각 유지 |
| ORDER-QUANTITY-02 | 같은 상품의 비연속 입력도 2+3=5로 합산 |
| ORDER-QUANTITY-03 | 각 입력이 0·음수이면 합산 전 거절 |
| ORDER-QUANTITY-04 | 개별 값이 유효해도 합계 Integer 상한 초과 거절 |
| ORDER-QUANTITY-05 | null·빈 목록·null 항목 거절 |
| ORDER-QUANTITY-06 | 양수가 아닌 상품 ID 거절 |
| ORDER-QUANTITY-07 | Long 최댓값 ID·Integer 최댓값 수량·정확히 최댓값인 합계 허용 |
| ORDER-QUANTITY-08 | 입력 목록을 변경하지 않고 반환 수량의 외부 변경도 방지 |

[실행 기록](commerce-tdd-order-quantity-log.md)의 단위 테스트 16개가 통과했다. API-11의 실제 항목 1행 저장·재고 5 차감·HTTP 오류와 주문 전체 원자성은 별도 [주문 TDD](commerce-tdd-order-log.md)에서 검증했다. 값객체의 단위 검사와 API 완료 증거는 구분한다.

## 고객 브랜드 상세 조회의 TDD

`application/brand/BrandQueryService`는 고객에게 노출할 수 있는 브랜드만 조회한다. `@Service`로 등록하고 `@Transactional(readOnly = true)` 범위에서 domain 저장소를 호출한 뒤 내부 결과 `BrandInfo`를 만든다. 결과를 HTTP DTO로 바꾸는 책임은 interfaces에 남긴다.

| 사례 ID | 입력·상태 | 기대값 |
| --- | --- | --- |
| BRAND-QUERY-01 | 서로 다른 미삭제 브랜드 두 개 중 두 번째 ID 요청 | 요청한 ID·이름 반환, DB 상태 유지 |
| BRAND-QUERY-02 | 기존 브랜드가 있지만 요청 ID의 행은 없음 | BRAND_NOT_FOUND, 기존 행 유지 |
| BRAND-QUERY-03 | 삭제 시각이 저장된 브랜드 ID 요청 | 동일한 BRAND_NOT_FOUND, 모든 행·이름·감사 시각·삭제 시각 유지 |

[BrandQueryServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandQueryServiceIntegrationTest.java)는 실제 MySQL에 커밋한 데이터를 Spring 서비스 빈으로 조회한다. 테스트 전체를 하나의 트랜잭션으로 감싸지 않으며, 호출 전후 DB 스냅샷으로 상태 보존을 확인한다. `readOnly` 선언 자체가 쓰기를 차단한다고 가정하지 않는다. 삭제 상태는 SQL fixture로 준비하며 삭제 유스케이스의 완료를 뜻하지 않는다. 기존 저장소의 삭제 포함 계약과 관리자 조회 규칙은 유지한다. C01의 HTTP 연결·입력 오류·응답은 후속 [HTTP TDD](commerce-tdd-brand-http-log.md)에서 검증했다. API-01의 상품 상세는 별도 [상품 TDD](commerce-tdd-product-log.md)에서 검증했다.

## API·규칙별 테스트 기대값

아래 API ID는 선행 W2~W3 계약 추적용으로 보존한다. W5 신규 결제에 영향을 받는 API-12~14·20·23·24와 예약이 있는 API-19는 위 W5-TDD 사례를 함께 적용한다. POINT 차감·모든 재확정 409를 신규 PG에 강제하지 않으며, 기존 POINT 사실과 변경 대상이 아닌 기대값은 계속 보호한다.

아래는 **테스트 계획**이다. [API 계약표](commerce-api-contract.md)의 ID를 테스트 이름 또는 설명에 연결하고 HTTP 상태·응답 필드뿐 아니라 성공·실패 후 저장값을 함께 검증한다. 금액·포인트는 승인된 원 단위 정수 Long/BIGINT이며 아래 표현 상한은 `M=Long.MAX_VALUE=9,223,372,036,854,775,807`로 고정한다. 덧셈·곱셈의 범위 초과를 검출하고 실패 시 상태를 보존한다.

아래 `API-16`의 삭제 거절 기대값과 `API-24`의 브랜드 경합 증거는 W2 기준으로 보존한다. 새 브랜드 삭제 계약과 연결 API의 추가 검증은 위 `W3-BRAND-01..08`을 따른다.

자원 ID와 수량의 범위는 이미 확정했다. 커머스 API의 `brandId`·`productId`·`orderId`에는 `Long.MAX_VALUE + 1`, 수량·재고에는 `Integer.MAX_VALUE + 1`을 원문 숫자로 보내 바인딩 단계의 범위 초과도 검증한다. 오류 응답은 승인된 400 INVALID_REQUEST로 검증하고 저장값은 유지한다. 현재 재고 객체의 `int` 단위 테스트만으로 HTTP 숫자 변환까지 검증했다고 보지 않는다.

커머스 HTTP 경계에서는 누락·null·허용하지 않은 필드와 JSON 타입 강제 변환을 거절한다. C01·C02·C03은 X-USER-ID를 무시하고, C06 경로와 관리자 주문 필터는 외부 식별 문자열을 사용한다. 관리자 권한 검사는 Spring Security로 경로·본문 바인딩 전에 실행한다. 관리자·일반 사용자·미식별 요청을 MockMvc로 검증하고 쓰기 권한 테스트에는 유효한 csrf()를 넣는다. CSRF 누락·불일치는 별도 403 사례로 검증한다. 405 METHOD_NOT_ALLOWED·415 UNSUPPORTED_MEDIA_TYPE·406 NOT_ACCEPTABLE의 승인된 정확한 메시지와 기존 Example API 관찰 계약 보존을 함께 검증한다.

### 상품 목록 검증용 fixture

브랜드 10·20은 미삭제이고, 상품 105만 삭제된 상태로 준비한다. 생성 시각은 같은 날짜의 아래 시각으로 고정한다. 좋아요 수는 값을 상품에 직접 넣지 않고 서로 다른 fixture 사용자들의 관계 행으로 만든다. 각 테스트는 초기 데이터를 독립적으로 준비한다.

| productId | brandId | 가격 | 재고 | 생성 시각 | 관계 수 | 상태 |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 10 | 1000 | 5 | 01:00:00Z | 2 | 미삭제 |
| 102 | 10 | 1000 | 3 | 02:00:00Z | 0 | 미삭제 |
| 103 | 20 | 2000 | 4 | 02:00:00Z | 2 | 미삭제 |
| 104 | 10 | 500 | 7 | 00:00:00Z | 2 | 미삭제 |
| 105 | 10 | 500 | 1 | 03:00:00Z | 1 | 삭제 |

| 사례 ID·관련 API | 정상·대표 오류 입력 | 응답 및 저장 기대값 |
| --- | --- | --- |
| API-01 / C01·C03 | 존재하는 미삭제 브랜드·상품 상세, 없는 ID, 삭제 ID | 정상 200·BrandView/ProductView. 상품 응답에 브랜드와 집계 likeCount 포함. 없음·삭제는 같은 404 코드·메시지. 조회로 저장 상태 변경 없음 |
| API-02 / C02·A06 | 위 fixture에서 latest / price_asc / likes_desc | 고객 순서 각각 `[103,102,101,104]`, `[104,102,101,103]`, `[104,103,101,102]`. 동률은 ID 내림차순. 관리자 latest는 삭제 105도 포함하여 `[105,103,102,101,104]`(삭제 포함·동률 기준은 P05·06 확정) |
| API-03 / C02 | `brandId=10&sort=price_asc&size=2`로 page=0/1/2를 각각 요청 | 페이지는 `[104,102]`, `[101]`, `[]`; totalElements=3, totalPages=2. 브랜드 필터와 정렬을 모두 적용한 뒤 페이지를 자름. 별도 없는/삭제 브랜드 필터는 200 빈 페이지 |
| API-04 / 모든 목록 API | sort=unknown, page=-1, size=0·101, page=abc | 지원하는 해당 쿼리의 잘못된 입력은 `400 INVALID_REQUEST`. 기본값으로 대체하거나 저장값을 변경하지 않음 |
| API-05 / C04·C05·C06 | 관계 없는 alice가 관계 수 2인 상품에 등록→중복 등록→취소→재취소→재등록 | 모든 성공 상태·응답 필드는 P04·P12 확정이며 liked는 true→true→false→false→true, likeCount는 3→3→2→2→3. 본인 DB 관계 행 수는 1→1→0→0→1로 물리 삭제와 재등록을 검증. 본인 목록에도 반영하고 다른 사용자의 관계는 유지 |
| API-06 / C04·C05·C06 | 본인 관계가 있는 상품 삭제 후 새 등록·내 목록·취소 | 상품 논리 삭제만으로 관계를 제거하지 않음. 새 등록 404, 내 목록에서 제외, 본인 취소 200·관계 행 실제 삭제·집계 1 감소. 다른 사용자의 관계는 유지 |
| API-07 / C07·C08 | 잔액 2000에 amount=3000, 같은 요청을 다시 충전, 별도 잔액 0 조회 | 첫 충전 후 DB·GET balance=5000. 같은 요청의 두 번째 성공도 새 충전이므로 DB·GET balance=8000(P07). 충전 성공 상태는 200. 별도 잔액 0 fixture는 200·balance=0 |
| API-08 / C07 | amount 누락·null·문자열·소수·0·음수·표현 상한 M 초과 | 400·INVALID_REQUEST, 초기 잔액 2000 유지. 잔액 1에 유효한 최댓값 M을 충전하면 합산 초과 409·POINT_BALANCE_LIMIT_EXCEEDED, 잔액 1 유지 |
| API-09 / C09 | 상품101×2, 상품103×1, 잔액10000 | 201·DRAFT, 소계2000·2000, totalAmount=4000, paidAmount/confirmedAt 생략. 주문1행·항목2행 저장. 재고5·4와 잔액10000 유지. 별도 반복 요청 테스트에서는 같은 요청을 두 번 성공시키면 서로 다른 주문2행·항목4행이며 차감 없음(P07) |
| API-10 / C09 | 빈 items, 수량0·음수·소수·누락·Integer 상한 초과, 없는/삭제 상품 또는 삭제 브랜드의 상품 | 입력 오류400 또는 PRODUCT_NOT_FOUND404. 주문·항목 저장 없고 잔액·재고 유지. 뒤 품목이 실패해도 앞 품목만 저장하지 않음 |
| API-11 / C09·C10 | 같은 상품에 수량2·3을 전달. 별도 상한 테스트는 Integer.MAX_VALUE와 1을 전달 | P01 합산 확정: 항목1행·수량5·소계=단가×5. 생성201은 확정 계약. 합산된 주문을 현재 재고4로 확정하면409, 재고5면 다른 조건 충족 시5차감. 개별 수량이 유효해도 합이 Integer 범위를 초과하면 거절·주문과 항목 저장 없음. 초과 오류는400 INVALID_REQUEST |
| API-12 / C10 | API-09의 본인 DRAFT 확정 | 200·CONFIRMED·paidAmount=4000·confirmedAt 저장. 재고3·3, 잔액6000. 주문·모든 재고·잔액이 한 트랜잭션으로 반영 |
| API-13 / C10 | 재고 부족 / 잔액3999 / DRAFT 생성 후 상품 삭제 / 저장 수량을0으로 훼손한 오류 fixture | 각 409 INSUFFICIENT_STOCK / 409 INSUFFICIENT_POINTS / 404 PRODUCT_NOT_FOUND / 500 INTERNAL_ERROR. DRAFT와 요청 직전의 모든 재고·잔액 유지. 부족 조건 개선 후 별도 확정 요청 가능하며 삭제는 재고 증가만으로 해결되지 않음 |
| API-14 / C10·C12 | 생성 후 상품명·가격 변경, 확정 후 재확정·상품/브랜드 삭제 | 생성 당시 이름·단가·총액 유지. 본인 확정 재요청은409 ORDER_ALREADY_CONFIRMED·meta.result=FAIL·data생략. 기존 paidAmount·confirmedAt·재고·잔액 불변, 상품/브랜드 삭제 후에도 동일409. 기존 결과는 과거 주문 GET200으로 조회. W2의 재요청200 기대값은 P16으로 대체 |
| API-15 / C06·C10·C11·C12 | alice 헤더로 bob 목록·주문 접근, 없는 주문 접근 | 타인 좋아요 목록404 USER_NOT_FOUND. 타인/없는 주문은 동일404 ORDER_NOT_FOUND·메시지. 내 주문 목록에는 alice 주문만 포함하고 타인 정보·변경 없음 |
| API-16 / A01~A05·C01 (W2 기록) | 관리자 브랜드 생성→목록·상세→이름 수정→고객 상세→삭제·재삭제 | 생성201, 조회·수정200, 고객 상세에 수정된 이름. 이름 공백·101자 입력400·기존 값 유지. W2 당시에는 미삭제 상품이 하나라도 연결되면 재고0이어도 삭제409·브랜드 유지. 상품이 없거나 모두 삭제되면 삭제200·고객 상세404. 브랜드 행·ID를 보존하고 deleted_at 기록. 재삭제는200·최초 deleted_at과 관련 상품 상태 유지(P00·P04). W3의 새 기대값은 W3-BRAND-01..06 참조 |
| API-17 / A06~A10·C02·C03 | 유효한 상품 생성→조회→상품명·가격 수정→삭제·재삭제 | 생성201, 조회·수정200, 고객 조회에 현재 값 반영. brandId·재고는 유지. brandId를 PUT에 전달하면400 INVALID_REQUEST(P05·P12). 없는/삭제 브랜드로 생성404·저장 없음. 삭제200 후 고객 목록 제외·상세404·기존 주문 보존. 상품 행·ID를 보존하고 deleted_at 기록. 재삭제는200·최초 deleted_at과 주문·좋아요 관계 유지(P00·P04) |
| API-18 / A04·A09·A11 | 삭제된 브랜드·상품 수정 또는 삭제 상품 재고 변경 | 각404 BRAND_NOT_FOUND/PRODUCT_NOT_FOUND, 기존 정보·재고·삭제 상태 유지. name 누락·공백·101자, price 음수·타입·범위 오류는400. 가격0은 P02에 따라 거절 |
| API-19 / A11·C03 | 현재 재고10에서 stockQuantity=3, 이어서0, 별도 -1 요청. 별도 상한 테스트는 Integer.MAX_VALUE와 그 초과값 | 200·최종3, 200·최종0, 음수는400·직전 재고 유지. 최댓값은 정상 설정하고 초과 입력은 거절·직전 재고 유지. 고객 상세에도 최종 수량 반영. 더하기 연산으로 처리하지 않음 |
| API-20 / A12·A13·C11·C12 | alice·bob의 DRAFT/CONFIRMED 주문, 관리자 구매자·상태 필터 | 관리자 목록·상세200·구매자/품목/상태/총액/결제액 포함. 필터와 일치한 주문만 반환. 고객 응답은 본인 주문이며 관리자 필드 미노출. 없는 주문404 |
| API-21 / 본인·관리자 API 공통 | 고객 X-USER-ID 누락·공백 / 매핑 없음, 관리자의 Security 역할·CSRF | 고객은 400 INVALID_REQUEST / 404 USER_NOT_FOUND와 단일 fixture를 유지한다. 관리자는 P15에 따라 일반·미식별 요청 모두403 FORBIDDEN, ADMIN 허용. 모든 쓰기 권한 테스트에 유효한 csrf()를 넣고 CSRF 누락·불일치403은 별도 검증한다. X-USER-ID만으로 관리자 접근 불가. 일반 사용자의 잘못된 ID·깨진 JSON에도 권한 오류 우선이며 전체 DB 상태 보존 |
| API-22 / C09·C10 | 표현 가능한 단가·수량의 곱 또는 소계 합이 금액 표현 상한 M을 초과 | C09는409 ORDER_AMOUNT_LIMIT_EXCEEDED·주문 저장 없음. C10은 유효하게 저장한 생성 금액으로 처리하고 현재 가격으로 재산출하지 않음 |
| API-23 / C10·C07·A11 | 같은 주문 동시 확정, 서로 다른 주문의 재고·잔액 경쟁, 충전·재고 설정과 확정 경쟁 | 같은 주문은 최초 성공 한 건만200·한 번의 차감, 이후 상태 확인 요청은409 ORDER_ALREADY_CONFIRMED(P16). 다른 조건이 충족되고 재고5에 각4개인 두 주문은 하나만 확정, 재고1·실패 주문DRAFT. 잔액·재고 음수와 갱신 유실·부분 저장 없음. READ_COMMITTED·주문→사용자→브랜드 ID 오름차순→상품 ID 오름차순 잠금에서 경합 결과를 검증. 별도 장기 점유·교착 사례는 3초 잠금 타임아웃/409 CONCURRENT_MODIFICATION·서버 자동 재시도 없음·실패 요청의 추가 효과 없음을 검증 |
| API-24 / A05·A07·A10·C10 | 브랜드 삭제와 상품 등록, 상품 삭제와 주문 확정 경쟁 | 행 잠금으로 실행 순서를 보장하여 삭제 브랜드에 미삭제 상품이 생기지 않음. 삭제가 먼저 반영된 상품의 DRAFT 확정은 거절. 확정이 먼저 완료됐다면 이후 삭제해도 확정 결과 보존 |

대표 사례만 통과시키고 API 계약표의 다른 행을 생략하지 않는다. 각 API에는 정상 1개 이상·대표 오류 1개 이상의 HTTP 계약 검증을 연결한다. 삭제·수정·충전·확정의 실패는 오류 코드뿐 아니라 **실패한 요청의 변경이 남지 않는지**도 확인한다. 단독 실행에서는 변경 전후 DB 값이 같아야 한다. 동시 실행에서는 다른 요청의 성공 효과를 보존하고 실패 요청의 추가 효과가 없는지 확인한다. API-23의 재고5에서 두 주문이 각4개를 요청하는 경우 최종 재고1·성공1건·실패 주문DRAFT를 유지해야 한다.

## 충전부터 주문 조회까지의 연결 검증

이 절은 과거 POINT 결제의 연결 검증 기록이다. W5 신규 주문에는 아래 잔액 3,000 기대값을 재사용하지 않고 W5-TDD-24의 충전·PG 분리와 포인트 미차감을 검증한다.

잔액 SQL 갱신 없이 fixture 초기 잔액 0에서 시작한다. 충전 API로 10000원을 충전하고 단가 2000원×2개·3000원×1개를 DRAFT로 생성한다. 생성 직후 잔액10000·재고 유지, 확정 후 결제액7000·잔액3000·각 상품 재고 차감을 확인한다. 별도 HTTP 요청으로 내 주문 목록·상세와 잔액을 다시 조회해 저장 결과를 검증하며 다른 사용자의 주문·잔액도 보존한다. [PointOrderJourneyApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/PointOrderJourneyApiE2ETest.java)가 이 흐름을 담당한다.

## 작은 기능의 구현 순서와 검증 기록

아래는 순서 제안이며 한 행의 API를 한 번에 모두 구현한다는 뜻이 아니다. 매번 변경할 책임·파일·관련 테스트를 정리하고 작은 기능 하나의 diff와 결과를 확인한 뒤 다음으로 넘어간다.

문서를 보완한 뒤에는 다음 순서로 기존 구현과 다음 규칙을 연결한다.

1. **문서 보완:** 업무 계약과 [계층·DIP 기준](commerce-erd-draft.md#구조와-코드-의존)을 확인한다. 새로운 업무 정책이나 검사 기준 변경은 먼저 질문하고, 설명 보완과 구분한다.
2. **기존 구현 재검증:** 영향을 받는 책임·입출력 타입·의존을 대조하고 관련 테스트를 확인한다. 설명만 보완했고 현재 구현이 이미 맞으면 유지한다. 이 검증을 새로운 Red로 기록하지 않는다.
3. **다음 규칙의 TDD:** 다음 작은 행동의 기대값을 테스트에 추가해 의도한 실패를 확인하고, 최소 구현 후 통과시키고 리팩터링한다. 이미 통과하는 경계 사례를 인위적으로 실패시키지 않는다.
4. **기록 갱신:** 실제 구현·검사 결과와 남은 범위를 문서에 반영한 뒤 다음 규칙으로 진행한다.

| 순서 | 기능 단위 | 연결할 계약·검증 |
| --- | --- | --- |
| 1 | 상품 재고 차감·최종 수량 설정 규칙 | STOCK-01~05와 STOCK-SET-01~05의 Red → Green → Refactor |
| 2 | 사용자 식별·권한 해석, fixture 연결·HTTP 진입 검증 | IDENTITY-01~10의 TDD 완료. 후속 HTTP에서 X-USER-ID와 API-21 연결까지 검증 |
| 3 | 브랜드 이름·모델·생명주기·삭제 조건 후 등록·상세부터 CRUD 하나씩 | W2 기준 BRAND-NAME-01~04·BRAND-01~06·BRAND-LIFECYCLE-01~06·BRAND-DELETE-01~07 단위 검증과 BRAND-PERSIST-01~07의 Brand DB 매핑 검증 완료. BRAND-QUERY-01~03의 고객 상세 application 검증 완료. C01 HTTP와 관리자 등록·상세 application도 검증 완료. 상품 존재 SQL·나머지 관리자 유스케이스·행 잠금·A01~A05 HTTP와 API-16·18도 W2 계약으로 검증 완료. [관리자 브랜드 기록](commerce-tdd-admin-brand-http-log.md)은 과거 증거이며, W3 삭제·경합의 구현·검증은 W3-BRAND-01..08에서 별도로 진행 |
| 4 | 상품 등록·수정·재고 설정 각각 | A07~A11, API-17~19, API-24의 브랜드 삭제·상품 등록 경합. 수량 객체를 상품 생명주기에 연결하고 STOCK-06 검증 |
| 5 | 상품 상세·목록·필터·페이지·정렬을 순서대로 | C02·C03, A06, API-01~04 |
| 6 | 좋아요 등록·취소·내 목록 각각 | C04~C06, API-05·06·15. 동시 중복도 관계1건·추가 변경 없음 확인. 신규·중복·취소의 200과 LikeResult는 확정 계약 |
| 7 | 포인트 충전·저장 잔액 조회 | C07·C08, API-07·08 |
| 8 | 주문 생성·목록·상세 | C09·C11·C12, API-09~11·15·22 |
| 9 | 주문 확정·재요청·동시성 | C10, API-12~14·23·24 |
| 10 | 관리자 주문 조회·고객 조회와 차이 | A12·A13, API-20·21 |

도메인 단위 테스트는 상태·계산을, API 테스트는 method/path/입력/응답/오류를, DB 통합 테스트는 저장·유일 제약·트랜잭션·동시성을 검증한다. 단계별 작업 기록에는 **관련 계약 ID, Red의 의도한 실패, Green 결과, Refactor 내용, diff, 실행한 검사와 미실행 이유**를 남긴다. 아직 수행하지 않은 단계의 성공 기록은 만들지 않는다.

기능별 테스트에 이어 관련 Checkstyle·ArchUnit을 확인한다. 아래 명령은 검사 방법이며, 문서 작성으로 실행 완료한 결과가 아니다.

```shell
./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:test --tests 'com.loopers.architecture.ArchitectureTest'
```
