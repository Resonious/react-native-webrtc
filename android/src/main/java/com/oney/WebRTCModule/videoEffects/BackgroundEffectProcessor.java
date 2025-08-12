package com.oney.WebRTCModule.videoEffects;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import com.google.mediapipe.solutions.selfiesegmentation.SelfieSegmentation;
import com.google.mediapipe.solutions.selfiesegmentation.SelfieSegmentationOptions;
import com.google.mediapipe.solutions.selfiesegmentation.SelfieSegmentationResult;

import org.webrtc.GlUtil;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrame.Buffer;
import org.webrtc.VideoFrame.I420Buffer;
import org.webrtc.VideoFrame.TextureBuffer;
import org.webrtc.YuvConverter;

import java.nio.ByteBuffer;

/**
 * Video frame processor that replaces the background with a solid color using MediaPipe.
 * This implementation uses MediaPipe's selfie segmentation model to detect the person
 * and replace the background with white or any specified color.
 */
public class BackgroundEffectProcessor implements VideoFrameProcessor {
    private static final String TAG = "BackgroundEffectProcessor";
    
    private SelfieSegmentation selfieSegmentation;
    private final int backgroundColor;
    private YuvConverter yuvConverter;
    private TextureBufferImpl outputTextureBuffer;
    private final Matrix transformMatrix = new Matrix();
    
    // OpenGL resources
    private int[] textures = new int[2];
    private boolean glResourcesInitialized = false;
    
    public BackgroundEffectProcessor() {
        this(Color.WHITE); // Default to white background
    }
    
    public BackgroundEffectProcessor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        initializeMediaPipe();
    }
    
    private void initializeMediaPipe() {
        SelfieSegmentationOptions options = SelfieSegmentationOptions.builder()
            .setStaticImageMode(false) // Process video stream
            .setModelSelection(1) // 0: general model, 1: landscape model (better quality)
            .build();
            
        selfieSegmentation = new SelfieSegmentation(options);
        selfieSegmentation.setResultListener(this::onSegmentationResult);
        selfieSegmentation.setErrorListener((message, e) -> {
            android.util.Log.e(TAG, "MediaPipe error: " + message, e);
        });
    }
    
    private SelfieSegmentationResult currentSegmentationResult;
    
    private void onSegmentationResult(SelfieSegmentationResult result) {
        currentSegmentationResult = result;
    }
    
    @Override
    public VideoFrame process(VideoFrame frame, SurfaceTextureHelper textureHelper) {
        if (frame == null || textureHelper == null) {
            return frame;
        }
        
        try {
            // Convert frame to Bitmap for MediaPipe processing
            Bitmap inputBitmap = frameToBitmap(frame, textureHelper);
            if (inputBitmap == null) {
                return frame;
            }
            
            // Send frame to MediaPipe for segmentation
            long timestamp = frame.getTimestampNs() / 1000; // Convert to microseconds
            selfieSegmentation.send(inputBitmap, timestamp);
            
            // Wait for segmentation result (in production, consider async processing)
            if (currentSegmentationResult == null) {
                return frame; // Return original if no segmentation available yet
            }
            
            // Create output bitmap with background replacement
            Bitmap outputBitmap = applyBackgroundEffect(inputBitmap, currentSegmentationResult);
            
            // Convert bitmap back to VideoFrame
            VideoFrame outputFrame = bitmapToVideoFrame(outputBitmap, frame, textureHelper);
            
            // Clean up
            inputBitmap.recycle();
            outputBitmap.recycle();
            
            return outputFrame;
            
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error processing frame", e);
            return frame;
        }
    }
    
    private Bitmap frameToBitmap(VideoFrame frame, SurfaceTextureHelper textureHelper) {
        Buffer buffer = frame.getBuffer();
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        if (buffer instanceof TextureBuffer) {
            // Handle texture buffer
            TextureBuffer textureBuffer = (TextureBuffer) buffer;
            
            // Initialize OpenGL resources if needed
            if (!glResourcesInitialized) {
                initializeGlResources();
            }
            
            // Render texture to bitmap using OpenGL
            textureHelper.getHandler().post(() -> {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureBuffer.getTextureId());
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            });
            
        } else if (buffer instanceof I420Buffer) {
            // Handle I420 buffer
            I420Buffer i420Buffer = (I420Buffer) buffer;
            
            // Convert YUV to RGB
            if (yuvConverter == null) {
                yuvConverter = new YuvConverter();
            }
            
            // This is a simplified conversion - in production, use proper YUV to RGB conversion
            // You might need to implement a custom converter or use existing WebRTC utilities
            android.util.Log.w(TAG, "I420 to Bitmap conversion not fully implemented");
            return null;
        }
        
        return bitmap;
    }
    
    private Bitmap applyBackgroundEffect(Bitmap input, SelfieSegmentationResult segmentationResult) {
        Bitmap mask = segmentationResult.getSegmentationMask();
        if (mask == null) {
            return input;
        }
        
        int width = input.getWidth();
        int height = input.getHeight();
        
        // Create output bitmap
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        
        // Draw white background
        canvas.drawColor(backgroundColor);
        
        // Scale mask to match input size if needed
        Bitmap scaledMask = mask;
        if (mask.getWidth() != width || mask.getHeight() != height) {
            scaledMask = Bitmap.createScaledBitmap(mask, width, height, true);
        }
        
        // Apply person on top using mask
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        
        // Create a paint with the mask as alpha
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int maskPixel = scaledMask.getPixel(x, y);
                float confidence = Color.red(maskPixel) / 255.0f; // Mask is grayscale
                
                if (confidence > 0.5f) { // Threshold for person detection
                    int inputPixel = input.getPixel(x, y);
                    paint.setAlpha((int)(confidence * 255));
                    canvas.drawPoint(x, y, paint);
                    output.setPixel(x, y, inputPixel);
                }
            }
        }
        
        if (scaledMask != mask) {
            scaledMask.recycle();
        }
        
        return output;
    }
    
    private VideoFrame bitmapToVideoFrame(Bitmap bitmap, VideoFrame originalFrame, SurfaceTextureHelper textureHelper) {
        // Create texture from bitmap
        if (!glResourcesInitialized) {
            initializeGlResources();
        }
        
        final int[] textureId = new int[1];
        
        textureHelper.getHandler().post(() -> {
            GLES20.glGenTextures(1, textureId, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0]);
            
            // Set texture parameters
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            
            // Upload bitmap to texture
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        });
        
        // Create TextureBuffer
        TextureBuffer textureBuffer = new TextureBufferImpl(
            bitmap.getWidth(),
            bitmap.getHeight(),
            TextureBuffer.Type.RGB,
            textureId[0],
            transformMatrix,
            textureHelper.getHandler(),
            yuvConverter,
            null
        );
        
        // Create new VideoFrame with the processed texture
        return new VideoFrame(textureBuffer, originalFrame.getRotation(), originalFrame.getTimestampNs());
    }
    
    private void initializeGlResources() {
        GLES20.glGenTextures(2, textures, 0);
        glResourcesInitialized = true;
    }
    
    public void release() {
        if (selfieSegmentation != null) {
            selfieSegmentation.close();
            selfieSegmentation = null;
        }
        
        if (glResourcesInitialized) {
            GLES20.glDeleteTextures(2, textures, 0);
            glResourcesInitialized = false;
        }
        
        if (yuvConverter != null) {
            yuvConverter.release();
            yuvConverter = null;
        }
    }
}