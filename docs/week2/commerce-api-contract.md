# 커머스 API 계약

[전체 설계](commerce-erd-draft.md) · [정책 선택과 설계 검토](commerce-policy-decisions.md) · [TDD 계획](commerce-tdd-plan.md)

고객 계약 C01~C12와 관리자 계약 A01~A13의 입력·응답·오류와 W5의 결제 교체 계약을 정리한다. 업무 규칙과 세 대표 흐름은 전체 설계를 참조한다. **2026-10-23 현재 내부 startNew·startOrReuse·단건 refreshOnce에서 실제 PG HTTP, HMAC 저장/비교와 주문·재고 TX A/B를 연결했다. 고객 confirm·고객/관리자 주문 조회·최초 요청 제한 조회·PG 조회 회로를 연결했다. callback/reconcile HTTP도 연결했으며 이번 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 따른다. confirm·조회·제한 조회의 선행 증거는 [고객 결제 API TDD 기록](../week5/customer-payment-api-tdd-log.md)으로 보존한다.** HTTP·재고의 선행 결과는 [HTTP·주문·재고 연결 기록](../week5/order-payment-http-connection-tdd-log.md), 후속 지문 공동 저장·내부 동일 재요청은 [주문 HMAC 연결 기록](../week5/order-payment-fingerprint-connection-tdd-log.md)을 따른다. [재고 확보·반환 기록](../week5/order-stock-reservation-tdd-log.md), [PG HTTP 기록](../week5/local-test-pg-http-tdd-log.md), [주문·결제 기록](../week5/order-payment-persistence-tdd-log.md), [예약 상태 기록](../week5/order-reservation-state-tdd-log.md)과 [결제 시도 기록](../week5/payment-attempt-state-tdd-log.md)은 선행 증거로 보존한다. 고객 confirm API는 이제 PG 방식이며 관리자 status 필터는 승인된 DRAFT·CONFIRMED·PAYMENT_PENDING·PAYMENT_FAILED를 허용한다. 이전 POINT의 저장 사실과 본문 없는 재확정 오류는 보존한다. 최초 요청의 제한 조회·시간/회로 초기값과 저장 A안, 후속 UUID 키·테스트 PG 규격 카드 입력·호출 전 DB 상태 전이로 최초 발송을 보호하는 사용자 선택은 유지한다. 아래 W5 고객 confirm·조회와 callback/reconcile 계약은 현재 코드에 연결했다. 선행 관리자 재고 상한 응답은 구현돼 있다. `기본 API 계약` 이하의 기존 표는 W2~W4 구현과 POINT 결제의 기준을 보존하며 변경 대상이 아닌 계약은 계속 유지한다.

2026-09-18 추천 묶음 승인은 기존 입력·응답·정렬·오류 계약에 대한 것이다. 기존 “제안” 절 제목은 연결된 앵커를 보존하기 위한 이름이며 당시 확정 범위를 가리킨다. **W5의 신규 필드·오류 코드·기술 제안까지 승인된 것으로 해석하지 않는다.**

**2026-10-08 W3 변경 범위:** A05 브랜드 삭제를 연결된 미삭제 상품의 일괄 논리 삭제로 확장했다. **`W3-BRAND-01~08`의 정상 처리·기본 경계·전체 롤백·연결 API·접근 제한·주문 및 상품 등록 경합 검증을 완료**했다. 마지막 연결·동시성 검증은 [네 번째 증분 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증), 전체 범위는 [체크리스트](commerce-completion-checklist.md)를 따른다. 기존 W2 실행 기록은 당시 계약의 증거로 보존한다.

**후속 승인 · 주문 재확정:** C10의 본인 CONFIRMED 주문 재요청은 `409 ORDER_ALREADY_CONFIRMED`로 거절하도록 변경했다. 사용자 승인 P16의 `W3-RECONFIRM-01..06` 구현·검증을 완료했으며, 실제 결과는 [재확정 실행 기록](../week3/order-reconfirmation-tdd-log.md)을 따른다. 이 오류는 W5 교체 후에도 **이전 POINT 확정 주문에 유지**하고, PG 동일 시도의 종결 결과 반환과 구분한다. 당시 재확정 증분은 W3의 8개 주문 재고 경쟁·3개 주문 포인트 경쟁·주문 내부 저장 경계 실패/롤백 검증·갱신 유실 대조군까지 포함한 완료 기록은 아니다.

## W5 PG 결제 API 설계

출처는 [W5 과제 원문](../week5/assignment-original.md)·[채점 기준](../week5/grading-criteria.md)이다. 아래에서 **과제 확정**은 원문의 요구이고 **제안·미정**은 구현 전에 선택할 세부 계약이다. 상태·TX 경계는 [W5 전체 설계](commerce-erd-draft.md#w5-pg-결제-설계), 질문과 선택 상태는 [W5 정책 검토](commerce-policy-decisions.md#w5-결제-교체의-선택과-질문)를 따른다.

### 교체 범위와 기존 API의 관계

| 대상 | W5에서 적용할 계약 | 유지·주의할 점 |
| --- | --- | --- |
| C10 · 기존·신규 DRAFT | 확정 요청에서 로컬 테스트 PG 시도를 시작한다. 주문당 한 결제 시도이며 새 PG 결제의 포인트 차감은 0회다. | 소유권·저장 품목·양수 수량·저장 금액·삭제·재고 검사는 유지한다. 포인트 부족으로 PG 결제를 거절하거나 PG 장애를 포인트로 대신 결제하지 않는다. |
| 이전 POINT 확정 주문 | 수단 POINT·결제액·결과·당시 품목을 조회하고, 재확정은 기존 `409 ORDER_ALREADY_CONFIRMED`다. | PG 시도로 바꾸거나 다시 결제하지 않는다. 수단의 모델·SQL 이행은 구현했지만 현재 고객/관리자 조회 DTO에도 수단을 추가했다. 사용자 DB 이행은 실행하지 않았고 보존 전제를 유지한다. |
| C07·C08 · 포인트 | 충전·잔액 조회의 기존 method/path·입력·응답·오류를 유지한다. | 주문 결제와 분리한다. 새 충전·사용 이력이나 실제 외부 결제는 추가하지 않는다. |
| C09 · 주문 생성 | 생성 스냅샷·DRAFT 저장과 기존 입력·오류를 유지한다. | 생성 시 재고 예약·포인트 차감은 없다. PG 호출 전 재고 확보는 C10의 책임이다. |
| C11·C12·A12·A13 · 주문 조회 | 기존 품목·금액에 결제수단, 시도 상태, 최종 결과의 구분을 더한다. | 고객 본인 조회와 관리자 권한을 유지한다. 이전 POINT 사실은 보존하고, GET이 새 PG 결제를 생성하지 않는다. |
| A11 · 재고 설정 | `stockQuantity`는 **예약을 제외한 가용 수량의 최종값**이다. 기존 예약을 지우지 않는다. | `W5-Q03` 승인: 같은 상품을 잠근 상태에서 `설정할 가용 수량 + 아직 소비·반환하지 않은 HELD 예약 합계 ≤ Integer.MAX_VALUE`를 확인한다. 반환 여유 초과는 후속 사용자 승인한 `409 STOCK_CAPACITY_EXCEEDED`·“예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.”다. `meta.result=FAIL`·`data` 생략이며 기존 가용량·예약·캐시 버전을 유지한다. |
| A05·A10 · 삭제와 미완료 주문 | 새 조회·새 주문·예약 전 DRAFT 확정은 기존대로 삭제 대상을 제외한다. 예약을 마친 결제 대기는 저장된 품목·금액·예약으로 마무리한다. | 실패 시 예약을 반환해도 삭제 상품을 재노출하지 않는다. 기존 논리 삭제와 과거 주문 보존은 유지한다. |

### Commerce 요청과 응답 의미

아래 고객 PG confirm·조회 및 callback/reconcile 계약을 코드에 연결했다. `startNew/prepareNew`는 신규 전용이며 `startOrReuse/prepareOrReuse`가 저장 지문 비교와 동일 요청의 현재 상태 반환을 담당한다. `refreshOnce`는 저장 식별자로 한 번 조회하고 종결됐으면 조회 없이 반환한다. [HMAC 계산](../week5/payment-request-fingerprint-tdd-log.md)·[저장](../week5/payment-request-fingerprint-persistence-tdd-log.md)·[키 공급](../week5/payment-fingerprint-key-configuration-tdd-log.md)·[내부 TX 연결](../week5/order-payment-fingerprint-connection-tdd-log.md)은 구현했다. **후속 사용자 선택으로 아래 상세 JSON·오류·ID 계약을 확정하고 경계 DTO/오류 분류/생성 유틸리티에 이어 고객 confirm·조회·180/175초 제한 조회·조회 회로를 연결했다. callback/reconcile HTTP도 연결했으며 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 참조한다.** 실제 HTTP 연결의 검사 범위는 [API TDD 기록](../week5/customer-payment-api-tdd-log.md)으로 구분한다. 과거 NULL 기록을 위한 5인자 PaymentAttempt 생성자는 신규 준비에서 사용하지 않는다. 담당자 사전 승인은 구현 조건이 아니다.

| 연결 | method/path | 입력·접근 | 반환·효과 |
| --- | --- | --- | --- |
| C10 변경 | `POST /api/v1/orders/{orderId}/confirm` | 고객 `X-USER-ID`, 경로 orderId, 본문의 `paymentAttemptKey`·합성 카드 입력. 금액은 저장된 주문에서 결정한다. | 최초 발송 실행은 재고 확보·시도 저장 후 PG 생성·제한 조회를 수행한다. 종결 확인 시 `200`, 선택한 시간/횟수 한도 뒤 미완료면 `202`·상태·조회 경로. 동일 시도 재요청은 저장 결과를 반환하며 추가 효과를 만들지 않는다. |
| C12 확장 | `GET /api/v1/orders/{orderId}` | 고객 `X-USER-ID`, 경로 orderId | `200`, 본인 주문의 저장된 품목·금액·결제수단·시도 상태·최종 결과. 없는 주문과 타인 주문은 동일한 `404 ORDER_NOT_FOUND`다. |
| C11 확장 | `GET /api/v1/orders` | 고객 `X-USER-ID`, 기존 page·size | `200`, 본인 주문 페이지. 대기·미확인·최종 결과를 구별하며 기존 정렬·페이지 계약을 유지한다. |
| 신규 복구 · 연결 | `POST /api/v1/orders/{orderId}/payment/reconcile` | 고객 `X-USER-ID`, 경로 orderId. 본문 없음 또는 `{}`를 허용하며 카드·시도 키는 받지 않는다. 대상은 본인의 기존 PG 시도다. | 기존 외부 거래를 조회·검증·적용한다. 대기·미확인은 `202`, 같은 시도의 종결 결과는 `200`. 새 시도·예약·PG create를 만들지 않는다. |
| 신규 알림 · 연결 | `POST /api/v1/payments/callback` | 로컬 PG의 `providerOrderId`, `transactionKey`, `status` 알림 | 본문만으로 확정하지 않고 고정 PG를 권위 조회한 뒤 동일한 결과 적용 경계를 사용한다. ACK·오류·단건 예산은 아래 [복구 계약](#복구callback-http-계약-선택)의 위임 선택을 따른다. 고객 식별 헤더를 PG 신뢰의 근거로 사용하지 않는다. |
| A12 확장 | `GET /api-admin/v1/orders` | 기존 ADMIN 권한, userId·status·page·size | `200`, 구매자·상태별 주문 페이지에 결제 구분을 추가한다. status 필터도 결제 대기·실패 상태를 표현할 수 있도록 확장한다. 기존 `DRAFT`·`CONFIRMED` 의미와 권한 우선을 유지한다. |
| A13 확장 | `GET /api-admin/v1/orders/{orderId}` | 기존 ADMIN 권한, 경로 orderId | `200`, 구매자와 주문·결제 상태 조회. 관리자도 이 GET으로 새 결제를 만들지 않는다. |

`202`는 결제 성공이 아니라 **아직 최종 결과를 확정하지 못했다는 응답**이다. 주문·시도 상태와 조회 경로를 함께 반환하며, 조회 경로는 기존 C12와 신규 reconcile을 구분해 안내한다. `200`도 HTTP 요청의 정상 처리와 PG의 SUCCESS·FAILED 결과를 구분하여 읽는다. **후속 사용자 선택으로 기존 ApiResponse를 유지하고 정상 상태 반환은 meta.SUCCESS, 실제 결과는 payment.status로 구분한다.** 아래 [상세 계약](#고객-결제-api-상세-계약)이 JSON·오류·식별자 선택의 최신 기준이다. confirm·조회와 callback/reconcile HTTP를 연결했다. 검증 결과는 최신 복구 기록을 따른다.

| 구분 | 과제에서 확정한 의미 | 사용자 선택과 남은 세부 계약 |
| --- | --- | --- |
| 결제 입력 | 호출자가 UUID 형식의 `paymentAttemptKey`를 생성하고 재요청에 재사용한다. 서비스 전체에서 키 유일성을 보호하며 카드 입력은 PG의 `cardType`·`cardNo` 규격에 맞춘다. 저장 주문의 금액을 사용하고 고객의 금액·URL을 신뢰하지 않는다. | 특정 합성 번호 한 종류 제한은 추가하지 않는다. 요청 동일성은 [서버 HMAC-SHA256](commerce-policy-decisions.md#w5-hmac-요청-지문-제안)이며 기존 세 컬럼 NULL 보존·비교 불가를 유지한다. 후속 사용자가 아래 세 필드 JSON·입력 경계·오류 구분을 선택했다. 카드 번호 DB·로그 비저장을 유지하며 고객 controller에 연결했다. |
| 주문 응답 | 기존 orderId·품목·totalAmount와 POINT/PG 수단, 시도 유무·진행 상태·최종 결과를 구별한다. | [주문·결제 시도 전이표](commerce-erd-draft.md#주문결제-시도의-상태-전이표)의 내부 상태·sendStartedAt과 결제수단은 모델·JPA·SQL에 구현했다. DRAFT 수단은 null이고 PG 준비 이후는 PG 필수다. 후속 상세 계약에서 paymentMethod·payment·PG 조회 경로와 null 생략을 선택했다. 시도가 없는 DRAFT를 UNKNOWN으로 표현하지 않으며 고객/관리자 상세·목록에 같은 결제 표현을 연결했다. |
| 금액·확정 결과 | PENDING·UNKNOWN을 CONFIRMED로 표시하지 않는다. PG SUCCESS를 검증·반영한 경우에만 확정 결제액·시각을 저장한다. | 미완료·실패를 금액 0 또는 가짜 성공으로 표현하지 않는다. 응답 DTO의 구체 모양은 구현 전 계약 테스트 기대값으로 확정한다. |
| 첫 응답과 복구 | 대기·미확인은 202, 수동 reconcile이 기본 복구 경로다. 조회 횟수·간격·총 대기는 유한해야 한다. | `W5-Q02·Q06` 선택: 최초 confirm은 전체 180초 목표/외부 확인 175초, 생성 1회·조회 시도 최대 12회의 제한 조회. [단일 설정표](commerce-policy-decisions.md#w5-최초-결제-요청의-대기조회회로-설정)를 따른다. 제한 조회·회로 연결은 구현했다. 운영 전체 하드 상한 검증은 남았다. callback/reconcile은 단건 GET·호출 전체10초를 선택했으며 HTTP를 연결했고 검증 결과는 최신 복구 기록을 따른다. |

**요청 지문의 계산·비교·저장·키 공급·재요청 비교를 고객 PG confirm까지 연결했다.** 고객이나 PG가 HMAC을 보내거나 중복 제거하는 구조가 아니다. 기존 전체NULL 지문은 소급 생성하지 않으며, 없는 형식/키 버전을 현재 버전으로 대체하지 않는다. 키 설정이 없어도 앱은 시작하고 계산·비교만 불가로 둔다. [키 관리 정책](commerce-policy-decisions.md#키-관리와-검증-범위)을 유지한다. 후속 사용자 승인에 따라 전체NULL·서버 검증 불가·저장 손상의 내부 사유를 나누고 아래 409/503/500으로 분류한다. 카드·지문 없이 GET·기존 거래 복구를 유지하며, 비교 불가를 PG FAILED·예약 반환·재발송으로 바꾸지 않는다. 과제 해석 질문은 선택 사항이다.

### 고객 결제 API 상세 계약

**2026-10-23 사용자 선택:** 평평한 세 필드 요청, 기존 주문 응답에 결제 정보 추가, API 처리 결과와 결제 결과 분리, 오류 세 종류 구분, 주문 ID 기반 PG 식별자를 선택했다. 아래 confirm·조회 계약을 실제 controller에 연결했다. 계약 경계의 선행 기록과 [고객 API·제한 조회·회로 연결 기록](../week5/customer-payment-api-tdd-log.md)을 구분하며 callback/reconcile은 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)의 범위로 연결했다.

#### 요청 JSON

`POST /api/v1/orders/42/confirm` · `X-USER-ID: alice` · `Content-Type: application/json`

```json
{
  "paymentAttemptKey": "de305d54-75b4-431b-adb2-eb6b9e546014",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000"
}
```

| 항목 | 계약·이유 |
| --- | --- |
| 세 필드 | 모두 필수 문자열. 키는 `8-4-4-4-12` 자리 UUID 표현이며 16진수 대소문자는 같은 UUID로 해석한다. 카드 종류·번호는 공백만 있는 값을 거절하며 원문을 사용한다. PG와 같은 이름을 써 중첩 객체를 추가하지 않는다. |
| 입력 경계 | 누락·null·다른 JSON 타입·추가 필드는 기존 `400 INVALID_REQUEST`. 카드 문자열의 trim·하이픈 제거, 실카드 검사/Luhn, 예시 번호 한 종류 제한을 추가하지 않는다. 테스트에는 합성 카드만 사용하며 문자열 검증이 실제 카드와 합성 카드를 판별한다는 뜻은 아니다. |
| 서버 소유 값 | 금액·구매자·PG용 주문 ID·callback URL·HMAC은 본문으로 받지 않는다. 금액은 저장 주문, 구매자는 식별/소유권 검사, PG ID/URL은 서버가 결정한다. |
| 보존 | 카드 원문·요청 본문은 DB·로그에 남기지 않는다. 요청 DTO의 문자열 표현도 원문을 숨긴다. 재요청은 같은 UUID를 사용하며 내용 비교·한 번의 발송은 기존 내부 경계가 보호한다. |
| POINT 호환 | PG 입력 파서는 PG 분기에서 사용한다. 기존 본문 없는 POINT 본인 재확정 `409 ORDER_ALREADY_CONFIRMED`, 타인 `404 ORDER_NOT_FOUND`를 필수 카드 누락 400으로 바꾸지 않는다. |

#### 응답 JSON과 상태

아래는 최종 결과를 아직 모르는 PG 주문의 예시다. confirm의 선택된 대기 한도 뒤에는 `202`, 같은 데이터의 GET 조회는 `200`이다. 동시 동일 confirm은 긴 대기를 다시 시작하지 않고 현재 저장 결과를 반환한다.

```json
{
  "meta": { "result": "SUCCESS" },
  "data": {
    "orderId": 42,
    "status": "PAYMENT_PENDING",
    "items": [
      { "productId": 7, "productName": "테스트 상품", "unitPrice": 7000, "quantity": 1, "subtotal": 7000 }
    ],
    "totalAmount": 7000,
    "createdAt": "2026-10-23T00:00:00Z",
    "paymentMethod": "PG",
    "payment": {
      "paymentAttemptKey": "de305d54-75b4-431b-adb2-eb6b9e546014",
      "status": "UNKNOWN"
    },
    "links": {
      "order": "/api/v1/orders/42",
      "reconcile": "/api/v1/orders/42/payment/reconcile"
    }
  }
}
```

| 표현 | 계약·이유 |
| --- | --- |
| 기존 필드 | `orderId/status/items/totalAmount/paidAmount/createdAt/confirmedAt`의 이름·의미를 유지한다. 기존 최상위 주문 구조를 전부 `order` 객체 안으로 이동시키지 않는다. |
| 새 필드 | `paymentMethod`와 `payment: {paymentAttemptKey, status}`를 추가한다. 주문 상태와 결제 시도 상태는 다른 의미이므로 구분한다. 고객/관리자 상세·목록에도 같은 의미를 적용하고 관리자의 `userId`는 유지한다. |
| 시도 없는 주문 | DRAFT는 수단·payment 생략, 과거 POINT는 `paymentMethod=POINT`이며 payment 생략. DRAFT를 UNKNOWN으로 표현하거나 POINT의 가짜 PG 시도를 만들지 않는다. 기존 null 생략 직렬화를 유지한다. |
| PG의 다섯 상태 | `NOT_OBSERVED`: 발송 시작은 저장했지만 PG 결과 관찰 전. `PENDING`: PG 처리 중 확인. `UNKNOWN`: 결과 확인 불가. `SUCCESS/FAILED`: 검증·저장한 최종 결과. NOT_OBSERVED를 PG가 접수한 PENDING으로 바꾸지 않는다. |
| 금액·시각 | PG SUCCESS 때만 `paidAmount/confirmedAt`을 채운다. 미완료·FAILED는 생략하며 0원 결제로 꾸미지 않는다. 과거 POINT의 저장 금액·시각은 그대로 반환한다. |
| 후속 경로 | PG 응답에 `links.order`(GET 저장 상태 조회), `links.reconcile`(POST 외부 거래 재확인)를 둔다. 서버가 상대 경로를 만들고 고객 URL을 반영하지 않는다. reconcile 경로도 연결했으며 저장 종결 반환·미완료 단건 재확인 계약을 따른다. |
| 비노출 | 카드 번호·HMAC·형식/키 버전·비밀키·제공자 계정·발송 시각은 응답하지 않는다. providerOrderId/transactionKey도 현재 고객 응답에는 필요하지 않아 제외한다. |

| 응답 상황 | HTTP | `meta.result` | 업무 결과 |
| --- | --- | --- | --- |
| confirm/reconcile의 미완료 | 202 | SUCCESS | 주문 PAYMENT_PENDING, 결제 NOT_OBSERVED/PENDING/UNKNOWN, 조회 경로 |
| confirm/reconcile의 검증된 PG 성공 | 200 | SUCCESS | 주문 CONFIRMED, 결제 SUCCESS |
| confirm/reconcile의 검증된 PG 실패·효과 0건 확정 거절 | 200 | SUCCESS | 주문 PAYMENT_FAILED, 결제 FAILED. 실패도 확인된 업무 결과이며 새 시도를 허용하지 않음 |
| 정상 GET 조회 | 200 | SUCCESS | 저장된 상태 그대로. GET은 PG 생성·결과 재확인 호출을 하지 않음 |
| 입력·소유권·충돌·내부 처리 오류 | 해당 4xx/5xx | FAIL | `meta.errorCode/message`, data 생략 |

`meta.result=SUCCESS`는 API가 상태를 정상 반환했다는 뜻이며 결제 성공 표시가 아니다. 결제 성공 판단에는 반드시 `payment.status=SUCCESS`와 주문 확정 상태를 사용한다. `202`의 미완료 의미와 상태 조회 경로는 [RFC 9110 §15.3.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3)을 참고하되 JSON 이름·meta 의미는 이번 프로젝트의 선택이다.

#### 요청 충돌·비교 불가 오류

| 원인 | HTTP · errorCode | message | 선택 이유 |
| --- | --- | --- | --- |
| 다른 주문의 같은 키·같은 주문의 새 키·같은 키의 다른 내용 | `409 PAYMENT_REQUEST_CONFLICT` | 기존 결제 시도와 일치하지 않는 요청입니다. | 저장된 시도와 요청이 충돌한다. 다른 주문 정보나 어떤 카드인지 노출하지 않는다. |
| 기존 기록의 지문·두 버전이 모두 NULL | `409 PAYMENT_REQUEST_UNVERIFIABLE` | 기존 결제 요청과 비교할 정보가 없습니다. 주문 조회 또는 결제 결과 재확인을 이용해 주세요. | 현재 기록으로 요청 비교가 불가능하다. 기다리거나 새 카드로 소급 지문을 만들면 해결되는 문제가 아니다. |
| 서버 키 누락·키 설정 불가·저장 형식/키 버전 미지원 | `503 PAYMENT_VERIFICATION_UNAVAILABLE` | 현재 결제 요청을 확인할 수 없습니다. | 고객 입력 오류가 아닌 서버의 검증 기능 사용 불가다. 서버 설정·지원 복구가 필요하며 자동 회복 시각을 약속하지 않는다. |
| 일부만 NULL·지문 길이/버전 손상 등 저장 불변식 위반 | 기존 `500 INTERNAL_ERROR` | 일시적인 오류가 발생했습니다. | 정상적인 과거 무지문 기록과 손상 데이터를 구분한다. |

모두 `meta.result=FAIL`, data 생략이다. 지문·키·예외 원문을 응답하지 않고 PG FAILED 변경·예약 반환·새 create도 하지 않는다. 키 문제로 신규 TX A가 실패하면 준비 전체를 롤백하며, 기존 시도의 GET/기존 거래 복구는 지문 없이 유지한다. 복구 시간을 모르므로 `Retry-After`를 임의로 넣지 않는다. 503을 받은 클라이언트에게 새 시도 키를 만들거나 create를 재발송하라는 의미를 부여하지 않는다.

내부에서는 예외의 **타입/사유 값**으로 구분하며 메시지 문자열을 파싱하지 않는다. PG 결과 충돌과 비활성화 오류는 아래 후속 승인 표를 따른다. callback ACK·충돌 구조 로그·복구 요청의 단건 예산은 아래 복구 계약에서 위임 선택으로 정했으며 HTTP 연결·검증과 구분한다. 현재 상태 충돌의 [409 의미](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.10), 서비스 사용 불가의 [503 의미](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.6.4)를 참고한 프로젝트 선택이며 RFC가 위 업무 코드를 정하는 것은 아니다.

후속 승인한 서비스 가용성·외부 결과 충돌 응답도 고객 confirm에 연결했다.

| 원인 | HTTP · errorCode | message | 보존·후속 |
| --- | --- | --- | --- |
| PG 기능이 비활성화되어 신규 DRAFT 결제를 시작할 수 없음 | `503 PAYMENT_PROVIDER_UNAVAILABLE` | 현재 결제 서비스를 사용할 수 없습니다. | 새 시도·예약·포인트 차감 없음. POINT 대체 결제를 하지 않는다. 소유권·기존 POINT 재확정·PG 입력 검사는 먼저 유지한다. 기존 PG 재요청은 adapter 없이 지문을 비교해 저장 결과 또는 정해진 비교 오류를 반환한다. |
| PG 거래 식별·금액 또는 종결 결과가 저장 정보와 충돌 | `409 PAYMENT_RESULT_CONFLICT` | 외부 결제 결과가 저장된 결제 정보와 충돌합니다. | 기존 결과·예약을 보존하며 새 create·임의 실패·반환을 하지 않는다. 충돌 WARN 구조 로그·callback ACK는 아래 복구 계약의 선택을 따르며 HTTP에 연결했으며 검증 결과는 최신 복구 기록을 따른다. |

두 오류도 `meta.result=FAIL`, data 생략이며 원시 PG 응답·카드·지문을 노출하지 않는다.

#### PG용 주문 ID

| 항목 | 선택·이유 |
| --- | --- |
| 신규 규칙 | 서버가 `LP-ORD-` + 내부 주문 ID의 **최소 10자리** 십진수 표현을 사용한다. 주문 42 → `LP-ORD-0000000042`. 10자리를 넘는 Long은 자르지 않는다. |
| 생성·보존 | 신규 TX A에 저장한 값을 최초 POST·조회에 계속 사용한다. 기존 시도가 있으면 새 규칙으로 재생성하지 않고 저장된 providerOrderId를 읽는다. 과거 매핑 차이로 정상 재요청을 충돌로 오판하지 않는다. |
| 식별 역할 | 내부 주문 ID는 주문, 호출자 UUID는 시도, providerOrderId는 PG 주문, transactionKey는 PG 거래다. providerOrderId가 같아도 PG가 중복 제거하지 않으므로 기존 DB 발송 보호를 유지한다. |
| 신뢰 경계 | 고객 입력으로 받지 않는다. callback의 문자열 접두사를 잘라 내부 주문을 신뢰하지 않고 저장된 제공자 계정·PG 주문 식별 관계로 찾고 검증한다. |
| 대안과 한계 | 서버 UUID를 별도로 생성하는 안보다 주문 추적이 쉽다. 대신 같은 PG 계정을 여러 DB가 공유하거나 DB만 초기화해 주문 ID를 재사용하면 충돌할 수 있다. 이번 격리 실습 범위의 선택이며 다중 환경 운영 안전성을 주장하지 않는다. 사용자 DB 초기화는 하지 않는다. |

계약 경계의 선행 검사는 [고객 결제 계약 TDD 기록](../week5/customer-payment-contract-tdd-log.md), 실제 controller·조회·제한 조회/회로 연결은 [고객 결제 API TDD 기록](../week5/customer-payment-api-tdd-log.md)으로 구분한다. callback ACK·미시작 reconcile 오류·복구 요청 예산·충돌 구조 로그는 아래 위임 선택으로 정했고 구현·검증 결과는 별도로 기록한다.

### 복구·callback HTTP 계약 선택

**2026-10-23 사용자 위임에 따른 이번 구현안 선택이다.** 과제가 아래 오류 이름·ACK 모양을 유일한 정답으로 강제한다는 뜻은 아니다. 두 HTTP 진입점은 현재 연결했으며 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 따른다. 기존 내부 단건 조회·TX B·롤백·복구 증거는 그대로 보존한다.

#### 고객 수동 재확인

| 항목 | 선택한 계약 |
| --- | --- |
| 요청 | `POST /api/v1/orders/{orderId}/payment/reconcile`, 고객 `X-USER-ID`. 본문 없음 또는 빈 JSON 객체 `{}`만 허용한다. 카드·시도 키·금액·외부 URL은 받지 않는다. |
| 접근 순서 | 기존 식별·경로 파싱과 소유권을 먼저 확인한다. 없는/타인 주문은 `404 ORDER_NOT_FOUND`; 본인 DRAFT는 `409 PAYMENT_NOT_STARTED`, 본인 POINT는 기존 `409 ORDER_ALREADY_CONFIRMED`다. |
| 기존 PG 종결 | SUCCESS/FAILED는 저장 결과 `200`; PG 조회 0회. PG OFF·지문 없음·키 설정 불가여도 저장 사실을 반환한다. |
| 기존 PG 미완료 | 저장된 transactionKey가 있으면 거래 GET, 없으면 저장 providerOrderId의 주문별 GET을 최대 1회 한다. 카드/HMAC 비교 없이 권위 결과를 검증하고 기존 TX B에 반영한다. |
| 결과 | PG 종결을 반영하면 `200`, PENDING/UNKNOWN·조회 404/빈 목록·회로 차단 등으로 미완료이면 `202`. 기존 `OrderPaymentResponse`와 `meta.result=SUCCESS`를 사용한다. |
| PG OFF | 미완료는 `503 PAYMENT_PROVIDER_UNAVAILABLE`; 종결은 앞의 `200`. 새 시도·예약·PG 생성·POINT 대체 없음. |
| 충돌·내부 실패 | 외부 식별·금액·종결 충돌은 `409 PAYMENT_RESULT_CONFLICT`, DB 저장 실패는 기존 `500 INTERNAL_ERROR`, 잠금 경합은 기존 `409 CONCURRENT_MODIFICATION`. 미완료 202로 숨기지 않는다. |

#### 로컬 PG callback

```json
{"providerOrderId":"LP-ORD-0000000042","transactionKey":"TEST-TX-0042","status":"SUCCESS"}
```

| 항목 | 선택한 계약 |
| --- | --- |
| 요청 | `POST /api/v1/payments/callback`, JSON의 위 세 필드만 허용한다. 필드 누락·추가·잘못된 타입은 기존 `400 INVALID_REQUEST`다. |
| 입력 범위 | providerOrderId는 6~255 Unicode 코드 포인트, transactionKey는 1~255 코드 포인트의 비공백 문자열이다. 정리·대소문자 변환 없이 원문 식별값을 사용한다. status는 대소문자가 일치하는 `PENDING`·`SUCCESS`·`FAILED`만 허용한다. |
| 신뢰 경계 | 본문의 status는 힌트일 뿐이다. 내부 고객 헤더를 인증 근거로 삼지 않고 서버의 고정 제공자 계정 `135135` + 저장 providerOrderId로 시도를 찾는다. 접두사에서 내부 주문 ID를 파생해 신뢰하지 않는다. |
| 사전 검사 | 저장 시도 없음은 `404 PAYMENT_ATTEMPT_NOT_FOUND`; 이미 저장된 transactionKey와 알림 키가 다르면 `409 PAYMENT_RESULT_CONFLICT`. 이 두 경우 PG 조회 0회다. |
| 권위 조회 | 알림의 transactionKey로 고정 PG 거래 GET을 최대 1회 한다. 종결 시도도 실제 거래를 조회해 반대 종결·식별·금액 불일치를 구분한다. 알림 status가 아니라 검증된 조회 결과를 적용한다. |
| 반영·ACK | 유효한 PENDING, 같은 결과의 중복, 검증된 최종 결과의 반영/보존은 `200`, `meta.result=SUCCESS`, `data={"acknowledged":true}`. ACK가 결제 SUCCESS를 뜻하지는 않는다. |
| 확인 불가 | 거래 404·UNKNOWN·회로 차단은 `503 PAYMENT_RESULT_UNAVAILABLE`; PG OFF는 `503 PAYMENT_PROVIDER_UNAVAILABLE`. 회로 차단은 새 관찰 없이 저장 상태를 보존한다. 실제 조회 실패는 미완료 시도에 UNKNOWN을 기록할 수 있으나 기존 종결은 보존한다. |
| 충돌·내부 실패 | 조회가 다른 주문·거래·금액·계정을 가리키거나 반대 종결이면 `409 PAYMENT_RESULT_CONFLICT`; 기존 결과·예약은 보존한다. DB 실패500·기존 잠금409를 ACK 성공으로 숨기지 않는다. |

성공 ACK 예시는 `{"meta":{"result":"SUCCESS"},"data":{"acknowledged":true}}`다. 오류는 기존 공통 envelope의 `meta.result=FAIL`·`data` 생략을 따른다.

| 신규 오류 | 메시지 | 의미 |
| --- | --- | --- |
| `409 PAYMENT_NOT_STARTED` | 결제를 시작하지 않은 주문입니다. | 본인 DRAFT에는 조회·복구할 시도가 없음 |
| `404 PAYMENT_ATTEMPT_NOT_FOUND` | 결제 시도를 찾을 수 없습니다. | callback의 고정 제공자 계정·주문 식별에 해당하는 시도 없음 |
| `503 PAYMENT_RESULT_UNAVAILABLE` | 현재 결제 결과를 확인할 수 없습니다. | callback을 권위 있는 결과로 확인하지 못함. 결제 실패·재발송 허가 아님 |

#### 공통 시간·원자성·기록 경계

두 진입점은 **기존 조회 회로를 공유하는 GET 최대 1회·기존 호출 전체 제한 10초**를 사용한다. 자동 retry·sleep·최초 confirm의 180초 루프를 붙이지 않는다. 사전 DB 조회·TX B·락 대기·응답 시간까지 합친 엄격한 10초 완료 보장은 아니다. PG 통신 중 DB 트랜잭션·잠금을 보유하지 않으며 결과 적용은 기존 잠금 순서와 TX B를 재사용한다. 사전 검사 뒤 HTTP 중 다른 실행이 거래를 연결하거나 종결할 수 있으므로 TX B에서 저장 식별·금액·상태를 다시 검증한다. 새 create·시도·예약·중복 반환은 없다.

충돌은 WARN 구조 로그에 **내부 orderId·attempt UUID·고정 source·고정 reason**만 남긴다. callback 원문·카드·HMAC·외부 식별 문자열·예외 원문은 기록하지 않는다. 로그는 영속 감사 보장이 아니며 새 테이블·outbox를 만들지 않는다. 외부 서명 인증·상시 재조회 스케줄러·승인 취소/환불은 이번 범위에 추가하지 않는다. 실제 PG 운영에 공개하기 전에는 신뢰·위변조 방어를 별도로 설계해야 한다.

### 최초 confirm의 대기와 응답 시점

**2026-10-22 사용자 선택, 2026-10-23 연결:** 우선순위가 가장 높은 servlet 필터 진입에서 단조 시각을 한 번 저장해 전체 예산을 계산하며 MVC 입력 변환/TX A·PG 호출·조회 간 대기·최종 처리를 포함한다. 필터 진입 이전의 네트워크·서버 큐 대기는 측정에 포함되지 않는다. 개별 timeout이 날 때마다 예산을 초기화하지 않는다. 175초 외부 확인 마감과 5초 최종 처리 여유는 180초 목표를 위한 초기 설계이며, 전체 지연의 검증된 하드 상한이나 DB commit 보장이 아니다.

| 관찰·실행 | 응답 기준 |
| --- | --- |
| 최초 생성 PENDING 또는 timeout | 종결 저장 결과가 없다면 시간/회로가 허용하는 기존 거래 조회로 이어감. 개별 timeout만으로 즉시 202 또는 실패로 끝내는 방식은 선택하지 않음 |
| 조회·callback으로 종결 확인 | 현재 주문·시도 상태를 보호하며 결과 반영의 커밋을 확인한 뒤 조기 반환. 이미 확인된 결과를 반환하기 위해 시간을 채우지 않음 |
| 조회 상한 소진·다음 호출 예산 부족·마감 | 저장된 최종 상태를 다시 확인. 여전히 미완료면 202와 상태·조회 경로. 결제 취소·실패·예약 만료를 뜻하지 않음 |
| 조회 회로 OPEN | 실제 외부 조회 0회. 최초 실행은 남은 전체 예산 안에서 유한하게 대기한 뒤 허용된 탐색을 시도. 종료 시에도 미완료면 202. 예약·기존 종결 사실 보존 |
| 동시 동일 confirm | 발송 권한이 없는 실행은 저장 상태를 반환. 생성이나 최초 실행의 긴 조회 루프를 중복 시작하지 않음 |
| 결과 적용 TX B 실패 | 내부 오류/경합의 기존 계약을 유지. 시간/조회 예산 소진과 혼동해 DB 오류를 202로 숨기지 않음. 기존 거래 재조회로 반영 복구 |

개별 timeout·조회 간격·분산·회로 수치와 이유는 [정책 설정표](commerce-policy-decisions.md#w5-최초-결제-요청의-대기조회회로-설정)에 둔다. callback·별도 수동 reconcile은 기존 거래만 확인하는 공통 원칙과 조회 회로를 공유하지만, 이번 선택이 그 요청들에 최초 confirm의 180초 루프까지 적용하는 승인은 아니다. 두 진입점은 위임 선택으로 GET 최대1회·호출 전체10초·반복 대기 없음으로 정했고 ACK는 위 복구 계약을 따른다. DB 처리까지 포함한 엄격한10초 보장은 아니다. `202` 이후 같은 거래를 복구하는 내부 단건 조회·결과 반영은 구현돼 있으나 callback·수동 reconcile HTTP에 연결했으며 검증 결과는 최신 복구 기록을 따른다.

`Resilience4j 2.3.0` core CircuitBreaker를 PG의 두 GET 조회에만 공유하며 생성 POST는 회로·자동 재시도 대상이 아니다. 설정의 생성/조회 전체 timeout은 각각 65초/10초 예산보다 커질 수 없도록 검증한다. 빠른 제어 시계 시험과 실제 로컬 HTTP 연결을 함께 검사하지만, DB 대기·최종 commit·네트워크를 포함한 운영 환경의 180초 하드 상한을 검증했다는 뜻은 아니다.

### 재요청·오류·상태 보존

| 상황 | 외부 응답 또는 보존할 의미 | 추가 효과 |
| --- | --- | --- |
| 입력 형식 오류 | 기존 `400 INVALID_REQUEST`를 유지한다. 신규 키·카드의 세부 검증은 위 확정한 세 필드 계약을 따른다. | PG 호출·예약 없음 |
| 없는 주문·타인 주문 | 기존 동일한 `404 ORDER_NOT_FOUND`·메시지. 주문·시도 상태를 타인에게 노출하지 않는다. | 소유권 확인 전에 결제 시작·외부 조회·결과 적용 없음 |
| 사전 검증 실패 | 없는/삭제 상품 `404 PRODUCT_NOT_FOUND`, 재고 부족 `409 INSUFFICIENT_STOCK` 등 기존 의미 유지. TX A 전체 롤백으로 시도가 저장되지 않았다면 조건 개선 후 다시 요청할 수 있다. | PG 호출 0회, 부분 예약·시도 저장 없음 |
| 같은 주문·같은 시도의 동시·반복 confirm | 진행 중이면 저장된 대기 상태, 종결했으면 저장된 결과를 반환한다. PG 호출 전 발송 시작 상태 전이를 원자적으로 성공시키고 커밋한 그 실행만 POST한다. 이미 시작된 상태를 읽은 실행은 발송하지 않는다. | 시도·예약·PG 생성 중복 없음 |
| 같은 키의 다른 주문·금액·요청 / 같은 주문의 새 키 | 내부 `PaymentRequestConflictException`과 UUID 유일 제약 충돌을 후속 승인한 `409 PAYMENT_REQUEST_CONFLICT`로 분류한다. 소유권 우선·타인 정보 비노출은 유지한다. 실제 고객 confirm에서도 같은 오류를 반환한다. | 두 번째 시도·예약·PG 생성 없음 |
| 기존 PG 시도에 요청 지문이 없음 | **사용자 승인:** 전체NULL은 비교 불가이며 `409 PAYMENT_REQUEST_UNVERIFIABLE`로 분류한다. 새 카드로 소급 생성하지 않으며 조회·기존 거래 복구는 지문 없이 유지한다. 키/형식 문제와 저장 손상은 위 상세 오류표로 구분한다. | 기존 상태·예약·거래 결과 보존. PG FAILED 변경·새 create·임의 반환 없음 |
| 이전 POINT 확정 주문의 재확정 | 기존 `409 ORDER_ALREADY_CONFIRMED`를 유지한다. PG 종결 재요청의 200과 다르다. | 기존 결과·재고·포인트 불변 |
| timeout·응답 유실·조회 실패·회로 OPEN | 실제 확인 실패는 다른 종결 결과가 없으면 UNKNOWN으로 두고, 회로 OPEN 자체는 저장 상태를 그대로 보존한다. 최초 confirm은 제한 조회 뒤 미완료면 202이며 reconcile HTTP의 단건 재확인도 연결했다. | 새 create·예약 반환·포인트 결제 없음 |
| PG에서 검증한 FAILED / 효과 0건 확정 거절 | 시도 FAILED·주문 실패와 예약 한 번 반환으로 종결한다. 단순 네트워크 실패와 다르며 실패 후 새 시도를 열지 않는다. | 예약 반환 1회, 중복 적용 추가 0회 |
| 조회 0건·복수 거래·식별·금액 불일치 | 조회 0건/404로 결과를 확인하지 못하면 미완료 시도는 UNKNOWN, 종결 시도는 기존 종결을 유지한다. 형식이 유효한 식별/금액 불일치·복수 거래는 충돌로 기존 상태·거래 연결을 보존한다. 임의 첫 거래 선택·확정은 없으며 고객 confirm은 승인된 `409 PAYMENT_RESULT_CONFLICT`를 반환한다. 충돌 구조 로그·callback ACK는 위 복구 계약의 선택을 따른다. | 새 create·임의 예약 반환 없음 |
| 종결 뒤 늦은 PENDING·timeout 또는 상충 종결 결과 | 이미 반영한 종결 상태를 되돌리거나 덮어쓰지 않는다. callback ACK·충돌 처리의 외부 표현은 `W5-Q05`. | 추가 차감·중복 반환 없음 |
| PG 성공 후 내부 저장 실패 | 로컬 결과 적용 TX를 롤백한다. 기존 `500 INTERNAL_ERROR`와 내부 상세 비노출은 유지하되, 외부 결제까지 취소된 것으로 설명하지 않는다. 같은 거래를 재조회해 다시 적용한다. | PG 재생성 없이 복구, 이미 커밋된 시도·예약은 보존 |

기존 `실패한 변경 요청은 부분 저장하지 않는다`는 규칙은 W5에서 **각 로컬 TX의 원자성**에 적용한다. TX A가 커밋된 뒤 PG 호출이나 TX B가 실패해도 TX A·외부 효과 전체가 함께 롤백되는 것은 아니다. PG 생성·조회 동안 DB TX·재고 잠금을 보유하지 않으며, 발송 시작을 영속 기록한 뒤에는 재시작·거래 0건·대기 시간 경과를 이유로 create를 자동 재발송하지 않는다.

**발송 상태의 의미:** 우리 DB의 발송 시작은 PG 접수/성공 응답이 아니다. **Q08 사용자 선택에 따라 재고 예약·시도·주문 대기와 발송 시작 기록을 TX A에 함께 저장**하고, 최초 전이·커밋에 성공한 실행에만 TX 밖 HTTP 발송을 허용한다. rollback·커밋 미확인 실행은 보내지 않으며 이후 상태가 PENDING/UNKNOWN으로 바뀌어도 발송 시작 이력을 유지한다. 이는 별도 권한 API/테이블의 추가가 아니라 [내부 상태 전이의 보호 규칙](commerce-policy-decisions.md#카드-입력과-최초-발송의-선택)이다. 내부 조율은 활성 외부 TX를 거절하고 기존 REQUIRED·READ_COMMITTED의 별도 프록시 TX A가 종료된 후 POST한다. 선행 내부 조율 검사는 [HTTP·DB 기록](../week5/order-payment-http-connection-tdd-log.md), 고객 요청 비교·API 연결은 [고객 API 기록](../week5/customer-payment-api-tdd-log.md)을 따른다. 내부 `APPLIED`·`UNCHANGED`·`CONFLICT` 판정 자체는 커밋 완료나 HTTP 응답이 아니고, 별도로 선택한 HTTP 오류·callback ACK의 의미와 구분한다.

**POINT 입력 호환 경계:** 기존 본문 없는 POINT 재확정 요청의 409를 새 키·카드 필수 검사로 400으로 바꾸지 않는다. 일반 형식 오류 처리와 소유권 확인은 유지하되, PG 전용 필수 입력 검사는 소유권·기존 POINT 분기 뒤에 수행하도록 연결했다. 과거 POINT의 본문 없는 본인 요청 409·타인 요청 404와 신규 DRAFT의 PG 필수 입력 누락 400을 각각 회귀 검증한다. `@RequestBody(required=false)`로 본문 없는 요청을 받고 본인 POINT 확인 뒤 세 필드 파서를 적용한다. 문법적으로 잘못된 JSON은 MVC 변환 오류로 기존 400이다.

관리자 재고 설정의 반환 여유 검사는 합산 과정의 overflow까지 검출한다. 더 넓은 중간 자료형 등을 사용하고, 요청 수량 자체가 유효해도 예약 합계 때문에 반환 여유가 없으면 상태 충돌로 구분한다. 후속 사용자 승인으로 `409 STOCK_CAPACITY_EXCEEDED`·“예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.”를 사용한다. 기존 수량·포인트의 입력 상한이나 충전 한도를 바꾸는 승인은 아니다.

### 로컬 테스트 PG의 별도 HTTP 계약

아래 표는 **commerce가 호출하는 로컬 PG 서버**의 계약이다. 같은 `/api/v1/payments` 이름이어도 commerce의 callback API와 대상 서버·책임이 다르다. 원문의 fixture 계약이며 실제 외부 PG의 공통 규격이라고 주장하지 않는다.

연결 대상은 [직접 준비한 로컬 HTTP 테스트 PG](commerce-erd-draft.md#이번-과제의-테스트-pg는-무엇인가)이며 토스페이먼츠 등 업체의 테스트 서버가 아니다. 실제 HTTP 요청을 사용하지만 업체 계정·API 키·실제 결제는 사용하지 않는다. 아래 헤더와 JSON은 이 과제의 계약이며 업체 문서의 규격을 섞지 않는다.

| 요청 | 입력 | 응답 |
| --- | --- | --- |
| `POST /api/v1/payments` | JSON `orderId`, `cardType`, `cardNo`, `amount`, `callbackUrl` | 생성 수락 `202`, 객체 `transactionKey`, `orderId`, `amount`, `status` |
| `GET /api/v1/payments/{transactionKey}` | PG가 발급한 거래 식별자 | `200`, 같은 거래 필드의 객체. 없는 transactionKey는 `404` |
| `GET /api/v1/payments?orderId={providerOrderId}` | 생성에 사용한 외부 orderId. URI builder로 인코딩 | `200`, 같은 거래 객체의 배열. 빈 배열도 표현하며 2건 이상이면 한 건을 임의 선택하지 않음 |

| 공통·식별 | 계약 |
| --- | --- |
| 계정과 타입 | 세 요청 모두 `X-USER-ID: 135135`. 외부 orderId는 6자 이상 문자열, amount는 원화 정수, 외부 상태는 `PENDING`·`SUCCESS`·`FAILED`다. 내부 고객 fixture 헤더와 구분한다. |
| 식별자 구분 | 내부 orderId는 주문, UUID paymentAttemptKey는 내부 시도, providerOrderId는 PG로 보낸 orderId, transactionKey는 PG가 발급한 거래를 가리킨다. UUID 선택이 PG에도 그 키를 전달하거나 PG가 중복 제거한다는 뜻은 아니다. 생성·조회에 같은 제공자 계정과 저장 providerOrderId를 사용한다. 원문의 예시를 바탕으로 후속 사용자가 신규 `LP-ORD-` + 최소10자리 주문 ID를 선택했으며 기존 시도의 매핑은 보존한다. |
| 멱등성 한계 | 테스트 PG는 paymentAttemptKey를 받지 않으며 같은 orderId의 새 POST를 중복 제거한다고 가정하지 않는다. commerce가 DB 상태로 한 번의 발송을 보호한다. |
| 카드와 URL | `cardType`·`cardNo`는 테스트 PG 계약에 맞춘 TEST·합성 번호를 사용하고 합성 번호도 DB·로그에 남기지 않는다. 예시 번호 하나만 허용하는 추가 제한은 이번 선택에 포함하지 않는다. PG base URL과 callback URL은 로컬 설정으로 고정하며 고객에게 입력받지 않는다. |
| callback 검증 | 알림을 받은 뒤 고정 PG adapter의 권위 조회 결과에서 계정 범위·providerOrderId·transactionKey·저장 금액을 검증한다. 알림 문자열만으로 결과를 적용하지 않는다. |

#### 생성 전 효과 0건 거절의 엄격한 조건

| 확인할 항목 | 모두 충족해야 하는 값·의미 |
| --- | --- |
| 응답의 출처 | 고정 로컬 PG의 **생성 POST 응답**이다. 일반 거래 조회 404·빈 목록과 다르다. |
| HTTP·code | HTTP `422`이며 JSON `code`가 정확히 `TEST_PG_REJECTED_BEFORE_CREATE`다. |
| 효과 여부·요청 일치 | `transactionCreated`가 JSON boolean `false`이고 orderId·amount가 생성 요청과 일치한다. 문자열 `"false"`나 누락을 같은 값으로 간주하지 않는다. |
| fixture의 보장 | 거래 저장·비동기 처리 예약 전에 종료했고, 이후에도 그 요청의 거래를 만들지 않는다. |
| 내부 적용 | 위 조건을 모두 만족할 때만 `REJECTED_NO_EFFECT`로 해석하여 FAILED·예약 한 번 반환으로 연결한다. 존재하지 않는 transactionKey를 만들지 않는다. |

본문 없는 422·다른 code의 4xx/5xx·잘못된 JSON·식별/금액 불일치·timeout은 효과 0건의 증거가 아니다. 확인 불가라면 미완료 시도는 UNKNOWN·예약 유지로 남긴다. 단, 형식이 유효한 응답의 식별/금액 불일치는 원문 5절에 따라 충돌로 거절하고 기존 상태·거래 연결을 보존하며, 이미 반영한 종결 결과도 덮어쓰지 않는다. 이 구분은 [PAY-03·09](commerce-erd-draft.md#paymentattempt의-관찰결과-전이)를 따른다. **PG의 422를 commerce 응답에 그대로 전달하는 계약도 아니다.** commerce의 HTTP·업무 결과 표현과 외부 adapter의 관찰을 분리한다.

#### 3단계에서 구현한 adapter 관찰

`PaymentGatewayResult`는 HTTP 응답을 읽은 결과이며 DB 변경이나 Commerce 응답 DTO가 아니다. [실제 HTTP 검증 기록](../week5/local-test-pg-http-tdd-log.md)에 생성·조회 형식, 아래 분류와 개별 timeout을 남긴다. 고정 URL은 로컬 설정만 사용하고 카드 원문은 일시적 요청에만 담는다. 후속 [주문 HMAC 연결](../week5/order-payment-fingerprint-connection-tdd-log.md)은 이 adapter의 계약을 바꾸지 않는다. 내부 서비스 TX A·재요청 비교와 고객 confirm·조회까지 연결했으며 최신 범위는 [고객 API 기록](../week5/customer-payment-api-tdd-log.md)을 따른다. callback/reconcile HTTP도 연결했으며 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 참조한다. 담당자 확인은 선택적 보완 사항이며 구현 착수 조건이 아니다.

| 외부 관찰 | adapter 결과 | 후속 처리의 경계 |
| --- | --- | --- |
| 유효한 생성 202·조회 200의 거래 | `OBSERVED`와 `PaymentTransaction` | 내부 조율에서 실제 주문의 계정·주문·거래·저장 금액을 검증하고 TX B에 적용. PENDING·SUCCESS·FAILED를 구별 |
| 위 조건을 모두 만족한 생성 422 | `REJECTED_NO_EFFECT`, 거래 목록 없음 | 해당 생성 호출의 관찰만 TX B의 예약 반환에 연결. 가짜 transactionKey 없음 |
| 거래 키 조회 404·주문별 조회의 빈 배열 | `NOT_FOUND` | 효과 0건 확정 거절이나 재발송 허용이 아님 |
| 유효 형식의 요청 식별·금액 불일치 또는 복수 거래 | `CONFLICT`, 관찰 거래 보존 | 임의 첫 거래 선택 없음. 고객 충돌409·내부 식별자만의 WARN 기록은 선택한 복구 계약을 따름 |
| timeout·네트워크 오류·계약 밖 상태·잘못된 JSON/타입 | `UNKNOWN`과 `TIMEOUT`·`NETWORK`·`HTTP_STATUS`·`INVALID_RESPONSE` | 주문별 조회 404는 `HTTP_STATUS`. 확정 FAILED·재고 반환으로 바꾸지 않음 |

생성/조회 모두 adapter 내부 자동 재시도와 redirect를 끈다. DB 발송 보호는 내부 coordinator의 신규 준비·커밋 경계에 연결했고, adapter 자체가 별도의 두 `create()` 호출을 중복 제거하는 것은 아니다. 내부 연결의 실제 HTTP·DB 실행 결과는 [후속 기록](../week5/order-payment-http-connection-tdd-log.md)을 따른다. callback URL 전달 자체와 callback controller·권위 조회 연결의 증거는 구분하며 후자는 최신 복구 기록을 따른다.

## 기본 API 계약

아래 **25개 method/path와 핵심 업무 조건은 W2~W4의 과제·승인·구현 기준**이다. W5 교체 후 C10의 본문 없음·포인트 차감·모든 CONFIRMED 재확정 거절은 새 PG 흐름에 적용하지 않는다. 조회의 추가 상태·결제 구분, A11의 가용 재고 의미, 신규 reconcile·callback은 위 W5 절을 따른다. 기존 표를 W5까지 포함한 전체 API 수로 세지 않는다.

2026-09-18 사용자가 선택한 [정책 선택표](commerce-policy-decisions.md#구현-전에-확인할-정책-선택)의 확정 범위를 반영한다. 나머지 응답 필드·HTTP 상태·오류 코드·메시지·금액 자료형은 후속 묶음 승인(P08·P12·P13)으로 **확정 계약**이다. 기존 예시 API의 관찰 계약은 유지한다. C01의 HTTP 계약은 아래와 같이 사용자 승인되었고 [HTTP TDD](commerce-tdd-brand-http-log.md)로 구현·검증했다. 25개 API의 실제 구현·검증 증거는 [전체 완료 체크리스트](commerce-completion-checklist.md)에서 확인한다. 정책 승인과 구현 완료 증거는 구분한다.

### C01 확정 계약과 구현 범위

2026-09-18 사용자가 제안한 C01 HTTP 계약을 승인했다. 아래는 선행 C01 승인 내용이며, 나머지 계약은 후속 묶음 승인으로 확정했다.

- `GET /api/v1/brands/{brandId}`는 공개 조회이며 `X-USER-ID`가 없어도 허용하고, 있어도 사용자 식별에 사용하지 않는다.
- 성공은 `200`, `{"meta":{"result":"SUCCESS"},"data":{"brandId":1,"name":"브랜드명"}}` 형태다. 성공의 errorCode·message는 생략한다.
- 없는/삭제된 브랜드는 `404 BRAND_NOT_FOUND` / `브랜드를 찾을 수 없습니다.`다.
- 문자·소수·0·음수·Long 범위 초과 ID는 `400 INVALID_REQUEST` / `요청 값이 올바르지 않습니다.`다.
- 예상하지 못한 서버 오류는 `500 INTERNAL_ERROR` / `일시적인 오류가 발생했습니다.`다.
- 실패는 meta의 result=FAIL·errorCode·message를 반환하고 data를 생략한다. 조회 성공·실패 모두 저장 상태를 유지한다.

아래 공통 계약 중 C01과 겹치는 항목은 이 확정 계약을 유지한다. 후속 승인으로 C02·C03의 공개 조회·헤더 무시와 나머지 API의 응답·입력, 405·415·406 프레임워크 오류도 확정했다.

### 공통 입력·식별·응답

| 항목 | 확정 계약 |
| --- | --- |
| 고객 식별 | 본인 데이터 API는 필수 `X-USER-ID`를 사용한다. 누락·빈 문자열·공백만 있는 값은 `400 INVALID_REQUEST`다. 문자열을 숫자 ID로 강제하지 않는다. 공개 조회 C01·C02·C03은 헤더가 없어도 허용하고 전달된 사용자 헤더도 무시한다. |
| 사용자 연결 | 외부 식별 문자열과 DB ID·역할은 실습용 fixture 매핑 한 곳에서 관리한다. `alice → user.id=1/CUSTOMER`, `bob → user.id=2/CUSTOMER`, `admin → user.id=3/ADMIN`으로 확정했다. 외부 식별자는 대소문자와 앞뒤 공백을 구분하여 원문으로 비교하며 trim·소문자 변환을 하지 않는다. 이 ID로 사용자 행을 찾고 잔액·주문·좋아요 FK에 사용한다. 최초 요청에서 사용자를 자동 생성하지 않는다. 초기 잔액은 0이며 테스트는 필요한 잔액을 명시한다. 운영 인증이나 회원가입 API를 추가하지 않는다. |
| URL의 사용자 ID | C06의 `{userId}`와 관리자 주문 필터 `userId`는 `alice` 같은 외부 식별 문자열이다. 고객은 헤더와 같은 사용자의 목록만 조회할 수 있다. 이 경로도 P03의 단일 fixture 매핑·원문 비교 규칙을 공유한다. |
| 관리자 식별·오류 응답 | Spring Security의 인증 주체가 `ROLE_ADMIN`을 가져야 한다. 일반 사용자·미식별 요청은 모두 `403 FORBIDDEN`이다. `X-USER-ID`는 관리자 인증에 사용하지 않는다. 쓰기 요청에는 유효한 CSRF 입력이 필요하며 누락·불일치도 403이다. |
| 경로·쿼리의 자원 ID | `brandId`, `productId`, `orderId`는 양의 정수이며 `Long` 범위를 사용한다. 형식 오류·0·음수·표현 범위 초과는 `400 INVALID_REQUEST`다. 사용자 식별 문자열과 구분한다. |
| JSON 입력 | 본문이 필요한 요청은 `application/json`을 사용한다. 필수 값 누락·null·잘못된 타입·허용하지 않은 필드는 `400 INVALID_REQUEST`다. 숫자 문자열이나 소수를 정수로 자동 변환하지 않는다. 본문이 없는 API에는 요청 DTO를 만들지 않는다. |
| 숫자 범위 | 자원 ID는 `Long`, 수량·재고는 `Integer` 범위로 확정했으며 수량 최댓값은 `2,147,483,647`이다. 금액·포인트는 [비용 비교와 승인](commerce-policy-decisions.md#금액-자료형의-공식-자료-확인과-비용-비교)에 따라 원 단위 정수 `Long`/`BIGINT`, 상한 `9,223,372,036,854,775,807`로 확정했다. 주문 수량은 1 이상, 재고·잔액은 0 이상, 충전액은 1 이상이다. 연산 후 값뿐 아니라 덧셈·곱셈 과정의 범위 초과도 검출한다. |
| 이름·가격 | 브랜드명·상품명은 앞뒤 공백 제거 후 1~100자, 가격은 1원 이상으로 확정했다. 브랜드명·상품명 길이는 모두 Unicode 코드 포인트로 계산한다. 이름 중복 금지는 요구하지 않는다. |
| 성공 응답 | 기존 `ApiResponse`를 이어받아 `meta.result=SUCCESS`, `data`에 아래 지정한 결과를 넣는다. `meta.errorCode`, `meta.message`는 생략한다. P04의 중복 좋아요·없는 관계 취소·재삭제는 `200`으로 확정했다. 그 외 성공 상태는 생성 `201`, 나머지 `200`이다. |
| 실패 응답 | `meta.result=FAIL`, `meta.errorCode`와 `meta.message`를 반환하고 `data`는 생략한다. 실패한 변경 요청은 부분 저장하지 않는다. 아래 오류 코드와 상태는 승인된 커머스 계약이며 현재 `ErrorType`에 모두 구현되어 있다는 뜻이 아니다. |
| 처리 순서 | 고객 API는 형식 검증 → 요청자·소유권 확인 → 상태 분기 → 업무 검증 → 변경·저장 순서다. 관리자 API는 요청자·관리자 권한 확인 → 입력 형식 검증 → 대상·업무 검증 → 변경·저장 순서로 확정했다. 권한과 형식이 모두 잘못되면 권한 오류가 우선한다. 주문 재확정은 본인 여부 확인 후 `409 ORDER_ALREADY_CONFIRMED`로 거절하며 현재 상품·브랜드를 다시 검사하지 않는다. |

사용자 연결의 fixture 값·역할과 원문 비교 규칙은 P03에서 확정했다. 매핑용 외부 식별자 DB 컬럼을 함께 추가하지 않는다. C01의 헤더·HTTP 계약은 위 별도 승인 내용을 따른다. C02·C03의 헤더 생략·무시, 나머지 HTTP 응답과 초기 잔액 0도 후속 승인으로 확정했다.

[사용자 식별·권한 공통 기능](commerce-tdd-user-identity-log.md)은 조회 대역으로 구현·검증했다. 현재 `infrastructure/user/FixtureUserIdentityRepository`의 불변 Map 한 곳에 확정한 매핑을 두고 `@Component UserResolver`에 주입하는 연결을 TDD로 구현·검증했다. 후속 [포인트·좋아요 TDD](commerce-tdd-point-like-log.md)에서 DB 사용자 행·잔액·초기화를 연결했고, [관리자 브랜드 HTTP TDD](commerce-tdd-admin-brand-http-log.md)에서 당시의 헤더 기반 진입 검사를 검증했다. 과제 피드백 반영 후 관리자 진입은 [Spring Security·MockMvc 검증](commerce-admin-security-log.md)으로 대체한다. 고객 fixture의 누락400·미등록404는 유지한다.

### 응답 데이터와 입력 DTO 제안

다음 이름은 계약표를 간결하게 읽기 위한 데이터 형태이며 실제 클래스 이름을 강제하지 않는다. JSON은 camelCase를 사용하므로 관계 집계값 `like_count`는 응답에서 `likeCount`로 표현한다. 시각은 시간대가 있는 ISO 8601 문자열이다. 공통 직렬화 설정에 따라 null 필드는 생략한다.

| 형태 | 필드·의미 |
| --- | --- |
| `BrandView` | `brandId`, `name` |
| `ProductView` | `productId`, `name`, `price`, `stockQuantity`, `brand: BrandView`, `likeCount` |
| `LikeResult` | `productId`, `liked: boolean`, `likeCount` |
| `BalanceView` | `balance` — 0도 정상 값 |
| `OrderItemView` | `productId`, `productName`(스냅샷), `unitPrice`(스냅샷), `quantity`, `subtotal` |
| `OrderView` | `orderId`, `status`, `items: OrderItemView[]`, `totalAmount`, `paidAmount`, `createdAt`, `confirmedAt`. DRAFT의 `paidAmount`·`confirmedAt`은 생략하며 CONFIRMED는 `paidAmount=totalAmount`와 확정 시각을 반환한다. 결제 성공 결과는 이 상태·결제액·시각으로 표현한다. |
| `AdminBrandView` | `BrandView` + `createdAt`, `updatedAt`, `deletedAt` |
| `AdminProductView` | `ProductView` + `createdAt`, `updatedAt`, `deletedAt` |
| `AdminOrderView` | `OrderView` + 구매자의 외부 식별값 `userId`. 고객 응답에는 다른 구매자 정보나 관리자용 삭제 시각을 넣지 않는다. |
| `Page<T>` | `items: T[]`, `page`, `size`, `totalElements`, `totalPages`. 결과가 없거나 마지막 페이지를 넘으면 `items=[]`; `totalElements`는 필터를 적용한 전체 개수다. |
| `BrandInput` | `{"name":"브랜드 A"}` — POST·PUT 모두 name 필수 |
| `ProductCreateInput` | `{"brandId":10,"name":"상품 A","price":1000,"stockQuantity":5}` — 네 필드 필수, 미삭제 브랜드 참조 |
| `ProductUpdateInput` | `{"name":"상품 A 수정","price":1200}` — 두 필드 필수. 수정 대상은 name·price만으로 확정했고 `brandId`, `stockQuantity`는 받지 않는다. 금지 필드 포함 시 `400 INVALID_REQUEST`다. 브랜드는 유지하고 재고는 전용 API로 변경한다. |
| `StockInput` | `{"stockQuantity":3}` — 필수, 0 이상인 최종 재고 |
| `OrderCreateInput` | `{"items":[{"productId":101,"quantity":2}]}` — 비어 있지 않은 배열, 각 productId·quantity 필수. 상품명·단가·합계는 요청으로 받지 않고 서버가 계산한다. |

관리자 목록·상세는 삭제된 행도 조회하는 것으로 확정했다. 응답에서 `deletedAt`으로 구분한다. 관리자 조회 가능 여부와 수정 가능 여부는 다르며, 삭제 대상의 수정·재고 변경은 허용하지 않는다. 고객에게는 삭제된 상품·브랜드를 노출하지 않는다.

### 목록 필터·페이지·정렬 제안

page/size 기본 0/20, size 1~100, 상품 정렬 동률의 ID 내림차순, 없는/삭제 브랜드 필터의 빈 목록은 확정했다. 아래의 나머지 세부 계약도 후속 승인으로 확정했다.

| 대상 | 입력과 기본값 | 정렬·오류 기준 |
| --- | --- | --- |
| 고객 상품 C02 | 선택 `brandId`, `page=0`, `size=20`, `sort=latest` | `brandId`는 일치 필터다. 없는/삭제된 브랜드 필터는 빈 페이지를 반환한다. 형식이 잘못된 ID는 400이다. 상품과 소속 브랜드 모두 미삭제인 결과만 포함한다. |
| 상품 정렬 `latest` | 생성일 최신순 | `product.created_at DESC, product.id DESC` |
| 상품 정렬 `price_asc` | 가격 낮은순 | `product.price ASC, product.id DESC` |
| 상품 정렬 `likes_desc` | 관계에서 계산한 좋아요 수 많은순 | `likeCount DESC, product.id DESC`. 좋아요 0인 상품도 포함하고, 전체 결과를 정렬한 뒤 페이지를 자른다. |
| 내 좋아요 C06 | `page=0`, `size=20` | `like.created_at DESC, like.id DESC`. 본인 관계 중 미삭제 상품·브랜드만 포함한다. |
| 내 주문 C11 | `page=0`, `size=20` | 본인 주문만 `order.created_at DESC, order.id DESC` |
| 관리자 브랜드 A01 | `page=0`, `size=20` | `brand.created_at DESC, brand.id DESC` |
| 관리자 상품 A06 | 선택 `brandId`, `page=0`, `size=20`, `sort=latest` | 고객 상품과 같은 세 정렬·동률 기준. 관리자 조회는 삭제 행도 포함한다. |
| 관리자 주문 A12 | 선택 `userId`(구매자), `status`(`DRAFT` 또는 `CONFIRMED`), `page=0`, `size=20` | 조건이 있으면 모두 적용하고 `order.created_at DESC, order.id DESC`. 없는 구매자 필터는 빈 페이지다. 잘못된 status는 400이다. |
| 페이지 공통 | page는 0 이상, size는 1~100인 정수 | 음수 page, size 0·101, 소수·문자·표현 범위 초과, 미지원 sort는 `400 INVALID_REQUEST`. 잘못된 값을 기본값으로 바꾸지 않는다. |

동률 기준은 같은 데이터 상태에서 순서를 결정한다. 페이지 요청 사이에 상품·가격·좋아요 수가 바뀌는 상황까지 스냅샷으로 고정한다는 보장은 현재 추가하지 않는다.

### 고객 API 계약표

C01·C02·C03은 사용자 헤더를 무시하는 공개 조회다. 나머지는 `X-USER-ID`로 본인 데이터를 처리한다. 모든 입력 형식 오류에는 공통 `400 INVALID_REQUEST`를 적용한다. 본인 API에는 공통 요청자 오류도 적용하며, 표의 마지막 열은 그 밖의 대표 오류와 저장 결과를 명시한다.

| ID | method/path | 입력 | 성공 결과 | 대표 오류·기대값 |
| --- | --- | --- | --- | --- |
| C01 | `GET /api/v1/brands/{brandId}` | 경로 brandId | `200`, `BrandView` | 없거나 삭제됨: `404 BRAND_NOT_FOUND` |
| C02 | `GET /api/v1/products` | brandId·page·size·sort | `200`, `Page<ProductView>` | 잘못된 필터·페이지·정렬: 400. 조건에 맞는 상품이 없으면 200 빈 페이지 |
| C03 | `GET /api/v1/products/{productId}` | 경로 productId | `200`, `ProductView` | 상품 또는 소속 브랜드가 없거나 삭제됨: `404 PRODUCT_NOT_FOUND` |
| C04 | `POST /api/v1/products/{productId}/likes` | 경로 productId, 본문 없음 | `200`, `LikeResult(liked=true)`, 관계 생성 후 집계값 | 상품·브랜드가 없거나 삭제됨: `404 PRODUCT_NOT_FOUND`. 중복은 기존 관계를 유지하고 200 반환으로 확정했으며 개수를 더 늘리지 않음 |
| C05 | `DELETE /api/v1/products/{productId}/likes` | 경로 productId, 본문 없음 | `200`, `LikeResult(liked=false)`, 본인 관계 행을 실제 삭제한 후 집계값 | 없는 상품: `404 PRODUCT_NOT_FOUND`. 삭제된 상품도 본인 관계 취소 가능. 이미 관계가 없으면 추가 변경 없이 200 반환. 현재 개수를 포함한 LikeResult 반환 |
| C06 | `GET /api/v1/users/{userId}/likes` | 외부 사용자 식별 문자열, page·size | `200`, `Page<ProductView>` | 헤더와 다른 사용자 또는 없는 사용자: `404 USER_NOT_FOUND`. 삭제 상품 관계는 목록에서만 제외 |
| C07 | `POST /api/v1/points/charge` | `{"amount":3000}` | `200`, `BalanceView`, 잔액 2000이면 DB·응답 모두 5000 | 유효하지 않은 amount: 400. 잔액 합산 범위 초과: `409 POINT_BALANCE_LIMIT_EXCEEDED`. 실패 시 잔액 유지 |
| C08 | `GET /api/v1/points` | 본문 없음 | `200`, 요청자의 저장된 `BalanceView` | 없는 요청자: `404 USER_NOT_FOUND`. 잔액 0은 `200 {balance:0}` |
| C09 | `POST /api/v1/orders` | `OrderCreateInput` | `201`, 생성한 DRAFT `OrderView`, 재고·포인트 차감 없음 | 없는/삭제 상품·브랜드: `404 PRODUCT_NOT_FOUND`. 잘못된 수량·빈 items: 400. 중복 상품은 수량 합산. 금액 범위와 오류는 아래 확정 계약 참조. 실패 시 주문·항목 저장 없음 |
| C10 | `POST /api/v1/orders/{orderId}/confirm` | 경로 orderId, 본문 없음 | 최초 확정 성공은 `200`, 저장된 CONFIRMED `OrderView`. 생성 당시 금액으로 차감 | 없거나 타인 주문: `404 ORDER_NOT_FOUND`. 본인 CONFIRMED 재확정: `409 ORDER_ALREADY_CONFIRMED`, 기존 결과는 GET으로 조회하며 저장 상태 불변. DRAFT에 삭제/없는 상품·브랜드: `404 PRODUCT_NOT_FOUND`, 재고·포인트 부족: 각 409. 저장 수량이 양수가 아닌 서버 데이터 오류는 `500 INTERNAL_ERROR`. DRAFT 확정 실패는 DRAFT·재고·잔액 유지 |
| C11 | `GET /api/v1/orders` | page·size | `200`, `Page<OrderView>`, 본인 주문만 조회 | 잘못된 페이지: 400. 주문이 없으면 200 빈 페이지 |
| C12 | `GET /api/v1/orders/{orderId}` | 경로 orderId | `200`, `OrderView`, 현재 상품이 삭제돼도 저장 스냅샷 조회 | 없거나 타인 주문: 동일한 `404 ORDER_NOT_FOUND`·메시지 |

C10은 주문 잠금 후 소유권을 먼저 확인하고, 본인 CONFIRMED 주문이면 `409 ORDER_ALREADY_CONFIRMED` / `이미 확정된 주문입니다.`로 거절한다. 실패 본문은 `meta.result=FAIL`·해당 errorCode/message이며 `data`는 생략한다. 확정 후 상품·브랜드가 삭제되었어도 현재 판매 가능 여부를 다시 검사하지 않고 같은 상태 오류를 반환한다. 타인 주문은 확정 여부와 무관하게 먼저 `404 ORDER_NOT_FOUND`로 숨긴다. 저장된 확정 결과와 재고·잔액은 변경하지 않으며 기존 결과는 C11·C12 GET으로 조회한다.

DRAFT에만 저장된 상품·브랜드와 양수 수량을 검증한다. C09의 요청 수량 오류는 400이지만, C10에는 수량 입력이 없으므로 저장된 수량의 불변식 위반을 클라이언트 입력 오류로 분류하지 않는다. 재고·포인트 부족으로 실패한 DRAFT는 조건 개선 후 새 확정 요청을 할 수 있다. 같은 주문의 동시 확정은 최초 성공 한 건만 200이며, 그 성공 이후 상태를 확인한 요청은 409로 거절하고 추가 차감하지 않는다. 기술적 교착·잠금 타임아웃은 `CONCURRENT_MODIFICATION`으로 별도 구분한다. `POST` 충전·주문 생성의 각 성공 요청은 새 충전·새 주문으로 처리하며 별도 요청 식별 키를 추가하지 않는다.

### 관리자 API 계약표

모든 관리자 API는 `/api-admin/**`의 Spring Security 경계에서 `ADMIN` 권한을 입력 형식 검증보다 먼저 확인한다. MockMvc의 `user("admin").roles("ADMIN")`, 일반 사용자 `roles("USER")`, 식별 없는 요청으로 실제 controller·application·repository·DB를 검증한다. POST·PUT·DELETE의 권한 거절 테스트에도 `csrf()`를 제공해 권한 검사와 CSRF 거절을 구분한다. 잘못된 입력을 함께 보낸 경우에도 권한 오류가 우선하며, 아래 구체 오류 상태·코드도 승인되었다. 권한 없는 요청은 `403 FORBIDDEN`이며 데이터가 바뀌지 않는다. 유효하지 않은 ID·본문·필터·페이지는 `400 INVALID_REQUEST`다. 아래 조회 응답의 관리 필드와 응답 본문도 확정 계약이다. 이미 삭제된 대상의 재삭제가 추가 변경 없이 200을 반환하는 정책은 확정했다.

| ID | method/path | 입력 | 성공 결과 | 대표 오류·기대값 |
| --- | --- | --- | --- | --- |
| A01 | `GET /api-admin/v1/brands` | page·size | `200`, `Page<AdminBrandView>` | 잘못된 페이지: 400. 데이터가 없으면 200 빈 페이지 |
| A02 | `POST /api-admin/v1/brands` | `BrandInput` | `201`, 저장한 `AdminBrandView` | 이름 누락·공백·길이 초과: 400, 저장 없음 |
| A03 | `GET /api-admin/v1/brands/{brandId}` | 경로 brandId | `200`, `AdminBrandView` | 행이 없음: `404 BRAND_NOT_FOUND`. 삭제 행은 삭제 시각과 함께 관리자에게 조회 |
| A04 | `PUT /api-admin/v1/brands/{brandId}` | 경로 brandId, `BrandInput` | `200`, 수정한 `AdminBrandView` | 없음/삭제됨: `404 BRAND_NOT_FOUND`, 이름 오류: 400. 실패 시 기존 값 유지 |
| A05 | `DELETE /api-admin/v1/brands/{brandId}` | 경로 brandId, 본문 없음 | `200`, `{brandId,deleted:true}`. 브랜드와 연결된 모든 미삭제 상품을 함께 논리 삭제(재고 0 포함). 연결 상품이 없어도 성공 | 없음: `404 BRAND_NOT_FOUND`. 이미 삭제된 브랜드는 추가 변경 없이 200, 최초 삭제 시각과 관련 상품 상태 유지. W3-BRAND-01~08 검증 완료 |
| A06 | `GET /api-admin/v1/products` | brandId·page·size·sort | `200`, `Page<AdminProductView>` | 잘못된 조건: 400. 조건에 맞는 행이 없으면 200 빈 페이지 |
| A07 | `POST /api-admin/v1/products` | `ProductCreateInput` | `201`, 저장한 `AdminProductView` | 없는/삭제 브랜드: `404 BRAND_NOT_FOUND`. 이름·가격·재고 오류: 400, 저장 없음 |
| A08 | `GET /api-admin/v1/products/{productId}` | 경로 productId | `200`, `AdminProductView` | 행이 없음: `404 PRODUCT_NOT_FOUND`. 삭제 행도 조회 가능 |
| A09 | `PUT /api-admin/v1/products/{productId}` | 경로 productId, `ProductUpdateInput` | `200`, 수정한 `AdminProductView`, brandId·재고 유지 | 없음/삭제됨: `404 PRODUCT_NOT_FOUND`. 이름·가격 오류 또는 brandId·stockQuantity 포함: 400, 기존 값 유지 |
| A10 | `DELETE /api-admin/v1/products/{productId}` | 경로 productId, 본문 없음 | `200`, `{productId,deleted:true}`, 주문이 있어도 삭제 | 없음: `404 PRODUCT_NOT_FOUND`. 주문·주문항목은 삭제하지 않음. 이미 삭제된 행은 추가 변경 없이 200 반환으로 확정. 구체 응답 필드도 확정 |
| A11 | `PUT /api-admin/v1/products/{productId}/stock` | 경로 productId, `StockInput` | `200`, `{productId,stockQuantity}`, 현재 10에 3 요청이면 최종 3 | 없음/삭제됨: `404 PRODUCT_NOT_FOUND`. 음수·타입·범위 오류: 400. W5 반환 여유 초과: `409 STOCK_CAPACITY_EXCEEDED`. 실패 시 기존 재고·예약·목록 버전 유지 |
| A12 | `GET /api-admin/v1/orders` | userId·status·page·size | `200`, `Page<AdminOrderView>` | 잘못된 조건: 400. 구매자·상태 조건에 맞는 주문이 없으면 200 빈 페이지 |
| A13 | `GET /api-admin/v1/orders/{orderId}` | 경로 orderId | `200`, `AdminOrderView`, 구매자·품목·상태·금액·결제 결과 | 없음: `404 ORDER_NOT_FOUND`. 관리자 권한이면 다른 구매자의 주문도 조회 가능 |

### W3 브랜드 삭제 계약 변경 — 연결·동시성 검증 완료

method/path·요청·응답 모양은 유지하고 삭제 범위만 확장한다. 미삭제 상품 존재 자체를 실패로 보는 `BRAND_HAS_PRODUCTS`는 **새 A05 계약과 구현에서 제외**했다. 변경 근거와 트랜잭션·잠금의 합의 사항 및 구현 선택은 [W3 브랜드·상품 일괄 논리 삭제](commerce-policy-decisions.md#w3-브랜드상품-일괄-논리-삭제)를 따른다.

| 연결 범위 | 삭제 성공 후 기대 결과·보존할 계약 |
| --- | --- |
| 대상·원자성 | 브랜드와 연결된 미삭제 상품 전체에 삭제 시각을 기록하고 행·ID·FK는 보존한다. 이미 삭제된 상품의 최초 삭제 시각은 유지한다. 한 요청의 변경은 같은 트랜잭션으로 모두 반영하거나 모두 롤백한다. |
| 고객 조회 C01~C03 | 삭제 브랜드 상세는 `404 BRAND_NOT_FOUND`, 대상 상품 상세는 `404 PRODUCT_NOT_FOUND`. 상품 목록에서 제외하며 삭제 브랜드 필터는 빈 페이지다. |
| 좋아요 C04~C06 | 새 등록은 `404 PRODUCT_NOT_FOUND`, 내 좋아요 목록에서는 제외한다. 관계를 연쇄 삭제하지 않으며 남아 있는 본인 관계 취소는 기존대로 허용한다. |
| 주문 C09·C10 | 새 주문은 `404 PRODUCT_NOT_FOUND`로 거절한다. 삭제 전에 만든 DRAFT도 최초 확정 시 잠금을 얻은 뒤 삭제 상태를 다시 확인하여 거절하고 추가 차감을 남기지 않는다. |
| 관리자 A03·A04·A07~A09·A11 | 삭제 행 조회는 유지한다. 삭제 브랜드 수정·해당 브랜드 상품 등록은 `404 BRAND_NOT_FOUND`, 삭제 상품 수정·재고 변경은 `404 PRODUCT_NOT_FOUND`다. |
| 다른 대상·과거 주문 | 다른 브랜드·상품과 기존 주문·주문항목의 품목·수량·단가·총액·포인트 결제 결과를 변경하지 않는다. 과거 주문은 현재 상품 가격으로 재계산하지 않고 기존 조회 계약을 유지한다. |
| 실패·접근 경계 | 관리자 권한·CSRF `403`, 잘못된 ID `400`, 없는 브랜드 `404`를 유지한다. 중간 저장 실패는 성공 응답 없이 전체 롤백한다. 잠금 대기 제한·교착의 `409 CONCURRENT_MODIFICATION`도 유지하며 기술 오류를 삭제 성공으로 숨기지 않는다. |

위 연결은 새 일괄 삭제 후에도 유지해야 할 계약이다. 첫 증분에서는 정상 처리·기존 데이터 보존, 두 번째는 기본 경계, 세 번째는 상품·브랜드 SQL 후 저장 경계의 런타임 예외에 대한 `500 INTERNAL_ERROR`·전체 롤백을 검증했다. 네 번째 증분에서는 실제 관리자 DELETE 후 고객 조회·좋아요·새 주문·DRAFT 최초 확정·관리자 변경·권한·CSRF와 기존 주문 조회를 검증했다. 상품 목록의 세 정렬과 삭제 브랜드 필터, 본인 좋아요 취소 후 타인 관계 보존도 포함한다.

주문 생성·최초 확정·상품 등록 각각과 삭제의 양쪽 선후 결과는 순차 호출로 검증했다. 별도의 실제 경합 테스트는 시작만 맞춰 Spring 서비스·저장소·MySQL을 호출하고, 허용된 요청 결과와 최종 DB 정합성을 확인한다. 업무 거절의 `404` 매핑은 공통 오류 변환기로 확인하며, 동시 HTTP 요청·특정 DB 락 대기·양쪽 선후 순서를 강제로 재현한 것으로 표현하지 않는다. 검증 방법과 한계는 [네 번째 증분 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증)을 따른다.

### 대표 오류와 상태 보존 제안

1주차의 `INVALID_REQUEST`, `ORDER_NOT_FOUND`, `INTERNAL_ERROR` 의미를 이어받는다. 아래 메시지를 승인된 계약 테스트의 기대값으로 사용하며, 타인 주문과 없는 주문은 코드·메시지가 동일해야 한다. 기존 예시 API의 `Bad Request`, `Not Found` 관찰 계약과 별도로 적용한다.

| HTTP | errorCode | message | 대표 상황·보존할 상태 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | `요청 값이 올바르지 않습니다.` | 필수 입력·타입·범위·쿼리 오류. 변경 없음 |
| 404 | `USER_NOT_FOUND` | `사용자를 찾을 수 없습니다.` | 고객 API에서 fixture에 없는 요청자 또는 다른 사용자의 내 좋아요 목록 접근. 변경 없음 |
| 403 | `FORBIDDEN` | `관리자 권한이 필요합니다.` | 관리자 API의 일반 사용자·미식별 요청 또는 CSRF 누락·불일치. 변경 없음 |
| 404 | `BRAND_NOT_FOUND` | `브랜드를 찾을 수 없습니다.` | 브랜드 없음 또는 미삭제 대상을 요구하는 작업에서 삭제됨 |
| 404 | `PRODUCT_NOT_FOUND` | `상품을 찾을 수 없습니다.` | 상품 없음 또는 해당 작업에서 사용 불가능한 삭제 상태. DRAFT 확정 실패 시 주문·차감 상태 유지 |
| 404 | `ORDER_NOT_FOUND` | `주문을 찾을 수 없습니다.` | 고객에게 없는 주문과 타인 주문을 구분하지 않음 |
| 409 | `ORDER_ALREADY_CONFIRMED` | `이미 확정된 주문입니다.` | 본인 CONFIRMED 주문에 다시 확정 요청. 상품·브랜드의 이후 삭제 여부와 무관하며 기존 주문·차감·결제 결과 불변. 소유권 검사를 먼저 수행 |
| 409 | `INSUFFICIENT_STOCK` | `상품 재고가 부족합니다.` | 확정 실패, DRAFT·모든 상품 재고·잔액 유지 |
| 409 | `STOCK_CAPACITY_EXCEEDED` | `예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.` | W5 관리자 재고 설정에서 가용 수량과 모든 HELD 합계가 int 상한을 초과. 기존 가용량·예약·목록 버전 유지 |
| 409 | `INSUFFICIENT_POINTS` | `포인트가 부족합니다.` | 확정 실패, DRAFT·모든 상품 재고·잔액 유지 |
| 409 | `POINT_BALANCE_LIMIT_EXCEEDED` | `포인트 잔액의 허용 범위를 초과합니다.` | amount 자체는 유효하지만 잔액 합산 초과. 기존 잔액 유지 |
| 409 | `ORDER_AMOUNT_LIMIT_EXCEEDED` | `주문 금액의 허용 범위를 초과합니다.` | 유효한 상품 단가·수량으로 계산한 소계·총액이 표현 범위 초과. 주문 저장 없음. 저장 데이터 손상 등 예상하지 못한 서버 계산 오류와 구분 |
| 409 | `CONCURRENT_MODIFICATION` | `다른 요청과 충돌했습니다. 다시 요청해 주세요.` | 행 잠금 타임아웃·교착. 실패 요청의 변경 롤백, 서버 자동 재시도 없음 |
| 405 | `METHOD_NOT_ALLOWED` | `지원하지 않는 HTTP 메서드입니다.` | 커머스 경로의 미지원 HTTP 메서드 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `지원하지 않는 Content-Type입니다.` | 커머스 요청의 미지원 본문 미디어 타입 |
| 406 | `NOT_ACCEPTABLE` | `지원하지 않는 응답 형식입니다.` | 커머스 요청에서 제공할 수 없는 응답 미디어 타입 |
| 500 | `INTERNAL_ERROR` | `일시적인 오류가 발생했습니다.` | 예상하지 못한 서버 오류. 내부 상세를 노출하지 않고 변경 트랜잭션 롤백 |

프레임워크 오류도 아래 승인된 상태·메시지로 실제 HTTP 테스트를 검증한다. controller가 결정되기 전의 오류도 커머스 요청 범위에 적용하고 기존 Example API의 관찰 계약을 유지한다. 기존 공통 처리기에 의해 405 등이 500으로 바뀌지 않는지 확인하며, 승인만으로 오류 매핑이 구현되었다고 간주하지 않는다.

변경 트랜잭션은 `READ_COMMITTED`에서 필요한 행을 **주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순**으로 잠근 뒤 최신 상태를 검증·변경·저장한다. 락 대기 전에 읽은 상태만으로 처리하지 않는다. 상품 등록·변경과 브랜드 삭제는 브랜드 잠금을 공유하고, 좋아요 등록·취소는 상품을 잠근다. DB 행 락 대기 제한은 3초이며 서버가 자동 재시도하지 않는다. 교착·타임아웃은 위 409 충돌 계약을 따른다. 일괄 삭제는 브랜드 잠금 뒤 대상 상품 ID를 오름차순으로 잠그고 bulk UPDATE하는 방식으로 구현했다. 구체 구현과 검증 범위는 [W3 정책 검토](commerce-policy-decisions.md#w3-브랜드상품-일괄-논리-삭제)·[체크리스트](commerce-completion-checklist.md)를 따른다.
