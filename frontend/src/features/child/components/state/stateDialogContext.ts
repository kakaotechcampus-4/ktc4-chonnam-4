import { createContext } from "react"

/** StateDialog 안에 있는지 여부. 모달 안에서는 제목·설명을 모달 제목·설명으로 연결한다. */
export const StateDialogContext = createContext(false)
