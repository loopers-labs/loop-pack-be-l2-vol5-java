---
name: loop-backend-delivery
description: "Loop Java 백엔드의 문서 기반 구현이나 세션 재개에 사용한다. 현재 Git·코드·검사 근거를 대조하고 기능별 구현, 환경 제약 구분, 검증된 커밋과 로컬 인수인계를 지원한다. 일반 Java 질문이나 과거 기록만 제공된 요청에는 적용하지 않는다."
---

# Loop 백엔드 작업 이어가기

## 현재 상태 확인

- 상위 Loop 폴더라면 기존 `loop-pack-be-l2-vol5-java` 저장소를 찾는다. `git rev-parse --show-toplevel`과 현재 사용자 요청부터 확인한다.
- 저장소 루트 `AGENTS.md`를 협업·기술·검증·Git 규칙의 기준으로 삼는다. 현재 주차 README/설계/구현 결과와 관련 코드만 이어 읽는다.
- `git status --short --branch`, 최근 커밋과 필요한 diff로 사용자 수정·staging·미추적 파일을 확인한다.
  과거 C01/C02 착수 지시나 지난 주차 브랜치를 현재 다음 작업으로 재사용하지 않는다.
- `docs/week8/handoff.md`가 있으면 로컬 인계로 읽는다. Git에서 제외되어 다른 checkout에 없을 수 있으며,
  없으면 Git·코드·검사 근거로 상태를 재구성한다. 인계 파일 존재가 Git 제외 완료의 증거는 아니다.
- JUnit XML의 시각·tests/failures/errors/skipped와 실제 태스크/필터를 대조한다. 구현·검사·커밋 완료와 과거 재사용 근거를 구분한다.

## 구현과 검증 연결

- 승인된 기능의 application 계약/흐름과 infrastructure 구현을 함께 완성한다. 과거 단계의 presentation 순서를 무관한 새 작업에 강제하지 않는다.
- 쿠폰의 DB 총량·Semaphore·Redis 경계가 혼동될 때 [쿠폰 경계](references/coupon-boundaries.md)를 확인하고 현재 설계를 우선한다.
- 실제 DB·DDL·외부 기술이 필요한 근거를 mock·컴파일 성공으로 대체하지 않는다. 검사 선택·리뷰/QA·커밋 형식은 AGENTS를 따른다.
- 문서만 바뀌면 링크·내용·diff를 검증한다. 원격 코드도 통합했다면 그 영향에 필요한 검사를 추가한다.

## 환경 제약 구분

- Docker 엔진 미기동, named pipe 거절, API 호환 400, Gradle cache 쓰기, JDK, DB 접속 실패를 구별한다.
  다른 세션의 권한이나 앱이 켜졌다는 사실로 현재 접근 성공을 가정하지 않는다.
- 현재 주차 운영 안내와 이전 실행 근거의 확인된 명령을 참고한다. 전역 ACL·설정·기존 DB/cache/container를 일괄 변경하지 않는다.
- 같은 거절을 반복하거나 우회하지 않는다. 가능한 정적 확인을 마친 뒤 미실행 검사·원인·현재 PowerShell의 정확한 재개 명령을 남긴다.
- 사용자가 별도 터미널에서 실행했다면 그 출처와 XML을 명시한다. 실패·skip·0건을 통과로 기록하지 않는다.

## Git와 로컬 인계

- 요청된 파일만 명시적으로 staging하고 사용자 JMeter 등 무관한 변경을 제외한다. 긴 커밋 본문은 UTF-8 파일과 `git commit --file`을 사용한다.
- 원격 동기화가 요청되면 원격 URL·upstream·목표를 확인하고 fetch 뒤 차이를 검토한다.
  사용자 변경을 보존하며 공유 이력을 재작성하지 않는다. push/PR/병합은 현재 요청의 범위만 수행한다.
- [인계 템플릿](assets/handoff-template.md)에 다음 작업·실제 브랜치/HEAD·검증 출처·남은 한계·환경 제약·최소 재개 명령을 적는다.
- 인계는 영구 설계/검증 문서의 사본이 아니라 현재 재개 메모다. 자격증명은 쓰지 않는다.
- 로컬 전용 인계는 정확한 ignore 경로를 사용한다. 이미 추적 중이면 승인 범위에서 `git rm --cached -- docs/week8/handoff.md`로 추적만 해제하고
  실제 파일을 보존한다. `git ls-files`와 `git check-ignore -v`로 확인하며 본문을 다시 stage하지 않는다.

## 이 스킬 확인

`scripts/validate.ps1`은 frontmatter와 스킬 내부 링크를 검사한다. 저장소 코드·명령의 현행성이나 실제 기능 검증을 대신하지 않는다.
