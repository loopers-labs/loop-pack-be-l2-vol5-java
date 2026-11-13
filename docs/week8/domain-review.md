# 도메인 검토 안내의 통합 위치

도메인만 구현하고 다음 계층 검토를 기다리던 단계는 종료됐다. 이 경로는 기존 참조를 위해 유지한다.

- 모델·생성/복원·순수성 경계: [현재 설계](design.md#도메인-경계)
- 원본 소스: [도메인 모델](../../apps/commerce-api/src/main/java/com/loopers/domain/coupon/model/), [저장 계약](../../apps/commerce-api/src/main/java/com/loopers/domain/coupon/repository/)
- 도메인 10건과 실제 DB/HTTP 검증의 구분: [검증 매핑](verification-plan.md#확인한-테스트)
- 집계 계약과 실제 Redis 구현: [Redis 누적 집계](design.md#redis-누적-집계)
