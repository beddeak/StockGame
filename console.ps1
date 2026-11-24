param([Parameter(Mandatory=$true)][string]$Command)
$ErrorActionPreference = 'Stop'
$stockPidPath = Join-Path $PSScriptRoot 'server\server.pid'
if (-not (Test-Path $stockPidPath)) { throw '백그라운드 서버가 실행 중이 아닙니다.' }
if (-not (Get-Process -Id ([int](Get-Content $stockPidPath)) -ErrorAction SilentlyContinue)) { throw '서버 프로세스가 종료되었습니다.' }
$stockInbox = Join-Path $PSScriptRoot 'server\control\inbox'
if (-not (Test-Path $stockInbox)) { throw 'start.ps1 -Background 로 시작한 서버에서 사용하세요.' }
$stockRequest = Join-Path $stockInbox (([DateTime]::UtcNow.Ticks.ToString()) + '-' + ([guid]::NewGuid().ToString()) + '.cmd')
[System.IO.File]::WriteAllText("$stockRequest.tmp", $Command, (New-Object System.Text.UTF8Encoding($false)))
Move-Item -LiteralPath "$stockRequest.tmp" -Destination $stockRequest
Write-Host "콘솔 명령 전달: $Command"
Write-Host '결과: server\console.log 또는 server\logs\latest.log'
