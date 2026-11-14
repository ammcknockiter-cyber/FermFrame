# FermFrame Installer
# Puts FermFrame on a Geekmagic "Smart Weather Clock" and sets it up, over Wi-Fi.
#
#  1. Finds the clock: its own Wi-Fi ("Smart weather clock..." when new, "FermFrame-Setup"
#     when FermFrame is on it but not set up) or on the home network.
#  2. New clock: sends fermframe-lite.bin to the clock's own update page (/update_ota).
#  3. Sends the full fermframe.bin through FermFrame's update page (/update).
#  4. Saves the home Wi-Fi + Brewfather/RAPT details (/save) and puts this PC back on its Wi-Fi.
#  5. Waits for the clock on the home network and reports how it's doing.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing, System.Net.Http
[System.Windows.Forms.Application]::EnableVisualStyles()
trap {
  [void][System.Windows.Forms.MessageBox]::Show("Something went wrong:`n`n$($_.Exception.Message)", 'FermFrame Installer', 'OK', 'Error')
  exit 1
}

$Here      = Split-Path -Parent $MyInvocation.MyCommand.Path
$LiteBin   = Join-Path $Here 'fermframe-lite.bin'
$FullBin   = Join-Path $Here 'fermframe.bin'
$ClockAP   = '192.168.4.1'
$StockMax  = 507904                      # largest file a new clock's update page takes
$SetupSsid = 'FermFrame-Setup'
$OldSetupSsid = 'BrewScreen-Setup'   # name before FermFrame
$TmpPrefix = 'fermframe-tmp-'
$script:PrevProfile = $null
$script:Iface = $null
$script:MadeProfiles = @()
$script:ClockMac = $null

# ------------------------------------------------------------------ Wi-Fi scan trigger (wlanapi)

Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public static class BsWlan {
  [DllImport("wlanapi.dll")] static extern uint WlanOpenHandle(uint v, IntPtr r, out uint nv, out IntPtr h);
  [DllImport("wlanapi.dll")] static extern uint WlanEnumInterfaces(IntPtr h, IntPtr r, out IntPtr list);
  [DllImport("wlanapi.dll")] static extern uint WlanScan(IntPtr h, ref Guid g, IntPtr s, IntPtr ie, IntPtr r);
  [DllImport("wlanapi.dll")] static extern void WlanFreeMemory(IntPtr p);
  [DllImport("wlanapi.dll")] static extern uint WlanCloseHandle(IntPtr h, IntPtr r);
  public static void ScanAll() {
    uint nv; IntPtr h;
    if (WlanOpenHandle(2, IntPtr.Zero, out nv, out h) != 0) return;
    IntPtr list;
    if (WlanEnumInterfaces(h, IntPtr.Zero, out list) == 0) {
      int n = Marshal.ReadInt32(list);
      for (int i = 0; i < n; i++) {
        Guid g = (Guid)Marshal.PtrToStructure(new IntPtr(list.ToInt64() + 8 + i * 532), typeof(Guid));
        WlanScan(h, ref g, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
      }
      WlanFreeMemory(list);
    }
    WlanCloseHandle(h, IntPtr.Zero);
  }
}
"@

# ------------------------------------------------------------------ UI

$font  = New-Object System.Drawing.Font('Segoe UI', 11)
$bold  = New-Object System.Drawing.Font('Segoe UI', 11, [System.Drawing.FontStyle]::Bold)
$big   = New-Object System.Drawing.Font('Segoe UI', 18, [System.Drawing.FontStyle]::Bold)
$amber = [System.Drawing.Color]::FromArgb(255, 204, 51)

$form = New-Object System.Windows.Forms.Form
$form.Text = 'FermFrame Installer'
$form.ClientSize = New-Object System.Drawing.Size(520, 760)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.BackColor = [System.Drawing.Color]::FromArgb(24, 24, 24)
$form.ForeColor = [System.Drawing.Color]::Gainsboro
$form.Font = $font

$y = 14
function Add-Label($text, $f = $font, $h = 26, $color = $null) {
  $l = New-Object System.Windows.Forms.Label
  $l.Text = $text; $l.Font = $f; $l.AutoSize = $false
  $l.Location = New-Object System.Drawing.Point(20, $script:y)
  $l.Size = New-Object System.Drawing.Size(480, $h)
  if ($color) { $l.ForeColor = $color }
  $form.Controls.Add($l); $script:y += $h
  return $l
}
function Add-Box([switch]$Password) {
  $t = New-Object System.Windows.Forms.TextBox
  $t.Location = New-Object System.Drawing.Point(20, $script:y)
  $t.Size = New-Object System.Drawing.Size(480, 28)
  $t.BackColor = [System.Drawing.Color]::FromArgb(40, 40, 40); $t.ForeColor = [System.Drawing.Color]::White
  if ($Password) { $t.UseSystemPasswordChar = $true }
  $form.Controls.Add($t); $script:y += 36
  return $t
}

[void](Add-Label 'FermFrame Installer' $big 40 $amber)
[void](Add-Label 'Plug your clock into power (no cable to this PC needed). This PC needs Wi-Fi turned on.' $font 46)

[void](Add-Label '1. Your home Wi-Fi' $bold 28 $amber)
[void](Add-Label 'Wi-Fi name')
$ssidBox = New-Object System.Windows.Forms.ComboBox
$ssidBox.Location = New-Object System.Drawing.Point(20, $y); $ssidBox.Size = New-Object System.Drawing.Size(480, 28)
$ssidBox.BackColor = [System.Drawing.Color]::FromArgb(40, 40, 40); $ssidBox.ForeColor = [System.Drawing.Color]::White
$form.Controls.Add($ssidBox); $y += 36
[void](Add-Label 'Wi-Fi password')
$passBox = Add-Box -Password

[void](Add-Label '2. Where are your readings?' $bold 28 $amber)
$rbBf = New-Object System.Windows.Forms.RadioButton
$rbBf.Text = 'Brewfather'; $rbBf.Checked = $true
$rbBf.Location = New-Object System.Drawing.Point(24, $y); $rbBf.Size = New-Object System.Drawing.Size(200, 28)
$rbRapt = New-Object System.Windows.Forms.RadioButton
$rbRapt.Text = 'RAPT Pill'
$rbRapt.Location = New-Object System.Drawing.Point(250, $y); $rbRapt.Size = New-Object System.Drawing.Size(200, 28)
$form.Controls.AddRange(@($rbBf, $rbRapt)); $y += 32
$help = New-Object System.Windows.Forms.LinkLabel
$help.Location = New-Object System.Drawing.Point(20, $y); $help.Size = New-Object System.Drawing.Size(480, 44)
$help.LinkColor = $amber; $help.ActiveLinkColor = $amber; $help.Font = New-Object System.Drawing.Font('Segoe UI', 9.5)
$form.Controls.Add($help); $y += 46
$l1 = Add-Label 'Brewfather user ID'
$userBox = Add-Box
$l2 = Add-Label 'Brewfather API key'
$keyBox = Add-Box -Password

function Update-Labels {
  if ($rbBf.Checked) {
    $l1.Text = 'Brewfather user ID'; $l2.Text = 'Brewfather API key'
    $help.Text = 'Where are these? In Brewfather: Settings > API > Generate API key (tick "Read batches"). Click here to open Brewfather.'
    $help.Tag = 'https://web.brewfather.app/tabs/settings'
  } else {
    $l1.Text = 'RAPT email'; $l2.Text = 'RAPT API secret (not your password)'
    $help.Text = 'Where is this? On the RAPT website: My account > API Secrets > Create. Click here to open RAPT.'
    $help.Tag = 'https://app.rapt.io/'
  }
  $help.LinkArea = New-Object System.Windows.Forms.LinkArea(($help.Text.IndexOf('Click here')), 10)
}
$rbBf.Add_CheckedChanged({ Update-Labels })
$help.Add_LinkClicked({ Start-Process $help.Tag })
Update-Labels

$y += 6
$go = New-Object System.Windows.Forms.Button
$go.Text = 'Set up my clock'
$go.Location = New-Object System.Drawing.Point(20, $y); $go.Size = New-Object System.Drawing.Size(480, 48)
$go.BackColor = $amber; $go.ForeColor = [System.Drawing.Color]::Black; $go.FlatStyle = 'Flat'
$go.Font = New-Object System.Drawing.Font('Segoe UI', 13, [System.Drawing.FontStyle]::Bold)
$form.Controls.Add($go); $y += 58

$logBox = New-Object System.Windows.Forms.RichTextBox
$logBox.Location = New-Object System.Drawing.Point(20, $y); $logBox.Size = New-Object System.Drawing.Size(480, (760 - $y - 14))
$logBox.ReadOnly = $true; $logBox.BorderStyle = 'None'
$logBox.BackColor = [System.Drawing.Color]::FromArgb(34, 34, 34); $logBox.ForeColor = [System.Drawing.Color]::Gainsboro
$logBox.Font = New-Object System.Drawing.Font('Segoe UI', 10)
$form.Controls.Add($logBox)

function Pump { [System.Windows.Forms.Application]::DoEvents() }
function Wait-Seconds($s) { $until = (Get-Date).AddSeconds($s); while ((Get-Date) -lt $until) { Pump; Start-Sleep -Milliseconds 100 } }
function Log($text, [string]$kind = '') {
  $logBox.SelectionStart = $logBox.TextLength
  $logBox.SelectionColor = switch ($kind) {
    'good' { [System.Drawing.Color]::LightGreen } 'bad' { [System.Drawing.Color]::Salmon }
    'step' { $amber } default { [System.Drawing.Color]::Gainsboro } }
  $logBox.AppendText("$text`r`n"); $logBox.ScrollToCaret(); Pump
}

# ------------------------------------------------------------------ Wi-Fi helpers (netsh)

function Netsh([string[]]$a) { (& netsh.exe wlan @a 2>&1 | Out-String) }
function Field($text, $name) { $m = [regex]::Match($text, "(?m)^\s*$name\s*:\s*(.*)$"); if ($m.Success) { $m.Groups[1].Value.Trim() } else { $null } }

function Get-Iface {
  $t = Netsh @('show', 'interfaces')
  $name = Field $t 'Name'
  if (-not $name) { return $null }
  [pscustomobject]@{ Name = $name; Connected = ($t -match '(?m)^\s*State\s*:\s*connected'); Ssid = (Field $t 'SSID'); Profile = (Field $t 'Profile') }
}

function Get-Networks {
  try { [BsWlan]::ScanAll() } catch {}
  Wait-Seconds 4
  $t = Netsh @('show', 'networks', 'mode=bssid')
  if ($t -match 'location') { throw 'LOCATION' }
  $nets = @()
  foreach ($block in ($t -split '(?m)^SSID \d+ :') | Select-Object -Skip 1) {
    $ssid = ($block -split "`r?`n")[0].Trim()
    $bands = [regex]::Matches($block, 'Band\s*:\s*([\d.]+)') | ForEach-Object { $_.Groups[1].Value }
    $chans = [regex]::Matches($block, '(?m)^\s*Channel\s*:\s*(\d+)') | ForEach-Object { [int]$_.Groups[1].Value }
    $has24 = ($bands -contains '2.4') -or (@($chans | Where-Object { $_ -le 14 }).Count -gt 0)
    if ($ssid) { $nets += [pscustomobject]@{ Ssid = $ssid; Has24 = $has24 } }
  }
  $nets
}

function Join-Open($ssid, $seconds) {
  $prof = "$TmpPrefix$ssid"
  $x = [Security.SecurityElement]::Escape($ssid); $p = [Security.SecurityElement]::Escape($prof)
  $xml = "<?xml version=`"1.0`"?><WLANProfile xmlns=`"http://www.microsoft.com/networking/WLAN/profile/v1`"><name>$p</name><SSIDConfig><SSID><name>$x</name></SSID></SSIDConfig><connectionType>ESS</connectionType><connectionMode>manual</connectionMode><MSM><security><authEncryption><authentication>open</authentication><encryption>none</encryption><useOneX>false</useOneX></authEncryption></security></MSM></WLANProfile>"
  $file = Join-Path $env:TEMP 'fermframe-profile.xml'
  [IO.File]::WriteAllText($file, $xml)
  [void](Netsh @('add', 'profile', "filename=$file", "interface=$($script:Iface)", 'user=current'))
  Remove-Item $file -ErrorAction SilentlyContinue
  if ($script:MadeProfiles -notcontains $prof) { $script:MadeProfiles += $prof }
  $until = (Get-Date).AddSeconds($seconds); $asked = [datetime]::MinValue
  while ((Get-Date) -lt $until) {
    if (((Get-Date) - $asked).TotalSeconds -gt 8) { $asked = Get-Date; [void](Netsh @('connect', "name=$prof", "ssid=$ssid", "interface=$($script:Iface)")) }
    Wait-Seconds 1
    $i = Get-Iface
    if ($i -and $i.Connected -and $i.Ssid -eq $ssid) {
      $ip = Get-NetIPAddress -InterfaceAlias $script:Iface -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like '192.168.4.*' }
      if ($ip) { Wait-Seconds 1; return $true }
    }
  }
  return $false
}

function Restore-Wifi {
  if (-not $script:Iface) { return $true }
  if ($script:PrevProfile) { [void](Netsh @('connect', "name=$($script:PrevProfile)", "interface=$($script:Iface)")) }
  foreach ($p in $script:MadeProfiles) { [void](Netsh @('delete', 'profile', "name=$p")) }
  $script:MadeProfiles = @()
  if (-not $script:PrevProfile) { return $true }
  $until = (Get-Date).AddSeconds(40)
  while ((Get-Date) -lt $until) {
    $i = Get-Iface
    if ($i -and $i.Connected -and $i.Profile -eq $script:PrevProfile) {
      $ip = Get-NetIPAddress -InterfaceAlias $script:Iface -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '169.254.*' }
      if ($ip) { return $true }
    }
    Wait-Seconds 1
  }
  return $false
}

function Saved-WifiPassword($ssid) {
  try {
    $t = (& netsh.exe wlan show profile "name=$ssid" key=clear 2>$null | Out-String)
    $k = Field $t 'Key Content'
    if ($k) { return $k }
  } catch {}
  return ''
}

# ------------------------------------------------------------------ HTTP helpers

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$http = New-Object System.Net.Http.HttpClient($handler)
$http.Timeout = [TimeSpan]::FromSeconds(150)

function Await($task, $seconds) {
  $until = (Get-Date).AddSeconds($seconds)
  while (-not $task.IsCompleted) { if ((Get-Date) -gt $until) { return $null }; Pump; Start-Sleep -Milliseconds 50 }
  if ($task.IsFaulted -or $task.IsCanceled) { return $null }
  return $task.Result
}

function Http-Get($url, $seconds = 6) {
  $cts = New-Object System.Threading.CancellationTokenSource([TimeSpan]::FromSeconds($seconds))
  $r = Await ($http.GetAsync($url, $cts.Token)) ($seconds + 1)
  if (-not $r) { return $null }
  $body = Await ($r.Content.ReadAsStringAsync()) 5
  [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $body }
}

function Http-Json($url, $seconds = 6) {
  $r = Http-Get $url $seconds
  if (-not $r -or $r.Status -ne 200) { return $null }
  try { $r.Body | ConvertFrom-Json } catch { $null }
}

function Http-PostFile($url, $field, $fileName, [byte[]]$bytes, $seconds = 150) {
  $mp = New-Object System.Net.Http.MultipartFormDataContent
  $fc = New-Object System.Net.Http.ByteArrayContent(, $bytes)
  $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/octet-stream')
  $mp.Add($fc, $field, $fileName)
  $t = $http.PostAsync($url, $mp)
  $r = Await $t $seconds
  if (-not $r) {
    $err = if ($t.Exception) { $t.Exception.GetBaseException().Message } else { 'timed out' }
    return [pscustomobject]@{ Status = 0; Body = ''; Error = $err }
  }
  [pscustomobject]@{ Status = [int]$r.StatusCode; Body = (Await ($r.Content.ReadAsStringAsync()) 5); Error = $null }
}

function Http-PostForm($url, [hashtable]$fields, $seconds = 20) {
  $d = New-Object 'System.Collections.Generic.Dictionary[string,string]'
  foreach ($k in $fields.Keys) { $d[$k] = [string]$fields[$k] }
  $r = Await ($http.PostAsync($url, (New-Object System.Net.Http.FormUrlEncodedContent($d)))) $seconds
  if (-not $r) { return 0 }
  [int]$r.StatusCode
}

function Title-Of($body) { [regex]::Match([string]$body, '(?i)<title>([^<]*)</title>').Groups[1].Value.Trim() }

# The clock's status, from FermFrame (/fermframe.json) or the older BrewScreen (/brewscreen.json)
function Get-ClockInfo($base, $seconds = 6) {
  foreach ($path in '/fermframe.json', '/brewscreen.json') {
    $j = Http-Json "$base$path" $seconds
    if ($j -and ($j.firmware -eq 'fermframe' -or $j.firmware -eq 'brewscreen')) { return $j }
  }
  return $null
}

# ------------------------------------------------------------------ finding the clock on the home network

# $mac: only accept the clock with this MAC (there may be more than one clock at home)
function Find-ClockOnLan($mac = $null) {
  $j = Get-ClockInfo 'http://fermframe.local' 4
  if (-not $j) { $j = Get-ClockInfo 'http://brewscreen.local' 4 }
  if ($j -and (-not $mac -or $j.mac -eq $mac)) { return [pscustomobject]@{ Kind = 'fermframe'; Base = "http://$($j.ip)"; Info = $j } }
  $mine = Get-NetIPAddress -InterfaceAlias $script:Iface -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1
  if (-not $mine) { return $null }
  $prefix = ($mine.IPAddress -split '\.')[0..2] -join '.'
  $probes = foreach ($n in 1..254) {
    $c = New-Object System.Net.Sockets.TcpClient
    [pscustomobject]@{ Ip = "$prefix.$n"; Client = $c; Task = $c.ConnectAsync("$prefix.$n", 80) }
  }
  Wait-Seconds 2
  $open = foreach ($p in $probes) { if ($p.Task.Status -eq 'RanToCompletion' -and $p.Client.Connected) { $p.Ip }; $p.Client.Close() }
  foreach ($ip in $open) {
    $j = Get-ClockInfo "http://$ip" 3
    if ($j) {
      if (-not $mac -or $j.mac -eq $mac) { return [pscustomobject]@{ Kind = 'fermframe'; Base = "http://$ip"; Info = $j } }
      continue
    }
    if ($mac) { continue }
    $r = Http-Get "http://$ip/" 3
    if ($r -and (Title-Of $r.Body) -match '(?i)smart weather clock|스마트') {
      return [pscustomobject]@{ Kind = 'stock'; Base = "http://$ip"; Title = (Title-Of $r.Body) }
    }
  }
  return $null
}

# ------------------------------------------------------------------ the steps

function Flash-Stock($base, $title) {
  $bytes = [IO.File]::ReadAllBytes($LiteBin)
  if ($bytes.Length -gt $StockMax) { throw "fermframe-lite.bin is too big for a new clock ($($bytes.Length) bytes)." }
  $prefix = if ($title -match '스마트') { 'KR_SDP' } else { 'SDP' }
  $m = Http-Get "$base/mac" 5
  if ($m -and $m.Body -match '^\s*([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5})\s*$') { $script:ClockMac = $Matches[1].ToUpper(); Log "Clock ID: $($script:ClockMac)" }
  Log 'Installing FermFrame... (about 30 seconds - keep the clock plugged in!)'
  $r = Http-PostFile "$base/update_ota" 'update' "$prefix-fermframe-lite.bin" $bytes 150
  if ($r.Status -eq 200 -and $r.Body.Trim() -eq 'OK') { Log 'Installed. The clock is restarting.' 'good'; return }
  if ($r.Status -eq 0) { Log "The clock stopped answering after the upload ($($r.Error)) - checking whether it worked..."; return }
  throw "The clock didn't accept the file (it said: $($r.Status) $($r.Body))."
}

$FullVersion = '2.2.0'

# This PC's time zone in the form the clock uses (for the Clock page and night dimming)
function Get-PosixTimeZone {
  $id = try { (Get-TimeZone).Id } catch { '' }
  $map = @{
    'GMT Standard Time' = 'GMT0BST,M3.5.0/1,M10.5.0'; 'Greenwich Standard Time' = 'GMT0BST,M3.5.0/1,M10.5.0'
    'W. Europe Standard Time' = 'CET-1CEST,M3.5.0,M10.5.0/3'; 'Central Europe Standard Time' = 'CET-1CEST,M3.5.0,M10.5.0/3'
    'Romance Standard Time' = 'CET-1CEST,M3.5.0,M10.5.0/3'; 'Central European Standard Time' = 'CET-1CEST,M3.5.0,M10.5.0/3'
    'E. Europe Standard Time' = 'EET-2EEST,M3.5.0/3,M10.5.0/4'; 'FLE Standard Time' = 'EET-2EEST,M3.5.0/3,M10.5.0/4'
    'GTB Standard Time' = 'EET-2EEST,M3.5.0/3,M10.5.0/4'
    'Eastern Standard Time' = 'EST5EDT,M3.2.0,M11.1.0'; 'Central Standard Time' = 'CST6CDT,M3.2.0,M11.1.0'
    'Mountain Standard Time' = 'MST7MDT,M3.2.0,M11.1.0'; 'Pacific Standard Time' = 'PST8PDT,M3.2.0,M11.1.0'
    'AUS Eastern Standard Time' = 'AEST-10AEDT,M10.1.0,M4.1.0/3'; 'Tasmania Standard Time' = 'AEST-10AEDT,M10.1.0,M4.1.0/3'
    'E. Australia Standard Time' = 'AEST-10'; 'New Zealand Standard Time' = 'NZST-12NZDT,M9.5.0,M4.1.0/3'
    'South Africa Standard Time' = 'SAST-2'; 'UTC' = 'UTC0'
  }
  if ($map.ContainsKey($id)) { return $map[$id] }
  return 'GMT0BST,M3.5.0/1,M10.5.0'
}

# Puts the full build on (if needed) and saves the settings. $base is http://ip
function Finish-Setup($base, $viaHotspot) {
  $info = Get-ClockInfo $base 8
  if (-not $info) { throw "The clock isn't answering at $base." }
  $script:ClockMac = $info.mac
  $old = $true
  try { $old = [version]$info.version -lt [version]$FullVersion } catch {}
  if ($info.build -ne 'full' -or $old) {
    Log "Installing the full FermFrame $FullVersion..."
    $r = Http-PostFile "$base/update" 'firmware' 'fermframe.bin' ([IO.File]::ReadAllBytes($FullBin)) 150
    if ($r.Status -ne 200) {
      if ($info.build -ne 'full') { throw "The full version didn't install ($($r.Status) $($r.Error)). Keep the clock near the PC and press Set up my clock again." }
      Log 'Skipped updating (the clock still works on its current version).'
    }
    else {
      Log 'The clock is restarting...'
      Wait-Seconds 12
      if ($viaHotspot) {
        if (-not (Join-Open $SetupSsid 90)) { throw "The clock didn't come back after the update. Unplug it, plug it back in and press Set up again." }
      }
      $until = (Get-Date).AddSeconds(60)
      do { $info = Get-ClockInfo $base 5; if ($info) { break }; Wait-Seconds 2 } while ((Get-Date) -lt $until)
      if (-not $info) { throw "The clock didn't come back after the update. Unplug it, plug it back in and press Set up again." }
    }
  }
  Log 'Saving your Wi-Fi and account details on the clock...'
  $fields = @{ ssid = $ssidBox.Text.Trim(); pass = $passBox.Text; source = $(if ($rbBf.Checked) { 'brewfather' } else { 'rapt' });
               user = $userBox.Text.Trim(); secret = $keyBox.Text.Trim(); tz = (Get-PosixTimeZone) }
  $code = Http-PostForm "$base/save" $fields 20
  if ($code -ne 200) { throw "The clock didn't save the settings (code $code)." }
  Log 'Saved. The clock joins your Wi-Fi and shows your brew.' 'good'
}

function Explain-Problem($p) {
  if (-not $p) { return }
  Log "The clock says: $p" 'bad'
  if ($p -match 'wrong ID|wrong email') { Log 'Check the ID and key (use the help link above), then press Set up my clock again.' }
  elseif ($p -match 'Fermenting') { Log 'In Brewfather, set your batch''s status to Fermenting - the clock picks it up within 5 minutes.' }
}

function Run-Install {
  $logBox.Clear()
  $script:ClockMac = $null
  if (-not (Test-Path $LiteBin) -or -not (Test-Path $FullBin)) { Log 'The firmware files are missing. Unzip the whole folder and try again.' 'bad'; return }
  if (-not $ssidBox.Text.Trim()) { Log 'Type your home Wi-Fi name first.' 'bad'; return }
  if (-not $userBox.Text.Trim() -or -not $keyBox.Text.Trim()) { Log "Fill in your $($l1.Text) and $($l2.Text) first." 'bad'; return }

  $i = Get-Iface
  if (-not $i) { Log 'This PC has no Wi-Fi. Use a laptop or a PC with Wi-Fi.' 'bad'; return }
  $script:Iface = $i.Name
  $script:PrevProfile = if ($i.Connected) { $i.Profile } else { $null }

  try {
    Log 'Looking for your clock...' 'step'
    $nets = Get-Networks
    $homeNet = $nets | Where-Object { $_.Ssid -eq $ssidBox.Text.Trim() } | Select-Object -First 1
    if ($homeNet -and -not $homeNet.Has24) { Log "Heads up: '$($homeNet.Ssid)' looks like 5 GHz only. The clock needs 2.4 GHz Wi-Fi." 'bad' }

    $stockNet = $nets | Where-Object { $_.Ssid -match '(?i)^smart weather clock' } | Select-Object -First 1
    $setupNet = $nets | Where-Object { $_.Ssid -eq $SetupSsid -or $_.Ssid -eq $OldSetupSsid } | Select-Object -First 1
    $lan = $null
    if (-not $stockNet -and -not $setupNet) { Log 'Not broadcasting its own Wi-Fi - looking on your home network...'; $lan = Find-ClockOnLan }

    if (-not $stockNet -and -not $setupNet -and -not $lan) {
      Log "Can't find the clock." 'bad'
      Log 'Unplug the clock, count to 5, plug it back in, wait 30 seconds, then press Set up my clock again.'
      return
    }

    if ($stockNet) {
      Log "Found a new clock ('$($stockNet.Ssid)'). This PC will leave your Wi-Fi for a minute or two - that's normal." 'step'
      if (-not (Join-Open $stockNet.Ssid 45)) { throw "Couldn't connect to the clock's Wi-Fi." }
      $r = Http-Get "http://$ClockAP/" 8
      $title = if ($r) { Title-Of $r.Body } else { '' }
      if ($title -notmatch '(?i)smart weather clock|스마트') { throw "This doesn't look like a Geekmagic Smart Weather Clock (it says '$title')." }
      Flash-Stock "http://$ClockAP" $title
      Wait-Seconds 10
      Log 'Waiting for FermFrame to start...'
      if (Join-Open $SetupSsid 90) { Finish-Setup "http://$ClockAP" $true }
      else {
        [void](Restore-Wifi)
        Log 'Looking for it on your home network...'
        $lan = Find-ClockOnLan
        if (-not $lan -or $lan.Kind -ne 'fermframe') {
          throw "Can't see FermFrame starting. Unplug the clock, plug it back in, wait 30 seconds and press Set up my clock again."
        }
        Finish-Setup $lan.Base $false
      }
    }
    elseif ($setupNet) {
      Log 'Found a clock with FermFrame waiting to be set up. This PC will leave your Wi-Fi for a minute.' 'step'
      if (-not (Join-Open $setupNet.Ssid 45)) { throw "Couldn't connect to the clock's Wi-Fi." }
      Finish-Setup "http://$ClockAP" $true
    }
    elseif ($lan.Kind -eq 'stock') {
      Log "Found a new clock on your home network ($($lan.Base))." 'step'
      Flash-Stock $lan.Base $lan.Title
      Wait-Seconds 15
      Log 'Waiting for FermFrame to start...'
      $found = $null; $until = (Get-Date).AddSeconds(90)
      while (-not $found -and (Get-Date) -lt $until) {
        $nets = Get-Networks
        if ($nets | Where-Object { $_.Ssid -eq $SetupSsid }) { $found = 'hotspot' }
        else { $l = Find-ClockOnLan $script:ClockMac; if ($l -and $l.Kind -eq 'fermframe') { $found = $l } }
      }
      if ($found -eq 'hotspot') {
        if (-not (Join-Open $SetupSsid 45)) { throw "Couldn't connect to the clock's Wi-Fi." }
        Finish-Setup "http://$ClockAP" $true
      } elseif ($found) { Finish-Setup $found.Base $false }
      else { throw "Can't see FermFrame starting. Unplug the clock, plug it back in, wait 30 seconds and press Set up my clock again." }
    }
    else {
      $ask = [System.Windows.Forms.MessageBox]::Show("Found a clock that already has FermFrame at $($lan.Base) (ID $($lan.Info.mac)).`n`nIf you meant to set up a NEW clock, press No, then unplug the new clock, plug it back in and try again.`n`nUpdate this clock's settings?", 'FermFrame Installer', 'YesNo', 'Question')
      if ($ask -ne 'Yes') { Log 'Nothing changed. Unplug the new clock, plug it back in, wait 30 seconds and try again.'; return }
      Log "Updating FermFrame at $($lan.Base)." 'step'
      Finish-Setup $lan.Base $false
    }

    Log 'Putting this PC back on your Wi-Fi...'
    if (-not (Restore-Wifi)) { Log "This PC didn't get back on its Wi-Fi by itself - pick it from the Wi-Fi menu." 'bad' }

    Log 'Checking the clock on your home network...' 'step'
    $found = $null; $until = (Get-Date).AddSeconds(120)
    while (-not $found -and (Get-Date) -lt $until) { $found = Find-ClockOnLan $script:ClockMac; if (-not $found) { Wait-Seconds 5 } }
    if (-not $found -or $found.Kind -ne 'fermframe') {
      Log "The clock hasn't shown up on '$($ssidBox.Text.Trim())' yet." 'bad'
      Log 'If its screen says "Can''t find Wi-Fi", the Wi-Fi name or password was wrong: scan the code on the clock with your phone to fix it.'
      return
    }
    Log "The clock is on your Wi-Fi at $($found.Base)" 'good'
    Log 'Waiting for its first readings...'
    $until = (Get-Date).AddSeconds(60); $info = $found.Info
    while ((Get-Date) -lt $until) { Wait-Seconds 10; $j = Get-ClockInfo $found.Base 5; if ($j) { $info = $j; if ($j.problem) { break } } }
    if ($info.problem) { Explain-Problem $info.problem }
    else {
      Log 'All done! Your graph is on the clock.' 'good'
      Log "To change settings later: open http://fermframe.local or scan the code on the clock."
    }
  }
  catch {
    $msg = $_.Exception.Message
    if ($msg -eq 'LOCATION') {
      Log 'Windows needs Location turned on so this program can see Wi-Fi networks.' 'bad'
      Log 'Turn on "Location services" and "Let desktop apps access your location", then press Set up my clock again.'
      Start-Process 'ms-settings:privacy-location'
    } else { Log $msg 'bad' }
    if ($script:PrevProfile) { [void](Restore-Wifi) }
  }
}

$go.Add_Click({
  $go.Enabled = $false; $go.Text = 'Working... please wait'
  try { Run-Install } finally { $go.Enabled = $true; $go.Text = 'Set up my clock' }
})

$ssidBox.Add_SelectedIndexChanged({ $p = Saved-WifiPassword $ssidBox.Text; if ($p) { $passBox.Text = $p } })

$form.Add_Shown({
  $i = Get-Iface
  if ($i -and $i.Connected) {
    $ssidBox.Text = $i.Ssid
    $p = Saved-WifiPassword $i.Profile
    if ($p) { $passBox.Text = $p }
  }
  try {
    foreach ($n in (& netsh.exe wlan show profiles 2>$null | Select-String 'All User Profile\s*:\s*(.+)$')) {
      $name = $n.Matches[0].Groups[1].Value.Trim()
      if ($name -notlike "$TmpPrefix*" -and -not $ssidBox.Items.Contains($name)) { [void]$ssidBox.Items.Add($name) }
    }
  } catch {}
  if (-not $i) { Log 'This PC has no Wi-Fi. Use a laptop or a PC with Wi-Fi.' 'bad' }
})

$form.Add_FormClosing({ if ($script:MadeProfiles.Count) { [void](Restore-Wifi) } })
[void]$form.ShowDialog()
