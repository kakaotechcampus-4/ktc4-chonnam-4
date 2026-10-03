import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import { ClassroomListPage } from './features/instructor/pages/ClassroomListPage'
import { ClassroomDetailPage } from './features/instructor/pages/ClassroomDetailPage'
import { ClassroomCreatePage } from './features/instructor/pages/ClassroomCreatePage'
import { ChildDetailPage } from './features/instructor/pages/ChildDetailPage'
import { ActivityCreatePage } from './features/instructor/pages/ActivityCreatePage'
import { ActivityReportPage } from './features/instructor/pages/ActivityReportPage'
import { LoginPage } from './features/instructor/pages/LoginPage'
import { SignupPage } from './features/instructor/pages/SignupPage'
import { RequireInstructorAuth } from './features/instructor/auth/RequireInstructorAuth'
import { ChildAccessPage } from './features/child/pages/ChildAccessPage'
import { ChildWelcomePage } from './features/child/pages/ChildWelcomePage'
import { ChildActivitiesPage } from './features/child/pages/ChildActivitiesPage'
import { ChildActivityIntroPage } from './features/child/pages/ChildActivityIntroPage'
import { ChildQuizPage } from './features/child/pages/ChildQuizPage'
import { ChildRoleplayPage } from './features/child/pages/ChildRoleplayPage'
import { ChildDonePage } from './features/child/pages/ChildDonePage'
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
  {
    path: '/classrooms/:classId/children/:childId',
    element: (
      <RequireInstructorAuth>
        <ChildDetailPage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/activities/new',
    element: (
      <RequireInstructorAuth>
        <ActivityCreatePage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/activities/:activityId/report',
    element: (
      <RequireInstructorAuth>
        <ActivityReportPage />
      </RequireInstructorAuth>
    ),
  },
  { path: '/child', element: <ChildAccessPage /> },
  {
    element: <RequireChildSession />,
    children: [
      { path: '/child/hello', element: <ChildWelcomePage /> },
      { path: '/child/activities', element: <ChildActivitiesPage /> },
      { path: '/child/activities/:activityId', element: <ChildActivityIntroPage /> },
      { path: '/child/quiz/:activityId', element: <ChildQuizPage /> },
      { path: '/child/roleplay/:activityId', element: <ChildRoleplayPage /> },
      { path: '/child/done/:activityId', element: <ChildDonePage /> },
    ],
  },
  { path: '/child/_dev/states', element: <ChildStatePreviewPage /> },
])