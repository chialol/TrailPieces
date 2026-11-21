# Turn one cut scene folder into app assets.
# Runs prep_reveal, then import_catalog when the photo id is already on a trail.
# Does not edit trails.json and does not build the app.
# Usage, from anywhere: .\tools\finish_reveal.ps1 <photo-id>

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Scene
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

function Fail([string]$Message) {
    Write-Host $Message
    exit 1
}

function Test-OnTrail([string]$PhotoId) {
    $trailsPath = Join-Path $root "shared\source\catalog\trails.json"
    if (-not (Test-Path -LiteralPath $trailsPath -PathType Leaf)) {
        Fail "Missing shared/source/catalog/trails.json."
    }
    try {
        $doc = Get-Content -LiteralPath $trailsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Fail "shared/source/catalog/trails.json is not valid JSON."
    }
    foreach ($trail in @($doc.trails)) {
        foreach ($photoId in @($trail.photoIds)) {
            if ("$photoId" -eq $PhotoId) {
                return $true
            }
        }
    }
    return $false
}

function Find-ByStem([string]$Directory, [string[]]$Stems) {
    foreach ($stem in $Stems) {
        foreach ($ext in @(".jpg", ".jpeg", ".png", ".webp")) {
            $candidate = Join-Path $Directory ($stem + $ext)
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return $candidate
            }
        }
    }
    return $null
}

$sceneId = Split-Path -Leaf $Scene
if ($sceneId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]*$') {
    Fail "Pass the scene folder name, like mossyrock. The folder is shared/source/reveal/<id>."
}

$folder = Join-Path $root "shared\source\reveal\$sceneId"
$sibling = Join-Path $root "shared\source\reveal\$sceneId-pieces"
$unzipHint = "Unzip the tracer zip into shared/source/reveal/$sceneId so pieces/ and meta.json sit next to photo.jpg."
if (Test-Path -LiteralPath $sibling -PathType Container) {
    $unzipHint = "Found shared/source/reveal/$sceneId-pieces. Move its pieces/ folder and meta.json into shared/source/reveal/$sceneId, next to photo.jpg."
}

if (-not (Test-Path -LiteralPath $folder -PathType Container)) {
    Fail "No folder shared/source/reveal/$sceneId. Put photo.jpg there. $unzipHint"
}

$color = Find-ByStem $folder @("photo", $sceneId, "color")
if (-not $color) {
    Fail "No color photo in shared/source/reveal/$sceneId. Expected photo.jpg."
}

$piecesDir = Join-Path $folder "pieces"
$pieceExt = @(".png", ".jpg", ".jpeg", ".webp")
$pieces = @()
if (Test-Path -LiteralPath $piecesDir -PathType Container) {
    $pieces = @(Get-ChildItem -LiteralPath $piecesDir -File | Where-Object {
        $pieceExt -contains $_.Extension.ToLowerInvariant() -and $_.BaseName -match '^\d+(?:-.+)?$'
    })
}
if ($pieces.Count -eq 0) {
    Fail "No pieces in shared/source/reveal/$sceneId/pieces. 01.png is enough. $unzipHint"
}

$colorStem = [System.IO.Path]::GetFileNameWithoutExtension($color)
$plate = Find-ByStem $folder @("$colorStem-bw", "$sceneId-bw", "bw")
if (-not $plate) {
    Fail "No dark plate in shared/source/reveal/$sceneId. Add photo-bw.jpg next to photo.jpg (same pixel size), then run this again."
}

$title = $null
$metaPath = Join-Path $folder "meta.json"
if (Test-Path -LiteralPath $metaPath -PathType Leaf) {
    try {
        $meta = Get-Content -LiteralPath $metaPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Fail "shared/source/reveal/$sceneId/meta.json is not valid JSON."
    }
    if ($meta.title -and "$($meta.title)".Trim()) {
        $title = "$($meta.title)".Trim()
    }
}

$python = Join-Path $root "tools\chop_puzzle\.venv\Scripts\python.exe"
$prep = Join-Path $root "tools\prep_reveal\prep_reveal.py"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    Fail "Missing $python`nFrom the repo:`npython -m venv tools\chop_puzzle\.venv`ntools\chop_puzzle\.venv\Scripts\pip install -r tools\prep_layers\requirements.txt"
}

$prepArgs = @($prep, $sceneId)
if ($title) { $prepArgs += @("--title", $title) }
& $python @prepArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
if (Test-OnTrail $sceneId) {
    $import = Join-Path $root "tools\import_catalog\import_catalog.py"
    & $python $import
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "Install from Android Studio and open Walk a trail."
} else {
    Write-Host "$sceneId will not appear in Walk a trail until its id is added to a trail photoIds list and finish_reveal (or import_catalog) is run again."
}
