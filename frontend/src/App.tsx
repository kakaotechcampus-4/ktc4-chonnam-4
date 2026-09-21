import { Link } from 'react-router-dom'

/**
 * 임시 랜딩. 지금은 강사 서비스 진입점만 안내한다.
 * 아동 화면 진입(/child)은 feature/child-common-layout 이 병합되면 함께 노출한다.
 */
function App() {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-4">
      <h1 className="text-2xl font-semibold">느링고</h1>
      <Link className="underline" to="/classrooms">
        강사 화면 진입
      </Link>
    </div>
  )
}

export default App
