#import <Foundation/Foundation.h>
#import <WebRTC/WebRTC.h>

NS_ASSUME_NONNULL_BEGIN

/// Helper class to control WebRTC audio engine availability from Swift/ObjC code.
/// Useful for CallKit integration where you need to control when the audio engine runs.
@interface RTCEngineAvailabilityHelper : NSObject

/// Sets the shared peer connection factory reference.
/// This is called automatically by react-native-webrtc when the factory is created.
+ (void)setSharedPeerConnectionFactory:(RTCPeerConnectionFactory *)factory;

/// Controls whether the audio engine is allowed to run.
/// @param isInputAvailable Whether audio input (microphone) should be available
/// @param isOutputAvailable Whether audio output (speaker/receiver) should be available
/// @return YES if successful, NO if factory is not initialized or API not available
+ (BOOL)setEngineAvailabilityWithInput:(BOOL)isInputAvailable output:(BOOL)isOutputAvailable;

@end

NS_ASSUME_NONNULL_END
