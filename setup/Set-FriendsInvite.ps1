param(
    [Parameter(Mandatory)] [string]$ModJar,
    [Parameter(Mandatory)] [string]$TrustedInviteJar,
    [Parameter(Mandatory)] [string]$Destination,
    [Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9.:-]+$')][string]$RelayHost,
    [Parameter(Mandatory)] [ValidateRange(1,65535)][int]$RelayPort,
    [Parameter(Mandatory)] [ValidatePattern('^[a-f0-9]{16}$')][string]$Network,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9.]+$')][string]$EmbeddedHost,
    [ValidateRange(1,65535)][int]$EmbeddedPort = 25576
)

$ErrorActionPreference = 'Stop'
$entryName = 'dabois-link-invite.properties'
$modSource = (Resolve-Path -LiteralPath $ModJar).Path
$inviteSource = (Resolve-Path -LiteralPath $TrustedInviteJar).Path
$destinationPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
if ($destinationPath -in @($modSource, $inviteSource)) {
    throw 'Choose a separate destination for the personalized JAR.'
}
$embeddedAddress = [Net.IPAddress]::Parse($EmbeddedHost)
if ($embeddedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'The embedded host must be IPv4.' }

$trusted = [IO.Compression.ZipFile]::OpenRead($inviteSource)
try {
    $reader = [IO.StreamReader]::new($trusted.GetEntry($entryName).Open())
    try { $oldInvite = $reader.ReadToEnd() | ConvertFrom-StringData } finally { $reader.Dispose() }
} finally { $trusted.Dispose() }
if ($oldInvite.fingerprint -notmatch '^[a-f0-9]{64}$') { throw 'The trusted invite has no valid certificate pin.' }
$properties = @(
    "host=$RelayHost", "port=$RelayPort", 'name=DABOIS', "fingerprint=$($oldInvite.fingerprint)",
    "ztNetwork=$Network", "ztHost=$embeddedAddress", "ztPort=$EmbeddedPort"
)
if ($oldInvite.voicePort -match '^[0-9]{1,5}$') { $properties += "voicePort=$($oldInvite.voicePort)" }

$source = [IO.Compression.ZipFile]::OpenRead($modSource)
$stage = "$destinationPath.$([guid]::NewGuid().ToString('N')).pending"
try {
    $reader = [IO.StreamReader]::new($source.GetEntry('fabric.mod.json').Open())
    try { $mod = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($mod.id -ne 'dabois_link' -or $mod.version -ne '0.6.0') { throw 'Expected the DABOIS Link 0.6.0 build.' }
    if ($source.Entries.FullName -match '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$') { throw 'Expected an unsigned DABOIS Link JAR.' }
    $result = [IO.Compression.ZipFile]::Open($stage,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $source.Entries) {
            if ($entry.FullName -eq $entryName) { continue }
            $copy = $result.CreateEntry($entry.FullName)
            if ($entry.FullName.EndsWith('/')) { continue }
            $reader = $entry.Open()
            try {
                $writer = $copy.Open()
                try { $reader.CopyTo($writer) } finally { $writer.Dispose() }
            } finally { $reader.Dispose() }
        }
        $writer = [IO.StreamWriter]::new($result.CreateEntry($entryName).Open(),[Text.UTF8Encoding]::new($false))
        try { $writer.Write(($properties -join "`n") + "`n") } finally { $writer.Dispose() }
    } finally { $result.Dispose() }
    Move-Item -LiteralPath $stage -Destination $destinationPath -Force
} finally {
    $source.Dispose()
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage }
}
Get-Item -LiteralPath $destinationPath
