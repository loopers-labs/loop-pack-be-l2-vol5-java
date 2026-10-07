# AI 작업 규칙

합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다.
정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.
글을 작성할땐 어떠한 형태로든 AI에게 작성한 요청이나 프롬프트 내용을 남기지 않는다.

## 프로젝트 참고

- 이번 주 설계: `docs/week2/design.md` (API 계약, 규칙 R1..R6, 책임 배치)
- 패키지 의존 규칙: `apps/commerce-api/src/test/java/com/loopers/architecture/ArchitectureTest.java`
- 검사 명령: `./gradlew :apps:commerce-api:check` (Checkstyle + 전체 테스트)
- 사용자 식별: `X-USER-ID` 요청 헤더 (로컬 실습용)
