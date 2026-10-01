import path from "path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// https://vite.dev/config/
// defineConfig 를 "vitest/config" 에서 가져와야 아래 test 블록에 타입이 붙는다.
// vitest.config.ts 를 따로 만들면 이 파일이 통째로 무시되니 설정은 여기 한 곳에 둔다.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },
  // 테스트에서만 저장소 루트의 contracts/(FE ↔ BE API 명세)를 읽게 연다. 개발 서버는 기본값(frontend 안) 그대로다.
  server: process.env.VITEST
    ? { fs: { allow: [import.meta.dirname, path.resolve(import.meta.dirname, "../contracts")] } }
    : undefined,
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
    // 파일마다 첫 화면 렌더(jsdom·React Query·라우터 초기화)가 병렬 실행 중에는 5초(기본값)를 넘길 때가 있다.
    // 속성 기반 테스트도 한 테스트에서 요청을 백 번쯤 보낸다. 느린 PC·CI 에서 시간 초과로 거짓 실패가 나지 않게 늘린다.
    testTimeout: 20_000,
    // CI 에서는 PR 주석(github-actions)과 요약용 JUnit XML 을 같이 남긴다.
    reporters: process.env.CI ? ["default", "github-actions", "junit"] : ["default"],
    outputFile: { junit: "./test-results/vitest-junit.xml" },
    coverage: {
      provider: "v8",
      reporter: ["text-summary", "html"],
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.{ts,tsx}", "src/test/**", "src/main.tsx", "src/components/ui/**"],
    },
  },
});
