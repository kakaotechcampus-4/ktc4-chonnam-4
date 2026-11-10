import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import { ClassroomListPage } from './features/instructor/pages/ClassroomListPage'
import { ClassroomDetailPage } from './features/instructor/pages/ClassroomDetailPage'
import { ClassroomCreatePage, ClassroomEditPage } from './features/instructor/pages/ClassroomCreatePage'
import { ChildDetailPage } from './features/instructor/pages/ChildDetailPage'
import { ChildEditPage } from './features/instructor/pages/ChildEditPage'
import { ActivityCreatePage } from './features/instructor/pages/ActivityCreatePage'
import { ActivityManagementPage } from './features/instructor/pages/ActivityManagementPage'
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
import { AgentRunsPage } from './features/ops/pages/AgentRunsPage'

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
    path: '/activities',
    element: (
      <RequireInstructorAuth>
        <ActivityManagementPage />
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
  // 운영 대시보드. 강사 메뉴에는 넣지 않는다. 로그인 없이 볼 수 있는지(public-read)와 볼 수 있는 계정은 서버가 정하므로
  // 화면에서는 로그인을 요구하지 않는다. 공개가 꺼져 있으면 첫 조회의 401 처리(api.ts)가 로그인 화면으로 보낸다.
  { path: '/ops/agent-runs', element: <AgentRunsPage /> },
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