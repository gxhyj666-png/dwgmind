# DwgMind 启动服务
# 由 start.bat 调用；也可单独执行：powershell -ExecutionPolicy Bypass -File scripts\start.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$javaHome = 'C:\Users\86180\.jdks\corretto-17.0.20.1'
$javaExe = Join-Path $javaHome 'bin\java.exe'
$jar = Join-Path $root 'backend\target\dwgmind.jar'
$frontend = Join-Path $root 'frontend'

Write-Host '============================================'
Write-Host '  DwgMind - Start services'
Write-Host '============================================'
Write-Host ''

# ---------- 1. 环境检查 ----------
Write-Host '[1/4] Checking JDK 17 ...'
if (-not (Test-Path $javaExe)) {
    Write-Host "  [FAIL] JDK not found: $javaExe" -ForegroundColor Red
    exit 1
}
# java -version 会写 stderr；在 Stop 模式下必须单独兜住，否则会被当成终止性错误
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$ver = (& $javaExe -version 2>&1 | Out-String).Trim().Split("`n")[0]
$ErrorActionPreference = $prevEap
if ($ver -notmatch '17') {
    Write-Host "  [WARN] JDK version may not be 17: $ver" -ForegroundColor Yellow
} else {
    Write-Host "  [OK] $ver"
}

if (-not (Test-Path $jar)) {
    Write-Host "  [FAIL] jar not found: $jar" -ForegroundColor Red
    Write-Host '  Run: cd backend ^&^& mvn -DskipTests package'
    exit 1
}

# ---------- 2. 清理占用 ----------
Write-Host '[2/4] Checking ports 8080 / 5173 ...'
foreach ($port in @(8080, 5173)) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conns) {
        $pids = $conns | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($procId in $pids) {
            Write-Host "  port $port occupied by PID=$procId, stopping it first"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 600
    }
}
Write-Host '  [OK] ports are free'

# ---------- 3. 启动后端与前端 ----------
Write-Host '[3/4] Starting backend (8080) and frontend (5173) ...'

# 大模型配置：优先取环境变量 / 本机配置文件，未配置则不传（后端自动降级为纯规则引擎）
$llmEnv = [Ordered]@{}
$llmConfigFile = Join-Path $root 'llm.local.env'
if (Test-Path $llmConfigFile) {
    Get-Content $llmConfigFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
            $kv = $line.Split('=', 2)
            $llmEnv[$kv[0].Trim()] = $kv[1].Trim()
        }
    }
    Write-Host '  [OK] loaded llm.local.env'
}
foreach ($name in @('LLM_PROVIDER', 'LLM_BASE_URL', 'LLM_MODEL', 'LLM_API_KEY', 'LLM_ENABLED')) {
    if (-not $llmEnv.Contains($name)) {
        $v = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not $v) { $v = [Environment]::GetEnvironmentVariable($name, 'User') }
        if ($v) { $llmEnv[$name] = $v }
    }
}

# 用「当前进程环境变量 + Start-Process」传递配置：
# Start-Process 会脱离父进程独立存活（脚本退出不会带走 Java），
# 同时子进程继承本 PowerShell 进程的环境变量，实现对每个进程注入配置。
foreach ($k in $llmEnv.Keys) {
    Set-Item -Path "Env:$k" -Value ([string]$llmEnv[$k])
}
if ($llmEnv.Contains('LLM_API_KEY') -and $llmEnv['LLM_API_KEY']) {
    $masked = $llmEnv['LLM_API_KEY']
    if ($masked.Length -gt 12) { $masked = $masked.Substring(0, 8) + '...' + $masked.Substring($masked.Length - 4) }
    $model = if ($llmEnv.Contains('LLM_MODEL')) { $llmEnv['LLM_MODEL'] } else { 'deepseek-chat' }
    Write-Host "  [OK] LLM enabled: $model (key $masked)"
} else {
    Write-Host '  [i] LLM_API_KEY not set -> rule-only mode'
}

Start-Process -FilePath $javaExe `
    -ArgumentList @('-jar', "`"$jar`"") `
    -WorkingDirectory $root `
    -WindowStyle Minimized `
    -RedirectStandardOutput (Join-Path $root 'backend\backend-runtime.log') `
    -RedirectStandardError (Join-Path $root 'backend\backend-runtime.err.log')

Start-Process -FilePath 'node' -ArgumentList @("`"$frontend\server.js`"", '5173') -WorkingDirectory $frontend -WindowStyle Minimized

# ---------- 4. 等待就绪 ----------
Write-Host '[4/4] Waiting for services to be ready ...'
$backendOk = $false
$frontendOk = $false
$healthUrl = 'http://127.0.0.1:8080/api/v1/health'
$frontUrl = 'http://127.0.0.1:5173/'

for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 750
    if (-not $backendOk) {
        try {
            $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { $backendOk = $true }
        } catch { }
    }
    if (-not $frontendOk) {
        try {
            $r = Invoke-WebRequest -Uri $frontUrl -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { $frontendOk = $true }
        } catch { }
    }
    if ($backendOk -and $frontendOk) { break }
}

Write-Host ''
if ($backendOk) { Write-Host '  [OK] backend  http://127.0.0.1:8080' -ForegroundColor Green }
else { Write-Host '  [WARN] backend not responding, check the console window' -ForegroundColor Yellow }

if ($frontendOk) { Write-Host '  [OK] frontend http://127.0.0.1:5173' -ForegroundColor Green }
else { Write-Host '  [WARN] frontend not responding (is Node.js installed?)' -ForegroundColor Yellow }

# ---------- 局域网地址（手机 / 平板访问用） ----------
Write-Host ''
Write-Host '  --- Phone / Tablet access (same Wi-Fi) ---' -ForegroundColor Cyan
$lanIps = @()
try {
    # 只取「已连接」的物理网卡，排除虚拟网卡与已断开的接口
    $upAdapters = Get-NetAdapter -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Status -eq 'Up' -and
            $_.InterfaceDescription -notmatch 'VirtualBox|VMware|Hyper-V|Loopback|TAP'
        } |
        Select-Object -ExpandProperty Name
    if ($upAdapters) {
        $lanIps = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -notlike '127.*' -and
                $_.IPAddress -notlike '169.254.*' -and
                $upAdapters -contains $_.InterfaceAlias
            } |
            Select-Object -ExpandProperty IPAddress -Unique
    }
} catch { }

if ($lanIps.Count -gt 0) {
    foreach ($ip in $lanIps) {
        Write-Host ("    http://{0}:5173" -f $ip) -ForegroundColor White
    }
    Write-Host ''
    Write-Host '    Open the URL above in your phone browser, then use' -ForegroundColor DarkGray
    Write-Host '    "Add to Home Screen" to install it as an app.' -ForegroundColor DarkGray
    Write-Host '    Note: phone and PC must be on the same network.' -ForegroundColor DarkGray
} else {
    Write-Host '    (no LAN IP detected, phone access unavailable)' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '  To stop: double-click stop.bat'
Write-Host ''

if ($backendOk -and $frontendOk) {
    Start-Process $frontUrl
}

