# Short commands for prep and publish. Run from the repo root:
#   .\make prep move assets/mianyue taiwan
#   .\make publish validate taiwan
#   .\make publish approve taiwan
#   .\make publish publish taiwan --dry-run
#   .\make publish publish taiwan
# A bare package name is assets/<name>. A path is used as given.

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

$python = Join-Path $PSScriptRoot "tools\publish\.venv\Scripts\python.exe"

function Show-Usage {
    Write-Host @"
Usage:
  .\make prep move <photo-folder> <package>
  .\make publish validate <package>
  .\make publish approve <package>
  .\make publish publish <package> [--dry-run]
  .\make publish list

<package> is a name under assets/, such as taiwan. Pass a path to use another folder.
"@
}

if (-not (Test-Path -LiteralPath $python)) {
    Write-Error "Missing $python. Create it with: python -m venv tools\publish\.venv"
    exit 1
}

if ($args.Count -lt 1) {
    Show-Usage
    exit 1
}

$group = $args[0]
$rest = @()
if ($args.Count -gt 1) {
    $rest = @($args[1..($args.Count - 1)])
}

switch ($group) {
    "prep" {
        if ($rest.Count -lt 1) {
            Show-Usage
            exit 1
        }
        & $python (Join-Path $PSScriptRoot "tools\publish\prep_asset.py") @rest
        exit $LASTEXITCODE
    }
    "publish" {
        if ($rest.Count -lt 1) {
            Show-Usage
            exit 1
        }
        $command = $rest[0]
        $tail = @()
        if ($rest.Count -gt 1) {
            $tail = @($rest[1..($rest.Count - 1)])
        }
        $folderCommands = @("prepare", "validate", "approve", "import-review", "publish")
        if ($folderCommands -contains $command -and $tail.Count -ge 1) {
            $folder = [string]$tail[0]
            $isPath = $folder -match '[\\/]' -or $folder -match '^[A-Za-z]:'
            if (-not $folder.StartsWith("-") -and -not $isPath) {
                $tail[0] = Join-Path "assets" $folder
            }
        }
        & $python (Join-Path $PSScriptRoot "tools\publish\publish.py") $command @tail
        exit $LASTEXITCODE
    }
    default {
        Show-Usage
        exit 1
    }
}
