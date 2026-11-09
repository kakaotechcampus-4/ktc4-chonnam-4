# 거절 근거와 수정 조건

target_cue_violation은 일반 학습 전략에서 ANALYSIS_RESPONSE_MISMATCH로 거절할 때만 reason·quote를 쓴다. 해당 검사 false·실패 코드와 일치시킨다. NO_TARGET_CUE는 후보 text 전체, WRONG_TARGET_CUE·NO_REACTION_TO_INPUT은 실제 text의 해당 연속 구절을 인용한다. 목표 ID는 서버가 고정했으므로 반복 출력하지 않는다. 그 외와 안전·입력 확인 우선 경로는 null이다.

캐릭터 검수의 revision_instruction에는 keep/change/avoid만 쓴다. required는 서버가 구성하므로 출력하지 않는다. 검수 필드명·스키마 작성 지시를 후보 수정 자료에 넣지 않는다. 생성은 서버 required 안에서 수정한다.
