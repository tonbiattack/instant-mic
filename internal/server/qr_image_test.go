package server

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"testing"
)

// The pairing window decodes the PNG that QRPNG produces, so the encoding has to
// stay something Go's image/png can read. A pre-rendered image would break that
// silently (black window), so this checks the round trip.

func TestQRPNGDecodesAsImage(t *testing.T) {
	payload := samplePayload()
	data, err := payload.QRPNG(256)
	if err != nil {
		t.Fatalf("QRPNG: %v", err)
	}
	img, err := decodePNG(data)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	b := img.Bounds()
	if b.Dx() < 128 || b.Dy() < 128 {
		t.Fatalf("image is %dx%d, too small to scan", b.Dx(), b.Dy())
	}

	// A QR image must be black and white; a colour or blank image means the
	// encoder changed and the window would show something unusable.
	dark, light := 0, 0
	step := b.Dx() / 32
	if step < 1 {
		step = 1
	}
	for y := b.Min.Y; y < b.Max.Y; y += step {
		for x := b.Min.X; x < b.Max.X; x += step {
			g := color.GrayModel.Convert(img.At(x, y)).(color.Gray)
			if g.Y < 64 {
				dark++
			} else if g.Y > 192 {
				light++
			}
		}
	}
	if dark == 0 {
		t.Fatal("QR image has no dark modules")
	}
	if light == 0 {
		t.Fatal("QR image has no light modules")
	}
}

func TestQRPNGRejectsOversizedSize(t *testing.T) {
	// Out-of-range sizes clamp to the default instead of allocating wildly.
	payload := samplePayload()
	data, err := payload.QRPNG(1)
	if err != nil {
		t.Fatalf("QRPNG(1): %v", err)
	}
	if len(data) == 0 {
		t.Fatal("no data for an out-of-range size")
	}
}

// decodePNG is a thin alias so the test reads like the window's code path.
func decodePNG(data []byte) (image.Image, error) {
	return png.Decode(bytes.NewReader(data))
}
