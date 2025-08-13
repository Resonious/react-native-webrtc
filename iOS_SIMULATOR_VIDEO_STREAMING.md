# iOS Simulator Video File Streaming

This implementation adds the ability to stream video from a file instead of the camera when running on iOS Simulator, making testing possible without physical camera access.

## Overview

The implementation consists of three main components:

### 1. VideoFileCaptureController

A new capture controller that reads frames from a video file and streams them as video frames:

- **Location**: `ios/RCTWebRTC/VideoFileCaptureController.{h,m}`
- **Functionality**: Uses `AVAssetReader` to read video frames from an MP4 file and converts them to WebRTC video frames
- **Features**: 
  - Loops video file continuously
  - Configurable frame rate
  - Automatic frame timing using NSTimer
  - Proper memory management

### 2. Modified WebRTCModule+RTCMediaStream.m

The main WebRTC module has been modified to use video file streaming on iOS Simulator:

- **Location**: `ios/RCTWebRTC/WebRTCModule+RTCMediaStream.m`
- **Changes**: 
  - Added conditional compilation for `TARGET_IPHONE_SIMULATOR`
  - On simulator: Creates `VideoFileCaptureController` with test video file
  - On device: Uses existing `VideoCaptureController` with camera
  - Automatically searches for `test_video.mp4` in main bundle

### 3. Test Video File Setup

Instructions and placeholder for adding a test video file:

- **Location**: `examples/GumTestApp/ios/GumTestApp/test_video_placeholder.md`
- **Requirements**: MP4 file named `test_video.mp4` added to iOS app bundle
- **Recommendations**: 5-10 second video, 640x480 resolution, 30fps

## How It Works

1. **Detection**: Code detects if running on iOS Simulator using `TARGET_IPHONE_SIMULATOR` preprocessor directive

2. **File Loading**: Attempts to load `test_video.mp4` from the main app bundle using `NSBundle`

3. **Asset Reading**: Creates `AVAssetReader` to read video frames from the MP4 file

4. **Frame Conversion**: Converts `CVPixelBuffer` frames to `RTCVideoFrame` objects

5. **Streaming**: Sends frames to WebRTC video source at specified frame rate

6. **Looping**: Automatically restarts from beginning when video file ends

## Usage

### For Development:

1. Add a test video file named `test_video.mp4` to your iOS app bundle
2. Build and run on iOS Simulator
3. Call `getUserMedia({ video: true })` - it will use the video file instead of camera
4. Video will loop continuously during the stream

### For Production:

The code automatically detects when running on a real device and uses the camera normally. No changes needed for production builds.

## Adding a Test Video

### Method 1: Using Xcode
1. Find or create an MP4 video file (recommended: short, 640x480, 30fps)
2. Name it `test_video.mp4`
3. In Xcode, right-click your project
4. Select "Add Files to [ProjectName]"
5. Choose your video file and ensure it's added to the target

### Method 2: Using ffmpeg (if available)
```bash
# Create a 10-second test pattern video
ffmpeg -f lavfi -i testsrc2=duration=10:size=640x480:rate=30 \\
       -c:v libx264 -pix_fmt yuv420p test_video.mp4
```

## Benefits

- **Simulator Testing**: Enables camera-based features to work on iOS Simulator
- **Automated Testing**: Predictable video content for consistent test results
- **Development Speed**: No need for physical device during development
- **No Production Impact**: Automatically disabled on real devices

## Files Modified/Added

- **Added**: `ios/RCTWebRTC/VideoFileCaptureController.h`
- **Added**: `ios/RCTWebRTC/VideoFileCaptureController.m`
- **Modified**: `ios/RCTWebRTC/WebRTCModule+RTCMediaStream.m`
- **Added**: `examples/GumTestApp/ios/GumTestApp/test_video_placeholder.md`
- **Added**: `iOS_SIMULATOR_VIDEO_STREAMING.md` (this file)

## Limitations

- Only works on iOS Simulator (by design)
- Requires test video file in app bundle
- Video loops continuously (no start/stop control)
- Fixed to MP4 format
- No audio track support from video file

## Future Enhancements

- Configuration options for video file name/path
- Support for multiple test videos
- Runtime video file selection
- Audio track support
- Integration with React Native configuration