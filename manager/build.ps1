# Builds FermFrame.exe (the Windows app) with the C# compiler that comes with Windows.
# The firmware files from ..\installer\files are built into the .exe.
#   powershell -ExecutionPolicy Bypass -File build.ps1            (test build: FermFrame.exe only)
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Release   (also dist\FermFrame-<version>.exe + checksum)
param([switch]$Release)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$obj  = Join-Path $here 'obj'
New-Item -ItemType Directory -Force $obj | Out-Null

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }

$full = Join-Path $root 'installer\files\fermframe.bin'
$lite = Join-Path $root 'installer\files\fermframe-lite.bin'
foreach ($f in $full, $lite) { if (-not (Test-Path $f)) { throw "Missing $f - build the firmware first." } }

# Firmware version from platformio.ini
$ini = Get-Content (Join-Path $root 'platformio.ini') -Raw
$ver = [regex]::Match($ini, 'FERMFRAME_VERSION=\\"([^\\"]+)\\"').Groups[1].Value
if (-not $ver) { throw 'Could not find FERMFRAME_VERSION in platformio.ini' }
# Shop list: shops.json is built in; shops-url.txt (optional, one https:// line) is where the app
# looks for a newer copy, so the shop links can change without a new app.
$shops = Join-Path $here 'shops.json'
$null = Get-Content $shops -Raw | ConvertFrom-Json   # stop here if it isn't valid JSON
$shopsUrl = ''
$urlFile = Join-Path $here 'shops-url.txt'
if (Test-Path $urlFile) { $shopsUrl = (Get-Content $urlFile -Raw).Trim() }
if ($shopsUrl -and $shopsUrl -notmatch '^https://') { throw 'shops-url.txt must hold one https:// address' }
# Project page (optional, one https:// line): shown in About and in Windows' Apps list
$projectUrl = ''
$projFile = Join-Path $here 'project-url.txt'
if (Test-Path $projFile) { $projectUrl = (Get-Content $projFile -Raw).Trim() }
if ($projectUrl -and $projectUrl -notmatch '^https://') { throw 'project-url.txt must hold one https:// address' }
Set-Content -Encoding utf8 (Join-Path $obj 'Version.cs') "namespace FermFrame { static class Bundled { public const string Version = `"$ver`"; public const string ShopsUrl = `"$shopsUrl`"; public const string ProjectUrl = `"$projectUrl`"; } }"

# App version, from AppInfo.Version in the source
$appVer = [regex]::Match((Get-Content (Join-Path $here 'FermFrameApp.cs') -Raw), 'public const string Version = "([^"]+)"').Groups[1].Value
if (-not $appVer) { throw 'Could not find AppInfo.Version in FermFrameApp.cs' }
$license = Join-Path $root 'LICENSE'
$notices = Join-Path $root 'THIRD-PARTY-NOTICES.txt'
foreach ($f in $license, $notices) { if (-not (Test-Path $f)) { throw "Missing $f" } }

# Icon: an amber rounded square with a falling gravity line (PNG-in-ICO)
$ico = Join-Path $obj 'fermframe.ico'
if (-not (Test-Path $ico)) {
  Add-Type -AssemblyName System.Drawing
  $pngs = @()
  foreach ($s in 256, 48, 32, 16) {
    $bmp = New-Object System.Drawing.Bitmap $s, $s
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $r = [single]($s * 0.2); $m = [single]($s * 0.04); $w = [single]($s - 2 * $m)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($m, $m, 2 * $r, 2 * $r, 180, 90); $path.AddArc($m + $w - 2 * $r, $m, 2 * $r, 2 * $r, 270, 90)
    $path.AddArc($m + $w - 2 * $r, $m + $w - 2 * $r, 2 * $r, 2 * $r, 0, 90); $path.AddArc($m, $m + $w - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $path.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(24, 24, 24))), $path)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 204, 51)), ([single][Math]::Max(1.5, $s * 0.09))
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'; $pen.LineJoin = 'Round'
    $pts = @(); foreach ($i in 0..20) { $x = 0.2 + 0.6 * $i / 20; $y = 0.28 + 0.44 / (1 + [Math]::Exp(-($i - 9) / 2.6)); $pts += New-Object System.Drawing.PointF ([single]($x * $s)), ([single]($y * $s)) }
    $g.DrawLines($pen, $pts)
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream; $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    $pngs += , @($s, $ms.ToArray())
  }
  $out = New-Object System.IO.MemoryStream; $bw = New-Object System.IO.BinaryWriter $out
  $bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$pngs.Count)
  $offset = 6 + 16 * $pngs.Count
  foreach ($p in $pngs) {
    $d = if ($p[0] -ge 256) { 0 } else { $p[0] }
    $bw.Write([byte]$d); $bw.Write([byte]$d); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32); $bw.Write([uint32]$p[1].Length); $bw.Write([uint32]$offset)
    $offset += $p[1].Length
  }
  foreach ($p in $pngs) { $bw.Write([byte[]]$p[1]) }
  [IO.File]::WriteAllBytes($ico, $out.ToArray())
}

$exe = Join-Path $root 'FermFrame.exe'
& $csc /nologo /nowarn:162 /target:winexe /optimize+ /codepage:65001 "/out:$exe" "/win32icon:$ico" `
  "/resource:$full,fermframe.bin" "/resource:$lite,fermframe-lite.bin" "/resource:$shops,shops.json" "/resource:$license,LICENSE.txt" "/resource:$notices,THIRD-PARTY-NOTICES.txt" `
  /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll /r:System.Net.Http.dll /r:System.Web.Extensions.dll /r:System.Security.dll `
  (Join-Path $here 'FermFrameApp.cs') (Join-Path $obj 'Version.cs')
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Write-Host "Built $exe (app $appVer, FermFrame $ver firmware inside, $([math]::Round((Get-Item $exe).Length / 1KB)) KB)"

# Release file for the download page, plus its SHA-256 so people can check their download
if (-not $Release) { Write-Host 'Test build only (add -Release to make the release file).'; return }
$dist = Join-Path $root 'dist'
$relCheck = Join-Path $dist "FermFrame-$appVer.exe"
if ((Test-Path $relCheck) -and -not $env:FERMFRAME_OVERWRITE_RELEASE) {
  throw "dist\FermFrame-$appVer.exe already exists - if $appVer is published, bump AppInfo.Version first (or set FERMFRAME_OVERWRITE_RELEASE=1 if it was never published)."
}
New-Item -ItemType Directory -Force $dist | Out-Null
$rel = Join-Path $dist "FermFrame-$appVer.exe"
Copy-Item $exe $rel -Force
$hash = (Get-FileHash $rel -Algorithm SHA256).Hash.ToLower()
Set-Content -Encoding ascii (Join-Path $dist "FermFrame-$appVer.exe.sha256") "$hash  FermFrame-$appVer.exe"
# Keep the checksum in the release notes up to date
$notes = Join-Path $root "docs\release-notes-$appVer.md"
if (Test-Path $notes) {
  $txt = [IO.File]::ReadAllText($notes)
  $txt = [regex]::Replace($txt, '(?m)^`([0-9a-f]{64}|SHA256_GOES_HERE)`', '`' + $hash + '`')
  [IO.File]::WriteAllText($notes, $txt, (New-Object Text.UTF8Encoding $false))
}
Write-Host "Release: $rel"
Write-Host "SHA-256: $hash"
