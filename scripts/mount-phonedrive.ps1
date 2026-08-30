# PhonDrive - Mount script using rclone
# Usage: .\mount-phonedrive.ps1 [-DriveLetter Z] [-Unmount]

param(
    [string]$DriveLetter = "Z",
    [switch]$Unmount
)

$rcloneExe = "C:\Users\PC\AppData\Local\Microsoft\WinGet\Packages\Rclone.Rclone_Microsoft.Winget.Source_8wekyb3d8bbwe\rclone-v1.74.4-windows-amd64\rclone.exe"
$configFile = "C:\Users\PC\AppData\Port\rclone\rclone.conf"
$phoneIP = "100.84.246.7"
$phonePort = "8080"

Write-Host "Checking phone WebDAV server..."
try {
    $cred = [System.Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("user:pass"))
    $resp = Invoke-WebRequest -Uri "http://${phoneIP}:${phonePort}/" -UseBasicParsing -TimeoutSec 5 -Headers @{Authorization="Basic $cred"}
    Write-Host "Phone server OK!" -ForegroundColor Green
} catch {
    Write-Host "ERROR: Phone WebDAV server not responding at ${phoneIP}:${phonePort}" -ForegroundColor Red
    Write-Host "Make sure PhonDrive app is running on the phone." -ForegroundColor Yellow
    exit 1
}

if ($Unmount) {
    Write-Host "Unmounting ${DriveLetter}:..."
    & $rcloneExe mount "phonedrive:/" "${DriveLetter}:" --config $configFile --unmount 2>&1
    Write-Host "Done." -ForegroundColor Green
    exit 0
}

$existingMount = Get-PSDrive -Name $DriveLetter -ErrorAction SilentlyContinue
if ($existingMount) {
    Write-Host "Drive ${DriveLetter}: is already mounted at $($existingMount.Root)" -ForegroundColor Yellow
    exit 1
}

Write-Host "Mounting phone storage as ${DriveLetter}: drive..."
$job = Start-Job -ScriptBlock {
    param($exe, $config, $letter)
    & $exe mount "phonedrive:/" "${letter}:" --config $config --volname PhonDrive --vfs-cache-mode writes --network-mode --dir-cache-time 5s --log-file "C:\Users\PC\AppData\Local\PhonDrive\mount.log"
} -ArgumentList $rcloneExe, $configFile, $DriveLetter

Start-Sleep -Seconds 3

$drive = Get-PSDrive -Name $DriveLetter -ErrorAction SilentlyContinue
if ($drive) {
    Write-Host "Mounted! ${DriveLetter}: = Phone storage" -ForegroundColor Green
    Write-Host "Root: $($drive.Root)"
    dir "${DriveLetter}:\" | Select-Object -First 10
} else {
    Write-Host "Mount may have failed. Check log: C:\Users\PC\AppData\Local\PhonDrive\mount.log" -ForegroundColor Yellow
    Write-Host "Job state: $($job.State)"
}
