# 4주차 측정 환경 — 비교 실험의 전제

인덱스·쿼리 개선 전후, 캐시 전후를 비교하려면 **바뀌는 것이 비교 대상 하나뿐**이어야 한다. 이 문서는 그 전제와 근거(실제로 확인한 환경 값)를 기록한다. 측정 결과 문서는 이 환경을 기준으로 한다.

- 기록일: 2026-10-15
- 대상: `docker/perf-compose.yml`의 `loopers-perf-mysql` 컨테이너, DB `loopers_perf`
- 시드·검증: `docker/perf/seed.sql`, `docker/perf/verify.sql`

## 1. 전제 요약

| 전제 | 보장 방법 | 확인 값 |
|---|---|---|
| 같은 데이터 | `seed.sql`이 해시 공식으로 값을 정하고 TRUNCATE 후 같은 순서로 넣는다 (`RAND()` 미사용) | `verify.sql` [7] 체크섬이 아래 5절 값과 같아야 측정한다 |
| 바뀌는 것은 하나 | 같은 컨테이너·설정·앱에서 인덱스(또는 쿼리·캐시)만 바꾼다. 인덱스 없는 별도 테이블·DB를 두지 않는다 | 측정 직전 `SHOW INDEX`를 기록한다 |
| 메모리 기준 측정 | 측정 대상 데이터·인덱스가 버퍼 풀에 모두 들어가는 크기로 잡고, warm-up 뒤 측정한다 | 데이터 535MB 적재 / 버퍼 풀 1GB (7절) |
| 스키마 고정 | 앱은 `ddl-auto=none`으로 실행해 JPA가 스키마를 건드리지 않게 한다 | 실행 명령 9절 |
| 자원 경쟁 최소화 | 측정 중에는 이 컨테이너만 실행한다 | `mysql-undo-lab` 중지 확인 |
| 공용 DB와 분리 | 로컬 공용 DB(3306 Windows MySQL, 3307 MariaDB)·다른 실험 컨테이너와 포트·볼륨을 나눈다 | `127.0.0.1:3309`, 볼륨 `loopers-perf_mysql-perf-data` |

## 2. 하드웨어·OS

| 항목 | 값 |
|---|---|
| CPU | AMD Ryzen 7 7735HS, 8코어 16스레드 |
| 메모리 | 27.3GB (측정 시점 여유 약 8GB) |
| 디스크 | NVMe SSD 954GB (C:, 여유 272GB) |
| OS | Windows 11 Pro 10.0.26200 |

## 3. Docker

| 항목 | 값 |
|---|---|
| 엔진 | Docker Desktop 27.1.1, WSL2 백엔드 (커널 6.18.54.1-microsoft-standard-WSL2) |
| VM 자원 | vCPU 16, 메모리 13.3GB (`.wslconfig` 없음 → WSL2 기본값) |
| 컨테이너 제한 | CPU·메모리 제한 없음 |
| 디스크 이미지 | `docker_data.vhdx` 4.6GB (컨테이너·볼륨·이미지 포함) |
| 함께 있는 컨테이너 | `mysql-undo-lab` (다른 실험, 측정 중 중지) |

## 4. MySQL 설정

이미지 `mysql:8.0` (8.0.46). 프로젝트 테스트(Testcontainers)·`infra-compose.yml`과 같은 이미지다. **기본값과 다른 설정은 버퍼 풀 크기 하나뿐**이다.

| 설정 | 값 | 비고 |
|---|---|---|
| `innodb_buffer_pool_size` | **1GB** (8 instances) | 기본 128MB에서 변경. 7절 |
| `innodb_page_size` | 16KB | |
| `innodb_old_blocks_pct` / `time` | 37 / 1000ms | 전체 스캔이 자주 쓰는 페이지를 밀어내지 않게 하는 LRU 중간 삽입 |
| `innodb_stats_persistent_sample_pages` | 20 | 실행 계획 통계는 표본 추정. 측정 전 `ANALYZE TABLE` |
| `sort_buffer_size` | 256KB | 큰 정렬은 디스크 병합으로 넘어갈 수 있다 (filesort 비용 해석 시 참고) |
| `tmp_table_size` | 16MB | |
| `transaction_isolation` | REPEATABLE-READ | |
| `innodb_flush_log_at_trx_commit` | 1 | |
| `character_set_server` / `collation_server` | utf8mb4 / utf8mb4_general_ci | 테스트 컨테이너와 같음 |
| binlog | ON (MySQL 8 기본) | 읽기 측정과 무관. 시드 1회에 약 200MB씩 쌓인다 |

## 5. 데이터 (시드)

네이버 스토어형 분포. 브랜드·상품·좋아요 3개 테이블만 채우고 `orders`·`order_item`·`user_point`·`example`은 비워 둔다.

| 테이블 | 전체 | 활성 | 삭제 | 체크섬 (`verify.sql` [7]) |
|---|---|---|---|---|
| `brand` | 500 | 495 | 5 (입점 종료) | 1248679101 |
| `product` | 297,314 | 286,401 | 10,913 | 1649620414 |
| `product_like` | 4,688,494 | 4,219,007 | 469,487 (취소) | 1772200849 |
| 합계 | 4,986,308 | | | |

**분포** (활성 기준)

| 항목 | 값 |
|---|---|
| 브랜드당 상품 | 대형 20개 3,166~8,347 / 중형 129개 485~1,437 / 소형 346개 19~292 |
| 가격 | 서로 다른 가격 6,582가지, 같은 가격 최대 4,709개 (끝자리 900·000 위주) |
| 상품당 좋아요 | 0개 88,028 · 1~10개 143,062 · 11~50개 47,105 · 51~300개 7,412 · 301개 이상 794 (최대 1,799) |
| 규칙 위반 | 0건 (`verify.sql` [2] 9개 항목) |

**측정 후보 브랜드** (확정은 측정 단계에서)

| 구분 | 브랜드 | 활성 상품 | 가격 합계 | 서로 다른 가격 | 같은 가격 최대 |
|---|---|---|---|---|---|
| 대형 1위 | 하기스 (id 18) | 8,347 | 578,940,300 | 387 | 322 |
| 대형 | 뉴발란스 (id 17) | 8,020 | 1,123,878,600 | 776 | 187 |
| 소형 | 스마트홈 (id 151) | 139 | 26,084,800 | 125 | 3 |

같은 가격 상품이 많아서, 가격순 정렬에 동점 보조 기준이 없으면 인덱스 전후로 동점 상품의 순서가 달라질 수 있다. 결과 비교 전에 동점 정렬 기준을 먼저 정한다.

## 6. 저장 공간

**테이블·인덱스** (`mysql.innodb_index_stats`, `ANALYZE TABLE` 직후)

| 테이블 | 인덱스 | 크기 | 평균 행 길이 |
|---|---|---|---|
| `product_like` | PRIMARY (데이터) | 291.9MB | 67B |
| `product_like` | UK `(user_id, product_id)` | 239.0MB | |
| `product` | PRIMARY (데이터) | 36.6MB | 129B |
| `brand` | PRIMARY (데이터) | 0.1MB | |
| 합계 | | 567.6MB | |

`product_like`의 유니크 인덱스가 데이터에 가까운 크기인 것은 사용자 id가 무작위 순서로 들어가 페이지 분할이 많았기 때문이다.

**컨테이너 데이터 디렉터리** (`/var/lib/mysql`, 합계 1.2GB)

| 항목 | 크기 |
|---|---|
| `loopers_perf` (테이블스페이스 파일) | 589MB (`product_like.ibd` 544MB, `product.ibd` 44MB) |
| binlog | 약 260MB |
| undo | 129MB (대량 INSERT 트랜잭션의 흔적, 자동 정리 대상) |
| redo | 101MB |
| 시스템 테이블스페이스 등 | 약 70MB |

**호스트**: Docker 볼륨 `loopers-perf_mysql-perf-data` 1.2GB, C: 여유 272GB. 시드를 다시 돌리면 binlog가 약 200MB씩 늘어난다.

## 7. 버퍼 풀 적재 상태

시드·검증 뒤 각 테이블과 인덱스를 전체 스캔해 warm-up한 상태다.

| 대상 | 적재 | 버퍼 풀 비율 |
|---|---|---|
| `product_like` PRIMARY | 291.1MB | 28.4% |
| `product_like` UK `(user_id, product_id)` | 207.9MB | 20.3% |
| `product` PRIMARY | 35.9MB | 3.5% |
| `brand` PRIMARY | 0.1MB | 0.0% |
| 측정 대상 합계 | **535.0MB** | 52.2% |
| 그 밖의 페이지 (시스템·undo 등) | 151.5MB | 14.8% |
| **빈 페이지** | **330MB** | 32.3% |

- 측정 대상 테이블은 사용 중인 페이지가 **모두** 메모리에 있다. 따라서 이 환경의 지연 시간은 디스크 I/O가 아니라 **읽는 행 수·정렬·CPU 비용**을 반영한다.
- 앞으로 추가할 인덱스(상품 `(brand_id, price, id)` 후보 수십 MB, 좋아요 `product_id` 인덱스 약 100~150MB 예상)도 빈 공간 330MB 안에 들어간다.
- 버퍼 풀은 비어 있는 시간에 줄어들지 않는다. 컨테이너를 재시작하면 기본 설정(`innodb_buffer_pool_dump_pct=25`)에 따라 직전 페이지의 25%만 다시 올라온다 (재시작 직후 177MB 확인). 그래서 재시작 뒤에는 warm-up을 다시 한다.

## 8. 기준선 인덱스 상태 (개선 전)

| 테이블 | 인덱스 |
|---|---|
| `brand` | PRIMARY (`id`) |
| `product` | PRIMARY (`id`) **만 있음** — 브랜드 필터·가격 정렬용 보조 인덱스 없음 |
| `product_like` | PRIMARY (`id`), UNIQUE (`user_id`, `product_id`) — `product_id`로 시작하는 인덱스 없음 |

현재 목록 API의 좋아요 수 조회는 `product_id`로 찾지만 그 인덱스가 없어 요청마다 `product_like` 전체를 읽는다. 이것도 개선 전 측정에서 함께 기록한다.

**인덱스 비교 실험 뒤 (2026-10-16)**: 실습 DB에는 최종 인덱스 두 개를 남겨 두었다.

**좋아요 집계 실험 뒤 (2026-10-17)**: 좋아요 인덱스를 `(product_id)`에서 `(product_id, deleted_at)`으로 바꿨다(실험 기록 비교 7). 지금 남아 있는 인덱스와, 기준선으로 되돌리는 방법은 아래와 같다.

```sql
-- 남겨 둔 인덱스 (앱 엔티티에 선언한 것과 같은 이름·컬럼)
--   product      idx_product_brand_deleted_price  (brand_id, deleted_at, price, id)
--   product_like idx_product_like_product_deleted (product_id, deleted_at)
DROP INDEX idx_product_brand_deleted_price ON product;
DROP INDEX idx_product_like_product_deleted ON product_like;
ANALYZE TABLE product, product_like;
```

측정 스크립트: `docker/perf/bench.sh` (SQL 단위, `performance_schema` 서버 시간), `docker/perf/api-bench.sh` (API 단위, curl 응답 시간).

## 9. 매 측정 전 확인 절차

1. 측정용 컨테이너만 실행 중인지 확인한다: `docker ps`
2. 데이터가 같은지 확인한다: `verify.sql` [7] 체크섬이 5절 값과 같아야 한다
3. 인덱스 상태를 기록한다: `SHOW INDEX FROM product; SHOW INDEX FROM product_like;`
4. 인덱스를 바꿨다면 `ANALYZE TABLE product, product_like;`로 통계를 갱신한다
5. 컨테이너를 재시작했다면 warm-up한다 (각 테이블 전체 스캔)
6. 앱은 아래처럼 실행한다. `ddl-auto=none`이 빠지면 `local` 프로필의 `create` 때문에 테이블이 비워진다

```bash
./gradlew :apps:commerce-api:bootRun --args='--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3309/loopers_perf --spring.jpa.hibernate.ddl-auto=none'
```

7. 측정은 준비 실행(warm-up 요청)과 측정 실행을 나누고, 표본 수와 지연 분포(p50·p95 등)를 함께 남긴다

## 10. 한계

- **노트북 + Docker Desktop(WSL2)** 환경이다. 서버 하드웨어와 절대 시간이 다르고, 백그라운드 프로세스의 영향을 받는다. 반복 측정과 분포로 보완하되 절대값보다 전후 차이를 본다.
- **앱과 DB가 같은 머신**에 있어 DB 왕복 지연이 매우 작다. 요청당 SQL 수(N+1 등)의 비용은 실제 네트워크 환경보다 작게 나타난다. 그래서 SQL 수는 시간과 별도로 센다.
- **데이터가 모두 메모리에 있는 조건**이다. 디스크까지 읽는 비용(cold)은 기본 측정에 포함되지 않는다. 필요하면 컨테이너 재시작 직후의 첫 요청을 따로 기록한다.
- 순차 요청 기준이며, 동시 요청 부하 측정은 아니다.
- 실행 계획은 표본 통계(20페이지) 기반이라 `ANALYZE TABLE` 시점에 따라 예상 rows가 조금 달라질 수 있다. 판단은 `EXPLAIN ANALYZE`의 실제 rows로 한다.

## 11. 재현 절차

```bash
# 1. 컨테이너 시작 (처음이면 볼륨 생성)
docker compose -f ./docker/perf-compose.yml up -d

# 2. 테이블 생성 — 앱을 이 DB에 ddl-auto=create 로 한 번 연결 (local 프로필 기본값)
#    --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3309/loopers_perf

# 3. 시드 (약 7분)
docker exec -i loopers-perf-mysql mysql -uapplication -papplication loopers_perf < docker/perf/seed.sql

# 4. 검증 (약 40초) — 5절 체크섬과 비교
docker exec -i loopers-perf-mysql mysql -uapplication -papplication -t loopers_perf < docker/perf/verify.sql
```

DBeaver 같은 클라이언트가 MySQL 8 기본 인증을 지원하지 않으면, 볼륨을 새로 만든 뒤 아래를 한 번 실행한다.

```bash
docker exec loopers-perf-mysql mysql -uroot -proot -e "ALTER USER 'application'@'%' IDENTIFIED WITH mysql_native_password BY 'application';"
```

## 12. 캐시 측정 환경 (2026-10-18 추가)

| 항목 | 값 |
|---|---|
| Redis | `redis:latest`(8.10.2), 실습 compose의 전용 컨테이너 `loopers-perf-redis`, `127.0.0.1:6389`. 영속화 끔(`--save ""`, `--appendonly no`) — 재시작하면 빈 캐시 |
| 연결 | master·replica 설정 모두 같은 전용 Redis를 가리킨다 |
| 대기 상한 | 연결 수립 1초, 명령 300ms, 연결이 끊긴 동안 명령은 바로 거절 |
| revision 테이블 | `product_list_revision`(`(brand_id, slot)` 기본 키, 정렬별 revision 3개), `product_revision`(`product_id` 기본 키). 측정 실행은 `ddl-auto=none`이라 `seed.sql`이 엔티티와 같은 정의로 만든다 |
| Redis 왕복 | 같은 PC에서 `PING` p50 2.25ms, p95 3.00ms (200회) |

앱 실행 인자 (`replicas`는 목록이라 명령줄에서 일부만 주면 목록 전체가 바뀐다 — host와 port를 함께 준다):

```bash
./gradlew :apps:commerce-api:bootRun --args='--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3309/loopers_perf --spring.jpa.hibernate.ddl-auto=none --datasource.redis.master.port=6389 --datasource.redis.replicas[0].host=localhost --datasource.redis.replicas[0].port=6389'
```

**캐시 측정 전 추가 확인**

- 시드를 다시 넣으면 revision이 0으로 돌아가므로, 같은 revision의 옛 캐시가 남지 않게 실습 Redis도 비운다: `docker exec loopers-perf-redis redis-cli FLUSHALL` (실습 전용 Redis에만)
- Redis를 재시작한 직후에는 재연결이 끝나기 전의 요청이 DB로 우회한다. hit 측정은 hit의 SQL 수가 3개로 돌아온 것을 확인한 뒤 시작한다.
- 쓰기 부하(좋아요 동시 요청) 측정은 시드 사용자와 겹치지 않는 사용자 id(100000 초과)를 쓰고, 끝나면 그 좋아요를 지우고 revision 테이블과 Redis를 비운 뒤 체크섬이 5절 값과 같은지 확인한다.
- 동시 요청 측정에는 셸의 `xargs -P`를 쓰지 않는다. 이 PC에서는 요청마다 프로세스를 띄우는 비용 때문에 요청이 사실상 하나씩 나갔다(초당 8건). 스레드로 동시에 보내는 부하 도구를 쓴다.
