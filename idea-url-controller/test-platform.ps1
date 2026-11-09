param(
    [string]$IdeaHome = 'D:\@Softwares\idea',
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '..\..\work\idea-url-controller-build'),
    [string]$JavaHome = (Join-Path $IdeaHome 'jbr')
)
$ErrorActionPreference = 'Stop'
$BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
$dependencies = Join-Path $BuildDirectory 'test-deps'
New-Item -ItemType Directory -Force -Path $dependencies | Out-Null
$artifacts = @(
    'cn/hutool/hutool-core/5.8.40/hutool-core-5.8.40.jar',
    'junit/junit/4.13.2/junit-4.13.2.jar',
    'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
    'org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar'
)
foreach ($artifact in $artifacts) {
    $destination = Join-Path $dependencies ($artifact.Split('/')[-1])
    if (!(Test-Path -LiteralPath $destination)) {
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/$artifact" -OutFile $destination
    }
}
$sandbox = Join-Path $BuildDirectory ('platform-sandbox-' + [Guid]::NewGuid().ToString('N'))
$pluginDirectory = Join-Path $sandbox 'plugins'
New-Item -ItemType Directory -Force -Path $pluginDirectory | Out-Null
Copy-Item -LiteralPath (Join-Path $BuildDirectory 'package\url-controller-search') -Destination $pluginDirectory -Recurse
$testJar = Join-Path $pluginDirectory 'url-controller-search\lib\platform-tests.jar'
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $BuildDirectory 'tests'), $testJar)
$frontend = Join-Path $IdeaHome 'lib\frontend\'
$sdkJars = @(Get-ChildItem -LiteralPath (Join-Path $IdeaHome 'lib') -Filter '*.jar' -Recurse |
    Where-Object { !$_.FullName.StartsWith($frontend, [StringComparison]::OrdinalIgnoreCase) })
$sdkJars += @(Get-ChildItem -LiteralPath (Join-Path $IdeaHome 'plugins\java\lib') -Filter '*.jar' -Recurse)
$streams = Join-Path $IdeaHome 'plugins\java-debugger-streams\lib'
if (Test-Path -LiteralPath $streams) {
    $sdkJars += @(Get-ChildItem -LiteralPath $streams -Filter '*.jar' -Recurse)
}
$classpath = "$BuildDirectory\tests;$BuildDirectory\classes;$dependencies\*;" + ($sdkJars.FullName -join ';')
$product = Get-Content -LiteralPath (Join-Path $IdeaHome 'product-info.json') -Raw | ConvertFrom-Json
$opens = @($product.launch[0].additionalJvmArguments | Where-Object { $_.StartsWith('--add-opens=') })
$java = Join-Path $JavaHome 'bin\java.exe'
$log = Join-Path $BuildDirectory ('platform-test-' + $product.version + '.log')
& $java -ea @opens '-Djava.awt.headless=true' "-Didea.home.path=$IdeaHome" `
    "-Didea.config.path=$sandbox\config" "-Didea.system.path=$sandbox\system" `
    "-Didea.plugins.path=$pluginDirectory" '-Didea.load.plugins.id=local.urlcontroller,com.intellij.java' `
    '-Didea.force.use.core.classloader=true' '-Didea.is.unit.test=true' '-Didea.platform.prefix=Idea' "-Djna.boot.library.path=$IdeaHome\lib\jna\amd64" `
    -cp $classpath local.urlcontroller.testboot.Bootstrap *> $log
$result = $LASTEXITCODE
Get-Content -LiteralPath $log -Tail 60
if ($result -ne 0) { throw "IDEA integration tests failed. Log: $log" }
