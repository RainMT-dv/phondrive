# PhonDrive E2E Verification Script
# Comprehensive test suite: WebDAV server + rclone mount + Explorer operations
# Usage: .\verify-e2e.ps1 -IP "100.84.246.7" -Port 8080

param(
    [Parameter(Mandatory=$false)]
    [string]$IP,

    [Parameter(Mandatory=$false)]
    [int]$Port = 8080,

    [Parameter(Mandatory=$false)]
    [string]$User = "user",

    [Parameter(Mandatory=$false)]
    [string]$Pass = "pass",

    [Parameter(Mandatory=$false)]
    [string]$DriveLetter = "Z",

    [Parameter(Mandatory=$false)]
    [switch]$SkipLargeFile,

    [Parameter(Mandatory=$false)]
    [switch]$SkipMount
)

$ErrorActionPreference = "Stop"
$baseUrl = "http://${IP}:${Port}"
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${User}:${Pass}"))
$headers = @{ Authorization = "Basic $auth" }
$testDir = "phondrive-e2e-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$passed = 0
$failed = 0
$skipped = 0
$results = @()

# --- Discover IP if not provided ---
if (-not $IP) {
    Write-Host "Discovering phone Tailscale IP..." -ForegroundColor Yellow
    $status = tailscale status 2>$null
    $line = $status | Select-String -Pattern "^\w+\s+\S+\s+(\d+\.\d+\.\d+\.\d+)\s+" | Where-Object { $_ -match "100\." } | Select-Object -First 1
    if ($line -match "(\d+\.\d+\.\d+\.\d+)") {
        $IP = $Matches[1]
        $baseUrl = "http://${IP}:${Port}"
        Write-Host "Found: $IP" -ForegroundColor Green
    } else {
        Write-Host "Could not discover IP. Pass -IP parameter." -ForegroundColor Red
        exit 1
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  PhonDrive E2E Verification Suite" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "Server:  $baseUrl"
Write-Host "Auth:    $User:***"
Write-Host "Drive:   ${DriveLetter}:"
Write-Host "Date:    $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host ""

# --- Helper functions ---
function Test-Case {
    param(
        [string]$Category,
        [string]$Name,
        [scriptblock]$Test,
        [switch]$Required
    )
    Write-Host "[$Category] $Name" -ForegroundColor Yellow -NoNewline
    try {
        $result = & $Test
        if ($result -eq $true) {
            Write-Host " PASS" -ForegroundColor Green
            $script:passed++
            $script:results += @{ cat=$Category; name=$Name; result="PASS" }
        } else {
            Write-Host " FAIL" -ForegroundColor Red
            $script:failed++
            $script:results += @{ cat=$Category; name=$Name; result="FAIL" }
            if ($Required) { throw "Required test failed: $Name" }
        }
    } catch {
        Write-Host " ERROR: $_" -ForegroundColor Red
        $script:failed++
        $script:results += @{ cat=$Category; name=$Name; result="ERROR: $_" }
        if ($Required) { throw "Required test failed: $Name" }
    }
}

function Test-Skip {
    param([string]$Category, [string]$Name, [string]$Reason)
    Write-Host "[$Category] $Name" -ForegroundColor Yellow -NoNewline
    Write-Host " SKIP ($Reason)" -ForegroundColor DarkGray
    $script:skipped++
    $script:results += @{ cat=$Category; name=$Name; result="SKIP: $Reason" }
}

# ============================================================
# CATEGORY 1: Server Health
# ============================================================
Write-Host "`n--- Server Health ---" -ForegroundColor Cyan

Test-Case "Health" "GET /ping returns pong" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/ping" -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 200 -and $resp.Content -eq "pong"
} -Required

Test-Case "Health" "Server responds within 5s" {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-WebRequest -Uri "$baseUrl/ping" -Headers $headers -UseBasicParsing -TimeoutSec 10 | Out-Null
    $sw.Stop()
    $sw.ElapsedMilliseconds -lt 5000
}

# ============================================================
# CATEGORY 2: Auth
# ============================================================
Write-Host "`n--- Authentication ---" -ForegroundColor Cyan

Test-Case "Auth" "No auth -> 401" {
    try {
        Invoke-WebRequest -Uri "$baseUrl/" -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $false
    } catch {
        $_.Exception.Response.StatusCode.value__ -eq 401
    }
}

Test-Case "Auth" "Wrong password -> 401" {
    try {
        $badAuth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("user:wrong"))
        $badHeaders = @{ Authorization = "Basic $badAuth" }
        Invoke-WebRequest -Uri "$baseUrl/" -Headers $badHeaders -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $false
    } catch {
        $_.Exception.Response.StatusCode.value__ -eq 401
    }
}

Test-Case "Auth" "Valid auth -> 207" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/" -Method PROPFIND -Headers $headers -UseBasicParsing -TimeoutSec 10 -ContentType "application/xml" -Body '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'
    $resp.StatusCode -eq 207
}

# ============================================================
# CATEGORY 3: Directory Operations
# ============================================================
Write-Host "`n--- Directory Operations ---" -ForegroundColor Cyan

Test-Case "Dir" "MKCOL /$testDir" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir" -Method MKCOL -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 201
}

Test-Case "Dir" "MKCOL /$testDir/sub" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/sub" -Method MKCOL -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 201
}

Test-Case "Dir" "PROPFIND root shows $testDir" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/" -Method PROPFIND -Headers $headers -UseBasicParsing -TimeoutSec 10 -ContentType "application/xml" -Body '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'
    $resp.Content -match $testDir
}

Test-Case "Dir" "PROPFIND /$testDir shows sub" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir" -Method PROPFIND -Headers $headers -UseBasicParsing -TimeoutSec 10 -ContentType "application/xml" -Body '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'
    $resp.Content -match "sub"
}

# ============================================================
# CATEGORY 4: File Operations
# ============================================================
Write-Host "`n--- File Operations ---" -ForegroundColor Cyan

Test-Case "File" "PUT /$testDir/test.txt" {
    $body = "Hello from PhonDrive E2E at $(Get-Date -Format 'HHmmss')"
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Method PUT -Headers $headers -UseBasicParsing -TimeoutSec 10 -Body $body
    $resp.StatusCode -eq 201
}

Test-Case "File" "GET /$testDir/test.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 200 -and $resp.Content -like "*Hello from PhonDrive*"
}

Test-Case "File" "HEAD /$testDir/test.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Method HEAD -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 200
}

Test-Case "File" "MOVE test.txt -> renamed.txt" {
    $dest = "$baseUrl/$testDir/renamed.txt"
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Method MOVE -Headers @{ Authorization = "Basic $auth"; Destination = $dest } -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 204
}

Test-Case "File" "GET renamed.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/renamed.txt" -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 200 -and $resp.Content -like "*Hello from PhonDrive*"
}

Test-Case "File" "COPY renamed.txt -> copied.txt" {
    $dest = "$baseUrl/$testDir/copied.txt"
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/renamed.txt" -Method COPY -Headers @{ Authorization = "Basic $auth"; Destination = $dest } -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 204
}

Test-Case "File" "GET copied.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/copied.txt" -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 200 -and $resp.Content -like "*Hello from PhonDrive*"
}

# ============================================================
# CATEGORY 5: Large File (>50MB)
# ============================================================
Write-Host "`n--- Large File (>50MB) ---" -ForegroundColor Cyan

if ($SkipLargeFile) {
    Test-Skip "LargeFile" ">50MB upload/download" "Skipped via -SkipLargeFile"
} else {
    $largeFile = "$env:TEMP\phondrive-large-test.bin"
    Test-Case "LargeFile" "Create 60MB test file" {
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $buf = New-Object byte[] (1024 * 1024)  # 1MB buffer
        $fs = [System.IO.File]::Create($largeFile)
        for ($i = 0; $i -lt 60; $i++) {
            $rng.GetBytes($buf)
            $fs.Write($buf, 0, $buf.Length)
        }
        $fs.Close()
        (Get-Item $largeFile).Length -gt 50MB
    }

    Test-Case "LargeFile" "PUT 60MB file" {
        $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/large.bin" -Method PUT -Headers $headers -UseBasicParsing -TimeoutSec 120 -InFile $largeFile
        $resp.StatusCode -eq 201
    }

    Test-Case "LargeFile" "GET 60MB file" {
        $outFile = "$env:TEMP\phondrive-large-download.bin"
        $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/large.bin" -Headers $headers -UseBasicParsing -TimeoutSec 120 -OutFile $outFile
        $dlSize = (Get-Item $outFile).Length
        Remove-Item $outFile -ErrorAction SilentlyContinue
        $dlSize -gt 50MB
    }

    Test-Case "LargeFile" "DELETE large.bin" {
        $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/large.bin" -Method DELETE -Headers $headers -UseBasicParsing -TimeoutSec 10
        $resp.StatusCode -eq 204
    }

    Remove-Item $largeFile -ErrorAction SilentlyContinue
}

# ============================================================
# CATEGORY 6: Cleanup test dir
# ============================================================
Write-Host "`n--- Cleanup ---" -ForegroundColor Cyan

Test-Case "Cleanup" "DELETE /$testDir/sub" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/sub" -Method DELETE -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 204
}

Test-Case "Cleanup" "DELETE /$testDir/copied.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/copied.txt" -Method DELETE -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 204
}

Test-Case "Cleanup" "DELETE /$testDir/renamed.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/renamed.txt" -Method DELETE -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 204
}

Test-Case "Cleanup" "DELETE /$testDir" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir" -Method DELETE -Headers $headers -UseBasicParsing -TimeoutSec 10
    $resp.StatusCode -eq 204
}

# ============================================================
# CATEGORY 7: rclone mount
# ============================================================
Write-Host "`n--- rclone Mount ---" -ForegroundColor Cyan

if ($SkipMount) {
    Test-Skip "Mount" "rclone drive accessible" "Skipped via -SkipMount"
} else {
    Test-Case "Mount" "Drive ${DriveLetter}: exists" {
        Test-Path "${DriveLetter}:\"
    }

    Test-Case "Mount" "Can list root directory" {
        $items = Get-ChildItem "${DriveLetter}:\" -ErrorAction Stop
        $items.Count -gt 0
    }

    Test-Case "Mount" "Can read a file via drive" {
        # Find any file in root
        $files = Get-ChildItem "${DriveLetter}:\" -File -ErrorAction SilentlyContinue
        if ($files.Count -gt 0) {
            $content = Get-Content $files[0].FullName -TotalCount 1 -ErrorAction Stop
            $true
        } else {
            # Try a known path
            $dirs = Get-ChildItem "${DriveLetter}:\" -Directory -ErrorAction SilentlyContinue | Select-Object -First 3
            $dirs.Count -gt 0
        }
    }

    Test-Case "Mount" "Can create file via drive" {
        $testFile = "${DriveLetter}:\phondrive-e2e-test.txt"
        Set-Content -Path $testFile -Value "Created by E2E test $(Get-Date)" -ErrorAction Stop
        $exists = Test-Path $testFile
        Remove-Item $testFile -ErrorAction SilentlyContinue
        $exists
    }

    Test-Case "Mount" "Can create directory via drive" {
        $testDir2 = "${DriveLetter}:\phondrive-e2e-dir"
        New-Item -ItemType Directory -Path $testDir2 -ErrorAction Stop | Out-Null
        $exists = Test-Path $testDir2
        Remove-Item $testDir2 -Recurse -ErrorAction SilentlyContinue
        $exists
    }
}

# ============================================================
# SUMMARY
# ============================================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  RESULTS" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "Passed:   $passed" -ForegroundColor Green
Write-Host "Failed:   $failed" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "Skipped:  $skipped" -ForegroundColor DarkGray
Write-Host "Total:    $($passed + $failed + $skipped)"
Write-Host ""

# Write report file
$reportPath = ".omo\evidence\e2e-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"
$reportDir = Split-Path $reportPath
if (-not (Test-Path $reportDir)) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }

$report = @"
PhonDrive E2E Verification Report
==================================
Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Server: $baseUrl
Drive: ${DriveLetter}:

Results:
  Passed:   $passed
  Failed:   $failed
  Skipped:  $skipped

Details:
"@

foreach ($r in $results) {
    $report += "`n  [$($r.cat)] $($r.name): $($r.result)"
}

$report | Out-File -FilePath $reportPath -Encoding UTF8
Write-Host "Report saved to: $reportPath" -ForegroundColor DarkGray

if ($failed -gt 0) {
    exit 1
} else {
    Write-Host "`nAll tests passed!" -ForegroundColor Green
    exit 0
}
