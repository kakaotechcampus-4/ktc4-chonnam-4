import * as React from "react"
import { Dialog as DialogPrimitive } from "radix-ui"
import { ChevronRight } from "lucide-react"
import { cn } from "@/lib/utils"
import { ChildButton } from "../ChildButton"
import { Character } from "../Character"
import { StateDialogContext } from "./stateDialogContext"
import {
  AddressBarIllustration,
  AppCameraToggleIllustration,
  RetryButtonIllustration,
  SettingsAppIllustration,
  SettingsListIllustration,
  SitePermissionIllustration,
} from "./CameraGuideIllustrations"

type GuideStep = { art: React.ReactNode; text: string }

// 카메라 권한은 두 겹이다: Chrome 안의 사이트 권한(주소창)과 기기 설정의 Chrome 앱 권한(설정 앱).
// 흔한 경우인 사이트 권한을 먼저 보여 주고, 그래도 안 되면 기기 설정을 보여 준다.
// 메뉴 이름은 OS·Chrome 버전마다 조금씩 달라 실제 기기로 확인이 필요하다.
const SITE_STEPS: GuideStep[] = [
  { art: <AddressBarIllustration />, text: "주소창 왼쪽 아이콘을 눌러요" },
  { art: <SitePermissionIllustration />, text: "카메라를 허용해요" },
  { art: <RetryButtonIllustration />, text: "아래 버튼을 눌러요" },
]

const DEVICE_STEPS: GuideStep[] = [
  { art: <SettingsAppIllustration />, text: "설정 앱을 열어요" },
  { art: <SettingsListIllustration />, text: "Chrome을 찾아요" },
  { art: <AppCameraToggleIllustration />, text: "카메라를 켜요" },
]

const DEVICE_PATHS = [
  { device: "갤럭시 탭", path: "설정 › 애플리케이션 › Chrome › 권한 › 카메라" },
  { device: "아이패드", path: "설정 › Chrome › 카메라" },
]

function GuideSteps({ steps }: { steps: GuideStep[] }) {
  return (
    <ol className="flex items-start gap-1">
      {steps.map((step, index) => (
        <li key={step.text} className="flex min-w-0 flex-1 items-start gap-1">
          <div className="flex min-w-0 flex-1 flex-col items-center gap-1.5 text-center">
            {/* 두 방법과 버튼이 태블릿 가로 화면(768px 높이)에 스크롤 없이 들어가도록 그림 크기를 제한한다. */}
            <div className="w-full max-w-[8.5rem]">{step.art}</div>
            <p className="text-sm font-semibold text-[var(--child-text)]">
              <span className="mr-1 text-[var(--child-primary)]">{index + 1}</span>
              {step.text}
            </p>
          </div>
          {index < steps.length - 1 ? (
            <ChevronRight aria-hidden="true" className="mt-10 size-4 shrink-0 text-[var(--child-text-muted)]" />
          ) : null}
        </li>
      ))}
    </ol>
  )
}

function GuideSection({ badge, title, children }: { badge: string; title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-2.5 text-left">
      <h3 className="flex items-center gap-2 text-base font-bold text-[var(--child-text)]">
        <span className="rounded-[var(--child-radius-pill)] bg-[var(--child-primary)] px-2.5 py-0.5 text-sm text-white">
          {badge}
        </span>
        {title}
      </h3>
      {children}
    </section>
  )
}

/**
 * 카메라 권한 거부 안내 (C-ONB-03 거부 시 안내, 사이트맵 "카메라 권한 거부").
 * 웹은 보안상 브라우저·기기 설정 화면으로 바로 보낼 수 없어, 설정 방법을 그림으로 보여 준다.
 * 아이가 직접 바꾸기는 어려워 선생님께 보여 주도록 한다.
 * 새로 고침하면 아동 세션이 사라지므로 새로 고침 대신 "다시 확인하기"로 권한을 다시 확인한다.
 */
function CameraPermissionGuide({ onRetry }: { onRetry: () => void }) {
  const inDialog = React.useContext(StateDialogContext)

  const title = <p className="font-child-display text-xl font-extrabold text-[var(--child-text)]">카메라를 쓸 수 없어요</p>
  const description = <p className="text-lg text-[var(--child-text-muted)]">선생님께 이 화면을 보여줘요</p>

  return (
    <div
      className={cn(
        "flex flex-col gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)] px-6 py-6 break-keep sm:px-8",
        inDialog && "shadow-xl"
      )}
    >
      <div className="flex items-center gap-4 text-left">
        <Character name="turtle" size="sm" />
        <div className="flex flex-col gap-1">
          {inDialog ? <DialogPrimitive.Title asChild>{title}</DialogPrimitive.Title> : title}
          {inDialog ? <DialogPrimitive.Description asChild>{description}</DialogPrimitive.Description> : description}
        </div>
      </div>

      <GuideSection badge="방법 1" title="먼저 해 보세요">
        <GuideSteps steps={SITE_STEPS} />
      </GuideSection>

      <GuideSection badge="방법 2" title="그래도 안 되면 (태블릿)">
        <GuideSteps steps={DEVICE_STEPS} />
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 rounded-xl bg-[var(--child-surface-muted)] px-4 py-2.5 text-sm">
          {DEVICE_PATHS.map(({ device, path }) => (
            <React.Fragment key={device}>
              <dt className="font-bold text-[var(--child-text)]">{device}</dt>
              <dd className="text-[var(--child-text-muted)]">{path}</dd>
            </React.Fragment>
          ))}
        </dl>
        <p className="text-sm text-[var(--child-text-muted)]">켠 뒤 느링고로 돌아와 아래 버튼을 눌러 주세요.</p>
      </GuideSection>

      <ChildButton className="self-center" onClick={onRetry}>
        다시 확인하기
      </ChildButton>
    </div>
  )
}

export { CameraPermissionGuide }
