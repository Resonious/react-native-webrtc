#import <objc/runtime.h>

#import <React/RCTBridge.h>
#import <React/RCTBridgeModule.h>

#import "WebRTCModule.h"
#import <WebRTC/WebRTC.h>

// Store a weak reference to the peerConnectionFactory so it can be accessed from CallKit
static __weak RTCPeerConnectionFactory *sharedPeerConnectionFactory = nil;

@implementation WebRTCModule (RTCAudioSession)

RCT_EXPORT_BLOCKING_SYNCHRONOUS_METHOD(audioSessionDidActivate) {
    [[RTCAudioSession sharedInstance] audioSessionDidActivate:[AVAudioSession sharedInstance]];
    return nil;
}

RCT_EXPORT_BLOCKING_SYNCHRONOUS_METHOD(audioSessionDidDeactivate) {
    [[RTCAudioSession sharedInstance] audioSessionDidDeactivate:[AVAudioSession sharedInstance]];
    return nil;
}

+ (void)setSharedPeerConnectionFactory:(RTCPeerConnectionFactory *)factory {
    sharedPeerConnectionFactory = factory;
}

+ (BOOL)setEngineAvailabilityWithInput:(BOOL)isInputAvailable output:(BOOL)isOutputAvailable {
    RTCPeerConnectionFactory *factory = sharedPeerConnectionFactory;
    if (!factory) {
        NSLog(@"[WebRTC] Warning: setEngineAvailability called but peerConnectionFactory is nil");
        return NO;
    }

    RTCAudioDeviceModule *audioDeviceModule = factory.audioDeviceModule;
    if (![audioDeviceModule respondsToSelector:@selector(setEngineAvailability:)]) {
        NSLog(@"[WebRTC] Warning: audioDeviceModule does not support setEngineAvailability");
        return NO;
    }

    RTCAudioEngineAvailability availability = {
        .isInputAvailable = isInputAvailable,
        .isOutputAvailable = isOutputAvailable
    };
    int result = [audioDeviceModule setEngineAvailability:availability];
    if (result == -1) {
        NSLog(@"[WebRTC] Error: setEngineAvailability failed with return code -1 (input: %d, output: %d)", isInputAvailable, isOutputAvailable);
        return NO;
    }
    NSLog(@"[WebRTC] Set engine availability - input: %d, output: %d (result: %d)", isInputAvailable, isOutputAvailable, result);
    return YES;
}

@end
