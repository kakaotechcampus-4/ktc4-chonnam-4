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

// TODO(human): 아래 다섯 함수의 본문을 채워주세요.
//
// 공통 패턴: fetch로 요청 보내기 -> await res.json()으로 파싱 -> ApiEnvelope<T>의 .data 꺼내서 반환
// POST 요청은 세 번째 인자로 옵션 객체가 필요합니다:
//   fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
//
// 1) listClassrooms(): Promise<Classroom[]>
//    - GET `${API_BASE_URL}/classrooms`
//
// 2) createClassroom(name: string): Promise<Classroom>
//    - POST `${API_BASE_URL}/classrooms`, body: { name }
//
// 3) getClassroom(classId: string): Promise<Classroom>
//    - GET `${API_BASE_URL}/classrooms/${classId}`
//
// 4) listChildren(classId: string): Promise<Child[]>
//    - GET `${API_BASE_URL}/classrooms/${classId}/children`
//
// 5) createChild(classId: string, displayName: string): Promise<Child>
//    - POST `${API_BASE_URL}/classrooms/${classId}/children`, body: { displayName }

export async function listClassrooms(): Promise<Classroom[]> {
    const res = await fetch(`${API_BASE_URL}/classrooms`)
    const body: ApiEnvelope<Classroom[]> = await res.json()
    return body.data
}

export async function createClassroom(name: string): Promise<Classroom> {
  const res = await fetch(`${API_BASE_URL}/classrooms`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    })
    const body: ApiEnvelope<Classroom> = await res.json()
    return body.data
}

export async function getClassroom(classId: string): Promise<Classroom> {
  const res = await fetch(`${API_BASE_URL}/classrooms/${classId}`)
    const body: ApiEnvelope<Classroom> = await res.json()
    return body.data
}

export async function listChildren(classId: string): Promise<Child[]> {
  const res = await fetch(`${API_BASE_URL}/classrooms/${classId}/children`)
    const body: ApiEnvelope<Child[]> = await res.json()
    return body.data
}

export async function createChild(classId: string, displayName: string): Promise<Child> {
  const res = await fetch(`${API_BASE_URL}/classrooms/${classId}/children`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName }),
    })
    const body: ApiEnvelope<Child> = await res.json()
    return body.data
}
