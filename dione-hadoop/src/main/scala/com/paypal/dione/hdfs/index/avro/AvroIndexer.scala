package com.paypal.dione.hdfs.index.avro

import com.paypal.dione.hdfs.index.{HdfsIndexer, HdfsIndexerMetadata}
import org.apache.avro.Schema
import org.apache.avro.file.TransparentFileReader
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}
import org.apache.avro.mapred.FsInput
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

case class AvroIndexer(file: Path, start: Long, end: Long, conf: Configuration) extends HdfsIndexer[GenericRecord]() {

  private val fileReader = new TransparentFileReader(new FsInput(file, conf), new GenericDatumReader[GenericRecord])
  private var reusedRecord: GenericRecord = _

  override def closeCurrentFile(): Unit = {
    if (fileReader != null)
      fileReader.close()
  }

  /**
   * Regular seek. Called once per offset (block).
   */
  override def seek(offset: Long): Unit = fileReader.seek(offset)

  /**
   * Skip the next row - can avoid deserialization, etc.
   */
  override def skip(): Unit = readNext()

  private def readNext() = {
    reusedRecord =
      if (reusedRecord == null)
        fileReader.next(reusedRecord)
      else
        fileReader.next()
  }

  fileReader.sync(start)
  private var prevSync = -1L
  private var numInBlock = -1
  private var lastPosition = 0L
  private var totalInBlock = -1L

  /**
   * Read the next row
   */
  override def readLine(): GenericRecord = {

    if (fileReader.pastSync(end) || !fileReader.hasNext)
      return null

    if (prevSync != fileReader.previousSync()) {
      prevSync = fileReader.previousSync()
      totalInBlock = fileReader.getBlockCount
      numInBlock = -1
    }

    readNext()
    numInBlock += 1

    reusedRecord
  }

  override def getCurMetadata(): HdfsIndexerMetadata = {
    val size = tryInferSize(fileReader, lastPosition, totalInBlock.intValue())
    lastPosition = fileReader.previousSync()
    HdfsIndexerMetadata(file.toString, prevSync, numInBlock, size)
  }

  def getSchema(): Schema = fileReader.getSchema

  private def tryInferSize(reader: TransparentFileReader, lastPosition: Long, totalInBlock: Int): Int = {
    val pos = reader.previousSync()
    val bytes =
      if (totalInBlock == 1) // exactly 1 row in block
        pos - lastPosition
      else { // more than 1 row in block, do rough estimate:
        val block = reader.getCurrentBlock
        val blockSize = block.limit() - block.arrayOffset()
        val i = blockSize / totalInBlock // mean size in block...}
        i
      }
    bytes.intValue()
  }
}