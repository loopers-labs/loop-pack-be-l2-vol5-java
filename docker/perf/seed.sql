-- =====================================================================
-- 4주차 조회 성능 실습 시드 — 네이버 스토어형 분포
--   brand        500개    (대형 20 · 중형 130 · 소형 350, 그중 5개 입점 종료)
--   product      약 30만  (대형 3000~9000 · 중형 500~1500 · 소형 20~300 / 브랜드, 단종 약 3%)
--   product_like 약 480만 (사용자 10만 명, 취소 약 10%)
--   합계 약 500만 행 — 데이터·인덱스가 버퍼 풀(1GB, perf-compose.yml)에 들어가는 규모
--
-- 대상 DB : docker/perf-compose.yml 의 loopers_perf (테이블은 앱 실행 시 ddl-auto=create 로 먼저 만들어 둔다)
-- 실행    : docker exec -i loopers-perf-mysql mysql -uapplication -papplication loopers_perf < docker/perf/seed.sql
-- 검증    : docker/perf/verify.sql
--
-- 재현성  : RAND() 대신 CRC32('키') 해시로 값을 정하고, TRUNCATE 후 정해진 순서로 넣어 id까지 매번 같다.
-- 주의    : brand·product·product_like 의 기존 행을 모두 지우고 다시 만든다. 다른 테이블은 건드리지 않는다.
--           캐시 revision 테이블도 비워 0으로 돌아가므로, 같은 revision의 옛 캐시가 남지 않게 실습 Redis도 비운다:
--           docker exec loopers-perf-redis redis-cli FLUSHALL   (perf-compose.yml의 실습 전용 Redis에만 실행한다)
-- =====================================================================
USE loopers_perf;
SET NAMES utf8mb4;

-- 캐시 revision 테이블 — 앱(ddl-auto=create)이 엔티티로 만드는 것과 같은 정의. 측정 실행은 ddl-auto=none이라 여기서 만든다.
-- 시드와 함께 0에서 다시 시작하는 파생 데이터라, 이전 구조가 남아 있어도 맞춰지게 지우고 다시 만든다
DROP TABLE IF EXISTS product_list_revision;
CREATE TABLE product_list_revision (
    brand_id bigint not null,
    latest_revision bigint not null,
    likes_revision bigint not null,
    price_revision bigint not null,
    slot bigint not null,
    primary key (brand_id, slot)
) engine=InnoDB;
DROP TABLE IF EXISTS product_revision;
CREATE TABLE product_revision (
    product_id bigint not null,
    revision bigint not null,
    primary key (product_id)
) engine=InnoDB;

SET SESSION cte_max_recursion_depth = 9000;
-- 사용자 변수에 날짜 값을 넣으면 latin1 문자열로 바뀌므로 문자열로 두고 쓸 때 TIMESTAMP()로 감싼다
SET @base := '2024-10-01 00:00:00';      -- 상품 등록 기간 시작
SET @end := '2026-10-01 00:00:00';       -- 상품 등록·좋아요 기간 끝 (2년)
SET @closed_at := '2026-10-02 00:00:00'; -- 입점 종료 시각이자 모든 삭제 시각의 상한

TRUNCATE TABLE product_like;
TRUNCATE TABLE product;
TRUNCATE TABLE brand;

-- 1~9000 숫자표: 브랜드별 상품 수(최대 9000)·상품별 좋아요 수(최대 2000)만큼 행을 펼치는 데 쓴다
DROP TEMPORARY TABLE IF EXISTS seed_seq;
CREATE TEMPORARY TABLE seed_seq (n INT PRIMARY KEY);
INSERT INTO seed_seq
WITH RECURSIVE r (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 9000)
SELECT n FROM r;

-- ---------------------------------------------------------------------
-- 1) brand 500개
--    1~20 대형(스토어형 실제 브랜드명), 21~150 중형, 151~500 소형 (접두어 24 × 접미어 20 조합)
--    5개는 입점 종료로 삭제하고, 그 브랜드 상품은 2)에서 전부 함께 삭제한다 (브랜드 일괄 삭제 계약)
-- ---------------------------------------------------------------------
INSERT INTO brand (name, created_at, updated_at, deleted_at)
SELECT name, created_at, COALESCE(deleted_at, created_at), deleted_at
FROM (
    SELECT n,
           IF(n <= 20,
              ELT(n, '나이키', '아디다스', '삼성전자', 'LG전자', '애플', '농심', 'CJ제일제당', '오뚜기',
                  '아모레퍼시픽', '이니스프리', '무신사 스탠다드', '유니클로', '탑텐', '한샘', '이케아',
                  '락앤락', '뉴발란스', '하기스', '베베숲', '로얄캐닌'),
              CONCAT(
                  ELT(FLOOR((n - 21) / 20) + 1, '데일리', '모던', '그린', '어반', '클래식', '내추럴', '스마트',
                      '해피', '퓨어', '리틀', '블루', '코지', '비비드', '심플', '원더', '루나', '오가닉', '노블',
                      '프레시', '슬로우', '써니', '무드', '위드', '바른'),
                  ELT((n - 21) % 20 + 1, '웨어', '핏', '클로젯', '테크', '일렉', '팜', '키친', '푸드', '뷰티',
                      '코스메틱', '홈', '리빙', '하우스', '스포츠', '아웃도어', '베이비', '키즈', '펫', '독앤캣',
                      '마켓'))) AS name,
           TIMESTAMP(@base) - INTERVAL 30 DAY + INTERVAL n MINUTE AS created_at,
           IF(n IN (88, 175, 260, 333, 420), TIMESTAMP(@closed_at), NULL) AS deleted_at
    FROM seed_seq
    WHERE n <= 500
) b
ORDER BY n;

-- 브랜드별 카테고리·상품 수
-- 카테고리: 1 패션, 2 디지털, 3 식품, 4 뷰티, 5 리빙, 6 스포츠, 7 유아, 8 반려동물
-- 대형은 브랜드마다 지정, 나머지는 브랜드명 접미어로 정한다 (예: ~웨어 → 패션, ~펫 → 반려동물)
DROP TEMPORARY TABLE IF EXISTS seed_brand_plan;
CREATE TEMPORARY TABLE seed_brand_plan (
    brand_id      BIGINT PRIMARY KEY,
    brand_name    VARCHAR(255) NOT NULL,
    category      INT NOT NULL,
    product_count INT NOT NULL,
    closed_at     DATETIME(6) NULL
);
INSERT INTO seed_brand_plan
SELECT id,
       name,
       CAST(IF(id <= 20,
               ELT(id, 6, 6, 2, 2, 2, 3, 3, 3, 4, 4, 1, 1, 1, 5, 5, 5, 6, 7, 7, 8),
               ELT((id - 21) % 20 + 1, 1, 6, 1, 2, 2, 3, 5, 3, 4, 4, 5, 5, 5, 6, 6, 7, 7, 8, 8, 3)) AS UNSIGNED),
       CASE WHEN id <= 20 THEN 3000 + CRC32(CONCAT('pc:', id)) % 6001
            WHEN id <= 150 THEN 500 + CRC32(CONCAT('pc:', id)) % 1001
            ELSE 20 + CRC32(CONCAT('pc:', id)) % 281 END,
       deleted_at
FROM brand;

-- ---------------------------------------------------------------------
-- 2) product 약 30만 개
--    이름  : [브랜드] 품목 옵션 모델코드 (100자 이내)
--    가격  : 카테고리 가격대 안에서 낮은 쪽에 몰리게(u²) 정한 뒤 1,000원 단위 + 끝자리 900 또는 000
--            → 같은 가격이 자연스럽게 많이 생긴다
--    재고  : 약 5%는 0(품절), 나머지 0~499
--    삭제  : 입점 종료 브랜드 상품은 전부, 나머지는 약 3%를 단종으로 삭제
--    순서  : 등록 시각 순서로 넣어 최근 상품일수록 id가 크다
-- ---------------------------------------------------------------------
INSERT INTO product (brand_id, name, price, stock, created_at, updated_at, deleted_at)
SELECT brand_id, name, price, stock, created_at, COALESCE(deleted_at, created_at), deleted_at
FROM (
    SELECT brand_id, k, name, price, stock, created_at,
           CASE WHEN closed_at IS NOT NULL THEN closed_at
                WHEN h_del % 100 < 3 THEN LEAST(created_at + INTERVAL (h_del % 180 + 1) DAY, TIMESTAMP(@closed_at))
           END AS deleted_at
    FROM (
        SELECT brand_id, k, closed_at, h_del,
               CONCAT('[', brand_name, '] ',
                      ELT((category - 1) * 10 + h_item % 10 + 1,
                          -- 1 패션
                          '오버핏 맨투맨', '와이드 데님 팬츠', '린넨 셔츠', '경량 패딩', '니트 가디건',
                          '세미와이드 슬랙스', '후드 집업', '트렌치 코트', '플리츠 스커트', '반팔 티셔츠',
                          -- 2 디지털
                          '무선 블루투스 이어폰', '블루투스 스피커', '무선 게이밍 마우스', '기계식 키보드',
                          '27인치 QHD 모니터', '고속충전 보조배터리', '알루미늄 노트북 거치대', '스마트워치 밴드',
                          'USB-C 멀티 허브', '태블릿 거치대',
                          -- 3 식품
                          '생수 2L 12병', '즉석밥 210g 24개', '하루견과 30봉', '닭가슴살 10팩', '컵라면 6개입',
                          '그릭요거트 450g', '냉동 물만두 1kg', '콜드브루 원액 1L', '포기김치 5kg', '단백질 바 12개입',
                          -- 4 뷰티
                          '수분크림 50ml', '톤업 선크림 SPF50+', '약산성 클렌징폼', '비타민C 세럼', '벨벳 립틴트',
                          '커버 쿠션 팩트', '각질 토너 패드', '헤어 에센스', '대용량 바디로션', '시트 마스크팩 10매',
                          -- 5 리빙
                          '극세사 차렵이불', '서랍형 수납함', '유리 밀폐용기 세트', '무선 핸디 청소기', '암막 커튼',
                          '실내 디퓨저', '원목 3단 선반', '스텐 편수 냄비', '메모리폼 베개', '규조토 욕실 매트',
                          -- 6 스포츠
                          '쿠셔닝 러닝화', 'TPE 요가 매트', '아령 덤벨 세트', '등산 배낭 30L', '트레이닝 조거 팬츠',
                          '쿨링 기능성 티셔츠', 'EVA 폼롤러', '경량 캠핑 의자', '자전거 반장갑', '미러 수경',
                          -- 7 유아
                          '팬티형 기저귀 대형', '아기 물티슈 캡형 10팩', '실리콘 유아 식판', '베이비 로션',
                          'PPSU 젖병 세트', '오가닉 유아 내복', '범퍼 침대', '실리콘 치발기', '힙시트 아기띠',
                          '유아 칫솔 세트',
                          -- 8 반려동물
                          '강아지 사료 2kg', '두부 고양이 모래 7L', '배변패드 100매', '고양이 짜먹는 간식 20개',
                          '강아지 하네스', '골판지 스크래쳐', '자동 급식기', '푹신 쿠션 방석', '노즈워크 담요',
                          '원목 캣타워'),
                      ' ',
                      ELT(FLOOR(h_item / 10) % 12 + 1, '블랙', '화이트', '그레이', '네이비', '베이지', '1+1',
                          '2개 세트', '대용량', '기획세트', '단품', '스페셜 에디션', '리뉴얼'),
                      ' ', CHAR(65 + FLOOR(h_item / 120) % 26 USING utf8mb4), LPAD(k, 4, '0')) AS name,
               GREATEST(price_min, LEAST(price_max,
                   FLOOR((price_min + (price_max - price_min) * POW((h_price % 10000) / 10000, 2)) / 1000) * 1000
                   + IF(FLOOR(h_price / 10000) % 3 = 0, 0, 900))) AS price,
               IF(h_stock % 100 < 5, 0, h_stock % 500) AS stock,
               TIMESTAMP(@base) + INTERVAL (h_time % (730 * 86400)) SECOND AS created_at
        FROM (
            SELECT bp.brand_id, bp.brand_name, bp.category, bp.closed_at, s.n AS k,
                   CAST(ELT(bp.category, 9900, 19900, 3900, 5900, 4900, 9900, 5900, 3900) AS UNSIGNED) AS price_min,
                   CAST(ELT(bp.category, 299000, 3490000, 59900, 129000, 499000, 399000, 199000, 99000) AS UNSIGNED)
                       AS price_max,
                   CRC32(CONCAT('pi:', bp.brand_id, ':', s.n)) AS h_item,
                   CRC32(CONCAT('pp:', bp.brand_id, ':', s.n)) AS h_price,
                   CRC32(CONCAT('ps:', bp.brand_id, ':', s.n)) AS h_stock,
                   CRC32(CONCAT('pt:', bp.brand_id, ':', s.n)) AS h_time,
                   CRC32(CONCAT('pd:', bp.brand_id, ':', s.n)) AS h_del
            FROM seed_brand_plan bp
            JOIN seed_seq s ON s.n <= bp.product_count
        ) h
    ) v
) p
ORDER BY created_at, brand_id, k;

-- ---------------------------------------------------------------------
-- 3) product_like 약 480만 행 — 사용자 10만 명 (user_id 1~100000, 사용자 테이블은 없음)
--    상품별 좋아요 수: 0개 30% · 1~10개 50% · 11~50개 17% · 51~300개 2.7% · 301~2000개 0.3%
--    k번째 좋아요의 사용자 = (상품별 시작값 + k × 7919) % 100000 + 1
--      → 7919와 100000이 서로소라 k < 100000 이면 겹치지 않아 (user_id, product_id)가 유일하다
--    약 10%는 취소(논리 삭제) 상태, 좋아요 시각은 상품 등록 이후
-- ---------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS seed_like_plan;
CREATE TEMPORARY TABLE seed_like_plan (
    product_id         BIGINT PRIMARY KEY,
    product_created_at DATETIME(6) NOT NULL,
    like_count         INT NOT NULL,
    user_offset        BIGINT NOT NULL
);
INSERT INTO seed_like_plan
SELECT id,
       created_at,
       CASE WHEN r < 300 THEN 0
            WHEN r < 800 THEN 1 + h % 10
            WHEN r < 970 THEN 11 + h % 40
            WHEN r < 997 THEN 51 + h % 250
            ELSE 301 + h % 1700 END,
       CRC32(CONCAT('lu:', id))
FROM (
    SELECT id, created_at, CRC32(CONCAT('lr:', id)) % 1000 AS r, CRC32(CONCAT('lc:', id)) AS h
    FROM product
) t;

INSERT INTO product_like (user_id, product_id, created_at, updated_at, deleted_at)
SELECT user_id, product_id, created_at, COALESCE(deleted_at, created_at), deleted_at
FROM (
    SELECT user_id, product_id, k, created_at,
           IF(h_cancel % 10 = 0, LEAST(created_at + INTERVAL (h_cancel % 30 + 1) DAY, TIMESTAMP(@closed_at)), NULL)
               AS deleted_at
    FROM (
        SELECT lp.product_id,
               s.n AS k,
               (lp.user_offset + (s.n - 1) * 7919) % 100000 + 1 AS user_id,
               LEAST(lp.product_created_at
                         + INTERVAL (CRC32(CONCAT('lt:', lp.product_id, ':', s.n)) % (365 * 86400)) SECOND,
                     TIMESTAMP(@end)) AS created_at,
               CRC32(CONCAT('lx:', lp.product_id, ':', s.n)) AS h_cancel
        FROM seed_like_plan lp
        JOIN seed_seq s ON s.n <= lp.like_count
    ) h
) l
ORDER BY created_at, product_id, k;

-- 실행 계획이 실제 분포를 반영하도록 통계 갱신
ANALYZE TABLE brand, product, product_like;

DROP TEMPORARY TABLE seed_like_plan;
DROP TEMPORARY TABLE seed_brand_plan;
DROP TEMPORARY TABLE seed_seq;

SELECT 'brand' AS tbl, COUNT(*) AS total_rows FROM brand
UNION ALL SELECT 'product', COUNT(*) FROM product
UNION ALL SELECT 'product_like', COUNT(*) FROM product_like;
