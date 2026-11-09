import { useQueries, useQuery } from '@tanstack/react-query'
import { listClassroomActivities, listClassroomLearningReports, type Classroom } from '../api'

/**
 * 학급 단위 조회는 이 파일에서만 부른다. 학급 상세·리포트 목록·대시보드가 같은 캐시 키를 써서 화면을 오가도 다시 받지 않는다.
 */
function classroomActivitiesQuery(classId: string) {
  return {
    queryKey: ['classrooms', classId, 'activities'],
    queryFn: () => listClassroomActivities(classId),
  }
}

/** 학급 아동 전체의 활동(최근 배정 순). */
function useClassroomActivities(classId: string | undefined) {
  return useQuery({ ...classroomActivitiesQuery(classId ?? ''), enabled: !!classId })
}

/** 학급 아동 전체의 완료 리포트(최근 완료 순). */
function useClassroomReports(classId: string | undefined) {
  return useQuery({
    queryKey: ['learning-records', { classId }],
    queryFn: () => listClassroomLearningReports(classId!),
    enabled: !!classId,
  })
}

/** 대시보드처럼 여러 학급을 한꺼번에 볼 때. 학급마다 한 번씩 부르고 결과를 학급 순서대로 돌려준다. */
function useActivitiesOfClassrooms(classrooms: Classroom[] | undefined) {
  return useQueries({
    queries: (classrooms ?? []).map((classroom) => classroomActivitiesQuery(classroom.classId)),
  })
}

export { useActivitiesOfClassrooms, useClassroomActivities, useClassroomReports }
