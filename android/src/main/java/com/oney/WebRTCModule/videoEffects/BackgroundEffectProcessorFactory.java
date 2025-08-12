package com.oney.WebRTCModule.videoEffects;

/**
 * Factory class for creating BackgroundEffectProcessor instances.
 * This factory is registered with ProcessorProvider to enable the background effect.
 */
public class BackgroundEffectProcessorFactory implements VideoFrameProcessorFactoryInterface {
    
    @Override
    public VideoFrameProcessor build() {
        return new BackgroundEffectProcessor();
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
                return new BackgroundEffectProcessor(android.graphics.Color.WHITE);
            }
        });
        
        ProcessorProvider.addProcessor("backgroundGreen", new VideoFrameProcessorFactoryInterface() {
            @Override
            public VideoFrameProcessor build() {
                return new BackgroundEffectProcessor(android.graphics.Color.GREEN);
            }
        });
        
        ProcessorProvider.addProcessor("backgroundCustom", new VideoFrameProcessorFactoryInterface() {
            @Override
            public VideoFrameProcessor build() {
                // This could read color from configuration
                return new BackgroundEffectProcessor(android.graphics.Color.parseColor("#F0F0F0"));
            }
        });
    }
}