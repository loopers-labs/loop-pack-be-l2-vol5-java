> 갱신: 좋아요 수를 `product_like_count`로 비정규화하는 결정을 채택했다([ADR 0020](../adr/0020-like-count-denormalized-counter.md)). 실제 카운터 코드·3개 benchmark DB 초기화·전체 검사·실제 API 재측정까지 완료했다. 최신 변경·검증 순서는 [구현 기록 16절](implementation-log.md#counter-production)을 참고한다. 아래 이전 단계의 미적용 서술은 당시 기록이다.

> 현재 benchmark DB는 기존 상품·좋아요 인덱스와 product_like_count를 사용한다. 신규 DB seed 이후 카운터 backfill이 필요하다. 실험 후보 인덱스의 상태는 각 실험 절을 참고하며 현재 운영 구성과 구분한다.

# 4주차 환경 준비와 실행

커밋된 측정 근거는 [measurements 보관 목록](measurements/README.md)에서 찾는다. 이전 개별 파일명은 인덱스·카운터 묶음의 `results` 키로 보관한다. 아래 재측정 명령은 기존처럼 개별 로컬 JSON·로그를 생성하며, 출력 파일은 기본적으로 Git에서 제외된다.

현재 benchmark DB에는 기존 인덱스를 유지한 채로 가격 정렬 인덱스(idx_product_brand_price)와 좋아요 product_id 인덱스(idx_product_like_product)가 이미 적용되어 있다. 뒤의 인덱스 적용 절은 적용 방법과 이전 기록이며, 새로 합의한 단계에서만 실행한다. 앱 코드에는 앞서 구현한 정렬·페이지가 남아 있으므로 SQL 재검토와 실제 API를 구분한다.

명령은 저장소 루트에서 실행한다. Docker Desktop과 프로젝트에서 사용하는 Java 21이 필요하다. seed 실행에는 Python 3도 사용한다.

## 처음 실행할 때 따라가기

현재 MySQL·Redis 연결과 상품 seed, 가격순 첫 페이지 캐시가 준비되어 있다. 아래 순서로 API에서 상품을 확인할 수 있다. 캐시 범위와 최신 검증 결과는 [현재 실행 기록](implementation-log.md#price-cache-without-count)을 따른다.

### 1. Docker Desktop 켜기

Docker Desktop이 실행 중인지 확인한다. 아래 명령을 입력할 터미널은 IDE에서 프로젝트 폴더를 연 뒤 사용한다. 현재 폴더에 `gradlew`와 `docker` 폴더가 있어야 한다.

### 2. MySQL과 Redis 켜기

```bash
docker compose -f docker/infra-compose.yml --profile benchmark up -d mysql-benchmark redis-master redis-readonly
```

이미 실행 중이어도 같은 명령을 사용해도 된다. 컨테이너를 켜는 작업이지 앱을 실행하는 명령은 아니다.

다음 명령으로 Redis가 응답하는지 확인한다.

```bash
docker exec redis-master redis-cli PING
```

`PONG`이 나오면 Redis가 실행 중이다. 이 결과만으로 앱의 캐시 기능이 동작한다는 뜻은 아니다.

### 3. 앱 실행하기

현재 측정 DB는 테이블 준비가 끝났으므로, 아래 명령을 실행한다.

```bash
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=benchmark'
```

로그에 `Started CommerceApiApplication`이 나오면 시작이 완료된 것이다. 앱을 사용하는 동안 이 터미널을 켜둔다. Gradle 작업이 계속 실행 중으로 보이는 것은 정상이다.

Java 버전 오류가 나면 `java -version`으로 Java 21인지 확인한다. 이 컴퓨터에 설치된 Homebrew Java 21을 명시해서 실행할 수도 있다.

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=benchmark'
```

### 4. API 호출해보기

브라우저에서 [Swagger](http://localhost:8082/swagger-ui.html)를 연다. 상품 목록 API에서 `Try it out`을 누르고 `brandId=7`, `page=0`, `size=20`, `sort=price_asc`를 입력한 뒤 `Execute`를 누른다. seed 그대로라면 가격순 상품 20개와 그 상품들의 좋아요 수, page·size가 나온다.

새 터미널에서 직접 호출할 수도 있다.

```bash
curl 'http://localhost:8082/api/v1/products?brandId=7&page=0&size=20&sort=price_asc'
```

목록의 `isLiked`는 헤더가 없으면 `false`다. seed 사용자 95의 하트를 확인하려면 다음처럼 호출한다. 공통 캐시를 읽어도 내 좋아요 여부는 페이지 상품 ID로 DB에서 다시 조회한다([정책](../adr/0023-product-list-personal-like-state.md)).

```bash
curl -H 'X-USER-ID: 95' \
  'http://localhost:8082/api/v1/products?brandId=7&page=0&size=20&sort=price_asc'
```

앱 연결 상태를 확인하려면 **새 터미널**에서 다음 명령을 실행한다.

```bash
curl http://localhost:8083/actuator/health
```

응답의 `status`가 `UP`이면 연결 상태가 정상이다. 이 호출은 상품 조회 성능을 측정하는 명령은 아니다.

### 5. 종료하기

앱을 실행한 터미널에서 `Ctrl+C`를 누른다. 앱을 종료해도 MySQL·Redis 컨테이너와 저장한 데이터는 유지된다. 나중에 앱을 다시 사용하려면 3번 명령을 실행한다.

## 상품 seed 넣기·확인하기

현재 측정용 DB에는 이미 데이터를 넣었다. 다시 넣을 필요 없이 앱을 실행해 조회하면 된다.

새 빈 측정 DB에 넣을 때는 테이블 준비 후 아래 명령을 실행한다. 스크립트는 Compose의 `mysql-benchmark`와 `loopers_benchmark`에만 연결한다. 500행씩 묶어 한 트랜잭션으로 넣으며, 대상 네 테이블에 데이터가 있으면 변경 없이 중단한다. 동시에 여러 seed 실행기를 실행하지 않는다.

```bash
python3 scripts/seed-week4.py
```

기존 seed를 삽입 없이 확인하려면:

```bash
python3 scripts/seed-week4.py --verify-only
```

검증 성공 시 다음 건수와 브랜드 7의 가격순 첫 페이지가 출력된다.

| 데이터              | 예상 건수 |
| ------------------- | --------: |
| 상품 전체           |    10,000 |
| 고객 노출 가능 상품 |     9,699 |
| 브랜드 전체 / 활성  |  100 / 98 |
| 사용자              |       200 |
| 좋아요 관계         |    62,756 |

seed는 고정된 ID·가격·시간·좋아요 수로 생성한다. 관리자 변경 등으로 데이터를 바꾼 뒤에는 원래 seed 검증값과 다를 수 있다. 스크립트가 기존 데이터를 자동 삭제하지 않으며, 재생성은 측정 DB의 초기화 범위를 먼저 확인한 뒤 진행한다. 인덱스 추가와 ANALYZE TABLE은 seed 명령에 포함하지 않았다.

## 조회·캐시 실험 순서

seed와 기존 조회 기준선은 확인했다. `commerce.cache.price.enabled`로 캐시를 켜고 끄며, `commerce.cache.price.provider=local|redis`로 저장소를 고른다. [현재 비교 조건](price-cache-comparison.md)을 참고한다. 아래 초기 인덱스 측정 원본은 각 실행 시점의 기록이다.

### 기존 조회 측정 명령

benchmark 앱을 실행한 뒤 새 터미널에서 실행한다.

```bash
python3 scripts/measure-week4-query.py --capture-sql --output docs/week4/measurements/baseline-repeat.json
```

고정 요청을 준비 10회·측정 30회 순차 실행하고, 응답이 seed 기대값과 같은지 확인한다. 이후 별도 API 한 요청의 실제 SQL과 EXPLAIN·EXPLAIN ANALYZE를 기록한다. 측정용 MySQL 로그를 잠시 켜고 기존 설정으로 복원하므로 측정 중 다른 요청이나 DB 작업을 함께 실행하지 않는다. Docker와 HTTP 연결이 필요하다.

결과 JSON에는 응답·체크섬·개별 시간·SQL·실행 계획이 들어간다. 이미 있는 파일을 덮어쓰지 않으므로 재측정 시 새 파일명을 선택한다. 기본 SQL 출력은 끈 상태로 측정한다. 시간만 재려면 `--capture-sql`을 생략할 수 있다.

이는 기존 첫 페이지 한 요청의 기준선이고, 변경·실패·복구 테스트와 동시 부하 검증은 이후 진행한다.

최초 구현의 기록은 `measurements/baseline.json`이다. 현재 코드는 브랜드 조건과 가격순 정렬·페이지 선택을 SQL로 옮긴 단계다. latest·likes_desc 페이지 처리는 Java에서 한다. 페이지 이동 결과는 `measurements/price-page.json`이다. 정렬 이동 결과는 `measurements/price-sort.json`이다. 이 단계의 결과는 `measurements/brand-filter.json`이다. 앞서 여러 변경을 함께 적용한 `measurements/price-sql.json`은 과거 시험 기록이고 현재 코드의 결과는 아니다. 재측정은 새 파일에 저장해 비교한다.

| 순서 | 할 일                                   | 확인할 결과                                      |
| ---- | --------------------------------------- | ------------------------------------------------ |
| 1    | 측정용 DB에 상품 seed 넣기              | 상품·브랜드·좋아요 건수와 대표 조회 결과         |
| 2    | 캐시 없이 상품 조회                     | 응답, SQL 수, 실행 계획, 반복 조회 시간          |
| 3    | 캐시를 켜고 저장되지 않은 조건으로 조회 | cache miss: MySQL에서 조회하고 Redis에 결과 저장 |
| 4    | 같은 조건으로 다시 조회                 | cache hit: 저장된 결과 재사용, 비싼 DB 조회 감소 |
| 5    | 관리자 변경 후 다시 조회                | 변경 내용 반영과 기존 조회 계약 유지             |
| 6    | Redis 장애와 복구 확인                  | 제한된 대기 뒤 DB 우회, 복구 뒤 올바른 재채움    |

캐시를 사용하지 않는 비교는 앱이 의도적으로 MySQL만 읽게 하는 것이다. Redis 장애 실험은 캐시를 사용하려는 앱에서 Redis 연결이 실패하는 상황을 확인하는 것이다. 두 실험은 따로 진행한다.

## 스킬과 이 문서의 역할

`$week4-redis`는 Codex가 Redis 작업을 할 때 읽는 작업 안내서다. 터미널 명령이 아니며 앱 실행 전에 호출할 필요는 없다. Codex에게 “`$week4-redis`로 상태 확인해줘”처럼 요청할 수 있다.

직접 실행할 때는 이 문서의 터미널 명령을 사용한다. 스킬을 설치했다고 상품 seed나 캐시가 자동으로 구현되는 것은 아니다.

## MySQL·Redis 실행

```bash
docker compose -f docker/infra-compose.yml --profile benchmark up -d mysql-benchmark redis-master redis-readonly
```

세 서비스는 `restart: unless-stopped` 설정으로 Docker Desktop 재시작 시 자동으로 시작한다. 명시적으로 중단했거나 컨테이너를 제거했다면 위 명령으로 다시 실행한다. 측정용 MySQL은 별도 `mysql-benchmark-data` 볼륨에 데이터를 보존한다. 볼륨을 삭제하면 데이터도 삭제된다.

## DB 확장 프로그램 연결

| 항목           | 값                |
| -------------- | ----------------- |
| 연결 이름 예시 | Week4 Benchmark   |
| Host           | localhost         |
| Port           | 3307              |
| Database       | loopers_benchmark |
| User           | application       |
| Password       | application       |

Redis 주소는 master `localhost:6379`, read-only replica `localhost:6380`이다.

## 최초 스키마 준비

현재 로컬 측정 DB는 테이블 준비가 끝났다. 새 볼륨으로 빈 DB를 만들었을 때만 다음 명령을 실행하고, 앱 시작이 완료되면 Ctrl+C로 종료한다.

```bash
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=benchmark --spring.jpa.hibernate.ddl-auto=update'
```

이 명령은 엔티티에 맞춰 스키마를 준비한다. 새 빈 DB라면 앱 종료 후 위 seed 명령으로 데이터를 넣는다.

## 일반 측정 실행

```bash
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=benchmark'
```

`local`과 함께 활성화하지 않고 `benchmark`만 선택한다. 기본 DDL 설정은 validate라 스키마를 검사하고 데이터를 유지한다. 측정 중 인덱스 변경은 명시적인 SQL로 적용하고 전후 상태를 기록한다.

| 접근 대상 | 주소                                      |
| --------- | ----------------------------------------- |
| 상품 API  | http://localhost:8082                     |
| Swagger   | http://localhost:8082/swagger-ui.html     |
| Health    | http://localhost:8083/actuator/health     |
| Metrics   | http://localhost:8083/actuator/prometheus |

benchmark 프로필은 콘솔 로그를 사용하고 SQL 출력은 기본적으로 끈다. 앱 종료는 Ctrl+C로 한다.

## 상태 확인

```bash
docker compose -f docker/infra-compose.yml --profile benchmark ps mysql-benchmark redis-master redis-readonly
docker exec redis-master redis-cli PING
docker exec redis-readonly redis-cli PING
curl http://localhost:8083/actuator/health
```

컨테이너는 healthy, Redis는 PONG, 앱 실행 중 health는 UP인지 확인한다.

## offset·커서 SQL 비교

앱 실행 없이 측정용 MySQL과 기존 seed만 준비되면 실행할 수 있다. 데이터·인덱스를 바꾸지 않고 실제 상품 컬럼 조회를 비교한다. 기존 파일을 덮어쓰지 않으므로 새 결과 경로를 사용한다.

```bash
python3 scripts/compare-week4-pagination.py --output docs/week4/measurements/pagination-repeat.json
```

첫 구간과 1,000·8,000 offset에 대응하는 커서를 비교한다. 방식별 준비 10회·측정 30회이며 모든 결과를 seed 기대값과 비교한다. SQL 시간·실행 계획만의 측정이고 HTTP API나 커서 API 구현이 아니다.

## 좋아요 인덱스 읽기·쓰기 비교

실제 product_like에 후보 인덱스를 추가하기 전, benchmark MySQL과 seed만 준비해 실행한다. 앱 실행은 필요 없다.

```bash
python3 scripts/compare-week4-like-index.py --output docs/week4/measurements/like-index-repeat.json
```

두 복제 테이블을 만들어 한쪽에만 product_id 인덱스를 추가하고 집계·등록·취소를 비교한다. 기존 결과 파일이나 같은 이름의 실험 테이블이 있으면 중단한다. 원본 product_like는 변경하지 않으며 실행기가 생성한 테이블만 finally에서 정리한다. 강제 종료 등으로 정리가 실패했다면 남은 테이블의 소유·용도를 확인한 뒤 수동 정리해야 한다. 시간은 SQL 단위이며 HTTP 전체 성능은 아니다.

## 실제 좋아요 인덱스 적용 상태

측정용 DB(`localhost:3307`, `loopers_benchmark`)에는 `scripts/sql/week4-like-product-index.sql`을 적용했다. 기존 DB에 재적용하면 같은 이름의 인덱스가 있어 오류가 나므로 반복 실행하지 않는다. 새로 준비한 DB에만 인덱스 존재 여부를 확인한 뒤 적용한다. JPA의 validate는 기존 DB에 인덱스를 생성하지 않는다.

MySQL 확장에서 같은 DB에 연결해 다음으로 확인할 수 있다.

```sql
SELECT DATABASE();
SHOW INDEX FROM product_like;
```

기존 PRIMARY·uk_product_like_user_product와 새 idx_product_like_product가 함께 보여야 한다. 이후 기존 EXPLAIN ANALYZE 집계 SQL을 다시 실행한다. 위 복제 비교 실행기는 후보 적용 전 상태를 요구하므로 현재 실제 테이블에서는 의도적으로 중단한다.

## 페이지 선행 JOIN 후보 비교

product_id 인덱스를 적용한 benchmark DB와 seed에서 앱 없이 실행할 수 있다.

```bash
python3 scripts/compare-week4-like-join.py
```

상품 페이지+별도 좋아요 집계와 페이지 선행 LEFT JOIN을 비교한다. 결과 파일은 measurements/like-join.json이며 이미 있으면 덮어쓰지 않고 중단한다. 재실행하려면 출력 경로를 바꾸어 기존 기록을 보존한다. HTTP JOIN API 비교는 아니다.

## 상품 가격 정렬 인덱스

측정용 DB에 `scripts/sql/week4-product-price-index.sql`을 적용했다. 새 DB에만 기존 인덱스 여부를 확인하고 실행하며 이미 적용된 DB에 중복 실행하지 않는다. MySQL 확장에서 기존 상품 페이지 SQL을 EXPLAIN ANALYZE로 실행하면 `idx_product_brand_price` 사용 여부·실제 rows·Sort 유무를 확인할 수 있다. 기존 브랜드 인덱스는 유지한다.

## 첫 변경 재검토: 브랜드 필터

```bash
python3 scripts/measure-week4-brand-filter.py --output docs/week4/measurements/brand-filter-handler-repeat.json
```

전체 활성 상품과 브랜드 7 후보 조회만 비교한다. 새 가격 인덱스는 쿼리의 IGNORE INDEX로 제외한다. 실행 SQL은 [week4-01-brand-filter.sql](../../scripts/sql/week4-01-brand-filter.sql)에 있다. Handler 직전·직후 조회는 같은 연결에서 실행해야 하며, 확장이 실행마다 다른 연결을 쓰면 해당 비교는 유효하지 않다. 실행기는 한 클라이언트 연결 안에서 전후를 조회한다. 기존 결과 파일은 덮어쓰지 않는다.

## 현재 재검토 DB 상태

사용자 요청으로 추가했던 상품 가격 인덱스와 좋아요 product_id 인덱스를 측정용 DB에서 삭제했다. 앞의 적용 상태 설명은 당시 기록이며 현재는 원래 인덱스 상태다. 추가 DDL은 순서대로 다시 검토할 때만 실행한다. 재검토 중에는 benchmark의 ddl-auto: validate를 유지한다. 모델 선언은 보존되어 있어 update/create 실행 시 새 인덱스가 다시 생성될 수 있다.

week4-01-brand-filter.sql은 새 인덱스 삭제 후 실행하는 SQL로 맞췄다. 실행기는 가격 인덱스가 존재하면 제외하고, 없으면 힌트 없이 실행한다.

## 고정 상품 페이지 쿼리 확인

```bash
python3 scripts/measure-week4-fixed-product-page.py --output docs/week4/measurements/fixed-page-repeat.json
```

동일한 가격순 20개 쿼리의 계획·Handler·시간·seed 결과를 확인한다. [직접 실행 SQL](../../scripts/sql/week4-03-fixed-product-page.sql). 출력 파일은 덮어쓰지 않는다. 현재 인덱스 목록을 저장하므로 추가 전후는 별도 결과 파일로 남긴다.

## 조건·페이지 실험 따라가기

09는 가격 우선, 10은 브랜드 IN, 11은 삭제 조건, 12는 OFFSET·커서 SQL이다. SQL 파일의 주석과 실행 순서를 확인하고 한 블록씩 실행한다. 11은 복제 테이블 생성·복제 데이터 변경을 포함하며 실제 product에는 쓰지 않는다. 동일 복제 이름이 이미 있으면 임의로 삭제하지 말고 용도를 확인한다. 직접 실험이 끝나면 자신이 만든 복제만 DROP한다.

자동으로 ②~④를 재현하려면 scripts/compare-week4-index-conditions.py를 실행한다. 이미 measurements/index-conditions.json이 있으면 덮어쓰지 않고 중단하므로 재실행할 때는 출력 경로를 변경해 기존 기록을 보존한다. 이 실행기는 복제 테이블을 생성하고 종료 시 정리한다. Handler는 자동 COUNT 없는 클라이언트로 수집한다. 확장에서는 EXPLAIN ANALYZE의 실제 읽기·정렬을 먼저 확인한다.

## JOIN 실제 API 실험

이 실험은 기존 benchmark seed·최종 상품/좋아요 인덱스가 필요하다. 운영 소스 대신 init script로 실험 테스트 소스를 opt-in한다. 별도 상용 API나 설정을 추가하지 않는다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
DOCKER_HOST=unix:///Users/johyeon-a/.docker/run/docker.sock \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
./gradlew -I scripts/experiments/join-api.init.gradle \
  :apps:commerce-api:test --tests '*ProductJoinApiExperimentTest' \
  :apps:commerce-api:checkstyleTest -Pversion=week4-join-final
```

이미 measurements/join-api-final.json이 있으면 의도적으로 중단한다. 재실행은 테스트의 출력 경로를 새 파일로 바꿔 기존 원본을 보존한다. 테스트는 실제 상품 목록 HTTP를 동일 서버에서 전환해 비교하며 benchmark DB에는 SELECT만 보낸다. SQL 수집 동안 general log 설정을 잠깐 변경하고 finally에서 복원한다. 일반 check에는 실험 소스가 포함되지 않는다. 비용 비교용 코드를 운영 구현으로 오인하지 않는다.

## Handler 결과 확인

[Handler 검증 결과서](handler-results.md)는 현재 01~14 SQL의 실제 차분을 모은 문서다. 기존 원본을 재사용하고 누락 경로는 scripts/measure-week4-handler-supplement.py로 보완했다. 실행기는 자동 COUNT 없는 한 연결에서 측정하고 실제 업무 SQL을 읽기 전용으로 재실행한다. 이미 measurements/handler-supplement.json이 있으면 덮어쓰지 않는다. 재실행하려면 출력 경로를 새 파일로 바꿔 기존 원본을 보존한다.


## 10만 건 핵심 비교·쓰기·최종 API 재현

이미 준비된 `loopers_benchmark_100k`(상품 100,000·좋아요 628,186)와 `loopers_benchmark_100k_hotspot`(좋아요 678,186)을 사용한다. seed를 다시 넣지 않는다. 15~17의 결과는 implementation-log 12절, 통제 비교는 13절을 참고한다. 한 블록씩 직접 계획을 확인하려면 [18 SQL](../../scripts/sql/week4-18-100k-before-after.sql)을 사용한다. 18은 읽기 경로에서 IGNORE INDEX를 사용한 보조 확인이고, 자동 측정 원본은 실제 인덱스 구성이 다른 복제끼리 비교한 결과다.

```bash
python3 scripts/compare-week4-100k.py \
  --output docs/week4/measurements/100k-read-write-skew-rerun.json
```

실험 전용 `w4_100k_*` 테이블이 이미 있거나 출력 파일이 있으면 중단한다. 자신이 생성한 네 복제 테이블만 finally에서 제거한다. 원본은 조회만 하며 읽기 결과와 쓰기 후 복구를 검증한다. 쓰기 결과는 서버 SQL 트랜잭션 시간이며 실제 관리자·좋아요 API 전체 시간은 아니다. 다른 benchmark 요청과 병행하지 않는다.

실제 API는 Java 21로 현재 코드를 빌드한 뒤 실행한다. `--jar`에는 이번 빌드가 만든 실제 파일명을 넣는다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew :apps:commerce-api:bootJar

# 이번 실행의 파일명 예시. 재현 시 build/libs의 실제 이름 확인.
python3 scripts/measure-week4-100k-api.py \
  --jar apps/commerce-api/build/libs/commerce-api-a083b48.jar \
  --output docs/week4/measurements/100k-final-api-rerun.json
```

18082/18083 포트가 비어 있어야 한다. 실행기는 DB별 독립 JVM을 시작하고 `validate`를 유지하며 부족한 여섯 테이블의 빈 스키마만 임시 복제한다. 종료 후 비어 있는 자신 소유 테이블을 제거하고 데이터·인덱스 정의 보존을 확인한다. 직접 띄운 JVM만 종료하며 앱 설정 파일은 바꾸지 않는다. SQL 캡처 동안 general log를 잠깐 켠 뒤 기존 설정으로 복원한다. 결과 JSON에는 응답·SQL·실행 계획이, 같은 디렉터리의 DB명-api.log에는 시작/종료 로그가 남는다. 기존 JSON은 덮어쓰지 않으며 로그는 재실행 시 교체된다.


## 카운터 후보 부하 실험과 직접 SQL 확인

현재 일반·핫스팟 10만 건 seed가 필요하다. 원본에는 쓰지 않고 DB마다 `w4_counter_control`, `w4_counter_relation`, `w4_counter_value`만 생성·정리한다. 이 이름이나 결과 파일이 이미 있으면 중단한다. 다른 측정과 병행하지 않는다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
./gradlew -I scripts/experiments/join-api.init.gradle \
  :apps:commerce-api:test --tests '*LikeCounterExperimentTest' \
  :apps:commerce-api:checkstyleTest
```

기존 init script는 실험 소스셋 등록에 재사용한다. JOIN 테스트를 실행하는 명령이 아니다. 결과 `measurements/100k-counter-experiment.json`이 있으면 기존 증거를 보존하도록 중단한다. 재실행하려면 실험 클래스의 출력 파일명을 새 이름으로 지정한다. 일반 `check`에는 실험 소스를 포함하지 않는다. p95는 JDBC 작업 시간으로, 13절의 실제 HTTP p95와 구분한다.

[19 SQL](../../scripts/sql/week4-19-like-counter.sql)은 같은 연결에서 세션 임시 카운터를 만들고 집계·카운터 계획과 초기 값 일치를 확인하는 학습용이다. 원본에 쓰지 않으며 마지막에 임시 테이블을 제거한다. 동시성·쓰기 성능은 위 Java 실험으로 확인한다. SQL 01~18의 파일 상단에 대상 DB·기록 절을 적었고 각 쿼리 위에 확인 목적을 한 줄씩 두었다. 자세한 실행 주의점과 이전 메모는 [구현 기록 14절](implementation-log.md#sql-reading-guide)에 보존했다.

## 카운터 비정규화 적용 후 DB 준비

[ADR 0020](../adr/0020-like-count-denormalized-counter.md) 채택 이후 `ProductModel`·`LikeModel`에 `product_like_count` 테이블을 기대하는 코드가 들어갔다. `benchmark` 프로필은 `ddl-auto: validate`라 이 테이블이 없는 기존 DB(`loopers_benchmark`, `loopers_benchmark_100k`, `loopers_benchmark_100k_hotspot`)에서는 앱이 기동하지 않는다. 기존 seed 스크립트(`seed-week4.py`, `seed-week4-100k.py`, `seed-week4-100k-hotspot.py`)는 카운터를 직접 채우지 않으므로, seed 이후 한 번 [20 SQL](../../scripts/sql/week4-20-like-counter-backfill.sql)로 백필해야 한다.

```bash
docker compose -f docker/infra-compose.yml --profile benchmark exec -T mysql-benchmark \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root -D <대상 DB>' \
  < scripts/sql/week4-20-like-counter-backfill.sql
```

이 SQL은 쓰기를 멈춘 상태에서의 offline 전환만 지원한다(`LOCK TABLES`로 원본을 READ 잠그고 카운터만 WRITE). 실행 중인 쓰기 앱과 동시에 적용하지 않는다. 끝에 누락·불일치·고아 카운터 수를 검증하는 SELECT가 있고, 둘 다 0이어야 한다. 재실행해도 `ON DUPLICATE KEY UPDATE`로 중복 증가하지 않는다. 세 로컬 benchmark DB는 이미 이 절차로 전환을 완료했다([검증 원본 · counter-backfill.json](measurements/like-counter-results.json)).

새 benchmark DB를 처음부터 준비한다면 순서는: ① `CREATE DATABASE` ② 스키마 적용(`ddl-auto: update`로 한 번 기동하거나 기존 DB의 `SHOW CREATE TABLE`을 복제) ③ seed 스크립트 ④ 20번 SQL로 카운터 백필 ⑤ `ddl-auto: validate`로 전환.


## 가격순 기본 캐시 실행·DB-only 비교

현재 구현은 ADR 0021의 GET → miss면 DB/SET+TTL → 변경 commit 후 DEL이다. 먼저 [캐시 기본 흐름](cache-basics.md)을 읽는다. 브랜드 필터·price_asc·page=0·size=20에 적용하며 metadata 초기화는 필요 없다.

기존 상품 seed·인덱스와 좋아요 카운터 backfill은 앞 절의 절차를 유지한다. 새 jar 빌드 후 실제 파일 이름을 확인하고 실행한다.

```bash
DOCKER_CONTEXT=desktop-linux python3 scripts/measure-week4-price-cache.py \
  --jar apps/commerce-api/build/libs/commerce-api-a083b48.jar \
  --output docs/week4/measurements/100k-price-cache-basic-api-rerun.json
```

실행기는 기존 JSON 덮어쓰기를 거부하며 별도 UUID Redis prefix와 18082/18083 포트를 사용한다. 원본을 보존하고 임시 빈 테이블·직접 시작한 JVM·자신의 캐시 키만 정리한다. benchmark 서비스는 desktop-linux, Testcontainers는 기존 Colima TCP 터널을 사용한다. Docker 환경 문제 때만 scripts/fix-docker-testcontainers.sh를 사용한다.

`--commerce.cache.price.enabled=false`는 캐시 우회다. local/benchmark의 기본은 true, 그 외 false다. `commerce.cache.price.prefix`와 `commerce.cache.price.ttl-seconds`로 prefix와 TTL을 설정한다(local 기본 commerce:price-page:v3:local, benchmark 기본 commerce:price-page:v3:benchmark·300초). 키는 `prefix:brand:{id}:sort:price_asc:page:0:size:20`이다. JDBC URL을 다른 DB로 변경할 때에는 prefix도 해당 DB 전용으로 지정한다. 측정 실행기는 UUID prefix로 분리한다. 현재 비활성화는 읽기/저장과 영향 쓰기의 after-commit DEL을 함께 끈다. 같은 DB를 공유하는 캐시 사용 앱과 비사용 쓰기 앱의 혼합 배포 용도로 사용하지 않는다. 직접 SQL 변경은 자동 DEL되지 않으므로 쓰기 앱을 중지하고 변경한 뒤 자신의 관련 키만 지우거나 만료를 확인한다.

캐시 통합 테스트는 `:apps:commerce-api:test --tests '*PricePageCacheIntegrationTest'`로 실행한다. 실제 MySQL과 전용 Redis Testcontainer를 사용하고 장애 테스트는 그 Redis만 stop/start한다. 공유 Redis나 다른 컨테이너를 중단하거나 FLUSHALL하지 않는다. 실패/늦은 저장의 회복 테스트에서는 자신의 키 TTL을 100ms로 단축해 실제 만료를 관찰한다. 운영 기본 300초를 실제 기다린 테스트는 아니다. 재시작 뒤 재채움 테스트는 자신의 키를 명시적으로 DEL하며 자동 복구 보장으로 설명하지 않는다.

[현재 정책과 비용](../adr/0021-price-page-cache-aside-with-after-commit-eviction.md), [현재 실행 기록](implementation-log.md#price-cache-without-count).


## 인기순의 60초 Redis 순위 확인

현재 local/benchmark는 `popular` 캐시가 켜져 있다. 브랜드별 첫 20개 ID를 저장하고, 좋아요 변경으로 지우지 않는다. 표시 숫자·내 하트·상품 정보는 현재 DB에서 가져온다. 기존 `likes_desc`는 DB에서 현재 좋아요순으로 조회한다. [설계와 판단](design.md#popular-cache-choice), [ADR 0025](../adr/0025-popular-page-snapshot-with-ttl.md)를 먼저 읽는다.

```bash
curl -H 'X-USER-ID: 95' \
  'http://localhost:8082/api/v1/products?brandId=7&page=0&size=20&sort=popular'
```

popular는 브랜드 필수·page=0·size=20만 지원하며 다른 조건은 400이다. 상품 생성/삭제·브랜드 삭제는 commit 후 해당 브랜드 키를 지운다. TTL은 저장부터 60초이고 hit로 연장하지 않는다. 만료 뒤 다음 요청이 재계산하며 정시 배치가 아니다. 표시 숫자와 순서가 잠시 어긋날 수 있다. 삭제 대상과 다른 사용자 정보는 노출하지 않는다.

인기순 캐시만 끄려면 `--commerce.cache.popular.enabled=false`를 사용한다. 가격순 설정과 별개다. 같은 프로필로 다른 DB를 실험할 때에는 `--commerce.cache.popular.prefix=전용값`을 명시해 키를 분리한다. 사용/비사용 쓰기 앱을 같은 DB에 섞는 설정으로 사용하지 않는다.

실제 10만 건 비교는 전체 검사와 현재 JAR 빌드 뒤 전용 복제 DB에서 재현한다. 새 출력 경로를 사용한다. 이 스크립트는 기본 60초 만료를 한 번 실제 관찰하므로 그만큼의 시간이 추가된다. [현재 실행 기록](implementation-log.md#popular-cache-snapshot)에 조건과 한계를 둔다.

```bash
python3 scripts/compare-week4-popular-cache.py \
  --jar apps/commerce-api/build/libs/commerce-api-a083b48.jar \
  --output docs/week4/measurements/100k-popular-cache-repeat.json
```

통합 테스트는 `:apps:commerce-api:test --tests '*PopularPageCacheIntegrationTest'`다. 실제 MySQL과 전용 Redis를 쓰고 실패/늦은 채움의 만료 테스트는 자신의 키를 100ms로 단축한다. 기본 60초 관찰은 위 API 비교의 별도 단계다. Redis stop/start 후에는 자신의 키를 명시적으로 DEL해 재채움을 확인하며 자동 최신 복구를 주장하지 않는다.
