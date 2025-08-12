#import <Foundation/Foundation.h>
#import <WebRTC/RTCVideoFrame.h>
#import <Vision/Vision.h>
#import <CoreImage/CoreImage.h>
#import "VideoFrameProcessor.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * Video frame processor that replaces the background with a solid color using Vision framework.
 * This implementation uses Vision's person segmentation to detect the person
 * and replace the background with white or any specified color.
 */
@interface BackgroundEffectProcessor : NSObject <VideoFrameProcessorDelegate>

@property (nonatomic, strong) UIColor *backgroundColor;
@property (nonatomic, strong) CIContext *ciContext;
@property (nonatomic, strong) VNGeneratePersonSegmentationRequest *segmentationRequest;
@property (nonatomic, assign) VNPersonSegmentationQualityLevel qualityLevel;

- (instancetype)init;
- (instancetype)initWithBackgroundColor:(UIColor *)color;
- (instancetype)initWithBackgroundColor:(UIColor *)color 
                           qualityLevel:(VNPersonSegmentationQualityLevel)quality;

@end

NS_ASSUME_NONNULL_END