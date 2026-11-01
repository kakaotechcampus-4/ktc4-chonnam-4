import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  attachActivityScenario,
  createScenarioVersion,
  getLearningGoal,
  getScenarioCandidate,
} from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ApprovalConfirmPanel } from '../components/ApprovalConfirmPanel'
import { OpeningPrompts, ScenarioSections } from '../components/ScenarioContent'
import { ScenarioStatusBadge } from '../components/ScenarioBadges'
import { ButtonLabel } from '../components/ButtonLabel'
import { Notice } from '../components/Feedback'
import { cardClass, outlineButtonClass, primaryButtonClass } from '../components/styles'
import { describeError } from '../errorMessage'
import { cn } from '@/lib/utils'

const linkClass = 'font-semibold underline underline-offset-2'

/**
 * 재사용 후보 검토(시안 T-SCN-02 배치). 최초 결합 활동에 쓸 공용 시나리오를 하나씩 보고
 * [승인](확인 단계를 거쳐 활동에 붙임) · [수정하기](이 목표용 새 판을 만들어 고침) · [다음 후보] · [새로 만들기](시나리오 생성 전까지 비활성) 중 고른다.
 * 넘겨 본 후보는 서버에 남지 않아, 화면을 다시 열면 처음 후보부터 보인다.
 */
function ScenarioCandidatePage() {
  const { activityId } = useParams<{ activityId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  // 지금까지 본 후보. [이전 후보] 없이 [다음 후보]만 있어 마지막 것만 쓰지만, 다음 후보 조회 기준(after)이 된다.
  const [after, setAfter] = useState<string | undefined>(undefined)
  const [confirming, setConfirming] = useState(false)

  const candidateQuery = useQuery({
    queryKey: ['activities', activityId, 'scenario-candidate', after ?? null],
    queryFn: () => getScenarioCandidate(activityId!, after),
    enabled: !!activityId,
  })
  const scenario = candidateQuery.data?.scenario ?? null
  const hasNext = candidateQuery.data?.hasNext ?? false

  const goalQuery = useQuery({
    queryKey: ['learning-goals', scenario?.goalId],
    queryFn: () => getLearningGoal(scenario!.goalId!),
    enabled: !!scenario?.goalId,
  })

  const approve = useMutation({
    mutationFn: () => attachActivityScenario(activityId!, scenario!.scenarioId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['scenario-approvals'] })
      // 활동 상세만 새로 받는다. 후보 조회까지 다시 부르면 이미 붙었다는 409 가 성공 안내를 덮는다.
      void queryClient.invalidateQueries({ queryKey: ['activities', activityId], exact: true })
    },
  })

  const edit = useMutation({
    mutationFn: () => createScenarioVersion(scenario!.scenarioId, scenario!.version, scenario!.goalId!),
    onSuccess: (draft) => {
      void queryClient.invalidateQueries({ queryKey: ['scenario-approvals'] })
      navigate(`/scenarios/${draft.scenarioId}/edit`)
    },
  })

  const alreadyAttached =
    candidateQuery.error instanceof ApiError && candidateQuery.error.code === 'SCENARIO_ALREADY_ATTACHED'
  const draftExists = edit.error instanceof ApiError && edit.error.code === 'SCENARIO_DRAFT_EXISTS'

  const actions =
    scenario && !confirming && !approve.isSuccess ? (
      <>
        <button
          type="button"
          disabled
          title="시나리오 생성이 들어오면 새로 만들 수 있어요"
          className={outlineButtonClass}
        >
          새로 만들기
        </button>
        <button
          type="button"
          disabled={!hasNext || candidateQuery.isFetching}
          onClick={() => setAfter(scenario.scenarioId)}
          className={outlineButtonClass}
        >
          다음 후보
        </button>
        <button type="button" disabled={edit.isPending} onClick={() => edit.mutate()} className={outlineButtonClass}>
          <ButtonLabel pending={edit.isPending} label="수정하기" pendingLabel="준비 중..." />
        </button>
        <button type="button" onClick={() => setConfirming(true)} className={primaryButtonClass}>
          승인으로 이동
        </button>
      </>
    ) : undefined

  return (
    <InstructorLayout title="시나리오 검토" actions={actions}>
      <nav aria-label="현재 위치" className="mb-4 text-xs text-[var(--instructor-text-subtle)]">
        <Link to="/content" className="hover:underline">
          승인 대기함
        </Link>
      </nav>

      {approve.isSuccess ? (
        <Notice tone="success" role="status">
          승인했습니다. 아동이 이 시나리오로 역할극을 시작할 수 있습니다.{' '}
          <Link to="/content" className={linkClass}>
            승인 대기함으로
          </Link>
        </Notice>
      ) : candidateQuery.isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : alreadyAttached ? (
        <Notice tone="info">
          이미 시나리오가 정해진 활동입니다.{' '}
          <Link to="/content" className={linkClass}>
            승인 대기함으로
          </Link>
        </Notice>
      ) : candidateQuery.isError ? (
        <Notice tone="error">{describeError(candidateQuery.error, '후보를 불러오지 못했습니다.')}</Notice>

      ) : !scenario ? (
        <div className="flex flex-col gap-3">
          <Notice tone="info">
            재사용할 수 있는 시나리오가 없습니다. 시나리오 생성이 들어오면 [새로 만들기]로 만들 수 있습니다.
          </Notice>
          {after && (
            <button type="button" onClick={() => setAfter(undefined)} className={cn(outlineButtonClass, 'self-start')}>
              처음 후보부터 다시 보기
            </button>
          )}
        </div>
      ) : confirming ? (
        <ApprovalConfirmPanel
          scenario={scenario}
          goal={goalQuery.data}
          change="재사용 · 내용 변경 없음"
          backLabel="검토로 돌아가기"
          isPending={approve.isPending}
          error={approve.isError ? describeError(approve.error, '승인하지 못했습니다.') : null}
          onBack={() => setConfirming(false)}
          onApprove={() => approve.mutate()}
        />
      ) : (
        <div className="flex flex-col gap-4">
          {edit.isError && (
            <Notice tone="error" role="alert">
              {draftExists ? (
                <>
                  이 목표로 고치는 중인 시나리오가 이미 있습니다.{' '}
                  <Link to="/content" className={linkClass}>
                    승인 대기함
                  </Link>
                  에서 이어서 고쳐 주세요.
                </>
              ) : (
                describeError(edit.error, '수정할 판을 만들지 못했습니다.')
              )}
            </Notice>
          )}
          <div className="grid grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] items-start gap-4">
            <div className={cardClass}>
              <div className="flex items-start justify-between gap-3 px-5 py-4">
                <div>
                  <h2 className="text-base font-semibold">{scenario.title}</h2>
                  <p className="mt-1 text-xs text-[var(--instructor-text-subtle)]">
                    v{scenario.version} · {scenario.scenarioLevel}
                    {goalQuery.data ? ` · ${goalQuery.data.title}` : ''}
                  </p>
                </div>
                <ScenarioStatusBadge status={scenario.status} />
              </div>
              <ScenarioSections scenario={scenario} />
            </div>
            <OpeningPrompts scenario={scenario} />
          </div>
          <p className="text-xs text-[var(--instructor-text-subtle)]">
            {hasNext ? '[다음 후보]로 다른 시나리오를 볼 수 있습니다.' : '마지막 후보입니다.'} 넘겨 본 후보는 저장되지 않습니다.
          </p>
        </div>
      )}
    </InstructorLayout>
  )
}

export { ScenarioCandidatePage }
