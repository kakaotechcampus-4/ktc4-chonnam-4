# 활동 ID 로 학급·목표 내용 해시를 찾아 승인 시나리오(L1·L2·L3)를 넣는다. DB 는 run-backend.ps1 이 띄운 컨테이너다.
#   powershell -File scripts/local/seed-scenarios.ps1 -ActivityId <활동 UUID> -Tag first
param(
    [Parameter(Mandatory = $true)][string]$ActivityId,
    [string]$Tag = "local",
    [string]$Container = "neuringo-rr-db",
    [string]$Database = "neuringo_local",
    [string]$User = "neuringo"
)
$ErrorActionPreference = "Stop"
if ($ActivityId -notmatch '^[0-9a-f-]{36}$') { throw "ActivityId must be a UUID" }

$row = docker exec $Container psql -U $User -d $Database -At -F '|' -c "select c.class_id, g.content_hash from activity a join child c on c.child_id = a.child_id join learning_goal g on g.goal_id = a.goal_id where a.activity_id = '$ActivityId'"
if (-not $row) { throw "activity $ActivityId has no goal" }
$classId, $goalHash = $row.Trim().Split('|')

# Windows PowerShell 5.1 은 네이티브 명령에 파이프로 넘길 때 ASCII 로 바꿔 한글이 ? 로 깨진다. 파일을 컨테이너에 복사해 psql 이 직접 읽게 한다.
$sql = Join-Path $PSScriptRoot "seed-approved-scenarios.sql"
docker cp $sql "${Container}:/tmp/seed-approved-scenarios.sql" | Out-Null
docker exec $Container psql -U $User -d $Database -v "class_id=$classId" -v "goal_hash=$goalHash" -v "tag=$Tag" -f /tmp/seed-approved-scenarios.sql
