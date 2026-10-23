# W5 주문 품목 예약 상태 TDD 실행 기록

후속 선택·정정(2026-10-23): 요청 동일성 판별에 HMAC-SHA256을 기술안으로 선택했으며 코드·DDL·검증은 아직 미완료다. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아니다. 아래의 ‘제안·미채택·허용 확인 전’ 표현은 당시 판단 기록으로 보존하며 [현재 선택과 정정](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-제안)을 따른다.

2026-10-23, W5-TDD-01의 **예약 상태 전이 하위 범위만 구현·검증**한 당시 기록이다. 이 증분 당시 결제·주문 상태, 실제 재고 변경, 영속화·트랜잭션·동시성·PG 연동은 구현하지 않았다. [전체 TDD 계획](../week2/commerce-tdd-plan.md#w5-pg-결제-tdd-계획)과 [완료 체크리스트](../week2/commerce-completion-checklist.md#w5-설계-선택과-구현-상태)에 부분 완료로 연결한다.

같은 날 후속 [결제 시도 상태 증분](payment-attempt-state-tdd-log.md)에서 PaymentAttempt 순수 모델을, [주문·예약·DB 연결 증분](order-payment-persistence-tdd-log.md)에서 Order·OrderItem·JPA 저장과 이행 검증을 추가했다. 아래 Red·Green·70개 회귀 결과와 남은 범위는 예약 enum 증분 당시 기록으로 보존하며, 최신 범위는 후속 기록을 따른다.

## 이번 범위와 선택 이유

| 대상 | 이번 변경 |
| --- | --- |
| [OrderItemReservationStatus](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderItemReservationStatus.java) | domain/order의 순수 enum. `NOT_RESERVED`, `HELD`, `CONSUMED`, `RELEASED`와 `hold()`, `consume()`, `release()`의 허용 전이 구현 |
| [단위 테스트](../../apps/commerce-api/src/test/java/com/loopers/domain/order/OrderItemReservationStatusTest.java) | 4개 상태 × 3개 행동의 12개 조합 검증 |
| 기존 OrderItem·Order·Product·서비스·API·DDL | 변경하지 않음. OrderItem의 구매 수량 불변, 기존 POINT 결제·저장 구조 유지 |
| HMAC·PaymentAttempt·PG·예약 영속화 | 이번 증분에서 구현하지 않음. HMAC은 허용 여부 확인 전 제안으로 유지 |

[과제 원문 4·5절](assignment-original.md)의 예약 유지·성공 소비·확정 실패 반환·종결 보존 의미를 먼저 작은 순수 모델로 표현했다. 이 enum은 이후 기존 OrderItem의 예약 상태로 연결할 후보이지 별도 StockReservation 객체나 테이블이 아니다. 상태 이름은 기존 설계안의 이름을 이번 코드에서 사용했다.

OrderItem에 `@Transient` 상태를 임시로 붙이는 대안은 채택하지 않았다. 재조회하면 예약 상태가 사라질 수 있기 때문이다. JPA 필드만 먼저 추가하는 방법도 수동 DDL·기존 POINT/DRAFT 이행·스키마 검증과 함께 다뤄야 하므로 후속 증분으로 남겼다.

## 구현한 전이표

| 현재 상태 | hold · 확보 | consume · 소비 | release · 반환 |
| --- | --- | --- | --- |
| NOT_RESERVED | HELD | 거절 | 거절 |
| HELD | HELD · 변화 없음 | CONSUMED | RELEASED |
| CONSUMED | 거절 | CONSUMED · 변화 없음 | 거절 |
| RELEASED | 거절 | 거절 | RELEASED · 변화 없음 |

허용하지 않는 전이는 내부 `IllegalStateException`으로 거절한다. 새로운 업무 errorCode·HTTP 응답을 정하거나 API에 연결한 것은 아니다. 반대 종결 관찰을 어떻게 기록하고 외부에 응답할지는 기존 W5-Q05의 후속 계약이다.

enum은 현재 객체를 변경하지 않고 **다음 상태를 반환**한다. 중복 호출도 같은 상태를 반환하므로 이 코드만으로 실제 재고 효과를 한 번으로 제한할 수는 없다. 후속 연결에서는 이전 상태와 다음 상태의 차이 또는 사전 종결 검사를 사용하고, 실제 재고·예약·결제 결과를 같은 DB 트랜잭션과 잠금 경계로 보호해야 한다. `release()` 결과가 RELEASED라는 이유만으로 매번 재고를 더하면 중복 반환이 발생한다.

PENDING·UNKNOWN은 이 enum의 상태가 아니다. 그 관찰에서 HELD를 유지하고 늦은 관찰이 종결을 되돌리지 않는지는 결제 시도·조율 로직을 연결할 때 검증한다.

## Red → Green → Refactor

최초 sandbox 실행은 Gradle 사용자 캐시 잠금 파일 권한 때문에 테스트 시작 전에 종료됐다. 이는 Red 증거로 세지 않았다. 캐시 접근을 허용한 재실행부터 아래 결과를 확인했으며 DB 테스트·Docker 조작은 하지 않았다.

| 순서 | 변경·실행 | 실제 결과 |
| --- | --- | --- |
| Red 1 | 정상·중복 전이 6개 사례를 먼저 작성. enum 메서드는 현재 상태를 그대로 반환하는 최소 골격 | 6개 실행·3개 실패. NOT_RESERVED→HELD, HELD→CONSUMED/RELEASED가 아직 바뀌지 않아 assertion 실패, 종료 코드 1 |
| Green 1 | 각 행동이 목표 상태를 반환하도록 구현 | 6개 통과, 실패·오류·skip 0, 종료 코드 0 |
| Red 2 | 예약 전 소비/반환·종결 후 확보·반대 종결의 거절 6개 사례 추가 | 12개 실행·추가한 6개 실패. 금지한 행동이 예외 없이 상태를 반환하여 assertion 실패, 종료 코드 1 |
| Green 2 | 행동별로 허용 출발 상태 또는 이미 도달한 상태인지 검사 | 12개 통과, 실패·오류·skip 0, 종료 코드 0 |
| Refactor·회귀 | 반복하는 검사와 상태 반환을 private `transition(source, target)`으로 통합. 상태 규칙과 실제 재고/DB 효과의 경계를 Javadoc에 명시 | 아래 70개 테스트와 main/test Checkstyle 통과, 종료 코드 0 |

각 Red/Green의 단위 테스트 명령은 다음과 같다.

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.order.OrderItemReservationStatusTest' \
  --console=plain
```

최종 리팩터링 뒤에는 관련 테스트·정적 검사를 실제 재실행했다.

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.domain.product.*' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  --rerun-tasks --console=plain
```

| 검사 | 실제 결과 |
| --- | --- |
| OrderItemReservationStatusTest | 12개 통과 |
| 기존 OrderTest·OrderQuantitiesTest | 8 + 16 = 24개 통과 |
| 기존 ProductTest·ProductStockTest | 14 + 19 = 33개 통과 |
| ArchitectureTest | 1개 통과 |
| 합계 | 6개 스위트·70개 테스트, 실패·오류·skip 모두 0 |
| commerce-api Checkstyle main/test | 보고서 2개, 위반 0 |
| 빌드 | BUILD SUCCESSFUL, 종료 코드 0. 기존 RedisProductListCache의 deprecated API 컴파일 경고는 남아 있으며 이번 변경 대상 아님 |

로컬 보고서: [테스트](../../apps/commerce-api/build/reports/tests/test/index.html), [Checkstyle main](../../apps/commerce-api/build/reports/checkstyle/main.html), [Checkstyle test](../../apps/commerce-api/build/reports/checkstyle/test.html). 이 파일들은 다음 검사로 갱신될 수 있다. 위 결과는 이번 실행 범위를 기록하며 전체 `check`나 실제 DB/HTTP/동시성 테스트의 통과를 뜻하지 않는다.

## 남은 범위와 다음 증분

| 범위 | 상태·다음 작업 |
| --- | --- |
| W5-TDD-01 전체 | **부분 완료.** 주문·PaymentAttempt의 상태 전이와 PENDING/UNKNOWN·늦은 관찰·결제 결과 연결은 남음 |
| Order의 신규 상태 검사 | 아직 기존 DRAFT/CONFIRMED뿐. 새 대기/실패 상태를 추가할 때 `requireDraft()`가 CONFIRMED만 거절하는 기존 구현도 함께 변경·검증해야 함 |
| 예약 영속화·이행 | OrderItem 매핑·DDL·과거 POINT/DRAFT 보존을 함께 설계·검증. 임시 메모리 상태로 대체하거나 실제 DB/볼륨을 초기화하지 않음 |
| 실제 재고·TX·락 | 확보/반환 행동, TX A/HTTP/TX B, 잠금 순서·DB 제약·동시 결과 반영은 아직 미구현. 이 증분 당시 발송 기록의 TX 위치는 미정이었으며, 2026-10-23 후속 [Q08 선택](../week2/commerce-policy-decisions.md#발송-시작-기록의-tx-배치-비교)으로 준비 TX A에 함께 저장하기로 결정. 영속화·실제 공동 커밋/rollback 검증은 남음 |
| 요청 비교·PG·복구 | HMAC 제안은 미채택. 카드 비교·발송·HTTP·callback·reconcile·시간/회로 검증은 별도 증분 |

이번 범위의 상태 의미는 원문과 기존 설계로 정해져 있어 추가 제품 정책 질문 없이 진행했다. 다음은 주문·결제 시도의 상태와 예약 전이를 연결할 책임을 구체화하는 것이다. 이번 단위 테스트 결과로 PG 결제 교체나 재고 중복 차감·반환 방지 완료를 선언하지 않는다.
