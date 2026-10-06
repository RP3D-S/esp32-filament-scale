#!/usr/bin/env bash
# PC side of the ESP32 PPP bridge (Linux). Run as a normal user; it uses sudo.
#   usage: pc/start-bridge.sh [serial-device] [baud]
set -euo pipefail

DEV="${1:-/dev/ttyUSB0}"
BAUD="${2:-460800}"          # must match CONFIG_BRIDGE_PPP_BAUD
PC_IP=10.0.0.1
ESP_IP=10.0.0.2
SUBNET=10.0.0.0/30

WAN="$(ip route show default | awk '/default/ {print $5; exit}')"
[ -n "$WAN" ] || { echo "no default route: the PC has no internet" >&2; exit 1; }
[ -e "$DEV" ] || { echo "$DEV not found" >&2; exit 1; }

echo "serial=$DEV baud=$BAUD wan=$WAN"

OLD_FWD="$(sysctl -n net.ipv4.ip_forward)"
sudo sysctl -q -w net.ipv4.ip_forward=1
sudo iptables -t nat -A POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE
sudo iptables -I FORWARD -s "$SUBNET" -j ACCEPT
sudo iptables -I FORWARD -d "$SUBNET" -m state --state ESTABLISHED,RELATED -j ACCEPT

cleanup() {
    sudo iptables -t nat -D POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE || true
    sudo iptables -D FORWARD -s "$SUBNET" -j ACCEPT || true
    sudo iptables -D FORWARD -d "$SUBNET" -m state --state ESTABLISHED,RELATED -j ACCEPT || true
    sudo sysctl -q -w net.ipv4.ip_forward="$OLD_FWD"
}
trap cleanup EXIT INT TERM

# Opening the port toggles DTR/RTS, which can reset the ESP32 once. The
# firmware's supervisor then retries until PPP comes up.
sudo pppd "$DEV" "$BAUD" "$PC_IP:$ESP_IP" \
    local noauth nodetach nocrtscts nodefaultroute \
    ms-dns 1.1.1.1 ms-dns 8.8.8.8 \
    lcp-echo-interval 10 lcp-echo-failure 3 \
    debug
