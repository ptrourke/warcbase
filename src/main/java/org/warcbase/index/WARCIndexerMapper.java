package org.warcbase.index;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.archive.io.ArchiveRecord;
import org.archive.io.ArchiveRecordHeader;

import uk.bl.wa.hadoop.WritableArchiveRecord;
import uk.bl.wa.hadoop.indexer.WritableSolrRecord;
import uk.bl.wa.indexer.WARCIndexer;
import uk.bl.wa.solr.SolrRecord;
import uk.bl.wa.solr.SolrWebServer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class WARCIndexerMapper extends MapReduceBase implements
    Mapper<Text, WritableArchiveRecord, IntWritable, WritableSolrRecord> {
  private static final Log LOG = LogFactory.getLog(WARCIndexerMapper.class);

  static enum MyCounters {
    NUM_RECORDS, NUM_ERRORS, NUM_NULLS, NUM_EMPTY_HEADERS
  }

  private String mapTaskId;
  private String inputFile;
  private int numRecords = 0;
  private int numShards = 1;

  private WARCIndexer indexer;
  private Config config;

  @Override
  public void configure(JobConf job) {
    try {
      this.config = ConfigFactory.parseString(job.get(WARCIndexerRunner.CONFIG_PROPERTIES));
      this.indexer = new WARCIndexer(config);

      numShards = config.getInt(SolrWebServer.NUM_SHARDS);
      mapTaskId = job.get("mapred.task.id");
      inputFile = job.get("map.input.file");

      LOG.info("Got task.id " + mapTaskId + " and input.file " + inputFile);
    } catch (NoSuchAlgorithmException e) {
      LOG.error("WARCIndexerMapper.configure(): " + e.getMessage());
    }
  }

  @Override
  public void map(Text key, WritableArchiveRecord value,
      OutputCollector<IntWritable, WritableSolrRecord> output, Reporter reporter)
      throws IOException {
    ArchiveRecordHeader header = value.getRecord().getHeader();
    ArchiveRecord rec = value.getRecord();
    SolrRecord solr = new SolrRecord(key.toString(), rec.getHeader());

    numRecords++;

    try {
      if (!header.getHeaderFields().isEmpty()) {
        solr = indexer.extract(key.toString(), value.getRecord());

        // If there is no result, report it.
        if (solr == null) {
          LOG.debug("WARCIndexer returned NULL for: " + header.getUrl());
          reporter.incrCounter(MyCounters.NUM_NULLS, 1);
          return;
        }

        // Increment record counter.
        reporter.incrCounter(MyCounters.NUM_RECORDS, 1);
      } else {
        // Report headerless records.
        reporter.incrCounter(MyCounters.NUM_EMPTY_HEADERS, 1);
      }

    } catch (Exception e) {
      LOG.error(e.getClass().getName() + ": " + e.getMessage() + "; " + header.getUrl() + "; " + header.getOffset());
      reporter.incrCounter(MyCounters.NUM_ERRORS, 1);
      solr.addParseException(e);
    } catch (OutOfMemoryError e) {
      LOG.error("OOME " + e.getClass().getName() + ": " + e.getMessage() + "; " + header.getUrl() + "; " + header.getOffset());
      reporter.incrCounter(MyCounters.NUM_ERRORS, 1);
      solr.addParseException(e);
    }

    // Random partition assignment.
    IntWritable partition = new IntWritable((int) (Math.round(Math.random() * numShards)));

    // Wrap up and collect the result:
    WritableSolrRecord solrRecord = new WritableSolrRecord(solr);
    output.collect(partition, solrRecord);

    // Occasionally update application-level status.
    if ((numRecords % 1000) == 0) {
      reporter.setStatus(numRecords + " processed from " + inputFile);
      // Also assure framework that we are making progress.
      reporter.progress();
    }
  }
}
