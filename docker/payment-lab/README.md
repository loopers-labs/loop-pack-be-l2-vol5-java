# 결제 보존 실험 실행 환경

테스트 PG와 commerce는 **별도 Java 프로세스**로 실행한다. MySQL 서버는 공유하지만 PG는 `loopers_test_pg`, commerce는 기존 `loopers` 스키마를 사용한다.

## 최초 준비

저장소 루트에서 실행한다. 기존 MySQL 볼륨은 그대로 사용하며 아래 명령은 테이블을 삭제하지 않는다.

```powershell
docker compose -f docker/infra-compose.yml up -d mysql redis-master redis-readonly
Get-Content -Raw -Encoding utf8 docker/payment-lab/001-pg-schema.sql |
    docker compose -f docker/infra-compose.yml exec -T mysql mysql -uroot -proot --default-character-set=utf8mb4
```

앱은 SQL을 자동 실행하지 않는다. PG에는 `001` → `002`, 기존 commerce에는 `003` → `004` → `005` → `006` → `007`을 적용한다.
`005`는 복구 작업, `006`은 주문별 차감 기록과 품목 테이블, `007`은 주문의 PG 결제 대기/실패 상태 추가다.
R01까지 준비한 DB에는 `007-order-pg-status.sql`만 한 번 적용한다. 기존 주문·POINT 기록은 보존한다.

commerce 테이블이 전혀 없는 **새 학습 DB**라면 먼저 `000-commerce-baseline.sql`을 적용한다.
이 파일은 4주차의 테이블 구조를 만들며, 기존 DB를 고치거나 예전 버전에서 자동 업그레이드하는 용도가 아니다.
이어서 `003`~`007`을 적용한다. 기존 데이터가 있는 DB에는 `000`을 실행하지 않는다.

PG 거래 테이블은 스키마 준비 다음에 적용한다.

```powershell
Get-Content -Raw -Encoding utf8 docker/payment-lab/002-pg-transactions.sql |
    docker compose -f docker/infra-compose.yml exec -T mysql mysql -uroot -proot --default-character-set=utf8mb4
```

commerce의 신규 결제 시도 테이블은 `003-payment-attempts.sql`이다. 기존 코드의 `order_records` 테이블에 결제수단을 추가할 때는 `004-order-payment-method.sql`을 한 번 적용한다. 기존 행의 금액·결과·시각은 유지하고 결제수단만 POINT로 채운다. 초기 스키마에 이미 해당 컬럼이 있다면 이 ALTER를 다시 실행하지 않는다.

## 실행

각각 다른 터미널에서 실행한다.

```powershell
./gradlew.bat :apps:test-pg:bootRun
./gradlew.bat :apps:commerce-api:bootRun --args='--spring.profiles.active=payment-lab'
```

PG 주소는 `http://127.0.0.1:8081`, commerce API 기본 포트는 8080, commerce 관리 포트는 8082다.
commerce의 `payment-lab`에서 관리 포트를 분리해 두 앱을 함께 실행할 수 있다. 해당 프로필은 INFO 콘솔 로그로 기동과 오류를 확인한다.
PG에는 commerce 실행 클래스·Redis 의존성이 없다. PG는 기본으로 `payment-lab`을 사용한다.

commerce의 `payment-lab` 실행은 기존 commerce 테이블이 준비되어 있어야 한다. `local`을 함께 활성화하지 않는다. 기존 `local`/`test`의 격리용 테이블 재생성 설정은 그대로 유지한다.

## 재시작과 초기화

두 앱의 `payment-lab`은 `ddl-auto=validate`를 사용한다. 재시작은 테이블을 지우거나 만들지 않고, 기존 스키마가 코드와 맞는지만 검사한다.

보존 실험에서는 프로세스만 종료·재시작한다. `docker compose down -v`는 사용하지 않는다. 데이터 초기화가 필요하면 초기화 대상과 SQL을 직접 확인해 별도로 실행한다. 현재 준비 SQL에는 삭제 명령이 없다.

접속 설정을 바꿀 때 PG는 `PG_MYSQL_HOST/PORT/USER/PASSWORD`, commerce는 `COMMERCE_MYSQL_HOST/PORT/USER/PASSWORD` 환경 변수를 사용한다. 기본 계정은 로컬 학습용 `test_pg/test_pg`, `application/application`이다.

## 별도 JVM으로 실제 연결·보존 확인하기

이 검사는 기존 로컬 데이터와 분리한 새 MySQL 인스턴스에서 실행한다. 예를 들어 호스트 포트 `13316`을 사용하는
임시 MySQL에 위 SQL을 적용한다. 두 스키마는 같은 인스턴스에 두고 서로 다른 계정으로 접근한다.

1. `:apps:test-pg:bootJar :apps:commerce-api:bootJar`를 빌드한다.
2. 새 터미널에서 `PG_MYSQL_PORT=13316`을 설정하고 PG JAR를 `java -jar`로 실행한다.
3. 아래처럼 commerce JAR를 풀고 실험용 Java 프로그램을 컴파일한다. JDK 21의 `java`, `jar`, `javac`를 사용한다.

```powershell
$jdk = 'C:/Program Files/Java/jdk-21.0.10/bin' # 설치 위치에 맞게 변경
$commerceJar = (Get-ChildItem apps/commerce-api/build/libs/*.jar |
    Where-Object Name -NotLike '*plain*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force build/payment-lab-runtime, build/payment-lab-probe | Out-Null
Push-Location build/payment-lab-runtime
& "$jdk/jar.exe" xf $commerceJar
Pop-Location
$classpath = 'build/payment-lab-probe;build/payment-lab-runtime/BOOT-INF/classes;build/payment-lab-runtime/BOOT-INF/lib/*'
& "$jdk/javac.exe" -proc:none -encoding UTF-8 -cp $classpath -d build/payment-lab-probe docker/payment-lab/PaymentLabProbe.java
$env:COMMERCE_MYSQL_PORT = '13316'
& "$jdk/java.exe" -cp $classpath com.loopers.lab.PaymentLabProbe exercise
& "$jdk/java.exe" -cp $classpath com.loopers.lab.PaymentLabProbe verify
```

`exercise`는 정상 성공·응답 유실 후 성공/실패·지연·생성 전 거절을 실제 adapter로 확인하고 관찰 결과를 저장한다.
제품 API를 추가하지 않으며 실제 주문/상품 변경도 하지 않는다. 실험의 완료 확인용 polling은 R03 자동 재조회 구현이 아니다.
고정 실험 ID를 사용하므로 `exercise`는 빈 실험 DB에서 한 번 실행한다. `verify`는 별도 새 JVM에서 관찰·조건·차감 기록과
복구 작업의 실행 ID·사용 횟수가 남는지 확인한다. 성공하면 `PAYMENT_LAB_EXERCISE_OK`, `PAYMENT_LAB_VERIFY_OK`가 출력된다.
실패 시 프로세스 오류를 확인한다. 중간에 실패한 데이터를 기존 데이터와 섞어 재실행하지 않는다.

PG 자체 재시작은 처리 지연을 15초, 생성 응답을 NORMAL, callback을 DROP으로 지정해 PENDING 거래를 만든 뒤 확인한다.
이 실험에서 실행한 PG 프로세스만 강제 종료하고 DB의 PENDING을 확인한다. 같은 DB로 PG를 다시 실행한 뒤 같은 거래 키가
SUCCESS가 되는지, 앞서 완료한 거래도 같은 결과로 조회되는지 확인한다. 테이블이나 볼륨은 재시작 사이에 초기화하지 않는다.

POINT 증분 호환은 `000` 직후 `orders`/`order_records`에 기존 PAID 행을 넣고 `003`~`006`을 적용해 확인한다.
`payment_method=POINT`가 채워지고 기존 amount/status/created_at이 보존되어야 한다. `payment-lab` validate 기동도 확인한다.
