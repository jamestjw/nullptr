#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'nullptr pairing reset: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 0 ]]; then
    die "usage: $0"
fi
if ((EUID == 0)); then
    die 'run this as your desktop user, without sudo'
fi
for command in flock ss systemctl; do
    command -v "$command" >/dev/null || die "required command not found: $command"
done

config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
runtime_dir=${XDG_RUNTIME_DIR:-"/run/user/$EUID"}
binary="$HOME/.local/bin/nullptr-companion"
unit=nullptr-companion.service

exec 9>"$runtime_dir/nullptr-install.lock"
flock -n 9 || die 'another nullptr maintenance command is running'

[[ -x $binary && -f $config_home/systemd/user/$unit ]] ||
    die 'the companion service is not installed; use install-companion.sh first'
systemctl --user stop "$unit"
listeners=$(ss -Hltn 'sport = :47822') || die 'could not check the companion port'
[[ -z $listeners ]] || die 'TCP port 47822 is still in use; stop the other listener and try again'
rm -f -- "$config_home/nullptr/token"

printf 'Pairing credential cleared. To show a new QR code, run:\n  %q --pair\n' "$binary"
printf 'After pairing, press Ctrl+C and run:\n  systemctl --user start %s\n' "$unit"
