import type { ReviewScenario } from '../api'
import { OpeningPrompts, ScenarioSections } from './ScenarioContent'
import { Notice } from './Feedback'
import {
  cardClass,
  dangerOutlineButtonClass,
  fieldLabelClass,
  helperTextClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
} from './styles'
import { MAX_EVIDENCE, type MicroGoalForm, type ScenarioForm } from '../lib/scenarioEdits'
import { cn } from '@/lib/utils'

// 시안(T-SCN-03)의 빠른 지시. 지시로 다시 만드는 기능이 없어 누를 수 없다.
const QUICK_INSTRUCTIONS = ['턴 수 줄이기', '쉬운 낱말로', '유도 질문 보강']

/**
 * 시나리오 수정(시안 T-SCN-03 배치). 제목·상황·작은 목표(설명·필요 증거)만 고친다. 고친 내용은 따로 저장하지 않고
 * [승인으로 이동] → [승인] 때 한 번에 보낸다. 수정 지시와 [재생성]은 지시로 다시 만드는 기능이 없어 비활성이다.
 */
function ScenarioEditView({
  scenario,
  form,
  problems,
  pending,
  onChange,
  onBack,
  onConfirm,
  onNext,
  onCancel,
}: {
  scenario: ReviewScenario
  form: ScenarioForm
  problems: string[]
  pending: boolean
  onChange: (form: ScenarioForm) => void
  onBack: () => void
  onConfirm: () => void
  onNext: () => void
  onCancel: () => void
}) {
  function updateGoal(microGoalId: string, patch: Partial<MicroGoalForm>) {
    onChange({
      ...form,
      microGoals: form.microGoals.map((goal) => (goal.microGoalId === microGoalId ? { ...goal, ...patch } : goal)),
    })
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3">
        <span className="text-xs text-[var(--instructor-text-subtle)]">
          v{scenario.scenarioVersion} → v{scenario.scenarioVersion + 1} 작성 중 · 승인하기 전까지 저장되지 않습니다
        </span>
      </div>
      {problems.length > 0 && (
        <Notice tone="error" role="alert">
          {problems.join(' ')}
        </Notice>
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
                onChange={(event) => onChange({ ...form, title: event.target.value })}
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
                    onChange={(event) => onChange({ ...form, summary: event.target.value })}
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
                          onChange={(event) => updateGoal(goal.microGoalId, { description: event.target.value })}
                        />
                      </label>
                      <label className={fieldLabelClass}>
                        필요 증거
                        <textarea
                          className={cn(inputClass, 'min-h-16 py-2')}
                          value={goal.evidence}
                          onChange={(event) => updateGoal(goal.microGoalId, { evidence: event.target.value })}
                        />
                        <span className={helperTextClass}>한 줄에 하나씩 적습니다(1~{MAX_EVIDENCE}개).</span>
                      </label>
                    </li>
                  ))}
                </ol>
              ),
            }}
          />
          <p className={cn(helperTextClass, 'border-t border-[var(--instructor-border-subtle)] px-5 py-3')}>
            고정 사실·등장인물·시작 질문은 고치지 않습니다. 고친 내용은 새 버전으로 저장되고 원본은 그대로 남습니다.
          </p>
        </div>

        <div className="flex flex-col gap-4">
          <div className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
            <h3 className="text-sm font-semibold">수정 지시 입력</h3>
            <p className={helperTextClass}>지시로 다시 만드는 기능은 아직 없습니다.</p>
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

          <div className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
            <h3 className="text-sm font-semibold">처리</h3>
            <button type="button" disabled className={outlineButtonClass} title="지시로 다시 만드는 기능은 아직 없어요">
              재생성
            </button>
            <button
              type="button"
              disabled={pending || problems.length > 0}
              onClick={onConfirm}
              className={primaryButtonClass}
            >
              승인으로 이동
            </button>
            <div className="grid grid-cols-2 gap-2">
              <button type="button" disabled={pending} onClick={onNext} className={outlineButtonClass}>
                다른 후보
              </button>
              <button type="button" disabled={pending} onClick={onCancel} className={dangerOutlineButtonClass}>
                검토 그만하기
              </button>
            </div>
            <button type="button" disabled={pending} onClick={onBack} className={outlineButtonClass}>
              고치지 않고 검토로 돌아가기
            </button>
            <p className={helperTextClass}>
              [다른 후보]는 이번 검토에서만 넘깁니다. 다른 강사에게는 이 시나리오가 계속 보입니다.
            </p>
          </div>
          <OpeningPrompts scenario={scenario} />
        </div>
      </div>
    </div>
  )
}

export { ScenarioEditView }
