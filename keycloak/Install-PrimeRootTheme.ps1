[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [string] $KeycloakHome
)

$ErrorActionPreference = 'Stop'
$homePath = (Resolve-Path -LiteralPath $KeycloakHome).Path
$themeSource = Join-Path $PSScriptRoot 'themes\prime-root'
$assetSource = Join-Path $PSScriptRoot '..\src\main\resources\static\assets'
$themeDestination = Join-Path $homePath 'themes\prime-root'
$archive = Join-Path $homePath 'lib\lib\main\org.keycloak.keycloak-themes-26.8.1.jar'
if (-not (Test-Path -LiteralPath (Join-Path $homePath 'themes') -PathType Container)) {
    throw "Not a Keycloak distribution: $homePath"
}
foreach ($asset in @('prime-root-mark.svg', 'produce-distribution.svg')) {
    if (-not (Test-Path -LiteralPath (Join-Path $assetSource $asset) -PathType Leaf)) {
        throw "Missing theme asset: $asset"
    }
}
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    Write-Warning 'This theme is validated against Keycloak 26.8.1. Verify native login, errors, recovery and MFA after a Keycloak upgrade.'
}
if ($PSCmdlet.ShouldProcess($themeDestination, 'Install the Prime Root login theme')) {
    New-Item -ItemType Directory -Path $themeDestination -Force | Out-Null
    Copy-Item -Path (Join-Path $themeSource '*') -Destination $themeDestination -Recurse -Force
    $images = Join-Path $themeDestination 'login\resources\img'
    New-Item -ItemType Directory -Path $images -Force | Out-Null
    foreach ($asset in @('prime-root-mark.svg', 'produce-distribution.svg')) {
        Copy-Item -LiteralPath (Join-Path $assetSource $asset) -Destination (Join-Path $images $asset) -Force
    }
    Write-Output "Installed: $themeDestination"
    Write-Output 'Activate prime-root under Realm settings > Themes > Login theme, then save.'
    Write-Output 'If this client has a Login theme override, select prime-root there too.'
}
