# Kafka consumer 처리 지연 부하 실험 결과

## 시나리오 개요

partition 1개와 서비스별 consumer group 1개를 사용했다. 서비스별 DB·계정은 MySQL 8.0에 분리했고 인위적 처리 지연은 넣지 않았다. 공통 목표는 각 record의 partition 기록부터 담당 서비스 DB commit까지 3초 이내다. 초당 25건·주문 20건으로 시작해 초당 100·250·500건은 주문 500건씩, 마지막으로 초당 1,000건·주문 1,000건을 발행했다.

## 결과

### 초당 100건·주문 500건

총 3,000 records가 생성됐다. 평균 event age는 Inventory `OrderCreated` 451ms, Payment `InventoryReserved` 163ms, Order `PaymentSucceeded` 38ms, Shipping `OrderConfirmed` 614ms·`ShipmentStarted` 634ms였다.

| 담당 group | 최대 event age | 3초 초과 |
| --- | ---: | ---: |
| Inventory | 1,165ms | 0 / 500 |
| Payment | 360ms | 0 / 500 |
| Order | 100ms | 0 / 500 |
| Shipping | 843ms | 0 / 1,000 |

부하 중 최대 lag는 Inventory 360, Payment 171, Order 43, Shipping 570이었다. 발행을 멈추자 네 group 모두 offset 3,000까지 따라잡아 lag 0이 됐다. 네 MySQL DB에는 각각 예약 500건, 결제 성공 500건, 주문 확정 500건, 배송 완료 500건이 저장됐다.

초당 25건 기준 실행에서도 3초 초과는 없었다. 초당 100건 부하에서도 lag는 일시적으로 쌓였지만 record별 3초 목표는 지켰다. 이 결과는 로컬 단일 MySQL 인스턴스의 처리 결과이며 운영 용량 보장은 아니다.

### 부하 증가 결과

| 주문 발행률 | 주문 수 | Inventory `OrderCreated` 평균 / 최댓값 | 3초 초과 | 최대 lag |
| ---: | ---: | ---: | ---: | ---: |
| 250건/초 | 500 | 1,268 / 1,501ms | 0 / 500 | Inventory 1,175 |
| 500건/초 | 500 | 1,646 / 2,246ms | 0 / 500 | Inventory 1,499 |
| 1,000건/초 | 1,000 | 3,138 / 5,032ms | 547 / 1,000 | Inventory 3,180 |

초당 1,000건 실행에서 Inventory `OrderCreated`가 처음으로 3초 목표를 넘었다. 초과한 547건은 `load-order-454`부터 `load-order-1000`까지였고, 최댓값은 5,032ms였다. 같은 실행의 다른 단계는 목표 안에 있었다: Payment 최대 1,175ms, Order 최대 375ms, Shipping 최대 1,708ms. 이는 현재 단일 파티션 구성에서 재고 consumer group이 먼저 밀리기 시작한 관찰이다.

부하 중 lag가 커졌지만 발행 종료 후 모든 group이 최종 offset 6,000까지 따라잡아 lag 0이 됐다. 따라서 이 실행에서는 처리 지연 목표 위반은 확인했지만, backlog가 계속 증가해 복구하지 못하는 상태는 관찰하지 않았다. 단일 파티션·로컬 MySQL 실험 결과이므로 운영 한계치로 일반화하지 않는다.
