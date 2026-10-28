/**
 * 요청 중에 글자가 바뀌는 버튼의 글자 자리. 디자인 시스템 Button 규칙("Loading 시 폭 고정")대로
 * 평소 글자와 요청 중 글자를 한 칸에 겹쳐 두어, 버튼 폭이 늘 둘 중 긴 쪽에 맞는다.
 * 안 보이는 쪽은 aria-hidden 이라 버튼 이름은 지금 보이는 글자 하나다.
 */
function ButtonLabel({ pending, label, pendingLabel }: { pending: boolean; label: string; pendingLabel: string }) {
  return (
    <span className="inline-grid">
      <span aria-hidden={pending} className={pending ? 'invisible col-start-1 row-start-1' : 'col-start-1 row-start-1'}>
        {label}
      </span>
      <span aria-hidden={!pending} className={pending ? 'col-start-1 row-start-1' : 'invisible col-start-1 row-start-1'}>
        {pendingLabel}
      </span>
    </span>
  )
}

export { ButtonLabel }
