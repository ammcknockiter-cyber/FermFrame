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
  'docs\*.md',
  'manager\FermFrameApp.cs', 'manager\build.ps1', 'manager\prepare-github.ps1', 'manager\shops.json', 'manager\README.md',
  'manager\project-url.txt', 'manager\shops-url.txt',
  'installer\FermFrame Installer.bat', 'installer\Read Me.txt',
  'installer\files\FermFrame-Installer.ps1', 'installer\files\fermframe.bin', 'installer\files\fermframe-lite.bin'
)
$n = 0
foreach ($pattern in $publish) {
  foreach ($f in Get-ChildItem (Join-Path $root $pattern) -File -Force -ErrorAction SilentlyContinue) {
    $rel = $f.FullName.Substring($root.Length + 1)
    $dest = Join-Path $out $rel
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $f.FullName $dest
    $n++
  }
}

# Last check: nothing personal or private in the text files
$bad = Get-ChildItem $out -Recurse -File | Where-Object { $_.Extension -notin '.bin' -and $_.Name -ne 'prepare-github.ps1' } |
  Select-String -Pattern 'backup-rapthooker|PLUSNET|\\Users\\[^\\<>"]+\\' -List
if ($bad) { $bad | ForEach-Object { Write-Warning "Check before uploading: $($_.Path):$($_.LineNumber)" } }

Write-Host "$n files ready in $out"
