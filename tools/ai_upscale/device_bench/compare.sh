#!/bin/sh
# compare.sh <original.rgba> <W> <H> <label>=<result.rgba> ...  -> PSNR vs the original + a crop sheet
# Needs ImageMagick 7 (`magick`). Writes compare_sheet.png (same crop from every image, 2x zoom).
set -e
ORIG="$1"; W="$2"; H="$3"; shift 3
CROP="${CROP:-360x200+760+560}"
magick -size "${W}x${H}" -depth 8 "RGBA:$ORIG" -alpha off /tmp/cmp_orig.png
TILES="/tmp/cmp_tile_orig.png"
magick /tmp/cmp_orig.png -crop "$CROP" +repage -filter point -resize 200% \
    -gravity north -background black -splice 0x22 -fill white -pointsize 18 -annotate +0+2 "original" /tmp/cmp_tile_orig.png
for item in "$@"; do
    label="${item%%=*}"; file="${item#*=}"
    magick -size "${W}x${H}" -depth 8 "RGBA:$file" -alpha off "/tmp/cmp_$label.png"
    psnr=$(magick compare -metric PSNR /tmp/cmp_orig.png "/tmp/cmp_$label.png" null: 2>&1 | cut -d' ' -f1)
    echo "PSNR $label = $psnr dB"
    magick "/tmp/cmp_$label.png" -crop "$CROP" +repage -filter point -resize 200% \
        -gravity north -background black -splice 0x22 -fill white -pointsize 18 -annotate +0+2 "$label  ($psnr dB)" "/tmp/cmp_tile_$label.png"
    TILES="$TILES /tmp/cmp_tile_$label.png"
done
magick $TILES -append compare_sheet.png
echo "sheet: $(pwd)/compare_sheet.png"
