# 커머스 스키마 적용

[전체 설계](commerce-erd-draft.md) · [완료 체크리스트](commerce-completion-checklist.md) · [전체 스키마 TDD](commerce-tdd-schema-log.md) · [기존 브랜드 스키마 TDD](commerce-tdd-brand-schema-log.md)

## 제공 범위와 적용 순서

커머스의 6개 테이블을 빈 MySQL 8 데이터베이스에 수동으로 최초 생성한다. 기존 `001-brand.sql`은 변경하지 않았고 나머지 5개 파일을 추가했다. P14에 따라 JPA 기능 구현 후 실제 모델에 맞춘 운영 SQL을 작성했다.

| 순서·파일 | 생성하는 테이블·주요 제약 |
| --- | --- |
| [001-brand.sql](../../apps/commerce-api/src/main/resources/db/schema/001-brand.sql) | brand, 이름 VARCHAR(100), 생성·수정·논리 삭제 시각 |
| [002-user.sql](../../apps/commerce-api/src/main/resources/db/schema/002-user.sql) | user, fixture가 지정하는 ID, 비음수 BIGINT 잔액 |
| [003-product.sql](../../apps/commerce-api/src/main/resources/db/schema/003-product.sql) | product, brand FK, 1원 이상 BIGINT 가격·비음수 INT 재고, 논리 삭제 |
| [004-like.sql](../../apps/commerce-api/src/main/resources/db/schema/004-like.sql) | 인용한 `like`, user/product FK, UNIQUE(user_id, product_id) |
| [005-order.sql](../../apps/commerce-api/src/main/resources/db/schema/005-order.sql) | 인용한 `order`, user FK, DRAFT/CONFIRMED, 금액·결제 상태의 CHECK |
| [006-order-item.sql](../../apps/commerce-api/src/main/resources/db/schema/006-order-item.sql) | order_item, order/product FK, UNIQUE(order_id, product_id), 양수 수량·생성 스냅샷 |

SQL 파일은 애플리케이션 시작 시 자동 실행되지 않는다. 새 마이그레이션 프레임워크나 자동 스키마 적용 프로파일을 추가하지 않았다. 파일의 번호는 수동 적용 순서다. 기존 예시 API의 ExampleModel 테이블은 이 커머스 6개 테이블 범위에 포함하지 않는다.

## 빈 데이터베이스에 최초 생성

프로젝트 루트에서 MySQL 클라이언트를 실행한다. 아래 접속값은 [로컬 compose](../../docker/infra-compose.yml)의 값이며 대상 환경의 연결 정보로 바꿀 수 있다. DB 자체는 먼저 생성되어 있어야 한다. 비밀번호는 프롬프트에서 입력한다.

```shell
mysql --host=127.0.0.1 --port=3306 --user=application --password \
  --default-character-set=utf8mb4 loopers
```

다음 SOURCE를 **한 줄씩**, 직전 단계의 성공을 확인하고 실행한다. 오류가 발생하면 남은 단계를 진행하지 않는다.

```sql
SOURCE apps/commerce-api/src/main/resources/db/schema/001-brand.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/002-user.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/003-product.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/004-like.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/005-order.sql;
SOURCE apps/commerce-api/src/main/resources/db/schema/006-order-item.sql;
```

각 파일은 한 테이블의 CREATE TABLE이다. MySQL DDL의 암묵적 커밋 때문에 여섯 파일이 하나의 트랜잭션으로 취소되지는 않는다. 중간 파일이 실패하면 앞서 성공한 테이블은 남는다. 실패 원인과 실제 생성 상태를 확인한 뒤 남은 적용을 판단하며, 기존 테이블을 삭제하고 무조건 처음부터 다시 실행하는 절차를 제공하지 않는다.

이미 대상 테이블이 있으면 해당 CREATE가 실패한다. 기존 데이터를 지우거나 `IF NOT EXISTS`로 구조 차이를 숨기지 않는다. 이전에 브랜드 SQL만 적용했다면 기존 brand 구조가 검증한 001과 같은지 확인한 후 002부터 진행한다. 운영 중인 다른 스키마를 변경·교체하는 마이그레이션은 이번 최초 생성 파일의 역할이 아니다.

## 저장 형태와 제약

모든 테이블은 InnoDB·utf8mb4_general_ci를 사용한다. ID·가격·금액·잔액은 signed BIGINT, 재고·수량은 signed INT다. user ID만 애플리케이션 fixture가 지정하며 나머지 테이블의 ID는 AUTO_INCREMENT다. 브랜드·상품명과 주문 상품명 스냅샷은 VARCHAR(100)이고 이름에 유일 제약을 추가하지 않는다.

6개 FK는 모두 필수이며 참조 행의 물리 삭제를 RESTRICT한다. 상품·브랜드 논리 삭제는 deleted_at만 갱신하므로 주문항목·좋아요를 보존한다. 좋아요 취소는 좋아요 관계 행만 물리 삭제한다. 복합 유일 제약은 사용자별 동일 상품 좋아요 한 건, 주문별 동일 상품 항목 한 건을 보장하며 각 단일 컬럼을 유일하게 만들지 않는다.

DB CHECK는 잔액·재고의 비음수, 현재 상품 가격의 1원 이상, 주문 수량의 양수, 스냅샷 단가·총액·결제액의 비음수를 보장한다. DRAFT의 결제액·확정 시각은 null이고 CONFIRMED는 결제액이 총액과 같으며 확정 시각이 필수다. CONFIRMED의 결제액에는 명시적인 IS NOT NULL 조건도 적용해 SQL CHECK의 NULL 판정이 누락값을 허용하지 않도록 했다.

FK는 브랜드의 미삭제 상태나 주문 소유권을 확인하지 않는다. 이름 strip, 주문 항목 최소 한 건, 소계·총액 계산, 재고·포인트 검증과 여러 행의 원자성은 JPA 모델과 application 트랜잭션이 담당한다. 운영 CHECK를 추가한 이유로 해당 도메인 검증이나 HTTP 오류 처리를 삭제하지 않았다.

인덱스는 브랜드의 미삭제 상품 존재 조회, 고객 상품의 최신·가격 정렬, 상품별 좋아요 집계, 본인 좋아요·주문 최신순 조회를 지원한다. 좋아요 수 정렬은 관계를 집계한 다음 정렬하며 캐시 카운터를 추가하지 않았다. 인덱스의 존재·컬럼 순서는 검증했지만 대용량 운영 부하의 실행 계획·처리량 보장을 뜻하지 않는다.

## 애플리케이션 설정과 초기 사용자

[jpa.yml](../../modules/jpa/src/main/resources/jpa.yml)의 local/test 기본값은 `ddl-auto=create`다. 수동 생성한 스키마와 데이터를 유지하려면 실행 시 자동 생성을 끈다.

```shell
./gradlew :apps:commerce-api:bootRun --args='--spring.jpa.hibernate.ddl-auto=none'
```

사용자 ID·역할·외부 식별자는 [FixtureUserIdentityRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserIdentityRepository.java)의 단일 매핑을 따른다. 운영 SQL에 같은 매핑을 복사하거나 별도 외부 ID 컬럼·회원 가입 기능을 추가하지 않았다. [FixtureUserInitializer](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserInitializer.java)가 애플리케이션 시작 시 `alice=1`, `bob=2`, `admin=3`의 빠진 user 행을 잔액 0으로 만든다. 이미 있는 행은 잔액과 감사 시각을 덮어쓰지 않는다. 재시작으로 충전한 잔액이 0으로 돌아가지 않는 동작은 사용자 영속성 테스트에서 별도로 검증한다.

모든 감사·확정 시각은 DATETIME(6)이다. DATETIME 자체에 시간대가 없으므로 기존 `hibernate.timezone.default_storage=NORMALIZE_UTC`·`hibernate.jdbc.time_zone=UTC`를 유지하며 UTC로 해석한다. 직접 SQL로 시각을 넣을 때도 UTC 값을 사용한다. 한글·이모지 100 코드 포인트와 마이크로초 시각의 실제 JPA 왕복을 검증했다.

배포 대상에서도 strict SQL mode와 CHECK 집행 여부를 확인한다. SQL 파일 자체가 세션 SQL mode를 변경하지는 않는다.

```sql
SELECT VERSION(), @@SESSION.sql_mode;
SHOW CREATE TABLE product;
SHOW CREATE TABLE `order`;
SELECT id, point_balance FROM `user` ORDER BY id;
```

## 검증 방법과 테스트 데이터 경계

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.infrastructure.schema.CommerceSchemaIntegrationTest' \
  --tests 'com.loopers.infrastructure.brand.BrandSchemaIntegrationTest' \
  --console=plain -q
```

전체 스키마 테스트는 전용 임시 MySQL 컨테이너에서 수동 SQL을 먼저 적용하고, 커머스 6개 엔티티를 Hibernate `validate`로만 검사한다. Hibernate 자동 생성 없이 관계 전체를 저장·조회하며 FK·복합 유일·CHECK·NOT NULL·Unicode·시각·인덱스·재실행 보존을 확인한다. 기존 브랜드 전용 테스트와 컨테이너도 분리하며 로컬·운영 DB의 데이터를 정리하지 않는다.

API-13의 저장 데이터 손상 방어 테스트는 Hibernate가 생성한 테스트 스키마에 수량 0을 직접 준비해 서버의 500 처리·무차감 상태 보존을 확인한다. 실제 운영 SQL의 CHECK(quantity > 0)는 그 직접 UPDATE부터 거절한다. 두 테스트의 저장소 준비 방식과 검증 책임을 구분하며, 운영 제약을 완화해서 손상 fixture를 허용하지 않는다. 실제 Red·Green 결과와 수치는 [전체 스키마 기록](commerce-tdd-schema-log.md)에 남긴다.
