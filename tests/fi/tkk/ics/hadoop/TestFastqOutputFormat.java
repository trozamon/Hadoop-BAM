// Copyright (C) 2011-2012 CRS4.
//
// This file is part of Hadoop-BAM.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

package tests.fi.tkk.ics.hadoop.bam;

import fi.tkk.ics.hadoop.bam.FastqOutputFormat.FastqRecordWriter;
import fi.tkk.ics.hadoop.bam.SequencedFragment;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;

import org.junit.*;
import static org.junit.Assert.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;

public class TestFastqOutputFormat
{
	private SequencedFragment fragment;

	private ByteArrayOutputStream outputBuffer;
	private DataOutputStream dataOutput;
	private FastqRecordWriter writer;

	@Before
	public void setup() throws IOException
	{
		fragment = new SequencedFragment();
		fragment.setInstrument("instrument");
		fragment.setRunNumber(1);
		fragment.setFlowcellId("xyz");
		fragment.setLane(2);
		fragment.setTile(1001);
		fragment.setXpos(10000);
		fragment.setYpos(9999);
		fragment.setRead(1);
		fragment.setFilterPassed(true);
		fragment.setControlNumber(33);
		fragment.setIndexSequence("CATCAT");
		fragment.setSequence(new Text("AAAAAAAAAA"));
		fragment.setQuality(new Text("##########"));

		outputBuffer = new ByteArrayOutputStream();
		dataOutput = new DataOutputStream(outputBuffer);
		writer = new FastqRecordWriter(new Configuration(), dataOutput);
	}

	@Test
	public void testSimple() throws IOException
	{
		writer.write(null, fragment);
		writer.close(null);

		String[] lines = new String(outputBuffer.toByteArray(), "US-ASCII").split("\n");
		assertEquals(4, lines.length);

		String idLine = lines[0];
		assertTrue(idLine.startsWith("@"));

		compareMetadata(fragment, idLine);

		assertEquals(fragment.getSequence().toString(), lines[1]);
		assertEquals("+", lines[2]);
		assertEquals(fragment.getQuality().toString(), lines[3]);
	}

	@Test
	public void testCustomId() throws IOException
	{
		String customKey = "hello";
		writer.write(new Text(customKey), fragment);
		writer.close(null);

		String[] lines = new String(outputBuffer.toByteArray(), "US-ASCII").split("\n");
		assertEquals(4, lines.length);

		String idLine = lines[0];
		assertTrue(idLine.startsWith("@"));
		assertEquals(customKey, idLine.substring(1));
	}

	@Test
	public void testBaseQualitiesInIllumina() throws IOException
	{
		Configuration conf = new Configuration();
		conf.set("hbam.fastq-output.base-quality-encoding", "illumina");
		writer.setConf(conf);

		// ensure sanger qualities are converted to illumina
		String seq = "AAAAAAAAAA";
		String qual = "##########";

		fragment.setSequence(new Text(seq));
		fragment.setQuality(new Text(qual));

		writer.write(null, fragment);
		writer.close(null);

		String[] lines = new String(outputBuffer.toByteArray(), "US-ASCII").split("\n");
		assertEquals(qual.replace("#", "B"), lines[3]);
	}

	@Test
	public void testConfigureOutputInSanger() throws IOException
	{
		Configuration conf = new Configuration();
		conf.set("hbam.fastq-output.base-quality-encoding", "sanger");
		writer.setConf(conf);
		testSimple();
	}

	@Test(expected=RuntimeException.class)
	public void testBadConfig() throws IOException
	{
		Configuration conf = new Configuration();
		conf.set("hbam.fastq-output.base-quality-encoding", "blalbal");
		writer.setConf(conf);
	}

	@Test
	public void testClose() throws IOException
	{
		// doesn't really do anything but exercise the code
		writer.close(null);
	}

	private static void compareMetadata(SequencedFragment fragment, String idLine)
	{
		idLine = idLine.substring(1);
		String[] pieces = idLine.split(" ")[0].split(":"); // first part: location on flowcell
		assertEquals(fragment.getInstrument(), pieces[0]);
		assertEquals(fragment.getRunNumber().toString(), pieces[1]);
		assertEquals(fragment.getLane().toString(), pieces[2]);
		assertEquals(fragment.getTile().toString(), pieces[3]);
		assertEquals(fragment.getXpos().toString(), pieces[4]);
		assertEquals(fragment.getYpos().toString(), pieces[5]);

		pieces = idLine.split(" ")[1].split(":"); // second part
		assertEquals(fragment.getRead().toString(), pieces[0]);
		assertEquals(fragment.getFilterPassed() ? "N" : "Y", pieces[1]);
		assertEquals(fragment.getControlNumber().toString(), pieces[2]);
		assertEquals(fragment.getIndexSequence().toString(), pieces[3]);
	}
}