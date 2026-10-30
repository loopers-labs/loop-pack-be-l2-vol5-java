# 주문 확정 이벤트 계약과 전체 경계

기존 절 번호 참조: [2.11](publication.md#211-주문-확정-이벤트-발행의-책임과-설정) · [4.6](publication.md#46-주문-확정-이벤트-발행) · [5.1](../../common/api-contract.md#51-공통-계약과-입력-정책).

읽기: 공통 [구조](../../common/architecture.md)·[API](../../common/api-contract.md) → 아래 전체 경계/사건/이벤트 계약 → [발행 측 상세설계](publication.md) → [소비 측 상세설계](consumption.md) → [대안·선택 근거](../../decisions/order-events.md). 최초 PG 확정의 기존 계약은 [결제 모델](../order-payment/model.md)·[결과 적용](../order-payment/processing.md)을 함께 확인한다.

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

**상태:** 2026-10-29 사용자 최종 승인 설계다. 발행·소비 GPT 검토와 전체 Claude 검토·재검토 후 보완을 반영했다. 별도 승인한 구현 단위와 P1~P9·C1~C10 관련 검증을 마쳤다. 실행 조건·결과·한계는 [검증 기록](../../verification/order-events.md)에 둔다. 소비 측 모델·파싱·동일 사실 비교·TX/ack·중단/복구·C1~C10은 [소비 상세설계](consumption.md)를 따른다.

2026-10-28 주문 확정 이벤트 **발행 측 상세설계 초안**을 추가했다. 사용자와 합의한 사건 식별·최초 확정 시각·AFTER_COMMIT·비동기 결과 관측·로그·제한된 producer 재시도·종료 대기를 구체화했다. 책임·설정은 2.11, 저장 불변조건은 3.7, 처리 의사코드·검증 기준은 4.6, 외부 이벤트 계약은 5.8, 대안·선택 근거는 A.34가 기준이다. 이후 소비 상세설계와 검토 보완을 통합했고 2026-10-29 사용자가 최종 승인했다. 이후 승인된 단위별 구현과 관련 검증을 수행했다. 당시 설계 승인과 이후 실행 결과를 구분한다.

## 1.3 주문 확정 사실의 전달

**상태:** 사용자 승인 설계다. 기존 PG부터 아래 Kafka 발행·구매 활동 연결까지 구현했고 실제 종단 검증을 수행했다. 단일 broker·준비된 실행 등 검증 범위는 별도 기록을 따른다. 소비 측 상세 구조는 [소비 상세설계](consumption.md)를 따른다.

```text
commerce-api: 검증된 PG SUCCESS → 최초 CONFIRMED·예약 소비·confirmed_at 저장 (TX B)
  → DB commit → AFTER_COMMIT → order-events에 OrderConfirmed 전송
  → broker 확인/Future 결과 로그
commerce-streamer: 보존된 record 소비 → 구매 활동 DB 반영 (별도 업무·별도 TX)
```

주문은 확정 사실을 알리고 후속 구매 활동의 저장 구현을 직접 호출하지 않는다. 후속 기능의 장애를 주문 확정 결과에서 분리하는 대신 전달 지연·중복·누락을 확인해야 하는 비용을 받아들인다. broker 전송 확인은 구매 활동 완료를 뜻하지 않는다. DB commit과 Kafka 전송은 원자적이지 않으므로 commit 뒤 미발행 공백이 남는다. Outbox·랭킹 API·애플리케이션 재발행 기능은 이 목표에 포함하지 않는다. 상세 계약과 근거는 2.11·3.7·4.6·5.8·A.34를 따른다.
## 3.7 최초 PG 확정 시각과 사건 값

**상태:** 사용자 승인 계약에 따라 구현·관련 단위/DB 검증을 수행했다. 3.6의 기존 주문 금액·품목·결제 제약을 유지하며 아래 확정 시각 규칙을 추가한다. 외부 표현은 [5.8](#58-orderconfirmed-v1-이벤트-계약)을 따른다.

|저장 필드|Java·SQL 목표|수명·불변조건|
|---|---|---|
|`orders.confirmed_at`|`ZonedDateTime`, UTC 정규화, `DATETIME(6) NULL`|최초 내부 PG 확정 전이에서 한 번 설정. 같은 종결 반복에도 변경하지 않음|

시각은 PG의 승인 시각·DB commit 완료 시각이 아니다. 최초 CONFIRM 분기에서 `ZonedDateTime.now(orderConfirmationClock)`을 UTC로 정규화하고 마이크로초로 절삭해 한 번 얻는다. 정규화한 동일 값을 Order와 사건에 전달한다. 기존 `created_at`·`updated_at`·다른 PG 시각 생성은 변경하지 않는다. 타입 변경 없이 재현 가능한 고정 Clock을 제공하며, domain은 Clock을 직접 호출하지 않는다.

|주문 상태·방식|confirmedAt의 계약|
|---|---|
|DRAFT|없음|
|PG PAYMENT_PENDING / PAYMENT_FAILED|없음. 최초 확정 전에는 확정 시각을 만들지 않음|
|PG CONFIRMED|필수. UTC·마이크로초 정밀도이며 변경 불가|
|기존 POINT CONFIRMED fixture·조회|기존 금액·품목·시각 정책 유지. 이번 변경으로 확정 시각 추정·PG 이벤트 생성 없음|

`OrderModel.confirmPg(amount, confirmedAt)`은 기존 PG 대기·금액 일치 검사와 함께 현재 확정 시각이 비어 있고 입력이 null이 아닌 UTC·마이크로초 정밀도인지 검사한 뒤 paidAmount·status·confirmedAt을 함께 설정한다. 잘못된 저장 상태·내부 시각 입력은 기존 `INTERNAL_ERROR`다. 동일 종결은 이 메서드를 재호출하지 않는다. startPgPayment/failPg와 PaymentTransactionService의 PG 저장 정합성 검사에도 위 상태별 시각 조건을 반영한다. 단순히 모든 CONFIRMED에 조건을 걸어 POINT fixture를 실패시키지 않는다. 기존 PG fixture의 정상 확정 준비는 새 메서드로 고정 시각을 제공하도록 바꾸되, 기존 업무 결과 검증을 삭제·완화하지 않는다.

nullable은 미확정 주문과 POINT 호환을 위한 것이며 신규 PG 확정의 누락을 정상으로 허용하는 뜻이 아니다. 확정 시각 전용 eventId 컬럼·발행 상태 컬럼·새 결제 상태·별도 CHECK는 추가하지 않는다. ORM의 실제 생성 DDL과 새 영속성 컨텍스트 재조회에서 타입·정밀도·UTC 순간이 일치해야 한다. DB에서 반올림된 값을 조회하기 위해 AFTER_COMMIT에서 Entity를 다시 읽는 방식은 사용하지 않는다.

목표 매핑은 `@Column(name="confirmed_at", columnDefinition="DATETIME(6)")`과 기존 UTC 정규화 설정이다. `updatable=false`는 사용하지 않는다. DRAFT 생성 뒤 확정 TX의 UPDATE로 최초 값을 기록해야 하기 때문이다. 최초 기록 뒤 불변성은 도메인 전이·호출 경계로 지키고, 모든 경로를 막는 DB 불변 제약이라고 주장하지 않는다. DB 직접 쓰기는 이 보호를 우회할 수 있다.

사용자가 보존할 실데이터가 없다고 확인했으므로 **이번 컬럼 추가의 backfill·과거 행 식별·보존 전환 절차는 제외**한다. DB를 조회한 결과로 표현하지 않는다. 기존 스키마가 없는 것도 아니므로 새 컬럼 반영·validate 경로는 구현 단위에서 확인한다. 데이터 없는 개발 DB의 스키마 준비와 재시작 검증 중 데이터 유지 조건을 구분한다. 3.6의 5주차 POINT→PG 보존 전환 계약·기존 회귀 근거를 삭제하지 않으며 이번 6주차의 필수 신규 이관 작업으로 다시 실행하지 않는다. 실제 보존 데이터 발견 시 자동 보정·drop하지 않고 적용을 멈춰 확인한다.

### OrderConfirmed 값 계약

`OrderConfirmed.from(order)`는 최초 CONFIRM의 실제 TX 안에서 PG·CONFIRMED·필수 시각·저장 품목 정합성을 확인하고 scalar만 복사한다. orderId/productId는 양수 long, quantity도 양수 long이다. items는 비어 있지 않고 productId별 수량을 `Math.addExact`로 합산·ID 오름차순 정렬한 뒤 `List.copyOf`한다. 품목 record도 불변이다. 정상 Order 생성은 이미 중복을 합치지만 외부 계약의 정규화도 명시한다. 누락·음수·합산 overflow는 내부 저장 정합성 오류로 TX를 실패시키며 임의 품목 제거·수량 절삭을 하지 않는다. 상품의 현재 존재·노출·가격은 조회하지 않는다.

검사 순서는 원본 항목별 productId·quantity 양수 확인 → 상품별 안전한 합산 → 합산 결과 양수 확인이다. 합계만 검사하여 -1과 3 같은 손상 원본을 정상 수량 2로 은폐하지 않는다.

`eventId()`는 `"order-confirmed:" + Long.toString(orderId)`다. 새 UUID·현재 시각·전송 횟수·schemaVersion을 섞지 않는다. 한 주문의 최초 확정이 하나이며 같은 topic의 주문 ID 공간이 하나라는 전제다. eventId의 동일성은 send 1회·broker record 1개·소비 DB 효과 1회를 각각 보장하지 않는다. 재확정·다중 주문 ID 공간·스키마의 업무 의미 변경 시 식별 규칙을 재검토하되 이번 범위에 미리 구현하지 않는다.

## 5.8 OrderConfirmed v1 이벤트 계약

**상태:** 사용자 승인 설계. wire 계약은 발행자와 후속 소비 설계의 공통 기준으로 두며, 소비 측의 파싱·중복 비교·DB TX·ack 상세 흐름은 [소비 상세설계](consumption.md)를 따른다. HTTP 요청·응답 계약과 PG 권위 검증은 기존 5.1~5.7을 유지한다.

|항목|wire 계약|
|---|---|
|topic|`order-events`. 설정 `commerce.order-events.topic`. 테스트는 고유 suffix로 격리|
|key|양수 내부 orderId의 10진 문자열. 선행 0·공백·별도 접두사 없음. UTF-8 StringSerializer 사용|
|value|UTF-8 JSON object. 숫자는 정수로 표현하고 아래 필드 모두 필수|
|`eventType`|String 상수 `OrderConfirmed`|
|`schemaVersion`|정수 `1`. eventId의 일부가 아님|
|`eventId`|String `order-confirmed:{orderId}`. key·payload orderId와 같은 주문 식별|
|`orderId`|양수 64bit 정수. 저장된 내부 주문 ID|
|`confirmedAt`|3.7의 저장된 최초 내부 확정 순간. UTC ISO-8601 문자열, `Z` 표기, 소수초 최대 6자리. 전송·소비 현재 시각을 대신 쓰지 않음|
|`items`|비어 있지 않은 배열. 각 원소는 양수 64bit 정수 `productId`, `quantity`. productId별 수량 합산·하나의 원소, 발행자는 ID 오름차순으로 정렬|
|header|Spring Java 클래스 type header를 발행 계약으로 사용하지 않음. `spring.json.add.type.headers=false` 유효 적용. 기타 전송 header의 존재 자체를 업무 사실의 차이로 해석하지 않음|

```json
{
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "eventId": "order-confirmed:42",
  "orderId": 42,
  "confirmedAt": "2026-10-28T07:00:00.123456Z",
  "items": [
    {"productId": 7, "quantity": 2},
    {"productId": 8, "quantity": 1}
  ]
}
```

예시의 ID·시각·품목은 합성 값이다. key는 `"42"`다. 실제 값은 주문 TX 안에서 복사한 사실만 사용한다. 같은 순간의 소수초 후행 0 표기·JSON 속성 순서·items 순서만으로 새 사건이라고 보지 않는다. schemaVersion은 payload 해석 계약이며 ID의 동일성은 업무 사실의 동일성을 위한 것이다. 현재 v1의 고정 필드·정규화한 품목과 시각을 비교해야 하고, JSON 원문 bytes 전체를 사실 fingerprint로 간주하지 않는다. 향후 v2 호환 구현·복수 이벤트 유형 처리는 이번 단위에서 추가하지 않는다.

userId·카드·PG 비밀·providerOrderId·transactionKey·현재 가격·상품명은 이 value에 넣지 않는다. 과거 주문 사실이므로 삭제/비노출 상품을 조회·복원하거나 현재 정보로 덮어쓰지 않는다. 공개 확정 이벤트 생성 API·관리자 재발행 API는 추가하지 않는다. 합성 메시지 주입은 테스트 도구에서만 한다.
