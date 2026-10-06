import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  createActivity,
  createLearningGoal,
  listChildren,
  listClassrooms,
  type Child,
  type Classroom,
} from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { WizardStepper, type WizardStep } from '../components/WizardStepper'
import {
  cardClass,
  errorTextClass,
  fieldLabelClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from '../components/styles'
import {
  GOAL_CATEGORIES,
  GOAL_CATEGORY_LABELS,
  GOAL_PRESETS,
  isGoalCategory,
  type GoalCategory,
} from '../lib/goalPresets'
import { cn } from '@/lib/utils'

type StepKey = 'target' | 'goal' | 'confirm'

// S1 에서 실제로 동작하는 단계만 둔다. 시안의 유형·추가 조건·난이도 결과 단계는 D9(활동 유형)과 역할극이 정해지면
// 이 배열에 끼운다(ADR 2026-10-03 D2).
const STEPS: WizardStep<StepKey>[] = [
  { key: 'target', label: '대상' },
  { key: 'goal', label: '목표' },
  { key: 'confirm', label: '확인 · 배정' },
]

const STEP_TITLES: Record<StepKey, string> = {
  target: '활동 만들기 — 대상 선택',
  goal: '학습 목표 설정',
  confirm: '확인 · 배정',
}

const GOAL_TITLE_MAX = 200

type GoalTab = 'preset' | 'custom'

type GoalChoice = { title: string; category: GoalCategory | null }

const ASSIGN_ERROR_MESSAGES: Record<string, string> = {
  DUPLICATE_ASSIGNMENT: '이 아동에게 같은 목표로 아직 시작하지 않은 활동이 있어요. 활동 이력에서 확인해 주세요.',
  QUIZ_ITEMS_UNAVAILABLE: '배정할 수 있는 승인된 퀴즈 문항이 없어요. 운영 담당자에게 문항 등록을 요청해 주세요.',
}

/**
 * 활동 만들기 (시안 T-ACT-01~03 을 S1 범위로 줄인 마법사, ADR 2026-10-03 D1~D4).
 * 대상 아동 한 명 → 학습 목표 → 확인 후 배정. 퀴즈 문항은 서버가 승인 문항 중에서 고른다. 강사는 고르지 않는다.
 *
 * 단계·학급·아동은 주소(?step=&classId=&childId=)에 두어 새로고침해도 남는다. 아동 상세의 "활동 만들기"는 대상을 채워 연다.
 * 목표 입력은 화면 상태라 새로고침하면 목표 단계부터 다시 한다.
 */
function ActivityCreatePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const classId = searchParams.get('classId')
  const childId = searchParams.get('childId')

  const [goalTab, setGoalTab] = useState<GoalTab>('preset')
  const [categoryFilter, setCategoryFilter] = useState<GoalCategory>('EMOTION_RECOGNITION')
  const [presetId, setPresetId] = useState<string | null>(null)
  const [customTitle, setCustomTitle] = useState('')
  const [customCategory, setCustomCategory] = useState<GoalCategory | null>(null)

  const classroomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: listClassrooms })
  const childrenQuery = useQuery({
    queryKey: ['classrooms', classId, 'children'],
    queryFn: () => listChildren(classId!),
    enabled: !!classId,
  })
  const classroom = classroomsQuery.data?.find((c) => c.classId === classId)
  const child = childrenQuery.data?.find((c) => c.childId === childId)

  const preset = GOAL_PRESETS.find((p) => p.id === presetId)
  const goal: GoalChoice | null =
    goalTab === 'preset'
      ? preset
        ? { title: preset.title, category: preset.category }
        : null
      : customTitle.trim()
        ? { title: customTitle.trim(), category: customCategory }
        : null

  // 주소의 단계가 앞 단계 입력 없이 열리면(새로고침 등) 채워야 할 단계로 돌려보낸다.
  const requestedStep = searchParams.get('step')
  let step: StepKey = 'target'
  if (child && requestedStep === 'confirm' && goal) {
    step = 'confirm'
  } else if (child && (requestedStep === 'goal' || requestedStep === 'confirm')) {
    step = 'goal'
  }

  function moveTo(next: StepKey, target: { classId: string | null; childId: string | null } = { classId, childId }) {
    const params: Record<string, string> = { step: next }
    if (target.classId) params.classId = target.classId
    if (target.childId) params.childId = target.childId
    setSearchParams(params)
  }

  function selectClassroom(nextClassId: string) {
    moveTo('target', { classId: nextClassId || null, childId: null })
  }

  function selectChild(nextChildId: string) {
    moveTo('target', { classId, childId: nextChildId })
  }

  // 배정 한 번에 요청 키 하나. 응답을 못 받아 다시 누르면 같은 키로 보내 서버가 활동을 하나만 남긴다(ADR D4).
  // 대상이나 목표가 바뀌면 다른 배정이므로 새 키를 쓴다. 이미 저장한 목표도 같은 조건이면 다시 만들지 않는다.
  const submission = `${childId}|${goal?.title}|${goal?.category}`
  const attemptRef = useRef<{ submission: string; requestKey: string; goalId: string | null } | null>(null)
  const isSubmittingRef = useRef(false)

  const assignMutation = useMutation({
    mutationFn: async ({ target, choice }: { target: Child; choice: GoalChoice }) => {
      if (attemptRef.current?.submission !== submission) {
        attemptRef.current = { submission, requestKey: crypto.randomUUID(), goalId: null }
      }
      const attempt = attemptRef.current
      if (!attempt.goalId) {
        const saved = await createLearningGoal(target.childId, {
          title: choice.title,
          ...(choice.category ? { situationType: choice.category } : {}),
        })
        attempt.goalId = saved.goalId
      }
      return createActivity(target.childId, attempt.goalId, attempt.requestKey)
    },
    onSuccess: (_activity, { target }) => {
      queryClient.invalidateQueries({ queryKey: ['children', target.childId] })
      navigate(`/classrooms/${target.classId}/children/${target.childId}`)
    },
  })

  function handleAssign() {
    if (!child || !goal || isSubmittingRef.current) {
      return
    }
    isSubmittingRef.current = true
    assignMutation.mutate(
      { target: child, choice: goal },
      {
        onSettled: () => {
          isSubmittingRef.current = false
        },
      }
    )
  }

  // 배정 오류는 그 확인 단계에서만 보인다. 확인 단계를 벗어나면(이전·뒤로가기) 지운다.
  // 요청이 끝난 뒤에만 지워서, 진행 중인 요청의 onSettled(isSubmittingRef 해제)를 끊지 않는다.
  const leftConfirm = step !== 'confirm'
  const { isError: hasAssignError, reset: resetAssign } = assignMutation
  useEffect(() => {
    if (leftConfirm && hasAssignError) {
      resetAssign()
    }
  }, [leftConfirm, hasAssignError, resetAssign])

  const assignError = assignMutation.error
  const assignErrorMessage =
    assignError instanceof ApiError && ASSIGN_ERROR_MESSAGES[assignError.code]
      ? ASSIGN_ERROR_MESSAGES[assignError.code]
      : describeError(assignError, '활동을 배정하지 못했습니다.')

  const stepIndex = STEPS.findIndex((s) => s.key === step)

  return (
    <InstructorLayout
      title={STEP_TITLES[step]}
      actions={
        <span className="text-xs text-[var(--instructor-text-muted)]">
          {stepIndex + 1} / {STEPS.length} 단계
        </span>
      }
      subheader={<WizardStepper steps={STEPS} current={step} />}
    >
      {step === 'target' && (
        <TargetStep
          classrooms={classroomsQuery.data}
          classroomsError={classroomsQuery.error}
          classId={classId}
          childrenList={childrenQuery.data}
          isChildrenLoading={childrenQuery.isLoading}
          childrenError={childrenQuery.error}
          childId={child ? childId : null}
          onSelectClassroom={selectClassroom}
          onSelectChild={selectChild}
          onCancel={() => navigate('/classrooms')}
          onNext={() => moveTo('goal')}
        />
      )}

      {step === 'goal' && (
        <div className="flex flex-col gap-4">
          <div role="tablist" aria-label="학습 목표 입력 방식" className={cn(cardClass, 'flex px-2')}>
            {(
              [
                ['preset', '목표 목록에서 선택'],
                ['custom', '직접 입력'],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={goalTab === key}
                onClick={() => setGoalTab(key)}
                className={cn(
                  'relative -mb-px border-b-2 px-4 py-3 text-sm',
                  goalTab === key
                    ? 'border-[var(--instructor-primary)] font-semibold'
                    : 'border-transparent text-[var(--instructor-text-muted)]'
                )}
              >
                {label}
              </button>
            ))}
          </div>

          {goalTab === 'preset' ? (
            <section className={cn(cardClass, 'flex flex-col gap-4 p-6')} aria-label="목표 목록">
              <div className="flex gap-2">
                {GOAL_CATEGORIES.map((category) => (
                  <button
                    key={category}
                    type="button"
                    aria-pressed={categoryFilter === category}
                    onClick={() => {
                      // 고른 목표가 목록에서 가려지면 선택도 비운다. 보이지 않는 목표가 배정되지 않게 한다.
                      setCategoryFilter(category)
                      setPresetId(null)
                    }}
                    className={cn(
                      'rounded-full border px-4 py-1.5 text-sm',
                      categoryFilter === category
                        ? 'border-[var(--instructor-primary)] bg-[var(--instructor-primary)] text-[var(--instructor-primary-foreground)]'
                        : 'border-[var(--instructor-input-border)]'
                    )}
                  >
                    {GOAL_CATEGORY_LABELS[category]}
                  </button>
                ))}
              </div>
              <fieldset className="flex flex-col">
                <legend className="sr-only">학습 목표</legend>
                {GOAL_PRESETS.filter((p) => p.category === categoryFilter).map((p) => (
                  <label
                    key={p.id}
                    className={cn(
                      'flex cursor-pointer items-start gap-3 border-b border-[var(--instructor-border)] px-3 py-3.5 last:border-b-0',
                      presetId === p.id && 'bg-[var(--instructor-surface-muted)]'
                    )}
                  >
                    <input
                      type="radio"
                      name="goal-preset"
                      checked={presetId === p.id}
                      onChange={() => setPresetId(p.id)}
                      className="mt-1"
                    />
                    <span>
                      <span className="block text-sm font-semibold">{p.title}</span>
                      <span className="block text-xs text-[var(--instructor-text-muted)]">
                        {GOAL_CATEGORY_LABELS[p.category]} · 권장 {p.recommendedLevel}
                      </span>
                    </span>
                  </label>
                ))}
              </fieldset>
              <p className="text-xs text-[var(--instructor-text-muted)]">목표는 활동당 1개만 선택합니다.</p>
            </section>
          ) : (
            <section className={cn(cardClass, 'flex max-w-xl flex-col gap-5 p-6')} aria-label="직접 입력">
              <label className={fieldLabelClass}>
                학습 목표
                <input
                  value={customTitle}
                  onChange={(e) => setCustomTitle(e.target.value)}
                  maxLength={GOAL_TITLE_MAX}
                  placeholder="예: 친구가 속상할 때 위로하는 말을 한다"
                  className={inputClass}
                />
              </label>
              <label className={fieldLabelClass}>
                분류 (선택)
                <select
                  value={customCategory ?? ''}
                  onChange={(e) => setCustomCategory(isGoalCategory(e.target.value) ? e.target.value : null)}
                  className={inputClass}
                >
                  <option value="">선택 안 함</option>
                  {GOAL_CATEGORIES.map((category) => (
                    <option key={category} value={category}>
                      {GOAL_CATEGORY_LABELS[category]}
                    </option>
                  ))}
                </select>
              </label>
            </section>
          )}

          <div className="flex justify-between">
            <button type="button" onClick={() => moveTo('target')} className={outlineButtonClass}>
              이전
            </button>
            <button type="button" disabled={!goal} onClick={() => moveTo('confirm')} className={primaryButtonClass}>
              다음 · 확인
            </button>
          </div>
        </div>
      )}

      {step === 'confirm' && child && goal && (
        <div className="flex max-w-2xl flex-col gap-4">
          <section className={cn(cardClass, 'p-6')} aria-label="배정 내용">
            <dl className="grid grid-cols-[7rem_1fr] gap-x-4 gap-y-4 text-sm">
              <dt className="text-[var(--instructor-text-muted)]">대상</dt>
              <dd className="font-semibold">
                {child.displayName}
                {classroom && (
                  <span className="font-normal text-[var(--instructor-text-muted)]"> · {classroom.name}</span>
                )}
              </dd>
              <dt className="text-[var(--instructor-text-muted)]">학습 목표</dt>
              <dd>
                <span className="font-semibold">{goal.title}</span>
                {goal.category && (
                  <span className="text-[var(--instructor-text-muted)]"> · {GOAL_CATEGORY_LABELS[goal.category]}</span>
                )}
              </dd>
              <dt className="text-[var(--instructor-text-muted)]">활동</dt>
              <dd>
                <span className="font-semibold">표정 퀴즈</span>
                <span className="block text-xs text-[var(--instructor-text-muted)]">
                  검증된 문항 중에서 유형별로 1개씩, 최대 3문항이 자동으로 준비돼요.
                </span>
              </dd>
            </dl>
          </section>

          {assignMutation.isError && (
            <p role="alert" className={errorTextClass}>
              {assignErrorMessage}
            </p>
          )}

          <div className="flex justify-between">
            <button
              type="button"
              onClick={() => moveTo('goal')}
              disabled={assignMutation.isPending}
              className={outlineButtonClass}
            >
              이전
            </button>
            <button
              type="button"
              onClick={handleAssign}
              disabled={assignMutation.isPending}
              className={primaryButtonClass}
            >
              {assignMutation.isPending ? '배정 중...' : '배정하기'}
            </button>
          </div>
        </div>
      )}
    </InstructorLayout>
  )
}

function TargetStep({
  classrooms,
  classroomsError,
  classId,
  childrenList,
  isChildrenLoading,
  childrenError,
  childId,
  onSelectClassroom,
  onSelectChild,
  onCancel,
  onNext,
}: {
  classrooms: Classroom[] | undefined
  classroomsError: Error | null
  classId: string | null
  childrenList: Child[] | undefined
  isChildrenLoading: boolean
  childrenError: Error | null
  childId: string | null
  onSelectClassroom: (classId: string) => void
  onSelectChild: (childId: string) => void
  onCancel: () => void
  onNext: () => void
}) {
  // 배정은 참여 중인 아동에게만 한다.
  const activeChildren = childrenList?.filter((c) => c.status === 'ACTIVE')

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <section className={cn(cardClass, 'flex flex-col gap-5 p-6')} aria-label="대상 범위">
        <h2 className="text-base font-bold">대상 범위</h2>
        <p className="rounded-[var(--instructor-radius-control)] border border-[var(--instructor-primary)] px-4 py-3 text-sm">
          <span className="block font-semibold">특정 아동 선택</span>
          <span className="block text-xs text-[var(--instructor-text-muted)]">
            아동 한 명에게 활동을 배정합니다.
          </span>
        </p>
        <label className={fieldLabelClass}>
          학급
          <select value={classId ?? ''} onChange={(e) => onSelectClassroom(e.target.value)} className={inputClass}>
            <option value="">학급을 고르세요</option>
            {classrooms?.map((c) => (
              <option key={c.classId} value={c.classId}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        {classroomsError && <p className={errorTextClass}>{classroomsError.message}</p>}
      </section>

      <section className={cn(cardClass, 'flex flex-col gap-4 p-6')} aria-label="대상 리스트">
        <h2 className="text-base font-bold">대상 리스트</h2>
        {!classId ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">학급을 먼저 골라 주세요.</p>
        ) : isChildrenLoading ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
        ) : childrenError ? (
          <p className={errorTextClass}>{childrenError.message}</p>
        ) : activeChildren?.length === 0 ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">이 학급에 참여 중인 아동이 없습니다.</p>
        ) : (
          <fieldset className="flex flex-col">
            <legend className="sr-only">아동</legend>
            {activeChildren?.map((c) => (
              <label
                key={c.childId}
                className={cn(
                  'flex cursor-pointer items-center gap-3 border-b border-[var(--instructor-border)] px-3 py-3 text-sm last:border-b-0',
                  childId === c.childId && 'bg-[var(--instructor-surface-muted)]'
                )}
              >
                <input
                  type="radio"
                  name="target-child"
                  checked={childId === c.childId}
                  onChange={() => onSelectChild(c.childId)}
                />
                {c.displayName}
              </label>
            ))}
          </fieldset>
        )}

        <div className="mt-auto flex items-center justify-end gap-2 pt-2">
          <button type="button" onClick={onCancel} className={outlineButtonClass}>
            취소
          </button>
          <button type="button" disabled={!childId} onClick={onNext} className={primaryButtonClass}>
            다음 · 학습 목표 설정
          </button>
        </div>
      </section>
    </div>
  )
}

export { ActivityCreatePage }
