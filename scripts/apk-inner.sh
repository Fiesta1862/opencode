#!/bin/sh
# Build Alpine .apk packages with abuild. Runs inside an Alpine container.
# Called by scripts/package-apk.ts; expects the repository mounted at /work.
set -eu

ROOT=${ROOT:-/work}
VERSION=$(sed -n 's/^  "version": "\([^"]*\)".*/\1/p' "$ROOT/package.json")
[ -n "$VERSION" ] || { echo "could not read version from $ROOT/package.json" >&2; exit 1; }

owner=$(stat -c '%u:%g' "$ROOT")

apk add --no-cache alpine-sdk >/dev/null
abuild-keygen -a -n >/dev/null
# Trust our ephemeral key so `abuild -r` can index the packages it just signed.
cp /root/.abuild/*.rsa.pub /etc/apk/keys/

for pair in "x86_64:minicode-linux-x64-musl" "aarch64:minicode-linux-arm64-musl"; do
  arch=${pair%%:*}
  bin=${pair#*:}
  [ -f "$ROOT/dist/$bin" ] || { echo "missing dist/$bin; run 'bun run build:all' first" >&2; exit 1; }

  work="/tmp/minicode-apk-$arch"
  rm -rf "$work"
  mkdir -p "$work"
  cp "$ROOT/dist/$bin" "$work/minicode"

  # abuild expects "hash  filename" entries in the checksum array.
  sum=$(sha512sum "$work/minicode" | awk '{print $1 "  minicode"}')

  cat > "$work/APKBUILD" <<EOF
# Maintainer: minicode maintainers <minicode@users.noreply.github.com>
pkgname=minicode
pkgver=$VERSION
pkgrel=0
pkgdesc="A minimal AI coding agent for the terminal"
url="https://github.com/Fiesta1862/opencode"
arch="$arch"
license="MIT"
# Never strip the prebuilt Bun binary. tracedeps cannot resolve aarch64
# dependencies on an x86_64 host, so declare libstdc++ explicitly.
options="!check !strip !tracedeps"
depends="libstdc++"
source="minicode"
sha512sums="$sum"

package() {
	install -Dm755 "\$srcdir/minicode" "\$pkgdir/usr/bin/minicode"
}
EOF

  echo "▸ building Alpine package for $arch"
  ( cd "$work" && CARCH=$arch abuild -F -d -r )

  # abuild drops the arch from the package filename; find the one for this arch.
  found=$(find /root/packages -path "*/$arch/minicode-$VERSION-r0.apk" -print -quit)
  [ -n "$found" ] || { echo "abuild did not produce a package for $arch" >&2; exit 1; }

  built_arch=$(tar -xOzf "$found" .PKGINFO | sed -n 's/^arch = //p')
  [ "$built_arch" = "$arch" ] || {
    echo "package $found has arch=$built_arch, expected $arch" >&2
    exit 1
  }
  tar -tzf "$found" | grep -q '^\.SIGN\.RSA\.' || {
    echo "package $found is not signed" >&2
    exit 1
  }

  dest="$ROOT/dist/minicode-$VERSION-r0.$arch.apk"
  cp -f "$found" "$dest"
  chown "$owner" "$dest"
done
