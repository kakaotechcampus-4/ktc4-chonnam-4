import { createContext } from 'react'
import type { AgentRunOverview } from '../api'

/** 화면 전체의 조회 기간 이름(예: "최근 24시간"). 패널 머리의 기간 표시가 읽는다. */
export const PeriodContext = createContext<string | null>(null)

/** 시간 구간 하나의 실행 수(흐름 종류 네 가지의 합). */
export function runsIn(bucket: AgentRunOverview['series'][number]): number {
  return bucket.normal + bucket.retried + bucket.guided + bucket.failed
}
