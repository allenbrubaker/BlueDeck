#!/usr/bin/env bash
set -euo pipefail

# Regenerate Android resources from the approved, unmodified source artwork.
branding_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
res_dir="$branding_dir/../app/src/main/res"
if command -v magick >/dev/null 2>&1; then
    image_tool=magick
else
    image_tool=convert
fi

# A 1080px layer maps to Android's 108dp adaptive-icon canvas. The source
# occupies its central 64dp; extend only the edge pixels into the padding.
# This preserves the artwork and avoids a visible rectangular background seam.
"$image_tool" "$branding_dir/bluedeck-ioniq-source.png" \
    -resize 640x640 -virtual-pixel edge \
    -set option:distort:viewport 1080x1080-220-220 \
    -distort SRT 0 +repage -strip \
    "$res_dir/drawable-nodpi/bluedeck_logo.png"

# The central 72dp is the standard launcher viewport. Keep the fallback
# artwork at the same visual scale as the adaptive icon.
"$image_tool" "$res_dir/drawable-nodpi/bluedeck_logo.png" \
    -crop 720x720+180+180 +repage -resize 512x512 \
    -define webp:lossless=true \
    "$res_dir/mipmap/ic_launcher.webp"

"$image_tool" "$res_dir/drawable-nodpi/bluedeck_logo.png" \
    -crop 720x720+180+180 +repage \
    \( -size 720x720 xc:none -fill white -draw 'circle 360,360 360,0' \) \
    -alpha off -compose CopyOpacity -composite -resize 512x512 \
    -define webp:lossless=true \
    "$res_dir/mipmap/ic_launcher_round.webp"
