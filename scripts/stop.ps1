# DwgMind 停止服务
# 由 stop.bat 调用；也可单独执行：powershell -ExecutionPolicy Bypass -File scripts\stop.ps1

$ErrorActionPreference = 'SilentlyContinue'

Write-Host '============================================'
Write-Host '  DwgMind stop services'
Write-Host '============================================'
Write-Host ''

$targets = @(
    @{ Port = 8080; Name = 'Backend(Spring Boot)' },
    @{ Port = 5173; Name = 'Frontend(Static Server)' }
)

$stopped = $false

foreach ($t in $targets) {
    $conns = Get-NetTCPConnection -LocalPort $t.Port -State Listen
    if (-not $conns) {
        Write-Host ("  [skip] {0} port {1} not listening" -f $t.Name, $t.Port)
        continue
    }
    $pids = $conns | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        $pr = Get-Process -Id $procId
        if ($pr) {
            Write-Host ("  [stop] {0} PID={1} ({2})" -f $t.Name, $procId, $pr.ProcessName)
            Stop-Process -Id $procId -Force
            $stopped = $true
        }
    }
}

Start-Sleep -Milliseconds 900

$left = Get-NetTCPConnection -LocalPort 8080, 5173 -State Listen
if ($left) {
    Write-Host ''
    Write-Host '  [WARN] ports still in use:' -ForegroundColor Yellow
    $left | Format-Table LocalPort, OwningProcess -AutoSize
} elseif ($stopped) {
    Write-Host ''
    Write-Host '  All services stopped successfully.' -ForegroundColor Green
} else {
    Write-Host ''
    Write-Host '  Nothing to stop (both ports are free).'
}

Write-Host ''
Write-Host 'Note: the two console windows opened by start.bat'
Write-Host '      (DwgMind-Backend / DwgMind-Frontend) can now be closed.'
