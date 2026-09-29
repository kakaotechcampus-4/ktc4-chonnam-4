import { useState } from "react"
import { ChildLayout } from "../layout/ChildLayout"
import { ChildButton } from "../components/ChildButton"
import {
  LoadingState,
  ErrorState,
  PermissionDeniedState,
  ExpiredState,
  NetworkState,
  StateDialog,
} from "../components/state"

const DIALOG_PREVIEWS = [
  { key: "error", label: "오류" },
  { key: "camera", label: "카메라 권한" },
  { key: "microphone", label: "마이크 권한" },
  { key: "expired", label: "코드 만료" },
  { key: "network", label: "네트워크" },
] as const

type DialogPreviewKey = (typeof DIALOG_PREVIEWS)[number]["key"]

/**
 * 공통 상태 컴포넌트를 한 화면에서 확인하는 QA용 페이지.
 * 로딩은 화면 안에 그대로 두고, 나머지 오류 상태는 실제 화면처럼 모달로 띄워 본다.
 */
function ChildStatePreviewPage() {
  const [openDialog, setOpenDialog] = useState<DialogPreviewKey | null>(null)
  const close = () => setOpenDialog(null)

  return (
    <ChildLayout activityTitle="상태 컴포넌트 미리보기 (개발용)">
      <div className="flex flex-1 flex-col gap-6 py-6">
        <LoadingState />

        <div className="flex flex-wrap justify-center gap-3">
          {DIALOG_PREVIEWS.map(({ key, label }) => (
            <ChildButton key={key} variant="outline" onClick={() => setOpenDialog(key)}>
              {label} 모달
            </ChildButton>
          ))}
        </div>
      </div>

      <StateDialog open={openDialog === "error"}>
        <ErrorState onRetry={close} />
      </StateDialog>
      <StateDialog open={openDialog === "camera"}>
        <PermissionDeniedState device="camera" onRetry={close} />
      </StateDialog>
      <StateDialog open={openDialog === "microphone"}>
        <PermissionDeniedState device="microphone" onRetry={close} />
      </StateDialog>
      <StateDialog open={openDialog === "expired"}>
        <ExpiredState onRetry={close} />
      </StateDialog>
      <StateDialog open={openDialog === "network"}>
        <NetworkState onRetry={close} />
      </StateDialog>
    </ChildLayout>
  )
}

export { ChildStatePreviewPage }
