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
        # 动态获取当前分支名称，避免写死 main/master 导致冲突
        $branch = git branch --show-current
        if ([string]::IsNullOrEmpty($branch)) {
            $branch = git rev-parse --abbrev-ref HEAD
        }
        
        Write-Host "Pushing to remote repository on branch: $branch..." -ForegroundColor Cyan
        git push -u origin $branch
    }
}
