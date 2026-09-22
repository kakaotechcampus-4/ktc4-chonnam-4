import { createBrowserRouter } from "react-router-dom"
import App from "./App"
import { ChildAccessPage } from "./features/child/pages/ChildAccessPage"
import { ChildActivitiesPage } from "./features/child/pages/ChildActivitiesPage"
import { ChildQuizPage } from "./features/child/pages/ChildQuizPage"
import { ChildRoleplayPage } from "./features/child/pages/ChildRoleplayPage"
import { ChildStatePreviewPage } from "./features/child/pages/ChildStatePreviewPage"

// 강사 서비스 라우트는 배재일(PREP-BAE-01)이 별도로 추가한다.
export const router = createBrowserRouter([
  { path: "/", element: <App /> },
  { path: "/child", element: <ChildAccessPage /> },
  { path: "/child/activities", element: <ChildActivitiesPage /> },
  { path: "/child/quiz/:activityId", element: <ChildQuizPage /> },
  { path: "/child/roleplay/:activityId", element: <ChildRoleplayPage /> },
  { path: "/child/_dev/states", element: <ChildStatePreviewPage /> },
])
