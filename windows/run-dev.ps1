# Builds the app, registers the package from the build folder (needs Windows' developer mode;
# nothing is signed), and starts it. A running copy is closed first.
#
#   powershell -ExecutionPolicy Bypass -File windows\run-dev.ps1 [-Platform ARM64|x64] [-NoBuild]
param(
    [ValidateSet('ARM64', 'x64')] [string] $Platform = $(if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'ARM64' } else { 'x64' }),
    [switch] $NoBuild
)
$ErrorActionPreference = 'Stop'
$app = Join-Path $PSScriptRoot 'App'

# A running copy holds the build's files open.
Get-Process PocketKeePass -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500

if (-not $NoBuild) {
    dotnet build $app -p:Platform=$Platform -v:q -nologo
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Directory.Build.props puts Windows build output on the local disk.
$out = Join-Path $env:LOCALAPPDATA "PocketKeePass\build\PocketKeePass\bin\$Platform\Debug\net10.0-windows10.0.26100.0"
$manifest = Join-Path $out 'AppxManifest.xml'
try {
    Add-AppxPackage -Register $manifest -ForceApplicationShutdown -ForceUpdateFromAnyVersion
} catch {
    # A change to the package itself (a new language, for one) can't be registered over the old
    # one: remove that first, keeping the app's data (settings, the remembered database).
    Get-AppxPackage -Name 'LovelandSolutions.PocketKeePass.Dev' | Remove-AppxPackage -PreserveApplicationData
    Add-AppxPackage -Register $manifest -ForceApplicationShutdown
}
$package = Get-AppxPackage -Name 'LovelandSolutions.PocketKeePass.Dev'
Start-Process "shell:AppsFolder\$($package.PackageFamilyName)!App"
