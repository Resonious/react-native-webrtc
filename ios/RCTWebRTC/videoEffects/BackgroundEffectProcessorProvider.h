#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Registration helper for BackgroundEffectProcessor.
 * This should be called during module initialization to register the background effects.
 */
@interface BackgroundEffectProcessorProvider : NSObject

+ (void)registerProcessors;

@end

NS_ASSUME_NONNULL_END