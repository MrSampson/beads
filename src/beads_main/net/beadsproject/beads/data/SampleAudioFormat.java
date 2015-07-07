/*
 * This file is part of Beads. See http://www.beadsproject.net for all information.
 */
package net.beadsproject.beads.data;

/**
 * Encapsulates data about audio format for Samples.
 * 
 * We have elected to use our own AudioFormat instead of
 * javax.sound.sampled.AudioFormat as javasound is not supported everywhere.
 * 
 * @author ben
 */
public class SampleAudioFormat {

    private final int channels;
    private final int bitDepth;
    private final float sampleRate;
    private final boolean bigEndian;
    private final boolean signed;

    /**
     * @return the number of channels
     */
    public int getChannels() {
        return this.channels;
    }

    /**
     * @return the bit depth
     */
    public int getBitDepth() {
        return this.bitDepth;
    }

    /**
     * @return the sample rate
     */
    public float getSampleRate() {
        return this.sampleRate;
    }

    /**
     * @return if the file is Big Endian
     */
    public boolean isBigEndian() {
        return this.bigEndian;
    }

    /**
     * @return if the data is signed
     */
    public boolean isSigned() {
        return this.signed;
    }

    /**
     * Constructor.
     * 
     * @param sampleRate
     * @param bitDepth
     * @param channels
     * @param signed
     * @param bigEndian
     */
    public SampleAudioFormat(float sampleRate, int bitDepth, int channels,
            boolean signed, boolean bigEndian) {
        this.sampleRate = sampleRate;
        this.bitDepth = bitDepth;
        this.signed = signed;
        this.bigEndian = bigEndian;
        this.channels = channels;
    }

    /**
     * Constructor with defaults of <code>TRUE</code> for {@link bigEndian} and
     * {@link signed}
     * 
     * @param sampleRate
     * @param bitDepth
     * @param channels
     */
    public SampleAudioFormat(float sampleRate, int bitDepth, int channels) {
        this(sampleRate, bitDepth, channels, true, true);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("Channels: " + this.channels);
        b.append("Bit depth: " + this.bitDepth);
        b.append("Sample Rate: " + this.sampleRate);
        b.append("Big Endian: " + this.bigEndian);
        b.append("Signed:" + this.signed);
        return b.toString();
    }

    @Override
    public SampleAudioFormat clone() {
        return new SampleAudioFormat(this.sampleRate, this.bitDepth,
                this.channels, this.bigEndian, this.signed);
    }

}
