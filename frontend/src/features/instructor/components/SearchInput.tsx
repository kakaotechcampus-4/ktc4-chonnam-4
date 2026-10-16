import { Search } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * 필터 막대의 검색칸(디자인 시스템 Filter Bar 의 Search). 높이 34, 앞에 돋보기 아이콘.
 * 이름은 보이지 않는 label 로 준다. 아이콘은 꾸밈이라 화면 낭독기가 읽지 않는다.
 */
function SearchInput({
  id,
  label,
  value,
  onChange,
  placeholder,
  className,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  className?: string
}) {
  return (
    <div className={cn('relative', className)}>
      <label htmlFor={id} className="sr-only">
        {label}
      </label>
      <Search
        aria-hidden
        className="pointer-events-none absolute top-1/2 left-2.5 size-[18px] -translate-y-1/2 text-[var(--instructor-icon-subtle)]"
      />
      <input
        id={id}
        type="search"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="h-[34px] w-full rounded-[var(--instructor-radius-control)] border border-[var(--instructor-input-border)] bg-[var(--instructor-surface)] pr-2.5 pl-9 text-sm placeholder:text-[var(--instructor-text-disabled)] focus-visible:border-[var(--instructor-focus)] focus-visible:outline-none"
      />
    </div>
  )
}

export { SearchInput }
