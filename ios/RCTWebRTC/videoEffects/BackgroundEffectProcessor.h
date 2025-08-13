#import <Foundation/Foundation.h>
#import <WebRTC/RTCVideoFrame.h>
#import <CoreImage/CoreImage.h>
#import "VideoFrameProcessor.h"

NS_ASSUME_NONNULL_BEGIN

// Forward declarations for Vision framework
@class VNGeneratePersonSegmentationRequest;

/**
 * Video frame processor that replaces the background with a solid color using Vision framework.
 * This implementation uses Vision's person segmentation to detect the person
 * and replace the background with white or any specified color.
 */
@interface BackgroundEffectProcessor : NSObject <VideoFrameProcessorDelegate>

@property (nonatomic, strong) UIColor *backgroundColor;
@property (nonatomic, strong) CIContext *ciContext;
@property (nonatomic, strong) VNGeneratePersonSegmentationRequest *segmentationRequest;
@property (nonatomic, assign) NSInteger qualityLevel; // Using NSInteger for Vision framework compatibility

- (instancetype)init;
- (instancetype)initWithBackgroundColor:(UIColor *)color;
- (instancetype)initWithBackgroundColor:(UIColor *)color 
                           qualityLevel:(NSInteger)quality;

- (RTCVideoFrame *)applySolidBackgroundToFrame:(RTCVideoFrame *)frame;

@end

NS_ASSUME_NONNULL_END