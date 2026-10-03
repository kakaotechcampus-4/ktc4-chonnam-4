// 서버는 한국 시간(+09:00)으로 보낸다. 브라우저 시간대와 상관없이 한국 시간으로 보인다.
const partsFormat = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function seoulParts(date: Date) {
  const parts = Object.fromEntries(partsFormat.formatToParts(date).map((part) => [part.type, part.value]))
  return { year: parts.year, month: parts.month, day: parts.day, time: `${parts.hour}:${parts.minute}` }
}

/**
 * 시안 표기(T-LRN-03 "발급 9/1", T-ACT-01 "최근 활동 오늘")를 따른다.
 * 오늘이면 "오늘 17:31", 올해면 "10/3 17:31", 그 전이면 "2025/12/30 17:31". 값이 없으면 "-".
 */
export function formatDateTime(value: string | null, now: Date = new Date()): string {
  if (!value) {
    return '-'
  }
  const target = seoulParts(new Date(value))
  const today = seoulParts(now)
  if (target.year === today.year && target.month === today.month && target.day === today.day) {
    return `오늘 ${target.time}`
  }
  const date = target.year === today.year ? `${target.month}/${target.day}` : `${target.year}/${target.month}/${target.day}`
  return `${date} ${target.time}`
}
