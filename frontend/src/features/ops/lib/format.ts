/** 대시보드 숫자·시각 표기. 표의 숫자는 자리수를 맞추고, 큰 숫자는 한 줄에 들어가게 줄인다. */

const NUMBER = new Intl.NumberFormat('ko-KR')

export function count(value: number): string {
  return NUMBER.format(value)
}

/** 1,000 이상은 천·만 단위로 줄인다. 타일의 큰 숫자에만 쓴다. */
export function compact(value: number): string {
  if (value >= 10_000) return `${trim(value / 10_000)}만`
  if (value >= 1_000) return `${trim(value / 1_000)}천`
  return NUMBER.format(value)
}

export function duration(ms: number): string {
  if (ms < 1_000) return `${Math.round(ms)}ms`
  if (ms < 60_000) return `${trim(ms / 1_000)}초`
  return `${Math.floor(ms / 60_000)}분 ${Math.round((ms % 60_000) / 1_000)}초`
}

export function percent(part: number, whole: number): string {
  if (whole === 0) return '0%'
  const value = (part / whole) * 100
  return `${value > 0 && value < 1 ? '<1' : value >= 99.5 && part < whole ? '99' : Math.round(value)}%`
}

function trim(value: number): string {
  return value.toFixed(1).replace(/\.0$/, '')
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function clock(iso: string): string {
  const date = new Date(iso)
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function dayClock(iso: string): string {
  const date = new Date(iso)
  return `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function dayClockSeconds(iso: string): string {
  return `${dayClock(iso)}:${pad(new Date(iso).getSeconds())}`
}

/** 축 눈금이 딱 떨어지게 최댓값을 올림한다(1·2·5 × 10ⁿ). 0 이면 눈금이 서도록 4 를 준다. */
export function niceMax(value: number): number {
  if (value <= 4) return 4
  const power = 10 ** Math.floor(Math.log10(value))
  const scaled = value / power
  return (scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10) * power
}
