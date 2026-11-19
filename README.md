# Loop Java 백엔드 · 개발과 학습 안내

상품·브랜드·좋아요·포인트·주문에서 시작해 PG 결제, Outbox·Kafka·Inbox, 쿠폰 발급으로 확장한 Java 21 / Spring Boot 프로젝트다.
9주차에는 구매 활동을 주문 파티션별로 병렬 처리해 주간 랭킹으로 공개한다. 단계별 준비 문구보다 현재 코드·Git 이력과 아래 기준 문서를 우선한다.

## 현재 개발에 필요한 문서

| 목적 | 기준 문서 |
|---|---|
| 에이전트 작업·협업 규칙 | [AGENTS](AGENTS.md) |
| 구조·의존·객체·트랜잭션 관례 | [개발 컨벤션](docs/week2/conventions.md) |
| 실행·DB 준비·PG·Outbox | [결제 실험 환경](docker/payment-lab/README.md), [테스트 PG](apps/test-pg/README.md) |
| 결제 상태·장애 대응 | [상태 모델](docs/week5/states/total.md), [회복 전략](docs/week5/resilience/README.md) |
| 이벤트 계약·소비·복구 | [6~7주차 안내](docs/week6-7/README.md) |
| 쿠폰 설계·API·운영·검증 한계 | [8주차 안내](docs/week8/README.md) |
| 테스트 실행과 검증 경계 | [domain](docs/test/domain.md), [application](docs/test/application.md), [infrastructure](docs/test/infrastructure.md), [interfaces](docs/test/interfaces.md) |

현재 구현은 실제 로그인·공개 서비스 운영을 제공하지 않는다. 고객의 `X-USER-ID`는 로컬 fixture 식별이며,
주문 고객 경로와 쿠폰의 소유권 검사는 각 기능 계약을 따른다. 관리자 fixture와 테스트 PG 제공자 헤더는 별도 계약이다.

## 주차별 학습 기록

| 주차 | 내용과 읽는 기준 |
|---|---|
| [1주차](docs/week1/order-discount-contract.md) | 중단한 주문 할인 설계. 질문 17개와 미확인 정책을 보존하며 8주차 쿠폰 발급 구현과 구분 |
| [2주차](docs/week2/README.md) | 당시 요구사항·초기 계약·구현 선택. 현재 개발 관례는 위 컨벤션 우선 |
| [3주차](docs/week3/total_requirement.md) | 브랜드 삭제와 주문 정합성·동시성 |
| [3주차 후속](docs/week3-2/total_requirement.md) | 조회·집계·잠금·파사드 변경의 이유와 결과 |
| [구조 리팩터링](docs/refactor/result.md) | 패키지 개편과 테스트 경량화의 당시 근거 |
| [4주차](docs/week4/total_requirement.md) | 인덱스·로컬 캐시와 원본 측정 근거 |
| [5주차](docs/week5/total_requirement.md) | PG 결제·장애 회복, 당시 최종 점검 |
| [6~7주차](docs/week6-7/README.md) | Outbox·Kafka·Inbox와 전달 복구 |
| [8주차](docs/week8/README.md) | DB 쿠폰 발급·인스턴스별 수용 제어·Redis 누적 집계 |
| [9주차](docs/week9-partitioned/README.md) | 주문 파티션 분배·로컬 상품 파티셔닝·주간 랭킹 공개와 장애 인수 |

과제 원문은 출처로, 과거 결과·수치는 해당 실행 시점의 근거로 읽는다. 과거 계획의 미체크나 브랜치 지시는
현재 미완료·다음 작업의 증거가 아니다. 현재 코드와 다른 정책은 현행 안내의 차이 설명을 함께 확인한다.

## 로컬 실행과 검사

저장소 루트 PowerShell에서 실행한다. 인프라 기동은 앱 스키마 준비를 대신하지 않는다.
기존 DB를 사용하는 경우 먼저 [보존 실행 절차](docker/payment-lab/README.md)와 [쿠폰 운영 안내](docs/week8/completed/operations.md)를 읽는다.

```powershell
docker compose -f docker/infra-compose.yml up -d
./gradlew.bat :apps:commerce-api:bootRun
```

선택 사항인 모니터링 스택은 `docker compose -f docker/monitoring-compose.yml up -d`로 실행한다.
기본 Grafana 주소는 `http://localhost:3000`, 로컬 기본 계정은 `admin/admin`이다.

```powershell
./gradlew.bat :apps:commerce-api:test
./gradlew.bat :apps:commerce-api:slowTest
./gradlew.bat :apps:commerce-api:check
```

`test` 단독은 slow/example 태그를 제외하고 `slowTest`는 해당 태그만 실행한다.
commerce-api의 `check`/`build`는 `test` 한 번에 모든 태그를 포함하며 Checkstyle·ArchUnit을 함께 확인한다.
저장소 전체 검사는 `./gradlew.bat check --continue`다. 과거 성공 수치를 이번 실행 결과로 재사용하지 않는다.

## 모듈

- `apps/commerce-api`: 상품·주문·결제·쿠폰 HTTP와 업무 처리.
- `apps/commerce-streamer`: 주문 이벤트의 Inbox·구매 활동·실패 보관.
- `apps/test-pg`: 별도 프로세스로 실행하는 학습용 결제 제공자.
- `apps/commerce-batch`: 배치 앱 기반. `modules`: JPA·Redis·Kafka 구성. `supports`: Jackson·로깅·모니터링.
