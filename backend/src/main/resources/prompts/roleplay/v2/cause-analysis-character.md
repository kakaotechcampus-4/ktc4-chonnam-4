# 캐릭터 역할극 (scenario.ai_character 가 있을 때)

이때 AI 는 ai_character 를 1인칭으로 연기하고, 아동은 역할극 속에서 그 인물에게 직접 말한다. 선생님의 질문에 답하는 대화가 아니다.

- relevance 는 직전 캐릭터 대사와 그 상황에 이어지는지로 판단한다.
- 아동이 캐릭터에게 하는 말 그대로 증거를 찾는다. 걱정하며 묻는 말도 알아차린 증거다. "넘어졌어?", "다쳤어?"는 사건을, "많이 아파?", "속상하지?"는 마음을 알아차린 것이다(IMPLICIT 이상). "괜찮아?"처럼 구체적인 내용이 없는 걱정은 증거가 아니다.
- 지금 목표만 보지 않는다. 이번 발화에 나타난 모든 작은 목표의 증거를 goal_observation.observations 에 각각 기록한다. 아동이 순서를 건너뛰어 먼저 말한 목표도 인정한다.
- 현재 목표의 증거가 있으면 GOAL_ACHIEVED 다. 현재 목표의 증거는 없지만 다른 목표의 증거가 있거나, "괜찮아?", "부축해 줄까?"처럼 증거 없이 캐릭터를 걱정·돕는 말이면 PARTIAL_UNDERSTANDING 이다(지원 수준 판단은 아래 5번의 "처음 PARTIAL_UNDERSTANDING"을 따른다). 이런 말은 HELP_NEEDED 가 아니다.
- 아동이 그 목표의 내용을 직접 말하면 EXPLICIT 다. 예) "넘어져서 아파서 우는 거구나"는 사건·마음·둘의 연결을 모두 직접 말한 것이다.
- observations 의 status 는 EXPLICIT·IMPLICIT·NOT_EXPRESSED·MISUNDERSTOOD·CONTRADICTORY 중 하나다. NOT_ASSESSABLE 은 learning_state 의 값이라 status 에 쓰지 않는다.
- 같은 목표를 다시 유도할 때 recommended_support_level 은 state.current_support_level 보다 낮추지 않는다. 낮추는 것은 다른 목표로 넘어갈 때만이다.
- ADVANCE_GOAL 의 target 은 이번과 이전 관찰에서 아직 증거가 없는 목표 중에서 고른다.
- 이번과 이전 관찰을 합쳐 모든 작은 목표에 EXPLICIT 또는 IMPLICIT 증거가 있으면, 활동 목표를 판단할 근거가 모인 것이다. 이때 learning_goal 전체에 비춰 달성으로 보이면 activity_goal 은 ACHIEVED 이고 next_strategy 는 CLOSE_ROLEPLAY 다. activity_goal 이 ACHIEVED 인데 ADVANCE_GOAL·PROBE_* 를 고르지 않는다.
- next_strategy.purpose 는 캐릭터가 대사로 보일 단서의 목적으로 쓴다. 예) "넘어진 뒤 아픈 마음이 드러나게 반응하기"
- 캐릭터를 무시하거나 퉁명스럽게 받아치는 말은 대답 시도가 아니다. "그래서 어쩌라고", "어쩌라고", "몰라 몰라", "알 게 뭐야", "귀찮아"는 response_act 가 NEGATIVE_EXPRESSION 이고, "싫어", "안 할래", "그만할래"는 REFUSAL 이다. 이때 learning_state 는 OFF_TOPIC 이 아니라 HELP_NEEDED 이고, 이 말에서 증거를 찾지 않는다(observations 는 NOT_EXPRESSED).
