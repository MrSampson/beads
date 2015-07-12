package net.beadsproject.beads.data.audiofile;

/**
 * Audio file types that may be supported by an AudioFileReader or AudioFileWriter
 * @author aengus
 */
public enum AudioFileType {
	/**
	 * WAV files
	 */
	WAV, 
	/**
	 *  AIFF files
	 */
	AIFF, 
	/**
	 *  MP3 files
	 */
	MP3, 
	/**
	 * Ogg-Vorbis files
	 */
	OGG, 
	/**
	 * Windows Media files
	 */
	WMA;
}
