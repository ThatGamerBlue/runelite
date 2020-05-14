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
package net.runelite.cache;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import net.runelite.cache.definitions.EnumDefinition;
import net.runelite.cache.definitions.loaders.EnumLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.IDClass;

public class EnumManager
{
	private static final String NEGATIVE_PREFIX = "NEGATIVE_";

	private final Store store;
	private final Map<Integer, EnumDefinition> enums = new HashMap<>();
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	public EnumManager(Store store)
	{
		this.store = store;
	}

	public void load() throws IOException
	{
		EnumLoader loader = new EnumLoader();

		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.ENUM.getId());

		byte[] archiveData = storage.loadArchive(archive);
		ArchiveFiles files = archive.getFiles(archiveData);

		for (FSFile f : files.getFiles())
		{
			EnumDefinition def = loader.load(f.getFileId(), f.getContents());
			enums.put(f.getFileId(), def);
		}
	}

	public Collection<EnumDefinition> getEnums()
	{
		return Collections.unmodifiableCollection(enums.values());
	}

	public EnumDefinition getEnum(int enumId)
	{
		return enums.get(enumId);
	}

	public void exportAll(File out) throws IOException
	{
		out.mkdirs();
		int i = 0;

		for (EnumDefinition def : enums.values())
		{
			if (def == null)
			{
				continue;
			}
			exportDef(out, def);
			i++;
		}

		System.out.println("Exported " + i + " enums");
	}

	public void exportOne(File out, int enumId) throws IOException
	{
		out.mkdirs();

		EnumDefinition def = enums.get(enumId);

		if (def == null)
		{
			return;
		}

		exportDef(out, def);
	}

	private void exportDef(File out, @Nonnull EnumDefinition def) throws IOException
	{
		File jsonFile = new File(out, def.getId() + ".json");
		Files.asCharSink(jsonFile, Charset.defaultCharset()).write(gson.toJson(def));
	}

	public void javaAll(File java, boolean reversed) throws IOException
	{
		java.mkdirs();
		int i = 0;

		for (EnumDefinition def : enums.values())
		{
			if (def == null)
			{
				continue;
			}

			if (reversed)
			{
				java(java, def.getId(), "Enum" + def.getId() + "Reversed", true);
			}
			else
			{
				java(java, def.getId(), "Enum" + def.getId(), false);
			}
			i++;
		}

		System.out.println("Exported " + i + " enums in java, " + (reversed ? "reversed format" : "normal format"));
	}

	/**
	 * Dumps an enum to java file.
	 *
	 * @param java      Directory to put the class file in
	 * @param enumId    Enum id in the cache
	 * @param className Java class name
	 * @param reversed  If true, writes VALUE = KEY instead of KEY = VALUE
	 * @throws IOException              If the java file fails to write
	 * @throws IllegalArgumentException If the enum id doesn't exist in the cache
	 */
	public void java(File java, int enumId, String className, boolean reversed) throws IOException, IllegalArgumentException
	{
		java.mkdirs();

		try (IDClass ids = IDClass.create(java, className, "enums"))
		{
			EnumDefinition def = enums.get(enumId);

			if (def == null)
			{
				throw new IllegalArgumentException("Enum ID " + enumId + " does not correspond to an enum in the cache!");
			}

			int[] keys = def.getKeys();
			boolean strings = def.getStringVals() != null;
			Object[] values = strings ? def.getStringVals() : primToObj(def.getIntVals());

			for (int i = 0; i < keys.length; i++)
			{
				if (!reversed)
				{
					String key = String.valueOf(keys[i]);
					if (keys[i] < 0)
					{
						key = NEGATIVE_PREFIX + key.substring(1);
					}
					if (strings)
					{
						ids.add(key, (String) values[i]);
					}
					else
					{
						ids.add(key, (int) values[i]);
					}
				}
				// all below here is reversed
				else if (!strings)
				{
					String key = String.valueOf(values[i]);
					if (((int) values[i]) < 0)
					{
						key = NEGATIVE_PREFIX + key.substring(1);
					}
					ids.add(key, keys[i]);
				}
				else
				{
					ids.add(String.valueOf(values[i]), keys[i]);
				}
			}
		}
	}

	private Integer[] primToObj(int[] ary)
	{
		Integer[] retn = new Integer[ary.length];
		for (int i = 0; i < ary.length; i++)
		{
			retn[i] = ary[i];
		}
		return retn;
	}
}
