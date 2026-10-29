/** 아동 실명을 문장에 넣을 때 성을 빼고 받침에 따라 조사를 맞춘다 (VS-003 실명 표시). */

// 두 글자 성. 이 성으로 시작하는 네 글자 이름은 앞 두 글자를 성으로 본다(예: 남궁민수 → 민수).
const TWO_LETTER_SURNAMES = ["남궁", "황보", "제갈", "선우", "독고", "사공", "서문", "동방"]

function hasFinalConsonant(name: string) {
  const code = name.charCodeAt(name.length - 1) - 0xac00
  return code >= 0 && code <= 11171 && code % 28 !== 0
}

/**
 * 부를 때 쓰는 이름. 강사가 실명(성+이름)으로 등록하므로 성을 뺀다.
 * - 띄어 쓴 이름은 마지막 덩어리를 쓴다(예: 김 하늘 → 하늘).
 * - 세 글자 이상이면 앞 한 글자(두 글자 성이면 두 글자)를 뺀다(예: 김하늘 → 하늘).
 * - 두 글자 이하는 성인지 이름인지 알 수 없어 그대로 쓴다(예: 하늘, 이안).
 */
export function givenName(name: string) {
  const parts = name.trim().split(/\s+/)
  const last = parts[parts.length - 1] ?? ""
  if (parts.length > 1) return last
  if (last.length === 4 && TWO_LETTER_SURNAMES.some((surname) => last.startsWith(surname))) return last.slice(2)
  return last.length >= 3 ? last.slice(1) : last
}

/** 부르는 말. 예: 김서연 → 서연아, 박지우 → 지우야 */
export function withVocative(name: string) {
  const given = givenName(name)
  return `${given}${hasFinalConsonant(given) ? "아" : "야"}`
}

/** 받침이 있으면 "이"를 붙인다. 뒤에 "의"·"는" 등을 이어 쓴다. 예: 김서연 → 서연이의, 박지우 → 지우의 */
export function withNameSuffix(name: string) {
  const given = givenName(name)
  return hasFinalConsonant(given) ? `${given}이` : given
}
