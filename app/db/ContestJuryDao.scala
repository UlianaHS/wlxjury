package db

import org.intracer.wmua.ContestJury

trait ContestJuryDao {

  def currentRound(id: Long): Option[Long]

  def byCountry: Map[String, Seq[ContestJury]]

  def byId(id: Long): ContestJury

  def find(id: Long): Option[ContestJury]

  def findAll(): Seq[ContestJury]

  def countAll(): Long

  def updateImages(id: Long, images: Option[String]): Int

  def setCurrentRound(id: Long, round: Long): Int

}