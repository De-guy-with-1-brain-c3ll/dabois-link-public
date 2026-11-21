param(
    [Parameter(Mandatory)] [string]$ModJar,
    [Parameter(Mandatory)] [string]$GsonJar,
    [string]$VoiceJar,
    [string]$HmclJar,
    [string]$FriendsJar,
    [string]$RuntimeList
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo 'build/setup-packages'
$stage = Join-Path $repo ('build/setup-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $stage 'classes'
$payload = Join-Path $classes 'payload'
New-Item -ItemType Directory -Path $output,$payload -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$voiceHash = 'f2de66761c0f249bb0f9a4b348f98b97ab4e89d3ed3fd37638420364b7431b757933aa08c0cc3e7ba3dd85a602d6c2de313c2309fc587e93cabf31fc5f6df27e'
if (!$VoiceJar) {
    $VoiceJar = Join-Path $stage 'voice.jar'
    Invoke-WebRequest -Uri 'https://cdn.modrinth.com/data/9eGKb6K1/versions/x9jLR94Q/voicechat-fabric-2.6.27%2B26.3.jar' -OutFile $VoiceJar -TimeoutSec 60
}
if ((Get-FileHash -LiteralPath $VoiceJar -Algorithm SHA512).Hash -ine $voiceHash) {
    throw 'Simple Voice Chat failed its publisher checksum.'
}
Copy-Item -LiteralPath $VoiceJar -Destination (Join-Path $payload 'voice.jar')
Copy-Item -LiteralPath $ModJar -Destination (Join-Path $payload 'server-mod.jar')
if (!$HmclJar) {
    $HmclJar = Join-Path $stage 'HMCL-3.16.4.jar'
    Invoke-WebRequest -Uri 'https://github.com/HMCL-dev/HMCL/releases/download/v3.16.4/HMCL-3.16.4.jar' -OutFile $HmclJar -TimeoutSec 90
}
if ((Get-FileHash -LiteralPath $HmclJar -Algorithm SHA256).Hash -ine '98f0123f38e86a606aa583f74d21ae74128679e921ea7ec0c43ab26303767e9e') {
    throw 'The official HMCL launcher failed checksum verification.'
}
Copy-Item -LiteralPath $HmclJar -Destination (Join-Path $payload 'hmcl.jar')
if ($FriendsJar) {
    $invite = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $FriendsJar).Path)
    try {
        if (!$invite.GetEntry('dabois-link-invite.properties')) { throw 'The bundle needs a DABOIS invite JAR.' }
    } finally { $invite.Dispose() }
}
$gson = (Resolve-Path -LiteralPath $GsonJar).Path
& javac --release 25 -encoding UTF-8 -cp $gson -d $classes (Join-Path $PSScriptRoot 'Setup.java')
if ($LASTEXITCODE) { throw 'The setup interface failed to compile.' }
$dependency = [IO.Compression.ZipFile]::OpenRead($gson)
try {
    foreach ($entry in $dependency.Entries) {
        if (($entry.FullName -notmatch '^com/google/gson/[A-Za-z0-9_$/]+\.class$') -and
                ($entry.FullName -notin @('META-INF/LICENSE','META-INF/NOTICE'))) { continue }
        $target = Join-Path $classes $entry.FullName
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
    }
} finally { $dependency.Dispose() }
$wizard = Join-Path $stage 'DABOIS-Setup.jar'
& jar --create --file $wizard --main-class Setup -C $classes .
if ($LASTEXITCODE) { throw 'Could not package the setup interface.' }
& java -jar $wizard --verify
if ($LASTEXITCODE) { throw 'The setup interface failed verification.' }
$clientWizard = $wizard
if ($FriendsJar) {
    $clientWizard = Join-Path $stage 'DABOIS-Player-Setup.jar'
    Copy-Item -LiteralPath $wizard -Destination $clientWizard
    Copy-Item -LiteralPath $FriendsJar -Destination (Join-Path $payload 'client-invite.jar')
    & jar --update --file $clientWizard -C $classes payload/client-invite.jar
    if ($LASTEXITCODE) { throw 'Could not include your server invite in the player installer.' }
}

$runtimes = Join-Path $stage 'java-downloads.tsv'
if ($RuntimeList) {
    Copy-Item -LiteralPath $RuntimeList -Destination $runtimes
} else {
    $platforms = @('windows-x64','mac-x64','mac-aarch64','linux-x64','linux-aarch64')
    $lines = $platforms | ForEach-Object -Parallel {
        $ErrorActionPreference = 'Stop'
        $platform = $_
        $os,$architecture = $platform.Split('-')
        $url = "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$architecture&image_type=jre&os=$os&vendor=eclipse"
        $latest = Invoke-RestMethod -Uri $url -TimeoutSec 40
        $bundle = @($latest)[0].binary.package
        if ($bundle.checksum -notmatch '^[a-f0-9]{64}$' -or !$bundle.link.StartsWith('https://github.com/adoptium/')) {
            throw "Invalid Java runtime metadata for $platform."
        }
        "$platform`t$($bundle.checksum)`t$($bundle.link)"
    } -ThrottleLimit 5
    if (@($lines).Count -ne $platforms.Count) { throw 'Could not prepare Java download links for every platform.' }
    [IO.File]::WriteAllLines($runtimes,[string[]]$lines,$utf8)
}

function Add-File($archive, [string]$path, [string]$name, [bool]$executable) {
    $entry = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$path,$name)
    if ($executable) { $entry.ExternalAttributes = -2115174400 }
}

foreach ($platform in 'Windows','Mac','Linux') {
    foreach ($role in 'client','server') {
        $kind = if ($role -eq 'client') { 'Play' } else { 'Host' }
        $folder = Join-Path $stage "$platform-$role"
        New-Item -ItemType Directory -Path $folder | Out-Null
        $readme = Join-Path $folder 'Read me.txt'
        $instructions = if ($role -eq 'client') {
            "Extract this ZIP and open Play DABOIS. Choose fresh HMCL or your existing launcher. Use the included invite if available, or select the JAR your host sent. Launch DABOIS-26.3-Fabric from your launcher. Internet access is needed to prepare a fresh game."
        } else {
            "Extract this ZIP. Open Host DABOIS, choose a new server folder, and accept the Minecraft EULA. Start the server from Setup and share the generated friends JAR with players. Remote players need a reachable host address or a relay. Internet access is needed during setup."
        }
        [IO.File]::WriteAllText($readme,$instructions + [Environment]::NewLine,$utf8)
        $ext = switch ($platform) { 'Windows' { 'cmd' } 'Mac' { 'command' } default { 'sh' } }
        $launch = Join-Path $folder "$kind DABOIS.$ext"
        if ($platform -eq 'Windows') {
            $batch = @'
@echo off
setlocal DisableDelayedExpansion
set "DABOIS_SETUP_ROOT=%~dp0"
set "DABOIS_SETUP_ROLE=__ROLE__"
powershell.exe -NoProfile -Command "& ([scriptblock]::Create((Get-Content -Raw -LiteralPath (Join-Path $env:DABOIS_SETUP_ROOT 'launch.ps1'))))"
if errorlevel 1 pause
'@
            [IO.File]::WriteAllText($launch,($batch.Replace('__ROLE__',$role) -replace '\r?\n',"`r`n"),[Text.Encoding]::ASCII)
        } else {
            $shell = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'launch.sh') -Raw
            [IO.File]::WriteAllText($launch,($shell.Replace('__ROLE__',$role) -replace '\r\n',"`n"),$utf8)
        }
        $tempZip = Join-Path $folder 'package.zip'
        $archive = [IO.Compression.ZipFile]::Open($tempZip,[IO.Compression.ZipArchiveMode]::Create)
        try {
            Add-File $archive $launch (Split-Path -Leaf $launch) ($platform -ne 'Windows')
            Add-File $archive $readme 'Read me.txt' $false
            $jar = if ($role -eq 'client') { $clientWizard } else { $wizard }
            Add-File $archive $jar 'DABOIS-Setup.jar' $false
            Add-File $archive $runtimes 'java-downloads.tsv' $false
            if ($platform -eq 'Windows') {
                Add-File $archive (Join-Path $PSScriptRoot 'launch.ps1') 'launch.ps1' $false
            }
        } finally { $archive.Dispose() }
        $bytes = [IO.File]::ReadAllBytes($tempZip)
        if ($platform -ne 'Windows') {
            $directory = [int][BitConverter]::ToUInt32($bytes,$bytes.Length - 6)
            if ([BitConverter]::ToUInt32($bytes,$directory) -ne 0x02014b50) { throw 'Invalid launcher ZIP directory.' }
            $bytes[$directory+5] = 3
        }
        $destination = Join-Path $output "DABOIS-$kind-$platform.zip"
        [IO.File]::WriteAllBytes($destination,$bytes)
        Get-Item -LiteralPath $destination
    }
}
