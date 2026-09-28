import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import './index.css'
import { router } from './router.tsx'
import { ApiError } from './features/instructor/api'

const MAX_QUERY_RETRIES = 3

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 4xx(없는·권한 밖 리소스, 인증 만료, 검증 실패)는 다시 보내도 결과가 같다. 기본 재시도(최대 3회, 약 7초)를
      // 기다리지 않고 바로 오류를 보여 준다. 네트워크 오류·5xx 는 일시적일 수 있어 그대로 재시도한다.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) &&
        failureCount < MAX_QUERY_RETRIES,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
