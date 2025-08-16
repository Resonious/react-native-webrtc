/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 * @flow strict-local
 */

import React, {useState, useRef} from 'react';
import {
  Button,
  SafeAreaView,
  StyleSheet,
  View,
  StatusBar,
  Text,
  ScrollView,
} from 'react-native';
import { Colors } from 'react-native/Libraries/NewAppScreen';
import { mediaDevices, startIOSPIP, stopIOSPIP, RTCPIPView } from '@baillie/react-native-webrtc';


const App = () => {
  const view = useRef()
  const [stream, setStream] = useState(null);
  const [currentEffect, setCurrentEffect] = useState('none');
  const start = async () => {
    console.log('start');
    if (!stream) {
      try {
        const s = await mediaDevices.getUserMedia({ video: true });
        setStream(s);
      } catch(e) {
        console.error(e);
      }
    }
  };
  const startPIP = () => {
    startIOSPIP(view);
  };
  const stopPIP = () => {
    stopIOSPIP(view);
  };
  const stop = () => {
    console.log('stop');
    if (stream) {
      stream.release();
      setStream(null);
      setCurrentEffect('none');
    }
  };

  // Apply background effect
  const applyEffect = (effectName) => {
    if (!stream) {
      console.warn('No stream available');
      return;
    }

    try {
      const videoTrack = stream.getVideoTracks()[0];
      if (videoTrack && videoTrack._setVideoEffect) {
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
    if (!stream) return;
    
    try {
      const videoTrack = stream.getVideoTracks()[0];
      if (videoTrack && videoTrack._setVideoEffects) {
        videoTrack._setVideoEffects([]);
        setCurrentEffect('none');
        console.log('Removed all effects');
      }
    } catch (error) {
      console.error('Error removing effects:', error);
    }
  };
  let pipOptions = {
    startAutomatically: true,
    fallbackView: (<View style={{ height: 50, width: 50, backgroundColor: 'red' }} />),
    preferredSize: {
      width: 400,
      height: 800,
    }
  }
  return (
    <>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.body}>
      {
        stream &&
        <RTCPIPView
            ref={view}
            streamURL={stream.toURL()}
            style={styles.stream}
            iosPIP={pipOptions} >
        </RTCPIPView>
      }
        <ScrollView style={styles.footer}>
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Stream Controls</Text>
            <View style={styles.buttonRow}>
              <Button
                title = "Start"
                onPress = {start} />
              <Button
                title = "Stop"
                onPress = {stop} />
            </View>
          </View>

          <View style={styles.section}>
            <Text style={styles.sectionTitle}>PIP Controls</Text>
            <View style={styles.buttonRow}>
              <Button
                title = "Start PIP"
                onPress = {startPIP} />
              <Button
                title = "Stop PIP"
                onPress = {stopPIP} />
            </View>
          </View>

          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Background Effects</Text>
            <Text style={styles.currentEffect}>Current: {currentEffect}</Text>
            <View style={styles.buttonRow}>
              <Button
                title = "White BG"
                onPress = {() => applyEffect('backgroundWhite')}
                disabled = {!stream} />
              <Button
                title = "Green BG"
                onPress = {() => applyEffect('backgroundGreen')}
                disabled = {!stream} />
            </View>
            <View style={styles.buttonRow}>
              <Button
                title = "Custom BG"
                onPress = {() => applyEffect('backgroundCustom')}
                disabled = {!stream} />
              <Button
                title = "Remove Effects"
                onPress = {removeEffects}
                disabled = {!stream || currentEffect === 'none'}
                color = "#FF3B30" />
            </View>
          </View>
        </ScrollView>
      </SafeAreaView>
    </>
  );
};

const styles = StyleSheet.create({
  body: {
    backgroundColor: Colors.white,
    ...StyleSheet.absoluteFill
  },
  stream: {
    flex: 1
  },
  footer: {
    backgroundColor: Colors.lighter,
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    maxHeight: 250,
    padding: 10,
  },
  section: {
    marginBottom: 15,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    marginBottom: 8,
    color: '#333',
  },
  currentEffect: {
    fontSize: 14,
    color: '#666',
    marginBottom: 8,
    fontStyle: 'italic',
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    marginBottom: 8,
  },
});

export default App;
