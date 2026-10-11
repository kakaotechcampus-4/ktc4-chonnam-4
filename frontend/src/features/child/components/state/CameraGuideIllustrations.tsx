import type { ReactNode } from "react"

/**
 * 카메라 권한 안내 단계 그림. 실제 화면 캡처 대신 누를 곳만 강조한 단순한 그림이라,
 * OS·Chrome 화면이 조금 바뀌어도 안내가 크게 틀리지 않고 고치기도 쉽다.
 * 모든 그림은 같은 크기(160×110)이고, 누를 곳은 보라 동그라미로 표시한다.
 */

const PRIMARY = "#7B68EE"
const LINE = "#DEDCE4"
const TEXT = "#3A3742"

function Frame({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 160 110" className="h-auto w-full" aria-hidden="true">
      <rect x="1" y="1" width="158" height="108" rx="12" fill="#FFFFFF" stroke={LINE} strokeWidth="2" />
      {children}
    </svg>
  )
}

/** 누를 곳 표시. 보라 테두리 동그라미와 옅은 바깥 원. */
function TapMark({ cx, cy, r = 11 }: { cx: number; cy: number; r?: number }) {
  return (
    <>
      <circle cx={cx} cy={cy} r={r + 6} fill={PRIMARY} fillOpacity="0.15" />
      <circle cx={cx} cy={cy} r={r} fill="none" stroke={PRIMARY} strokeWidth="2.5" />
    </>
  )
}

/** 켜진 스위치 */
function ToggleOn({ x, y }: { x: number; y: number }) {
  return (
    <>
      <rect x={x} y={y} width="30" height="16" rx="8" fill={PRIMARY} />
      <circle cx={x + 22} cy={y + 8} r="6" fill="#FFFFFF" />
    </>
  )
}

/** 카메라 아이콘 (선) */
function CameraGlyph({ x, y }: { x: number; y: number }) {
  return (
    <g transform={`translate(${x} ${y})`} fill="none" stroke={TEXT} strokeWidth="1.8" strokeLinejoin="round">
      <rect x="0" y="3" width="13" height="10" rx="2" />
      <path d="M13 6.5 L18 4 V12 L13 9.5" />
    </g>
  )
}

/** 주소창 왼쪽 아이콘(조절 아이콘)을 누르는 그림. 방법 1의 1단계. */
export function AddressBarIllustration() {
  return (
    <Frame>
      {/* 브라우저 위쪽 막대와 주소창 */}
      <rect x="1" y="1" width="158" height="34" rx="12" fill="#F4F3F7" />
      <rect x="12" y="9" width="136" height="18" rx="9" fill="#FFFFFF" stroke={LINE} strokeWidth="1.5" />
      {/* 조절 아이콘: 가로줄 두 개와 손잡이 */}
      <g stroke={TEXT} strokeWidth="1.6" strokeLinecap="round">
        <path d="M20 15 H32" />
        <path d="M20 21 H32" />
      </g>
      <circle cx="24" cy="15" r="2.3" fill="#FFFFFF" stroke={TEXT} strokeWidth="1.6" />
      <circle cx="29" cy="21" r="2.3" fill="#FFFFFF" stroke={TEXT} strokeWidth="1.6" />
      <rect x="40" y="15" width="70" height="6" rx="3" fill={LINE} />
      <TapMark cx={26} cy={18} r={9} />
      {/* 아래는 느링고 화면 자리 */}
      <rect x="14" y="48" width="132" height="10" rx="5" fill="#F0EEF3" />
      <rect x="14" y="66" width="96" height="10" rx="5" fill="#F0EEF3" />
      <rect x="14" y="84" width="112" height="10" rx="5" fill="#F0EEF3" />
    </Frame>
  )
}

/** 주소창 아래로 펼쳐진 창에서 카메라를 허용으로 바꾸는 그림. 기기 공통(안드로이드는 "권한" 안에 있음). */
export function SitePermissionIllustration() {
  return (
    <Frame>
      <rect x="1" y="1" width="158" height="22" rx="12" fill="#F4F3F7" />
      <rect x="12" y="6" width="136" height="12" rx="6" fill="#FFFFFF" stroke={LINE} strokeWidth="1.2" />
      {/* 펼쳐진 창 */}
      <rect x="12" y="28" width="136" height="74" rx="10" fill="#FFFFFF" stroke={LINE} strokeWidth="1.5" />
      <rect x="24" y="38" width="64" height="7" rx="3.5" fill={LINE} />
      <path d="M20 56 H140" stroke={LINE} strokeWidth="1" />
      <CameraGlyph x={24} y={68} />
      <text x="48" y="80" fontSize="11" fontWeight="700" fill={TEXT}>
        카메라
      </text>
      <ToggleOn x={106} y={68} />
      <TapMark cx={121} cy={76} r={13} />
    </Frame>
  )
}

/** 홈 화면에서 회색 톱니바퀴 "설정" 앱을 찾는 그림. 방법 2의 1단계. */
export function SettingsAppIllustration() {
  const apps = [
    [22, 18],
    [62, 18],
    [102, 18],
    [22, 60],
    [102, 60],
  ]
  return (
    <Frame>
      <rect x="1" y="1" width="158" height="108" rx="12" fill="#EEF4FB" />
      {apps.map(([x, y]) => (
        <rect key={`${x}-${y}`} x={x} y={y} width="30" height="30" rx="8" fill="#FFFFFF" stroke={LINE} strokeWidth="1.2" />
      ))}
      {/* 설정 앱: 회색 바탕에 톱니바퀴 */}
      <rect x="62" y="60" width="30" height="30" rx="8" fill="#8E8B97" />
      <g transform="translate(77 75)" stroke="#FFFFFF" strokeWidth="2.4" strokeLinecap="round">
        {[0, 45, 90, 135].map((deg) => (
          <path key={deg} d="M0 -10 V10" transform={`rotate(${deg})`} />
        ))}
      </g>
      <circle cx="77" cy="75" r="6.5" fill="#8E8B97" stroke="#FFFFFF" strokeWidth="2.4" />
      <TapMark cx={77} cy={75} r={18} />
      <text x="77" y="106" fontSize="9" fontWeight="700" fill={TEXT} textAnchor="middle">
        설정
      </text>
    </Frame>
  )
}

/** 설정 앱 목록에서 Chrome을 찾는 그림. 방법 2의 2단계. */
export function SettingsListIllustration() {
  return (
    <Frame>
      <rect x="1" y="1" width="62" height="108" rx="12" fill="#F4F3F7" />
      {[14, 34, 54, 74].map((y) => (
        <rect key={y} x="10" y={y} width="44" height="8" rx="4" fill={y === 54 ? "transparent" : LINE} />
      ))}
      {/* 목록 중 Chrome 줄 */}
      <rect x="6" y="49" width="52" height="18" rx="6" fill="#FFFFFF" stroke={PRIMARY} strokeWidth="2" />
      <circle cx="15" cy="58" r="4.5" fill="none" stroke={TEXT} strokeWidth="1.6" />
      <circle cx="15" cy="58" r="1.6" fill={TEXT} />
      <text x="23" y="62" fontSize="9.5" fontWeight="700" fill={TEXT}>
        Chrome
      </text>
      {/* 오른쪽: Chrome 설정 내용 자리 */}
      <rect x="74" y="16" width="70" height="8" rx="4" fill={LINE} />
      <rect x="74" y="34" width="76" height="18" rx="6" fill="#F4F3F7" />
      <rect x="74" y="58" width="76" height="18" rx="6" fill="#F4F3F7" />
    </Frame>
  )
}

/** Chrome 설정 화면에서 카메라 스위치를 켜는 그림. 방법 2의 3단계. */
export function AppCameraToggleIllustration() {
  return (
    <Frame>
      <text x="16" y="24" fontSize="11" fontWeight="700" fill={TEXT}>
        Chrome
      </text>
      <rect x="12" y="34" width="136" height="64" rx="10" fill="#F4F3F7" />
      <rect x="22" y="44" width="60" height="7" rx="3.5" fill={LINE} />
      <path d="M18 60 H142" stroke={LINE} strokeWidth="1" />
      <CameraGlyph x={22} y={70} />
      <text x="46" y="82" fontSize="11" fontWeight="700" fill={TEXT}>
        카메라
      </text>
      <ToggleOn x={106} y={70} />
      <TapMark cx={121} cy={78} r={13} />
    </Frame>
  )
}

/** 느링고 화면에서 "다시 확인하기"를 누르는 그림. 방법 1의 3단계. */
export function RetryButtonIllustration() {
  return (
    <Frame>
      <circle cx="80" cy="34" r="14" fill="#FFF5C5" />
      <CameraGlyph x={71} y={26} />
      {/* 버튼 글자가 가려지지 않도록 동그라미 대신 버튼 둘레를 감싼다. */}
      <rect x="20" y="56" width="120" height="38" rx="19" fill={PRIMARY} fillOpacity="0.15" />
      <rect x="25" y="60" width="110" height="30" rx="15" fill="none" stroke={PRIMARY} strokeWidth="2.5" />
      <rect x="30" y="64" width="100" height="22" rx="11" fill={PRIMARY} />
      <text x="80" y="79" fontSize="11" fontWeight="700" fill="#FFFFFF" textAnchor="middle">
        다시 확인하기
      </text>
    </Frame>
  )
}
