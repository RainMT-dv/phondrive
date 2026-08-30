# PhonDrive Windows Build Script
# Builds the tray app into a standalone .exe using PyInstaller

param(
    [Parameter(Mandatory=$false)]
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$windowsDir = Join-Path $projectDir "windows"
$outputDir = Join-Path $projectDir "dist"

Write-Host "=== PhonDrive Windows Build ===" -ForegroundColor Cyan

# Check PyInstaller
Write-Host "Checking PyInstaller..." -ForegroundColor Yellow
$pyinstaller = Get-Command pyinstaller -ErrorAction SilentlyContinue
if (-not $pyinstaller) {
    Write-Host "PyInstaller not found. Installing..." -ForegroundColor Red
    pip install pyinstaller
}

# Clean if requested
if ($Clean) {
    Write-Host "Cleaning build artifacts..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force "$windowsDir\build" -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force "$windowsDir\dist" -ErrorAction SilentlyContinue
    Remove-Item -Force "$windowsDir\*.spec" -ErrorAction SilentlyContinue
}

# Build
Write-Host "Building PhonDrive.exe..." -ForegroundColor Yellow
Push-Location $windowsDir

pyinstaller `
    --onefile `
    --noconsole `
    --name "PhonDrive" `
    --add-data "requirements.txt;." `
    --hidden-import pystray `
    --hidden-import PIL `
    --hidden-import PIL.Image `
    --hidden-import PIL.ImageDraw `
    tray_app.py

Pop-Location

# Check result
$exePath = Join-Path $outputDir "PhonDrive.exe"
if (Test-Path $exePath) {
    $size = (Get-Item $exePath).Length / 1MB
    Write-Host ""
    Write-Host "BUILD SUCCESSFUL" -ForegroundColor Green
    Write-Host "Output: $exePath"
    Write-Host "Size: $([math]::Round($size, 1)) MB"
    Write-Host ""
    Write-Host "Note: Windows SmartScreen may warn about this unsigned executable." -ForegroundColor Yellow
    Write-Host "Click 'More info' -> 'Run anyway' to proceed." -ForegroundColor Yellow
} else {
    Write-Host "BUILD FAILED" -ForegroundColor Red
    exit 1
}
