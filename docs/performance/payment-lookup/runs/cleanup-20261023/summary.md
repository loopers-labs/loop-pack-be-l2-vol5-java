# 실험 자원 정리

2026-10-23, 복수 거래 보완의 전체 검사 종료 뒤 확인했다. 최신 검증은 [Commerce 699개, test-pg 112개](../multiple-transactions-20261023/summary.md)이며 실패, 오류, 건너뜀이 없고 Checkstyle과 ArchUnit도 통과했다.

| 자원 | 확인과 처리 |
| --- | --- |
| PG 임시 디렉터리 9개 | 실행 중인 검사와 연결된 worktree가 없음을 확인하고 삭제. 삭제 전 `du -sk` 합계 182,176KiB, 약 177.9MiB. 정리 후 `pg-*` 디렉터리 0개 |
| `loopers-test-pg-check-test-pg:latest` | 컨테이너 참조 0개 확인 후 삭제. Docker 표시 크기는 522MB이며 공유 계층 때문에 실제 회수한 디스크 크기로 해석하지 않음 |
| Testcontainers | 검사 종료 후 자동 정리됨. 남은 컨테이너 0개 |
| HTTP 서버와 테스트 worker | 8080, 8081, 8082 LISTEN 없음. Gradle 검사와 테스트 worker 없음. 대기 중인 Gradle daemon은 유지 |
| Prometheus와 Grafana | 후속 측정에서 사용한다는 사용자 선택에 따라 `loopers-index-observability`의 두 컨테이너를 유지 |

Red/Green 결과 집계, 검증한 소스 해시와 재현용 init script는 저장소에 보존했다. [결과 파일을 분리한 전체 검사](../../README.md#결과-파일을-분리한-전체-검사)로 삭제한 임시 경로 없이 재현할 수 있다. 임시 커밋 백업의 소스 72개는 71개가 Git 이력에 있고 나머지 하나는 현재 저장본과 마지막 빈 줄만 달랐다.

이번 정리는 실행 자원과 문서만 대상으로 했으며 테스트를 재실행하지 않았다. 검증 기록의 소스 해시가 현재 파일과 일치하는지 확인했다. 정리 전후 목록과 확인 결과(`verification.json`)

이 기록의 이미지 정리 대상은 이름 있는 PG 이미지 1개였다. 후속 점검에서 확인한 기존 Testcontainers 이미지와 중간 빌드 산출물, 이미지 수명 설정의 보강은 [섹션 6 기록](../section6-20261023/summary.md)에 별도로 보존했다.
