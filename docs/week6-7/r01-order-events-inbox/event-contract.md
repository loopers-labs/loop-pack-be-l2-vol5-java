# R01 이벤트 계약 · OrderConfirmed v1

이 문서는 [상세 설계](plan.md)의 추천안을 풀어 쓴 wire/identity 초안이다. 아직 runtime에 연결한 계약 구현은 없다.
사용자는 앱별 DTO·createdAt 재사용·snapshot 비교·최초 UUID 저장·추가 필드 무시를 [확정](trade-offs.md)했다.

## 형식

- Topic: `order-events`, group: `purchase-activity`.
- Key: orderId를 선행 0/공백/부호 없이 표현한 십진 문자열.
- Value: UTF-8 JSON object. key/value null 또는 tombstone은 구매 활동 입력으로 허용하지 않는다.
- Kafka headers에 Java 클래스 이름을 싣거나 소비 타입 선택의 근거로 사용하지 않는다.

```json
{
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "eventId": "e321d56a-59b3-4cf1-a7d0-6238451af280",
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
| eventId | 최초 확정 TX에 저장한 UUID의 소문자 표준 36자 표현. 공백/비표준 축약 표현을 보정하지 않음 |
| orderId | JSON 정수 1~Long.MAX_VALUE. 문자열/소수/범위 밖/null 거절 |
| confirmedAt | Z 또는 UTC offset이 있는 ISO-8601 문자열. UTC Instant로 비교. 마이크로초보다 작은 유효 값은 거절 |
| items | 비어 있지 않은 JSON 배열. 각 요소는 productId·quantity를 가진 object |
| productId | JSON 정수 1~Long.MAX_VALUE |
| quantity | JSON 정수 1~Integer.MAX_VALUE. 같은 상품의 합산 결과도 int 범위 안이어야 함 |

같은 v1의 알려지지 않은 추가 필드는 무시한다. 최상위와 품목 object에 같은 원칙을 적용한다.
알려진 필드의 누락/null·잘못된 타입·중복 JSON key·암묵적 숫자 변환·단일 object의 배열 변환은 거절한다.
올바른 type/version이 확인되지 않은 record를 기존 계약의 이벤트로 처리하지 않는다.

## 사건 ID와 시각

producer는 최초 PG 성공의 내부 확정 TX에서 UUID를 생성하고 order_records.confirmation_event_id에 저장한다.
주문 CONFIRMED·예약 소비·확정 기록·UUID가 함께 commit/rollback된다. 생성기는 테스트에서 고정할 수 있게 분리한다.
기존 완료 주문의 중복 callback/reconcile은 저장한 UUID를 그대로 사용하며 전송 시도 때 새로 만들지 않는다.
확정 TX가 rollback한 미저장 후보 UUID는 사건이 아니다. 재시도 후 실제 commit된 UUID가 최초 사건 identity다.
consumer도 eventId와 orderId의 유일성을 각각 보호해 같은 주문의 다른 UUID는 충돌로 처리한다.
테스트·서로 다른 DB 환경은 topic/group을 격리한다. 독립 DB의 orderId 충돌은 UUID만으로 해결했다고 보지 않는다.

기존 POINT와 과거 PG 기록을 보존하기 위해 새 컬럼은 nullable·unique로 추가한다.
신규 PG 확정 생성 경로는 UUID를 필수로 요구한다. 과거 PG의 null을 현재 UUID로 임의 채우거나 자동 소급 발행하지 않는다.
복구 시 저장 identity가 부족하면 새 UUID로 같은 과거 사건인 척하지 않고 원인과 대상을 드러낸다.

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
6. 알려지지 않은 추가 필드는 소비 snapshot과 같은 사실 비교에 포함하지 않는다.

JSON 속성/품목 순서와 공백은 사실의 identity가 아니다. raw bytes 비교나 원문 JSON 문자열 비교는 사용하지 않는다.
Kafka topic/partition/offset·전송 시도·receivedAt·processedAt은 이 비교에서 제외한다.

| 입력 조합 | 판정 |
|---|---|
| 같은 envelope, 품목 순서만 다름 | 같은 사실 |
| 상품 7×1 두 품목과 상품 7×2 한 품목 | 같은 사실 |
| 다른 timezone 문자열, 같은 Instant | 같은 사실 |
| 같은 알려진 필드에 모르는 메타데이터만 추가/변경 | 같은 소비 사실 |
| 같은 eventId, 다른 주문/수량/시각 | 충돌 |
| 같은 orderId, 다른 eventId | 충돌 |
| 같은 상품, 서로 다른 orderId와 eventId | 서로 다른 정상 구매 |
| 다른 schemaVersion/알 수 없는 eventType | 현재 parser의 지원 범위 밖, 소비 실패 |

## Codec와 스키마 변경

사용자 T01 선택에 따라 API producer DTO와 streamer consumer DTO·정규화·codec을 독립적으로 둔다.
공통 계약 Java 모듈은 만들지 않고 같은 JSON fixture와 wire 검증으로 두 앱의 계약을 맞춘다.
HTTP 전역 ObjectMapper의 느슨한 설정을 수정하지 않고 이벤트 전용 codec을 사용한다.
parser는 엄격한 JSON tree 검증 후 계약 객체를 만든다. 저장 snapshot 복원도 같은 필수 조건을 확인한다.

producer 직렬화 결과를 consumer parser에 넣는 wire 검증과 실제 broker 경로를 모두 둔다.
같은 v1의 모르는 추가 필드는 무시하되, 알려진 필드의 값/의미 변경을 추가 필드로 숨기지 않는다.
새 필드를 구매 사실의 의미로 사용하려면 버전·기존 snapshot·호환성 정책을 함께 설계한다.
이 과제에서는 v2를 자동 수용하지 않는다. 새 계약 지원 전에는 오류 record를 성공 처리하지 않는다.
