# W6 주문 확정 이벤트 모델 TDD 실행 기록

## 이번 증분의 범위

2026-10-29 사용자가 **W6-Q01: `order-confirmed:v1:{orderId}` 유도 ID**, **W6-Q02: after-commit listener**를 선택했다. 이번에는 1단계의 생산자 순수 이벤트 모델을 구현했다. 기준 HEAD는 `6e686d824d979ad16a7a2baa70afbeee2c3ce460`이며 아래 결과는 그 위의 미커밋 작업 트리를 실행한 것이다. W6 전체 완료 또는 W7 재현용 commit을 뜻하지 않는다.

| 변경 | 책임 |
| --- | --- |
| [OrderConfirmedEvent](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderConfirmedEvent.java) | 생산자용 불변 record. 고정 종류·버전·eventId·key, 최초 확정 시각과 품목 복사, 양수/합산 경계와 정규화 |
| [OrderConfirmedEventTest](../../apps/commerce-api/src/test/java/com/loopers/domain/order/OrderConfirmedEventTest.java) | 신규 순수 테스트 40개. 생성·동등성·불변성·직렬화 필드 및 잘못된 입력/원본 상태 거절 |
| [전체 설계](../week2/commerce-erd-draft.md#w6-주문-확정-이벤트와-구매-활동-설계)·[정책](../week2/commerce-policy-decisions.md#w6-이벤트-전달의-선택과-남은-결정)·[TDD](../week2/commerce-tdd-plan.md#w6-주문-확정-이벤트와-구매-활동-tdd-계획)·[API](../week2/commerce-api-contract.md#w6-확정-이벤트와-기존-api의-경계) | W6 연결 목표와 선택한 Q01/Q02, 이번 순수 모델의 경계와 후속 미정 선택 구분 |

기존 Order·OrderItem·PaymentAttempt·서비스·트랜잭션·잠금·HTTP·DDL·Kafka 설정은 변경하지 않았다. 사용자 DB, 별도 테스트 DB, Docker, broker, 실제 비밀키에는 접근하지 않았다. after-commit listener는 **선택한 연결 설계**이고 이번 증분에 실행 코드를 추가한 것은 아니다.

## Red → Green과 구조 점검

| 순서 | 실행·관찰 | 결과 |
| --- | --- | --- |
| 환경 확인 | 최초 sandbox 실행은 기존 Gradle 캐시의 `.lck` 파일 접근 제한으로 종료 | 테스트 Red로 세지 않음. 캐시 접근이 가능한 실행으로 재시도 |
| Red 1 | 스냅샷·정규화·불변성·상품 변경/삭제·JSON 필드 테스트 5개를 먼저 작성 | `compileTestJava`에서 아직 없는 `OrderConfirmedEvent` 관련 24개 컴파일 오류, 종료 1 |
| Green 1 | 고정 metadata·저장 시각/품목 복사·정렬/합산·불변 목록을 구현 | 5개 통과, 실패·오류·skip 0, 종료 0 |
| Red 2 | 잘못된 metadata/ID/수량·원본 주문 상태 등으로 확장 | 40개 실행 중 **33개 실패**, 오류·skip 0, 종료 1. POINT 허용·누락 검증·예외 분류 등의 미구현을 확인 |
| Green 2 | canonical 생성자 검증, PG 확정 상태 검사 재사용, ID 검사와 합산 초과 거절을 구현 | 신규 **40개 통과**, 실패·오류·skip 0, 종료 0 |
| 구조 점검·회귀 | 값 검증/정규화/원본 복사 책임과 기존 주문 모델·계층 규칙 확인 | 아래 관련 183개·Checkstyle 통과. 추가 리팩터링을 위한 불필요한 기존 코드 변경은 하지 않음 |

앞의 네 번은 다음 같은 명령을 각 시점의 테스트·구현에 실행했다.

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.order.OrderConfirmedEventTest' \
  --offline --console=plain
```

## 실제로 확인한 기대값

| 장면 | 확인한 값·보호 |
| --- | --- |
| 주문 42의 PG 확정 | `eventType=OrderConfirmed`, `schemaVersion=1`, `eventId=order-confirmed:v1:42`, key `42`. 상품 7 수량 2·상품 8 수량 1 |
| 저장 시각 복사 | 원본 입력 `2026-10-29T12:34:56.123456789+09:00`은 기존 Order가 마이크로초로 저장. 이벤트는 저장된 값과 같은 시점인 `2026-10-29T03:34:56.123456Z`를 사용. 하루 뒤 중복 성공 적용에도 최초 사실 불변 |
| 순서·상품 중복 | 역순·비연속 중복 상품 항목을 상품 ID 순으로 정렬하고 합산. 같은 사실은 같은 값으로 비교되고 다른 수량·시각은 구분 |
| 경계 입력 | 잘못된 종류·버전·eventId/주문 ID 조합, 누락 시각, null/빈 품목, null 원소, 양수가 아닌 ID/수량 거절. 최대 long ID와 정확히 최대 int 합계 허용, 합산 초과 거절 |
| 원본 주문 | DRAFT·PAYMENT_PENDING·PAYMENT_FAILED·과거 POINT 확정 제외. PG 확정이어도 시각/확정액/예약/수량/식별자가 손상된 경우 거절. 기존 `validatePgPaymentState()` 재사용 |
| 스냅샷 보존 | 입력 목록을 지워도 이벤트 불변, 반환 목록 수정 불가. 상품명·가격 변경과 논리 삭제 이후에도 주문 품목의 ID/수량·최초 시각 유지 |
| JSON 최소 필드 | JavaTimeModule을 등록한 테스트 mapper에서 eventType/schemaVersion/eventId/orderId/confirmedAt/items만 직렬화. 품목은 productId/quantity만 포함. 엔티티·사용자·결제 상세·카드·HMAC 필드 없음 |

원본의 상품 ID는 기존 `OrderItem.getProductId()`를 통해 얻는다. 현재 상품 상세·가격·삭제 상태를 재조회해 이벤트를 계산하지 않으며 이벤트 자체는 Product/Order 엔티티 참조를 보관하지 않는다. 이번 검사는 메모리 객체와 테스트 mapper의 검사이며 실제 DB 조회 수·영속성 컨텍스트 종료 뒤 접근·운영 Kafka serializer를 검증한 것은 아니다.

모델의 `from(Order)` 성공은 **실제 DB commit·검증된 PG SUCCESS·최초 전이·한 번 발행**의 증거가 아니다. 동일 확정 모델에서 같은 스냅샷을 재구성할 수 있으며, 발행 등록은 이후 공통 TX B 최초 전이에서 제한하고 전송은 실제 commit 이후에 연결해야 한다.

이 모델은 생산자가 생성할 정규 eventId만 허용한다. 소비자는 별도 입력 경계에서 같은 orderId/다른 eventId와 같은 eventId/다른 payload를 충돌로 드러내야 하며, 이번 생성자 거절을 소비자 충돌 처리의 완료로 간주하지 않는다.

## 최종 관련 검사

2026-10-29 **08:54 KST**, 다음 명령은 **BUILD SUCCESSFUL / 7초 / 종료 0**이었다.

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  --offline --console=plain
```

| 실제 실행 스위트 | 테스트 수 |
| --- | ---: |
| OrderConfirmedEventTest · 신규 | 40 |
| OrderPaymentStateTest | 106 |
| OrderItemReservationStatusTest | 12 |
| OrderQuantitiesTest | 16 |
| OrderTest | 8 |
| ArchitectureTest | 1 |
| 합계 | **183** |

- 총 **6개 스위트·183개 통과**, 실패·오류·skip **0**. 신규 40개는 이 합계에 포함하며 앞선 실행 건수를 더하지 않는다.
- commerce-api Checkstyle main/test 보고서 2개, 위반 **0**.
- API ArchUnit은 이번 API 모델의 계층 의존 확인이며 별도 streamer의 검사 완료로 확대하지 않는다.
- 전체 `check`, PG HTTP/DB 통합, Kafka/streamer 검사와 장시간 성능 실험은 이번 순수 모델 증분에서 실행하지 않았다.

검사 XML은 `apps/commerce-api/build/test-results/test/TEST-*.xml`, HTML은 [테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)에 생성됐다. build 결과는 후속 실행으로 바뀔 수 있으므로 위 명령·시점·범위·관찰값을 이번 증거로 보존한다.

## 남은 연결

1단계의 이벤트 모델 증분은 완료했다. 소비 저장 구조·수신 사실 비교·실패 handler·발행 대기/관측·실제 broker fixture 선택(Q03~06)은 후속으로 남는다. 2단계 Kafka 기반, 3단계 공통 최초 확정/after-commit 발행, 4단계 소비 DB, 5단계 실제 재전달·실패·W7 두 사례, 6단계 관련 회귀/제출 증거는 완료하지 않았다. 공식 11개 항목의 전체 판정은 [현재 체크리스트](implementation-checklist.md)에서 별도로 추적한다.
