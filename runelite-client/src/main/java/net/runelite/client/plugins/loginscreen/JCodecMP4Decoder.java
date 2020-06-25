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
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.NALUnit;
import org.jcodec.codecs.h264.io.model.NALUnitType;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.common.AutoFileChannelWrapper;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.scale.ColorUtil;

@Slf4j
public class JCodecMP4Decoder
{
	@Getter
	private boolean valid;

	private JCodecMP4FrameGrab frameGrab;

	public JCodecMP4Decoder(final File file)
	{
		try
		{
			frameGrab = new JCodecMP4FrameGrab(new AutoFileChannelWrapper(file));
		}
		catch (IOException | IllegalArgumentException | InterruptedException e)
		{
			log.error("Failed to open file {} for reading MP4 video", file, e);
			return;
		}

		valid = true;
	}

	public BufferedImage getNextFrame()
	{
		try
		{
			return frameGrab.getFrame();
		}
		catch (IOException e)
		{
			log.error("Failed to read frame from MP4 video using JCodec", e);
		}
		return null;
	}

	public void destroy()
	{
		frameGrab.stop();
		frameGrab = null;
	}

	static class JCodecMP4FrameGrab
	{
		private final MP4DecoderThread mp4DecoderThread;

		public JCodecMP4FrameGrab(AutoFileChannelWrapper afcw) throws IOException, InterruptedException
		{
			mp4DecoderThread = new MP4DecoderThread(afcw);
			mp4DecoderThread.setUncaughtExceptionHandler((thread, ex) ->
			{
				if (ex instanceof RuntimeException && ex.getCause() instanceof IOException)
				{
					log.error("Uncaught rethrown IOException in MP4DecoderThread");
				}
				else
				{
					log.error("Unknown uncaught exception thrown in MP4DecoderThread");
				}
				ex.printStackTrace();
			});
			mp4DecoderThread.start();
		}

		public void stop()
		{
			mp4DecoderThread.setRunning(false);
		}

		public BufferedImage getFrame() throws IOException
		{
			return mp4DecoderThread.getFrame();
		}

		private static class MP4DecoderThread extends Thread
		{
			// covers the most common patterns of bframes and iframes interleaved
			private static final int FRAME_BUFFER_SIZE = 35;

			private final SeekableDemuxerTrack videoTrack;
			private final H264Decoder decoder;
			private final TreeMap<Double, BufferedImage> preloadedFrames;

			private byte[][] buffers;

			@Setter
			private boolean running = true;

			public MP4DecoderThread(AutoFileChannelWrapper afcw) throws IOException, InterruptedException
			{
				preloadedFrames = new TreeMap<>(Double::compare);

				MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(afcw);

				if (demuxer.getVideoTracks().isEmpty())
				{
					throw new IllegalArgumentException("MP4 file has no video track.");
				}

				videoTrack = (SeekableDemuxerTrack) demuxer.getVideoTracks().get(0);

				if (videoTrack.getMeta().getCodec() != Codec.H264)
				{
					throw new IllegalArgumentException("MP4 file must be in H264 format.");
				}

				decoder = H264Decoder.createH264DecoderFromCodecPrivate(videoTrack.getMeta().getCodecPrivate());

				bufferFrames(FRAME_BUFFER_SIZE);
			}

			@Override
			public void run()
			{
				while (running)
				{
					try
					{
						bufferFrames(FRAME_BUFFER_SIZE);
					}
					catch (IOException | InterruptedException e)
					{
						throw new RuntimeException(e);
					}
				}
			}

			private void bufferFrames(int size) throws IOException, InterruptedException
			{
				// FIXME: this is very stupid but it works
				Thread.sleep(1);
				while (preloadedFrames.size() < size)
				{
					Packet frame = videoTrack.nextFrame();
					if (frame == null)
					{
						videoTrack.gotoFrame(0);
						frame = videoTrack.nextFrame();
					}

					BufferedImage image = toBufferedImage(decoder.decodeFrame(frame.getData(), getBuffer()));
					synchronized (preloadedFrames)
					{
						preloadedFrames.put(frame.getPtsD(), image);
					}
				}
			}

			public BufferedImage getFrame() throws IOException
			{
				synchronized (preloadedFrames)
				{
					Map.Entry<Double, BufferedImage> retn = preloadedFrames.firstEntry();
					if (retn == null)
					{
						// we're at the end of the buffer
						return null;
					}
					preloadedFrames.remove(retn.getKey());
					return retn.getValue();
				}
			}

			private byte[][] getBuffer()
			{
				if (buffers == null)
				{
					Size size = calcBufferSize();
					buffers = Picture.create(size.getWidth(), size.getHeight(), ColorSpace.YUV444).getData();
				}
				return buffers;
			}

			private Size calcBufferSize()
			{
				// declare output variables
				int w = Integer.MIN_VALUE;
				int h = Integer.MIN_VALUE;

				// get track metadata
				DemuxerTrackMeta meta = videoTrack.getMeta();

				// allocate buffers to hold metadata
				ByteBuffer codecBuffer = meta.getCodecPrivate().duplicate();
				ByteBuffer nalBuffer;

				// search the metadata for the size units
				while ((nalBuffer = H264Utils.nextNALUnit(codecBuffer)) != null)
				{
					// read unit from the nalBuffer
					NALUnit unit = NALUnit.read(nalBuffer);

					// only interested in Sequence Parameter Set units
					if (unit.type != NALUnitType.SPS)
					{
						continue;
					}

					// read the sps from the buffer
					// luckily jcodec does 99% of the work for us
					SeqParameterSet sps = H264Utils.readSPS(nalBuffer);

					// read the width from the parameters
					int tempW = sps.picWidthInMbsMinus1 + 1;
					if (tempW > w)
					{
						w = tempW;
					}

					// read the height from the parameters
					int tempH = SeqParameterSet.getPicHeightInMbs(sps);
					if (tempH > h)
					{
						h = tempH;
					}
				}

				return new Size(w << 4, h << 4);
			}

			private BufferedImage toBufferedImage(Picture src)
			{
				if (src.getColor() != ColorSpace.RGB)
				{
					Picture rgb = Picture.create(src.getWidth(), src.getHeight(), ColorSpace.RGB);
					ColorUtil.getTransform(src.getColor(), ColorSpace.RGB).transform(src, rgb);
					src = rgb;
				}

				BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
				int[] data = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
				byte[] srcData = src.getPlaneData(0);

				for (int i = 0; i < data.length; i++)
				{
					int color = (srcData[(i * 3)] + 128) << 16; // r
					color |= (srcData[(i * 3) + 1] + 128) << 8; // g
					color |= (srcData[(i * 3) + 2] + 128); // b
					data[i] = color;
				}

				return dst;
			}
		}
	}
}
