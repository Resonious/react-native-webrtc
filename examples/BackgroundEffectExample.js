/**
 * Example usage of the background effect feature in React Native WebRTC
 * 
 * This example demonstrates how to apply background replacement effects
 * to a video stream using the MediaStreamTrack API.
 */

import React, { useEffect, useState } from 'react';
import {
  View,
  Button,
  StyleSheet,
  Text,
  ScrollView,
  Platform,
} from 'react-native';
import {
  RTCView,
  mediaDevices,
  MediaStream,
  MediaStreamTrack,
} from 'react-native-webrtc';

const BackgroundEffectExample = () => {
  const [localStream, setLocalStream] = useState(null);
  const [currentEffect, setCurrentEffect] = useState('none');
  const [videoTrack, setVideoTrack] = useState(null);

  // Initialize media stream
  useEffect(() => {
    const initStream = async () => {
      try {
        const stream = await mediaDevices.getUserMedia({
          video: {
            facingMode: 'user',
            width: { ideal: 1280 },
            height: { ideal: 720 },
          },
          audio: false,
        });
        
        setLocalStream(stream);
        const vTrack = stream.getVideoTracks()[0];
        setVideoTrack(vTrack);
      } catch (error) {
        console.error('Error accessing camera:', error);
      }
    };

    initStream();

    // Cleanup
    return () => {
      if (localStream) {
        localStream.getTracks().forEach(track => track.stop());
      }
    };
  }, []);

  // Apply background effect
  const applyEffect = (effectName) => {
    if (!videoTrack) {
      console.warn('No video track available');
      return;
    }

    try {
      // Apply the effect using the private API
      // In production, you might want to expose this as a public method
      if (videoTrack._setVideoEffect) {
        videoTrack._setVideoEffect(effectName);
        setCurrentEffect(effectName);
        console.log(`Applied effect: ${effectName}`);
      } else {
        console.warn('Video effects not supported on this track');
      }
    } catch (error) {
      console.error('Error applying effect:', error);
    }
  };

  // Remove all effects
  const removeEffects = () => {
    if (!videoTrack) return;
    
    try {
      // Clear effects by passing empty array
      if (videoTrack._setVideoEffects) {
        videoTrack._setVideoEffects([]);
        setCurrentEffect('none');
        console.log('Removed all effects');
      }
    } catch (error) {
      console.error('Error removing effects:', error);
    }
  };

  // Available effects
  const effects = [
    { name: 'backgroundWhite', label: 'White Background' },
    { name: 'backgroundBlur', label: 'Blur Background' },
    { name: 'backgroundGreen', label: 'Green Screen' },
    { name: 'backgroundCustom', label: 'Custom Gray' },
  ];

  // Platform-specific effects
  if (Platform.OS === 'ios' && Platform.Version >= 15) {
    effects.push({ name: 'backgroundWhiteHD', label: 'HD White (iOS 15+)' });
  }

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.title}>Background Effect Demo</Text>
      
      {/* Video preview */}
      <View style={styles.videoContainer}>
        {localStream && (
          <RTCView
            streamURL={localStream.toURL()}
            style={styles.video}
            objectFit="cover"
            mirror={true}
          />
        )}
        {!localStream && (
          <Text style={styles.loadingText}>Loading camera...</Text>
        )}
      </View>

      {/* Current effect indicator */}
      <View style={styles.statusContainer}>
        <Text style={styles.statusLabel}>Current Effect:</Text>
        <Text style={styles.statusValue}>{currentEffect}</Text>
      </View>

      {/* Effect buttons */}
      <View style={styles.buttonContainer}>
        {effects.map((effect) => (
          <Button
            key={effect.name}
            title={effect.label}
            onPress={() => applyEffect(effect.name)}
            disabled={!videoTrack}
            color={currentEffect === effect.name ? '#007AFF' : '#8E8E93'}
          />
        ))}
        <Button
          title="Remove Effects"
          onPress={removeEffects}
          disabled={!videoTrack || currentEffect === 'none'}
          color="#FF3B30"
        />
      </View>

      {/* Instructions */}
      <View style={styles.instructionsContainer}>
        <Text style={styles.instructionsTitle}>Instructions:</Text>
        <Text style={styles.instructionsText}>
          1. Allow camera access when prompted{'\n'}
          2. Select a background effect from the buttons above{'\n'}
          3. The effect will be applied in real-time{'\n'}
          4. Use "Remove Effects" to return to normal video{'\n'}
          {'\n'}
          Note: Effects require iOS 15+ or Android with MediaPipe support.
        </Text>
      </View>

      {/* Performance tips */}
      <View style={styles.tipsContainer}>
        <Text style={styles.tipsTitle}>Performance Tips:</Text>
        <Text style={styles.tipsText}>
          • Effects use GPU processing and may impact battery life{'\n'}
          • For best results, ensure good lighting{'\n'}
          • HD effects require more processing power{'\n'}
          • Consider reducing video resolution if performance is poor
        </Text>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F2F2F7',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    textAlign: 'center',
    marginVertical: 20,
    color: '#000',
  },
  videoContainer: {
    height: 300,
    backgroundColor: '#000',
    marginHorizontal: 20,
    borderRadius: 12,
    overflow: 'hidden',
    justifyContent: 'center',
    alignItems: 'center',
  },
  video: {
    width: '100%',
    height: '100%',
  },
  loadingText: {
    color: '#FFF',
    fontSize: 16,
  },
  statusContainer: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    marginVertical: 15,
    paddingHorizontal: 20,
  },
  statusLabel: {
    fontSize: 16,
    fontWeight: '600',
    marginRight: 10,
    color: '#000',
  },
  statusValue: {
    fontSize: 16,
    color: '#007AFF',
    fontWeight: 'bold',
  },
  buttonContainer: {
    paddingHorizontal: 20,
    gap: 10,
  },
  instructionsContainer: {
    margin: 20,
    padding: 15,
    backgroundColor: '#FFF',
    borderRadius: 8,
  },
  instructionsTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 10,
    color: '#000',
  },
  instructionsText: {
    fontSize: 14,
    lineHeight: 20,
    color: '#3C3C43',
  },
  tipsContainer: {
    margin: 20,
    marginTop: 0,
    padding: 15,
    backgroundColor: '#FFF',
    borderRadius: 8,
  },
  tipsTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 10,
    color: '#000',
  },
  tipsText: {
    fontSize: 14,
    lineHeight: 20,
    color: '#3C3C43',
  },
});

export default BackgroundEffectExample;