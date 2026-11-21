#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
role='__ROLE__'
case "$(uname -s)" in
    Darwin) os=mac ;;
    Linux) os=linux ;;
    *) printf '%s\n' 'Choose the DABOIS setup for your operating system.'; exit 1 ;;
esac
case "$(uname -m)" in
    arm64|aarch64) arch=aarch64 ;;
    x86_64|amd64) arch=x64 ;;
    *) printf '%s\n' 'This processor is unsupported by this setup package.'; exit 1 ;;
esac
line=$(awk -F '\t' -v id="$os-$arch" '$1==id {print $2 " " $3; exit}' java-downloads.tsv)
[ -n "$line" ] || { echo 'No Java runtime was supplied for this computer.'; exit 1; }
hash=${line%% *}
url=${line#* }
case "$hash" in *[!0-9a-f]*|'') echo 'The Java download information failed verification.'; exit 1 ;; esac
[ "${#hash}" -eq 64 ] || exit 1
case "$url" in https://github.com/adoptium/*) ;; *) echo 'The Java download source is invalid.'; exit 1;; esac
if [ "$os" = mac ]; then
    cache="$HOME/Library/Caches/DABOIS/Java/$hash"
    java="$cache/Contents/Home/bin/java"
else
    cache="${XDG_CACHE_HOME:-$HOME/.cache}/dabois/java/$hash"
    java="$cache/bin/java"
fi
work=''
finish() {
    result=$?
    if [ -n "$work" ]; then rm -rf -- "$work"; fi
    if [ "$result" -ne 0 ]; then printf '\nSetup stopped. See the message above.\n'; fi
    if [ "$os" = mac ] && [ -t 0 ]; then
        printf '%s' 'Press Return to close. '
        read -r ignored || true
    fi
}
trap finish EXIT
if [ ! -x "$java" ]; then
    echo 'Preparing Java for DABOIS. This is usually only needed once.'
    command -v curl >/dev/null || { echo 'This computer needs curl to download the Java runtime.'; exit 1; }
    mkdir -p -- "$(dirname -- "$cache")"
    work=$(mktemp -d "$(dirname -- "$cache")/.download.XXXXXXXX")
    curl --fail --show-error --silent --location --retry 2 --max-time 180 "$url" -o "$work/java.tar.gz"
    if [ "$os" = mac ]; then
        actual=$(shasum -a 256 "$work/java.tar.gz" | awk '{print $1}')
    else
        actual=$(sha256sum "$work/java.tar.gz" | awk '{print $1}')
    fi
    [ "$actual" = "$hash" ] || { echo 'The Java download failed verification.'; exit 1; }
    mkdir -p -- "$work/runtime"
    tar -xzf "$work/java.tar.gz" -C "$work/runtime" --strip-components=1
    if [ ! -d "$cache" ]; then mv -- "$work/runtime" "$cache"; fi
fi
[ -x "$java" ] || { echo 'The Java launcher is missing. Remove the downloaded runtime and try again.'; exit 1; }
"$java" --enable-native-access=ALL-UNNAMED -jar DABOIS-Setup.jar "$role"
