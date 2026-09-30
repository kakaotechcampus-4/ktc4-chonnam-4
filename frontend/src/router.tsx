import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import { ClassroomListPage } from './features/instructor/pages/ClassroomListPage'
import { ClassroomDetailPage } from './features/instructor/pages/ClassroomDetailPage'
import { ClassroomCreatePage } from './features/instructor/pages/ClassroomCreatePage'
import { LoginPage } from './features/instructor/pages/LoginPage'
import { SignupPage } from './features/instructor/pages/SignupPage'
import { RequireInstructorAuth } from './features/instructor/auth/RequireInstructorAuth'
import { ChildAccessPage } from './features/child/pages/ChildAccessPage'
import { ChildActivitiesPage } from './features/child/pages/ChildActivitiesPage'
import { ChildQuizPage } from './features/child/pages/ChildQuizPage'
import { ChildRoleplayPage } from './features/child/pages/ChildRoleplayPage'
import { ChildStatePreviewPage } from './features/child/pages/ChildStatePreviewPage'
import { RequireChildSession } from './features/child/routes/RequireChildSession'

export const router = createBrowserRouter([
  { path: '/', element: <App /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
  {
    path: '/classrooms',
    element: (
      <RequireInstructorAuth>
        <ClassroomListPage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/classrooms/new',
    element: (
      <RequireInstructorAuth>
        <ClassroomCreatePage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/classrooms/:classId',
    element: (
      <RequireInstructorAuth>
        <ClassroomDetailPage />
      </RequireInstructorAuth>
    ),
  },
  { path: '/child', element: <ChildAccessPage /> },
  {
    element: <RequireChildSession />,
    children: [
      { path: '/child/activities', element: <ChildActivitiesPage /> },
      { path: '/child/quiz/:activityId', element: <ChildQuizPage /> },
      { path: '/child/roleplay/:activityId', element: <ChildRoleplayPage /> },
    ],
  },
  { path: '/child/_dev/states', element: <ChildStatePreviewPage /> },
])