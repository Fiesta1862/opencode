#!/usr/bin/env bash
# minicode installer
#
#   curl -fsSL https://github.com/Fiesta1862/opencode/releases/latest/download/install.sh | bash
#
# Environment variables:
#   MINICODE_REPO         GitHub repo, e.g. yourname/minicode (required if not baked in)
#   MINICODE_VERSION      Version to install, e.g. 0.1.0 (default: latest)
#   MINICODE_INSTALL_DIR  Install directory (default: ~/.local/bin)
set -euo pipefail

REPO="${MINICODE_REPO:-Fiesta1862/opencode}"
VERSION="${MINICODE_VERSION:-latest}"
VERSION="${VERSION#v}"
INSTALL_DIR="${MINICODE_INSTALL_DIR:-$HOME/.local/bin}"

if [[ "$REPO" == OWNER/* ]]; then
  echo "This installer still points at the placeholder repo." >&2
  echo "Set MINICODE_REPO=yourname/minicode and try again." >&2
  exit 1
fi

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

if [[ "$os" == windows && "$arch" != x64 ]]; then
  fail "Windows builds are only available for x64. Download the .exe from GitHub Releases."
fi

asset="minicode-${os}-${arch}"
[[ "$os" == windows ]] && asset="${asset}.exe"

if [[ "$VERSION" == latest ]]; then
  base_url="https://github.com/${REPO}/releases/latest/download"
else
  base_url="https://github.com/${REPO}/releases/download/v${VERSION}"
fi

command -v curl >/dev/null 2>&1 || fail "curl is required."
command -v install >/dev/null 2>&1 || fail "the 'install' utility is required."

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

info "Downloading ${asset} from ${REPO} (${VERSION})…"
curl -fsSL "${base_url}/${asset}" -o "${tmp}/${asset}" || fail "Download failed: ${base_url}/${asset}"

if curl -fsSL "${base_url}/checksums.txt" -o "${tmp}/checksums.txt" 2>/dev/null; then
  expected="$(awk -v a="$asset" '$2 == a { print $1 }' "${tmp}/checksums.txt")"
  if [[ -n "$expected" ]]; then
    if command -v sha256sum >/dev/null 2>&1; then
      actual="$(sha256sum "${tmp}/${asset}" | awk '{ print $1 }')"
    elif command -v shasum >/dev/null 2>&1; then
      actual="$(shasum -a 256 "${tmp}/${asset}" | awk '{ print $1 }')"
    else
      actual=""
      warn "no sha256 tool found; skipping checksum verification"
    fi
    if [[ -n "$actual" && "$actual" != "$expected" ]]; then
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

echo
"${INSTALL_DIR}/minicode" --version || true
echo "Run 'minicode --help' to get started."
