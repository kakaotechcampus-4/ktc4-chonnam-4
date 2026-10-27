import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  createActivity,
  listChildActivities,
  listChildren,
  listClassrooms,
  type Activity,
  type ActivityType,
  type Child,
  type Classroom,
} from '../api'
import { describeError } from '../errorMessage'
import { InstructorLayout } from '../layout/InstructorLayout'
import { WizardStepper, type WizardStep } from '../components/WizardStepper'
import {
  badgeClass,
  cardClass,
  fieldLabelClass,
  filterButtonClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
  tabBaseClass,
  tabIdleClass,
  tabSelectedClass,
  tableHeadRowClass,
} from '../components/styles'
import {
  GOAL_CATEGORIES,
  GOAL_CATEGORY_LABELS,
  GOAL_PRESETS,
  isGoalCategory,
  type GoalCategory,
} from '../lib/goalPresets'
import { ACTIVITY_TYPE_LABELS } from '../lib/activityTypes'
import { cn } from '@/lib/utils'
import { Notice } from '../components/Feedback'
import { ButtonLabel } from '../components/ButtonLabel'

type StepKey = 'target' | 'type' | 'goal' | 'confirm'

// 동작하는 단계만 둔다. 시안의 추가 조건·난이도 결과 단계는 역할극이 생기면 이 배열에 끼운다.
// 독립 퀴즈는 학습 목표가 없어 목표 단계를 건너뛴다.
const ALL_STEPS: WizardStep<StepKey>[] = [
  { key: 'target', label: '대상' },
  { key: 'type', label: '유형' },
  { key: 'goal', label: '목표' },
  { key: 'confirm', label: '확인 · 배정' },
]

const STEP_TITLES: Record<StepKey, string> = {
  target: '활동 만들기 — 대상 선택',
  type: '활동 유형 선택',
  goal: '학습 목표 설정',
  confirm: '확인 · 배정',
}

const QUIZ_TYPES = ['SELF_EMOTION_QUIZ', 'OTHER_EMOTION_QUIZ'] as const

function isAssignableType(value: string | null): value is ActivityType {
  return value === 'FIRST_COMBINED' || value === 'SELF_EMOTION_QUIZ' || value === 'OTHER_EMOTION_QUIZ'
}

/** 확인 단계에 보일 문항 구성. 서버 QuizAssignmentService.COMPOSITION 과 맞춘다. */
const COMPOSITION_TEXT: Record<ActivityType, string> = {
  FIRST_COMBINED: '검증된 문항 중에서 유형별로 1개씩, 3문항이 자동으로 준비돼요. 역할극은 준비되는 대로 이어져요.',
  SELF_EMOTION_QUIZ: '내 감정 상황 문항 10개가 자동으로 준비돼요.',
  OTHER_EMOTION_QUIZ: '상대 감정 상황 문항 7개와 표정 그림 문항 3개가 자동으로 준비돼요.',
  ROLEPLAY: '',
}

/**
 * 아동의 지난 활동으로 지금 고를 수 있는 유형을 정한다. 최초 결합은 아동당 한 번이고,
 * 그것을 마친 뒤에 독립 퀴즈가 열린다. 역할극은 아직 없다. 서버도 같은 규칙으로 막는다.
 * V10 이 예전 활동을 모두 최초 결합으로 바꿔 한 아동에게 여럿 있을 수 있으므로, 서버처럼 마친 것이 하나라도 있는지 본다.
 */
function typeAvailability(activities: Activity[]): Availability {
  const firsts = activities.filter((a) => a.activityType === 'FIRST_COMBINED')
  return {
    firstCombined: firsts.length === 0,
    quiz: firsts.some((a) => a.status === 'COMPLETED'),
  }
}

type Availability = { firstCombined: boolean; quiz: boolean }

/** 유형 선택 화면 위 안내. 아동이 지금 어느 단계인지에 맞춰 다음에 열리는 것을 알린다. */
function typeGuide(availability: Availability): string {
  if (availability.firstCombined) return '처음 배정하는 아동은 최초 결합 활동부터 시작해요.'
  if (!availability.quiz) return '최초 결합 활동을 마치면 표정 퀴즈를 만들 수 있어요.'
  return '최초 결합 활동을 마쳐서 표정 퀴즈를 만들 수 있어요.'
}

/**
 * 지금 배정할 유형. 주소의 유형이 지금 고를 수 없는 것이면(새로고침 뒤 상태가 바뀐 경우 등) 고르지 않은 것으로 본다.
 * 고를 수 있는 것이 최초 결합 하나뿐이면 미리 고른 것으로 본다.
 * 이미 보낸 배정의 유형(sentType)은 그대로 둔다. 서버가 저장하고 응답만 잃었다면 다시 불린 목록에 그 활동이 들어 있어
 * 새 배정으로는 고를 수 없지만, 같은 요청 키로 다시 보내면 서버가 처음 만든 활동을 돌려준다.
 */
function resolveType(
  requested: string | null,
  availability: Availability | null,
  sentType: ActivityType | null,
): ActivityType | null {
  if (!availability) return null
  if (isAssignableType(requested)) {
    if (requested === sentType) return requested
    const allowed = requested === 'FIRST_COMBINED' ? availability.firstCombined : availability.quiz
    return allowed ? requested : null
  }
  return availability.firstCombined && !availability.quiz ? 'FIRST_COMBINED' : null
}

const GOAL_TITLE_MAX = 200

type GoalTab = 'preset' | 'custom'

type GoalChoice = { title: string; category: GoalCategory | null }

const ASSIGN_ERROR_MESSAGES: Record<string, string> = {
  DUPLICATE_ASSIGNMENT: '이 아동에게 같은 활동이 아직 시작하지 않은 채로 있어요. 활동 이력에서 확인해 주세요.',
  FIRST_ACTIVITY_EXISTS: '이 아동은 이미 최초 활동을 배정받았어요. 활동 이력에서 확인해 주세요.',
  FIRST_ACTIVITY_NOT_COMPLETED: '최초 활동을 마친 뒤에 배정할 수 있어요.',
  QUIZ_ITEMS_UNAVAILABLE: '배정할 수 있는 승인된 퀴즈 문항이 모자라요. 운영 담당자에게 문항 등록을 요청해 주세요.',
}

/**
 * 활동 만들기 (시안의 활동 만들기 화면을 줄인 마법사).
 * 대상 아동 한 명 → 활동 유형 → 학습 목표(최초 결합만) → 확인 후 배정. 퀴즈 문항은 서버가 유형별 구성대로 고른다.
 *
 * 단계·학급·아동·유형은 주소(?step=&classId=&childId=&type=)에 두어 새로고침해도 남는다. 아동 상세의 "활동 만들기"는
 * 대상을 채워 연다. 목표 입력은 화면 상태라 새로고침하면 목표 단계부터 다시 한다.
 */
function ActivityCreatePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const classId = searchParams.get('classId')
  const childId = searchParams.get('childId')
  const requestedType = searchParams.get('type')

  const [goalTab, setGoalTab] = useState<GoalTab>('preset')
  const [categoryFilter, setCategoryFilter] = useState<GoalCategory>('EMOTION_RECOGNITION')
  const [presetId, setPresetId] = useState<string | null>(null)
  const [customTitle, setCustomTitle] = useState('')
  const [customCategory, setCustomCategory] = useState<GoalCategory | null>(null)
  // 이 화면에서 이 아동에게 보낸 배정의 유형. 응답을 못 받았을 때 다시 보낼 수 있게 남긴다.
  const [sent, setSent] = useState<{ childId: string; type: ActivityType } | null>(null)

  const classroomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: listClassrooms })
  const childrenQuery = useQuery({
    queryKey: ['classrooms', classId, 'children'],
    queryFn: () => listChildren(classId!),
    enabled: !!classId,
  })
  const classroom = classroomsQuery.data?.find((c) => c.classId === classId)
  const child = childrenQuery.data?.find((c) => c.childId === childId)
  const activitiesQuery = useQuery({
    queryKey: ['children', childId, 'activities'],
    queryFn: () => listChildActivities(childId!),
    enabled: !!child,
  })
  const availability = activitiesQuery.data ? typeAvailability(activitiesQuery.data) : null
  const activityType = resolveType(requestedType, availability, sent?.childId === childId ? sent.type : null)
  const needsGoal = activityType === 'FIRST_COMBINED'
  const steps = activityType === null || needsGoal ? ALL_STEPS : ALL_STEPS.filter((s) => s.key !== 'goal')

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
  if (child && requestedStep !== null && requestedStep !== 'target') {
    if (!activityType) {
      step = 'type'
    } else if (requestedStep === 'confirm' && (!needsGoal || goal)) {
      step = 'confirm'
    } else if (needsGoal && (requestedStep === 'goal' || requestedStep === 'confirm')) {
      step = 'goal'
    } else {
      step = 'type'
    }
  }

  type Target = { classId: string | null; childId: string | null; type: string | null }

  function moveTo(next: StepKey, target: Target = { classId, childId, type: activityType }) {
    const params: Record<string, string> = { step: next }
    if (target.classId) params.classId = target.classId
    if (target.childId) params.childId = target.childId
    if (target.type) params.type = target.type
    setSearchParams(params)
  }

  function selectClassroom(nextClassId: string) {
    moveTo('target', { classId: nextClassId || null, childId: null, type: null })
  }

  function selectChild(nextChildId: string) {
    moveTo('target', { classId, childId: nextChildId, type: null })
  }

  function selectType(nextType: ActivityType) {
    moveTo('type', { classId, childId, type: nextType })
  }

  // 배정 한 번에 요청 키 하나. 응답을 못 받아 다시 누르면 같은 키로 보내 서버가 목표와 활동을 하나만 남긴다(ADR D4).
  // 대상·유형·목표가 바뀌면 다른 배정이므로 새 키를 쓴다.
  const submission = `${childId}|${activityType}|${needsGoal ? `${goal?.title}|${goal?.category}` : ''}`
  const attemptRef = useRef<{ submission: string; requestKey: string } | null>(null)
  const isSubmittingRef = useRef(false)

  const assignMutation = useMutation({
    mutationFn: ({ target, type, choice }: { target: Child; type: ActivityType; choice: GoalChoice | null }) => {
      if (attemptRef.current?.submission !== submission) {
        attemptRef.current = { submission, requestKey: crypto.randomUUID() }
      }
      return createActivity(
        target.childId,
        type,
        choice ? { title: choice.title, ...(choice.category ? { category: choice.category } : {}) } : null,
        attemptRef.current.requestKey,
      )
    },
    // 아동 캐시는 화면을 떠났어도 갱신한다(활동은 이미 배정됐다).
    onSuccess: (_activity, { target }) => {
      queryClient.invalidateQueries({ queryKey: ['children', target.childId] })
    },
  })

  function handleAssign() {
    if (!child || !activityType || (needsGoal && !goal) || isSubmittingRef.current) {
      return
    }
    isSubmittingRef.current = true
    setSent({ childId: child.childId, type: activityType })
    assignMutation.mutate(
      { target: child, type: activityType, choice: needsGoal ? goal : null },
      {
        // 아동 상세 이동은 mutate 쪽 콜백에 둔다. 이 콜백은 화면이 사라지면 실행되지 않아, 배정 중 사이드바·뒤로가기로
        // 다른 화면에 간 뒤 응답이 도착해도 강제로 이동시키지 않는다. useMutation 쪽 onSuccess 는 화면이 사라져도 실행된다.
        onSuccess: (_activity, { target }) => {
          navigate(`/classrooms/${target.classId}/children/${target.childId}`)
        },
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

  const stepIndex = steps.findIndex((s) => s.key === step)

  return (
    <InstructorLayout
      title={STEP_TITLES[step]}
      actions={
        <span className="text-xs text-[var(--instructor-text-muted)]">
          {stepIndex + 1} / {steps.length} 단계
        </span>
      }
      subheader={<WizardStepper steps={steps} current={step} />}
      headerTools={false}
      fill
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
          onNext={() => moveTo('type')}
        />
      )}

      {step === 'type' && child && (
        <TypeStep
          child={child}
          availability={availability}
          activitiesError={activitiesQuery.error}
          selected={activityType}
          onSelect={selectType}
          onBack={() => moveTo('target')}
          onNext={() => moveTo(needsGoal ? 'goal' : 'confirm')}
        />
      )}

      {step === 'goal' && (
        <div className="flex flex-col gap-4">
          <div role="tablist" aria-label="학습 목표 입력 방식" className="flex gap-1 border-b border-[var(--instructor-border)]">
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
                className={cn(tabBaseClass, goalTab === key ? tabSelectedClass : tabIdleClass)}
              >
                {label}
              </button>
            ))}
          </div>

          {/* 시안 T-ACT-03: 왼쪽은 목표 고르기, 오른쪽은 작은 목표 미리보기와 이동 버튼 */}
          <div className="grid flex-1 grid-cols-2 gap-4">
          {goalTab === 'preset' ? (
            <section className={cn(cardClass, 'flex flex-col gap-4 p-5')} aria-label="목표 목록">
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
                    className={filterButtonClass}
                  >
                    {GOAL_CATEGORY_LABELS[category]}
                  </button>
                ))}
              </div>
              <fieldset className="flex flex-1 flex-col rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border)]">
                <legend className="sr-only">학습 목표</legend>
                {GOAL_PRESETS.filter((p) => p.category === categoryFilter).map((p) => (
                  <label
                    key={p.id}
                    className={cn(
                      'flex cursor-pointer items-start gap-3 border-b border-[var(--instructor-border-subtle)] px-4 py-4',
                      presetId === p.id && 'bg-[var(--instructor-surface-muted)]'
                    )}
                  >
                    <input
                      type="radio"
                      name="goal-preset"
                      checked={presetId === p.id}
                      onChange={() => setPresetId(p.id)}
                    />
                    <span>
                      <span className={cn('block text-sm', presetId === p.id && 'font-semibold')}>{p.title}</span>
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
            <section className={cn(cardClass, 'flex flex-col gap-5 p-5')} aria-label="직접 입력">
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

            <div className="flex flex-col gap-3">
              <section className={cn(cardClass, 'flex flex-1 flex-col gap-1 p-5')} aria-labelledby="sub-goal-heading">
                <h2 id="sub-goal-heading" className="text-sm font-semibold">
                  작은 목표 미리보기
                </h2>
                <p className="text-xs text-[var(--instructor-text-muted)]">선택한 목표에서 자동 분해됩니다.</p>
                <p className="mt-3 rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-4 py-3 text-sm text-[var(--instructor-text-muted)]">
                  작은 목표 분해는 준비 중이에요.
                </p>
              </section>
              <div className="flex gap-2.5">
                <button type="button" onClick={() => moveTo('type')} className={outlineButtonClass}>
                  이전
                </button>
                <button
                  type="button"
                  disabled={!goal}
                  onClick={() => moveTo('confirm')}
                  className={cn(primaryButtonClass, 'flex-1')}
                >
                  다음 · 확인
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {step === 'confirm' && child && activityType && (
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
              {needsGoal && goal && (
                <>
                  <dt className="text-[var(--instructor-text-muted)]">학습 목표</dt>
                  <dd>
                    <span className="font-semibold">{goal.title}</span>
                    {goal.category && (
                      <span className="text-[var(--instructor-text-muted)]">
                        {' '}
                        · {GOAL_CATEGORY_LABELS[goal.category]}
                      </span>
                    )}
                  </dd>
                </>
              )}
              <dt className="text-[var(--instructor-text-muted)]">활동</dt>
              <dd>
                <span className="font-semibold">{ACTIVITY_TYPE_LABELS[activityType]}</span>
                <span className="block text-xs text-[var(--instructor-text-muted)]">
                  {COMPOSITION_TEXT[activityType]}
                </span>
              </dd>
            </dl>
          </section>

          {assignMutation.isError && (
            <Notice tone="error" role="alert">
              {assignErrorMessage}
            </Notice>
          )}

          <div className="flex justify-between">
            <button
              type="button"
              onClick={() => moveTo(needsGoal ? 'goal' : 'type')}
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
              <ButtonLabel pending={assignMutation.isPending} label="배정하기" pendingLabel="배정 중..." />
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

  // 1280px 보다 좁으면 두 패널을 세로로 쌓는다. 2열로 두면 대상 리스트의 아동 이름 칸이 줄어 카드 밖으로 넘친다.
  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
      <section className={cn(cardClass, 'flex flex-col gap-4 p-5')} aria-label="대상 범위">
        <h2 className="text-sm font-semibold">대상 범위</h2>
        {/* 시안의 "학급 전체" 배정은 아직 없어 비활성으로 두고, 지금 되는 "특정 아동 선택"을 고른 상태로 보인다. */}
        <div role="radiogroup" aria-label="대상 범위" className="flex flex-col gap-3">
          <ScopeOption title="학급 전체" description="학급 아동 모두에게 같은 활동을 배정합니다. 준비 중이에요." />
          <ScopeOption title="특정 아동 선택" description="아동 한 명에게 활동을 배정합니다." selected />
        </div>
        <label className={cn(fieldLabelClass, 'pt-2')}>
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
        {classroomsError && <Notice tone="error">{classroomsError.message}</Notice>}
      </section>

      <section className={cn(cardClass, 'flex flex-col gap-4 p-5')} aria-label="대상 리스트">
        <h2 className="text-sm font-semibold">대상 리스트</h2>
        {!classId ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">학급을 먼저 골라 주세요.</p>
        ) : isChildrenLoading ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
        ) : childrenError ? (
          <Notice tone="error">{childrenError.message}</Notice>
        ) : activeChildren?.length === 0 ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">이 학급에 참여 중인 아동이 없습니다.</p>
        ) : (
          // 시안의 난이도·지원·최근 활동 열은 아동별 값이 아직 없어 학급 목록의 빈 열처럼 "—" 로 둔다.
          <fieldset className="flex-1 overflow-hidden rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border)]">
            <legend className="sr-only">아동</legend>
            <div className={cn(tableHeadRowClass, TARGET_COLUMNS, 'py-3')} aria-hidden>
              <span />
              <span>아동</span>
              <span>난이도</span>
              <span>지원</span>
              <span>최근 활동</span>
            </div>
            {activeChildren?.map((c) => (
              <label
                key={c.childId}
                className={cn(
                  TARGET_COLUMNS,
                  'cursor-pointer border-b border-[var(--instructor-border-subtle)] py-3 text-sm',
                  childId === c.childId && 'bg-[var(--instructor-surface-muted)]'
                )}
              >
                <input
                  type="radio"
                  name="target-child"
                  checked={childId === c.childId}
                  onChange={() => onSelectChild(c.childId)}
                  className="mx-auto"
                />
                <span className="break-keep">{c.displayName}</span>
                <span aria-hidden className="text-[var(--instructor-text-muted)]">—</span>
                <span aria-hidden className="text-[var(--instructor-text-muted)]">—</span>
                <span aria-hidden className="text-[var(--instructor-text-muted)]">—</span>
              </label>
            ))}
          </fieldset>
        )}

        <div className="mt-auto flex items-center justify-between gap-2 pt-2">
          <span className="text-xs text-[var(--instructor-text-muted)]">선택 {childId ? 1 : 0}명</span>
          <div className="flex gap-2.5">
            <button type="button" onClick={onCancel} className={outlineButtonClass}>
              취소
            </button>
            <button type="button" disabled={!childId} onClick={onNext} className={primaryButtonClass}>
              다음 · 활동 유형 선택
            </button>
          </div>
        </div>
      </section>
    </div>
  )
}

// 대상 리스트의 열: 고르기 · 아동 · 난이도 · 지원 · 최근 활동. 아동 이름 칸은 아무리 좁아도 96px 은 남긴다.
const TARGET_COLUMNS = 'grid grid-cols-[40px_minmax(96px,1fr)_64px_64px_88px] items-center px-1'

/**
 * 대상 범위 선택지. 지금은 고를 수 있는 범위가 하나라 고르는 동작은 없고, 선택된 것과 준비 중(비활성)인 것을 라디오로 알린다.
 * 비활성 선택지는 aria-disabled 로 표시해 화면 낭독기가 "선택할 수 없음"으로 읽는다.
 */
function ScopeOption({ title, description, selected = false }: { title: string; description: string; selected?: boolean }) {
  return (
    <div
      role="radio"
      aria-checked={selected}
      aria-disabled={!selected}
      className={cn(
        'flex items-center gap-3 rounded-[var(--instructor-radius-control)] border px-4 py-4',
        selected
          ? 'border-2 border-[var(--instructor-primary)]'
          : 'border-[var(--instructor-border)] text-[var(--instructor-text-disabled)]'
      )}
    >
      {/* 라디오 모양은 index.css 의 디자인 시스템 Radio 와 같은 값(내용 20px, 빈 칸 테두리 1·선택 6)이다. */}
      <span
        aria-hidden
        className={cn(
          'box-content size-5 shrink-0 rounded-full',
          selected
            ? 'border-[6px] border-[var(--instructor-primary)]'
            : 'border border-[var(--instructor-border)] bg-[var(--instructor-disabled-bg)]'
        )}
      />
      <span>
        <span className="block text-sm font-semibold">{title}</span>
        <span className={cn('block text-xs', selected && 'text-[var(--instructor-text-muted)]')}>{description}</span>
      </span>
    </div>
  )
}

/**
 * 활동 유형 (시안의 활동 유형 화면). 시안의 카드 3개를 그대로 두고, "표정 퀴즈만" 카드 안에서 내 감정 / 상대 감정을 고른다.
 * 지금 고를 수 없는 카드는 이유와 함께 비활성으로 보인다.
 *
 * 카드 묶음은 기본 라디오 버튼처럼 움직인다. Tab 은 선택된 카드(없으면 고를 수 있는 첫 카드)에만 멈추고, 방향키로 고를 수 있는 카드 사이를 옮기며
 * 바로 선택한다. 화면 낭독기 사용자는 라디오 묶음에서 방향키를 먼저 눌러 보기 때문이다.
 */
function TypeStep({
  child,
  availability,
  activitiesError,
  selected,
  onSelect,
  onBack,
  onNext,
}: {
  child: Child
  availability: Availability | null
  activitiesError: Error | null
  selected: ActivityType | null
  onSelect: (type: ActivityType) => void
  onBack: () => void
  onNext: () => void
}) {
  const isQuizSelected = selected === 'SELF_EMOTION_QUIZ' || selected === 'OTHER_EMOTION_QUIZ'
  const cardRefs = useRef<(HTMLDivElement | null)[]>([])

  if (activitiesError) {
    return <Notice tone="error">{activitiesError.message}</Notice>
  }
  if (!availability) {
    return <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
  }

  // 화면 순서대로. 역할극은 아직 열리지 않는다.
  const cards = [
    {
      enabled: availability.firstCombined,
      checked: selected === 'FIRST_COMBINED',
      select: () => onSelect('FIRST_COMBINED'),
    },
    {
      enabled: availability.quiz,
      checked: isQuizSelected,
      select: () => !isQuizSelected && onSelect('SELF_EMOTION_QUIZ'),
    },
    { enabled: false, checked: false, select: () => {} },
  ]
  const checkedIndex = cards.findIndex((card) => card.enabled && card.checked)
  const tabStop = checkedIndex >= 0 ? checkedIndex : cards.findIndex((card) => card.enabled)

  function moveSelection(e: KeyboardEvent<HTMLDivElement>) {
    const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key]
    // 퀴즈 종류 라디오는 진짜 input 이라 브라우저가 방향키를 처리한다. 카드에서 누른 방향키만 여기서 다룬다.
    const from = cardRefs.current.indexOf(e.target as HTMLDivElement)
    if (step === undefined || from < 0) return
    e.preventDefault()
    // 고를 수 있는 카드가 하나뿐이면 한 바퀴 돌아 그 카드를 고른다(기본 라디오와 같다).
    for (let i = 1; i <= cards.length; i += 1) {
      const next = (from + step * i + cards.length) % cards.length
      if (cards[next].enabled) {
        cards[next].select()
        cardRefs.current[next]?.focus()
        return
      }
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="text-xl font-semibold">어떤 활동을 만들까요?</h2>
        <p className="mt-1 text-sm text-[var(--instructor-text-muted)]">
          {child.displayName} · {typeGuide(availability)}
        </p>
      </div>

      <div role="radiogroup" aria-label="활동 유형" onKeyDown={moveSelection} className="grid gap-4 lg:grid-cols-3">
        <TypeCard
          radioRef={(el) => {
            cardRefs.current[0] = el
          }}
          tabbable={tabStop === 0}
          title={ACTIVITY_TYPE_LABELS.FIRST_COMBINED}
          description="표정 퀴즈로 감정 인식을 확인한 뒤 같은 주제로 역할극을 이어갑니다."
          meta={['예상 소요 15~20분', '난이도·지원 수준 자동 산정']}
          badge={availability.firstCombined ? '권장' : undefined}
          disabledReason={availability.firstCombined ? undefined : '최초 활동은 아동마다 한 번이에요.'}
          checked={cards[0].checked}
          onSelect={cards[0].select}
        />
        <TypeCard
          radioRef={(el) => {
            cardRefs.current[1] = el
          }}
          tabbable={tabStop === 1}
          title="표정 퀴즈만"
          description="상황과 표정을 보고 감정을 고르는 문항만 배정합니다."
          meta={['예상 소요 10~15분', '10문항']}
          disabledReason={availability.quiz ? undefined : '최초 활동을 마치면 열려요.'}
          checked={cards[1].checked}
          onSelect={cards[1].select}
        >
          {isQuizSelected && (
            <fieldset className="mt-4 flex flex-col gap-2 border-t border-[var(--instructor-border)] pt-4">
              <legend className="sr-only">표정 퀴즈 종류</legend>
              {QUIZ_TYPES.map((type) => (
                <label key={type} className="flex cursor-pointer items-center gap-2 text-sm">
                  <input
                    type="radio"
                    name="quiz-type"
                    checked={selected === type}
                    onChange={() => onSelect(type)}
                  />
                  {type === 'SELF_EMOTION_QUIZ' ? '내 감정 알기' : '상대 감정 알기'}
                </label>
              ))}
            </fieldset>
          )}
        </TypeCard>
        <TypeCard
          radioRef={(el) => {
            cardRefs.current[2] = el
          }}
          tabbable={tabStop === 2}
          title="역할극만"
          description="상황 시나리오로 대화를 주고받으며 공감 표현을 연습합니다."
          meta={['예상 소요 10~15분', '시나리오 승인 필요']}
          disabledReason="역할극은 준비 중이에요."
          checked={cards[2].checked}
          onSelect={cards[2].select}
        />
      </div>

      <div className="mt-auto flex justify-between">
        <button type="button" onClick={onBack} className={outlineButtonClass}>
          이전
        </button>
        <button type="button" disabled={!selected} onClick={onNext} className={primaryButtonClass}>
          {selected === 'FIRST_COMBINED' ? '다음 · 학습 목표 설정' : '다음 · 확인'}
        </button>
      </div>
    </div>
  )
}

function TypeCard({
  radioRef,
  tabbable,
  title,
  description,
  meta,
  badge,
  disabledReason,
  checked,
  onSelect,
  children,
}: {
  radioRef: (el: HTMLDivElement | null) => void
  tabbable: boolean
  title: string
  description: string
  meta: string[]
  badge?: string
  disabledReason?: string
  checked: boolean
  onSelect: () => void
  children?: ReactNode
}) {
  const disabled = disabledReason !== undefined
  // 카드 틀과 고르는 부분(role="radio")을 나눈다. "표정 퀴즈만"의 하위 라디오(children)는 고르는 부분 밖, 카드 틀 안에 둔다.
  // 라디오 안에 다른 입력이 들어가면 화면 낭독기가 하위 입력을 제대로 읽지 못한다(nested-interactive).
  return (
    <div
      className={cn(
        cardClass,
        'flex flex-col p-6',
        disabled ? 'text-[var(--instructor-text-disabled)]' : 'hover:border-[var(--instructor-primary)]',
        checked && 'border-2 border-[var(--instructor-primary)]'
      )}
    >
      <div
        ref={radioRef}
        role="radio"
        aria-checked={checked}
        aria-disabled={disabled}
        aria-label={title}
        tabIndex={tabbable ? 0 : -1}
        onClick={() => !disabled && onSelect()}
        onKeyDown={(e) => {
          if (!disabled && (e.key === 'Enter' || e.key === ' ')) {
            e.preventDefault()
            onSelect()
          }
        }}
        className={cn('flex flex-1 flex-col text-left', disabled ? 'cursor-not-allowed' : 'cursor-pointer')}
      >
        {/* 시안의 그림 자리(40px, 모서리 8). 그림이 아직 없어 빈 칸으로 두고, 고를 수 없는 카드는 점선으로 그린다. */}
        <div className="flex items-start justify-between">
          <span
            aria-hidden
            className={cn(
              'box-content size-10 rounded-[var(--instructor-radius-control)]',
              disabled
                ? 'border border-dashed border-[var(--instructor-border-strong)] bg-[var(--instructor-disabled-bg)]'
                : 'bg-[var(--instructor-avatar)]'
            )}
          />
          {badge && (
            <span className={cn(badgeClass, 'bg-[var(--instructor-info-bg)] text-[var(--instructor-info-fg)]')}>
              {badge}
            </span>
          )}
        </div>
        <span className="mt-4 text-base font-semibold">{title}</span>
        <p className={cn('mt-2 text-sm', !disabled && 'text-[var(--instructor-text-muted)]')}>{description}</p>
        <ul className={cn('mt-2 text-xs leading-5', !disabled && 'text-[var(--instructor-text-muted)]')}>
          {meta.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
        {disabledReason && <p className="mt-2 text-xs font-semibold">{disabledReason}</p>}
      </div>
      {children}
    </div>
  )
}

export { ActivityCreatePage }
