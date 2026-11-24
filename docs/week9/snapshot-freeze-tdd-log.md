# W9 입력 snapshot 고정 TDD 기록

> 이 문서는 해당 단계 당시의 기록이다. 이후 단계와 세 앱 전체 회귀의 최신 판정은 [6단계 최종 검증](final-verification.md)을 따른다.

2026-11-19, `volume-9` 작업 트리에서 **2단계 입력 고정**을 구현했다. 사용자가 선택한 A(구매 활동 DB에 별도 계산 테이블), 기간·revision 식별과 [설계](../week2/commerce-erd-draft.md#w9-주간-구매-랭킹-설계)를 따른다. 아직 점수 job·checkpoint 재시작·결과 공개·고객 API는 구현하지 않았다.

## 무엇을 저장하는가

| 책임 | 구현 | 동작 |
| --- | --- | --- |
| 기간·식별 | [WeeklyRankingKey](../../apps/commerce-batch/src/main/java/com/loopers/domain/ranking/WeeklyRankingKey.java) | UTC 월요일 시작 포함·다음 월요일 제외, 양의 revision. 예: `weekly-20260817-r1` |
| 불변 구매 입력 | [RankingPurchase](../../apps/commerce-batch/src/main/java/com/loopers/domain/ranking/RankingPurchase.java), [FrozenRankingInput](../../apps/commerce-batch/src/main/java/com/loopers/domain/ranking/FrozenRankingInput.java) | 주문·상품별 모든 수량과 최초 confirmedAt의 초·나노초 보존. 잘못된 수량·기간·중복 품목 거절, 정렬된 불변 목록과 checksum v1 |
| 입력 고정 | [WeeklyRankingSnapshotService](../../apps/commerce-batch/src/main/java/com/loopers/application/ranking/WeeklyRankingSnapshotService.java) | 기존 입력 검증·재사용 또는 원본 읽기 → manifest·입력 공동 저장 |
| JDBC adapter | [JdbcRankingSnapshotStore](../../apps/commerce-batch/src/main/java/com/loopers/infrastructure/ranking/JdbcRankingSnapshotStore.java) | 같은 DataSource의 실제 RR TX 확인, 원본 keyset 페이지 조회, 저장 값 대조와 배치 INSERT |
| 추가 schema | [001-weekly-ranking-input.sql](../../apps/commerce-batch/src/main/resources/db/schema/001-weekly-ranking-input.sql) | `weekly_ranking_snapshot`·`weekly_ranking_input`, 기간/revision 및 품목 유일성·FK·수치 CHECK |

manifest는 입력의 설명표다. 기간·revision·고정 관측 시각·입력 건수·상품 수·checksum을 보관한다. `input_state=FROZEN`은 **입력이 완성됐다는 뜻**이며 랭킹 계산·고객 공개 완료를 뜻하지 않는다. 결과 완료 정보·결과 checksum은 후속 단계다.

신규 DDL은 자동 실행 설정에 연결하지 않았다. 이번 적용은 테스트가 소유한 MySQL에만 했다. 기존 streamer의 [구매 활동 DDL](../../apps/commerce-streamer/src/main/resources/db/schema/001-purchase-activity.sql)을 Gradle test resource로 복사해서 실제 원본 구조와 맞췄으며, 앱 사이 Java 의존이나 원본 스키마 변경은 추가하지 않았다.

## 트랜잭션과 재사용 경계

```text
기간·revision 입력
  → 하나의 REPEATABLE_READ TX 시작
  → 고정 관측 시각 확보 → 기존 manifest 조회(첫 읽기 view)
      ├─ 기존 입력 있음: 저장 입력·건수·checksum 검증 → 그대로 반환
      └─ 없음: 같은 view에서 원본 페이지 조회 → manifest + 입력 INSERT
  → commit (중간 예외면 새 manifest·입력 모두 rollback)
```

- 원본 페이지는 `(order_id, product_id)` 오름차순이다. 페이지마다 TX를 새로 열지 않으며 `max(id)`를 commit 순서로 해석하지 않는다.
- `frozenAt`은 첫 SELECT 직전에 읽는 Clock 값이다. 해당 시각까지 모든 거래가 포함됐다는 watermark가 아니다. 실제 복사 행과 checksum이 입력의 근거다.
- 같은 기간·revision이면 원본 조회·입력 INSERT 없이 저장된 입력을 돌려준다. manifest와 입력의 식별·건수·상품 수·checksum이 다르면 `RankingSnapshotConflictException`으로 중단하며 손상 증거를 덮어쓰지 않는다.
- 새 구매를 포함하려면 운영자가 새 revision을 지정한다. 전체 JobInstance 재시작·증가 revision 실행 조율은 3단계 이후에 연결한다.
- `@Transactional(REPEATABLE_READ)`만 신뢰하지 않고 실제 바인딩된 DataSource 연결·autoCommit=false·RR을 검사한다. 트랜잭션 없음, 다른 DataSource의 TX, 바깥 READ_COMMITTED TX는 거절한다. 후속 freeze Step도 바깥 TX 시작부터 RR을 설정해야 한다.
- 같은 식별값의 동시 신규 INSERT가 충돌하면 DB 유일 제약 예외로 해당 TX 전체가 rollback된다. 이 단계에서 이를 임의 성공으로 바꾸지 않는다. 전체 계산은 합의한 단일 실행 범위로 연결할 예정이다.

원본 읽기·입력 쓰기는 `JdbcTemplate`을 통해 같은 transaction-bound connection을 사용한다. 이번 DB 검증의 manager는 `JdbcTransactionManager`다. 이것으로 Boot의 실제 `JobRepository`·chunk writer·checkpoint 공동 TX까지 검증했다고 주장하지 않는다.

## Red → Green → Refactor

| 실행 | 관찰 결과 |
| --- | --- |
| 도메인 Red | 구현 stub 상태에서 29개 전부 실패. UTC 기간·불변성·중복·checksum 기대값이 실행됨 |
| 도메인 Green | 구현 후 29개 통과, main/test Checkstyle 통과 |
| DB Red | 실제 MySQL·생산 DDL을 준비한 뒤 서비스/adapter stub에서 14개 실패. DB 연결 실패를 Red로 세지 않음 |
| 연결 검사 보완 | 처음에는 JdbcTemplate의 connection proxy를 그대로 비교해 정상 TX도 거절했다. proxy의 실제 연결로 비교하고, JDBC 호출 전에 해당 DataSource가 바인딩돼 있는지도 확인하도록 수정 |
| 동시성 fixture 보완 | 전체 실행에서는 원본 부모 confirmation INSERT가 대기했지만 같은 테스트 단독 실행은 통과했다. 부모를 먼저 commit하고 활동 행의 낮은 ID 지연 commit만 경합시켜 검증 대상을 분리. 기대 수량·ID·입력 불변 조건·제한 시간은 유지 |
| 최종 Green | 무TX·다른 DataSource 가드 2개를 추가해 DB 16개. 도메인·ArchUnit·기존 앱 부팅·demo까지 Batch 전체 49개 통과 |

## 실제 MySQL에서 확인한 결과

테스트는 [WeeklyRankingSnapshotIntegrationTest](../../apps/commerce-batch/src/test/java/com/loopers/application/ranking/WeeklyRankingSnapshotIntegrationTest.java)다. 테스트 전체를 rollback TX로 감싸지 않고 서비스 종료 후 별도 연결의 commit 상태를 읽었다. 실패 주입은 테스트 spy에서 **실제 SQL을 먼저 실행한 뒤** 발생시켰다.

| 장면 | 검증 결과 |
| --- | --- |
| 과제의 4개 구매 활동 | 주문 42의 두 품목을 포함한 입력 4행·상품 3개, 원본 전체 행 불변 |
| UTC·나노 경계 | 시작 직전 1ns 제외·시작 포함·끝 직전 1ns 포함·끝 제외. 복사된 초·나노 값 정확히 유지 |
| 구매 0건 | manifest 1행·입력 0행·빈 checksum, 재호출 때 원본 재조회·새 INSERT 없음 |
| 늦은 구매 | 처음 4행을 고정한 뒤 원본에 1행 추가. revision1은 4행 그대로, revision2만 5행 |
| commit 순서 역전·페이지 사이 삽입 | 낮은 activity ID를 미커밋으로 둔 채 높은 ID를 먼저 commit. 첫 페이지 뒤 낮은 ID와 새 활동을 commit해도 기존 입력은 3행, 새 revision은 5행. 부모 선준비는 이 source 가시성 실험의 fixture이며 consumer 공동 TX의 증거가 아님 |
| 실제 입력 일부 저장 후 예외 | 같은 TX에서 manifest 1행·입력 2행을 확인한 뒤 실패. 외부 조회는 둘 다 0행, 원본 보존. 같은 식별값 재시도 성공 |
| manifest 저장 후 예외 | 실제 INSERT 확인 후 실패하면 manifest·입력 모두 0행 |
| 실제 중복 입력 SQL | 동일 batch를 두 번 INSERT해 DB 유일 제약 실패. manifest·모든 새 입력 rollback, 원본 보존 |
| 저장 불일치 4종 | 건수·입력 수량·checksum·revision 손상을 각각 거절하고 기존 증거 보존. 원본 재복사 없음 |
| 기간·revision 충돌 | 다른 snapshotId여도 같은 기간/revision INSERT는 실제 DB 유일 제약으로 거절 |
| TX 경계 3종 | 무TX, 바깥 READ_COMMITTED, 다른 DataSource의 TX를 거절. 다른 pool의 연결을 새로 바인딩하지 않음 |

입력 checksum 독립 기대값은 아래와 같다. 제품 serializer로 기대값을 다시 생성하는 자체 비교를 하지 않는다.

| 입력 | SHA-256, checksumVersion=v1 |
| --- | --- |
| 과제 원문 4행, UTF-8 152 bytes | `4a00f7d7a6421c8e457a59012754f53ca6069dc391e14c582fc7e551bab60863` |
| 0행 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |

## 검사와 자원 정리

```bash
./gradlew :apps:commerce-batch:check --console=plain
```

2026-11-19 실제 실행 결과는 **7개 suite·49개 테스트**, 실패·오류·skip **모두 0**, Batch main/test Checkstyle **위반 0**, ArchUnit **통과**, **BUILD SUCCESSFUL·종료 코드 0**이다. Gradle 출력 소요 시간은 21초이며 처리 성능 측정값이 아니다. 신규 도메인 29개 + DB 16개 + ArchUnit 1개, 기존 앱 부팅 1개 + demo 2개로 구분한다.

런타임은 Java 21.0.10, Boot 선언 3.4.4, dependencyInsight로 확인한 Spring Batch 5.2.2·Spring JDBC 6.2.5, 격리 MySQL 8.0이다. 버전은 변경하지 않았다. 로컬 실행 로그는 `/private/tmp/w9-snapshot-batch-check.log`, XML 요약은 `/private/tmp/w9-snapshot-batch-check.json`, [HTML 보고서](../../apps/commerce-batch/build/reports/tests/test/index.html)는 다음 검사 때 갱신될 수 있다.

새 DB 테스트는 클래스 전용 Testcontainers MySQL을 사용한다. 기존 Batch 회귀도 저장소의 Testcontainers fixture를 사용한다. 처음 꺼져 있던 Docker 앱을 시작했으며 데이터·볼륨 삭제나 프로세스 강제 종료를 하지 않았다. 종료 후 Testcontainers 라벨의 잔여 컨테이너 0개를 읽기 전용으로 확인했다. executor 종료·연결 풀 close·컨테이너 종료를 확인하고 **사용자 DB·공유 Redis·기존 topic/group은 사용하지 않았다.** 커밋·푸시도 하지 않았다.

## 현재 한계와 다음 단계

- 읽기/쓰기 묶음은 기본 500행이지만 구현은 검증·정렬을 위해 전체 입력 List를 메모리에 둔다. 총 메모리는 입력 크기에 비례하며 대용량 처리량을 검증한 상태가 아니다. 긴 freeze TX와 메모리 비용은 후속 측정·개선 대상이다.
- 원본 기간 조회 인덱스·점수 쿼리·랭킹 API 성능은 이번에 변경하거나 측정하지 않았다.
- 구매 활동은 실제 기존 DDL에 넣은 합성 fixture다. 실제 PG → Outbox → Kafka → streamer → Batch → HTTP 연결은 5단계다.
- 다음은 **3단계 chunk=2 작은 재시작 실험**이다. 상품 7·8과 영속 reader 위치 2를 commit하고 9 직전 실패시킨 뒤, 같은 JobInstance·새 JobExecution에서 9부터 이어 가는지 확인한다. writer SQL과 checkpoint의 실제 공동 TX도 이때 검증한다.
- 남은 단계는 3단계 점수·재시작, 4단계 공개·API, 5단계 실제 전체 흐름, 6단계 최종 회귀·정리다. 이번 증거만으로 W9-C01~09 전체를 통과 처리하지 않는다.
