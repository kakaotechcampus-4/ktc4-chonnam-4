import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import { ClassroomListPage } from './features/instructor/pages/ClassroomListPage'
import { ClassroomDetailPage } from './features/instructor/pages/ClassroomDetailPage'
import { ChildAccessPage } from './features/child/pages/ChildAccessPage'
import { ChildActivitiesPage } from './features/child/pages/ChildActivitiesPage'
import { ChildQuizPage } from './features/child/pages/ChildQuizPage'
import { ChildRoleplayPage } from './features/child/pages/ChildRoleplayPage'
import { ChildStatePreviewPage } from './features/child/pages/ChildStatePreviewPage'

export const router = createBrowserRouter([
  { path: '/', element: <App /> },
  { path: '/classrooms', element: <ClassroomListPage /> },
  { path: '/classrooms/:classId', element: <ClassroomDetailPage /> },
  { path: '/child', element: <ChildAccessPage /> },
  { path: '/child/activities', element: <ChildActivitiesPage /> },
  { path: '/child/quiz/:activityId', element: <ChildQuizPage /> },
  { path: '/child/roleplay/:activityId', element: <ChildRoleplayPage /> },
  { path: '/child/_dev/states', element: <ChildStatePreviewPage /> },
])