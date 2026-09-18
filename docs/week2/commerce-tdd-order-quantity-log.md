# 주문 수량 합산 도메인 TDD

2026-09-18 전체 완료 목표에서, 금액 표현·HTTP 오류·잠금 세부 정책에 의존하지 않는 P01/P08의 확정 규칙을 먼저 구현했다. 같은 상품의 양수 수량을 합산하고 각 입력과 합계의 Integer 범위를 지킨다. 주문 생성·저장·확정의 완료 기록은 아니다.

## 책임과 파일

| 파일 | 책임 |
| --- | --- |
| [OrderQuantities](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderQuantities.java) | 상품별 수량 검증·중복 합산과 불변 결과. 상품 ID의 양수 Long, 개별 양수 수량과 합산 상한 검사 |
| [OrderQuantityException](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderQuantityException.java) | 잘못된 항목·상품 ID·개별 수량·수량 합산 초과를 HTTP와 독립된 사유로 구분 |
| [OrderQuantitiesTest](../../apps/commerce-api/src/test/java/com/loopers/domain/order/OrderQuantitiesTest.java) | 8개 규칙의 16개 단위 실행 사례 |

`domain/order`는 Java 컬렉션과 정수 연산만 사용한다. controller DTO·application·infrastructure·DB에 의존하지 않으며 Layer-first·모듈·ArchUnit 규칙을 유지한다. 입력 항목은 상품 ID·수량만 담는다. 반환 Map은 복사하여 변경할 수 없고, 호출자가 가진 입력 목록도 바꾸지 않는다. 항목 순서나 HTTP 응답 정렬을 새 정책으로 정하지 않는다.

## 실제 Red → Green → Refactor

| 순서 | 실제 Red | Green·정리 |
| --- | --- | --- |
| ORDER-QUANTITY-01 | 빈 Map 골격이 상품101×2·103×1을 잃음. 1개 중 1개 실패 | 서로 다른 상품 수량을 보존해 1개 통과 |
| ORDER-QUANTITY-02 | 떨어져 있는 상품101×2·101×3에서 Duplicate key 예외. 2개 중 1개 실패 | 동일 상품의 수량을 합쳐 101×5·103×1, 2개 통과 |
| ORDER-QUANTITY-03·04 | 0·-1·Integer.MIN_VALUE와 합계 초과 두 사례가 거절되지 않음. 7개 중 5개 실패 | 개별 양수 검증과 Math.addExact·도메인 사유 변환을 추가해 7개 통과 |
| ORDER-QUANTITY-05~08 | null/빈 목록/null 항목과 0·-1·Long.MIN_VALUE ID의 6개 실패. 총 16개 실행 | 항목·ID 검증 후 16개 통과. Long/Integer 최댓값, 정확히 Integer 최댓값인 합계와 불변성은 기존 구현으로 통과 |
| Refactor | 이미 통과한 기대값 유지 | 항목 검증과 안전한 합산을 별도 private 메서드로 정리하고 16개 재통과 |

합산할 입력 중 하나가 음수이면 최종 합계가 양수여도 거절한다. 개별 값이 양수여도 합계가 Integer 최댓값을 넘으면 `QUANTITY_LIMIT_EXCEEDED`로 거절하며 작은 음수로 순환한 값을 반환하지 않는다. 실패·성공 모두 입력 목록을 변경하지 않고 완성된 결과만 반환한다.

## 검사 결과

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.order.OrderQuantitiesTest' --console=plain -q
```

리팩터링 후 **16개 통과**, 실패·오류·건너뜀 0개다. 전체 회귀·Checkstyle·ArchUnit 결과는 [전체 완료 체크리스트](commerce-completion-checklist.md)에 함께 기록한다. 기존 테스트·기대값·의존 규칙을 삭제하거나 완화하지 않았다.

## 남은 연결

- C09에서 도메인 항목으로 변환하고 실제 상품·브랜드 조회, 생성 당시 상품명·단가 스냅샷·금액 계산과 주문/항목 저장을 연결한다.
- C10에서 저장된 상품별 합산 수량으로 재고를 검사·차감하고 포인트·주문 상태와 원자적으로 반영한다.
- API-11의 항목 1행 저장·수량 5 확정·실패 시 주문/항목 미저장과 HTTP 오류 매핑은 아직 검증하지 않았다.
- 금액 타입·나머지 API·구체 잠금 정책은 답변 대기다. 내부 예외 사유를 HTTP 코드·상태의 승인으로 확대하지 않는다.
