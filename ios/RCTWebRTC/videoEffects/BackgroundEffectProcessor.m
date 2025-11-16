#import "BackgroundEffectProcessor.h"
#import <WebRTC/RTCVideoFrameBuffer.h>
#import <WebRTC/RTCI420Buffer.h>
#import <WebRTC/RTCCVPixelBuffer.h>
#import <Accelerate/Accelerate.h>
@import Vision;

@implementation BackgroundEffectProcessor {
    dispatch_queue_t _processingQueue;
    CVPixelBufferPoolRef _pixelBufferPool;
    NSDictionary *_pixelBufferAttributes;
    RTCVideoFrame *_lastCompletedFrame;
    BOOL _isProcessing;
    NSObject *_processingLock;
}

- (instancetype)init {
    return [self initWithBackgroundColor:[UIColor whiteColor]];
}

- (instancetype)initWithBackgroundColor:(UIColor *)color {
    return [self initWithBackgroundColor:color 
                            qualityLevel:0]; // VNPersonSegmentationQualityLevelBalanced
}

- (instancetype)initWithBackgroundColor:(UIColor *)color 
                           qualityLevel:(NSInteger)quality {
    self = [super init];
    if (self) {
        _backgroundColor = color;
        _qualityLevel = quality;
        _processingQueue = dispatch_queue_create("com.webrtc.backgroundeffect", DISPATCH_QUEUE_SERIAL);
        _processingLock = [[NSObject alloc] init];
        _isProcessing = NO;
        _lastCompletedFrame = nil;
        
        [self setupVision];
        [self setupCoreImage];
        [self setupPixelBufferPool];
    }
    return self;
}

- (void)setupVision {
    if (@available(iOS 15.0, *)) {
        _segmentationRequest = [[VNGeneratePersonSegmentationRequest alloc] init];

        // Map NSInteger to VNPersonSegmentationQualityLevel enum using raw values
        // VNPersonSegmentationQualityLevelFast = 0, Balanced = 1, Accurate = 2
        switch (_qualityLevel) {
            case 0:
                _segmentationRequest.qualityLevel = 0; // VNPersonSegmentationQualityLevelFast
                break;
            case 1:
                _segmentationRequest.qualityLevel = 1; // VNPersonSegmentationQualityLevelBalanced
                break;
            case 2:
                _segmentationRequest.qualityLevel = 2; // VNPersonSegmentationQualityLevelAccurate
                break;
            default:
                _segmentationRequest.qualityLevel = 1; // VNPersonSegmentationQualityLevelBalanced
                break;
        }

        _segmentationRequest.outputPixelFormat = kCVPixelFormatType_OneComponent8;

        // Create sequence request handler for efficient video processing
        _sequenceRequestHandler = [[VNSequenceRequestHandler alloc] init];
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

    // Create the pixel buffer pool
    CVReturn result = CVPixelBufferPoolCreate(
        kCFAllocatorDefault,
        NULL,  // pool attributes (NULL uses defaults)
        (__bridge CFDictionaryRef)_pixelBufferAttributes,
        &_pixelBufferPool
    );

    if (result != kCVReturnSuccess) {
        NSLog(@"❌ Failed to create CVPixelBufferPool: %d", result);
        _pixelBufferPool = NULL;
    } else {
        NSLog(@"✅ Created CVPixelBufferPool successfully");
    }
}

- (RTCVideoFrame *)capturer:(RTCVideoCapturer *)capturer 
        didCaptureVideoFrame:(RTCVideoFrame *)frame {
    
    if (!_segmentationRequest) {
        NSLog(@"🚫 No segmentation request - returning original frame");
        return frame; // Return original if Vision not available
    }

    @autoreleasepool {
        // Check if we're already processing
        @synchronized(_processingLock) {
            if (_isProcessing) {
                // Already processing - return last completed frame or solid color
                if (_lastCompletedFrame) {
                    // Return cached frame with current timestamp to maintain timing
                    RTCVideoFrame *cachedFrame = [[RTCVideoFrame alloc] initWithBuffer:_lastCompletedFrame.buffer
                                                                              rotation:frame.rotation
                                                                           timeStampNs:frame.timeStampNs];
                    // NSLog(@"⏭️ Skipping frame - returning cached frame");
                    return cachedFrame;
                } else {
                    // No cached frame yet, return solid color background
                    CVPixelBufferRef pixelBuffer = [self pixelBufferFromFrame:frame];
                    if (!pixelBuffer) {
                        return frame;
                    }
                    
                    CVPixelBufferRef solidBuffer = [self createSolidBackground:pixelBuffer];
                    CVPixelBufferRelease(pixelBuffer);
                    
                    if (!solidBuffer) {
                        return frame;
                    }
                    
                    RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:solidBuffer];
                    RTCVideoFrame *solidFrame = [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer
                                                                             rotation:frame.rotation
                                                                          timeStampNs:frame.timeStampNs];
                    CVPixelBufferRelease(solidBuffer);
                    
                    // NSLog(@"⏭️ Skipping frame - no cache yet, returning solid color");
                    return solidFrame;
                }
            }
            _isProcessing = YES;
        }
        
        // Start async processing
        __weak typeof(self) weakSelf = self;
        dispatch_async(_processingQueue, ^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf) return;
            
            @autoreleasepool {
                // Get the pixel buffer from the frame
                CVPixelBufferRef pixelBuffer = [strongSelf pixelBufferFromFrame:frame];
                if (!pixelBuffer) {
                    NSLog(@"❌ Could not get pixel buffer from frame");
                    @synchronized(strongSelf->_processingLock) {
                        strongSelf->_isProcessing = NO;
                    }
                    return;
                }
                
                // Process the frame
                CVPixelBufferRef processedBuffer = [strongSelf processPixelBuffer:pixelBuffer];
                if (!processedBuffer) {
                    NSLog(@"❌ Could not process pixel buffer");
                    CVPixelBufferRelease(pixelBuffer);
                    @synchronized(strongSelf->_processingLock) {
                        strongSelf->_isProcessing = NO;
                    }
                    return;
                }
                
                // Create new RTCVideoFrame with processed buffer
                RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:processedBuffer];
                RTCVideoFrame *processedFrame = [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer
                                                                              rotation:frame.rotation
                                                                           timeStampNs:frame.timeStampNs];
                
                CVPixelBufferRelease(pixelBuffer);
                CVPixelBufferRelease(processedBuffer);
                
                // Update cached frame
                @synchronized(strongSelf->_processingLock) {
                    strongSelf->_lastCompletedFrame = processedFrame;
                    strongSelf->_isProcessing = NO;
                    NSLog(@"✅ Processing complete - cached new frame");
                }
            }
        });
        
        // Return last completed frame or solid color while processing
        @synchronized(_processingLock) {
            if (_lastCompletedFrame) {
                // Return cached frame with current timestamp
                RTCVideoFrame *cachedFrame = [[RTCVideoFrame alloc] initWithBuffer:_lastCompletedFrame.buffer
                                                                          rotation:frame.rotation
                                                                       timeStampNs:frame.timeStampNs];
                return cachedFrame;
            } else {
                // First frame - create solid color frame while processing
                CVPixelBufferRef pixelBuffer = [self pixelBufferFromFrame:frame];
                if (!pixelBuffer) {
                    return frame;
                }
                
                CVPixelBufferRef solidBuffer = [self createSolidBackground:pixelBuffer];
                CVPixelBufferRelease(pixelBuffer);
                
                if (!solidBuffer) {
                    return frame;
                }
                
                RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:solidBuffer];
                RTCVideoFrame *solidFrame = [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer
                                                                         rotation:frame.rotation
                                                                      timeStampNs:frame.timeStampNs];
                CVPixelBufferRelease(solidBuffer);
                
                NSLog(@"🎨 Returning solid color frame for first frame");
                return solidFrame;
            }
        }
    }
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

        // Create pixel buffer from pool
        CVPixelBufferRef pixelBuffer = NULL;
        CVReturn result;

        if (_pixelBufferPool) {
            result = CVPixelBufferPoolCreatePixelBuffer(
                kCFAllocatorDefault,
                _pixelBufferPool,
                &pixelBuffer
            );
        } else {
            // Fallback to direct creation if pool isn't available
            result = CVPixelBufferCreate(
                kCFAllocatorDefault,
                i420Buffer.width,
                i420Buffer.height,
                kCVPixelFormatType_32BGRA,
                (__bridge CFDictionaryRef)_pixelBufferAttributes,
                &pixelBuffer
            );
        }

        if (result != kCVReturnSuccess || !pixelBuffer) {
            return NULL;
        }

        // Convert I420 to BGRA
        NSLog(@"⚠️  Converting to BGRA!");
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
    // NSLog(@"🔄 Starting processPixelBuffer...");

    if (@available(iOS 15.0, *)) {
        // NSLog(@"📱 iOS 15+ available, performing segmentation...");

        @autoreleasepool {
            // Perform person segmentation using sequence request handler
            // VNSequenceRequestHandler is NOT thread-safe, so we synchronize access
            // We must also capture results within the synchronized block because
            // _segmentationRequest.results can be overwritten by concurrent calls
            NSError *error = nil;
            BOOL success;
            VNPixelBufferObservation *observation = nil;

            @synchronized(self) {
                success = [_sequenceRequestHandler performRequests:@[_segmentationRequest]
                                                    onCVPixelBuffer:inputBuffer
                                                              error:&error];

                // Capture results immediately while still synchronized
                if (success && !error) {
                    observation = _segmentationRequest.results.firstObject;
                }
            }

            if (error) {
                NSLog(@"❌ Segmentation error: %@", error);
                return NULL;
            }

            if (!success) {
                NSLog(@"❌ Segmentation request failed - creating fallback solid background for testing");
                // Create a simple solid background for testing on simulator
                return [self createSolidBackground:inputBuffer];
            }

            // NSLog(@"📊 Segmentation request completed, checking results...");
            if (!observation) {
                NSLog(@"❌ No segmentation observation found");
                return NULL;
            }

            // NSLog(@"🎭 Observation found, applying background effect...");
            // Apply background replacement
            CVPixelBufferRef result = [self applyBackgroundEffect:inputBuffer withMask:observation.pixelBuffer];
            if (result) {
                // NSLog(@"✨ Background effect applied successfully!");
            } else {
                NSLog(@"❌ Failed to apply background effect");
            }
            return result;
        }

    } else {
        NSLog(@"⚠️ iOS 15+ not available, returning original buffer");
        // Fallback for older iOS versions - return original
        CVPixelBufferRetain(inputBuffer);
        return inputBuffer;
    }
}

- (CVPixelBufferRef)applyBackgroundEffect:(CVPixelBufferRef)inputBuffer 
                                  withMask:(CVPixelBufferRef)maskBuffer {
    
    @autoreleasepool {
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

        // Create output pixel buffer from pool
        CVPixelBufferRef outputBuffer = NULL;
        CVReturn result;

        if (_pixelBufferPool) {
            result = CVPixelBufferPoolCreatePixelBuffer(
                kCFAllocatorDefault,
                _pixelBufferPool,
                &outputBuffer
            );
        } else {
            // Fallback to direct creation if pool isn't available
            result = CVPixelBufferCreate(
                kCFAllocatorDefault,
                CVPixelBufferGetWidth(inputBuffer),
                CVPixelBufferGetHeight(inputBuffer),
                CVPixelBufferGetPixelFormatType(inputBuffer),
                (__bridge CFDictionaryRef)_pixelBufferAttributes,
                &outputBuffer
            );
        }

        if (result == kCVReturnSuccess && outputBuffer) {
            [_ciContext render:outputImage toCVPixelBuffer:outputBuffer];
        }

        return outputBuffer;
    }
}

- (CVPixelBufferRef)createSolidBackground:(CVPixelBufferRef)inputBuffer {
    NSLog(@"🎨 Creating solid background fallback (no person segmentation)");

    // Get dimensions from input buffer
    size_t width = CVPixelBufferGetWidth(inputBuffer);
    size_t height = CVPixelBufferGetHeight(inputBuffer);

    // Create output buffer from pool
    CVPixelBufferRef outputBuffer = NULL;
    CVReturn result;

    if (_pixelBufferPool) {
        result = CVPixelBufferPoolCreatePixelBuffer(
            kCFAllocatorDefault,
            _pixelBufferPool,
            &outputBuffer
        );
    } else {
        // Fallback to direct creation if pool isn't available
        result = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            (__bridge CFDictionaryRef)_pixelBufferAttributes,
            &outputBuffer
        );
    }

    if (result != kCVReturnSuccess || !outputBuffer) {
        NSLog(@"❌ Failed to create output buffer");
        return NULL;
    }
    
    // Lock the buffer for writing
    CVPixelBufferLockBaseAddress(outputBuffer, 0);
    void *baseAddress = CVPixelBufferGetBaseAddress(outputBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(outputBuffer);
    
    // Get background color components
    CGFloat red, green, blue, alpha;
    [_backgroundColor getRed:&red green:&green blue:&blue alpha:&alpha];
    
    // Convert to 0-255 range
    uint8_t r = (uint8_t)(red * 255);
    uint8_t g = (uint8_t)(green * 255);  
    uint8_t b = (uint8_t)(blue * 255);
    uint8_t a = (uint8_t)(alpha * 255);
    
    // Fill buffer with solid color (BGRA format)
    uint8_t *pixelData = (uint8_t *)baseAddress;
    for (size_t y = 0; y < height; y++) {
        for (size_t x = 0; x < width; x++) {
            size_t offset = y * bytesPerRow + x * 4;
            pixelData[offset + 0] = b;  // Blue
            pixelData[offset + 1] = g;  // Green
            pixelData[offset + 2] = r;  // Red
            pixelData[offset + 3] = a;  // Alpha
        }
    }
    
    CVPixelBufferUnlockBaseAddress(outputBuffer, 0);
    
    NSLog(@"✅ Created solid %@ background", _backgroundColor);
    return outputBuffer;
}

- (void)dealloc {
    if (_pixelBufferPool) {
        CVPixelBufferPoolRelease(_pixelBufferPool);
    }
}

@end
