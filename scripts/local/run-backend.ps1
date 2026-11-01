# 로컬 끝까지 돌려 보기용 백엔드. 역할극 HTTP API 를 켜고, STT 는 대본(scripted)·TTS 는 끔(silent), LLM 은 엘리스 ML API 실제 모델이다.
#   역할극 원인 판단·응답 생성: 기본 경로(AI_*) = MODEL_HAIKU55_*
#   리포트 초안: AI_REPORT_GENERATION_* = MODEL_HAIKU55_*, 리포트 평가: AI_REPORT_REVIEW_* = MODEL_ARGON4_*
# 모델 주소·키는 backend/.env.local 에서 읽고 화면에 찍지 않는다. DB 는 이 PC 의 PostgreSQL(5432)과 겹치지 않게 55433 컨테이너다.
#   powershell -File scripts/local/run-backend.ps1
param(
    [string]$Container = "neuringo-rr-db",
    [int]$DbPort = 55433
)
$ErrorActionPreference = "Stop"
$backend = Join-Path $PSScriptRoot "..\..\backend" | Resolve-Path
$stateDir = Join-Path $env:LOCALAPPDATA "neuringo-roleplay-report"
New-Item -ItemType Directory -Force $stateDir, (Join-Path $stateDir "spool") | Out-Null

function Get-OrCreateSecret([string]$name, [int]$bytes) {
    $path = Join-Path $stateDir $name
    if (-not (Test-Path $path)) {
        $buffer = New-Object byte[] $bytes
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
        [Convert]::ToBase64String($buffer).Replace('+', 'A').Replace('/', 'B').Replace('=', '') | Set-Content -NoNewline -Encoding ascii $path
    }
    (Get-Content -Raw $path).Trim()
}
$dbPassword = Get-OrCreateSecret "db-password" 24
$hmacSecret = Get-OrCreateSecret "child-access-hmac" 48

# Windows PowerShell 5.1 은 Stop 일 때 네이티브 명령의 stderr 를 오류로 바꾼다. docker 는 stderr 를 내지 않는 방식으로 묻는다.
$ErrorActionPreference = "Continue"
$existing = docker ps -a --filter "name=^${Container}$" --format "{{.Names}}"
if (-not $existing) {
    docker run -d --name $Container -e POSTGRES_DB=neuringo_local -e POSTGRES_USER=neuringo -e "POSTGRES_PASSWORD=$dbPassword" -p "${DbPort}:5432" postgres:18.6 | Out-Null
} else {
    docker start $Container | Out-Null
}
for ($i = 0; $i -lt 30; $i++) {
    docker exec $Container pg_isready -U neuringo -d neuringo_local *> $null
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Seconds 1
}
$ErrorActionPreference = "Stop"

$envFile = @{}
Get-Content (Join-Path $backend ".env.local") | Where-Object { $_ -match '^\s*[A-Z_0-9]+\s*=' } | ForEach-Object {
    $key, $value = $_ -split '=', 2
    $envFile[$key.Trim()] = $value.Trim().Trim('"').Trim("'")
}
foreach ($required in "AI_API_KEY", "MODEL_HAIKU55_URL", "MODEL_HAIKU55_ID", "MODEL_ARGON4_URL", "MODEL_ARGON4_ID") {
    if (-not $envFile[$required]) { throw "backend/.env.local has no $required" }
}

$settings = @{
    SPRING_PROFILES_ACTIVE = "local"
    SPRING_DOCKER_COMPOSE_ENABLED = "false"
    DB_URL = "jdbc:postgresql://localhost:$DbPort/neuringo_local"
    DB_USERNAME = "neuringo"
    DB_PASSWORD = $dbPassword
    CHILD_ACCESS_HMAC_SECRET = $hmacSecret
    AI_CHAT_PROVIDER = "openai"
    AI_API_KEY = $envFile["AI_API_KEY"]
    AI_BASE_URL = $envFile["MODEL_HAIKU55_URL"]
    AI_MODEL = $envFile["MODEL_HAIKU55_ID"]
    AI_TIMEOUT = "25s"
    AI_MAX_RETRIES = "0"
    AI_MAX_OUTPUT_TOKENS = "2000"
    # 추론 모델은 추론 토큰도 2,000 상한에 들어가 JSON 이 잘린다. 지난 실측처럼 haiku55 는 추론을 끈 값을 쓴다.
    AI_REASONING_EFFORT = $envFile["MODEL_HAIKU55_REASONING"]
    NEURINGO_AI_CALL_LOG = "true"
    # 모델 출력이 서버 검사(80자·물음표 수·전략 일치 등)에 걸리면 어느 검사인지 남긴다. 모델 출력 글은 남기지 않는다.
    LOGGING_LEVEL_COM_NEURINGO_NEURINGOBE_AI_APPLICATION_STRUCTURED = "DEBUG"
    AI_REPORT_GENERATION_BASE_URL = $envFile["MODEL_HAIKU55_URL"]
    AI_REPORT_GENERATION_MODEL = $envFile["MODEL_HAIKU55_ID"]
    AI_REPORT_GENERATION_REASONING_EFFORT = $envFile["MODEL_HAIKU55_REASONING"]
    AI_REPORT_REVIEW_BASE_URL = $envFile["MODEL_ARGON4_URL"]
    AI_REPORT_REVIEW_MODEL = $envFile["MODEL_ARGON4_ID"]
    AI_REPORT_REVIEW_REASONING_EFFORT = $envFile["MODEL_ARGON4_REASONING"]
    STT_PROVIDER = "scripted"
    TTS_PROVIDER = "silent"
    ROLEPLAY_HTTP_ENABLED = "true"
    # 팀 코드에 운영 입력 처리기가 아직 없다. 대본 답의 공백만 정리하는 로컬 처리기를 켠다(실제 아동 음성에는 쓰지 않는다).
    ROLEPLAY_LOCAL_SCRIPT_INPUT = "true"
    ROLEPLAY_SPOOL_DIRECTORY = (Join-Path $stateDir "spool")
    ROLEPLAY_UPLOAD_MAX_BYTES = "2097152"
    ROLEPLAY_REQUEST_MAX_BYTES = "2200000"
    ROLEPLAY_ALLOWED_FORMATS = "WAV,WEBM"
    ROLEPLAY_WORKER_COUNT = "2"
    ROLEPLAY_QUEUE_CAPACITY = "4"
    ROLEPLAY_MAXIMUM_TURN_COUNT = "10"
    ROLEPLAY_MAXIMUM_SUPPORT_TURNS_PER_MICRO_GOAL = "3"
}
foreach ($entry in $settings.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
}
Write-Output "db: $Container on $DbPort, roleplay http on, stt scripted, tts silent, report routes on (values hidden)"
Set-Location $backend
& .\gradlew.bat bootRun --console=plain
