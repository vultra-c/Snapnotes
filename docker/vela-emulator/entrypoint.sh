#!/bin/sh
set -e

Xvfb :99 -screen 0 1280x800x24 >/tmp/xvfb.log 2>&1 &
export DISPLAY=:99
sleep 2

fluxbox >/tmp/fluxbox.log 2>&1 &
sleep 1

x11vnc -display :99 -nopw -listen 0.0.0.0 -xkb -ncache 10 -ncache_cr -forever >/tmp/x11vnc.log 2>&1 &
websockify --web /usr/share/novnc 6080 localhost:5900 >/tmp/websockify.log 2>&1 &

echo "[vela-emulator] Xvfb/VNC/noVNC started on :99 / :5900 / :6080"

if [ "$1" != "" ]; then
  exec "$@"
fi

tail -f /tmp/xvfb.log /tmp/x11vnc.log /tmp/websockify.log 2>/dev/null || sleep infinity
