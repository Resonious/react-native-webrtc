#import "RTCEngineAvailabilityHelper.h"
#import <objc/runtime.h>

// Store a weak reference to the peerConnectionFactory
static __weak RTCPeerConnectionFactory *sharedPeerConnectionFactory = nil;

// Store pending engine availability state for when factory isn't ready yet
static BOOL hasPendingAvailability = NO;
static BOOL pendingInputAvailable = NO;
static BOOL pendingOutputAvailable = NO;

@implementation RTCEngineAvailabilityHelper

+ (BOOL)setSharedPeerConnectionFactory:(RTCPeerConnectionFactory *)factory {
    sharedPeerConnectionFactory = factory;

    // If there's a pending availability state from CallKit, apply it now
    if (hasPendingAvailability && factory != nil) {
        NSLog(@"[RTCEngineAvailabilityHelper] Applying pending engine availability - input: %d, output: %d", pendingInputAvailable, pendingOutputAvailable);
        [self setEngineAvailabilityWithInput:pendingInputAvailable output:pendingOutputAvailable];
        // Note: hasPendingAvailability is cleared inside setEngineAvailabilityWithInput on success
        return YES; // Pending state was applied
    }
    return NO; // No pending state
}

+ (BOOL)setEngineAvailabilityWithInput:(BOOL)isInputAvailable output:(BOOL)isOutputAvailable {
    RTCPeerConnectionFactory *factory = sharedPeerConnectionFactory;
    if (!factory) {
        // Factory not ready yet (app cold starting from push notification)
        // Store the state and apply it when factory is initialized
        NSLog(@"[RTCEngineAvailabilityHelper] peerConnectionFactory is nil, storing pending state - input: %d, output: %d", isInputAvailable, isOutputAvailable);
        hasPendingAvailability = YES;
        pendingInputAvailable = isInputAvailable;
        pendingOutputAvailable = isOutputAvailable;
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
    int result = [audioDeviceModule setEngineAvailability:availability];
    if (result == -1) {
        NSLog(@"[RTCEngineAvailabilityHelper] Error: setEngineAvailability failed with return code -1 (input: %d, output: %d)", isInputAvailable, isOutputAvailable);
        return NO;
    }
    NSLog(@"[RTCEngineAvailabilityHelper] Set engine availability - input: %d, output: %d (result: %d)", isInputAvailable, isOutputAvailable, result);

    // Clear pending state since we successfully applied
    hasPendingAvailability = NO;

    return YES;
}

@end
