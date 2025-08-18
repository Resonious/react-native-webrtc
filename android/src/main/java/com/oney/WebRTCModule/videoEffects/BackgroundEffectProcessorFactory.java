package com.oney.WebRTCModule.videoEffects;

import android.content.Context;

/**
 * Factory class for creating BackgroundEffectProcessor instances.
 * This factory is registered with ProcessorProvider to enable the background effect.
 */
public class BackgroundEffectProcessorFactory implements VideoFrameProcessorFactoryInterface {
    
    private static Context applicationContext;
    
    /**
     * Set the application context. Must be called before creating processors.
     */
    public static void setApplicationContext(Context context) {
        applicationContext = context.getApplicationContext();
    }
    
    @Override
    public VideoFrameProcessor build() {
        if (applicationContext != null) {
            // Use MediaPipe processor for better performance
            return new MediaPipeBackgroundProcessor(applicationContext);
        } else {
            // Fallback to ML Kit processor if context not available
            return new BackgroundEffectProcessor();
        }
    }
    
    /**
     * Register this factory with the ProcessorProvider.
     * This should be called during module initialization.
     */
    public static void register() {
        ProcessorProvider.addProcessor("backgroundWhite", new BackgroundEffectProcessorFactory());
        
        // Also register variations for different colors
        ProcessorProvider.addProcessor("backgroundBlur", new VideoFrameProcessorFactoryInterface() {
            @Override
            public VideoFrameProcessor build() {
                // For blur effect, you would implement a different processor
                // For now, returning white background as placeholder
                if (applicationContext != null) {
                    return new MediaPipeBackgroundProcessor(applicationContext, android.graphics.Color.WHITE);
                } else {
                    return new BackgroundEffectProcessor(android.graphics.Color.WHITE);
                }
            }
        });
        
        ProcessorProvider.addProcessor("backgroundGreen", new VideoFrameProcessorFactoryInterface() {
            @Override
            public VideoFrameProcessor build() {
                if (applicationContext != null) {
                    return new MediaPipeBackgroundProcessor(applicationContext, android.graphics.Color.GREEN);
                } else {
                    return new BackgroundEffectProcessor(android.graphics.Color.GREEN);
                }
            }
        });
        
        ProcessorProvider.addProcessor("backgroundCustom", new VideoFrameProcessorFactoryInterface() {
            @Override
            public VideoFrameProcessor build() {
                // This could read color from configuration
                int customColor = android.graphics.Color.parseColor("#F0F0F0");
                if (applicationContext != null) {
                    return new MediaPipeBackgroundProcessor(applicationContext, customColor);
                } else {
                    return new BackgroundEffectProcessor(customColor);
                }
            }
        });
    }
}