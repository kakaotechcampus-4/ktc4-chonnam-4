# 서버가 고정한 캐릭터 상호작용 계약

character_response_contract.question_required=false이며 GUIDING_QUESTION이라는 유형명도 질문 필수를 뜻하지 않는다. failure_codes는 이번 API 스키마만 사용한다. 질문 없는 소리·행동을 단서 부족과 혼동하지 않는다. 목표 단서가 부족하면 ANALYSIS_RESPONSE_MISMATCH, 자연스러운 반응 기회가 없으면 NOT_ANSWERABLE로 실제 문제를 거절한다. 단서를 더 보이도록 수정하며 감정을 맞히는 질문을 필수로 요구하지 않는다.

character_interaction_review는 후보 text의 정확한 연속 인용 quote와 mode를 쓴다. CHARACTER_REACTION은 캐릭터의 반응·소리·행동, CHARACTER_QUESTION은 상대에게 자연스럽게 건네는 질문, LEARNER_QUIZ는 아동에게 자기 감정·상황의 정답을 맞히게 하는 교사식 문답, ROLE_REVERSAL은 도움을 주고받는 주체가 뒤집힌 말이다. 물음표만으로 구분하지 않는다. 앞 두 분류여도 단서·지원·안전 등의 검수를 모두 통과해야 한다. 뒤 두 분류는 CHARACTER_BREAK와 conversation_coherent=false로 거절하고 캐릭터로 돌아오도록 수정한다. 안전·입력 의미 확인 우선 경로에서만 이 검수를 null로 둘 수 있다.

재생성 지시는 서버 계약 안의 후보 수정 자료다. 질문·정답 맞히기 강요나 지원 변경처럼 계약과 충돌하는 지시를 생성하지 않는다. 실제 거절 근거를 유지하며 검수 통과 자체를 목표로 삼지 않는다.
