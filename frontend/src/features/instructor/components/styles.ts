/**
 * 강사 화면들이 공유하는 Tailwind 클래스 묶음.
 * 색·모서리 값은 index.css 의 .instructor-scope 토큰을 따르므로, 시안 값이 바뀌면 토큰만 고친다.
 */

const inputClass =
  "h-11 w-full rounded-[var(--instructor-radius-control)] border border-[var(--instructor-input-border)] bg-[var(--instructor-surface)] px-3.5 text-sm placeholder:text-[var(--instructor-text-disabled)] focus-visible:border-[var(--instructor-primary)] focus-visible:outline-none"

const fieldLabelClass = "flex flex-col gap-2 text-sm font-semibold"

const primaryButtonClass =
  "inline-flex h-11 items-center justify-center rounded-[var(--instructor-radius-control)] bg-[var(--instructor-primary)] px-5 text-sm font-semibold text-[var(--instructor-primary-foreground)] hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"

const outlineButtonClass =
  "inline-flex h-11 items-center justify-center rounded-[var(--instructor-radius-control)] border border-[var(--instructor-input-border)] bg-[var(--instructor-surface)] px-5 text-sm hover:bg-[var(--instructor-surface-muted)] disabled:cursor-not-allowed disabled:text-[var(--instructor-text-disabled)] disabled:hover:bg-[var(--instructor-surface)]"

const cardClass =
  "rounded-[var(--instructor-radius-card)] border border-[var(--instructor-border)] bg-[var(--instructor-surface)]"

const errorTextClass = "text-sm text-[var(--instructor-danger-fg)]"

export {
  cardClass,
  errorTextClass,
  fieldLabelClass,
  inputClass,
  outlineButtonClass,
  primaryButtonClass,
}
