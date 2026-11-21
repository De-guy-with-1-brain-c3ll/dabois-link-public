$ErrorActionPreference = 'Stop'
$directory = $env:DABOIS_SETUP_ROOT
$role = $env:DABOIS_SETUP_ROLE
try {
    $architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
    $line = Get-Content -LiteralPath (Join-Path $directory 'java-downloads.tsv') |
        Where-Object { $_.StartsWith("windows-$architecture`t") } | Select-Object -First 1
    if (!$line) { throw 'This Windows processor is unsupported by this setup package.' }
    $platform,$checksum,$url = $line -split "`t"
    if ($checksum -notmatch '^[a-f0-9]{64}$' -or !$url.StartsWith('https://github.com/adoptium/')) {
        throw 'The Java download information failed verification.'
    }
    $cache = Join-Path $env:LOCALAPPDATA "Dabois/Java/$checksum"
    $java = Join-Path $cache 'bin/java.exe'
    if (!(Test-Path -LiteralPath $java -PathType Leaf)) {
        Write-Host 'Preparing Java for DABOIS. This is usually only needed once.'
        $work = Join-Path $env:TEMP ('dabois-java-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $work | Out-Null
        try {
            $bundle = Join-Path $work 'java.zip'
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $bundle -TimeoutSec 180
            if ((Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash -ine $checksum) { throw 'The Java download failed verification.' }
            $unpacked = Join-Path $work 'java'
            Expand-Archive -LiteralPath $bundle -DestinationPath $unpacked
            $runtime = Get-ChildItem -LiteralPath $unpacked -Directory |
                Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
            if (!$runtime) { throw 'The Java download is missing its launcher.' }
            New-Item -ItemType Directory -Path (Split-Path -Parent $cache) -Force | Out-Null
            if (!(Test-Path -LiteralPath $cache)) { Move-Item -LiteralPath $runtime.FullName -Destination $cache }
        } finally {
            $actual = [IO.Path]::GetFullPath($work)
            $temp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd([IO.Path]::DirectorySeparatorChar)
            if ($actual.StartsWith($temp + [IO.Path]::DirectorySeparatorChar + 'dabois-java-',
                    [StringComparison]::OrdinalIgnoreCase)) {
                Remove-Item -LiteralPath $actual -Recurse -Force
            }
        }
    }
    & $java '--enable-native-access=ALL-UNNAMED' -jar (Join-Path $directory 'DABOIS-Setup.jar') $role
    exit $LASTEXITCODE
} catch {
    Write-Host "Setup could not start. $($_.Exception.Message)" -ForegroundColor Yellow
    exit 1
}
