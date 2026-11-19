# 주문 파티션 명칭과 기존 데이터 전환

## C05·C07 기준 데이터

C05의 고정 입력, 상품별 최종 점수, 완성 순위와 공개 포인터는 기존 014~016 스키마와 같은 테이블을 쓴다. 기존 Spring Batch Job `weeklyRanking`과 그 실행 이력도 유지한다. 기존 공개 revision을 발견한 스케줄러는 같은 revision을 새 계획으로 중복 생성하지 않는다. 이미 시작한 단일 Job은 그 버전에서 마무리한다. 새 방식으로 늦은 구매까지 계산할 때는 다음 revision을 요청한다. C07의 이벤트 기반 실행 요청과 새 스케줄러를 비교하는 경우에는 두 구현에 각각 격리된 DB를 사용한다.

## 새 구현에서 쓰는 이름

| 대상 | 값 |
|---|---|
| 인스턴스가 맡는 단위 | 주문 파티션. 연속 주문번호 경계와 그 주의 `confirmedAt`으로 소속을 정한다. |
| Spring Batch Job과 식별 파라미터 | `weeklyRankingOrderPartition`, `snapshotId`와 `orderPartitionId` |
| 담당자와 세대 | `ownerId`와 `generation`은 식별에 포함되지 않는다. 인수하면 같은 JobInstance에 새 JobExecution이 생긴다. |
| JVM 내부 계산 | 로컬 상품 파티션. 같은 주문 파티션에서 상품 ID의 범위를 나눠 worker Step과 reader 체크포인트를 유지한다. |
| DB | `weekly_ranking_order_partitions.order_partition_id`, `weekly_ranking_plans.order_partition_count` |
| 복구 CLI | `--ranking.scheduler.order-partition-id` |

Spring Batch의 JobInstance는 Job 이름과 식별 파라미터로 결정된다. 중간 개발 버전의 Job 이름이나 식별 파라미터 이름 중 하나만 달라져도 이전 실행을 찾을 수 없다. Step 이름과 reader 상태 키도 달라졌다. 진행 중인 실행의 메타데이터를 새 이름으로 바꾸면 재시작 위치를 잃을 수 있다. `OrderPartitionJobIdentityTest`가 실제 MySQL JobRepository에서 이전 식별자로 이전 실행을 조회하고, 새 식별자로 만든 실행이 담당자 변경 후에도 같은 JobInstance를 사용하는지 검사한다.

## 개발 중간 버전의 저장 데이터가 있을 때

아래 절차는 **이번 새 브랜치에서 명칭이 확정되기 전 생성했던 실험 DB**를 위한 선택적 이관이다. C05/C07에서 출발하는 새 설치에는 [운영 안내](README.md)의 017·018을 적용한다.

1. 기존 바이너리에서 진행 중인 계획과 복구 대기를 먼저 해결한다. 계획의 최종 상태와 공개 포인터를 확인한 뒤 기존 스케줄러와 쓰기 프로세스를 멈추고 일관된 DB 백업을 만든다.
2. 별도 MySQL에 백업을 `loopers_pre_order_partition`으로 복원한다. 같은 MySQL의 빈 대상 DB `loopers`에는 같은 시점의 공통 테이블과 데이터를 복원한다. 여기에는 `BATCH_*` 전체, 구매 활동, 고정 입력, 최종 점수, 완성 기록, 순위, 공개 포인터가 포함된다. 대상 복원에서 제외할 중간 버전 조율 테이블은 `weekly_ranking_members`, `weekly_ranking_schedule_cursor`, `weekly_ranking_plans`, `weekly_ranking_shards`, `weekly_ranking_partial_scores`, `weekly_ranking_score_conflicts` 여섯 개다.
3. 대상 DB에 새 017·018을 적용한다. 스케줄러를 정지한 상태에서 MySQL 클라이언트로 [완료 데이터 이관 SQL](../../docker/week9/compat/import-completed-order-partitions.sql)을 실행한다. 명령 예시는 MySQL 클라이언트 안에서 `SOURCE docker/week9/compat/import-completed-order-partitions.sql`이다.
4. 이관 SQL은 미완료 계획·실행과 이미 사용 중인 대상을 거절한다. 완료된 계획·주문 파티션·부분 점수·충돌 증거와 주차 커서를 한 트랜잭션에서 복사한다. 새 JVM이 기동할 때 자신의 생존 신호를 등록한다. 종료한 프로세스의 생존 신호는 옮기지 않는다.
5. 원본·대상의 주차별 공개 snapshot ID와 revision, 상품별 부분 점수, 공개 순위를 대조한다. `BATCH_*`의 기존 Job·Step 이름과 실행 ID는 역사적 기록으로 유지된다. 새 바이너리에서 **다음 revision**을 시작해 새 이름의 JobInstance가 생성되는지 확인한다.

### 이관 실측

MySQL 8.0.46의 격리된 두 DB에서 과거 공개본과 Batch 이력으로 절차를 실행했다. 열린 계획은 오류 1644로 거절됐고 대상 계획은 0행이었다. 계획을 종료한 뒤 이관한 결과는 주문 파티션 2개, 부분 점수 `[2,3]`, 공개 revision 1의 상품 7이 5점·1위였다. 이전 JobInstance 80과 JobExecution 85 및 기존 식별 파라미터가 보존됐다. 새 생존 신호 0행, 주차 커서 1행, 원본 부분 점수 2행을 확인했다. 같은 대상을 다시 이관하면 거절되고 두 행·5점이 유지됐다.

이관 SQL과 호환성 테스트에 등장하는 이전 영문 식별자는 과거 DB를 읽기 위한 참조다. 새 스케줄러의 실행 파라미터와 DB 쓰기에는 위 표의 이름을 사용한다.
