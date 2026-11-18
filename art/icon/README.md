# App icon

The padlock-shaped pocket. It's our own design; it doesn't use KeePass's logo.

- `pocketkeepass-icon.svg`: the full icon (background and glyph), the source for the others.
- `pocketkeepass-glyph.svg`: the glyph alone.
- `play-store-512.png`: the 512 × 512 store icon.

The app uses the adaptive icon in `app/src/main/res` (`mipmap-anydpi-v26/ic_launcher.xml`, with
`drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml` for themed icons, and
the `ic_launcher_background` color). The minimum SDK is 26, so no PNG launcher icons are needed.
- `feature-graphic.svg`: Google Play's 1024 × 500 feature graphic (the glyph, the name, and two lines), drawn
  over the icon's background color. `rsvg-convert -w 1024 -h 500 feature-graphic.svg -o feature-graphic-1024x500.png`
  renders it.
