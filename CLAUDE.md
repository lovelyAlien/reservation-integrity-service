# 프로젝트 설정

## Git 커밋 규칙

- 커밋 메시지는 한글로 작성한다.
- `Co-Authored-By: Claude ...` 등 AI 출처/서명 트레일러는 절대 붙이지 않는다.
- 위 규칙은 `.githooks/commit-msg` 훅으로 강제된다 (최초 1회 `git config core.hooksPath .githooks` 필요).
