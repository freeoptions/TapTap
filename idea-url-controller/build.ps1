param(
    [string]$IdeaHome = 'D:\@Softwares\idea',
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '..\..\work\idea-url-controller-build'),
    [string]$SdkHome = (Join-Path $BuildDirectory 'sdk-2023.1'),
    [string]$JavaHome = (Join-Path $IdeaHome 'jbr')
)
$ErrorActionPreference = 'Stop'
$sdk = Get-Content -LiteralPath (Join-Path $SdkHome 'product-info.json') -Raw | ConvertFrom-Json
if ($sdk.version -ne '2023.1') { throw "Expected compilation SDK 2023.1; found $($sdk.version)" }
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
$classes = Join-Path $BuildDirectory 'classes'
$tests = Join-Path $BuildDirectory 'tests'
New-Item -ItemType Directory -Force -Path $classes,$tests | Out-Null
Get-ChildItem -LiteralPath $classes,$tests -Filter '*.class' -File -Recurse | Remove-Item -Force
$sdkJars = @(Get-ChildItem -LiteralPath (Join-Path $SdkHome 'lib') -Filter '*.jar')
$sdkJars += @(Get-ChildItem -LiteralPath (Join-Path $SdkHome 'plugins\java\lib') -Filter '*.jar' -Recurse)
$classpath = ($sdkJars.FullName -join ';')
$sources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Filter '*.java' -Recurse).FullName)
& $javac -encoding UTF-8 --release 17 -cp $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Plugin compilation failed' }
$testSources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java') -Filter '*.java' -Recurse).FullName)
$dependencies = Join-Path $BuildDirectory 'test-deps'
New-Item -ItemType Directory -Force -Path $dependencies | Out-Null
$hutool = Join-Path $dependencies 'hutool-core-5.8.40.jar'
if (!(Test-Path -LiteralPath $hutool)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.40/hutool-core-5.8.40.jar' -OutFile $hutool
}
& $javac -encoding UTF-8 --release 17 -cp "$classes;$classpath;$hutool" -d $tests @testSources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& $java -ea -cp "$tests;$classes;$classpath;$hutool" local.urlcontroller.RouteTableTest
if ($LASTEXITCODE -ne 0) { throw 'Route tests failed' }
& $java -ea -cp "$tests;$classes;$classpath;$hutool" local.urlcontroller.MultiKeywordQueryTest
if ($LASTEXITCODE -ne 0) { throw 'Multi-keyword tests failed' }
$package = Join-Path $BuildDirectory 'package\url-controller-search\lib'
New-Item -ItemType Directory -Force -Path $package | Out-Null
$pluginJar = Join-Path $package 'url-controller-search.jar'
if (Test-Path -LiteralPath $pluginJar) { Remove-Item -LiteralPath $pluginJar }
[IO.Compression.ZipFile]::CreateFromDirectory($classes, $pluginJar)
$archive = [IO.Compression.ZipFile]::Open($pluginJar, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,
        (Join-Path $PSScriptRoot 'src\main\resources\META-INF\plugin.xml'), 'META-INF/plugin.xml') | Out-Null
} finally { $archive.Dispose() }
$output = Join-Path $PSScriptRoot '..\URL-Controller-Search-1.1.10.zip'
Compress-Archive -LiteralPath (Join-Path $BuildDirectory 'package\url-controller-search') -DestinationPath $output -Force
Write-Output "Install ZIP: $([IO.Path]::GetFullPath($output))"
