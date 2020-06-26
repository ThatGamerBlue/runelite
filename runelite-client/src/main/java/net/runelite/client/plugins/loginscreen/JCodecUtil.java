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
import java.nio.ByteBuffer;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.NALUnit;
import org.jcodec.codecs.h264.io.model.NALUnitType;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.scale.ColorUtil;

public class JCodecUtil
{
	public static BufferedImage toBufferedImage(Picture src)
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

	public static Size calcBufferSize(DemuxerTrackMeta meta)
	{
		// declare output variables
		int w = Integer.MIN_VALUE;
		int h = Integer.MIN_VALUE;

		// allocate buffers to hold metadata
		ByteBuffer codecBuffer = meta.getCodecPrivate().duplicate();
		ByteBuffer nalBuffer;

		// search the metadata for the size unit
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
}
