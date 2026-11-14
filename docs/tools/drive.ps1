# Drives the FermFrame app like a person would (real mouse moves, clicks, scrolling and typing),
# for making the manual's screenshots and the video. Dot-source it:  . .\drive.ps1
# Always run the app with /demo so only made-up FermFrames appear.

Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms, System.Drawing
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class FFN {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, int data, IntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);
}
'@
$script:FFExe = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'FermFrame.exe'
if (-not (Test-Path $script:FFExe)) { $script:FFExe = Join-Path (Split-Path -Parent $PSScriptRoot) '..\FermFrame.exe' }
$AE = [System.Windows.Automation.AutomationElement]
$TS = [System.Windows.Automation.TreeScope]

function Pump([double]$s) { $u = (Get-Date).AddSeconds($s); while ((Get-Date) -lt $u) { [System.Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 15 } }

# A plain dark background behind the app, so screenshots and video show nothing else on this PC
function Show-Backdrop([int]$x = 0, [int]$y = 0, [int]$w = 1920, [int]$h = 1080, [string]$title = '', [string]$sub = '') {
  $f = New-Object System.Windows.Forms.Form
  $f.FormBorderStyle = 'None'; $f.StartPosition = 'Manual'; $f.ShowInTaskbar = $false
  $f.Bounds = New-Object System.Drawing.Rectangle $x, $y, $w, $h
  $f.BackColor = [System.Drawing.Color]::FromArgb(14, 14, 14)
  $f.Tag = @($title, $sub)
  $f.Add_Paint({ param($s, $e)
    $t = $s.Tag; if (-not $t[0]) { return }
    $g = $e.Graphics; $g.TextRenderingHint = 'AntiAliasGridFit'
    $c = New-Object System.Drawing.StringFormat; $c.Alignment = 'Center'; $c.LineAlignment = 'Center'
    $r1 = New-Object System.Drawing.RectangleF 0, ($s.Height * 0.36), $s.Width, 120
    $r2 = New-Object System.Drawing.RectangleF 0, ($s.Height * 0.36 + 110), $s.Width, 60
    $g.DrawString($t[0], (New-Object System.Drawing.Font 'Segoe UI', 64, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)), (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 204, 51))), $r1, $c)
    $g.DrawString($t[1], (New-Object System.Drawing.Font 'Segoe UI', 28, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)), (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(200, 200, 200))), $r2, $c)
  })
  $f.Show(); Pump 0.3
  return $f
}

function Set-BackdropText($f, [string]$title, [string]$sub) { $f.Tag = @($title, $sub); $f.Invalidate(); Pump 0.1 }

# Starts the app (always in demo mode) and places its main window
function Start-FF([string[]]$extra = @(), [double]$scale = 1.0, [int]$x = 60, [int]$y = 40) {
  $env:FERMFRAME_SCALE = "$scale"
  $p = Start-Process $script:FFExe -ArgumentList (@('/demo') + $extra) -PassThru
  for ($i = 0; $i -lt 80 -and $p.MainWindowHandle -eq 0; $i++) { Pump 0.25; $p.Refresh() }
  [void][FFN]::SetWindowPos($p.MainWindowHandle, [IntPtr]::Zero, $x, $y, 0, 0, 0x0001 -bor 0x0004)
  [void][FFN]::SetForegroundWindow($p.MainWindowHandle)
  Pump 1
  return $p
}

# The app's windows, including dialogs (which Windows lists inside the window that opened them)
function Get-Windows($p) {
  $cond = New-Object System.Windows.Automation.PropertyCondition ($AE::ProcessIdProperty), $p.Id
  $isWin = New-Object System.Windows.Automation.PropertyCondition ($AE::ControlTypeProperty), ([System.Windows.Automation.ControlType]::Window)
  $list = @()
  foreach ($top in $AE::RootElement.FindAll($TS::Children, $cond)) {
    foreach ($d in $top.FindAll($TS::Descendants, $isWin)) { $list += $d }   # dialogs first: they're on top
    $list += $top
  }
  return $list
}

# Finds a control by its visible text (exact, or -Partial), in any of the app's windows
function Find-El($p, [string]$name, [switch]$Partial, [int]$timeout = 8, [string]$Type = '') {
  $until = (Get-Date).AddSeconds($timeout)
  do {
    foreach ($w in Get-Windows $p) {
      foreach ($el in $w.FindAll($TS::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
        try {
          $n = $el.Current.Name
          if ($Type -and $el.Current.ControlType.ProgrammaticName -ne "ControlType.$Type") { continue }
          if (($Partial -and $n -like "*$name*") -or (-not $Partial -and $n -eq $name)) {
            if (-not $el.Current.IsOffscreen -and $el.Current.BoundingRectangle.Width -gt 0) { return $el }
          }
        } catch {}
      }
    }
    Pump 0.2
  } while ((Get-Date) -lt $until)
  throw "Couldn't find '$name' in the app"
}

function Get-Center($el) { $r = $el.Current.BoundingRectangle; return @([int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2)) }

# Glides the mouse to x,y over ms milliseconds (eased, like a hand)
function Move-Mouse([int]$x, [int]$y, [int]$ms = 600) {
  $from = [System.Windows.Forms.Cursor]::Position
  $steps = [Math]::Max(1, [int]($ms / 15))
  for ($i = 1; $i -le $steps; $i++) {
    $t = $i / $steps; $e = $t * $t * (3 - 2 * $t)
    [void][FFN]::SetCursorPos([int]($from.X + ($x - $from.X) * $e), [int]($from.Y + ($y - $from.Y) * $e))
    Pump 0.015
  }
}

function Drag([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms = 900) {
  Move-Mouse $x1 $y1 500; Pump 0.1
  [FFN]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero); Pump 0.1
  Move-Mouse $x2 $y2 $ms; Pump 0.1
  [FFN]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero); Pump 0.3
}

# Moves a window so it sits in the middle of the given area
function Center-Window([IntPtr]$h, [int]$w = 1920, [int]$hh = 1080, [int]$top = -1) {
  $b = Get-Bounds $h
  $y = if ($top -ge 0) { $top } else { [int](($hh - $b.Height) / 2) }
  [void][FFN]::SetWindowPos($h, [IntPtr]::Zero, [int](($w - $b.Width) / 2), $y, 0, 0, 0x0001 -bor 0x0004)
  Pump 0.3
}

function Click-At([int]$x, [int]$y, [int]$ms = 600) {
  Move-Mouse $x $y $ms; Pump 0.15
  [FFN]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero); Pump 0.08; [FFN]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
  Pump 0.3
}

function Click-El($el, [int]$ms = 600, [int]$dx = 0, [int]$dy = 0) { $c = Get-Center $el; Click-At ($c[0] + $dx) ($c[1] + $dy) $ms }

function Click-Name($p, [string]$name, [switch]$Partial, [int]$ms = 600, [string]$Type = '') { Click-El (Find-El $p $name -Partial:$Partial -Type $Type) $ms }

# Mouse wheel: negative = scroll down
function Scroll([int]$notches, [int]$pause = 60) {
  $dir = [Math]::Sign($notches)
  for ($i = 0; $i -lt [Math]::Abs($notches); $i++) { [FFN]::mouse_event(0x0800, 0, 0, 120 * $dir, [IntPtr]::Zero); Pump ($pause / 1000) }
  Pump 0.3
}

# Types like a person (SendKeys special characters escaped)
function Type-Text([string]$s, [int]$msPerChar = 70) {
  foreach ($ch in $s.ToCharArray()) {
    $k = [string]$ch; if ('+^%~(){}[]'.Contains($k)) { $k = '{' + $k + '}' }
    [System.Windows.Forms.SendKeys]::SendWait($k); Pump ($msPerChar / 1000)
  }
}

function Press([string]$keys) { [System.Windows.Forms.SendKeys]::SendWait($keys); Pump 0.3 }

# Exact window bounds (no invisible borders) of an element's window or a window handle
function Get-Bounds($hwnd) {
  $r = New-Object FFN+RECT
  if ([FFN]::DwmGetWindowAttribute($hwnd, 9, [ref]$r, 16) -ne 0) { [void][FFN]::GetWindowRect($hwnd, [ref]$r) }
  return New-Object System.Drawing.Rectangle $r.L, $r.T, ($r.R - $r.L), ($r.B - $r.T)
}

# Screenshot of one of the app's windows (by title) to a PNG
function Snap($p, [string]$title, [string]$file, [System.Drawing.Rectangle]$crop = [System.Drawing.Rectangle]::Empty) {
  $w = Get-Windows $p | Where-Object { $_.Current.Name -eq $title } | Select-Object -First 1
  if (-not $w) { throw "No window called '$title'" }
  $b = Get-Bounds ([IntPtr]$w.Current.NativeWindowHandle)
  if (-not $crop.IsEmpty) { $b = New-Object System.Drawing.Rectangle ($b.X + $crop.X), ($b.Y + $crop.Y), $crop.Width, $crop.Height }
  $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
  $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size); $g.Dispose()
  $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}

# The FermFrame list is custom-drawn (invisible to automation): click entry $i (0 = first) by position
function Click-ListItem($p, [int]$i, [int]$ms = 600) {
  $s = [double]$env:FERMFRAME_SCALE; if (-not $s) { $s = 1 }
  $b = Get-Bounds $p.MainWindowHandle
  Click-At ([int]($b.X + 150 * $s)) ([int]($b.Y + 31 + (127 + 70 * $i) * $s)) $ms
}

function Stop-FF($p) { try { if (-not $p.HasExited) { [void]$p.CloseMainWindow(); if (-not $p.WaitForExit(3000)) { $p.Kill() } } } catch {} }
