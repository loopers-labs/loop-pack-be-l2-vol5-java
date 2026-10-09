# R09 트레이드오프

[요구사항](../requirement.md) · [구현 계획](../plan.md) · [전체 요구사항](../../total_requirement.md) · [주제 문서 템플릿](../../_template.md)

**굵게 = 채택** · ~~취소선 = 미채택~~ · `[검토 전]` / `[검토 중]` = 미확정 · `[보류]` = 필요할 때 재검토

모든 주제를 결정했다(2026-10-09 문답). R02 [01](../../../week3/r02-order-consistency/trade_off/01-business-policy.md)의 "도메인 서비스로 업무 흐름 묶기"와 `ConfirmOrderWriter` 포트 구조를 대체한다.

## 선택 현황

1. [조율 구조와 도메인 정책](01-orchestration.md): **파사드가 Order·Wallet·Product Service를 순서대로 호출, `OrderConfirmationPolicy` 해체, 수량 합산은 `Order.quantitiesByProductId()`** / ~~파사드 + 정책 유지~~ / ~~현행 Writer 포트 + 정책~~
2. [Service API·트랜잭션·잠금 순서](02-service-api.md): **기존 Service에 public 메서드, 파사드에만 `@Transactional`, 잠금 순서는 파사드 호출 순서 + `decreaseStocks` id 정렬, WalletService 포함** / ~~전용 컴포넌트~~ / ~~Service MANDATORY~~ / ~~Service REQUIRED~~ / ~~각 Service 내부만 책임~~
3. [위치·이름·테스트](03-naming-and-tests.md): **`application.ordering.facade.ConfirmOrderFacade`, 기존 UseCase 이름 유지, 파사드 단위(`InOrder`) + 기존 통합 테스트 유지** / ~~`service` 패키지~~ / ~~다이어그램 이름~~ / ~~통합만~~

## 구현 계획으로 이어갈 것

1~3번 모두 [구현 계획](../plan.md)으로 구체화한다.
