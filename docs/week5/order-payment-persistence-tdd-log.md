# W5 주문·예약·DB 연결 TDD 기록

후속 선택·정정(2026-10-23): 요청 동일성 판별에 HMAC-SHA256을 기술안으로 선택했으며 코드·DDL·검증은 아직 미완료다. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아니다. 아래의 ‘제안·미채택·허용 확인 전’ 표현은 당시 판단 기록으로 보존하며 [현재 선택과 정정](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-제안)을 따른다.

2026-10-23, 2단계의 **주문·예약 모델과 결제 시도 저장 연결** 증분이다. [선행 시도 모델](payment-attempt-state-tdd-log.md)·[예약 enum](order-reservation-state-tdd-log.md)의 기록은 그대로 보존한다. 실제 PG 호출·재고 변경·서비스 TX A/B까지 구현한 기록은 아니다.

## 선택과 변경 범위

사용자 확인(W5-Q09): 주문 생성 시 결제수단은 `null`이다. 아직 결제하지 않은 DRAFT에 PG를 미리 넣지 않는다. PG 준비 후에는 PG가 필수이고, 결제 진행·완료 상태의 누락값은 손상 상태로 거절한다. 이전 POINT 확정은 POINT로 보존한다. 미래의 모든 결제수단을 PG로 제한하는 설계가 아니라 이번 W5 신규 경로의 구분이다.

| 책임 | 구현·보존 범위 |
| --- | --- |
| [Order](../../apps/commerce-api/src/main/java/com/loopers/domain/order/Order.java) | `preparePgPayment`는 DRAFT→PAYMENT_PENDING, `confirmPgPayment`는 CONFIRMED, `failPgPayment`는 PAYMENT_FAILED. 전체 품목·수량·저장 금액·수단·성공 필드를 변경 전에 검사한다. 중복 종결은 false로 반환하고 최초 결과를 보존한다. |
| [OrderItem](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderItem.java) | 불변 quantity를 유지하며 NOT_RESERVED→HELD→CONSUMED/RELEASED를 저장한다. 별도 StockReservation은 만들지 않는다. |
| [PaymentAttempt](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentAttempt.java) | 기존 상태 판단을 유지하면서 JPA 매핑·생성 ID·UUID BINARY(16)·발송 시각·외부 식별자를 저장한다. 카드 원문·HMAC 필드는 없다. |
| [저장소](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PaymentAttemptRepositoryImpl.java) | 주문·UUID·계정/PG 주문·계정/거래 키 조회와 비관적 잠금 조회. 잠금 호출은 같은 TX에서 주문을 먼저 잠그는 호출자 전제를 갖는다. 실제 경합 선점 완료를 의미하지 않는다. |
| [009 SQL](../../apps/commerce-api/src/main/resources/db/schema/009-order-payment-state.sql) | 주문 상태·수단·품목 예약을 추가한다. 기존 CONFIRMED만 POINT로 표시하고 DRAFT는 null, 모든 기존 품목은 NOT_RESERVED로 둔다. 금액·확정/감사 시각을 재계산하지 않는다. |
| [010 SQL](../../apps/commerce-api/src/main/resources/db/schema/010-payment-attempt.sql) | 주문 FK, 주문당 한 시도, 서비스 전체 UUID 유일성, 계정별 PG 주문/거래 키 유일성, 비음수 금액·관찰 상태 CHECK. 외부 식별자의 대소문자·끝 공백을 구별한다. |
| 기존 API·서비스 | 기존 POINT confirm과 트랜잭션·락을 유지한다. 내부 enum 추가가 관리자 status 필터까지 자동 확장하지 않도록 DRAFT/CONFIRMED만 명시적으로 허용한다. 새 내부 상태 필터는 아직 400이다. |
| W4 성능 테스트 준비 | 최신 JPA 모델과 함께 실행하는 수동 schema 목록에 009/010을 추가했다. baseline은 008도 추가하고 007 인덱스의 전후 비교 순서는 유지했다. 캐시 방식·TTL·과거 측정 결과는 바꾸지 않았다. |

`PaymentAttempt.orderId`는 scalar 매핑이므로 Hibernate 자동 생성만으로 주문 FK가 생기지 않는다. **FK·상태 CHECK는 실제 수동 SQL을 적용한 검사로 증명**하고 JPA 저장 검사와 구분한다. DB 한 행의 CHECK만으로 주문·예약·시도 여러 행의 정합성을 보장하지 않으며, 실제 서비스 TX·잠금 연결이 필요하다.

## 무엇을 테스트했는가

| 테스트 | 관찰·기대값 |
| --- | --- |
| [OrderPaymentStateTest](../../apps/commerce-api/src/test/java/com/loopers/domain/order/OrderPaymentStateTest.java) | DRAFT null·POINT 구분·PG 준비/성공/실패·중복·금지 전이. 2품목의 실제 create→prepare→종결 연결. 마지막 품목 손상이나 종결 금액·시각 손상 때 앞선 품목까지 변경하지 않는 전체 불변 검사. |
| [W5PaymentSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/schema/W5PaymentSchemaIntegrationTest.java) | 먼저 001~008과 과거 POINT/DRAFT fixture를 만든 뒤 실제 009/010 적용. 기존 금액·품목·감사 시각·잔액·재고·삭제·revision 보존. 새 schema JPA validate·5상태 왕복·무거래 실패·유일/FK/CHECK·NULL 삼값 논리·식별자 비교·중간 flush 후 rollback. |
| [PaymentAttemptPersistenceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentAttemptPersistenceIntegrationTest.java) | 실제 Spring 저장소의 저장·다른 TX 재조회·발송/거래 이력 보존·같은 종결 재적용·네 유일성 충돌·실제 flush 후 주문/품목/시도 공동 rollback. 실패 뒤 독립 TX에서 다시 조회한다. |
| [CommerceSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/schema/CommerceSchemaIntegrationTest.java) | 최신 001~010과 엔티티를 연결하며 기존 Unicode·수치·시각·FK·버전 보존 기대값을 유지한다. 기존 정상 CONFIRMED SQL fixture에 POINT를 명시한다. |
| [OrderV1ApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) | 새 내부 PAYMENT_PENDING/PAYMENT_FAILED 필터 요청은 기존 INVALID_REQUEST 400과 DB 불변. 기존 HTTP 계약을 무심코 늘리지 않는다. |
| 기존 W3 동시성 3개 | 성공 주문은 새 payment_method=POINT, 실패/미변경 주문은 원래 null, 기존 품목은 NOT_RESERVED임을 추가 검사한다. 성공·부족 수, 재고·잔액·금액과 나머지 행 보존 기대값은 유지한다. |

수동 schema 테스트는 전용 MySQL Testcontainer, Spring 통합 테스트는 테스트 소유 DB를 사용했다. 사용자 로컬 DB·볼륨에는 SQL을 적용하거나 초기화하지 않았다. 테스트의 공동 저장은 모델/JPA의 기초 원자성 증거이며 실제 TX A/B 서비스의 재고·캐시 효과와 외부 PG 복구 증거로 확대하지 않는다.

## 실제 Red → Green 기록

| 단계 | 실제 결과 |
| --- | --- |
| 주문 모델 Red | 99개 중 90개 실패. 첫 실행의 상품 가격 0 fixture 준비 오류를 저장 snapshot 0 경계로 교정한 뒤 같은 99/90 실패를 확인했다. 상품 양수 가격 규칙은 유지했다. |
| 주문 모델 Green | 99개 통과. 당시 관련 도메인·ArchUnit 9스위트 250개와 commerce-api Checkstyle main/test 통과. 이후 2품목 연속·손상 종결 회귀 7개를 추가했다. |
| DB 연결 최초 Red | 82개 중 24개 실패. 그중 테스트 작성 오류 6개(정상 POINT fixture 수단 누락 1, UUID byte[]의 참조 비교 5)를 교정했다. UUID는 HEX 전체 16바이트 값으로 비교하며 기대를 완화하지 않았다. |
| 교정한 연결 Red | 82개 중 18개 실패: 비JPA PaymentAttempt 7, 미연결 저장소 9, 관리자 필터 2. 기존 schema 33개와 새 schema 31개는 이미 통과했다. 오류·skip 0, 종료 코드 1. |
| 연결 Green | JPA·저장소·필터 연결 후 **82개 모두 통과**, 종료 코드 0. 새 schema 38 + 기존 schema 33 + 저장소 9 + 필터 2. |

**009/010 SQL 초안은 schema 테스트보다 먼저 작성했다.** SQL을 테스트에서 제외하거나 정상 SQL을 일부러 훼손해 Red를 만들지 않았다. 실제 SQL은 최초 적용부터 성공했으며 JPA·저장소 연결의 미구현을 Red로 확인했다. Gradle 캐시 접근 권한으로 테스트 시작 전에 실패한 실행은 Red 증거로 세지 않는다.

연결 Red/Green 명령:

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.infrastructure.schema.CommerceSchemaIntegrationTest' \
  --tests 'com.loopers.infrastructure.schema.W5PaymentSchemaIntegrationTest' \
  --tests 'com.loopers.infrastructure.payment.PaymentAttemptPersistenceIntegrationTest' \
  --tests 'com.loopers.interfaces.api.order.OrderV1ApiE2ETest.internalPgStatesDoNotExpandTheExistingAdminFilterContract' \
  --console=plain
```

### 전체 회귀와 후속 보강

첫 전체 검사에서 W3 동시성 3개가 새 결제수단 컬럼의 변경을 기존 전체 행 비교에서 허용하지 않아 실패했다. 성공 행의 `payment_method`만 변경 대상으로 추가하면서 **성공=POINT·실패/미변경=원래 값**을 명시적으로 검사했다. 기존 동시성 결과·재고·잔액 검사를 삭제하거나 완화하지 않았다.

첫 전체 검사: **990개 중 3개 실패**, 종료 코드 1. 실패는 위 W3 세 사례의 새 수단 컬럼 비교였고, 나머지 987개는 통과했다. 이 결과를 전체 통과로 기록하지 않는다.

추가 검토에서 Java의 나노초 발송 시각과 DB DATETIME(6)의 정밀도 차이를 확인했다. `.123456789` 입력을 `.123456`으로 맞추고 재조회·중복 시작에서도 최초 시각을 보존하는 단위/저장 테스트 2개를 추가했다. **새 테스트 2개 실패 + 수정한 W3 세 테스트 통과(5개 중 2개 실패)**를 먼저 확인한 뒤 `startDispatch`를 기존 주문 확정과 같은 MICRO 절삭으로 수정했다. 과거 금액·시각을 일괄 갱신하는 변경은 아니다.

### 최종 실행 결과

**2026-10-23 11:01 KST 완료:** 아래 명령으로 정밀도 보강·W3 정확한 수단 검사까지 포함해 전체 회귀를 재실행했다.

| 검사 | 결과 |
| --- | --- |
| 기본 전체 회귀 | **88개 스위트·992개 테스트 통과**, 실패·오류·skip 모두 0 |
| 이번 핵심 범위 | 주문 상태 106, 결제 시도 64 + 값 검증 18, 결제 저장소 10, 새 스키마 38 모두 통과 |
| ArchUnit | 1개 통과(위 전체 수에 포함) |
| 모든 Java 모듈 Checkstyle | XML 보고서 11개, 위반 0 |
| Gradle | BUILD SUCCESSFUL, 종료 코드 0, 7분 27초 |
| 변경 파일 | git diff --check 통과 |

정밀도 테스트 2개도 이 전체 실행에서 Green을 확인했다. 보고서는 [테스트 HTML](../../apps/commerce-api/build/reports/tests/test/index.html)과 `build/test-results/test/TEST-*.xml`이며 재실행 시 갱신되는 산출물이다. 위 수치는 이 시점의 기록으로 보존한다.

```shell
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

기존 설정상 장시간 `ttl-measurement`는 별도 task이며 이번에는 재측정하지 않는다. W4 기준선·혼합 부하·cold-start를 포함한 기본 회귀와 모든 Java 모듈 Checkstyle·ArchUnit을 검사한다. 과거 성능 측정 문서는 당시 증거로 유지한다.

## 남은 범위

- 같은 UUID에서 카드 등 요청 내용이 바뀌었는지 비교하는 방식은 아직 미정이다. UUID 유일성 검증만으로 요청 동일성 전체가 끝난 것이 아니며 HMAC은 제안으로 유지한다.
- 실제 재고 확보/반환, 관리자 반환 여유, 목록 버전, 서비스 TX A/B·외부 TX 차단·커밋 승자만 최초 호출·다중 요청 경합은 후속 연결이다.
- 로컬 HTTP PG fixture·adapter·timeout·조회 circuit breaker·callback/reconcile·새 HTTP DTO/오류/ACK는 아직 구현하지 않았다.
- 사용자 데이터 이행은 [스키마 적용 안내](../week2/commerce-schema-operations.md)의 별도 절차다. 파일 제공·격리 테스트 통과가 사용자 DB에 적용했다는 뜻은 아니다.
- 커밋·push·PR 제출은 하지 않았다.
