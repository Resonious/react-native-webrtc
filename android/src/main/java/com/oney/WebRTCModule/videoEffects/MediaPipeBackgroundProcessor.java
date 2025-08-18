package com.oney.WebRTCModule.videoEffects;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
            BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .setDelegate(Delegate.GPU) // Use GPU for better performance
                .build();
            
            ImageSegmenter.ImageSegmenterOptions options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
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
            long startTime = System.currentTimeMillis();
            
            // Convert frame to Bitmap
            Bitmap inputBitmap = frameToBitmap(frame, textureHelper);
            if (inputBitmap == null) {
                return frame;
            }
            
            // Create MediaPipe image
            MPImage mpImage = new BitmapImageBuilder(inputBitmap).build();
            
            // Perform segmentation
            long timestampMs = frame.getTimestampNs() / 1_000_000L;
            ImageSegmenterResult result = imageSegmenter.segmentForVideo(mpImage, timestampMs);
            
            // Apply background replacement
            Bitmap outputBitmap = applyBackground(inputBitmap, result);
            
            // Convert back to VideoFrame
            VideoFrame processedFrame = bitmapToVideoFrame(outputBitmap, frame, textureHelper);
            
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
            
            // Performance tracking
            long processingTime = System.currentTimeMillis() - startTime;
            frameCount++;
            totalProcessingTime += processingTime;
            
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
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract mask buffer", e);
            return input;
        }
        
        int maskWidth = segmentationResult.categoryMask().get().getWidth();
        int maskHeight = segmentationResult.categoryMask().get().getHeight();
        
        int inputWidth = input.getWidth();
        int inputHeight = input.getHeight();
        
        Bitmap output = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        
        // Draw background color
        canvas.drawColor(backgroundColor);
        
        // Create person mask bitmap
        Bitmap personMask = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        
        float scaleX = (float)maskWidth / inputWidth;
        float scaleY = (float)maskHeight / inputHeight;
        
        // Process mask and create person cutout
        for (int y = 0; y < inputHeight; y++) {
            for (int x = 0; x < inputWidth; x++) {
                int maskX = Math.min((int)(x * scaleX), maskWidth - 1);
                int maskY = Math.min((int)(y * scaleY), maskHeight - 1);
                int maskIndex = maskY * maskWidth + maskX;
                
                if (maskIndex < maskBuffer.capacity()) {
                    byte category = maskBuffer.get(maskIndex);
                    // Category 1 is person, 0 is background
                    if (category == 1) {
                        personMask.setPixel(x, y, input.getPixel(x, y));
                    } else {
                        personMask.setPixel(x, y, Color.TRANSPARENT);
                    }
                }
            }
        }
        
        // Draw person on top of background
        canvas.drawBitmap(personMask, 0, 0, paint);
        personMask.recycle();
        
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