#if !TARGET_OS_TV

#import "VideoFileCaptureController.h"
#import <React/RCTLog.h>
#import <WebRTC/RTCCVPixelBuffer.h>
#import <WebRTC/RTCVideoFrame.h>

// Minimal RTCVideoCapturer subclass for video effects compatibility
@interface VideoFileVideoCapturer : RTCVideoCapturer
@end

@implementation VideoFileVideoCapturer
@end

@interface VideoFileCaptureController ()

@property(nonatomic, strong) RTCVideoSource *videoSource;
@property(nonatomic, strong) NSURL *videoFileURL;
@property(nonatomic, assign) BOOL running;
@property(nonatomic, assign) int width;
@property(nonatomic, assign) int height;
@property(nonatomic, assign) int frameRate;
@property(nonatomic, strong) dispatch_source_t frameTimer;
@property(nonatomic, strong) dispatch_queue_t captureQueue;
@property(nonatomic, strong) AVAssetReader *assetReader;
@property(nonatomic, strong) AVAssetReaderTrackOutput *trackOutput;
@property(nonatomic, strong) AVAsset *asset;
@property(nonatomic, strong) RTCVideoCapturer *capturer;

@end

@implementation VideoFileCaptureController

- (instancetype)initWithVideoSource:(RTCVideoSource *)videoSource 
                       videoFileURL:(NSURL *)videoFileURL
                       andConstraints:(NSDictionary *)constraints {
    self = [super init];
    if (self) {
        self.videoSource = videoSource;
        self.videoFileURL = videoFileURL;
        self.running = NO;
        
        // Set default values
        self.width = 640;
        self.height = 480;
        self.frameRate = 30;
        
        // Apply constraints if provided
        if (constraints) {
            [self applyConstraints:constraints error:nil];
        }
        
        // Load the video asset
        self.asset = [AVAsset assetWithURL:videoFileURL];
        
        // Create capture queue
        self.captureQueue = dispatch_queue_create("com.webrtc.videofile.capture", DISPATCH_QUEUE_SERIAL);
        
        // Create capturer for video effects compatibility
        self.capturer = [[VideoFileVideoCapturer alloc] initWithDelegate:videoSource];
    }
    
    return self;
}

- (void)startCapture {
    if (self.running) {
        return;
    }
    
    RCTLog(@"[VideoFileCaptureController] Starting video file capture");
    
    // Setup asset reader
    NSError *error = nil;
    self.assetReader = [[AVAssetReader alloc] initWithAsset:self.asset error:&error];
    if (error) {
        RCTLogError(@"[VideoFileCaptureController] Error creating asset reader: %@", error);
        return;
    }
    
    // Get the video track
    NSArray *videoTracks = [self.asset tracksWithMediaType:AVMediaTypeVideo];
    AVAssetTrack *videoTrack = [videoTracks firstObject];
    if (!videoTrack) {
        RCTLogError(@"[VideoFileCaptureController] No video track found in file");
        return;
    }
    
    // Create track output
    NSDictionary *outputSettings = @{
        (NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
    };
    
    self.trackOutput = [[AVAssetReaderTrackOutput alloc] initWithTrack:videoTrack outputSettings:outputSettings];
    [self.assetReader addOutput:self.trackOutput];
    
    if (![self.assetReader startReading]) {
        RCTLogError(@"[VideoFileCaptureController] Failed to start reading asset. Status: %ld", 
                    (long)self.assetReader.status);
        if (self.assetReader.error) {
            RCTLogError(@"[VideoFileCaptureController] Asset reader error: %@", self.assetReader.error);
        }
        return;
    }
    
    self.running = YES;
    
    // Create dispatch timer for consistent frame capture
    NSTimeInterval frameInterval = 1.0 / self.frameRate;
    self.frameTimer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, self.captureQueue);
    
    // Set timer to fire at frameRate fps
    dispatch_source_set_timer(self.frameTimer,
                              dispatch_time(DISPATCH_TIME_NOW, 0),
                              (uint64_t)(frameInterval * NSEC_PER_SEC),
                              (uint64_t)(frameInterval * NSEC_PER_SEC / 10)); // 10% leeway
    
    // Set timer handler
    __weak typeof(self) weakSelf = self;
    dispatch_source_set_event_handler(self.frameTimer, ^{
        [weakSelf captureFrame];
    });
    
    // Start the timer
    dispatch_resume(self.frameTimer);
    
    RCTLog(@"[VideoFileCaptureController] Capture started at %d fps", self.frameRate);
}

- (void)stopCapture {
    if (!self.running) {
        return;
    }
    
    RCTLog(@"[VideoFileCaptureController] Stopping capture");
    
    self.running = NO;
    
    if (self.frameTimer) {
        dispatch_source_cancel(self.frameTimer);
        self.frameTimer = nil;
    }
    
    if (self.assetReader) {
        [self.assetReader cancelReading];
        self.assetReader = nil;
        self.trackOutput = nil;
    }
    
    RCTLog(@"[VideoFileCaptureController] Capture stopped");
}

- (void)captureFrame {
    if (!self.running || !self.assetReader || !self.trackOutput) {
        return;
    }
    
    CMSampleBufferRef sampleBuffer = [self.trackOutput copyNextSampleBuffer];
    if (!sampleBuffer) {
        // End of file reached, restart from beginning for looping
        [self restartCapture];
        return;
    }
    
    CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (pixelBuffer) {
        
        // Create RTCVideoFrame
        RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:pixelBuffer];
        
        // Get timestamp
        int64_t timeStampNs = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) * 1000000000;
        
        RTCVideoFrame *frame = [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer
                                                            rotation:RTCVideoRotation_0
                                                         timeStampNs:timeStampNs];
        
        // Send frame through the capturer's delegate (which may be a video effect processor)
        if (self.capturer.delegate) {
            // Check if delegate is a VideoEffectProcessor that can process frames
            if ([self.capturer.delegate respondsToSelector:@selector(capturer:didCaptureVideoFrame:)]) {
                // Call the delegate method - for VideoEffectProcessor this processes and forwards the frame
                [(id)self.capturer.delegate capturer:self.capturer didCaptureVideoFrame:frame];
            } else {
                // Delegate doesn't process frames, send directly to video source
                [self.videoSource capturer:self.capturer didCaptureVideoFrame:frame];
            }
        } else {
            // No delegate, send directly to video source
            [self.videoSource capturer:self.capturer didCaptureVideoFrame:frame];
        }
    }
    
    CFRelease(sampleBuffer);
}

- (void)restartCapture {
    // Restart the asset reader for continuous looping
    [self.assetReader cancelReading];
    
    NSError *error = nil;
    self.assetReader = [[AVAssetReader alloc] initWithAsset:self.asset error:&error];
    if (error) {
        RCTLogError(@"[VideoFileCaptureController] Error recreating asset reader: %@", error);
        return;
    }
    
    AVAssetTrack *videoTrack = [[self.asset tracksWithMediaType:AVMediaTypeVideo] firstObject];
    NSDictionary *outputSettings = @{
        (NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
    };
    
    self.trackOutput = [[AVAssetReaderTrackOutput alloc] initWithTrack:videoTrack outputSettings:outputSettings];
    [self.assetReader addOutput:self.trackOutput];
    
    [self.assetReader startReading];
}

- (void)applyConstraints:(NSDictionary *)constraints error:(NSError **)outError {
    if (constraints[@"width"]) {
        self.width = [constraints[@"width"] intValue];
    }
    if (constraints[@"height"]) {
        self.height = [constraints[@"height"] intValue];
    }
    if (constraints[@"frameRate"]) {
        self.frameRate = [constraints[@"frameRate"] intValue];
        
        // Update timer if running
        if (self.running && self.frameTimer) {
            dispatch_source_cancel(self.frameTimer);
            
            // Create new timer with updated frame rate
            NSTimeInterval frameInterval = 1.0 / self.frameRate;
            self.frameTimer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, self.captureQueue);
            
            dispatch_source_set_timer(self.frameTimer,
                                      dispatch_time(DISPATCH_TIME_NOW, 0),
                                      (uint64_t)(frameInterval * NSEC_PER_SEC),
                                      (uint64_t)(frameInterval * NSEC_PER_SEC / 10));
            
            __weak typeof(self) weakSelf = self;
            dispatch_source_set_event_handler(self.frameTimer, ^{
                [weakSelf captureFrame];
            });
            
            dispatch_resume(self.frameTimer);
        }
    }
}

- (NSDictionary *)getSettings {
    return @{
        @"deviceId" : @"video_file",
        @"groupId" : @"",
        @"height" : @(self.height),
        @"width" : @(self.width),
        @"frameRate" : @(self.frameRate),
        @"facingMode" : @"environment"
    };
}

- (void)dealloc {
    [self stopCapture];
}

@end

#endif