package com.github.mjakubowski84.parquet4s

import com.google.common.io.Files
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Inspectors}

import java.time.LocalDate
import java.util.UUID
import scala.collection.compat.immutable.LazyList
import scala.util.Random

class StatsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with Inspectors {

  case class Embedded(x: Int)
  case class Data(
                   idx: Int,
                   float: Float,
                   double: Double,
                   enum: String,
                   bool: Boolean,
                   date: LocalDate,
                   decimal: BigDecimal,
                   embedded: Embedded,
                   optional: Option[Int],
                   random: String
                 )

  val enum: Seq[String] = List("a", "b", "c", "d")
  val dataSize: Int = 256 * 256
  val halfSize: Int = dataSize / 2
  val path: String = Files.createTempDir().getAbsolutePath
  val zeroDate: LocalDate = LocalDate.of(1900, 1, 1)
  def decimal(i: Int): BigDecimal = BigDecimal.valueOf(0.001 * (i - halfSize))

  implicit val localDateOrdering: Ordering[LocalDate] = new Ordering[LocalDate] {
    override def compare(x: LocalDate, y: LocalDate): Int = x.compareTo(y)
  }
  val vcc: ValueCodecConfiguration = ValueCodecConfiguration.default

  lazy val data: LazyList[Data] =
    LazyList.range(0, dataSize).map { i =>
      Data(
        idx = i,
        float = (BigDecimal("0.01") * BigDecimal(i)).toFloat,
        double = (BigDecimal("0.00000001") * BigDecimal(i)).toDouble,
        enum = enum(Random.nextInt(enum.size)),
        bool = Random.nextBoolean(),
        date = zeroDate.plusDays(i),
        decimal = decimal(i),
        embedded = Embedded(i),
        optional = if (i % 2 == 0) None else Some(i),
        random = UUID.randomUUID().toString
      )
    }
  val filterByRandom: Filter = Col("random") >= "a" && Col("random") <= "b"
  lazy val filteredByRandom: LazyList[Data] = data.filter(v => v.random >= "a" && v.random <= "b")

  override def beforeAll(): Unit = {
    super.beforeAll()
    val writeOptions = ParquetWriter.Options(
      rowGroupSize = dataSize / 16,
      pageSize = 16 * 256,
      dictionaryPageSize = 16 * 256
    )
    val window = dataSize / 4
    data
      .sliding(window, window)
      .zipWithIndex
      .foreach { case (part, index) =>
        ParquetWriter.writeAndClose(s"$path/$index.parquet", part, writeOptions)
      }

  }

  "recordCount" should "be valid for a single file" in {
    Stats(s"$path/0.parquet").recordCount should be(dataSize / 4)
  }

  it should "be valid for a whole dataset" in {
    Stats(path).recordCount should be(dataSize)
  }

  it should "be valid when filtering an interval out of monotonic value " in {
    val lowerBound = 16
    val upperBound = 116
    val expectedSize = upperBound - lowerBound
    Stats(path, filter = Col("idx") > lowerBound && Col("idx") <= upperBound).recordCount should be(expectedSize)
  }

  it should "be valid when filtering over random data" in {
    val expectedSize = filteredByRandom.size
    Stats(path, filter = filterByRandom).recordCount should be(expectedSize)
  }

  "min & max" should "should provide proper value for a single file" in {
    val stats = Stats(s"$path/0.parquet")
    stats.min[Int]("idx") should be(Some(0))
    stats.max[Int]("idx") should be(Some((dataSize / 4) - 1))
  }

  it should "should provide proper value for a single file when filtering" in {
    val expectedMin = 16
    val expectedMax = 128
    val stats = Stats(s"$path/0.parquet", filter = Col("idx") >= expectedMin && Col("idx") <= expectedMax)
    stats.min[Int]("idx") should be(Some(expectedMin))
    stats.max[Int]("idx") should be(Some(expectedMax))
  }

  it should "be valid when filtering over random data" in {
    val expectedMax = Option(filteredByRandom.maxBy(_.random).random)
    val expectedMin = Option(filteredByRandom.minBy(_.random).random)

    val stats = Stats(path, filter = filterByRandom)
    stats.max[String]("random") should be(expectedMax)
    stats.min[String]("random") should be(expectedMin)
  }

  it should "should provide proper value for each column" in {
    val maxIdx = dataSize - 1
    val stats = Stats(path)

    stats.min[Int]("idx") should be(Some(0))
    stats.max[Int]("idx") should be(Some(maxIdx))

    stats.min[Float]("float") should be(Some(0.0f))
    stats.max[Float]("float") should be(Some(maxIdx * 0.01f))

    stats.min[Double]("double") should be(Some(0.0d))
    stats.max[Double]("double") should be(Some(maxIdx * 0.00000001d))

    stats.min[String]("enum") should be(Some("a"))
    stats.max[String]("enum") should be(Some("d"))

    stats.min[Boolean]("bool") should be(Some(false))
    stats.max[Boolean]("bool") should be(Some(true))

    stats.min[LocalDate]("date") should be(Some(zeroDate))
    stats.max[LocalDate]("date") should be(Some(zeroDate.plusDays(maxIdx)))

    stats.min[BigDecimal]("decimal") should be(Some(decimal(0)))
    stats.max[BigDecimal]("decimal") should be(Some(decimal(maxIdx)))

    stats.min[Int]("embedded.x") should be(Some(0))
    stats.max[Int]("embedded.x") should be(Some(maxIdx))

    stats.min[Int]("optional") should be(Some(1))
    stats.max[Int]("optional") should be(Some(maxIdx))

    stats.min[Int]("invalid") should be(None)
    stats.max[Int]("invalid") should be(None)
  }

}
