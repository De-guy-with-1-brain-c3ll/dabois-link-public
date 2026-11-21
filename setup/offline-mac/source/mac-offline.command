#!/bin/bash
set -euo pipefail
umask 077

version='DABOIS-26.3-Fabric-0.6'
mode=${1:-}
platform="$(uname -s)"
work="$(mktemp -d "${TMPDIR:-/tmp}/dabois-mac.XXXXXXXX")"

finish() {
    local result=$?
    /bin/rm -rf -- "$work"
    if [ "$result" -ne 0 ]; then
        printf '\nSetup stopped. See the message above.\n'
    fi
    if [ "$mode" = '' ] && [ -t 0 ]; then
        read -r -p 'Press Return to close. ' unused || true
    fi
}
trap finish EXIT

stop() { echo "$*" >&2; exit 1; }

if [ "$platform" = Darwin ]; then decode_flag=-D; else decode_flag=-d; fi
/usr/bin/awk '/^# BEGIN DABOIS-ZIP$/ { reading=1; next } /^# END DABOIS-ZIP$/ { exit } reading' "$0" |
    /usr/bin/base64 "$decode_flag" > "$work/payload.zip"
payload_hash="$(/usr/bin/shasum -a 256 "$work/payload.zip" | /usr/bin/awk '{print $1}')"
[ "$payload_hash" = '__PAYLOAD_SHA256__' ] || stop 'The offline installer payload failed verification.'
/usr/bin/unzip -tq "$work/payload.zip" >/dev/null
[ "$mode" != '--verify' ] || { echo 'Offline payload verified.'; exit 0; }
[ "$platform" = Darwin ] || stop 'This installer is for macOS.'
[ "$EUID" -ne 0 ] || stop 'Run the installer from your regular macOS account.'
/usr/bin/unzip -q "$work/payload.zip" -d "$work/payload"
payload="$work/payload"

has_base() {
    [ -d "$1/versions" ] || [ -f "$1/launcher_profiles.json" ] || [ -f "$1/launcher_profiles_microsoft_store.json" ]
}

here="$(cd -- "$(dirname -- "$0")" && pwd)"
root=''
fresh=false
launcher=''
cache_root=''
if [ "$mode" = '--game-dir' ]; then
    [ "$#" -eq 2 ] || stop 'Pass the path to the existing HMCL game directory.'
    root=$2
else
    for folder in "$here/.minecraft" "$HOME/Desktop/.minecraft" "$HOME/.minecraft" +        "$HOME/Library/Application Support/minecraft" "$HOME/Library/Application Support/HMCL/.minecraft"; do
        if has_base "$folder"; then root=$folder; break; fi
    done
    choice="$(/usr/bin/osascript -e 'set choice to choose from list {"Use my existing launcher", "Install a fresh copy of HMCL"} with title "DABOIS Setup" with prompt "Choose how you want to play DABOIS."' -e 'if choice is false then error number -128' -e 'return item 1 of choice')" || exit 1
    if [ "$choice" = 'Install a fresh copy of HMCL' ]; then
        fresh=true
        cache_root=$root
        launcher="$HOME/DABOIS Launcher"
        if [ -e "$launcher" ] && [ ! -f "$launcher/.dabois-launcher" ]; then
            stop 'The DABOIS Launcher folder already contains another installation. Rename it and try again.'
        fi
        root="$launcher/.minecraft"
        mkdir -p -- "$root/versions"
        : > "$launcher/.dabois-launcher"
        if [ -n "$cache_root" ]; then
            for cache in assets libraries; do
                if [ -d "$cache_root/$cache" ] && [ ! -e "$root/$cache" ]; then
                    /bin/ln -s "$cache_root/$cache" "$root/$cache"
                fi
            done
        fi
    fi
fi
if [ -z "$root" ]; then
    root="$(/usr/bin/osascript -e 'POSIX path of (choose folder with prompt "Choose your HMCL game folder containing versions, libraries and assets.")')" || exit 1
    if has_base "$root/.minecraft"; then root="$root/.minecraft"; fi
fi
has_base "$root" || stop 'The selected folder does not contain an existing HMCL game installation.'

client=''
for candidate in "$root/versions/26.3/26.3.jar" "$root"/versions/*/*.jar "${cache_root:-$root}"/versions/*/*.jar; do
    [ -f "$candidate" ] || continue
    [ "$(/usr/bin/stat -f '%z' "$candidate")" = '__VANILLA_SIZE__' ] || continue
    [ "$(/usr/bin/shasum "$candidate" | /usr/bin/awk '{print $1}')" = '__VANILLA_SHA1__' ] || continue
    client=$candidate
    break
done
asset_index="$root/assets/indexes/__ASSET_INDEX__.json"

install_file() {
    local from="$1" to="$2" parent file
    parent="$(dirname -- "$to")"
    mkdir -p -- "$parent"
    file="$(mktemp "$parent/.dabois.XXXXXXXX")"
    if ! /bin/cp -- "$from" "$file" || ! /bin/mv -f -- "$file" "$to"; then
        /bin/rm -f -- "$file"
        return 1
    fi
}

game="$root/versions/$version"
base="$root/versions/26.3"
if [ -n "$client" ] && [ "$client" != "$base/26.3.jar" ]; then install_file "$client" "$base/26.3.jar"; fi
if [ ! -e "$base/26.3.json" ]; then install_file "$payload/vanilla.json" "$base/26.3.json"; fi
while IFS= read -r -d '' library; do
    relative=${library#"$payload/libraries/"}
    install_file "$library" "$root/libraries/$relative"
done < <(/usr/bin/find "$payload/libraries" -type f -print0)
install_file "$payload/profile.json" "$game/$version.json"
install_file "$payload/DABOIS-Link.jar" "$game/mods/DABOIS-Link.jar"
install_file "$payload/voicechat-fabric-2.6.27+26.3.jar" "$game/mods/voicechat-fabric-2.6.27+26.3.jar"

settings="$game/.hmcl/config/instance-game-settings.json"
if [ ! -e "$settings" ]; then
    mkdir -p -- "$(dirname -- "$settings")"
    printf '%s\n' '{"$schema":"https://schemas.glavo.site/hmcl/instance-game-settings/1.0.0","overrideProperties":["runningDirectory"]}' > "$settings"
fi
if "$fresh"; then
    install_file "$payload/HMCL.jar" "$launcher/HMCL.jar"
    cat > "$launcher/Open HMCL.command" <<'LAUNCH_HMCL'
#!/bin/bash
set -eu
cd -- "$(dirname -- "$0")"
java_home="$(/usr/libexec/java_home -v '25+' 2>/dev/null || true)"
if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
    printf '%s\n' 'HMCL needs Java 25 or newer. Install a compatible Java runtime and open this launcher again.'
    read -r -p 'Press Return to close. ' unused || true
    exit 1
fi
exec "$java_home/bin/java" "-Dhmcl.dir=$PWD/.hmcl" -jar HMCL.jar
LAUNCH_HMCL
    chmod 755 "$launcher/Open HMCL.command"
else
    for profiles in "$root/launcher_profiles.json" "$root/launcher_profiles_microsoft_store.json"; do
        [ -f "$profiles" ] || continue
        if [ ! -e "$profiles.before-dabois" ]; then install_file "$profiles" "$profiles.before-dabois"; fi
        /usr/bin/osascript -l JavaScript - "$profiles" "$game" "$version" <<'REGISTER'
function run(args) {
    ObjC.import('Foundation');
    var source = $.NSString.stringWithContentsOfFileEncodingError(args[0], $.NSUTF8StringEncoding, null);
    var config = JSON.parse(ObjC.unwrap(source));
    config.profiles = config.profiles || {};
    config.profiles['dabois-fabric'] = {
        name: args[2], type: 'custom', lastVersionId: args[2], gameDir: args[1]
    };
    if (!$(JSON.stringify(config, null, 2)).writeToFileAtomicallyEncodingError(args[0], true, $.NSUTF8StringEncoding, null)) {
        throw new Error('Could not register DABOIS with Minecraft Launcher.');
    }
}
REGISTER
    done
fi
pairing='config/dabois-link/paired-__CERT_PIN__.properties'
if [ ! -e "$game/$pairing" ]; then
    saved=''
    first_token=''
    multiple=false
    for instance in "$root" "$root"/versions/* "${cache_root:-$root}" "${cache_root:-$root}"/versions/*; do
        candidate="$instance/$pairing"
        [ -f "$candidate" ] || continue
        token="$(/usr/bin/sed -n 's/^token=//p' "$candidate" | /usr/bin/tr -d '\r')"
        [[ "$token" =~ ^[a-f0-9]{64}$ ]] || continue
        if [ -n "$first_token" ] && [ "$first_token" != "$token" ]; then multiple=true; fi
        saved="$candidate"
        first_token="$token"
    done
    if [ -n "$saved" ] && ! "$multiple"; then install_file "$saved" "$game/$pairing"; fi
fi

echo "Installed $version with Fabric, DABOIS Link, and Simple Voice Chat."
uninstaller='/Library/Application Support/ZeroTier/One/uninstall.sh'
remove_zerotier=false
if [ -f "$uninstaller" ]; then
    action="$(/usr/bin/osascript -e 'button returned of (display dialog "DABOIS is installed. Remove the standalone ZeroTier app from this Mac? Other ZeroTier networks may still need it." with title "DABOIS Setup" buttons {"Keep ZeroTier", "Remove ZeroTier"} default button "Keep ZeroTier")')" || action=''
    if [ "$action" = 'Remove ZeroTier' ]; then remove_zerotier=true; fi
fi
if "$remove_zerotier"; then
    [ ! -L "$uninstaller" ] && [ "$(/usr/bin/stat -f '%u' "$uninstaller")" = 0 ] ||
        stop 'The ZeroTier uninstaller is not owned by root. The HMCL instance is ready.'
    echo 'Removing the standalone ZeroTier app. macOS will ask for your administrator password.'
    /usr/bin/sudo -v || stop 'Administrator approval is needed to remove ZeroTier. The HMCL instance is ready.'
    old_state="$(dirname -- "$uninstaller")"
    new_state="$game/config/dabois-link/embedded-zerotier"
    carried=false
    if [ ! -e "$new_state/identity.secret" ] && [ ! -e "$new_state/identity.public" ] &&
        /usr/bin/sudo /usr/bin/test -f "$old_state/identity.secret" &&
        /usr/bin/sudo /usr/bin/test -f "$old_state/identity.public"; then
        /usr/bin/sudo /bin/cat "$old_state/identity.secret" > "$work/identity.secret"
        /usr/bin/sudo /bin/cat "$old_state/identity.public" > "$work/identity.public"
        if /usr/bin/awk -F: 'NR==1 {key=$1; tag=$2; pub=$3; next}
            NR==2 {exit !(length(key)==10 && key==$1 && tag==$2 && pub==$3 && NF==4)}
            END {if(NR!=2) exit 1}' "$work/identity.public" "$work/identity.secret"; then
            carried=true
        fi
    fi
    /usr/bin/sudo "$uninstaller" || stop 'The ZeroTier uninstall did not complete. The HMCL instance is ready.'
    if "$carried"; then
        install_file "$work/identity.public" "$new_state/identity.public"
        install_file "$work/identity.secret" "$new_state/identity.secret"
        chmod 600 "$new_state/identity.public" "$new_state/identity.secret"
        echo 'Moved the existing ZeroTier identity into the Fabric instance on this Mac.'
    fi
fi

if "$fresh"; then
    echo "Open $launcher/Open HMCL.command and select $version."
else
    echo "Open your Minecraft launcher and select $version."
fi
if [ -z "$client" ] || [ ! -s "$asset_index" ]; then
    echo 'Minecraft will download any uncached game files on the first online launch.'
fi
echo 'When online, open Multiplayer and Pair server. If the embedded device is new, the host can approve the node shown there.'
exit 0

: <<'DABOIS_PAYLOAD'
