import { useRef, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { hasAccessToken, login } from '../api'
import { Button } from '@/components/ui/button'
import { AuthLayout } from '../layout/AuthLayout'

function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const navigate = useNavigate()
  const location = useLocation()
  // 보호 화면에서 튕겨 왔으면 로그인 뒤 그 화면으로 돌려보낸다.
  const from = (location.state as { from?: string } | null)?.from ?? '/classrooms'

  const loginMutation = useMutation({
    mutationFn: () => login(email.trim(), password),
    onSuccess: () => navigate(from, { replace: true }),
  })

  const isSubmitDisabled = loginMutation.isPending || email.trim() === '' || password === ''
  // isPending 은 다음 렌더부터 반영되어, 같은 순간 들어온 두 번째 제출은 통과할 수 있다. ref 로 즉시 잠근다.
  const isSubmittingRef = useRef(false)

  // 이미 로그인한 채로 들어온 경우만 넘긴다. 로그인 직후에는 onSuccess 의 이동(from)이 처리한다.
  if (hasAccessToken() && loginMutation.isIdle) {
    return <Navigate to="/classrooms" replace />
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (isSubmitDisabled || isSubmittingRef.current) {
      return
    }
    isSubmittingRef.current = true
    loginMutation.mutate(undefined, {
      onSettled: () => {
        isSubmittingRef.current = false
      },
    })
  }

  return (
    <AuthLayout title="로그인">
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <label htmlFor="login-email" className="flex flex-col gap-1 text-sm">
          이메일
          <input
            id="login-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="rounded-lg border border-border px-2.5 py-1.5 text-sm"
          />
        </label>
        <label htmlFor="login-password" className="flex flex-col gap-1 text-sm">
          비밀번호
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="rounded-lg border border-border px-2.5 py-1.5 text-sm"
          />
        </label>

        {loginMutation.isError && (
          <p role="alert" className="text-sm text-red-600">
            {loginMutation.error instanceof Error
              ? loginMutation.error.message
              : '로그인에 실패했습니다.'}
          </p>
        )}

        <Button type="submit" size="lg" disabled={isSubmitDisabled}>
          {loginMutation.isPending ? '로그인 중...' : '로그인'}
        </Button>
      </form>

      <p className="mt-4 text-center text-sm">
        계정이 없나요?{' '}
        <Link to="/signup" className="underline">
          가입하기
        </Link>
      </p>
    </AuthLayout>
  )
}

export { LoginPage }
