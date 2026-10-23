# W5 결제 시도 상태 TDD 실행 기록

후속 선택·정정(2026-10-23): 요청 동일성 판별에 HMAC-SHA256을 기술안으로 선택했으며 코드·DDL·검증은 아직 미완료다. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아니다. 아래의 ‘제안·미채택·허용 확인 전’ 표현은 당시 판단 기록으로 보존하며 [현재 선택과 정정](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-제안)을 따른다.

2026-10-23, W5-TDD-01의 **PaymentAttempt 순수 모델 하위 범위**를 구현·검증한 당시 기록이다. 앞선 [예약 상태 enum 증분](order-reservation-state-tdd-log.md)에 이어 발송 시작·외부 관찰·종결 보존을 추가했다. 당시 DB 저장, 주문·예약과의 공동 변경, 실제 PG 호출은 연결하지 않았다.

같은 날 후속 [주문·예약·DB 연결 기록](order-payment-persistence-tdd-log.md)에서 Order·OrderItem 연결과 PaymentAttempt JPA·DDL·저장 검증을 추가했다. 아래 81개·151개와 미구현 범위는 선행 증분 당시 기록으로 보존한다.

## 이번 범위와 선택 이유

| 책임·파일 | 이번 구현 |
| --- | --- |
| [PaymentAttempt](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentAttempt.java) | 주문 ID·UUID 시도 키·PG 계정/주문·저장 금액, 최초 발송 시각, 거래 연결과 관찰 결과 상태를 관리하는 순수 모델 |
| [PaymentAttemptStatus](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentAttemptStatus.java) | NOT_OBSERVED / PENDING / UNKNOWN / SUCCESS / FAILED. 발송 시작과 외부 결과를 별도 축으로 표현 |
| [PaymentTransaction](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentTransaction.java) | PG 생성 응답 또는 권위 있는 조회에서 얻은 거래의 내부 값. raw callback 요청 DTO와 구분 |
| [PaymentObservationResult](../../apps/commerce-api/src/main/java/com/loopers/domain/payment/PaymentObservationResult.java) | APPLIED / UNCHANGED / CONFLICT로 모델 변경 여부·충돌을 구분. 신규 HTTP 오류 계약을 정하지 않음 |
| [PaymentAttemptTest](../../apps/commerce-api/src/test/java/com/loopers/domain/payment/PaymentAttemptTest.java) | 발송·상태 전이·중복·늦은 관찰·식별/금액 불일치·무효 입력 후 전체 필드 보존 63개 |
| [PaymentValueValidationTest](../../apps/commerce-api/src/test/java/com/loopers/domain/payment/PaymentValueValidationTest.java) | 필수 식별값·음수 거절, 비음수 저장 금액의 0·Long 최댓값 표현 경계 18개 |
| 기존 Order·OrderItem·Product·서비스·API·DDL | 변경하지 않음. 기존 POINT 결제·트랜잭션·락 유지 |

Order 상태 확장도 함께 하는 대안은 이번 작은 증분에서 제외했다. 현재 관리자 주문 API는 `OrderStatus.valueOf()`로 필터를 받기 때문에 enum 추가만으로 외부 입력 범위가 넓어진다. 반면 기존 `005-order.sql`의 ENUM·CHECK는 새 상태를 저장할 수 없다. **Order·API·JPA·DDL·이행 검증을 함께 다룰 후속 증분**으로 두고, 미정인 HTTP 필드·오류 정책을 이번 모델 구현으로 확정하지 않았다.

별도 PaymentAttempt 클래스는 선택한 저장 책임을 향한 첫 구현이지만 아직 JPA 엔티티·테이블·저장소는 아니다. 카드 번호·HMAC·카드 비교값을 저장하지 않으며 HMAC 제안의 채택도 하지 않았다.

## 구현한 규칙과 테스트 목표

| 상황 | 기대값·실제 검증 |
| --- | --- |
| 새 시도 | NOT_OBSERVED, 발송 시각·거래 키 없음. 로컬 준비를 PG 접수로 표현하지 않음 |
| `startDispatch` 반복 | 같은 객체의 최초 호출만 true, 이후 false. 최초 시각 보존·결과 상태 불변 |
| 발송 전 결과 적용 | 거래 관찰·UNKNOWN·효과 0건 거절 모두 내부 예외, 전체 필드 불변 |
| 미완료 상태에서 유효 거래 관찰 | NOT_OBSERVED/PENDING/UNKNOWN에서 PENDING/SUCCESS/FAILED 전이 9개 조합. 같은 PENDING은 UNCHANGED |
| timeout 등 확인 불가 | 미완료는 UNKNOWN, 기존 거래 키·발송 기록 보존. UNKNOWN→동일 거래 PENDING으로 다시 확인 가능 |
| 종결 뒤 관찰 | 동일 결과·같은 거래의 늦은 PENDING·UNKNOWN은 UNCHANGED. 반대 종결은 CONFLICT, 기존 결과 보존 |
| 식별·금액 불일치 | 계정·PG 주문·금액·이미 연결한 거래 키가 다르면 CONFLICT. 종결 후 늦은 PENDING도 **식별 검사를 먼저** 수행 |
| 거래 생성 전 확정 거절 | 검증된 효과 0건 거절은 거래 키 없이 FAILED, 중복은 UNCHANGED. 이미 알려진 거래가 있거나 거절 후 실제 거래가 관찰되면 CONFLICT |
| 입력 경계 | null/공백 식별·null 상태·음수 금액 등 거절. 모델은 0·Long 최댓값을 표현할 수 있으며 잘못된 시각/관찰로 부분 변경 없음 |

금액 경계 테스트는 기존 주문 SQL의 `total_amount >= 0` 범위와 long 표현을 다루는 순수 모델 검증이다. 현재 정상 상품 가격은 **1원 이상**이며 이를 변경하지 않았다. 0원 상품·신규 0원 결제 허용이라는 제품 정책을 정한 것이 아니고, 실제 시도 준비 시 기존 주문·품목·저장 금액의 검증을 함께 수행해야 한다.

충돌 사례는 상태만 확인하지 않고 주문 ID·시도 키·PG 계정/주문·금액·결과 상태·발송 시각·거래 키의 전체 snapshot을 비교했다. W5-STATE-05~10의 **모델이 담당하는 부분**이며, 복수 거래 선택·HTTP 응답 분류·주문/예약 효과·DB 경합까지 완료했다는 뜻은 아니다.

## 트랜잭션·멱등성의 경계

- `startDispatch()`는 **한 객체의 상태 전이**다. 메모리의 true/false만으로 다중 요청·다중 서버·재시작의 중복 POST를 방지했다고 주장하지 않는다. 후속 저장 경계에서 잠금/제약과 TX A 커밋을 확인한 최초 실행만 실제 PG를 호출해야 한다.
- 사용자 선택대로 발송 기록은 재고 예약·시도·주문 대기·목록 버전과 **TX A에 함께 저장**할 계획이다. 이번에는 `@Transactional`·DB 락·공동 저장을 구현하지 않았다.
- 발송 시작 기록은 **PG가 받았다는 증거가 아니다.** 커밋 직후 HTTP 전에 중단됐더라도 자동 POST 재시도는 금지한다. 이 중단 장면의 실제 DB/HTTP 검증은 후속 범위다.
- APPLIED는 **메모리 모델이 바뀌었다**는 뜻이다. 결제 상태만 먼저 저장해서는 안 되며, 주문·예약·필요한 재고 변경과 TX B에서 함께 커밋해야 한다. 실제 소비/반환 횟수는 아직 검증하지 않았다.
- `observeRejectedBeforeCreate()`의 호출 전 adapter가 strict 422·정해진 오류 코드·JSON boolean false·요청 일치를 검증해야 한다. 이 모델 테스트는 일반 4xx/timeout/조회 0건을 확정 거절로 분류하는 HTTP 검증을 대신하지 않는다.
- callback 본문은 신뢰한 거래 관찰이 아니다. 권위 조회 결과를 검증해 전달하는 adapter/조율 경계는 아직 미구현이다.

## Red → Green → 회귀 보강

최초 sandbox 실행은 Gradle 캐시 잠금 파일 권한 때문에 테스트 전 종료됐다. 이를 Red로 세지 않았으며, 캐시 접근을 허용한 실행의 결과를 아래에 기록한다. DB·Docker·볼륨은 조작하지 않았다.

| 순서 | 변경·실행 | 실제 결과 |
| --- | --- | --- |
| Red 1 | 초기 상태·발송 시작 2개 테스트. `startDispatch()`는 false를 반환하는 골격 | 2개 실행·1개 실패. 최초 발송 전이가 true가 아님, 종료 코드 1 |
| Green 1 | 최초 시작 기록·중복 거절 구현 | 2개 통과, 종료 코드 0 |
| Red 2 | 관찰·중복·종결·식별 충돌 테스트 추가. 관찰 메서드는 UNCHANGED 골격 | 50개 실행·48개 실패, 종료 코드 1. 일부 사례는 정상 전이를 사용하는 준비 단계에서 실패 |
| Green 2 | 거래 연결·미완료/종결 전이·UNKNOWN·효과 0건 거절 구현 | 50개 통과, 종료 코드 0 |
| Red 3 | 생성자·금액 경계와 추가 회귀 20개 추가 | 70개 실행·16개 실패. 무효 생성자 입력이 거절되지 않음. 나머지 4개 추가 사례는 이 시점에도 통과 |
| Green 3 | 기본 식별값·상태·음수 입력 검증 추가 | 70개 통과, 종료 코드 0 |
| 리뷰·회귀 보강 | 종결 뒤 다른 대상의 PENDING 8개, 효과 0건 거절 뒤 다른 대상의 거절 3개 추가. 모델/DB/HTTP 책임을 Javadoc에 명시 | 신규 총 81개 통과. 이미 맞게 동작한 경계의 회귀 보강이며 별도 Red를 주장하지 않음 |

Red 1~Green 2는 다음 명령을 사용했다.

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.payment.PaymentAttemptTest' --console=plain
```

Red 3·Green 3는 필터를 `com.loopers.domain.payment.*`로 넓혔다. 최종 관련 검사는 다음과 같다.

```bash
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.payment.*' \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.domain.product.*' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  --rerun-tasks --console=plain
```

| 최종 검사 | 실제 결과 |
| --- | --- |
| 신규 PaymentAttemptTest·PaymentValueValidationTest | 63 + 18 = 81개 통과 |
| 앞선 예약 상태 enum | 12개 통과 |
| 기존 OrderTest·OrderQuantitiesTest | 8 + 16 = 24개 통과 |
| 기존 ProductTest·ProductStockTest | 14 + 19 = 33개 통과 |
| ArchitectureTest | 1개 통과 |
| 합계 | **8개 스위트·151개 테스트**, 실패·오류·skip 모두 0 |
| commerce-api Checkstyle main/test | 보고서 2개, 위반 0 |
| 빌드 | BUILD SUCCESSFUL, 종료 코드 0. 기존 RedisProductListCache의 deprecated API 경고는 남아 있음 |

로컬 [테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html), [Checkstyle main](../../apps/commerce-api/build/reports/checkstyle/main.html), [Checkstyle test](../../apps/commerce-api/build/reports/checkstyle/test.html)는 다음 실행 때 갱신될 수 있다. 전체 `check`·실제 DB·HTTP·동시성 테스트를 이번에 통과했다는 의미는 아니다.

## 남은 범위와 다음 증분

다음은 Order의 PG 준비/종결 상태와 OrderItem 예약 상태를 **실제 저장 구조·기존 POINT 보존과 함께 연결**할 범위를 구체화하는 것이다. 그 후 사용자 선택한 TX A 공동 저장·락·rollback·커밋 승자의 최초 발송을 실제 DB와 HTTP로 검증한다.

HMAC 채택 여부, 신규 오류·callback ACK·응답 필드, PG 주문 식별자 매핑, HTTP 도구와 별도 reconcile 예산은 기존 미정 상태를 유지한다. 이번 순수 모델 범위는 그 선택 없이 진행했지만, 관련 경계 구현 전에는 확인해야 한다. 이 증분을 PG 결제 전환 완료나 분산 멱등성 완료로 표시하지 않는다.
