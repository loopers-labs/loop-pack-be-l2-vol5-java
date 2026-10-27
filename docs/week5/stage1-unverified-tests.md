# 5주차 1단계-A · 실행 못 한 테스트 목록과 추후 검증 절차

- 작성일: 2026-10-23
- 용어: **1단계-A** = 테스트 먼저 작성(이 문서), **1단계-B** = 1단계 구현, **2단계** = callback·reconcile·스케줄러, **3단계** = 실제 HTTP adapter
- 상태: **미검증.** 1단계-A(테스트 + 컴파일용 껍데기) 코드는 작성했으나, 작업 PC 에 Docker 가 없어 MySQL·Redis Testcontainers 가 필요한 테스트를 실행하지 못했다.
- 이 문서의 목적: Docker 가 있는 환경에서 무엇을 어떤 순서로 돌리고, 어떤 결과가 나와야 "의도한 대로 실패(Red)"인지 기록한다. 1단계-B 에서 정리할 기존 테스트·삭제 대상·빈 등록 계획도 여기에 둔다.
- 관련 문서: 설계 `docs/week5/commerce-payment.md` (1장 TX 경계, 4장 재요청 응답, 5장 TX B 실패, 8장 상태 모델)

---

## 1. 이미 확인한 것 (Docker 없이 가능)

| 검증 | 명령 | 결과 |
|---|---|---|
| 컴파일 | `./gradlew :apps:commerce-api:compileJava :apps:commerce-api:compileTestJava` | 통과 |
| Checkstyle | `./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest` | 통과 |
| ArchUnit | `./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'` | 통과 |
| 마스킹 단위 테스트 3건 | `--tests '*OrderV1DtoTest' --tests '*OrderCommandTest' --tests '*PaymentCreateCommandTest'` | 통과 (데이터 모델 코드라 1단계에서도 초록) |

## 2. 실행 못 한 테스트 (19건 + 기존 테스트)

### 2-1. 새 테스트 19건

1단계-A 껍데기는 행동 메서드가 전부 `UnsupportedOperationException("1단계 미구현")` 을 던진다. 아래 "기대 실패 사유"가 그대로 나오면 Red 가 맞다. **컴파일 오류·컨텍스트 로딩 오류로 실패하면 잘못된 것**이다.

| # | 클래스 · 메서드 | 기대값 요약 | 1단계 기대 실패 사유 |
|---|---|---|---|
| 1 | `OrderPaymentIntegrationTest.FirstConfirm.startsPgPaymentAndSettlesOnSuccess` | create 1회 → 시도 PENDING+key → TX B SUCCESS → 주문 CONFIRMED·PG, 재고 5→3 유지, 예약 CONSUMED, 포인트 변화 0 | `confirm` 이 UOE |
| 2 | `...FirstConfirm.rejectsWithoutPgCall_whenStockIsInsufficient` | CONFLICT "재고가 부족합니다.", DRAFT 유지, 시도·예약 0, create 0 | UOE 는 `CoreException` 이 아니라 `assertThrows` 실패 |
| 3 | `...FirstConfirm.createsOneAttemptAndCallsPgOnce_whenSameKeyConfirmsConcurrently` | 같은 키 8개 동시: 시도 1, 예약 1, create 1, 재고 3 | 8개 future 전부 UOE → "오류 0건" 실패 |
| 4 | `OrderConfirmV1ApiE2ETest.Reconfirm.returnsAcceptedWithStoredState_whenAttemptIsInProgress` | 202 + orderStatus/attemptStatus/links, 재요청도 202, create 1 | 500 ≠ 202 |
| 5 | `...Reconfirm.returnsOkWithStoredResult_whenAttemptIsSettled` | 200 + CONFIRMED/SUCCESS, create 1, 응답에 key·카드번호 없음 | 첫 호출이 500 |
| 6 | `OrderPaymentIntegrationTest.Reconfirm.rejectsConflict_whenDifferentKeyIsUsedForSameOrder` | 다른 키: CONFLICT, create 1 유지 | arrange 의 `confirm` 이 UOE |
| 7 | `...Reconfirm.rejectsConflict_whenSameKeyIsUsedForDifferentRequest` | 같은 키를 다른 주문에: CONFLICT, 둘째 주문 DRAFT, create 1 | UOE |
| 8 | `...CreateOutcome.keepsReservationAsUnknown_whenCreateResponseIsLost` | 시도 UNKNOWN·key 없음, 예약 RESERVED, 재고 3, 가짜 PG 거래 1, create 1 | UOE |
| 9 | `...CreateOutcome.failsAndReleasesOnce_whenCreateIsRejectedBeforeCreate` | 거래 0, 시도 FAILED·REJECTED_BEFORE_CREATE·key 없음, 주문 PAYMENT_FAILED, 재고 5, 예약 RELEASED | UOE |
| 10 | `PaymentResultServiceIntegrationTest.DuplicateApply.releasesOnlyOnce_whenSameFailedResultIsAppliedTwice` | true/false, 재고 5 (7 아님), 예약 RELEASED 1회 | `applyFinal` 이 UOE |
| 11 | `...DuplicateApply.appliesOnlyOnce_whenSameResultIsAppliedConcurrently` | 동시 8개 중 true 정확히 1개, 재고 5 | UOE |
| 12 | `...AfterSettlement.doesNotOverwrite_whenFailedArrivesAfterSuccess` | false, SUCCESS·CONFIRMED·CONSUMED·재고 3 유지 | UOE |
| 13 | `PaymentResultServiceFailureTest.rollsBackWholeTxB_andAppliesOnceOnRetry_whenReservationUpdateFailsFirst` | ⑧ 첫 호출만 예외 → 전부 rollback(PENDING·PAYMENT_PENDING·RESERVED·재고 3) → 재적용 시 CONFIRMED 1회, create 0 | UOE 는 주입한 `IllegalStateException` 이 아니라 `assertThrows` 실패 |
| 14 | `...AfterSettlement.ignoresLateCreateResponse_whenAlreadySettled` | SUCCESS 뒤 `recordPending`·`recordUnknown` → 0행, SUCCESS·key 유지 | UOE |
| 15 | `OrderPaymentIntegrationTest.ExistingRulesPreserved.rejectsAlreadyConfirmed_whenOrderWasPaidWithPoint` | "이미 확정된 주문입니다.", create 0, paymentMethod POINT 유지 | UOE |
| 16 | `...ExistingRulesPreserved.rejectsNotFound_whenRequesterIsNotOwner` | NOT_FOUND 기존 문구, DRAFT 유지, create 0 | UOE |
| 17 | `...ExistingRulesPreserved.neverStoresOrLogsCardNumber` | information_schema 컬럼·저장 행·`com.loopers` 로그 어디에도 `0000-0000-0000-0000` 없음 | act 가 UOE |
| 18 | `OrderPaymentIntegrationTest.CardInput.rejectsBadRequest_whenCardTypeIsNotTest` | cardType `VISA` → BAD_REQUEST, DRAFT 유지, 시도·예약 0, create 0 | UOE 는 `CoreException` 이 아니라 `assertThrows` 실패 |
| 19 | `...CardInput.rejectsBadRequest_whenCardNumberIsNotAllowedTestNumber` | 카드 번호 `1111-1111-1111-1111` → BAD_REQUEST, DRAFT 유지, 시도·예약 0, create 0 | UOE |

카드 허용 목록(1단계-B 에서 구현): `cardType == "TEST"` 이고 카드 번호가 허용 목록(`0000-0000-0000-0000`)에 있을 때만 통과. 형식 검사가 아니라 목록 일치다. 그 외는 기존 `ErrorType.BAD_REQUEST`.

### 2-2. 깨질 것으로 예상하지만 실제 실패를 확인하지 못한 기존 테스트 (1단계-B 수정 계획)

호출부만 새 시그니처로 바꾸고 기대값은 그대로 두었다. 1단계-A 에서는 UOE(또는 E2E 는 본문 없는 POST 로 400)로 실패해야 한다. 고치는 것은 1단계-B 다.

| 테스트 | 왜 깨지는가 | 1단계-B 예정 |
|---|---|---|
| `OrderFacadeIntegrationTest` 2건 | 포인트 잔액·즉시 CONFIRMED 기대 | 1건 삭제(새 테스트 1 이 대체), 1건 TX A 롤백 검증으로 재작성 |
| `OrderFacadeTest.ConfirmOrder` 6건 | `pointService.deduct`·옛 협력자 스텁 | 포인트 2건 삭제, 재고 2건 `PaymentStartService` 테스트로 이동, 2건 재작성 |
| `OrderV1ApiE2ETest.ConfirmOrder` 6건 | 본문 없음(400), 잔액·즉시 CONFIRMED 기대 | 포인트 부족 1건 삭제, 나머지 본문 추가·202 기준 재작성 |
| `OrderConcurrencyTest.ConfirmOrderConcurrently` 3건 | 포인트 경쟁·즉시 CONFIRMED | 포인트 2건 삭제, 재고 경쟁 1건 PAYMENT_PENDING 5·create 5 로 재작성 |
| `ProductCacheFailureTest.ordersUseDbPrice_andStockIsFresh_whileOldPriceIsShown` | `paidAmount` 는 TX B 뒤에만 채워짐 | confirm 뒤에 가짜 PG `markSuccess` + `PaymentResultService.applyFinal(SUCCESS)` 를 추가하고 `paidAmount` 단언은 그대로 둔다. **`totalAmount` 로 바꾸지 않는다** ("결제된 금액" 의미 유지). 이 테스트 클래스에 `@Import(FakePaymentGatewayConfig.class)` 추가 |
| `ProductCacheInvalidationTest.confirmOrder` | TX A 만으로 재고 98 은 유지되어 통과할 가능성 있음 | Docker 환경에서 확인 후 결정. 확정 의미를 맞추려면 위와 같이 `applyFinal(SUCCESS)` 추가 |
| `ProductCacheInvalidationTest.keepsOrderAmounts_afterPriceChange` | `confirmed.paidAmount()` 의존 | 위와 같이 `applyFinal(SUCCESS)` 추가, `paidAmount` 단언 유지, `@Import(FakePaymentGatewayConfig.class)` 추가 |
| `OrderServiceTest.ConfirmOrder` 2건, `PointServiceTest.Deduct` 3건, `PointTest` 의 차감 금액 검증 1건 | 1단계-A 는 통과. 아래 2-3 삭제 연쇄에 포함 | 삭제 |

### 2-3. 1단계-B 삭제 대상 (사용처 확인 완료, 2026-10-23)

main 코드에서 사용처를 확인한 결과다. **다른 사용처가 없으므로** 1단계-B 에서 아래를 함께 삭제한다. 삭제 전에 같은 grep 을 한 번 더 돌려 사용처가 생기지 않았는지 확인한다.

| 삭제 대상 | main 사용처 | 함께 삭제하는 테스트 |
|---|---|---|
| `PointService.deduct` | 없음 (유일 호출자였던 `OrderFacade.confirmOrder` 는 교체됨) | `PointServiceTest.Deduct` 3건, `OrderFacadeTest` 의 `pointService` 목·검증 |
| `PointRepository.deductIfEnough` + `PointRepositoryImpl` 구현 | `PointService.deduct` 만 | (위에 포함) |
| `Point.validateDeductAmount` | `PointService.deduct` 만 | `PointTest` 의 차감 금액 검증 1건 (L88 근처), `Point.java` L58 주석 |
| `OrderService.confirmOrder` | 없음 | `OrderServiceTest.ConfirmOrder` 2건, `OrderFacadeTest` 의 `orderService.confirmOrder` 스텁 |
| `OrderRepository.confirmIfDraft` + `OrderRepositoryImpl` 구현 | `OrderService.confirmOrder` 만 | (위에 포함). `Order.java` L54, `OrderTest.java` L71 주석을 `finishIfPaymentPending` 기준으로 고친다 |
| `OrderRepository.reload` + 구현 | `OrderService.confirmOrder` 만 | (위에 포함) |

`PointService.charge`·조회와 `PointV1ApiE2ETest` 는 과제 요구대로 유지한다.

### 2-4. PG 포트 빈 등록 계획 (1단계-B 에서 구현)

확인 결과: 기존 통합 테스트 29개는 공통 기반 클래스·공통 애너테이션 없이 각자 `@SpringBootTest` 를 붙인다. 공통 기반이 없으므로 **운영 코드에 "호출하면 예외를 던지는" `PaymentGateway` 껍데기**를 둔다.

- `infrastructure/payment/UnavailablePaymentGateway` (`@Component`): 세 메서드 모두 `IllegalStateException("PG adapter 가 설정되지 않았다")` 를 던진다. 가짜를 넣지 않은 테스트가 실수로 PG 를 부르면 크게 실패해 드러난다. 운영 기동(`bootRun`)도 빈이 있어 뜬다.
- `FakePaymentGatewayConfig.fakePaymentGateway()` 에 `@Primary` 를 붙인다. `@Import` 한 테스트에서는 포트 구현이 둘이 되므로 가짜가 이긴다.
- `OrderFacade` 가 `PaymentGateway` 를 주입받는 시점(1단계-B)에 위 둘을 같이 넣는다.
- **3단계**: 실제 HTTP adapter `@Component` 를 추가하면서 `UnavailablePaymentGateway` 를 삭제한다. 가짜는 `@Primary` 그대로라 `@Import` 한 테스트에서만 진짜를 가리고, 나머지 컨텍스트는 진짜 adapter 를 쓴다.

## 3. Docker 가 있는 환경에서 할 일

전제: Docker 데몬이 떠 있어야 한다 (`docker info` 가 성공). 테스트는 `mysql:8.0`·`redis:latest` 컨테이너를 자동으로 띄운다.

```bash
# 1) 새 테스트 19건 — 전부 "미구현" 사유로 빨강이어야 한다
./gradlew :apps:commerce-api:test \
  --tests '*OrderPaymentIntegrationTest' \
  --tests '*PaymentResultService*Test' \
  --tests '*OrderConfirmV1ApiE2ETest'

# 2) 기존 테스트 — 2-2 목록만 빨강이고, 그 외(상품·브랜드·좋아요·관리자 등)는 초록이어야 한다
./gradlew :apps:commerce-api:test
```

확인 체크리스트

- [ ] 19건이 전부 실패하고, 실패 원인이 `UnsupportedOperationException: 1단계 미구현` 이거나 그로 인한 500·`assertThrows` 불일치다
- [ ] 컨텍스트 로딩 실패(`IllegalStateException ... DockerClientProviderStrategy`, `BeanCreationException` 등)가 **없다**
- [ ] 2-2 목록 밖의 기존 테스트는 전부 초록이다. 특히 Fake 를 `@Import` 하지 않은 기존 `@SpringBootTest` 컨텍스트가 정상으로 뜬다 (1단계-A 의 `OrderFacade` 는 `PaymentGateway` 를 주입받지 않으므로 성립해야 한다. 1단계-B 부터는 2-4 의 `UnavailablePaymentGateway` 가 그 역할을 맡는다)
- [ ] `ProductCacheInvalidationTest.confirmOrder` 가 실제로 깨지는지 확인하고 2-2 표를 갱신한다
- [ ] 결과(통과/실패 수, 실패 사유)를 이 문서 아래 "4. 실행 기록"에 적는다

실행하지 않은 테스트를 통과로 보고하지 않는다. `@Disabled`·H2 전환 같은 우회도 하지 않는다.

## 3-1. 1단계-B 구현 뒤 추가된 확인 사항 (2026-10-23)

1단계-B 구현이 끝났다. Docker 환경에서는 **1단계-A 19건이 초록**이어야 하고, 2-2 표의 기존 테스트도 고친 기대값으로 초록이어야 한다.
이 PC 에서 통과한 것: compileJava·compileTestJava·checkstyleMain·checkstyleTest·ArchitectureTest·DB 없이 도는 단위 테스트 91건
(PaymentStartServiceTest 9, PaymentResultServiceTest 4, PaymentCardTest 5, OrderTest 9, OrderServiceTest 8, OrderFacadeTest 9, PointTest 4, PointServiceTest 4, ProductServiceTest 22, StockTest 6, OrderItemTest 5, 마스킹 3, ArchUnit 3).

### 실패가 나오면 먼저 의심할 "테스트 장치" 쪽 목록

구현 문제와 구분하기 위해, 아래는 테스트 장치(컨텍스트 기동·@Import·fixture 정리·동시 실행 헬퍼) 쪽 후보다.

| # | 의심 지점 | 증상 | 확인 방법 |
|---|---|---|---|
| 1 | `@MockitoSpyBean StockReservationRepository` (`PaymentResultServiceFailureTest`) | 컨텍스트 기동 실패 또는 spy 가 `PaymentResultService` 에 주입되지 않아 1차 `applyFinal` 이 예외 없이 성공 | 실패 로그에 `BeanOverride`·`MockitoSpyBean` 이 있으면 장치 문제. spy 대신 `@TestConfiguration` + `@Primary` 로 감싼 구현으로 바꿀 수 있다 |
| 2 | `@Import(FakePaymentGatewayConfig)` + `@Primary` 와 운영 `UnavailablePaymentGateway` 공존 | `NoUniqueBeanDefinitionException` 또는 `IllegalStateException: PG adapter 가 설정되지 않았다` | 전자는 `@Primary` 누락, 후자는 해당 테스트 클래스에 `@Import` 누락 |
| 3 | 가짜 PG 상태가 테스트 간에 남음 (컨텍스트 캐시로 같은 인스턴스 공유) | `createCount` 가 기대보다 큼, `transactions()` 에 다른 테스트 거래가 섞임 | `@BeforeEach fakePaymentGateway.reset()` 이 있는지. `ProductCache*Test` 두 클래스는 reset 을 넣지 않았다(createCount 를 단언하지 않음) |
| 4 | `ConcurrentRunner` (ready/start 래치, 30초 timeout) | 기술 오류로 `TimeoutException` | 커넥션 풀(test 프로필 최대 10) 안에서 8 스레드가 주문 행 락을 기다린다. 풀 고갈이면 N 을 줄여 본다 |
| 5 | `DatabaseCleanUp.truncateAllTables()` | 다음 테스트에서 UNIQUE(order_id)·UNIQUE(user_id, attempt_key) 위반 | `payment_attempts`·`stock_reservations` 가 TRUNCATE 목록에 들어가는지(둘 다 `@Entity` + `@Table`) |
| 6 | 테스트 17 의 로그 캡처 (`ListAppender` on `com.loopers`) | 기대와 다른 로그 메시지로 실패 | 캡처 범위는 `com.loopers` 만이다. Hibernate SQL 은 stdout 이라 잡히지 않는다 |
| 7 | `OrderV1ApiE2ETest`·`OrderConfirmV1ApiE2ETest` 의 `ApiResponse<ConfirmResponse>` 역직렬화 | `data` 가 null 이거나 `links` 가 null | record 역직렬화 설정. 다른 E2E 와 같은 방식이라 가능성은 낮다 |
| 8 | `OrderFacadeIntegrationTest` 의 CHECK 제약 주입 | `ALTER TABLE ... DROP CHECK` 실패로 다음 테스트 오염 | 제약 추가 전 실패 시 `@AfterEach` 의 DROP 이 먼저 터진다. MySQL 8.0.16+ 만 CHECK 를 강제한다 |

### 실패가 나오면 "구현" 쪽으로 봐야 할 것

- 테스트 3·11(동시성): 시도 2건, create 2회, `true` 2개 → 잠금·조건부 UPDATE 문제
- 테스트 9·10: 재고 7 → 예약 반환이 두 번 일어남
- 테스트 12·14: SUCCESS 가 덮어써짐 → ⑥ 문지기 WHERE 조건 문제
- 테스트 13: 1차 실패 뒤 시도가 SUCCESS 로 남음 → ⑥⑦⑧ 이 한 TX 가 아님
- `findByIdForUpdate` 가 락을 걸지 않음 → 테스트 3 에서 시도 2건 또는 UNIQUE 위반 예외

## 4. 실행 기록

| 날짜 | 환경 | 명령 | 결과 |
|---|---|---|---|
| 2026-10-23 | 작업 PC (Docker 없음) | `--tests '*PaymentResultServiceIntegrationTest'` | 4건 실패. 원인은 컨텍스트 로딩 단계 `DockerClientProviderStrategy` IllegalStateException. **의도한 실패가 아니므로 미실행으로 분류** |
| | | | |