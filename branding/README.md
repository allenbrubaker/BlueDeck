# BlueDeck fork icon

`bluedeck-ioniq-source.png` is the original approved artwork: an electric-blue
IONIQ-inspired car with an open proximity ring. Keep this source unmodified.
`logo.svg` is the earlier upstream artwork, retained for reference.

Regenerate the packaged resources with ImageMagick 6 or 7:

```sh
bash branding/generate-icons.sh
```

The script produces the shared launcher/splash bitmap and the square and round
launcher fallbacks. The adaptive layer uses a 1080px canvas with the source
centered at 640px; the car and ring remain inside the central safe area. Padding
extends the source's outermost pixels, preserving the original design without
adding a visible frame. Android applies the final mask to the adaptive icon.

The launcher and splash drawables share this padded bitmap. Their background
color is midnight navy (`#021638`), including the night-mode splash theme.

Sizing reference: [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
