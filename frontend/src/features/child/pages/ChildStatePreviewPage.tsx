import { ChildLayout } from "../layout/ChildLayout"
import {
  LoadingState,
  ErrorState,
  PermissionDeniedState,
  ExpiredState,
  NetworkState,
} from "../components/state"

/**
 * 5개 공통 상태 컴포넌트를 한 화면에 모아 보여주는 QA용 페이지.
 * 아직 퀴즈·역할극 실제 로직이 없어 이 화면들이 붙을 곳이 없으므로,
 * 팀 리뷰·완료 기준 확인용으로 임시 운영한다.
 */
function ChildStatePreviewPage() {
  return (
    <ChildLayout activityTitle="상태 컴포넌트 미리보기 (개발용)">
      <div className="flex flex-1 flex-col gap-6 py-6">
        <LoadingState />
        <ErrorState onRetry={() => {}} />
        <PermissionDeniedState device="camera" onRetry={() => {}} />
        <PermissionDeniedState device="microphone" onRetry={() => {}} />
        <ExpiredState />
        <NetworkState onRetry={() => {}} />
      </div>
    </ChildLayout>
  )
}

export { ChildStatePreviewPage }
