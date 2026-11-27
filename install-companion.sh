#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'nullptr installer: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 0 ]]; then
    die "usage: $0"
fi
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
    die 'the published companion requires x86-64 Linux'
fi
if ((EUID == 0)); then
    die 'run this as your desktop user, without sudo'
fi
for command in curl jq sha256sum tar flock ss systemctl tailscale; do
    command -v "$command" >/dev/null || die "required command not found: $command"
done

config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
runtime_dir=${XDG_RUNTIME_DIR:-"/run/user/$EUID"}
bin_dir="$HOME/.local/bin"
unit_dir="$config_home/systemd/user"
autostart_dir="$config_home/autostart"
unit=nullptr-companion.service

manager_env=$(systemctl --user show-environment) ||
    die 'cannot reach the systemd user manager; run this from your desktop session'
exec 9>"$runtime_dir/nullptr-install.lock"
flock -n 9 || die 'another nullptr installation is running'

scratch=$(mktemp -d)
staged=
cleanup() {
    if [[ -n $staged ]]; then
        rm -f -- "$staged"
    fi
    rm -rf -- "$scratch"
}
trap cleanup EXIT

download() {
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        --retry 3 --connect-timeout 10 --max-time 120 --output "$2" "$1"
}

repo=jamestjw/nullptr
download "https://api.github.com/repos/$repo/releases/latest" "$scratch/release.json"
tag=$(jq -er 'select(.draft == false and .prerelease == false) | .tag_name' "$scratch/release.json") ||
    die 'could not determine the latest stable GitHub release'
[[ $tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "unexpected release tag: $tag"

archive="nullptr-companion-linux-x86_64-$tag.tar.gz"
release_url="https://github.com/$repo/releases/download/$tag"
printf 'Downloading nullptr companion %s...\n' "$tag"
download "$release_url/$archive" "$scratch/$archive"
download "$release_url/SHA256SUMS.txt" "$scratch/SHA256SUMS.txt"
awk -v archive="$archive" '
    NF == 2 && $2 == archive && length($1) == 64 && $1 !~ /[^[:xdigit:]]/ {
        print $1 "  " archive
        count++
    }
    END { if (count != 1) exit 1 }
' "$scratch/SHA256SUMS.txt" >"$scratch/companion.sha256" ||
    die 'release does not contain exactly one checksum for the companion'
(cd "$scratch" && sha256sum --check --status companion.sha256) ||
    die 'companion checksum does not match; installation was not changed'
[[ $(tar -tzf "$scratch/$archive") == nullptr-companion ]] ||
    die 'companion archive has unexpected contents'
tar -xOzf "$scratch/$archive" -- nullptr-companion >"$scratch/nullptr-companion"
chmod 755 "$scratch/nullptr-companion"
if ! "$scratch/nullptr-companion" --help >"$scratch/help.log" 2>&1; then
    cat "$scratch/help.log" >&2
    die 'the release companion cannot run on this system'
fi

cat >"$scratch/$unit" <<'EOF'
[Unit]
Description=nullptr trackpad companion
ConditionEnvironment=DISPLAY
StartLimitIntervalSec=0

[Service]
Type=exec
ExecCondition=/usr/bin/test -s %E/nullptr/token
ExecStart=%h/.local/bin/nullptr-companion
Restart=on-failure
RestartSec=5s
StandardOutput=null
NoNewPrivileges=yes
EOF

cat >"$scratch/nullptr-companion.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=nullptr trackpad companion
Exec=systemctl --user start nullptr-companion.service
Terminal=false
NoDisplay=true
EOF

mkdir -p -- "$bin_dir" "$unit_dir" "$autostart_dir"
service_changed=false
install_file() {
    local source=$1 destination=$2 mode=$3
    if [[ ! -L $destination && -f $destination && $(stat -c %a -- "$destination") == "$mode" ]] &&
        cmp -s -- "$source" "$destination"; then
        return
    fi
    staged=$(mktemp "${destination}.XXXXXX")
    install -m "$mode" -- "$source" "$staged"
    mv -fT -- "$staged" "$destination"
    staged=
    service_changed=true
}

install_file "$scratch/nullptr-companion.desktop" "$autostart_dir/nullptr-companion.desktop" 644
service_changed=false
install_file "$scratch/nullptr-companion" "$bin_dir/nullptr-companion" 755
install_file "$scratch/$unit" "$unit_dir/$unit" 644
systemctl --user daemon-reload
printf 'Installed nullptr companion %s. It starts at X11 desktop login.\n' "$tag"

if [[ ! -s $config_home/nullptr/token ]]; then
    printf 'To pair, run %q --pair in your X11 terminal. After pairing, press Ctrl+C and run:\n' \
        "$bin_dir/nullptr-companion"
    printf '  systemctl --user start %s\n' "$unit"
elif ! grep -q '^DISPLAY=.' <<<"$manager_env"; then
    printf 'No X11 display is available to systemd yet. The service will start at desktop login.\n'
elif systemctl --user is-active --quiet "$unit"; then
    if [[ $service_changed == true ]]; then
        systemctl --user restart "$unit"
    fi
elif [[ -n $(ss -Hltn 'sport = :47822') ]]; then
    printf 'TCP port 47822 is already in use. Stop the existing companion, then run:\n'
    printf '  systemctl --user start %s\n' "$unit"
else
    systemctl --user start "$unit"
fi
