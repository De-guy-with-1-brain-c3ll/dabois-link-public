param(
    [Parameter(Mandatory)] [string]$FriendsJar,
    [string]$LibraryDirectory = (Join-Path $env:USERPROFILE 'Desktop/.minecraft/libraries'),
    [string]$VoiceJar,
    [string]$HmclJar
)

$ErrorActionPreference = 'Stop'
$friendPath = (Resolve-Path -LiteralPath $FriendsJar).Path
$buildRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'build'
$output = Join-Path $buildRoot 'setup-packages'
New-Item -ItemType Directory -Path $buildRoot,$output -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$voice = 'voicechat-fabric-2.6.27+26.3.jar'
$voiceHash = 'f2de66761c0f249bb0f9a4b348f98b97ab4e89d3ed3fd37638420364b7431b757933aa08c0cc3e7ba3dd85a602d6c2de313c2309fc587e93cabf31fc5f6df27e'
$versionId = 'DABOIS-26.3-Fabric-0.6'

$modArchive = [IO.Compression.ZipFile]::OpenRead($friendPath)
try {
    foreach ($platform in 'macos-arm64','macos-x64') {
        $library = $modArchive.GetEntry("dabois-zt/$platform/libdabois_zerotier.dylib")
        if (!$library -or $library.Length -lt 65536) { throw "The native library for $platform is missing." }
        $stream = $library.Open()
        try {
            $header = [byte[]]::new(8)
            $stream.ReadExactly($header)
            $expected = if ($platform.EndsWith('-arm64')) { 0x0100000c } else { 0x01000007 }
            if ([BitConverter]::ToUInt32($header,0) -ne [uint32]4277009103 -or
                    [BitConverter]::ToUInt32($header,4) -ne $expected) {
                throw "The native library has the wrong Mach-O architecture for $platform."
            }
        } finally { $stream.Dispose() }
    }
    $invitation = $modArchive.GetEntry('dabois-link-invite.properties')
    if (!$invitation) { throw 'Expected a server-specific DABOIS Link JAR.' }
    $reader = [IO.StreamReader]::new($invitation.Open())
    try { $invite = $reader.ReadToEnd() | ConvertFrom-StringData } finally { $reader.Dispose() }
    if ($invite.fingerprint -notmatch '^[a-f0-9]{64}$' -or
            $invite.ztNetwork -notmatch '^[a-f0-9]{16}$' -or $invite.voicePort -ne '24454') {
        throw 'The DABOIS Link JAR is missing its certificate pin, embedded network, or voice settings.'
    }
} finally { $modArchive.Dispose() }

$basePath = Join-Path $buildRoot 'setup-vanilla-26.3.json'
$versions = Invoke-RestMethod 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json' -TimeoutSec 30
$official = $versions.versions | Where-Object id -eq '26.3' | Select-Object -First 1
if (!$official) { throw 'Minecraft 26.3 is missing from the official manifest.' }
Invoke-WebRequest -Uri $official.url -OutFile $basePath -TimeoutSec 60
if ((Get-FileHash -LiteralPath $basePath -Algorithm SHA1).Hash -ine $official.sha1) { throw 'Minecraft metadata checksum mismatch.' }
$base = Get-Content -LiteralPath $basePath -Raw | ConvertFrom-Json
$profile = Invoke-RestMethod 'https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/profile/json' -TimeoutSec 30
if ($base.id -ne '26.3' -or $profile.inheritsFrom -ne '26.3' -or
        $profile.libraries.name -notcontains 'net.fabricmc:fabric-loader:0.20.1' -or
        $base.downloads.client.sha1 -notmatch '^[a-f0-9]{40}$' -or
        $base.assetIndex.id -notmatch '^[0-9]+$') {
    throw 'Expected verified Minecraft 26.3 and Fabric Loader 0.20.1 metadata.'
}
$profile.id = $versionId
$voicePath = $VoiceJar
if (!$voicePath) {
    $voicePath = Join-Path $buildRoot $voice
    Invoke-WebRequest -Uri 'https://cdn.modrinth.com/data/9eGKb6K1/versions/x9jLR94Q/voicechat-fabric-2.6.27%2B26.3.jar' -OutFile $voicePath -TimeoutSec 60
}
if ((Get-FileHash -LiteralPath $voicePath -Algorithm SHA512).Hash -ine $voiceHash) {
    throw 'The cached Simple Voice Chat download failed its publisher checksum.'
}
if (!$HmclJar) {
    $HmclJar = Join-Path $buildRoot 'setup-HMCL-3.16.4.jar'
    Invoke-WebRequest -Uri 'https://github.com/HMCL-dev/HMCL/releases/download/v3.16.4/HMCL-3.16.4.jar' -OutFile $HmclJar -TimeoutSec 90
}
if ((Get-FileHash -LiteralPath $HmclJar -Algorithm SHA256).Hash -ine '98f0123f38e86a606aa583f74d21ae74128679e921ea7ec0c43ab26303767e9e') {
    throw 'The official HMCL launcher failed checksum verification.'
}

$stage = Join-Path $buildRoot ('mac-offline-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $stage,$output | Out-Null
$profilePath = Join-Path $stage 'profile.json'
[IO.File]::WriteAllText($profilePath, ($profile | ConvertTo-Json -Depth 50), $utf8)
$payloadPath = Join-Path $stage 'payload.zip'
$payload = [IO.Compression.ZipFile]::Open($payloadPath,[IO.Compression.ZipArchiveMode]::Create)
try {
    $files = [ordered]@{
        'DABOIS-Link.jar' = $friendPath
        'profile.json' = $profilePath
        'vanilla.json' = $basePath
        'HMCL.jar' = $HmclJar
    }
    $files[$voice] = $voicePath
    foreach ($lib in $profile.libraries) {
        $group,$artifact,$release = $lib.name -split ':'
        if ($group -notmatch '^[A-Za-z0-9_.-]+$' -or $artifact -notmatch '^[A-Za-z0-9_.-]+$' -or
                $release -notmatch '^[A-Za-z0-9_.+-]+$' -or !$lib.url.StartsWith('https://')) {
            throw "Invalid Fabric dependency $($lib.name)."
        }
        $relative = "$($group.Replace('.', '/'))/$artifact/$release/$artifact-$release.jar"
        $verified = Join-Path $LibraryDirectory $relative
        if (!(Test-Path -LiteralPath $verified -PathType Leaf) -or
                ($lib.sha1 -and (Get-FileHash -LiteralPath $verified -Algorithm SHA1).Hash -ine $lib.sha1)) {
            $verified = Join-Path $stage "$artifact-$release.jar"
            Invoke-WebRequest -Uri ($lib.url.TrimEnd('/') + '/' + $relative) -OutFile $verified -TimeoutSec 60
        }
        if ($lib.sha1 -and (Get-FileHash -LiteralPath $verified -Algorithm SHA1).Hash -ine $lib.sha1) {
            throw "Checksum mismatch for $($lib.name)."
        }
        $libraryArchive = [IO.Compression.ZipFile]::OpenRead($verified)
        $libraryArchive.Dispose()
        $files["libraries/$relative"] = $verified
    }
    foreach ($entry in $files.GetEnumerator()) {
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($payload,$entry.Value,$entry.Key)
    }
} finally { $payload.Dispose() }

$script = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source/mac-offline.command') -Raw).
    Replace('__PAYLOAD_SHA256__',(Get-FileHash -LiteralPath $payloadPath).Hash.ToLowerInvariant()).
    Replace('__VANILLA_SHA1__',$base.downloads.client.sha1).
    Replace('__VANILLA_SIZE__',[string]$base.downloads.client.size).
    Replace('__ASSET_INDEX__',$base.assetIndex.id).
    Replace('__CERT_PIN__',$invite.fingerprint)
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($payloadPath),[Base64FormattingOptions]::InsertLineBreaks)
$command = Join-Path $stage 'Install-DABOIS-Mac-Offline.command'
[IO.File]::WriteAllText($command,(($script.TrimEnd() + "`n# BEGIN DABOIS-ZIP`n$encoded`n# END DABOIS-ZIP`nDABOIS_PAYLOAD`n") -replace '\r\n',"`n"),$utf8)
$archive = Join-Path $stage 'launchable.zip'
$outer = [IO.Compression.ZipFile]::Open($archive,[IO.Compression.ZipArchiveMode]::Create)
try {
    $entry = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($outer,$command,'Install-DABOIS-Mac-Offline.command')
    $entry.ExternalAttributes = -2115174400
} finally { $outer.Dispose() }
$bytes = [IO.File]::ReadAllBytes($archive)
$directory = [int][BitConverter]::ToUInt32($bytes,$bytes.Length-6)
if ([BitConverter]::ToUInt32($bytes,$directory) -ne 0x02014b50) { throw 'Invalid macOS ZIP directory.' }
$bytes[$directory+5] = 3
$result = Join-Path $output 'Install-DABOIS-Mac-Offline.zip'
[IO.File]::WriteAllBytes($result,$bytes)
Get-Item -LiteralPath $result,$command
