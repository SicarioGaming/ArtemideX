# Gives the Release workflow the signing key, as encrypted GitHub secrets. Run it once (and again
# only if the key changes), after signing in with `gh auth login`:
#
#   .\tools\set-release-secrets.ps1
#
# It reads keystore.properties in the project folder and never prints the passwords.
$ErrorActionPreference = 'Stop'
$gh = 'C:\Program Files\GitHub CLI\gh.exe'
$repo = 'SicarioGaming/Voidlink'

$props = @{}
foreach ($line in Get-Content (Join-Path $PSScriptRoot '..\keystore.properties')) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] }
}
$store = $props['storeFile']
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($store))

$base64 | & $gh secret set VOIDLINK_KEYSTORE_BASE64 -R $repo
$props['storePassword'] | & $gh secret set VOIDLINK_KEYSTORE_PASSWORD -R $repo
$props['keyAlias'] | & $gh secret set VOIDLINK_KEY_ALIAS -R $repo
$props['keyPassword'] | & $gh secret set VOIDLINK_KEY_PASSWORD -R $repo

Write-Host 'Release secrets set:'
& $gh secret list -R $repo
