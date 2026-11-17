# Gathers exactly the files to publish on GitHub into dist\github-upload (and nothing private).
#   powershell -ExecutionPolicy Bypass -File prepare-github.ps1
# Drag the CONTENTS of that folder onto your GitHub repository's "Add file > Upload files" page.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$out = Join-Path $root 'dist\github-upload'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }

$publish = @(
  '.gitignore', 'LICENSE', 'README.md', 'THIRD-PARTY-NOTICES.txt', 'platformio.ini',
  'src\main.cpp',
  'docs\*.md', 'docs\manual\images\*.png', 'docs\tools\*.ps1', 'docs\video\script.md', 'docs\video\FermFrame-guide.srt',
  'manager\FermFrameApp.cs', 'manager\build.ps1', 'manager\prepare-github.ps1', 'manager\shops.json', 'manager\README.md',
  'manager\project-url.txt', 'manager\shops-url.txt',
  'installer\FermFrame Installer.bat', 'installer\Read Me.txt',
  'installer\files\FermFrame-Installer.ps1', 'installer\files\fermframe.bin', 'installer\files\fermframe-lite.bin',
  # Android app source (never keystore.properties, local.properties or build output)
  'android\build.gradle.kts', 'android\settings.gradle.kts', 'android\gradle.properties', 'android\gradlew', 'android\gradlew.bat',
  'android\gradle\wrapper\*', 'android\app\build.gradle.kts'
)
$recurse = @('android\app\src')   # whole folders
$n = 0
$files = @(foreach ($pattern in $publish) { Get-ChildItem (Join-Path $root $pattern) -File -Force -ErrorAction SilentlyContinue })
$files += foreach ($d in $recurse) { Get-ChildItem (Join-Path $root $d) -File -Recurse -Force }
foreach ($f in $files) {
  $rel = $f.FullName.Substring($root.Length + 1)
  $dest = Join-Path $out $rel
  New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
  Copy-Item $f.FullName $dest
  $n++
}

# Last check: nothing personal or private in the text files. Your own words to look for (Wi-Fi name, account
# IDs, ...) go one per line in manager\private-words.txt, which is never uploaded.
$words = @('backup-rapthooker', '\\Users\\[^\\<>"]+\\', 'sk_[0-9a-f]{20,}')
$priv = Join-Path $PSScriptRoot 'private-words.txt'
if (Test-Path $priv) { $words += Get-Content $priv | Where-Object { $_.Trim() } | ForEach-Object { [regex]::Escape($_.Trim()) } }
$bad = Get-ChildItem $out -Recurse -File | Where-Object { $_.Extension -notin '.bin', '.png' } |
  Select-String -Pattern ($words -join '|') -List
if ($bad) { $bad | ForEach-Object { Write-Warning "Check before uploading: $($_.Path):$($_.LineNumber)" } }

Write-Host "$n files ready in $out"
