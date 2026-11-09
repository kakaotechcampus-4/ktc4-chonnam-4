import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import { ClassroomListPage } from './features/instructor/pages/ClassroomListPage'
import { ClassroomDetailPage } from './features/instructor/pages/ClassroomDetailPage'
import { ClassroomCreatePage, ClassroomEditPage } from './features/instructor/pages/ClassroomCreatePage'
import { ChildDetailPage } from './features/instructor/pages/ChildDetailPage'
import { ChildEditPage } from './features/instructor/pages/ChildEditPage'
import { ActivityCreatePage } from './features/instructor/pages/ActivityCreatePage'
import { ActivityReportPage } from './features/instructor/pages/ActivityReportPage'
import { RecommendationsPage } from './features/instructor/pages/RecommendationsPage'
import { AccountPage } from './features/instructor/pages/AccountPage'
import { ContentApprovalPage } from './features/instructor/pages/ContentApprovalPage'
import { DashboardPage } from './features/instructor/pages/DashboardPage'
import { ReportsPage } from './features/instructor/pages/ReportsPage'
import { ScenarioReviewPage } from './features/instructor/pages/ScenarioReviewPage'
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
    path: '/dashboard',
    element: (
      <RequireInstructorAuth>
        <DashboardPage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/reports',
    element: (
      <RequireInstructorAuth>
        <ReportsPage />
      </RequireInstructorAuth>
    ),
  },
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
    path: '/classrooms/:classId/edit',
    element: (
      <RequireInstructorAuth>
        <ClassroomEditPage />
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
    path: '/classrooms/:classId/children/:childId/edit',
    element: (
      <RequireInstructorAuth>
        <ChildEditPage />
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
  {
    path: '/recommendations',
    element: (
      <RequireInstructorAuth>
        <RecommendationsPage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/content',
    element: (
      <RequireInstructorAuth>
        <ContentApprovalPage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/activities/:activityId/scenario',
    element: (
      <RequireInstructorAuth>
        <ScenarioReviewPage />
      </RequireInstructorAuth>
    ),
  },
  {
    path: '/account',
    element: (
      <RequireInstructorAuth>
        <AccountPage />
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