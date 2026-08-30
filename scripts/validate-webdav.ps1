# PhonDrive WebDAV Server - E2E Test Script
# Run this script after installing the APK and starting the server
# Usage: .\validate-webdav.ps1 -IP "100.84.246.7" -Port 8080

param(
    [Parameter(Mandatory=$true)]
    [string]$IP,
    
    [Parameter(Mandatory=$false)]
    [int]$Port = 8080,
    
    [Parameter(Mandatory=$false)]
    [string]$User = "user",
    
    [Parameter(Mandatory=$false)]
    [string]$Pass = "pass"
)

$baseUrl = "http://${IP}:${Port}"
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${User}:${Pass}"))
$headers = @{ Authorization = "Basic $auth" }
$testDir = "phondrive-test-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$passed = 0
$failed = 0

Write-Host "=== PhonDrive WebDAV E2E Test ===" -ForegroundColor Cyan
Write-Host "Server: $baseUrl"
Write-Host "Test directory: /$testDir"
Write-Host ""

function Test-Step {
    param([string]$Name, [scriptblock]$Test)
    Write-Host "TEST: $Name" -ForegroundColor Yellow
    try {
        $result = & $Test
        if ($result) {
            Write-Host "  PASS" -ForegroundColor Green
            $script:passed++
        } else {
            Write-Host "  FAIL" -ForegroundColor Red
            $script:failed++
        }
    } catch {
        Write-Host "  FAIL: $_" -ForegroundColor Red
        $script:failed++
    }
    Write-Host ""
}

# 1. Test ping endpoint
Test-Step "GET /ping" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/ping" -Headers $headers -UseBasicParsing
    $resp.StatusCode -eq 200 -and $resp.Content -eq "pong"
}

# 2. Test PROPFIND on root
Test-Step "PROPFIND / (list root)" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/" -Method PROPFIND -Headers $headers -UseBasicParsing -ContentType "application/xml" -Body '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'
    $resp.StatusCode -eq 207
}

# 3. Test MKCOL (create directory)
Test-Step "MKCOL /$testDir" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir" -Method MKCOL -Headers $headers -UseBasicParsing
    $resp.StatusCode -eq 201
}

# 4. Test PUT (upload file)
Test-Step "PUT /$testDir/test.txt" {
    $body = "Hello from PhonDrive E2E test at $(Get-Date)"
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Method PUT -Headers $headers -UseBasicParsing -Body $body
    $resp.StatusCode -eq 201
}

# 5. Test GET (download file)
Test-Step "GET /$testDir/test.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Headers $headers -UseBasicParsing
    $resp.StatusCode -eq 200 -and $resp.Content -like "*Hello from PhonDrive*"
}

# 6. Test HEAD
Test-Step "HEAD /$testDir/test.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Method HEAD -Headers $headers -UseBasicParsing
    $resp.StatusCode -eq 200
}

# 7. Test PROPFIND on subdirectory
Test-Step "PROPFIND /$testDir" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir" -Method PROPFIND -Headers $headers -UseBasicParsing -ContentType "application/xml" -Body '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'
    $resp.StatusCode -eq 207 -and $resp.Content -like "*test.txt*"
}

# 8. Test MOVE (rename)
Test-Step "MOVE /$testDir/test.txt -> /$testDir/renamed.txt" {
    $dest = "$baseUrl/$testDir/renamed.txt"
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/test.txt" -Method MOVE -Headers @{ Authorization = "Basic $auth"; Destination = $dest } -UseBasicParsing
    $resp.StatusCode -eq 204
}

# 9. Test GET renamed file
Test-Step "GET /$testDir/renamed.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/renamed.txt" -Headers $headers -UseBasicParsing
    $resp.StatusCode -eq 200 -and $resp.Content -like "*Hello from PhonDrive*"
}

# 10. Test COPY
Test-Step "COPY /$testDir/renamed.txt -> /$testDir/copied.txt" {
    $dest = "$baseUrl/$testDir/copied.txt"
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/renamed.txt" -Method COPY -Headers @{ Authorization = "Basic $auth"; Destination = $dest } -UseBasicParsing
    $resp.StatusCode -eq 204
}

# 11. Test DELETE file
Test-Step "DELETE /$testDir/copied.txt" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir/copied.txt" -Method DELETE -Headers $headers -UseBasicParsing
    $resp.StatusCode -eq 204
}

# 12. Test DELETE directory
Test-Step "DELETE /$testDir" {
    $resp = Invoke-WebRequest -Uri "$baseUrl/$testDir" -Method DELETE -Headers $headers -UseBasicParsing
    $resp.StatusCode -eq 204
}

# 13. Test auth required (no auth)
Test-Step "Auth required (no auth -> 401)" {
    try {
        Invoke-WebRequest -Uri "$baseUrl/" -UseBasicParsing -ErrorAction Stop
        $false
    } catch {
        $_.Exception.Response.StatusCode.value__ -eq 401
    }
}

# Summary
Write-Host "=== Results ===" -ForegroundColor Cyan
Write-Host "Passed: $passed" -ForegroundColor Green
Write-Host "Failed: $failed" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "Total: $($passed + $failed)"

if ($failed -gt 0) {
    exit 1
} else {
    Write-Host "All tests passed!" -ForegroundColor Green
    exit 0
}
