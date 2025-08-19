package com.oney.WebRTCModule.videoEffects;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;

import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoFrame.TextureBuffer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrame.Buffer;
import org.webrtc.VideoFrame.I420Buffer;
import org.webrtc.YuvConverter;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MediaPipe-based video frame processor for background replacement.
 * Uses MediaPipe's selfie segmentation model for real-time person detection.
 */
public class MediaPipeBackgroundProcessor implements VideoFrameProcessor {
    private static final String TAG = "MediaPipeBackgroundProcessor";
    private static final String MODEL_NAME = "selfie_segmenter.tflite";
    
    private ImageSegmenter imageSegmenter;
    private final Context context;
    private final int backgroundColor;
    private YuvConverter yuvConverter;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // Cache for last processed frame
    private VideoFrame lastProcessedFrame = null;
    private long lastProcessedTimestamp = -1;
    private final Object frameLock = new Object();
    
    // Performance tracking
    private long frameCount = 0;
    private long totalProcessingTime = 0;
    
    // Temporal smoothing for better quality
    private byte[] previousMask = null;
    private final float TEMPORAL_SMOOTHING = 0.1f; // Lighter temporal smoothing for performance
    
    public MediaPipeBackgroundProcessor(Context context) {
        this(context, Color.WHITE);
    }
    
    public MediaPipeBackgroundProcessor(Context context, int backgroundColor) {
        this.context = context;
        this.backgroundColor = backgroundColor;
        initializeSegmenter();
        Log.d(TAG, "MediaPipe Background Processor initialized");
    }
    
    private void initializeSegmenter() {
        try {
            // Use CPU delegate - GPU has buffer format issues with our pipeline
            BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .setDelegate(Delegate.CPU) // CPU is more reliable for our use case
                .build();
            Log.d(TAG, "Using CPU delegate for MediaPipe segmentation");
            
            ImageSegmenter.ImageSegmenterOptions options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO) // VIDEO mode optimized for streaming
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build();
            
            imageSegmenter = ImageSegmenter.createFromOptions(context, options);
            Log.d(TAG, "MediaPipe ImageSegmenter initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPipe ImageSegmenter", e);
        }
    }
    
    @Override
    public VideoFrame process(VideoFrame frame, SurfaceTextureHelper textureHelper) {
        if (imageSegmenter == null) {
            Log.w(TAG, "ImageSegmenter not initialized");
            return frame;
        }
        
        // Skip if already processing
        if (!isProcessing.compareAndSet(false, true)) {
            synchronized (frameLock) {
                if (lastProcessedFrame != null && frame.getTimestampNs() - lastProcessedTimestamp < 100_000_000L) {
                    // Return cached frame if less than 100ms old
                    return lastProcessedFrame;
                }
            }
            return frame;
        }
        
        try {
            long totalStartTime = System.currentTimeMillis();
            
            // Convert frame to Bitmap optimized for processing
            long bitmapStart = System.currentTimeMillis();
            Bitmap inputBitmap = frameToBitmapOptimized(frame, textureHelper);
            if (inputBitmap == null) {
                return frame;
            }
            long bitmapTime = System.currentTimeMillis() - bitmapStart;
            
            // Use bitmap directly for MediaPipe - no additional scaling
            long mpImageStart = System.currentTimeMillis();
            MPImage mpImage = new BitmapImageBuilder(inputBitmap).build();
            long mpImageTime = System.currentTimeMillis() - mpImageStart;
            
            // Perform segmentation using VIDEO mode with proper timestamps
            long segmentationStart = System.currentTimeMillis();
            long timestampMs = frame.getTimestampNs() / 1_000_000L;
            ImageSegmenterResult result = imageSegmenter.segmentForVideo(mpImage, timestampMs);
            long segmentationTime = System.currentTimeMillis() - segmentationStart;
            
            // Apply background replacement directly on input bitmap
            long applyStart = System.currentTimeMillis();
            Bitmap outputBitmap = applyBackground(inputBitmap, result);
            long applyTime = System.currentTimeMillis() - applyStart;
            
            // Convert back to VideoFrame
            long videoFrameStart = System.currentTimeMillis();
            VideoFrame processedFrame = bitmapToVideoFrame(outputBitmap, frame, textureHelper);
            long videoFrameTime = System.currentTimeMillis() - videoFrameStart;
            
            // Cache the processed frame
            synchronized (frameLock) {
                if (lastProcessedFrame != null) {
                    lastProcessedFrame.release();
                }
                lastProcessedFrame = processedFrame;
                if (lastProcessedFrame != null) {
                    lastProcessedFrame.retain();
                    lastProcessedTimestamp = frame.getTimestampNs();
                }
            }
            
            // Clean up
            inputBitmap.recycle();
            outputBitmap.recycle();
            mpImage.close();
            
            // Performance tracking with detailed breakdown
            long totalTime = System.currentTimeMillis() - totalStartTime;
            frameCount++;
            totalProcessingTime += totalTime;
            
            // Log performance summary much less frequently
            if (frameCount % 100 == 0) {
                Log.d(TAG, String.format("Frame %d - Avg processing time: %.1fms", 
                    frameCount, (float)totalProcessingTime / frameCount));
            }
            
            return processedFrame != null ? processedFrame : frame;
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
            return frame;
        } finally {
            isProcessing.set(false);
        }
    }
    
    private Bitmap applyBackground(Bitmap input, ImageSegmenterResult segmentationResult) {
        if (segmentationResult == null || !segmentationResult.categoryMask().isPresent()) {
            Log.w(TAG, "No segmentation mask available");
            return input;
        }
        
        // Extract mask buffer using ByteBufferExtractor
        ByteBuffer maskBuffer;
        try {
            maskBuffer = ByteBufferExtractor.extract(segmentationResult.categoryMask().get());
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract mask buffer", e);
            return input;
        }
        
        int maskWidth = segmentationResult.categoryMask().get().getWidth();
        int maskHeight = segmentationResult.categoryMask().get().getHeight();
        
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        
        // Create and smooth the mask for better quality
        byte[] smoothedMask = createSmoothedMask(maskBuffer, maskWidth, maskHeight, inputWidth, inputHeight);
        
        // Use bulk pixel operations for performance
        int[] inputPixels = new int[inputWidth * inputHeight];
        input.getPixels(inputPixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        
        int[] outputPixels = new int[inputWidth * inputHeight];
        
        // Apply background with smoothed mask - be more conservative about person detection
        for (int i = 0; i < inputWidth * inputHeight; i++) {
            byte maskValue = smoothedMask[i];
            int maskInt = maskValue & 0xFF;
            
            // More inclusive for hair and fine details, less bleed-through bias
            if (maskInt <= 80) { // More inclusive for hair and fine details
                outputPixels[i] = inputPixels[i]; // Keep person
            } else if (maskInt >= 160) { // Confident background pixels
                outputPixels[i] = backgroundColor; // Replace background
            } else {
                // For uncertain areas, use simple linear blending for performance
                float alpha = (maskInt - 80) / 80.0f; // Scale 80-160 to 0-1
                
                int inputPixel = inputPixels[i];
                int r = (int)((1 - alpha) * Color.red(inputPixel) + alpha * Color.red(backgroundColor));
                int g = (int)((1 - alpha) * Color.green(inputPixel) + alpha * Color.green(backgroundColor));
                int b = (int)((1 - alpha) * Color.blue(inputPixel) + alpha * Color.blue(backgroundColor));
                outputPixels[i] = Color.rgb(r, g, b);
            }
        }
        
        // Create output bitmap directly from pixel array
        Bitmap output = Bitmap.createBitmap(outputPixels, inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        
        return output;
    }
    
    private byte[] createSmoothedMask(ByteBuffer maskBuffer, int maskWidth, int maskHeight, 
                                     int targetWidth, int targetHeight) {
        byte[] result = new byte[targetWidth * targetHeight];
        float scaleX = (float)maskWidth / targetWidth;
        float scaleY = (float)maskHeight / targetHeight;
        
        // First, resample the mask to target resolution
        byte[] resampledMask = new byte[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int maskX = Math.min((int)(x * scaleX), maskWidth - 1);
                int maskY = Math.min((int)(y * scaleY), maskHeight - 1);
                int maskIndex = maskY * maskWidth + maskX;
                
                if (maskIndex < maskBuffer.capacity()) {
                    resampledMask[y * targetWidth + x] = maskBuffer.get(maskIndex);
                } else {
                    resampledMask[y * targetWidth + x] = (byte)255; // Default to background
                }
            }
        }
        
        // Simplified processing for better performance
        System.arraycopy(resampledMask, 0, result, 0, result.length);
        
        // Light smoothing only - skip expensive erosion for performance
        applyLightSmoothing(result, targetWidth, targetHeight);
        
        // Apply temporal smoothing with previous frame (main quality improvement)
        applyTemporalSmoothing(result, targetWidth, targetHeight);
        
        return result;
    }
    
    
    private void applyLightSmoothing(byte[] mask, int width, int height) {
        // Faster 3-point horizontal smoothing only for performance
        for (int y = 0; y < height; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                int left = mask[index - 1] & 0xFF;
                int center = mask[index] & 0xFF;
                int right = mask[index + 1] & 0xFF;
                
                // Simple 3-point average
                mask[index] = (byte)((left + center + right) / 3);
            }
        }
    }
    
    private void applyTemporalSmoothing(byte[] currentMask, int width, int height) {
        if (previousMask == null || previousMask.length != currentMask.length) {
            // First frame or resolution change - just store current mask
            previousMask = new byte[currentMask.length];
            System.arraycopy(currentMask, 0, previousMask, 0, currentMask.length);
            return;
        }
        
        // Blend current mask with previous mask for temporal stability
        for (int i = 0; i < currentMask.length; i++) {
            int currentValue = currentMask[i] & 0xFF;
            int previousValue = previousMask[i] & 0xFF;
            
            // Weighted average: more weight to current frame, some to previous
            int blendedValue = (int)(currentValue * (1.0f - TEMPORAL_SMOOTHING) + 
                                   previousValue * TEMPORAL_SMOOTHING);
            
            currentMask[i] = (byte)blendedValue;
        }
        
        // Store current mask for next frame
        System.arraycopy(currentMask, 0, previousMask, 0, currentMask.length);
    }
    
    
    
    private Bitmap frameToBitmapOptimized(VideoFrame frame, SurfaceTextureHelper textureHelper) {
        try {
            Buffer buffer = frame.getBuffer();
            
            if (buffer instanceof TextureBuffer) {
                return textureBufferToBitmapOptimized((TextureBuffer) buffer, textureHelper);
            } else if (buffer instanceof I420Buffer) {
                return i420BufferToBitmapOptimized((I420Buffer) buffer);
            }
            
            Log.w(TAG, "Unsupported buffer type: " + buffer.getClass().getSimpleName());
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting frame to bitmap (optimized)", e);
            return null;
        }
    }
    
    
    private Bitmap textureBufferToBitmapOptimized(TextureBuffer textureBuffer, SurfaceTextureHelper textureHelper) {
        try {
            if (yuvConverter == null) {
                yuvConverter = new YuvConverter();
            }
            
            // Use YuvConverter's optimized path directly to bitmap
            I420Buffer i420Buffer = yuvConverter.convert(textureBuffer);
            Bitmap bitmap = i420BufferToBitmapOptimized(i420Buffer);
            i420Buffer.release();
            
            return bitmap;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting texture buffer to bitmap (optimized)", e);
            return null;
        }
    }
    
    private Bitmap i420BufferToBitmapOptimized(I420Buffer i420Buffer) {
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            
            // Pre-allocate pixel array
            int[] pixels = new int[width * height];
            
            ByteBuffer yBuffer = i420Buffer.getDataY();
            ByteBuffer uBuffer = i420Buffer.getDataU();
            ByteBuffer vBuffer = i420Buffer.getDataV();
            
            int yStride = i420Buffer.getStrideY();
            int uStride = i420Buffer.getStrideU();
            
            // Optimized YUV to RGB conversion with fewer lookups
            for (int y = 0; y < height; y++) {
                int yRowIndex = y * yStride;
                int uvRowIndex = (y / 2) * uStride;
                int pixelRowIndex = y * width;
                
                for (int x = 0; x < width; x++) {
                    int yValue = yBuffer.get(yRowIndex + x) & 0xFF;
                    int uvIndex = uvRowIndex + (x / 2);
                    int uValue = uBuffer.get(uvIndex) & 0xFF;
                    int vValue = vBuffer.get(uvIndex) & 0xFF;
                    
                    // Fast YUV to RGB conversion using integer math
                    int c = yValue - 16;
                    int d = uValue - 128;
                    int e = vValue - 128;
                    
                    int r = (298 * c + 409 * e + 128) >> 8;
                    int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                    int b = (298 * c + 516 * d + 128) >> 8;
                    
                    // Clamp values efficiently
                    r = r < 0 ? 0 : (r > 255 ? 255 : r);
                    g = g < 0 ? 0 : (g > 255 ? 255 : g);
                    b = b < 0 ? 0 : (b > 255 ? 255 : b);
                    
                    pixels[pixelRowIndex + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                }
            }
            
            // Create bitmap directly from pixel array
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting I420 to bitmap (optimized)", e);
            return null;
        }
    }
    
    private VideoFrame bitmapToVideoFrame(Bitmap bitmap, VideoFrame originalFrame, SurfaceTextureHelper textureHelper) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            // Convert bitmap to I420
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
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
                public Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight, 
                                          int scaleWidth, int scaleHeight) {
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
        Log.d(TAG, "Releasing MediaPipe Background Processor");
        
        try {
            if (imageSegmenter != null) {
                imageSegmenter.close();
                imageSegmenter = null;
            }
            
            synchronized (frameLock) {
                if (lastProcessedFrame != null) {
                    lastProcessedFrame.release();
                    lastProcessedFrame = null;
                }
            }
            
            if (yuvConverter != null) {
                yuvConverter.release();
                yuvConverter = null;
            }
            
            // Clean up temporal smoothing data
            previousMask = null;
            
            Log.d(TAG, String.format("Total frames processed: %d, Avg time: %.2fms", 
                frameCount, frameCount > 0 ? (float)totalProcessingTime / frameCount : 0));
            
        } catch (Exception e) {
            Log.e(TAG, "Error during release", e);
        }
    }
}