# W3 재고 갱신 유실 대조군 검증 계획과 실행 기록

[전체 설계](../week2/commerce-erd-draft.md) · [정책 선택](../week2/commerce-policy-decisions.md#2-도메인-상태-저장과-동시성) · [TDD 계획](../week2/commerce-tdd-plan.md#w3-갱신-유실-대조군의-tdd-계획) · [완료 체크리스트](../week2/commerce-completion-checklist.md#w3-갱신-유실-대조군)

재고 5를 두 작업이 각각 읽고 1개씩 차감했는데도 최종 재고가 3이 아닌 4로 남는 상황을 재현한다. **트랜잭션 두 개가 모두 커밋되어도 오래된 조회값을 덮어쓰면 차감 효과가 사라질 수 있다**는 점을 확인하는 음성 대조군이다. 음성 대조군은 잘못된 처리 방식을 테스트 안에 한정해 원인을 설명하는 예제이며, 실제 주문 서비스의 동작이나 새 제품 정책이 아니다.

**현재 상태: 테스트 구현·관련 회귀·전체 검사 완료.** 기준 ID는 `W3-LOST-UPDATE-01`이다. 출처는 사용자가 제공한 W3 「Implementation Quest」의 「4. 실패·경쟁 결과 확인 — 공통 · 갱신 유실을 재현하는 작은 대조군」과 후속 구현 요청이다. 기존 545개 테스트 통과 기록은 이전 증분의 증거로 보존하며 이번 실행 결과와 구분한다.

## 재현할 문제

아래는 두 작업이 먼저 같은 값을 읽고 저장하는 예시다. A와 B 중 누가 먼저 저장하는지는 고정하지 않는다.

| 단계 | 작업 A | 작업 B |
| --- | --- | --- |
| 초기 상태 | 커밋된 동일 상품의 재고 5를 사용 | 같은 상품을 사용 |
| 조회 | 일반 SELECT로 5를 읽고 보관 | 일반 SELECT로 5를 읽고 보관 |
| 계산 | 보관한 값에서 1을 빼 4 계산 | 보관한 값에서 1을 빼 4 계산 |
| 저장 | 계산한 상수 4로 UPDATE 후 커밋 | 계산한 상수 4로 UPDATE 후 커밋 |
| 두 작업 종료 후 | 두 커밋은 성공했지만 최종 재고는 4 | 정상적인 두 번의 차감 결과인 3과 다름 |

`2 + 4 ≠ 5`는 **차감했다고 집계한 수량과 남은 재고의 합이 초기 재고와 맞지 않는다**는 뜻이다. 여기서 성공 2건은 대조군의 차감 작업이 커밋된 횟수이며, 실제 주문 확정 2건이나 HTTP 성공 2건을 의미하지 않는다.

이 대조군은 재고 4와 불변식 위반이 재현됐다고 assertion하면 통과한다. 정상 기대값 3을 넣어 실패하는 테스트를 방치하거나, 실제 서비스의 잠금을 제거해 실패를 만드는 작업이 아니다.

## 테스트 진행 순서

[StockLostUpdateControlTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/StockLostUpdateControlTest.java)에 구현했다. HTTP·`OrderService`·상품 잠금 Repository를 호출하지 않고 테스트 내부의 JDBC 읽기·쓰기만으로 원인을 분리한다. 테스트 전체를 감싸는 부모 `@Transactional`은 두지 않는다.

| 순서 | 구현한 처리 | 확인하는 증거 |
| --- | --- | --- |
| 1. 데이터 준비 | 기존 MySQL fixture와 저장소로 브랜드·재고 5의 상품을 준비하고 worker 실행 전에 커밋한다. 비교용 다른 상품도 준비한다. | 시작 전 별도 조회에서 대상 재고 5, 비교할 행의 초기 값 |
| 2. 독립 실행 시작 | worker 스레드 2개에서 각각 `TransactionTemplate`으로 READ_COMMITTED 트랜잭션을 시작한다. | 새 트랜잭션·실제 트랜잭션 활성, 서로 다른 `CONNECTION_ID()` |
| 3. 조회 후 대기 | 각 worker가 `FOR UPDATE` 없는 SELECT로 재고를 읽고 보관한 뒤 저장을 기다린다. | 두 조회값이 모두 5. 조회 결과를 메인 테스트에 안전하게 전달 |
| 4. 쓰기 허용 | 메인 테스트가 두 조회값과 서로 다른 연결을 확인한 다음 쓰기 대기를 해제한다. | 두 조회가 끝나기 전에는 어느 worker도 UPDATE하지 않음 |
| 5. 오래된 값 저장 | 각 worker는 자신이 보관한 값에서 1을 뺀 4를 같은 연결의 UPDATE에 바인딩한다. | 조회·저장이 같은 worker 트랜잭션 안에서 실행됨 |
| 6. 커밋 완료 수집 | `TransactionTemplate.execute()`가 끝난 후 worker 결과를 반환하고 두 Future의 완료를 기다린다. | 콜백 본문 종료가 아니라 커밋 완료를 포함한 성공 2건, 기술 오류 0건 |
| 7. 최종 DB 조회 | 두 worker가 종료된 뒤 별도 JDBC 조회로 최종 상태를 확인한다. | 최종 재고 4, 불변식 위반, 대상 재고 외 값 보존 |

저장 SQL의 형태는 다음과 같다. 첫 UPDATE 인자는 각 worker가 계산한 `읽은 값 - 1`, 즉 4다. WHERE에는 대상 기본 키만 두고 재고 비교·version 조건은 추가하지 않는다.

```sql
SELECT stock_quantity FROM product WHERE id = ?;
UPDATE product SET stock_quantity = ? WHERE id = ?;
```

`SET stock_quantity = stock_quantity - 1`로 바꾸면 DB의 현재 값을 감소시키는 다른 실험이 된다. 이번 대조군은 **애플리케이션이 이미 읽은 값으로 계산한 상수를 덮어쓰는 문제**를 재현한다.

## 통과 조건과 오류 구분

다음은 하나의 대조군 사례에서 함께 확인할 조건이다. 개별 행을 별도 테스트 실행 건수로 세지 않는다.

| 확인 대상 | 기대값 또는 통과 조건 |
| --- | --- |
| 읽은 값 | A=5, B=5 |
| 실행 경계 | 독립된 트랜잭션 2개·서로 다른 DB 연결 2개, 각 worker의 조회와 저장은 같은 연결 |
| 결과 집계 | 커밋 성공 2건, 기술 오류 0건, 전체 작업 2건 |
| 최종 재고 | 4 |
| 깨진 불변식 | 성공 작업의 차감 수량 합 2 + 최종 재고 4가 초기 재고 5와 같지 않음. 정상적인 최종 재고는 3 |
| 다른 값 보존 | 대상 상품의 재고 외 컬럼, 다른 상품·브랜드 등 준비한 무관한 행은 변경하지 않음 |
| 오류 처리 | 읽기·쓰기 대기 시간 초과, SQL 오류, 커밋 실패, worker 미종료는 테스트 실패. 갱신 유실 재현 성공으로 바꾸지 않음 |

UPDATE 반환 행 수만으로 성공을 집계하지 않는다. 두 번째 쓰기가 이미 4인 값을 다시 4로 지정하면 드라이버 설정에 따라 반환 행 수의 의미가 달라질 수 있다. [Connector/J의 useAffectedRows 설명](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-connection.html)을 참고한다. 성공은 예외 없이 커밋까지 끝났는지로 판정한다. 이전 롤백 검증의 `JdbcWriteFailureProbe`는 UPDATE 반환 행 수가 양수임을 요구하며 예외를 주입하는 장치이므로 여기서는 사용하지 않는다.

## 대기와 자원 정리

- 두 조회 완료 대기·쓰기 허용 대기는 각각 5초, Future 완료 대기·executor 종료 대기는 각각 10초를 초기 테스트 한도로 둔다. 이는 테스트의 대기 한도이며 제품의 DB 잠금 타임아웃 3초를 변경하지 않는다. JDBC 실행·트랜잭션에도 유한한 timeout을 설정해 Future 취소만으로 DB 작업이 끝났다고 가정하지 않는다.
- 테스트 전용 `JdbcTemplate`의 기본 query timeout은 5초, `TransactionTemplate` timeout은 10초다. 트랜잭션 안에서는 남은 트랜잭션 시간이 JDBC timeout보다 우선 적용되므로 모든 SQL이 반드시 5초에 중단된다는 뜻은 아니다. 공유 빈 설정은 바꾸지 않았다. [Spring JdbcTemplate 설명](https://docs.spring.io/spring-framework/docs/6.2.5/javadoc-api/org/springframework/jdbc/core/JdbcTemplate.html#setQueryTimeout(int))을 따른다.
- assertion이나 대기가 실패해도 `finally`에서 쓰기 대기를 해제하고, 남은 Future를 취소한 뒤 executor 종료를 요청한다. 제한 시간 안에 모든 worker가 종료됐는지 확인한다.
- Connection의 commit·rollback·반환은 기존 Spring 트랜잭션 관리에 맡긴다. 별도 DataSource나 수동 commit 경로를 만들지 않는다. worker 종료가 확인되기 전에는 DB 테이블을 정리하지 않는다.
- 조회 후 기다리는 장벽은 이 대조군 안에만 둔다. 다음 실제 서비스 경쟁 테스트에는 시작 장벽만 사용하고, 잠금 보유 구간에 장벽이나 sleep을 넣지 않는다.

## 실제 서비스와 다른 점

| 비교 | 갱신 유실 대조군 | 기존 실제 서비스 |
| --- | --- | --- |
| 목적 | 잘못된 읽기·쓰기 방식의 결과 재현 | 정상 업무 처리와 데이터 정합성 보장 |
| 진입점 | 테스트 worker → TransactionTemplate → JdbcTemplate | HTTP 또는 서비스 호출 → Spring 프록시 → 서비스·Repository |
| 동시성 보호 | SELECT의 명시적 행 잠금과 version 검사를 사용하지 않음 | P09·P13의 비관적 잠금 후 검증·변경 |
| 실행 조정 | 둘 다 5를 읽은 뒤 쓰기를 허용 | 실제 서비스 검증은 worker의 시작만 맞춤 |
| 성공의 의미 | 잘못된 최종 재고 4를 재현한 테스트가 통과 | 성공한 주문 수량·금액과 최종 재고·잔액이 일치 |

대조군에도 UPDATE의 DB 행 잠금은 존재한다. “DB 락이 전혀 없다”는 설명은 사용하지 않는다. 조회 때의 값을 두 작업이 각각 보관한 상태에서 같은 4를 덮어쓰는 것이 문제다. [MySQL 8.0 잠금 설명](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html), [UPDATE 설명](https://dev.mysql.com/doc/refman/8.0/en/update.html)을 참고한다.

## 영향 범위와 후속 검증

| 대상 | 이번 변경 |
| --- | --- |
| 본 문서 | 계획을 유지하고 구현 범위·실제 실행 결과를 추가 |
| 전체 설계·정책 선택 | 대조군의 구현·검증 상태 갱신. 제품 정책·잠금 선택은 유지 |
| TDD 계획·완료 체크리스트 | `W3-LOST-UPDATE-01`의 완료 증거와 최신 검사 결과 기록 |
| 테스트 코드 | `StockLostUpdateControlTest` 신규 추가. 기존 MySQL fixture·정리 도구 재사용 |
| 서비스·Repository·API·스키마·설정 | 변경 없음. 락을 제거하거나 우회 API를 추가하지 않음 |

새 대조군 테스트 → 기존 주문 트랜잭션·동시성 테스트 → 전체 `:apps:commerce-api:check` 순서로 검증한다. 테스트 수·실패 원인·종료 결과, Checkstyle·ArchUnit은 아래 실제 실행 결과만 기록한다. 문서만 작성한 이전 단계에서는 Gradle을 실행하지 않았으며 과거의 545개 통과 수치는 그대로 보존한다.

이 대조군의 통과만으로 실제 주문 서비스의 동시성까지 검증됐다고 표시하지 않는다. 다음 작업은 재고 5의 8개 주문 경쟁이며, 이어서 포인트 3개 주문 경쟁과 지정 금액의 충전·결제 경쟁을 검증한다. 제출용 기술 글은 이 재현 결과를 재료로 활용하되 별도 제출 범위로 관리한다.

## 실행 기록 — 2026-10-08

### 테스트 우선 검증과 변경 판단

| 단계 | 실제 수행·결과 |
| --- | --- |
| 테스트 먼저 추가 | 합의한 읽기 5·5 → 상수 4 저장 → 커밋 2·오류 0 → 최종 4와 불변식 위반을 하나의 테스트로 작성 |
| 최초 실행 | 20:37 KST, 단독 테스트 1개·Checkstyle test 통과. 재현 자체가 기대값이므로 최초 실행부터 Green이며 업무 Red를 관찰한 것으로 기록하지 않음 |
| 구현 변경 판단 | 실제 서비스가 아니라 테스트 전용 대조군을 구현한 증분이다. 서비스의 잠금을 제거하거나 정상 기대값을 완화해 인위적인 Red를 만들지 않음 |
| 리뷰·정리 | 실행 전에 혼입된 주석·메시지의 오기를 수정. 읽기 증거·커밋 결과·최종 DB 상태를 나눠 검증하며 별도 생산 코드 리팩터링은 하지 않음 |

### 실제 확인한 결과

| 관찰·검증 대상 | 통과한 assertion |
| --- | --- |
| 읽기와 트랜잭션 | 두 worker 모두 재고 5, READ-COMMITTED, autocommit=false, 새 트랜잭션·실제 활성 상태. 서로 다른 DB 연결이며 각 worker의 읽기·쓰기 전후 연결 ID는 동일 |
| 쓰기 전 상태 | 메인 테스트가 두 조회값을 검증하기 전에는 쓰기 불가. 대기 중 전체 6개 테이블이 준비 직후와 동일 |
| 커밋 집계 | `execute()` 정상 반환 후에만 성공으로 수집. 커밋 성공 2·기술 오류 0, 각 작업의 계산값 4 |
| 최종 상태 | 부모 트랜잭션 없이 worker 종료 뒤 다시 조회한 재고 4. 차감했다고 집계한 수량 2 + 재고 4 ≠ 초기 5이며 정상 차감 결과 3과 다름 |
| 영향 범위·정리 | 6개 테이블을 비교해 대상 재고 외 모든 행·컬럼 보존, 두 worker 종료 확인 후 DB 정리 |

이 사례는 실제 주문 확정이나 HTTP 응답 검증이 아니다. 오류 처리·정리 경로는 코드에 마련했지만 timeout·SQL 실패·worker 미종료를 각각 강제 주입한 별도 테스트까지 수행한 것은 아니다.

### 실행 명령

```bash
./gradlew :apps:commerce-api:test --tests '*StockLostUpdateControlTest' :apps:commerce-api:checkstyleTest --console=plain -q

./gradlew :apps:commerce-api:test --tests '*StockLostUpdateControlTest' --tests '*OrderTransactionTest' --tests '*OrderConcurrencyIntegrationTest' --tests '*OrderServiceIntegrationTest' :apps:commerce-api:checkstyleTest --console=plain -q

./gradlew :apps:commerce-api:check --console=plain -q
```

| 실행 시점 (KST) | 검사 | 실제 결과 |
| --- | --- | --- |
| 20:37 | 새 대조군·Checkstyle test | 테스트 1개 통과, 종료 코드 0 |
| 20:38 | 대조군 + 기존 주문 회귀·Checkstyle test | 4개 스위트·15개 통과. 대조군 1·OrderTransactionTest 4·OrderConcurrencyIntegrationTest 7·OrderServiceIntegrationTest 3, 실패·오류·건너뜀 0 |
| 20:40 | 전체 `:apps:commerce-api:check` | 종료 코드 0. XML **57개 스위트·546개 통과**, 실패·오류·건너뜀 0. 기존 56개·545개 대비 신규 대조군 1개 증가 |
| 20:40 | 전체 검사에 연결된 정적 검사 | Checkstyle XML 11개 보고서 위반 0, ArchitectureTest 1개 통과. 기존 규칙 유지 |

단독·관련 회귀·전체 검사에서 같은 대조군을 각각 실행했다. 이를 서로 다른 테스트 3개로 세지 않으며 모든 스케줄링 순서나 부하 환경을 보장하는 증거로 확대하지 않는다.
