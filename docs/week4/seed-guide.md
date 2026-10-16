# 증분 성능 데이터 준비

상태: **2026-10-16 D0 생성·검증 완료 / init·append 실제 실행 성공 / more_likes 미실행**. 아래 증분 호출 예시는 일반 사용법이며 이번에는 1,000개와 99,000개로 나누어 총 상품 100,000·좋아요 1,000,000을 생성했다. 생성 프로시저는 블로그 글에 포함하지 않는다.

## 현재 모델과 실행 환경

현재 모델의 `users`, `point`, `brand`, `product`, `product_like`와 공통 `id/created_at/updated_at/deleted_at`을 사용한다. UserFixture와 같이 사용자별 잔액 0의 Point를 함께 만든다. 상품 재고는 생성 기본값인 0이며 주문 검증의 재고·포인트는 별도 fixture나 기존 API로 준비한다. 직접 SQL 생성은 JPA callback을 실행하지 않으므로 이름·가격·재고·시간·관계 조건을 SQL에서 지정했다.

기존 Docker MySQL/Redis + IntelliJ 앱 실행을 사용할 수 있다. [현재 local JPA 설정](../../modules/jpa/src/main/resources/jpa.yml)은 `ddl-auto: create`이므로 그대로 재시작하면 앱 테이블이 재생성될 수 있다. 초기 테이블을 준비한 뒤 IntelliJ 실행 인자 등에 `--spring.jpa.hibernate.ddl-auto=validate` 또는 `none`을 명시하고 실제 적용을 확인한다. 이 문서 작업에서는 앱 설정 파일을 변경하지 않았다. JDBC URL이 같은 대상 schema를 가리키는지도 확인한다.

seed 설치·생성은 SQL 콘솔에서 수행한다. 열린 업무 TX 안에서 CALL하지 않고 준비 중 앱 쓰기를 중단한다. advisory lock은 seed끼리만 직렬화하며 앱 INSERT까지 막지 않는다. 사용자 선택대로 기존 로컬 MySQL의 loopers와 Redis를 함께 사용한다. Quest의 전용 실험 DB와 차이·동시 작업/실행 구간을 기록하고 기존 데이터/제약/인덱스는 보존한다. 최신 사용자 선택대로 비교는 loopers의 실제 테이블에서 수행하고 별도 schema·복제/테스트 테이블을 만들지 않는다. 실제 Redis 중단은 선택한 로컬 endpoint를 대상으로 측정/seed와 구간을 나누고 재시작·사용 가능 상태로 복구한다. 이번 문서 갱신에서는 자원을 조작하지 않았다.

## 설치 전 확인

현재 loopers schema를 선택하고 다음을 확인한 뒤 SQL 파일 전체를 설치한다. 앱의 모델 이름과 실제 DB가 같다는 가정만으로 실행하지 않는다.

```sql
SELECT DATABASE(), VERSION();
SET SESSION time_zone = '+00:00';
SHOW CREATE TABLE users;
SHOW CREATE TABLE point;
SHOW CREATE TABLE brand;
SHOW CREATE TABLE product;
SHOW CREATE TABLE product_like;
SHOW INDEX FROM product;
SHOW INDEX FROM product_like;
```

설치에는 CREATE ROUTINE과 실험용 관리 테이블의 권한이 필요하다. 프로시저는 호출자의 권한으로 실행한다. SQL 파일은 기존 업무 테이블을 DROP/TRUNCATE하거나 제약을 해제하지 않는다. 실행되는 DDL/숫자표 생성은 `w4_seed_*` 관리 자원만 만든다. 업무 데이터 CALL 예시는 주석이라 설치만으로 상품·좋아요가 추가되지 않는다.

## 100개 다음 1,000개 추가

v2 dataset명은 `-v2`로 끝낸다. 구버전 dataset에 이어 붙이지 않는다. 기존 v1 자료가 있다면 보존하고 실험 대상/혼입 여부부터 확인한다. 숫자표·기존 관리 테이블만 사용하며 별도 실험 테이블은 추가하지 않는다. 논리 순번의 완전 블록 순위를 계산한 뒤 요청 구간을 선택하므로 append 호출/commit chunk를 나누어도 같은 순번의 값은 같다.

```sql
-- dataset명, 브랜드 수, 사용자 수, 첫 브랜드에 배정할 상품 비율
CALL w4_seed_init('index-d0-v2', 100, 20000, 20);

-- dataset명, 추가할 상품 수, 좋아요 분포 배수, TX당 상품 수
CALL w4_seed_append('index-d0-v2', 100, 1, 1000);
CALL w4_seed_append('index-d0-v2', 1000, 1, 1000);
```

누적 상품은 1,100개다. `p_add_products`는 목표 총수가 아니라 **이번 호출에서 추가할 수**다. 기존 상품·관계는 수정하지 않는다. 논리 순번과 실제 ID를 따로 저장하므로 기존 DB의 ID가 1부터 시작할 필요가 없다.

v2는 완전한 1,000개 논리 순번 블록에서 브랜드·가격·좋아요에 서로 다른 고정 SHA2 salt의 순위를 사용한다. 좋아요 배수 1에서 `0/1/2/5/5/10/12/15/20/30` bucket을 각각 100개 상품에 배정해 블록당 합계 10,000개다. 100개·1,100개처럼 부분 블록으로 끝나면 좋아요 수는 단순히 상품 수의 10배가 아니므로 관리 합계와 실제 관계 수를 확인한다. 0개·동점·상대적으로 인기 있는 상품을 함께 만든다. 이 인공 분포를 실제 서비스의 분포로 설명하지 않는다.

## 승인한 기본 규모까지 늘리기

앞의 1,100개를 생성한 동일 dataset에서 다음을 호출하면 누적 상품 100,000·좋아요 1,000,000개다.

```sql
CALL w4_seed_append('index-d0-v2', 98900, 1, 1000);
```

100개→1,000개→100,000개라는 서로 다른 총량을 기록하고 싶다면 추가량을 각각 `100`, `900`, `99000`으로 쓴다. 한 번에 생성하는 API라도 내부는 지정 상품 수의 묶음별 INSERT ... SELECT와 commit으로 실행한다. 데이터 준비의 chunk 크기는 성능 비교용 묶음 쓰기 크기와 별개다.

완전한 1,000순번 블록마다 가격은 100원 간격의 1,000개 값을 한 번씩 배정한다. 첫 브랜드 배정 순위와 좋아요 bucket 순위는 가격 순위와 다른 salt로 정해 같은 가격이 항상 같은 좋아요 수를 갖던 기존 산술 결합을 제거한다. 이는 실제 서비스 분포나 통계적 독립을 보장한다는 뜻이 아니므로 브랜드×가격·가격×좋아요 교차 분포를 검증한다. 첫 브랜드는 hot 비율 20에서 상품 100,000개 중 20,000개를 갖는다. seed 기본값에는 삭제 행이 없으므로 deleted_at 열의 선택도 효과는 이 자료만으로 입증할 수 없다. D0 읽기를 모두 끝낸 뒤 같은 자료에서 브랜드별 고정 hash 순위로 총 30,000개를 선정한 DDEL을 비교하며 비노출 정확성은 별도 테스트로 확인한다.

## 상품 수를 유지하고 좋아요만 추가

```sql
-- 논리 상품 순번 10 한 개에 1,000개 추가: 강한 인기 쏠림 조건
CALL w4_seed_more_likes('index-d0-v2', 10, 10, 1000, 100);

-- 순번 1..100000에 각각 10개 추가: 집계 관계 수만 변화
CALL w4_seed_more_likes('index-d0-v2', 1, 100000, 10, 1000);
```

같은 사용자–상품 중복은 만들지 않으며 상품당 관계 수는 초기 사용자 pool보다 커질 수 없다. 단일 호출의 추가 관계는 상품당 최대 10,000개다. 앱에서 해당 좋아요를 변경한 뒤에는 seed 관리 count와 달라질 수 있으므로 이 프로시저를 쓰지 않는다. 소스 비교 중 API 변경이 필요하면 별도 dataset이나 테스트를 사용한다.

사용자·브랜드 pool은 init에서 정하고 해당 dataset의 append 중에는 고정한다. 현재 작성한 SQL의 증분 대상은 **상품과 좋아요**다. 사용자/브랜드 pool까지 확대하려면 다른 dataset을 초기화한다. 같은 schema에 dataset 둘을 만들면 API 결과에는 둘 다 보이므로 동일 자료의 전후 비교에서는 섞지 않는다.

## 분포와 생성 결과 확인

```sql
SELECT dataset, brand_base+1 AS hot_brand_id, user_base+1 AS http_user_id,
       brand_count, user_count, last_product_seq FROM w4_seed_dataset;

SELECT COUNT(*) AS mapped_products, SUM(seed_like_count) AS planned_likes,
       MIN(product_id) AS first_product_id, MAX(product_id) AS last_product_id
FROM w4_seed_product WHERE dataset='index-d0-v2';

SELECT COUNT(*) AS actual_products, SUM(p.price) AS price_checksum
FROM product p JOIN w4_seed_product s ON s.product_id=p.id
WHERE s.dataset='index-d0-v2';

SELECT COUNT(*) AS actual_likes
FROM product_like l JOIN w4_seed_product s ON s.product_id=l.product_id
WHERE s.dataset='index-d0-v2';

SELECT p.brand_id, COUNT(*) AS products
FROM product p JOIN w4_seed_product s ON s.product_id=p.id
WHERE s.dataset='index-d0-v2' GROUP BY p.brand_id ORDER BY products DESC;

SELECT seed_like_count, COUNT(*) AS products
FROM w4_seed_product WHERE dataset='index-d0-v2'
GROUP BY seed_like_count ORDER BY seed_like_count;
```

추가로 브랜드별 `COUNT(DISTINCT p.price)`와 가격 범위별 상품 수, `(p.price, s.seed_like_count)`별 건수를 교차 집계한다. 대표 hot 브랜드가 전체 가격 구간을 포함하는지, 같은 가격이 여러 좋아요 bucket과 결합하는지 확인한다. 정적 생성식 점검과 실제 MySQL 생성 검증은 구분한다.

상품별 관리값만 보지 말고 실제 DB 관계 수와 일치하는지도 확인한다. 시간 측정 전 통계 갱신 절차는 모든 후보에서 같게 한다.

## 실패와 재개

init은 하나의 TX로 완료되거나 rollback된다. append/more_likes는 chunk마다 commit하므로 중간 실패 시 완료한 chunk는 남는다. 관리값과 실제 건수를 확인해 남은 수량만 추가한다. 같은 CALL을 다시 보내면 추가량이 다시 적용되므로 목표 총수에 자동 수렴하는 방식은 아니다. more_likes 재개에서는 완료한 순번 범위를 제외한다.

단순 CALL 재전송을 멱등으로 취급하지 않고 실제 건수·매핑을 확인한다. 관리 테이블은 앱의 domain 테이블이나 migration이 아니다. 스키마를 다시 만들었다면 관리값과의 일치를 확인하고 이전 seed와 같은 조건이라고 간주하지 않는다.
