package com.lucidworks.spark.rdd

import com.lucidworks.spark.query.{SolrStreamIterator, StreamingExpressionResultIterator}
import com.lucidworks.spark.util.QueryConstants._
import com.lucidworks.spark.util.{SolrQuerySupport, SolrSupport}
import com.lucidworks.spark.{CloudStreamPartition, HashQPartition, SolrPartitioner}
import com.typesafe.scalalogging.LazyLogging
import org.apache.solr.client.solrj.SolrQuery
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.{Partition, SparkContext, TaskContext}

import scala.collection.JavaConverters

class StreamingSolrRDD(
    zkHost: String,
    collection: String,
    @transient sc: SparkContext,
    requestHandler: Option[String] = None,
    query : Option[String] = Option(DEFAULT_QUERY),
    fields: Option[Array[String]] = None,
    rows: Option[Int] = Option(DEFAULT_PAGE_SIZE),
    splitField: Option[String] = None,
    splitsPerShard: Option[Int] = None,
    solrQuery: Option[SolrQuery] = None,
    uKey: Option[String] = None)
  extends SolrRDD[java.util.Map[_, _]](zkHost, collection, sc)
  with LazyLogging {

  protected def copy(
    requestHandler: Option[String] = requestHandler,
    query: Option[String] = query,
    fields: Option[Array[String]] = fields,
    rows: Option[Int] = rows,
    splitField: Option[String] = splitField,
    splitsPerShard: Option[Int] = splitsPerShard,
    solrQuery: Option[SolrQuery] = solrQuery): StreamingSolrRDD = {
    new StreamingSolrRDD(zkHost, collection, sc, requestHandler, query, fields, rows, splitField, splitsPerShard, solrQuery, uKey)
  }

  /*
   * Get an Iterator that uses the export handler in Solr
   */
  @throws(classOf[Exception])
  private def getExportHandlerBasedIterator(shardUrl : String, query : SolrQuery, numWorkers: Int, workerId: Int) = {

    // Direct the queries to each shard, so we don't want distributed
    query.set("distrib", false)

    val sorts = query.getSorts
    val sortParam = query.get("sort")
    if ((sorts == null || sorts.isEmpty) && (sortParam == null || sortParam.isEmpty)) {
      val fields = query.getFields
      if (fields != null) {
        if (fields.contains("id")) {
          query.addSort("id", SolrQuery.ORDER.asc)
        } else {
          val firstField = fields.split(",")(0)
          query.addSort(firstField, SolrQuery.ORDER.asc)
        }
      } else {
        query.addSort("id", SolrQuery.ORDER.asc)
      }
      logger.warn(s"Added required sort clause: "+query.getSorts+
        "; this is probably incorrect so you should provide your own sort criteria.")
    }

    new SolrStreamIterator(shardUrl, SolrSupport.getHttpSolrClient(shardUrl), query, numWorkers, workerId)
  }


  @DeveloperApi
  override def compute(split: Partition, context: TaskContext): Iterator[java.util.Map[_, _]] = {
    logger.info("Computing split")
    split match {
      case partition: CloudStreamPartition =>
        logger.info(s"Using StreamingExpressionResultIterator to process streaming expression for $partition")
        val resultsIterator = new StreamingExpressionResultIterator(partition.zkhost, partition.collection, partition.params)
        JavaConverters.asScalaIteratorConverter(resultsIterator.iterator()).asScala
      case partition: HashQPartition =>

        //TODO: Add backup mechanism to StreamingResultsIterator by being able to query any replica in case the main url goes down
        val url = partition.preferredReplica.replicaUrl
        val query = partition.query
        logger.info("Using the shard url " + url + " for getting partition data for split: "+ split.index)
        val solrRequestHandler = requestHandler.getOrElse(DEFAULT_REQUEST_HANDLER)
        query.setRequestHandler(solrRequestHandler)
        logger.info("Using export handler to fetch documents from " + partition.preferredReplica + " for query: "+partition.query)
        val resultsIterator = getExportHandlerBasedIterator(url, query, partition.numWorkers, partition.workerId)
        context.addTaskCompletionListener { (context) =>
          logger.info(f"Fetched ${resultsIterator.getNumDocs} rows from shard $url for partition ${split.index}")
        }
        JavaConverters.asScalaIteratorConverter(resultsIterator.iterator()).asScala
      case partition: AnyRef => throw new Exception("Unknown partition type '" + partition.getClass)
    }
  }

  override def getPartitions: Array[Partition] = {
    val query = if (solrQuery.isEmpty) buildQuery else solrQuery.get
    val rq = requestHandler.getOrElse(DEFAULT_REQUEST_HANDLER)
    if (rq == QT_STREAM || rq == QT_SQL) {
      logger.info(s"Using SolrCloud stream partitioning scheme to process request to $rq for collection $collection")
      return Array(CloudStreamPartition(0, zkHost, collection, query))
    }

    val shards = SolrSupport.buildShardList(zkHost, collection)
    val numReplicas = shards.head.replicas.length
    val numSplits = splitsPerShard.getOrElse(2 * numReplicas)
    logger.info(s"Using splitField=$splitField, splitsPerShard=$splitsPerShard, and numReplicas=$numReplicas for computing partitions.")

    val partitions : Array[Partition] = if (numSplits > 1) {
      val splitFieldName = splitField.getOrElse(DEFAULT_SPLIT_FIELD)
      logger.info(s"Applied $numSplits intra-shard splits on the $splitFieldName field for $collection to better utilize all active replicas. Set the 'split_field' option to override this behavior or set the 'splits_per_shard' option = 1 to disable splits per shard.")
      query.set("partitionKeys", splitFieldName)
      SolrPartitioner.getHashPartitions(shards, query, splitFieldName, numSplits)
    } else {
      // no explicit split field and only one replica || splits_per_shard was explicitly set to 1, no intra-shard splitting needed
      SolrPartitioner.getShardPartitions(shards, query)
    }

    if (logger.underlying.isDebugEnabled) {
      logger.debug(s"Found ${partitions.length} partitions: ${partitions.mkString(",")}")
    } else {
      logger.info(s"Found ${partitions.length} partitions.")
    }
    partitions
  }

  override def query(q: String): StreamingSolrRDD = copy(query = Some(q))

  override def query(solrQuery: SolrQuery): StreamingSolrRDD = copy(solrQuery = Some(solrQuery))

  override def select(fl: String): StreamingSolrRDD = copy(fields = Some(fl.split(",")))

  override def select(fl: Array[String]): StreamingSolrRDD = copy(fields = Some(fl))

  override def rows(rows: Int): StreamingSolrRDD = copy(rows = Some(rows))

  override def doSplits(): StreamingSolrRDD = copy(splitField = Some(DEFAULT_SPLIT_FIELD))

  override def splitField(field: String): StreamingSolrRDD = copy(splitField = Some(field))

  override def splitsPerShard(splitsPerShard: Int): StreamingSolrRDD = copy(splitsPerShard = Some(splitsPerShard))

  override def requestHandler(requestHandler: String): StreamingSolrRDD = copy(requestHandler = Some(requestHandler))

  override def buildQuery: SolrQuery = {
    var solrQuery : SolrQuery = SolrQuerySupport.toQuery(query.get)
    if (!solrQuery.getFields.eq(null) && solrQuery.getFields.length > 0) {
      solrQuery = solrQuery.setFields(fields.getOrElse(Array.empty[String]):_*)
    }
    if (!solrQuery.getRows.eq(null)) {
      solrQuery = solrQuery.setRows(rows.get)
    }

    solrQuery.set("collection", collection)
    solrQuery
  }

}

object StreamingSolrRDD {
  def apply(zkHost: String, collection: String, sparkContext: SparkContext): StreamingSolrRDD = {
    new StreamingSolrRDD(zkHost, collection, sparkContext)
  }
}
