# ADR 0080: 주간 랭킹 테이블 다섯 개는 commerce-batch가 SQL 스키마로 소유하고, commerce-api는 읽기만 한다

Status: Accepted
Related: [9주차 설계 5절](../week9/design.md), [구현 기록](../week9/implementation-log.md)

## Context

과제는 개념상 다섯 가지 데이터를 요구하고, 실제 테이블 구성은 설계에 맡긴다.

- snapshot 정보
- snapshot 입력
- 점수 stage
- 완성 ranking
- 기간별 공개 pointer

쓰는 쪽은 commerce-batch다. 공개된 결과를 읽는 쪽은 commerce-api다.

local·test 프로필의 `spring.jpa.hibernate.ddl-auto`는 `create`다. Hibernate는 앱이 뜰 때 자기 엔티티가 가진 테이블을 지우고 다시 만든다. 이 설정은 공용 `jpa.yml`에 있다.

이 설정 때문에 두 가지 문제가 생길 수 있다.

- 배치가 엔티티로 테이블을 가지면, 실패한 job을 다시 실행할 때 앱이 뜨면서 snapshot과 stage가 지워져 재시작할 것이 남지 않는다.
- 두 앱이 같은 테이블을 엔티티로 가지면, api를 재시작할 때 배치가 공개한 랭킹이 지워진다.

지금 저장소에서 각 테이블의 엔티티는 한 앱에만 있다. 예를 들어 `product_purchase_activity`는 commerce-streamer에만 있다.

## Decision

- 다섯 가지 데이터를 목적별 테이블 다섯 개로 나눈다. `ranking_snapshot`, `ranking_snapshot_input`, `ranking_score_stage`, `ranking_entry`, `ranking_publication`이다. 점수 stage(계산 중, 배치만 읽음)와 완성 ranking(공개 단계와 API가 읽음)도 분리한다.
- commerce-batch가 다섯 테이블을 모두 만들고 쓴다. 엔티티는 두지 않는다. `schema/weekly-ranking.sql`의 `CREATE TABLE IF NOT EXISTS`를 `spring.sql.init`으로 실행한다. local·test만 `mode: always`이고 그 밖의 프로필은 `never`다. 배치의 업무 SQL은 `JdbcTemplate`으로 쓴다.
- commerce-api는 `ranking_entry`·`ranking_publication`을 엔티티 없이 SQL로 읽기만 한다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| batch에 JPA 엔티티 + batch local만 `ddl-auto: update` | 처음 합의한 안이다. 목적은 같지만 배치 SQL이 모두 `JdbcTemplate`이라 엔티티는 스키마만을 위해 생긴다. 공용 `jpa.yml` 위에 batch만 다른 값을 덮어야 하고, `update`는 기존 열을 바꾸거나 지우지 않아 스키마 변경을 드러내지 못한다 |
| batch와 api가 같은 테이블을 각자 엔티티로 매핑 | api 재시작이 local에서 공개본을 지운다. 테이블 정의가 두 곳에 생긴다 |
| api가 완성 ranking·공개 pointer를 소유하고 batch는 JDBC로 쓰기 | 쓰는 쪽과 스키마 주인이 달라진다. api의 `create`가 공개본을 지우는 문제도 그대로다 |
| stage와 완성 ranking을 한 테이블에 두고 rank 칸을 나중에 채움 | 테이블은 하나 줄지만 "계산 중"과 "완성"이 같은 행의 상태로만 구분된다. API가 읽는 곳에 중간 결과가 함께 있게 된다 |

## Consequences

- 스키마의 기준은 SQL 파일 하나다. local에서 열을 바꾸려면 테이블을 직접 지우거나 고쳐야 한다(`IF NOT EXISTS`는 바뀐 정의를 반영하지 않는다).
- 운영 DB에는 이 스키마를 미리 만들어야 한다. 그 절차는 이번 범위에서 만들지 않는다.
- commerce-api 테스트는 이 두 테이블을 직접 준비해야 한다.
- batch 테스트는 원본 `product_purchase_activity`를 streamer와 같은 열·제약으로 직접 만든다.

## 검증

`WeeklySnapshotFreezeTest`·`RankingJobRestartTest`가 이 스키마로 입력 고정·점수 계산·재시작을 확인했다. 두 테스트는 같은 MySQL 컨테이너에서 실행되며, 한 테스트 안에서 실패와 재실행을 할 때 snapshot과 stage가 그대로 남았다. local에서 앱을 다시 띄울 때 데이터가 남는지는 실행해 보지 않았다. 결과는 [구현 기록](../week9/implementation-log.md)을 따른다.
