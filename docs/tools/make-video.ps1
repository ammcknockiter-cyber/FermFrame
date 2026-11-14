# Makes docs\video\FermFrame-guide.mp4: narration + the app (demo mode) driven and recorded scene by scene.
#   powershell -NoProfile -STA -ExecutionPolicy Bypass -File make-video.ps1 [-Voice "Sarah"] [-Draft]
# Voice clips, in order of preference:
#   1. docs\video\voice\01.mp3 ... 14.mp3 (or .wav) that you made yourself
#   2. ElevenLabs, if the ELEVENLABS_API_KEY user environment variable is set (-Voice picks the voice by name)
#   3. a Windows voice (-Draft, or when neither of the above is available) - for test runs
# Needs FFmpeg. Leave the mouse and keyboard alone while it records (about 5 minutes).

param([string]$Voice = '', [switch]$Draft, [double]$Scale = 1.2, [string]$Credit = 'Voice: ElevenLabs', [switch]$ReuseRecording)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'drive.ps1')
$videoDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'video'
$voiceDir = Join-Path $videoDir 'voice'
$work = Join-Path $videoDir 'work'
New-Item -ItemType Directory -Force $voiceDir, $work | Out-Null

$ffmpeg = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source
if (-not $ffmpeg) { $ffmpeg = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName }
if (-not $ffmpeg) { throw 'FFmpeg not found' }
$ffprobe = Join-Path (Split-Path $ffmpeg) 'ffprobe.exe'
function Dur($f) { [double](& $ffprobe -v error -show_entries format=duration -of csv=p=0 $f) }
function FF([string[]]$a) { & $ffmpeg -hide_banner -loglevel error -y @a; if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed: $a" } }

# ---------------------------------------------------------------- the script
$lines = @{}
foreach ($row in Get-Content (Join-Path $videoDir 'script.md') -Encoding UTF8) {
  if ($row -match '^\|\s*(\d\d)\s*\|[^|]*\|\s*(.+?)\s*\|\s*$') { $lines[$Matches[1]] = $Matches[2] }
}
$ids = $lines.Keys | Sort-Object

# ---------------------------------------------------------------- voice clips
$key = [Environment]::GetEnvironmentVariable('ELEVENLABS_API_KEY', 'User')
if (-not $key) { $key = $env:ELEVENLABS_API_KEY }
$usedEleven = $false; $usedDraft = $false
$voiceId = $null
function ElevenVoiceId([string]$name) {
  $v = Invoke-RestMethod 'https://api.elevenlabs.io/v1/voices' -Headers @{ 'xi-api-key' = $key }
  if ($name) { $m = $v.voices | Where-Object { $_.name -like "$name*" } | Select-Object -First 1; if (-not $m) { throw "No ElevenLabs voice called '$name' on your account" }; return $m.voice_id }
  # default: a calm, warm stock voice
  foreach ($n in 'Sarah', 'Matilda', 'Alice', 'Rachel') { $m = $v.voices | Where-Object { $_.name -like "$n*" } | Select-Object -First 1; if ($m) { return $m.voice_id } }
  return ($v.voices | Select-Object -First 1).voice_id
}
function WindowsVoice([string]$text, [string]$out) {
  Add-Type -AssemblyName System.Speech
  $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
  try { $s.SelectVoice('Microsoft Hazel Desktop') } catch {}
  $s.Rate = -1; $s.SetOutputToWaveFile($out); $s.Speak($text); $s.Dispose()
}
$clips = @{}
foreach ($id in $ids) {
  $own = Get-ChildItem $voiceDir -File -ErrorAction SilentlyContinue | Where-Object { $_.BaseName -eq $id -and $_.Extension -in '.mp3', '.wav', '.m4a' } | Select-Object -First 1
  if ($own -and -not $Draft) { $clips[$id] = $own.FullName; continue }
  if ($key -and -not $Draft) {
    if (-not $voiceId) { $voiceId = ElevenVoiceId $Voice }
    $out = Join-Path $voiceDir "$id.mp3"
    $body = @{ text = $lines[$id]; model_id = 'eleven_multilingual_v2'; voice_settings = @{ stability = 0.55; similarity_boost = 0.75; style = 0.15 } } | ConvertTo-Json
    Invoke-RestMethod "https://api.elevenlabs.io/v1/text-to-speech/$voiceId`?output_format=mp3_44100_128" -Method Post -Headers @{ 'xi-api-key' = $key } -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -OutFile $out
    $clips[$id] = $out; $usedEleven = $true; Write-Host "  voice $id (ElevenLabs)"; continue
  }
  $out = Join-Path $work "draft-$id.wav"; WindowsVoice $lines[$id] $out; $clips[$id] = $out; $usedDraft = $true
}
if ($usedDraft) { Write-Host '  (draft: Windows voice)' }
$len = @{}; foreach ($id in $ids) { $len[$id] = Dur $clips[$id] }

# ---------------------------------------------------------------- recording
$lead = 0.5   # seconds of picture before the voice starts in each scene
$script:rec = $null
function Start-Rec([string]$file) {
  if (Test-Path $file) { Remove-Item $file }
  $psi = New-Object System.Diagnostics.ProcessStartInfo $ffmpeg
  $psi.Arguments = "-hide_banner -loglevel error -nostats -y -f gdigrab -framerate 30 -draw_mouse 1 -offset_x 0 -offset_y 0 -video_size 1920x1080 -i desktop -c:v libx264 -preset ultrafast -tune zerolatency -crf 14 -pix_fmt yuv420p -f mpegts -flush_packets 1 `"$file`""
  $psi.UseShellExecute = $false; $psi.RedirectStandardInput = $true; $psi.CreateNoWindow = $true
  $script:rec = [System.Diagnostics.Process]::Start($psi)
  $until = (Get-Date).AddSeconds(10)
  while ((Get-Date) -lt $until) { if ((Test-Path $file) -and (Get-Item $file).Length -gt 0) { break }; Pump 0.02 }   # frames are written as they are captured
  $script:recStart = Get-Date
}
function Stop-Rec { $script:rec.StandardInput.Write('q'); $script:rec.StandardInput.Close(); [void]$script:rec.WaitForExit(15000) }
function Elapsed { ((Get-Date) - $script:recStart).TotalSeconds }
function Hold([double]$until) { while ((Elapsed) -lt $until) { Pump 0.05 } }
function Park { Move-Mouse 2300 1250 250 }

$scenes = [ordered]@{}
$s = $Scale

$scenes['01'] = { Hold ($lead + $len['01'] + 1.0) }
$scenes['02'] = {
  Hold 1.6; Move-Mouse 1000 700 700; Pump 0.4
  Click-El (Find-El $script:inst 'Install') 0 | Out-Null
} # (the click is harmless in demo mode: it only says "nothing was installed")
$scenes['03'] = {
  Hold 2.2
  $b = Get-Bounds $script:app.MainWindowHandle
  foreach ($i in 0..2) { Move-Mouse ([int]($b.X + 150 * $s)) ([int]($b.Y + 31 + (127 + 70 * $i) * $s)) 700; Pump 1.0 }
}
$scenes['04'] = {
  Hold 0.8
  foreach ($n in 'Gravity', 'Temperature', 'ABV so far', 'Fermenting') { $c = Get-Center (Find-El $script:app $n); Move-Mouse ($c[0] + 30) ($c[1] + 30) 600; Pump 1.0 }
}
$scenes['05'] = {
  $b = Get-Bounds $script:app.MainWindowHandle
  $y = [int]($b.Y + 31 + 500 * $s)
  Move-Mouse ([int]($b.X + 400 * $s)) $y 700
  Move-Mouse ([int]($b.X + 1040 * $s)) ([int]($b.Y + 31 + 600 * $s)) 3500
}
$scenes['06'] = {
  Hold 0.6; Click-ListItem $script:app 1 700; Pump 1.5
  $c = Get-Center (Find-El $script:app 'Ready to keg?'); Move-Mouse $c[0] ($c[1] + 6) 800
}
$scenes['07'] = {
  Click-Name $script:app 'Rename' 700; Pump 0.8
  Press '^a'; Type-Text 'Stout corner' 90; Pump 0.5
  Click-Name $script:app 'OK' 500; Pump 0.5
}
$scenes['08'] = {
  Click-Name $script:app 'Settings' 700; Pump 1.6
  Move-Mouse ([int]($script:b.X + 700 * $s)) ([int]($script:b.Y + 500 * $s)) 500
  Scroll -5 120; Pump 0.6
  Click-El (Find-El $script:app 'Beer (amber on black)') 700; Pump 0.6; Press '{DOWN}'; Pump 0.3; Press '{ENTER}'; Pump 1.0
  $l = (Find-El $script:app 'Brightness' -Partial).Current.BoundingRectangle
  $r = 9 * $s; $w = 560 * $s; $y = [int]($l.Y + 24 * $s + 22 * $s)
  $x80 = [int]($l.X + $r + ($w - 2 * $r) * (80 - 5) / 95); $x55 = [int]($l.X + $r + ($w - 2 * $r) * (55 - 5) / 95)
  Drag $x80 $y $x55 $y 900; Pump 0.4; Drag $x55 $y $x80 $y 900
}
$scenes['09'] = {
  Move-Mouse ([int]($script:b.X + 700 * $s)) ([int]($script:b.Y + 450 * $s)) 400
  Scroll -5 120; Pump 1.2; Scroll -3 120; Pump 0.5
  Click-El (Find-El $script:app 'Big number') 700; Pump 0.8
}
$scenes['10'] = { Click-Name $script:app 'Save to FermFrame' 800; Pump 1.0; $c = Get-Center (Find-El $script:app 'Saved' -Partial); Move-Mouse $c[0] ($c[1] + 20) 600 }
$scenes['11'] = {
  Click-Name $script:app 'Firmware' 700; Pump 1.0
  Click-Name $script:app 'Reinstall' -Partial 700; Park
  $until = (Get-Date).AddSeconds(25)
  while ((Get-Date) -lt $until) { try { [void](Find-El $script:app 'now on FermFrame' -Partial -timeout 1); break } catch {} }
  Pump 1.0
}
$scenes['12'] = {
  Click-Name $script:app 'Buy another FermFrame' 800; Pump 2.0
  foreach ($n in 'AliExpress - the tested clock', 'Amazon UK') { $c = Get-Center (Find-El $script:app $n); Move-Mouse $c[0] $c[1] 700; Pump 1.0 }
}
$scenes['13'] = {
  Click-Name $script:app 'Set up a new FermFrame' -Partial 800; Pump 1.2
  Click-El (Find-El $script:app 'Brewfather user ID') 500 0 ([int](30 * $s)); Type-Text 'aB3dE5fG7hJ9kL1mN3pQ5rS7tU9v' 25
  Click-El (Find-El $script:app 'Brewfather API key') 500 0 ([int](30 * $s)); Type-Text 'demo-api-key-123' 40
  Click-Name $script:app 'Set up my FermFrame' 600; Park
}
$scenes['14'] = { Hold ($lead + $len['14'] + 1.5) }

# Before/after each scene (not recorded): open and close windows
$before = @{
  '01' = { Set-BackdropText $script:bg 'FermFrame' 'Your fermentation, at a glance' }
  '02' = { Set-BackdropText $script:bg '' ''; $script:inst = Start-FF @('/installer') $s; Center-Window $script:inst.MainWindowHandle; Park }
  '03' = { Stop-FF $script:inst; $script:app = Start-FF @() $s; Center-Window $script:app.MainWindowHandle 1920 1080 16; $script:b = Get-Bounds $script:app.MainWindowHandle; Park; Pump 3 }
  '12' = { }
  '14' = { Press '%{F4}'; Pump 0.5; Stop-FF $script:app; $dot = '   ' + [char]0xB7 + '   '; $credit = if (-not $usedDraft -and $Credit) { $dot + $Credit } else { '' }
           Set-BackdropText $script:bg 'FermFrame' ("Free and open source" + $dot + "github.com/ammcknockiter-cyber/FermFrame" + $credit) }
}
$after = @{
  '12' = { Click-Name $script:app 'Close' 400; Pump 0.5 }
}

$segs = @()
if ($ReuseRecording) { Write-Host '  using the existing recordings' }
else {
$script:bg = Show-Backdrop 0 0 1920 1080
try {
  foreach ($id in $ids) {
    if ($before.ContainsKey($id)) { & $before[$id] }
    Park
    $file = Join-Path $work "seg$id.ts"
    Start-Rec $file
    Hold $lead
    & $scenes[$id]
    Hold ($lead + $len[$id] + 0.8)      # never shorter than the narration
    Stop-Rec
    $segs += $file
    Write-Host ("  scene {0}: {1:0.0} s" -f $id, (Dur $file))
    if ($after.ContainsKey($id)) { & $after[$id] }
  }
}
finally {
  foreach ($p in @($script:app, $script:inst)) { if ($p) { Stop-FF $p } }
  $script:bg.Close()
}
}

# ---------------------------------------------------------------- putting it together
$list = @(); $srt = New-Object Text.StringBuilder; $t0 = 0.0; $n = 0
function T([double]$x) { $ts = [TimeSpan]::FromSeconds($x); '{0:00}:{1:00}:{2:00},{3:000}' -f [int]$ts.TotalHours, $ts.Minutes, $ts.Seconds, $ts.Milliseconds }
foreach ($id in $ids) {
  $mp4 = Join-Path $work "seg$id.mp4"
  FF @('-i', (Join-Path $work "seg$id.ts"), '-i', $clips[$id], '-filter_complex', "[1:a]aresample=48000,aformat=channel_layouts=stereo,adelay=$([int]($lead*1000)):all=1,apad[a]",
       '-map', '0:v', '-map', '[a]', '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p', '-r', '30', '-c:a', 'aac', '-b:a', '160k', '-shortest', $mp4)
  $list += "file '$($mp4.Replace('\', '/'))'"
  # captions: the scene's sentences, spread over its narration by length
  $sent = [regex]::Split($lines[$id], '(?<=[.!?])\s+') | Where-Object { $_ }
  $total = ($sent | Measure-Object -Property Length -Sum).Sum; $at = $t0 + $lead
  foreach ($x in $sent) {
    $d = $len[$id] * $x.Length / $total; $n++
    [void]$srt.AppendLine($n); [void]$srt.AppendLine("$(T $at) --> $(T ($at + $d))"); [void]$srt.AppendLine($x); [void]$srt.AppendLine('')
    $at += $d
  }
  $t0 += Dur $mp4
}
[IO.File]::WriteAllLines((Join-Path $work 'list.txt'), $list)
$srtFile = Join-Path $videoDir 'FermFrame-guide.srt'
[IO.File]::WriteAllText($srtFile, $srt.ToString(), (New-Object Text.UTF8Encoding $false))
FF @('-f', 'concat', '-safe', '0', '-i', (Join-Path $work 'list.txt'), '-c', 'copy', (Join-Path $work 'joined.mp4'))

$final = Join-Path $videoDir $(if ($usedDraft) { 'FermFrame-guide-DRAFT.mp4' } else { 'FermFrame-guide.mp4' })
Copy-Item $srtFile (Join-Path $work 'captions.srt') -Force
Push-Location $work
try {
  $style = "FontName=Segoe UI,FontSize=15,PrimaryColour=&H00FFFFFF,BackColour=&H33000000,BorderStyle=4,Outline=0,Shadow=0,MarginV=6"
  FF @('-i', 'joined.mp4', '-vf', "subtitles=captions.srt:force_style='$style'", '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p', '-af', 'loudnorm=I=-16:TP=-1.5:LRA=11', '-ar', '48000', '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', $final)
}
finally { Pop-Location }
Write-Host ("Video: {0} ({1:0} s, {2:0.0} MB)" -f $final, (Dur $final), ((Get-Item $final).Length / 1MB))
Write-Host "Captions: $srtFile"
