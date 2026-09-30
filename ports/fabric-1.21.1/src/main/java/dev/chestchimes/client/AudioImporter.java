package dev.chestchimes.client;

import dev.chestchimes.audio.AudioRules;
import dev.chestchimes.audio.WavReader;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Locale;
import javazoom.jl.decoder.*;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public final class AudioImporter {
    public record Imported(String name, int sourceBytes, byte[] pcm) {}
    private AudioImporter() {}

    public static Imported choose(String title, String filterLabel) throws Exception {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.wav")).put(stack.UTF8("*.ogg")).put(stack.UTF8("*.mp3")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(title, "", filters, filterLabel, false);
        }
        if (selected == null) return null;
        Path path = Path.of(selected);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("chestchimes.error.file");
        AudioRules.sourceSize(Files.size(path));
        byte[] file;
        try (InputStream input = Files.newInputStream(path)) { file = input.readNBytes(AudioRules.MAX_FILE + 1); }
        AudioRules.sourceSize(file.length);
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        byte[] pcm;
        if (lower.endsWith(".wav")) pcm = WavReader.decode(file);
        else if (lower.endsWith(".ogg") && magic(file, "OggS")) pcm = ogg(file);
        else if (lower.endsWith(".mp3") && (magic(file, "ID3")
                || file.length >= 2 && (file[0] & 255) == 255 && (file[1] & 224) == 224)) pcm = mp3(file);
        else throw new IllegalArgumentException("chestchimes.error.format");
        AudioRules.pcm(pcm);
        return new Imported(name.substring(0, Math.min(80, name.length())), file.length, pcm);
    }

    private static boolean magic(byte[] file, String magic) {
        if (file.length < magic.length()) return false;
        for (int i = 0; i < magic.length(); i++) if (file[i] != magic.charAt(i)) return false;
        return true;
    }

    private static byte[] ogg(byte[] file) {
        ByteBuffer input = MemoryUtil.memAlloc(file.length);
        long decoder = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            input.put(file).flip();
            IntBuffer error = stack.mallocInt(1);
            decoder = STBVorbis.stb_vorbis_open_memory(input, error, null);
            if (decoder == 0) throw new IllegalArgumentException("chestchimes.error.ogg");
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(decoder, info);
            int channels = info.channels(), rate = info.sample_rate();
            if (channels < 1 || channels > 2 || rate < 8000 || rate > 96000)
                throw new IllegalArgumentException("chestchimes.error.ogg");
            ShortBuffer decoded = MemoryUtil.memAllocShort(rate * channels * 5);
            try {
                int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, decoded);
                short[] samples = new short[frames * channels];
                decoded.get(samples);
                return WavReader.normalize(samples, channels, rate, frames);
            } finally { MemoryUtil.memFree(decoded); }
        } finally {
            if (decoder != 0) STBVorbis.stb_vorbis_close(decoder);
            MemoryUtil.memFree(input);
        }
    }

    private static byte[] mp3(byte[] file) throws Exception {
        Bitstream input = new Bitstream(new ByteArrayInputStream(file));
        Decoder decoder = new Decoder();
        short[] samples = null;
        int used = 0, rate = 0, channels = 0;
        try {
            Header header;
            while ((header = input.readFrame()) != null) {
                try {
                    SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, input);
                    if (samples == null) {
                        rate = frame.getSampleFrequency();
                        channels = frame.getChannelCount();
                        if (rate < 8000 || rate > 96000 || channels < 1 || channels > 2)
                            throw new IllegalArgumentException("chestchimes.error.audio");
                        samples = new short[rate * channels * 5];
                    } else if (rate != frame.getSampleFrequency() || channels != frame.getChannelCount()) {
                        throw new IllegalArgumentException("chestchimes.error.audio");
                    }
                    int count = Math.min(samples.length - used, frame.getBufferLength());
                    System.arraycopy(frame.getBuffer(), 0, samples, used, count);
                    used += count;
                    if (used == samples.length) break;
                } finally { input.closeFrame(); }
            }
        } finally { input.close(); }
        if (samples == null || used == 0) throw new IllegalArgumentException("chestchimes.error.audio");
        return WavReader.normalize(Arrays.copyOf(samples, used), channels, rate, used / channels);
    }
}
