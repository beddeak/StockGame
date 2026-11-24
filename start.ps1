param([switch]$AcceptEula, [switch]$Background, [ValidateRange(1024,65535)][int]$Port = 25565, [ValidatePattern('^[1-9][0-9]*[MG]$')][string]$Memory = '2G')
$ErrorActionPreference = 'Stop'
$stockRoot = $PSScriptRoot
$stockServer = Join-Path $stockRoot 'server'
$stockJava = if ($env:STOCK_JAVA_HOME) { Join-Path $env:STOCK_JAVA_HOME 'bin\java.exe' } elseif (Test-Path 'C:\Program Files\Java\jdk-21\bin\java.exe') { 'C:\Program Files\Java\jdk-21\bin\java.exe' } else { 'java' }
$stockPidPath = Join-Path $stockServer 'server.pid'
if (Test-Path $stockPidPath) {
    $stockServerPid = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $stockPidPath -Raw).Trim(), [ref]$stockServerPid) -or $stockServerPid -le 0) { throw 'server\server.pid 내용이 잘못되었습니다. 서버 종료 여부를 확인하고 파일을 정리하세요.' }
    # 비정상 종료로 남은 PID는 Windows가 다른 프로그램에 재사용할 수 있어 java 여부와 시작 시각도 확인합니다.
    $stockServerProcess = Get-Process -Id $stockServerPid -ErrorAction SilentlyContinue
    if ($stockServerProcess -and $stockServerProcess.ProcessName -eq 'java' -and $stockServerProcess.StartTime -le (Get-Item -LiteralPath $stockPidPath).LastWriteTime) { throw '이미 실행 중인 StockGame 서버가 있습니다.' }
    Remove-Item -LiteralPath $stockPidPath -Force
}
$stockProps = Join-Path $stockServer 'server.properties'
$stockPortChecks = @($Port, 25565)
if (Test-Path $stockProps) {
    $stockPortLine = Get-Content -LiteralPath $stockProps | Where-Object { $_ -match '^server-port=[0-9]+$' } | Select-Object -Last 1
    if ($stockPortLine) { $stockPortChecks += [int]($stockPortLine -replace '^server-port=', '') }
}
foreach ($stockCheckPort in ($stockPortChecks | Select-Object -Unique)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $stockCheckPort -ErrorAction SilentlyContinue) { throw "포트 $stockCheckPort 를 사용 중인 서버가 있습니다. 기존 서버를 정상 종료한 뒤 다시 시작하세요." }
}
if (-not (Test-Path "$stockServer\paper.jar")) { & python "$stockRoot\tools\bootstrap.py"; if ($LASTEXITCODE -ne 0) { throw 'Paper 다운로드 실패' } }
$stockReleaseJar = Join-Path $stockRoot 'dist\StockGame-1.0.0.jar'
if (-not (Test-Path $stockReleaseJar)) { & "$stockRoot\build.ps1"; if ($LASTEXITCODE -ne 0) { throw '빌드 실패' } }
$stockNpcReleaseJar = Join-Path $stockRoot 'dist\Citizens-2.0.35-b3581.jar'
# 배포본의 기존 Citizens도 검증한 뒤 dist에 준비합니다. 로컬 원본이 없을 때만 다운로드합니다.
& python (Join-Path $stockRoot 'tools\prepare_npc.py') '--offline'
if ($LASTEXITCODE -ne 0) {
    & python (Join-Path $stockRoot 'tools\prepare_npc.py') '--online'
    if ($LASTEXITCODE -ne 0) { throw 'Citizens 준비 실패' }
}
# 실행 중인 PID/사용할 포트가 없는 것을 확인한 뒤, 최신 빌드를 설치합니다.
New-Item -ItemType Directory -Force -Path "$stockServer\plugins" | Out-Null
Copy-Item -LiteralPath $stockReleaseJar -Destination "$stockServer\plugins\StockGame-1.0.0.jar" -Force
Copy-Item -LiteralPath $stockNpcReleaseJar -Destination "$stockServer\plugins\Citizens.jar" -Force
$stockEula = Join-Path $stockServer 'eula.txt'
if ($AcceptEula) { Set-Content -LiteralPath $stockEula -Value '# https://aka.ms/MinecraftEULA', 'eula=true' -Encoding ASCII }
if (-not (Test-Path $stockEula) -or -not (Select-String -LiteralPath $stockEula -Pattern '^eula=true$' -Quiet)) { throw 'Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의하는 경우 start.ps1 -AcceptEula 로 실행하세요.' }
if (-not (Test-Path $stockProps)) {
    @"
server-port=$Port
server-ip=
online-mode=true
motd=Stock Exchange
max-players=10
spawn-protection=0
view-distance=6
simulation-distance=4
level-name=world
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
allow-nether=false
enable-command-block=false
enable-rcon=false
enable-query=false
sync-chunk-writes=true
"@ | Set-Content -LiteralPath $stockProps -Encoding ASCII
} else {
    $stockLines = Get-Content -LiteralPath $stockProps
    $stockLines -replace '^server-port=.*$', "server-port=$Port" | Set-Content -LiteralPath $stockProps -Encoding ASCII
}
$stockPython = (Get-Command python -ErrorAction Stop).Source
$stockSupervisor = Join-Path $stockRoot 'tools\server_supervisor.py'
if ($Background) {
    $stockArguments = '"{0}" --java "{1}" --memory {2}' -f $stockSupervisor, $stockJava, $Memory
    Start-Process -FilePath $stockPython -ArgumentList $stockArguments -WorkingDirectory $stockServer -WindowStyle Hidden | Out-Null
    Write-Host "서버를 백그라운드로 시작했습니다. 주소: localhost:$Port"
    Write-Host '준비 완료 여부는 server\logs\latest.log 에서 확인하세요. 종료: .\stop.bat 또는 .\stop.ps1'
    exit 0
}
Write-Host "서버를 시작합니다. 주소: localhost:$Port"
Write-Host '관리자 창 명령도 함께 받을 수 있습니다. 정상 종료: 콘솔 stop 또는 .\stop.bat'
& $stockPython $stockSupervisor '--java' $stockJava '--memory' $Memory '--console'
if ($LASTEXITCODE -ne 0) { throw "서버 종료 코드: $LASTEXITCODE" }
