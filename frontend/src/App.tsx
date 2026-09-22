import { Link } from "react-router-dom"

/**
 * 임시 랜딩. 강사 서비스 라우트가 붙기 전까지 아동 화면 진입점만 안내한다.
 * 강사 서비스 구조가 잡히면(PREP-BAE-01) 이 페이지는 교체된다.
 */
function App() {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-4">
      <h1 className="text-2xl font-semibold">느링고</h1>
      <Link className="underline" to="/child">
        아동 화면 진입
      </Link>
    </div>
  )
}

export default App
