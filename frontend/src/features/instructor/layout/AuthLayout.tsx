import * as React from 'react'

/**
 * 로그인·가입 화면의 셸. 로그인 전이라 강사 헤더(사용자 이름·로그아웃)를 보이지 않는다.
 * 강사용 인증 화면 시안이 나오면 이 파일만 교체한다.
 */
function AuthLayout({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center px-6 py-10">
      <div className="w-full max-w-sm">
        <p className="mb-1 text-center text-sm font-semibold">느링고 강사용</p>
        <h1 className="mb-6 text-center text-2xl font-semibold text-foreground">{title}</h1>
        {children}
      </div>
    </div>
  )
}

export { AuthLayout }
