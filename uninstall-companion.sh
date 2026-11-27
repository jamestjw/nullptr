#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'nullptr uninstaller: %s\n' "$*" >&2
    exit 1
}

if (($# > 1)); then
    die "usage: $0 [--keep-token | --purge]"
fi
case ${1-} in
'') purge=ask ;;
--keep-token) purge=no ;;
--purge) purge=yes ;;
*) die "usage: $0 [--keep-token | --purge]" ;;
esac

if ((EUID == 0)); then
    die 'run this as your desktop user, without sudo'
fi
for command in flock systemctl; do
    command -v "$command" >/dev/null || die "required command not found: $command"
done

config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
runtime_dir=${XDG_RUNTIME_DIR:-"/run/user/$EUID"}
unit=nullptr-companion.service
token="$config_home/nullptr/token"

exec 9>"$runtime_dir/nullptr-install.lock"
flock -n 9 || die 'another nullptr maintenance command is running'

if [[ $purge == ask && -t 0 && (-e $token || -L $token) ]]; then
    if read -r -p 'Delete the saved pairing credential? [y/N] ' answer; then
        case ${answer,,} in
        y | yes) purge=yes ;;
        esac
    fi
fi

load_state=$(systemctl --user show --property=LoadState --value "$unit") ||
    die 'cannot reach the systemd user manager; run this from your desktop session'
if [[ $load_state != not-found ]]; then
    systemctl --user stop "$unit"
fi

if [[ $purge == yes ]]; then
    rm -f -- "$token"
fi
rm -f -- "$config_home/autostart/nullptr-companion.desktop" \
    "$config_home/systemd/user/$unit" \
    "$HOME/.local/bin/nullptr-companion"
systemctl --user daemon-reload
if [[ $purge == yes ]]; then
    printf '%s\n' 'Uninstalled the nullptr companion and cleared its saved pairing.'
else
    printf '%s\n' 'Uninstalled the nullptr companion without deleting its pairing credential.'
fi
