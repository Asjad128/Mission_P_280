# Update-Driver.ps1
# Installs a freshly built IddSampleDriver package over the existing device ROOT\DISPLAY\0001.
# Run in an ELEVATED PowerShell:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\Update-Driver.ps1 -Package "F:\Docs-Files\IddSampleDriver-Debug-x64"
#
# Each CI build generates a NEW test certificate, so this script first trusts the signer of the
# new DLL (LocalMachine Root + TrustedPublisher), then installs the package, then restarts the device.
# It stops at the first real failure and prints the reason. It never creates a new device.

param(
    [string]$Package = 'F:\Docs-Files\IddSampleDriver-Debug-x64',
    [string]$InstanceId = 'ROOT\DISPLAY\0001'
)

$ErrorActionPreference = 'Stop'

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Not elevated. Open PowerShell with Run as administrator.'
}

# 1. Locate the package folder: the folder that holds the .inf, .dll and .cat together.
$inf = Get-ChildItem -Path $Package -Recurse -Filter 'IddSampleDriver.inf' -ErrorAction Stop |
    Where-Object { (Test-Path (Join-Path $_.DirectoryName 'IddSampleDriver.dll')) -and (Get-ChildItem $_.DirectoryName -Filter '*.cat') } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $inf) { throw "No folder with IddSampleDriver.inf + .dll + .cat found under $Package" }
$dir = $inf.DirectoryName
$dll = Join-Path $dir 'IddSampleDriver.dll'
"Package folder : $dir"

# 2. Check the device exists (this script never creates one).
$dev = Get-PnpDevice -InstanceId $InstanceId -ErrorAction SilentlyContinue
if (-not $dev) { throw "Device $InstanceId not found. Do not create another device; tell me this output." }
"Device         : $InstanceId (current status: $($dev.Status))"

# 3. Trust the signer of the new DLL.
$sig = Get-AuthenticodeSignature -FilePath $dll
if (-not $sig.SignerCertificate) { throw "DLL is not signed: $($sig.StatusMessage)" }
$thumb = $sig.SignerCertificate.Thumbprint
"Signer         : $($sig.SignerCertificate.Subject)"
"Thumbprint     : $thumb"
$cer = Join-Path $env:TEMP "IddSampleDriver-$thumb.cer"
Export-Certificate -Cert $sig.SignerCertificate -FilePath $cer -Force | Out-Null
foreach ($store in 'Root', 'TrustedPublisher') {
    $has = Get-ChildItem "Cert:\LocalMachine\$store" | Where-Object Thumbprint -eq $thumb
    if ($has) { "Cert $store : already trusted" }
    else { Import-Certificate -FilePath $cer -CertStoreLocation "Cert:\LocalMachine\$store" -ErrorAction Stop | Out-Null; "Cert $store : imported" }
}

# 4. Report test-signing state (informational).
$ts = [bool](bcdedit /enum '{current}' | findstr /i 'testsigning' | findstr /i 'Yes')
"Test-signing   : $ts"

# 5. Install the package onto the existing device.
""
"--- pnputil /add-driver /install ---"
$out = pnputil /add-driver $inf.FullName /install 2>&1
$rc = $LASTEXITCODE
$out
"pnputil exit code: $rc"
if ($rc -ne 0 -and $rc -ne 3010) {
    "FAILED. If the message mentions a signature or trust problem, enable test-signing (bcdedit /set testsigning on), restart, and run this script again."
    return
}

# 6. Restart the device so the new DLL is loaded.
""
"--- restarting $InstanceId ---"
pnputil /restart-device $InstanceId

# 7. Result.
Start-Sleep -Seconds 3
""
"--- Device ---"
pnputil /enum-devices /instanceid $InstanceId /drivers
(Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName 'DEVPKEY_Device_ProblemCode' -ErrorAction SilentlyContinue | Select-Object KeyName, Data | Format-Table -AutoSize | Out-String)
"--- Screens ---"
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Screen]::AllScreens | ForEach-Object { $_.DeviceName + '  Bounds=' + $_.Bounds + '  Primary=' + $_.Primary }
"--- Monitors ---"
Get-PnpDevice -Class Monitor -Status OK -ErrorAction SilentlyContinue | Select-Object FriendlyName, InstanceId | Format-Table -AutoSize | Out-String -Width 200