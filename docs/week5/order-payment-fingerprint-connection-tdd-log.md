# W5 HMAC 주문 처리 연결 TDD 실행 기록

[선행 키 설정](payment-fingerprint-key-configuration-tdd-log.md) · [선행 지문 저장](payment-request-fingerprint-persistence-tdd-log.md) · [정책](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-제안) · [TDD 계획](../week2/commerce-tdd-plan.md#hmac-요청-지문의-검증-계획) · [완료 체크리스트](../week2/commerce-completion-checklist.md)

## 이번 증분의 범위

사용자 요청에 따라 HMAC을 **내부 주문 처리**에 연결했다. 신규 결제는 TX A에서 지문·두 버전·주문 대기·예약·발송 시작 기록을 함께 저장하고, 같은 시도의 재요청은 저장된 지문으로 비교한다. 사용자 DB에는 접근하지 않고 격리 Testcontainers MySQL과 로컬 HTTP fixture, 합성 카드·합성 키만 사용한다.

**2026-10-23 16:08:14 KST 결과 확인: 신규 44개를 포함한 관련 42개 스위트·798개 통과, 실패·오류·skip 0.** ArchUnit 1개 통과, Checkstyle main/test 보고서 2개·위반 0. BUILD SUCCESSFUL·1분 8초·종료 0이다. 이 문서는 고객 confirm API의 PG 전환, callback·180초 제한 조회·회로·W5 전체 완료를 뜻하지 않는다.

| 책임·파일 | 변경 |
| --- | --- |
| [OrderPaymentStockService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentStockService.java) | `PaymentRequestFingerprinter` 주입, 신규 지문 공동 저장, `prepareOrReuse`의 소유권 우선·저장 지문 비교·현재 상태 반환 |
| [OrderPaymentCoordinator](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentCoordinator.java) | `startOrReuse`에서 신규 실행만 TX A 커밋 후 PG 생성. 동일 재요청은 저장 결과만 반환. 기존 strict `startNew`와 발송 경계 공유 |
| [PaymentRequestConflictException](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentRequestConflictException.java) | 다른 요청/시도의 내부 충돌. PG FAILED·비교 불가와 구분하며 요청 원문·다른 주문 정보·원인 예외를 넣지 않음 |
| [PaymentAttemptRepositoryImpl](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PaymentAttemptRepositoryImpl.java) | 전역 UUID의 명명된 유일 제약 충돌만 위 예외로 변환. 다른 무결성·잠금 오류는 기술 오류 그대로 전파 |

기존 `prepareNew`는 신규 DRAFT 전용의 엄격한 진입점으로 유지하되 카드 입력 두 인자를 받고 지문을 반드시 저장한다. 신규·반복 요청을 함께 처리할 때는 `startOrReuse` → `prepareOrReuse`를 사용한다. 무지문으로 신규 결제를 준비하는 서비스 overload는 남기지 않는다. PaymentAttempt의 5인자 생성자는 과거 NULL 기록의 fixture·호환을 위해 남기며 서비스 신규 준비에서는 6인자 생성자만 사용한다.

## 트랜잭션·잠금과 요청 판단

| 경계 | 처리·보존 |
| --- | --- |
| 신규 TX A | 주문 소유권·DRAFT·저장 수량/금액·카드/키·삭제·재고 검증. 저장 주문의 사용자·주문·금액과 카드로 지문 계산 후 예약·시도·지문·발송·재고·revision 공동 commit/rollback |
| 신규 잠금 | 기존 `REQUIRED`·`READ_COMMITTED`, 주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순 → 시도 → revision 유지. 다른 주문의 UUID 선조회는 비잠금이며 최종 보호는 DB 유일 제약 |
| 동일 시도 재요청 TX | 주문 → 시도 잠금, 소유권·식별 관계·상태 일관성을 확인하고 저장된 형식/키 버전으로 비교. 상품의 현재 가격·삭제·재고를 재검증하거나 재차감하지 않음 |
| TX 밖 HTTP | 신규 발송 기록을 커밋한 실행만 create. 조율 메서드는 활성 외부 TX를 거절. 동일 요청은 추가 create/lookup·최초 요청의 긴 대기를 시작하지 않음 |
| 결과 TX B | 기존 검증·종결 보존·예약 소비/반환·revision 원자성 유지. HMAC은 PG 결과의 진위를 확인하는 장치가 아님 |

동일한 카드라도 주문 UUID가 다르거나, 같은 UUID를 다른 주문에 쓰면 충돌이다. 같은 주문·키라도 카드 종류/번호가 달라지면 종결 후에도 충돌이다. 같은 내용은 관찰 시점의 NOT_OBSERVED/PENDING/UNKNOWN/SUCCESS/FAILED 저장 결과를 반환한다. 최초 HTTP 응답을 영구 복제하지 않는다.

기존 NULL 지문·미지원 형식/키·비밀키 누락은 **비교 불가**로 남기며 새 지문으로 덮어쓰거나 결제 실패/예약 반환으로 처리하지 않는다. `refreshOnce`는 카드·지문 비교 없이 기존 식별자로 권위 조회할 수 있다. POINT 재확정과 타인 소유권 검사는 PG 카드·키 필수 검사보다 먼저 처리한다. 고객 오류 HTTP/code/message는 이번에 추가로 정하지 않았다.

## TDD 과정

| 단계 | 실제 관찰 |
| --- | --- |
| Red 1 | 새 호출·충돌 타입이 없는 상태로 테스트를 먼저 작성. 컴파일 오류 25건, 테스트 본문 미실행 |
| Red 2 | 연결 전 동작을 유지하는 최소 진입점에서 신규 36개 실행, 26개 실패·10개 통과. 지문 저장·재사용·내용 충돌·키 불가·UUID 오류 변환의 실패 확인 |
| 첫 구현 검사 | 동시성 4개를 추가한 40개 실행에서 38개 통과·2개 실패. 두 실패는 DB 예외 번역 바깥에서 SQL 오류를 세던 위치와 테스트용 IllegalStateException의 Spring 예외 번역 때문 |
| 검증 보강 | SQL 오류는 JPA 저장 안쪽에서 관찰하고, 롤백은 번역되지 않는 전용 주입 예외로 확인. UNIQUE 충돌·공동 rollback 기대값은 그대로 유지. 새 조율의 외부 TX 거절·최초 DRAFT 동시 진입도 추가 |
| 실제 프록시 경합 Red | 보강한 44개 중 1개 실패. 실제 저장소의 요청 충돌 예외가 IllegalStateException 상속 때문에 Spring에 의해 DB 사용 오류로 다시 번역되는 구현 문제를 확인 |
| Green | 충돌 타입을 RuntimeException 직접 상속으로 수정해 unchecked rollback은 유지하고 업무 충돌 의미를 보존. 신규 **44개 모두 통과**, Checkstyle main/test 통과·26초·종료 0 |
| 기존 회귀 Red·수정 | 저장 금액/수량 손상 기존 3개 중 음수 금액 1개가 실패했다. 새 카드 검증이 저장 금액까지 고객 입력으로 분류하던 문제를 확인하고, 일시 입력만 검증한 뒤 실제 금액은 기존 Order의 저장 상태 검사에 맡기도록 수정했다. 기존 IllegalStateException 기대값은 바꾸지 않음 |

기존 테스트 7개에는 카드 입력과 합성 키 `@TestBean`만 추가해 기존 결과·잠금·롤백 기대값을 유지했다. 기존 영속성 유일 제약 테스트는 UUID 중복만 새 명시적 충돌 타입을 기대하도록 연결했고, 다른 제약은 여전히 `DataIntegrityViolationException`을 요구한다. 유일성 검사나 실패 기대를 삭제·완화하지 않았다. `PaymentAttemptKeyConflictTest`가 정확한 제약명 변환과 다른 DB 오류의 전파를 별도로 검증한다.

## 검증 장면

| 테스트 | 핵심 증거 |
| --- | --- |
| [OrderPaymentFingerprintIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentFingerprintIntegrationTest.java) | 실제 PG 생성 후 32바이트 지문/두 버전/발송/예약 저장, 상태별 동일 재요청의 추가 HTTP 0, 카드/키 충돌, 삭제·가격 변경 후 저장 금액 사용, NULL/버전 불가·카드 없는 복구 |
| [OrderPaymentFingerprintKeyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentFingerprintKeyIntegrationTest.java) | 같은 DB에서 계산기 키 버전 변경·조율 객체 재생성 후 과거 지문 유지, 새 주문만 새 키 사용, 키 불가 신규 준비 무효과, 이전 POINT·소유권 우선, 외부 TX 거절 |
| [OrderPaymentFingerprintConcurrencyTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentFingerprintConcurrencyTest.java) | 실제 HTTP 대기 중 동일/변경 재요청, 독립 연결의 전역 UUID UNIQUE 경합·패자 보존, 실제 지문/재고/revision SQL 후 실패·독립 연결 재조회, 최초 DRAFT 동시 시작 |
| [PaymentAttemptKeyConflictTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentAttemptKeyConflictTest.java) | UUID 제약만 충돌로 변환, 다른 제약·잠금 오류는 원래 기술 오류 유지 |

통합 테스트는 전체를 테스트 트랜잭션 하나로 감싸지 않는다. 준비 데이터를 커밋하고 프록시 서비스 호출이 끝난 뒤 DB를 다시 읽는다. 테스트 worker·HTTP 서버에는 유한 대기와 종료가 있고, worker가 끝난 다음에만 테스트 DB를 정리한다. 실제 사용자 DB·Docker 기존 데이터/볼륨은 변경하지 않는다.

키는 test bean으로 대체하므로 사용자 환경변수의 키 공급 bean을 생성하지 않는다. 키 교체 검증은 같은 DB·교체 가능한 합성 계산기·새 조율 객체를 사용한 검증이며 실제 서버 프로세스 재시작/다중 서버 배포 검증은 아니다. 카드 비노출 검사는 합성 값으로 수행하며 고객 HTTP DTO·전체 운영 로그 경로의 검증 완료를 뜻하지 않는다.

## 실행 결과와 명령

최종 실행은 신규 테스트 44개(24+6+6+8)를 포함한 798개다. 신규 검사 수를 이전 전체 검사 수에 더해 전체 프로젝트 통과라고 주장하지 않는다. 전체 `check`와 W4 장시간 성능 측정은 이번에 재실행하지 않았다. 기존 주문·결제 모델/저장/HTTP·동시성, 관리자 예약 반환 여유·상품 변경, 브랜드 삭제 경합/rollback, 고객·관리자 주문 API와 ArchUnit을 선택 실행했다.

신규 Green 명령:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*OrderPaymentFingerprint*Test' \
  --tests '*PaymentAttemptKeyConflictTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

최종 관련 검사 명령:

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.application.order.*' \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.domain.payment.*' \
  --tests 'com.loopers.infrastructure.payment.*' \
  --tests '*AdminReservedStockCapacityTest' \
  --tests '*ProductReservedStockTest' \
  --tests '*AdminProductServiceIntegrationTest' \
  --tests '*BrandRemovalConcurrencyTest' \
  --tests '*BrandRemovalTransactionTest' \
  --tests '*OrderV1ApiE2ETest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

[HTML 테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)는 다음 실행으로 덮어써질 수 있다. 작업 시작 시 해시와 비교해 기존 HMAC 계산/키 설정, PaymentAttempt/Order/상품 모델, SQL 001~011, 고객 controller, application 설정과 빌드 설정을 바꾸지 않았음을 확인했다. 파일 삭제도 없다.

## 남은 범위

고객 confirm/reconcile·조회 DTO·callback ACK/검증, 고객 충돌·비교 불가 오류 계약, 최초 요청 180초 제한 조회·회로와 최종 전체 회귀는 별도다. 기존 고객 confirm API는 아직 POINT 경로이며, 이 증분의 내부 서비스 검증으로 고객 API PG 교체를 완료 처리하지 않는다. 실제 비밀키 공급·사용자 DB 이행·커밋·PR은 수행하지 않는다.
