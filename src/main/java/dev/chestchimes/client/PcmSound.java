package dev.chestchimes.client;

import dev.chestchimes.audio.AudioRules;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.lwjgl.BufferUtils;

public final class PcmSound extends AbstractTickableSoundInstance {
    private static final ResourceLocation ID = new ResourceLocation("chestchimes", "imported");
    private final byte[] pcm;
    private final long started = System.nanoTime();
    private final long duration;
    public PcmSound(byte[] pcm, BlockPos pos, boolean preview) {
        super(ID, SoundSource.BLOCKS, RandomSource.create());
        AudioRules.pcm(pcm);
        this.pcm = pcm;
        this.duration = pcm.length * 1_000_000_000L / (AudioRules.RATE * 2);
        this.x = pos.getX() + .5; this.y = pos.getY() + .5; this.z = pos.getZ() + .5;
        this.volume = .8f;
        this.pitch = 1;
        this.relative = preview;
        this.attenuation = preview ? Attenuation.NONE : Attenuation.LINEAR;
        if (preview) { this.x = 0; this.y = 0; this.z = 0; }
    }
    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(ID.toString(), ConstantFloat.of(1), ConstantFloat.of(1),
                1, Sound.Type.FILE, true, false, 16);
        return new WeighedSoundEvents(ID, null);
    }
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean loop) {
        return CompletableFuture.completedFuture(new AudioStream() {
            private int cursor;
            @Override public AudioFormat getFormat() { return new AudioFormat(AudioRules.RATE, 16, 1, true, false); }
            @Override public ByteBuffer read(int size) {
                int count = Math.min(size, pcm.length - cursor);
                if (count == 0) return null;
                ByteBuffer result = BufferUtils.createByteBuffer(count);
                result.put(pcm, cursor, count).flip();
                cursor += count;
                return result;
            }
            @Override public void close() { cursor = pcm.length; }
        });
    }
    @Override public void tick() { if (finished()) stop(); }
    public boolean finished() { return System.nanoTime() - started >= duration + 100_000_000L; }
}
