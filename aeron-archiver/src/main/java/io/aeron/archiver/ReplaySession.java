/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archiver;

import io.aeron.*;
import io.aeron.archiver.codecs.RecordingDescriptorDecoder;
import io.aeron.logbuffer.ExclusiveBufferClaim;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.*;
import org.agrona.concurrent.EpochClock;

import java.io.*;

/**
 * A replay session with a client which works through the required request response flow and streaming of recorded data.
 * The {@link ArchiveConductor} will initiate a session on receiving a ReplayRequest
 * (see {@link io.aeron.archiver.codecs.ReplayRequestDecoder}). The session will:
 * <ul>
 * <li>Validate request parameters and respond with error,
 *     or OK message(see {@link io.aeron.archiver.codecs.ControlResponseDecoder})</li>
 * <li>Stream recorded data into the replayPublication {@link Publication}</li>
 * </ul>
 */
class ReplaySession implements
    ArchiveConductor.Session,
    RecordingFragmentReader.SimplifiedControlledPoll
{
    private enum State
    {
        INIT, REPLAY, LINGER, INACTIVE, CLOSED
    }

    public static final int REPLAY_SEND_BATCH_SIZE = 8;
    public static final long LINGER_LENGTH_MS = 1000;

    private final long recordingId;
    private final long fromPosition;
    private final long replayLength;

    private final ArchiveConductor conductor;
    private final ExclusivePublication controlPublication;

    private final File archiveDir;
    private final ControlSessionProxy controlSessionProxy;
    private final ExclusiveBufferClaim bufferClaim = new ExclusiveBufferClaim();

    private State state = State.INIT;
    private RecordingFragmentReader cursor;
    private final long replaySessionId;
    private final long correlationId;
    private final EpochClock epochClock;
    private final String replayChannel;
    private final int replayStreamId;

    private ExclusivePublication replayPublication;
    private int mtuLength;
    private int termBufferLength;
    private int initialTermId;
    private long lingerSinceMs;


    ReplaySession(
        final long recordingId,
        final long fromPosition,
        final long replayLength,
        final ArchiveConductor conductor,
        final ExclusivePublication controlPublication,
        final File archiveDir,
        final ControlSessionProxy controlSessionProxy,
        final long replaySessionId,
        final long correlationId,
        final EpochClock epochClock,
        final String replayChannel, final int replayStreamId)
    {
        // TODO: set position, set MTU (add to metadata)
        this.recordingId = recordingId;

        this.fromPosition = fromPosition;
        this.replayLength = replayLength;
        this.conductor = conductor;

        this.controlPublication = controlPublication;
        this.archiveDir = archiveDir;
        this.controlSessionProxy = controlSessionProxy;
        this.replaySessionId = replaySessionId;
        this.correlationId = correlationId;
        this.epochClock = epochClock;
        this.lingerSinceMs = epochClock.time();

        this.replayChannel = replayChannel;
        this.replayStreamId = replayStreamId;
    }

    public int doWork()
    {
        int workDone = 0;
        if (state == State.REPLAY)
        {
            workDone += replay();
        }
        else if (state == State.INIT)
        {
            workDone += init();
        }
        else if (state == State.LINGER)
        {
            workDone += linger();
        }

        if (state == State.INACTIVE)
        {
            workDone += close();
        }

        return workDone;
    }

    private int linger()
    {
        if (isLingerDone())
        {
            this.state = State.INACTIVE;
        }

        return 0;
    }

    private boolean isLingerDone()
    {
        return epochClock.time() - LINGER_LENGTH_MS > lingerSinceMs;
    }

    public void abort()
    {
        this.state = State.INACTIVE;
    }

    public boolean isDone()
    {
        return state == State.CLOSED;
    }

    public void remove(final ArchiveConductor conductor)
    {
        conductor.removeReplaySession(replaySessionId);
    }

    private int init()
    {
        if (cursor == null)
        {
            final String recordingMetaFileName = ArchiveUtil.recordingMetaFileName(recordingId);
            final File recordingMetaFile = new File(archiveDir, recordingMetaFileName);
            if (!recordingMetaFile.exists())
            {
                return closeOnError(null, recordingMetaFile.getAbsolutePath() + " not found");
            }

            final RecordingDescriptorDecoder metaData;
            try
            {
                metaData = ArchiveUtil.recordingMetaFileFormatDecoder(recordingMetaFile);
            }
            catch (final IOException ex)
            {
                return closeOnError(ex, recordingMetaFile.getAbsolutePath() + " : failed to map");
            }

            final long initialPosition = metaData.initialPosition();
            final long lastPosition = metaData.lastPosition();
            mtuLength = metaData.mtuLength();
            termBufferLength = metaData.termBufferLength();
            initialTermId = metaData.initialTermId();

            IoUtil.unmap(metaData.buffer().byteBuffer());

            if (this.fromPosition < initialPosition)
            {
                return closeOnError(null, "Requested replay start position(=" + fromPosition +
                    ") is less than recording initial position(=" + initialPosition + ")");
            }
            final long toPosition = this.replayLength + fromPosition;
            if (toPosition > lastPosition)
            {
                return closeOnError(null, "Requested replay end position(=" + toPosition +
                    ") is more than recording end position(=" + lastPosition + ")");
            }

            try
            {
                cursor = new RecordingFragmentReader(
                    recordingId,
                    archiveDir,
                    fromPosition,
                    replayLength);
            }
            catch (final IOException ex)
            {
                return closeOnError(ex, "Failed to open cursor for a recording");
            }
        }

        if (replayPublication == null)
        {
            replayPublication = conductor.newReplayPublication(
                replayChannel,
                replayStreamId,
                fromPosition,
                mtuLength,
                initialTermId,
                termBufferLength);
        }

        if (!replayPublication.isConnected())
        {
            if (isLingerDone())
            {
                // TODO: add counter
                this.state = State.INACTIVE;
            }
            return 0;
        }


        controlSessionProxy.sendResponse(controlPublication, null, correlationId);
        this.state = State.REPLAY;

        return 1;
    }

    private int closeOnError(final Throwable e, final String errorMessage)
    {
        this.state = State.INACTIVE;
        if (controlPublication.isConnected())
        {
            controlSessionProxy.sendResponse(controlPublication, errorMessage, correlationId);
        }

        if (e != null)
        {
            LangUtil.rethrowUnchecked(e);
        }

        return 0;
    }

    private int replay()
    {
        try
        {
            final int polled = cursor.controlledPoll(this, REPLAY_SEND_BATCH_SIZE);
            if (cursor.isDone())
            {
                lingerSinceMs = epochClock.time();
                this.state = State.LINGER;
            }
            return polled;
        }
        catch (final Exception ex)
        {
            return closeOnError(ex, "Cursor read failed");
        }
    }

    private int close()
    {
        CloseHelper.close(replayPublication);
        CloseHelper.close(cursor);
        this.state = State.CLOSED;

        return 1;
    }

    public boolean onFragment(
        final DirectBuffer fragmentBuffer,
        final int fragmentOffset,
        final int fragmentLength,
        final DataHeaderFlyweight header)
    {
        if (isDone())
        {
            return false;
        }

        final long result = replayPublication.tryClaim(fragmentLength, bufferClaim);
        if (result > 0)
        {
            try
            {
                final MutableDirectBuffer publicationBuffer = bufferClaim.buffer();
                bufferClaim.flags((byte)header.flags());
                bufferClaim.reservedValue(header.reservedValue());
                // TODO: ??? bufferClaim.headerType(header.type()); ???

                final int offset = bufferClaim.offset();
                publicationBuffer.putBytes(offset, fragmentBuffer, fragmentOffset, fragmentLength);
            }
            finally
            {
                bufferClaim.commit();
            }

            return true;
        }
        else if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED)
        {
            closeOnError(null, "Reply publication to replay requestor has shutdown mid-replay");
        }

        return false;
    }
}
