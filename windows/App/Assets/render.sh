#!/bin/sh
# Renders the package's images from art/icon (run from anywhere; needs rsvg-convert).
# Tiles and the Store logo use the full square icon; the taskbar and Start menu (the unplated
# Square44x44Logo sizes) use the Mac icon's rounded shape, as Windows 11 apps' icons are.
set -eu
cd "$(dirname "$0")"
art=../../../art/icon
square="$art/pocketkeepass-icon.svg"
rounded="$art/pocketkeepass-icon-mac.svg"

render() { rsvg-convert -w "$2" -h "$3" "$1" -o "$4"; }

for scale in 100 200; do
  render "$square" $((150 * scale / 100)) $((150 * scale / 100)) "Square150x150Logo.scale-$scale.png"
  render "$square" $((44 * scale / 100)) $((44 * scale / 100)) "Square44x44Logo.scale-$scale.png"
  render "$square" $((50 * scale / 100)) $((50 * scale / 100)) "StoreLogo.scale-$scale.png"
done
for size in 16 24 32 48 256; do
  render "$rounded" $size $size "Square44x44Logo.targetsize-${size}_altform-unplated.png"
done
# The splash screen and wide tile (both about 2:1): the glyph centred on the icon's background
# colour, two thirds of the height, by widening the glyph's view box.
wide() {
  sed 's/viewBox="18 18 72 72" width="512" height="512"/viewBox="-57.6 0 223.2 108" width="620" height="300"/' \
    "$art/pocketkeepass-glyph.svg" > wide.tmp.svg
  rsvg-convert -w "$1" -h "$2" -b '#1B1F2A' wide.tmp.svg -o "$3"
}
for scale in 100 200; do
  wide $((620 * scale / 100)) $((300 * scale / 100)) "SplashScreen.scale-$scale.png"
  wide $((310 * scale / 100)) $((150 * scale / 100)) "Wide310x150Logo.scale-$scale.png"
done
rm -f wide.tmp.svg
