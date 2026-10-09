# 범위·공용 헬퍼·재유입 방지

[← 전체 선택 현황](total_trade_off.md)

## 판단할 문제

JDBC가 아닌 native 쿼리까지 바꿀지, 반복되는 검증을 어디에 둘지, 운영 코드에 JDBC가 다시 들어오는 것을 어떻게 막을지 정한다.

> **채택 — JDBC 클래스만 대상(Q5), 주문 확정 검증 3종만 공용 헬퍼(Q6), 운영 코드 ArchUnit 규칙 추가(Q7)**
>
> - 범위: `DatabaseCleanUp`의 TRUNCATE와 운영 `INSERT IGNORE`는 JPA native 쿼리라 그대로 둔다.
> - 공용 헬퍼: 주문 확정 테스트 3개가 반복하는 "포인트 사용 기록 수·주문 상태·주문 기록 수"만 `com.loopers.support.test`의 헬퍼로 뺀다. 나머지는 각 테스트 클래스의 private 메서드로 둔다.
> - 규칙: `LayerArchitectureTest`에 "`JdbcClient`·`JdbcTemplate`(`org.springframework.jdbc..`)에 의존할 수 있는 운영 클래스는 `com.loopers.infrastructure.dao..`의 `Jdbc*`뿐"을 추가한다.
>
> 대신 테스트 코드의 JDBC 재유입은 규칙으로 막지 않는다(문서로만 기록).

## 장단점 비교

| 주제 | 채택 | 미채택 |
|---|---|---|
| 범위(Q5) | **JDBC 클래스만**: 목적(배치 외 JDBC 금지)에 맞음 | native 쿼리까지: `INSERT IGNORE`는 영향 행 수 판별에 필요(R03 08), TRUNCATE는 픽스처 |
| 헬퍼(Q6) | **실제로 세 번 반복되는 것만 공용** | 모두 클래스 안: 반복 유지. 모두 공용: 테스트를 읽을 때 파일을 오감 |
| 재유입(Q7) | **운영 코드 ArchUnit**: 배치 예외를 검사로 고정 | 테스트도 검사: 별도 ArchUnit 설정 필요. 문서만: 다시 들어와도 모름 |

## 옵션별 판단

> **채택 — JDBC 클래스만, 주문 확정 검증 3종 공용 헬퍼, 운영 코드 ArchUnit 규칙**

> **미채택 — 테스트 코드 ArchUnit 규칙:** 사용자 선택(Q7 a).

## 남은 사항

- 테스트에 JDBC가 다시 들어오면 테스트 대상 ArchUnit 규칙을 다시 검토한다.
