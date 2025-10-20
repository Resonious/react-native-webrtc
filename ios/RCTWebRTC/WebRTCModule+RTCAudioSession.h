#import "WebRTCModule.h"

@interface WebRTCModule (RTCAudioSession)

+ (void)setSharedPeerConnectionFactory:(RTCPeerConnectionFactory *)factory;

/// Sets the audio engine availability for input and output.
/// @param isInputAvailable Whether audio input (microphone) should be available
/// @param isOutputAvailable Whether audio output (speaker/receiver) should be available
/// @return YES if the setting was successfully applied, NO otherwise
+ (BOOL)setEngineAvailabilityWithInput:(BOOL)isInputAvailable output:(BOOL)isOutputAvailable;

@end
