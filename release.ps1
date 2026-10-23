# Builds the signed Sorune APK and publishes it as a GitHub release; installed copies find it
# and offer the update themselves.
#   Usage:  ./release.ps1 -Version 0.2.0 -NotesFile notes.md
#   Needs:  ~/.sorune-android/signing.properties + the key it points to (same key for every
#           release, or phones refuse the update), the GitHub CLI signed in.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$NotesFile
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

if (-not (Test-Path "$env:USERPROFILE\.sorune-android\signing.properties")) {
    throw "İmza anahtarı bulunamadı (~/.sorune-android). Anahtarsız yayın telefonlarda güncellenemez."
}

# versionCode must grow with every release: 0.2.1 -> 201, 1.4.0 -> 10400.
$parts = $Version.Split(".") | ForEach-Object { [int]$_ }
$code = $parts[0] * 10000 + $parts[1] * 100 + $parts[2]
$gradleFile = "$root\app\build.gradle.kts"
$text = [IO.File]::ReadAllText($gradleFile)
$text = [Regex]::Replace($text, 'versionCode = \d+', "versionCode = $code")
$text = [Regex]::Replace($text, 'versionName = "[^"]*"', "versionName = `"$Version`"")
[IO.File]::WriteAllText($gradleFile, $text, [Text.UTF8Encoding]::new($false))

# Java on this PC needs a space-free folder for its internal sockets.
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Users\Public"
Push-Location $root
try {
    & .\gradlew.bat assembleRelease --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Derleme başarısız." }
}
finally {
    Pop-Location
}

New-Item -ItemType Directory -Force "$root\release" | Out-Null
$apk = "$root\release\Sorune.apk"
Copy-Item "$root\app\build\outputs\apk\release\app-release.apk" $apk -Force

$gh = "C:\Program Files\GitHub CLI\gh.exe"
& $gh release create "v$Version" $apk --repo Claxe-exe/sorune-android --title "Sorune Android $Version" --notes-file $NotesFile
Write-Host "Yayınlandı: v$Version"
