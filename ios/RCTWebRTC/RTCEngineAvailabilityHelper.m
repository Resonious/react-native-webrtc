#import "RTCEngineAvailabilityHelper.h"
#import <objc/runtime.h>

// Store a weak reference to the peerConnectionFactory
static __weak RTCPeerConnectionFactory *sharedPeerConnectionFactory = nil;

@implementation RTCEngineAvailabilityHelper

+ (void)setSharedPeerConnectionFactory:(RTCPeerConnectionFactory *)factory {
    sharedPeerConnectionFactory = factory;
}

+ (BOOL)setEngineAvailabilityWithInput:(BOOL)isInputAvailable output:(BOOL)isOutputAvailable {
    RTCPeerConnectionFactory *factory = sharedPeerConnectionFactory;
    if (!factory) {
        NSLog(@"[RTCEngineAvailabilityHelper] Warning: peerConnectionFactory is nil. Make sure react-native-webrtc is initialized.");
        return NO;
    }

    RTCAudioDeviceModule *audioDeviceModule = factory.audioDeviceModule;
    if (![audioDeviceModule respondsToSelector:@selector(setEngineAvailability:)]) {
        NSLog(@"[RTCEngineAvailabilityHelper] Warning: audioDeviceModule does not support setEngineAvailability. WebRTC version may be too old.");
        return NO;
    }

    // RTCAudioEngineAvailability is a struct, not a class
    RTCAudioEngineAvailability availability = {
        .isInputAvailable = isInputAvailable,
        .isOutputAvailable = isOutputAvailable
    };
    [audioDeviceModule setEngineAvailability:availability];
    NSLog(@"[RTCEngineAvailabilityHelper] Set engine availability - input: %d, output: %d", isInputAvailable, isOutputAvailable);
    return YES;
}

@end
