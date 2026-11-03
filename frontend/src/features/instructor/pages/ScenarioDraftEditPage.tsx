import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  deleteScenario,
  getLearningGoal,
  getScenario,
  reviewScenario,
  type Scenario,
  type ScenarioReview,
} from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ApprovalConfirmPanel } from '../components/ApprovalConfirmPanel'
import { OpeningPrompts, ScenarioSections } from '../components/ScenarioContent'
import { ScenarioStatusBadge } from '../components/ScenarioBadges'
import { DeleteConfirmDialog } from '../components/DeleteConfirmDialog'
import { ButtonLabel } from '../components/ButtonLabel'
import { Notice } from '../components/Feedback'
import {
  cardClass,
  dangerOutlineButtonClass,
  fieldLabelClass,
  helperTextClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from '../components/styles'
import { describeError } from '../errorMessage'
import { cn } from '@/lib/utils'

// 시안(T-SCN-03)의 빠른 지시. 시나리오 생성이 들어오기 전까지 누를 수 없다.
const QUICK_INSTRUCTIONS = ['턴 수 줄이기', '쉬운 낱말로', '유도 질문 보강']

type MicroGoalForm = { microGoalId: string; description: string; evidence: string }

type DraftForm = { title: string; summary: string; microGoals: MicroGoalForm[] }

function toForm(scenario: Scenario): DraftForm {
  return {
    title: scenario.title,
    summary: scenario.summary ?? '',
    microGoals: scenario.microGoals.map((goal) => ({
      microGoalId: goal.microGoalId,
      description: goal.description,
      evidence: goal.requiredEvidence.join('\n'),
    })),
  }
}

/** 고친 칸만 보낸다. 필요 증거는 한 줄에 하나다. */
function toReview(scenario: Scenario, form: DraftForm): ScenarioReview {
  const review: ScenarioReview = { version: scenario.version, editRevision: scenario.editRevision }
  if (form.title !== scenario.title) review.title = form.title
  if (form.summary !== (scenario.summary ?? '')) review.summary = form.summary
  const changed = form.microGoals.filter((goal) => {
    const original = scenario.microGoals.find((g) => g.microGoalId === goal.microGoalId)
    return original && (original.description !== goal.description || original.requiredEvidence.join('\n') !== goal.evidence)
  })
  if (changed.length > 0) {
    review.microGoals = changed.map((goal) => ({
      microGoalId: goal.microGoalId,
      description: goal.description,
      requiredEvidence: goal.evidence
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    }))
  }
  return review
}

/** 화면을 연 뒤 다른 곳(다른 탭·기기)에서 먼저 저장해 수정 번호가 달라졌다. */
function isEditConflict(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'VERSION_CONFLICT'
}

/** 승인 확인 화면에 보여 줄 모습. 승인 요청에 실을 고친 칸을 그대로 덮어, 보이는 내용과 승인되는 내용이 같게 한다. */
function withReview(scenario: Scenario, review: ScenarioReview): Scenario {
  return {
    ...scenario,
    title: review.title ?? scenario.title,
    summary: review.summary ?? scenario.summary,
    microGoals: scenario.microGoals.map((goal) => {
      const edited = review.microGoals?.find((g) => g.microGoalId === goal.microGoalId)
      return edited ? { ...goal, description: edited.description, requiredEvidence: edited.requiredEvidence } : goal
    }),
  }
}

/**
 * 시나리오 수정(시안 T-SCN-03 배치). [수정하기]로 만든 이 목표용 새 판(초안)을 고친다.
 * 명세대로 제목·상황(요약)·작은 목표(설명·필요 증거)만 직접 고치고, 고정 사실·금지 추론·시작 질문은 읽기만 한다.
 * 수정 지시와 [재생성]은 시나리오 생성이 들어오기 전까지 비활성이다.
 */
function ScenarioDraftEditPage() {
  const { scenarioId } = useParams<{ scenarioId: string }>()
  const scenarioQuery = useQuery({
    queryKey: ['scenarios', scenarioId],
    queryFn: () => getScenario(scenarioId!),
    enabled: !!scenarioId,
  })
  const scenario = scenarioQuery.data

  return (
    <InstructorLayout title="시나리오 수정 · 재생성">
      <nav aria-label="현재 위치" className="mb-4 text-xs text-[var(--instructor-text-subtle)]">
        <Link to="/content" className="hover:underline">
          승인 대기함
        </Link>
      </nav>
      {scenarioQuery.isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : scenarioQuery.isError || !scenario ? (
        <Notice tone="error">{describeError(scenarioQuery.error, '시나리오를 불러오지 못했습니다.')}</Notice>
      ) : (
        // 판정으로 상태가 바뀌면 편집 칸을 새로 그린다. 저장만 하면 칸이 이미 저장한 값과 같아 그대로 둔다.
        <DraftEditor key={`${scenario.scenarioId}-${scenario.status}`} scenario={scenario} />
      )}
    </InstructorLayout>
  )
}

function DraftEditor({ scenario }: { scenario: Scenario }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [form, setForm] = useState<DraftForm>(() => toForm(scenario))
  // [승인으로 이동]을 누른 순간의 고친 내용. 확인 화면과 승인 요청이 이 하나를 같이 쓴다.
  const [confirming, setConfirming] = useState<ScenarioReview | null>(null)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [discardOpen, setDiscardOpen] = useState(false)
  const [savedNotice, setSavedNotice] = useState(false)
  const isDraft = scenario.status === 'DRAFT'

  const goalQuery = useQuery({
    queryKey: ['learning-goals', scenario.goalId],
    queryFn: () => getLearningGoal(scenario.goalId!),
    enabled: !!scenario.goalId,
  })

  function refresh(updated: Scenario) {
    queryClient.setQueryData(['scenarios', updated.scenarioId], updated)
    void queryClient.invalidateQueries({ queryKey: ['scenario-approvals'] })
  }

  const save = useMutation({
    mutationFn: () => reviewScenario(scenario.scenarioId, toReview(scenario, form)),
    onMutate: () => setSavedNotice(false),
    onSuccess: (updated) => {
      refresh(updated)
      setSavedNotice(true)
    },
  })
  const approve = useMutation({
    mutationFn: (review: ScenarioReview) => reviewScenario(scenario.scenarioId, { ...review, status: 'APPROVED' }),
    onSuccess: refresh,
    // 확인 화면은 보지 못한 내용으로 바뀐 초안을 보여 주고 있다. 편집 화면으로 돌아가 새로 불러오게 한다.
    onError: (error) => {
      if (isEditConflict(error)) setConfirming(null)
    },
  })
  const reject = useMutation({
    mutationFn: () =>
      reviewScenario(scenario.scenarioId, {
        version: scenario.version,
        editRevision: scenario.editRevision,
        status: 'REJECTED',
      }),
    onSuccess: (updated) => {
      setRejectOpen(false)
      refresh(updated)
    },
    onError: (error) => {
      if (isEditConflict(error)) setRejectOpen(false)
    },
  })
  // 저장된 내용으로 칸을 다시 채운다. 저장하지 않은 입력은 버린다.
  const reload = useMutation({
    mutationFn: () => getScenario(scenario.scenarioId),
    onSuccess: (fresh) => {
      refresh(fresh)
      setForm(toForm(fresh))
      save.reset()
      approve.reset()
      reject.reset()
    },
  })
  const conflict = [save, approve, reject].some((mutation) => isEditConflict(mutation.error))
  const discard = useMutation({
    mutationFn: () => deleteScenario(scenario.scenarioId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['scenario-approvals'] })
      navigate('/content')
    },
  })

  // 승인 뒤에는 서버 상태로 판단한다. 승인 응답으로 캐시가 바뀌면 이 편집 영역이 새로 그려지기 때문이다.
  if (scenario.status === 'APPROVED') {
    return (
      <Notice tone="success" role="status">
        승인했습니다. 아동이 이 시나리오로 역할극을 시작할 수 있습니다.{' '}
        <Link to="/content" className="font-semibold underline underline-offset-2">
          승인 대기함으로
        </Link>
      </Notice>
    )
  }

  if (confirming) {
    return (
      <ApprovalConfirmPanel
        scenario={withReview(scenario, confirming)}
        goal={goalQuery.data}
        change={`v${scenario.version - 1}에서 고친 판`}
        backLabel="수정으로 돌아가기"
        isPending={approve.isPending}
        error={approve.isError ? describeError(approve.error, '승인하지 못했습니다.') : null}
        onBack={() => setConfirming(null)}
        onApprove={() => approve.mutate(confirming)}
      />
    )
  }

  const editing = isDraft
  const pending = save.isPending || approve.isPending || reject.isPending || discard.isPending || reload.isPending

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3">
        <ScenarioStatusBadge status={scenario.status} />
        <span className="ml-auto text-xs text-[var(--instructor-text-subtle)]">
          {isDraft ? `v${scenario.version - 1} → v${scenario.version} 작성 중` : `v${scenario.version}`}
        </span>
      </div>
      {!isDraft && <Notice tone="info">판정이 끝난 시나리오라 고칠 수 없습니다.</Notice>}
      {savedNotice && !save.isPending && (
        <Notice tone="success" role="status">
          저장했습니다.
        </Notice>
      )}
      {conflict ? (
        <Notice tone="error" role="alert">
          <span>다른 곳에서 먼저 고쳤습니다. 새로 불러오면 저장하지 않은 입력은 사라지고 저장된 내용이 보입니다.</span>
          <button type="button" disabled={pending} onClick={() => reload.mutate()} className={cn(outlineButtonClass, 'ml-3')}>
            <ButtonLabel pending={reload.isPending} label="새로 불러오기" pendingLabel="불러오는 중..." />
          </button>
          {reload.isError && <span className="mt-2 block">{describeError(reload.error, '불러오지 못했습니다.')}</span>}
        </Notice>
      ) : (
        save.isError && (
          <Notice tone="error" role="alert">
            {describeError(save.error, '저장하지 못했습니다.')}
          </Notice>
        )
      )}

      <div className="grid grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] items-start gap-4">
        <div className={cardClass}>
          <div className="px-5 py-4">
            <h2 className="mb-3 text-sm font-semibold">섹션별 수정</h2>
            <label className={fieldLabelClass}>
              제목
              <input
                className={inputClass}
                value={form.title}
                maxLength={200}
                disabled={!editing}
                onChange={(event) => setForm({ ...form, title: event.target.value })}
              />
            </label>
          </div>
          <ScenarioSections
            scenario={scenario}
            replace={{
              summary: (
                <label className={fieldLabelClass}>
                  <span className="sr-only">상황</span>
                  <textarea
                    className={cn(inputClass, 'min-h-20 py-2')}
                    value={form.summary}
                    maxLength={1000}
                    disabled={!editing}
                    onChange={(event) => setForm({ ...form, summary: event.target.value })}
                  />
                </label>
              ),
              microGoals: (
                <ol className="flex flex-col gap-4">
                  {form.microGoals.map((goal, index) => (
                    <li key={goal.microGoalId} className="flex flex-col gap-2">
                      <label className={fieldLabelClass}>
                        작은 목표 {index + 1}
                        <input
                          className={inputClass}
                          value={goal.description}
                          maxLength={500}
                          disabled={!editing}
                          onChange={(event) =>
                            setForm({
                              ...form,
                              microGoals: form.microGoals.map((g) =>
                                g.microGoalId === goal.microGoalId ? { ...g, description: event.target.value } : g,
                              ),
                            })
                          }
                        />
                      </label>
                      <label className={fieldLabelClass}>
                        필요 증거
                        <textarea
                          className={cn(inputClass, 'min-h-16 py-2')}
                          value={goal.evidence}
                          disabled={!editing}
                          onChange={(event) =>
                            setForm({
                              ...form,
                              microGoals: form.microGoals.map((g) =>
                                g.microGoalId === goal.microGoalId ? { ...g, evidence: event.target.value } : g,
                              ),
                            })
                          }
                        />
                        <span className={helperTextClass}>한 줄에 하나씩 적습니다(최대 5개).</span>
                      </label>
                    </li>
                  ))}
                </ol>
              ),
            }}
          />
          <p className={cn(helperTextClass, 'border-t border-[var(--instructor-border-subtle)] px-5 py-3')}>
            고정 사실·금지 추론·시작 질문은 직접 고치지 않습니다. 시나리오 생성이 들어오면 수정 지시로 다시 만들 수 있습니다.
          </p>
        </div>

        <div className="flex flex-col gap-4">
          <div className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
            <h3 className="text-sm font-semibold">수정 지시 입력</h3>
            <p className={helperTextClass}>시나리오 생성이 들어오면 쓸 수 있습니다.</p>
            <textarea
              aria-label="수정 지시"
              disabled
              placeholder="문장으로 지시하면 해당 부분만 다시 씁니다."
              className={cn(inputClass, 'min-h-28 py-2')}
            />
            <div className="flex flex-wrap gap-2">
              {QUICK_INSTRUCTIONS.map((label) => (
                <button key={label} type="button" disabled className={outlineButtonClass}>
                  {label}
                </button>
              ))}
            </div>
          </div>

          {editing && (
            <div className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
              <h3 className="text-sm font-semibold">처리</h3>
              <button type="button" disabled className={primaryButtonClass} title="시나리오 생성이 들어오면 쓸 수 있어요">
                재생성 (v{scenario.version + 1} 생성)
              </button>
              <div className="grid grid-cols-2 gap-2">
                <button type="button" disabled={pending} onClick={() => save.mutate()} className={outlineButtonClass}>
                  <ButtonLabel pending={save.isPending} label="저장" pendingLabel="저장 중..." />
                </button>
                <button type="button" disabled={pending} onClick={() => setConfirming(toReview(scenario, form))} className={primaryButtonClass}>
                  승인으로 이동
                </button>
                <button type="button" disabled={pending} onClick={() => setRejectOpen(true)} className={outlineButtonClass}>
                  반려
                </button>
                <button type="button" disabled={pending} onClick={() => setDiscardOpen(true)} className={dangerOutlineButtonClass}>
                  폐기
                </button>
              </div>
              <p className={helperTextClass}>반려하면 기록으로 남고, 폐기하면 목록에서 사라집니다.</p>
            </div>
          )}
          <OpeningPrompts scenario={scenario} />
        </div>
      </div>

      <DeleteConfirmDialog
        open={rejectOpen}
        onOpenChange={setRejectOpen}
        title="이 시나리오를 반려할까요?"
        description={<p>반려한 시나리오는 기록으로 남고 다시 고치거나 승인할 수 없습니다. 활동은 계속 시나리오를 기다립니다.</p>}
        actionLabel="반려"
        pendingLabel="반려하는 중..."
        isPending={reject.isPending}
        error={reject.isError ? describeError(reject.error, '반려하지 못했습니다.') : null}
        onConfirm={() => reject.mutate()}
      />
      <DeleteConfirmDialog
        open={discardOpen}
        onOpenChange={setDiscardOpen}
        title="이 초안을 폐기할까요?"
        description={<p>폐기한 초안은 지워지고 목록에서 사라집니다. 원본 시나리오는 그대로 남습니다.</p>}
        actionLabel="폐기"
        pendingLabel="폐기하는 중..."
        isPending={discard.isPending}
        error={discard.isError ? describeError(discard.error, '폐기하지 못했습니다.') : null}
        onConfirm={() => discard.mutate()}
      />
    </div>
  )
}

export { ScenarioDraftEditPage }
