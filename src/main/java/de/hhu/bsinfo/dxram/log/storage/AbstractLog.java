/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science,
 * Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxram.log.storage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;

import de.hhu.bsinfo.dxram.util.HarddriveAccessMode;
import de.hhu.bsinfo.dxutils.jni.JNIFileDirect;
import de.hhu.bsinfo.dxutils.jni.JNIFileRaw;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.TimePool;
import de.hhu.bsinfo.dxutils.stats.ValuePool;

/**
 * Skeleton for a log
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 06.06.2014
 */
public abstract class AbstractLog {
    private static final TimePool SOP_WRITE_PRIMARY_LOG = new TimePool(AbstractLog.class, "WritePrimaryLog");
    private static final TimePool SOP_WRITE_SECONDARY_LOG = new TimePool(AbstractLog.class, "WriteSecondaryLog");
    private static final TimePool SOP_READ_SECONDARY_LOG = new TimePool(AbstractLog.class, "ReadSecondaryLog");
    private static final ValuePool SOP_WRITE_SECONDARY_LOG_DATA = new ValuePool(AbstractLog.class, "WriteSecondaryLog");

    static {
        StatisticsManager.get().registerOperation(AbstractLog.class, SOP_WRITE_PRIMARY_LOG);
        StatisticsManager.get().registerOperation(AbstractLog.class, SOP_WRITE_SECONDARY_LOG);
        StatisticsManager.get().registerOperation(AbstractLog.class, SOP_READ_SECONDARY_LOG);
        StatisticsManager.get().registerOperation(AbstractLog.class, SOP_WRITE_SECONDARY_LOG_DATA);
    }

    private static byte[] ms_nullSegment = new byte[1];
    private static DirectByteBufferWrapper ms_nullSegmentWrapper;

    // Attributes
    private final long m_logFileSize;
    private long m_totalUsableSpace;
    private int m_logSegmentSize;
    private int m_flashPageSize;
    private File m_logFile;

    private HarddriveAccessMode m_mode;
    private RandomAccessFile m_randomAccessFile;
    private int m_fileID;

    private ReentrantLock m_fileAccessLock;

    // Constructors

    /**
     * Initializes the common resources of a log
     *
     * @param p_logFile
     *         the random access file (RAF) of the log
     * @param p_logSize
     *         the size in byte of the log
     * @param p_mode
     *         the HarddriveAccessMode
     */
    AbstractLog(final File p_logFile, final long p_logSize, HarddriveAccessMode p_mode, final int p_logSegmentSize,
            final int p_flashPageSize) {
        m_logFile = p_logFile;
        m_logFileSize = p_logSize;
        m_totalUsableSpace = p_logSize;

        m_logSegmentSize = p_logSegmentSize;
        m_flashPageSize = p_flashPageSize;

        m_mode = p_mode;
        m_randomAccessFile = null;
        m_fileID = -1;

        m_fileAccessLock = new ReentrantLock(false);
        ms_nullSegmentWrapper = new DirectByteBufferWrapper(m_flashPageSize, true);
    }

    /**
     * Returns the number of bytes in log
     *
     * @return the number of bytes in log
     */
    abstract long getOccupiedSpace();

    /**
     * Returns the log file's size
     *
     * @return the size
     */
    final long getFileSize() {
        if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
            return m_logFile.length();
        } else if (m_mode == HarddriveAccessMode.ODIRECT) {
            return JNIFileDirect.length(m_fileID);
        } else {
            return JNIFileRaw.length(m_fileID);
        }
    }

    // Getter

    /**
     * Key function to read from log file randomly
     *
     * @param p_bufferWrapper
     *         buffer to fill with log data
     * @param p_length
     *         number of bytes to read
     * @param p_readPos
     *         the position within the log file
     * @param p_randomAccessFile
     *         the RandomAccessFile
     * @throws IOException
     *         if reading the random access file failed
     */
    static void readFromSecondaryLogFile(final DirectByteBufferWrapper p_bufferWrapper, final int p_length,
            final long p_readPos, final RandomAccessFile p_randomAccessFile) throws IOException {
        final long bytesUntilEnd = p_randomAccessFile.length() - p_readPos;

        if (p_length > 0) {
            assert p_length <= bytesUntilEnd;

            SOP_READ_SECONDARY_LOG.start();

            p_randomAccessFile.seek(p_readPos);
            p_randomAccessFile.readFully(p_bufferWrapper.getBuffer().array(), 0, p_length);

            SOP_READ_SECONDARY_LOG.stop();
        }
    }

    /**
     * Key function to read from log file randomly - O_DIRECT or Raw version
     *
     * @param p_bufferWrapper
     *         page-aligned buffer to fill with log data
     * @param p_length
     *         number of bytes to read
     * @param p_readPos
     *         the position within the log file
     * @param p_fileID
     *         the descriptor of the logfile
     * @param p_mode
     *         the HarddriveAccessMode
     */
    static void readFromSecondaryLogFile(final DirectByteBufferWrapper p_bufferWrapper, final int p_length,
            final long p_readPos, final int p_fileID, final HarddriveAccessMode p_mode) {

        if (p_mode == HarddriveAccessMode.ODIRECT) {
            final long bytesUntilEnd = JNIFileDirect.length(p_fileID) - p_readPos;

            if (p_length > 0) {
                assert p_length <= bytesUntilEnd;

                SOP_READ_SECONDARY_LOG.start();

                JNIFileDirect.read(p_fileID, p_bufferWrapper.getAddress(), 0, p_length, p_readPos);

                SOP_READ_SECONDARY_LOG.stop();
            }
        } else {
            final long bytesUntilEnd = JNIFileRaw.length(p_fileID) - p_readPos;

            if (p_length > 0) {
                assert p_length <= bytesUntilEnd;

                SOP_READ_SECONDARY_LOG.start();

                JNIFileRaw.read(p_fileID, p_bufferWrapper.getAddress(), 0, p_length, p_readPos);

                SOP_READ_SECONDARY_LOG.stop();
            }
        }
    }

    // Methods

    /**
     * Opens a random access file for the log
     *
     * @param p_logFile
     *         the log file
     * @return file descriptor to the log file
     * @throws IOException
     *         if opening the random access file failed
     */
    private static RandomAccessFile openLog(final File p_logFile) throws IOException {
        return new RandomAccessFile(p_logFile, "rw");
    }

    /**
     * Closes the log
     *
     * @throws IOException
     *         if the closing fails
     */
    public final void close() throws IOException {
        if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
            m_randomAccessFile.close();
        } else if (m_mode == HarddriveAccessMode.ODIRECT) {
            if (JNIFileDirect.close(m_fileID) < 0) {
                throw new IOException("Error Closing the log");
            }
        } else {
            if (JNIFileRaw.closeLog(m_fileID) < 0) {
                throw new IOException("Error Closing the log");
            }
        }
    }

    /**
     * Closes the log and deletes it
     *
     * @throws IOException
     *         if the closing fails
     */
    public void closeAndRemove() throws IOException {
        if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
            m_randomAccessFile.close();

            if (m_logFile.exists()) {
                if (!m_logFile.delete()) {
                    throw new FileNotFoundException();
                }
            }
        } else if (m_mode == HarddriveAccessMode.ODIRECT) {
            if (JNIFileDirect.close(m_fileID) < 0) {
                throw new IOException("Error Closing the log");
            }

            if (m_logFile.exists()) {
                if (!m_logFile.delete()) {
                    throw new FileNotFoundException();
                }
            }
        } else {
            if (JNIFileRaw.closeLog(m_fileID) < 0) {
                throw new IOException("Error Closing the log");
            }

            JNIFileRaw.deleteLog(m_fileID);
        }
    }

    /**
     * Creates and initializes random access file
     *
     * @return whether the log could be created
     * @throws IOException
     *         if the header could not be read or written
     */
    final boolean createLogAndWriteHeader() throws IOException {
        boolean ret = true;

        if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
            if (m_logFile.exists()) {
                ret = m_logFile.delete();
            }

            if (ret && !m_logFile.getParentFile().exists()) {
                // Create folders
                ret = m_logFile.getParentFile().mkdirs();
            }

            if (ret) {
                // Create file
                ret = m_logFile.createNewFile();

                // Write header
                m_randomAccessFile = openLog(m_logFile);
            }
        } else if (m_mode == HarddriveAccessMode.ODIRECT) {
            if (m_logFile.exists()) {
                ret = m_logFile.delete();
            }

            if (ret && !m_logFile.getParentFile().exists()) {
                // Create folders
                ret = m_logFile.getParentFile().mkdirs();
            }

            String path = m_logFile.getCanonicalPath();
            m_fileID = JNIFileDirect.open(path, 0, m_totalUsableSpace);
        } else {
            String fileName = m_logFile.getName();
            m_fileID = JNIFileRaw.createLog(fileName, m_logFileSize);
            // Check for error in native Code
            if (m_fileID < 0) {
                throw new IOException("JNI error: Cannot create or open log file");
            }
        }

        return ret;
    }

    /**
     * Key function to read from log randomly
     *
     * @param p_bufferWrapper
     *         page-aligned buffer to fill with log data
     * @param p_length
     *         number of bytes to read
     * @param p_readPos
     *         the position within the log file
     * @throws IOException
     *         if reading the random access file failed
     */
    final void readFromSecondaryLog(final DirectByteBufferWrapper p_bufferWrapper, final int p_length,
            final long p_readPos) throws IOException {
        final long bytesUntilEnd = m_totalUsableSpace - p_readPos;

        if (p_length > 0) {
            SOP_READ_SECONDARY_LOG.start();

            // All reads might be concurrent to writes by writer thread -> lock file
            m_fileAccessLock.lock();

            assert p_length <= bytesUntilEnd;
            assert p_readPos % m_logSegmentSize + p_length <= m_logSegmentSize;

            if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
                m_randomAccessFile.seek(p_readPos);
                m_randomAccessFile.readFully(p_bufferWrapper.getBuffer().array(), 0, p_length);
            } else if (m_mode == HarddriveAccessMode.ODIRECT) {
                if (JNIFileDirect.read(m_fileID, p_bufferWrapper.getAddress(), 0, p_length, p_readPos) < 0) {
                    throw new IOException("Error reading from log");
                }
            } else {
                if (JNIFileRaw.read(m_fileID, p_bufferWrapper.getAddress(), 0, p_length, p_readPos) < 0) {
                    throw new IOException("Error reading from log");
                }
            }

            m_fileAccessLock.unlock();

            SOP_READ_SECONDARY_LOG.stop();
        }
    }

    /**
     * Key function to write in log sequentially
     *
     * @param p_bufferWrapper
     *         buffer with data to write in log
     * @param p_length
     *         number of bytes to write
     * @param p_writePos
     *         the current write position
     * @return updated write position
     * @throws IOException
     *         if reading the random access file failed
     */
    final long appendToPrimaryLog(final DirectByteBufferWrapper p_bufferWrapper, final int p_length,
            final long p_writePos) throws IOException {
        long ret;

        SOP_WRITE_PRIMARY_LOG.start();

        if (p_bufferWrapper == null) {
            throw new IOException("Error writing to log. Buffer wrapper is null");
        }

        assert p_writePos + p_length <= m_totalUsableSpace;
        assert p_bufferWrapper.getBuffer().position() == 0;

        // Mark the end of the write access
        p_bufferWrapper.getBuffer().put(p_length, (byte) 0);

        if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
            m_randomAccessFile.seek(p_writePos);
            m_randomAccessFile.write(p_bufferWrapper.getBuffer().array(), 0, p_length + 1);
        } else if (m_mode == HarddriveAccessMode.ODIRECT) {
            if (JNIFileDirect
                    .write(m_fileID, p_bufferWrapper.getAddress(), 0, p_length + 1, p_writePos, (byte) 0, (byte) 0) <
                    0) {
                throw new IOException("Error writing to log");
            }
        } else {
            if (JNIFileRaw
                    .write(m_fileID, p_bufferWrapper.getAddress(), 0, p_length + 1, p_writePos, (byte) 0, (byte) 0) <
                    0) {
                throw new IOException("Error writing to log");
            }
        }

        ret = p_writePos + p_length;

        SOP_WRITE_PRIMARY_LOG.stop();

        return ret;
    }

    /**
     * Key function to write in log
     *
     * @param p_bufferWrapper
     *         buffer with data to write in log
     * @param p_bufferOffset
     *         offset in buffer
     * @param p_writePos
     *         offset in log file
     * @param p_length
     *         number of bytes to write
     * @param p_accessed
     *         whether the RAF is accessed by another thread or not
     * @throws IOException
     *         if reading the random access file failed
     */
    final void writeToSecondaryLog(final DirectByteBufferWrapper p_bufferWrapper, final int p_bufferOffset,
            final long p_writePos, final int p_length, final boolean p_accessed) throws IOException {

        if (p_length > 0) {
            SOP_WRITE_SECONDARY_LOG_DATA.add(p_length);
            SOP_WRITE_SECONDARY_LOG.start();

            if (p_accessed) {
                m_fileAccessLock.lock();
            }

            assert p_writePos + p_length <= m_totalUsableSpace;
            assert p_writePos % m_logSegmentSize + p_length <= m_logSegmentSize;

            if (p_bufferWrapper == null && p_length == 1) {
                // Write 0 to the beginning of the segment
                if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
                    m_randomAccessFile.seek(p_writePos);
                    m_randomAccessFile.write(ms_nullSegment, 0, 1);
                } else if (m_mode == HarddriveAccessMode.ODIRECT) {
                    if (JNIFileDirect
                            .write(m_fileID, ms_nullSegmentWrapper.getAddress(), 0, 1, p_writePos, (byte) 0, (byte) 0) <
                            0) {
                        throw new IOException("Error writing to log");
                    }
                } else {
                    if (JNIFileRaw
                            .write(m_fileID, ms_nullSegmentWrapper.getAddress(), 0, 1, p_writePos, (byte) 0, (byte) 0) <
                            0) {
                        throw new IOException("Error writing to log");
                    }
                }
            } else {
                if (p_bufferWrapper == null) {
                    throw new IOException("Error writing to log. Buffer wrapper is null");
                }

                if (p_bufferOffset + p_length + 1 < p_bufferWrapper.getBuffer().capacity() &&
                        p_writePos % m_logSegmentSize + p_length < m_logSegmentSize) {

                    // Mark the end of the segment
                    byte oldByte = p_bufferWrapper.getBuffer().get(p_bufferOffset + p_length);
                    p_bufferWrapper.getBuffer().put(p_bufferOffset + p_length, (byte) 0);

                    if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
                        m_randomAccessFile.seek(p_writePos);
                        m_randomAccessFile.write(p_bufferWrapper.getBuffer().array(), p_bufferOffset, p_length + 1);
                    } else if (m_mode == HarddriveAccessMode.ODIRECT) {
                        if (JNIFileDirect
                                .write(m_fileID, p_bufferWrapper.getAddress(), p_bufferOffset, p_length + 1, p_writePos,
                                        (byte) 0, (byte) 0) < 0) {
                            throw new IOException("Error writing to log");
                        }
                    } else {
                        if (JNIFileRaw
                                .write(m_fileID, p_bufferWrapper.getAddress(), p_bufferOffset, p_length + 1, p_writePos,
                                        (byte) 0, (byte) 0) < 0) {
                            throw new IOException("Error writing to log");
                        }
                    }

                    // Write back old byte at boundary
                    p_bufferWrapper.getBuffer().put(p_bufferOffset + p_length, oldByte);
                } else {
                    if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
                        m_randomAccessFile.seek(p_writePos);
                        m_randomAccessFile.write(p_bufferWrapper.getBuffer().array(), p_bufferOffset, p_length);
                    } else if (m_mode == HarddriveAccessMode.ODIRECT) {
                        if (JNIFileDirect
                                .write(m_fileID, p_bufferWrapper.getAddress(), p_bufferOffset, p_length, p_writePos,
                                        (byte) 0, (byte) 0) < 0) {
                            throw new IOException("Error writing to log");
                        }
                    } else {
                        if (JNIFileRaw
                                .write(m_fileID, p_bufferWrapper.getAddress(), p_bufferOffset, p_length, p_writePos,
                                        (byte) 0, (byte) 0) < 0) {
                            throw new IOException("Error writing to log");
                        }
                    }
                    if (p_writePos % m_logSegmentSize + p_length < m_logSegmentSize) {
                        // Mark end of the segment
                        if (m_mode == HarddriveAccessMode.RANDOM_ACCESS_FILE) {
                            m_randomAccessFile.seek(p_writePos + p_length);
                            m_randomAccessFile.write(ms_nullSegment, 0, 1);
                        } else if (m_mode == HarddriveAccessMode.ODIRECT) {
                            if (JNIFileDirect
                                    .write(m_fileID, ms_nullSegmentWrapper.getAddress(), 0, 1, p_writePos + p_length,
                                            (byte) 0, (byte) 0) < 0) {
                                throw new IOException("Error writing to log");
                            }
                        } else {
                            if (JNIFileRaw
                                    .write(m_fileID, ms_nullSegmentWrapper.getAddress(), 0, 1, p_writePos + p_length,
                                            (byte) 0, (byte) 0) < 0) {
                                throw new IOException("Error writing to log");
                            }
                        }
                    }
                }
            }

            if (p_accessed) {
                m_fileAccessLock.unlock();
            }

            SOP_WRITE_SECONDARY_LOG.stop();
        }
    }

    /**
     * Writes data to log
     *
     * @param p_data
     *         a buffer
     * @param p_length
     *         length of data
     * @throws InterruptedException
     *         if the write access fails
     * @throws IOException
     *         if the write access fails
     */

    abstract void appendData(DirectByteBufferWrapper p_data, int p_length) throws IOException, InterruptedException;
}
