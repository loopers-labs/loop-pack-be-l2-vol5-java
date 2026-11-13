# 8주차 · 쿠폰 발급과 인스턴스별 수용 제어

현재 구현과 검증을 찾는 시작점이다. C01~C05는 커밋했고, C06 고객·관리자 HTTP와 독립 JVM 두 개의 대상 검증을 통과했다.
전체 검사에서 실패한 두 모듈은 Docker API 환경을 맞춘 재실행으로 해결했다. UP-TO-DATE 재사용을 포함한 최신 XML 635건은
실패·오류·skip 0이며, 단일 clean 실행 635건의 결과라는 뜻은 아니다. C06 기능 커밋을 준비한 상태다.

| 문서 | 역할 |
|---|---|
| [구현 현황과 검증 근거](completed/README.md) | C01~C06 상태, 확인한 결과, 남은 한계 |
| [현재 설계](design.md) | 도메인·DB 발급·수용·복구·Redis 집계의 실제 경계 |
| [요구사항](total_requirement.md) | 현재 적용 계약과 별도로 보존한 최초 과제 원문 |
| [검증 매핑과 미확인 항목](verification-plan.md) | 테스트별 근거와 아직 입증하지 않은 시나리오 |
| [API·운영 안내](completed/operations.md) | 데이터 보존 실행, 준비·개방·복구·집계 명령 |
| [설계 결정 기록](trade-offs.md) | 선택·기각 이유와 당시 논의 기록 |
| [기술 글 초안](completed/technical-article.md) | 실제 실패 경계와 선택 이유 |
| [PR 게시용 본문](completed/pr-description.md) | 변경 결과·검증·리뷰 포인트·한계 |
| [이전 Redis 수용 설계](history/redis-admission-design.md) | 변경 전 전역 수용 설계의 역사 자료 |

발급 결과의 원본은 DB이며, 쿠폰·슬롯·`issuedCount`를 같은 TX로 확정한다. 처리 자리는 인스턴스별 singleton
Java Semaphore가 담당한다. Redis는 확정 발급의 누적 집계이며 전역 수용·사용자 분산락에 사용하지 않는다.
기본 수용 상태는 닫힘이고, 기존 완료 결과와 본인 목록 조회는 신규 permit 없이 유지한다.

단계별 준비·진행 메모는 기준 문서로 통합했다. 외부 참조가 있는 [개발 순서](development-plan.md),
[준비 안내](campaign-preparation.md), [도메인 안내](domain-review.md), [환경 안내](environment.md),
[초기 준비 기록](initial-setup.md), [인계](handoff.md) 경로는 짧은 연결 안내로 남긴다.
