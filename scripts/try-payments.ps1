param(
    [string]$Api = "http://localhost:8081",
    [string]$AdminEmail = "admin@paycore.demo",
    [string]$StaffPassword = "admin_user_test"
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

function CallExpectingError([string]$Method, [string]$Path, [string]$Token, $Body) {
    try {
        Call $Method $Path $Token $Body | Out-Null
        return "UNEXPECTED SUCCESS"
    } catch {
        $response = $_.Exception.Response
        $status = [int]$response.StatusCode
        $body = $_.ErrorDetails.Message
        if (-not $body) {
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

function Key([string]$Prefix) { return "$Prefix-$(Get-Random -Minimum 10000000 -Maximum 99999999)" }

function SignIn([string]$Email, [string]$Password) {
    return (Call POST "/api/v1/auth/login" $null @{ email = $Email; password = $Password }).accessToken
}

$reason = @{ reason = "Local test" }

Step "Customer John Doe registers, passes KYC and gets an ACTIVE account"
$suffix = Get-Random -Minimum 10000000 -Maximum 99999999
$email = "pay$suffix@example.com"
Call POST "/api/v1/customers" $null @{
    firstName = "John"; lastName = "Doe"; email = $email
    countryCode = "NG"; phoneNumber = "080$suffix"; password = "Password123"
} | Out-Null
$customer = SignIn $email "Password123"
$account = Call POST "/api/v1/accounts" $customer @{ type = "PERSONAL"; currency = "NGN" }

Call POST "/api/v1/kyc/start" $customer $null | Out-Null
Call POST "/api/v1/kyc/bvn" $customer @{
    bvn = "22222222222"; firstName = "John"; lastName = "Doe"; dateOfBirth = "1990-01-01"
} | Out-Null
$pdf = Join-Path $env:TEMP "paycore-sample.pdf"
Set-Content -Path $pdf -Value "%PDF-1.4`n%%EOF" -NoNewline -Encoding ascii
& curl.exe -s -f -o NUL -X POST "$Api/api/v1/kyc/documents" `
    -H "Authorization: Bearer $customer" `
    -F "documentType=NATIONAL_ID" -F "file=@$pdf;type=application/pdf"
if ($LASTEXITCODE -ne 0) { throw "Document upload failed" }
$kyc = Call POST "/api/v1/kyc/submit" $customer $null

$admin = SignIn $AdminEmail $StaffPassword
Call POST "/api/v1/kyc/$($kyc.id)/start-review" $admin $null | Out-Null
Call POST "/api/v1/kyc/$($kyc.id)/approve" $admin $null | Out-Null
Call POST "/api/v1/admin/accounts/$($account.id)/activate" $admin $reason | Out-Null
Write-Host "account $($account.accountNumber) is ACTIVE"

$maker = SignIn "ops.officer@paycore.demo" $StaffPassword
$checker = SignIn "ops.supervisor@paycore.demo" $StaffPassword

Step "An ordinary top-up of N5,000"
$topUp = Call POST "/api/v1/ledger/accounts/$($account.id)/fundings" $customer @{
    amount = 5000; currency = "NGN"; idempotencyKey = (Key "topup")
}
Write-Host "credited $($topUp.amount) ($($topUp.reference))"

Step "Top-up of N300.98: the processor charges the card but its answer is lost"
$pending = Call POST "/api/v1/ledger/accounts/$($account.id)/fundings" $customer @{
    amount = 300.98; currency = "NGN"; idempotencyKey = (Key "topup")
}
Write-Host "payment $($pending.reference) is $($pending.status)  (expect UNKNOWN: 202, not 'you were not charged')"
$checked = Call POST "/api/v1/admin/payments/$($pending.id)/status-check" $maker $null
Write-Host "after asking the processor: $($checked.status)  (expect SUCCEEDED; the background job does this every 30 s)"

$transfer = @{ bankCode = "999001"; accountNumber = "4123456789"; currency = "NGN" }

Step "Transfer of N100.96 to Test Bank: paid, but the rail can never confirm it"
$t = Call POST "/api/v1/ledger/accounts/$($account.id)/bank-transfers" $customer ($transfer + @{
    amount = 100.96; idempotencyKey = (Key "out")
})
Write-Host "transfer $($t.paymentReference): $($t.transferStatus)  (expect PENDING; the money stays reserved)"
$escalated = Call POST "/api/v1/admin/payments/$($t.paymentId)/escalate" $maker $null
Write-Host "officer escalates it: $($escalated.status)  (expect NEEDS_REVIEW)"
$request = Call POST "/api/v1/ops/requests/payment-resolutions" $maker @{
    paymentId = $t.paymentId; resolution = "COMPLETE"; reason = "Rail support ticket 4411: session paid"
}
Write-Host "maker requests COMPLETE: request $($request.status)"
Write-Host (CallExpectingError POST "/api/v1/ops/requests/$($request.id)/approve" $maker @{ note = "self" }) "(expect 403 FOUR_EYES_REQUIRED)"
$approved = Call POST "/api/v1/ops/requests/$($request.id)/approve" $checker @{ note = "Ticket checked" }
$payment = Call GET "/api/v1/payments/$($t.paymentId)" $customer $null
Write-Host "checker approves: request $($approved.status), payment $($payment.status)  (expect APPROVED, SUCCEEDED)"

Step "Top-up of N250.96: charged, never confirmable; refunded to the card"
$p = Call POST "/api/v1/ledger/accounts/$($account.id)/fundings" $customer @{
    amount = 250.96; currency = "NGN"; idempotencyKey = (Key "topup")
}
Call POST "/api/v1/admin/payments/$($p.id)/escalate" $maker $null | Out-Null
$request = Call POST "/api/v1/ops/requests/payment-resolutions" $maker @{
    paymentId = $p.id; resolution = "REFUND"; reason = "Customer asked for the money back on the card"
}
Call POST "/api/v1/ops/requests/$($request.id)/approve" $checker @{ note = "OK" } | Out-Null
$payment = Call GET "/api/v1/payments/$($p.id)" $customer $null
Write-Host "payment $($payment.status), refund $($payment.refundReference)  (expect FAILED, SIMREF-...)"

Step "Transfer of N100.98: the rail paid it, so staff cannot reverse it"
$t = Call POST "/api/v1/ledger/accounts/$($account.id)/bank-transfers" $customer ($transfer + @{
    amount = 100.98; idempotencyKey = (Key "out")
})
Call POST "/api/v1/admin/payments/$($t.paymentId)/escalate" $maker $null | Out-Null
Write-Host (CallExpectingError POST "/api/v1/ops/requests/payment-resolutions" $maker @{
    paymentId = $t.paymentId; resolution = "REVERSE"; reason = "Customer says it never arrived"
}) "(expect 409 RESOLUTION_CONTRADICTS_PROVIDER)"
$checked = Call POST "/api/v1/admin/payments/$($t.paymentId)/status-check" $maker $null
Write-Host "a status check applies the rail's answer: $($checked.status)  (expect SUCCEEDED)"

Step "Where the customer's money stands"
$balance = Call GET "/api/v1/ledger/accounts/$($account.id)/balance" $customer $null
Write-Host "balance $($balance.balance)  (expect 5099.04 = 5000 + 300.98 - 100.96 - 100.98)"
Write-Host ""
Write-Host "Sign in to the UI as ops.officer@paycore.demo and open Payments to see these, with their history."
