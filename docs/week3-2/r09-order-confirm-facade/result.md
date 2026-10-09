# R09 구현 결과

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [구현 계획](plan.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r09-order-confirm-facade` (R08 브랜치에서 분기, R08 병합(PR #20) 후 main 위로 리베이스, 트리 동일) · PR 대상: `volume-3/main`

상태: 구현·검증 완료. `./gradlew :apps:commerce-api:check`에서 테스트 271건 모두 통과했다(실패·오류·skip 0). Checkstyle·ArchUnit도 규칙 변경 없이 통과했다.

## 1. 구현 결과

주문 확정이 파사드가 주문·지갑·상품 Service를 순서대로 부르는 구조로 바뀌었다. 인프라의 `ConfirmOrderWriter` 포트와 도메인의 `OrderConfirmationPolicy`는 없어졌다.

```
ConfirmOrderFacade.execute(command)  @Transactional          (application.ordering.facade)
  orderService.lockForConfirm(orderId)         주문 FOR UPDATE, 없으면 ORDER_NOT_FOUND, ensureCanConfirm
  walletService.lockByUserId(userId)           지갑 FOR UPDATE
  productService.decreaseStocks(order.quantitiesByProductId())
                                               상품 id 오름차순 FOR UPDATE → 품목 순서로 전부 검증 → 차감 → 저장
  walletService.pay(wallet, order)             wallet.use(주문 합계) → 지갑·사용 기록 저장
  orderService.confirm(order)                  order.confirm() → 저장(주문 기록 cascade)
```

| 영역 | 이전 | 이후 |
|---|---|---|
| 진입점 | `ConfirmOrderService` | `ConfirmOrderFacade`(새 종류 패키지 `facade`) |
| 잠금 조회·저장 | `ConfirmOrderWriter` 포트 + `JpaConfirmOrderWriter` | 각 Service 메서드. 순서는 파사드 호출 순서 |
| 업무 규칙 | `OrderConfirmationPolicy`(전부 검증 → 결제 → 주문) | 각 Service와 도메인 모델. 수량 합산은 `Order.quantitiesByProductId()` |
| 트랜잭션 | `ConfirmOrderService`에만 | 파사드에만. 새 Service 메서드 5개에는 `@Transactional` 없음 |

유지한 것: 잠금 순서(주문 → 지갑 → 상품 id 오름차순), 오류 우선순위(주문 상태 → 상품(품목 등장 순서) → 잔액), 저장 순서(상품 → 지갑 → 사용 기록 → 주문), API 응답.

## 2. 검증

| 시점 | 결과 |
|---|---|
| 커밋 3 이후(에이전트) | `ConfirmOrderIntegrationTest` 5, `ConfirmOrderSqlRollbackIntegrationTest` 1, `OrderApiE2ETest` 15, `slowTest` `ConfirmOrderConcurrencyIntegrationTest` 6 통과 |
| 최종(에이전트) | `check` 271건, 실패·오류·skip 0 |
| 검토 후 재실행(직접) | 테스트 결과를 지우고 `check` 재실행, 102초, 271건 통과 |

- **기존 통합·E2E 테스트는 파일 자체가 바뀌지 않았다**(`git diff`로 확인). 오류 코드·우선순위·금액 기대값이 그대로 통과했다.
- **"잔액 부족 시 재고 유지"는 이제 트랜잭션 롤백으로 보장된다.** 재고 차감이 결제보다 먼저 일어나지만, 기존 통합 테스트 "포인트가 부족하면 재고 차감을 포함해 전체를 롤백한다"가 변경 없이 통과했다.
- 이전 이름(`ConfirmOrderWriter`, `ConfirmOrderLoad`, `OrderConfirmationPolicy`, `OrderConfirmation`, `ConfirmOrderService`)은 운영·테스트 코드에 남지 않았다.

### 옛 정책 테스트 10건의 이전 위치

| 옛 정책 테스트 | 이제 있는 곳 |
|---|---|
| 정상 확정, 재고·잔액 차감, 사용 기록 | `ConfirmOrderFacadeTest`(호출 순서·결과), `OrderServiceTest.Confirm`, `WalletServiceTest.Pay`, `ProductServiceTest.DecreaseStocks` |
| 주문 기록이 주문과 일치, 사용 기록이 결제 영수증 | `OrderServiceTest.Confirm`, `WalletServiceTest.Pay` |
| 같은 상품 품목 합산 후 한 번 차감 | `OrderTest.QuantitiesByProductId` |
| 합산 수량이 재고 초과 / 재고 1 부족 / 삭제 상품 | `ProductServiceTest.DecreaseStocks` |
| 합산 overflow | `OrderTest.QuantitiesByProductId` |
| 이미 확정된 주문 | `OrderServiceTest.LockForConfirm` |
| 뒤쪽 품목 실패 시 앞쪽 재고 유지 | `ProductServiceTest.DecreaseStocks`(저장 없음까지 확인) |
| 잔액 1 부족 | `WalletServiceTest.Pay` |

`JpaConfirmOrderWriterTest`의 잠금·저장 순서 검증 2건은 `ConfirmOrderFacadeTest`의 `InOrder`와 `ProductServiceTest`의 id 오름차순 잠금·저장 테스트가 대신한다.

## 3. 계획과의 차이

| 항목 | 계획 | 실제 | 이유 |
|---|---|---|---|
| `OrderService.confirm` 반환 | 저장 결과 | 메모리의 주문 | 이전 코드도 메모리 주문으로 응답을 만들었다. 응답은 같다 |
| `ConfirmOrderService` 제거 시점 | 커밋 3 또는 4 | 커밋 3에서 `@Service`만 떼고 커밋 4에서 삭제 | UseCase 빈을 하나로 유지하면서 커밋 단위를 지킴 |
| "현재 가격이 바뀌어도 저장된 합계로 결제" 케이스 | 파사드 또는 `WalletService` 테스트 | `WalletServiceTest.Pay` | 결제 금액을 정하는 곳이 `pay` |
| 문서 | CLAUDE.md, docs/test | AGENTS.md도 수정, `docs/test/infrastructure.md`의 `dao` 절 삭제(유일한 행이던 `JpaConfirmOrderWriterTest` 삭제) | 이전 구조 언급 정리 |

## 4. 한계

- **메모리 수준의 "전부 검증 후 변경"은 없어졌다.** 잔액이 부족하면 재고를 먼저 차감한 뒤 결제에서 실패한다. DB 정합성은 파사드 트랜잭션의 롤백이 보장하고 통합 테스트가 검증하지만, 파사드 밖에서 Service 메서드를 따로 조합하면 이 보장이 없다.
- **Service 메서드는 트랜잭션 안에서만 써야 한다.** 잠금 조회는 트랜잭션이 없으면 실패한다. `MANDATORY`로 강제하지 않았다(R02 03 결정 유지).
- **잠금 순서가 두 곳에 나뉜다.** 파사드 호출 순서와 `decreaseStocks`의 id 정렬이다. 단위 테스트(`InOrder`)로 고정했다.
- **`docs/test` 일부 개수는 계산값이다.** application 문서의 합계는 테스트 결과에서 다시 셌지만, domain·infrastructure 문서의 클래스·케이스 수는 삭제한 테스트를 빼서 계산했다(에이전트 보고).

## 5. 회고

- **목적을 먼저 정하니 추천이 바뀌었다.** 처음에는 R02 결정을 따라 도메인 정책 유지를 추천했다. 사용자가 목적(책임 분리·Writer 제거·재사용)을 정한 뒤 다시 비교하자, 정책을 남기면 Service가 조회·저장 래퍼가 되어 목적과 어긋났다. 한 트랜잭션 안에서는 "전부 검증 후 변경"이 DB 결과를 바꾸지 않는다는 점이 해체의 근거가 됐다.
- **보존할 것을 계획에 못 박았다.** 잠금 순서·오류 우선순위·저장 순서를 계획과 위임 지시에 명시하고 기존 통합 테스트를 기대값 그대로 두게 해, 구조를 크게 바꾸고도 기존 테스트 파일을 한 줄도 고치지 않았다.
