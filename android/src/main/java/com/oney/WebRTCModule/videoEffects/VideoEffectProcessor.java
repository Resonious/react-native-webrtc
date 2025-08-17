package com.oney.WebRTCModule.videoEffects;

import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoFrame;
import org.webrtc.VideoProcessor;
import org.webrtc.VideoSink;

import java.util.List;

/**
 * Lightweight abstraction for an object that can receive video frames, process and add effects in
 * them, and pass them on to another object.
 */
public class VideoEffectProcessor implements VideoProcessor {
    private VideoSink mSink;
    final private SurfaceTextureHelper textureHelper;
    final private List<VideoFrameProcessor> videoFrameProcessors;

    public VideoEffectProcessor(List<VideoFrameProcessor> processors, SurfaceTextureHelper textureHelper) {
        this.textureHelper = textureHelper;
        this.videoFrameProcessors = processors;
    }

    @Override
    public void onCapturerStarted(boolean success) {}

    @Override
    public void onCapturerStopped() {}

    @Override
    public void setSink(VideoSink sink) {
        mSink = sink;
    }

    /**
     * Called just after the frame is captured.
     * Will process the VideoFrame with the help of VideoFrameProcessor and send the processed
     * VideoFrame back to webrtc using onFrame method in VideoSink.
     * @param frame raw VideoFrame received from webrtc.
     */
    @Override
    public void onFrameCaptured(VideoFrame frame) {
        if (frame == null || mSink == null) {
            return;
        }
        
        frame.retain();
        VideoFrame outputFrame = frame;
        
        try {
            for (VideoFrameProcessor processor : this.videoFrameProcessors) {
                if (processor == null) {
                    continue;
                }
                
                VideoFrame processedFrame = processor.process(outputFrame, textureHelper);
                
                if (processedFrame == null) {
                    // If processing failed, send original frame
                    mSink.onFrame(frame);
                    frame.release();
                    return;
                }
                
                // Release the previous frame if it's not the original
                if (outputFrame != frame) {
                    outputFrame.release();
                }
                
                outputFrame = processedFrame;
            }

            mSink.onFrame(outputFrame);
            
        } catch (Exception e) {
            android.util.Log.e("VideoEffectProcessor", "Error processing frame", e);
            // Send original frame if processing fails
            mSink.onFrame(frame);
        } finally {
            // Clean up
            if (outputFrame != frame) {
                outputFrame.release();
            }
            frame.release();
        }
    }
}
