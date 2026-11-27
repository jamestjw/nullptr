#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'nullptr uninstaller: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 0 ]]; then
    die "usage: $0"
fi
if ((EUID == 0)); then
    die 'run this as your desktop user, without sudo'
fi
for command in flock systemctl; do
    command -v "$command" >/dev/null || die "required command not found: $command"
done

config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
runtime_dir=${XDG_RUNTIME_DIR:-"/run/user/$EUID"}
unit=nullptr-companion.service

exec 9>"$runtime_dir/nullptr-install.lock"
flock -n 9 || die 'another nullptr installation or removal is running'

load_state=$(systemctl --user show --property=LoadState --value "$unit") ||
    die 'cannot reach the systemd user manager; run this from your desktop session'
if [[ $load_state != not-found ]]; then
    systemctl --user stop "$unit"
fi

rm -f -- "$config_home/autostart/nullptr-companion.desktop" \
    "$config_home/systemd/user/$unit" \
    "$HOME/.local/bin/nullptr-companion"
systemctl --user daemon-reload
printf '%s\n' 'Uninstalled the nullptr companion. Your pairing credential was kept.'
