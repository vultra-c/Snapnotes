#!/bin/sh
set -e

echo "[setup-emulator] initializing Vela emulator environment..."
npx aiot initEmulatorEnv || true

echo "---"
echo "Next steps on your server:"
echo "1) Create/select a VVD inside the container (AIoT IDE flow or VvdManager scripts)"
echo "2) Build an RPK: npx aiot build"
echo "3) Start an emulator instance and install/start the RPK"
echo "4) Open: https://your-server:6080/vnc.html?autoconnect=1&resize=scale"
echo "---"
echo "Web workbench remains on Render at / and can link to this noVNC URL."
