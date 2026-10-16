# 성능 측정 데이터 재현

상품·재고 각 100만 개, 브랜드 1,000개, 사용자 24만 명, 좋아요 69만 개를 고정 seed 42로 생성한다. CSV와 적재 결과는 로컬에만 보관하고 생성·검증·적재 도구와 설정·체크섬은 제출한다.

Java 21·프로젝트 Gradle wrapper·Python 3.11 이상·MySQL 8 클라이언트·Docker가 필요하다. 명령은 저장소 루트에서 실행한다.

## 생성·검증

출력 폴더에 기존 CSV가 있으면 덮어쓰지 말고 별도 빈 폴더를 지정한다.

```bash
./gradlew -I docs/week4/seed-data/scripts/seed.gradle :apps:commerce-api:generateCatalogCsv \
  -PdatasetDir=build/catalog-seed-1m -PseedValue=42 -PdatasetProfile=skewed-1m
python3 docs/week4/seed-data/scripts/validate.py build/catalog-seed-1m --write-manifest
```

[생성 설정](skewed-1m-v1/generation.json)과 [기준 manifest](skewed-1m-v1/manifest.json)에 생성 조건·분포·CSV 체크섬이 있다. 생성한 manifest의 `files` 항목과 기준 manifest를 비교하면 동일한 CSV인지 확인할 수 있다.

브랜드별 상품은 333~5,000개로 배분한다. 상품 79%는 좋아요가 없고 상위 10% 상품에 좋아요 84%가 집중된다. 좋아요 분포는 Taobao의 9일간 즐겨찾기 행동 통계를 참고한 합성 분포이며 브랜드 배분은 실험용 가정이다. 가격·재고 수량은 균등 난수로 생성한다.

## 격리 DB 준비·적재

```bash
docker compose -f docs/week4/seed-data/compose.yml up -d --wait
docker compose -f docs/week4/seed-data/compose.yml exec -e MYSQL_PWD=root mysql mysql -uroot -e \
  "CREATE DATABASE perf_skewed_1m_v1 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL ON perf_skewed_1m_v1.* TO 'application'@'%';"
PERF_DB_NAME=perf_skewed_1m_v1 ./gradlew -I docs/week4/seed-data/scripts/seed.gradle :apps:commerce-api:initCatalogSchema
docs/week4/seed-data/scripts/load.sh build/catalog-seed-1m perf_skewed_1m_v1
```

이미 DB가 있으면 생성 명령을 반복하지 않는다. 스키마 초기화 도구는 테이블이 있는 DB를 거절하고, 적재 도구는 대상 테이블에 데이터가 있으면 중단한다. 공유 DB에 적용하지 않는다.

브랜드·사용자 → 상품 → 재고·좋아요 순서로 적재하며, 경고·건수·행 체크섬을 검사한다. 연결은 `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PWD`로 지정하고 기본 포트는 13307이다. 이후 앱은 `PERF_DB_NAME=perf_skewed_1m_v1`, `local,perf` 프로필과 `ddl-auto=none`으로 실행한다.

현재 엔티티로 초기화하면 개선된 인덱스가 포함된다. 과거 인덱스 전후 측정을 그대로 재실행하는 명령은 아니며, 제출된 분포·데이터를 재현하는 절차다.
