// 강사 로그인 토큰 보관(DEC-001 4절). sessionStorage 는 탭을 닫으면 지워지고 새로고침에는 남는다.
// 공용 PC 에 로그인이 남지 않는 대신 JS 가 읽을 수 있으므로, 사용자 입력을 HTML 로 렌더링하지 않는 규칙을 지킨다.
const ACCESS_TOKEN_KEY = 'neuringo.instructor.accessToken'

// 개인정보 보호 모드 등에서 저장소 접근이 막혀도 앱이 멈추지 않게 한다. 그 경우 로그인 상태가 유지되지 않을 뿐이다.
function getAccessToken(): string | null {
  try {
    return sessionStorage.getItem(ACCESS_TOKEN_KEY)
  } catch {
    return null
  }
}

function setAccessToken(token: string) {
  try {
    sessionStorage.setItem(ACCESS_TOKEN_KEY, token)
  } catch {
    // 저장하지 못하면 다음 요청이 401 을 받아 로그인 화면으로 돌아간다.
  }
}

function clearAccessToken() {
  try {
    sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  } catch {
    // 지울 수 없으면 서버 세션 만료(12시간)에 맡긴다.
  }
}

export { clearAccessToken, getAccessToken, setAccessToken }
