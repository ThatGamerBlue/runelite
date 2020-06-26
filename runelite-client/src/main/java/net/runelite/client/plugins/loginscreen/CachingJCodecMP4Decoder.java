/*
 * Copyright (c) 2020, ThatGamerBlue <thatgamerblue@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.loginscreen;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.TreeMap;
import lombok.Getter;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.common.AutoFileChannelWrapper;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.model.ColorSpace;
import static org.jcodec.common.model.ColorSpace.*;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;

public class CachingJCodecMP4Decoder implements IJCodecMP4Decoder
{
	private final TreeMap<Double, ByteBuffer> cachedFrames;
	private final H264Decoder decoder;
	private final Size imageSizeForBuffer;
	private final int totalFrameCount;

	@Getter
	private final boolean valid;

	private byte[][] pictureBuffer;
	private int currentFrameCount;

	public CachingJCodecMP4Decoder(final File file) throws IOException
	{
		MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(new AutoFileChannelWrapper(file));

		if (demuxer.getVideoTracks().isEmpty())
		{
			throw new IllegalArgumentException("MP4 file has no video track.");
		}

		DemuxerTrack videoTrack = demuxer.getVideoTracks().get(0);
		DemuxerTrackMeta meta = videoTrack.getMeta();

		if (meta.getCodec() != Codec.H264)
		{
			throw new IllegalArgumentException("MP4 file must be in H264 format.");
		}

		if (meta.getTotalFrames() == 0)
		{
			throw new IllegalStateException("MP4 file has no frames.");
		}

		totalFrameCount = meta.getTotalFrames();
		cachedFrames = new TreeMap<>(Double::compare);
		decoder = H264Decoder.createH264DecoderFromCodecPrivate(meta.getCodecPrivate());
		imageSizeForBuffer = JCodecUtil.calcBufferSize(meta);

		initialize(videoTrack);

		valid = true;
	}

	@Override
	public BufferedImage getNextFrame()
	{
		// fetch data from array
		final Optional<ByteBuffer> dataOptional = cachedFrames.values().stream().skip(currentFrameCount++).findFirst();
		if (!dataOptional.isPresent())
		{
			throw new IllegalStateException("dataOptional missing after " + (currentFrameCount - 1) + " frames");
		}
		ByteBuffer data = dataOptional.get();

		// loop back to beginning
		if (currentFrameCount == totalFrameCount)
		{
			currentFrameCount = 0;
		}

		// make sure data is at the start
		data.rewind();

		// decode the frame and return it
		return JCodecUtil.toBufferedImage(decoder.decodeFrame(data, getPictureBuffer()));
	}

	@Override
	public void destroy()
	{
		for (ByteBuffer buf : cachedFrames.values())
		{
			buf.clear();
			buf = null;
		}
	}

	private void initialize(DemuxerTrack videoTrack) throws IOException
	{
		Packet frame;
		int i = 0;
		while ((frame = videoTrack.nextFrame()) != null)
		{
			// put the frame into the map in timestamp order, to avoid frame jitter
			cachedFrames.put(frame.getPtsD(), frame.getData());
			i++;
		}
	}

	/**
	 * This function was mostly copied from {@link org.jcodec.common.model.Picture#createCropped(int, int, org.jcodec.common.model.ColorSpace, org.jcodec.common.model.Rect)}
 	 */
	private byte[][] getPictureBuffer()
	{
		if (pictureBuffer != null)
		{
			return pictureBuffer;
		}

		ColorSpace colorSpace = YUV444;
		int width = imageSizeForBuffer.getWidth();
		int height = imageSizeForBuffer.getHeight();

		int[] planeSizes = new int[MAX_PLANES];
		for (int i = 0; i < colorSpace.nComp; i++)
		{
			planeSizes[colorSpace.compPlane[i]] += (width >> colorSpace.compWidth[i]) * (height >> colorSpace.compHeight[i]);
		}
		int nPlanes = 0;
		for (int i = 0; i < MAX_PLANES; i++)
		{
			nPlanes += planeSizes[i] != 0 ? 1 : 0;
		}

		byte[][] data = new byte[nPlanes][];
		for (int i = 0, plane = 0; i < MAX_PLANES; i++)
		{
			if (planeSizes[i] != 0)
			{
				data[plane++] = new byte[planeSizes[i]];
			}
		}

		pictureBuffer = data;

		return pictureBuffer;
	}
}
