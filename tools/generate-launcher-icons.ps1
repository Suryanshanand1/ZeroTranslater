<#
.SYNOPSIS
    Generates the legacy (pre-API-26) launcher icon PNGs.

.DESCRIPTION
    minSdk is 24, but adaptive icons only exist from API 26. API 24 and 25 need
    real bitmap mipmaps, and this project has no Android Studio to produce them.
    This script renders the same design as res/drawable/ic_launcher_*.xml:
    an indigo gradient tile with a white "Z" glyph.

    Everything is drawn at 4x and downsampled with HighQualityBicubic, which
    gives cleaner edges than drawing at 1x with antialiasing.

    Run from the repository root:
        powershell -ExecutionPolicy Bypass -File tools\generate-launcher-icons.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$resDir = Join-Path $PSScriptRoot '..\app\src\main\res'
$superSample = 4

# Launcher icon densities: directory suffix -> edge length in px.
$densities = [ordered]@{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

# The "Z" glyph, expressed in the same 108x108 space as the adaptive-icon
# vector so both renditions stay visually identical.
$glyph = @(
    @(28, 34), @(80, 34), @(80, 43), @(46, 65), @(80, 65),
    @(80, 74), @(28, 74), @(28, 65), @(62, 43), @(28, 43)
)

function New-RoundedRectPath([System.Drawing.RectangleF]$rect, [float]$radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 180, 90)
    $path.CloseFigure()
    return $path
}

function New-CirclePath([System.Drawing.RectangleF]$rect) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse($rect)
    return $path
}

function New-Tile([int]$size, [bool]$round) {
    $big = $size * $superSample
    $bmp = New-Object System.Drawing.Bitmap($big, $big)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    # Gradient tile background.
    $full = New-Object System.Drawing.RectangleF(0, 0, $big, $big)
    $bgPath = if ($round) { New-CirclePath $full } else { New-RoundedRectPath $full ($big * 0.22) }

    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF(0, 0)),
        (New-Object System.Drawing.PointF($big, $big)),
        [System.Drawing.Color]::FromArgb(255, 0x63, 0x66, 0xF1),
        [System.Drawing.Color]::FromArgb(255, 0x43, 0x38, 0xCA)
    )
    $g.FillPath($brush, $bgPath)

    # White "Z", scaled from 108-space into the tile.
    $scale = $big / 108.0
    $pts = [System.Drawing.PointF[]]@(
        $glyph | ForEach-Object {
            New-Object System.Drawing.PointF([float]($_[0] * $scale), [float]($_[1] * $scale))
        }
    )
    $g.FillPolygon([System.Drawing.Brushes]::White, $pts)

    $g.Dispose()
    $brush.Dispose()
    $bgPath.Dispose()

    # Downsample to the target density.
    $out = New-Object System.Drawing.Bitmap($size, $size)
    $g2 = [System.Drawing.Graphics]::FromImage($out)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g2.Clear([System.Drawing.Color]::Transparent)
    $g2.DrawImage($bmp, 0, 0, $size, $size)
    $g2.Dispose()
    $bmp.Dispose()
    return $out
}

foreach ($dir in $densities.Keys) {
    $size = $densities[$dir]
    $target = Join-Path $resDir $dir
    if (-not (Test-Path $target)) { New-Item -ItemType Directory -Path $target | Out-Null }

    foreach ($variant in @(@('ic_launcher', $false), @('ic_launcher_round', $true))) {
        $name = $variant[0]
        $isRound = [bool]$variant[1]
        $path = Join-Path $target "$name.png"
        $bmp = New-Tile $size $isRound
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        Write-Host ("{0,-22} {1,3}px  {2}" -f $name, $size, $path)
    }
}

Write-Host "`nLauncher icon PNGs generated."
