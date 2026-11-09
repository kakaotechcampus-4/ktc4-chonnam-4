/**
 * 캐릭터 대사의 괄호 지문("(종이를 들고) 나 그림 그리고 있었어.")을 대사와 나눈다.
 * 지문은 서버가 음성 감정을 고르는 데 쓰고 음성으로는 읽지 않는다. 화면에서는 괄호 없이 말풍선 위 작은 글씨로 보여 준다.
 * S0 첫 대사("(훌쩍이며) 흐윽…")처럼 단서가 지문에만 있는 경우가 있어 지우지 않고 따로 보여 준다.
 */
export type StageDirectedLine = {
  /** 괄호를 뺀 지문. 여러 개면 순서대로 쉼표로 잇는다. 없으면 null. */
  action: string | null
  /** 괄호 지문을 뺀 대사. */
  speech: string
}

const STAGE_DIRECTION = /\(([^()]*)\)/g

export function splitStageDirection(text: string): StageDirectedLine {
  const actions: string[] = []
  const speech = text
    .replace(STAGE_DIRECTION, (_, inner: string) => {
      const action = inner.trim()
      if (action) actions.push(action)
      return " "
    })
    .replace(/\s+/g, " ")
    .trim()
  return { action: actions.length > 0 ? actions.join(", ") : null, speech }
}
