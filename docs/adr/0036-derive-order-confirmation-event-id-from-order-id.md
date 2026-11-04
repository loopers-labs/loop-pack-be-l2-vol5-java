# ADR 0036: 주문 확정 eventId를 내부 주문 ID에서 고정 생성한다

Status: Accepted
Related: [ADR 0035](0035-publish-order-confirmation-after-db-commit.md), [week6 설계](../week6/design.md#event-identity)

## Context

이번 계약에서 PG 주문의 확정은 주문당 한 번 발생한다. 같은 확정 사실이 재발행·재전달돼도 consumer가 같은 사건으로 식별할 수 있어야 한다. Kafka key는 partition 선택에 쓰이는 값이며 이 사건 식별과 책임이 다르다.

사용자는 orderId만 사용해도 되는지, 이름을 붙이는 이유가 무엇인지 확인한 뒤 사건 종류를 포함한 고정 ID를 선택했다. 이 결정은 ADR 0035의 커밋 후 전달 경계를 유지하면서 사건 식별 방식을 구체화한다.

## Decision

- eventId는 `order-confirmed:v1:{orderId}`로 생성한다. 주문 42는 항상 `order-confirmed:v1:42`다.
- `OrderConfirmed.eventId()`에서 내부 주문 ID로 유도한다. 전송 시각·시도 횟수·새 UUID를 섞거나 별도 eventId 컬럼을 추가하지 않는다.
- Kafka key는 기존 결정대로 orderId의 문자열 표현이다. 주문 42의 key는 `"42"`이며 eventId와 역할을 구분한다.
- prefix는 주문 확정 사건의 이름 공간이다. 이후 취소 등 별도 사건의 식별자를 설계할 때 같은 주문 번호의 확정 사실과 구별할 수 있다. `v1`은 이 ID 형식의 일부이며, 재전송이나 JSON 필드 변경만으로 기존 확정 사실의 eventId를 바꾸지 않는다.

## Options considered

| 방식 | 이점 | 비용·한계 | 선택 |
| --- | --- | --- | --- |
| 주문 ID 문자열만 사용 | 단순하며 현재의 주문당 한 번 확정 범위에서 식별 가능 | eventId만 사용하는 비교 범위가 다른 사건 종류까지 넓어질 때 이름 공간이 필요 | 미채택 |
| 사건 이름 + 내부 주문 ID | 같은 사실에서 언제나 같은 값, 별도 저장 불필요, 사건 종류 구별 | 내부 주문 ID의 유일성과 주문당 한 번 확정 계약을 전제로 함 | 채택 |
| 최초 확정 때 UUID를 생성·저장 | 저장한 ID를 재사용하면 안정적인 사건 식별 가능 | 컬럼·최초 생성·재사용 처리가 추가됨 | 미채택 |

## Consequences

consumer의 중복 방지에는 같은 eventId뿐 아니라 주문 ID·최초 확정 시각·정규화한 품목의 일치도 필요하다. ID가 같다는 이유만으로 다른 수량·주문 사실을 정상 중복으로 처리하지 않는다. 실제 DB 유일 제약과 충돌 처리는 후속 소비 기능의 범위다.

## Evidence

- [OrderConfirmedTest](../../apps/commerce-api/src/test/java/com/loopers/domain/order/OrderConfirmedTest.java): 같은 주문의 고정 ID·다른 주문의 구별.
- [KafkaOrderConfirmedPublisherTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/order/KafkaOrderConfirmedPublisherTest.java): key와 JSON eventId·버전·품목 계약.
- [OrderConfirmedPublicationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderConfirmedPublicationTest.java): 실제 주문 확정과 재전송의 record·동일 사건 ID 검증.
- 실행 명령·결과와 현재 구현 상태는 [week6 구현 기록](../week6/implementation-log.md#kafka-producer)에서 관리한다.
