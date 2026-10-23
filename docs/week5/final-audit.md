# 5주차 병합 후 최종 점검

2026-10-23 기준으로 원문 두 개, 사용자와 확정한 변경 사항, 병합된 코드와 검증 기록을 대조했다.

**점검한 범위에서 빠진 필수 기능은 찾지 못했고, 발견한 재고 반환 캐시의 검증 공백도 commit·rollback 테스트2건으로 보완했다.** 기능 코드 수정 없이 기존 캐시 통합 검사16건과 ArchUnit8건, 테스트 Checkstyle을 통과했다.

[전체 요구사항](total_requirement.md) · [원문](reference/implementation-quest.md) · [전체 상태 흐름](states/total.md)

## 최신화와 검증 기준

- R01 [PR #24](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/24), R02 [PR #25](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/25), R03 [PR #26](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/26)가 모두 병합됐다.
- 로컬 `volume-5/main`을 원격과 같은 `b70d83b1a626a2949cca39b2f4c8efe35ea6afc7`까지 fast-forward했다.
- 병합 커밋의 전체 파일 트리는 검증을 마친 PR03 HEAD `8e2e92f`와 같다. 기능 코드 기준은 `ae46776`이다.
- 기존 전체 검사 XML을 다시 집계했다. PG25＋commerce484＝**509건**, 실패·오류·건너뛰기0이다. 당시 전체 check는 Checkstyle·ArchUnit·slow/example 포함, 종료 코드0이었다.
- 최초 요구사항 점검에서는 병합으로 코드·설정이 달라지지 않아 기존 증거를 대조했다. 이후 사용자 요청으로 캐시 반환 테스트2건을 추가하고 아래 관련24건과 테스트 Checkstyle을 실행했다. 전체 테스트와 실서버 실험은 반복하지 않았다.
- 이전 독립 서버 실험은 R02의10건과 R03의14건이다. 전체509건과 별개이며 과거 모듈 검사384/432건을 다시 합산하지 않는다.

## 원문 Checklist 대조

| 요구사항 | 현재 코드에서 확인한 동작 | 판단·검증 근거 |
|---|---|---|
| 미확정 주문은 PG 사용·포인트 이중 차감 금지 | confirm이 준비→PG 생성→관찰 저장→공통 업무 반영을 호출한다. 신규 결제 흐름에 Wallet 결제를 호출하지 않는다 | 완료. ConfirmOrderIntegrationTest, R02/R03 실험의 USE0 |
| 과거 POINT·품목·금액·소유권 보존 | POINT 증분 SQL과 기존 주문 품목 snapshot을 유지한다. 고객 상세/확정/대사는 본인 주문만 허용한다 | 완료. OrderApiE2ETest, 과거 POINT 주문42의 실제 GET/기록 보존 |
| 접수·처리 중·성공·실패·미확인 구분 | 주문 상태, 결제 결과/거절/충돌, 복구 진행을 별도 표현한다. PENDING 수락을 완료로 바꾸지 않는다 | 완료. PaymentAttemptTest, 상태/조회/API 검사 |
| 시도·발송·차감의 DB 중복 방지·HTTP 동안 잠금 해제 | 주문 CAS와 시도/주문 유일 제약, STARTED 발송 기록을 준비 TX에서 commit한다. PG 호출은 TX 밖이다 | 완료. PrepareOrderPaymentIntegrationTest, 동시 confirm/HTTP TX 경계 검사 |
| 성공은 추가 차감 없이 완료·확정 실패만 한 번 반환 | 업무 TX에서 주문 종결 CAS·확정 기록 또는 재고 반환·차감 기록·APPLIED를 함께 반영한다 | 완료. ApplyOrderPaymentIntegrationTest, 실패 callback/대사 동시 실행 |
| timeout·재시작·조회0건에서 임의 재결제/반환 금지 | NOT_FOUND/UNAVAILABLE은 마지막 결제 사실을 덮지 않는다. 발송 후 create 재시도가 없다. 중단 작업은 수동 인계한다 | 완료. adapter/재시작 검사, R03 주문68의 회차1·HTTP0 보존 |
| 실제 HTTP 계약·callback 권위 확인 | 세 경로의 고정 제공자 계정·문자열 ID·정수 금액과 응답을 검증한다. callback 본문 대신 저장 식별자로 조회한다 | 완료. PaymentProviderContractTest, PgPaymentHttpTest, PaymentCallbackApiE2ETest |
| 중복·순서 변경·내부 실패·timeout·회로·복구 | 관찰 먼저 commit/업무 TX 분리, 종결 회귀 차단, 조회 전용 회로와 제한 Retry를 연결했다 | 완료. 동시/늦은 응답/rollback 검사, R02/R03 독립 서버 실험 |
| 관련 회귀·lint·ArchUnit·실험 정리 | 기존 전체509건 통과·실험 정리 기록과, 후속 캐시16건/ArchUnit8건·테스트 Checkstyle 통과 근거가 있다 | 완료. 반환 캐시 검증 공백도 아래2건으로 보완 |

원문의 상세 요구인 삭제 상품의 기존 주문 종결·실패 반환, 관리자 가용 재고 설정, 저장 결과 재적용, 거래0건/복수/금액·식별 불일치도 검사와 연결돼 있다. 반환 overflow는 사용자 결정대로 전체 업무 rollback·실패 사실 보존·수동 처리다.

## 추가로 확정한 학습 요구 대조

| 항목 | 확인 결과 |
|---|---|
| 직접 만든 독립 실행 테스트 PG | apps:test-pg가 별도 JVM·PG 전용 MySQL 스키마에서 실행된다. 거래·예정 결과·테스트 조건을 보존하고 재시작 뒤 완료한다 |
| 요청별 테스트 조건 | 요청에 프리셋＋세부 조건을 넣고 최초 실효값9개를 저장·비교한다. 별도 제어 API 선택은 철회된 이력이다 |
| 멱등성 | 전역 UUID 시도 키·주문당 한 시도·같은 주문/금액/실효 조건을 확인한다. 카드 비교 제외는 사용자 승인 범위다 |
| 자동 조회 | 최초 발송 승자만 시작, 최초 포함4회·1/3/10초±20%·최초 전달부터30초다. DB 회차 commit 뒤 권한/완료·HTTP 전 시간을 재확인한다 |
| 실행기와 재시작 | 2 worker/큐2·명확한 거절100ms 뒤 추가 접수1회다. 사용 회차는 남고 재시작으로 자동 조회를 부활시키지 않는다 |
| Resilience4j | 별도 빈의 @Retry와 조회 전용 @CircuitBreaker, Spring AOP·실제 프록시·지표를 연결했다. Spring Retry 선택은 대체됐다 |
| 회로 집계와 fallback | 검증 FAILED는 정상 표본, 조회 장애는 실패, 나머지4xx/알려진 충돌 등은 제외한다. OPEN은 HTTP0·재고 유지다. 상세 분류와 수치는 [정책표](r03-payment-resilience/trade_off/02-lookup-circuit-breaker.md)를 따른다 |
| 내부 반영 재시도 | 전체 rollback 확인＋MySQL1213/40001만100ms 뒤 추가1회다. 실제 DB victim 선정 실험까지 완료한 것은 아니다 |
| 담당자 확인 | 기존 미완료 GET은 읽기 전용이다. 명시적 POST는 새 실행 ID로 인계하며 저장 종결 근거는 조회0회, 그 외 PG 조회 최대1회다 |
| 문서·제출 | 주문/재고/결제/전체 상태 문서, TX 경계, 실행 SQL/PG, PR별 결과·재현·운영 문서와 기술 글이 있다. 세 PR 모두 게시·병합됐다 |

## 보완 완료: 재고 반환 뒤 캐시의 직접 검증

재고 반환 기능이 빠진 것은 아니다. `ProductService.restoreStocks`는 상품을 잠가 수량을 더하고 `ProductDetailsChangedEvent`를 발행한다. 기존 listener는 AFTER_COMMIT에서 해당 상품 캐시만 무효화하며 rollback에는 동작하지 않도록 연결돼 있다.

최초 점검에서는 기존 캐시 통합 검사가 PG 준비 단계의 차감 commit·rollback만 직접 확인하고, PG 실패 반환 commit·rollback을 캐시 활성 상태로 읽는 사례는 빠져 있음을 확인했다. 사용자 요청에 따라 기존 클래스에 아래2건을 추가했다.

추가 테스트는 실제 MySQL·기존 PG 대역·관찰 저장/공통 업무 TX·활성화한 상품 상세 캐시를 사용한다.

1. **반환 commit:** `reloadsRestoredStockAfterPaymentFailureCommit`. 재고5→선차감3을 캐시에 읽은 뒤 검증 FAILED를 반영했다. DB와 새 상세 조회는5, 차감 기록은RESTORED·주문은PAYMENT_FAILED였고 무관한 상품 캐시 객체는 유지됐다.
2. **반환 뒤 rollback:** `keepsDebitedCacheWhenStockRestorationRollsBack`. beforeCommit에서 실제 DB 재고5와 RESTORED 기록을 먼저 확인하고 실패를 주입했다. 반환 SQL·이벤트 뒤 업무 전체가 rollback돼 DB와 기존 캐시 객체는3, 차감 기록은DEDUCTED·주문은PAYMENT_PENDING·작업은MANUAL_REQUIRED였고 별도 commit한 FAILED 근거는 남았다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
./gradlew.bat -I build/docker-api.init.gradle :apps:commerce-api:test --tests '*ProductDetailCacheInvalidationIntegrationTest' :apps:commerce-api:checkstyleTest --console=plain
```

최종 실행은 `BUILD SUCCESSFUL in 1m 14s`·종료 코드0이었다. **캐시16건(신규2건 포함)＋ArchUnit8건＝24건**, 실패·오류·건너뛰기0이며 Checkstyle 오류도0이다. 기존 전체509건과 중복되므로 합산하거나 새 전체 검사를 완료한 것으로 기록하지 않는다. 초회에는 추가 테스트의 Java 타입 추론 컴파일 오류가 있었고 명시적인 반환 타입을 지정해 수정했다. 기능 코드·기대값·검사 규칙은 바꾸지 않았다.

현재 판정은 **반환 경로의 캐시 통합 검증까지 보완 완료**다. 동작 변경 없는 테스트/문서 변경이므로 기존 정책에 따라 별도 세 관점 기능 리뷰는 반복하지 않았다. 로그·종료 코드·집계는 무시 대상 `build/week5-cache-restoration-check.log`, `build/week5-cache-restoration-check.exit`, `build/week5-cache-restoration-summary.json`에 남긴다. 모듈의 현재 테스트 XML은 이번 관련 실행 결과이며 이전 전체 실행의 집계는 `build/r03-full-check-summary.json`과 PR03 결과를 따른다.

## 보존·제외 범위와 문서 상태

- 원문 두 파일은 첨부 원본과 SHA-256이 동일하다. 체크박스도 원문 그대로 보존했다. 완료 여부는 이 점검표와 PR별 결과에서 판단한다.
- 사용자 JMeter SHA-256은 작업 전후 `F51DC3363E79FB6EEB15846ACA55C9028CB58F207C8F89CC973E2CE3B3FC89C0`으로 같다. 브랜치 이동에서도 수정·staging·되돌리기하지 않았다.
- 자동 lease/재시작 스캔, Retry-After, callback outbox/발송 보장, 환불·취소·실패 후 새 결제, 실서비스 인증/권한 체계는 승인된 학습 범위 밖이다. 누락된 필수 기능으로 분류하지 않는다.
- 회로/Retry 지표는 JVM 재시작 시 초기화된다. 발송 기록 뒤 실제 HTTP 전 종료된 시도·관찰 저장 전에 잃은 생성 전 거절은 자동 완료가 보장되지 않는다. 빈 조회만으로 재고를 반환하지 않는 한계를 유지한다.
- 병합 전 인계의 ‘PR 생성 예정’과 전체 요구사항의 ‘R03 검증 남음’은 현재와 달라 안내를 갱신했다. PR별 당시 진행/결과는 역사적 기록으로 유지한다.

## 근거를 따라 읽기

- 실행/검증 결과: [R01](r01-pg-payment-foundation/result.md), [R02](r02-pg-order-payment/result.md), [R03](r03-payment-resilience/result.md).
- 거래 해석: [adapter](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/gateway/pay/HttpPaymentProviderGateway.java), [관찰 저장](../../apps/commerce-api/src/main/java/com/loopers/application/pay/service/RecordPaymentObservationService.java).
- 주문과 재고: [준비 TX](../../apps/commerce-api/src/main/java/com/loopers/application/ordering/service/PrepareOrderPaymentService.java), [공통 반영 TX](../../apps/commerce-api/src/main/java/com/loopers/application/ordering/service/ApplyOrderPaymentService.java).
- 반환 캐시 연결: [ProductService](../../apps/commerce-api/src/main/java/com/loopers/application/mall/service/ProductService.java), [AFTER_COMMIT listener](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/query/mall/ProductDetailCacheInvalidationListener.java), [기존 캐시 통합 검사](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/query/mall/ProductDetailCacheInvalidationIntegrationTest.java).
- 상태·운영·기술 글: [상태 흐름](states/total.md), [담당자 처리](r03-payment-resilience/operations.md), [기술 글](r03-payment-resilience/technical-article.md).
