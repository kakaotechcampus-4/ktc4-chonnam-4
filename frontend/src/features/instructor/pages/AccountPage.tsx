import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getCurrentUser, withdraw } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog'
import { cardClass, dangerButtonClass, inputClass } from '../components/styles'
import { cn } from '@/lib/utils'

/**
 * 내 계정 정보와 회원 탈퇴. 시안이 없어 기존 강사 레이아웃·카드·버튼만 조립한 임시 화면이다.
 *
 * 탈퇴는 아동·학급 삭제와 같은 확인 창을 거치고, 그 창에서 현재 비밀번호를 다시 받는다. 확인 창은 실수로 누르는 일을 막고,
 * 비밀번호는 로그인이 남은 기기를 다른 사람이 쓸 때 탈퇴하지 못하게 막는다.
 */
function AccountPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [isWithdrawOpen, setIsWithdrawOpen] = useState(false)
  const [password, setPassword] = useState('')

  // 사이드바(InstructorLayout)와 같은 키라 이미 받은 값을 그대로 쓴다.
  const { data: user } = useQuery({
    queryKey: ['auth', 'session'],
    queryFn: getCurrentUser,
    staleTime: Infinity,
  })

  const withdrawMutation = useMutation({
    mutationFn: withdraw,
    // 계정이 지워졌으므로 이전 강사의 데이터가 남지 않게 캐시를 모두 비우고 로그인 화면으로 보낸다.
    onSuccess: () => {
      queryClient.clear()
      navigate('/login', { replace: true, state: { notice: '탈퇴가 완료됐습니다.' } })
    },
  })

  return (
    <InstructorLayout title="계정">
      <div className="flex max-w-md flex-col gap-4">
        <section className={cn(cardClass, 'p-6')} aria-labelledby="account-info-heading">
          <h2 id="account-info-heading" className="mb-4 text-base font-bold">
            내 정보
          </h2>
          <dl className="grid grid-cols-[4.5rem_1fr] gap-x-3 gap-y-2.5 text-sm">
            <dt className="text-[var(--instructor-text-muted)]">이름</dt>
            <dd className="break-keep">{user?.name}</dd>
            <dt className="text-[var(--instructor-text-muted)]">이메일</dt>
            <dd className="break-all">{user?.email}</dd>
            <dt className="text-[var(--instructor-text-muted)]">소속</dt>
            <dd className="break-keep">{user?.orgName ?? '-'}</dd>
          </dl>
        </section>

        <section className={cn(cardClass, 'p-6')} aria-labelledby="withdraw-heading">
          <h2 id="withdraw-heading" className="mb-2 text-base font-bold text-[var(--instructor-danger-fg)]">
            회원 탈퇴
          </h2>
          <p className="mb-5 text-sm text-[var(--instructor-text-muted)]">
            탈퇴하면 모든 학급·아동·학습 기록이 바로 삭제되고 복구할 수 없어요.
          </p>
          <div className="flex justify-end">
            <button type="button" onClick={() => setIsWithdrawOpen(true)} className={dangerButtonClass}>
              회원 탈퇴
            </button>
          </div>
        </section>
      </div>

      <DeleteConfirmDialog
        open={isWithdrawOpen}
        onOpenChange={(open) => {
          setIsWithdrawOpen(open)
          if (!open) {
            withdrawMutation.reset()
            setPassword('')
          }
        }}
        title="정말 탈퇴할까요?"
        description={
          <>
            <p>계정과 모든 학급·아동·학습 기록이 바로 삭제돼요.</p>
            <p>삭제하면 되돌릴 수 없어요.</p>
          </>
        }
        actionLabel="회원 탈퇴"
        pendingLabel="탈퇴 중..."
        isPending={withdrawMutation.isPending}
        error={
          withdrawMutation.isError
            ? describeError(withdrawMutation.error, '탈퇴하지 못했습니다. 잠시 후 다시 시도해 주세요.')
            : null
        }
        canConfirm={password.length > 0}
        onConfirm={() => withdrawMutation.mutate(password)}
      >
        <label className="flex flex-col gap-2 text-sm">
          <span>확인을 위해 비밀번호를 입력해 주세요.</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={withdrawMutation.isPending}
            autoComplete="current-password"
            className={inputClass}
          />
        </label>
      </DeleteConfirmDialog>
    </InstructorLayout>
  )
}

export { AccountPage }
