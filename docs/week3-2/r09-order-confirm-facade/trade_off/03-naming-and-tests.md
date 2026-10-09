# 위치·이름·테스트

[← 전체 선택 현황](total_trade_off.md)

## 판단할 문제

파사드를 어느 패키지에 어떤 이름으로 둘지, 정책 해체로 사라지는 테스트를 어디로 옮길지 정한다.

> **채택 — `application.ordering.facade.ConfirmOrderFacade`, 파사드 단위 테스트 + 기존 통합 테스트 유지**
>
> - `ConfirmOrderFacade implements ConfirmOrderUseCase`. 새 종류 패키지 `facade`를 둔다. UseCase 이름은 기존 규칙(`ConfirmOrderUseCase`)을 유지하고 `ConfirmOrderService`는 제거한다.
> - `ConfirmOrderFacadeTest`(Mockito): `InOrder`로 호출 순서, 앞 단계가 실패하면 뒤 단계를 부르지 않음, 저장된 주문 합계로 결제(기존 `ConfirmOrderServiceTest` 케이스 이전).
> - `OrderConfirmationPolicyTest` 10건의 규칙 조합은 `Order` 도메인 테스트(수량 합산·overflow)와 Service 단위 테스트(재고·삭제·잔액·뒤쪽 품목 실패 시 앞쪽 유지)로 옮긴다.
> - `JpaConfirmOrderWriterTest`의 잠금·저장 순서 검증은 파사드 `InOrder`와 `ProductService` id 오름차순 테스트로 대신한다.
> - "잔액 부족 시 재고 유지"는 메모리가 아니라 트랜잭션 롤백으로 보장되므로, 기존 통합 테스트가 그대로 검증한다.
>
> 대신 정책 테스트처럼 Mock 없이 확정 규칙 전체를 한 번에 보는 단위 테스트는 없어진다.

## 장단점 비교

| 주제 | 채택 | 미채택 |
|---|---|---|
| 위치 | **`facade` 패키지**: Service와 역할이 구분됨 | `service` 패키지: 종류는 안 늘지만 UseCase 구현이 섞임 |
| 이름 | **기존 규칙 유지(`ConfirmOrder*`)** | 다이어그램 이름(`OrderConfirmUsecase`): UseCase 이름까지 바뀌어 변경 범위가 커짐 |
| 테스트 | **파사드 단위 + 통합 유지** | 통합만: 호출 순서(잠금 순서)를 빠르게 검증할 수 없음 |
