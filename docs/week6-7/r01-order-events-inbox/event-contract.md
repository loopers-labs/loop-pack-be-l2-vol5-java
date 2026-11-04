# R01 이벤트 계약 · OrderConfirmed v1

이 문서는 [상세 설계](plan.md)의 wire/identity 규칙이다. 아직 runtime에 연결한 계약 구현은 없다.

## 형식

- Topic: `order-events`, group: `purchase-activity`.
- Key: orderId를 선행 0/공백/부호 없이 표현한 십진 문자열.
- Value: UTF-8 JSON object. key/value null 또는 tombstone은 구매 활동 입력으로 허용하지 않는다.
- Kafka headers에 Java 클래스 이름을 싣거나 소비 타입 선택의 근거로 사용하지 않는다.

```json
{
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "eventId": "order-confirmed:v1:42",
  "orderId": 42,
  "confirmedAt": "2026-11-04T01:00:00.123456Z",
  "items": [
    {"productId": 7, "quantity": 2},
    {"productId": 8, "quantity": 1}
  ]
}
```

위 ID·시각·품목은 합성 예시다. 실제 발행은 저장된 확정 주문의 사실을 사용한다.

## 필수 필드와 검증

| 필드 | v1 규칙 |
|---|---|
| eventType | 정확히 OrderConfirmed |
| schemaVersion | JSON 정수 1 |
| eventId | 1~128자 ASCII 영숫자와 `:._-`만 허용. 앞뒤 공백을 보정하지 않음 |
| orderId | JSON 정수 1~Long.MAX_VALUE. 문자열/소수/범위 밖/null 거절 |
| confirmedAt | Z 또는 UTC offset이 있는 ISO-8601 문자열. UTC Instant로 비교. 마이크로초보다 작은 유효 값은 거절 |
| items | 비어 있지 않은 JSON 배열. 각 요소는 productId·quantity를 가진 object |
| productId | JSON 정수 1~Long.MAX_VALUE |
| quantity | JSON 정수 1~Integer.MAX_VALUE. 같은 상품의 합산 결과도 int 범위 안이어야 함 |

중복 JSON key·알 수 없는 필드·누락/null·암묵적인 숫자 변환·단일 object에서 배열로의 자동 변환을 거절한다.
올바른 type/version이 확인되지 않은 record를 기존 계약의 이벤트로 처리하지 않는다.

## 사건 ID와 시각

producer는 최초 PG 확정 주문에서 `order-confirmed:v1:{orderId}`를 만든다.
같은 DB의 한 주문은 확정 사건이 하나라는 기존 규칙을 이용하며 eventId 별도 컬럼은 추가하지 않는다.
테스트·서로 다른 DB 환경은 topic/group도 격리한다. 독립 DB가 같은 topic에 같은 orderId를 발행하는 다중 원본은 이번 범위가 아니다.

consumer는 eventId가 이 문자열 공식과 같다는 가정으로 dedup하지 않는다.
올바른 형식의 다른 eventId가 같은 orderId로 들어오면 Inbox의 업무 유일성과 충돌 비교에서 차단한다.

confirmedAt은 외부 PG 완료/조회 시각이나 consumer 수신 시각이 아니라 최초 내부 CONFIRMED 전이의 저장 시각이다.
기존 order_records.created_at을 재사용한다.

1. PG 성공 전이에서 Clock.systemUTC 또는 테스트의 고정 Clock을 읽는다.
2. `truncatedTo(MICROS)`로 정밀도를 맞춘 값을 새 PG OrderRecord 생성 경로에 전달한다.
3. Mapper가 그 값을 Entity에 보존한다. PrePersist는 값이 없는 기존 생성 경로에만 시각을 채운다.
4. 저장/flush 이후 재조회한 기록·품목으로 snapshot을 만든다.
5. 중복 callback/reconcile은 기존 기록을 반환하며 이 시각을 다시 정하지 않는다.

DB의 datetime(6)과 이벤트에 같은 값을 저장한다. 나노초 값 전송 뒤 DB가 알아서 잘라주길 기대하지 않는다.
기존 POINT 기록과 기존 확정 PG 기록의 시각은 수정하지 않는다. 과거 기록을 임의로 새 사건으로 소급하지 않는다.

## 정규화와 같은 사실 판정

1. 각 품목의 productId/quantity를 검증한다.
2. 같은 productId를 Math.addExact로 합산한다. overflow는 오류다.
3. productId 오름차순으로 정렬하고 불변 list로 만든다.
4. 시각은 UTC Instant로 표현한다. JSON 문자열의 Z/다른 offset 표현이 같은 Instant라면 같은 값이다.
5. eventType·schemaVersion·eventId·orderId·confirmedAt·정규화 items를 모두 비교한다.

JSON 속성/품목 순서와 공백은 사실의 identity가 아니다. raw bytes 비교나 원문 JSON 문자열 비교는 사용하지 않는다.
Kafka topic/partition/offset·전송 시도·receivedAt·processedAt은 이 비교에서 제외한다.

| 입력 조합 | 판정 |
|---|---|
| 같은 envelope, 품목 순서만 다름 | 같은 사실 |
| 상품 7×1 두 품목과 상품 7×2 한 품목 | 같은 사실 |
| 다른 timezone 문자열, 같은 Instant | 같은 사실 |
| 같은 eventId, 다른 주문/수량/시각 | 충돌 |
| 같은 orderId, 다른 eventId | 충돌 |
| 같은 상품, 서로 다른 orderId와 eventId | 서로 다른 정상 구매 |
| 다른 schemaVersion/알 수 없는 eventType | 현재 parser의 지원 범위 밖, 소비 실패 |

## Codec와 스키마 변경

공통 record는 순수 Java 구조와 정규화만 소유한다. API producer와 streamer parser가 JSON codec을 각각 가진다.
HTTP 전역 ObjectMapper의 느슨한 설정을 수정하지 않고 이벤트 전용 codec을 사용한다.
parser는 엄격한 JSON tree 검증 후 계약 객체를 만든다. 저장 snapshot 복원도 같은 필수 조건을 확인한다.

producer 직렬화 결과를 consumer parser에 넣는 wire 검증과 실제 broker 경로를 모두 둔다.
v1의 필수 값·의미를 바꾸거나 필드를 추가할 때는 현재 unknown-field 거절 정책과 호환성을 먼저 검토한다.
이 과제에서는 v2를 자동 수용하지 않는다. 새 계약 지원 전에는 오류 record를 성공 처리하지 않는다.
