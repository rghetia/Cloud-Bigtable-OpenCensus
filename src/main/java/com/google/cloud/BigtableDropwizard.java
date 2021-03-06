package com.google.cloud;

import com.codahale.metrics.ConsoleReporter;
import com.google.cloud.bigtable.hbase.BigtableConfiguration;
import com.google.cloud.bigtable.metrics.BigtableClientMetrics;
import com.google.cloud.bigtable.metrics.DropwizardMetricRegistry;
import io.opencensus.contrib.dropwizard.DropWizardMetrics;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
import io.opencensus.metrics.Metrics;
import java.io.IOException;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * A minimal application that connects to Cloud Bigtable using the native HBase API
 * and performs some basic operations.
 */
public class BigtableDropwizard {
  private static final String PROJECT_ID = "xxx";
  private static final String INSTANCE_ID = "yyy";
  // Refer to table metadata names by byte array in the HBase API
  private static final byte[] TABLE_NAME = Bytes.toBytes("Hello-Bigtable");
  private static final byte[] COLUMN_FAMILY_NAME = Bytes.toBytes("cf1");
  private static final byte[] COLUMN_NAME = Bytes.toBytes("greeting");

  // Write some friendly greetings to Cloud Bigtable
  private static final String[] GREETINGS =
    { "Hello!", "Hello Cloud Bigtable!", "Hello HBase!"};

  /**
   * Connects to Cloud Bigtable, runs some basic operations and prints the results.
   */
  private static void doHelloWorld(String projectId, String instanceId)
    throws InterruptedException {

    // [START connecting_to_bigtable]
    // Create the Bigtable connection, use try-with-resources to make sure it gets closed
    try (Connection connection = BigtableConfiguration.connect(projectId, instanceId)) {

      // The admin API lets us create, manage and delete tables
      Admin admin = connection.getAdmin();
      // [END connecting_to_bigtable]

      // [START creating_a_table]
      // Create a table with a single column family
      HTableDescriptor descriptor = new HTableDescriptor(TableName.valueOf(TABLE_NAME));
      descriptor.addFamily(new HColumnDescriptor(COLUMN_FAMILY_NAME));

      print("Create table " + descriptor.getNameAsString());
      //admin.createTable(descriptor);
      // [END creating_a_table]

      // [START writing_rows]
      // Retrieve the table we just created so we can do some reads and writes
      Table table = connection.getTable(TableName.valueOf(TABLE_NAME));

      // Write some rows to the table
      print("Write some greetings to the table");
      for (int i = 0; i < 1000000; i++) {
        // Each row has a unique row key.
        //
        // Note: This example uses sequential numeric IDs for simplicity, but
        // this can result in poor performance in a production application.
        // Since rows are stored in sorted order by key, sequential keys can
        // result in poor distribution of operations across nodes.
        //
        // For more information about how to design a Bigtable schema for the
        // best performance, see the documentation:
        //
        //     https://cloud.google.com/bigtable/docs/schema-design
        String rowKey = "greeting" + i;

        // Put a single row into the table. We could also pass a list of Puts to write a batch.
        Put put = new Put(Bytes.toBytes(rowKey));
        put.addColumn(COLUMN_FAMILY_NAME, COLUMN_NAME, Bytes.toBytes(GREETINGS[i % 3]));
        table.put(put);

        Thread.sleep(100 * new Random().nextInt(15));
      }
      // [END writing_rows]

      // [START getting_a_row]
      // Get the first greeting by row key
      String rowKey = "greeting0";
      Result getResult = table.get(new Get(Bytes.toBytes(rowKey)));
      String greeting = Bytes.toString(getResult.getValue(COLUMN_FAMILY_NAME, COLUMN_NAME));
      System.out.println("Get a single greeting by row key");
      System.out.printf("\t%s = %s\n", rowKey, greeting);
      // [END getting_a_row]

      // [START scanning_all_rows]
      // Now scan across all rows.
      Scan scan = new Scan();

      print("Scan for all greetings:");
      ResultScanner scanner = table.getScanner(scan);
      for (Result row : scanner) {
        byte[] valueBytes = row.getValue(COLUMN_FAMILY_NAME, COLUMN_NAME);
        System.out.println('\t' + Bytes.toString(valueBytes));
      }
      // [END scanning_all_rows]

      // [START deleting_a_table]
      // Clean up by disabling and then deleting the table
      print("Delete the table");
      admin.disableTable(table.getName());
      admin.deleteTable(table.getName());
      // [END deleting_a_table]

    } catch (IOException e) {
      System.err.println("Exception while running BigtableDropwizard: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }

  }

  private static void print(String msg) {
    System.out.println("BigtableDropwizard: " + msg);
  }

  public static void main(String[] args) throws InterruptedException, IOException {
    enableOpenCensusObservability();

    doHelloWorld(PROJECT_ID, INSTANCE_ID);

    Thread.sleep(30000);
  }

  private static void enableOpenCensusObservability() throws IOException {
    DropwizardMetricRegistry registry = new DropwizardMetricRegistry();

    final ConsoleReporter reporter = ConsoleReporter.forRegistry(registry.getRegistry())
      .convertRatesTo(TimeUnit.SECONDS)
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .build();
    reporter.start(10, TimeUnit.SECONDS);

    BigtableClientMetrics.setMetricRegistry(registry);

    Metrics.getExportComponent().getMetricProducerManager().add(
      new DropWizardMetrics(Collections.singletonList(registry.getRegistry())));

    StackdriverStatsExporter.createAndRegister();

  }

}
