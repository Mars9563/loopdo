#!/usr/bin/env bash
# Builds loopdo_ for this laptop and installs it in your home folder (no root):
#   ~/.local/opt/loopdo/loopdo.jar, ~/.local/bin/loopdo, the menu entry, and (with --autostart) start at login.
# Needs Java 21+ on the PATH (Arch: jre-openjdk). Usage: desktop/packaging/install-local.sh [--autostart] [--no-build]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; root="$(cd "$here/../.." && pwd)"
autostart=false; build=true
for a in "$@"; do case "$a" in --autostart) autostart=true ;; --no-build) build=false ;; esac; done
if $build; then (cd "$root" && JAVA_HOME="${LOOPDO_BUILD_JAVA_HOME:-/opt/android-studio/jbr}" ./gradlew -q :desktop:packageUberJarForCurrentOS); fi
jar=$(ls "$root"/desktop/build/compose/jars/loopdo-linux-x64-*.jar | sort -V | tail -1)
opt="$HOME/.local/opt/loopdo"; bin="$HOME/.local/bin"; apps="$HOME/.local/share/applications"; icons="$HOME/.local/share/icons/hicolor/256x256/apps"
mkdir -p "$opt" "$bin" "$apps" "$icons"
cp "$jar" "$opt/loopdo.jar.new" && mv "$opt/loopdo.jar.new" "$opt/loopdo.jar"
cp "$here/loopdo.png" "$icons/loopdo.png"
cat > "$bin/loopdo" <<SH
#!/usr/bin/env bash
exec java --enable-native-access=ALL-UNNAMED -jar "$opt/loopdo.jar" "\$@"
SH
chmod +x "$bin/loopdo"
sed "s|@BIN@|$bin/loopdo|g" "$here/loopdo.desktop" > "$apps/loopdo.desktop"
if $autostart; then mkdir -p "$HOME/.config/autostart"; sed "s|@BIN@|$bin/loopdo|g" "$here/loopdo-autostart.desktop" > "$HOME/.config/autostart/loopdo.desktop"; fi
command -v update-desktop-database >/dev/null && update-desktop-database -q "$apps" || true
echo "installed: $bin/loopdo (menu: loopdo_)$($autostart && echo ', starts at login')"
