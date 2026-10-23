# 토스 PG 결제 승인 계약


| 항목  | 계약                          |
| --- | --------------------------- |
| 요청  | `POST /v1/payments/confirm` |
| 성공  | `200`, 아래 성공 응답 객체          |
| 오류  | `400` 또는 `403`, 아래 오류 응답 객체 |


## 요청 헤더

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `Authorization` | Basic | Toss 시크릿 키와 콜론을 Base64로 인코딩한 인증 값 |
| `Idempotency-Key` | string, 선택 | 멱등 요청에 사용하는 키. 재전송에는 같은 키를 사용한다. 최대 300자이며 초과하면 `400 INVALID_IDEMPOTENCY_KEY`를 반환한다. |

## 입력


| 필드           | 타입     | 의미                        |
| ------------ | ------ | ------------------------- |
| `paymentKey` | string | 인증된 결제의 키. 로컬 테스트에서 미리 준비 |
| `orderId`    | string | PG에 전달한 주문 번호             |
| `amount`     | number | 저장된 주문의 결제 금액             |


## 성공 응답


| 필드            | 타입     | 의미                    |
| ------------- | ------ | --------------------- |
| `paymentKey`  | string | 요청한 결제인지 확인·후속 조회     |
| `orderId`     | string | 주문 일치 확인              |
| `totalAmount` | number | 저장된 결제 금액과 비교         |
| `status`      | string | `DONE`: 승인 성공         |
| `approvedAt`  | string | 결제 성공 시각. ISO 8601 형식 |


## 오류 응답


| 필드        | 타입     | 의미       |
| --------- | ------ | -------- |
| `code`    | string | 오류 구분 코드 |
| `message` | string | 오류 설명    |


### 로컬 테스트에서 반환할 오류


| HTTP  | code                         | 상황          |
| ----- | ---------------------------- | ----------- |
| `400` | `ALREADY_PROCESSED_PAYMENT`  | 이미 처리된 결제   |
| `400` | `ALREADY_PROCESSING_REQUEST` | 이미 처리 중인 요청 |
| `400` | `PROVIDER_ERROR`             | PG의 일시적인 오류 |
| `400` | `INVALID_REQUEST`            | 잘못된 요청      |
| `403` | `REJECT_CARD_PAYMENT`        | 한도 초과·잔액 부족으로 승인 거절 |
