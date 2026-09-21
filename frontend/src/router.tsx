import { createBrowserRouter } from 'react-router-dom'
import App from './App'
import ClassroomListPage from './pages/ClassroomListPage'
import ClassroomDetailPage from './pages/ClassroomDetailPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <ClassroomListPage /> },
      { path: 'classrooms/:classId', element: <ClassroomDetailPage /> },
    ],
  },
])
