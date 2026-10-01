/** 아동 실명을 문장에 넣을 때 받침에 따라 조사를 맞춘다 (VS-003 실명 표시). */

function hasFinalConsonant(name: string) {
  const code = name.charCodeAt(name.length - 1) - 0xac00
  return code >= 0 && code <= 11171 && code % 28 !== 0
}

/** 부르는 말. 예: 서연아, 지우야 */
export function withVocative(name: string) {
  return `${name}${hasFinalConsonant(name) ? "아" : "야"}`
}

/** 받침이 있으면 "이"를 붙인다. 뒤에 "의"·"는" 등을 이어 쓴다. 예: 서연이의, 지우의, 서연이는 */
export function withNameSuffix(name: string) {
  return hasFinalConsonant(name) ? `${name}이` : name
}
