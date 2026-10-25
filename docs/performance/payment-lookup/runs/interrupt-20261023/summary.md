# Retry backoff 중 호출자 취소 검증

TimeLimiter를 끈 상태에서 결과 기반 Retry가 backoff 중 interrupt를 받으면 `CancellationException`을 던졌다. 이 예외가 회로까지 전달돼 실패 표본으로 기록되고 조회 불가 응답 대신 500으로 끝날 수 있었다.

회로 안쪽의 조회 decorator에서 이 예외를 `LocalRejection(CANCELLED)`로 변환한다. 호출자의 interrupt 상태를 복원하고 추가 HTTP 조회를 보내지 않는다. 기존 회로 설정에 따라 성공, 실패 표본 모두에서 제외한다.

## 검증 결과

- Red: TimeLimiter를 끄고 RateLimiter 사용 여부를 나눈 두 테스트가 `CancellationException`으로 실패했다.
- Green: HTTP 1회 뒤 backoff에 진입한 것을 확인하고 호출자를 중단했다. 두 경우 모두 `CANCELLED`, interrupt 유지, 추가 HTTP 0회, 회로 표본 0개를 확인했다.
- 대상 검사: 정책, 프로필, ArchUnit 테스트 28개와 Checkstyle 통과.
- 전체 검사: `:apps:commerce-api:check :apps:test-pg:check` 통과. Commerce 694개, test-pg 112개이며 실패, 오류, 건너뜀은 모두 0개다. 두 모듈의 Checkstyle과 ArchUnit 2개도 통과했다. 실행 시간은 4분 15초이며 30개 작업이 모두 실행됐다.

동시 실행의 결과 파일 충돌을 피하려고 임시 init script로 프로젝트별 build 디렉터리와 Gradle 프로젝트 캐시를 분리했다. 첫 전체 검사에서는 임시 경로가 ArchUnit의 테스트 제외 패턴과 달라 테스트 클래스까지 검사했다. 해당 실행을 중단하고 표준 `build/classes/java/test` 형태로 경로를 바로잡은 뒤 전체 검사를 통과했다. 테스트와 검사 규칙은 변경하지 않았다. 실행 명령, 집계와 소스 해시(`verification.json`)

당시 사용한 init script와 같은 내용을 [저장소에 보관](../../isolated-build.init.gradle)했다. [결과 파일 분리 실행 절차](../../README.md#결과-파일을-분리한-전체-검사) 또는 JSON의 `reproductionCommand`를 사용하면 당시 임시 경로 없이 같은 검사를 재현할 수 있다.

깨진 PG 문서 앵커, timeout과 실험 프로필 주석, 네 조회 정책의 실행과 주기 조회를 혼동하는 표현도 수정했다. 정책값과 기본 프로필의 활성화 여부는 바꾸지 않았다. 정상 결제 5분 부하는 다시 실행하지 않았다.
