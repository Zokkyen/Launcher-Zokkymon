param(
    [string]$MainBranch = "main",
    [string]$BetaBranch = "beta",
    [string]$Remote = "origin",
    [string]$ReadmePath = "README.md",
    [string]$CommitMessage = "release: merge beta into main (keep main README)",
    [switch]$AllowDirty,
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args
    )

    Write-Host "> git $($Args -join ' ')" -ForegroundColor Cyan
    & git @Args
    if ($LASTEXITCODE -ne 0) {
        throw "Git command failed: git $($Args -join ' ')"
    }
}

function Get-GitOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args
    )

    $output = & git @Args 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Git command failed: git $($Args -join ' ')"
    }
    return $output
}

$repoCheck = & git rev-parse --is-inside-work-tree 2>$null
if ($LASTEXITCODE -ne 0 -or "$repoCheck".Trim() -ne "true") {
    throw "Ce script doit être exécuté dans un dépôt Git."
}

if (-not $AllowDirty) {
    $dirty = Get-GitOutput -Args @("status", "--porcelain")
    if ($dirty) {
        throw "Working tree non propre. Commit/stash d'abord, ou relance avec -AllowDirty."
    }
}

Invoke-Git -Args @("fetch", $Remote)
Invoke-Git -Args @("checkout", $MainBranch)
Invoke-Git -Args @("pull", "--ff-only", $Remote, $MainBranch)

$mergeSucceeded = $true
try {
    Invoke-Git -Args @("merge", "--no-ff", "--no-commit", $BetaBranch)
}
catch {
    $mergeSucceeded = $false
}

if (-not $mergeSucceeded) {
    Write-Warning "Conflits détectés pendant le merge."
}

Invoke-Git -Args @("checkout", "--ours", "--", $ReadmePath)
Invoke-Git -Args @("add", $ReadmePath)

$unmerged = Get-GitOutput -Args @("diff", "--name-only", "--diff-filter=U")
if ($unmerged) {
    Write-Host "Fichiers encore en conflit:" -ForegroundColor Yellow
    $unmerged | ForEach-Object { Write-Host " - $_" -ForegroundColor Yellow }
    throw "Résous les conflits restants, puis commit/push manuellement."
}

$pending = Get-GitOutput -Args @("status", "--porcelain")
if (-not $pending) {
    Write-Host "Aucun changement à commit après merge." -ForegroundColor Green
}
else {
    Invoke-Git -Args @("commit", "-m", $CommitMessage)
}

if (-not $NoPush) {
    Invoke-Git -Args @("push", $Remote, $MainBranch)
    Write-Host "Merge $BetaBranch -> $MainBranch terminé et poussé." -ForegroundColor Green
}
else {
    Write-Host "Merge terminé localement (push désactivé avec -NoPush)." -ForegroundColor Green
}
