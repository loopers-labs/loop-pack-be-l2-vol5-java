# R08 트레이드오프

[요구사항](../requirement.md) · [구현 계획](../plan.md) · [전체 요구사항](../../total_requirement.md) · [주제 문서 템플릿](../../_template.md)

**굵게 = 채택** · ~~취소선 = 미채택~~ · `[검토 전]` / `[검토 중]` = 미확정 · `[보류]` = 필요할 때 재검토

모든 주제를 결정했다(2026-10-09 문답 Q1~Q7). R03 [04 §6](../../r03-jdbc-and-like-aggregation/trade_off/04-query-conversion.md#테스트-코드의-jdbcclient-허용-6번)의 "테스트 JdbcClient 허용"을 대체한다.

## 선택 현황

1. [테스트의 데이터 준비·검증 방식](01-test-data-access.md): **검증 조회는 `JPAQueryFactory`, 막아 둔 컬럼은 QueryDSL `update`, 원시 INSERT는 도메인 저장소 + 생성 id, `LikeStorageIntegrationTest` 삭제** / ~~테스트 전용 Spring Data 저장소~~ / ~~운영 Repository·QueryDao~~ / ~~`createNativeQuery`~~ / ~~운영 경로로 좋아요 반영~~ / ~~QueryDSL insert~~ / ~~persist 두 번으로 제약 검증~~
2. [범위·공용 헬퍼·재유입 방지](02-scope-and-guard.md): **JDBC 클래스만 대상, 주문 확정 검증 3종만 공용 헬퍼, 운영 코드 JDBC 의존 ArchUnit 규칙** / ~~native 쿼리까지~~ / ~~모두 클래스 안~~ / ~~모두 공용~~ / ~~테스트 코드도 ArchUnit~~ / ~~문서만~~

## 구현 계획으로 이어갈 것

1·2번 모두 [구현 계획](../plan.md)으로 구체화한다.
