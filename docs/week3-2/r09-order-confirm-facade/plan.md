# R09 구현 계획

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r09-order-confirm-facade` · PR 대상: `volume-3/main`

상태: 사용자 합의 완료(2026-10-09), Sonnet 위임. R08 브랜치(PR #20)에서 분기했다. R08 병합(PR #20) 후 main 위로 리베이스했다(트리 동일).

## 문서와 진행 원칙

- 결정 근거는 [01 조율 구조](trade_off/01-orchestration.md), [02 Service API](trade_off/02-service-api.md), [03 위치·이름·테스트](trade_off/03-naming-and-tests.md)를 따른다.
- 커밋 메시지는 `type: 한국어 요약 한 문장` + 빈 줄 + `- 설명` 목록 본문이다(목록 기호는 하이픈). Co-Authored-By 등 AI 표기 줄을 붙이지 않는다.
- 기존 통합·롤백·동시성·E2E 테스트의 **기대값은 바꾸지 않는다.** 삭제·이전하는 테스트는 아래 커밋 4에 적은 것뿐이다.
- Checkstyle·ArchUnit을 완화하지 않는다. 새 파일 첫 줄에는 한국어 역할 주석을 단다. push·PR은 하지 않는다.

## 설계

```
ConfirmOrderFacade.execute(command)  @Transactional
  Order order   = orderService.lockForConfirm(orderId)
  Wallet wallet = walletService.lockByUserId(order.getUserId())
  productService.decreaseStocks(order.quantitiesByProductId())
  PointBill bill = walletService.pay(wallet, order)
  Order confirmed = orderService.confirm(order)
  return new ConfirmOrderResult(OrderResult.from(confirmed), record.getAmount(), record.getStatus())
```

메서드별 책임은 [02](trade_off/02-service-api.md) 표를 따른다. Service 메서드에는 `@Transactional`을 붙이지 않는다.

## 커밋별 구현 순서

### 커밋 1 — 주문의 상품별 합산 수량

- [x] `OrderTest`(또는 기존 Order 도메인 테스트)에 먼저 추가: 같은 상품 품목 수량 합산, 품목 등장 순서 유지(`LinkedHashMap`), 합산 overflow 시 `DomainException(CALCULATION_OVERFLOW)`.
- [x] `Order.quantitiesByProductId()` 추가(정책의 `aggregateQuantities`·`addExact` 로직을 옮김). 정책은 아직 지우지 않는다.
- [x] 커밋: `refactor: 주문에 상품별 합산 수량 계산을 추가`

### 커밋 2 — Service 메서드 추가

- [x] Service 단위 테스트(Mockito)를 먼저 쓴다. 정책 테스트 10건의 규칙 조합을 해당 Service로 옮긴다.
  - `ProductService.decreaseStocks`: 상품 id 오름차순으로 잠금 조회(`InOrder`), 없는 상품은 `PRODUCT_NOT_FOUND`, 삭제된 상품·재고 부족은 기존 도메인 오류, 품목 등장 순서로 검증, 뒤쪽 품목이 실패하면 앞쪽 재고도 메모리에서 바뀌지 않고 저장도 없음, 성공 시 차감 후 저장.
  - `WalletService.pay`: 잔액 부족이면 `INSUFFICIENT_POINT`이고 저장 없음, 성공 시 잔액 차감·사용 기록 반환·지갑과 사용 기록 저장(현재 상품 가격이 아니라 주문 합계로 결제).
  - `OrderService.lockForConfirm`: 없으면 `ORDER_NOT_FOUND`, 이미 확정된 주문은 기존 상태 오류. `OrderService.confirm`: 확정 후 저장, 주문 기록이 주문과 일치.
- [x] `OrderService.lockForConfirm/confirm`, `WalletService.lockByUserId/pay`, `ProductService.decreaseStocks` 구현. 필요한 저장소 의존을 각 Service에 추가한다(`WalletService`는 이미 `WalletRepository`·`PointBillRepository` 보유).
- [x] 커밋: `refactor: 주문 확정 단계를 주문·지갑·상품 서비스 메서드로 분리`

### 커밋 3 — 파사드

- [x] `ConfirmOrderFacadeTest`(Mockito)를 먼저 쓴다: 위 설계 순서대로 호출(`InOrder`), 주문 단계 실패 시 지갑·상품·결제·확정 미호출, 상품 단계 실패 시 결제·확정 미호출, 결과가 확정된 주문·주문 기록을 담음. 기존 `ConfirmOrderServiceTest`의 "현재 상품 가격이 바뀌어도 저장된 주문 합계로 결제" 케이스를 파사드 또는 `WalletService` 테스트로 옮긴다.
- [x] `application/ordering/facade/ConfirmOrderFacade implements ConfirmOrderUseCase` 추가, `@Service` + `@Transactional`. `ConfirmOrderService`의 `@Service`를 제거하거나 클래스를 이 커밋에서 지워 UseCase 빈이 하나만 남게 한다.
- [x] 기존 `ConfirmOrderIntegrationTest`·`ConfirmOrderSqlRollbackIntegrationTest`·`OrderApiE2ETest`가 기대값 변경 없이 통과해야 한다.
- [x] 커밋: `refactor: 주문 확정 진입점을 서비스 조율 파사드로 전환`

### 커밋 4 — 이전 구조 제거

- [x] 제거: `ConfirmOrderService`(커밋 3에서 남았다면), `ConfirmOrderWriter`, `ConfirmOrderLoad`, `JpaConfirmOrderWriter`, `OrderConfirmationPolicy`, `OrderConfirmation`.
- [x] 테스트 제거: `ConfirmOrderServiceTest`, `JpaConfirmOrderWriterTest`, `OrderConfirmationPolicyTest`(규칙 조합은 커밋 1~3의 테스트로 옮겨졌음을 확인한 뒤).
- [x] `git grep -n "ConfirmOrderWriter\|ConfirmOrderLoad\|OrderConfirmationPolicy\|OrderConfirmation\b\|ConfirmOrderService" apps/commerce-api/src` 결과가 없어야 한다.
- [x] 커밋: `refactor: 주문 확정 Writer 포트와 도메인 정책 제거`

### 커밋 5 — 문서

- [x] `CLAUDE.md`: 패키지 종류에 `facade` 추가, `ConfirmOrderWriter`를 예로 든 문장 정리, 주문 확정 흐름 한 줄 추가. `docs/test/*.md`: 삭제·추가된 테스트 목록과 개수. (R02 01 상단의 R09 후속 결정 안내는 문서 준비 커밋에서 이미 추가함)
- [x] 검증 기록 작성.
- [x] 커밋: `docs: 주문 확정 파사드 전환을 관련 문서에 반영`

## 검증 계획

- 커밋마다 바꾼 테스트 클래스와 관련 주문 확정 테스트만 `--tests`로 실행하고 `checkstyleMain`·`checkstyleTest`를 실행한다. 동시성 테스트(`ConfirmOrderConcurrencyIntegrationTest`, slow 태그)는 커밋 3 이후 `slowTest --tests`로 한 번 실행한다.
- 마지막에 `./gradlew :apps:commerce-api:check`(test 태스크 한 번으로 slow 포함 전체)를 실행하고 건수·실패·skip을 기록한다.
- **중단 조건:** 기존 통합·E2E 테스트의 기대값(특히 오류 코드와 그 우선순위)을 바꿔야만 통과하면 멈추고 보고한다. ArchUnit이 `facade` → 다른 컨텍스트 Service 의존을 막으면 규칙을 고치지 말고 보고한다.

## 완료 체크리스트

- [x] `ConfirmOrderWriter`·`OrderConfirmationPolicy` 제거
- [x] 잠금 순서(주문 → 지갑 → 상품 id 오름차순)를 파사드 단위 테스트로 고정
- [x] 기존 주문 확정 테스트 기대값 변경 없음
- [x] `./gradlew :apps:commerce-api:check` 통과

## 검증 기록

- 커밋 4 이후 `git grep -n "ConfirmOrderWriter\|ConfirmOrderLoad\|OrderConfirmationPolicy\|OrderConfirmation\|ConfirmOrderService" apps/commerce-api/src` 결과 없음(종료 코드 1).
- 커밋 3 이후 `slowTest --tests "*ConfirmOrderConcurrency*"` 6건 통과.
- `./gradlew :apps:commerce-api:check` BUILD SUCCESSFUL: 테스트 271건(100개 결과 파일), 실패 0, 오류 0, skip 0. ArchUnit·Checkstyle 통과(규칙 완화 없음).
- 기존 `ConfirmOrderIntegrationTest`(5), `ConfirmOrderSqlRollbackIntegrationTest`(1), `ConfirmOrderConcurrencyIntegrationTest`(6), `OrderApiE2ETest`(15) 기대값 변경 없이 통과.
- 삭제한 정책 테스트 10건의 이전 위치: 정상 확정·기록 일치는 `ConfirmOrderFacadeTest`·`OrderServiceTest.Confirm`·`WalletServiceTest.Pay`, 합산·overflow는 `OrderTest.QuantitiesByProductId`, 합산 재고 초과·삭제 상품·재고 1 부족·뒤쪽 품목 실패는 `ProductServiceTest.DecreaseStocks`, 이미 확정된 주문은 `OrderServiceTest.LockForConfirm`, 잔액 1 부족은 `WalletServiceTest.Pay`. 잠금·저장 순서(`JpaConfirmOrderWriterTest` 2건)는 `ConfirmOrderFacadeTest`의 `InOrder`와 `ProductServiceTest`의 id 오름차순 잠금·저장 테스트가 대신한다.
