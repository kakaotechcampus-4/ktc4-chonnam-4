import { create } from "zustand"

type ChildSession = {
  childId: string | null
  childName: string | null
}

type ChildSessionStore = ChildSession & {
  /**
   * 입장·사용 종료 때마다 1씩 오른다. 요청을 시작할 때 값을 기억해 두고 응답이 왔을 때 비교하면,
   * 그 사이 사용 종료했거나 다른 아동이 들어온 경우 늦게 온 응답으로 화면을 옮기지 않을 수 있다 (#56 FE 전달사항 4).
   */
  sessionVersion: number
  startSession: (session: ChildSession) => void
  /** 사용 종료 버튼: 브라우저 상태를 지우고 코드 입력 화면으로 되돌린다 (VS-003 참고). */
  endSession: () => void
}

// 아동 개인정보는 새로고침 시 함께 사라져야 하므로 persist 미들웨어를 쓰지 않는다.
export const useChildSessionStore = create<ChildSessionStore>((set) => ({
  childId: null,
  childName: null,
  sessionVersion: 0,
  startSession: (session) => set((state) => ({ ...session, sessionVersion: state.sessionVersion + 1 })),
  endSession: () => set((state) => ({ childId: null, childName: null, sessionVersion: state.sessionVersion + 1 })),
}))

/** 지금 세션 버전을 기억하고, 나중에 부르면 그 세션이 아직 그대로인지 알려 주는 함수를 돌려준다. */
export function rememberChildSession() {
  const version = useChildSessionStore.getState().sessionVersion
  return () => useChildSessionStore.getState().sessionVersion === version
}
