#!/usr/bin/env bash
# KubeFn CLI Installer
# Usage: curl -sf https://kubefn.com/install.sh | sh
set -euo pipefail

VERSION="${KUBEFN_VERSION:-latest}"
INSTALL_DIR="${KUBEFN_INSTALL_DIR:-/usr/local/bin}"
REPO="kubefn/kubefn"

echo "Installing kubefn CLI..."

# Determine download URL
if [ "$VERSION" = "latest" ]; then
    DOWNLOAD_URL="https://raw.githubusercontent.com/${REPO}/main/kubefn-cli/kubefn"
else
    DOWNLOAD_URL="https://raw.githubusercontent.com/${REPO}/v${VERSION}/kubefn-cli/kubefn"
fi

# Download
TMPFILE=$(mktemp)
if command -v curl > /dev/null 2>&1; then
    curl -sfL "$DOWNLOAD_URL" -o "$TMPFILE"
elif command -v wget > /dev/null 2>&1; then
    wget -q "$DOWNLOAD_URL" -O "$TMPFILE"
else
    echo "Error: curl or wget required"
    exit 1
fi

# Verify download
if [ ! -s "$TMPFILE" ]; then
    echo "Error: Failed to download kubefn CLI"
    rm -f "$TMPFILE"
    exit 1
fi

# Install
if [ -w "$INSTALL_DIR" ]; then
    mv "$TMPFILE" "$INSTALL_DIR/kubefn"
    chmod +x "$INSTALL_DIR/kubefn"
else
    echo "Installing to $INSTALL_DIR (requires sudo)..."
    sudo mv "$TMPFILE" "$INSTALL_DIR/kubefn"
    sudo chmod +x "$INSTALL_DIR/kubefn"
fi

echo ""
echo "kubefn installed to $INSTALL_DIR/kubefn"
echo ""
kubefn version 2>/dev/null || echo "Run: kubefn --help"
