# Builds the platform icon files from src/main/resources/logo.png.
#
# jpackage wants a .ico on Windows and a .icns on macOS, and neither can be a plain PNG. Both are
# simple containers around PNG data, so this writes them by hand rather than pulling in a toolchain
# that only exists on one of the two platforms — the mac icon has to be produced on Windows, because
# that is where the repo is edited.
#
# Run it only when the logo changes; the generated icons are committed.
#
#   powershell -ExecutionPolicy Bypass -File tools\make-icons.ps1

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "src\main\resources\icons"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# The dedicated square icon art if there is any, otherwise the wide in-app logo. They are different
# pictures on purpose: the logo is a wordmark sized for a header, and a wordmark shrunk into a 32px
# taskbar icon is an unreadable smudge.
$src = Join-Path $outDir "icon-source.png"
if (-not (Test-Path $src)) {
    $src = Join-Path $root "src\main\resources\logo.png"
}
Write-Host "source: $src"

$logo = [System.Drawing.Image]::FromFile($src)

# Renders the source centred on a square transparent canvas, scaled to fit by its LONGER side.
#
# Fitting by the longer side is what lets one generator handle both shapes: a square source fills
# the canvas, and a wide wordmark fits its width and sits as a band in the middle rather than being
# cropped. The 8% margin is for macOS, which draws no frame of its own and expects the art to stop
# short of the edge.
function New-Square([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $target = $size * 0.92
    $scale = [math]::Min($target / $logo.Width, $target / $logo.Height)
    $w = $logo.Width * $scale
    $h = $logo.Height * $scale
    $g.DrawImage($logo, [float](($size - $w) / 2), [float](($size - $h) / 2), [float]$w, [float]$h)
    $g.Dispose()
    return $bmp
}

function Get-PngBytes([System.Drawing.Bitmap]$bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $ms.ToArray()
    $ms.Dispose()
    return ,$bytes
}

function Write-BE32([System.IO.Stream]$s, [uint32]$v) {
    $s.WriteByte([byte](($v -shr 24) -band 0xFF))
    $s.WriteByte([byte](($v -shr 16) -band 0xFF))
    $s.WriteByte([byte](($v -shr 8) -band 0xFF))
    $s.WriteByte([byte]($v -band 0xFF))
}

# --- app.ico -----------------------------------------------------------------
# ICONDIR + one ICONDIRENTRY per size + the PNG payloads. Windows has accepted PNG-compressed ICO
# entries since Vista, so every size is stored as PNG rather than as a BMP with an AND mask.
$icoSizes = @(16, 32, 48, 64, 128, 256)
$icoImages = @()
foreach ($s in $icoSizes) {
    $bmp = New-Square $s
    $icoImages += ,(Get-PngBytes $bmp)
    $bmp.Dispose()
}

$icoPath = Join-Path $outDir "app.ico"
$fs = [System.IO.File]::Create($icoPath)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([uint16]0)                       # reserved
$bw.Write([uint16]1)                       # type: 1 = icon
$bw.Write([uint16]$icoSizes.Count)

$offset = 6 + (16 * $icoSizes.Count)
for ($i = 0; $i -lt $icoSizes.Count; $i++) {
    $size = $icoSizes[$i]
    # 256 is stored as 0 — the field is a single byte, so 256 does not fit.
    $dim = if ($size -eq 256) { 0 } else { $size }
    $bw.Write([byte]$dim)                  # width
    $bw.Write([byte]$dim)                  # height
    $bw.Write([byte]0)                     # palette size (0 = truecolour)
    $bw.Write([byte]0)                     # reserved
    $bw.Write([uint16]1)                   # colour planes
    $bw.Write([uint16]32)                  # bits per pixel
    $bw.Write([uint32]$icoImages[$i].Length)
    $bw.Write([uint32]$offset)
    $offset += $icoImages[$i].Length
}
foreach ($img in $icoImages) { $bw.Write($img) }
$bw.Flush(); $bw.Dispose(); $fs.Dispose()
Write-Host "wrote $icoPath ($((Get-Item $icoPath).Length) bytes)"

# --- app.icns ----------------------------------------------------------------
# 'icns' magic, total length, then typed chunks. The OSTypes below are the PNG-based ones, which is
# what every macOS since 10.7 reads; the older mask-plus-data types are not needed.
$icnsTypes = @(
    @{ Type = "ic07"; Size = 128 },
    @{ Type = "ic08"; Size = 256 },
    @{ Type = "ic09"; Size = 512 },
    @{ Type = "ic10"; Size = 1024 }
)
$chunks = @()
foreach ($t in $icnsTypes) {
    $bmp = New-Square $t.Size
    $chunks += ,@{ Type = $t.Type; Data = (Get-PngBytes $bmp) }
    $bmp.Dispose()
}

$total = 8
foreach ($c in $chunks) { $total += 8 + $c.Data.Length }

$icnsPath = Join-Path $outDir "app.icns"
$out = [System.IO.File]::Create($icnsPath)
$out.Write([System.Text.Encoding]::ASCII.GetBytes("icns"), 0, 4)
Write-BE32 $out ([uint32]$total)
foreach ($c in $chunks) {
    $out.Write([System.Text.Encoding]::ASCII.GetBytes($c.Type), 0, 4)
    Write-BE32 $out ([uint32](8 + $c.Data.Length))
    $out.Write($c.Data, 0, $c.Data.Length)
}
$out.Dispose()
Write-Host "wrote $icnsPath ($((Get-Item $icnsPath).Length) bytes)"

$logo.Dispose()
