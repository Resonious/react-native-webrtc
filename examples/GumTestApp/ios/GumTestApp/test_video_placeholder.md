# Test Video File

To use video file streaming on iOS Simulator, you need to add a test video file named `test_video.mp4` to this directory.

## How to add a test video:

1. Find or create a short MP4 video file (recommended: 5-10 seconds, 640x480 resolution)
2. Name it `test_video.mp4`
3. Add it to this directory
4. In Xcode, add the file to the project bundle by:
   - Right-clicking in the project navigator
   - Choose "Add Files to GumTestApp"
   - Select your test_video.mp4 file
   - Make sure "Add to target: GumTestApp" is checked

## Creating a test video with ffmpeg (if available):
```bash
ffmpeg -f lavfi -i testsrc2=duration=10:size=640x480:rate=30 -c:v libx264 -pix_fmt yuv420p test_video.mp4
```

This creates a 10-second test pattern video at 640x480 resolution with 30 fps.