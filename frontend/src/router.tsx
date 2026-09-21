import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import { ClassroomListPage } from './features/instructor/pages/ClassroomListPage'
import { ClassroomDetailPage } from './features/instructor/pages/ClassroomDetailPage'

// 아동 서비스 라우트(/child ...)는 feature/child-common-layout 에서 별도로 추가한다.
export const router = createBrowserRouter([
  { path: '/', element: <App /> },
  { path: '/classrooms', element: <ClassroomListPage /> },
  { path: '/classrooms/:classId', element: <ClassroomDetailPage /> },
])
