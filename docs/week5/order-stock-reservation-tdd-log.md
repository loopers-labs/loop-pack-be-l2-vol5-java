# W5 4단계 첫 증분 · 재고 확보·반환 TDD 기록

후속 선택·정정(2026-10-23): 요청 동일성 판별에 HMAC-SHA256을 기술안으로 선택했으며 코드·DDL·검증은 아직 미완료다. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아니다. 아래의 ‘제안·미채택·허용 확인 전’ 표현은 당시 판단 기록으로 보존하며 [현재 선택과 정정](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-제안)을 따른다.

2026-10-23. [과제 원문 4절](assignment-original.md) · [전체 설계](../week2/commerce-erd-draft.md#w5-pg-결제-설계) · [정책 선택](../week2/commerce-policy-decisions.md#w5-결제-교체의-선택과-질문) · [TDD 계획](../week2/commerce-tdd-plan.md#w5-pg-결제-tdd-계획)

후속: [PG HTTP·주문·재고 연결 기록](order-payment-http-connection-tdd-log.md)에서 실제 HTTP와 내부 TX A/B를 연결했다. 아래는 재고 첫 증분 당시의 범위·검사 결과를 보존한 기록이다.

## 이번 범위와 현재 상태

**내부 재고·TX A/B 구현과 전체 회귀를 통과했다.** 주문·예약·결제 시도의 [저장 기반](order-payment-persistence-tdd-log.md)에 실제 상품 재고와 내부 TX A/B를 연결했다. [3단계 HTTP adapter](local-test-pg-http-tdd-log.md)는 독립 경계로 유지하고 이번 서비스에서 호출하지 않는다. 기존 고객 confirm API는 여전히 POINT 방식이다.

| 연결하는 책임 | 내부 동작 | 이번에 연결하지 않는 것 |
| --- | --- | --- |
| [OrderPaymentStockService.prepareNew](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentStockService.java) | DRAFT 신규 준비만 수행. 가용 차감·HELD·PG 주문 대기·시도·발송 시작·목록 버전을 함께 저장 | 고객 confirm 재요청의 카드 동일성 판정·실제 PG 호출·발송 권한 조율 |
| `apply` | 저장된 시도와 검증된 거래의 식별·금액을 대조하고 SUCCESS/FAILED/PENDING을 한 번 반영 | raw callback을 신뢰하거나 HTTP 본문을 직접 받아 확정하는 경로 |
| `markUnknown` | 이미 시작한 시도의 확인 불가를 기록하되 예약과 종결 결과 보존 | timeout만으로 실패 처리·반환·재발송 |
| `rejectBeforeCreate` | adapter가 엄격한 효과 0건 거절을 확인한 경우를 위한 내부 적용 경계 | 일반 422·조회 0건으로 이 메서드를 호출하는 정책 |
| Product·관리자 재고 | 현재 가용량에 예약 수량 반환, 관리자 설정 시 모든 HELD의 반환 여유 확인 | 별도 예약 테이블·수량 자료형 확대·기존 삭제 검사 완화 |

`prepareNew`는 **신규 준비 전용**이다. 이미 시도가 있는 주문의 반복 호출은 추가 확보 없이 내부 오류로 거절한다. 이것을 최종 고객 API의 동일 PG 시도 재요청 계약으로 사용하지 않는다. HMAC은 미채택 제안이며 카드 번호·지문 저장을 추가하지 않는다. 준비 결과에는 카드 입력이 없고, 금액은 잠근 주문의 저장 금액에서 정한다.

## 재고와 결과의 연결

다른 변경이 없고 재고 5에서 수량 2를 주문하는 예다.

| 사건 | 주문 / 시도 / 품목 예약 | 가용량 | 목록 버전 |
| --- | --- | ---: | --- |
| TX A 신규 준비 | PAYMENT_PENDING / NOT_OBSERVED·발송 시작 기록 / HELD | 3 | 함께 +1 |
| PENDING 또는 UNKNOWN | PAYMENT_PENDING / 관찰 상태 / HELD | 3 | 불변 |
| SUCCESS | CONFIRMED / SUCCESS / CONSUMED | 3 | 추가 증가 없음 |
| FAILED 또는 검증된 효과 0건 거절 | PAYMENT_FAILED / FAILED / RELEASED | 5 | 반환과 함께 +1 |
| 같은 실패 반복·동시 적용 | 기존 실패·반환 보존 | 5, 7이 아님 | 추가 증가 없음 |
| 반대 종결·금액/식별 충돌 | 기존 저장 결과 보존, CONFLICT | 불변 | 불변 |

관리자가 확보 후 가용 수량을 10으로 설정했다면 실패 반환은 **10 + 2 = 12**다. 예약 당시의 5로 덮어쓰지 않는다. 예약 후 상품·브랜드가 논리 삭제되었어도 결과 처리를 마무리하며 반환으로 삭제 시각을 지우지 않는다. 예약 전 삭제는 신규 준비를 거절한다.

## 트랜잭션·잠금·캐시

- 기존 `REQUIRED`·`READ_COMMITTED`와 비관적 잠금, DB 락 대기 설정을 유지한다. PG 통신을 기존 POINT 서비스의 트랜잭션 안에 넣지 않는다.
- 필요한 기존 행의 상대 순서는 **주문 → 필요한 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순 → 시도 → 목록 revision 마지막**이다. 카드·PG 통신은 이 내부 서비스의 책임이 아니다.
- 관리자 HELD 합계는 **상품 잠금 뒤 비잠금 집계**로 읽는다. 관리자 경로에서 주문을 역순으로 잠그지 않으며 확보·소비·반환도 같은 상품 행을 보호한다.
- 확보·반환은 업무 flush와 revision 증가까지 같은 TX로 묶는다. 성공은 예약만 소비하므로 가용 재고와 revision을 다시 변경하지 않는다. Redis 호출·TTL·키는 변경하지 않는다.
- 외부 트랜잭션에 REQUIRED로 참여했다면 **메서드 반환은 커밋 완료의 증거가 아니다.** 후속 비TX 조율 계층에서 실제 TX A 커밋을 확인한 실행만 PG를 보내도록 별도로 연결해야 한다.

공통 주문 잠금과 저장 제약은 내부 준비·결과의 중복 효과를 막는 수단이다. 이번 증분은 HTTP를 호출하지 않으므로 실제 PG 생성 1회·외부 거래 0/1건·HTTP 동안 DB TX 미보유를 통합 검증했다고 주장하지 않는다. 시도/품목의 저장 관계와 실제 FK 검증도 선행 [SQL 009·010 기록](order-payment-persistence-tdd-log.md)과 구분한다.

## 관리자 반환 여유와 사용자 승인

사용자가 반환 여유 검사를 승인했고, 이번 추가 확인에서 다음 응답을 선택했다.

| 항목 | 확정 계약 |
| --- | --- |
| 검사 | `설정할 가용 수량 + 모든 HELD 예약 합계 ≤ Integer.MAX_VALUE` |
| 경계 | 같으면 허용, 초과하면 거절. HELD가 없으면 기존 최댓값 입력 허용 |
| HTTP / code | `409 STOCK_CAPACITY_EXCEEDED` |
| message | `예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.` |
| 공통 응답·저장 | `meta.result=FAIL`, `data` 생략. 기존 가용량·예약·목록 버전 보존 |

유효한 int 입력도 **현재 예약 상태** 때문에 수용하지 못할 수 있어 기존 `400 INVALID_REQUEST`와 구분했다. 음수·타입·범위 오류의 기존 400 계약과 실제 재고 부족의 기존 409는 유지한다.

### 앞으로 고민할 점 · 이번 필수 구현과 구분

| 주제 | 지금의 선택과 검토할 내용 |
| --- | --- |
| 더 큰 수량이 필요하다면 | int 상한 검사를 먼저 유지한다. Long 확대는 대안이지만 API 숫자 범위·JPA·DDL·이행·기존 테스트를 함께 검토한 뒤 별도로 결정한다. |
| 관리자에게 가능한 최댓값 안내 | `Integer.MAX_VALUE − HELD 합계` 표시를 추가할 수 있으나 이번 필수 사항·채택 정책은 아니다. 표시 뒤 예약이 바뀔 수 있으므로 저장 시 잠금 아래 검사는 여전히 필요하다. |
| 오래 남은 HELD | 단순 시간 초과만으로 해제하면 이미 성공한 외부 결제의 재고를 팔 수 있다. 검증된 외부 결과로 마무리한다. 자동 만료·예약 취소 정책은 이번에 추가하지 않는다. |
| 합계 조회 비용 | 상품별 HELD 합계의 실제 행 수·실행 계획·잠금 대기를 측정한 뒤 인덱스나 별도 합계 보관을 판단한다. 측정 없이 새 캐시·집계 테이블을 도입하지 않는다. |
| 거절을 피하려고 예약 삭제 | 채택하지 않는다. 아직 반환 가능한 예약을 지우면 결제 실패 뒤 정확한 반환과 중복 방지 근거를 잃는다. |

## 테스트와 실행 증거

| 순서 | 실제 관찰·상태 |
| --- | --- |
| 재고 반환 도메인 Red | 새 기대값 18개 실패를 확인했다. |
| 재고 반환 도메인 Green | 관련 3개 스위트 **51개(18+19+14) 통과**, 3초·종료 코드 0. 서비스·DB 경합 완료 증거는 아니다. |
| 최초 DB 통합 실행 | Docker가 꺼져 있어 환경 초기화가 실패했다. 명목상 실패 36개는 업무 동작의 Red로 세지 않는다. 데이터·볼륨 삭제 없이 Docker 시작 후 재실행했다. 컴파일의 AssertJ 타입 추론 수정 1회도 업무 Red에서 제외한다. |
| DB 업무 Red | 실제 MySQL 기동 후 **62개 중 53개 실패**, 29초·종료 1. 내부 서비스 미구현을 확인했다. Integration 34/34·Concurrency 7/7·Rollback 2/2 실패, AdminCapacity 19개 중 10개 실패·9개 통과. |
| 첫 Green 보완 | 관련 93개 중 3개 실패. 손상된 음수 저장 금액 검사가 PaymentAttempt 생성자보다 뒤에 있어 Order의 저장 검사를 먼저 수행하도록 수정했다. rollback 테스트 cleanup의 spy 대상 타입 추론/varargs 문제와 그 정리 실패로 발생한 후속 행 수 오류도 수정했다. 기대값을 완화하지 않았다. |
| 관련 범위 Green | **2026-10-23 13:47:59 KST**, 35초·종료 0. 신규 93개 + ArchUnit 1개 = **94개 통과**, Checkstyle main/test 위반 0. Integration 47·Concurrency 7·Rollback 2·AdminCapacity 19·도메인 18개다. |
| 추가 관리자 경합 | **2026-10-23 13:49:14 KST**, 17초·종료 0. 관리자 설정과 확보/소비/반환의 실제 경합 및 상품 잠금 뒤 비잠금 합계 SQL의 **4개 통과**. Checkstyle test 위반 0. 최초부터 Green이며 인위적 Red로 세지 않는다. 신규 증분 합계는 97개다. |
| 전체 회귀 | **2026-10-23 13:57 KST**, 7분 29초·종료 0·`BUILD SUCCESSFUL`(39개 작업 모두 실행). XML 실측 **98개 스위트·1,209개 통과**, 실패·오류·skip 0. ArchUnit 1개 통과, Checkstyle XML 11개·위반 0. 신규 97개를 포함한 현재 증분의 결과다. |

[최종 테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html). 앞 단계의 992·1,112개 결과를 덮어쓰지 않고 이번 증분 결과를 별도로 기록했다.

| 테스트 파일 | 검증한 범위 |
| --- | --- |
| [ProductReservedStockTest](../../apps/commerce-api/src/test/java/com/loopers/domain/product/ProductReservedStockTest.java) · 기존 ProductStock/Product 테스트 | 양수 반환·오버플로·삭제 상품 반환·현재 가용량 덧셈·기존 차감 규칙 |
| [OrderPaymentStockIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentStockIntegrationTest.java) | 신규 준비, 성공/실패/미확인·중복/충돌, 저장 품목·금액·포인트 보존·삭제 전후 |
| [OrderPaymentStockRollbackTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentStockRollbackTest.java) | 실제 SQL 변경 후 TX A/B 실패·별도 재조회·전체 원복·같은 결과 재적용 |
| [OrderPaymentStockConcurrencyTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentStockConcurrencyTest.java) | 신규 준비·재고 경쟁·중복 실패·상충 종결·관리자/삭제 경합, SQL 잠금 상대 순서와 revision 마지막 |
| [OrderPaymentStockCapacityConcurrencyTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentStockCapacityConcurrencyTest.java) | 관리자 설정과 확보/소비/반환의 상한 경합 3개, 상품 잠금 뒤 비잠금 HELD 합계·주문 역순 잠금 없음 1개 |
| [AdminReservedStockCapacityTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/AdminReservedStockCapacityTest.java) | 모든 HELD 합계·상한 동등/초과·상태 보존·사용자 승인 409 응답 |

실제 DB Red 명령:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*OrderPaymentStock*Test' \
  --tests '*AdminReservedStockCapacityTest' --console=plain
```

관련 범위 Green 명령(당시 추가 관리자 경합 4개 작성 전):

```bash
./gradlew :apps:commerce-api:test \
  --tests '*OrderPaymentStock*Test' \
  --tests '*AdminReservedStockCapacityTest' \
  --tests '*ProductReservedStockTest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

추가 경합과 전체 회귀 명령:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*OrderPaymentStockCapacityConcurrencyTest' \
  :apps:commerce-api:checkstyleTest --console=plain
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

`ttl-measurement` 태그의 장시간 TTL·메모리 측정만 별도 작업이므로 이번에 재실행하지 않는다. 해당 W4 수치는 당시 측정 기록으로 보존한다. 기존 기동 직후·혼합 부하 등의 테스트는 기본 전체 회귀에 포함되며 모든 성능 측정을 제외한다는 뜻은 아니다.

동시성 테스트는 worker 시작을 맞추고 각각 실제 서비스 프록시를 호출한다. 모든 실행 순서를 강제로 재현했다는 뜻은 아니다. 유한 대기와 executor 종료를 확인한 후 별도 JDBC 조회로 최종 상태를 대조하며, 기술 오류를 정상 업무 거절로 세지 않는다.

## 아직 남은 단계

1. 요청 동일성 방식·새 Commerce DTO/오류를 결정하고 기존 confirm을 PG 흐름으로 교체한다. HMAC은 계속 제안이다.
2. 비TX 조율: TX A 커밋 → 실제 HTTP → TX B 연결, 실제 동시 POST 1회·HTTP 동안 DB TX/업무 잠금 미보유를 검증한다.
3. callback 권위 조회·수동 reconcile·재시작/외부 결과 복구·제한 조회·서킷브레이커를 연결한다.
4. 최종 PG 교체 회귀·문서·제출 증거를 정리한다. 이번 증분은 W5 전체 완료가 아니다.

사용자 DB/볼륨 삭제·초기화·수동 SQL 이행·커밋·PR 제출은 하지 않는다.
