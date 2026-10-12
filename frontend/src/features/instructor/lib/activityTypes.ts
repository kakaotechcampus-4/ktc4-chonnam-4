import type { ActivityType } from '../api'

/** 활동 유형의 화면 이름. 시안의 활동 유형 카드 이름을 따르고, 표정 퀴즈는 내 감정 / 상대 감정으로 나눈다. */
export const ACTIVITY_TYPE_LABELS: Record<ActivityType, string> = {
  FIRST_COMBINED: '최초 결합 활동',
  SELF_EMOTION_QUIZ: '표정 퀴즈 · 내 감정 알기',
  OTHER_EMOTION_QUIZ: '표정 퀴즈 · 상대 감정 알기',
  ROLEPLAY: '역할극',
}

