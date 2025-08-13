#if !TARGET_OS_TV

#import <Foundation/Foundation.h>
#import <WebRTC/RTCVideoSource.h>
#import <AVFoundation/AVFoundation.h>

#import "CaptureController.h"

@interface VideoFileCaptureController : CaptureController

@property(nonatomic, readonly, strong) RTCVideoSource *videoSource;
@property(nonatomic, readonly, strong) NSURL *videoFileURL;
@property(nonatomic, readonly, assign) int width;
@property(nonatomic, readonly, assign) int height;
@property(nonatomic, readonly, assign) int frameRate;

- (instancetype)initWithVideoSource:(RTCVideoSource *)videoSource 
                       videoFileURL:(NSURL *)videoFileURL
                       andConstraints:(NSDictionary *)constraints;

@end

#endif