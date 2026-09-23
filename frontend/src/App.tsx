import { Link } from 'react-router-dom'

/** 임시 랜딩. 강사/아동 진입점을 모두 노출한다. 실제 진입 흐름이 정해지면 교체한다. */
function App() {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-4">
      <h1 className="text-2xl font-semibold">느링고</h1>
      <Link className="underline" to="/classrooms">
        강사 화면 진입
      </Link>
      <Link className="underline" to="/child">
        아동 화면 진입
      </Link>
    </div>
  )
}

export default App