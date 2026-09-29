import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import { ClassroomListPage } from './features/instructor/pages/ClassroomListPage'
import { ClassroomDetailPage } from './features/instructor/pages/ClassroomDetailPage'
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
  { path: '/classrooms', element: <ClassroomListPage /> },
  { path: '/classrooms/:classId', element: <ClassroomDetailPage /> },
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