import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { hasAccessToken } from '../api'

/**
 * 토큰이 없으면 로그인 화면으로 보낸다. 로그인 뒤 원래 보던 화면으로 돌아오도록 경로를 넘긴다.
 * 토큰이 만료됐는지는 알 수 없으므로, 그 경우는 첫 API 요청의 401 처리(api.ts)가 로그인 화면으로 보낸다.
 */
function RequireInstructorAuth({ children }: { children: ReactNode }) {
  const location = useLocation()

  if (!hasAccessToken()) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return children
}

export { RequireInstructorAuth }
