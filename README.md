# Perfect Image v0.1.0

Independent, offline Android photo viewer and rendered-photo saver. Designed for Samsung Galaxy S23 Ultra (Android 16). This is an experimental first GitHub build, not a copy of AA Image Viewer's proprietary code or artwork.

## Features
- Open photos from the Android document picker or an image VIEW intent
- Standard (Mitchell-Netravali), Natural (Lanczos-2), and Clear (Lanczos-3 plus light sharpening) rendering
- Optional linear-light sRGB resampling
- Conservative normalization and automatic white balance
- Heat, brightness, contrast and saturation sliders
- Pinch to zoom and pan
- Save processed full photo as JPEG quality 100 or PNG
- Save the visible on-screen framing as JPEG or PNG
- MediaStore exports to Pictures/Perfect Image; no broad storage or internet permission

## Build
GitHub Actions > Build Perfect Image APK > latest successful run > Artifacts > Perfect-Image-v0.1.0-debug.

**Limitations:** Full-resolution input is currently capped by the decode path at approximately 4096 pixels on the long side; larger files may be downsampled. Colour profile/EXIF export, tiled 50MP/200MP rendering and a built-in folder grid are future improvements. Visible-crop export uses the displayed viewport resolution. This app has not yet been device-QA tested.

## Originality
All rendering and UI source was independently authored. No AA Image Viewer APK files, JNI binaries, copyrighted source, or assets are included.
