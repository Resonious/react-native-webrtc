# Background Effect Setup Guide

## Overview
This guide explains how to set up and use the background replacement feature in react-native-webrtc.

## Setup Instructions

### 1. Android Setup

#### Register the processors in your MainApplication.java:

```java
import com.oney.WebRTCModule.videoEffects.BackgroundEffectProcessorFactory;

@Override
public void onCreate() {
    super.onCreate();
    // ... other initialization code
    
    // Register background effect processors
    BackgroundEffectProcessorFactory.register();
}
```

#### Ensure MediaPipe dependencies are included:
The `android/build.gradle` has already been updated with:
```gradle
implementation 'com.google.mediapipe:solution-core:latest.release'
implementation 'com.google.mediapipe:selfie-segmentation:latest.release'
```

### 2. iOS Setup

#### Register the processors in your AppDelegate.m:

```objc
#import "BackgroundEffectProcessorProvider.h"

- (BOOL)application:(UIApplication *)application 
    didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    // ... other initialization code
    
    // Register background effect processors
    [BackgroundEffectProcessorProvider registerProcessors];
    
    return YES;
}
```

#### Requirements:
- iOS 15.0+ for Vision framework person segmentation
- No additional dependencies needed (uses built-in Vision and Core Image frameworks)

## Usage in JavaScript

```javascript
import { mediaDevices } from 'react-native-webrtc';

// Get video stream
const stream = await mediaDevices.getUserMedia({ video: true });
const videoTrack = stream.getVideoTracks()[0];

// Apply white background effect
videoTrack._setVideoEffect('backgroundWhite');

// Or apply multiple effects in sequence
videoTrack._setVideoEffects(['backgroundWhite', 'someOtherEffect']);

// Remove all effects
videoTrack._setVideoEffects([]);
```

## Available Effects

- `backgroundWhite` - Solid white background
- `backgroundBlur` - Blurred background (placeholder, needs implementation)
- `backgroundGreen` - Green screen for chroma key
- `backgroundCustom` - Custom gray background
- `backgroundWhiteHD` - High quality white background (iOS 15+ only)

## Performance Considerations

### Android
- MediaPipe runs inference on each frame
- GPU acceleration is used when available
- Consider reducing video resolution for better performance
- Typical processing time: 15-30ms per frame

### iOS
- Vision framework uses Neural Engine when available
- Core Image filters are GPU accelerated
- Quality levels: Fast, Balanced, Accurate
- Typical processing time: 10-20ms per frame

## Customization

### Adding Custom Colors

Android:
```java
ProcessorProvider.addProcessor("backgroundBlue", 
    () -> new BackgroundEffectProcessor(Color.BLUE));
```

iOS:
```objc
BackgroundEffectProcessor *blueProcessor = [[BackgroundEffectProcessor alloc] 
    initWithBackgroundColor:[UIColor blueColor]];
[ProcessorProvider addProcessor:blueProcessor forName:@"backgroundBlue"];
```

### Adding Background Images

To add background image support, modify the processors to:
1. Load an image from resources or URL
2. Scale/crop to match video dimensions
3. Composite using the segmentation mask

## Troubleshooting

### Android Issues
- **Black frames**: Check OpenGL context initialization
- **Crashes**: Ensure MediaPipe models are downloaded
- **Poor segmentation**: Improve lighting conditions

### iOS Issues
- **No effect**: Check iOS version (15.0+ required)
- **Memory warnings**: Reduce video resolution
- **Lag**: Switch to Fast quality level

## Future Enhancements

1. **Blur Effect**: Implement actual background blur using:
   - Android: RenderScript or custom OpenGL shader
   - iOS: CIGaussianBlur filter

2. **Virtual Backgrounds**: Add support for:
   - Static images
   - Videos
   - Dynamic backgrounds

3. **Performance Optimizations**:
   - Frame skipping for lower-end devices
   - Adaptive quality based on device capabilities
   - Background thread processing

4. **Additional Effects**:
   - Edge smoothing/feathering
   - Shadow preservation
   - Light wrap for realistic compositing