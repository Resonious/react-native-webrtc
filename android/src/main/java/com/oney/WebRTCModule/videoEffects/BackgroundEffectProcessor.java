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
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import org.webrtc.GlUtil;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrame.Buffer;
import org.webrtc.VideoFrame.I420Buffer;
import org.webrtc.VideoFrame.TextureBuffer;
import org.webrtc.YuvConverter;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLContext;

/**
 * Video frame processor that replaces the background with a solid color using ML Kit.
 * This implementation uses ML Kit's selfie segmentation model to detect the person
 * and replace the background with white or any specified color.
 */
public class BackgroundEffectProcessor implements VideoFrameProcessor {
    private static final String TAG = "BackgroundEffectProcessor";
    
    private Segmenter segmenter;
    private final int backgroundColor;
    private YuvConverter yuvConverter;
    private final Matrix transformMatrix = new Matrix();
    
    // OpenGL resources
    private int[] textures = new int[2];
    private boolean glResourcesInitialized = false;
    
    // Frame caching - cache the last fully processed frame
    private VideoFrame cachedProcessedFrame = null;
    private final Object frameCacheLock = new Object();
    private boolean segmentationInProgress = false;
    
    public BackgroundEffectProcessor() {
        this(Color.WHITE); // Default to white background
        Log.e(TAG, "***IMPORTANT*** BackgroundEffectProcessor constructor called - UPDATED VERSION");
    }
    
    public BackgroundEffectProcessor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        Log.e(TAG, "***IMPORTANT*** BackgroundEffectProcessor constructor called with color - UPDATED VERSION");
        initializeSegmenter();
    }
    
    private void initializeSegmenter() {
        try {
            SelfieSegmenterOptions options = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .enableRawSizeMask()
                .build();
                
            segmenter = Segmentation.getClient(options);
            Log.d(TAG, "ML Kit segmenter initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ML Kit segmenter", e);
        }
    }
    
    @Override
    public VideoFrame process(VideoFrame frame, SurfaceTextureHelper textureHelper) {
        if (segmenter == null) {
            Log.w(TAG, "Segmenter not initialized, returning original frame");
            return frame;
        }
        
        Log.d(TAG, "Processing frame for background effect");
        
        try {
            // Convert frame to Bitmap for ML Kit processing
            Bitmap inputBitmap = frameToBitmap(frame, textureHelper);
            if (inputBitmap == null) {
                Log.w(TAG, "Failed to convert frame to bitmap, returning original");
                return frame;
            }
            
            // Apply background with ML Kit segmentation
            Bitmap outputBitmap = processWithSegmentation(inputBitmap);
            
            // Convert back to VideoFrame
            VideoFrame result = bitmapToVideoFrame(outputBitmap, frame, textureHelper);
            
            // Clean up bitmaps immediately to prevent memory issues
            inputBitmap.recycle();
            outputBitmap.recycle();
            
            Log.d(TAG, "Frame processed successfully");
            return result != null ? result : frame;
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
            return frame;
        }
    }
    
    private Bitmap processWithSegmentation(Bitmap inputBitmap) {
        try {
            InputImage inputImage = InputImage.fromBitmap(inputBitmap, 0);
            
            // Use Tasks.await to make ML Kit synchronous
            SegmentationMask mask = Tasks.await(segmenter.process(inputImage), 1000, TimeUnit.MILLISECONDS);
            
            if (mask != null) {
                Bitmap result = applyBackgroundWithMask(inputBitmap, mask);
                Log.d(TAG, "Applied segmentation mask successfully");
                return result;
            } else {
                Log.w(TAG, "No segmentation mask, using simple background");
                return applySimpleBackground(inputBitmap);
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Segmentation failed, using simple background", e);
            return applySimpleBackground(inputBitmap);
        }
    }

    private void processFrameAsync(VideoFrame frame, SurfaceTextureHelper textureHelper) {
        // Check if already processing - if so, skip this frame
        synchronized (frameCacheLock) {
            if (segmentationInProgress) {
                Log.d(TAG, "Already processing, skipping frame");
                return;
            }
            segmentationInProgress = true;
        }
        
        // Retain the frame for async processing
        frame.retain();
        
        // Process on background thread to avoid blocking the video pipeline
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Convert frame to Bitmap for ML Kit processing
                    Bitmap inputBitmap = frameToBitmap(frame, textureHelper);
                    if (inputBitmap == null) {
                        Log.w(TAG, "Failed to convert frame to bitmap");
                        frame.release();
                        segmentationInProgress = false;
                        return;
                    }
                    
                    // Process with ML Kit segmentation
                    InputImage inputImage = InputImage.fromBitmap(inputBitmap, 0);
                    
                    segmenter.process(inputImage)
                        .addOnSuccessListener(new OnSuccessListener<SegmentationMask>() {
                            @Override
                            public void onSuccess(SegmentationMask segmentationMask) {
                                try {
                                    // Apply background effect with segmentation
                                    Bitmap outputBitmap = applyBackgroundWithMask(inputBitmap, segmentationMask);
                                    
                                    // Convert back to VideoFrame
                                    VideoFrame processedFrame = bitmapToVideoFrame(outputBitmap, frame, textureHelper);
                                    
                                    // Cache the completed processed frame
                                    if (processedFrame != null) {
                                        synchronized (frameCacheLock) {
                                            if (cachedProcessedFrame != null) {
                                                cachedProcessedFrame.release();
                                            }
                                            cachedProcessedFrame = processedFrame;
                                            cachedProcessedFrame.retain();
                                            segmentationInProgress = false;
                                        }
                                        processedFrame.release();
                                    }
                                    
                                    // Clean up
                                    inputBitmap.recycle();
                                    outputBitmap.recycle();
                                    frame.release();
                                    
                                    Log.d(TAG, "Frame processed and cached successfully");
                                    
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in segmentation success handler", e);
                                    frame.release();
                                    synchronized (frameCacheLock) {
                                        segmentationInProgress = false;
                                    }
                                }
                            }
                        })
                        .addOnFailureListener(new OnFailureListener() {
                            @Override
                            public void onFailure(Exception e) {
                                Log.w(TAG, "Segmentation failed, using fallback", e);
                                
                                try {
                                    // Fallback: apply simple background
                                    Bitmap outputBitmap = applySimpleBackground(inputBitmap);
                                    VideoFrame processedFrame = bitmapToVideoFrame(outputBitmap, frame, textureHelper);
                                    
                                    if (processedFrame != null) {
                                        synchronized (frameCacheLock) {
                                            if (cachedProcessedFrame != null) {
                                                cachedProcessedFrame.release();
                                            }
                                            cachedProcessedFrame = processedFrame;
                                            cachedProcessedFrame.retain();
                                            segmentationInProgress = false;
                                        }
                                        processedFrame.release();
                                    }
                                    
                                    inputBitmap.recycle();
                                    outputBitmap.recycle();
                                } catch (Exception fallbackError) {
                                    Log.e(TAG, "Error in fallback processing", fallbackError);
                                }
                                
                                frame.release();
                                synchronized (frameCacheLock) {
                                    segmentationInProgress = false;
                                }
                            }
                        });
                        
                } catch (Exception e) {
                    Log.e(TAG, "Error starting async frame processing", e);
                    frame.release();
                    synchronized (frameCacheLock) {
                        segmentationInProgress = false;
                    }
                }
            }
        }).start();
    }
    
    private Bitmap applyBackgroundWithMask(Bitmap input, SegmentationMask mask) {
        int width = input.getWidth();
        int height = input.getHeight();
        
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        
        // Draw background color
        canvas.drawColor(backgroundColor);
        
        try {
            // Get mask data
            ByteBuffer maskBuffer = mask.getBuffer();
            int maskWidth = mask.getWidth();
            int maskHeight = mask.getHeight();
            
            // Calculate scaling factors
            float scaleX = (float) maskWidth / width;
            float scaleY = (float) maskHeight / height;
            
            // Create a bitmap for the person (foreground)
            Bitmap personBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            
            // Process each pixel
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // Map to mask coordinates
                    int maskX = Math.min((int)(x * scaleX), maskWidth - 1);
                    int maskY = Math.min((int)(y * scaleY), maskHeight - 1);
                    
                    // Get confidence from mask
                    int maskIndex = maskY * maskWidth + maskX;
                    if (maskIndex * 4 < maskBuffer.capacity()) {
                        maskBuffer.position(maskIndex * 4);
                        float confidence = maskBuffer.getFloat();
                        
                        if (confidence > 0.5f) { // Person detected
                            int inputPixel = input.getPixel(x, y);
                            personBitmap.setPixel(x, y, inputPixel);
                        } else {
                            personBitmap.setPixel(x, y, Color.TRANSPARENT);
                        }
                    }
                }
            }
            
            // Draw person on top of background
            canvas.drawBitmap(personBitmap, 0, 0, null);
            personBitmap.recycle();
            
        } catch (Exception e) {
            Log.w(TAG, "Error applying mask, falling back to simple background", e);
            // If mask processing fails, draw input with some transparency
            Paint paint = new Paint();
            paint.setAlpha(180);
            canvas.drawBitmap(input, 0, 0, paint);
        }
        
        return output;
    }
    
    private Bitmap applySimpleBackground(Bitmap input) {
        int width = input.getWidth();
        int height = input.getHeight();
        
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        
        // Draw background
        canvas.drawColor(backgroundColor);
        
        // Draw input with some transparency to see the effect
        Paint paint = new Paint();
        paint.setAlpha(128); // 50% opacity
        canvas.drawBitmap(input, 0, 0, paint);
        
        return output;
    }
    
    private Bitmap frameToBitmap(VideoFrame frame, SurfaceTextureHelper textureHelper) {
        try {
            Buffer buffer = frame.getBuffer();
            
            if (buffer instanceof TextureBuffer) {
                return textureBufferToBitmap((TextureBuffer) buffer, textureHelper);
            } else if (buffer instanceof I420Buffer) {
                return i420BufferToBitmap((I420Buffer) buffer);
            }
            
            Log.w(TAG, "Unsupported buffer type: " + buffer.getClass().getSimpleName());
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting frame to bitmap", e);
            return null;
        }
    }
    
    private Bitmap textureBufferToBitmap(TextureBuffer textureBuffer, SurfaceTextureHelper textureHelper) {
        try {
            int width = textureBuffer.getWidth();
            int height = textureBuffer.getHeight();
            
            // Use YuvConverter to convert texture to bitmap
            if (yuvConverter == null) {
                yuvConverter = new YuvConverter();
            }
            
            // Create I420 buffer from texture
            I420Buffer i420Buffer = yuvConverter.convert(textureBuffer);
            
            // Convert I420 to bitmap
            Bitmap bitmap = i420BufferToBitmap(i420Buffer);
            
            // Release the I420 buffer
            i420Buffer.release();
            
            return bitmap;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting texture buffer to bitmap", e);
            
            // Fallback: create a simple bitmap
            int width = textureBuffer.getWidth();
            int height = textureBuffer.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.GRAY);
            return bitmap;
        }
    }
    
    private Bitmap i420BufferToBitmap(I420Buffer i420Buffer) {
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            
            // Create bitmap
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            
            // Convert YUV to RGB
            ByteBuffer yBuffer = i420Buffer.getDataY();
            ByteBuffer uBuffer = i420Buffer.getDataU();
            ByteBuffer vBuffer = i420Buffer.getDataV();
            
            int yStride = i420Buffer.getStrideY();
            int uStride = i420Buffer.getStrideU();
            int vStride = i420Buffer.getStrideV();
            
            int[] pixels = new int[width * height];
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int yIndex = y * yStride + x;
                    int uvIndex = (y / 2) * uStride + (x / 2);
                    
                    if (yIndex < yBuffer.capacity() && uvIndex < uBuffer.capacity() && uvIndex < vBuffer.capacity()) {
                        int yValue = yBuffer.get(yIndex) & 0xFF;
                        int uValue = uBuffer.get(uvIndex) & 0xFF;
                        int vValue = vBuffer.get(uvIndex) & 0xFF;
                        
                        // YUV to RGB conversion
                        int r = (int) (yValue + 1.402 * (vValue - 128));
                        int g = (int) (yValue - 0.344136 * (uValue - 128) - 0.714136 * (vValue - 128));
                        int b = (int) (yValue + 1.772 * (uValue - 128));
                        
                        // Clamp values
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));
                        
                        pixels[y * width + x] = Color.rgb(r, g, b);
                    }
                }
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting I420 to bitmap", e);
            
            // Fallback
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.LTGRAY);
            return bitmap;
        }
    }
    
    private VideoFrame bitmapToVideoFrame(Bitmap bitmap, VideoFrame originalFrame, SurfaceTextureHelper textureHelper) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            // Create I420 buffer from bitmap
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            // Convert RGB to YUV
            byte[] yData = new byte[width * height];
            byte[] uData = new byte[width * height / 4];
            byte[] vData = new byte[width * height / 4];
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[y * width + x];
                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);
                    
                    // RGB to YUV conversion
                    int yValue = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                    int uValue = (int) (-0.147 * r - 0.289 * g + 0.436 * b + 128);
                    int vValue = (int) (0.615 * r - 0.515 * g - 0.100 * b + 128);
                    
                    yData[y * width + x] = (byte) Math.max(0, Math.min(255, yValue));
                    
                    if (y % 2 == 0 && x % 2 == 0) {
                        int uvIndex = (y / 2) * (width / 2) + (x / 2);
                        uData[uvIndex] = (byte) Math.max(0, Math.min(255, uValue));
                        vData[uvIndex] = (byte) Math.max(0, Math.min(255, vValue));
                    }
                }
            }
            
            // Create I420 buffer
            ByteBuffer yBuffer = ByteBuffer.allocateDirect(yData.length);
            ByteBuffer uBuffer = ByteBuffer.allocateDirect(uData.length);
            ByteBuffer vBuffer = ByteBuffer.allocateDirect(vData.length);
            
            yBuffer.put(yData);
            uBuffer.put(uData);
            vBuffer.put(vData);
            
            yBuffer.rewind();
            uBuffer.rewind();
            vBuffer.rewind();
            
            I420Buffer i420Buffer = new I420Buffer() {
                @Override
                public int getWidth() { return width; }
                
                @Override
                public int getHeight() { return height; }
                
                @Override
                public ByteBuffer getDataY() { return yBuffer; }
                
                @Override
                public ByteBuffer getDataU() { return uBuffer; }
                
                @Override
                public ByteBuffer getDataV() { return vBuffer; }
                
                @Override
                public int getStrideY() { return width; }
                
                @Override
                public int getStrideU() { return width / 2; }
                
                @Override
                public int getStrideV() { return width / 2; }
                
                @Override
                public void retain() {}
                
                @Override
                public void release() {}
                
                @Override
                public I420Buffer toI420() { return this; }
                
                @Override
                public Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight, int scaleWidth, int scaleHeight) {
                    return null;
                }
            };
            
            return new VideoFrame(i420Buffer, originalFrame.getRotation(), originalFrame.getTimestampNs());
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting bitmap to VideoFrame", e);
            return null;
        }
    }
    
    public void release() {
        Log.d(TAG, "Releasing BackgroundEffectProcessor resources");
        
        try {
            if (segmenter != null) {
                segmenter.close();
                segmenter = null;
            }
            
            synchronized (frameCacheLock) {
                if (cachedProcessedFrame != null) {
                    cachedProcessedFrame.release();
                    cachedProcessedFrame = null;
                }
            }
            
            if (glResourcesInitialized && textures != null) {
                GLES20.glDeleteTextures(2, textures, 0);
                glResourcesInitialized = false;
            }
            
            if (yuvConverter != null) {
                yuvConverter.release();
                yuvConverter = null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during release", e);
        }
    }
}