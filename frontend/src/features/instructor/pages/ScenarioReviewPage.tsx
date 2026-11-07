import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useBlocker, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  NETWORK_ERROR,
  cancelScenarioReview,
  confirmScenarioReview,
  prepareScenarioReview,
  type ScenarioConfirmation,
  type ScenarioEdits,
  type ScenarioReview,
  type ScenarioReviewAction,
} from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ApprovalConfirmPanel } from '../components/ApprovalConfirmPanel'
import { OpeningPrompts, ReviewConditionsCard, ScenarioSections } from '../components/ScenarioContent'
import { ScenarioEditView } from '../components/ScenarioEditView'
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog'
import { ButtonLabel } from '../components/ButtonLabel'
import { Notice } from '../components/Feedback'
import { cardClass, dangerOutlineButtonClass, outlineButtonClass, primaryButtonClass } from '../components/styles'
import { describeError } from '../errorMessage'
import {
  applyEdits,
  describeEdits,
  formProblems,
  toEdits,
  toForm,
  type ScenarioForm,
} from '../lib/scenarioEdits'
import { cn } from '@/lib/utils'

const linkClass = 'font-semibold underline underline-offset-2'

type View = 'review' | 'edit' | 'confirm'

// 서버에서 검토가 바뀌었거나 끝났다. 새 키로 START_OR_RESUME 를 보내 지금 상태를 다시 불러온다.
const RESTORE_CODES = new Set([
  'SCENARIO_REVIEW_CHANGED',
  'SCENARIO_REVIEW_CLOSED',
  'SCENARIO_REVIEW_NOT_READY',
  'SCENARIO_CANDIDATE_INVALID',
])

// 본문이 따로 안내·복구하는 오류. 확인창에서 나면 창을 닫아 그 안내가 가려지지 않게 한다.
const BODY_CODES = new Set([
  ...RESTORE_CODES,
  'SCENARIO_REVIEW_INPUT_CHANGED',
  'SCENARIO_ALREADY_ASSIGNED',
  'INITIAL_DIFFICULTY_NOT_READY',
])

function isBodyError(error: unknown): boolean {
  return error instanceof ApiError && BODY_CODES.has(error.code)
}

function hasCode(error: unknown, code: string): boolean {
  return error instanceof ApiError && error.code === code
}

/** 서버가 요청을 처리했는지 알 수 없는 실패. 같은 키·본문으로 다시 보내야 두 번 처리되지 않는다. */
function isRetryable(error: unknown): boolean {
  return error instanceof ApiError && (error.code === NETWORK_ERROR || error.status >= 500)
}

/**
 * 동작마다 Idempotency-Key 를 만든다. 같은 본문을 다시 보내는 재시도면 같은 키를 쓰고,
 * 성공했거나 다시 보내도 소용없는 실패면 다음 요청에 새 키를 쓴다.
 */
function useRequestKeys() {
  const last = useRef<{ signature: string; key: string } | null>(null)
  return useMemo(
    () => ({
      keyFor(body: unknown) {
        const signature = JSON.stringify(body)
        if (last.current?.signature !== signature) last.current = { signature, key: crypto.randomUUID() }
        return last.current.key
      },
      settle(error: unknown) {
        if (!isRetryable(error)) last.current = null
      },
    }),
    [],
  )
}

/**
 * 시나리오 검토(시안 T-SCN-02 → 03 수정 → 04 승인 확인). 최초 결합·역할극 단독 활동에 쓸 시나리오를 하나씩 보고 고르거나 고친다.
 * 역할극 단독은 배정 때 고정한 다음 역할극 난이도가 검토 조건의 레벨이다.
 * 검토 상태(후보 순서·지금 위치)는 서버에 남아 새로고침·재로그인 뒤에도 이어 보고, 화면에서 고친 내용은 승인 전까지 남지 않는다.
 * 맞는 후보가 없거나 다 넘기면 같은 요청 안에서 새로 만든다.
 */
function ScenarioReviewPage() {
  const { activityId } = useParams<{ activityId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const keys = useRequestKeys()
  // [검토 그만하기]로 나가는 이동은 고친 내용 경고 없이 보낸다.
  const leavingRef = useRef(false)

  const [review, setReview] = useState<ScenarioReview | null>(null)
  const [view, setView] = useState<View>('review')
  const [form, setForm] = useState<ScenarioForm | null>(null)
  // [승인으로 이동]을 누른 순간의 고친 내용. 확인 화면과 승인 요청이 이 하나를 같이 쓴다.
  const [confirmingEdits, setConfirmingEdits] = useState<ScenarioEdits | undefined>(undefined)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [discardNextOpen, setDiscardNextOpen] = useState(false)

  const scenario = review?.scenario ?? null
  const edits = scenario && form ? toEdits(scenario, form) : undefined
  const problems = form ? formProblems(form) : []

  function showReview(next: ScenarioReview) {
    // 늦게 도착한 낮은 번호의 응답은 버린다.
    if (review && review.reviewId === next.reviewId && next.revision < review.revision) return
    setReview(next)
    setForm(toForm(next.scenario))
    setConfirmingEdits(undefined)
    setView('review')
  }

  function afterClose() {
    void queryClient.invalidateQueries({ queryKey: ['scenario-approvals'] })
    void queryClient.invalidateQueries({ queryKey: ['activities', activityId] })
  }

  const prepare = useMutation({
    mutationFn: (action: ScenarioReviewAction) => prepareScenarioReview(activityId!, action, keys.keyFor(action)),
    onSuccess: (next) => {
      setDiscardNextOpen(false)
      showReview(next)
      void queryClient.invalidateQueries({ queryKey: ['scenario-approvals'] })
    },
    onError: (error) => {
      if (isBodyError(error)) setDiscardNextOpen(false)
    },
    onSettled: (_data, error) => keys.settle(error),
  })
  const confirm = useMutation({
    mutationFn: (confirmation: ScenarioConfirmation) =>
      confirmScenarioReview(activityId!, confirmation, keys.keyFor({ confirm: confirmation })),
    onSuccess: afterClose,
    onSettled: (_data, error) => keys.settle(error),
  })
  const cancel = useMutation({
    mutationFn: (target: { reviewId: string; revision: number }) =>
      cancelScenarioReview(activityId!, target, keys.keyFor({ cancel: target })),
    onSuccess: () => {
      afterClose()
      leavingRef.current = true
      navigate('/content')
    },
    onError: (error) => {
      if (isBodyError(error)) setCancelOpen(false)
    },
    onSettled: (_data, error) => keys.settle(error),
  })

  // 화면을 열면 검토를 시작하거나 이어 본다. 개발 모드의 두 번 실행에도 한 번만 보낸다.
  const startedRef = useRef(false)
  const startReview = prepare.mutate
  useEffect(() => {
    if (!activityId || startedRef.current) return
    startedRef.current = true
    startReview({ action: 'START_OR_RESUME' })
  }, [activityId, startReview])

  // 고친 내용이 있는 채로 떠나면 사라진다고 알린다.
  const dirty = edits !== undefined && !confirm.isSuccess
  const blocker = useBlocker(() => dirty && !leavingRef.current)
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const pending = prepare.isPending || confirm.isPending || cancel.isPending
  const lastError = prepare.error ?? confirm.error ?? cancel.error
  const needsRestore = [prepare.error, confirm.error, cancel.error].some(
    (error) => error instanceof ApiError && RESTORE_CODES.has(error.code),
  )

  function restore() {
    confirm.reset()
    cancel.reset()
    prepare.mutate({ action: 'START_OR_RESUME' })
  }

  function next() {
    if (!review) return
    confirm.reset()
    prepare.mutate({ action: 'NEXT', reviewId: review.reviewId, revision: review.revision })
  }

  function requestNext() {
    if (edits) setDiscardNextOpen(true)
    else next()
  }

  function goConfirm() {
    setConfirmingEdits(edits)
    confirm.reset()
    setView('confirm')
  }

  function approve() {
    if (!review) return
    confirm.mutate({
      reviewId: review.reviewId,
      revision: review.revision,
      scenarioId: review.scenario.scenarioId,
      scenarioVersion: review.scenario.scenarioVersion,
      ...(confirmingEdits ? { edits: confirmingEdits } : {}),
    })
  }

  const actions =
    scenario && view === 'review' && !confirm.isSuccess ? (
      <>
        <button type="button" disabled={pending} onClick={() => setCancelOpen(true)} className={dangerOutlineButtonClass}>
          검토 그만하기
        </button>
        <button type="button" disabled={pending} onClick={requestNext} className={outlineButtonClass}>
          <ButtonLabel pending={prepare.isPending} label="다른 후보" pendingLabel="준비하는 중..." />
        </button>
        <button type="button" disabled={pending} onClick={() => setView('edit')} className={outlineButtonClass}>
          수정하기
        </button>
        <button type="button" disabled={pending} onClick={goConfirm} className={primaryButtonClass}>
          승인으로 이동
        </button>
      </>
    ) : undefined

  return (
    <InstructorLayout title={view === 'edit' ? '시나리오 수정' : '시나리오 검토'} actions={actions}>
      <nav aria-label="현재 위치" className="mb-4 text-xs text-[var(--instructor-text-subtle)]">
        <Link to="/content" className="hover:underline">
          승인 대기함
        </Link>
      </nav>

      {renderBody()}

      <DeleteConfirmDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        title="이번 검토를 그만할까요?"
        description={
          <p>
            지금까지 넘긴 후보와 고친 내용은 사라지고, 활동은 계속 시나리오를 기다립니다. 승인된 시나리오는 그대로 남습니다.
          </p>
        }
        actionLabel="검토 그만하기"
        pendingLabel="그만하는 중..."
        isPending={cancel.isPending}
        error={cancel.isError && !isBodyError(cancel.error) ? describeError(cancel.error, '검토를 그만하지 못했습니다.') : null}
        onConfirm={() => review && cancel.mutate({ reviewId: review.reviewId, revision: review.revision })}
      />
      <DeleteConfirmDialog
        open={discardNextOpen}
        onOpenChange={setDiscardNextOpen}
        title="고친 내용을 버리고 다른 후보를 볼까요?"
        description={<p>고친 내용은 승인 전이라 저장되지 않았습니다. 다른 후보로 넘기면 사라집니다.</p>}
        actionLabel="다른 후보 보기"
        pendingLabel="준비하는 중..."
        isPending={prepare.isPending}
        error={prepare.isError && !isBodyError(prepare.error) ? describeError(prepare.error, '다른 후보를 불러오지 못했습니다.') : null}
        onConfirm={next}
      />
      <DeleteConfirmDialog
        open={blocker.state === 'blocked'}
        onOpenChange={(open) => {
          if (!open && blocker.state === 'blocked') blocker.reset()
        }}
        title="고친 내용을 버리고 나갈까요?"
        description={<p>고친 내용은 승인 전이라 저장되지 않았습니다. 나가면 사라집니다. 검토 위치는 남아 다시 들어오면 이어 봅니다.</p>}
        actionLabel="나가기"
        pendingLabel="나가는 중..."
        isPending={false}
        error={null}
        onConfirm={() => blocker.state === 'blocked' && blocker.proceed()}
      />
    </InstructorLayout>
  )

  function renderBody() {
    if (confirm.isSuccess) {
      return (
        <Notice tone="success" role="status">
          승인했습니다. 아동이 이 시나리오로 역할극을 시작할 수 있습니다.{' '}
          <Link to="/content" className={linkClass}>
            승인 대기함으로
          </Link>
        </Notice>
      )
    }
    if (hasCode(lastError, 'SCENARIO_ALREADY_ASSIGNED')) {
      return (
        <Notice tone="info">
          이미 시나리오가 정해진 활동입니다.{' '}
          <Link to="/content" className={linkClass}>
            승인 대기함으로
          </Link>
        </Notice>
      )
    }
    if (hasCode(lastError, 'INITIAL_DIFFICULTY_NOT_READY')) {
      return <Notice tone="info">아직 퀴즈로 난이도가 정해지지 않은 활동입니다. 아동이 퀴즈를 마치면 검토할 수 있습니다.</Notice>
    }
    if (hasCode(lastError, 'SCENARIO_REVIEW_INPUT_CHANGED')) {
      return (
        <Notice tone="error" role="alert">
          검토를 시작한 뒤 목표나 난이도가 바뀌어 이 검토로는 승인할 수 없습니다.
          {review && (
            <button
              type="button"
              disabled={pending}
              onClick={() => cancel.mutate({ reviewId: review.reviewId, revision: review.revision })}
              className={cn(outlineButtonClass, 'ml-3')}
            >
              검토 그만하기
            </button>
          )}
        </Notice>
      )
    }
    if (needsRestore) {
      return (
        <Notice tone="error" role="alert">
          <span>다른 곳에서 검토가 바뀌었습니다. 새로 불러오면 지금 상태에서 이어 봅니다. 고친 내용은 사라집니다.</span>
          <button type="button" disabled={pending} onClick={restore} className={cn(outlineButtonClass, 'ml-3')}>
            <ButtonLabel pending={prepare.isPending} label="새로 불러오기" pendingLabel="불러오는 중..." />
          </button>
        </Notice>
      )
    }
    if (prepare.isPending) {
      return (
        <p role="status" className="text-sm text-[var(--instructor-text-muted)]">
          시나리오를 준비하고 있습니다. 맞는 후보가 없으면 새로 만들어서 1~2분 걸릴 수 있습니다.
        </p>
      )
    }
    if (prepare.isError) {
      return (
        <Notice tone="error" role="alert">
          {describeError(prepare.error, '시나리오를 준비하지 못했습니다.')}
          {isRetryable(prepare.error) && prepare.variables && (
            <button
              type="button"
              onClick={() => prepare.mutate(prepare.variables!)}
              className={cn(outlineButtonClass, 'ml-3')}
            >
              다시 시도
            </button>
          )}
        </Notice>
      )
    }
    if (!review || !scenario || !form) return null

    if (view === 'confirm') {
      return (
        <ApprovalConfirmPanel
          scenario={applyEdits(scenario, confirmingEdits)}
          conditions={review.conditions}
          edited={confirmingEdits !== undefined}
          change={describeEdits(confirmingEdits)}
          isPending={confirm.isPending}
          error={confirm.isError ? describeError(confirm.error, '승인하지 못했습니다.') : null}
          onBack={() => setView(confirmingEdits ? 'edit' : 'review')}
          onApprove={approve}
        />
      )
    }

    if (view === 'edit') {
      return (
        <ScenarioEditView
          scenario={scenario}
          form={form}
          problems={problems}
          pending={pending}
          onChange={setForm}
          onBack={() => {
            setForm(toForm(scenario))
            setView('review')
          }}
          onConfirm={goConfirm}
          onNext={requestNext}
          onCancel={() => setCancelOpen(true)}
        />
      )
    }

    return (
      <div className="grid grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] items-start gap-4">
        <div className={cardClass}>
          <div className="px-5 py-4">
            <h2 className="text-base font-semibold">{scenario.title}</h2>
            <p className="mt-1 text-xs text-[var(--instructor-text-subtle)]">
              v{scenario.scenarioVersion} · {scenario.scenarioLevel}
            </p>
          </div>
          <ScenarioSections scenario={scenario} />
        </div>
        <div className="flex flex-col gap-4">
          <ReviewConditionsCard conditions={review.conditions} />
          <OpeningPrompts scenario={scenario} />
          <p className="text-xs text-[var(--instructor-text-subtle)]">
            [다른 후보]는 이번 검토에서만 넘깁니다. 후보를 다 넘기면 새로 만듭니다.
          </p>
        </div>
      </div>
    )
  }
}

export { ScenarioReviewPage }
