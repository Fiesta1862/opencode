#!/bin/sh
# minicode installer
#
#   curl -fsSL https://github.com/Fiesta1862/opencode/releases/latest/download/install.sh | sh
#
# Environment variables (set them on the sh side of a pipe):
#   MINICODE_REPO         GitHub repo, e.g. yourname/minicode (required if not baked in)
#   MINICODE_VERSION      Version to install, e.g. 0.1.2 (default: latest)
#   MINICODE_INSTALL_DIR  Install directory (default: ~/.local/bin)
#
#   curl -fsSL .../install.sh | MINICODE_VERSION=0.1.2 sh
set -eu

REPO="${MINICODE_REPO:-Fiesta1862/opencode}"
VERSION="${MINICODE_VERSION:-latest}"
VERSION="${VERSION#v}"
INSTALL_DIR="${MINICODE_INSTALL_DIR:-$HOME/.local/bin}"

case "$REPO" in
  OWNER/*)
    echo "This installer still points at the placeholder repo." >&2
    echo "Set MINICODE_REPO=yourname/minicode and try again." >&2
    exit 1
    ;;
esac

info() { printf '\033[36m%s\033[0m\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

case "$(uname -s)" in
  Linux*) os=linux ;;
  Darwin*) os=darwin ;;
  MINGW*|MSYS*|CYGWIN*) os=windows ;;
  *) fail "Unsupported operating system: $(uname -s)" ;;
esac

case "$(uname -m)" in
  x86_64|amd64) arch=x64 ;;
  arm64|aarch64) arch=arm64 ;;
  *) fail "Unsupported architecture: $(uname -m)" ;;
esac

if [ "$os" = windows ] && [ "$arch" != x64 ]; then
  fail "Windows builds are only available for x64. Download the .exe from GitHub Releases."
fi

libc=glibc
if [ "$os" = linux ]; then
  if [ -f /etc/alpine-release ] || ldd --version 2>&1 | head -n 1 | grep -qi musl; then
    libc=musl
  fi
fi

asset="minicode-${os}-${arch}"
if [ "$libc" = musl ]; then
  asset="${asset}-musl"
fi
if [ "$os" = windows ]; then
  asset="${asset}.exe"
fi

if [ "$VERSION" = latest ]; then
  base_url="https://github.com/${REPO}/releases/latest/download"
else
  base_url="https://github.com/${REPO}/releases/download/v${VERSION}"
fi

command -v curl >/dev/null 2>&1 || fail "curl is required."
command -v install >/dev/null 2>&1 || fail "the 'install' utility is required."

# Bun's musl builds link against libstdc++, but a minimal Alpine install does
# not include it. The .apk declares this dependency; the raw binary path must
# handle it here.
need_libstdcxx=0
if [ "$os" = linux ] && [ "$libc" = musl ]; then
  if ! ls /lib/libstdc++.so.6* /usr/lib/libstdc++.so.6* >/dev/null 2>&1; then
    if [ "$(id -u)" = 0 ] && command -v apk >/dev/null 2>&1; then
      info "Installing libstdc++ (required by the musl build)…"
      if ! apk add --no-cache libstdc++ >/dev/null 2>&1; then
        need_libstdcxx=1
      fi
    else
      need_libstdcxx=1
    fi
  fi
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

info "Downloading ${asset} from ${REPO} (${VERSION})…"
curl -fsSL "${base_url}/${asset}" -o "${tmp}/${asset}" || fail "Download failed: ${base_url}/${asset}"

if curl -fsSL "${base_url}/checksums.txt" -o "${tmp}/checksums.txt" 2>/dev/null; then
  expected="$(awk -v a="$asset" '$2 == a { print $1 }' "${tmp}/checksums.txt")"
  if [ -n "$expected" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      actual="$(sha256sum "${tmp}/${asset}" | awk '{ print $1 }')"
    elif command -v shasum >/dev/null 2>&1; then
      actual="$(shasum -a 256 "${tmp}/${asset}" | awk '{ print $1 }')"
    else
      actual=""
      warn "no sha256 tool found; skipping checksum verification"
    fi
    if [ -n "$actual" ] && [ "$actual" != "$expected" ]; then
      fail "Checksum mismatch for ${asset}. Refusing to install."
    fi
  fi
else
  warn "checksums.txt unavailable; skipping verification"
fi

mkdir -p "$INSTALL_DIR"
install -m 0755 "${tmp}/${asset}" "${INSTALL_DIR}/minicode"

info "Installed minicode to ${INSTALL_DIR}/minicode"

if ! command -v minicode >/dev/null 2>&1; then
  echo
  warn "${INSTALL_DIR} is not on your PATH."
  echo "  Add this to your shell profile:"
  echo "    export PATH=\"${INSTALL_DIR}:\$PATH\""
fi

if [ "$need_libstdcxx" = 1 ]; then
  echo
  warn "libstdc++ is required by the musl build but is not installed."
  echo "  Alpine: sudo apk add libstdc++"
  echo "  Or install the .apk package, which pulls it automatically:"
  echo "    https://github.com/${REPO}/releases/latest"
else
  echo
  "${INSTALL_DIR}/minicode" --version || true
fi
echo "Run 'minicode --help' to get started."
