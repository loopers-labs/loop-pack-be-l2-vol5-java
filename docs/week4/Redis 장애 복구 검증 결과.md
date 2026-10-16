# Redis 장애 복구 검증 결과

2026-10-15, 로컬 실제 MySQL·Redis Testcontainers와 기존 HTTP API 기준.

**Redis 중단 중에는 DB로 우회하고, 복구 후에는 변경 전 캐시를 재사용하지 않고 현재 revision으로 다시 채운다.**

`100원 캐시 → Redis 중단 → DB 가격 400원 수정 → Redis 복구 → 새 revision에 400원 저장`

| 검증 항목 | 결과 |
| --- | --- |
| 장애 전 miss/hit | 응답 동일, 두 요청의 원본 상품 조회 합계 1회 |
| 실제 연결한 Redis 중단 | 전용 컨테이너 정지 확인, DB는 정상 유지 |
| 장애 중 가격 수정 후 조회 | DB 우회, 새 가격 400원 반환 |
| 장애 중 상품 삭제 후 조회 | 404 반환 |
| 제한된 대기 | 수정·삭제 조회 각각 5초 미만 회귀 검사 통과 |
| 같은 Redis 재시작 | 서버 PONG과 앱 연결의 자동 재연결 확인 |
| 옛 키가 남은 상태의 복구 | 옛 키 무시, 새 revision에 정확한 응답과 TTL 저장 |
| 복구 후 hit·삭제 조회 | hit의 원본 상품 조회 0회, 삭제 상품은 404 유지 |
| 실험 자원 정리 | 테스트 전용 Redis 복원·종료, DB fixture 정리 |

관련 실행에서 **복구 반복 3개 + 장애/복구 보호 조건 6개, 총 9개 통과**. 실패·오류·skip 0. [실행 결과](experiments/21-cache-path-validation/gap-focused-results.json)

명령·연결 타임아웃은 각각 100ms, 재연결 대기 상한은 1초다. 준비 데이터 정리는 전용 컨테이너 내부 `FLUSHDB SYNC`로 수행하고 OK·종료 코드·DBSIZE=0을 검사한다. 실제 조회·복구 검증은 앱의 연결과 설정을 사용한다.

## 검증 범위

테스트마다 앱 컨텍스트를 새로 만든다. 각 시나리오 안에서는 같은 앱 연결의 장애·재연결을 확인하지만, 동일 앱 연결에서 연속 장애를 여러 번 반복한 결과는 아니다. 5초는 이전 장시간 대기를 감지하는 회귀 기준이며 정상 상태 p99 SLO가 아니다. EC2 장애 주입·장애 부하·네트워크 지연 주입은 포함하지 않는다.

[사용자·주문·삭제 보호 결과](캐시%20변경·장애%20보호%20검증%20결과.md) · [테스트 코드](../../apps/commerce-api/src/test/java/com/loopers/config/cache/RedisRecoveryHttpTest.java) · [이전 실패·원인 분석·설정 변경 기록](experiments/22-cache-redis-recovery/report.md)
