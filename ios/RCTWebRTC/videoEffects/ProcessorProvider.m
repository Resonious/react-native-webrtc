#import "ProcessorProvider.h"

@implementation ProcessorProvider

static NSMutableDictionary<NSString *, NSObject<VideoFrameProcessorDelegate> *> *processorMap;

+ (void)initialize {
    processorMap = [[NSMutableDictionary alloc] init];
}

+ (NSObject<VideoFrameProcessorDelegate> *)getProcessor:(NSString *)name {
    NSObject<VideoFrameProcessorDelegate> *processor = [processorMap objectForKey:name];
    NSLog(@"[ProcessorProvider] Getting processor '%@': %@", name, processor);
    return processor;
}

+ (void)addProcessor:(NSObject<VideoFrameProcessorDelegate> *)processor forName:(NSString *)name {
    NSLog(@"[ProcessorProvider] Adding processor '%@': %@", name, processor);
    [processorMap setObject:processor forKey:name];
    NSLog(@"[ProcessorProvider] Total processors registered: %lu", (unsigned long)[processorMap count]);
}

+ (void)removeProcessor:(NSString *)name {
    [processorMap removeObjectForKey:name];
}

@end
