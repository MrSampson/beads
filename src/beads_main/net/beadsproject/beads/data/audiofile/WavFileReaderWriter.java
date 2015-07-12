package net.beadsproject.beads.data.audiofile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;

import net.beadsproject.beads.data.SampleAudioFormat;

/**
 * This class supports 'one-shot' reading and writing of wav files. It has no
 * dependencies on 3rd party libraries so it should work on all platforms.
 * 
 * Based on WavFile IO class by: A.Greensted (http://www.labbookpages.co.uk)
 * 
 * This version has modified by: - Modified for use in the Beads library -
 * Augmented to read and write 32- and 64-bit Floating Point PCM - Bugfixes
 * 
 * File format is based on the information from -
 * http://www.sonicspot.com/guide/wavefiles.html -
 * http://www.blitter.com/~russtopia/MIDI/~jglatt/tech/wave.htm Version 1.0
 * 
 * @author aengus
 */
public class WavFileReaderWriter implements AudioFileReader, AudioFileWriter {

    private enum IOState {
        READING, WRITING, CLOSED
    }

    private final static int BUFFER_SIZE = 4096;
    private final static int FMT_CHUNK_ID = 0x20746D66;
    private final static int DATA_CHUNK_ID = 0x61746164;
    private final static int RIFF_CHUNK_ID = 0x46464952;
    private final static int RIFF_TYPE_ID = 0x45564157;

    // Compression Codes
    private final static int WAVE_FORMAT_PCM = 0x0001;
    private final static int WAVE_FORMAT_IEEE_FLOAT = 0x0003;

    private int bytesPerSample; // Number of bytes required to store a single
                                // sample
    private long numFrames; // Number of frames within the data section
    private OutputStream oStream; // Output stream used for writing data
    private InputStream iStream; // Input stream used for reading data
    private double floatScale; // Scaling factor used for int <-> float
                               // conversion
    private double floatOffset; // Offset factor used for int <-> float
                                // conversion
    private boolean wordAlignAdjust; // Specify if an extra byte at the end of
                                     // the data chunk is required for word
                                     // alignment

    // Wav Header
    private int numChannels; // 2 bytes unsigned, 0x0001 (1) to 0xFFFF (65,535)
    private long sampleRate; // 4 bytes unsigned, 0x00000001 (1) to 0xFFFFFFFF
                             // (4,294,967,295); Although a java int is 4 bytes,
                             // it is signed, so need to use a long
    private int blockAlign; // 2 bytes unsigned, 0x0001 (1) to 0xFFFF (65,535)
    private int validBits; // 2 bytes unsigned, 0x0002 (2) to 0xFFFF (65,535)
    private int compressionCode; // Must be either: WAVE_FORMAT_PCM or
                                 // WAVE_FORMAT_IEEE_FLOAT

    // Buffering
    private byte[] buffer; // Local buffer used for IO
    private int bufferPointer; // Points to the current position in local buffer
    private int bytesRead; // Bytes read after last read into local buffer
    private long frameCounter; // Current number of frames read or written

    private IOState ioState = IOState.CLOSED;

    public WavFileReaderWriter() {
        this.buffer = new byte[BUFFER_SIZE];
    }

    /**
     * @param data
     * @param out
     * @param type
     * @param saf
     * @throws IOException
     * @throws OperationUnsupportedException
     * @throws FileFormatException
     */
    public void writeAudioFile(float[][] data, OutputStream out,
            AudioFileType type, SampleAudioFormat saf) throws IOException,
            OperationUnsupportedException, FileFormatException {

        if (!getSupportedFileTypesForWriting().contains(type)) {
            throw new OperationUnsupportedException(
                    "Unsupported file type for writing: " + type);
        }

        this.sampleRate = (long) saf.getSampleRate();
        this.numChannels = data.length;
        this.validBits = saf.getBitDepth();
        this.numFrames = data[0].length;
        this.ioState = IOState.WRITING;

        this.oStream = out;

        try {
            writeHeader();
            writeData(data);
            close();
        } catch (IOException e) {
            throw new IOException("Could not write audio file: "
                    + e.getMessage());
        } catch (FileFormatException e) {
            throw new FileFormatException("Could not write audio file: "
                    + e.getMessage());
        }
    }

    @Override
    public void writeAudioFile(float[][] data, String filename,
            AudioFileType type, SampleAudioFormat saf) throws IOException,
            OperationUnsupportedException, FileFormatException {

        FileOutputStream stream = new FileOutputStream(filename);
        writeAudioFile(data, stream, type, saf);
    }

    @Override
    public HashSet<AudioFileType> getSupportedFileTypesForWriting() {
        HashSet<AudioFileType> types = new HashSet<AudioFileType>();
        types.add(AudioFileType.WAV);
        return types;
    }

    @Override
    public float[][] readAudioFile(String filename)
            throws OperationUnsupportedException, FileNotFoundException,
            IOException, FileFormatException {

        if (!(filename.endsWith(".wav") || filename.endsWith(".WAV"))) {
            throw new OperationUnsupportedException(
                    "Only wav files (ending in .wav or .WAV) are supported");
        }

        return readAudioFile(new File(filename));
    }

    /**
     * @param f
     * @return
     * @throws FileFormatException
     * @throws OperationUnsupportedException
     * @throws IOException
     */
    public float[][] readAudioFile(File f) throws IOException,
            OperationUnsupportedException, FileFormatException {
        
        FileInputStream stream = new FileInputStream(f);
            
        float[][] data = readAudioFile(stream);
        
        stream.close();
        
        return data;
    }

    /**
     * @param stream
     * @return
     * @throws IOException
     * @throws OperationUnsupportedException
     * @throws FileFormatException
     */
    public float[][] readAudioFile(InputStream stream) throws IOException,
            OperationUnsupportedException, FileFormatException {

       
        this.iStream = stream;

        float[][] data = null;
        try {
            readHeader();
            data = readData();           
        } catch (IOException e) {
            throw new IOException("Could not read audio file: "
                    + e.getMessage());
        } catch (FileFormatException e) {
            throw new FileFormatException("Could not read audio file: "
                    + e.getMessage());
        } catch (OperationUnsupportedException e) {
            throw new OperationUnsupportedException(
                    "Could not read audio file: " + e.getMessage());
        }
        return data;
    }

    @Override
    public HashSet<AudioFileType> getSupportedFileTypesForReading() {
        HashSet<AudioFileType> types = new HashSet<AudioFileType>();
        types.add(AudioFileType.WAV);
        return types;
    }

    @Override
    public SampleAudioFormat getSampleAudioFormat() {
        return new SampleAudioFormat(this.sampleRate, this.validBits,
                this.numChannels);
    }

    /**
     * Write the wav file header.
     * 
     * @throws IOException
     * @throws WavFileException
     */
    private void writeHeader() throws IOException, FileFormatException {
        this.bytesPerSample = (this.validBits + 7) / 8;
        this.blockAlign = this.bytesPerSample * this.numChannels;

        // Sanity checks
        if (this.numChannels < 1 || this.numChannels > 65535)
            throw new FileFormatException(
                    "Illegal number of channels, valid range 1 to 65536");
        if (this.numFrames < 0)
            throw new FileFormatException("Number of frames must be positive");
        if (this.validBits < 2 || this.validBits > 65535)
            throw new FileFormatException(
                    "Illegal number of valid bits, valid range 2 to 65536");
        if (this.sampleRate < 0)
            throw new FileFormatException("Sample rate must be positive");

        // Set the compression code: if 32 or 64 bits, automatically use
        // floating point format
        if (this.validBits == 32 || this.validBits == 64) {
            this.compressionCode = WAVE_FORMAT_IEEE_FLOAT;
        } else {
            this.compressionCode = WAVE_FORMAT_PCM;
        }

        // Calculate the chunk sizes
        long dataChunkSize = this.blockAlign * this.numFrames;
        int formatDataSize;
        if (this.compressionCode == WAVE_FORMAT_PCM) {
            formatDataSize = 16;
        } else {
            formatDataSize = 18;
        }
        long mainChunkSize = 4 + // Riff Type
                8 + // Format ID and size
                formatDataSize + // Format data
                8 + // Data ID and size
                dataChunkSize;

        // Chunks must be word aligned, so if odd number of audio data bytes
        // adjust the main chunk size
        if (dataChunkSize % 2 == 1) {
            mainChunkSize += 1;
            this.wordAlignAdjust = true;
        } else {
            this.wordAlignAdjust = false;
        }

        // Set the main chunk size
        putLE(RIFF_CHUNK_ID, this.buffer, 0, 4);
        putLE(mainChunkSize, this.buffer, 4, 4);
        putLE(RIFF_TYPE_ID, this.buffer, 8, 4);

        // Write out the header
        this.oStream.write(this.buffer, 0, 12);

        // Put format data in buffer
        long averageBytesPerSecond = this.sampleRate * this.blockAlign;

        putLE(FMT_CHUNK_ID, this.buffer, 0, 4); // Chunk ID
        putLE(formatDataSize, this.buffer, 4, 4); // Chunk Data Size (16 or 18)
        putLE(this.compressionCode, this.buffer, 8, 2); // Compression Code (1
                                                        // or 3)
        putLE(this.numChannels, this.buffer, 10, 2); // Number of channels
        putLE(this.sampleRate, this.buffer, 12, 4); // Sample Rate
        putLE(averageBytesPerSecond, this.buffer, 16, 4); // Average Bytes Per
                                                          // Second
        putLE(this.blockAlign, this.buffer, 20, 2); // Block Align
        putLE(this.validBits, this.buffer, 22, 2); // Valid Bits
        if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT) {
            putLE(0, this.buffer, 24, 2); // Size of the Fact Chunk (0)
        }

        // Write Format Chunk
        this.oStream.write(this.buffer, 0, 8 + formatDataSize);

        // Start Data Chunk
        putLE(DATA_CHUNK_ID, this.buffer, 0, 4); // Chunk ID
        putLE(dataChunkSize, this.buffer, 4, 4); // Chunk Data Size

        // Write Data Chunk Header
        this.oStream.write(this.buffer, 0, 8);

        // Calculate the scaling factor for converting to a normalized double
        if (this.validBits > 8) {
            // If more than 8 validBits, data is signed
            // Conversion required multiplying by magnitude of max positive
            // value
            this.floatOffset = 0;
            this.floatScale = Long.MAX_VALUE >> (64 - this.validBits);
        } else {
            // Else if 8 or less validBits, data is unsigned
            // Conversion required dividing by max positive value
            this.floatOffset = 1;
            this.floatScale = 0.5 * ((1 << this.validBits) - 1);
        }

        // Finally, set the IO State
        this.bufferPointer = 0;
        this.bytesRead = 0;
        this.frameCounter = 0;

        this.ioState = IOState.WRITING;
    }

    /**
     * Write the audio data to the wav file
     * 
     * @param data
     *            the audio data
     * @throws IOException
     */
    private void writeData(float[][] data) throws IOException {
        int frameCounter = 0;
        int blockSize = 10000;
        while (frameCounter < this.numFrames) {
            // Determine how many frames to write, up to a maximum of the buffer
            // size
            long remaining = getFramesRemaining();
            int toWrite = (remaining > blockSize) ? blockSize : (int) remaining;

            // Write the buffer
            writeFrames(data, frameCounter, toWrite);
            frameCounter += toWrite;
        }
    }

    /**
     * Read the header of the wav file.
     * 
     * @throws IOException
     */
    private void readHeader() throws IOException, FileFormatException,
            OperationUnsupportedException {

        // Read the first 12 bytes of the file
        int bytesRead = this.iStream.read(this.buffer, 0, 12);
        if (bytesRead != 12)
            throw new FileFormatException(
                    "Not enough wav file bytes for header");

        // Extract parts from the header
        long riffChunkID = getLE(this.buffer, 0, 4);
        long chunkSize = getLE(this.buffer, 4, 4);
        long riffTypeID = getLE(this.buffer, 8, 4);

        // Check the header bytes contains the correct signature
        if (riffChunkID != RIFF_CHUNK_ID)
            throw new FileFormatException(
                    "Invalid Wav Header data, incorrect riff chunk ID");
        if (riffTypeID != RIFF_TYPE_ID)
            throw new FileFormatException(
                    "Invalid Wav Header data, incorrect riff type ID");

        // Check that the file size matches the number of bytes listed in header
        // FIXME figure out how to do this with streams
        // if (iStream.length() != chunkSize + 8) {
        // throw new FileFormatException("Header chunk size (" + chunkSize
        // + ") does not match file size (" + file.length() + ")");
        // }

        boolean foundFormat = false;
        boolean foundData = false;

        // Search for the Format and Data Chunks
        while (true) {
            // Read the first 8 bytes of the chunk (ID and chunk size)
            bytesRead = this.iStream.read(this.buffer, 0, 8);
            if (bytesRead == -1)
                throw new FileFormatException(
                        "Reached end of file without finding format chunk");
            if (bytesRead != 8)
                throw new FileFormatException("Could not read chunk header");

            // Extract the chunk ID and Size
            long chunkID = getLE(this.buffer, 0, 4);
            chunkSize = getLE(this.buffer, 4, 4);

            // Word align the chunk size
            // chunkSize specifies the number of bytes holding data. However,
            // the data should be word aligned (2 bytes) so we need to calculate
            // the actual number of bytes in the chunk
            long numChunkBytes = (chunkSize % 2 == 1) ? chunkSize + 1
                    : chunkSize;

            if (chunkID == FMT_CHUNK_ID) {
                // Flag that the format chunk has been found
                foundFormat = true;

                // Read in the header info
                bytesRead = this.iStream.read(this.buffer, 0, 16);

                // Check this is uncompressed data
                int compressionCode = (int) getLE(this.buffer, 0, 2);
                if (compressionCode != WAVE_FORMAT_PCM
                        && compressionCode != WAVE_FORMAT_IEEE_FLOAT) {
                    throw new OperationUnsupportedException("Compression Code "
                            + compressionCode + " not supported");
                }
                this.compressionCode = compressionCode;

                // Extract the format information
                this.numChannels = (int) getLE(this.buffer, 2, 2);
                this.sampleRate = getLE(this.buffer, 4, 4);
                this.blockAlign = (int) getLE(this.buffer, 12, 2);
                this.validBits = (int) getLE(this.buffer, 14, 2);

                if (this.numChannels == 0)
                    throw new FileFormatException(
                            "Number of channels specified in header is equal to zero");
                if (this.blockAlign == 0)
                    throw new FileFormatException(
                            "Block Align specified in header is equal to zero");
                if (this.validBits < 2)
                    throw new FileFormatException(
                            "Valid Bits specified in header is less than 2");
                if (this.validBits > 64)
                    throw new FileFormatException(
                            "Valid Bits specified in header is greater than 64, this is greater than a long can hold");
                if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                        && this.validBits != 32 && this.validBits != 64)
                    throw new IOException(
                            "Only 32-bit and 64-bit Floating Point PCM files are supported");

                // Calculate the number of bytes required to hold 1 sample
                this.bytesPerSample = (this.validBits + 7) / 8;
                if (this.bytesPerSample * this.numChannels != this.blockAlign)
                    throw new FileFormatException(
                            "Block Align does not agree with bytes required for validBits and number of channels");

                // Account for number of format bytes and then skip over
                // any extra format bytes
                numChunkBytes -= 16;
                if (numChunkBytes > 0)
                    this.iStream.skip(numChunkBytes);
            } else if (chunkID == DATA_CHUNK_ID) {
                // Check if we've found the format chunk,
                // If not, throw an exception as we need the format information
                // before we can read the data chunk
                if (foundFormat == false)
                    throw new FileFormatException(
                            "Data chunk found before Format chunk");

                // Check that the chunkSize (wav data length) is a multiple of
                // the
                // block align (bytes per frame)
                if (chunkSize % this.blockAlign != 0)
                    throw new FileFormatException(
                            "Data Chunk size is not multiple of Block Align");

                // Calculate the number of frames
                this.numFrames = chunkSize / this.blockAlign;

                // Flag that we've found the wave data chunk
                foundData = true;

                break;
            } else {
                // If an unknown chunk ID is found, just skip over the chunk
                // data
                this.iStream.skip(numChunkBytes);
            }
        }

        // Throw an exception if no data chunk has been found
        if (foundData == false)
            throw new FileFormatException("Did not find a data chunk");

        // Calculate the scaling factor for converting to a normalised double
        // These factors will only be used for linear PCM (not floating point)
        if (this.validBits > 8) {
            // If more than 8 validBits, data is signed
            // Conversion required dividing by magnitude of max negative value
            this.floatOffset = 0;
            this.floatScale = 1 << (this.validBits - 1);
        } else {
            // Else if 8 or less validBits, data is unsigned
            // Conversion required dividing by max positive value
            this.floatOffset = -1;
            this.floatScale = 0.5 * ((1 << this.validBits) - 1);
        }

        this.bufferPointer = 0;
        this.bytesRead = 0;
        this.frameCounter = 0;

        this.ioState = IOState.READING;
    }

    /**
     * Read the wav file audio data
     * 
     * @throws IOException
     */
    private float[][] readData() throws IOException {

        float[][] data = new float[this.numChannels][(int) this.numFrames];
        long framesRead = 0;
        int offset = 0;
        int blockSize = 10000;

        do {
            // Read frames into buffer
            framesRead = readFrames(data, offset, blockSize);
            offset += framesRead;
        } while (framesRead != 0);

        return data;
    }

    /**
     * Close the file after reading/writing.
     * 
     * @throws IOException
     */
    private void close() throws IOException {
        // Close the input stream and set to null
        if (this.iStream != null) {
            this.iStream.close();
            this.iStream = null;
        }

        if (this.oStream != null) {
            // Write out anything still in the local buffer
            if (this.bufferPointer > 0)
                this.oStream.write(this.buffer, 0, this.bufferPointer);

            // If an extra byte is required for word alignment, add it to the
            // end
            if (this.wordAlignAdjust)
                this.oStream.write(0);

            // Close the stream and set to null
            this.oStream.close();
            this.oStream = null;
        }

        // Flag that the stream is closed
        this.ioState = IOState.CLOSED;
    }

    /**
     * Write frames to a file
     * 
     * @param sampleBuffer
     *            the buffer storing the frames to be written
     * @param offset
     *            position in buffer from where numFramesToWrite frames will be
     *            retrieved for writing
     * @param numFramesToWrite
     *            the number of frames to write
     * @return
     * @throws IOException
     */
    private int writeFrames(float[][] sampleBuffer, int offset,
            int numFramesToWrite) throws IOException {
        if (this.ioState != IOState.WRITING)
            throw new IOException("Incorrect IOState");

        if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 32) {
            for (int f = 0; f < numFramesToWrite; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    writeSample((long) Float
                            .floatToIntBits(sampleBuffer[c][offset]));
                }
                offset++;
                this.frameCounter++;
            }
        } else if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 64) {
            for (int f = 0; f < numFramesToWrite; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    writeSample((long) Double
                            .doubleToLongBits((double) sampleBuffer[c][offset]));
                }
                offset++;
                this.frameCounter++;
            }
        } else {
            for (int f = 0; f < numFramesToWrite; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    writeSample((long) (this.floatScale * (this.floatOffset + (double) sampleBuffer[c][offset])));
                }
                offset++;
                this.frameCounter++;
            }
        }

        return numFramesToWrite;
    }

    /**
     * Write frames to a file
     * 
     * This method is not currently used. It is included to cater for the
     * possibility that in the future, Beads will move to storing audio data as
     * doubles.
     * 
     * @param sampleBuffer
     *            the buffer storing the frames to be written
     * @param offset
     *            position in buffer from where numFramesToWrite frames will be
     *            retrieved for writing
     * @param numFramesToWrite
     *            the number of frames to write
     * @return
     * @throws IOException
     */
    @SuppressWarnings("unused")
    private int writeFrames(double[][] sampleBuffer, int offset,
            int numFramesToWrite) throws IOException {
        if (this.ioState != IOState.WRITING)
            throw new IOException("Incorrect IOState");

        if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 32) {
            for (int f = 0; f < numFramesToWrite; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    writeSample((long) Float
                            .floatToIntBits((float) sampleBuffer[c][offset]));
                }
                offset++;
                this.frameCounter++;
            }
        } else if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 64) {
            for (int f = 0; f < numFramesToWrite; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    writeSample((long) Double
                            .doubleToLongBits(sampleBuffer[c][offset]));
                }
                offset++;
                this.frameCounter++;
            }
        } else {
            for (int f = 0; f < numFramesToWrite; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    writeSample((long) (this.floatScale * (this.floatOffset + sampleBuffer[c][offset])));
                }
                offset++;
                this.frameCounter++;
            }
        }

        return numFramesToWrite;
    }

    /**
     * Write a single sample
     * 
     * @param val
     * @throws IOException
     */
    private void writeSample(long val) throws IOException {
        for (int b = 0; b < this.bytesPerSample; b++) {
            if (this.bufferPointer == BUFFER_SIZE) {
                this.oStream.write(this.buffer, 0, BUFFER_SIZE);
                this.bufferPointer = 0;
            }

            this.buffer[this.bufferPointer] = (byte) (val & 0xFF);
            val >>= 8;
            this.bufferPointer++;
        }
    }

    /**
     * Read frames into a float[][]
     * 
     * @param sampleBuffer
     *            the frames read from file will be written here
     * @param offset
     *            number of frames into sampleBuffer to begin writing
     * @param numFramesToRead
     *            number of frames to retrieve
     * @return number of frames read
     * @throws IOException
     */
    private int readFrames(float[][] sampleBuffer, int offset,
            int numFramesToRead) throws IOException {
        if (this.ioState != IOState.READING)
            throw new IOException("Incorrect IOState");

        if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 32) {
            for (int f = 0; f < numFramesToRead; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    sampleBuffer[c][offset] = Float
                            .intBitsToFloat((int) readSample());
                }
                offset++;
                this.frameCounter++;
            }
        } else if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 64) {
            for (int f = 0; f < numFramesToRead; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    sampleBuffer[c][offset] = (float) (Double
                            .longBitsToDouble(readSample()));
                }
                offset++;
                this.frameCounter++;
            }
        } else {
            for (int f = 0; f < numFramesToRead; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    sampleBuffer[c][offset] = (float) (this.floatOffset + (double) readSample()
                            / this.floatScale);
                }
                offset++;
                this.frameCounter++;
            }
        }

        return numFramesToRead;
    }

    /**
     * Read frames into a double[][].
     * 
     * This method is not currently used. It is included to cater for the
     * possibility that in the future, Beads will move to storing audio data as
     * doubles.
     * 
     * @param sampleBuffer
     *            the frames read from file will be written here
     * @param offset
     *            number of frames into sampleBuffer to begin writing
     * @param numFramesToRead
     *            number of frames to retrieve
     * @return number of frames read
     * @throws IOException
     */
    @SuppressWarnings("unused")
    private int readFrames(double[][] sampleBuffer, int offset,
            int numFramesToRead) throws IOException {
        if (this.ioState != IOState.READING)
            throw new IOException("Incorrect IOState");

        if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 32) {
            for (int f = 0; f < numFramesToRead; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    sampleBuffer[c][offset] = (double) (Float
                            .intBitsToFloat((int) readSample()));
                }
                offset++;
                this.frameCounter++;
            }
        } else if (this.compressionCode == WAVE_FORMAT_IEEE_FLOAT
                && this.validBits == 64) {
            for (int f = 0; f < numFramesToRead; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    sampleBuffer[c][offset] = Double
                            .longBitsToDouble(readSample());
                }
                offset++;
                this.frameCounter++;
            }
        } else {
            for (int f = 0; f < numFramesToRead; f++) {

                if (this.frameCounter == this.numFrames)
                    return f;

                for (int c = 0; c < this.numChannels; c++) {
                    sampleBuffer[c][offset] = this.floatOffset
                            + (double) readSample() / this.floatScale;
                }
                offset++;
                this.frameCounter++;
            }
        }

        return numFramesToRead;
    }

    /**
     * Read a single sample
     * 
     * @return
     * @throws IOException
     */
    private long readSample() throws IOException {
        long val = 0;

        for (int b = 0; b < this.bytesPerSample; b++) {
            if (this.bufferPointer == this.bytesRead) {
                int read = this.iStream.read(this.buffer, 0, BUFFER_SIZE);
                if (read == -1)
                    throw new IOException("Not enough data available");
                this.bytesRead = read;
                this.bufferPointer = 0;
            }

            long v = (long) this.buffer[this.bufferPointer];
            if (b < this.bytesPerSample - 1 || this.bytesPerSample == 1)
                v &= 0xFFL;
            val += v << (b * 8);

            this.bufferPointer++;
        }

        return val;
    }

    private long getFramesRemaining() {
        return this.numFrames - this.frameCounter;
    }

    /**
     * Get little endian data from local buffer
     * 
     * @param buffer
     * @param pos
     * @param numBytes
     * @return
     */
    private static long getLE(byte[] buffer, int pos, int numBytes) {
        numBytes--;
        pos += numBytes;

        long val = buffer[pos] & 0xFF;
        for (int b = 0; b < numBytes; b++)
            val = (val << 8) + (buffer[--pos] & 0xFF);

        return val;
    }

    /**
     * Put little endian data in a buffer
     * 
     * @param val
     * @param buffer
     * @param pos
     * @param numBytes
     */
    private static void putLE(long val, byte[] buffer, int pos, int numBytes) {
        for (int b = 0; b < numBytes; b++) {
            buffer[pos] = (byte) (val & 0xFF);
            val >>= 8;
            pos++;
        }
    }
}
