# 쿠폰 검증 매핑과 미확인 항목

이 문서는 준비 시점의 실행 계획을 현재 테스트와 확인한 근거로 대체한다.
현재 정책은 [설계](design.md), 단계별 실행 기록은 [구현 현황](completed/README.md)에 있다.
서로 다른 실행에서 반복한 아키텍처 검사나 단계별 건수를 합산해 고유 테스트 총수로 표현하지 않는다.

## 확인한 테스트

테스트는 `apps/commerce-api/src/test/java/com/loopers/` 아래의 실제 클래스다.

| 범위·클래스 | 확인한 핵심 동작 |
|---|---|
| `domain/coupon/model/CouponCampaignTest`, `CouponBenefitTest`, `CouponTest` | 도메인 10건: 준비 수·기간 경계·최대 한도·Unicode/금액·재활성 수량·불변 snapshot·복원 검증 |
| `application/coupon/service/PrepareCouponCampaignIntegrationTest` | 실제 MySQL 5건: 정상 준비·활성화, 부분 저장 전체 rollback, 기발급 재고 보존, 부분 준비 거절, 외부 RR snapshot 격리 |
| `infrastructure/persistence/coupon/CouponCampaignSchemaIntegrationTest` | 독립 MySQL에 실제 012 DDL 적용, 두 Entity validate·commit/clear/재조회, FK·복합 유일 키·인덱스·CHECK 1건 |
| `application/coupon/service/IssueCouponIntegrationTest` | 실제 MySQL 13건: 같은 사용자 동시 중복, 업무 거절 뒤 기존 결과, 잠긴 마지막 슬롯/품절 구분, 실패 전체 rollback, 마지막 상태·DB 시각 재검증, 1초 lock timeout 복원, FK/슬롯 오류 구분, DB epoch |
| `infrastructure/persistence/coupon/CouponIssueSchemaIntegrationTest` | 실제 012·013 DDL 및 쿠폰 매핑·snapshot·복합 FK·유일 제약·목록 인덱스 1건 |
| `infrastructure/query/coupon/CouponQueryDaoIntegrationTest` | 본인/타인 격리, 시각·동일 시각 ID 정렬, 빈 목록, 저장 혜택, 마감 뒤 기존 결과 3건 |
| `application/coupon/service/CouponAdmissionIntegrationTest` | 실제 Semaphore·Spring TX·MySQL 4건: C=3/네 번째 거절/반환 재진입, 확정 rollback 반환, 실제 잠긴 슬롯 재시도, UNKNOWN 보류·세션 소멸 뒤 복구 |
| `application/coupon/service/IssueCouponFacadeTest` | 결정적 재시도 예산·횟수·close·interrupt, 신호 부재/UNKNOWN, commit/생성 예외 보류 10건 |
| `application/coupon/support/CouponAdmissionControlTest` | 기본 닫힘·검증/개방, 한 번 반환, 지정 ID 부분 복구, 인스턴스/boot 독립, 증거 조회 예외 보류, 재검증 실패 무효화 8건 |
| `config/coupon/CouponAdmissionPropertiesTest` | 실제 설정 바인딩 0/음수/문자 거절 및 2/3/5 양수 구성, singleton·원 handle 한 번 반환 6건 |
| `infrastructure/persistence/coupon/JdbcCouponExecutionEvidenceTest` | PROCESS 가시성, 같은 DB·세션 소멸·TX/업무 정합성, Sleep 거절, 이전 세션 확인 8건. adapter 분기 단위 검증이며 실제 권한 변경 시험은 아님 |
| `application/coupon/service/CountingIssueCouponUseCaseTest` | 확정 결과 뒤 집계 실패 시 응답 보존, 발급 실패/불명 시 집계 미진입 2건 |
| `config/coupon/CouponCounterRedisConfigTest` | 전용 master와 command/connect timeout 1초 구성 1건 |
| `application/coupon/service/CouponCounterIntegrationTest` | 실제 Redis·MySQL 8건: 미준비/0/유실/오류, 동시 16회 중복 증가 1, 실패한 build 보존·TTL, commit·기존 결과·DB 복구, 오류 후 쿠폰/permit 보존, 재집계/신규 commit 경합, Redis 접속 실패, 옛 publish fence 회귀 |
| `interfaces/api/coupon/controller/CouponApiE2ETest` | 실제 HTTP·MySQL·Redis 6건: 201/200 동일 결과·소유권·5필드, 헤더/경로, 업무 거절·닫힘, 관리자·검증/복구, 집계 오류/재집계, 실제 3개 점유와 네 번째 429 |
| `interfaces/api/coupon/controller/CouponErrorMappingTest` | 일시 슬롯·잠금·종료 불명 503의 응답 경계 3건. 실제 DB 장애 재현과 구분 |
| `interfaces/api/coupon/controller/CouponMultiProcessE2ETest` | 실제 JVM 2개·HTTP·같은 DB 1건: 각각 C=3/합계 6, A의 포화·반환 재진입, B 독립, close 독립, 최종 쿠폰/발급 수/ISSUED 7·permit 복원 |
| `architecture/DomainPurityArchitectureTest`, `LayerArchitectureTest` | 도메인 순수성과 계층 경계 8건 |

## 실행 근거

C01 준비 5건 XML 시작은 2026-11-13 14:51:15 KST, 명시 DDL 1건은 15:04:38 KST다. 모두 실패·오류·skip 0이었다.
스키마 시험은 새 MySQL 8 컨테이너에서 실제 SQL과 저장/재조회·제약을 확인했으며 기존 DB를 초기화한 결과가 아니다.
C01의 초기 직접 컴파일·Checkstyle·ArchUnit 기록은 실제 MySQL 검증을 대신한 것이 아니고, 이후 DB 6건의 성공으로 보완됐다.

C06의 고객·관리자 HTTP 6건과 오류 매핑 3건, 아키텍처 8건은 첫 실행에서 통과했다.
독립 프로세스 1건은 자식 관리 포트 8081 충돌을 수정한 뒤 17:02 KST 실행에서 통과했다.
XML에 A PID 11128/port 50598, B PID 30896/port 64779가 기록됐다. 두 PID는 이 시험의 실행 기록이며 고정 운영 값이 아니다.
자식 관리 포트만 비활성화했고 쿠폰 주 서버는 임의 포트로 동작한다. 현재 대상 검증의 실패·오류·skip은 모두 0이다.

수용 통합은 실제 Spring TX 서비스와 DB adapter에 테스트별 실제 Control/Facade/Semaphore를 조립한다.
C06 HTTP는 실제 Spring Primary use case 경로를 검증한다. 이 두 근거를 같은 검증으로 표현하지 않는다.
UNKNOWN과 통신 오류의 일부 경로는 완료 신호·예외 실패 주입이며 실제 네트워크 단절 전체 재현은 아니다.

### 전체 검사와 환경 재실행

사용자 PowerShell의 `./gradlew.bat check --continue`에서 commerce-batch 3건과 test-pg 15건이 Docker API 400으로 실패했다.
성공한 모듈에는 로컬 Docker API 1.44 설정이 있었고 실패한 두 모듈에는 해당 설정이 없었다.
사용자 PowerShell에만 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`를 적용하고 `./gradlew.bat --stop` 뒤 두 모듈 테스트를
`--continue`로 재실행해 1분 34초에 성공했다. 기존 환경 변수는 finally에서 복원했으며 프로덕션 설정은 바꾸지 않았다.

| 모듈 | 최신 XML 건수 | 근거 |
|---|---:|---|
| commerce-api | 584 | 2026-11-13 17:10 KST 실행 |
| commerce-batch | 3 | 17:24 KST 환경 재실행 |
| test-pg | 25 | 17:25 KST 환경 재실행 |
| commerce-streamer | 22 | 2026-11-04 XML, 이번 전체 검사에서 UP-TO-DATE 재사용 |
| kafka | 1 | 2026-11-04 XML, 이번 전체 검사에서 UP-TO-DATE 재사용 |
| 합계 | 635 | 모두 실패·오류·skip 0 |

전체 요구 검사에서 실패했던 두 모듈을 환경 재실행으로 해결한 근거다. **한 번의 clean 전체 실행에서 635건을 새로 수행했다는 뜻은 아니다.**
이 확인으로 아래 개별 미확인 실험이 완료되는 것도 아니다.

## 미확인 항목

| 항목 | 보존할 조건·현재 한계 |
|---|---|
| 원문 quota=5, 서로 다른 사용자 8명 | 성공 5·품절 3·쿠폰/issuedCount 5 그대로의 종료 실험 근거가 아직 없음. 429/503 일시 경합을 품절 성공으로 대신 세지 않음 |
| 마감 전 최종 검증 뒤 마감 후 commit | 정책상 허용하지만 그 지연 commit 장면 자체의 실제 통합 근거는 미확인. 현재 실제 case는 잠금 대기 뒤 마감 거절을 검증 |
| C=3에서 C=2로 실제 재시작 | 2/3/5 독립 설정 바인딩과 실제 프로세스 재시작 실험은 다름. 같은 인스턴스의 변경 전후 재시작 실험은 미확인 |
| 실제 네트워크 commit 응답 유실 | fault injection·재요청 동일 결과와 구분. 원 서버/네트워크 단절 전체 조합은 미확인 |
| 운영 계정 권한 | 테스트컨테이너 관리자 계정 성공과 운영 `application` 계정의 직접 전역 PROCESS 권한·가시성 확인을 구분 |
| Redis 영속 전달 | commit 뒤 process 종료 시 집계 누락 가능. DB 재집계가 복구 수단이며 Outbox/EOS/영구 재시도 보장은 없음 |

## 재실행과 정리 경계

변경 영향이 없는 통과 검사는 반복하지 않는다. 실패·새 변경·미해결 우려에 필요한 항목만 재실행하고,
PR 마무리의 전체 check 결과는 별도로 확인한다. 연결 실패·0건·skip을 성공으로 처리하지 않는다.
barrier·HTTP·DB 대기는 유한 deadline을 사용하고 시험이 만든 실행·프로세스·컨테이너만 정리한다.
기존 상품·주문·PG·Outbox·Inbox·사용자 DB·캐시·볼륨은 문서 정리 대상이 아니다.
