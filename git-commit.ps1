param (
    [Parameter(Mandatory=$true, Position=0)]
    [string]$FeatureName,
    
    [Parameter(Mandatory=$false)]
    [switch]$Push
)

# 1. Check Git Repository
if (-not (Test-Path '.git')) {
    Write-Host 'No Git repository detected. Initializing Git...' -ForegroundColor Yellow
    git init
    git branch -M main
}

# 2. Check for changes
$status = git status --porcelain
if ([string]::IsNullOrEmpty($status)) {
    Write-Host 'No changes to commit. Working tree clean.' -ForegroundColor Cyan
    exit
}

# 3. Commit changes
git add -A
$commitMsg = 'Modify ' + $FeatureName + ' before'
git commit -m $commitMsg

Write-Host 'Local backup completed successfully!' -ForegroundColor Green
Write-Host "Commit message: $commitMsg" -ForegroundColor Green

# 4. Push if requested
if ($Push) {
    $remotes = git remote
    if ([string]::IsNullOrEmpty($remotes)) {
        Write-Host 'Warning: No remote repository configured. Cannot push.' -ForegroundColor Yellow
        Write-Host 'Please run: git remote add origin <your-repo-url>' -ForegroundColor Yellow
    } else {
        Write-Host 'Pushing to remote repository...' -ForegroundColor Cyan
        git push -u origin main
    }
}
