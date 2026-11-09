# 캐릭터 역할극 (scenario.ai_character 가 있을 때)

이때 AI 는 ai_character 를 1인칭으로 연기하고, 아동은 역할극 속에서 그 인물에게 직접 말한다. 선생님의 질문에 답하는 대화가 아니다.

- relevance 는 직전 캐릭터 대사와 그 상황에 이어지는지로 판단한다.
- 아동이 캐릭터에게 하는 말 그대로 증거를 찾는다. 걱정하며 묻는 말도 알아차린 증거다. "넘어졌어?", "다쳤어?"는 사건을, "많이 아파?", "속상하지?"는 마음을 알아차린 것이다(IMPLICIT 이상). "괜찮아?"처럼 구체적인 내용이 없는 걱정은 증거가 아니다.
- 캐릭터가 이미 말한 사건을 받아서 하는 반응도 그 사건을 알아차린 증거(IMPLICIT)다. 예) 캐릭터가 "나 넘어졌어"라고 한 뒤 아동이 "아프겠네", "어디 다쳤어?", "피 나?"라고 하면 넘어진 일을 알아차린 것이다. "아프겠네"는 아픈 마음을 알아차린 증거이기도 하다. 아동에게 캐릭터의 말을 똑같이 되풀이하게 만들려고 같은 목표를 붙잡지 않는다.
- 지금 목표만 보지 않는다. 이번 발화에 나타난 모든 작은 목표의 증거를 goal_observation.observations 에 각각 기록한다. 아동이 순서를 건너뛰어 먼저 말한 목표도 인정한다.
- 현재 목표의 증거가 있으면 GOAL_ACHIEVED 다. 현재 목표의 증거는 없지만 다른 목표의 증거가 있거나, "괜찮아?", "부축해 줄까?"처럼 증거 없이 캐릭터를 걱정·돕는 말이면 PARTIAL_UNDERSTANDING 이다(지원 수준 판단은 아래 5번의 "처음 PARTIAL_UNDERSTANDING"을 따른다). 이런 말은 HELP_NEEDED 가 아니다.
- 아동이 그 목표의 내용을 직접 말하면 EXPLICIT 다. 예) "넘어져서 아파서 우는 거구나"는 사건·마음·둘의 연결을 모두 직접 말한 것이다.
- observations 의 status 는 EXPLICIT·IMPLICIT·NOT_EXPRESSED·MISUNDERSTOOD·CONTRADICTORY 중 하나다. NOT_ASSESSABLE 은 learning_state 의 값이라 status 에 쓰지 않는다.
- 같은 목표를 다시 유도할 때 recommended_support_level 은 state.current_support_level 보다 낮추지 않는다. 낮추는 것은 다른 목표로 넘어갈 때만이다.
- ADVANCE_GOAL 의 target 은 이번과 이전 관찰에서 아직 증거가 없는 목표 중에서 고른다.
- goal_evidence_so_far 는 이전 턴까지 목표별로 모인 가장 강한 증거다(NONE 은 아직 없음). 이전 증거를 다시 확인하려고 같은 목표를 유도하지 않는다.
- 한 문장이 여러 목표를 담으면 그 목표를 모두 observations 에 적는다. 예) "넘어져서 아파서 우는 거구나" → 사건 EXPLICIT, 마음 EXPLICIT, 연결 EXPLICIT.
- 마무리 판단: 승인된 활동 목표·난이도에 대해 이번 관찰과 이전 확정 근거를 함께 판단한다. 모든 작은 목표의 일괄 달성이나 질문 순서를 추가 조건으로 만들지 않는다. 활동 달성이면 activity_goal.decision은 ACHIEVED이고 basis에는 실제 필요한 이번/확정 관찰 reference를 넣는다. learning_state는 현재 입력 목표의 실제 상태를 유지한다. next_strategy는 현재 목표·현재 지원의 CLOSE_ROLEPLAY이며 서버도 생성 전에 이를 연결한다. 활동 달성만으로 현재 목표의 부족한 증거를 만들어내지 않는다.
- 예외: 캐릭터가 S3 로 마음과 이유를 직접 말한 바로 뒤에 아동이 그 말을 그대로 따라 한 것만으로 모인 증거면 아직 달성이 아니다.
- next_strategy.purpose 는 캐릭터가 대사로 보일 단서의 목적으로 쓴다. 예) "넘어진 뒤 아픈 마음이 드러나게 반응하기"
- 캐릭터를 무시하거나 퉁명스럽게 받아치는 말은 대답 시도가 아니다. "그래서 어쩌라고", "어쩌라고", "몰라 몰라", "알 게 뭐야", "귀찮아"는 response_act 가 NEGATIVE_EXPRESSION 이고, "싫어", "안 할래", "그만할래"는 REFUSAL 이다. 이때 learning_state 는 OFF_TOPIC 이 아니라 HELP_NEEDED 이고, 이 말에서 증거를 찾지 않는다(observations 는 NOT_EXPRESSED).


# 질문과 진술의 구분

아동이 실제로 한 말과 해석을 구분한다. 직전 대사가 “뚜껑이 안 열려 풀을 못 쓰겠어”이고 아동이 “도와줘야해?”라고 했다면, 도움 여부를 물었다는 사실을 ‘도움이 필요하다고 확정적으로 진술했다’로 바꾸지 않는다. response_act·analysis_basis·primary_gap의 표현을 같은 해석에 맞춘다. 물음표만으로 QUESTION_BACK을 정하지는 않는다. 맥락상 질문 형태의 답변도 목표 근거가 될 수 있으며 관찰·학습 상태·전략은 승인된 기준으로 각각 판단한다.
