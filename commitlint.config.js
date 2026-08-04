module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // 커밋 설명이 한글이라 case 개념이 없고, "CLAUDE.md"/"API"처럼
    // 대문자로 시작하는 고유명사가 자주 등장해 subject-case가
    // 오탐(예: upper-case로 잘못 판정)을 일으키므로 비활성화한다.
    'subject-case': [0],
  },
};
