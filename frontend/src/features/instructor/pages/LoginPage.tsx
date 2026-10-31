import { useRef, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { hasAccessToken, login } from '../api'
import { MaterialIcon } from '../components/MaterialIcon'
import { AuthLayout } from '../layout/AuthLayout'
import { cn } from '@/lib/utils'
import {
  fieldLabelClass,
  hitAreaClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from '../components/styles'
import { Notice } from '../components/Feedback'
import { ButtonLabel } from '../components/ButtonLabel'

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
        <Notice tone="info" role="status" className="mb-5">
          {notice}
        </Notice>
      )}

      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
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
          <label htmlFor="login-password" className="text-sm">
            비밀번호
          </label>
          <div className="relative">
            <input
              id="login-password"
              type={isPasswordVisible ? 'text' : 'password'}
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={cn(inputClass, 'pr-12')}
            />
            <button
              type="button"
              onClick={() => setIsPasswordVisible((visible) => !visible)}
              aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 보기'}
              aria-pressed={isPasswordVisible}
              className={cn('absolute inset-y-0 right-0 flex w-11 items-center justify-center text-[var(--instructor-icon-subtle)]', hitAreaClass)}
            >
              <MaterialIcon name={isPasswordVisible ? 'visibility_off' : 'visibility'} className="size-5" />
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
          <Notice tone="error" role="alert">
            {loginMutation.error instanceof Error
              ? loginMutation.error.message
              : '로그인에 실패했습니다.'}
          </Notice>
        )}

        <button type="submit" disabled={isSubmitDisabled} className={primaryButtonClass}>
          <ButtonLabel pending={loginMutation.isPending} label="로그인" pendingLabel="로그인 중..." />
        </button>
      </form>

      <button type="button" disabled className={cn(outlineButtonClass, 'mt-2 w-full')}>
        기관 계정으로 로그인 (향후 제공)
      </button>

      <div className="mt-6 border-t border-[var(--instructor-border-subtle)] pt-4 text-center text-xs text-[var(--instructor-text-subtle)]">
        계정이 없으신가요?{' '}
        <Link to="/signup" className="text-[var(--instructor-link)] underline underline-offset-2">
          가입하기
        </Link>
      </div>
    </AuthLayout>
  )
}

export { LoginPage }
