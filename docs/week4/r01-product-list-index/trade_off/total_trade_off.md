# R01 트레이드오프

[요구사항](../requirement.md) · [계획](../plan.md) · [전체 요구사항](../../total_requirement.md)

2026-10-16 대화에서 확인한 사용자 결정과 후속 설계 항목을 구분한다. 결정은 구현·실험 완료를 뜻하지 않는다.

## 선택 현황

1. [인덱스 구성](01-index-layout.md): **기존 4개 유지 + 전체 최신순·가격순용 2개 추가**. 기존 브랜드별 인덱스를 전체용으로 교체하거나 모든 정렬을 하나의 인덱스로 합치지 않는다.
2. [전후 측정](02-before-after-benchmark.md): **상품 100만 개, 3종 요청, 동시 1명·10명, 사용자 JMeter 실행, Codex 도구 준비·실행 계획 분석**. JMeter 초보자 안내도 포함한다.
3. [전용 프로필의 SQL seed](03-profile-sql-seed.md): **`benchmark-seed` 전용 프로필 + ApplicationRunner + ResourceDatabasePopulator, 전체 적재·검증 한 TX, 검증 후 종료**. 브랜드·사용자 각 1,000개와 고정 분포를 사용한다.
4. [JMeter 실행 조건](04-jmeter-run-settings.md): **사용자 직접 실행, 워밍업 20초·본 측정 60초·2회 반복**. 기존 4개 인덱스와 128MiB 버퍼 풀을 유지한다.

## 남은 측정 설계

- 사용자 위임으로 after CLI 12개를 완료했다. [실제 지표와 편차](../baseline/jmeter-after/README.md)를 확인하며 before는 부분 기준선으로 유지한다.
- 상품 목록 SQL의 전후 EXPLAIN·EXPLAIN ANALYZE와 새 2개 적용은 완료했다. [실제 분석](../baseline/index-review.md)을 따른다.

seed·전후 SQL 계획·새 2개 적용·API 결과 동일성과 위임한 after CLI 측정을 완료했다. before 미측정 조합의 개선율은 제시하지 않는다.
