# 커머스 6개 테이블 운영 스키마 TDD 실행 기록

2026-09-18 C01~C12·A01~A13 기능과 주문 경합의 Green을 확인한 뒤 P14 순서에 따라 나머지 운영 DDL을 작성했다. 기존 브랜드 SQL과 테스트를 보존하고, JPA 모델에 맞는 5개 CREATE TABLE 파일을 추가했다. 전체 기능 테스트의 최종 상태는 [완료 체크리스트](commerce-completion-checklist.md), 실행 절차는 [스키마 적용 안내](commerce-schema-operations.md)에 구분한다.

## 변경 책임

| 파일·그룹 | 책임 |
| --- | --- |
| [기존 001-brand.sql](../../apps/commerce-api/src/main/resources/db/schema/001-brand.sql) | 변경하지 않은 브랜드 최초 생성 스키마 |
| [002-user.sql](../../apps/commerce-api/src/main/resources/db/schema/002-user.sql) | 지정 ID·잔액·감사 시각 |
| [003-product.sql](../../apps/commerce-api/src/main/resources/db/schema/003-product.sql) | 브랜드 FK·이름·가격·재고·논리 삭제와 조회 인덱스 |
| [004-like.sql](../../apps/commerce-api/src/main/resources/db/schema/004-like.sql) | 예약어 인용·사용자와 상품 FK·복합 유일·집계와 본인 목록 인덱스 |
| [005-order.sql](../../apps/commerce-api/src/main/resources/db/schema/005-order.sql) | 주문 상태·생성 금액·확정 금액·시각·구매자 FK·최신순 조회 인덱스 |
| [006-order-item.sql](../../apps/commerce-api/src/main/resources/db/schema/006-order-item.sql) | 주문·상품 FK·생성 스냅샷·양수 수량·주문별 상품 유일 |
| [CommerceSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/schema/CommerceSchemaIntegrationTest.java) | 전용 빈 MySQL에서 실제 SQL 적용·JPA validate·DB 제약·관계 왕복 검증 |
| [commerce-schema-operations.md](commerce-schema-operations.md) | 수동 순서·DDL 부분 적용·초기 사용자·시각·실행 설정과 범위 |

운영 파일에는 DROP·데이터 초기화·자동 마이그레이션이 없다. `user`의 외부 식별자·역할 매핑을 SQL에 복제하지 않으며, 기존 FixtureUserIdentityRepository와 FixtureUserInitializer가 담당한다. 실행 프로파일의 기본 ddl-auto 설정과 ORM을 변경하지 않았다.

## 실제 Red → Green

| 단계 | 실제 실행 결과 | 반영 |
| --- | --- | --- |
| 최초 Red | 관계 전체 JPA 왕복 테스트 1개 실행·1개 실패. BeforeEach에서 기존 001을 적용한 뒤 `db/schema/002-user.sql` 누락으로 CannotReadScriptException/FileNotFoundException 발생 | 파일이 없었던 구현 전 실패를 확인한 뒤 002~006 SQL 작성 |
| 첫 검증 | 기존 BrandSchema 11개 전부 통과, 새 CommerceSchema 31개 중 20개 통과·11개 실패. JPA 6개 모델 validate와 전체 관계 왕복은 통과 | 11개 실패의 실제 SQL은 모두 CHECK에 의해 거절됨. JDBC 예외 분류 가정이 잘못된 원인을 분리 |
| 진단 보정 | MySQL CHECK 거절은 vendor code 3819·SQLSTATE HY000이며, 현재 Spring JDBC가 UncategorizedSQLException으로 감쌈. 최초 테스트의 DataIntegrityViolationException 기대와 다름 | CHECK 사례를 SQLException 오류 코드 3819와 해당 제약 이름까지 검사하도록 구체화. FK·NOT NULL·enum·유일 제약 기대와 모든 DB 전후 비교는 유지 |
| Green | CommerceSchema **31개**, 기존 BrandSchema **11개**, 합계 **42개 통과**. 실패·오류·건너뜀 0개 | Checkstyle main/test 위반 0개, git diff --check 통과 |
| 독립 검토 | 다른 구현 담당자가 모든 엔티티 컬럼·타입·nullable·FK·유일·시각·인덱스와 SQL을 읽어 비교 | 추가 SQL 결함 없음. CONFIRMED의 결제액 IS NOT NULL 및 초기 fixture 단일 관리 확인 |

마지막 통과 실행의 XML 시각은 새 스키마 `2026-09-18T04:48:07Z`, 기존 브랜드 `2026-09-18T04:48:19Z`다. 로컬 시간은 각각 13:48(KST)이며 증거 시점을 구분하기 위한 기록이다. 업무 기준이나 DB 제약을 제거·완화하여 통과시킨 것이 아니다.

## 검증 범위

| 새 테스트 묶음 | 실행 수 | 확인한 결과 |
| --- | ---: | --- |
| 실제 SQL과 JPA 매핑·전체 관계 왕복 | 1 | 자동 생성 없이 Brand·User·Product·ProductLike·Order·OrderItem validate 및 저장·조회. Unicode 100자, Long/Integer 상한, CONFIRMED 결제액, UTC 마이크로초 시각 |
| 물리 속성 | 1 | 6개 테이블, InnoDB·utf8mb4_general_ci, DATETIME(6) 12개, 이름 길이 100, BIGINT/INT, user ID 비자동 증가 |
| FK 대상 없음 | 6 | product→brand, like→user/product, order→user, order_item→order/product 모두 거절하고 6개 테이블의 기존 행 보존 |
| 복합 유일 제약 | 1 | 같은 사용자·상품과 같은 주문·상품 중복 거절. 다른 사용자/주문 또는 다른 상품 조합은 허용 |
| enum·필수값 | 7 | 알 수 없는 주문 상태, 필수 참조·이름·시각 null 거절과 저장 상태 보존 |
| 수량·금액·상태 CHECK | 10 | 각 이름 있는 CHECK가 오류 코드 3819로 해당 잘못된 상태를 거절 |
| 확정 결제 상태 | 1 | 정상 확정 허용, 총액과 다른 결제액·결제액 null·확정 시각 null 거절 |
| 논리 삭제와 연쇄 삭제 방지 | 1 | 상품 논리 삭제 후 주문항목·좋아요 보존, 참조된 상품·주문·사용자·브랜드의 물리 삭제 거절 |
| Unicode 상한 초과 | 1 | 상품명·주문 스냅샷 101자를 잘라 저장하지 않고 거절 |
| 인덱스 | 1 | 브랜드 미삭제 상품·최신/가격·상품 좋아요 집계·본인 좋아요/주문 최신순 인덱스의 실제 컬럼 순서 |
| 재실행 | 1 | 전체 생성 SQL과 새 user SQL의 재적용이 실패하고 모든 기존 행을 그대로 보존 |

6개 FK의 RESTRICT와 2개 복합 유일 제약을 사용한다. 상품 현재 가격은 1원 이상, 주문 단가 스냅샷·총액·결제액은 비음수라는 기존 설계 차이를 유지했다. CONFIRMED의 `paid_amount IS NOT NULL`은 CHECK에서 NULL이 통과할 수 있는 경계를 명시적으로 막는다. 상품별 좋아요 수 캐시나 별도 포인트 이력 테이블을 추가하지 않았다.

DB가 보장하지 않는 주문 항목 최소 한 건, 소계·총액 일치, 소유권, 미삭제 브랜드 등록, 여러 행의 원자성은 기존 도메인·application 검증에 남는다. 운영 CHECK가 수량 0의 직접 UPDATE를 거절하는 것과, Hibernate 생성 테스트 스키마에 손상 데이터를 준비해 500 처리·무차감을 검증하는 API-13의 목적을 구분한다.

## 재실행

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.infrastructure.schema.CommerceSchemaIntegrationTest' \
  --tests 'com.loopers.infrastructure.brand.BrandSchemaIntegrationTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  --console=plain -q
```

위 42개와 정적 검사는 실제 통과한 명령의 범위다. 전용 Testcontainers MySQL의 테이블만 테스트마다 자식부터 정리한다. 일반 Spring 테스트 DB·로컬·운영 데이터는 수정하지 않는다. 생성 파일은 여섯 개가 한 트랜잭션으로 적용되는 마이그레이션이 아니므로, 운영 적용 시 각 파일의 성공 여부와 부분 생성 상태를 확인해야 한다. 대용량 성능·운영 배포까지 수행했다는 의미는 아니다.
