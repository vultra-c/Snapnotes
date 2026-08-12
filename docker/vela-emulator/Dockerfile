FROM node:22-bookworm

RUN apt-get update && apt-get install -y \
  qemu-system-x86 qemu-utils \
  xvfb x11vnc fluxbox websockify novnc \
  python3 unzip curl ca-certificates \
  libgl1 libpulse0 libasound2 \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

RUN mkdir -p /root/.vela/sdk /root/.vela/vvd /workspace/scripts
COPY docker/vela-emulator/entrypoint.sh /entrypoint.sh
COPY scripts/setup-emulator.sh /workspace/scripts/setup-emulator.sh
RUN chmod +x /entrypoint.sh /workspace/scripts/setup-emulator.sh

EXPOSE 6080 5900 5555 10055

ENTRYPOINT ["/entrypoint.sh"]
