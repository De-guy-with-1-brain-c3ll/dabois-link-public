param([string]$MinecraftJar, [string]$LibraryDirectory, [string]$NativeResources)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$build = Join-Path $PSScriptRoot 'build'
$deps = Join-Path $build 'deps'
New-Item -ItemType Directory -Force $deps | Out-Null

function Get-Artifact($artifact, [string]$destination) {
    if (!(Test-Path -LiteralPath $destination)) {
        Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$destination.partial"
        if ($artifact.sha1 -and (Get-FileHash -Algorithm SHA1 -LiteralPath "$destination.partial").Hash -ne $artifact.sha1) {
            throw "Checksum mismatch for $($artifact.url)"
        }
        Move-Item -LiteralPath "$destination.partial" -Destination $destination
    }
}

if ($MinecraftJar -and $LibraryDirectory) {
    $libraries = Get-ChildItem -LiteralPath $LibraryDirectory -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'natives|installer' } |
        Select-Object -ExpandProperty FullName
    $classpath = @((Resolve-Path -LiteralPath $MinecraftJar).Path) + $libraries
} else {
    $release = Invoke-RestMethod 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
    $version = $release.versions | Where-Object id -eq '26.3' | Select-Object -First 1
    $metadata = Invoke-RestMethod $version.url
    $profile = Invoke-RestMethod 'https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/profile/json'
    $client = Join-Path $deps 'minecraft-26.3.jar'
    Get-Artifact $metadata.downloads.client $client
    $classpath = @($client)
    foreach ($library in $metadata.libraries) {
        $artifact = $library.downloads.artifact
        if ($artifact -and $artifact.path -notmatch 'natives') {
            $path = Join-Path $deps ($artifact.path -replace '/', '_')
            Get-Artifact $artifact $path
            $classpath += $path
        }
    }
    $mixin = $profile.libraries | Where-Object name -like 'net.fabricmc:sponge-mixin:*' | Select-Object -First 1
    $version = ($mixin.name -split ':')[-1]
    $path = Join-Path $deps "sponge-mixin-$version.jar"
    Get-Artifact @{ url = ("{0}net/fabricmc/sponge-mixin/{1}/sponge-mixin-{1}.jar" -f $mixin.url, $version) } $path
    $classpath += $path
    $loader = $profile.libraries | Where-Object name -like 'net.fabricmc:fabric-loader:*' | Select-Object -First 1
    $loaderVersion = ($loader.name -split ':')[-1]
    $loaderPath = Join-Path $deps "fabric-loader-$loaderVersion.jar"
    Get-Artifact @{ url = ("{0}net/fabricmc/fabric-loader/{1}/fabric-loader-{1}.jar" -f $loader.url, $loaderVersion) } $loaderPath
    $classpath += $loaderPath
}

$classes = Join-Path $build ('classes-' + [Guid]::NewGuid())
New-Item -ItemType Directory $classes | Out-Null
try {
    $sources = @(Get-ChildItem src/main/java -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
    $cp = $classpath -join [IO.Path]::PathSeparator
    # An argument file avoids the Windows command-line length limit.
    $argFile = Join-Path $classes 'javac.args'
    $javacArgs = @('--release', '25', '-encoding', 'UTF-8', '-proc:none', '-classpath', $cp, '-d', $classes) + $sources
    $lines = $javacArgs | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }
    [IO.File]::WriteAllLines($argFile, [string[]]$lines)
    & javac "@$argFile"
    if ($LASTEXITCODE) { throw "javac exited with $LASTEXITCODE" }
    Remove-Item -LiteralPath $argFile
    $jarArgs = @('--create', '--file', (Join-Path $build 'dabois-link.jar'), '-C', $classes, '.', '-C', 'src/main/resources', '.')
    if ($NativeResources) {
        $native = (Resolve-Path -LiteralPath $NativeResources).Path
        if (!(Test-Path -LiteralPath (Join-Path $native 'dabois-zt') -PathType Container)) {
            throw 'The native resources directory must contain dabois-zt.'
        }
        $jarArgs += @('-C', $native, 'dabois-zt')
    }
    & jar @jarArgs
    if ($LASTEXITCODE) { throw "jar exited with $LASTEXITCODE" }
} finally {
    $resolved = [IO.Path]::GetFullPath($classes)
    if ($resolved.StartsWith($build + [IO.Path]::DirectorySeparatorChar)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
