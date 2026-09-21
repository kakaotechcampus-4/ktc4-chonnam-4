const API_BASE_URL = 'http://localhost:8080' // TODO: G0 이후 환경변수로 교체

export type ClassroomStatus = 'ACTIVE' | 'ARCHIVED'

export type Classroom = {
  classId: string
  instructorId: string
  name: string
  status: ClassroomStatus
}

export type ChildStatus = 'ACTIVE' | 'PAUSED' | 'REMOVED'

export type Child = {
  childId: string
  classId: string
  displayName: string
  status: ChildStatus
}

type ApiEnvelope<T> = {
  data: T
  meta: { traceId: string }
}

type ApiErrorBody = {
  error: {
    status: number
    code: string
    message: string
    path: string
    traceId: string
    fieldErrors: string[]
  }
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function parseApiResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body: ApiErrorBody = await res.json()
    throw new ApiError(body.error.status, body.error.code, body.error.message)
  }

  const body: ApiEnvelope<T> = await res.json()
  return body.data
}

export async function listClassrooms(): Promise<Classroom[]> {
  const res = await fetch(`${API_BASE_URL}/classrooms`)
  return parseApiResponse<Classroom[]>(res)
}

export async function createClassroom(name: string): Promise<Classroom> {
  const res = await fetch(`${API_BASE_URL}/classrooms`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  return parseApiResponse<Classroom>(res)
}

export async function getClassroom(classId: string): Promise<Classroom> {
  const res = await fetch(`${API_BASE_URL}/classrooms/${classId}`)
  return parseApiResponse<Classroom>(res)
}

export async function listChildren(classId: string): Promise<Child[]> {
  const res = await fetch(`${API_BASE_URL}/classrooms/${classId}/children`)
  return parseApiResponse<Child[]>(res)
}

export async function createChild(classId: string, displayName: string): Promise<Child> {
  const res = await fetch(`${API_BASE_URL}/classrooms/${classId}/children`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ displayName }),
  })
  return parseApiResponse<Child>(res)
}
