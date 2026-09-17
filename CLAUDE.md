## 구현 규칙

1. 합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
2. 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
3. 작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
4. 검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다.
5. 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.

## PR 규칙

- `origin`은 fork(`youngho9999/loop-pack-be-l2-vol5-java`)다. PR은 원본 `loopers-labs/loop-pack-be-l2-vol5-java`의 `youngho9999` 브랜치를 base로, fork의 `volume-N`(N주차) 브랜치를 head로 올린다.
  ```bash
  gh pr create --repo loopers-labs/loop-pack-be-l2-vol5-java --base youngho9999 --head youngho9999:volume-N
  ```
- 제목: `[volume-N] 작업 내용 요약`
- 본문: `.github/pull_request_template.md` 형식을 따른다.
  - **Review Points**가 가장 중요하다. 리뷰어가 중점적으로 볼 곳, 고민한 설계 포인트, 확인이 필요한 테스트·예외 상황을 구체적으로 쓴다.
  - Checklist는 사용자가 제공한다. 임의로 채우지 않는다.
  - References는 정말 필요한 경우에만 쓰고, 없으면 섹션을 제거한다.
- PR을 바로 올리지 않는다. 제목과 본문 전문을 먼저 보여주고 허락을 받은 뒤에만 `gh pr create`를 실행한다.
