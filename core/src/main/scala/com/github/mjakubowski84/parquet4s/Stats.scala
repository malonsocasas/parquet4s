package com.github.mjakubowski84.parquet4s

import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.apache.parquet.column.statistics._
import org.apache.parquet.schema.MessageType

/**
 * Utilises statistics of Parquet files to provide number of records and minimum and maximum value of columns.
 * Values are provided for both unfiltered and filtered reads.
 * Reading statistics from unfiltered files is usually faster as then only file metadata are used. For filtered files
 * certain blocks must be scanned in order to provide correct results.
 */
trait Stats {
  /**
   * @return Number for records in given path. Filter is considered during calculation.
   */
  def recordCount: Long

  /**
   * @param columnPath dot-separated path to requested column
   * @param codec [[ValueCodec]] required to decode the value
   * @param ordering required to sort filtered values
   * @tparam V type of value stored in the column
   * @return Minimum value across Parquet data. [[Filter]] is considered during calculation.
   */
  def min[V](columnPath: String)(implicit codec: ValueCodec[V], ordering: Ordering[V]): Option[V] = min(columnPath, None)

  /**
   * @param columnPath dot-separated path to requested column
   * @param codec [[ValueCodec]] required to decode the value
   * @param ordering required to sort filtered values
   * @tparam V type of value stored in the column
   * @return Maximum value across Parquet data. [[Filter]] is considered during calculation.
   */
  def max[V](columnPath: String)(implicit codec: ValueCodec[V], ordering: Ordering[V]): Option[V] = max(columnPath, None)

  protected[parquet4s] def min[V](columnPath: String, currentMin: Option[V])
                                 (implicit codec: ValueCodec[V], ordering: Ordering[V]): Option[V]
  protected[parquet4s] def max[V](columnPath: String, currentMax: Option[V])
                                 (implicit codec: ValueCodec[V], ordering: Ordering[V]): Option[V]

  protected def statsMinValue(statistics: Statistics[_]): Option[Value] =
    statistics match {
      case s if s.isEmpty => Option.empty[Value]
      case s: IntStatistics => Option(IntValue(s.genericGetMin))
      case s: LongStatistics => Option(LongValue(s.genericGetMin))
      case s: BooleanStatistics => Option(BooleanValue(s.genericGetMin))
      case s: BinaryStatistics => Option(BinaryValue(s.genericGetMin))
      case s: DoubleStatistics => Option(DoubleValue(s.genericGetMin))
      case s: FloatStatistics => Option(FloatValue(s.genericGetMin))
    }

  protected def statsMaxValue(statistics: Statistics[_]): Option[Value] =
    statistics match {
      case s if s.isEmpty => Option.empty[Value]
      case s: IntStatistics => Option(IntValue(s.genericGetMax))
      case s: LongStatistics => Option(LongValue(s.genericGetMax))
      case s: BooleanStatistics => Option(BooleanValue(s.genericGetMax))
      case s: BinaryStatistics => Option(BinaryValue(s.genericGetMax))
      case s: DoubleStatistics => Option(DoubleValue(s.genericGetMax))
      case s: FloatStatistics => Option(FloatValue(s.genericGetMax))
    }

}

object Stats {

  /**
   * @param path URI to location of files
   * @param options [[ParquetReader.Options]]
   * @param filter optional [[Filter]] that is considered during calculation of [[Stats]]
   * @return [[Stats]] of Parquet files
   */
  def apply(
             path: String,
             options: ParquetReader.Options = ParquetReader.Options(),
             filter: Filter = Filter.noopFilter
           ): Stats =
    this.apply(path = new Path(path), options = options, projectionSchemaOpt = None, filter = filter)

  /**
   * If you are not interested in Stats of all columns then consider using projection to make the operation faster.
   * If you are going to use a filter mind that your projection has to contain columns that filter refers to.
   *
   * @param path URI to location of files
   * @param options [[ParquetReader.Options]]
   * @param filter optional [[Filter]] that is considered during calculation of [[Stats]]
   * @tparam T projected type
   * @return [[Stats]] of Parquet files
   */
  def withProjection[T: ParquetSchemaResolver](
                                                path: String,
                                                options: ParquetReader.Options = ParquetReader.Options(),
                                                filter: Filter = Filter.noopFilter
                                              ): Stats =
    this.apply(
      path = new Path(path),
      options = options,
      projectionSchemaOpt = Option(ParquetSchemaResolver.resolveSchema[T]),
      filter = filter
    )

  private[parquet4s] def apply(
             path: Path,
             options: ParquetReader.Options,
             projectionSchemaOpt: Option[MessageType],
             filter: Filter
           ): Stats = {
    val fs = FileSystem.get(options.hadoopConf)
    val statsArray = fs.listStatus(path).map(status => this.apply(status, options, projectionSchemaOpt, filter))
    if (statsArray.length == 1) statsArray.head
    else new CompoundStats(statsArray)
  }

  private def apply(
             status: FileStatus,
             options: ParquetReader.Options,
             projectionSchemaOpt: Option[MessageType],
             filter: Filter
           ): Stats =
    if (filter == Filter.noopFilter) {
      new FileStats(status, options, projectionSchemaOpt)
    } else {
      new FilteredFileStats(status, options, projectionSchemaOpt, filter)
    }

}

/**
 * Calculates [[Stats]] from multiple files.
 */
private class CompoundStats(statsSeq: Seq[Stats]) extends Stats {
  override lazy val recordCount: Long = statsSeq.map(_.recordCount).sum

  override def min[V](columnPath: String, currentMin: Option[V])
                     (implicit codec: ValueCodec[V], ordering: Ordering[V]): Option[V] =
    statsSeq.foldLeft(currentMin) {
      case (acc, stats) => stats.min(columnPath, acc)
    }

  override def max[V](columnPath: String, currentMax: Option[V])
                     (implicit codec: ValueCodec[V], ordering: Ordering[V]): Option[V] =
    statsSeq.foldLeft(currentMax) {
      case (acc, stats) => stats.max(columnPath, acc)
    }

}
