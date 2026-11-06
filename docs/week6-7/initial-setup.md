# 6~7주차 초기 준비 결과

기준일: 2026-11-04 (Asia/Seoul). 이 기록은 초기 준비만 다루며 업무 기능 구현 결과가 아니다.

## 준비 내용

- 원격 fetch 후 로컬 `volume-5/main`이 원격보다 사용자 커밋 1개 앞선 상태를 확인했다.
- 해당 `b4e499d`를 보존해 `volume-6-7/main`을 생성했다.
- 통합 요구사항·Inbox 추가 조건·R01~R03 순서·환경/다음 작업 안내를 정리했다.
- 사용자 제공 원문 4개를 reference에 내용 변경 없이 보존한다.
- 공용 Kafka Testcontainers fixture와 실제 broker 발행/소비 연결 검사를 추가했다.
- 기존 production Kafka/listener·주문/결제 코드와 사용자 변경은 수정하지 않는다.

## 실제 확인

- Java: 21.0.10.
- Docker: 처음 꺼진 상태였으며 Docker Desktop을 백그라운드 시작했다. Linux engine 29.8.0 확인.
- `:modules:kafka:test --tests '*KafkaTestFixtureTest'`: 실제 1건, 실패/오류/skip 0. 실제 2-partition topic 생성과 발행 metadata/수신 payload 일치를 확인했다.
- 첫 실행은 AdminClient의 default API timeout 10초가 기본 request timeout 30초보다 짧아 실패했다.
  테스트 client의 request timeout을 5초로 명시하고 같은 검사를 재실행해 통과했다. 업무 설정 변경은 없다.
- 같은 실행의 `:apps:commerce-streamer:compileTestJava`까지 BUILD SUCCESSFUL. streamer main은 컴파일됐고 test는 NO-SOURCE다.
  streamer 업무 테스트가 실행됐다는 의미가 아니다.
- 통합 안내 문서의 로컬 링크 24개가 존재함을 확인했다.
- 원문 4개는 복사 전후 SHA-256 일치. 새로 작성/수정한 파일의 diff whitespace 검사는 통과했다.
  원문 reference에는 첨부에 있던 후행 공백도 그대로 보존하므로 전체 `git diff --cached --check`는 그 경고를 표시한다.
  reference를 제외한 staged diff 검사로 신규 공백 오류가 없음을 확인하며 검사 설정 자체는 변경하지 않는다.
- 사용자 JMeter 파일 SHA-256은 준비 중 `F51DC3363E79FB6EEB15846ACA55C9028CB58F207C8F89CC973E2CE3B3FC89C0`로 동일함을 확인했다.
- 준비 후 첫 작업 브랜치는 `volume-6-7/r01-order-events-inbox`로 생성한다. R02/R03은 아직 만들지 않는다.

## 남은 범위

R01 이벤트·Inbox·구매 활동·직접 발행 실험, R02 Outbox/relay, R03 복구/E2E·회귀·제출은 미구현이다.
전체 기능 회귀나 실 주문 실험을 이번 초기 준비 검사로 대신했다고 기록하지 않는다.
