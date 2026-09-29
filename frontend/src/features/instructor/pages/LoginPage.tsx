import { useRef, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { hasAccessToken, login } from '../api'
import { Eye, EyeOff } from 'lucide-react'
import { AuthLayout } from '../layout/AuthLayout'
import { cn } from '@/lib/utils'
import {
  errorTextClass,
  fieldLabelClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from '../components/styles'

// 다른 화면이 로그인 화면으로 보낼 때 넘기는 값.
// from: 보호 화면에서 튕겨 온 경로(로그인 뒤 돌아갈 곳), notice·email: 가입 직후 자동 로그인이 실패했을 때의 안내.
type LoginLocationState = { from?: string; notice?: string; email?: string } | null

function LoginPage() {
  const location = useLocation()
  const locationState = location.state as LoginLocationState
  const [email, setEmail] = useState(locationState?.email ?? '')
  const [password, setPassword] = useState('')
  const [isPasswordVisible, setIsPasswordVisible] = useState(false)
  const navigate = useNavigate()
  const from = locationState?.from ?? '/classrooms'
  const notice = locationState?.notice

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
    <AuthLayout title="로그인" description="강사 · 기관 계정으로 로그인하세요">
      {notice && (
        <p
          role="status"
          className="mb-5 rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-3 py-2 text-sm"
        >
          {notice}
        </p>
      )}

      <form onSubmit={handleSubmit} className="flex flex-col gap-5">
        <label htmlFor="login-email" className={fieldLabelClass}>
          이메일
          <input
            id="login-email"
            type="email"
            autoComplete="email"
            placeholder="name@school.ac.kr"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className={inputClass}
          />
        </label>
        <div className="flex flex-col gap-2">
          <label htmlFor="login-password" className="text-sm font-semibold">
            비밀번호
          </label>
          <div className="relative">
            <input
              id="login-password"
              type={isPasswordVisible ? 'text' : 'password'}
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={cn(inputClass, 'pr-11')}
            />
            <button
              type="button"
              onClick={() => setIsPasswordVisible((visible) => !visible)}
              aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 보기'}
              aria-pressed={isPasswordVisible}
              className="absolute inset-y-0 right-0 flex w-11 items-center justify-center text-[var(--instructor-text-muted)]"
            >
              {isPasswordVisible ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
            </button>
          </div>
          {/* 비밀번호 재설정은 S1 범위 밖이라 자리만 둔다. */}
          <span
            aria-disabled="true"
            title="준비 중인 기능입니다"
            className="self-end text-xs text-[var(--instructor-text-disabled)]"
          >
            비밀번호를 잊으셨나요?
          </span>
        </div>

        {loginMutation.isError && (
          <p role="alert" className={errorTextClass}>
            {loginMutation.error instanceof Error
              ? loginMutation.error.message
              : '로그인에 실패했습니다.'}
          </p>
        )}

        <button type="submit" disabled={isSubmitDisabled} className={primaryButtonClass}>
          {loginMutation.isPending ? '로그인 중...' : '로그인'}
        </button>
      </form>

      <div className="my-5 flex items-center gap-3 text-xs text-[var(--instructor-text-disabled)]">
        <span className="h-px flex-1 bg-[var(--instructor-border)]" />
        또는
        <span className="h-px flex-1 bg-[var(--instructor-border)]" />
      </div>
      <button type="button" disabled className={cn(outlineButtonClass, 'w-full')}>
        기관 SSO 로그인 (향후 제공)
      </button>

      <div className="mt-6 text-center text-sm text-[var(--instructor-text-muted)]">
        계정이 없으신가요?{' '}
        <Link to="/signup" className="text-[var(--instructor-text)] underline underline-offset-2">
          가입하기
        </Link>
      </div>
    </AuthLayout>
  )
}

export { LoginPage }
