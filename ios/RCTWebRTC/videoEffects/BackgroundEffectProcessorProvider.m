#import "BackgroundEffectProcessorProvider.h"
#import "ProcessorProvider.h"
#import "BackgroundEffectProcessor.h"

@implementation BackgroundEffectProcessorProvider

+ (void)registerProcessors {
    NSLog(@"🔥 INSIDE registerProcessors METHOD - SOURCE VERSION");
    
    // Register white background effect
    BackgroundEffectProcessor *whiteProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:[UIColor whiteColor]];
    [ProcessorProvider addProcessor:whiteProcessor forName:@"backgroundWhite"];
    NSLog(@"[BackgroundEffectProcessorProvider] Registered backgroundWhite processor");
    
    // Register blur effect (placeholder - would need actual blur implementation)
    BackgroundEffectProcessor *blurProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:[UIColor whiteColor]];
    [ProcessorProvider addProcessor:blurProcessor forName:@"backgroundBlur"];
    NSLog(@"[BackgroundEffectProcessorProvider] Registered backgroundBlur processor");
    
    // Register green screen effect
    BackgroundEffectProcessor *greenProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:[UIColor greenColor]];
    [ProcessorProvider addProcessor:greenProcessor forName:@"backgroundGreen"];
    NSLog(@"[BackgroundEffectProcessorProvider] Registered backgroundGreen processor");
    
    // Register custom gray background
    UIColor *customColor = [UIColor colorWithRed:0.94 green:0.94 blue:0.94 alpha:1.0];
    BackgroundEffectProcessor *customProcessor = [[BackgroundEffectProcessor alloc] 
        initWithBackgroundColor:customColor];
    [ProcessorProvider addProcessor:customProcessor forName:@"backgroundCustom"];
    NSLog(@"[BackgroundEffectProcessorProvider] Registered backgroundCustom processor");
    
    // Register high quality version
    if (@available(iOS 15.0, *)) {
        BackgroundEffectProcessor *hdProcessor = [[BackgroundEffectProcessor alloc] 
            initWithBackgroundColor:[UIColor whiteColor]
            qualityLevel:2]; // VNPersonSegmentationQualityLevelAccurate
        [ProcessorProvider addProcessor:hdProcessor forName:@"backgroundWhiteHD"];
        NSLog(@"[BackgroundEffectProcessorProvider] Registered backgroundWhiteHD processor");
    }
    
    NSLog(@"[BackgroundEffectProcessorProvider] Processor registration complete");
}

@end