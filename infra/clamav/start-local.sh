#!/bin/sh
# Local single-daemon mode: the container must exit when clamd exits.
set -eu

# The upstream /init option-only branch configures clamd and execs it, but
# skips database bootstrap and stale-socket cleanup. Perform those first.
mkdir -p /var/lib/clamav
chown -R clamav:clamav /var/lib/clamav
if { [ ! -s /var/lib/clamav/main.cvd ] && [ ! -s /var/lib/clamav/main.cld ]; } ||
   { [ ! -s /var/lib/clamav/daily.cvd ] && [ ! -s /var/lib/clamav/daily.cld ]; }; then
    echo 'Initializing ClamAV signatures before starting the scanner'
    initial_config=$(mktemp /tmp/openhire-freshclam.XXXXXX)
    trap 'rm -f "$initial_config"' EXIT HUP INT TERM
    # Avoid a second in-memory database and notifying a not-yet-running daemon.
    sed -e '/^[[:space:]]*TestDatabases[[:space:]]/d' \
        -e '/^[[:space:]]*NotifyClamd[[:space:]]/d' \
        /etc/clamav/freshclam.conf > "$initial_config"
    printf '\nTestDatabases no\n' >> "$initial_config"
    chmod 644 "$initial_config"
    freshclam --foreground --stdout --config-file="$initial_config"
    rm -f "$initial_config"
    trap - EXIT HUP INT TERM
fi

for socket in /run/clamav/clamd.sock /tmp/clamd.sock; do
    if [ -S "$socket" ]; then unlink "$socket"; fi
done

# /init appends environment overrides on each start. Remove the managed
# setting first so repeated restarts do not accumulate conflicting entries.
sed -i '/^[[:space:]]*ConcurrentDatabaseReload[[:space:]]/d' /etc/clamav/clamd.conf
export CLAMD_CONF_ConcurrentDatabaseReload=no
# Never let the upstream healthcheck silently skip its scanner PING.
unset CLAMAV_NO_CLAMD
echo 'Starting ClamAV in foreground; daemon exit will restart the container'
exec /init --foreground
