# PhonDrive Windows Setup
# Run this once to install prerequisites (rclone + WinFsp)

Write-Host "=== PhonDrive Windows Setup ===" -ForegroundColor Cyan
Write-Host ""

# Check rclone
Write-Host "Checking rclone..." -ForegroundColor Yellow
$rclone = Get-Command rclone -ErrorAction SilentlyContinue
if ($rclone) {
    $version = & rclone version --check 2>&1 | Select-Object -First 1
    Write-Host "  OK: rclone found at $($rclone.Source)" -ForegroundColor Green
    Write-Host "  Version: $version"
} else {
    Write-Host "  rclone NOT found. Installing via winget..." -ForegroundColor Red
    winget install Rclone.Rclone --accept-source-agreements --accept-package-agreements
    Write-Host "  Installed. You may need to restart your terminal." -ForegroundColor Green
}

# Check WinFsp
Write-Host ""
Write-Host "Checking WinFsp..." -ForegroundColor Yellow
$winfsp = Get-ItemProperty "HKLM:\SOFTWARE\WinFsp" -ErrorAction SilentlyContinue
if ($winfsp) {
    Write-Host "  OK: WinFsp installed" -ForegroundColor Green
} else {
    Write-Host "  WinFsp NOT found. Installing via winget..." -ForegroundColor Red
    winget install WinFsp.WinFsp --accept-source-agreements --accept-package-agreements
    Write-Host "  Installed. You may need to restart your terminal." -ForegroundColor Green
}

# Check Python
Write-Host ""
Write-Host "Checking Python..." -ForegroundColor Yellow
$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) {
    $version = & python --version 2>&1
    Write-Host "  OK: $version" -ForegroundColor Green
} else {
    Write-Host "  Python NOT found. Please install Python 3.8+." -ForegroundColor Red
}

# Check pystray
Write-Host ""
Write-Host "Checking pystray..." -ForegroundColor Yellow
$pystray = python -c "import pystray" 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "  OK: pystray installed" -ForegroundColor Green
} else {
    Write-Host "  pystray NOT found. Installing..." -ForegroundColor Red
    pip install pystray Pillow
    Write-Host "  Installed." -ForegroundColor Green
}

# Check Tailscale
Write-Host ""
Write-Host "Checking Tailscale..." -ForegroundColor Yellow
$ts = Get-Command tailscale -ErrorAction SilentlyContinue
if ($ts) {
    Write-Host "  OK: Tailscale found" -ForegroundColor Green
} else {
    Write-Host "  Tailscale NOT found. Please install from https://tailscale.com" -ForegroundColor Red
}

# Create rclone remote config
Write-Host ""
Write-Host "Setting up rclone remote 'phondrive'..." -ForegroundColor Yellow
$rcloneConfigDir = "$env:APPDATA\rclone"
$rcloneConfigFile = "$rcloneConfigDir\rclone.conf"

if (-not (Test-Path $rcloneConfigDir)) {
    New-Item -ItemType Directory -Path $rcloneConfigDir -Force | Out-Null
}

$existingConfig = if (Test-Path $rcloneConfigFile) { Get-Content $rcloneConfigFile -Raw } else { "" }
if ($existingConfig -notmatch "\[phondrive\]") {
    # Get phone IP
    Write-Host "  Enter your phone's Tailscale IP (e.g., 100.84.246.7):" -ForegroundColor Yellow
    $phoneIP = Read-Host "  Phone IP"
    
    if ($phoneIP) {
        $config = @"
[phondrive]
type = webdav
url = http://${phoneIP}:8080
vendor = other
user = user
pass = pass
"@
        Add-Content -Path $rcloneConfigFile -Value $config
        Write-Host "  Created rclone remote 'phondrive'" -ForegroundColor Green
    } else {
        Write-Host "  Skipped. You can configure later in $rcloneConfigFile" -ForegroundColor Yellow
    }
} else {
    Write-Host "  OK: rclone remote 'phondrive' already exists" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Setup Complete ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "To mount your phone:" -ForegroundColor Yellow
Write-Host "  python windows/tray_app.py"
Write-Host ""
Write-Host "Or manually:" -ForegroundColor Yellow
Write-Host "  rclone mount phondrive:/ Z: --volname PhonDrive --vfs-cache-mode writes --network-mode"
