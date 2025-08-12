#import "BackgroundEffectProcessor.h"
#import <WebRTC/RTCVideoFrameBuffer.h>
#import <WebRTC/RTCI420Buffer.h>
#import <WebRTC/RTCCVPixelBuffer.h>
#import <Accelerate/Accelerate.h>

@implementation BackgroundEffectProcessor {
    dispatch_queue_t _processingQueue;
    CVPixelBufferPoolRef _pixelBufferPool;
    NSDictionary *_pixelBufferAttributes;
}

- (instancetype)init {
    return [self initWithBackgroundColor:[UIColor whiteColor]];
}

- (instancetype)initWithBackgroundColor:(UIColor *)color {
    return [self initWithBackgroundColor:color 
                            qualityLevel:VNPersonSegmentationQualityLevelBalanced];
}

- (instancetype)initWithBackgroundColor:(UIColor *)color 
                           qualityLevel:(VNPersonSegmentationQualityLevel)quality {
    self = [super init];
    if (self) {
        _backgroundColor = color;
        _qualityLevel = quality;
        _processingQueue = dispatch_queue_create("com.webrtc.backgroundeffect", DISPATCH_QUEUE_SERIAL);
        
        [self setupVision];
        [self setupCoreImage];
        [self setupPixelBufferPool];
    }
    return self;
}

- (void)setupVision {
    if (@available(iOS 15.0, *)) {
        _segmentationRequest = [[VNGeneratePersonSegmentationRequest alloc] init];
        _segmentationRequest.qualityLevel = _qualityLevel;
        _segmentationRequest.outputPixelFormat = kCVPixelFormatType_OneComponent8;
    } else {
        NSLog(@"Person segmentation requires iOS 15.0+");
    }
}

- (void)setupCoreImage {
    // Create CIContext with GPU acceleration
    NSDictionary *options = @{
        kCIContextUseSoftwareRenderer : @NO,
        kCIContextPriorityRequestLow : @NO
    };
    _ciContext = [CIContext contextWithOptions:options];
}

- (void)setupPixelBufferPool {
    // Setup pixel buffer pool for efficient memory management
    _pixelBufferAttributes = @{
        (__bridge NSString *)kCVPixelBufferPixelFormatTypeKey : @(kCVPixelFormatType_32BGRA),
        (__bridge NSString *)kCVPixelBufferWidthKey : @(1280),
        (__bridge NSString *)kCVPixelBufferHeightKey : @(720),
        (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey : @{}
    };
}

- (RTCVideoFrame *)capturer:(RTCVideoCapturer *)capturer 
        didCaptureVideoFrame:(RTCVideoFrame *)frame {
    
    if (!_segmentationRequest) {
        return frame; // Return original if Vision not available
    }
    
    // Get the pixel buffer from the frame
    CVPixelBufferRef pixelBuffer = [self pixelBufferFromFrame:frame];
    if (!pixelBuffer) {
        return frame;
    }
    
    // Process the frame
    CVPixelBufferRef processedBuffer = [self processPixelBuffer:pixelBuffer];
    if (!processedBuffer) {
        CVPixelBufferRelease(pixelBuffer);
        return frame;
    }
    
    // Create new RTCVideoFrame with processed buffer
    RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:processedBuffer];
    RTCVideoFrame *processedFrame = [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer
                                                                  rotation:frame.rotation
                                                               timeStampNs:frame.timeStampNs];
    
    CVPixelBufferRelease(pixelBuffer);
    CVPixelBufferRelease(processedBuffer);
    
    return processedFrame;
}

- (CVPixelBufferRef)pixelBufferFromFrame:(RTCVideoFrame *)frame {
    id<RTCVideoFrameBuffer> buffer = frame.buffer;
    
    // If it's already a CVPixelBuffer, use it directly
    if ([buffer isKindOfClass:[RTCCVPixelBuffer class]]) {
        RTCCVPixelBuffer *cvBuffer = (RTCCVPixelBuffer *)buffer;
        CVPixelBufferRef pixelBuffer = cvBuffer.pixelBuffer;
        CVPixelBufferRetain(pixelBuffer);
        return pixelBuffer;
    }
    
    // If it's I420, convert to CVPixelBuffer
    if ([buffer conformsToProtocol:@protocol(RTCI420Buffer)]) {
        id<RTCI420Buffer> i420Buffer = (id<RTCI420Buffer>)buffer;
        
        // Create pixel buffer
        CVPixelBufferRef pixelBuffer = NULL;
        CVReturn result = CVPixelBufferCreate(
            kCFAllocatorDefault,
            i420Buffer.width,
            i420Buffer.height,
            kCVPixelFormatType_32BGRA,
            (__bridge CFDictionaryRef)_pixelBufferAttributes,
            &pixelBuffer
        );
        
        if (result != kCVReturnSuccess || !pixelBuffer) {
            return NULL;
        }
        
        // Convert I420 to BGRA
        [self convertI420Buffer:i420Buffer toPixelBuffer:pixelBuffer];
        
        return pixelBuffer;
    }
    
    return NULL;
}

- (void)convertI420Buffer:(id<RTCI420Buffer>)i420Buffer 
           toPixelBuffer:(CVPixelBufferRef)pixelBuffer {
    
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    
    uint8_t *dstData = (uint8_t *)CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t dstStride = CVPixelBufferGetBytesPerRow(pixelBuffer);
    
    // Simple YUV to RGB conversion (this is simplified, consider using vImage for better performance)
    const uint8_t *srcY = i420Buffer.dataY;
    const uint8_t *srcU = i420Buffer.dataU;
    const uint8_t *srcV = i420Buffer.dataV;
    
    int width = i420Buffer.width;
    int height = i420Buffer.height;
    
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int yIndex = y * i420Buffer.strideY + x;
            int uvIndex = (y / 2) * i420Buffer.strideU + (x / 2);
            
            int Y = srcY[yIndex];
            int U = srcU[uvIndex] - 128;
            int V = srcV[uvIndex] - 128;
            
            int R = Y + 1.402 * V;
            int G = Y - 0.344 * U - 0.714 * V;
            int B = Y + 1.772 * U;
            
            R = MIN(MAX(R, 0), 255);
            G = MIN(MAX(G, 0), 255);
            B = MIN(MAX(B, 0), 255);
            
            int dstIndex = y * dstStride + x * 4;
            dstData[dstIndex + 0] = B;  // B
            dstData[dstIndex + 1] = G;  // G
            dstData[dstIndex + 2] = R;  // R
            dstData[dstIndex + 3] = 255; // A
        }
    }
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
}

- (CVPixelBufferRef)processPixelBuffer:(CVPixelBufferRef)inputBuffer {
    if (@available(iOS 15.0, *)) {
        // Perform person segmentation
        VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] 
            initWithCVPixelBuffer:inputBuffer options:@{}];
        
        NSError *error = nil;
        [handler performRequests:@[_segmentationRequest] error:&error];
        
        if (error) {
            NSLog(@"Segmentation error: %@", error);
            return NULL;
        }
        
        VNPixelBufferObservation *observation = _segmentationRequest.results.firstObject;
        if (!observation) {
            return NULL;
        }
        
        // Apply background replacement
        return [self applyBackgroundEffect:inputBuffer withMask:observation.pixelBuffer];
        
    } else {
        // Fallback for older iOS versions - return original
        CVPixelBufferRetain(inputBuffer);
        return inputBuffer;
    }
}

- (CVPixelBufferRef)applyBackgroundEffect:(CVPixelBufferRef)inputBuffer 
                                  withMask:(CVPixelBufferRef)maskBuffer {
    
    // Create CIImages
    CIImage *inputImage = [CIImage imageWithCVPixelBuffer:inputBuffer];
    CIImage *maskImage = [CIImage imageWithCVPixelBuffer:maskBuffer];
    
    // Get background color components
    CGFloat red, green, blue, alpha;
    [_backgroundColor getRed:&red green:&green blue:&blue alpha:&alpha];
    
    // Create solid color background
    CIColor *ciBackgroundColor = [CIColor colorWithRed:red green:green blue:blue alpha:alpha];
    CIImage *backgroundImage = [CIImage imageWithColor:ciBackgroundColor];
    backgroundImage = [backgroundImage imageByCroppingToRect:inputImage.extent];
    
    // Resize mask to match input size if needed
    CGAffineTransform scaleTransform = CGAffineTransformMakeScale(
        inputImage.extent.size.width / maskImage.extent.size.width,
        inputImage.extent.size.height / maskImage.extent.size.height
    );
    maskImage = [maskImage imageByApplyingTransform:scaleTransform];
    
    // Apply threshold to mask (convert confidence values to binary)
    CIFilter *thresholdFilter = [CIFilter filterWithName:@"CIColorThreshold"];
    [thresholdFilter setValue:maskImage forKey:kCIInputImageKey];
    [thresholdFilter setValue:@(0.5) forKey:@"inputThreshold"];
    CIImage *binaryMask = thresholdFilter.outputImage;
    
    // Blend person over background using mask
    CIFilter *blendFilter = [CIFilter filterWithName:@"CIBlendWithMask"];
    [blendFilter setValue:inputImage forKey:kCIInputImageKey];
    [blendFilter setValue:backgroundImage forKey:kCIInputBackgroundImageKey];
    [blendFilter setValue:binaryMask forKey:kCIInputMaskImageKey];
    
    CIImage *outputImage = blendFilter.outputImage;
    
    // Create output pixel buffer
    CVPixelBufferRef outputBuffer = NULL;
    CVPixelBufferCreate(
        kCFAllocatorDefault,
        CVPixelBufferGetWidth(inputBuffer),
        CVPixelBufferGetHeight(inputBuffer),
        CVPixelBufferGetPixelFormatType(inputBuffer),
        (__bridge CFDictionaryRef)_pixelBufferAttributes,
        &outputBuffer
    );
    
    if (outputBuffer) {
        [_ciContext render:outputImage toCVPixelBuffer:outputBuffer];
    }
    
    return outputBuffer;
}

- (void)dealloc {
    if (_pixelBufferPool) {
        CVPixelBufferPoolRelease(_pixelBufferPool);
    }
}

@end