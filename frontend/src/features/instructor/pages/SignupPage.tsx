import { useRef, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { ApiError, hasAccessToken, login, signup } from '../api'
import { Button } from '@/components/ui/button'
import { AuthLayout } from '../layout/AuthLayout'

// 백엔드 가입 규칙(SignupRequest)과 맞춘다. 서버가 최종 검증하고, 여기서는 바로 알 수 있는 실수만 막는다.
const PASSWORD_MIN_LENGTH = 8

const FIELD_LABELS: Record<string, string> = {
  email: '이메일',
  password: '비밀번호',
  passwordWithinByteLimit: '비밀번호',
  name: '이름',
  orgName: '소속 기관',
}

function SignupPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [orgName, setOrgName] = useState('')
  const navigate = useNavigate()

  // 가입에 성공하면 같은 자격으로 바로 로그인해 학급 화면으로 보낸다.
  // 가입 뒤 로그인만 실패하면 계정은 이미 있으므로, 이 화면에서 다시 가입하게 두지 않고(409) 로그인 화면으로 안내한다.
  const signupMutation = useMutation({
    mutationFn: async () => {
      const trimmedEmail = email.trim()
      await signup({
        email: trimmedEmail,
        password,
        name: name.trim(),
        orgName: orgName.trim() === '' ? undefined : orgName.trim(),
      })
      try {
        await login(trimmedEmail, password)
        return { loggedIn: true, email: trimmedEmail }
      } catch {
        return { loggedIn: false, email: trimmedEmail }
      }
    },
    onSuccess: ({ loggedIn, email: signedUpEmail }) => {
      if (loggedIn) {
        navigate('/classrooms', { replace: true })
        return
      }
      navigate('/login', {
        replace: true,
        state: { notice: '가입이 완료됐습니다. 로그인해 주세요.', email: signedUpEmail },
      })
    },
  })

  const isPasswordTooShort = password !== '' && password.length < PASSWORD_MIN_LENGTH
  const isSubmitDisabled =
    signupMutation.isPending ||
    email.trim() === '' ||
    name.trim() === '' ||
    password.length < PASSWORD_MIN_LENGTH
  // isPending 은 다음 렌더부터 반영되어, 같은 순간 들어온 두 번째 제출은 통과할 수 있다. ref 로 즉시 잠근다.
  const isSubmittingRef = useRef(false)

  // 이미 로그인한 채로 들어온 경우만 넘긴다. 가입 직후에는 onSuccess 의 이동이 처리한다.
  if (hasAccessToken() && signupMutation.isIdle) {
    return <Navigate to="/classrooms" replace />
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (isSubmitDisabled || isSubmittingRef.current) {
      return
    }
    isSubmittingRef.current = true
    signupMutation.mutate(undefined, {
      onSettled: () => {
        isSubmittingRef.current = false
      },
    })
  }

  const error = signupMutation.error
  const fieldErrors = error instanceof ApiError ? error.fieldErrors : []

  return (
    <AuthLayout title="강사 가입">
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <label htmlFor="signup-email" className="flex flex-col gap-1 text-sm">
          이메일
          <input
            id="signup-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="rounded-lg border border-border px-2.5 py-1.5 text-sm"
          />
        </label>
        <label htmlFor="signup-password" className="flex flex-col gap-1 text-sm">
          비밀번호 ({PASSWORD_MIN_LENGTH}자 이상)
          <input
            id="signup-password"
            type="password"
            autoComplete="new-password"
            aria-describedby={isPasswordTooShort ? 'signup-password-hint' : undefined}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="rounded-lg border border-border px-2.5 py-1.5 text-sm"
          />
        </label>
        {isPasswordTooShort && (
          <p id="signup-password-hint" className="-mt-2 text-xs text-red-600">
            비밀번호는 {PASSWORD_MIN_LENGTH}자 이상이어야 합니다.
          </p>
        )}
        <label htmlFor="signup-name" className="flex flex-col gap-1 text-sm">
          이름
          <input
            id="signup-name"
            autoComplete="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="rounded-lg border border-border px-2.5 py-1.5 text-sm"
          />
        </label>
        <label htmlFor="signup-org" className="flex flex-col gap-1 text-sm">
          소속 기관 (선택)
          <input
            id="signup-org"
            autoComplete="organization"
            value={orgName}
            onChange={(e) => setOrgName(e.target.value)}
            className="rounded-lg border border-border px-2.5 py-1.5 text-sm"
          />
        </label>

        {signupMutation.isError && (
          <div role="alert" className="text-sm text-red-600">
            <p>{error instanceof Error ? error.message : '가입에 실패했습니다.'}</p>
            {fieldErrors.length > 0 && (
              <ul className="mt-1 list-disc pl-5">
                {fieldErrors.map((fieldError) => (
                  <li key={`${fieldError.field}-${fieldError.message}`}>
                    {FIELD_LABELS[fieldError.field] ?? fieldError.field}: {fieldError.message}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        <Button type="submit" size="lg" disabled={isSubmitDisabled}>
          {signupMutation.isPending ? '가입 중...' : '가입하기'}
        </Button>
      </form>

      <p className="mt-4 text-center text-sm">
        이미 계정이 있나요?{' '}
        <Link to="/login" className="underline">
          로그인
        </Link>
      </p>
    </AuthLayout>
  )
}

export { SignupPage }
