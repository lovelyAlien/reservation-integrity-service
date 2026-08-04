# 프로젝트 설정

## Git 커밋 규칙

- 커밋 메시지는 한글로 작성한다.
- `Co-Authored-By: Claude ...` 등 AI 출처/서명 트레일러는 절대 붙이지 않는다.
- 첫 줄은 Conventional Commits 형식(`type(scope): 설명`)을 따른다.
  예) `feat: 로그인 API 추가`, `fix(auth): 토큰 만료 버그 수정`
  허용 타입: `feat fix docs style refactor test chore perf build ci`
- 위 규칙은 `.githooks/commit-msg` 훅으로 강제된다 (최초 1회 `git config core.hooksPath .githooks` 필요).
  이 저장소의 기존 커밋(타입 접두사 없음)은 소급 적용되지 않으며, 이후 커밋부터 적용된다.
