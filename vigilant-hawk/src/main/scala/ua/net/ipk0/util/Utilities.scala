package ua.net.ipk0.util

object Utilities {

  type RankComparator = (Int, Int) => Boolean

  def compareByRankClosest(point: Int): RankComparator =
    (t1, t2) => orderByRankClosest(t1, t2, point)

  def compareByRankEndmost(point: Int): RankComparator =
    (t1, t2) => orderByRankEndmost(t1, t2, point)

  private def orderByRankClosest(first: Int, second: Int, point: Int): Boolean = byRank(first, point) <= byRank(second, point)

  private def orderByRankEndmost(first: Int, second: Int, point: Int): Boolean = byRank(first, point) >= byRank(second, point)

  private def byRank(target: Int, point: Int): Int = {
    if (point >= target) {
      point - target
    } else {
      target - point
    }
  }

}
