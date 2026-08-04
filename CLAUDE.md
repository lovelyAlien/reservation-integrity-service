# 프로젝트 설정

## Git 커밋 규칙

- 커밋 메시지는 한글로 작성한다.
- `Co-Authored-By: Claude ...` 등 AI 출처/서명 트레일러는 절대 붙이지 않는다.
- 첫 줄은 Conventional Commits 형식(`type(scope): 설명`)을 따른다.
  예) `feat: 로그인 API 추가`, `fix(auth): 토큰 만료 버그 수정`
  허용 타입(`@commitlint/config-conventional` 기본값): `build chore ci docs feat fix perf refactor revert style test`
- 위 규칙은 `.githooks/commit-msg` 훅으로 강제된다. 형식 검증은 [commitlint](commitlint.config.js)가 담당하고,
  한글 작성/AI 서명 금지는 훅 스크립트가 직접 검사한다.
  최초 1회 `git config core.hooksPath .githooks` && `npm install` 필요
  (commitlint는 Node 도구이며, Java/Gradle 애플리케이션과는 무관한 커밋 검증 전용 의존성이다).
  `subject-case` 규칙은 비활성화되어 있다 — 한글 설명에는 대소문자 개념이 없고,
  "CLAUDE.md"처럼 대문자로 시작하는 고유명사가 자주 등장해 오탐(예: upper-case 오판)이 발생하기 때문이다.
  이 저장소의 기존 커밋(타입 접두사 없음)은 소급 적용되지 않으며, 이후 커밋부터 적용된다.

## 문서화된 학습

`docs/solutions/` — 과거에 해결한 문제(버그, 베스트 프랙티스, 워크플로우 패턴)를 카테고리별로
정리한 문서 모음. YAML frontmatter(`module`, `tags`, `problem_type`)로 검색 가능하며,
관련 영역에서 구현하거나 디버깅할 때 참고할 만하다.

이 저장소의 문서(PROBLEM.md, DESIGN.md, README.md, `docs/solutions/` 등)는 한글로 작성한다.
`docs/solutions/`의 YAML frontmatter 중 `problem_type`/`component`/`root_cause`/`resolution_type`/
`severity`는 ce-compound 스키마가 정한 영문 enum 값을 그대로 써야 하고, `tags`/`related_components`도
검색 키워드이므로 영문 kebab-case를 유지한다 — 이 필드들을 제외한 `title`, `symptoms`, 본문 프로즈는
한글로 작성한다. 코드 블록, 파일 경로, `file:line` 인용, 클래스/메서드명은 원문(영문/코드) 그대로 둔다.
