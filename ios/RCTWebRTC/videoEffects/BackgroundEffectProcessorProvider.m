#import "ProcessorProvider.h"
#import "BackgroundEffectProcessor.h"

/**
 * Registration helper for BackgroundEffectProcessor.
 * This should be called during module initialization to register the background effects.
 */
@interface BackgroundEffectProcessorProvider : NSObject

+ (void)registerProcessors;

@end

@implementation BackgroundEffectProcessorProvider

+ (void)registerProcessors {
    // Register white background effect
    BackgroundEffectProcessor *whiteProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:[UIColor whiteColor]];
    [ProcessorProvider addProcessor:whiteProcessor forName:@"backgroundWhite"];
    
    // Register blur effect (placeholder - would need actual blur implementation)
    BackgroundEffectProcessor *blurProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:[UIColor whiteColor]];
    [ProcessorProvider addProcessor:blurProcessor forName:@"backgroundBlur"];
    
    // Register green screen effect
    BackgroundEffectProcessor *greenProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:[UIColor greenColor]];
    [ProcessorProvider addProcessor:greenProcessor forName:@"backgroundGreen"];
    
    // Register custom gray background
    UIColor *customColor = [UIColor colorWithRed:0.94 green:0.94 blue:0.94 alpha:1.0];
    BackgroundEffectProcessor *customProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:customColor];
    [ProcessorProvider addProcessor:customProcessor forName:@"backgroundCustom"];
    
    // Register high quality version
    if (@available(iOS 15.0, *)) {
        BackgroundEffectProcessor *hdProcessor = [[BackgroundEffectProcessor alloc] 
            initWithBackgroundColor:[UIColor whiteColor]
            qualityLevel:VNPersonSegmentationQualityLevelAccurate];
        [ProcessorProvider addProcessor:hdProcessor forName:@"backgroundWhiteHD"];
    }
}

@end