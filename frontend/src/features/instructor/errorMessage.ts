import { ApiError } from './api'

/**
 * 화면에 보일 오류 문구. 입력 검증 실패(422)면 서버가 필드별로 알려 준 이유를 보인다.
 * 서버 문구는 GlobalExceptionHandler 가 직접 정한 것이라 입력값 원문이 섞이지 않는다.
 */
export function describeError(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.fieldErrors.length > 0) {
    return [...new Set(error.fieldErrors.map((fieldError) => fieldError.message))].join(' ')
  }
  return error instanceof Error ? error.message : fallback
}
