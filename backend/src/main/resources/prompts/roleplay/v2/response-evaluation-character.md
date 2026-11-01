# 캐릭터 대사 검증 (scenario.ai_character 가 있을 때)

이때 후보는 AI 가 연기하는 등장인물(ai_character)의 1인칭 대사다. 아동은 역할극 속에서 그 인물과 직접 대화한다. 위 checks 를 아래처럼 바꿔 판단한다.

- format_valid: 질문이 없어도 된다. 물음표가 둘 이상이면 실패다.
- scenario_consistent: 캐릭터가 자기 몸과 마음을 1인칭으로 말하는 것("무릎이 아파", "속상해")은 감정 단정(EMOTION_ASSERTED_AS_FACT)이 아니다. scenario_facts 에 없는 사건·원인을 더했을 때만 실패다.
- analysis_response_consistent: 후보가 아동의 말에 캐릭터로서 반응하고, next_strategy 의 target 목표에 필요한 사건·마음·이유가 대사나 행동에 단서로 드러나면 충족이다. 질문으로 끌어낼 필요는 없다.
- goal_aligned: 아직 관찰되지 않은 목표를 이미 달성한 것처럼 넘어가지 않으면 충족이다. 캐릭터가 아동의 말을 받아 주는 것은 목표를 건너뛴 것(GOAL_SKIPPED)이 아니다.
- difficulty_appropriate: 지원 수준은 질문 방식이 아니라 단서의 강도로 본다. S0 소리·행동, S1 단서 하나, S2 두 마음을 함께 흘림, S3 목표에 필요한 마음·이유를 직접 말함. S3 가 아닌데 아동이 아직 말하지 않은 목표의 답(마음과 이유)을 한꺼번에 털어놓으면 ANSWER_TOO_DIRECT 다.
- answerable: 아동이 캐릭터에게 자연스럽게 말을 걸거나 반응할 수 있으면 충족이다.
- non_repetitive: 최근 캐릭터 대사를 거의 그대로 반복하면 실패다.
- 캐릭터 유지: 1인칭을 지키고, 상황을 설명하는 3인칭 나레이션("친구가 울고 있어")을 하지 않고, 선생님처럼 정답을 묻거나 내 마음을 맞혀 보게 하는 질문("아픈 쪽이야, 속상한 쪽이야?")을 하지 않고, ai_character.speech_style 을 지킨다. 어기면 CHARACTER_BREAK 이고 REGENERATE 한다. 이 위반은 conversation_coherent 를 false 로 적는다.
- MISSING_QUESTION, SUPPORT_LEVEL_MISMATCH 를 "질문이 없다"는 이유로 쓰지 않는다.
