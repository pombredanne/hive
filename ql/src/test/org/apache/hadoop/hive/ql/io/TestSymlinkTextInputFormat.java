/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.io;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

/**
 * Unittest for SymlinkTextInputFormat.
 */
@SuppressWarnings("deprecation")
public class TestSymlinkTextInputFormat extends TestCase {
  private static Log log =
      LogFactory.getLog(TestSymlinkTextInputFormat.class);

  private Configuration conf;
  private JobConf job;
  private FileSystem fileSystem;
  private Path testDir;
  Reporter reporter;

  private Path dataDir1;
  private Path dataDir2;
  private Path symlinkDir;

  @Override
  protected void setUp() throws IOException {
    conf = new Configuration();
    job = new JobConf(conf);
    fileSystem = FileSystem.getLocal(conf);
    testDir = new Path(System.getProperty("test.data.dir", ".") +
                       "/TestSymlinkTextInputFormat");
    reporter = Reporter.NULL;
    fileSystem.delete(testDir, true);

    dataDir1 = new Path(testDir, "datadir1");
    dataDir2 = new Path(testDir, "datadir2");
    symlinkDir = new Path(testDir, "symlinkdir");
  }

  @Override
  protected void tearDown() throws IOException {
    fileSystem.delete(testDir, true);
  }

  /**
   * Test scenario: Two data directories, one symlink file that contains two
   * paths each point to a file in one of data directories.
   */
  public void testAccuracy1() throws IOException {
    // First data dir, contains 2 files.
    
    FileSystem fs = dataDir1.getFileSystem(job);
    int symbolLinkedFileSize = 0;
    
    Path dir1_file1 = new Path(dataDir1, "file1");
    writeTextFile(dir1_file1,
                  "dir1_file1_line1\n" +
                  "dir1_file1_line2\n");
    
    symbolLinkedFileSize += fs.getFileStatus(dir1_file1).getLen();
    
    Path dir1_file2 = new Path(dataDir1, "file2");
    writeTextFile(dir1_file2,
                  "dir1_file2_line1\n" +
                  "dir1_file2_line2\n");
    
    // Second data dir, contains 2 files.
    
    Path dir2_file1 = new Path(dataDir2, "file1");
    writeTextFile(dir2_file1,
                  "dir2_file1_line1\n" +
                  "dir2_file1_line2\n");
    
    Path dir2_file2 = new Path(dataDir2, "file2");
    writeTextFile(dir2_file2,
                  "dir2_file2_line1\n" +
                  "dir2_file2_line2\n");

    symbolLinkedFileSize += fs.getFileStatus(dir2_file2).getLen();
    
    // A symlink file, contains first file from first dir and second file from
    // second dir.
    writeSymlinkFile(
        new Path(symlinkDir, "symlink_file"),
        new Path(dataDir1, "file1"),
        new Path(dataDir2, "file2"));
    
    SymlinkTextInputFormat inputFormat = new SymlinkTextInputFormat();
    
    //test content summary
    ContentSummary cs = inputFormat.getContentSummary(symlinkDir, job);
    
    assertEquals(symbolLinkedFileSize, cs.getLength());
    assertEquals(2, cs.getFileCount());
    assertEquals(0, cs.getDirectoryCount());

    FileInputFormat.setInputPaths(job, symlinkDir);
    InputSplit[] splits = inputFormat.getSplits(job, 2);

    log.info("Number of splits: " + splits.length);

    // Read all values.
    List<String> received = new ArrayList<String>();
    for (InputSplit split : splits) {
      RecordReader<LongWritable, Text> reader =
          inputFormat.getRecordReader(split, job, reporter);

      LongWritable key = reader.createKey();
      Text value = reader.createValue();
      while (reader.next(key, value)) {
        received.add(value.toString());
      }
    }

    List<String> expected = new ArrayList<String>();
    expected.add("dir1_file1_line1");
    expected.add("dir1_file1_line2");
    expected.add("dir2_file2_line1");
    expected.add("dir2_file2_line2");

    assertEquals(expected, received);
  }

  /**
   * Scenario: Empty input directory, i.e. no symlink file.
   *
   * Expected: Should return empty result set without any exception.
   */
  public void testAccuracy2() throws IOException {
    fileSystem.mkdirs(symlinkDir);

    FileInputFormat.setInputPaths(job, symlinkDir);

    SymlinkTextInputFormat inputFormat = new SymlinkTextInputFormat();
    
    ContentSummary cs = inputFormat.getContentSummary(symlinkDir, job);
    
    assertEquals(0, cs.getLength());
    assertEquals(0, cs.getFileCount());
    assertEquals(0, cs.getDirectoryCount());
    
    InputSplit[] splits = inputFormat.getSplits(job, 2);

    log.info("Number of splits: " + splits.length);

    // Read all values.
    List<String> received = new ArrayList<String>();
    for (InputSplit split : splits) {
      RecordReader<LongWritable, Text> reader =
          inputFormat.getRecordReader(split, job, reporter);

      LongWritable key = reader.createKey();
      Text value = reader.createValue();
      while (reader.next(key, value)) {
        received.add(value.toString());
      }
    }

    List<String> expected = new ArrayList<String>();

    assertEquals(expected, received);
  }

  /**
   * Scenario: No job input paths.
   * Expected: IOException with proper message.
   */
  public void testFailure() {
    SymlinkTextInputFormat inputFormat = new SymlinkTextInputFormat();

    try {
      inputFormat.getSplits(job, 2);
      fail("IOException expected if no job input paths specified.");
    } catch (IOException e) {
      assertEquals("Incorrect exception message for no job input paths error.",
                   "No input paths specified in job.",
                   e.getMessage());
    }
  }

  /**
   * Writes the given string to the given file.
   */
  private void writeTextFile(Path file, String content) throws IOException {
    OutputStreamWriter writer = new OutputStreamWriter(fileSystem.create(file));
    writer.write(content);
    writer.close();
  }

  /**
   * Writes a symlink file that contains given list of paths.
   *
   * @param symlinkFile
   * The symlink file to write.
   *
   * @param paths
   * The list of paths to write to the symlink file.
   */
  private void writeSymlinkFile(Path symlinkFile, Path...paths)
      throws IOException {
    OutputStreamWriter writer =
        new OutputStreamWriter(fileSystem.create(symlinkFile));
    for (Path path : paths) {
      writer.write(path.toString());
      writer.write("\n");
    }
    writer.close();
  }
}
