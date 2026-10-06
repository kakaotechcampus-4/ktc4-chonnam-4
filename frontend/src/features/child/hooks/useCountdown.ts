import { useEffect, useRef, useState } from "react"

const TICK_MS = 100

/**
 * 화면에 올라온 순간부터 durationMs 동안 줄어드는 남은 시간(ms)을 돌려준다.
 * 매번 시작 시각과 비교해 계산하므로, 탭을 옮겼다 돌아와 타이머가 늦게 불려도 시간이 밀리지 않는다.
 * 시간이 다 되면 onExpire를 한 번 부른다.
 */
function useCountdown(durationMs: number, onExpire: () => void) {
  const [remainingMs, setRemainingMs] = useState(durationMs)
  const onExpireRef = useRef(onExpire)

  useEffect(() => {
    onExpireRef.current = onExpire
  }, [onExpire])

  useEffect(() => {
    const startedAt = Date.now()
    const timer = setInterval(() => {
      const left = Math.max(0, durationMs - (Date.now() - startedAt))
      setRemainingMs(left)
      if (left === 0) {
        clearInterval(timer)
        onExpireRef.current()
      }
    }, TICK_MS)
    return () => clearInterval(timer)
  }, [durationMs])

  return remainingMs
}

export { useCountdown }
