import { useEffect } from "react"
import { useChildSessionStore } from "../store/childSessionStore"

/** 세션이 활성 상태일 때 새로고침/닫기를 시도하면 브라우저 확인창을 띄운다. */
export function useWarnBeforeUnload() {
  const hasSession = useChildSessionStore((state) => state.childName !== null)

  useEffect(() => {
    if (!hasSession) return

    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ""
    }

    window.addEventListener("beforeunload", handleBeforeUnload)
    return () => window.removeEventListener("beforeunload", handleBeforeUnload)
  }, [hasSession])
}
