<#
Walks through the account flow against a locally running PayCore API:

  customer registers -> opens account (PENDING)
  admin tries to activate -> refused, KYC not verified
  customer completes KYC -> admin approves it
  admin activates (ACTIVE) -> freezes -> unfreezes
  customer lists accounts; admin reads the audit history
  plus: duplicate account (409) and another customer's account (404)

Start the API first, in demo mode with simulated KYC (see README), then:

  powershell -ExecutionPolicy Bypass -File scripts\try-accounts.ps1
  powershell -ExecutionPolicy Bypass -File scripts\try-accounts.ps1 -Api http://localhost:8080
#>
param(
    [string]$Api = "http://localhost:8081",
    [string]$AdminEmail = "admin@paycore.demo",
    [string]$AdminPassword = "admin_user_test"
)

$ErrorActionPreference = "Stop"

function Call([string]$Method, [string]$Path, [string]$Token, $Body) {
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = "$Api$Path"; Headers = $headers }
    if ($null -ne $Body) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($Body | ConvertTo-Json)
    }
    Invoke-RestMethod @params
}

# Calls that are supposed to fail: returns "<status> <error code>".
function CallExpectingError([string]$Method, [string]$Path, [string]$Token, $Body) {
    try {
        Call $Method $Path $Token $Body | Out-Null
        return "UNEXPECTED SUCCESS"
    } catch {
        $response = $_.Exception.Response
        $status = [int]$response.StatusCode
        $body = $_.ErrorDetails.Message
        if (-not $body) {
            # Windows PowerShell 5.1 leaves ErrorDetails empty; read the body.
            try {
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                $body = $reader.ReadToEnd()
            } catch {}
        }
        $code = ""
        if ($body) {
            try { $code = ($body | ConvertFrom-Json).error } catch {}
        }
        return "$status $code".Trim()
    }
}

function Step([string]$Text) { Write-Host ""; Write-Host "== $Text" -ForegroundColor Cyan }

function Register([string]$FirstName, [string]$LastName) {
    $suffix = Get-Random -Minimum 10000000 -Maximum 99999999
    $email = "try$suffix@example.com"
    Call POST "/api/v1/customers" $null @{
        firstName = $FirstName; lastName = $LastName; email = $email
        countryCode = "NG"; phoneNumber = "080$suffix"; password = "Password123"
    } | Out-Null
    $login = Call POST "/api/v1/auth/login" $null @{ email = $email; password = "Password123" }
    return @{ Email = $email; Id = $login.customerId; Token = $login.accessToken }
}

$reason = @{ reason = "Local test" }

Step "Customer registers and signs in"
# The simulated KYC provider knows one person: John Doe, born 1990-01-01.
$customer = Register "John" "Doe"
Write-Host "customer $($customer.Email)  id $($customer.Id)"

Step "Customer opens a personal NGN account"
$account = Call POST "/api/v1/accounts" $customer.Token @{ type = "PERSONAL"; currency = "NGN" }
Write-Host "account $($account.accountNumber)  status $($account.status)  (expect PENDING)"

Step "Opening a second NGN account"
Write-Host (CallExpectingError POST "/api/v1/accounts" $customer.Token @{ type = "PERSONAL"; currency = "NGN" }) "(expect 409 ACCOUNT_ALREADY_EXISTS)"

Step "Admin signs in and tries to activate before KYC is verified"
$admin = Call POST "/api/v1/auth/login" $null @{ email = $AdminEmail; password = $AdminPassword }
Write-Host (CallExpectingError POST "/api/v1/admin/accounts/$($account.id)/activate" $admin.accessToken $reason) "(expect 403 KYC_VERIFICATION_REQUIRED)"

Step "Customer completes KYC: BVN check, document upload, submit"
Call POST "/api/v1/kyc/start" $customer.Token $null | Out-Null
$bvn = Call POST "/api/v1/kyc/bvn" $customer.Token @{
    bvn = "22222222222"; firstName = "John"; lastName = "Doe"; dateOfBirth = "1990-01-01"
}
Write-Host "BVN check: $($bvn.result)"

$pdf = Join-Path $env:TEMP "paycore-sample.pdf"
Set-Content -Path $pdf -Value "%PDF-1.4`n%%EOF" -NoNewline -Encoding ascii
& curl.exe -s -f -o NUL -X POST "$Api/api/v1/kyc/documents" `
    -H "Authorization: Bearer $($customer.Token)" `
    -F "documentType=NATIONAL_ID" -F "file=@$pdf;type=application/pdf"
if ($LASTEXITCODE -ne 0) { throw "Document upload failed" }
Write-Host "document uploaded"

$kyc = Call POST "/api/v1/kyc/submit" $customer.Token $null
Write-Host "KYC status: $($kyc.status)"

Step "Admin reviews and approves the KYC"
Call POST "/api/v1/kyc/$($kyc.id)/start-review" $admin.accessToken $null | Out-Null
$kyc = Call POST "/api/v1/kyc/$($kyc.id)/approve" $admin.accessToken $null
Write-Host "KYC status: $($kyc.status)  (expect VERIFIED)"

Step "Admin activates, freezes and unfreezes the account"
Write-Host "activate -> $((Call POST "/api/v1/admin/accounts/$($account.id)/activate" $admin.accessToken $reason).status)"
Write-Host "freeze   -> $((Call POST "/api/v1/admin/accounts/$($account.id)/freeze" $admin.accessToken $reason).status)"
Write-Host "unfreeze -> $((Call POST "/api/v1/admin/accounts/$($account.id)/unfreeze" $admin.accessToken $reason).status)"

Step "Customer lists their accounts"
Call GET "/api/v1/accounts" $customer.Token $null |
    Format-Table accountNumber, type, currency, status -AutoSize | Out-String | Write-Host

Step "Another customer tries to read this account"
$other = Register "Jane" "Roe"
Write-Host (CallExpectingError GET "/api/v1/accounts/$($account.id)" $other.Token $null) "(expect 404 ACCOUNT_NOT_FOUND)"

Step "Admin reads the account's audit history"
Call GET "/api/v1/admin/accounts/$($account.id)/history" $admin.accessToken $null |
    Format-Table eventType, fromStatus, toStatus, reason, occurredAt -AutoSize | Out-String | Write-Host
