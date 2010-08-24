import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileAlreadyExistsException;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import hadooptrunk.InputSampler;
import hadooptrunk.MultipleOutputs;
import hadooptrunk.TotalOrderPartitioner;

import fi.tkk.ics.hadoop.bam.BAMInputFormat;
import fi.tkk.ics.hadoop.bam.BAMRecordReader;
import fi.tkk.ics.hadoop.bam.KeyIgnoringBAMOutputFormat;
import fi.tkk.ics.hadoop.bam.customsamtools.SAMRecord;

public final class Summarize extends Configured implements Tool {
	public static void main(String[] args) throws Exception {
		System.exit(ToolRunner.run(new Configuration(), new Summarize(), args));
	}

	private final List<Job> jobs = new ArrayList<Job>();

	@Override public int run(String[] args)
		throws ClassNotFoundException, IOException, InterruptedException
	{
		if (args.length < 3) {
			System.err.println(
				"Usage: Summarize <output directory> <levels> "+
				"file [file...]\n\n"+

				"Levels should be a comma-separated list of positive integers. "+
				"For each level,\noutputs a summary file describing the average "+
				"number of alignments at various\npositions in the file. The "+
				"level is the number of alignments that are\nsummarized into "+
				"one.");
			return 2;
		}

		FileSystem fs = FileSystem.get(getConf());

		Path outputDir = new Path(args[0]);
		if (fs.exists(outputDir) && !fs.getFileStatus(outputDir).isDir()) {
			System.err.printf(
				"ERROR: specified output directory '%s' is not a directory!\n",
				outputDir);
			return 2;
		}

		String[] levels = args[1].split(",");
		for (String l : levels) {
			try {
				int lvl = Integer.parseInt(l);
				if (lvl > 0)
					continue;
				System.err.printf(
					"ERROR: specified summary level '%d' is not positive!\n",
					lvl);
			} catch (NumberFormatException e) {
				System.err.printf(
					"ERROR: specified summary level '%s' is not an integer!\n", l);
			}
			return 2;
		}

		List<Path> files = new ArrayList<Path>(args.length - 2);
		for (String file : Arrays.asList(args).subList(2, args.length))
			files.add(new Path(file));

		if (new HashSet<Path>(files).size() < files.size()) {
			System.err.println("ERROR: duplicate file names specified!");
			return 2;
		}

		for (Path file : files) if (!fs.isFile(file)) {
			System.err.printf("ERROR: file '%s' is not a file!\n", file);
			return 2;
		}

		for (Path file : files)
			submitJob(file, outputDir, levels);

		int ret = 0;
		for (Job job : jobs)
			if (!job.waitForCompletion(true))
				ret = 1;
		return ret;
	}

	private void submitJob(
			Path inputFile, Path outputDir, String[] summaryLvls)
		throws ClassNotFoundException, IOException, InterruptedException
	{
		Configuration conf = new Configuration(getConf());

		// Used by SummarizeOutputFormat to construct the output filename
		conf.set(SummarizeOutputFormat.INPUT_FILENAME_PROP, inputFile.getName());

		conf.setStrings(SummarizeReducer.SUMMARY_LEVELS_PROP, summaryLvls);

		setSamplingConf(inputFile, conf);

		// As far as I can tell there's no non-deprecated way of getting this
		// info.
		int maxReduceTasks =
			new JobClient(new JobConf(conf)).getClusterStatus()
			.getMaxReduceTasks();

		conf.setInt("mapred.reduce.tasks", Math.max(1, maxReduceTasks * 9 / 10));

		Job job = new Job(conf);

		job.setJarByClass  (Summarize.class);
		job.setMapperClass (SummarizeMapper.class);
		job.setReducerClass(SummarizeReducer.class);

		job.setMapOutputKeyClass  (IntWritable.class);
		job.setMapOutputValueClass(Range.class);
		job.setOutputKeyClass     (NullWritable.class);
		job.setOutputValueClass   (RangeCount.class);

		job.setInputFormatClass (SummarizeInputFormat.class);
		job.setOutputFormatClass(SummarizeOutputFormat.class);

		FileInputFormat .setInputPaths(job, inputFile);
		FileOutputFormat.setOutputPath(job, outputDir);

		job.setPartitionerClass(TotalOrderPartitioner.class);

		sample(inputFile, job);

		for (String s : summaryLvls)
			MultipleOutputs.addNamedOutput(
				job, "summary" + s, SummarizeOutputFormat.class,
				NullWritable.class, Range.class);

		job.submit();
		jobs.add(job);
	}

	private void setSamplingConf(Path inputFile, Configuration conf)
		throws IOException
	{
		Path inputDir = inputFile.getParent();
		inputDir = inputDir.makeQualified(inputDir.getFileSystem(conf));

		Path partition = new Path(inputDir, "_partitioning");
		TotalOrderPartitioner.setPartitionFile(conf, partition);

		try {
			URI partitionURI = new URI(partition.toString() + "#_partitioning");
			DistributedCache.addCacheFile(partitionURI, conf);
			DistributedCache.createSymlink(conf);
		} catch (URISyntaxException e) { assert false; }
	}

	private void sample(Path inputFile, Job job)
		throws ClassNotFoundException, IOException, InterruptedException
	{
		InputSampler.Sampler<IntWritable,Range> sampler =
			new InputSampler.IntervalSampler<IntWritable,Range>(0.01, 100);

		InputSampler.<IntWritable,Range>writePartitionFile(job, sampler);
	}
}

// The identity function is fine.
final class SummarizeMapper
	extends Mapper<IntWritable,Range, IntWritable,Range>
{}

final class SummarizeReducer
	extends Reducer<IntWritable,Range, NullWritable,RangeCount>
{
	public static final String SUMMARY_LEVELS_PROP = "summarize.summary.levels";

	private MultipleOutputs<NullWritable,RangeCount> mos;

	// Each list is paired with the number of ranges it uses to compute a single
	// summary output.
	private final List<Triple<Integer, String, List<Range>>> summaryLists =
		new ArrayList<Triple<Integer, String, List<Range>>>();

	@Override public void setup(
			Reducer<IntWritable,Range, NullWritable,RangeCount>.Context
				ctx)
	{
		mos = new MultipleOutputs<NullWritable,RangeCount>(ctx);

		for (String s : ctx.getConfiguration().getStrings(SUMMARY_LEVELS_PROP)) {
			int level = Integer.parseInt(s);
			summaryLists.add(
				new Triple<Integer, String, List<Range>>(
					level, "summary" + level, new ArrayList<Range>()));
		}
	}

	@Override protected void reduce(
			IntWritable ignored, Iterable<Range> ranges,
			Reducer<IntWritable,Range, NullWritable,RangeCount>.Context
				context)
		throws IOException, InterruptedException
	{
		for (Range range : ranges)
		for (Triple<Integer, String, List<Range>> tuple : summaryLists) {
			int level = tuple.fst;
			String outName = tuple.snd;
			List<Range> rangeList = tuple.thd;

			rangeList.add(range);
			if (rangeList.size() == level)
				doSummary(rangeList, outName);
		}

		// Don't lose any remaining ones at the end.
		for (Triple<Integer, String, List<Range>> tuple : summaryLists)
			doSummary(tuple.thd, tuple.snd);
	}

	private void doSummary(Collection<Range> group, String outName)
		throws IOException, InterruptedException
	{
		RangeCount summary = computeSummary(group);
		group.clear();
		mos.write(NullWritable.get(), summary, outName);
	}

	private RangeCount computeSummary(Collection<Range> group) {
		RangeCount rc = new RangeCount();

		// Calculate mean centre of mass and length.
		int com = 0;
		int len = 0;
		for (Range r : group) {
			com += r.getCentreOfMass();
			len += r.getLength();
		}
		com /= group.size();
		len /= group.size();

		int beg = com - len/2;
		int end = com + len/2;

		rc.range.beg.set(beg);
		rc.range.end.set(end);
		rc.count.    set(group.size());

		return rc;
	}
}

final class Range implements Writable {
	public final IntWritable beg = new IntWritable();
	public final IntWritable end = new IntWritable();

	public void setFrom(SAMRecord record) {
		beg.set(record.getAlignmentStart());
		end.set(record.getAlignmentEnd());
	}

	public int getCentreOfMass() { return (beg.get() + end.get()) / 2; }
	public int getLength()       { return  end.get() - beg.get();      }

	@Override public void write(DataOutput out) throws IOException {
		beg.write(out);
		end.write(out);
	}
	@Override public void readFields(DataInput in) throws IOException {
		beg.readFields(in);
		end.readFields(in);
	}
}

final class RangeCount implements Comparable<RangeCount>, Writable {
	public final Range       range = new Range();
	public final IntWritable count = new IntWritable();

	// This is what the TextOutputFormat will write. The format is
	// tabix-compatible; see http://samtools.sourceforge.net/tabix.shtml.
	//
	// It might not be sorted by range.beg though! With the centre of mass
	// approach, it most likely won't be.
	@Override public String toString() {
		return "s" // Sequence name: we lose it.
		     + "\t" + range.beg
		     + "\t" + range.end
		     + "\t" + count;
	}

	// Comparisons only take into account the leftmost position.
	@Override public int compareTo(RangeCount o) {
		return Integer.valueOf(range.beg.get()).compareTo(o.range.beg.get());
	}

	@Override public void write(DataOutput out) throws IOException {
		range.write(out);
		count.write(out);
	}
	@Override public void readFields(DataInput in) throws IOException {
		range.readFields(in);
		count.readFields(in);
	}
}

// We want the centre of mass to be used as the key already at this point,
// because we want a total order so that we can meaningfully look at
// consecutive ranges in the reducers. If we were to set the final key in the
// mapper, the partitioner wouldn't use it.
//
// And since getting the centre of mass requires calculating the Range as well,
// we might as well get that here as well.
final class SummarizeInputFormat extends FileInputFormat<IntWritable,Range> {

	private final BAMInputFormat bamIF = new BAMInputFormat();

	@Override protected boolean isSplitable(JobContext job, Path path) {
		return bamIF.isSplitable(job, path);
	}
	@Override public List<InputSplit> getSplits(JobContext job)
		throws IOException
	{
		return bamIF.getSplits(job);
	}

	@Override public RecordReader<IntWritable,Range>
		createRecordReader(InputSplit split, TaskAttemptContext ctx)
			throws InterruptedException, IOException
	{
		final RecordReader<IntWritable,Range> rr = new SummarizeRecordReader();
		rr.initialize(split, ctx);
		return rr;
	}
}
final class SummarizeRecordReader extends RecordReader<IntWritable,Range> {

	private final BAMRecordReader bamRR = new BAMRecordReader();
	private final IntWritable     key   = new IntWritable();
	private final Range           range = new Range();

	@Override public void initialize(InputSplit spl, TaskAttemptContext ctx)
		throws IOException
	{
		bamRR.initialize(spl, ctx);
	}
	@Override public void close() throws IOException { bamRR.close(); }

	@Override public float getProgress() { return bamRR.getProgress(); }

	@Override public IntWritable getCurrentKey  () { return key; }
	@Override public Range       getCurrentValue() { return range; }

	@Override public boolean nextKeyValue() {
		if (!bamRR.nextKeyValue())
			return false;

		range.setFrom(bamRR.getCurrentValue().get());
		key.set(range.getCentreOfMass());
		return true;
	}
}

final class SummarizeOutputFormat
	extends TextOutputFormat<NullWritable,RangeCount>
{
	public static final String INPUT_FILENAME_PROP = "summarize.input.filename";

	@Override public Path getDefaultWorkFile(
			TaskAttemptContext context, String ext)
		throws IOException
	{
		Configuration conf = context.getConfiguration();

		// From MultipleOutputs. If we had a later version of FileOutputFormat as
		// well, we'd use getOutputName().
		String summaryName = conf.get("mapreduce.output.basename");

		String inputName  = conf.get(INPUT_FILENAME_PROP);
		String extension  = ext.isEmpty() ? ext : "." + ext;
		int    part       = context.getTaskAttemptID().getTaskID().getId();
		return new Path(super.getDefaultWorkFile(context, ext).getParent(),
			inputName + "-" + summaryName + "-" + part + extension);
	}

	// Allow the output directory to exist, so that we can make multiple jobs
	// that write into it.
	@Override public void checkOutputSpecs(JobContext job)
		throws FileAlreadyExistsException, IOException
	{}
}

final class Triple<A,B,C> {
	public A fst;
	public B snd;
	public C thd;
	public Triple(A a, B b, C c) {
		fst = a;
		snd = b;
		thd = c;
	}
}
