import { create } from "zustand"

type ChildSession = {
  childId: string | null
  childName: string | null
}

type ChildSessionStore = ChildSession & {
  startSession: (session: ChildSession) => void
  /** 사용 종료 버튼: 브라우저 상태를 지우고 코드 입력 화면으로 되돌린다 (VS-003 참고). */
  endSession: () => void
}

// 아동 개인정보는 새로고침 시 함께 사라져야 하므로 persist 미들웨어를 쓰지 않는다.
export const useChildSessionStore = create<ChildSessionStore>((set) => ({
  childId: null,
  childName: null,
  startSession: (session) => set(session),
  endSession: () => set({ childId: null, childName: null }),
}))
