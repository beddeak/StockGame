param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$stockRoot = $PSScriptRoot
$stockJava = if ($env:STOCK_JAVA_HOME) { $env:STOCK_JAVA_HOME } elseif (Test-Path 'C:\Program Files\Java\jdk-21\bin\java.exe') { 'C:\Program Files\Java\jdk-21' } else { $env:JAVA_HOME }
if (-not $stockJava -or -not (Test-Path "$stockJava\bin\javac.exe")) { throw 'JDK 21 경로를 STOCK_JAVA_HOME 환경 변수로 지정하세요.' }
$env:JAVA_HOME = $stockJava
$env:PATH = "$stockJava\bin;" + $env:PATH
$stockMaven = Join-Path $stockRoot '.tools\apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path $stockMaven)) {
    if ($Offline) { throw '최초 한 번은 build.ps1을 -Offline 없이 실행하세요.' }
    & python (Join-Path $stockRoot 'tools\bootstrap.py')
    if ($LASTEXITCODE -ne 0) { throw '도구 다운로드 실패' }
}
Push-Location $stockRoot
try {
    # 고정 버전 Citizens를 SHA256 검증 후 로컬 Maven 저장소와 dist에 준비합니다.
    $stockNpcArgs = @((Join-Path $stockRoot 'tools\prepare_npc.py'))
    if ($Offline) { $stockNpcArgs += '--offline' } else { $stockNpcArgs += '--online' }
    & python @stockNpcArgs
    if ($LASTEXITCODE -ne 0) { throw 'Citizens 의존성 준비 실패' }
    $stockArgs = @('-B', '-ntp', "-Dmaven.repo.local=$stockRoot\.tools\m2", 'verify')
    if ($Offline) { $stockArgs = @('-o') + $stockArgs }
    & $stockMaven @stockArgs
    if ($LASTEXITCODE -ne 0) { throw '빌드/테스트 실패' }
    New-Item -ItemType Directory -Force -Path 'dist','server\plugins' | Out-Null
    Copy-Item -LiteralPath 'target\StockGame-1.0.0.jar' -Destination 'dist\StockGame-1.0.0.jar' -Force
    # 실행 중에는 dist만 갱신합니다. 다음 사용자 시작 때 start.ps1이 최신 JAR을 설치합니다.
    $stockPidFile = Join-Path $stockRoot 'server\server.pid'
    $stockRunning = $false
    if (Test-Path $stockPidFile) {
        $stockServerPid = 0
        if ([int]::TryParse((Get-Content -LiteralPath $stockPidFile -Raw).Trim(), [ref]$stockServerPid) -and $stockServerPid -gt 0) {
            $stockRunning = [bool](Get-Process -Id $stockServerPid -ErrorAction SilentlyContinue)
        } else {
            $stockRunning = $true
        }
    }
    # 기존 직접 실행 서버의 PID가 없는 경우도 기본 포트와 마지막 설정 포트로 보호합니다.
    $stockPorts = @(25565)
    $stockProperties = Join-Path $stockRoot 'server\server.properties'
    if (Test-Path $stockProperties) {
        $stockPortLine = Get-Content -LiteralPath $stockProperties | Where-Object { $_ -match '^server-port=[0-9]+$' } | Select-Object -Last 1
        if ($stockPortLine) { $stockPorts += [int]($stockPortLine -replace '^server-port=', '') }
    }
    foreach ($stockPort in ($stockPorts | Select-Object -Unique)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $stockPort -ErrorAction SilentlyContinue) { $stockRunning = $true }
    }
    if ($stockRunning) {
        Write-Host '서버 실행 중이거나 포트를 사용 중이어서 서버의 JAR은 교체하지 않았습니다. dist의 최신 빌드는 다음 start.bat 실행 때 자동 설치됩니다.'
    } else {
        Copy-Item -LiteralPath 'dist\StockGame-1.0.0.jar' -Destination 'server\plugins\StockGame-1.0.0.jar' -Force
        Copy-Item -LiteralPath 'dist\Citizens-2.0.35-b3581.jar' -Destination 'server\plugins\Citizens.jar' -Force
    }
    Get-FileHash 'dist\StockGame-1.0.0.jar' -Algorithm SHA256
} finally { Pop-Location }
