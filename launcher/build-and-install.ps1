# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-StockLauncher <package>|none]
#        The stock launcher is detected from the TV when -StockLauncher is not given.
#        [-InputKeyService]   remote's Input / YouTube / Apps / Settings buttons work (Google TV)
#        [-HomeKeyService]    remotes whose Home button sends KEYCODE_ALL_APPS (Xiaomi sticks)
#        [-PrivateDns <hostname>|off]  ad-blocking DNS for the whole TV, dns.adguard-dns.com by default
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [switch]$InputKeyService,
    [switch]$HomeKeyService,
    [string]$PrivateDns = $(if ($env:PRIVATE_DNS) { $env:PRIVATE_DNS } else { 'dns.adguard-dns.com' })
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

.\gradlew.bat assembleRelease -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$py = '..\tools\venv\Scripts\python.exe'
# Which app owns Home differs by build; ask the TV, but only accept a launcher we recognise (the answer
# can also be Android's chooser "android", the setup wizard, or another third-party launcher).
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher', 'com.gathin.tvhome')   # last: the original CleanYourTV launcher
if (-not $StockLauncher) {
    $homePkg = & $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or -not $homePkg) { throw 'could not ask the TV which launcher owns Home; check -TvHost and the debugging prompt, or pass -StockLauncher <package>' }
    $homePkg = "$homePkg".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) { $StockLauncher = $homePkg }
    elseif ($homePkg -eq 'com.majnioui.cleanxiaomitv') { $StockLauncher = 'none'; Write-Host 'Home already belongs to this launcher; nothing will be disabled.' }
    elseif ($homePkg -eq 'android') {
        # Android's chooser: launchers tie and no default is set (a restore followed by a re-applied list
        # ends here). Take the one known stock launcher among the HOME claimants, if there is exactly one.
        $claimants = (& $py -I ..\tools\tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) { $StockLauncher = $found[0]; Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher." }
        else { throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none." }
    }
    else { throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = launcher chooser, com.google.android.tungsten.setupwraith = setup wizard, see docs/TROUBLESHOOTING.md; anything else = another launcher is the default). Run again with -StockLauncher <package to disable> or -StockLauncher none." }
}
Write-Host "Stock launcher: $StockLauncher"
$disableStock = if ($StockLauncher -eq 'none') { 'echo stock launcher left alone' } else { "pm disable-user --user 0 $StockLauncher" }

$apk = 'app\build\outputs\apk\release\app-release.apk'
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
# Android 9 only accepts a component here; Android 10+ also takes a bare package name.
$out = & $py -I ..\tools\tv.py 'cmd package set-home-activity --user 0 com.majnioui.cleanxiaomitv/com.gathin.tvhome.MainActivity'
Write-Host $out
if ("$out" -match 'Error') { throw 'could not set the new launcher as home; the stock launcher was left untouched' }
@(
    'pm grant com.majnioui.cleanxiaomitv android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
    'pm grant com.majnioui.cleanxiaomitv android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; echo key-helper-repair-granted',
    'cmd notification allow_listener com.majnioui.cleanxiaomitv/com.gathin.tvhome.MediaListener; echo now-playing-allowed',
    'cmd package compile -m speed -f com.majnioui.cleanxiaomitv',
    "am start -W -n com.majnioui.cleanxiaomitv/com.gathin.tvhome.MainActivity | grep -E '^(Status|Error)'",
    $disableStock,
    'cmd package set-home-activity --user 0 com.majnioui.cleanxiaomitv/com.gathin.tvhome.MainActivity'
) | & $py -I ..\tools\tv.py --batch
# Order matters, see build-and-install.sh: start the new launcher, disable the stock one, set the
# preference last, then check where Home ended up and put the stock launcher back if it is not ours.
$homeNow = (& $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | Select-Object -Last 1).Trim()
if ($homeNow -notlike 'com.majnioui.cleanxiaomitv/*') {
    if ($StockLauncher -ne 'none') { & $py -I ..\tools\tv.py "pm enable --user 0 $StockLauncher" | Out-Null }
    throw "Home resolves to '$homeNow', not to the new launcher; $StockLauncher was re-enabled. On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to your debloat list (see docs/TROUBLESHOOTING.md), apply it, then run this script again."
}
Write-Host "Home now resolves to $homeNow"
# Ad-blocking DNS, see build-and-install.sh. tools\restore.ps1 switches it back.
if ($PrivateDns -ne 'off') {
    & $py -I ..\tools\tv.py "settings put global private_dns_specifier $PrivateDns; settings put global private_dns_mode hostname; echo Private DNS: $PrivateDns"
}
# Optional accessibility services, appended to enabled_accessibility_services without dropping others.
function Enable-Service([string]$Name) {
    $svc = "com.majnioui.cleanxiaomitv/com.gathin.tvhome.$Name"
    & $py -I ..\tools\tv.py ('s=$(settings get secure enabled_accessibility_services); case "$s" in *' + $Name + '*) ;; null|"") settings put secure enabled_accessibility_services ' + $svc + ' ;; *) settings put secure enabled_accessibility_services "$s:' + $svc + '" ;; esac; settings put secure accessibility_enabled 1; echo ' + $Name + ' enabled')
}
if ($InputKeyService) { Enable-Service 'InputKeyService' }
if ($HomeKeyService) { Enable-Service 'HomeKeyService' }
Write-Host 'Installed. Press Home on the remote to see it.'
