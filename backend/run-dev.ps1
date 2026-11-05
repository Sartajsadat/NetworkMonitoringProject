<#
.SYNOPSIS
  Starts the NetMon backend for local development on Windows without typing settings each time.

.DESCRIPTION
  The first run asks for the database password once and saves the local settings in
  backend\.env.local (ignored by Git, never committed). It also creates a JWT secret there, so you
  stay signed in across restarts. Later runs just start the backend.

  Development only. Production uses the .env file and Docker Compose (docs/DEPLOYMENT.md).

.EXAMPLE
  cd D:\NetworkMonitoringProject\backend
  .\run-dev.ps1

.EXAMPLE
  .\run-dev.ps1 -Reset      # forget the saved settings and ask again
#>
param([switch] $Reset)

$ErrorActionPreference = 'Stop'
$settingsFile = Join-Path $PSScriptRoot '.env.local'

if ($Reset -and (Test-Path $settingsFile)) { Remove-Item $settingsFile }

if (-not (Test-Path $settingsFile)) {
    Write-Host 'First run: local database settings (saved in backend\.env.local, not committed).' -ForegroundColor Cyan
    $dbUser = Read-Host 'Database user [netmon]'
    if ([string]::IsNullOrWhiteSpace($dbUser)) { $dbUser = 'netmon' }
    $dbName = Read-Host 'Database name [netmon]'
    if ([string]::IsNullOrWhiteSpace($dbName)) { $dbName = 'netmon' }
    $dbPort = Read-Host 'Database port [5432]'
    if ([string]::IsNullOrWhiteSpace($dbPort)) { $dbPort = '5432' }
    $secure = Read-Host 'Database password (leave empty if PostgreSQL is set to "trust")' -AsSecureString
    $dbPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    $bytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $jwt = [Convert]::ToBase64String($bytes)
    @(
        "DB_URL=jdbc:postgresql://localhost:$dbPort/$dbName"
        "DB_USERNAME=$dbUser"
        "DB_PASSWORD=$dbPassword"
        "JWT_SECRET=$jwt"
        'REFRESH_COOKIE_SECURE=false'
        'CAPTURE_STORAGE_DIR=' + (Join-Path $PSScriptRoot 'dev-captures')
    ) | Set-Content -Path $settingsFile -Encoding UTF8
    # Readable only by the current user (best effort; the file is still ignored by Git if this fails).
    try {
        $acl = Get-Acl $settingsFile
        $acl.SetAccessRuleProtection($true, $false)
        $me = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($me, 'FullControl', 'Allow')))
        Set-Acl $settingsFile $acl
    } catch {
        Write-Host "Note: could not restrict file permissions ($($_.Exception.Message))." -ForegroundColor Yellow
    }
    Write-Host "Saved. Run .\run-dev.ps1 -Reset to change them." -ForegroundColor Cyan
}

Get-Content $settingsFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "env:$name" -Value $value
}

# A backend that is still running would hold port 8080 and the new one could not start.
$busy = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    $proc = Get-Process -Id $busy[0].OwningProcess -ErrorAction SilentlyContinue
    Write-Host "Port 8080 is already used by $($proc.ProcessName) (PID $($busy[0].OwningProcess))." -ForegroundColor Yellow
    Write-Host "Stop it with:  Stop-Process -Id $($busy[0].OwningProcess)   then run this script again." -ForegroundColor Yellow
    exit 1
}

Write-Host "Starting the backend ($env:DB_USERNAME @ $env:DB_URL) ..." -ForegroundColor Green
$log = Join-Path $PSScriptRoot 'run.log'
& (Join-Path $PSScriptRoot 'mvnw.cmd') spring-boot:run 2>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) {
    # Maven's last lines only say "BUILD FAILURE"; show the actual reason.
    Write-Host ''
    Write-Host '==================== WHY IT STOPPED ====================' -ForegroundColor Red
    $why = Select-String -Path $log -Pattern 'Caused by:', 'Description:', 'Action:', 'already in use', 'APPLICATION FAILED', 'FATAL:', 'Message    :' |
        Select-Object -First 8 | ForEach-Object { $_.Line.Trim() }
    if ($why) { $why | ForEach-Object { Write-Host $_ -ForegroundColor Red } } else { Write-Host "See $log" -ForegroundColor Red }
    Write-Host '========================================================' -ForegroundColor Red
    if ($why -match 'password authentication failed') {
        Write-Host 'The database password is wrong: run .\run-dev.ps1 -Reset and type the right one,' -ForegroundColor Yellow
        Write-Host 'or set it in PostgreSQL:  ALTER USER netmon WITH PASSWORD ''...'';' -ForegroundColor Yellow
    } elseif ($why -match 'does not exist') {
        Write-Host 'The database or user does not exist yet: create it with CREATE USER / CREATE DATABASE (docs/INSTALL-WINDOWS-DEV.md).' -ForegroundColor Yellow
    } elseif ($why -match 'Connection refused|Connection to localhost') {
        Write-Host 'PostgreSQL is not running (Services -> postgresql-x64-16 -> Start), or uses another port.' -ForegroundColor Yellow
    }
    exit 1
}
