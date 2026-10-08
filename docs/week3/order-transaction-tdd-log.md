# W3 주문 저장 경계 실패·롤백 TDD 실행 기록

[전체 설계](../week2/commerce-erd-draft.md#최초-확정의-실제-트랜잭션-진입과-sql-저장-경계) · [API 계약](../week2/commerce-api-contract.md#고객-api-계약표) · [TDD 계획](../week2/commerce-tdd-plan.md#w3-주문-저장-경계-실패와-롤백의-tdd-계획) · [완료 체크리스트](../week2/commerce-completion-checklist.md)

2026-10-08 사용자가 승인한 다음 증분은 **주문 확정에서 실제 변경 SQL 이후 저장 실패가 나도 재고·포인트·주문 결과가 함께 롤백되는지** 검증하는 것이다. 기존 서비스 코드·트랜잭션·락은 유지하고 테스트와 문서만 추가했다. 테스트가 값을 복구하는 것이 아니라, 서비스 트랜잭션이 복구한 결과를 요청 종료 후 확인한다.

## 변경 책임과 검증 사례

| 파일 | 책임 |
| --- | --- |
| [OrderTransactionTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderTransactionTest.java) | 실제 HTTP → Spring 서비스 → MySQL의 정상 확정·저장 중간 실패·동일 DRAFT 재시도, 응답과 DB 상태 검증 |
| [JdbcWriteFailureProbe](../../apps/commerce-api/src/test/java/com/loopers/support/JdbcWriteFailureProbe.java) | 테스트에서만 JDBC 실행을 감싸 실제 UPDATE 성공 뒤 다음 UPDATE에 일반 SQLException을 한 번 주입하고 증거 수집 |
| 전체 설계·TDD 계획·완료 체크리스트·본 기록 | 실제 프록시 진입·저장 경계, 기대값, 실제 실행 결과와 남은 범위 구분 |

공통 준비는 A 단가 1,000원·재고 5·주문 수량 2, B 단가 2,000원·재고 4·주문 수량 1, 구매자 잔액 10,000원이다. 주문 총액은 4,000원이다. 다른 브랜드·상품, 다른 구매자의 확정 주문과 잔액, 좋아요 관계도 만들어 무관한 데이터 보존을 확인한다. 모든 준비를 커밋한 뒤 실패 장치를 켠다.

| ID | 실행 사례 수 | 확인한 결과 |
| --- | ---: | --- |
| W3-ORDER-TX-01 | 1 | 정상 확정 `200 SUCCESS`. 재고 A=3·B=3, 잔액 6,000, CONFIRMED·결제액 4,000·확정 시각 저장. 품목 스냅샷·다른 데이터 보존 |
| W3-ORDER-TX-02 | 2 | UPDATE 1회 성공 후 2번째 실패 / 3회 성공 후 4번째 실패. `500 INTERNAL_ERROR`·`meta.result=FAIL`·일반 오류 메시지·`data` 생략. 내부 예외 내용 미노출. 실패 직전 실제 변경 행 각각 1개·3개 확인 후 종료 시 6개 테이블 전체 원복 |
| W3-ORDER-TX-03 | 1 | 동일한 저장 실패와 원복을 확인하고 실패 장치를 해제. 같은 DRAFT를 재시도하면 TX-01 결과로 한 번만 차감. 성공 이후 재확정은 기존 `409 ORDER_ALREADY_CONFIRMED`이며 전체 저장 상태 불변 |

## 실제 롤백을 확인한 방법

1. 테스트에 `@Transactional`을 붙이거나 호출자 `TransactionTemplate`으로 요청을 감싸지 않는다. RANDOM_PORT의 실제 HTTP 요청이 `OrderService` 프록시의 트랜잭션을 시작한다.
2. 테스트에서 `mySqlMainDataSource`의 실제 Connection을 감싼다. 실제 DB 조회·잠금·UPDATE는 원래 Connection으로 실행하고 commit·rollback·autoCommit 설정을 대신 처리하지 않는다.
3. `order`·`user`·`product` UPDATE의 JDBC execute가 실제로 성공하고 영향 행이 양수인 경우에만 성공 횟수를 센다. SQL 준비 관찰이나 repository 호출 횟수만으로 저장을 주장하지 않는다.
4. 지정한 다음 UPDATE를 실행하기 직전에 같은 Connection으로 6개 테이블의 미커밋 상태를 읽고, 일반 `SQLException("test-only: order write failed", "HY000", 0)`을 한 번 발생시킨다. 실제 트랜잭션 활성·autoCommit=false도 수집한다.
5. HTTP 종료 후 수집된 증거를 assertion한다. 실제 UPDATE 성공 1회/3회, 실패 주입 1회, 변경된 실제 행 1개/3개와 무관한 행 보존을 확인한다. 내부 콜백에서 assertion을 던져 이를 서버 오류로 오인하지 않는다.
6. 별도 `JdbcTemplate` 조회로 요청 전후 `brand`·`product`·`order`·`order_item`·`user`·`like`의 **모든 행·컬럼을 비교**한다. 재고·잔액뿐 아니라 DRAFT·결제액·확정 시각·품목·감사 시각도 이전 값이어야 한다. 데이터 정리는 이 비교가 끝난 뒤 수행한다.

실패 장치는 `finally`와 테스트 종료 시 해제한다. 다른 Connection의 대상 UPDATE 혼입이나 지원하지 않는 UPDATE batch가 생기면 구성 오류로 실패시킨다. 이 경우에는 정상 실패 주입 횟수를 기록하지 않으므로 단순 `500` 응답만으로 테스트를 통과할 수 없다. 현재 설정에는 JDBC 쓰기 batch가 없다.

## 트랜잭션·락과 테스트 선택 이유

기존 `OrderService`의 `READ_COMMITTED`와 **주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순** 비관적 잠금을 그대로 유지했다. 확정 경로는 관리 엔티티를 변경하고 JPA 변경 감지가 commit 과정의 flush에서 UPDATE를 실행한다. `save()`가 없는 이 경로에서 저장소 메서드만 mock하면 실제 SQL 이후 실패를 만들었다는 증거가 되지 않는다.

따라서 생산 코드에 테스트만을 위한 `saveAndFlush()`나 실패 분기를 추가하지 않고 JDBC 실행 경계에 테스트 장치를 뒀다. Hibernate SQL 순서를 업무 메서드 호출 순서와 같다고 가정하지 않으며, 특정 테이블이 첫 번째로 갱신된다고 assertion하지 않는다. 재고·포인트를 `REQUIRES_NEW`로 나누거나 저장 예외를 삼키는 변경도 하지 않았다.

## 테스트 작성·실패 교정·통과 기록

| 단계 | 실제 결과와 판단 |
| --- | --- |
| 테스트 우선 작성 | 정상·중간 실패 2사례·재시도, 총 4개 실행 사례와 테스트 전용 JDBC 장치를 먼저 작성 |
| 첫 실행의 환경 오류 | Docker 엔진 미기동으로 Spring/MySQL 준비 단계에서 4개 실패. 업무 코드 실행 전이므로 업무 Red로 세지 않음. Docker 기동 후 재실행 |
| 실제 DB 연결 후 첫 실행 | 4개 중 2개 통과·2개 실패. 정상·재시도는 통과했고, 실패 주입·DB 원복 검사도 통과. TX-02의 마지막 HTTP 응답 비교에서 생성 응답의 `+09:00`과 재조회 응답의 `Z` 문자열 차이로 실패 |
| 테스트 비교 기준 교정 | 생성·조회 시각이 같은 Instant인지 확인하고, 실패 전후에는 같은 GET 경로의 전체 응답을 비교. DB 6개 테이블의 모든 컬럼 비교와 업무 기대값은 유지 |
| 관련 검사 통과 | 2026-10-08 19:58 KST, OrderTransactionTest 4개와 Checkstyle test 통과. 기존 생산 코드의 업무 결함은 발견되지 않아 구현 변경 없음 |

환경 오류와 새 테스트의 시간대 비교 오류를 기존 업무 구현의 Red로 포장하지 않는다. 트랜잭션을 일부러 제거하거나 기대값을 완화해 Red·Green을 만들지 않았다.

## 실행 명령과 최종 결과

```shell
./gradlew :apps:commerce-api:test --tests '*OrderTransactionTest' --console=plain -q
./gradlew :apps:commerce-api:test --tests '*OrderTransactionTest' :apps:commerce-api:checkstyleTest --console=plain -q
./gradlew :apps:commerce-api:check --console=plain -q
```

**2026-10-08 20:00 KST**, 최종 코드에서 전체 check가 종료 코드 0으로 통과했다.

| 검사 | 최종 결과 |
| --- | --- |
| 신규 테스트 | OrderTransactionTest 4개 통과. 정상 1·실패 주입 2·재시도 1 |
| API 모듈 전체 테스트 XML | **56개 스위트·545개 통과**, 실패·오류·건너뜀 0개. 이전 541개에서 신규 4개 증가 |
| Checkstyle | 연결된 Java 모듈의 XML 보고서 11개, 위반 0개 |
| ArchUnit | ArchitectureTest 1개 통과, 기존 계층 규칙 유지 |
| 변경·문서 확인 | `git diff --check`와 신규 파일 공백 검사 통과. 변경한 Markdown 5개의 로컬 링크·앵커 327개 및 표·코드 블록 검사 오류 0개 |

전체 수치는 `apps/commerce-api/build/test-results/test/TEST-*.xml`을 합산했다. API check에 연결된 다른 모듈의 정적 검사도 포함하지만 그 모듈의 별도 통합 테스트까지 전부 실행했다는 뜻은 아니다. 기존 브랜드·상품·포인트·주문 테스트와 검사 규칙을 삭제하거나 완화하지 않았다.

## 검증 한계와 남은 범위

이번 증거는 **테스트 JDBC 경계에 주입한 저장 실패의 전체 롤백**이다. 실제 DB 제약 위반·네트워크 단절·물리 commit 실패·모든 장애 유형까지 재현했다는 뜻은 아니다. 기존 호출자 트랜잭션 기반 `OrderServiceIntegrationTest`는 과거 검증으로 유지하며 이번 실제 HTTP 요청의 서비스 트랜잭션 검증과 구분한다.

W3의 갱신 유실 대조군, 재고 5에 서로 다른 8개 주문 경쟁, 잔액 10,000에 4,000원 주문 3개 경쟁, 2,000원 충전과 7,000원 결제의 지정 수치 경쟁, 제출용 기술 글은 이번 완료 범위에 포함하지 않는다.
