# 테스트의 데이터 준비·검증 방식

[← 전체 선택 현황](total_trade_off.md)

## 판단할 문제

R03은 테스트의 데이터 준비·검증에 `JdbcClient`를 허용했다. 사용자는 JDBC를 배치 작업에만 쓰기로 정했으므로, 테스트 15개 클래스의 용도별 대체 수단을 정한다.

> **채택 — 검증·준비는 테스트에서 `JPAQueryFactory`, 원시 INSERT는 도메인 저장소, 유니크 제약 원시 테스트는 삭제(Q1~Q4)**
>
> - 검증 조회: `queryFactory.select(POINT_BILL.count()).from(POINT_BILL).where(…)`처럼 Q타입으로 조회한다.
> - 막아 둔 컬럼(`like_count`, `created_at`): QueryDSL `update(…).set(…).execute()`. 트랜잭션이 필요하면 `TransactionTemplate`으로 감싼다.
> - 원시 INSERT: `ProductRepository.save`·`LikeRepository.save` 등으로 만들고 생성된 id를 쓴다.
> - `LikeStorageIntegrationTest` 삭제: `LikeRepositoryIntegrationTest`의 "중복 등록은 1행·false"가 유니크 제약 없이는 성립하지 않는다.
>
> 대신 테스트가 인프라의 Q타입(JPA 엔티티)을 직접 안다. 지금도 테이블·컬럼 이름을 알고 있어 결합 수준은 같다.

## 장단점 비교

| 용도 | 채택 | 미채택 |
|---|---|---|
| 검증 조회(Q1) | **`JPAQueryFactory`**: 운영 코드 변경 없음, "조회는 QueryDSL" 기준과 같음, 타입 검사 | 테스트 전용 Spring Data 저장소: 검증마다 메서드 증가. 운영 Repository·QueryDao: 테스트용 조회를 운영에 추가. `createNativeQuery`: SQL 그대로라 이름만 바뀜 |
| 막아 둔 컬럼(Q2) | **QueryDSL `update`**: 일괄 UPDATE는 엔티티의 `updatable = false` 제한을 받지 않음(구현 중 확인) | 운영 경로로 좋아요 반영: 반영 DAO가 JDBC이고 `created_at`은 해결 못 함 |
| 원시 INSERT(Q3) | **도메인 저장소 + 생성 id** | QueryDSL `insert`: JPA 일괄 INSERT 제약이 많음 |
| 유니크 제약 테스트(Q4) | **삭제**: 다른 테스트가 같은 사실을 증명 | `EntityManager.persist` 두 번으로 예외 확인 |

## 함께 고민한 내용

1. **커밋된 값 보기:** `JdbcClient`는 영속성 컨텍스트를 거치지 않아 커밋된 값을 바로 봤다. QueryDSL 조회는 같은 트랜잭션의 1차 캐시 영향을 받을 수 있으므로, 트랜잭션 안에서 검증하는 테스트는 조회 전에 `flush`·`clear`한다. 대부분의 대상 테스트는 트랜잭션 밖에서 검증한다.
2. **ArchUnit과의 관계:** 계층 규칙 테스트는 `DoNotIncludeTests`라 application 패키지의 테스트가 인프라 Q타입을 써도 위반이 아니다.

## 옵션별 판단

> **채택 — `JPAQueryFactory` 검증·QueryDSL update 준비·도메인 저장소 INSERT·유니크 원시 테스트 삭제**

> **대체 — R03 04 §6 "테스트 JdbcClient 허용"**
