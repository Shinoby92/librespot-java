package xyz.gianlu.librespot.player.codecs;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.*;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;

/**
 * @author Gianlu
 */
public class VorbisCodec extends Codec {
    private static final int CONVERTED_BUFFER_SIZE = BUFFER_SIZE * 2;
    private static final Logger LOGGER = Logger.getLogger(VorbisCodec.class);
    private final StreamState joggStreamState = new StreamState();
    private final DspState jorbisDspState = new DspState();
    private final Block jorbisBlock = new Block(jorbisDspState);
    private final Comment jorbisComment = new Comment();
    private final Info jorbisInfo = new Info();
    private final Packet joggPacket = new Packet();
    private final Page joggPage = new Page();
    private final SyncState joggSyncState = new SyncState();
    private final AudioFormat audioFormat;
    private byte[] buffer;
    private int count;
    private int index;
    private byte[] convertedBuffer;
    private LinesHolder.LineWrapper outputLine;
    private float[][][] pcmInfo;
    private int[] pcmIndex;
    private long pcm_offset;

    public VorbisCodec(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, Player.@NotNull Configuration conf,
                       PlayerRunner.@NotNull Listener listener, @NotNull LinesHolder lines, int duration) throws IOException, CodecException, PlayerRunner.PlayerException {
        super(audioFile, normalizationData, conf, listener, lines, duration);

        this.joggSyncState.init();
        this.joggSyncState.buffer(BUFFER_SIZE);
        this.buffer = joggSyncState.data;

        readHeader();
        this.audioFormat = initializeSound(conf);

        audioIn.mark(-1);
    }

    @Override
    public int time() {
        return (int) (((float) pcm_offset / (float) jorbisInfo.rate) * 1000f);
    }

    /**
     * Reads the body. All "holes" (-1) in data will stop the playback.
     *
     * @throws xyz.gianlu.librespot.player.codecs.Codec.CodecException if a player exception occurs
     * @throws IOException                                             if an I/O exception occurs
     */
    private void readHeader() throws IOException, CodecException {
        boolean finished = false;
        int packet = 1;

        while (!finished) {
            count = audioIn.read(buffer, index, BUFFER_SIZE);
            joggSyncState.wrote(count);

            int result = joggSyncState.pageout(joggPage);
            if (result == -1) {
                throw new HoleInDataException();
            } else if (result == 0) {
                // Read more
            } else if (result == 1) {
                if (packet == 1) {
                    joggStreamState.init(joggPage.serialno());
                    joggStreamState.reset();

                    jorbisInfo.init();
                    jorbisComment.init();
                }

                if (joggStreamState.pagein(joggPage) == -1)
                    throw new CodecException();

                if (joggStreamState.packetout(joggPacket) == -1)
                    throw new HoleInDataException();

                if (jorbisInfo.synthesis_headerin(jorbisComment, joggPacket) < 0)
                    throw new NotVorbisException();

                if (packet == 3) finished = true;
                else packet++;
            }

            index = joggSyncState.buffer(BUFFER_SIZE);
            buffer = joggSyncState.data;

            if (count == 0 && !finished)
                throw new CodecException();
        }
    }

    @NotNull
    private AudioFormat initializeSound(Player.Configuration conf) throws CodecException, PlayerRunner.PlayerException {
        convertedBuffer = new byte[CONVERTED_BUFFER_SIZE];

        jorbisDspState.synthesis_init(jorbisInfo);
        jorbisBlock.init(jorbisDspState);

        int channels = jorbisInfo.channels;
        int rate = jorbisInfo.rate;

        AudioFormat format = new AudioFormat((float) rate, 16, channels, true, false);

        try {
            outputLine = lines.getLineFor(conf, format);
        } catch (LineUnavailableException | IllegalStateException | SecurityException ex) {
            throw new CodecException(ex);
        }

        pcmInfo = new float[1][][];
        pcmIndex = new int[jorbisInfo.channels];

        return format;
    }

    /**
     * Reads the body. All "holes" (-1) are skipped, and the playback continues
     *
     * @throws PlayerRunner.PlayerException if a player exception occurs
     * @throws IOException                  if an I/O exception occurs
     */
    public void readBody() throws PlayerRunner.PlayerException, IOException, LineUnavailableException {
        SourceDataLine line = outputLine.waitAndOpen(audioFormat);
        this.controller = new PlayerRunner.Controller(line, listener.getVolume());

        while (!stopped) {
            if (playing) {
                line.start();

                int result = joggSyncState.pageout(joggPage);
                if (result == -1 || result == 0) {
                    // Read more
                } else if (result == 1) {
                    if (joggStreamState.pagein(joggPage) == -1)
                        throw new PlayerRunner.PlayerException();

                    if (joggPage.granulepos() == 0)
                        break;

                    while (true) {
                        result = joggStreamState.packetout(joggPacket);
                        if (result == -1 || result == 0) {
                            break;
                        } else if (result == 1) {
                            decodeCurrentPacket(line);
                        }
                    }

                    if (joggPage.eos() != 0)
                        break;
                }

                index = joggSyncState.buffer(BUFFER_SIZE);
                buffer = joggSyncState.data;
                if (index == -1)
                    break;

                count = audioIn.read(buffer, index, BUFFER_SIZE);
                joggSyncState.wrote(count);
                if (count == 0)
                    break;
            } else {
                line.stop();

                try {
                    synchronized (pauseLock) {
                        pauseLock.wait();
                    }
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    private void decodeCurrentPacket(@NotNull SourceDataLine line) {
        if (jorbisBlock.synthesis(joggPacket) == 0)
            jorbisDspState.synthesis_blockin(jorbisBlock);

        int range;
        int samples;
        while ((samples = jorbisDspState.synthesis_pcmout(pcmInfo, pcmIndex)) > 0) {
            if (samples < CONVERTED_BUFFER_SIZE) range = samples;
            else range = CONVERTED_BUFFER_SIZE;

            for (int i = 0; i < jorbisInfo.channels; i++) {
                int sampleIndex = i * 2;
                for (int j = 0; j < range; j++) {
                    int value = (int) (pcmInfo[0][i][pcmIndex[i] + j] * 32767);
                    if (value > 32767) value = 32767;
                    else if (value < -32768) value = -32768;
                    else if (value < 0) value = value | 32768;

                    value *= normalizationFactor;

                    convertedBuffer[sampleIndex] = (byte) (value);
                    convertedBuffer[sampleIndex + 1] = (byte) (value >>> 8);

                    sampleIndex += 2 * jorbisInfo.channels;
                }
            }

            line.write(convertedBuffer, 0, 2 * jorbisInfo.channels * range);
            jorbisDspState.synthesis_read(range);

            long granulepos = joggPacket.granulepos;
            if (granulepos != -1 && joggPacket.e_o_s == 0) {
                granulepos -= samples;
                pcm_offset = granulepos;
                checkPreload();
            }
        }
    }

    public void read() {
        try {
            readBody();
            if (!stopped) listener.endOfTrack();
        } catch (PlayerRunner.PlayerException | IOException | LineUnavailableException ex) {
            if (!stopped) listener.playbackError(ex);
        } finally {
            cleanup();
        }
    }

    @Override
    public void seek(int positionMs) {
        if (positionMs >= 0) {
            try {
                audioIn.reset();
                if (positionMs > 0) {
                    int skip = Math.round(audioIn.available() / (float) duration * positionMs);
                    if (skip > audioIn.available()) skip = audioIn.available();

                    long skipped = audioIn.skip(skip);
                    if (skip != skipped)
                        throw new IOException(String.format("Failed seeking, skip: %d, skipped: %d", skip, skipped));
                }
            } catch (IOException ex) {
                LOGGER.fatal("Failed seeking!", ex);
            }
        }
    }

    @Override
    public void cleanup() {
        joggStreamState.clear();
        jorbisBlock.clear();
        jorbisDspState.clear();
        jorbisInfo.clear();
        joggSyncState.clear();
        outputLine.close();
    }

    private static class NotVorbisException extends CodecException {
    }

    private static class HoleInDataException extends CodecException {
    }
}
