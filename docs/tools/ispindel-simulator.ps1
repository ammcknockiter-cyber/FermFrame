# Pretends to be an iSpindel / GravityMon, for testing a FermFrame's "My own hydrometer" without one.
#
#   powershell -ExecutionPolicy Bypass -File docs\tools\ispindel-simulator.ps1 -FermFrame 192.168.1.90
#
# By default it sends 7 days of made-up fermentation (one reading every 30 minutes, dated in the past using
# the optional "time" field), then one reading for "now". -Live keeps sending a reading every -Every seconds.
# -Plato sends gravity in degrees Plato (like an iSpindel set up that way), -Fahrenheit sends °F.

param(
  [Parameter(Mandatory = $true)][string]$FermFrame,
  [int]$Days = 7,
  [double]$Og = 1.052,
  [double]$Fg = 1.011,
  [string]$Name = "iSpindel-Sim",
  [switch]$Plato,
  [switch]$Fahrenheit,
  [switch]$Live,
  [int]$Every = 60
)

$url = "http://$FermFrame/ispindel"
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

function Reading([long]$t, [double]$hours) {
  # S-shaped drop from OG to FG, temperature wobbling around 19 °C
  $sg = $Fg + ($Og - $Fg) / (1 + [Math]::Exp(($hours - 48) / 14))
  $tc = 19 + 0.8 * [Math]::Sin($hours / 7) + (Get-Random -Minimum -10 -Maximum 10) / 100
  $g = if ($Plato) { $p = -616.868 + 1111.14 * $sg - 630.272 * $sg * $sg + 135.997 * $sg * $sg * $sg; [Math]::Round($p, 2) } else { [Math]::Round($sg, 4) }
  $temp = if ($Fahrenheit) { [Math]::Round($tc * 9 / 5 + 32, 2) } else { [Math]::Round($tc, 2) }
  $body = [ordered]@{
    name = $Name; ID = 1234567; token = ""; angle = [Math]::Round(25 + ($sg - 1) * 600, 2)
    temperature = $temp; temp_units = $(if ($Fahrenheit) { "F" } else { "C" }); battery = 3.95
    gravity = $g; interval = 900; RSSI = -62
  }
  if ($t -gt 0) { $body.time = $t }
  $json = $body | ConvertTo-Json -Compress
  try { Invoke-RestMethod -Uri $url -Method Post -Body $json -ContentType "application/json" -TimeoutSec 10 | Out-Null; return $true }
  catch { Write-Warning "Not sent: $($_.Exception.Message)"; return $false }
}

$n = 0
if ($Days -gt 0) {
  $start = $now - $Days * 86400
  for ($t = $start; $t -lt $now - 1800; $t += 1800) {
    if (Reading $t (($t - $start) / 3600.0)) { $n++ }
  }
}
if (Reading 0 ($Days * 24)) { $n++ }
"Sent $n readings to $url"

if ($Live) {
  $h = $Days * 24
  while ($true) { Start-Sleep $Every; $h += $Every / 3600.0; if (Reading 0 $h) { "sent $(Get-Date -Format HH:mm:ss)" } }
}
