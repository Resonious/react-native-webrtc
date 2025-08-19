package com.oney.WebRTCModule.videoEffects;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.ByteBufferImageBuilder;
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
import java.util.List;
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
            // Try CPU delegate with VIDEO mode for better performance than IMAGE mode
            BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .setDelegate(Delegate.CPU) // CPU to avoid GPU timestamp issues
                .build();
            
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
            
            // Log detailed timing breakdown every few frames
            if (frameCount % 10 == 0) {
                Log.d(TAG, String.format("Frame %d timing breakdown:", frameCount));
                Log.d(TAG, String.format("  Bitmap conversion: %dms", bitmapTime));
                Log.d(TAG, String.format("  MPImage creation: %dms", mpImageTime));
                Log.d(TAG, String.format("  Segmentation: %dms", segmentationTime));
                Log.d(TAG, String.format("  Apply background: %dms", applyTime));
                Log.d(TAG, String.format("  VideoFrame conversion: %dms", videoFrameTime));
                Log.d(TAG, String.format("  TOTAL: %dms", totalTime));
                Log.d(TAG, String.format("  Average: %.1fms over %d frames", 
                    (float)totalProcessingTime / frameCount, frameCount));
            }
            
            if (frameCount % 30 == 0) {
                Log.d(TAG, String.format("Avg processing time: %.2fms", 
                    (float)totalProcessingTime / frameCount));
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
            Log.d(TAG, "Mask buffer extracted successfully, capacity: " + maskBuffer.capacity());
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract mask buffer", e);
            return input;
        }
        
        // Sample some mask values to understand what we're getting
        int sampleCount = Math.min(100, maskBuffer.capacity());
        int personPixels = 0;
        int backgroundPixels = 0;
        for (int i = 0; i < sampleCount; i++) {
            byte category = maskBuffer.get(i);
            if (category == 0) personPixels++;
            else if ((category & 0xFF) == 255) backgroundPixels++; // 255 unsigned = -1 signed
        }
        maskBuffer.rewind(); // Reset position after sampling
        
        Log.d(TAG, String.format("Mask sample (%d pixels): person (0)=%d, background (255)=%d", 
            sampleCount, personPixels, backgroundPixels));
        
        int maskWidth = segmentationResult.categoryMask().get().getWidth();
        int maskHeight = segmentationResult.categoryMask().get().getHeight();
        
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        
        float scaleX = (float)maskWidth / inputWidth;
        float scaleY = (float)maskHeight / inputHeight;
        
        // Use bulk pixel operations for much better performance
        int[] inputPixels = new int[inputWidth * inputHeight];
        input.getPixels(inputPixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        
        int[] outputPixels = new int[inputWidth * inputHeight];
        
        // Process mask efficiently - single pass through pixels
        for (int y = 0; y < inputHeight; y++) {
            for (int x = 0; x < inputWidth; x++) {
                int pixelIndex = y * inputWidth + x;
                int maskX = Math.min((int)(x * scaleX), maskWidth - 1);
                int maskY = Math.min((int)(y * scaleY), maskHeight - 1);
                int maskIndex = maskY * maskWidth + maskX;
                
                if (maskIndex < maskBuffer.capacity()) {
                    byte category = maskBuffer.get(maskIndex);
                    // For selfie_segmenter.tflite: 0 is PERSON, 255 (-1 signed) is BACKGROUND
                    if (category == 0) {
                        outputPixels[pixelIndex] = inputPixels[pixelIndex]; // Keep person
                    } else {
                        outputPixels[pixelIndex] = backgroundColor; // Replace background
                    }
                } else {
                    outputPixels[pixelIndex] = backgroundColor; // Default to background
                }
            }
        }
        
        // Create output bitmap directly from pixel array
        Bitmap output = Bitmap.createBitmap(outputPixels, inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        
        return output;
    }
    
    private MPImage createMPImageFromBitmap(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            // Extract pixels in RGBA format
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            
            // Try RGB format (3 bytes per pixel) instead of RGBA
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3);
            for (int pixel : pixels) {
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // Green
                buffer.put((byte) (pixel & 0xFF));         // Blue
                // Skip alpha channel
            }
            buffer.rewind();
            
            Log.d(TAG, String.format("Created manual RGB buffer: %d bytes (%dx%d * 3)", 
                buffer.capacity(), width, height));
            
            // Create MPImage with RGB format
            return new ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build();
            
        } catch (Exception e) {
            Log.e(TAG, "Manual ByteBuffer creation failed, falling back to BitmapImageBuilder", e);
            return new BitmapImageBuilder(bitmap).build();
        }
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
    
    private Bitmap textureBufferToBitmap(TextureBuffer textureBuffer, SurfaceTextureHelper textureHelper) {
        try {
            if (yuvConverter == null) {
                yuvConverter = new YuvConverter();
            }
            
            I420Buffer i420Buffer = yuvConverter.convert(textureBuffer);
            Bitmap bitmap = i420BufferToBitmap(i420Buffer);
            i420Buffer.release();
            
            return bitmap;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting texture buffer to bitmap", e);
            return null;
        }
    }
    
    private Bitmap i420BufferToBitmap(I420Buffer i420Buffer) {
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            
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
                    
                    int yValue = yBuffer.get(yIndex) & 0xFF;
                    int uValue = uBuffer.get(uvIndex) & 0xFF;
                    int vValue = vBuffer.get(uvIndex) & 0xFF;
                    
                    // YUV to RGB conversion
                    int r = (int) (yValue + 1.402 * (vValue - 128));
                    int g = (int) (yValue - 0.344136 * (uValue - 128) - 0.714136 * (vValue - 128));
                    int b = (int) (yValue + 1.772 * (uValue - 128));
                    
                    r = Math.max(0, Math.min(255, r));
                    g = Math.max(0, Math.min(255, g));
                    b = Math.max(0, Math.min(255, b));
                    
                    pixels[y * width + x] = Color.rgb(r, g, b);
                }
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting I420 to bitmap", e);
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
            
            Log.d(TAG, String.format("Total frames processed: %d, Avg time: %.2fms", 
                frameCount, frameCount > 0 ? (float)totalProcessingTime / frameCount : 0));
            
        } catch (Exception e) {
            Log.e(TAG, "Error during release", e);
        }
    }
}