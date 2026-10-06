# =====================================================================
#  一键打包脚本：将本项目打包为 Windows 便携版 exe（jpackage app-image）
#
#  产物：dist\<Name>\<Name>.exe（整个 <Name> 文件夹可直接拷贝分发）
#  为减小体积，先用 jlink 构建仅含必要模块的精简运行时，再交给 jpackage。
#
#  用法（在项目根目录）：
#      powershell -ExecutionPolicy Bypass -File .\build-exe.ps1
#  可选参数：
#      -Name    应用名（默认 WeatherWidget）
#      -Version 版本号（默认 1.0.0）
#      -Icon    图标路径（默认 src\main\resources\icon.ico，不存在则用默认图标）
# =====================================================================
param(
    [string]$Name = "WeatherWidget",
    [string]$Version = "1.0.0",
    [string]$Icon = "src\main\resources\icon.ico"
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

# 精简运行时保留的模块：
#   java.base      - 核心
#   java.desktop   - AWT/Swing（JavaFX 依赖）
#   java.logging   - JavaFX 内部日志
#   java.net.http  - HttpClient
#   java.prefs     - 偏好存储（默认定位等）
#   java.sql       - Jackson 处理日期类型时引用
#   java.xml, jdk.charsets - CSS 解析/字符集兜底
#   jdk.crypto.ec  - TLS 椭圆曲线套件（HTTPS 必需）
#   jdk.unsupported- JavaFX 反射访问 sun.misc
#   jdk.jfr        - jdeps 检测到的依赖
# 如后续新增库并报 NoClassDefFoundError，把对应模块追加到这里即可。
$Modules = "java.base,java.desktop,java.logging,java.net.http,java.prefs,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.jfr,jdk.charsets"

function Fail([string]$msg) {
    Write-Host "[错误] $msg" -ForegroundColor Red
    exit 1
}

# ---------- 1. 定位 JDK 工具 ----------
$jdkHome = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
    $jdkHome = $env:JAVA_HOME
} elseif (Get-Command jpackage.exe -ErrorAction SilentlyContinue) {
    $jdkHome = Split-Path (Split-Path (Get-Command jpackage.exe).Source)
}
if (-not $jdkHome) {
    Fail "未找到 jpackage.exe，请设置 JAVA_HOME 指向 JDK 21"
}
$jpackage = Join-Path $jdkHome "bin\jpackage.exe"
$jlink = Join-Path $jdkHome "bin\jlink.exe"
if (-not (Test-Path $jlink)) { Fail "未找到 jlink.exe（应与 jpackage 同目录）" }
Write-Host "JDK      : $jdkHome"

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Fail "未找到 mvn，请确保 Maven 已加入 PATH"
}

# ---------- 2. 结束占用旧产物的进程 ----------
$running = Get-Process -Name $Name -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "[提示] 检测到 $Name 正在运行，先结束进程以免文件被占用" -ForegroundColor Yellow
    $running | Stop-Process -Force
    Start-Sleep -Milliseconds 800
}

# ---------- 3. Maven 构建 ----------
Write-Host "`n[1/4] Maven 构建 jar 与依赖 ..." -ForegroundColor Cyan
mvn -q clean package
if ($LASTEXITCODE -ne 0) { Fail "Maven 构建失败" }

# ---------- 4. jlink 精简运行时 ----------
Write-Host "`n[2/4] jlink 构建精简运行时（仅必要模块）..." -ForegroundColor Cyan
$runtime = Join-Path $PWD "target\runtime"
if (Test-Path $runtime) { Remove-Item -Recurse -Force $runtime }
$linkArgs = @(
    "--add-modules", $Modules,
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    "--compress=zip-6",
    "--output", $runtime
)
& $jlink @linkArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "[提示] zip-6 压缩不被当前 JDK 支持，回退到 --compress=2" -ForegroundColor Yellow
    if (Test-Path $runtime) { Remove-Item -Recurse -Force $runtime }
    $linkArgs = @(
        "--add-modules", $Modules,
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--compress=2",
        "--output", $runtime
    )
    & $jlink @linkArgs
}
if ($LASTEXITCODE -ne 0) { Fail "jlink 构建运行时失败" }
$rtSize = [math]::Round((Get-ChildItem $runtime -Recurse | Measure-Object Length -Sum).Sum / 1MB, 1)
Write-Host "运行时大小: $rtSize MB"

# ---------- 5. jpackage 打包 ----------
$dest = Join-Path $PWD "dist"
$appDir = Join-Path $dest $Name
if (Test-Path $appDir) { Remove-Item -Recurse -Force $appDir }

Write-Host "`n[3/4] jpackage 生成便携版应用 ..." -ForegroundColor Cyan
$jpArgs = @(
    "--type", "app-image",
    "--name", $Name,
    "--app-version", $Version,
    "--input", "target\dist",
    "--main-jar", "WeatherWidget.jar",
    "--main-class", "com.weatherwidget.Launcher",
    "--runtime-image", $runtime,
    # AWT 系统托盘原生菜单在 JDK18+ 下需与 Windows ANSI 代码页(GBK)一致，否则中文显示为方框
    "--java-options", "-Dfile.encoding=GBK",
    "--dest", $dest
)
if (Test-Path $Icon) {
    $jpArgs += @("--icon", $Icon)
} else {
    Write-Host "[提示] 未找到图标 $Icon，将使用默认图标" -ForegroundColor Yellow
}

& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { Fail "jpackage 打包失败" }

# ---------- 6. 完成 ----------
$exe = Join-Path $appDir "$Name.exe"
Write-Host "`n[4/4] 完成！" -ForegroundColor Green
Write-Host "输出目录  : $appDir"
Write-Host "可执行文件: $exe"
if (Test-Path $exe) {
    $size = [math]::Round((Get-ChildItem $appDir -Recurse | Measure-Object Length -Sum).Sum / 1MB, 1)
    Write-Host "总大小    : $size MB"
}
