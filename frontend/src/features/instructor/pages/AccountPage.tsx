import { useState, type CSSProperties } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { getCurrentUser, listChildren, listClassrooms, withdraw } from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog'
import { ButtonLabel } from '../components/ButtonLabel'
import { useLogout } from '../layout/useLogout'
import {
  cardClass,
  dangerOutlineButtonClass,
  helperTextClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from '../components/styles'
import { cn } from '@/lib/utils'

/**
 * 설정 화면(시안 T-SET-01~03). 배치는 와이어프레임, 모양은 디자인 시스템을 따른다.
 * 지금 동작하는 것은 내 정보 보기·로그아웃·회원 탈퇴뿐이다. 시안에 있지만 아직 없는 기능(사진·정보·비밀번호 변경,
 * 개인정보 요청, 접근성 기본값)은 자리를 그대로 두고 비활성으로 보이며, 카드마다 준비 중이라고 알린다. 회원 탈퇴는 시안에 없어
 * 로그아웃 카드와 같은 모양으로 그 아래에 둔다.
 *
 * 탈퇴는 아동·학급 삭제와 같은 확인 창을 거치고, 그 창에서 현재 비밀번호를 다시 받는다. 확인 창은 실수로 누르는 일을 막고,
 * 비밀번호는 로그인이 남은 기기를 다른 사람이 쓸 때 탈퇴하지 못하게 막는다.
 * 아동 삭제 창처럼 함께 지워지는 학급 수·아동 수를 알린다. 다 세기 전이거나 세지 못하면 일반 안내를 보인다.
 */
function AccountPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const logoutMutation = useLogout()
  const [isWithdrawOpen, setIsWithdrawOpen] = useState(false)
  const [password, setPassword] = useState('')

  // 사이드바(InstructorLayout)와 같은 키라 이미 받은 값을 그대로 쓴다.
  const { data: user } = useQuery({
    queryKey: ['auth', 'session'],
    queryFn: getCurrentUser,
    staleTime: Infinity,
  })

  // 확인 창을 열 때만 센다. 학급 목록·학급별 아동 목록은 다른 화면과 같은 키라 받아 둔 값이 있으면 그대로 쓴다.
  const classroomsQuery = useQuery({
    queryKey: ['classrooms'],
    queryFn: listClassrooms,
    enabled: isWithdrawOpen,
  })
  const childCount = useQueries({
    queries: (classroomsQuery.data ?? []).map((classroom) => ({
      queryKey: ['classrooms', classroom.classId, 'children'],
      queryFn: () => listChildren(classroom.classId),
      enabled: isWithdrawOpen,
    })),
    combine: (results) =>
      results.every((result) => result.isSuccess)
        ? results.reduce((sum, result) => sum + (result.data?.length ?? 0), 0)
        : undefined,
  })
  const classroomCount = classroomsQuery.isSuccess ? classroomsQuery.data.length : undefined

  const withdrawMutation = useMutation({
    mutationFn: withdraw,
    // 계정이 지워졌으므로 이전 강사의 데이터가 남지 않게 캐시를 모두 비우고 로그인 화면으로 보낸다.
    onSuccess: () => {
      queryClient.clear()
      navigate('/login', { replace: true, state: { notice: '탈퇴가 완료됐습니다.' } })
    },
  })

  return (
    <InstructorLayout title="설정">
      {/* 시안 T-SET-01~03 은 세 탭의 카드를 한 화면 두 칸에 모두 보인다. 탭이 내용을 바꾸지 않아 탭 띠는 두지 않았다. */}
      <div className="grid grid-cols-2 items-start gap-5">
        <div className="flex flex-col gap-4">
          <section className={cn(cardClass, 'flex flex-col gap-2 p-6')} aria-labelledby="profile-heading">
            <h2 id="profile-heading" className="text-sm font-semibold">
              프로필 편집
            </h2>
            <div className="flex items-center gap-4 pt-3">
              <span className="size-14 rounded-full bg-[var(--instructor-avatar)]" aria-hidden />
              <button type="button" disabled className={outlineButtonClass}>
                사진 변경
              </button>
            </div>
            <ReadonlyField label="이름" value={user?.name} />
            <ReadonlyField label="이메일" value={user?.email} />
            <ReadonlyField label="소속 기관" value={user?.orgName ?? '—'} />
            <div className="flex gap-2.5 pt-3">
              <button type="button" disabled className={cn(primaryButtonClass, 'flex-1')}>
                저장
              </button>
              <button type="button" disabled className={cn(outlineButtonClass, 'w-[132px]')}>
                비밀번호 변경
              </button>
            </div>
            <p className={cn(helperTextClass, 'pt-1')}>사진 변경, 정보 수정, 비밀번호 변경은 준비 중이에요.</p>
          </section>

          <section className={cn(cardClass, 'flex items-center justify-between gap-4 px-6 py-5')} aria-labelledby="logout-heading">
            <div className="flex flex-col gap-1">
              <h2 id="logout-heading" className="text-sm font-semibold">
                로그아웃
              </h2>
              <p className={helperTextClass}>이 기기에서 계정을 해제합니다.</p>
            </div>
            <button
              type="button"
              onClick={() => logoutMutation.mutate()}
              disabled={logoutMutation.isPending}
              className={outlineButtonClass}
            >
              <ButtonLabel pending={logoutMutation.isPending} label="로그아웃" pendingLabel="로그아웃 중..." />
            </button>
          </section>

          <section className={cn(cardClass, 'flex items-center justify-between gap-4 px-6 py-5')} aria-labelledby="withdraw-heading">
            <div className="flex flex-col gap-1">
              <h2 id="withdraw-heading" className="text-sm font-semibold text-[var(--instructor-danger-fg)]">
                회원 탈퇴
              </h2>
              <p className={helperTextClass}>모든 학급·아동·학습 기록이 바로 삭제되고 복구할 수 없어요.</p>
            </div>
            <button type="button" onClick={() => setIsWithdrawOpen(true)} className={dangerOutlineButtonClass}>
              회원 탈퇴
            </button>
          </section>
        </div>

        <div className="flex flex-col gap-4">
          <section className={cn(cardClass, 'flex flex-col gap-2 p-6')} aria-labelledby="privacy-heading">
            <h2 id="privacy-heading" className="text-sm font-semibold">
              개인정보 · 데이터 요청
            </h2>
            <p className={helperTextClass}>처리 결과는 이메일로 안내됩니다. 요청 접수는 준비 중이에요.</p>
            <ul className="flex flex-col gap-3 pt-3">
              <RequestRow title="열람 요청" description="보관 중인 항목과 목적을 확인합니다." />
              <RequestRow title="수정 요청" description="잘못된 정보를 정정합니다." />
              <RequestRow title="삭제 요청" description="삭제 후에는 리포트를 복구할 수 없습니다." danger />
            </ul>
            <p className="pt-2 text-xs text-[var(--instructor-text-muted)]">처리 현황 · —</p>
          </section>

          <section className={cn(cardClass, 'flex flex-col p-6')} aria-labelledby="a11y-heading">
            <h2 id="a11y-heading" className="text-sm font-semibold">
              접근성 기본값
            </h2>
            <p className={cn(helperTextClass, 'pt-2')}>아동 화면에 적용되는 초기값입니다. 설정은 준비 중이에요.</p>
            <SliderRow label="글자 크기" valueText="크게 (120%)" value={66} marks={['보통', '크게', '아주 크게']} />
            <SliderRow label="음량 기본값" valueText="70%" value={70} />
            <SwitchRow label="음성 안내 자동 재생" checked />
            <SwitchRow label="애니메이션 줄이기" checked={false} />
          </section>
        </div>
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
            <p>{describeOwned(classroomCount, childCount)}</p>
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
        <label className="mt-2 flex flex-col gap-2 text-sm">
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

/** 바꿀 수 없는 값 칸. 수정 기능이 생기면 이름 칸부터 입력칸으로 바꾼다. */
function ReadonlyField({ label, value }: { label: string; value: string | undefined }) {
  return (
    <div className="flex flex-col gap-2 pt-2">
      <span className="text-xs text-[var(--instructor-text-muted)]">{label}</span>
      <span className="flex min-h-[42px] items-center rounded-[var(--instructor-radius-control)] border border-[var(--instructor-input-border)] bg-[var(--instructor-disabled-bg)] px-3 text-sm break-all">
        {value}
      </span>
    </div>
  )
}

function RequestRow({ title, description, danger = false }: { title: string; description: string; danger?: boolean }) {
  return (
    <li className="flex items-center justify-between gap-4 rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border-subtle)] px-4 py-4">
      <div className="flex flex-col gap-0.5">
        <span className={cn('text-xs font-semibold', danger && 'text-[var(--instructor-danger-fg)]')}>{title}</span>
        <span className={helperTextClass}>{description}</span>
      </div>
      <button type="button" disabled aria-label={`${title} 신청`} className={danger ? dangerOutlineButtonClass : outlineButtonClass}>
        신청
      </button>
    </li>
  )
}

// 시안의 슬라이더 자리. 아직 바꿀 수 없어 비활성으로 둔다. 모양은 index.css 의 강사 range 규칙을 따른다.
function SliderRow({ label, valueText, value, marks }: { label: string; valueText: string; value: number; marks?: string[] }) {
  return (
    <label className="flex flex-col gap-3 pt-5 text-xs">
      <span className="flex justify-between">
        <span className="text-[var(--instructor-text)]">{label}</span>
        <span className="text-[var(--instructor-text-muted)]">{valueText}</span>
      </span>
      <input
        type="range"
        disabled
        value={value}
        readOnly
        aria-valuetext={valueText}
        style={{ '--fill': `${value}%` } as CSSProperties}
        className="w-full"
      />
      {marks && (
        <span className="flex justify-between text-[var(--instructor-text-muted)]" aria-hidden>
          {marks.map((mark) => (
            <span key={mark}>{mark}</span>
          ))}
        </span>
      )}
    </label>
  )
}

// 디자인 시스템 Switch: 46×24 트랙, 안쪽 3px, 18px 손잡이, 켜짐은 Primary. 꺼짐 색은 시안에 없어 빈 체크박스 테두리색을 쓴다.
function SwitchRow({ label, checked }: { label: string; checked: boolean }) {
  return (
    <div className="mt-4 flex items-center justify-between border-t border-[var(--instructor-border-subtle)] pt-3 text-xs">
      <span aria-hidden>{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        disabled
        className={cn(
          'flex h-6 w-[46px] shrink-0 items-center rounded-full px-[3px] disabled:cursor-not-allowed',
          checked
            ? 'justify-end bg-[var(--instructor-primary)] disabled:bg-[var(--instructor-text-disabled)]'
            : 'bg-[var(--instructor-border-strong)] disabled:bg-[var(--instructor-disabled-bg)]',
        )}
      >
        <span className="size-[18px] rounded-full bg-[var(--instructor-surface)]" />
      </button>
    </div>
  )
}

function describeOwned(classroomCount: number | undefined, childCount: number | undefined): string {
  if (classroomCount === undefined || childCount === undefined) return '계정과 모든 학급·아동·학습 기록이 바로 삭제돼요.'
  if (classroomCount === 0) return '만든 학급은 없어요. 계정만 삭제돼요.'
  return `학급 ${classroomCount}개, 아동 ${childCount}명과 그 아동들의 학습 기록이 계정과 함께 바로 삭제돼요.`
}

export { AccountPage }
