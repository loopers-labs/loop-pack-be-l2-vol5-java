# Spring Boot Docker 실행

Spring Boot 앱 이미지를 로컬에서 만들어 파일로 전송하는 실험용 구성이다. Java 21·Docker Compose v2를 사용한다. MySQL 스키마와 데이터는 먼저 준비되어 있어야 한다.

## 빌드

저장소 루트에서 실행한다.

```bash
./gradlew :apps:commerce-api:prepareDocker
docker build -f docker/commerce-api.Dockerfile \
  -t commerce-api:local apps/commerce-api/build/docker
```

`prepareDocker`는 이번 빌드의 실행 JAR만 `apps/commerce-api/build/docker/app.jar`로 복사한다. 이미지 빌드에 소스·CSV·환경변수 파일은 전송하지 않는다. 컨테이너의 Java 프로세스는 UID 10001로 실행된다.

## 로컬 실행

```bash
cp docker/app.env.example docker/app.env
```

`docker/app.env`의 `ADMIN_PASSWORD`를 채운다. 예시는 기존 실험 MySQL(`host.docker.internal:13307`)에 연결한다. Redis master·replica 주소는 실제 실행 환경에 맞춘다. `host.docker.internal`은 Docker Desktop의 호스트 주소다.

```bash
docker compose --env-file docker/app.env -f docker/app-compose.yml up -d --wait
curl -H 'X-USER-ID: 1' \
  'http://127.0.0.1:18080/api/v1/products?brandId=7&sort=price_asc&page=0&size=20'
curl http://127.0.0.1:18081/actuator/prometheus
docker compose --env-file docker/app.env -f docker/app-compose.yml logs -f commerce-api
```

- 프로필은 `local,docker` 순서다. 기존 로컬 관리자 Basic 인증을 유지하고 비밀번호는 `ADMIN_PASSWORD`로 지정한다.
- Docker 프로필의 외부 DB·Redis 연결값이 local 기본값을 덮어쓴다. `ddl-auto=none`이며 Compose에서도 명시한다.
- 내부 포트는 API 8080, 관리 8081. 호스트에는 기본적으로 loopback에만 공개한다.
- healthcheck는 앱 liveness다. DB·Redis 연결 검증은 상품 API와 `/actuator/health` 등으로 별도 확인한다.
- 기존 `infra-compose.yml`·`monitoring-compose.yml`과 독립된 앱 실행 파일이다. DB·Redis를 만들거나 삭제하지 않는다.

중지·제거는 다음 명령으로 한다. 앱만 제거되고 외부 DB는 유지된다.

```bash
docker compose --env-file docker/app.env -f docker/app-compose.yml down
```

## EC2로 이미지 전송

EC2 CPU에 맞는 이미지 플랫폼을 선택한다. x86 인스턴스는 `linux/amd64`, Graviton은 `linux/arm64`다. 다음은 x86 예시다. 릴리스마다 새로운 이미지 태그를 사용한다.

```bash
./gradlew :apps:commerce-api:prepareDocker
docker buildx build --platform linux/amd64 --load \
  -f docker/commerce-api.Dockerfile -t commerce-api:week4-v1 \
  apps/commerce-api/build/docker
docker save -o build/commerce-api-week4-v1.tar commerce-api:week4-v1
```

EC2에 Docker Engine·Compose가 설치되어 있는 상태에서 이미지 tar, `app-compose.yml`, `app.env.example`을 같은 디렉터리에 전송한다. 실제 접속 계정과 주소로 대체한다.

```bash
scp build/commerce-api-week4-v1.tar docker/app-compose.yml docker/app.env.example \
  ubuntu@APP_EC2_IP:~/
```

EC2에서 `app.env.example`을 `app.env`로 복사하고 아래 값을 수정한다.

| 환경변수 | EC2 설정 |
| --- | --- |
| `APP_IMAGE` | `commerce-api:week4-v1` |
| `APP_BIND_ADDRESS`, `APP_PORT` | `0.0.0.0`, `8080` |
| `MYSQL_HOST`, `MYSQL_PORT` | DB EC2의 프라이빗 IP, MySQL 공개 포트 |
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PWD` | 미리 준비한 실험 DB·계정 |
| `REDIS_MASTER_HOST`, `REDIS_REPLICA_1_HOST` | Redis를 실행한 EC2의 프라이빗 IP |
| Redis 포트 | DB EC2의 실제 master·replica 공개 포트 |
| `ADMIN_PASSWORD` | 실험용 관리자 비밀번호 |

API 보안 그룹은 부하 발생기의 IP만 허용한다. DB·Redis는 앱 EC2에서 오는 연결만 허용한다. 관리 포트는 loopback을 유지하고 SSH 터널로 접근할 수 있다. 모니터링 도구에서 직접 접근하려면 바인딩과 보안 그룹을 별도로 정한다.

```bash
docker load -i commerce-api-week4-v1.tar
docker compose --env-file app.env -f app-compose.yml up -d --wait
```

코드를 수정하면 새로운 태그로 빌드·전송·load하고 `app.env`의 `APP_IMAGE`를 바꾼 뒤 같은 `up -d --wait`를 실행한다. 앱 컨테이너가 교체되며 외부 DB 데이터는 유지된다. 단일 앱 교체 중에는 짧은 중단이 발생한다. DB 구조 변경이 있다면 마이그레이션은 별도로 적용한다.

## 검증 결과 (2026-10-13)

- `prepareDocker`·이미지 빌드·Compose 설정 검사·컨테이너 healthcheck 통과.
- Docker Desktop의 `linux/arm64` 이미지로 실제 실행했다. EC2 실행은 아직 하지 않았다.
- 실험 MySQL과 임시 Redis master·replica에 연결해 `/actuator/health`의 UP 상태를 확인했다.
- 상품 목록 응답이 기존 CSV 검증 기준선과 일치했고, 앱 재시작 뒤에도 동일했다. 상품 10,000개·좋아요 999,493개를 유지했다.
- 관리자 API는 미인증 403, 환경변수로 지정한 비밀번호 인증 후 200. Prometheus HTTP·Hikari 지표 조회 성공.
- 비루트 UID 10001 실행 확인. 검증용 앱·Redis 컨테이너는 정리했다.
- `:apps:commerce-api:check` 통과: 테스트 367건, 실패·오류·skip 0건, Checkstyle·ArchUnit 포함.
- 전송용 로컬 이미지 파일은 `build/commerce-api-local.tar`이며 ARM64용이다. x86 EC2에는 위 `linux/amd64` 명령으로 별도 빌드한다.
