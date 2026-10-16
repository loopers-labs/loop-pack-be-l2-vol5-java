# R02 검증 근거

| 근거 | 실제 확인 내용 |
|---|---|
| [최종 전체 검사](validation-check.json) | check 성공, 테스트 333개·실패/오류/skip 0, Checkstyle 0·ArchUnit 8개 통과 |
| [최초 전체 검사·JAR 빌드](validation-check-initial.json) | 초기 check·bootJar 성공, 테스트 332개 통과. 이후 운영 코드와 측정 JAR 변경 없음 |
| [초기 관련 검사](validation-related.json) | 캐시·설정·데코레이터·리스너·쓰기·HTTP·MySQL 관련 선택 테스트 74개 통과 |
| [delta rollback 보완](validation-delta-rollback.json) | 실제 집계 SQL 뒤 외부 TX rollback과 대상·무관 캐시 보존, MySQL 통합 테스트 14개 통과 |
| [HTTP·SQL](http-sql/README.md) | 현재 100만 DB의 OFF 2회, ON miss/hit, 전체 응답 및 SQL 1/1/1/0 |
| [full smoke](jmeter-smoke/README.md) | OFF/ON별 100개 전체 기대 응답 대조, 총 200건·오류 0건 |
| [JMeter 본 측정](jmeter/README.md) | OFF/ON × 1명/10명 × 2회, 1,823,514건·오류 0건, 원본 검산·DB/설정 보존 |
| [런타임·장비](runtime.json) | 실제 JAR·라이브러리·JVM·MySQL·JMeter와 장비 정보 |

테스트의 쓰기·rollback은 Testcontainers MySQL에서 검증했다. 기존 실험 DB는 읽기 검증과 부하에만 사용했다.
전체 원본 로그와 JTL·HTML·manifest는 `build/benchmark/r02/`에 보존하며 Git에는 요약과 작은 증거만 남겼다.
상세 구현·동작 한계는 [실제 결과](../result.md)에서 확인한다.
