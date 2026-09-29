import { useRef, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { ApiError, hasAccessToken, login, signup } from '../api'
import { AuthLayout } from '../layout/AuthLayout'
import { errorTextClass, fieldLabelClass, inputClass, primaryButtonClass } from '../components/styles'

// 백엔드 가입 규칙(SignupRequest)과 맞춘다. 서버가 최종 검증하고, 여기서는 바로 알 수 있는 실수만 막는다.
const PASSWORD_MIN_LENGTH = 8

const FIELD_LABELS: Record<string, string> = {
  email: '이메일',
  password: '비밀번호',
  passwordWithinByteLimit: '비밀번호',
  name: '이름',
}

// 필수 약관 동의. 서버 기록(버전·시각)은 S3 범위라 지금은 가입 버튼을 여는 조건으로만 쓴다.
type Agreements = { terms: boolean; privacy: boolean }

const AGREEMENT_LABELS: Record<keyof Agreements, string> = {
  terms: '[필수] 서비스 이용약관 동의',
  privacy: '[필수] 개인정보 처리방침 동의',
}

/** 필수 항목이 모두 체크되어 "전체 동의"가 체크된 것으로 보일지 정한다. 선택 항목이 생기면 여기서 제외한다. */
function isAllAgreed(agreements: Agreements): boolean {
  return agreements.terms && agreements.privacy
}

/** "전체 동의"를 눌렀을 때 바뀐 뒤의 동의 상태. 일부만 체크된 상태에서 누르면 모두 체크한다. */
function toggleAllAgreements(agreements: Agreements): Agreements {
  const next = !isAllAgreed(agreements)
  return { terms: next, privacy: next }
}

function SignupPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [name, setName] = useState('')
  const [agreements, setAgreements] = useState<Agreements>({ terms: false, privacy: false })
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
  const isPasswordMismatch = passwordConfirm !== '' && passwordConfirm !== password
  const isSubmitDisabled =
    signupMutation.isPending ||
    email.trim() === '' ||
    name.trim() === '' ||
    password.length < PASSWORD_MIN_LENGTH ||
    passwordConfirm !== password ||
    !isAllAgreed(agreements)
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
    <AuthLayout title="강사 가입" description="기본 정보를 입력해 계정을 만드세요">
      <form onSubmit={handleSubmit} className="flex flex-col gap-5">
        <label htmlFor="signup-name" className={fieldLabelClass}>
          이름
          <input
            id="signup-name"
            autoComplete="name"
            placeholder="홍길동"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className={inputClass}
          />
        </label>
        <label htmlFor="signup-email" className={fieldLabelClass}>
          이메일
          <input
            id="signup-email"
            type="email"
            autoComplete="email"
            placeholder="name@school.ac.kr"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className={inputClass}
          />
        </label>
        <div className="flex flex-col gap-2">
          <div className="grid grid-cols-2 gap-3">
            <label htmlFor="signup-password" className={fieldLabelClass}>
              비밀번호
              <input
                id="signup-password"
                type="password"
                autoComplete="new-password"
                aria-describedby="signup-password-rule"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={inputClass}
              />
            </label>
            <label htmlFor="signup-password-confirm" className={fieldLabelClass}>
              비밀번호 확인
              <input
                id="signup-password-confirm"
                type="password"
                autoComplete="new-password"
                aria-describedby={isPasswordMismatch ? 'signup-password-mismatch' : undefined}
                value={passwordConfirm}
                onChange={(e) => setPasswordConfirm(e.target.value)}
                className={inputClass}
              />
            </label>
          </div>
          <p
            id="signup-password-rule"
            className={isPasswordTooShort ? 'text-xs text-[var(--instructor-danger-fg)]' : 'text-xs text-[var(--instructor-text-muted)]'}
          >
            비밀번호는 {PASSWORD_MIN_LENGTH}자 이상이어야 합니다.
          </p>
          {isPasswordMismatch && (
            <p id="signup-password-mismatch" className="text-xs text-[var(--instructor-danger-fg)]">
              비밀번호가 서로 다릅니다.
            </p>
          )}
        </div>

        <fieldset className="flex flex-col gap-3 border-t border-[var(--instructor-border)] pt-5">
          <legend className="sr-only">약관 동의</legend>
          <label className="flex items-center gap-2.5 text-sm font-semibold">
            <input
              type="checkbox"
              checked={isAllAgreed(agreements)}
              onChange={() => setAgreements(toggleAllAgreements)}
              className="size-4 accent-[var(--instructor-primary)]"
            />
            전체 동의
          </label>
          {(Object.keys(AGREEMENT_LABELS) as (keyof Agreements)[]).map((key) => (
            <label key={key} className="flex items-center gap-2.5 pl-0.5 text-sm text-[var(--instructor-text-muted)]">
              <input
                type="checkbox"
                checked={agreements[key]}
                onChange={(e) => setAgreements((prev) => ({ ...prev, [key]: e.target.checked }))}
                className="size-4 accent-[var(--instructor-primary)]"
              />
              {AGREEMENT_LABELS[key]}
            </label>
          ))}
        </fieldset>

        {signupMutation.isError && (
          <div role="alert" className={errorTextClass}>
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

        <button type="submit" disabled={isSubmitDisabled} className={primaryButtonClass}>
          {signupMutation.isPending ? '가입 중...' : '가입하기'}
        </button>
      </form>

      <div className="mt-6 text-center text-sm text-[var(--instructor-text-muted)]">
        이미 계정이 있으신가요?{' '}
        <Link to="/login" className="text-[var(--instructor-text)] underline underline-offset-2">
          로그인
        </Link>
      </div>
    </AuthLayout>
  )
}

export { SignupPage }
