# 주문 생성·조회·확정 TDD 실행 기록

2026-09-18 승인된 P01·P07·P08·P12·P13에 따라 C09~C12와 A12·A13을 도메인·JPA·application·HTTP까지 연결했다. 기존 [주문 수량 합산](commerce-tdd-order-quantity-log.md)은 변경하지 않고 생성 입력에 재사용한다. 아래 결과는 주문 증분의 증거이며 전체 프로젝트 최종 검사나 운영 수동 DDL의 완료를 뜻하지 않는다.

## 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [Order](../../apps/commerce-api/src/main/java/com/loopers/domain/order/Order.java) · [OrderItem](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderItem.java) | DRAFT 생성, 이름·단가·수량 스냅샷, checked 곱셈·합산, 소유자 검증, 확정 금액·시각 보존 |
| [OrderStatus](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderStatus.java) · [OrderException](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderException.java) | DRAFT/CONFIRMED와 HTTP 독립 실패 사유 |
| [OrderRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderRepository.java) · [OrderRepositoryImpl](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/order/OrderRepositoryImpl.java) · [OrderJpaRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/order/OrderJpaRepository.java) | 실제 저장·상세·주문 행 잠금, 구매자/상태 필터·정렬 후 페이지와 항목 일괄 조회 |
| [OrderService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderService.java) | 요청자 해석, 저장소 조율, READ_COMMITTED 트랜잭션·잠금 순서, 사전 검증 후 원자적 차감, 본인/관리자 조회 |
| [OrderInfo](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderInfo.java) · [AdminOrderInfo](../../apps/commerce-api/src/main/java/com/loopers/application/order/AdminOrderInfo.java) | 변경 가능한 엔티티를 노출하지 않는 내부 결과. 관리자에게만 외부 구매자 userId 추가 |
| [OrderV1Controller](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/order/OrderV1Controller.java) · [AdminOrderV1Controller](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/order/AdminOrderV1Controller.java) | 고객 4개·관리자 2개 경로, 엄격한 JSON·ID·페이지·상태 입력과 ApiResponse 연결 |

JPA의 `order`는 예약어 인용을 적용한다. Order→User, OrderItem→Order/Product FK와 주문별 상품 유일 제약을 매핑했다. 상품의 현재 이름·가격을 과거 조회에 사용하지 않으며, LAZY 상품 참조는 FK와 ID에만 사용한다. 목록은 조건에 맞는 ID를 정렬·페이지 처리한 뒤 그 주문들의 항목을 일괄 조회한다. 도메인 저장소는 controller DTO나 application 결과 타입에 의존하지 않는다.

생성은 수량 검증·합산 후 사용자→브랜드 ID 오름차순→상품 ID 오름차순으로 잠그고 스냅샷을 저장한다. 재고·잔액은 차감하지 않는다. 확정은 주문을 먼저 잠그고 소유권을 확인한다. 이미 CONFIRMED이면 현재 상품을 다시 검증하지 않고 저장 결과를 반환한다. DRAFT는 사용자→브랜드→상품 순으로 잠근 최신 상태에서 모든 수량·재고·잔액을 검증한 뒤 한 트랜잭션에서 변경한다. 상품 소속 조회는 잠금 전 스칼라 ID 조회로 수행하여 잠금 전에 오래된 상품 상태를 영속성 컨텍스트에 적재하지 않는다.

관리자 구매자 필터와 응답은 기존 사용자 fixture의 정·역조회 계약을 사용한다. 별도 매핑을 복제하지 않는다. 읽기 리뷰에서 발견한 DB 사용자 행 부재도 고객 주문 조회에서 USER_NOT_FOUND로 거절하고 회귀 사례로 확인했다. 이 보완에는 독립적인 Red 실행을 주장하지 않는다.

## 실제 Red → Green 및 구조 검토

| 증분 | 실제 실패 | 구현·재검증 |
| --- | --- | --- |
| 첫 총액·스냅샷 | OrderTest 1개 실패: 기대 총액 800, 실제 0 | 최소 합산 후 1개 통과. 상품 변경 후 기존 스냅샷과 재고 보존 확인 |
| 금액·상태·실제 생성 | OrderTest 8개 중 4개 실패: 합산 초과·빈 항목·타인 접근·확정 상태. 생성 통합 1개는 null 결과로 실패 | checked 합산, 입력·소유권·상태 규칙, 실제 생성 연결 후 도메인 8개·생성 통합 1개 통과. 곱셈 초과 등 기존 통과 사례를 인위적으로 실패시키지 않음 |
| 확정·HTTP 연결 | HTTP 생성 1개는 201 대신 404. 독립 추가된 확정 경합 6개는 미구현 confirm의 null·무변경으로 실패 | 6개 HTTP 경로와 확정·조회 조율 구현, HTTP 경계와 SQL 후 롤백 사례 확장 후 아래 55개 모두 통과 |

구조 검토에서 브랜드·상품 잠금 조합을 `lockProducts`로 모으고, 재고·잔액 검증은 각 도메인의 변경 없는 검증 메서드를 재사용했다. 숫자 업무 규칙을 application에 복제하지 않는다. 업무 실패의 HTTP 매핑은 공통 커머스 오류 경계에서 수행한다. 55개 검사 후 독립 리뷰에서 UserIdentityRepository의 역조회 기본 빈 결과를 필수 조회 계약으로 보완하고 기존 사용자 테스트 기대값을 보존했다. 이 마지막 계약 보완은 후속 전체 검사에 포함한다.

## 실제 검사 결과

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.order.OrderTest' \
  --tests 'com.loopers.application.order.OrderServiceIntegrationTest' \
  --tests 'com.loopers.application.order.OrderConcurrencyIntegrationTest' \
  --tests 'com.loopers.interfaces.api.order.OrderV1ApiE2ETest' \
  --tests 'com.loopers.application.brand.BrandConcurrencyIntegrationTest' --console=plain -q
```

2026-09-18 13:38 KST 실행 결과는 다음과 같다. XML에서 실패·오류·건너뜀 모두 0개를 확인했다.

| 검사 | 통과 | 검증 범위 |
| --- | --- | --- |
| [OrderTest](../../apps/commerce-api/src/test/java/com/loopers/domain/order/OrderTest.java) | 8 | 스냅샷·총액·Long 상한·초과·필수 항목·소유권·재확정·불변 항목 목록 |
| [OrderServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderServiceIntegrationTest.java) | 3 | 중복 합산 실제 저장·생성 시 차감 없음, 생성/확정 SQL flush 후 호출자 실패와 전체 행 롤백 |
| [OrderConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderConcurrencyIntegrationTest.java) | 6 | 같은 주문 1회 차감, 공유 재고/잔액 경쟁, 충전·재고 설정·상품 삭제와 확정 경합 |
| [OrderV1ApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) | 35 | 6개 경로, 반복 생성·재확정·삭제 후 스냅샷, 본인/관리자 필터·정렬·페이지, 정확한 오류·엄격 JSON·금액 초과·손상된 저장 수량500·상태 보존 |
| [BrandConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandConcurrencyIntegrationTest.java) | 3 | 함께 실행한 기존 공유 브랜드 잠금·타임아웃·교착 회귀 |
| 합계 | **55** | 주문 52개와 브랜드 경합 회귀 3개 |

롤백 검증은 입력 실패만 다루지 않는다. 실제 생성 INSERT 또는 확정 변경을 `EntityManager.flush()`로 DB에 반영한 뒤 같은 트랜잭션에서 예외를 발생시킨다. 요청 전후 user·product·order·order_item 전체 행을 비교하여 감사 시각을 포함한 부분 반영이 없음을 확인했다. 경합 실패는 다른 요청의 성공 효과를 없애는 뜻이 아니므로 직렬화 가능한 최종 상태와 실패 요청의 추가 효과 없음을 검증한다.

위 실행은 승인된 작은 범위의 통합 검사다. 최종 전체 회귀·Checkstyle·ArchUnit과 운영 수동 DDL 적용 결과는 [전체 완료 체크리스트](commerce-completion-checklist.md)에 별도로 기록한다. 기존 도메인·예시 테스트나 검사 기준을 삭제·완화하지 않았다.

## 후속 검토: 저장 금액 손상과 오류 구분

저장된 항목의 단가 스냅샷이 손상되어 소계 곱셈이 범위를 넘으면 새 주문의 정상 입력 계산 초과와 달리 `500 INTERNAL_ERROR`여야 한다. 정상 단가 100·수량 2로 주문을 저장한 뒤 SQL로 스냅샷 단가를 Long 최댓값으로 바꾸어 상세 조회와 확정을 각각 재현했다.

- Red: 두 HTTP 사례 모두 기대 500 대신 `409 ORDER_AMOUNT_LIMIT_EXCEEDED`로 실패했다.
- Green: `OrderInfo.from`의 저장 결과 변환에서 해당 금액 초과 사유만 `IllegalStateException`으로 감쌌다. 신규 생성의 금액 검증은 그대로 유지하여 기존 C09 곱셈·합산 초과 409를 보존했다.
- 2026-09-18 13:51 KST에 `OrderV1ApiE2ETest` 37개와 `OrderConcurrencyIntegrationTest` 7개, 합계 44개가 통과했다. 실패·오류·건너뜀은 모두 0개다. 손상된 저장 금액의 상세·확정 실패 후 user·product·order·order_item 전체 행이 같음을 확인했다. 동시성 7개에는 첫 확정의 커밋까지 두 번째 요청이 대기한 뒤 저장 결과를 재사용하는 후속 사례가 포함된다.

이 44개는 앞선 55개 실행에 단순 합산하지 않는다. 마지막 수정의 전체 회귀·Checkstyle·ArchUnit은 완료 체크리스트의 최종 검사로 확인한다.

## 최종 요구사항 대조

[OrderScenarioApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderScenarioApiE2ETest.java)에 문서의 구체 수치와 여러 항목의 실패 경계를 대조하는 5개 실행 사례를 추가했다.

| 계약 | 추가 검증 |
| --- | --- |
| API-09·12 | 단가 1000/2000·수량 2/1의 합계 4000, DRAFT 생성 시 차감 없음, 반복 생성은 별도 주문·항목. 확정 후 재고 3/3·잔액 6000 |
| API-10 | 두 번째 항목이 없는 상품·삭제 상품·삭제 브랜드인 3개 경우 모두 404. user·product·order·order_item 전체 상태 보존 |
| API-11 | 같은 상품 수량 2+3이 항목 1행·수량 5로 저장됨. 재고 4에서 확정 실패와 상태 보존, 관리자가 재고 5로 설정한 뒤 성공·재고 0·잔액 5000 |

이 사례들은 기존 구현에서 통과한 요구사항 보완 검증이다. 새로운 Red나 기능 구현으로 기록하지 않는다. 2026-09-18 13:54 KST의 전체 `:apps:commerce-api:check`에서 위 5개와 기존 주문·동시성 사례가 모두 통과했다. 전체 448개·Checkstyle·ArchUnit 결과는 [최종 검사](commerce-completion-checklist.md#최신-통합-검사)에 기록했다.

## 과제 피드백: 충전부터 주문 조회까지

[PointOrderJourneyApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/PointOrderJourneyApiE2ETest.java)는 사용자 fixture의 초기 잔액 0에서 시작한다. 브랜드·상품만 준비하며 잔액을 SQL이나 PointService 직접 호출로 채우지 않는다.

1. 잔액 API로 0을 확인하고 충전 API로 10000원을 충전한다.
2. 2000원 상품 2개·3000원 상품 1개를 주문한다. DRAFT의 합계7000·두 품목 스냅샷과 잔액10000·기존 재고를 확인한다.
3. 확정 API의 CONFIRMED·결제액7000·확정 시각을 확인한다.
4. 별도 요청으로 내 주문 목록·상세를 조회해 확정 응답과 같은 저장 결과를 확인하고, 잔액 API에서3000·상품 API에서재고3/3을 확인한다. 다른 사용자의 주문 목록·잔액은 비어 있음/0을 유지한다.

2026-09-18 14:17 KST의 최초 실행에서 1개가 바로 통과했다. 기존 기능 사이의 연결 누락을 보완한 회귀 테스트이며 인위적인 Red나 새 업무 구현을 주장하지 않는다. 관리자 Security 변경 후에도 [최종 전체 검사](commerce-completion-checklist.md#최신-통합-검사)에 함께 포함한다.
