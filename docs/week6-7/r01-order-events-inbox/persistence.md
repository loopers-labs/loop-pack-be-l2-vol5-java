# R01 Inbox·상품 구매 활동 저장 설계

[상세 설계](plan.md) · [이벤트 계약](event-contract.md). 아래는 설계 DDL이며 DB에 적용하지 않았다.

## 저장 책임

streamer의 `ordering` context가 두 사실을 소유한다.

- OrderConfirmationInbox: 어떤 확정 사건을 구매 활동으로 반영했는지와 그 고정 사실.
- ProductPurchaseActivity: 주문별·상품별 구매 수량과 최초 확정 시각.

consumer도 같은 commerce MySQL datasource를 사용하지만 주문/결제/상품/재고를 다시 변경하거나 조회해 보정하지 않는다.
Inbox와 활동은 append-only 사실이며 정상 재수신은 기존 행을 수정하지 않는다.

## 테이블과 제약

```sql
CREATE TABLE order_confirmation_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    normalized_payload LONGTEXT NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_inbox_event_id UNIQUE (event_id),
    CONSTRAINT uk_purchase_inbox_order_id UNIQUE (order_id),
    CONSTRAINT ck_purchase_inbox_order_positive CHECK (order_id > 0),
    CONSTRAINT ck_purchase_inbox_contract CHECK (event_type = 'OrderConfirmed' AND schema_version = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_purchase_activities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inbox_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_activities_order_product UNIQUE (order_id, product_id),
    CONSTRAINT fk_purchase_activities_inbox FOREIGN KEY (inbox_id) REFERENCES order_confirmation_inbox (id),
    CONSTRAINT ck_purchase_activity_order_positive CHECK (order_id > 0),
    CONSTRAINT ck_purchase_activity_product_positive CHECK (product_id > 0),
    CONSTRAINT ck_purchase_activity_quantity_positive CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- DB 유일 비교는 eventId의 대소문자까지 보존한다. 애플리케이션에서 trim/lowercase로 다른 ID를 합치지 않는다.
- surrogate PK는 JPA insert/활동 연결에 쓰고 사건 identity는 event_id, 업무 identity는 order_id로 보호한다.
- normalized_payload에는 정규화한 v1 snapshot을 저장한다. 복원한 필드를 비교하며 hash-only 판단을 하지 않는다.
- payload의 identity/시각과 중복 컬럼이 맞는지는 Mapper 복원/생성에서 검사한다. 불일치를 중복으로 숨기지 않는다.
- processedAt은 처리 TX에서 정한 UTC·마이크로초 시각이다. DB의 정확한 commit 시각을 뜻하지 않는다.
- order/product 원본 테이블에는 FK를 걸지 않는다. 삭제된 상품과 현재 원본 조회에 소비가 의존하지 않게 한다.
- 활동의 inbox_id/order_id/시각 일치는 같은 OrderConfirmation에서 생성하는 domain/application 경계로 보호한다.
- 추가 랭킹 인덱스는 실제 후속 조회에 맞춰 정한다. 현재 유일 키는 주문별 결과 조회에 사용할 수 있다.
- 명시 SQL은 새 파일에 추가하고 기존 기준 SQL/보존 DB를 초기화하지 않는다. Hibernate validate와 실제 MySQL 8.0에서 대조한다.

## 상태 모델

처리 중인 Inbox는 아직 commit되지 않았으므로 다른 트랜잭션의 완료 증거가 아니다.
정상 commit된 Inbox 행은 모든 구매 활동의 완료 기록이다. 별도 status/RECEIVED 행을 먼저 commit하지 않는다.

```text
미처리
  → [하나의 TX: Inbox insert + 모든 활동 insert]
      ├─ commit → 완료 기록과 모든 품목 존재
      └─ rollback → 이번 TX의 Inbox/활동 모두 없음

완료된 같은 사실 재수신 → no-op → ack 가능
완료 기록과 다른 사실 수신 → 충돌 → ack 금지
```

commit 후 offset ack가 실패해도 DB 결과는 유지한다. 재수신에서 같은 완료 사실을 확인한 뒤 다시 ack한다.
활동만 지우거나 Inbox만 지워 재처리를 준비하는 운영 도구는 만들지 않는다.

## 신규·중복 처리 알고리즘

```text
Kafka listener:
  key/value를 엄격히 파싱·정규화 → Command
  ConsumeOrderConfirmationUseCase.execute(command)
  성공 반환 뒤에만 같은 consumer 스레드에서 ack

ConsumeOrderConfirmationFacade [TX 없음]:
  try:
    RecordPurchaseActivityService.execute(command) [별도 Bean, 전체 쓰기 TX]
  catch 확인된 Inbox 유일 제약 경쟁:
    VerifyProcessedOrderConfirmationService.execute(command) [새 readOnly TX]

RecordPurchaseActivityService [REQUIRES_NEW]:
  eventId 또는 orderId로 기존 Inbox 조회 (최대 두 행)
  있으면 모든 식별 관계·고정 사실 일치 확인
    일치 → ALREADY_PROCESSED
    불일치/두 식별자가 다른 행 → 충돌
  없으면 Inbox insert + flush
  모든 정규화 품목을 활동으로 insert + flush
  commit 성공 뒤 CREATED 반환
```

단순 사전 조회는 최적화일 뿐 동시성 보장이 아니다. insert의 named 유일 제약이 경쟁을 막는다.
Inbox flush를 먼저 수행해 경합 승자를 정하고, 그 뒤 품목을 저장한다. 중간 실패는 같은 TX 전체를 rollback한다.
REQUIRES_NEW는 여기에서 **Inbox와 모든 활동의 묶음 전체**에 적용한다. Inbox 단독 commit이 아니다.
Facade에는 외부 TX를 허용하지 않고 proxy 자기 호출로 경계를 우회하지 않는다.

## 동시 insert 경쟁

1. 두 TX가 같은 사건을 모두 미처리로 읽을 수 있다.
2. 승자가 Inbox와 품목을 함께 commit한다.
3. 패자의 insert는 실제 MySQL 중복 키 예외가 된다. 패자 TX 전체를 rollback한다.
4. Facade가 rollback 이후 별도 Bean의 새 TX로 eventId/orderId 양쪽을 다시 조회한다.
5. 같은 완료 사실이면 ALREADY_PROCESSED, 다른 사실이면 충돌, 완료 증거가 없으면 원래 실패를 유지한다.

실패한 JPA TX 안에서 예외를 삼키고 추가 조회/정상 commit하지 않는다.
새 TX를 사용해 REPEATABLE READ의 이전 snapshot으로 승자의 완료를 놓치지 않게 한다.
승자도 rollback했다면 그 insert는 완료 증거가 아니다. 재수신/재시작에서 다시 처리할 수 있어야 한다.

예외는 MySQL 중복 키와 다음 **Inbox 제약 이름**이 확인된 경우만 경쟁 후보로 분류한다.

- uk_purchase_inbox_event_id
- uk_purchase_inbox_order_id

제약 이름의 DB prefix/인용부호는 드라이버가 제공하는 범위에서 정규화하되 임의 문구를 추측하지 않는다.
구매 활동의 유일 제약·NOT NULL·길이·FK·연결·데드락·기타 오류는 이 후보로 취급하지 않는다.
constraint 정보를 확인하지 못하거나 rollback이 실패했다면 성공으로 바꾸지 않고 소비 실패로 남긴다.
실제 드라이버가 반환한 제약 정보와 rollback/별도 TX 동작은 구현 시 MySQL 8.0 검사로 확정한다.

## 충돌 판정

| 기존 Inbox와 새 사건 | 결과 |
|---|---|
| 같은 eventId/orderId와 모든 고정 필드 | ALREADY_PROCESSED, 추가 쓰기 없음 |
| 같은 eventId·다른 orderId | 충돌 |
| 같은 orderId·다른 eventId | 충돌 |
| 같은 identity·다른 수량/확정 시각 | 충돌 |
| eventId와 orderId가 서로 다른 기존 행을 가리킴 | 충돌 |
| payload 복원 불가/컬럼 불일치 | 저장 사실 손상으로 오류 |

충돌은 새 구매가 아니다. 기존 snapshot/활동을 덮어쓰거나 보정하지 않고 소비를 중단한다.

## 보존과 이후 복구

이번 범위는 Inbox·미발행 Outbox·격리 사건을 자동 삭제하지 않는다.
오래된 사건을 Kafka/Outbox에서 다시 보낼 수 있는 동안 Inbox와 활동의 중복 방지 근거를 함께 보존한다.
보존/삭제 정책은 향후 별도 작업이며 Inbox만 먼저 지우지 않는다.

W6 A에는 Inbox가 없고 B에는 Inbox와 활동이 있다. W7에서 A/B의 저장된 확정 사실을 Outbox로 복구해도
A만 새로 저장되고 B는 ALREADY_PROCESSED다. PG 추가 호출·주문 재확정·재고 소비는 하지 않는다.
