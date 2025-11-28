#import <Foundation/Foundation.h>
#import <WebRTC/WebRTC.h>

NS_ASSUME_NONNULL_BEGIN

/// Helper class to control WebRTC audio engine availability from Swift/ObjC code.
/// Useful for CallKit integration where you need to control when the audio engine runs.
@interface RTCEngineAvailabilityHelper : NSObject

/// Sets the shared peer connection factory reference.
/// This is called automatically by react-native-webrtc when the factory is created.
/// If there is pending engine availability state from CallKit, it will be applied automatically.
/// @return YES if pending state was applied, NO otherwise
+ (BOOL)setSharedPeerConnectionFactory:(RTCPeerConnectionFactory *)factory;

/// Controls whether the audio engine is allowed to run.
/// If the factory is not yet initialized, the state is stored and applied when factory is set.
/// @param isInputAvailable Whether audio input (microphone) should be available
/// @param isOutputAvailable Whether audio output (speaker/receiver) should be available
/// @return YES if successful, NO if factory is not initialized (state will be stored for later)
+ (BOOL)setEngineAvailabilityWithInput:(BOOL)isInputAvailable output:(BOOL)isOutputAvailable;

@end

NS_ASSUME_NONNULL_END
