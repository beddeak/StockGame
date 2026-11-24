$ErrorActionPreference = 'Stop'
$stockPidFile = Join-Path $PSScriptRoot 'server\admin-panel.pid'
if (Test-Path $stockPidFile) {
    $stockOldPanel=Get-Process -Id ([int](Get-Content $stockPidFile)) -ErrorAction SilentlyContinue
    if ($stockOldPanel) { Write-Host 'StockGame 관리자 창이 이미 실행 중입니다.'; exit 0 }
}
$stockPython = (Get-Command python -ErrorAction Stop).Source
$stockPythonWindow = Join-Path (Split-Path $stockPython) 'pythonw.exe'
if (-not (Test-Path $stockPythonWindow)) { $stockPythonWindow = $stockPython }
$stockScript = Join-Path $PSScriptRoot 'tools\admin_panel.py'
# 사용자가 관리자 창을 열어 달라고 요청했으므로 이 앱 창은 표시합니다.
Start-Process -FilePath $stockPythonWindow -ArgumentList ('"{0}"' -f $stockScript) -WorkingDirectory $PSScriptRoot -WindowStyle Normal | Out-Null
