import { Outlet } from 'react-router-dom'

function App() {
  return (
    <div>
      <header className="border-border border-b px-6 py-4">
        <span className="text-sm font-semibold">느링고 강사용</span>
      </header>
      <Outlet />
    </div>
  )
}

export default App
