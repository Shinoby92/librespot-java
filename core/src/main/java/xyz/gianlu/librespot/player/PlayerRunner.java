package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.StandardCodec;
import xyz.gianlu.librespot.player.codecs.VorbisCodec;

import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import java.io.IOException;

/**
 * @author Gianlu
 */
public class PlayerRunner implements Runnable {
    public static final int VOLUME_STEPS = 64;
    public static final int VOLUME_MAX = 65536;
    private static final int VOLUME_STEP = 65536 / VOLUME_STEPS;
    private static final Logger LOGGER = Logger.getLogger(PlayerRunner.class);
    private final Codec codec;

    PlayerRunner(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, @NotNull LinesHolder lines,
                 @NotNull Player.Configuration conf, @NotNull Listener listener, int duration) throws IOException, Codec.CodecException, PlayerException {
        switch (audioFile.codec()) {
            case VORBIS:
                codec = new VorbisCodec(audioFile, normalizationData, conf, listener, lines, duration);
                break;
            case STANDARD:
                codec = new StandardCodec(audioFile, normalizationData, conf, listener, lines, duration);
                break;
            default:
                throw new IllegalArgumentException("Unknown codec: " + audioFile.codec());
        }

        LOGGER.trace(String.format("Player ready for playback, fileId: %s", audioFile.getFileIdHex()));
    }

    int time() {
        return codec.time();
    }

    @Override
    public void run() {
        codec.read();
    }

    void play() {
        codec.play();
    }

    void pause() {
        codec.pause();
    }

    void seek(int positionMs) {
        codec.seek(positionMs);
    }

    void stop() {
        codec.stop();
    }

    @Nullable
    Controller controller() {
        return codec.controller();
    }

    public interface Listener {
        void endOfTrack();

        void playbackError(@NotNull Exception ex);

        void preloadNextTrack();

        int getVolume();
    }

    public static class Controller {
        private final FloatControl masterGain;
        private int volume = 0;

        public Controller(@NotNull Line line, int initialVolume) {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN))
                masterGain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            else
                masterGain = null;

            setVolume(initialVolume);
        }

        private double calcLogarithmic(int val) {
            return Math.log10((double) val / VOLUME_MAX) * 20f;
        }

        public void setVolume(int val) {
            this.volume = val;

            if (masterGain != null)
                masterGain.setValue((float) calcLogarithmic(val));
        }

        int volumeDown() {
            setVolume(volume - VOLUME_STEP);
            return volume;
        }

        int volumeUp() {
            setVolume(volume + VOLUME_STEP);
            return volume;
        }
    }

    public static class PlayerException extends Exception {

        public PlayerException() {
        }

        private PlayerException(@NotNull Throwable ex) {
            super(ex);
        }

        PlayerException(String message) {
            super(message);
        }
    }
}
