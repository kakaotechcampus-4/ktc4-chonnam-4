import { act, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { VoiceRecordingPanel } from "./VoiceRecordingPanel"

// 역할극 음성 입력 시간 제한 검사. 한 번에 최대 30초까지 듣고, 시간이 다 되거나 "다 말했어요"를 누르면 끝난다.
// 의도적으로 제한 시간을 바꾸면 이 테스트도 같이 고치세요.

describe("듣고 있어요 30초 제한", () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it("처음에는 30초가 남아 있다", () => {
    render(<VoiceRecordingPanel onFinish={vi.fn()} />)

    expect(screen.getByRole("timer", { name: "남은 시간 30초" })).toBeInTheDocument()
  })

  it("30초가 지나면 시간 초과로 한 번만 끝난다", () => {
    const onFinish = vi.fn()
    render(<VoiceRecordingPanel onFinish={onFinish} />)

    act(() => vi.advanceTimersByTime(29_900))
    expect(onFinish).not.toHaveBeenCalled()

    act(() => vi.advanceTimersByTime(5_000))
    expect(onFinish).toHaveBeenCalledTimes(1)
    expect(onFinish).toHaveBeenCalledWith("timeout")
  })

  it("마지막 5초에는 심지가 경고 상태로 바뀐다", () => {
    render(<VoiceRecordingPanel onFinish={vi.fn()} />)

    act(() => vi.advanceTimersByTime(24_000))
    expect(screen.getByRole("timer", { name: "남은 시간 6초" })).not.toHaveAttribute("data-warning")

    act(() => vi.advanceTimersByTime(1_000))
    expect(screen.getByRole("timer", { name: "남은 시간 5초" })).toHaveAttribute("data-warning")
  })

  it("다 말했어요를 누르면 시간이 남아도 바로 끝난다", () => {
    const onFinish = vi.fn()
    render(<VoiceRecordingPanel onFinish={onFinish} />)

    act(() => vi.advanceTimersByTime(10_000))
    fireEvent.click(screen.getByRole("button", { name: "다 말했어요" }))

    expect(onFinish).toHaveBeenCalledWith("manual")
  })
})
