param([switch]$Test, [switch]$Package, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$compiler = Join-Path $framework 'csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw '需要 Windows 自带的 .NET Framework 4.x。' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $outputDirectory = Join-Path $projectRoot 'build'
} else {
    $outputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
}
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$outputExe = Join-Path $outputDirectory 'QixiaBuilder.exe'
$references = @('System.dll', 'System.Core.dll', 'System.Xaml.dll', 'System.Web.Extensions.dll', 'System.Windows.Forms.dll', 'System.Drawing.dll',
    'WPF/WindowsBase.dll', 'WPF/PresentationCore.dll', 'WPF/PresentationFramework.dll')
$compilerArguments = @('/nologo', '/target:winexe', '/platform:anycpu', '/optimize+', '/utf8output', '/warnaserror+',
    ('/out:' + $outputExe), ('/win32manifest:' + (Join-Path $PSScriptRoot 'app.manifest')),
    ('/resource:' + (Join-Path $PSScriptRoot 'MainWindow.xaml') + ',MainWindow.xaml'))
$compilerArguments += $references | ForEach-Object { '/reference:' + (Join-Path $framework $_) }
$compilerArguments += Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.cs' | ForEach-Object { $_.FullName }
& $compiler @compilerArguments
if ($LASTEXITCODE -ne 0) { throw 'EXE 编译失败。' }
Write-Output ('已生成：' + $outputExe)
if ($Test) {
    $testProcess = Start-Process -FilePath $outputExe -ArgumentList '--self-test' -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput (Join-Path $outputDirectory 'builder-tests.stdout.log') `
        -RedirectStandardError (Join-Path $outputDirectory 'builder-tests.stderr.log')
    Get-Content -LiteralPath (Join-Path $outputDirectory 'builder-tests.stdout.log') -Encoding UTF8
    if ($testProcess.ExitCode -ne 0) {
        Get-Content -LiteralPath (Join-Path $outputDirectory 'builder-tests.stderr.log') -Encoding UTF8
        throw 'EXE 自检失败。'
    }
}
if ($Package) {
    # 明确指定时才更新仓库中的分发文件，普通构建仍输出到 build。
    $distributedExe = Join-Path $PSScriptRoot 'QixiaBuilder.exe'
    Copy-Item -LiteralPath $outputExe -Destination $distributedExe -Force
    $checksum = (Get-FileHash -LiteralPath $distributedExe -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'QixiaBuilder.sha256'),
        "$checksum  QixiaBuilder.exe`n", [Text.UTF8Encoding]::new($false))
    Write-Output ('已更新分发 EXE 与 SHA-256：' + $distributedExe)
}
