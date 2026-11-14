# Takes every screenshot used in docs/MANUAL.md, from the app in demo mode (made-up FermFrames only).
#   powershell -NoProfile -STA -ExecutionPolicy Bypass -File manual-shots.ps1
# Leave the mouse and keyboard alone while it runs (about 2 minutes).

. (Join-Path $PSScriptRoot 'drive.ps1')
$out = Join-Path (Split-Path -Parent $PSScriptRoot) 'manual\images'
New-Item -ItemType Directory -Force $out | Out-Null
function Shot($p, $title, $name) { Snap $p $title (Join-Path $out "$name.png"); Write-Host "  $name.png" }

$bg = Show-Backdrop 0 0 1600 1100
try {
  # First run: the install window
  $p = Start-FF @('/installer')
  Move-Mouse 1500 1000 200; Shot $p 'FermFrame' 'install'
  Stop-FF $p

  # The main window
  $p = Start-FF
  Move-Mouse 1500 1000 200; Pump 3
  Shot $p 'FermFrame' 'overview'
  Click-ListItem $p 1; Move-Mouse 1500 1000 200; Pump 1.5; Shot $p 'FermFrame' 'overview-ready'
  Click-ListItem $p 2; Move-Mouse 1500 1000 200; Pump 1; Shot $p 'FermFrame' 'overview-offline'
  Click-ListItem $p 0; Pump 1.5

  # Rename
  Click-Name $p 'Rename'; Pump 0.8; Shot $p 'Rename FermFrame' 'rename'; Press '{ESC}'

  # Settings, top to bottom
  Click-Name $p 'Settings'; Move-Mouse 900 500 300; Pump 2.5
  Shot $p 'FermFrame' 'settings-1'
  Scroll -5; Pump 0.6; Shot $p 'FermFrame' 'settings-2'; Scroll -5; Pump 0.6; Shot $p 'FermFrame' 'settings-3'; Scroll -10; Pump 0.6; Shot $p 'FermFrame' 'settings-4'
  Click-Name $p 'Save to FermFrame'; Pump 1.5; Move-Mouse 1500 1000 200; Shot $p 'FermFrame' 'settings-saved'

  # Firmware
  Click-Name $p 'Firmware'; Move-Mouse 1500 1000 200; Pump 0.8; Shot $p 'FermFrame' 'firmware'
  Click-Name $p 'Reinstall' -Partial; Pump 1.2; Move-Mouse 1500 1000 200; Shot $p 'FermFrame' 'firmware-updating'
  Pump 16; Shot $p 'FermFrame' 'firmware-done'

  # Buy another FermFrame
  Click-Name $p 'Buy another FermFrame'; Pump 2.5; Move-Mouse 1500 1000 200; Shot $p 'Get another FermFrame' 'buy'
  Click-Name $p 'Close'; Pump 0.8

  # Set up a new FermFrame (demo: nothing real happens)
  Click-Name $p 'Set up a new FermFrame' -Partial; Pump 1.5; Move-Mouse 1500 1000 200; Shot $p 'Set up a new FermFrame' 'setup'
  Click-El (Find-El $p 'Brewfather user ID') 400 0 30; Type-Text 'aB3dE5fG7hJ9kL1mN3pQ5rS7tU9v' 10
  Click-El (Find-El $p 'Brewfather API key') 400 0 30; Type-Text 'demo-api-key-123' 10
  Click-Name $p 'Set up my FermFrame'; Pump 32; Move-Mouse 1500 1000 200; Shot $p 'Set up a new FermFrame' 'setup-done'
  Press '%{F4}'; Pump 1

  # About
  Click-Name $p 'About FermFrame' -Partial; Pump 1; Move-Mouse 1500 1000 200; Shot $p 'About FermFrame' 'about'
  Press '%{F4}'; Pump 0.5
  Stop-FF $p
}
finally { $bg.Close() }
Write-Host "Screenshots in $out"
