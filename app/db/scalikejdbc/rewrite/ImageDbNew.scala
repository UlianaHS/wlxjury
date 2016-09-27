package db.scalikejdbc.rewrite

import db.scalikejdbc.{ImageJdbc, SelectionJdbc}
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import scalikejdbc.{DBSession, _}

object ImageDbNew extends SQLSyntaxSupport[Image] {

  implicit def session: DBSession = autoSession

  //  private def isNotDeleted = sqls.isNull(SelectionJdbc.s.deletedAt)

  override val tableName = "images"

  val i = ImageJdbc.syntax("i")
  val s = SelectionJdbc.s
  val s1 = SelectionJdbc.syntax("s1")
  val s2 = SelectionJdbc.syntax("s2")
  val s3 = SelectionJdbc.syntax("s3")


  case class Limit(pageSize: Option[Int] = None, offset: Option[Int] = None, startPageId: Option[Long] = None)

  case class SelectionQuery(userId: Option[Long] = None,
                            roundId: Option[Long] = None,
                            rate: Option[Int] = None,
                            rated: Option[Boolean] = None,
                            criteriaId: Option[Long] = None,
                            limit: Option[Limit] = None,
                            grouped: Boolean = false,
                            groupWithDetails: Boolean = false,
                            order: Map[String, Int] = Map.empty
                           ) {

    val reader: WrappedResultSet => ImageWithRating =
      if (grouped) Readers.groupedReader else Readers.rowReader

    def query(count: Boolean = false): String = {

      val columns =
        if (!grouped)
          sqls"""select ${i.result.*}, ${s.result.*} """
        else
          sqls"""select ${i.result.*},  sum(s.rate) as rate, count(s.rate) as rate_count"""

      val groupBy = if (grouped) {
        " group by s.page_id"
      } else ""

      val sql = columns + imagesJoinSelection + where(count) + groupBy + orderBy()

      if (count)
        "select count(t.pi_on_i) from (" + sql + ") t"
      else
        sql + limitSql()
    }

    def list(): Seq[ImageWithRating] = {
      postProcessor(SQL(query()).map(reader).list().apply())
    }

    def count(): Int = {
      single(query(count = true))
    }

    def imageRank(pageId: Long) = {
      single(imageRank(pageId, query()))
    }

    def single(sql: String): Int = {
      SQL(sql).map(_.int(1)).single().apply().getOrElse(0)
    }

    val imagesJoinSelection =
      """ from images i
        |join selection s
        |on i.page_id = s.page_id""".stripMargin

    def where(count: Boolean = false): String = {
      val conditions =
        Seq(
          userId.map(id => "s.jury_id = " + id),
          roundId.map(id => "s.round = " + id),
          rate.map(r => "s.rate = " + r),
          rated.map(_ => "s.rate > 0")
//          limit.flatMap(_.startPageId).filter(_ => count).map(_ => "s.rate > 0")
        )

      val flatten = conditions.flatten

      flatten.headOption.fold("")(_ => " where ") + flatten.mkString(" and ")
    }

    def orderBy(fields: Map[String, Int] = order) = {
      val dirMap = Map(1 -> "asc", -1 -> "desc")

      fields.headOption.map { _ => " order by " + fields.map {
        case (name, dir) => name + " " + dirMap(dir)
      }.mkString(", ")
      }.getOrElse("")
    }

    def limitSql(): String = limit.map {
      l => s" LIMIT ${l.pageSize.getOrElse(0)} OFFSET ${l.offset.getOrElse(0)}"
    }.getOrElse("")

    val postProcessor: Seq[ImageWithRating] => Seq[ImageWithRating] =
      if (groupWithDetails) groupedWithDetails else identity

    def groupedWithDetails(images: Seq[ImageWithRating]): Seq[ImageWithRating] =
      images.groupBy(_.image.pageId).map { case (id, imagesWithId) =>
        new ImageWithRating(imagesWithId.head.image, imagesWithId.flatMap(_.selection))
      }.toSeq.sortBy(-_.selection.map(_.rate).filter(_ > 0).sum)

    def imageRank(pageId: Long, sql: String) = {
      sqls"""SELECT rank
            FROM (
                  SELECT @rownum :=@rownum + 1 'rank', page_id
                  FROM (SELECT @rownum := 0) r,
                  ($sql) t
                  ) t2
            WHERE page_id = $pageId;"""
    }

    def rankedList(where: String): Seq[ImageWithRating] = {
      SQL(
        s"""SELECT count(s2.page_id) + 1 AS rank, ${i.result.*}, ${s1.result.*}
    FROM images i
    JOIN (SELECT * FROM selection s WHERE $where) AS s1
    ON i.page_id = s1.page_id
    LEFT JOIN (SELECT * FROM selection s WHERE s.jury_id = $userId AND s.round = $roundId) AS s2
    ON s1.rate < s2.rate
    GROUP BY s1.page_id
    ORDER BY rank ASC
    $limit""").map(rs => (
        rs.int(1),
        ImageJdbc(i)(rs),
        SelectionJdbc(s1)(rs))
      ).list().apply().map {
        case (rank, img, sel) => ImageWithRating(img, Seq(sel), rank = Some(rank))
      }
    }

    def rangeRankedList(where: String): Seq[ImageWithRating] = {
      SQL(
        s"""SELECT s1.rank1, s2.rank2, ${i.result.*}, ${s1.result.*}
          FROM images i JOIN
            (SELECT t1.*, count(t2.page_id) + 1 AS rank1
            FROM (SELECT * FROM selection s WHERE  $where) AS t1
            LEFT JOIN (SELECT * FROM selection s WHERE $where) AS t2
              ON  t1.rate < t2.rate
          GROUP BY t1.page_id) s1
              ON  i.page_id = s1.page_id
          JOIN
              (SELECT t1.page_id, count(t2.page_id) AS rank2
                 FROM (SELECT * FROM selection s WHERE $where) AS t1
                 JOIN (SELECT * FROM selection s WHERE $where) AS t2
                   ON  t1.rate <= t2.rate
               GROUP BY t1.page_id) s2
            ON s1.page_id = s2.page_id
            ORDER BY rank1 ASC $limit()""").map(rs => (
        rs.int(1),
        rs.int(2),
        ImageJdbc(i)(rs),
        SelectionJdbc(s1)(rs))
      ).list().apply().map {
        case (rank1, rank2, i, s) => ImageWithRating(i, Seq(s), rank = Some(rank1), rank2 = Some(rank2))
      }
    }

    object Readers {

      def rowReader(rs: WrappedResultSet): ImageWithRating =
        ImageWithRating(
          image = ImageJdbc(i)(rs),
          selection = Seq(SelectionJdbc(s)(rs))
        )

      def groupedReader(rs: WrappedResultSet): ImageWithRating = {
        val image = ImageJdbc(i)(rs)
        val sum = rs.intOpt(1).getOrElse(0)
        val count = rs.intOpt(2).getOrElse(0)
        ImageWithRating(image,
          selection = Seq(new Selection(0, image.pageId, sum, 0, round = 0)),
          count
        )
      }
    }

  }

}
