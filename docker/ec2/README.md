# Week4 EC2 deployment · 시드니 기록

작성일: 2026-10-13

서울의 현재 배포는 [seoul.md](seoul.md)를 참고한다. 아래는 기존 시드니 배포 기록이다.

[시스템 구성도](../../docs/week4/system-architecture.md)

## 현재 상태

- 두 서버 SSH 연결·Docker Engine/Compose 설치 완료.
- MySQL 8.0.46·Redis 7.0 master/replica 정상. replica의 `master_link_status=up` 확인.
- `uniform-10k-v1` 적재 완료: 브랜드 100·사용자 10,000·상품 10,000·재고 10,000·좋아요 999,493행. CSV 모든 필드의 순서별 체크섬 일치, 경고 0 확인 후 commit.
- 실제 로컬 실험 DB에서 데이터 없이 DDL을 추출해 빈 EC2 DB에 적용. 기존 CHECK·이름 유일성 제약 정의 검증 완료, 추가 변경 0. FK 추가 없음.
- 앱 JAR 전송 및 EC2에서 amd64 이미지 빌드 완료. Compose 설정 검증 완료.
- 앱→DB의 3306/6379/6380 연결 확인. 앱 컨테이너 healthy, Actuator health UP·Prometheus HTTP 200.
- 앱 서버 내부에서 상품 목록 API 호출 성공. 기존 기준 응답과 모든 데이터 필드·상품 순서 일치 확인.
- 로컬→퍼블릭 IP:8080의 HTTP 200·기준 응답 일치 확인. EC2 부하 탐색 결과는 `docs/week4/experiments/preliminary/results/ec2-*`에 별도 기록한다.

## 실제 환경

| 항목 | 앱 | DB |
| --- | --- | --- |
| 이름 | week4-app | week4-db |
| 인스턴스 | m7i-flex.large, x86_64, 메모리 약 8 GiB | 동일 |
| OS | Ubuntu 26.04 LTS | 동일 |
| 리전/AZ | ap-southeast-2 / ap-southeast-2a | 동일 |
| 퍼블릭 IP | 3.106.241.33 | 16.176.204.86 |
| 프라이빗 IP | 172.31.2.234 | 172.31.14.182 |
| 보안 그룹 | sg-00780bd7938254d89 | sg-040925c8912903dff |
| 루트 파일시스템 | 약 19 GiB | 약 19 GiB (계획의 30 GiB 대신 현재 20 GiB 볼륨) |
| 실행 경로 | /home/ubuntu/week4 | /home/ubuntu/week4 |

SSH 사용자는 `ubuntu`. 키 파일은 저장소 밖에 보관한다. 비밀번호와 개인키는 커밋하지 않는다. 서버의 실행 디렉터리는 700, env 파일은 600이다.

## 연결 규칙

DB 보안 그룹 `sg-040925c8912903dff`에 아래 인바운드를 추가한다. 기존 SSH 내 IP 규칙은 유지한다.

| 프로토콜 | 포트 | 소스 |
| --- | --- | --- |
| TCP | 3306 | 앱 보안 그룹 `sg-00780bd7938254d89` |
| TCP | 6379 | 앱 보안 그룹 `sg-00780bd7938254d89` |
| TCP | 6380 | 앱 보안 그룹 `sg-00780bd7938254d89` |

DB Compose는 프라이빗 IP에 포트를 바인딩한다. EC2 퍼블릭 IP는 프라이빗 IP로 매핑되므로 이것만으로 외부 접근이 차단되는 것은 아니다. 보안 그룹에서 DB 포트를 앱에만 허용해야 한다.

앱 8080은 로컬 k6의 IP에서만 허용한다. 관리 포트 8081은 호스트 loopback에만 바인딩하고 SSH 터널로 접근한다. 관리 API Basic 인증 비밀번호는 별도로 생성했으며, DB 비밀번호와 함께 env 파일에만 저장했다.

## 배포 파일과 명령

- `install-docker.sh`: Ubuntu에 공식 Docker 저장소와 Engine/Compose 설치. Docker가 이미 있으면 버전 확인만 한다.
- `db-compose.yml`: MySQL·Redis master/replica와 영속 볼륨. 로컬 개발용 전체 infra Compose는 실행하지 않는다.
- 앱은 상위 `app-compose.yml`, `commerce-api.Dockerfile`을 사용한다. JAR는 `:apps:commerce-api:prepareDocker` 결과다.
- `db.env` 필수 값: `DB_BIND_ADDRESS`, `MYSQL_ROOT_PASSWORD`, `MYSQL_PWD`. 예제 비밀번호를 EC2에 사용하지 않는다.
- 앱 `app.env`에는 DB 프라이빗 주소, 같은 DB 비밀번호, Redis 주소, 별도 관리자 비밀번호와 이미지 태그를 넣는다. `local,docker`, `ddl-auto=none`, SQL 출력 off. 풀·스레드·JVM 메모리 옵션은 이번 배포에서 조정하지 않았다.

서버에서 실행:

```bash
cd ~/week4
# DB 서버
sudo docker compose --env-file db.env -f db-compose.yml up -d --wait

# 앱 서버: DB 연결 규칙 추가 후
sudo docker compose --env-file app.env -f app-compose.yml up -d --wait
```

데이터 적재는 기존 `docs/week4/seed-data/scripts/load.py`를 사용했다. 빈 테이블만 허용하고 체크섬 검증 후 commit하는 기존 규칙을 유지했다. 실행 중인 DB에 DDL·CSV를 다시 덮어쓰지 않는다.

## 실행 증거

- 로컬 `build/ec2-deploy/`: 설치·빌드·적재 로그, DDL snapshot, 적재 report, 전송 bundle. **env와 bundle에는 비밀번호가 있으므로 외부 공유하지 않는다.**
- DB 서버 `~/week4/docs/week4/seed-data/load-reports/perf_uniform_10k_v1.json`: 적재 필드별 비교를 반영한 테이블 체크섬·건수.
- 앱 이미지: `commerce-api:week4-20261013`, amd64.
- JAR SHA-256: `db92f25547197b1c9752bed015e773fd763fbf3c32a1291e76ce232ef19b00f9`.
- 이미지 ID: `sha256:6bc46ed244947869a25a0f72c5daf20cdf058f04b81fee7a70195ec1ed3c2b8e`.
- 앱 JAR 빌드 성공. 이번 변경은 배포 설정·스크립트이며 업무 코드 변경은 없다. 이번 배포 작업에서 전체 회귀·lint·ArchUnit은 재실행하지 않았다. 앱 서버 내부 HTTP 응답을 기존 기준선과 비교해 일치를 확인했다.
