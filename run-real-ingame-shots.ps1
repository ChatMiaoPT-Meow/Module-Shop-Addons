# Module-Shop-Addons 本地实机编译 + OpenGL 原生帧缓冲自动截屏脚本
# 更新模型：Gemini（2026-11-11）
# 用法：在 E:\心自安然项目\Module-Shop-Addons 目录打开 PowerShell 运行：
#   .\run-real-ingame-shots.ps1 -ModuleShopRoot "..\Module-Shop"

param(
    [string]$ModuleShopRoot = "..\Module-Shop",
    [string]$StockDir = ".\stock",
    [string]$BlindDir = ".\blind",
    [switch]$AutoQuit = $true
)

$ErrorActionPreference = "Stop"
$AbsShopRoot = (Resolve-Path $ModuleShopRoot).Path

Write-Host "[1/3] 正在使用 JDK 21 编译并运行单元测试 (stock + blind)..." -ForegroundColor Cyan
Push-Location $StockDir
try {
    & .\gradlew.bat build --offline "-Pmodule_shop_root=$AbsShopRoot"
    if ($LASTEXITCODE -ne 0) { & .\gradlew.bat build "-Pmodule_shop_root=$AbsShopRoot" }
} finally { Pop-Location }

Push-Location $BlindDir
try {
    & .\gradlew.bat build --offline "-Pmodule_shop_root=$AbsShopRoot"
    if ($LASTEXITCODE -ne 0) { & .\gradlew.bat build "-Pmodule_shop_root=$AbsShopRoot" }
} finally { Pop-Location }

Write-Host "[2/3] 正在组装双附属联调 mods 环境..." -ForegroundColor Cyan
$RunMods = Join-Path $BlindDir "run\mods"
New-Item -ItemType Directory -Force -Path $RunMods | Out-Null
$StockJar = Get-ChildItem (Join-Path $StockDir "build\libs\module_shop_stock-*.jar") | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $StockJar.FullName -Destination $RunMods -Force

# 自动探测已有测试存档以启用 --quickPlaySingleplayer 免手点直进世界
$SavesDir = Join-Path $BlindDir "run\saves"
$WorldArg = @()
if (Test-Path $SavesDir) {
    $FirstWorld = Get-ChildItem $SavesDir -Directory | Select-Object -First 1
    if ($FirstWorld) {
        Write-Host "检测到已有测试存档 '$($FirstWorld.Name)'，将自动直连进入世界抓拍实机帧缓冲！" -ForegroundColor Green
        $WorldArg = @("--args", "--quickPlaySingleplayer `"$($FirstWorld.Name)`"")
    }
}
if ($WorldArg.Count -eq 0) {
    Write-Host "提示：启动客户端后请进入任意单人世界，进入后将自动依次打开股票与遗迹盲仓完成原生 OpenGL 截屏！" -ForegroundColor Yellow
}

$QuitFlag = if ($AutoQuit) { "true" } else { "false" }
Write-Host "[3/3] 正在拉起真实 Minecraft 1.21.1 + NeoForge + AtomLib 客户端执行原生 Screenshot.grab 抓拍..." -ForegroundColor Cyan
$PrevJavaOpts = $env:JAVA_TOOL_OPTIONS
Push-Location $BlindDir
try {
    $env:JAVA_TOOL_OPTIONS = "$PrevJavaOpts -Daddons.realshot=all -Daddons.realshot.quit=$QuitFlag".Trim()
    & .\gradlew.bat runClient "-Pmodule_shop_root=$AbsShopRoot" @WorldArg
} finally {
    $env:JAVA_TOOL_OPTIONS = $PrevJavaOpts
    Pop-Location
}

$ShotDir = Join-Path $BlindDir "run\real_screenshots"
if (Test-Path $ShotDir) {
    Write-Host "实机原生截屏已全部输出至：$ShotDir" -ForegroundColor Green
    explorer.exe $ShotDir
}
