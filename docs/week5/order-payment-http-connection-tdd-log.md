# W5 4단계 두 번째 증분 · PG HTTP와 주문·재고 연결

후속 선택·정정(2026-10-23): 요청 동일성 판별에 HMAC-SHA256을 기술안으로 선택했으며 코드·DDL·검증은 아직 미완료다. 과제 해석 질문은 필요 시 선택적으로 확인하며 담당자 확인·승인은 구현의 필수 전제가 아니다. 아래의 ‘제안·미채택·허용 확인 전’ 표현은 당시 판단 기록으로 보존하며 [현재 선택과 정정](../week2/commerce-policy-decisions.md#w5-hmac-요청-지문-제안)을 따른다.

2026-10-23. [과제 원문](assignment-original.md) · [전체 설계](../week2/commerce-erd-draft.md#w5-pg-결제-설계) · [API 계약](../week2/commerce-api-contract.md#w5-pg-결제-api-설계) · [정책 선택](../week2/commerce-policy-decisions.md#w5-결제-교체의-선택과-질문) · [TDD 계획](../week2/commerce-tdd-plan.md#w5-pg-결제-tdd-계획)

## 이번 범위

[독립 HTTP adapter](local-test-pg-http-tdd-log.md)와 [내부 재고 TX A/B](order-stock-reservation-tdd-log.md)를 실제 HTTP·MySQL 흐름으로 연결했다. 고객 controller·DTO는 변경하지 않았다. **기존 고객 confirm API는 아직 POINT 경로**이며 이 기록은 W5 전체 완료나 고객 PG 결제 API 완료를 뜻하지 않는다.

| 책임·파일 | 이번 구현 | 포함하지 않은 것 |
| --- | --- | --- |
| [OrderPaymentCoordinator](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentCoordinator.java) · `startNew` | 신규 DRAFT 준비 커밋 → 실제 PG POST 한 번 → 관찰 결과를 TX B로 반영 | 기존 시도 재요청의 카드 동일성·HMAC·고객 HTTP 200/202 결정 |
| 같은 조율의 `refreshOnce` | 저장된 제공자 주문/거래 식별자로 GET 한 번. 종결 상태면 저장 결과 반환, 미완료면 검증·TX B 적용 | 고객 reconcile API·callback·최초 confirm의 180초 제한 조회·Circuit Breaker |
| [OrderPaymentStockService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentStockService.java) | 기존 TX A/B·락 유지. 소유권을 확인한 조회용 불변 snapshot과 충돌 시 무변경 반환 경계 추가 | 새로운 예약 테이블·수량 타입·오류 응답·충돌 감사 저장 구조 |
| [LocalTestPg](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/support/LocalTestPg.java) | 실제 거래를 저장한 뒤 생성 SUCCESS/FAILED를 결정적으로 응답하는 테스트 모드 추가. 기본 PENDING 유지 | 무작위 지연/성공률·실제 PG 제품·callback 발송 |

`providerOrderId`는 기존 내부 준비 인자를 그대로 사용한다. 고객 API의 외부 ID 매핑 형식을 새로 확정하지 않았다. 카드 타입·번호는 PG 계약에 맞는 일시 입력만 사용하고 금액은 잠근 주문의 저장 금액에서 얻는다. 입력 검증이 TX A 뒤에 실패해 불필요한 예약을 남기지 않도록 카드·외부 ID의 기존 입력 조건을 준비 전에 확인한다. 카드 지문/원문·추가 DDL을 저장하지 않는다.

기존 `commerce.test-pg.enabled=false` 기본값은 유지한다. true일 때 내부 조율과 adapter를 연결할 뿐 테스트 PG 서버를 자동 기동하거나 고객 confirm을 자동 교체하지 않는다.

## 트랜잭션·락과 선택 이유

```text
조율 진입: 이미 열린 DB TX / 동기화 범위가 있으면 거절
  → 별도 Spring 프록시의 prepareNew
      TX A: 주문·재고·예약·시도·발송 기록·목록 버전 → commit
  → DB TX 밖 실제 PG HTTP
  → 별도 Spring 프록시의 apply / rejectBeforeCreate / markUnknown
      TX B: 검증된 결과·주문·예약·필요한 반환·목록 버전 → commit
```

- 기존 `REQUIRED`·`READ_COMMITTED`·DB 락 대기 설정을 변경하지 않았다. 재고 변경의 **주문 → 필요한 사용자 → 브랜드 ID↑ → 상품 ID↑ → 시도 → revision 마지막** 순서도 유지한다.
- 바깥 TX가 없는 조율에서 **별도 TX 프록시가 성공적으로 반환**한 뒤에만 POST한다. 같은 객체의 자기 호출이나 어노테이션 존재만으로 커밋됐다고 가정하지 않는다.
- `NOT_SUPPORTED`로 외부 TX를 잠시 중지하는 대안은 선택하지 않았다. 중지된 외부 TX의 락은 남을 수 있기 때문이다. 진입을 거절하면 호출자가 경계를 명시적으로 바로잡아야 하는 비용이 있지만 HTTP 중 기존 TX/락 보유를 예방할 수 있다.
- 조회용 snapshot은 주문→시도 잠금으로 상태와 식별자를 함께 읽고 커밋한 뒤 HTTP로 넘긴다. 재고를 변경하지 않는 이 조회에는 상품 잠금을 추가하지 않는다. 결과 적용은 기존 TX B에서 다시 현재 상태·식별·금액을 검사하므로 조회 이후의 종결 경합도 보호한다.
- HTTP/DB 오류를 하나의 실패로 합치지 않는다. TX B 예외는 그대로 전달하며 PG FAILED·가짜 202로 숨기지 않는다. 이미 커밋한 TX A와 외부 거래는 TX B rollback으로 취소되지 않는다.
- 예약 차감·확정 실패 반환과 DB 목록 버전의 공동 커밋은 유지한다. SUCCESS는 예약 소비만 하므로 재고·버전을 다시 차감/증가하지 않는다. Redis 키·TTL은 변경하지 않았다.

## 실제 HTTP와 DB를 함께 확인한 기대값

다른 변경이 없는 재고 5·주문 수량 2·저장 금액 200을 기준으로 한다. 기본 흐름 테스트의 초기 포인트 10,000은 모든 PG 경로에서 10,000으로 보존한다. 별도 경계 테스트의 초기 포인트 0도 그대로 0이다.

| 장면 | 실제 외부 거래 / POST | 내부 결과 | 가용 재고 |
| --- | --- | --- | ---: |
| 생성 PENDING | 1 / 1 | PAYMENT_PENDING·PENDING·HELD | 3 |
| 생성 또는 GET으로 SUCCESS | 1 / 1 | CONFIRMED·SUCCESS·CONSUMED | 3 |
| 생성 또는 GET으로 FAILED | 1 / 1 | PAYMENT_FAILED·FAILED·RELEASED | 5 |
| 엄격한 생성 전 422 거절 | 0 / 1 | FAILED·RELEASED, 거래키 null. 같은 검증 거절 재적용에도 반환 추가 0 | 5 |
| 거래 생성 후 응답 유실·실제 timeout | 1 / 1 | UNKNOWN·HELD. 새 client의 기존 거래 조회로 복구 | 3 |
| 일반 오류·불완전 422·조회 404/빈 목록 | 외부 효과를 0으로 단정하지 않음, 새 POST 없음 | UNKNOWN·HELD, 실패·반환 아님 | 3 |
| 식별·금액 불일치·복수 거래 | 재생성·첫 거래 임의 선택 없음 | CONFLICT, 기존 저장 사실 보존 | 불변 |
| 사전 검사 실패·TX A SQL 후 rollback | 0 / 0 | 준비 전체 원복. 조건 개선 후 신규 준비 가능 | 기존 값 |
| 외부 SUCCESS/FAILED 뒤 TX B SQL 후 rollback | 1 / 1 | 결과 반영 전 상태로 원복. 같은 거래 GET 재적용으로 한 번 종결 | rollback 뒤 3 |
| 발송 기록 커밋 후 HTTP 전 중단 장면 | 0 / 0 | 조회 0건이어도 UNKNOWN·HELD. 새 POST 불가 | 3 |
| 엄격 거절 TX B rollback 후 증거 유실 | 0 / 1 | 조회 0건만으로 거절을 복원하지 못함. UNKNOWN·HELD·재발송 없음 | 3 |

마지막 두 경우처럼 중복 발송 방지가 모든 시도의 자동 완료를 보장하지는 않는다. 원응답 증거의 별도 영속화나 시간 경과에 따른 예약 해제를 새 정책으로 채택하지 않았다.

## 테스트와 TDD 실행

| 파일 | 검증 범위 |
| --- | --- |
| [OrderPaymentHttpIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentHttpIntegrationTest.java) | 실제 생성/조회와 DB 연결, 엄격 거절·UNKNOWN·재시작·식별 충돌·복수 거래, 기존 소유권·재고·삭제·POINT 보호, 입력 실패 전 무변경 |
| [OrderPaymentHttpBoundaryIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentHttpBoundaryIntegrationTest.java) | 외부 TX 진입 거절, 실제 HTTP 대기 중 커밋 관찰/관리자 변경 완료, 동시 신규 생성 1회, TX A/B 실제 SQL 후 rollback·재조회, 먼저 종결한 결과와 늦은 PENDING/timeout 경합, 거절 증거 유실 |
| [OrderPaymentCoordinatorWiringTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderPaymentCoordinatorWiringTest.java) | 설정 OFF 시 미활성, ON 시 내부 bean/adapter 연결·시작 시 발송 없음 |

동시 신규 `startNew`는 승자 한 실행만 준비·POST하고 패자는 내부 신규 전용 오류다. **같은 고객 confirm의 재요청을 저장 결과로 반환하는 최종 계약까지 검증한 것이 아니다.** 테스트가 내부 경계의 거절을 고객 API 기대값으로 고정하지 않는다.

| 실행 | 결과 |
| --- | --- |
| 첫 Red | 미구현 조율에서 24개 중 20개 실패. 기존 사전 검사 4개의 넓은 예외 단언을 실제 기존 타입·사유로 강화 |
| 경계 포함 Red | 31개 전부 실패, BUILD FAILED·종료 1·21초. HTTP·MySQL 환경은 정상이며 연결 미구현으로 실패 |
| 첫 Green | 같은 31개 통과, BUILD SUCCESSFUL·종료 0·20초 |
| 보강·관련 회귀 | HTTP·DB 신규 38개(기본 26·경계 12)와 기존 PG HTTP·재고·ArchUnit을 합해 **9개 스위트·200개 통과**, 실패·오류·skip 0. Checkstyle main/test 통과, BUILD SUCCESSFUL·종료 0·36초 |
| 최종 전체 검사 | **2026-10-23 14:17 KST, 101개 스위트·1,249개 통과**, 실패·오류·skip 0. 신규 40개(기본 26·경계 12·wiring 2) 포함. ArchUnit 1개 통과, Checkstyle 보고서 11개·위반 0. BUILD SUCCESSFUL·종료 0·7분 27초, 39개 task 모두 실행 |

실행한 주요 명령은 다음과 같다.

```bash
# 미구현 연결의 Red → 동일 사례 Green
./gradlew :apps:commerce-api:test --tests '*OrderPaymentHttp*Test' --console=plain

# 보강한 실제 HTTP·DB 연결 + 기존 경계 회귀 200개
./gradlew :apps:commerce-api:test --tests '*OrderPaymentHttp*Test' --tests '*PaymentProviderContractTest' --tests '*LocalTestPgTest' --tests '*OrderPaymentStock*Test' --tests '*ArchitectureTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain

# 최종 전체 재검사: 새 wiring 2개 및 기존 회귀 포함
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

기존 POINT·포인트 API·주문·관리자·삭제·상품/캐시·SQL 스키마 회귀를 포함한다. 기본 check에서 분리한 장시간 `ttlMeasurement`는 이번에도 별도로 재실행하지 않았다. [HTML 테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)는 로컬 생성물이며 결과 건수는 XML을 합산했다. 변경 diff 공백 검사와 갱신한 문서의 로컬 링크 대상 존재를 확인했고, 독립 코드 검토에서도 이번 내부 연결 범위의 명백한 오류는 발견하지 못했다.

실제 DB 테스트는 격리된 기존 Testcontainers MySQL을 사용한다. SQL 후 실패 주입은 테스트의 spy에서 실제 메서드 실행·flush·변경된 DB 확인 이후 수행하고, TX가 끝난 뒤 별도 JDBC 조회로 rollback을 검증한다. HTTP 대기 중 관리자 변경이 끝나는지와 HTTP 호출 스레드의 실제 TX 비활성을 함께 확인한다. 테스트가 생성한 HTTP 서버·client·worker만 유한 대기 후 종료한다.

## 다음 연결 전에 결정할 것과 남은 범위

1. HMAC은 여전히 미채택 제안이다. 같은 UUID의 카드 변경까지 판별하는 방식을 정해야 최종 고객 confirm의 재요청을 연결할 수 있다. 고객 입력 DTO·새 응답 필드·오류/충돌 표현·외부 주문 ID 매핑도 미정 계약을 확인한다.
2. 고객 confirm·GET/관리자 조회의 결제 표현과 reconcile·callback은 별도 연결한다. 기존 POINT 사실·재확정 오류를 보존한다.
3. 승인된 최초 180/175초 예산·최대 12회 조회·간격·Circuit Breaker를 연결한다. 이번 `refreshOnce`는 내부 단발 조회이며 즉시 202 정책 선택이나 긴 조회/CB 완료가 아니다.
4. callback ACK·별도 reconcile 예산/충돌 기록을 정하고 고객 API부터 실제 HTTP·DB까지의 통합 검증을 마무리한다.

사용자 DB·볼륨 삭제/초기화·수동 스키마 적용·커밋·PR 제출은 하지 않았다. 이전 [1,209개 통과 기록](order-stock-reservation-tdd-log.md)은 그 증분의 과거 증거로 유지한다.
